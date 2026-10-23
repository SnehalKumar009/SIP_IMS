# Logging pipeline

```
pod stdout (JSON, ECS fields) → /var/log/containers/*.log on the node
  → Fluent Bit (DaemonSet)      tail + cri + kubernetes metadata
  → Kafka topic ims-logs        (deploy/kafka)
  → Logstash                    Kafka consumer group logstash-ims-logs
  → Elasticsearch data stream logs-ims-default → Kibana
```

Fluent Bit, Logstash, Elasticsearch and Kibana run in the `logging` namespace; the ECK operator
runs in `elastic-system`.

## Fluent Bit

| | |
|---|---|
| Version | 5.1.3 (`cr.fluentbit.io/fluent/fluent-bit`) |
| Sources | `/var/log/containers/*_ims-core_*.log`, `*_monitoring_*.log` |
| Output | `ims-kafka-kafka-bootstrap.kafka:9092`, topic `ims-logs`, `acks=1`, lz4 |
| Config | `fluent-bit/fluent-bit.yaml` (generated ConfigMap; an edit rolls the DaemonSet) |

What each record looks like on `ims-logs`:

| Field | Source |
|-------|--------|
| `@timestamp`, `message`, `log.level`, `service.name`, `sip.call_id`, … | The app's JSON line, lifted to the top level |
| `message` (non-JSON lines) | The raw text of lines that are not JSON (e.g. Postgres, the Spring banner), moved from `log` because ECS reserves `log` for an object |
| `kubernetes.{namespace_name,pod_name,container_name,host,labels}` | Fluent Bit `kubernetes` filter |
| `event.created` | When the container runtime wrote the line |
| `stream` | `stdout` / `stderr` |

Why it needs more than a normal pod:

- **`hostPath` `/var/log`, read-only, as uid 0:** container logs are root-owned files on the node.
- **`hostPath` `/var/lib/fluent-bit`:** read offsets survive a pod restart (no lost or duplicated lines).
- **ClusterRole (read pods/namespaces):** the pods it describes live in other namespaces.

## Elasticsearch and Kibana (ECK)

| Component | Version | Resource |
|-----------|---------|----------|
| ECK operator | 3.5.0 | Helm, `elastic-system` (`elastic/install-operator.sh`) |
| Elasticsearch | 9.5.5 | `ims-es`: 1 node, 4 GiB (2 GiB heap), 20 GiB PVC |
| Kibana | 9.5.5 | `ims-kibana`: wired to `ims-es` by `elasticsearchRef` |

ECK generates the TLS certificates and the `elastic` password; nothing secret is in this repo.

Index configuration (`elastic/index-setup/`, loaded by `apply.sh`):

| Object | Setting |
|--------|---------|
| ILM policy `logs-ims` | Roll over daily or at 5 GB per shard, delete after 7 days |
| Index template `logs-ims` (priority 200, `logs-ims-*`) | Reuses the built-in `logs@mappings`, `logs@settings`, `ecs@mappings`; `number_of_replicas: 0` (single node); `service.*` and `sip.*` as keywords |

The data stream `logs-ims-default` uses `logsdb` mode (inherited from the built-in logs settings).
Documents that fail mapping land in its failure store, `logs-ims-default::failures`, instead of
being dropped.

## Logstash (ECK)

| | |
|---|---|
| Version | 9.5.5, `ims-logstash` (`logstash/30-logstash.yaml`), 1 GiB heap |
| Input | Kafka `ims-logs`, group `logstash-ims-logs`, 3 consumer threads (one per partition) |
| Output | Data stream `logs-ims-default`, as an ECK-created user with write access to `logs-*` (not superuser) |

The pipeline (inline in the resource) does three things:

- **Event time:** app lines keep their own `@timestamp`. Lines without one (non-app pods, pods on
  pre-ECS images) take `event.created`, the container runtime's write time.
- **`service.name` on every document:** the app's value, else the pod's `app.kubernetes.io/name`
  label, else the container name. Postgres and Homer lines become filterable the same way.
- Drops Logstash's own `@version` field.

`kubernetes.labels` is mapped as `flattened`: label keys such as `app.kubernetes.io/name` contain
dots, and expanding them into objects breaks the mapping as soon as one pod also has a plain `app`
label.

## Deploy

```bash
microk8s kubectl apply -f deploy/microk8s/namespaces.yaml
./deploy/logging/elastic/install-operator.sh             # once: ECK CRDs + operator
microk8s kubectl apply -k deploy/logging
microk8s kubectl -n logging rollout status ds/fluent-bit
microk8s kubectl -n logging wait elasticsearch/ims-es --for=jsonpath='{.status.health}'=green --timeout=600s
./deploy/logging/elastic/index-setup/apply.sh            # once, and after editing the JSON files
```

## Open Kibana

```bash
microk8s kubectl -n logging get secret ims-es-es-elastic-user -o jsonpath='{.data.elastic}' | base64 -d; echo
microk8s kubectl -n logging port-forward svc/ims-kibana-kb-http 5601
```

Browse to `https://localhost:5601` and log in as `elastic` with that password. The browser warns
about the certificate because ECK's CA is self-signed (TD-034).

## Verify

```bash
microk8s kubectl -n logging logs ds/fluent-bit | grep -E "\[(error|warn)\]"   # expect nothing
microk8s kubectl -n logging get elasticsearch,kibana,logstash                # all green

# Message count per partition, then the newest records
B=ims-kafka-kafka-bootstrap.kafka.svc.cluster.local:9092
microk8s kubectl -n kafka exec -i ims-kafka-dual-role-0 -- \
  /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server $B --topic ims-logs
microk8s kubectl -n kafka exec -i ims-kafka-dual-role-0 -- \
  /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server $B --topic ims-logs --max-messages 5
```

`--timeout-ms` never fires while pods are logging, so bound reads with `--max-messages`.

```bash
# Is Logstash keeping up? LAG should hover near 0.
microk8s kubectl -n kafka exec ims-kafka-dual-role-0 -- /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server $B --describe --group logstash-ims-logs

# Anything Elasticsearch refused lands here instead of being dropped
PW=$(microk8s kubectl -n logging get secret ims-es-es-elastic-user -o jsonpath='{.data.elastic}' | base64 -d)
microk8s kubectl -n logging exec ims-es-es-default-0 -c elasticsearch -- \
  curl -sk -u "elastic:$PW" "https://localhost:9200/logs-ims-default::failures/_count"
```
