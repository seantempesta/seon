---
type: research
status: active
tags: [research, runtime]
---

# Timeout + magic-number census — R50 audit (2026-07-24)

Read-only sol audit (lane timeoutcensus) under ruling R50. Findings
verbatim from the lane summary; orchestrator-persisted because the
lane ran in a read-only sandbox.

Audit completed on `codex/runtime-reliability-refactor`. No files were edited. The requested report could not be created because this session’s filesystem is read-only; the copy-ready findings are below.

Counts use one row per mechanism or coherent constant family:

- A — Legitimate external backstop: 10
- B — Poll where an event exists: 17
- C — Clock masking a missing signal: 21
- D — Tuned design constant: 33
- Total: 81

Key conclusion: the 900-second cleanup and 20-minute claim lease are both class C. The run-driving virtual thread’s termination was observable immediately, but `driver.host/dispatch!` discarded that event in `finally` without guaranteeing durable phase settlement.

## Required cross-checks

| Item | Classification | Finding |
|---|---|---|
| 900s turn/request deadline | C | The exhibit shows it was the only effective detector after the run-driving thread died. It must remain only a loud outer backstop. |
| Heartbeat lease | C | Appropriate as a distributed last-resort fence, but not as the primary detector of local virtual-thread or supervised-process death. |
| Watchdog `stale-ms` = 1,200,000 | C | Current ticker calls `driver/scan!`, which steals expired leases. The older loud `close-stale-runs!` is referenced only by a test; current takeover fault recording is unverified and appears absent. |
| R42 pod stall = 300,000 | C, with B poll | Named, documented, and throws loudly, but the 200ms readiness/log polling should consume file/process/readiness events. No durable fault datom was found. |
| `reactive-settle-ms` = 16 | D | Legitimate coalescing after Datahike interest delivery, not failure detection. It is already a config fact; calibration rationale remains unverified. |

## Census

### A — Legitimate external backstops

| File:line | Constant/current value | Event or nondeterminism | Recommended owner |
|---|---|---|---|
| `config/system.edn:150-162`, `src/seon/ai/http.clj:39-79,210-217` | Connect 300,000ms; request deadline from attempt | Remote HTTP connection/response | Keep in model-transport/provider facts. Timeout becomes a flat retryable result and durable attempt evidence. |
| `src/seon/agent/driver/host.clj:110-137` | Process holding the run LLM outer attempt, normally 120,000ms | Remote provider call | Keep. Firing returns `:seon.ai/timeout?` and terminalizes the attempt. |
| `config/system.edn:408-416,431-432` | Planning 300,000/360,000ms; execution 120,000ms; one retry | Hosted model request | Keep as per-agent/provider data with attempt receipts. |
| `config/system.edn:204-210`, `src/seon/agent/turn/core.cljc:256-280` | Max wait 20,000ms; total 60,000ms; four retries | Transient provider 429/5xx/transport failure | **LIFTED W-R50-6.** Ceilings and base/factor/jitter now come from the acquired `:seon.config/llm-retry` facts. |
| `config/system.edn:319-330`, `src/seon/agent/web/host.clj:300-438`, `pod.cljs:484-795` | 30,000ms | Remote HTTP/search completion | Keep. Flat error values are produced. The host’s inline 30s connect timeout should consume the same fact. |
| `config/system.edn:209-210`, `src/seon/agent/shell/leaf.clj:94-122`, `subprocess.cljs:193-220` | 30,000ms | Foreign command completion | Keep as shell policy; returned envelope records timeout. |
| `src/seon/agent/search/internal.cljs` | 10,000ms | Foreign `rg` subprocess | **LIFTED W-R50-6.** `:seon.config.search/timeout-ms` is acquired with the search caps and passed to the subprocess request. |
| `src/seon/diffusion/gemma.cljs:187-189,509-529` | Remote poll 3,000ms; local 250ms; 200 polls | RunPod job status | External RunPod polling is legitimate. Local-endpoint classification is **UNVERIFIED**; if locally supervised, consume process/response completion instead. Move all literals to provider data. |
| `src/seon/embed.clj:611-627` | Five retries; base 500ms; ×2; ±0.5; max 30,000ms; total 60,000ms | External embedding provider | Keep retry envelope, but move all policy literals to config/provider data. Firing logs and terminates; durable fault ownership is **UNVERIFIED**. |
| `script/seon/dev/state.clj:74-107` | Caller deadline; 50ms `tryLock` interval | Kernel lock held by another process | A is **UNVERIFIED**. Prefer interruptible blocking lock acquisition if available; otherwise name the retry cadence and ensure timeout evidence includes holder/process context. |

