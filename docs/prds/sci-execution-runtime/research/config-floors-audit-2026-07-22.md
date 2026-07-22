---
type: research
status: active
tags: [research, architecture, database]
---

# Config floor audit + operator reference draft — sol read-only pass (2026-07-22)

Orchestrator-accepted. Corrections: ALL 31 operational attributes are now
enforced (the W1.2-era heap+processors statement is stale); the closed
manifest exposes only ~12 of them. Ranked footguns (heap/frame/connections/
run-policy family), the input-bytes permanent-session wedge, and the writer
read-ceiling constants feed unit W1.7 (floors + exposure + reference doc).

# 1. Floor audit

Scope note: current `:seon.config/database` is a closed manifest section exposing only the six query/pull ceilings plus four operational overrides: heap, selected processors, frame bytes, and connections. The other 27 operational attributes are present in the launch-envelope and singleton schemas, but a normal selected manifest containing them is rejected as unknown input. [`src/seon/config/resolve.cljc:209`](src/seon/config/resolve.cljc:209), [`src/seon/config/resolve.cljc:647`](src/seon/config/resolve.cljc:647), [`src/seon/config.cljs:715`](src/seon/config.cljs:715)

All 31 operational attributes are nevertheless currently `:enforced`: if their resolved values differ, `config apply` reconstructs the writer. The older W1.2 research statement that only heap and processors are enforced is stale. [`src/seon/config/resolve.cljc:126`](src/seon/config/resolve.cljc:126), [`src/seon/config/resolve.cljc:163`](src/seon/config/resolve.cljc:163), [`script/seon/dev/cli.clj:386`](script/seon/dev/cli.clj:386)

Notation used below:

- `P = min(observed cores, requested selected-processors)`
- `C = max(1, P − 1)`
- `K = max(1, min(2, floor(C/2)))`
- `M = max(1, min(4, floor((P+1)/2)))`
- `V = min(6, P)`
- `H = resolved writer heap in bytes`

The formulas are owned by [`src/seon/config/resolve.cljc:891`](src/seon/config/resolve.cljc:891).

## Verdict-ranked audit

