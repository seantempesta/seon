---
type: research
status: active
tags: [research, architecture, database]
---

# W1 operational-limits inventory

## Audit scope and conclusion

Read-only audit of `/Users/sean/src/seon` on branch `codex/runtime-reliability-refactor`. No files were modified. The checkout contained concurrent changes in `src/seon/db/transport/uds.cljc`, `src/seon/host/context.clj`, and a new host-pool test; this report describes the source as observed.

Owner ruling 7 requires every operational limit to become a named Aero key resolved into `:seon.config` database facts at boot, with hardware-derived defaults where sensible, derived agent context for actionable limits, and steering error values that name the controlling key.

The principal findings are:

1. W1 is much larger than the named seeds. Limits exist across database transport/execution, host supervision, tools, rendering, read profiles, logging, embeddings, and optional model paths.
2. Existing `seon.config` facts are structurally correct but still duplicate defaults in schemas, resolver literals, accessors, and `config/system.edn`. W1 must leave one default owner at resolution time.
3. Fresh-boot JVM configuration is circular: the writer needs heap, transport, and executor limits before the pod can commit the config singleton.
4. Several actual duplicate bugs exist, including divergent result-body limits, JVM heap defaults, repair policies, and configuration-read ceilings.
5. Per-query Datahike ceilings must remain named operation-specific proofs. Replacing them all with the generous writer maximum would weaken containment.

“Hardware” below means the default should derive from cores, available memory, heap, file-descriptor budget, or another resolved limit. “Agent” means the effective limit belongs in the derived limits context.

## Existing config facts that W1 should retain

These names already follow the intended idiom. W1 should remove their source-level fallback literals and require a complete resolved singleton.

| Current location | Current value | Config key | Hardware? | Agent? | Required behavior |
|---|---:|---|---|---|---|
| `config.cljs:438`, `config/system.edn:56-61` | 100 batch turns | `:seon.config.run/batch-turn-limit` | No | Yes | Closing a run should cite the key. |
| `config.cljs:439`, `config/system.edn:58-59` | 300 stream forms | `:seon.config.run/stream-form-limit` | No | Yes | Same. |
| `config.cljs:440`, `config/system.edn:60-61` | 1,800,000 ms | `:seon.config.run/deadline-ms` | No | Yes | Deadline close cites the key. |
| `config.cljs:824-826`, `config/system.edn:74-79` | 16 / 300 / 500 ms | `:seon.config/reactive-settle-ms`, `:seon.config/reactive-structural-settle-ms`, `:seon.config/reactive-max-latency-ms` | No | No | Internal scheduling policy. |
| `config.cljs:828-838`, `config/system.edn:92-103` | Query `100m/1m/3m`; pull `25m/1m/3m` | `:seon.config.database.query/max-*`, `:seon.config.database.pull/max-*` | Result weight preferably memory-derived | Yes for agent DB calls | Resource errors name the exact key/value. |
| `config.cljs:875-892`, `config/system.edn:125-145` | EDN 16384; eval 1500; message 4000; result 16384; depth 3; keys/items 8; path segments 32; path bytes 4096; realized 1024; string 80; shape 8; verbatim 1500; width 72; render fn 2000 tokens | Existing `:seon.config.render/*` leaves | Mostly No | Yes | Loud clipping; path/realization admission rejects with the key. |
| `config.cljs:901-902` | 1 fix, 50 ms | `:seon.config.repair/max-fixes-per-form`, `:seon.config.repair/budget-ms` | No | Yes | Exhaustion/skip evidence names the key. |
| `config.cljs:873-874`, `config/system.edn:482` | depth 1 | `:seon.config/spawn-depth-cap` | No | Yes | Spawn refusal names the key. |
| `config.cljs:912-919`, `config/system.edn:483-485` | 1,200,000 ms; 3 crashes/1,800,000 ms; recent 12 | `:seon.config.watchdog/stale-ms`, `:seon.config.breaker/crash-count`, `:seon.config.breaker/window-ms`, `:seon.config.root/recent-limit` | No | Yes | Schedule refusal names the breaker keys. |
| `config.cljs:125-134`, `config/system.edn:110-113` | identity 512; endpoint 2048 | `:seon.config.model-transport/response-identity-cap`, `:seon.config.model-transport/endpoint-cap` | No | No | Bounded evidence, not request rejection. |

Source fallbacks that must disappear once the singleton is complete include `config.cljs:1104-1285`, `1373-1389`, `1501-1551`, and the default maps at `822-838`. Tests should inject explicit configuration rather than depend on hidden accessor defaults.

## Database writer, executor, and transport

### Writer read and executor policy

| Current location | Current value or derivation | Proposed key | Hardware? | Agent? | Rejection behavior |
|---|---|---|---|---|---|
| `db/writer.clj:43-61` | `max-work 100000000` | `:seon.config.database.read/max-work` | No | Yes | Existing Datahike resource error names key/value. |
| `db/writer.clj:43-61` | `max-results 1000000` | `:seon.config.database.read/max-results` | No | Yes | Same. |
| `db/writer.clj:43-61` | `max-result-weight 3000000` | `:seon.config.database.read/max-result-weight` | Prefer heap-derived | Yes | Same. |
| `db/writer.clj:43-61,3991-4006` | `30000 * ceil(read-queue/read-active)` ms | `:seon.config.database.read/deadline-ms` | CPU-derived | Yes | Deadline error names key and resolved milliseconds. |
| `db/executor.clj:123-124` | processors floor 1; CPU workers `max(1, processors-1)` | `:seon.config.database.executor/selected-processors`, `/cpu-workers` | Yes | No | Invalid configuration fails boot. |
| `db/executor.clj:125,144-146` | KNN active `max(1,min(2,cpu-workers/2))`; queue `max(4,2*active)`; per-db 2 | `:seon.config.database.executor.knn/maximum-active`, `/maximum-queued`, `/maximum-queued-by-database` | Yes | Indirectly | Capacity error names the predicate-specific key. |
| `db/executor.clj:126,131,150-152` | Mutation active `max(1,min(4,(processors+1)/2))`; queue/per-db `max(64,16*active)` | `:seon.config.database.executor.mutation/maximum-*` | Yes | Indirectly | Same. |
| `db/executor.clj:127,147-149` | Provider active `min(6,processors)`; queue `2*active`; per-db 2 | `:seon.config.database.executor.provider/maximum-*` | Yes | Indirectly | Same. |
| `db/executor.clj:128-130,141-143` | Read active CPU workers; queue `max(16,8*workers)`; per-db `min(queue,max(16,4*workers))` | `:seon.config.database.executor.read/maximum-*` | Yes | Yes when rejecting DB work | Same. |
| `db/executor.clj:153-155` | Delivery active CPU workers; queue `max(16,4*workers)`; per-db 1 | `:seon.config.database.executor.delivery/maximum-*` | Yes | Indirectly | Same. |
| `db/executor.clj:156-158` | HNSW active/queue/per-db all 1 | `:seon.config.database.executor.hnsw/maximum-*` | No; serialization policy | No | Same. |
| `db/executor.clj:135` | frame bytes plus 4-byte header | `:seon.config.database.executor/maximum-request-bytes` | Derived from frame fact | Yes | Oversize request names key. |
| `db/executor.clj:136-139` | total queued bytes 8/16/32 MiB for ≤2/≤4/>4 processors | `:seon.config.database.executor/maximum-queued-request-bytes` | CPU and heap | Indirectly | Busy error names key. |
| `db/executor.clj:783-794` | shutdown wait 5 seconds | `:seon.config.database.executor/shutdown-timeout-ms` | No | No | Operator diagnostics cite key. |