### B — Poll where an event exists

| File:line | Constant/current value | Observable event | Recommended owner |
|---|---|---|---|
| `src/seon/agent/loop.cljs:648-690` | 30,000ms ticker | Run/claim datom, run-holding process completion, scheduled due instant | Keep a scheduler only for cron due instants. Claim recovery should be driven by vthread completion, process exit, and database interest. |
| `src/seon/db/transport/uds.cljs:25,455-646` | 250ms deadline scan | Request Promise response/rejection or socket disconnect | Replace the shared scan with request-completion callbacks plus a nearest-deadline one-shot backstop. |
| `src/seon/db/session.cljs:513-545` | 1ms→250ms ambiguous-write retry | Original transaction receipt/terminal response | Await the stable request receipt through the existing database interest/response path. |
| `src/seon/db/host.clj:225-254` | 250ms source fallback; configured 1,000ms in web-render | Interest session EOF/restoration | Reconnect from socket-close and writer-ready events. A delay may rate-limit respawn, but must not detect readiness. |
| `src/seon/db/host.clj:700-748` | 10ms request-conflict polling | Active request/release receipt becomes terminal | Deliver completion to waiters instead of repeatedly issuing the same call. |
| `src/seon/client.cljs:2585-2715` | 10ms quiescence reread | Run/turn status transaction | Use `reactive/observe!`/database interest over current-run and running-turn attributes. |
| `script/seon/dev/process.clj:1048-1129` | 200ms readiness/log polling | Log modification, readiness file/socket publication, process exit | Use `WatchService`, readiness publication, and `ProcessHandle.onExit`. |
| `script/seon/dev/process.clj:1464-1507` | 10ms nonblocking UDS loops | Selector connect/write/read readiness | Use a `Selector` or blocking bounded socket operation. |
| `script/seon/dev/process.clj:1692-1719,1831-1867,2423-2456` | 25ms terminal/process polling | Terminal-file publication and `ProcessHandle.onExit` | Join the supervisor completion and watch the terminal file. |
| `script/seon/dev/process.clj:1323-1335` | 10ms × 50 identity polling | `ProcessHandle.Info.startInstant` availability or child exit | Capture identity from launch/supervisor acknowledgement. |
| `script/seon/dev/branch.clj:198-220` | 10ms for 5,000ms | Selector-owned session-release completion | Database transport should publish drain completion; branch release then waits for that event. |
| `script/seon/dev/program_artifact.clj:169-185` | 10ms × 100 delete attempts | Shadow flush/materialization completion | Join the build/flush completion before deletion. |
| `script/seon/dev/changed_test.clj:211-221` | 100ms for 3,000ms | Shadow manifest publication | Watch the manifest or consume the watcher’s publication event. |
| `script/seon/dev/changed_test.clj:443-498` | 10ms process-tree stabilization/absence | `ProcessHandle.onExit` for each observed descendant | Compose `onExit` completions; retain only a loud termination backstop. |
| `script/seon/dev/mcp.clj:543-568,1200-1281` | 200ms for 6,500ms | Runtime advertisement/session registration | Consume endpoint/advertisement publication rather than rereading files/sessions. |
| `script/seon/dev/mcp.clj:975-1058` | 150ms Promise bridge polling | Promise `.then`/`.catch` settlement | Send settlement over the existing REPL response channel. |
| `script/seon/dev/mcp.clj:1524-1539` | 5,000ms parent watchdog | Parent `ProcessHandle.onExit` | Register `onExit`; log once and terminate immediately. |

### C — Clock masking a missing signal

