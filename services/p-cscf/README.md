# P-CSCF (Proxy-CSCF)

SIP entry point for the UE. A stateful Jain SIP proxy built on `shared-telecom-lib`:
rate-limits inbound requests (Bucket4j), mirrors every message to Homer over HEPv3,
inserts the P-CSCF into the signaling path (Via / Record-Route / Path), and forwards
to the upstream I-CSCF. Exposes Actuator/Prometheus metrics on port `8080`.

> `next-hop` targets the I-CSCF (`i-cscf.ims-core.svc.cluster.local:5060`, Phase 3). Deploy
> the I-CSCF pod for upstream forwarding to succeed. Boot, SIP listen (5060), HEP capture,
> metrics, and rate-limiting all work standalone.

## Build

```bash
# from the repo root
mvn -pl services/p-cscf -am -DskipTests package
```

The runtime `Dockerfile` copies the prebuilt `target/p-cscf-*.jar` (no in-container Maven).

## Deploy to MicroK8s

Run from the repo root on the MicroK8s host.

### 1) Build image + push to the MicroK8s registry

```bash
microk8s enable registry        # one-time; gives you localhost:32000

# build context = services/p-cscf (where target/ and the Dockerfile live)
docker build -t localhost:32000/ims/p-cscf:latest services/p-cscf
docker push localhost:32000/ims/p-cscf:latest
```

### 2) Deploy the namespaces

```bash
microk8s kubectl apply -f deploy/microk8s/namespaces.yaml
microk8s kubectl get ns ims-core ims-media monitoring
```

### 3) Deploy the P-CSCF pod

```bash
microk8s kubectl apply -f deploy/manifests/p-cscf/p-cscf.yaml
microk8s kubectl -n ims-core rollout status deploy/p-cscf
```

## Verify

```bash
microk8s kubectl -n ims-core get pods -l app.kubernetes.io/name=p-cscf
# expect: Jain SIP stack 'p-cscf' started, listening on 0.0.0.0:5060/UDP
microk8s kubectl -n ims-core logs -l app.kubernetes.io/name=p-cscf

microk8s kubectl -n ims-core port-forward svc/p-cscf 8080:8080 &
curl -s localhost:8080/actuator/health
curl -s localhost:8080/actuator/prometheus | grep ims_sip
```

For HEP capture to land in Homer, deploy the observability stack first:

```bash
microk8s kubectl apply -k deploy/monitoring
```

## Configuration (`ims.pcscf.*`)

| Key | Default | Purpose |
|-----|---------|---------|
| `ims.pcscf.self-host` | `${POD_IP}` | Host advertised in Via / Record-Route / Path |
| `ims.pcscf.self-port` | `5060` | Port advertised to peers |
| `ims.pcscf.next-hop.{host,port,transport}` | `i-cscf.ims-core.svc.cluster.local:5060/udp` | Upstream I-CSCF (Phase 3) |
| `ims.pcscf.rate-limit.enabled` | `true` | Per-source token-bucket toggle |
| `ims.pcscf.rate-limit.capacity` | `50` | Burst size per source IP |
| `ims.pcscf.rate-limit.refill-tokens` | `50` | Tokens refilled per period |
| `ims.pcscf.rate-limit.refill-period-seconds` | `1` | Refill window |

SIP stack (`ims.sip.*`) and HEP capture (`ims.hep.*`) are configured via `shared-telecom-lib`.