`db/executor.clj:496-550` currently collapses capacity, fenced, and stopped states into one generic failure. W1 must identify the particular active, queue, per-database, request-byte, or aggregate-byte key that rejected the work. Fenced and stopped are state failures, not cap failures.

`read-defaults` has a separate bug: it computes its queue-wave deadline from a fresh default `executor/capacity`, while `writer/start!` may start a differently configured capacity. Both must derive from the same immutable resolved capacity.

### Database protocol and UDS transport

| Current location | Current value | Proposed key | Hardware? | Agent? | Rejection behavior |
|---|---:|---|---|---|---|
| `db/protocol.cljc:100-102`; `db/transport/uds.cljc:166`; `uds.cljs:19` | 4 MiB payload | `:seon.config.database.transport/maximum-frame-bytes` | Prefer heap-derived with protocol ceiling | Yes | Both peers reject oversize frames naming the key. |
| `db/protocol.cljc:306-310` | index page max 200 datoms | `:seon.config.database.index-page/maximum-datoms` | No | Yes | Reject with paging guidance and key. |
| `db/protocol.cljc:357-358` | 64 interest patterns | `:seon.config.database.interest/maximum-patterns` | No | Yes | Reject naming key. |
| `db/protocol.cljc:596` | 64 execute-many members | `:seon.config.database.execute-many/maximum-members` | Prefer queue/CPU-derived | Yes | Advise splitting the request. |
| `db/protocol.cljc:1225-1234` | grouped-result weight defaults to frame cap | `:seon.config.database.execute-many/max-result-weight` | Derived | Yes | Resource error names key. |
| `db/transport/uds.cljc:167,294-311` | codec queue 256; workers `max(2,min(8,processors))` | `:seon.config.database.transport/codec-worker-queue-capacity`, `/codec-workers` | Yes | No | Authority-full evidence names key. |
| `db/transport/uds.cljc:168` | shutdown 5,000 ms | `:seon.config.database.transport/shutdown-timeout-ms` | No | No | Operator evidence names key. |
| `db/transport/uds.cljc:169` | aggregate input 32 MiB | `:seon.config.database.transport/maximum-input-bytes` | Heap-derived | Indirectly | Pause/refusal evidence names key. |
| `db/transport/uds.cljc:170-171` | response slots 256 authority / 64 session | `:seon.config.database.transport/maximum-response-slots`, `/maximum-session-response-slots` | Connections/heap-derived | Indirectly | `authority-full`/`session-full` names exact key. |
| `db/transport/uds.cljc:172-173` | output 256 MiB authority / 128 MiB session | `:seon.config.database.transport/maximum-output-bytes`, `/maximum-session-output-bytes` | Heap-derived | Indirectly | Same. |
| `db/transport/uds.cljc:174` | 256 connections | `:seon.config.database.transport/maximum-connections` | FD, CPU, and heap-derived | No standing context | Refusal/operator evidence names key. |
| `db/transport/uds.cljc:313-328` | cleanup workers 2; queue = connection cap | `:seon.config.database.transport/cleanup-workers`, `/cleanup-worker-queue-capacity` | Yes | No | Internal capacity evidence names key. |
| `db/transport/uds.cljs:20` | 256 pending requests | `:seon.config.database.session/maximum-pending-requests` | Writer/session-derived | Yes | Busy error names key. |
| `db/transport/uds.cljs:21` | queued request bytes `2*frame` | `:seon.config.database.session/maximum-queued-bytes` | Derived | Yes | Overflow names key. |
| `db/transport/uds.cljs:22-24` | events 64; event bytes `2*frame` | `:seon.config.database.session/maximum-pending-events`, `/maximum-queued-event-bytes` | Partly derived | Yes for listeners | Overflow names exact key. |
| `db/transport/uds.cljs:25` | deadline tick 250 ms | `:seon.config.database.session/deadline-tick-ms` | No | No | Timeout reports the caller’s deadline key, not this tick. |

### Other writer limits