| Rank | Exact key(s) | Default derivation | Resolve/apply validation | Minimum actual boot/liveness requirement | Verdict |
|---:|---|---|---|---|---|
| 1 | `:seon.config.database.writer/jvm-heap-mb` | `clamp(system-memory-MiB / 16, 512, 4096)` | Shared integer floor `1`; explicit values bypass the default clamp. [`resolve.cljc:51`](src/seon/config/resolve.cljc:51), [`resolve.cljc:916`](src/seon/config/resolve.cljc:916) | The value is passed directly as `-XmxNm`. A read-only probe confirmed `-Xmx1m` aborts with “Too small maximum heap.” The writer must then open/ensure the file database before readiness. The store-dependent replay floor is **NOT GROUNDED**. [`process.clj:550`](script/seon/dev/process.clj:550), [`writer.clj:4258`](src/seon/db/writer.clj:4258) | **FOOTGUN** — legal manifest values can prevent JVM startup; excessive values can also exceed available memory. |
| 2 | `:seon.config.database.transport/maximum-frame-bytes` | Protocol ceiling, `4 MiB` | Explicit `[4096, 4 MiB]` check. The lower bound only guarantees the fixed session-open exchange. [`resolve.cljc:924`](src/seon/config/resolve.cljc:924), [`protocol.cljc:107`](src/seon/db/protocol.cljc:107) | q21 uses page size `32` and result-weight `60000`; current pages measured up to `13.9 KB`. Failed committed publication is fatal. `65536` has passed an end-to-end boot. [`admission.cljs:195`](src/seon/runtime/admission.cljs:195), [`program-synthesis-2026-07-21.md:922`](docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:922), [`program-synthesis-2026-07-21.md:1017`](docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:1017), [`client.cljs:2289`](src/seon/client.cljs:2289) | **FOOTGUN** — `4096` is legal but below current boot pages. Practical floor: `65536`; a universal future-corpus floor is **NOT GROUNDED**. |
| 3 | `:seon.config.database.transport/maximum-connections` | `min(max(64,16C),1024,floor(fd-soft-limit/4))` | Integer floor `1`; no workload floor. [`resolve.cljc:920`](src/seon/config/resolve.cljc:920) | Core pod boot needs one retained connection. At capacity, additional clients receive a structured rejection. The SCI host uses a separate lazy retained writer pool, so `1` leaves no room for agent-host database access while the pod connection remains open. [`uds.cljc:1302`](src/seon/db/transport/uds.cljc:1302), [`host/context.clj:192`](src/seon/host/context.clj:192), [`host/context.clj:211`](src/seon/host/context.clj:211) | **FOOTGUN** — core boot may succeed, but normal agent execution can become unavailable. Minimum for pod plus one host connection is `2`; fleet-safe sizing is workload-dependent. |
| 4 | `:seon.config.run/deadline-ms` | `1800000` ms | Integer floor `1`. [`resolve.cljc:473`](src/seon/config/resolve.cljc:473), [`resolve.cljc:1034`](src/seon/config/resolve.cljc:1034) | Not consumed by writer/pod boot. A legal `1` ms run has effectively no useful lifetime. | **FOOTGUN** — fact-only, but can make agents functionally unusable. |
| 5 | `:seon.config.run/batch-turn-limit`; `:seon.config.run/stream-form-limit` | `100`; `300` | Integer floor `1`. [`resolve.cljc:476`](src/seon/config/resolve.cljc:476) | Not boot-critical. Value `1` permits only one batch turn or streamed form. | **FOOTGUN** — bounded termination rather than a process wedge, but legal values can prevent useful multi-step work. |
| 6 | `:seon.config.watchdog/stale-ms` | `1200000` ms | Positive integer only. [`resolve.cljc:105`](src/seon/config/resolve.cljc:105), [`resolve.cljc:1082`](src/seon/config/resolve.cljc:1082) | Must exceed legitimate intervals without heartbeat progress. The shipped value is deliberately above the 15-minute turn timeout. [`config.cljs:1561`](src/seon/config.cljs:1561) | **FOOTGUN** — `1` can close live runs as `:crashed`. |
| 7 | `:seon.config.breaker/crash-count`; `:seon.config.breaker/window-ms` | `3`; `1800000` ms | Positive integers only. [`resolve.cljc:106`](src/seon/config/resolve.cljc:106), [`resolve.cljc:1084`](src/seon/config/resolve.cljc:1084) | Not boot-critical. The breaker refuses schedule wakes after the configured number of crashes within the window. [`config.cljs:1573`](src/seon/config.cljs:1573) | **FOOTGUN** — count `1` plus a long window can suppress scheduled work after one crash. |
| 8 | `:seon.config.database.query/max-work`; `/max-results`; `/max-result-weight` | `100000000`; `1000000`; `3000000` | Each is an integer ≥1. [`resolve.cljc:120`](src/seon/config/resolve.cljc:120), [`resolve.cljc:869`](src/seon/config/resolve.cljc:869) | Not used to acquire the configuration singleton, and q21 supplies explicit page budgets. Later ordinary agent queries inherit these facts. [`db.cljs:770`](src/seon/db.cljs:770) | **FOOTGUN** — values near `1` can make ordinary agent queries unusable, but do not prevent core boot. Oversized values are still clamped by the writer. |
| 9 | `:seon.config.database.pull/max-work`; `/max-results`; `/max-result-weight` | `25000000`; `1000000`; `3000000` | Each is an integer ≥1. [`resolve.cljc:123`](src/seon/config/resolve.cljc:123), [`resolve.cljc:875`](src/seon/config/resolve.cljc:875) | Same separation as query policy; later ordinary pulls inherit these facts. | **FOOTGUN** — too small can disable ordinary pulls; too large cannot exceed writer ceilings. |
| — | `:seon.config.database.executor/maximum-queued-request-bytes` | `clamp(H/16,8 MiB,64 MiB)` | Launch-envelope/singleton integer floor `1`; executor checks only positivity. Current closed manifest does **not** expose it. [`resolve.cljc:938`](src/seon/config/resolve.cljc:938), [`executor.clj:451`](src/seon/db/executor.clj:451) | Admission requires queued bytes plus the request/reservation to fit. A value of `1` rejects ordinary encoded boot jobs. [`executor.clj:526`](src/seon/db/executor.clj:526) | **NOT GROUNDED / UNEXPOSED HAZARD** — not a legal current manifest override, but an envelope-legal value can break boot. |
| — | `:seon.config.database.transport/maximum-input-bytes` | `min(32 MiB,H/16)` | Integer floor `1`; no relation to frame size. Not exposed by the current manifest schema. [`resolve.cljc:985`](src/seon/config/resolve.cljc:985) | A semantic request reserves its four-byte header plus payload. If one frame cannot fit, the read is paused; because nothing was admitted, no reservation can later free. [`uds.cljc:471`](src/seon/db/transport/uds.cljc:471), [`uds.cljc:540`](src/seon/db/transport/uds.cljc:540), [`uds.cljc:554`](src/seon/db/transport/uds.cljc:554) | **NOT GROUNDED / UNEXPOSED HAZARD** — latent permanent-session wedge. A one-frame floor is at least `maximum-frame-bytes + 4`; concurrent safe sizing is higher. |
| — | `:seon.config.database.transport/maximum-output-bytes`; `/maximum-session-output-bytes` | `min(256 MiB,H/2)`; `min(128 MiB,H/4)` | Integer floor `1`; no relation to frame size. Not exposed by the current manifest schema. [`resolve.cljc:994`](src/seon/config/resolve.cljc:994) | An encoded response must fit both the session and authority reservations. [`uds.cljc:581`](src/seon/db/transport/uds.cljc:581) | **NOT GROUNDED / UNEXPOSED HAZARD** — envelope values below a required boot response prevent delivery. Exact global concurrency floors are **NOT GROUNDED**. |
| — | `:seon.config.database.executor/selected-processors` | Observed cores, capped to observed cores | Explicit positive-integer check; resolved value is `min(observed, requested)`. [`resolve.cljc:895`](src/seon/config/resolve.cljc:895) | Resolver derives at least one CPU worker even at `P=1`. | **SAFE** — legal values cannot create zero workers; low values reduce throughput. |
| — | `:seon.config.database.executor.read/maximum-active`; `/maximum-queued`; `/maximum-queued-by-database` | `C`; `max(16,8C)`; `min(read-queue,max(16,4C))` | Positive integers; executor validates all three at startup. Not exposed by current manifest. [`resolve.cljc:942`](src/seon/config/resolve.cljc:942), [`executor.clj:457`](src/seon/db/executor.clj:457) | Boot reads are sequentially live with one active worker and one queue slot. | **SAFE** for minimal boot; small values cause bounded rejection under concurrency. |
| — | `:seon.config.database.executor.knn/maximum-active`; `/maximum-queued`; `/maximum-queued-by-database` | `K`; `max(4,2K)`; `2` | Positive integers; not exposed by current manifest. [`resolve.cljc:949`](src/seon/config/resolve.cljc:949) | KNN is not demonstrated as a generic cold-boot prerequisite. Embedding-enabled boot requirements are **NOT GROUNDED**. | **DEGRADE-ONLY** for generic boot. |
| — | `:seon.config.database.executor.provider/maximum-active`; `/maximum-queued`; `/maximum-queued-by-database` | `V`; `2V`; `2` | Positive integers; not exposed by current manifest. [`resolve.cljc:956`](src/seon/config/resolve.cljc:956) | Provider work is not a generic cold-boot prerequisite. | **DEGRADE-ONLY**. |
| — | `:seon.config.database.executor.mutation/maximum-active`; `/maximum-queued`; `/maximum-queued-by-database` | `M`; `max(64,16M)`; same queue | Positive integers; not exposed by current manifest. [`resolve.cljc:963`](src/seon/config/resolve.cljc:963) | Boot reconciliation needs mutation progress; the floor of one active worker supplies it. | **SAFE** for serial boot; small queues reduce concurrent capacity. |
| — | `:seon.config.database.executor.delivery/maximum-active`; `/maximum-queued`; `/maximum-queued-by-database` | `C`; `max(16,4C)`; `1` | Positive integers; not exposed by current manifest. [`resolve.cljc:970`](src/seon/config/resolve.cljc:970) | One active worker prevents a zero-delivery wedge. | **SAFE** for minimal boot; throughput degradation under listener load. |
| — | `:seon.config.database.executor.hnsw/maximum-active`; `/maximum-queued`; `/maximum-queued-by-database` | `1`; `1`; `1` | Positive integers; not exposed by current manifest. [`resolve.cljc:977`](src/seon/config/resolve.cljc:977) | HNSW work is not shown to be a generic cold-boot prerequisite. | **DEGRADE-ONLY** for generic boot. |
| — | `:seon.config.database.transport/maximum-response-slots`; `/maximum-session-response-slots` | Connections; `max(1,floor(connections/4))` | Positive integers; no cross-field ordering check; not exposed by current manifest. [`resolve.cljc:988`](src/seon/config/resolve.cljc:988) | One slot admits one serial request; occupied slots pause later reads and resume after release. [`uds.cljc:518`](src/seon/db/transport/uds.cljc:518) | **SAFE** for serial boot; low values reduce concurrency. |
| — | `:seon.config.database.transport/codec-workers`; `/codec-worker-queue-capacity` | `max(2,min(8,P))`; `256` | Positive integers; not exposed by current manifest. [`resolve.cljc:1002`](src/seon/config/resolve.cljc:1002) | One worker can decode sequential boot traffic. A one-entry queue may reject bursts; a cold-boot failure at `1` is **NOT GROUNDED**. | **SAFE** for minimal boot; load footgun if later exposed. |
| — | `:seon.config.database.transport/shutdown-timeout-ms` | `5000` ms | Positive integer; not exposed by current manifest. [`resolve.cljc:1000`](src/seon/config/resolve.cljc:1000) | Used during teardown, not startup. | **DEGRADE-ONLY** — too small weakens graceful shutdown evidence. |
| — | `:seon.config/spawn-depth-cap` | `1` | Nonnegative integer; invalid values fall back to `1`. [`resolve.cljc:1043`](src/seon/config/resolve.cljc:1043) | `0` deliberately prevents root from spawning. [`config.cljs:1550`](src/seon/config.cljs:1550) | **DEGRADE-ONLY**. |
| — | `:seon.config.root/recent-limit` | `12` | Integer ≥1. | Bounded root lookback/display only. [`config.cljs:1592`](src/seon/config.cljs:1592) | **DEGRADE-ONLY**. |
| — | `:seon.config/reactive-settle-ms`; `/reactive-structural-settle-ms`; `/reactive-max-latency-ms` | `16`; `300`; `500` | Integers ≥1. [`resolve.cljc:863`](src/seon/config/resolve.cljc:863) | Affect reactive scheduling, not the writer/pod boot path. Exact pathological churn/delay floors are **NOT GROUNDED**. | **DEGRADE-ONLY** on present evidence. |
| — | `:seon.config.model-transport/response-identity-cap`; `/endpoint-cap` | `512`; `2048` | Integers ≥1. [`resolve.cljc:76`](src/seon/config/resolve.cljc:76) | Bounds stored provider/endpoint evidence, not provider requests or boot. | **DEGRADE-ONLY**. |
| — | All `:seon.config.render/*` numeric and display-mode keys | See reference table below | Numeric values ≥1; closed enums/boolean for display modes. [`resolve.cljc:51`](src/seon/config/resolve.cljc:51), [`resolve.cljc:94`](src/seon/config/resolve.cljc:94) | No boot constructor consumes them; they are read-time rendering and value-browser bounds. [`config.cljs:1141`](src/seon/config.cljs:1141) | **DEGRADE-ONLY**. |
| — | All `:seon.config.repair/*` keys | See reference table below | Level enum; max-fixes/budget ≥1; classes keyword→boolean map. [`resolve.cljc:99`](src/seon/config/resolve.cljc:99), [`resolve.cljc:408`](src/seon/config/resolve.cljc:408) | Over-budget repair deliberately yields no fix and the ordinary error. [`config.cljs:1431`](src/seon/config.cljs:1431) | **DEGRADE-ONLY**. |

