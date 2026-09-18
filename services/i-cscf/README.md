# I-CSCF (Interrogating-CSCF)

Home-network entry proxy. A transaction-stateful Jain SIP proxy built on
`shared-telecom-lib` that interrogates the HSS over the **Cx interface (gRPC)** to route
requests to the right S-CSCF:

- **REGISTER** → `UAR` (User-Authorization). The HSS either returns the already-assigned
  S-CSCF or a capability set from which the I-CSCF selects one, then the REGISTER is
  forwarded to that S-CSCF.
- **Terminating requests** (e.g. INVITE) → `LIR` (Location-Info). The HSS returns the
  serving S-CSCF for the target IMPU; the request is forwarded there.

It inserts itself into the Via path (transaction-stateful) but **does not Record-Route** —
the I-CSCF stays out of subsequent in-dialog signaling. Every message is mirrored to Homer
over HEPv3 and Actuator/Prometheus metrics are exposed on port `8080`. The Cx boundary is
guarded by a Resilience4j circuit breaker (`hssCx`).

> The upstream S-CSCF does not exist yet (Phase 4): `scscf.*` is a static stub target.
> UAR/LIR against the HSS, S-CSCF selection, SIP forwarding, HEP capture, and metrics all
> work today.

## Build

```bash
# from the repo root
mvn -pl services/i-cscf -am -DskipTests package
```

The runtime `Dockerfile` copies the prebuilt `target/i-cscf-*.jar` (no in-container Maven).

## Deploy to MicroK8s

Run from the repo root on the MicroK8s host.

### 1) Build image + push to the MicroK8s registry

```bash
microk8s enable registry        # one-time; gives you localhost:32000

# build context = services/i-cscf (where target/ and the Dockerfile live)
docker build -t localhost:32000/ims/i-cscf:latest services/i-cscf
docker push localhost:32000/ims/i-cscf:latest
```

### 2) Deploy the I-CSCF pod (HSS must already be running)

```bash
microk8s kubectl apply -f deploy/manifests/i-cscf/i-cscf.yaml
microk8s kubectl -n ims-core rollout status deploy/i-cscf
```

## Verify

```bash
microk8s kubectl -n ims-core get pods -l app.kubernetes.io/name=i-cscf
# expect: Jain SIP stack 'i-cscf' started, listening on 0.0.0.0:5060/UDP
microk8s kubectl -n ims-core logs -l app.kubernetes.io/name=i-cscf

microk8s kubectl -n ims-core port-forward svc/i-cscf 8080:8080 &
curl -s localhost:8080/actuator/health
curl -s localhost:8080/actuator/prometheus | grep ims_sip
```

## Configuration (`ims.icscf.*`)

| Key | Default | Purpose |
|-----|---------|---------|
| `ims.icscf.self-host` | `${POD_IP}` | Host advertised in Via / Route |
| `ims.icscf.self-port` | `5060` | Port advertised to peers |
| `ims.icscf.home-domain` | `ims.snehal.com` | Home network domain served |
| `ims.icscf.visited-network` | `ims.snehal.com` | Visited-network id sent in the UAR |
| `ims.icscf.scscf.{host,port,transport}` | `s-cscf.ims-core.svc.cluster.local:5060/udp` | Default S-CSCF when the HSS returns only capabilities (stub until Phase 4) |
| `grpc.client.hss.address` | `static://hss.ims-core.svc.cluster.local:9090` | HSS Cx gRPC endpoint |

SIP stack (`ims.sip.*`) and HEP capture (`ims.hep.*`) are configured via `shared-telecom-lib`.
