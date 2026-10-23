# Kafka (Strimzi)

Shared Kafka cluster for the IMS platform, run by the Strimzi operator in the `kafka` namespace.

| Topic | Producer | Consumer | Purpose |
|-------|----------|----------|---------|
| `ims-logs` | Fluent Bit | Logstash | JSON pod logs on their way to Elasticsearch (best-effort) |
| *(Phase 6)* charging | S-CSCF | CDF-billing | Session start/end events (durable, `acks=all`) |

| Component | Version |
|-----------|---------|
| Strimzi Cluster Operator | 1.2.0 (`kafka.strimzi.io/v1` API) |
| Apache Kafka | 4.3.1, KRaft mode (no ZooKeeper) |

Bootstrap address for in-cluster clients:
`ims-kafka-kafka-bootstrap.kafka.svc.cluster.local:9092` (plaintext, TD-009)

## Files

| File | What it is |
|------|------------|
| `install-operator.sh` | Installs the pinned Strimzi operator (Helm chart from the GitHub release) |
| `10-kafka-cluster.yaml` | `KafkaNodePool` (1 dual-role node, 10Gi PVC) + `Kafka` `ims-kafka` |
| `20-topics.yaml` | `KafkaTopic` resources (`ims-logs`: 3 partitions, 24h / 1 GiB-per-partition retention) |

## Deploy

```bash
./deploy/kafka/install-operator.sh                     # once, or to upgrade the operator
microk8s kubectl apply -k deploy/kafka
microk8s kubectl -n kafka wait kafka/ims-kafka --for=condition=Ready --timeout=400s
```

## Verify

```bash
microk8s kubectl -n kafka get kafka,kafkanodepool,kafkatopic,pods

# Round trip through the bootstrap Service
B=ims-kafka-kafka-bootstrap.kafka.svc.cluster.local:9092
echo '{"message":"hello"}' | microk8s kubectl -n kafka exec -i ims-kafka-dual-role-0 -- \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server $B --topic ims-logs
microk8s kubectl -n kafka exec -i ims-kafka-dual-role-0 -- \
  /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server $B --topic ims-logs \
  --from-beginning --max-messages 1 --timeout-ms 15000
```

## Notes

- Topics are declared only as `KafkaTopic` resources. `auto.create.topics.enable: false` stops a
  producer from creating a topic by publishing to a misspelt name.
- Single broker, replication factor 1 everywhere (TD-029). Fine for a dev cluster; not for
  charging data in production.
- Removing the cluster keeps the data: `deleteClaim: false` leaves the PVC behind.