### Writer read-ceiling audit

These remain fixed writer constants, not manifest keys or database facts:

| Exact writer option | Default | Boot requirement | Verdict |
|---|---:|---|---|
| `:datahike.resource/max-work` | `100000000` | Exact q21/store-replay minimum is **NOT GROUNDED**. The current value passed the 64 KiB live boot. | **SAFE current default** |
| `:datahike.resource/max-results` | `1000000` | q21 pages contain 32 entities, but Datahike charges retained nested result nodes, so an exact floor cannot be inferred from row count. **NOT GROUNDED**. | **SAFE current default** |
| `:datahike.resource/max-result-weight` | `3000000` | Must be at least q21’s explicit `60000` page budget, because the writer clamps each request/member to `min(client, writer)`. [`writer.clj:871`](src/seon/db/writer.clj:871), [`admission.cljs:247`](src/seon/runtime/admission.cljs:247) | **SAFE**, with a grounded minimum of `60000` for q21 |
| `::seon.db.writer/read-deadline-ms` | `30000 × max(1,ceil(default-read-queue/default-read-active))` | Exact slow-store/replay floor is **NOT GROUNDED**. It is derived from a fresh default executor capacity, not the resolved launch capacity. | **SAFE current default**, derivation mismatch remains |

Definitions are at [`src/seon/db/writer.clj:43`](src/seon/db/writer.clj:43). The separate configuration-singleton bootstrap profile is `100000 work / 4096 results / 1 MiB weight`. [`src/seon/config.cljs:866`](src/seon/config.cljs:866)

