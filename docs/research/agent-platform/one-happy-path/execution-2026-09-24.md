---
type: research
status: census; read-only, nothing in src/test/resources/script edited
created: 2026-09-24
scope: execution, errors and operations. Covers seon.flow, await, the turn/agent loop compared with core.async.flow; fault/error/problems recording and delivery; schedule and maintenance; cluster boot, publication and refresh; dev_cache; the operator and bin/seon; the MCP bridge; seon.ai calls and retry; file watching; logging
---

# One happy path: execution, errors and operations (2026-09-24)

Brief: `tmp/orchestrator/one-happy-path-brief.md`. This census extends
[flow-usage-audit](../flow-usage-audit-2026-09-23.md) (D1-D10, 47 sites),
[fail-loud-dev-resilient-prod](../fail-loud-dev-resilient-prod-2026-09-24.md),
[dependency-already-does-it](../dependency-already-does-it-audit-2026-09-23.md) and
[cut-analysis](../cut-analysis-2026-09-23.md) B-f. Where a prior claim is repeated here, it
was re-checked at HEAD `43addd46c` plus the working tree.

## Broken, first

- **Two routes for one proc fault.**
  - A throw in a proc's stop transition goes synchronously to `fault/fault!`:
    `var-process` catches it and calls `stop-failed!` (`flow.clj:190-212,261`).
  - A throw in a transform is not caught there. Flow puts it on its own error channel,
    a sliding buffer of 100 that evicts old entries (`reference-code/core.async/.../flow/impl.clj:101-102,312-316`).
    From there it passes through a `mult`, then `CountedDroppingBuffer` 64
    (`flow.clj:1046-1081`), then a second flow graph, the committer (`flow.clj:1085-1155`),
    and finally `record!` plus a one-line `println` (`cluster.clj:3314`, `emit-core-fault!`).
  - The agent code shows the same split within one file. Disarm faults go through `fault!`
    (`cluster/agent.clj:1085-1092`). Turn-backstop and write-refusal faults use `offer!`
    into the dropping channel and fall back to `println` (`turn.clj:5359-5406`).
- **The first-ever dedup is still present.** `previously-reported-fault-signature?`
  (`fault.clj:32-40`) and `already-reported?` (`flow.clj:1117-1130`) still suppress a
  recurring signature in every later JVM.
  - The refusal misclassification is fixed at `43addd46c`: `boot.clj:662` now reads
    `:seon.cluster.boot/disposition`, and `cluster.clj:717` writes it.
- **Launcher faults are read by no one (flow audit D1 still holds).** The work launcher
  graph's error channel has no joined reader. Only the cluster graph and agent graphs are
  fanned out (`cluster.clj:3501`, `agent.clj:1019`).
- **Three listeners sit on the writer path** (probe P1 below): `:seon.sci.eval/program-identity`,
  `:seon.agent/route`, and one random-uuid key per agent from `schedule-step`
  (`schedule.clj:790-800`).
- **The MCP bridge runs a full runtime observation on every evaluation and swallows its
  failure.**
  - Every `eval_clj` goes `read-clj-endpoint` → `discovery-rows` → a second prepl
    connection → `mcp-runtime-observation` (`script/seon/dev/mcp.clj:96-115,139-188`).
  - The observation cost 80 ms per call (probe P2).
  - `(catch Exception _ nil)` at `mcp.clj:110` turns any failure into `:unknown` without
    naming the cause.

## Census (ranked by lines deletable)

Line counts come from top-level form spans measured on the working tree. The measuring
script is `forms.sh` in the scratchpad: an awk pass that records the line where each
top-level `(` starts.