| File:line | Constant/current value | Missing signal | Recommended owner |
|---|---|---|---|
| `src/seon/agent/driver/host.clj:587-614`; exhibit `tmp/orchestrator/lifecycle-redrive-gate.log:244-341` | Detected only at 900,000ms | Claimed virtual thread terminated with uncaught NPE | `driver.host/dispatch!` must join/completion-handle every vthread and atomically settle the phase error or observe already-terminal/displaced state before removing its handle. |
| `src/seon/web/serve.cljs:827-863,1544-1609` | Request-selected deadline; exhibit 900,000ms | Run and every request-owned turn reaching terminal datoms | Keep only as a loud API backstop. On firing, record a core fault naming the unsettled run/turn and owning config/request key. |
| `src/seon/config.cljs:1020-1029`, `src/seon/client.cljs:2590-2715`, `script/seon/dev/process.clj:1923-1924,2052-2059,2265-2269` | 900,000ms plus 120,000ms reserve | Durable run/turn settlement and quiesce completion | Replace the primary wait with database interest. One acquired config fact should own the backstop; remove source/operator duplicates. |
| `config/system.edn:599-607`, `src/seon/agent/run/core.cljc:15-33,99-136`, `driver.cljc:223-263` | 1,200,000ms claim lease | Local vthread completion or cluster JVM exit | Vthread completion and supervisor `onExit` must immediately trigger settlement/release/scan. Lease remains a loud distributed survivor backstop only. |
| `src/seon/agent/run.cljs:1035-1167`, `agent/loop.cljs:654-669` | Same 1,200,000ms | Watchdog firing/fault publication | `close-stale-runs!` is referenced only by a test; the ticker now calls `driver/scan!`. Record a fault on lease steal or retire the dead watchdog path. Current loudness is **UNVERIFIED** and appears absent. |
| `src/seon/host.clj:56-59,160-239` | 10,000ms startup frame | Managed pod session startup frame or socket EOF | Consume protocol-frame/EOF completion. If the last-resort timer fires, record a core fault; current timeout is intentionally suppressed from fault recording. |
| `src/seon/db/transport/uds.cljs:455-646`, `db/session.cljs:503-545` | 15,000ms calls; 120,000ms transactions | Writer response, receipt, or disconnect | Request completion owner must reject/deliver on every terminal transport path. Timer remains loud backstop only. |
| `src/seon/db/host.clj:15-22,134-140,558-805` | Pool 110,000ms; call/interest 120,000ms | Condition signal, task completion, active-request receipt | Strengthen pool/task/receipt completion. Defaults are duplicated inline and some calls read `defaults` instead of resolved writer options. |
| `src/seon/db/writer.clj:4523-4561`, `config/resolve.cljc:1724-1726` | `30,000 × read-queue-waves` | Read worker completion/failure | Completion must always deliver a response. Keep wall time only as loud core-fault backstop; work/result budgets remain D. |
| `src/seon/db/transport/uds.cljc:1552-1625` | 5,000ms repeated joins/termination waits | Selector/worker/cleanup executor completion | Compose termination futures; retain one configured loud shutdown backstop. |
| `src/seon/db/executor.clj:858-870` | Inline 5,000ms provider termination | Executor termination | Configure and log/record when forced shutdown fires. Worker threads currently join without a backstop. |
| `src/seon/eval.cljs:94-246,1309-1318,1790-1807` | 10,000ms | Evaluated Promise settlement | The Promise is already observable. A timeout that leaves it running is not settlement; use addressed async results or guarded invocation completion. |
| `src/seon/test/runner.cljs:363-400,557-600`, `config.cljs:1001-1007` | 15,000ms | Test/fixture Promise settlement | Keep only as a loud test-harness backstop. It reports an error, but cannot preempt the Promise. |
| `src/seon/execution/host.cljs:30-32,536-676,916-934` | Ready 10,000ms; ensure-host 240,000ms; cancel grace caller/config | Child ready/exit and host-session protocol response | Consume subprocess `.exited` and protocol messages; delete with the legacy execution owner where planned. |
| `src/seon/execution.cljs:1-25,1271-1312` | Maximum invocation 600,000ms | Child invocation result/error/exit | Protocol owner must correlate every invocation to a terminal result. Timer should only be a loud retirement backstop. |
| `script/seon/dev/process.clj:1056-1103`, `config/system.edn:190-197` | R42 stall 300,000ms | Concrete boot-progress or process-exit signal | Keep as named loud last resort; replace polling with progress events and persist structured fault evidence. |
| `script/seon/dev/process.clj:410-411,659-660,735-736` | Ready 300,000/180,000ms | Managed readiness publication or exit | Convert to configured loud backstops after event-driven readiness. |
| `script/seon/dev/process.clj:1460-1507` | Inline 2,000ms containment control phase | UDS acknowledgement or EOF | Selector/protocol completion; name the backstop and include owner generation in firing evidence. |
| `script/seon/dev/process.clj:1692-1719,1831-1867` | Shutdown grace + 10,000ms | Owner/workload exit and matching terminal file | Join `onExit` plus terminal publication. The deadline should report a containment bug, not perform primary detection. |
| `script/seon/dev/restore_state.clj:978-1239`, `process.clj:2423-2587` | Admin 120,000ms; lock 5,000ms; extra 30,000ms | Contained one-shot exit and terminal application result | Supervisor completion/terminal receipt owns settlement; consolidate literal fallbacks into operator config. |
| `script/seon/dev/mcp.clj:59-62,868-943,1245-1285` | 30,000ms default; max 120,000ms; connect 5,000ms | nREPL/prepl terminal response or socket close | Ensure each eval request gets response/EOF delivery; retain a loud client-facing backstop. |
| `script/seon/dev/changed_test.clj:17-20,545-575` | Test process 300,000ms | `Process.onExit` | Await exit directly and retain the deadline only to classify a harness wedge before forced termination. |