# 2. Reference draft

Suggested destination: `docs/seon/reference/operational-configuration.md`.

---

# Operational configuration recovery and limits

## Recovery flow

1. **Try a config-free reopen first.** With no explicit path or `SEON_CONFIG`, Seon reopens the existing database without treating `{}` as new desired state. It acquires and uses the retained configuration facts. [`script/seon/dev/config.clj:155`](script/seon/dev/config.clj:155), [`src/seon/client.cljs:2104`](src/seon/client.cljs:2104)

2. **Repair drift explicitly.** Run:

   ```bash
   bin/seon config apply config/system.edn
   ```

   The operator resolves the complete candidate under the stack lock. When only fact-backed options changed, it reconciles those facts directly. When any operational attribute changed, it drains agent work, replaces only the writer, reconnects, reapplies the resolved singleton, proves launch equality, and reopens committed-program admission. [`script/seon/dev/cli.clj:396`](script/seon/dev/cli.clj:396), [`src/seon/client.cljs:2615`](src/seon/client.cljs:2615)

3. **Inspect failure evidence before retrying.** Do not repeatedly apply a manifest whose heap, frame, connection, or agent-liveness limits are below the floors below.

4. **Use cluster reset last.** Reset stops the cluster and deletes its database. It is appropriate only when the retained database is intentionally disposable:

   ```bash
   bin/seon cluster reset default
   ```

   [`script/seon/dev/cli.clj:783`](script/seon/dev/cli.clj:783)