| Current location | Current value | Proposed key | Hardware? | Agent? | Behavior |
|---|---:|---|---|---|---|
| `db/writer.clj:157` | schema reference closure 64 | `:seon.config.database.schema/maximum-reference-count` | No | Yes | User-input rejection names key. |
| `db/writer.clj:571-573` | restore-admin error 4096 chars | `:seon.config.database.restore-admin/error-character-cap` | No | No | Loud truncation metadata. |
| `db/server.clj:63`, `db/protocol.cljc:418-419` | stop error 4096 chars | `:seon.config.database.terminal/stop-error-character-cap` | No | No | One producer/validator owner. |
| `db/server.clj:64,253-262` | admin input 1 MiB | `:seon.config.database.restore-admin/maximum-input-bytes` | No | Operator only | Reject before EDN parse naming key. |
| `db/server.clj:460-468` | startup wait 300,000 ms | `:seon.config.database/startup-timeout-ms` | No | No | Operator failure names key. |
| `db/writer.clj:921-930` | read-spend LRU 256 identities | `:seon.config.database.read-spend/maximum-identities` | Heap-derived | No | Eviction evidence only. |
| `db/writer.clj:1258-1269` | embedding backfill batch 256 | `:seon.config.database.embedding/backfill-batch-size` | Provider/executor-derived | No | Internal batching. |
| `db/writer.clj:1526` | initialization attempts 3 | `:seon.config.database.initialization/maximum-attempts` | No | No | Final error names key. |
| `db/writer.clj:2306-2307` | committed-report capacity 256; batch 32 | `:seon.config.database.committed-report/capacity`, `/batch-size` | CPU/heap-derived | No | Feed diagnostics cite key. |
| `db/writer.clj:3936-3937` | KNN reservation 64 KiB | `:seon.config.database.knn/reserved-request-bytes` | Queue/heap-derived | No | Rejection cites reservation and aggregate-byte keys. |
| `db/restore.cljc:219-240` | three reads: work 100k; results 1/32/1; weight 64 KiB | `:seon.config.database.restore.acquire/max-work`, `/completion-max-results`, `/publication-max-results`, `/generator-policy-max-results`, `/max-result-weight` | No | No | Acquisition failure names the member key. |
| `db/id.cljc:1044`, `db/id/schema.cljc:84` | collision attempts 16 | `:seon.config.database.id/maximum-attempts` | No | Yes on exhaustion | Return error value naming key. |
| `db/internal.cljs:331-334` | diagnostic preview 25 tokens | `:seon.config.database.transaction/error-preview-token-cap` | No | Yes | Loud clipping. |

## Execution, JVM host, and supervision

| Current location | Current value | Proposed key | Hardware? | Agent? | Behavior |
|---|---:|---|---|---|---|
| `execution.cljs:24`; duplicate `host.clj:89` | invocation deadline 600,000 ms | `:seon.config.execution/invocation-deadline-ms` | No | Yes | Timeout names key. |
| `execution.cljs:25-28` | result limit `frame-64KiB` | `:seon.config.execution/result-limit-bytes` | Derived from frame | Yes | `bounded-result` names key/observed bytes. |
| `execution.cljs:332-333` | program 16,384 results / 3 MiB | `:seon.config.execution.program/max-results`, `/max-result-weight` | Weight memory-derived | Indirectly | Acquisition error names key. |
| `execution.cljs:404-406` | config pull 100k/256/64 KiB | `:seon.config.execution.configuration/max-work`, `/max-results`, `/max-result-weight` | No | No | Core acquisition error names key. |
| `host.clj:177,1074-1076` | eval pool 10; watchdog pool 2 | `:seon.config.execution.host/eval-threads`, `/watchdog-threads` | Yes | Eval pool indirectly | Bounded admission failure names key. |
| `host.clj:881-884` | post-cancel wait 2,000 ms | `:seon.config.execution.host/cancel-wait-ms` | No | Yes | Retirement evidence names key. |
| `execution/host.cljs:32` | ready timeout 10,000 ms | `:seon.config.execution.host/ready-timeout-ms` | No | Yes | Host-unavailable error names key. |
| `execution/host.cljs:33` | Bun child idle timeout 300,000 ms | `:seon.config.execution.child/idle-timeout-ms` | No | Yes; live-value lifetime | Retirement evidence cites key. |
| `execution/host.cljs:34`, `subprocess.cljs:9` | cancel/kill grace 1,000 ms | `:seon.config.subprocess/kill-grace-ms` | No | No | One subprocess owner. |
| `execution/host.cljs:35` | stdout/stderr tail 16 KiB | `:seon.config.execution.host/diagnostic-tail-character-cap` | No | No | Truncation metadata. |
| `render/value.cljc:320-325` | live values 200; nodes 4096; weight 256 KiB | `:seon.config.eval.retained/value-cap`, `/node-cap`, `/weight-cap` | Memory-derived | Yes | Retention rejection and eviction cite key. |
| `render/value.cljc:954-957` | unknown-size probe 1001 items | `:seon.config.render/value-size-probe-items` | Memory-derived | Yes | Report unknown size; no rejection. |
| `host/record.clj:336-347` | result EDN 8192 chars | Reuse `:seon.config.render/result-body-cap` | No | Yes | Remove tier divergence. |
| `host/context.clj:189-199` | writer pool `max(1,cores-1)`; wait 1000 ms; call 120000 ms; conflict backoff 10 ms | `:seon.config.execution.host.writer/pool-size`, `/pool-wait-timeout-ms`, `/call-deadline-ms`, `/request-conflict-backoff-ms` | Pool yes | Yes on capacity/deadline | Pool/recovery errors name exact key. |
| `host/context.clj:1485-1514` | program projection 4096 rows; 1m work; 3 MiB/member; 6 MiB group | `:seon.config.execution.host.program/max-rows`, `/max-work`, `/max-result-weight`, `/execute-many-max-result-weight` | Weights memory-derived | No | Refresh fault names key. |
| `host/context.clj:1634-1707` | eval-ID attempts 16 | `:seon.config.execution.host/eval-id-allocation-attempts` | No | Yes on exhaustion | Error names key. |
| `subprocess.cljs:133` | omitted output limit becomes `Number.MAX_SAFE_INTEGER` | `:seon.config.subprocess/max-output-bytes` | Memory-derived | Indirectly | This must become finite; overflow returns a value naming key. |
| `runtime/admission.cljs:221-239` | 1m work; 4096 results; 3 MiB member; 6 MiB aggregate | `:seon.config.database.runtime-admission/max-work`, `/max-results`, `/max-result-weight`, `/aggregate-max-result-weight` | Weights memory-derived | No | Readiness failure names key. |

## Run, retry, lifecycle, and runtime timing

