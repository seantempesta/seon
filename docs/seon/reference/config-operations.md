---
type: reference
status: active
tags: [reference, database, config]
---

# Operational configuration

## Recovery flow

Configuration is desired-state input. A selected manifest resolves into the
configuration singleton and the operational envelope before a writer starts.
Runtime code uses the database facts and resolved launch values, never the
manifest file.

1. Try a config-free reopen first. With no explicit manifest path, an existing
   database retains its configuration facts.
2. Repair drift explicitly:

   ```bash
   bin/seon config apply config/system.edn
   ```

   Fact-only changes reconcile the singleton in place. Any enforced
   operational change drains admitted work, replaces only the writer,
   reconnects the pod, proves launch equality, and reopens admission.
3. Read the steering error before retrying. Resolve-time errors name the key,
   required floor or ordering, and the operational reason.
4. Use `bin/seon cluster reset default` only when the retained database is
   intentionally disposable. Reset is not a configuration repair mechanism.

A config-free reopen is not proven to recover operational facts that were
already made invalid by older code. Apply a valid manifest through the
operator when launch equality or singleton acquisition fails.

## Writer and transport

Rows marked `enforced` reconstruct the writer when their resolved value
changes. Defaults are resolved from one hardware observation and one manifest.

| Manifest key | Effect | Default derivation | Floor or relation | Failure when mis-sized | Apply |
|---|---|---|---|---|---|
| `:seon.config.database.writer/jvm-heap-mb` | Writer JVM `-Xmx` | `clamp(system-memory-MiB / 16, 512, 4096)` | `2` MiB, the pinned JVM's proven startup floor | Below: JVM refuses startup. Above available memory: allocation failure or host pressure. The database-dependent open/replay floor is **NOT GROUNDED**. | enforced |
| `:seon.config.database.read/max-work` | Writer ceiling for Datahike read work | `100000000` | `1`; exact store/replay requirement **NOT GROUNDED** | Small values bound ordinary reads before useful completion. | enforced |
| `:seon.config.database.read/max-results` | Writer ceiling for retained result nodes | `1000000` | `1`; exact nested-result requirement **NOT GROUNDED** | Small values reject reads whose retained tree exceeds the ceiling. | enforced |
| `:seon.config.database.read/max-result-weight` | Writer ceiling for shallow result weight | `3000000` | `60000`, required by committed-program admission pages | Below: boot publication can fail even when the client requests its grounded page budget. | enforced |
| `:seon.config.database.read/deadline-ms` | Physical writer read deadline | `30000 × max(1, ceil(resolved read queue / resolved read active))` | `1`; slow-store floor **NOT GROUNDED** | Small values cancel legitimate store open/replay or reads; large values retain hostile work longer. | enforced |
| `:seon.config.database.executor/selected-processors` | Caps processors used to derive worker defaults | observed cores, capped to observed cores | `1..observed cores` | Small values reduce throughput; larger overrides are capped. | enforced |
| `:seon.config.database.executor/maximum-queued-request-bytes` | Aggregate queued request-byte admission | `clamp(heap / 16, 8 MiB, 64 MiB)` | At least `maximum-frame-bytes + 4` | Below one encoded maximum request: requests are structurally inadmissible. | enforced |
| `:seon.config.database.transport/maximum-frame-bytes` | Negotiated semantic frame ceiling | `4 MiB` | `65536`; protocol maximum `4 MiB` | Below: current boot pages can fail. The separate `4096` session-open exchange keeps its own bound. | enforced |
| `:seon.config.database.transport/maximum-connections` | Concurrent admitted UDS sessions | `min(max(64, 16C), 1024, fd-limit / 4)` | `2`, for pod plus host | `1` can boot the pod but makes normal host database access unavailable. | enforced |
| `:seon.config.database.transport/maximum-input-bytes` | Authority-wide input reservation | `min(32 MiB, heap / 16)` | At least `maximum-frame-bytes + 4` | Below: a session pauses permanently before a complete maximum frame arrives. | enforced |
| `:seon.config.database.transport/maximum-response-slots` | Authority response-slot capacity | connections | `1`; must be at least the session value | Small values serialize or pause reads. | enforced |
| `:seon.config.database.transport/maximum-session-response-slots` | Per-session response slots | `max(1, connections / 4)` | `1`; no more than authority slots | Above authority capacity is unrepresentable; small values serialize one session. | enforced |
| `:seon.config.database.transport/maximum-output-bytes` | Authority-wide encoded-output reservation | `min(256 MiB, heap / 2)` | At least `maximum-frame-bytes` and the session value | Below one frame: response refusal or session loss. | enforced |
| `:seon.config.database.transport/maximum-session-output-bytes` | Per-session output reservation | `min(128 MiB, heap / 4)` | At least `maximum-frame-bytes`; no more than authority output | Below one frame: session-local response refusal. | enforced |
| `:seon.config.database.transport/shutdown-timeout-ms` | Graceful UDS teardown budget | `5000` | `1` | Small values weaken graceful-shutdown proof; large values delay failed recovery. | enforced |
| `:seon.config.database.transport/codec-workers` | Transit codec worker count | `max(2, min(8, processors))` | `1` | Small values reduce throughput; large values add CPU/thread pressure. | enforced |
| `:seon.config.database.transport/codec-worker-queue-capacity` | Pending codec work | `256` | `1` | Small values reject bursts; large values retain more queued data. | enforced |

