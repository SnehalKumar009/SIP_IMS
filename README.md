# IMS SIP Platform

Production-grade, containerized **IP Multimedia Subsystem (IMS)** SIP platform for Kubernetes.
Built pod-by-pod as a learning + reference project spanning **Kubernetes** and **IMS/telecom**.

## Architecture

| Plane      | Component(s)                     | Tech                                            |
|------------|----------------------------------|-------------------------------------------------|
| Signaling  | P-CSCF, I-CSCF, S-CSCF           | Java 21 + Spring Boot + Jain SIP (NIST), ZGC    |
| Control    | HSS                              | Spring Boot + gRPC (Cx) + PostgreSQL            |
| Media      | RTPEngine                        | Native C (Sipwise), Kubernetes DaemonSet        |
| Charging   | CDF-billing                      | Kafka consumer → ClickHouse                     |
| Observ.    | Homer (HEPv3), Prometheus, Grafana | kube-prometheus-stack + Heplify-Server        |

Inter-process: **gRPC** for the Cx interface, **Kafka** for charging, **Resilience4j** on every boundary.

## Repository layout

```
platform/                 shared libraries (non-deployable)
  shared-telecom-lib/      Jain SIP lifecycle, HEPv3 capture, SIP metrics
services/                  deployable Spring Boot pods (added per phase)
deploy/
  microk8s/                cluster bootstrap (addons, namespaces; NodePort/hostPort SIP)
  monitoring/              Homer + Prometheus ServiceMonitors
  manifests/  helm/  terraform/   (per-pod deploy, added per phase)
tests/sipp/                end-to-end SIPp scenarios
docs/                      architecture + ADRs
```

Group id: `com.snehal.ims` · packages `com.snehal.ims.<pod>`.

## Build (on the Ubuntu dev host)

```bash
mvn clean install            # full reactor
mvn -pl platform/shared-telecom-lib test
```

## Cluster bring-up (Phase 0)

```bash
# 1. Bootstrap MicroK8s (addons, registry, observability, namespaces)
./deploy/microk8s/setup.sh

# 2. Deploy the Homer SIP-capture stack + Prometheus ServiceMonitors
microk8s kubectl apply -k deploy/monitoring
```

`shared-telecom-lib` auto-configures for any pod that depends on it:
- HEPv3 capture to Homer (`ims.hep.*`)
- Jain SIP stack + NIST tuning (`ims.sip.*`, signaling pods only)
- Shared Prometheus SIP metrics

## Build phases

- **Phase 0 — Foundation** ✅ cluster + `shared-telecom-lib` + observability
- **Phase 1 — P-CSCF** (SIP entrypoint, NAT traversal, Bucket4j)
- **Phase 2 — HSS** (PostgreSQL + gRPC Cx)
- **Phase 3 — I-CSCF** · **Phase 4 — S-CSCF** · **Phase 5 — RTPEngine**
- **Phase 6 — CDF-billing** · **Phase 7 — E2E + stress (SIPp)**