| Current location | Current value | Proposed key | Hardware? | Agent? | Behavior |
|---|---:|---|---|---|---|
| `agent/loop.cljs:102-111` | no-form streak 3 | `:seon.config.run/no-forms-streak-limit` | No | Yes | Run close names key. |
| `config.cljs:1485-1494`; consumers `agent/loop.cljs:289-310`, `client.cljs:2414-2417` | turn/quiescence timeout 900,000 ms | `:seon.config.run/turn-timeout-ms` | No | Yes | Replace `SEON_TURN_TIMEOUT_MS` prose with fact name. |
| `eval.cljs:124-127` | default form await 10,000 ms | `:seon.config.eval/default-timeout-ms` | No | Yes | Timeout value names key; explicit budget may narrow/extend within policy. |
| `agent/turn.cljs:711-716` | retry base 500; factor 2; jitter .5; max delay 20,000; cumulative 60,000; retries 4 | `:seon.config.ai.retry/base-delay-ms`, `/factor`, `/jitter`, `/max-delay-ms`, `/total-cap-ms`, `/max-retries` | No | Yes | Exhausted attempt evidence names relevant keys. |
| `config.cljs:1474-1483`; duplicate `agent/turn.cljs:847-850` | LLM attempt 120,000 ms | `:seon.config.ai/attempt-timeout-ms` | No | Yes | Remove duplicate fallback; timeout names key. |
| `agent/loop.cljs:1194-1198` | tick 30,000 ms | `:seon.config.agent/tick-ms` | No | No | Internal. |
| `agent/schedule.cljs:191-194` | cron scan `366*24*60` minutes | `:seon.config.schedule/scan-limit-minutes` | No | Yes | Cap exhaustion must be distinguishable from “no next time.” |
| `agent.cljs:988` | spawn stale retries 32 | `:seon.config.agent.spawn/max-stale-attempts` | No | No | Exhaustion names key. |
| `agent/lifecycle.cljs:64` | lifecycle stale retries 32 | `:seon.config.agent.lifecycle/max-stale-attempts` | No | No | Same. |
| `state.cljs:67` | reconcile attempts 3 | `:seon.config.state/max-reconcile-attempts` | No | No | Same. |
| `warn.cljs:564-573` | same-pair message hops 4 | `:seon.config.agent.message/hop-cap` | No | Yes | Message refusal names key. |
| `warn.cljs:562` | slow eval warning 500 ms | `:seon.config.eval/slow-warning-ms` | No | Yes | Warning only. |
| `client.cljs:547-550` | process heartbeat 60,000 ms | `:seon.config.runtime/heartbeat-interval-ms` | No | No | Internal. |
| `web/datastar.cljs:338-345` | SSE heartbeat 15,000 ms | `:seon.config.web.feed/heartbeat-interval-ms` | No | No | Internal. |
| `client.cljs:2409-2412` | quiescence poll 10 ms | `:seon.config.runtime/quiescence-poll-ms` | No | No | Internal. |

## Agent capabilities and persisted projections

| Current location | Current value | Proposed key | Hardware? | Agent? | Behavior |
|---|---:|---|---|---|---|
| `agent/fs.cljs:612-615` | file view default 100 lines | `:seon.config.agent.fs/default-view-lines` | No | Yes | Default only. |
| `agent/fs/internal.cljs:147-153` | edit context ±3 lines / 200 tokens | `:seon.config.agent.fs/edit-context-lines`, `/edit-context-token-cap` | No | Yes | Loud clipping. |
| `agent/fs.cljs`, `agent/fs/internal.cljs:123-141`; `agent/ctx.cljs:133-145`; host wrapper `host/context.clj:1035-1041` | no finite walk-result, file-line, or file-byte ceiling; full slurp | `:seon.config.agent.fs/max-results`, `/max-view-lines`, `/max-file-bytes` | Memory-derived | Yes | Reject before slurp, name key, steer to paging/narrowing. |
| `agent/search/internal.cljs:20-42` | 10,000 ms; 8 MiB output; 32-token preview; 12 file rows | `:seon.config.agent.search/timeout-ms`, `/max-output-bytes`, `/preview-token-cap`, `/default-max-results` | Output memory-derived | Yes | Timeout/truncation names keys. |
| `agent/search.cljs:25` | context lines max 10 | `:seon.config.agent.search/context-lines-cap` | No | Yes | Reject above cap as a value. |
| `agent/shell/internal.cljs:21-32` | 30,000 ms; 2,000,000 bytes | `:seon.config.agent.shell/default-timeout-ms`, `/max-output-bytes` | Bytes memory-derived | Yes | Child stop/overflow names key. |
| `agent/shell/internal.cljs:151-161` | background stream 2,000,000 bytes; 32 exited jobs | `:seon.config.agent.shell/background-max-stream-bytes`, `/max-exited-jobs` | Memory-derived | Yes | Loud truncation/pruning. |
| `agent/web/internal.cljs:22-32` | 30,000 ms; body 2,000,000 bytes; preview 2000 tokens; redirects 5; links 25; search 10/default and 20/max; DOM thresholds 1m chars/3000 depth | `:seon.config.agent.web/default-timeout-ms`, `/default-max-bytes`, `/preview-token-cap`, `/redirect-cap`, `/links-cap`, `/default-search-results`, `/search-results-cap`, `/html-parse-max-characters`, `/html-parse-max-depth` | Byte/DOM limits memory-derived | Yes | Caller-controlled overages reject naming key; display caps clip loudly. |
| `my/blob.cljs:206-209` | default page 100 lines | `:seon.config.blob/default-text-lines` plus `/max-text-lines` | No | Yes | Hard overage rejects with paging guidance. |
| `my/blob/schema.cljc:24` | error max 1024 chars | `:seon.config.blob/error-max-characters` | No | Yes | Producer clips to schema-valid value. |
| `my/kb.cljc:67,374` | recall default 10 / hard 50 | `:seon.config.kb/default-recall-limit`, `/recall-limit-cap` | No | Yes | Replace Malli throw with steering error. |
| `agent/debug.cljs:313,338-340` | errors default 20 / max 200 | `:seon.config.agent.debug/default-errors-limit`, `/errors-limit-cap` | No | Yes | Above cap returns error value. |
| `agent/message.cljs:68` | recent messages max 200 | `:seon.config.agent.message/recent-limit-cap` | No | Yes | Above cap returns error value. |
| `agent/message/internal.cljs:57-58` | participant 64; human barrier 65,536 | `:seon.config.database.agent-message/participant-max-results`, `/human-message-max-results` | Prefer DB/memory-derived | No | Internal read failure names key. |
| `agent/message/internal.cljs:12` | title about 80 chars | `:seon.config.render.message/title-max-characters` | No | Yes | Loud ellipsis. |
| `error.cljs:237-240,344-346` | 20 frame entities; 32 pending errors | `:seon.config.error/max-frames`, `/pending-cap` | Memory-derived | No | Persist truncation/drop evidence. |
| `error.cljs:104-115,176-185,248-254` | ex-data/cause depths 5/6 | `:seon.config.error/ex-data-depth`, `/cause-depth` | No | No | Diagnostic truncation. |
| `runtime/recovery.cljs:34,45-46,244` | tail 2048 chars | `:seon.config.runtime.recovery/tail-max-characters` | No | No | One schema/producer owner. |
| `agent/turn.cljs:262` | persisted turn error 4096 chars | `:seon.config.agent.turn/error-max-characters` | No | Yes | Loud truncation. |
| `log.cljs:313-321` | log file 5 MiB, rotations 3 | `:seon.config.log/file-max-bytes`, `/rotation-count` | Storage-derived | No | Replace the separate `!config` policy owner. |