A config-free reopen is not guaranteed to recover already-invalid retained operational facts: **NOT GROUNDED**. If launch equality or boot acquisition fails, repair the selected/applied manifest through the operator rather than assuming the pod can self-heal.

## Writer and transport options

All rows marked **reconstruct** cause live writer replacement when their resolved value changes. [`script/seon/dev/cli.clj:386`](script/seon/dev/cli.clj:386)

| Key | Effect | Default | Operator range/floor | Too small / too large | Apply |
|---|---|---|---|---|---|
| `:seon.config.database.writer/jvm-heap-mb` | JVM writer maximum heap | `clamp(RAM-MiB/16,512,4096)` | Use the hardware default unless measured. Exact store floor **NOT GROUNDED**. | Too small: JVM refusal or store open/replay OOM. Too large: JVM allocation/start failure or host memory pressure. | reconstruct |
| `:seon.config.database.executor/selected-processors` | Caps writer processors used to derive worker defaults | observed cores, capped to observed cores | `1..observed cores` | Small: less throughput. Large override is clamped. | reconstruct |
| `:seon.config.database.executor/maximum-queued-request-bytes` | Aggregate queued request-byte admission | `clamp(heap/16,8 MiB,64 MiB)` | At least the largest encoded request plus reservations; exact floor **NOT GROUNDED** | Below one request: boot/read rejection. Large: more retained queue memory. Currently not manifest-exposed. | reconstruct |
| `:seon.config.database.transport/maximum-frame-bytes` | Negotiated semantic frame ceiling | `4 MiB` | `65536` is the proven practical floor; schema merely accepts `4096..4 MiB` | Below current page size: fatal q21 publication failure. Large: larger per-frame memory exposure, capped at 4 MiB. | reconstruct |
| `:seon.config.database.transport/maximum-connections` | Concurrent admitted UDS sessions | `min(max(64,16C),1024,fd/4)` | `1` for pod-only boot; at least `2` for pod plus one SCI-host connection; size for fleet concurrency | Too small: structured connection-capacity failures and unavailable agent execution. Large: FD/thread/heap pressure. | reconstruct |
| `:seon.config.database.transport/maximum-input-bytes` | Authority-wide in-flight input reservation | `min(32 MiB,heap/16)` | At least `maximum-frame-bytes + 4` for one maximum frame; higher for concurrency | Below one frame: permanently paused session. Large: more admitted in-flight memory. Currently not manifest-exposed. | reconstruct |
| `:seon.config.database.transport/maximum-response-slots` | Authority-wide outstanding response slots | connections | ≥1; normally at least expected concurrent requests | Small: reads pause until slots release. Large: more concurrent retained response state. Currently not manifest-exposed. | reconstruct |
| `:seon.config.database.transport/maximum-session-response-slots` | Per-session outstanding response slots | `max(1,connections/4)` | ≥1 | Small: serializes a session. Large above authority cap has no additional effect. Currently not manifest-exposed. | reconstruct |
| `:seon.config.database.transport/maximum-output-bytes` | Authority-wide encoded-output reservation | `min(256 MiB,heap/2)` | At least the largest required response; concurrency floor **NOT GROUNDED** | Too small: response refusal/session loss. Large: more retained output memory. Currently not manifest-exposed. | reconstruct |
| `:seon.config.database.transport/maximum-session-output-bytes` | Per-session encoded-output reservation | `min(128 MiB,heap/4)` | At least the largest required response | Same failure locally to one session. Currently not manifest-exposed. | reconstruct |
| `:seon.config.database.transport/shutdown-timeout-ms` | Graceful UDS teardown budget | `5000` | ≥1; retain default unless shutdown evidence shows otherwise | Small: forced/partial shutdown evidence. Large: slower failed shutdown recovery. Currently not manifest-exposed. | reconstruct |
| `:seon.config.database.transport/codec-workers` | Transit codec worker count | `max(2,min(8,P))` | ≥1 | Small: lower throughput. Large: thread/CPU overhead. Currently not manifest-exposed. | reconstruct |
| `:seon.config.database.transport/codec-worker-queue-capacity` | Pending codec tasks | `256` | ≥1 | Small: burst rejection/connection closure. Large: more queued memory. Currently not manifest-exposed. | reconstruct |