Here `C = max(1, selected-processors - 1)`. Result weight is Datahike's
shallow accounting measure, not encoded bytes.

## Executor families

Every key below is manifest-declarable and enforced. A floor of one preserves
serial progress; small queues trade concurrency for bounded rejection.

| Family | Exact keys | Defaults | Floor and failure |
|---|---|---|---|
| Read | `:seon.config.database.executor.read/maximum-active`, `/maximum-queued`, `/maximum-queued-by-database` | `C`, `max(16, 8C)`, `min(queue, max(16, 4C))` | `1/1/1`; small values reject concurrent reads. The resolved active and queue values derive the default read deadline. |
| KNN | `:seon.config.database.executor.knn/maximum-active`, `/maximum-queued`, `/maximum-queued-by-database` | `K`, `max(4, 2K)`, `2` | `1/1/1`; small values reduce KNN throughput. |
| Provider | `:seon.config.database.executor.provider/maximum-active`, `/maximum-queued`, `/maximum-queued-by-database` | `V`, `2V`, `2` | `1/1/1`; small values reduce provider throughput. |
| Mutation | `:seon.config.database.executor.mutation/maximum-active`, `/maximum-queued`, `/maximum-queued-by-database` | `M`, `max(64, 16M)`, same queue | `1/1/1`; small queues reject concurrent writes. |
| Delivery | `:seon.config.database.executor.delivery/maximum-active`, `/maximum-queued`, `/maximum-queued-by-database` | `C`, `max(16, 4C)`, `1` | `1/1/1`; small values delay or reject listener delivery. |
| HNSW | `:seon.config.database.executor.hnsw/maximum-active`, `/maximum-queued`, `/maximum-queued-by-database` | `1`, `1`, `1` | Serialized by default. |

`K = max(1, min(2, C / 2))`, `M = max(1, min(4,
(selected-processors + 1) / 2))`, and `V = min(6, selected-processors)`.

## Database request policy

These fact-only keys shape ordinary client requests. The writer independently
clamps them to the enforced read ceilings above.

| Keys | Defaults | Floor and failure | Apply |
|---|---|---|---|
| `:seon.config.database.query/max-work`, `/max-results`, `/max-result-weight` | `100000000`, `1000000`, `3000000` | `1`; values near one can make ordinary queries unusable. Exact useful minima are workload-dependent. | fact-only |
| `:seon.config.database.pull/max-work`, `/max-results`, `/max-result-weight` | `25000000`, `1000000`, `3000000` | `1`; values near one can make ordinary pulls unusable. | fact-only |

## Agent-liveness policy

Only structural relations are legislated. Values whose useful range depends on
the workload retain their schema floor and are documented as tuning footguns.