## Render, context, and UI limits

| Current location | Current value | Proposed key | Hardware? | Agent? | Behavior |
|---|---:|---|---|---|---|
| `agent/ctx.cljs:1237-1242` | referenced schemas 40 | `:seon.config.render.namespace/referenced-schema-cap` | No | Yes | Loud cap footer names key. |
| `agent/ctx/subagents.cljs:24-29` | 20 children; 800 tokens | `:seon.config.render.subagents/max-children`, `/token-cap` | No | Yes | Loud footer. |
| `agent/ctx/transcript.cljs:61-75` | decay 0→4096, 2→1024, 5→512; retained 25; window 50; eviction 25; settled 8192; window schema max 200 | `:seon.config.render.transcript/result-decay`, `/turns-retained`, `/turn-window-size`, `/turn-eviction-size`, `/settled-token-cap`, `/turn-window-cap` | No | Yes | Move block schema defaults into cluster facts. |
| `agent/ctx/transcript.cljs:441-444` | coalesce after 3 identical errors | `:seon.config.render.transcript/coalesce-min-run` | No | Yes | Render policy only. |
| `agent/ctx/transcript.cljs:823,900-901` | eval DB page 4; turn scan 64 × max 4 pages | `:seon.config.database.transcript/eval-page-size`, `/turn-scan-page-size`, `/max-turn-scan-pages` | DB/memory-derived | No | Honest incomplete-projection evidence. |
| `my/plan/internal.cljc:1257-1268` | frontier 7; recent done 5 | `:seon.config.render.plan/frontier-limit`, `/recent-done-limit` | No | Yes | Overflow summary names key. |
| `agent/ctx/typeahead_steps.cljs:453-455` | history 12 | `:seon.config.render.typeahead/history-cap` | No | Yes | Loud truncation. |
| `agent/ctx/menu.cljs:96-119,181-184` | margin 3.0; gate .9; probe 3; menu 8; toolkit 4; rounds 8; eval scans 30/200 | `:seon.config.typeahead/*` leaves | No, except scan cost | Yes | Move the second `:seon.typeahead/id "policy"` owner into config facts. |
| `ai/typeahead.cljs:251-274,299,325` | preview 160/600 chars; spans 64; offers 10; holes 16 | `:seon.config.typeahead/draft-preview-characters`, `/buffer-preview-characters`, `/buffer-span-cap`, `/offer-cap`, `/hole-cap` | Projection caps memory-derived | Yes | Loud truncation. |
| `ai/typeahead.cljs:644-651` | plan-pass document 190 tokens | `:seon.config.typeahead/plan-pass-doc-token-cap` | No | Yes | Skip evidence names key. |
| `ai/generate_code.cljs:116-122` | embedding hits 48; namespaces 16 | `:seon.config.render.namespace/ranked-function-hit-count`, `/ranked-namespace-limit` | Query count possibly derived | Yes | Loud omission evidence. |
| `handlers/eval.cljs:39-42` | source/result/error/activity 200/20/30/40 tokens | `:seon.config.render.eval/source-token-cap`, `/result-summary-token-cap`, `/error-summary-token-cap`, `/activity-label-token-cap` | No | Yes | Loud clipping. |
| `handlers/fn.cljs:18`, `handlers/test.cljs:17` | source inline threshold 200 chars twice | `:seon.config.render.source/inline-threshold` | No | Yes | One shared accessor. |
| `agent/ctx/canvas.cljs:360` | hardcoded 2000-token AI twin | Reuse `:seon.config.render/render-fn-token-cap` | No | Yes | Remove bypass of existing accessor. |
| `test/runner.cljs:613-616` | failure summary 50 tokens | `:seon.config.render.test/failure-summary-token-cap` | No | Yes | Loud clipping. |
| `web/serve.cljs:223-224` | value query 32768 bytes; path 8192 bytes | `:seon.config.render.value/query-framing-max-bytes`, `/path-framing-max-bytes` | Memory-derived | Yes | HTTP steering error names exact key. |
| `repair/candidates.cljc:33-35` | candidates 5 | `:seon.config.repair/candidate-cap` | No | Yes | Suggestion truncation. |
| `schema.cljc:717-723` | schema candidates/input keys 32/32 | `:seon.config.schema/shape-candidate-cap`, `/shape-input-key-cap` | Diagnostic-cost derived | Yes | Bounded diagnostic identifies key. |

## Optional worker, embeddings, and model limits