## Executor families

Every `maximum-active`, `maximum-queued`, and `maximum-queued-by-database` value is validated only as a positive integer. One active worker prevents a zero-worker wedge; low queue values produce bounded rejection under load. These keys are enforced but currently absent from the closed manifest section. [`src/seon/db/executor.clj:451`](src/seon/db/executor.clj:451)

| Family | Exact keys | Defaults | Safe floor / failure | Apply |
|---|---|---|---|---|
| Read | `:seon.config.database.executor.read/maximum-active`; `/maximum-queued`; `/maximum-queued-by-database` | `C`; `max(16,8C)`; `min(queue,max(16,4C))` | `1/1/1` remains serially live; small values reject concurrent reads | reconstruct |
| KNN | `:seon.config.database.executor.knn/maximum-active`; `/maximum-queued`; `/maximum-queued-by-database` | `K`; `max(4,2K)`; `2` | `1/1/1`; small values reduce KNN throughput | reconstruct |
| Provider | `:seon.config.database.executor.provider/maximum-active`; `/maximum-queued`; `/maximum-queued-by-database` | `V`; `2V`; `2` | `1/1/1`; small values reduce provider throughput | reconstruct |
| Mutation | `:seon.config.database.executor.mutation/maximum-active`; `/maximum-queued`; `/maximum-queued-by-database` | `M`; `max(64,16M)`; same queue | `1/1/1` preserves serial mutation progress; small queues reject concurrent writes | reconstruct |
| Delivery | `:seon.config.database.executor.delivery/maximum-active`; `/maximum-queued`; `/maximum-queued-by-database` | `C`; `max(16,4C)`; `1` | `1/1/1`; small values delay/reject listener delivery | reconstruct |
| HNSW | `:seon.config.database.executor.hnsw/maximum-active`; `/maximum-queued`; `/maximum-queued-by-database` | `1`; `1`; `1` | Serialized by default | reconstruct |

