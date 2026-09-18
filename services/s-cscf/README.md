# s-cscf — Serving-CSCF

The registrar and session controller of the IMS core, and the only stateful node in the
signaling chain.

## Responsibilities

| Area | Behaviour |
|------|-----------|
| Registration | Authenticates REGISTER with SIP Digest, maintains contact bindings, returns `Service-Route` / `P-Associated-URI` |
| Cx (gRPC) | `MAR` for authentication vectors, `SAR` for assignment + service profile |
| Terminating | Retargets the Request-URI onto a registered contact and replays the stored `Path` as the Route set |
| Originating | Strips UE-supplied identity headers and inserts a `P-Asserted-Identity` it vouches for |
| Session | Record-Routes dialogs, proxies CANCEL onto the right INVITE, tracks sessions for charging |
| iFC | Parses the service profile and evaluates trigger points (no AS wired yet — TD-011) |

## Authentication

The S-CSCF never sees a password. The HSS stores realm-bound `H(A1)` digests and returns
them over Cx; the S-CSCF issues a nonce, recomputes the expected response locally and
compares it in constant time.

- `MD5` (3GPP "SIP Digest") is the default for UE interop
- `SHA-256` (RFC 8760) is available via `ims.scscf.auth.algorithm`
- nonces are single-client, expire after `ims.scscf.auth.nonce-lifetime`, and reject a
  non-advancing `nonce-count` as a replay
- a failed authentication clears the HSS assignment, so a hijacked identity stops
  receiving terminating traffic

## Binding store

`ims.scscf.store` selects the implementation:

- `memory` (default) — per-pod. Correct because the StatefulSet pins each subscriber to
  the pod that registered them. Bindings are lost on restart (TD-021).
- `redis` — shared across replicas and survives restarts. Apply
  `deploy/manifests/s-cscf/redis.yaml` first.

Bindings are keyed by `+sip.instance` + `reg-id` when the UE supports RFC 5626, so a
device that changes address refreshes its binding instead of accumulating stale ones.

## Resilience

Cx calls are issued from Jain SIP listener threads, so the boundary is defended on three
axes: a per-call gRPC **deadline**, a **bulkhead** capping how many listener threads can
be inside the HSS at once, and a **circuit breaker**. All three fall back to
`DIAMETER_UNABLE_TO_COMPLY`, which becomes a SIP 503 rather than a stalled transaction.

Other guards: per-identity REGISTER rate limiting (Bucket4j), Via-based loop detection
(482 past `max-spirals`), Max-Forwards enforcement (483), and a per-AoR binding cap.

## Shutdown

`DrainCoordinator` flips readiness to refusing-traffic and answers new out-of-dialog
requests with `503 + Retry-After` while in-flight transactions drain. It deliberately does
**not** de-register subscribers: a rolling restart is not a de-registration.

## Extension seams

`MediaController` (Phase 5, RTPEngine) and `ChargingEventPublisher` (Phase 6, Kafka) are
interfaces with no-op/logging defaults. The call sites already exist, so neither phase has
to touch the dialog logic.

## Build

```bash
mvn -pl services/s-cscf -am -DskipTests package
```

## Deploy

```bash
# Build the image with services/s-cscf as the build context
docker build -t localhost:32000/ims/s-cscf:latest services/s-cscf
docker push localhost:32000/ims/s-cscf:latest

microk8s kubectl apply -f deploy/manifests/s-cscf/s-cscf.yaml
# Optional, only for ims.scscf.store=redis
microk8s kubectl apply -f deploy/manifests/s-cscf/redis.yaml
```

Upstream: HSS (`hss.ims-core.svc.cluster.local:9090`).
Downstream: reached from the I-CSCF; replies traverse the `Path` back through the P-CSCF.

## Metrics

Beyond the shared `ims_sip_*` meters: `ims_scscf_registered_bindings`,
`ims_scscf_active_sessions`, `ims_scscf_registrations_total{type}`,
`ims_scscf_auth_challenges_total`, `ims_scscf_auth_failures_total`,
`ims_scscf_register_rate_limited_total`, `ims_scscf_loops_detected_total`,
`ims_scscf_bindings_expired_total`.