| Current location | Current value | Proposed key | Hardware? | Agent? | Behavior |
|---|---:|---|---|---|---|
| `worker_eval.cljs:90-94` | worker eval 1000 ms | `:seon.config.worker.eval/budget-ms` | No | Yes when enabled | Timeout names key. |
| `worker_eval.cljs:484-491` | repair 10 ms / 3 fixes | Reuse existing repair facts | No | Yes | Remove conflict with pod’s 50 ms / 1 fix. |
| `ai/diffusiongemma.cljs:174-186` | denoise 48; polls 3000/250 ms; max 200 | `:seon.config.ai.diffusion/max-denoising-steps`, `/poll-ms`, `/local-poll-ms`, `/max-polls` | No | Yes when enabled | Exhaustion names key. |
| `embed.clj:122-125` | HNSW capacity 10,000 | `:seon.config.embed/index-capacity` | Memory-derived | No | Index-capacity failure names key. |
| `embed.clj:574-588` | text 8000 tokens; batch 18000 tokens / 100 texts | `:seon.config.embed/max-text-tokens`, `/max-batch-tokens`, `/max-batch-texts` | Provider constraint, not hardware | Yes indirectly | Input truncation/batching evidence names key. |
| `embed.clj:611-627` | retries 5; base 500 ms; duration 60,000 ms | `:seon.config.embed.retry/max-retries`, `/base-delay-ms`, `/total-cap-ms` | No | No | Exhaustion diagnostics name keys. |
| `embed.clj:1086-1091,1173-1178` | backfill 256; drain passes 256 | `:seon.config.embed/backfill-cap`, `/drain-pass-cap` | Executor/provider-derived | No | Internal batching/exhaustion. |

## Call-specific database-read profiles

These are operational limits and W1 inputs. They should use the dependency’s exact terms: `max-work`, `max-results`, and `max-result-weight`.

A suitable flattened naming form is:

```clojure
:seon.config.database.<consumer>.<read-name>/max-work
:seon.config.database.<consumer>.<read-name>/max-results
:seon.config.database.<consumer>.<read-name>/max-result-weight
```

Identical, intentionally shared profiles may have one named policy map. Different query shapes must not be collapsed merely because literals happen to match.

| Current locations | Current ceiling inventory | Proposed family |
|---|---|---|
| `my/plan.cljc:490-503,548,578-600,626-665,701,726-772,1685-1714,1847-1848` | Work 100k/250k/1m/5m; results 8/256/1000/4096/10000/200000; weights 1 KiB/4 KiB/128 KiB/256 KiB/1 MiB/2 MiB | `:seon.config.database.plan.<read>/max-*` |
| `my/plan/internal.cljc:1409-1451,1536,1585,1624` | Work 10k/100k/5m; results 8/2048/200000; weights 1 KiB/64 KiB/256 KiB/384 KiB/1 MiB | `:seon.config.database.plan-internal.<read>/max-*` |
| `execution.cljs:392-406,578,625-631,713-722` | Program 16384/3 MiB; config 100k/256/64 KiB; aggregate 1/3 MiB plus 64 KiB reserve | `:seon.config.database.execution.<read>/max-*` |
| `execution/runtime.cljs:215-249,288-289,376-402,460-464,615-621` | Work 10k/100k/1m/5m; results 8/256/4096/65536; weights 1 KiB/4 KiB/64 KiB/1 MiB/3 MiB/3.5 MiB/8 MiB | `:seon.config.database.execution-runtime.<read>/max-*` |
| `eval.cljs:3506-3507,3544,3754` | 2048 results/1 MiB; aggregate 2 MiB | `:seon.config.database.eval.<read>/max-*` |
| `agent.cljs:568-569,714-755,939-973,1284-1330` | Creation 4096/1 MiB; small reads 64 or 4096 results and 4/256 KiB | `:seon.config.database.agent.<read>/max-*` |
| `agent/loop.cljs:177-188,225,656-658` | Work 100k/500k; results 64/4096/65536; weights 4/64/256/512 KiB | `:seon.config.database.agent-loop.<read>/max-*` |
| `agent/lifecycle.cljs:164-165`; `agent/schedule.cljs:361` | Lifecycle 10000/512 KiB; schedule 1 MiB | `:seon.config.database.agent-lifecycle/*`, `:seon.config.database.schedule/*` |
| `agent/ctx.cljs:1658-1702` | Work 20k/100k; results 2048/4096; weight 256 KiB | `:seon.config.database.context.<read>/max-*` |
| `agent/ctx/menu.cljs:254-256,381-441` | Work 10k/1m; results 32/16384/32768; weights 4 KiB/1 MiB; aggregates about 1.25/3.06 MiB | `:seon.config.database.typeahead-menu.<read>/max-*` |
| `agent/ctx/canvas.cljs:56-67,139,152` | Work 2m/4m; results 32768/65536; weights 1 MiB; aggregate 1.125 MiB | `:seon.config.database.canvas.<read>/max-*` |
| `agent/ctx/namespaces.cljs:254-306,354,376-378,417,457-459` | Work 100k/500k/1m/2m; results 64/256/512/2048/8192/32768/50000; weights 4 KiB–3 MiB | `:seon.config.database.namespaces.<read>/max-*` |
| `agent/ctx/transcript.cljs:733-744,839-999,1097-1167` | Work commonly 100k/500k/1m; results 32/4096/32768/500000/1m; weights 4 KiB–576 KiB | `:seon.config.database.transcript.<read>/max-*` |
| `agent/ctx/subagents.cljs:158-173,255-289,353` | Work 10k/2m; results 1/8/4096; weights 1 KiB–3.5 MiB | `:seon.config.database.subagents.<read>/max-*` |
| `agent/ctx/warnings.cljs:128-157,251-258,303,353-355` | Work 100k–5m; results 64–65536; weights 4 KiB–4 MiB | `:seon.config.database.warnings.<read>/max-*` |
| `agent/ctx/typeahead_steps.cljs:68-77,152-206` | Work 10k/1m; results 8/64; weights 1 KiB–1 MiB | `:seon.config.database.typeahead-steps.<read>/max-*` |
| `agent/message.cljs:96-189`, `agent/message/internal.cljs:57-76,111,168-169` | Results 64/65536; weights 64–512 KiB | `:seon.config.database.agent-message.<read>/max-*` |
| `ai.cljs:470-472,984-986` | Config read 100k/256/1 MiB | `:seon.config.database.ai-configuration/max-*` |
| `ai/generate_code.cljs:213-215` | 100k/2048/256 KiB | `:seon.config.database.generate-code/max-*` |
| `runtime/admission.cljs:221-239` | 1m/4096/3 MiB; aggregate 6 MiB | `:seon.config.database.runtime-admission/max-*` |
| `web/brand.cljs:208-210`; `web/serve.cljs:1861-1862` | Brand 10k/1/64 KiB; data 100000 results/4 MiB | `:seon.config.database.web-brand/max-*`, `:seon.config.database.web-data/max-*` |
| `host/context.clj:1495-1514` | 1m work; 4097 results; 3 MiB/member; 6 MiB aggregate | `:seon.config.database.execution-host-program/max-*` |

