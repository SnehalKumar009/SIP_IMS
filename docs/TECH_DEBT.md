# Technical Debt Register

Known shortcuts, deferred work, and risks carried by the IMS SIP platform.
Each entry states the risk and what "done" looks like, so nothing is lost between phases.

Status: `OPEN` · `ACCEPTED` (deliberate, not scheduled) · `RESOLVED`

---

## Security

### TD-001 — Cx (gRPC) traffic is plaintext
**Status:** OPEN · **Severity:** high · **Introduced:** Phase 2

`grpc.client.hss.negotiation-type: plaintext` and the HSS gRPC server has no TLS. Subscriber
identities and digest HA1 values (usable to forge authentication responses) cross the pod network
in the clear. Anything with network access inside `ims-core` can read or spoof Cx.

**Done when:** mTLS between CSCF and HSS, certificates issued by cert-manager, client identity
pinned on the HSS side.

### TD-002 — Gm interface has no TLS / IPsec
**Status:** ACCEPTED · **Severity:** high · **Introduced:** Phase 1

UE↔P-CSCF signaling is UDP/TCP 5060 in the clear. 3GPP mandates IPsec (or at minimum SIP over
TLS) on Gm. Digest credentials and call metadata are exposed on the access network.

**Done when:** TLS listener on the P-CSCF with proper SIP-URI certificate handling, or an IPsec
security association established during registration.

### TD-003 — Kubernetes Secrets hold plaintext values
**Status:** OPEN · **Severity:** medium · **Introduced:** Phase 2

`hss-postgres` Secret uses `stringData` with a literal password checked into git
(`hss-change-me`). Fine for a local MicroK8s cluster, unacceptable anywhere else.

**Done when:** External Secrets Operator or Sealed Secrets, with the literal removed from the repo
and the credential rotated.

### TD-004 — No authorization on the Cx interface
**Status:** OPEN · **Severity:** medium · **Introduced:** Phase 2

Any client that can reach `hss:9090` can issue SAR and rewrite a subscriber's S-CSCF assignment,
or issue MAR and harvest HA1 values for every subscriber. There is no caller identity check.

**Done when:** mTLS client-certificate identity (TD-001) plus a per-method authorization check, and
a NetworkPolicy restricting `hss:9090` ingress to CSCF pods.

### TD-005 — SIP Digest uses MD5
**Status:** ACCEPTED · **Severity:** low · **Introduced:** Phase 4

3GPP "SIP Digest" is MD5-based, so MD5 is spec-correct and is the default for SIPp/softphone
interop. MD5 is cryptographically broken for collision resistance; for HTTP Digest the practical
risk is offline brute-force of HA1 rather than collision, but it is still weak.

**Mitigation in place:** the HSS stores a SHA-256 HA1 alongside the MD5 one, and the S-CSCF can be
switched with `ims.scscf.auth.algorithm: SHA-256` (RFC 8760) when the UE supports it.

**Done when:** SHA-256 becomes the default and MD5 is offered only as a fallback.

### TD-006 — No NetworkPolicies
**Status:** OPEN · **Severity:** medium · **Introduced:** Phase 0

Every pod in the cluster can reach every other pod. The HSS database, the Cx interface, and the
internal SIP ports are all reachable from any workload.

**Done when:** default-deny ingress in `ims-core`, with explicit allows for the known flows
(P→I, I→S, S→HSS, S→Redis, pods→heplify).

### TD-007 — Containers run as root, no seccomp/read-only rootfs
**Status:** OPEN · **Severity:** medium · **Introduced:** Phase 0

None of the Dockerfiles create a non-root user and no `securityContext` is set on the pod specs
(other than `fsGroup` for Postgres). A signaling-plane RCE would land as uid 0.

**Done when:** non-root USER in every image, plus `runAsNonRoot`, `allowPrivilegeEscalation: false`,
`readOnlyRootFilesystem: true`, and `seccompProfile: RuntimeDefault` on every pod spec.

### TD-008 — No replay protection on the Cx interface
**Status:** ACCEPTED · **Severity:** low · **Introduced:** Phase 2

Cx requests carry no nonce or sequence number, so a captured SAR could be replayed. Low impact
while the interface is idempotent, and largely moot once TD-001 lands.

---

## Correctness / standards conformance

### TD-010 — P-CSCF ignores the Service-Route returned in the REGISTER 200 OK
**Status:** OPEN · **Severity:** medium · **Introduced:** Phase 4