| concern | our mechanism(s) (file:line, lines) | the one happy path | evidence it duplicates or diverges | delete | keep/adapt | risk + first slice |
|---|---|---|---|---|---|---|
| Proc and bound fault delivery | fault graph, fan-out and dropped-fault descriptors `flow.clj:988-1373` (386); `offer-write-refusal-fault!` + `offer-turn-backstop-fault!` `turn.clj:5359-5406` (48); `emit-core-fault!` `cluster.clj:3314` (25); `previously-reported-fault-signature?` `fault.clj:32-40` (9); fan-out wiring `cluster.clj:3501-3589` (UNVERIFIED, about 20) | **`seon.fault/fault!`** (`fault.clj:165-220`), called on the proc's own thread by the existing `var-process` wrapper, in the transform arity as well as the stop arity. Flow's own contract is a step that throws (`flow/spi.clj`, `impl.clj:312-316`) | Source-proven divergence: the stop arity already commits synchronously (`flow.clj:261`), while the transform arity goes through two lossy buffers (`impl.clj:102`, `flow.clj:1046`). Disarm uses `fault!` (`agent.clj:1087`); the backstop uses `offer!`/`println` (`turn.clj:5378,5401`). A write that fails under panic still panics in `fault!` (`fault.clj:147-153`) | **≈ 470** | `var-process` gains a catch in the transform arity (+5); `fault!`; `fault/record!` | Risk: `:record` must return `[state nil]`, which is flow's own "continue" (`impl.clj:316`). First slice: the transform catch in `var-process` plus rewiring the two turn offers to `fault!`. Then delete the committer graph and the buffer. Probe: a transform throw in a scratch cluster yields one occurrence and zero entries left in the channel |
| Work launcher compute half | `submit!!` `flow.clj:920` (68); `execute-work!` `:434` (75); `refuse-compute-submission!` `:374` (7); `with-current-arm` + `carried-arm` `:333-362` (30); capacity observer `:284-332` (49); dead callers `turn.clj:3290-3334` `submission-time-limit-evaluation` + `submit-evaluation!!` (45) | Nothing. The only production submission is `effect.clj:857` `flow/submit!` `:io` | `rg 'submit-evaluation!!' src` finds only the definition, so the one caller of `submit!!` is dead. `submit!!` survives only in 9 test references. "No `:compute` proc does substantive work" (flow audit §1) still holds | **≈ 275** | `submit!` (io) | Low risk: dead code. Delete it together with its tests (`flow_test`, `env_test`, `instrument_test`, `cluster/turn_test` hits) |
| Work launcher io half | `RefusingBuffer` `flow.clj:394-433` (40); `io-terminal!`, `execute-io-work!`, launcher step, graph and start/stop `:509-919` (≈ 400) | `clojure.core.async.flow/futurize` with the root `:io` executor (`reference-code/core.async/.../flow.clj:334-346`). The admission bound can be a JDK `ThreadPoolExecutor` with a bounded `ArrayBlockingQueue` and `AbortPolicy`: refusal is `RejectedExecutionException`, which makes it a declared value | One consumer (`my.background`, `effect.clj:845-880`). Its settlement is already durable facts (the receipt), so the launcher graph adds a proc loop, a buffer and an unread error channel (D1) around one executor call | UNVERIFIED **≈ 400** | durable background receipt in `effect.clj` | Risk: cancel-on-stop and drain semantics (`stop-work-launcher!` `:797`). Prove the receipt covers a JVM exit before deleting. Second slice |
| Dead boot span | `boot-phase` `cluster.clj:242` (18), `reserve-cluster!` `:819` (12), `release-reservation!` `:831` (8), `create-directories!` `:839` (6), `require-cluster-target!` `:845` (12), `read-advertisement` `:3683` (26); test-only `publication-base!` `:2896` (74) | `seon.cluster.boot/start!` (`boot.clj`) is the one boot. The operator's `advertisements` (`script/seon/operator.clj:106`) is the one reader of advertisements | `rg` over src, script, bin and test: zero references to the six names; `publication-base!` is referenced only by `test/seon/cluster/publication_export_test.clj:28` | **156** | — | None. One commit plus that test's retirement |
| Stderr projection of faults | `error/log-line` `error.clj:1190` (79); `problems/log-report` `problems.clj:610` (54) | The stored fault plus its declared AI/HTML render pair (`error/render-ai`, `render-html`) | No production caller: `log-report`'s only src reference is its own docstring, and `log-line`'s only src caller is `log-report` (`problems.clj:630`). 15 test references | **133** | — | None. Retire their tests with them (`error_test:1401`, `problems_test`) |
| Graph executors binding an ambient projection | `graph-executor` `flow.clj:75` (21); `cluster/projection-executor` `cluster.clj:3391` (16) | `Executors/newVirtualThreadPerTaskExecutor` plus the root `:io`, with the projection carried as a value (A1 deletes the ambient dynamic vars, dependency audit row 20) | Two wrappers doing one job (flow audit row 15 still holds). Both exist only to call `schema/call-with-projection-state` | **37** | root executors | Blocked on A1-3/A1-4 (ambient projection) |
| Fault readers | `problems/error-signatures` `problems.clj:111` (21) pulls `[*]` and restores refs by hand in `restore-error-ref`/`restore-error-fact` `:85-110` (26) | `seon.error/observation-selector` (`error.clj:1534`) plus `error/latest-fact`, the owner `for-agent` already uses (`error.clj:1786`) | Two selectors for one entity: a `[*]` pull that decodes refs vs the declared observation selector. `open-faults` (`problems.clj:132`) filters the first reader's output | ≈ 35 | `open-faults`, `resolve!` | `problems.clj` is held by another lane. Queue behind it |
| GC head re-read | `collection-evidence` `maintenance.clj:599` (15), `branch-reopens?` `:614` (13) | Datahike GC's reachability permit and cutoff fence (`reference-code/datahike/src/datahike/gc.cljc:89,125-169`) | Dependency audit row 14; still present at HEAD | 28 | `collect!` | Already scheduled as A2 c9 (extend it to cover these two functions) |
| Hand topological sort | `cluster/reload-order` `cluster.clj:2113` (27) plus the Kahn loop in `sci/eval` `acquire-program!` (cut-analysis B-f) | ONE sort. No classpath dependency provides one (`clojure -Spath` lists neither tools.namespace nor clj-reload) | Two implementations of Kahn's algorithm | 27 | keep `reload-order` | `sci/eval.clj` is held; that lane deletes its copy and calls this one |
| Counted wake buffer | `CountedSlidingBuffer` `cluster/agent.clj:104` (23); its one reader `oversight.clj:74-75` | `async/sliding-buffer 1` | Wakes are payload-free and re-derived from facts (CLAUDE.md "channels carry only rederivable…"), so the count of overwritten kicks measures nothing a reader acts on | 25 | — | Low risk. The ping loses `:dropped`, which no decision reads |
| Schedule kick listener | per-agent `d/listen` `schedule.clj:790-800` plus `relevant-attributes`/`relevant-report?` `:743-757` (≈ 30) | The one router `:seon.agent/route` (`cluster/wake.clj`), with schedule interest declared as a wake | Probe P1: a separate listener per agent on the writer path beside the router (flow audit D3; the timer-thread half is already fixed, `schedule.clj:725-741`) | UNVERIFIED ≈ 30 | — | Unproven whether the wake grammar can match `:seon.schedule.*` attributes without new rules. Verify that before cutting |
| MCP io-prepl client | `collect-prepl-response!` `mcp.clj:327` (25); hand form splitter `reader-whitespace?`/`skip-comment!`/`next-form-position!` `:243-274` (33) | `seon.operator/prepl-value!`'s event loop (`operator.clj:133-190`), which the bridge already requires for discovery (`mcp.clj:98-105`). For the "exactly one form" check, Clojure `read` on the same `LineNumberingPushbackReader` | Two readers of prepl events, with divergent timeouts: operator uses a total bound plus a silence bound, MCP only a deadline. The splitter re-implements reader whitespace and comment rules only to report where the second form starts | UNVERIFIED ≈ 45 | stateful session sockets (the operator is one-shot) | Risk: the session keeps its socket, so share the event loop, not the connection. Also bound discovery (P2) |
| Bounded waits | direct `alts!!` with `timeout`: `await-turn-permit!` `turn.clj:5261` (45), backstop watcher `:5407-5456` (50); unbounded `<!!` in `disarm-agents!` `cluster.clj:3576` and `flow.clj:1236,1341` | `seon.await/await!` (`await.clj:106-153`): one owner over channel, Future and `IBlockingDeref`, with a named timeout | Two mechanisms for "wait under a bound". The unbounded ones break "Bound admission" (flow audit D7, still present) | UNVERIFIED ≈ 20 | `await!` | Convert each site to `await!`. The watcher disappears with row 1's backstop fault |
| Serialising arm/disarm | `(locking routing …)` `agent.clj:959,1167`; `(locking contexts …)` `:821` | The armer proc (`agent.clj:1220` `armer-step`), where flow's single proc thread is the serialiser | Flow audit D6 still holds: two serialisers for one lifecycle | ≈ 10 | armer proc | Direct installers (`submit-source!`) become messages to the armer. The lines are few; the payoff is removing lock waits of several seconds |
| Logging | timbre (`cluster.clj:908,3270,3275`, `fs.clj:210`, `web.clj:2344,2367`); about 15 `binding [*out* *err*]` printers (`boot.clj:324,370`, `store.clj:610`, `db.clj:1246`, `schema.clj:1185`, `export.clj:104`, `flow.clj:205,1208`, plus rows above); slf4j-simple; `rotate-logs!` `maintenance.clj:464` (38) | Faults go through `fault!`. Operational lines go through timbre, which is already a dependency | Three sinks for one stream. `rotate-logs!` copies the log and then truncates it (`:486-492`), so lines written between the copy and the truncate are lost (source-proven; this is logrotate's documented `copytruncate` caveat) | the fault printers are counted in row 1 | keep `rotate-logs!` (the detached JVM holds the fd) | Low value. Fold the printers into `fault!` as row 1 lands |
| JSON codec | `cheshire` in the JVM at `cluster/status.clj:6,34` (arrives transitively) beside `data.json` (`ai.clj:58`, `web/jvm.clj:4`, `render/web.clj:46`) | `clojure.data.json`, the declared dependency (`deps.edn:62`) | Two codecs; cheshire is not declared in `deps.edn` | 2 | bb side keeps cheshire (built into bb) | Trivial |

**Examined and kept (no second path).**

- `seon.ai` has one attempt per call. `disposition` and `delays` own the retry policy
  (`ai.clj:493-557,1081`) and `call-turn` owns the loop (`turn.clj:4604-4615`).
- The SSE parser (`ai.clj:770-882`): neither the JDK HttpClient nor any classpath
  dependency parses SSE.
- `cluster/process.clj` `run-process!` (123 lines): babashka.process has no silence bound
  (dependency audit row 26).
- `dev_cache.clj` (605 lines): Clojure and tools.build cache classpaths, not compiled
  dependency classes. Whether AppCDS could replace it is UNVERIFIED.
- `seon.await` is the one bounded wait.
- `schedule` delegates to cron-utils.

**File watching.** There is no in-JVM watcher. Publication is pushed by `bin/seon-hook`
(clj-kondo, then publish). The MCP bridge's mtime self-reload (`mcp.clj:986-1010`) is a
development-process convenience, outside default's Vars. No row.

## Probes (JVM mode, `default`, read-only)

- **P1:** `(keys @(:listeners (meta (seon.cluster.boot/connection "default"))))` →
  `[:seon.sci.eval/program-identity :seon.agent/route <uuid>]`.
- **P2:** timed `(seon.cluster/mcp-runtime-observation "default")` → `80.3 ms`,
  `[:observed 0]`. No open faults now, so health `:observed` is now a derived answer.

## Totals

- **Proven deletable: ≈ 1,190 lines.** Rows 1, 2, 4, 5, 6, 7, 8, 9, 10 and 16.
- **UNVERIFIED: ≈ 505 more.** The launcher io half, the schedule listener, the MCP reader,
  the waits and the locks.
- **Area total: ≈ 1,700 lines.**

**Three highest-value slices.**
1. **Transform faults go through `fault!` inside `var-process`.** Then delete the committer
   graph, the fan-out, `CountedDroppingBuffer`, the offers and the first-ever dedup
   (≈ 470). This also closes D1, D2, D5 and most of fail-loud items 1 and 4.
2. **Delete dead code (≈ 560).** The compute launcher (≈ 275), the dead boot span and
   `publication-base!` (156), and `log-line`/`log-report` (133). All of it needs no design.
3. **Replace the launcher io half with `futurize` plus a bounded JDK executor (≈ 400).**
   This follows slice 1 and a proof that the durable receipt settles across JVM exit.

**What the dependency genuinely lacks.**
- core.async.flow closes `error-chan` before procs handle `::flow/stop`
  (`impl.clj:174-183`). A stop-time error is then dropped. Seon's `var-process` catch
  covers this for our own procs. The upstream fix belongs in the core.async fork, not
  beside it: close after the proc loops exit.
- No classpath dependency provides a topological sort; keep one.
- The JDK has no SSE parser.

## Raised tasks

```clojure
#:seon.task{:id :seon.flow.task/transform-faults-through-fault
            :intent "A transform throw reaches the store through flow's sliding error channel, a counted dropping buffer and a second committer graph, while a stop-transition throw calls seon.fault/fault! directly. Catch in var-process's transform arity too and delete the committer graph, fan-out and buffer."}
#:seon.task{:id :seon.turn.task/bound-faults-through-fault
            :intent "Turn backstop and write-refusal faults offer! into a droppable channel and println when it refuses; disarm already calls seon.fault/fault!. Call fault! at both sites."}
#:seon.task{:id :seon.fault.task/first-occurrence-dedup-deleted
            :intent "previously-reported-fault-signature? silences a recurring signature in every later JVM. Delete it; the per-process seen set and the open/resolved state decide delivery."}
#:seon.task{:id :seon.flow.task/compute-launcher-deleted
            :intent "submit!!, the compute execution path and the capacity observer have no production caller (turn/submit-evaluation!! is itself uncalled). Delete them with their tests."}
#:seon.task{:id :seon.flow.task/io-launcher-is-futurize
            :intent "The work launcher graph wraps one executor call for my.background and its error channel has no reader. Replace it with core.async.flow/futurize on a bounded JDK executor whose rejection is the declared refusal, keeping the durable receipt."}
#:seon.task{:id :seon.cluster.task/dead-boot-span-deleted
            :intent "boot-phase, reserve-cluster!, release-reservation!, create-directories!, require-cluster-target! and read-advertisement have no caller, and publication-base! is test-only. Delete them; seon.cluster.boot/start! is the one boot."}
#:seon.task{:id :seon.error.task/log-projection-deleted
            :intent "error/log-line and problems/log-report are a third projection of a fault with no production caller. Delete them; the declared AI/HTML render pair is the one projection."}
#:seon.task{:id :seon.flow.task/one-graph-executor
            :intent "flow/graph-executor and cluster/projection-executor both exist to bind an ambient projection. Delete both once the projection travels as a value."}
#:seon.task{:id :seon.problems.task/one-fault-selector
            :intent "problems/error-signatures pulls [*] and restores refs by hand beside seon.error/observation-selector. Read faults through the error owner's selector and latest-fact."}
#:seon.task{:id :seon.cluster.agent.task/plain-sliding-wake
            :intent "CountedSlidingBuffer counts overwritten payload-free wakes that are rederived from facts. Use async/sliding-buffer 1."}
#:seon.task{:id :seon.schedule.task/router-kicks-schedule
            :intent "Each armed agent adds its own Datahike listener to kick its schedule proc beside the one wake router. Declare schedule interest as a wake the router delivers, then delete the listener."}
#:seon.task{:id :seon.dev.mcp.task/one-prepl-reader
            :intent "The MCP bridge reads prepl events with its own loop and splits forms with hand reader rules beside seon.operator/prepl-value!. Share the operator's event loop and use Clojure's reader, and bound or cache the per-call runtime observation instead of swallowing its failure."}
#:seon.task{:id :seon.await.task/every-wait-through-await
            :intent "Turn permit and backstop waits use raw alts!! with timeout, and disarm and fan-out stop wait with unbounded <!!. Route every wait through seon.await/await! with a named bound."}
#:seon.task{:id :seon.cluster.agent.task/armer-serializes-lifecycle
            :intent "arm!/disarm! hold (locking routing) across seconds of work beside the armer proc. Send arm and disarm to the armer and delete the monitors."}
#:seon.task{:id :seon.cluster.task/one-reload-sort
            :intent "cluster/reload-order and sci/eval acquire-program! each implement a topological sort. Keep reload-order and call it from acquire-program!."}
#:seon.task{:id :seon.cluster.status.task/one-json-codec
            :intent "cluster.status parses the thread dump with cheshire, which arrives only transitively, while the JVM declares clojure.data.json. Use data.json."}
```