### D — Tuned constants encoding design guesses

| File:line | Constant/current value | What it governs | Recommendation |
|---|---|---|---|
| `config/system.edn:63-73` | 100 turns; 300 forms; 1,800,000ms run deadline | Run resource policy | Named facts are correct. Record a fault when the deadline fires unexpectedly; calibrations are **UNVERIFIED**. |
| `config/system.edn:82-88` | Three 100M step budgets; 600,000ms; 1,638,400 output cap | Guard resource limits | Proper facts. Wall deadline is a last-resort circuit breaker; interpreter steps are the primary observable budget. |
| `config/system.edn:97-106`, `reactive.cljs:152-189` | 16/300/500ms | Reactive coalescing/max latency | Keep. Datahike interest is already the wake event. Add measured rationale. |
| `config/system.edn:40-45`, `turn/llm.cljc:26-115` | 400ms | Partial-reply snapshot cadence | Keep as fact; rationale/calibration **UNVERIFIED**. |
| `config/system.edn:75-80`, `config/resolve.cljc:1880-1889` | 1,000ms | Failed host respawn rate limit | Keep as config fact triggered by an exit/failure event. |
| `src/seon/client.cljs:546-556` | Inline 60,000ms | Keeps Bun event loop alive | Inline design workaround. Replace with real hosted-work/process ownership or move to config with rationale. |
| `config/system.edn:164-178`, `web/feed.clj:53-84` | Heartbeat 15,000ms; mailbox 1; connections 10,000; executor 256; pool 16; page 100; body 4MiB | JVM web-render capacities/cadence | Correctly configured; calibration is explicitly pending. Database call timeout belongs in C. |
| `src/seon/web/datastar.cljs:342-352` | Inline 15,000ms | Legacy pod SSE heartbeat | Duplicate of web-render policy; consume config or delete with the retired pod feed. |
| `config/system.edn:108-144` | Page 64; query/pull 25M–100M work; 1M results; 3M weight; mutation queues 32,768 | Database admission/read ceilings | Good config facts with substantial rationale. |
| `src/seon/config/resolve.cljc:1660-1834` | Hardware-derived worker, queue, heap, frame, byte, slot, and 5,000ms shutdown defaults | Database operational sizing | Facts are named, but several formulas remain design guesses. Preserve formulas with benchmark evidence; shutdown timeout is C. |
| `config/system.edn:150-162` | Identity 512; endpoint 2,048; response 16MiB | Model evidence/response caps | Good facts with rationale. |
| `config/system.edn:180-188` | Heap 4,096MiB; result 1MiB | Claim-driver containment | Keep configured. Pool/invocation deadlines belong in C. |
| `config/system.edn`, `src/seon/agent/turn/core.cljc` | Base 500ms; factor 2; jitter 0.5 | LLM retry shape | **LIFTED W-R50-6.** The existing `:seon.config/llm-retry` owner supplies all three acquired facts to both run-holding process and pod turn contexts. |
| `config/system.edn:319-330` | 2M bytes; 2,000 preview tokens; redirects 5; links 25; search 10/20; HTML 1M chars/depth 3,000 | Web response/search caps | Named facts; rationale mostly “preserves policy” and remains **UNVERIFIED**. |
| `config/system.edn`, `src/seon/agent/search/internal.cljs` | 8MiB; preview 32 tokens; 12 results | Search output caps | **LIFTED W-R50-6.** The acquired `:seon.config/search` row owns all three caps. |
| `config/system.edn`, `src/seon/subprocess.cljs`, `shell/leaf.clj` | Kill grace 1,000ms | SIGTERM→SIGKILL escalation | **LIFTED W-R50-6.** Both leaves consume `:seon.config.shell/kill-grace-ms`. The previous JVM-only 250ms drift was removed in favor of the existing cross-tier subprocess policy value. |
| `src/seon/diffusion/worker/eval.cljs:107-166` | 1,000ms | V8 generated-code budget | Named source constant with rationale, but should become provider/worker configuration if this subsystem remains. |
| `src/seon/agent/lifecycle.cljc:298-307` | 32 stale-value attempts | Lifecycle CAS retry | Inline guess. Prefer conflict convergence evidence; configure or justify 32. |
| `src/seon/agent.cljs:1030-1098,1430-1450` | 32 attempts | Spawn/namespace stale-value retry | Same issue; unify with one transaction-conflict policy. |
| `src/seon/runtime/state.cljs:72,547-585` | Three attempts | Config reconcile conflicts | Inline guess; unify with transaction-conflict policy. |
| `src/seon/db/id/schema.cljc:13-18`, `db/id.cljc:1340-1439` | 16 collision rounds | Generated identity collision | Named but explicitly awaiting relocation to a config fact. |
| `src/seon/host/context.clj:1730-1805` | 16 eval-ID attempts | Eval receipt identity collision | Duplicate collision policy; consume the database identity-policy fact. |
| `src/seon/db/writer.clj:1667,2117-2125` | Three attempts | Program initialization stale-value retry | Inline guess; initialization receipt/CAS should define whether retry remains valid. |
| `script/seon/dev/process.clj:35,410-411,584-585,659-660,1923-1924` | Shutdown 2,500/5,000/30,000ms; reserve 120,000ms | Process escalation/lifecycle budget | Move to one operator configuration family with per-process rationale. |
| `script/seon/dev/changed_test.clj:17-20,758-773` | Manifest 3,000ms; termination 2,000ms; hook lock 3,000ms; derived 910,000ms lock | Test tooling policy | Inline dev-tool guesses. Configure or derive from event-driven task ownership. |
| `script/seon/dev/mcp.clj:63-66` | Output 4,000/16,000/64 tokens; 256 events | MCP response caps | Inline tooling policy; name in MCP config if user-tunable. |
| `config/system.edn:212-250`, `config/resolve.cljc:2012-2029` | Render caps: 1,500–16,384 chars, depth 3, collections 8, path 32/4,096, etc. | Rendering admission/display | Good named facts, though several rationales are historical rather than measured. |
| `config/system.edn:451-592` | Context priorities, token caps, 25/50/25 transcript windows, decay 4,096/1,024/512 | Prompt/context policy | Correct config data. Treat as product tuning, not runtime reliability. |
| `config/system.edn:594-607` | Spawn depth 1; crash count 3; breaker window 1,800,000ms | Agent topology/schedule breaker | Good facts. Window/count rationale is **UNVERIFIED**; `stale-ms` is C. |
| `src/seon/config.cljs:294-302` | Work 100,000; results 4,096; weight 1MiB | Config-singleton read profile | Inline and explicitly marked for relocation; add database facts. |
| `src/seon/config/resolve.cljc:2035-2039`, `host/preflight.clj:35-36` | One repair; 50ms | Source repair budget | Named resolved values, but host carries a 50ms fallback. Remove the fallback. |
| `src/seon/execution/host.cljs:30-33,310-318` | Idle 300,000ms; output 64KiB; tail 16KiB | Legacy child eviction/evidence caps | Inline policy; likely delete with the legacy execution host instead of migrating. |
| `src/seon/execution.cljs:22-25` | Invocation 600,000ms; result `frame − 64KiB` | Legacy protocol ceilings | Result cap has a structural rationale. Invocation deadline belongs in C and should not remain a second authority. |
| `config/system.edn:90-95`, `config.cljs:1078+` | Recent limit 12 | Root activity lookback | Named product-policy fact; no runtime concern. |
| `config/system.edn:395-432` | Model tokens 16,384/8,192 and per-variant policies | Model variant resource tuning | Correctly stored as variant data; external deadlines/retries are covered under A. |