The S-CSCF returns `Service-Route` so that originating requests from a registered UE are routed
directly to the serving S-CSCF. The P-CSCF currently forwards everything to the I-CSCF instead,
which then has to LIR on the *callee* to find an S-CSCF. This works for a single-network two-party
call but skips originating service execution entirely and adds a needless Cx round-trip.

**Done when:** the P-CSCF caches the Service-Route per registered AoR and pre-loads it as the Route
set for originating requests, with the `orig` parameter set.

### TD-011 — No originating/terminating iFC service execution (no ISC interface)
**Status:** ACCEPTED · **Severity:** low · **Introduced:** Phase 4

`IfcEvaluator` parses the service profile and evaluates trigger points, but there is no Application
Server to route to, so every matched trigger is a no-op. The ISC interface does not exist.

**Done when:** an AS is deployed and the S-CSCF proxies matched requests to it, honouring
`DefaultHandling` on AS failure.

### TD-012 — P-CSCF does not rewrite Contact/SDP for NAT
**Status:** OPEN · **Severity:** medium · **Introduced:** Phase 1

The P-CSCF sets `rport` on its Via, which fixes the *response* path, but it does not rewrite a
UE's `Contact` header or the `c=`/`m=` lines in SDP. A UE behind NAT that advertises a private
address will not receive in-dialog requests or media.

**Done when:** Phase 5 — Contact rewriting plus RTPEngine media anchoring.

### TD-013 — I-CSCF derives the IMPI by string-stripping the IMPU
**Status:** OPEN · **Severity:** low · **Introduced:** Phase 3

When a REGISTER carries no `Authorization` header, `IcscfSipListener.privateIdentity()` guesses the
IMPI by removing the `sip:` scheme from the IMPU. That is only correct for the default 3GPP
derivation rule and breaks for subscribers whose IMPI is not derived from their IMPU.

**Done when:** the HSS resolves the IMPI from the IMPU during UAR instead of the I-CSCF guessing.

### TD-014 — Single IMPU per subscriber
**Status:** OPEN · **Severity:** low · **Introduced:** Phase 2

`Subscriber` has a unique 1:1 IMPI↔IMPU mapping. Real IMS subscribers have an implicit
registration set: several public identities sharing one private identity. `P-Associated-URI`
therefore only ever lists one URI.

**Done when:** `Subscriber` owns a collection of `PublicIdentity` rows and registration applies to
the whole implicit registration set.

### TD-015 — No CANCEL forking or 100rel/PRACK support
**Status:** ACCEPTED · **Severity:** low · **Introduced:** Phase 4

The S-CSCF forwards to a single contact (highest q-value) rather than forking in parallel, and
does not support reliable provisional responses.

### TD-016 — Terminating retarget picks one contact
**Status:** ACCEPTED · **Severity:** low · **Introduced:** Phase 4

When an AoR has several bindings, the terminating handler selects the highest-q, longest-lived
binding instead of forking to all of them. Multi-device ringing does not work.

---

## Operability

### TD-020 — I-CSCF Cx calls have no deadline or bulkhead
**Status:** RESOLVED (Phase 4) · **Severity:** high · **Introduced:** Phase 3

A circuit breaker reacts only *after* failures accumulate; it does not bound latency. Blocking
gRPC calls issued from Jain SIP listener threads could exhaust the 64-thread NIST pool during an
HSS brownout, before the breaker ever tripped.

**Resolved by:** per-call `withDeadlineAfter` on every stub invocation plus a Resilience4j
`Bulkhead` capping concurrent Cx calls, on both the I-CSCF and the S-CSCF.

### TD-021 — Registration state is lost on S-CSCF restart
**Status:** ACCEPTED · **Severity:** medium · **Introduced:** Phase 4

With the default `ims.scscf.store: memory`, all bindings are lost when the pod restarts. UEs
re-register on their own timer (up to `max-expires`, default 3600s), so recovery is eventually
automatic but can take an hour, during which terminating calls fail with 404.

**Mitigation in place:** `ims.scscf.store: redis` shares bindings across replicas and survives pod
restarts.

**Done when:** Redis becomes the default and is deployed as part of the standard manifest set.

### TD-022 — S-CSCF assignment is pinned to a single pod identity
**Status:** ACCEPTED · **Severity:** medium · **Introduced:** Phase 4