| Key | Default | Floor or relation | Failure and guidance | Apply |
|---|---:|---|---|---|
| `:seon.config.run/batch-turn-limit` | `100` | `1` | `1` permits one batch turn and prevents multi-turn work. Keep the shipped default unless a deliberately short run is required. | fact-only |
| `:seon.config.run/stream-form-limit` | `300` | `1` | `1` permits one streamed form. Keep the shipped default unless single-form termination is intentional. | fact-only |
| `:seon.config.run/deadline-ms` | `1800000` | At least the maximum resolved LLM attempt timeout across the process fallback, agent context, root context, and model variants | Below one attempt horizon, zero turns can complete. | fact-only |
| `:seon.config.watchdog/stale-ms` | `1200000` | Strictly greater than the resolved turn timeout | At or below the guarded horizon, a still-live turn can be closed as crashed. | fact-only |
| `:seon.config.breaker/crash-count` | `3` | `1` | `1` suppresses scheduled wakes after one crash in the window. The shipped value is the recommended general setting. | fact-only |
| `:seon.config.breaker/window-ms` | `1800000` | `1` | Very small values weaken protection; large values prolong suppression. The shipped 30-minute window is the general recommendation. | fact-only |
| `:seon.config/spawn-depth-cap` | `1` | `0` | `0` deliberately disables spawning. | fact-only |
| `:seon.config.root/recent-limit` | `12` | `1` | Small values only narrow root's recent-activity view. | fact-only |

The process fallback attempt timeout is `SEON_LLM_ATTEMPT_TIMEOUT_MS`, default
`120000`. The guarded turn timeout is `SEON_TURN_TIMEOUT_MS`, default `900000`.
Invalid or nonpositive environment values use those defaults.

## Render, reactive, model evidence, and repair

These keys are fact-only. Their numeric schemas require positive integers, but
no stronger universal floor is grounded.

| Family | Keys and defaults | Failure mode |
|---|---|---|
| Reactive | `:seon.config/reactive-settle-ms` `16`; `/reactive-structural-settle-ms` `300`; `/reactive-max-latency-ms` `500` | Small values increase churn; large values delay updates. |
| Model evidence | `:seon.config.model-transport/response-identity-cap` `512`; `/endpoint-cap` `2048` | Small values clip retained provider evidence. |
| Render text | `database-edn-cap` `16384`; `eval-cap` `1500`; `message-cap` `4000`; `result-body-cap` `16384`; `value-max-string` `80`; `value-verbatim-cap` `1500`; `value-width` `72`; `render-fn-token-cap` `2000` | Small values clip displayed or agent-visible content; large values increase rendering/context cost. |
| Render structure | `value-max-depth` `3`; `value-max-keys` `8`; `value-max-items` `8`; `value-max-path-segments` `32`; `value-max-path-bytes` `4096`; `value-max-realized-items` `1024`; `value-shape-sample` `8` | Small values narrow value navigation; large values increase realization work. |
| Render modes | `whitespace :raw`; `tabs :literal`; `trailing-ws :off`; `content-layout :structured`; `line-numbers false` | Closed enums and a boolean; these change presentation, not boot liveness. |
| Repair | `level :symbols`; `classes {}`; `max-fixes-per-form 1`; `budget-ms 50` | Small fix/budget values stop repair early; over-budget repair returns the ordinary parse error. |

## Environment-only bounds

These values are not configuration-singleton facts and do not independently
trigger `config apply` reconstruction.

| Environment key | Default | Failure mode |
|---|---:|---|
| `SEON_LLM_ATTEMPT_TIMEOUT_MS` | `120000` | Too small times out every model attempt; it also raises the run-deadline structural floor when larger. |
| `SEON_TURN_TIMEOUT_MS` | `900000` | Too small times out awaited turn steps; the watchdog must remain strictly larger. |
| `SEON_TEST_TIMEOUT_MS` | `15000` | Affects test execution only. |
| `SEON_TICK_MS` | caller default | Very small positive values increase ticker churn. |
| `SEON_RENDER_STRICT` | off | When enabled, renderer failures throw instead of degrading gracefully. |