All internal acquisition failures should name the effective profile key. Only profiles an agent can directly exceed need proactive context rendering.

## Already accepted proposed names

These should land exactly as accepted.

From `research/error-quality-u6-w3-design-2026-07-21.md` open decision 3:

- `:seon.config.instrument/enabled?`
- `:seon.config.render/error-head-token-cap` — proposed default 120 tokens.

From `research/w6-package-host-design-2026-07-21.md` §1.4:

- `:seon.config.packages/policy`
- `:seon.config.packages/allowlist`
- `:seon.config.packages/trusted-lifecycle-scripts`
- `:seon.config.packages/install-deadline-ms`
- `:seon.config.packages/max-rows`
- `:seon.config.packages.host/sessions`
- `:seon.config.packages.host/call-deadline-ms`
- `:seon.config.packages.host/ready-timeout-ms`
- `:seon.config.packages.host/respawn-backoff-ms`
- `:seon.config.packages.host/swap-queue-deadline-ms`
- `:seon.config.packages.host/jvm-heap-mb`
- `:seon.config.handle/per-channel-cap`
- `:seon.config.handle/summary-token-cap`

Package-policy, row-cap, install, swap, call, and handle-GC rejections must name their key.

## Operator and launcher limits

| Current location | Current value | Proposed key | Hardware? | Agent? | Behavior |
|---|---:|---|---|---|---|
| `script/seon/dev/config.clj:44-50`; `process.clj:552-555`; `artifact.clj:803-810`; `deps.edn:48-51` | writer `-Xmx512m` | `:seon.config.database.writer/jvm-heap-mb` | Yes, host-memory-derived | Root/operator | Store MiB as data; render `-Xmx` at launch. |
| `docker/seon-entrypoint:46` | packaged writer default 2 GiB | Same key | Yes | Root/operator | Remove conflicting default. |
| `script/seon/dev/process.clj:355-365` | watcher ready 300,000 ms; shutdown 2,500 ms | `:seon.config.operator.watcher/ready-timeout-ms`, `/shutdown-grace-ms` | No | No | Operator failure names key. |
| `process.clj:478-493` | pod ready 120,000 ms; shutdown 5,000 ms | `:seon.config.operator.pod/ready-timeout-ms`, `/shutdown-grace-ms` | No | Root only | Same. |
| `process.clj:549-566` | writer ready 180,000 ms; shutdown 30,000 ms | `:seon.config.operator.writer/ready-timeout-ms`, `/shutdown-grace-ms` | No | Root only | Same. |
| `process.clj:939-944` | launch diagnostic 4096 chars | `:seon.config.operator/launch-diagnostic-character-cap` | No | Root only | Truncation evidence. |
| `process.clj:1481,1621-1634` | lifecycle response 1 MiB | `:seon.config.operator/lifecycle-response-limit-bytes` | No | Root only | Structured operator error names key. |
| `process.clj:1482,1609-1616` | turn timeout fallback 900,000 ms | Reuse `:seon.config.run/turn-timeout-ms` | No | Yes | No second env-only policy. |
| `process.clj:1483,1818-1822` | lifecycle reserve 120,000 ms | `:seon.config.operator/lifecycle-reserve-ms` | No | Root only | Shutdown evidence cites key. |
| `process.clj:1536-1551` | restore timeout max 600,000; grace/lock max 60,000 | `:seon.config.operator.restore/maximum-timeout-ms`, `/maximum-shutdown-grace-ms`, `/maximum-lock-timeout-ms` | No | Root only | Validation error names key. |
| `bin/seon-server-call:24,95,109,160` | overall 30 s; connect 3000 ms; poll 1000 ms | `:seon.config.operator.server-call/deadline-ms`, `/connect-timeout-ms`, `/poll-timeout-ms` | No | Root only | Tool error names key. |

Test-suite waits in `bin/test-cljs` and `bin/test-writer` remain test harness policy, not cluster config.

## Genuine non-config constants

| Location | Constant | Reason |
|---|---:|---|
| `execution.cljs:23`, `host.clj:88` | execution protocol version 3 | Compatibility discriminator. |
| `db/protocol.cljc:98` | database protocol version 12 | Compatibility discriminator. |
| UDS encoders/decoders | 4-byte header, shifts 24/16/8, mask 255 | Wire-format definition. The payload ceiling is config; header width is not. |
| `db/transport/uds.cljc:30-32` | selector bits 1/4/16 | Java NIO protocol constants. |
| `db/protocol.cljc:215,308` | store-id arity 2–3; index prefix max 4 | Datahike value/index semantics. |
| ID, digest, and blob schemas | CUID/legacy ID widths; SHA-256 64 hex chars | Persisted representation invariants. |
| `agent/shell/internal.cljs:34-39` | exit 143 | POSIX `128 + SIGTERM(15)` semantic sentinel. |
| Port schemas | 0–65535 | TCP domain validation. |
| `render/value.cljc:117-118` | JS safe integer maximum | Numeric representation invariant. |
| `db/transport/uds.cljc:200`, `process.clj:1625` | initial/copy buffers 1024/8192 | Allocation hints, not ceilings. |
| CSS sizes, HTTP codes, clock ranges, parser byte values | Various | UI/protocol/data semantics. |
| Tests and fixtures | Various | Test inputs; production constructors should accept injected policy. |
| `deps.edn:123-124` | compiler/test `-Xms256m -Xmx3g` | Tooling JVM, not production writer/host policy. |
| G1GC, vector-module, native-access flags | N/A | Runtime compatibility/performance selection rather than operational bounds. |

## Duplicate-limit bugs