## Database read policy

These six facts affect ordinary database operations after configuration is installed. Operation-specific smaller values win at the client; the writer still clamps everything to its fixed ceilings. [`src/seon/db.cljs:770`](src/seon/db.cljs:770), [`src/seon/db/writer.clj:871`](src/seon/db/writer.clj:871)

| Keys | Defaults | Safe guidance | Failure | Apply |
|---|---|---|---|---|
| `:seon.config.database.query/max-work`; `/max-results`; `/max-result-weight` | `100000000 / 1000000 / 3000000` | Retain defaults unless a measured query needs tighter containment | Too small: bounded query errors; too large: writer ceilings still win | fact-only |
| `:seon.config.database.pull/max-work`; `/max-results`; `/max-result-weight` | `25000000 / 1000000 / 3000000` | Retain defaults unless a measured pull needs tighter containment | Too small: bounded pull errors; too large: writer ceilings still win | fact-only |

The fixed writer ceilings are `100000000 work`, `1000000 results`, and `3000000 result-weight`. q21 requires writer result-weight of at least `60000`; exact work/result-node floors are **NOT GROUNDED**. [`src/seon/db/writer.clj:43`](src/seon/db/writer.clj:43)

## Agent-liveness policy

| Key | Effect | Default | Safe guidance / failure | Apply |
|---|---|---:|---|---|
| `:seon.config.run/batch-turn-limit` | Maximum batch turns | 100 | ≥1; very small values stop multi-turn work | fact-only |
| `:seon.config.run/stream-form-limit` | Maximum streamed forms | 300 | ≥1; very small values stop multi-form work | fact-only |
| `:seon.config.run/deadline-ms` | Outer run deadline | 1800000 ms | Keep above intended task duration; `1` makes runs unusable | fact-only |
| `:seon.config/spawn-depth-cap` | Maximum spawning caller depth | 1 | ≥0; `0` disables spawning | fact-only |
| `:seon.config.watchdog/stale-ms` | Closes runs without heartbeat progress | 1200000 ms | Keep above legitimate turn waits; too small falsely closes live work | fact-only |
| `:seon.config.breaker/crash-count` | Schedule-wake breaker threshold | 3 | Too small suppresses schedules after few crashes | fact-only |
| `:seon.config.breaker/window-ms` | Breaker observation window | 1800000 ms | Large window prolongs suppression; tiny window weakens protection | fact-only |
| `:seon.config.root/recent-limit` | Root recent-activity lookback | 12 | Display/lookback degradation only | fact-only |
| `:seon.config/reactive-settle-ms` | Ordinary reactive burst settling | 16 ms | Too small increases churn; too large delays updates | fact-only |
| `:seon.config/reactive-structural-settle-ms` | Structural-change settling | 300 ms | Same | fact-only |
| `:seon.config/reactive-max-latency-ms` | Maximum reactive delay | 500 ms | Same; exact pathological boundaries **NOT GROUNDED** | fact-only |
| `:seon.config.model-transport/response-identity-cap` | Stored response-identity evidence cap | 512 | Evidence clipping only | fact-only |
| `:seon.config.model-transport/endpoint-cap` | Stored endpoint evidence cap | 2048 | Evidence clipping only | fact-only |

## Render policy

All numeric values are integers ≥1 and are fact-only. Small values clip or reject value-browser work; large values increase context or realization cost. None is grounded as a startup-liveness dependency. [`src/seon/config.cljs:1141`](src/seon/config.cljs:1141)

