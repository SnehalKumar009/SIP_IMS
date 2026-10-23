# Runbook: shut the platform down, bring it back

Run every command from the repo root (`~/Code/SIP_IMS/SIP_IMS`) as your normal user.

## Pick one

| | **A. Pause** (quick) | **B. Full teardown + rebuild** |
|---|---|---|
| Frees RAM/CPU | ✅ all of it | ✅ all of it |
| Frees disk | ❌ (a few hundred MB anyway) | ✅ mainly Docker images |
| Tomorrow | `microk8s start`, everything comes back as it was | Re-deploy everything, rebuild images |
| Keeps data | HSS DB, Kafka, logs in Elasticsearch, registry | Nothing (alice/bob are re-seeded) |

Where the space actually is: all Kubernetes volumes together (registry included) use **~500 MB**.
The big consumer is **Docker on the host** (≈32 GB of images, ≈28 GB of build cache), which A does
not touch and B cleans only for IMS images (Docker also holds your other projects' images).

### A. Pause

```bash
microk8s stop          # stops every pod and the cluster; also disables autostart at boot
# ...next day...
microk8s start
microk8s status --wait-ready
microk8s kubectl get pods -A        # wait until everything is Running again (~2-5 min)
```

The P-CSCF pod IP can change after a restart; look it up again (Part 2, step 9). That is all for A.

---

## B, Part 1: teardown (tonight)

### 1. Stop what you are running against the cluster

Ctrl+C any `port-forward` and SIPp runs in your terminals.

### 2. Delete the IMS services (reverse dependency order)

```bash
microk8s kubectl delete -f deploy/manifests/p-cscf/p-cscf.yaml
microk8s kubectl delete -f deploy/manifests/i-cscf/i-cscf.yaml
microk8s kubectl delete -f deploy/manifests/s-cscf/s-cscf.yaml
microk8s kubectl delete -f deploy/manifests/s-cscf/redis.yaml --ignore-not-found
microk8s kubectl delete -f deploy/manifests/hss/hss.yaml
microk8s kubectl -n ims-core wait --for=delete pod --all --timeout=180s
```

### 3. Delete the logging stack (Fluent Bit, Logstash, Kibana, Elasticsearch)

```bash
microk8s kubectl delete -k deploy/logging
microk8s kubectl -n logging wait --for=delete pod --all --timeout=300s
```

ECK deletes the Elasticsearch and Logstash volumes with their clusters.

### 4. Delete Kafka

```bash
microk8s kubectl delete -k deploy/kafka
microk8s kubectl -n kafka wait --for=delete pod -l strimzi.io/cluster=ims-kafka --timeout=300s
```

### 5. Uninstall the two operators

Only after steps 3 and 4: an operator has to be running to clean up its own resources.

```bash
microk8s helm3 uninstall elastic-operator -n elastic-system
microk8s helm3 uninstall strimzi-cluster-operator -n kafka
```

Some CRDs may stay behind; they take no resources and the install scripts reuse them.

### 6. Delete Homer (SIP capture)

```bash
microk8s kubectl delete -k deploy/monitoring
microk8s kubectl -n monitoring wait --for=delete pod --all --timeout=180s
```

### 7. Delete the volumes that StatefulSets leave behind

StatefulSet volumes (HSS Postgres, Redis, Kafka, Homer Postgres) are kept on purpose when their
pods go away. Delete them for a clean start:

```bash
microk8s kubectl get pvc -A                              # see what is left
microk8s kubectl -n ims-core   delete pvc --all
microk8s kubectl -n kafka      delete pvc --all
microk8s kubectl -n logging    delete pvc --all
microk8s kubectl -n monitoring delete pvc --all
```

Skip the `ims-core` line to keep the HSS database (registration state, demo users).

### 8. Check nothing of ours is left

```bash
microk8s kubectl get pods -A
```

Expected: only `kube-system`, `container-registry`, `ingress` and `observability` pods (MicroK8s
addons). `ims-core`, `kafka`, `logging`, `monitoring` and `elastic-system` are empty.

### 9. Clear the image registry

```bash
microk8s disable registry           # removes the registry and its volume (all pushed images)
```

Confirm no volume is left anywhere. The `microk8s-hostpath` storage class has
`reclaimPolicy: Delete`, so deleting a PVC also deletes its PersistentVolume and the data
directory on disk:

```bash
microk8s kubectl get pvc -A         # No resources found
microk8s kubectl get pv             # No resources found
```

If a PVC is stuck in `Terminating`, a pod still uses it: find it with
`microk8s kubectl get pods -A -o wide` and delete that pod first.

### 10. Remove the IMS images from Docker (frees the most disk)

```bash
docker images 'localhost:32000/ims/*' --format '{{.Repository}}:{{.Tag}}' | xargs -r docker rmi
docker rmi cr.fluentbit.io/fluent/fluent-bit:5.1.3 docker.elastic.co/logstash/logstash:9.5.5 2>/dev/null
```

Optional, frees up to ~20 GB but slows the first build of *every* project afterwards:

```bash
docker builder prune -f
```

Do **not** run `docker system prune -a` or `docker volume prune`: they also remove your other
projects' images and volumes.

### 11. Stop MicroK8s

```bash
microk8s stop
microk8s status                     # "microk8s is not running"
free -g                             # RAM back
```

`microk8s stop` also keeps it from starting at boot until you run `microk8s start`.

---

## B, Part 2: bring everything back (tomorrow)

Order matters: cluster → registry → namespaces → Homer → Kafka → logging → images → services.

### 1. Start MicroK8s

```bash
microk8s start
microk8s status --wait-ready
microk8s kubectl get nodes          # STATUS Ready
microk8s kubectl get pods -A        # wait until kube-system pods are Running
```

### 2. Re-enable the registry

```bash
microk8s enable registry
until curl -s --max-time 2 http://localhost:32000/v2/_catalog | grep -q repositories; do sleep 2; done
curl -s http://localhost:32000/v2/_catalog          # {"repositories":[]}
```

### 3. Namespaces

```bash
microk8s kubectl apply -f deploy/microk8s/namespaces.yaml
```

### 4. Homer (SIP capture, optional but the services send HEP to it)

```bash
microk8s kubectl apply -k deploy/monitoring
microk8s kubectl -n monitoring get pods -w          # Ctrl+C when all are Running
```

### 5. Kafka

```bash
./deploy/kafka/install-operator.sh
microk8s kubectl apply -k deploy/kafka
microk8s kubectl -n kafka wait kafka/ims-kafka --for=condition=Ready --timeout=600s
microk8s kubectl -n kafka get kafkatopic ims-logs   # READY True
```

### 6. Logging (Elasticsearch, Kibana, Logstash, Fluent Bit)

```bash
./deploy/logging/elastic/install-operator.sh
microk8s kubectl apply -k deploy/logging
microk8s kubectl -n logging wait elasticsearch/ims-es --for=jsonpath='{.status.health}'=green --timeout=900s
./deploy/logging/elastic/index-setup/apply.sh
```

Logstash may have created the data stream before `apply.sh` loaded the IMS template, in which
case it uses Elasticsearch's generic settings. A rollover makes the next backing index pick up the
IMS template; it is harmless when not needed:

```bash
PW=$(microk8s kubectl -n logging get secret ims-es-es-elastic-user -o jsonpath='{.data.elastic}' | base64 -d)
microk8s kubectl -n logging exec ims-es-es-default-0 -c elasticsearch -- \
  curl -sk -u "elastic:$PW" -X POST "https://localhost:9200/logs-ims-default/_rollover"
```

Then Kibana content (wait until Kibana is green):

```bash
microk8s kubectl -n logging wait kibana/ims-kibana --for=jsonpath='{.status.health}'=green --timeout=600s
./deploy/logging/kibana/import.sh                    # "success":true,"successCount":11
microk8s kubectl -n logging get elasticsearch,kibana,logstash   # all green
```

The `elastic` password is **new** after a teardown; read it again with the `PW=` line above.

### 7. Build the four services

```bash
mvn clean package -DskipTests                        # drop -DskipTests to also run the tests
ls services/*/target/*.jar                           # hss, i-cscf, p-cscf, s-cscf jars
```

### 8. Build and push the images (`:latest`)

```bash
for s in hss s-cscf i-cscf p-cscf; do
  docker build -t localhost:32000/ims/$s:latest services/$s && \
  docker push localhost:32000/ims/$s:latest
done
curl -s http://localhost:32000/v2/_catalog          # the four ims/* repositories
```

### 9. Deploy the services (dependency order)

HSS first (the CSCFs call it over Cx), then S-CSCF, I-CSCF, and the P-CSCF entry point last.

```bash
microk8s kubectl apply -f deploy/manifests/hss/hss.yaml
microk8s kubectl -n ims-core rollout status statefulset/hss-postgres --timeout=300s
microk8s kubectl -n ims-core rollout status deploy/hss --timeout=300s

microk8s kubectl apply -f deploy/manifests/s-cscf/s-cscf.yaml
# only if ims.scscf.store is redis:  microk8s kubectl apply -f deploy/manifests/s-cscf/redis.yaml
microk8s kubectl -n ims-core rollout status statefulset/s-cscf --timeout=300s

microk8s kubectl apply -f deploy/manifests/i-cscf/i-cscf.yaml
microk8s kubectl -n ims-core rollout status deploy/i-cscf --timeout=300s

microk8s kubectl apply -f deploy/manifests/p-cscf/p-cscf.yaml
microk8s kubectl -n ims-core rollout status deploy/p-cscf --timeout=300s

microk8s kubectl -n ims-core get pods
```

### 10. Verify end to end

```bash
PCSCF=$(microk8s kubectl -n ims-core get pod -l app.kubernetes.io/name=p-cscf -o jsonpath='{.items[0].status.podIP}'); echo $PCSCF
cd ~/Softwares/SIPP/sipp
sipp -sf sipp_scenarios/alice_register_1.xml $PCSCF:5060 -inf sipp_scenarios/users.csv \
     -s alice -au alice@ims.snehal.com -ap alice-secret -m 1
cd -
```

Exit code 0 means the registration passed. Then in Kibana:

```bash
microk8s kubectl -n logging port-forward svc/ims-kibana-kb-http 5601
```

Open **https://localhost:5601** (https, accept the certificate warning), log in as `elastic`,
open **Dashboards → IMS — Logs overview** or the **IMS — SIP signaling** search with
`sip.call_id : *`.

---

## When something does not come up

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| Service pod `ImagePullBackOff` | Registry not enabled yet, or image not pushed | Part 2 steps 2 and 8, then `microk8s kubectl -n ims-core rollout restart deploy/<name>` |
| Pod `Pending` | Its volume is not bound yet, or not enough memory | `microk8s kubectl -n <ns> describe pod <pod>` (Events at the bottom) |
| `apply -k deploy/kafka` says `no matches for kind "Kafka"` | Operator not installed | Run `./deploy/kafka/install-operator.sh` first |
| `apply -k deploy/logging` says `no matches for kind "Elasticsearch"` | ECK not installed | Run `./deploy/logging/elastic/install-operator.sh` first |
| I-CSCF/S-CSCF restart at start-up | HSS not ready yet when they connected over Cx | Wait for HSS, then `rollout restart` them |
| SIPp REGISTER times out | Old P-CSCF IP | Re-run the `PCSCF=` lookup |
| Kibana panels empty | Time range too short, or no traffic yet | Widen to "Last 24 hours", run a REGISTER |
| Documents in `logs-ims-default::failures` | Mapping clash | See "Troubleshooting" in `deploy/logging/README.md` |