1. **Invocation deadline:** `execution.cljs:24` and `host.clj:89` independently define 600,000 ms.
2. **Persisted result body:** config fact/default is 16,384 chars, but `host/record.clj:336-347` uses 8,192.
3. **Writer reads:** `writer.clj:58-60` repeats the existing query config facts `100m/1m/3m`.
4. **Writer heap:** development and `deps.edn` use 512 MiB; Docker defaults to 2 GiB.
5. **LLM attempt deadline:** `config.cljs:1474-1483` and `agent/turn.cljs:847-850` repeat 120,000 ms.
6. **Repair policy:** pod config is 50 ms/1 fix; worker is 10 ms/3 fixes.
7. **Source inline threshold:** function and test handlers each define 200 chars.
8. **Subprocess kill grace:** execution host and subprocess each define 1000 ms.
9. **Recovery tail:** 2048 chars is repeated in schemas and producer code.
10. **Shell stream bytes:** foreground and background each define 2,000,000 bytes while documentation asserts they are the same policy.
11. **Canvas render:** `agent/ctx/canvas.cljs:360` hardcodes 2000 instead of using the existing render-fn fact.
12. **Configuration acquisition:** prompt, agent-view, execution child, and AI paths use inconsistent caps for the same singleton pull.
13. **Prompt/agent-view pulls:** identical `5m/65536/3MiB` tuples are separately literalized.
14. **Generated-ID attempts:** runtime and schema both encode 16.
15. **Terminal stop error:** producer clip and protocol schema both encode 4096.
16. **Read profiles:** repeated `10k/8/1KiB` and `100k/4096/256KiB` triples are copied across renderers rather than shared named policies.

The many unrelated uses of `256` and `64` are not duplicates. Connections, codec queue, response slots, pending requests, feed capacity, embedding batch, schema closure, interest patterns, and execute-many count require separate names.

## Boot-time fact-resolution seams

### Current pod path

1. `config.cljs:697-706` is the sole Aero read seam.
2. `config.cljs:708-739` selects and validates the explicitly selected `SEON_CONFIG` manifest.
3. `config.cljs:840-964` resolves it into the flat singleton identified by `{:seon.config/id "cluster"}`.
4. `config.cljs:448-576` defines the attribute-per-key singleton schema.
5. `client.cljs:2142-2145` loads and resolves the selected manifest once during cold startup.
6. `client.cljs:1932-1966` sends routes, skills, and the singleton through the one `seon.state/reconcile!` desired-state path.
7. `client.cljs:606-623` reacquires and decodes the singleton from an immutable database value.
8. `client.cljs:2212-2214` installs the ordinary map once through `db/install-configuration-context!`.
9. `db.cljs:702-706` installs it in the existing async transaction context.
10. `db.cljs:755-775` uses that context for centralized database-request normalization.

This is the right runtime shape. W1 should expand it, not add another cache, file reader, or feature-specific config mechanism.

### JVM writer and host constraint

The writer and host cannot inherit the pod’s async context:

- `seon.config` is currently CLJS-only.
- The writer must start before the pod can connect and transact a fresh config singleton.
- Heap, frame size, connection admission, executor threads, and UDS queues are constructor-time limits.
- The operator needs heap and readiness policy before either process exists.
- The JVM host currently builds its writer/eval/watchdog pools before querying database facts.
- Frame size cannot be learned over a channel whose admissible frame size is itself unknown.

Therefore W1 needs one two-phase authority:

1. Resolve Aero once before process construction, with hardware observations supplied as explicit inputs.
2. Pass the resolved boot-critical subset in launch descriptors/argv to writer and host.
3. Reconcile the exact same resolved singleton into the database after the writer becomes available.
4. Have pod, writer, and host acquire runtime policy from committed facts at an immutable database value.
5. Prove the bootstrap subset equals the corresponding committed facts.
6. On config-free reopen, use only an explicit minimal bootstrap envelope sufficient to open the retained database; then replace it with retained facts.
7. Keep cross-process frame limits in that bootstrap handshake.
8. Do not independently run Aero in each process. A portable pure resolver may be `.cljc`, but file/environment IO remains single-owner.

## Steering-error contract

Caller-controlled caps should return values shaped along these lines:

```clojure
{:seon.error/message
 "The file exceeds the configured byte limit. Read a smaller range or page it."
 :seon.error/kind :agent
 :seon.error/data
 {:seon.config/key :seon.config.agent.fs/max-file-bytes
  :seon.config/effective-limit 8388608
  :seon.config/observed 12000000}}
```

This applies to database resource ceilings, frame/result admission, execute-many size, index pages, interest patterns, filesystem/blob paging, search/web/shell caps, KB/debug/message maxima, value paths, retention admission, host pool/deadline failures, package policy, and handle GC.

Display limits should clip loudly rather than reject. Internal limits need not render into standing context, but failures and diagnostics must still identify their key.

## Proposed W1 implementation order

1. **Settle the boot contract first.** Create one complete resolved singleton and an explicit boot-critical launch envelope, with hardware inputs and equality proof against committed facts.
2. **Make `seon.config` complete.** Register every leaf/section, materialize defaults only during resolution, and remove runtime accessor fallbacks.
3. **Fix duplicate owners.** Unify writer reads, invocation deadline, result-body cap, repair policy, configuration-read profiles, writer heap, kill grace, and generated-ID attempts.
4. **Move the W0.5 writer read policy.** Resolve read maxima and deadline from the same capacity map used by `writer/start!`.
5. **Move executor and JVM UDS families.** These are coupled constructor invariants; add predicate-specific admission errors.
6. **Move pod UDS/session limits.** Share the frame fact and key-named queue/event/timeout failures across peers.
7. **Move JVM host resources.** Writer pool first, then eval/watchdog pools, invocation/result/retention limits, projection reads, and allocation attempts.
8. **Move Bun supervisor and child limits.** Feed the acquired singleton into `execution.host/configure!` and child startup.
9. **Move run, turn, and retry policy.** Eliminate env-only and compatibility fallbacks; freeze the resolved policy at turn open.
10. **Move agent capabilities.** Filesystem first to close the unbounded slurp, then search, shell, web, blob, KB, debug, and messaging.
11. **Move rendering/context budgets.** Existing render facts first, followed by transcript, subagents, plan, typeahead, handlers, and error projections.
12. **Name every call-specific database-read profile.** Consolidate only genuinely identical query shapes; retain deliberately tighter proofs.
13. **Move optional worker/embed/diffusion policy.** Pass frozen resolved facts across existing process/request boundaries.
14. **Add the derived limits context block.** Render only agent-actionable limits, not operator shutdown or diagnostic internals.
15. **Finish with zero-magic and steering gates.** An `rg`-based audit should leave only the documented protocol, representation, allocation-hint, and test-only constants; behavioral tests should assert the exact controlling config key in every cap rejection.