| Key | Default | Effect |
|---|---:|---|
| `:seon.config.render/database-edn-cap` | 16384 chars | Stored EDN display clipping |
| `:seon.config.render/eval-cap` | 1500 chars | Echoed eval source/stdout clipping |
| `:seon.config.render/message-cap` | 4000 chars | Per-message transcript clipping |
| `:seon.config.render/result-body-cap` | 16384 chars | Citable result-body clipping |
| `:seon.config.render/value-max-depth` | 3 | Value skeleton depth |
| `:seon.config.render/value-max-keys` | 8 | Map keys per node |
| `:seon.config.render/value-max-items` | 8 | Collection items/page size |
| `:seon.config.render/value-max-path-segments` | 32 | Value-browser decoded path admission |
| `:seon.config.render/value-max-path-bytes` | 4096 | Raw encoded path admission |
| `:seon.config.render/value-max-realized-items` | 1024 | Maximum offset plus page realization |
| `:seon.config.render/value-max-string` | 80 chars | String-leaf clipping |
| `:seon.config.render/value-shape-sample` | 8 | Homogeneous-map shape sampling |
| `:seon.config.render/value-verbatim-cap` | 1500 chars | Whole-value versus skeleton cutoff |
| `:seon.config.render/value-width` | 72 | Inline/broken layout width |
| `:seon.config.render/render-fn-token-cap` | 2000 tokens | Per-render-function AI output cap |
| `:seon.config.render/whitespace` | `:raw` | `:raw` or `:visible` |
| `:seon.config.render/tabs` | `:literal` | `:literal` or `:arrow` |
| `:seon.config.render/trailing-ws` | `:off` | `:off` or `:dot` |
| `:seon.config.render/content-layout` | `:structured` | `:structured` or `:single-line` |
| `:seon.config.render/line-numbers` | `false` | Enables a one-based gutter |

Defaults and effects are owned by [`src/seon/config.cljs:1153`](src/seon/config.cljs:1153) through [`src/seon/config.cljs:1380`](src/seon/config.cljs:1380).

## Repair policy

| Key | Default | Safe range / failure | Apply |
|---|---|---|---|
| `:seon.config.repair/level` | `:symbols` | `:off`, `:safe-syntax`, `:symbols`, or `:aggressive`; stale invalid facts fall back to `:symbols` | fact-only |
| `:seon.config.repair/classes` | `{}` | Keyword→boolean overrides; wrong manifest shapes are rejected | fact-only |
| `:seon.config.repair/max-fixes-per-form` | 1 | Integer ≥1; small values stop chaining, large values spend more work | fact-only |
| `:seon.config.repair/budget-ms` | 50 ms | Integer ≥1; over budget means no repair and an ordinary error | fact-only |

[`src/seon/config.cljs:1395`](src/seon/config.cljs:1395)

## Environment-only bounds

These do not become singleton facts and do not participate in `config apply`:

| Environment key | Default | Risk |
|---|---:|---|
| `SEON_LLM_ATTEMPT_TIMEOUT_MS` | 120000 ms | `1` can time out every model attempt |
| `SEON_TURN_TIMEOUT_MS` | 900000 ms | `1` can time out every awaited turn step |
| `SEON_TEST_TIMEOUT_MS` | 15000 ms | Affects tests, not production boot |
| `SEON_TICK_MS` | caller default when absent | Positive values only; very small cadence/churn impact is **NOT GROUNDED** |
| `SEON_RENDER_STRICT` | off | When enabled, render/converter failures throw instead of degrading gracefully |

The numeric environment reader rejects nonpositive or unparseable input, but accepts `1`. [`src/seon/config.cljs:1080`](src/seon/config.cljs:1080), [`src/seon/config.cljs:1478`](src/seon/config.cljs:1478), [`src/seon/config.cljs:1505`](src/seon/config.cljs:1505)

## Current exposure gap

The following enforced keys are documented above but cannot currently be supplied through the closed `:seon.config/database` manifest section:

- `:seon.config.database.executor/maximum-queued-request-bytes`
- all 18 executor-family active/queue/per-database keys
- transport input, response-slot, output, shutdown, and codec keys

Their resolver defaults are active and their launch-envelope values are enforced, but operator override through an ordinary validated manifest is unavailable. That mismatch is grounded at [`src/seon/config/resolve.cljc:126`](src/seon/config/resolve.cljc:126) versus [`src/seon/config/resolve.cljc:209`](src/seon/config/resolve.cljc:209).