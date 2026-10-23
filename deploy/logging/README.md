# Logging pipeline

```
pod stdout (JSON, ECS fields) → /var/log/containers/*.log on the node
  → Fluent Bit (DaemonSet)      tail + cri + kubernetes metadata
  → Kafka topic ims-logs        (deploy/kafka)
  → Logstash                    (next step)
  → Elasticsearch → Kibana      (next step)
```

Everything here runs in the `logging` namespace.

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
| `log` | Only for lines that are not JSON (e.g. Postgres, the Spring banner) |
| `kubernetes.{namespace_name,pod_name,container_name,host,labels}` | Fluent Bit `kubernetes` filter |
| `event.created` | When the container runtime wrote the line |
| `stream` | `stdout` / `stderr` |

Why it needs more than a normal pod:

- **`hostPath` `/var/log`, read-only, as uid 0:** container logs are root-owned files on the node.
- **`hostPath` `/var/lib/fluent-bit`:** read offsets survive a pod restart (no lost or duplicated lines).
- **ClusterRole (read pods/namespaces):** the pods it describes live in other namespaces.

## Deploy

```bash
microk8s kubectl apply -f deploy/microk8s/namespaces.yaml
microk8s kubectl apply -k deploy/logging
microk8s kubectl -n logging rollout status ds/fluent-bit
```

## Verify

```bash
microk8s kubectl -n logging logs ds/fluent-bit | grep -E "\[(error|warn)\]"   # expect nothing

# Message count per partition, then the newest records
B=ims-kafka-kafka-bootstrap.kafka.svc.cluster.local:9092
microk8s kubectl -n kafka exec -i ims-kafka-dual-role-0 -- \
  /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server $B --topic ims-logs
microk8s kubectl -n kafka exec -i ims-kafka-dual-role-0 -- \
  /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server $B --topic ims-logs --max-messages 5
```

`--timeout-ms` never fires while pods are logging, so bound reads with `--max-messages`.