## Ranked fix lanes

| Rank | Lane package | Owned mechanism and acceptance evidence |
|---|---|---|
| 1 | Process holding the run completion settlement | `src/seon/agent/driver/host.clj`, `driver.cljc`, focused run-holding process tests. Every claimed vthread completion—success, throw, interruption—must either observe terminal/displaced state or commit phase error + release before handle removal. Reproduce the planner NPE and show settlement in one transaction without waiting for any clock. |
| 2 | Claim lease and process-death signalling | Claim driver, run-core lease policy, run-holding process supervision. Feed virtual-thread completion and supervised run-holding process `ProcessHandle.onExit` into immediate scan/release. Retain `stale-ms` only as a loud survivor backstop; lease steal records a core fault. Remove or rewire the dead `close-stale-runs!` path. |
| 3 | Run/API/quiescence settlement | `web/serve.cljs`, `client.cljs`, operator quiesce. Use database interest for request-owned run/turn completion and shutdown drainage. The outer deadline records a fault with exact unsettled datoms; no 10ms or 900s primary detection remains. |
| 4 | Database completion and receipt delivery | `db/transport/uds.cljs`, `db/session.cljs`, `db/host.clj`, writer receipt owner. Replace 250ms deadline scanning and 10ms conflict loops with Promise/receipt/disconnect delivery. Internal deadlines become named, loud backstops. |
| 5 | Event-driven operator | `script/seon/dev/process.clj`, branch/changed-test helpers. Use `ProcessHandle.onExit`, `WatchService`, selector readiness, and terminal-result publication. Keep R42’s 300s stall ceiling as configured loud evidence, not a 200ms poll loop. |
| 6 | Config-authority cleanup | **PARTIALLY LIFTED W-R50-6:** search, retry shape, and cross-tier shell kill grace now come from acquired database facts. Startup read, legacy execution-host, MCP, and operator literals remain in their separately owned/protected lanes. |
| 7 | Transaction retry policy | **STOPPED W-R50-6 — non-mechanical seam.** Fresh `runtime.state/reconcile!` commits the config singleton itself and cannot acquire a database-owned conflict fact before that first transaction succeeds. The 32/3 stale-database attempts, CAS semantic loss, and 16-round generated-identity collisions are distinct failure classes. Preserve them until the first-apply owner exposes a settled pre-configuration policy source; tracked in [[transaction-retry-policy-cannot-be-database-owned-during-first-config-reconcile]]. |
| 8 | Retire legacy execution clocks | Delete or consolidate `seon.execution.host`/pod feed timers with the planned execution-host retirement rather than building new configuration around obsolete paths. |
| 9 | External-backstop loudness proof | Add recurring tests that each remote HTTP/subprocess deadline produces the expected flat error, attempt receipt/log, and governing config key. |
| 10 | Dev-tool polling cleanup | Changed-test, MCP, program-artifact, and branch-release event conversion; lower production risk but removes many inline guesses. |

Top five summary: run-holding process vthread completion; process-death/lease signalling; database-driven run/quiescence settlement; database request/receipt completion; event-driven operator readiness and containment.