The S-CSCF runs as a StatefulSet and advertises its own stable pod DNS name as `server_name` in
SAR. The HSS stores that name, and the I-CSCF routes terminating requests to that exact pod. This
is the 3GPP assignment model and it is correct, but it means a pod outage makes its subscribers
unreachable until they re-register.

**Done when:** Redis-backed shared state (TD-021) plus a single Service name as `server_name`, so
any replica can serve any subscriber.

### TD-023 — No SIPp / end-to-end regression suite
**Status:** OPEN · **Severity:** medium · **Introduced:** Phase 0

Every component has unit tests but nothing exercises REGISTER→INVITE→BYE across the real pods.
Routing regressions are only caught by hand.

**Done when:** Phase 7 — `tests/sipp/` scenarios wired into CI against a kind/MicroK8s cluster.

### TD-024 — Single replica everywhere, no PodDisruptionBudgets
**Status:** OPEN · **Severity:** medium · **Introduced:** Phase 0

Every Deployment is `replicas: 1` with no PDB and no anti-affinity, and Postgres is a single
StatefulSet pod with no backup. Any node drain is a full outage and a node loss is data loss.

**Done when:** multi-replica CSCFs, PDBs, anti-affinity rules, and a Postgres backup/restore path.

### TD-025 — Images are `:latest` with no SBOM or signature
**Status:** OPEN · **Severity:** medium · **Introduced:** Phase 0

`localhost:32000/ims/<pod>:latest` is not reproducible, cannot be rolled back, and nothing verifies
image provenance.

**Done when:** immutable tags derived from the git SHA, `imagePullPolicy: IfNotPresent`, plus SBOM
generation and cosign signing in the build.

### TD-026 — No distributed tracing
**Status:** OPEN · **Severity:** low · **Introduced:** Phase 0

Call-ID is carried in the MDC and HEP correlates SIP hops in Homer, but the SIP→gRPC boundary has
no trace propagation, so a slow REGISTER cannot be attributed to the Cx round-trip from metrics
alone.

**Done when:** Micrometer Tracing + OTLP export, with Call-ID as a baggage item and the trace
context propagated on gRPC metadata.

### TD-027 — HSS has no read replica and is a single point of failure
**Status:** OPEN · **Severity:** medium · **Introduced:** Phase 2

Every REGISTER costs up to three synchronous Cx round-trips (UAR, MAR, SAR) into one Postgres pod.
Under a registration storm — for example after an S-CSCF restart — that is the bottleneck, and its
failure stops all registration and terminating call setup.

**Done when:** a read replica for UAR/LIR/MAR paths, plus an S-CSCF-local cache of the service
profile keyed by IMPU.

### TD-028 — Nonce and session state are per-pod in-memory
**Status:** ACCEPTED · **Severity:** low · **Introduced:** Phase 4

`NonceStore` and `SessionTracker` are Caffeine/`ConcurrentHashMap` instances local to each pod. A
challenge issued by one pod cannot be answered at another. Harmless under TD-022 (subscribers are
pinned to a pod) but blocks the stateless-replica model.

**Done when:** both move to Redis alongside the registration store.

---

## Data / schema

### TD-030 — Flyway migration is not baseline-safe for pre-Phase-4 volumes
**Status:** OPEN · **Severity:** low · **Introduced:** Phase 4

`V1__subscriber.sql` is written idempotently (`IF NOT EXISTS` / `IF EXISTS`) so it can adopt a
schema previously created by `ddl-auto: update`. It drops the legacy plaintext `password` column
without migrating it, so subscribers provisioned before Phase 4 must be re-provisioned.

**Done when:** the demo data path is replaced by a real provisioning API and the idempotent guards
can be removed from V1.

### TD-031 — Demo subscribers are seeded with well-known passwords
**Status:** OPEN · **Severity:** medium · **Introduced:** Phase 2

`DataSeeder` provisions `alice`/`bob` with hardcoded secrets whenever `ims.hss.seed-demo-data` is
true, which is the packaged default. If that default ever reaches a real deployment, two accounts
with published credentials exist.

**Mitigation in place:** the seeder only runs on an empty store, and the passwords are hashed to
HA1 immediately (the plaintext is never persisted).

**Done when:** seeding defaults to `false` and is enabled explicitly per environment.

### TD-032 — No provisioning API
**Status:** OPEN · **Severity:** low · **Introduced:** Phase 2

Subscribers can only be created by the seeder or by direct SQL. There is no Sh/Cx provisioning
interface and no audit trail of who changed what.
