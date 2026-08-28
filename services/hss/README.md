# HSS (Home Subscriber Server)

Control-plane subscriber database for the IMS core. Exposes the **Cx interface over
gRPC** (in place of Diameter) to the I-CSCF and S-CSCF, backed by its own PostgreSQL.
Speaks no SIP and mirrors nothing to Homer; it publishes Actuator/Prometheus metrics
on port `8080` and serves gRPC on `9090`.

## Cx interface (gRPC `ims.cx.v1.CxService`)

| RPC | Diameter analogue | Caller | Purpose |
|-----|-------------------|--------|---------|
| `UserAuthorization` | UAR/UAA | I-CSCF | Authorize a REGISTER; locate or assign an S-CSCF |
| `MultimediaAuth` | MAR/MAA | S-CSCF | Fetch authentication vectors (SIP Digest HA1) |
| `ServerAssignment` | SAR/SAA | S-CSCF | Store/clear the S-CSCF assignment; return the user profile |
| `LocationInfo` | LIR/LIA | I-CSCF | Locate the serving S-CSCF for a terminating request |

Result and experimental-result codes mirror 3GPP TS 29.229 (see `CxResultCodes`).

## Build

```bash
# from the repo root
mvn -pl services/hss -am -DskipTests package
```

The runtime `Dockerfile` copies the prebuilt `target/hss-*.jar` (protobuf/gRPC stubs are
generated during the Maven build; no in-container Maven).

## Deploy to MicroK8s

Run from the repo root on the MicroK8s host.

```bash
# 1) Build image + push to the MicroK8s registry
microk8s enable registry        # one-time; gives you localhost:32000
docker build -t localhost:32000/ims/hss:latest services/hss
docker push localhost:32000/ims/hss:latest

# 2) Deploy PostgreSQL + HSS (namespaces from Phase 0/1 must already exist)
microk8s kubectl apply -f deploy/manifests/hss/hss.yaml
microk8s kubectl -n ims-core rollout status deploy/hss
```

## Verify

```bash
microk8s kubectl -n ims-core get pods -l app.kubernetes.io/name=hss
microk8s kubectl -n ims-core logs -l app.kubernetes.io/name=hss   # expect demo subscribers seeded

# Actuator + metrics
microk8s kubectl -n ims-core port-forward svc/hss 8080:8080 &
curl -s localhost:8080/actuator/health

# gRPC (with grpcurl + server reflection or the compiled proto)
microk8s kubectl -n ims-core port-forward svc/hss 9090:9090 &
grpcurl -plaintext -d '{"public_identity":"sip:alice@ims.snehal.com","private_identity":"alice@ims.snehal.com"}' \
  localhost:9090 ims.cx.v1.CxService/UserAuthorization
```

## Demo subscribers

Seeded on first startup when the store is empty (`ims.hss.seed-demo-data=true`):

| IMPI | IMPU | Password |
|------|------|----------|
| `alice@ims.snehal.com` | `sip:alice@ims.snehal.com` | `alice-secret` |
| `bob@ims.snehal.com` | `sip:bob@ims.snehal.com` | `bob-secret` |

## Configuration (`ims.hss.*`)

| Key | Default | Purpose |
|-----|---------|---------|
| `ims.hss.realm` | `ims.snehal.com` | Digest realm / default identity domain |
| `ims.hss.auth-scheme` | `SIP Digest` | Scheme advertised in Multimedia-Auth answers |
| `ims.hss.seed-demo-data` | `true` | Provision demo subscribers on first startup |

Datastore is configured via `HSS_DB_URL` / `HSS_DB_USER` / `HSS_DB_PASSWORD`; the gRPC
port via `grpc.server.port`. The shared SIP stack and HEP capture are disabled
(`ims.sip.enabled=false`, `ims.hep.enabled=false`).
