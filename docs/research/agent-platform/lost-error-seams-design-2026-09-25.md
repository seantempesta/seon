---
type: design
status: "round 3 (Opus) after Astra re-review 431a9b4ee; corrections 3 and 7 closed; 9.6 ruled; awaiting Astra re-review"
created: 2026-09-25
scope: One owner change per lost-error class A–G from the 2026-09-25 audit, plus fault observation in the agent's MCP replies
---
# Lost-error seams: one owner change per class

**What exists.** Astra's [lost-errors audit](lost-errors-audit-2026-09-25.md) (`64ae7aca5`, pinned `93711ba43`) found
106 path instances in seven classes. The [review](lost-error-seams-design-review-2026-09-25.md) (`431a9b4ee`) closes
corrections 3 (the stop caller) and 7 (bootstrap classpaths). It keeps seven open, and all seven are integrated
below in the sections that own them.

**Data flow.** This design repairs three paths:
- a failure goes to its owning boundary, which writes one durable fact plus the committed recipient message, and
  the execution owner then applies the policy;
- a task reaches its real exit, and exactly one owner disposes of its outcome;
- a value passes through the one floor bound, and both faces derive from that.

**Principal Clojure approach.** Make each owner's contract total, and use the dependency seams that already exist:
- Flow's `ProcLauncher` SPI and core.async's `WritePort`;
- Malli's throwing `:report` in `-instrument`;
- the Datahike writer's serialized upsert;
- `Thread.join`, executor termination and `ProcessHandle.onExit`;
- the render owner's safe selection and continuation;
- the existing `seon.error/occurred-since` window.

Each cut then deletes its per-site copies. The design adds no registry, supervisor, retry, task framework, second
printer or transport cursor.

**Pin.** Lines refer to HEAD **`431a9b4ee`**. Its source matches review HEAD `0ecc0e6a2` for every cited path. This
round of the design is doc-only: no source changed and no test ran.

## 0. Findings that reshape the audit's seams

**0.1 One fault route.** `var-process` records every step throw on the proc's own thread (`flow.clj:198-232`,
`:290-303`). A second route runs beside it, and this design deletes all of it:
- the error fan-out (`:982-1081`);
- the committer graph with its own policy copy (`:839-889`, the source of B01);
- `CountedDroppingBuffer` (`:742-835`, the source of C03);
- four injected callbacks;
- six `offer!` producers that fall back to stderr.

Flow's own loop reports loop-level failures with `(async/>!! (outs ::flow/error) report)` (`impl.clj:313`, `:318`).
`outs` arrives through the `ProcLauncher` SPI (`impl.clj:155-162`), which `var-process` already reifies and forwards
(`flow.clj:310-318`). So **`var-process` substitutes `::flow/error` in the forwarded `:outs` with a per-proc
disposer**. The review rejected a side-effecting channel buffer (correction 1): `add!*` runs under the channel mutex
(`impl/protocols.clj:31-35`), and a throw there reaches the channel's exception handler (`impl/channels.clj:283-306`)
and is lost.

The disposer is therefore **a `WritePort` whose `put!` disposes the report on the reporting thread and returns a
completed box**, so `>!!` returns at once (`impl/protocols.clj:18-19`: `put!` answers "derefable boolean … if handled"; `async.clj:200-211`: `(if ret @ret (deref p))`). It holds no mutex and has
no buffer or reader. Flow's `stop` closes only the graph's `:error` channel (`impl.clj:179`), so a proc still
unwinding after stop reports into a live disposer.

The disposer's contract, written as data:

| Step | Value or action |
|---|---|
| input | Flow's report map: `::flow/pid`, `::flow/status`, `::flow/cid`, `::flow/op`, `::flow/msg`, `::flow/state`, `::flow/count`, `::flow/ex` |
| failure value | `(ex-info "Flow proc loop failure" (dissoc report ::flow/ex) (::flow/ex report))`. The report's members become ex-data values and the original throwable stays the cause, so the whole chain and every report value survive. The declared schema is `:seon.flow/exception-error`, the existing one. `proc-failed!` keeps its Throwable contract |
| recording | `proc-failed!` → `fault!` with the proc's carried environment, bounded by `db/transact!`'s declared write deadline. Outcome-unknown or refused panics (`fault.clj:163-169`) |
| panic action | `stop-panicked-graph!` (`flow.clj:234-246`, non-blocking `put!` and `poll!`) |
| totality | the whole body sits in one `try`. A throw from recording or rendering goes to an emergency `prn` of the report's pid and op plus the throwable's `floor`, which is itself wrapped in a `try`, and then the graph stops. `put!` always returns the completed box, so nothing propagates into Flow's catch (`impl.clj:312-319`, where a throw from `>!!` would escape the loop into an unread future) |

`proc-failed!`'s own panic printing (`:220-231`) goes inside the same totality `try`.

P1 is this change. The review accepts P1 as an investigation now and a production change after a scratch-graph
probe shows six things:
- a `send-outputs` failure reaches the disposer with its full report;
- a report that races `flow/stop` is disposed;
- more than 100 reports are all disposed;
- a stalled recorder blocks only the reporting proc, and the proc's ping goes unknown, never healthy;
- a failing recorder stops the graph with emergency evidence;
- a failing disposer render still stops the graph.

**0.2 Task ownership stays with each existing owner.** There is no `await/task`. Exit is proven by `Thread.join`,
executor termination or `ProcessHandle.onExit`. Disposition goes through the existing `io-terminal!` CAS or the
caller's join-then-consume (section A.2).

**0.3 Delivery through the agent's path.** The owner ruled at 20:50Z that faults go to root by default and appear as
errors in the agent's MCP interactions. Section M covers this.

## A. Asynchronous results not consumed on every exit (13)

**1. Seam and missing contract.** `seon.flow/io-terminal!` (`flow.clj:398-424`) settles each submission once by CAS.
It has four gaps.
1. A throwing `complete!` still has a success terminal offered in its `finally` (`:413-424`), while the exception
   stays in an unread `FutureTask` (`:444-465`). This is A01.
2. `stop-work-launcher!` cancels futures and settles running work from the stopping thread (`:628-644`), so a late
   real outcome loses the CAS.
3. A cancel before entry leaves `::running` work that never reaches `io-terminal!`.
4. The completion input is offered only while `::active?` holds (`:416`). Stop clears it (`:632`) and stops the
   graph (`:667`), so any disposition routed through that channel after stop is lost (correction 2).

A further detail: `work-launcher-step`'s `proc-failed!` call (`:518-519`) returns a boolean that nobody acts on. Only
`var-process`'s own wrapper stops the graph (`:291-303`).

**2. Owner change: `io-terminal!` disposes of failures before it removes the submission.** The completion channel
then carries only replaceable launcher accounting, never the only copy of a failure.

- **World and panic action.** `var-process` adds one closure, `::stop!`, to every proc's init args. The closure
  captures control and graph-ref and calls `stop-panicked-graph!`, and the section C producers use the same
  closure. The launcher step copies `::stop!` into each work item when `execute-io-work!` claims it. `io-terminal!`
  already has `(env/of work)` for the carried world (`:410-412`).
- **Disposition inside `io-terminal!`.** A body or callback throwable is recorded with
  `(fault/fault! (env/of work) failure {:seon.error/layer ::submission ...})`. If that throws a panic, `(::stop! work)`
  runs.
  - When both the body and the callback fail, the callback failure is added to the body failure with
    `.addSuppressed`, and one fault carries both.
  - All of this happens before `(swap! submissions dissoc id)`. Removal stays in `finally`, so it runs even if the
    disposal panics.
  - The completion offered to the launcher, while `::active?` still holds, carries a `::disposed` failure receipt
    for accounting only. The launcher's `proc-failed!` call at `:518-519` is deleted.
- **Thread handle.** The runnable's first swap on `active-work` (`:445-450`) already runs on the task thread. It now
  also stores `::thread (Thread/currentThread)` in that same swap.
  - Stop reads `active-work` after it clears `::active?`, and interrupts each recorded thread.
  - A task that entered but recorded its thread after stop's read is not interrupted. It still finishes and is
    still disposed, and the executor join still proves its exit. Interruption is best effort; the join is the proof.

The lifecycle, with each outcome and its one owner:

| Outcome | Owner and disposition (once, by the `::status` CAS) |
|---|---|
| body returned a value, or a declared refusal | `io-terminal!` settles it as `::value`. A declared refusal is the work's ordinary result, not a core fault |
| body threw | the body catch, then `io-terminal!` → `fault!`, then `::stop!` on panic |
| body returned, then `complete!` threw | the new catch around `complete!` → `fault!` |
| body threw and `complete!` threw | one fault: the body failure with the callback failure suppressed |
| executor rejected (`:469-473`) | `io-terminal!` → `fault!` |
| stopped before entry | the runnable's first form reads `::active?`, finds it false, and settles the launcher-stopped value without running the body |
| stopped while running | interrupt only; the task disposes of its own outcome, and the executor join proves exit |
| queued, never executed | stop's CAS from `::queued` to `::completed` settles the launcher-stopped value (`:404`) |
| a failure inside `io-terminal!` (`swap!` or `offer!`) | the runnable's outermost catch → emergency `prn` plus `::stop!`. **No Seon task reaches the uncaught handler** (correction 8) |

The FutureTask, `::task` and `.cancel` are deleted, and `execute-io-work!` becomes `(.execute executor ^Runnable
runnable)`.

**3. Migration.** The minus and plus figures are estimated physical source lines. Each range has one owning cut in §8.

| ID | Site | Conversion | − / + |
|---|---|---|---|
| A01 | `flow.clj:398-473`, `:513-524` | the lifecycle table above | −12 / +16 |
| A02 | `test.clj:282-311` | the thread join stays the exit proof. The watcher joins, then `.get`s the task, then disposes a late throw through `core-fault!` (`:2192`), then calls `release!`. On the timely path, `.get` moves before `release!`. A `release!` failure is suppressed onto a late failure; with no late failure it goes to `core-fault!` | −2 / +8 |
| A03 | `effect.clj:472-502` | G01, in one cut | in G01 |
| A04 | `flow.clj:1055-1081`, `agent.clj:1153-1160` | deleted in R1 | in C |
| A05, A06 | `operator.clj:553-580` | JDK only. The losing `CompletableFuture` is joined with `.get` under a bound after the socket closes, and its value or exception is consumed into the terminal map. A registered `.handle` is not a join (review) | +8 |
| A07 | `process.clj:266-274` | the streamed-output future is joined before `run-process!` returns or throws, including after `terminate-subprocess!` on timeout | −2 / +5 |
| A08 | `shell/jvm.clj:87-124`, `:330-408` | every capture thread's promise is consumed on every exit | −4 / +8 |
| A09, A10 | `web.clj:2925-2945`, `turn.clj:5545-5561` | section C.3 | in C |
| A11 | `operator.clj:757-759` | `@census` in `finally`, with the census failure suppressed onto the primary | −1 / +3 |
| A12 | `dependency_digest.clj:69-83` | JDK only: join the output future after the child exits; an output timeout throws | −3 / +5 |
| A13 | `operator_move_test.clj:23-26` | `.waitFor` before `.exitValue`; a bounded join of the output | −1 / +3 |

**4. Class regression.** Rows go in `test/seon/flow_test.clj`, covering every row of the lifecycle table through the
real `start-work-launcher!`, `submit!` and `stop-work-launcher!`. One row is a host body that ignores its first
interrupt and holds an injected latch. Rows in `test/seon/test_test.clj` cover a late throw and a release failure
through `seon.test/run`. Each row asserts:
- exactly one disposition;
- a stored, delivered fault for each failure;
- no success report for a failure;
- a failure after stop is disposed, not dropped;
- no release before the executor terminates.

**5. Cost.** One allocation per submission disappears (the FutureTask). The new catch costs nothing on success.
Cut L times `submit!` to terminal on the parent and on the change.

**6. Order.** A01 goes in cut L after R1, which frees `flow.clj`'s fault half and adds `::stop!`. A05–A08 and
A11–A13 go in S cuts and are startable now.

**7. Files.** See §8, cuts L, T, S-process and S-scripts.

## B. Catch converts failure to absence, fallback or incomplete evidence (35)

**1. Seam, and what an owner cannot do.** `fault!` (`fault.clj:179-234`) never swallows its own failure. Its gaps are:
- `record!` is public and total;
- the last resort prints `(str fault)` (`:159`);
- the receipt carries no recipient.

No owner change can make an arbitrary catch-and-default impossible, so B closes producer by producer. The means are
the listed deletions, `record!` becoming private at R4, and the class regression.

**2. Per-site rule** (rejection shapes probed at HEAD):

- **Core operation.** Delete the catch.
- **Declared rejection.** The `try` wraps only the rejecting call and matches its actual rejection:
  - edamame: `ExceptionInfo` with `:type :edamame/error`;
  - `clojure.edn`: a bare `RuntimeException`, so the `try` wraps `edn/read-string` alone;
  - **Malli `:malli.generator/no-generator` with no `ex-cause`**: the schema has no generator, so skip it.
    Malli throws the same keyword **with a cause** when a declared `:gen/gen` symbol throws while being evaluated
    (`generator.cljc:472-482`). That case is a machinery failure and stays red with its cause (correction 8).
- **Typed unknown.** Add `:seon.error/throwable failure` (`refusal/diagnostic`, `error/refusal.clj:91`).
- **Scripts.** The same diagnostic goes to the operator boundary, and cleanup failures become `.addSuppressed`.

**3. Migration.** B01 is deleted in R1. Rows marked *probe* are PLAUSIBLE: the lane shows the declared rejection
case, or refutes it, before editing.

| Rule | ID and site | Transformed producer |
|---|---|---|
| delete | B02 `schema.clj:247`; B03 `schema.clj:1122-1131`; B07 `ai.clj:868` | failure propagates |
| throwable | B06 `call_preparation.clj:489` | the incoherent-row refusal keeps its cause |
| narrow | B08 `test/accretion.clj:185-192` | skip only for no-generator without a cause; everything else is red with its cause |
| split | B11 `schema/admission.clj:100` narrowed to edamame; `:366` and `:493` deleted | findings only for declared shapes |
| split | B12 `render/ns.clj:134-141` (`read-edn`) narrowed to `RuntimeException` around the read; `:142-156` (`schema-form-refs`) catch deleted | the review's walk/read split |
| narrow | B14 `shell/jvm.clj:388` | only the broken-pipe `IOException` after reaping |
| throwable | B04 `turn.clj:3406-3416`, B05 `sci/kernel.clj:543-553`, B09 `test/runner.clj:949-954`, B10 `test.clj:105-106` and `:1469`, B13 `render/value.clj:309` and `:341` (render slice), B15 `web/jvm.clj:349`, B16 `cluster/status.clj:44`, `:92`, `:162` | typed unknown with the full cause |
| delete | B24 `script/seon/dev/docstring.clj:303-307` | a parse failure is a finding |
| probe | B26, B27 `fn/analyzer.clj:807`, `:852`; B28 `print.cljc` (render slice); B29 `repl.clj` (render slice); B30 `render/transcript.clj:1201`; B31 `sci/eval.clj:1199`; B32 `eval/drive.clj:125`; B33 `web/jvm.clj:427`; B34 `operator.clj:466`; B35 `bootstrap.clj:94` | narrow or delete after the probe |
| script | B17–B23 `script/seon/dev/mcp.clj`; B25 `operator.clj:295` | the whole cause reaches the boundary. `mcp.clj` belongs to delete-mcp-bridge-opus, whose survivors this cut converts |

**4. Class regression.** Rows go in `test/seon/cluster/fault_storage_test.clj`:
- a nested cause, namespaced ex-data and a Seon frame are stored whole, or an explicit storage-failed panic occurs;
- a declared edamame rejection stays a rejection;
- no-generator without a cause is skipped;
- no-generator with a cause is red with that cause;
- a typed unknown carries its chain;
- a cleanup failure is suppressed onto the primary.

Each changed producer also adds its own row in its existing suite.

**5–7.** Only failure paths change. Order: after P2, and each held file waits for its holder. Files: see §8, cuts
B-platform, T and bridge.

## C. Exhaustion becomes no work, or its terminal fault is not durable (6)

**1. Seam and missing contract.** `opening-deferred?` answers `true` when the dial is absent (`turn.clj:2739`), and
both consumers treat that as an ordinary deferral: `:2793` accepts any truthy value, and `:2913` propagates only
errors. C03 offers exhaustion and backstop faults to the dropping channel (`turn.clj:5466-5512`). C04 is at
`test.clj:2308` and `plan.clj:716-725`. C05 and C06 turn process refusals into nil.

**2. Owner change.**
- `opening-deferred?` answers the declared missing-dial error, and both consumers branch on it.
- **Duplicate disposition is decided at the writer, by obligation identity, without comparing clocks**
  (correction 8). The obligation is the unanswered wake, identified by its `:seon.message/id`.
  - The deferral fault's commit request names `:seon.error/obligation <wake message id>`.
  - `recording` folds that identity into the occurrence identity map it already hashes (`error.clj:1537-1544`).
  - `commit-call` already reads `old` under the serialized writer (`:1341`). When a request names an obligation and
    `old` exists, `commit-call` returns only the process row. The count does not increment and no message is sent.
  - Two stale derivations from one held basis therefore both reach the writer, and exactly one records.
  - A read refusal of `old` already returns as the transaction's refusal (`:1351-1361`) and becomes a panic in
    `fault!`. It is never treated as "no prior occurrence".
  - A pre-exhausted agent at arm (C02) uses the same rule, with the obligation being the unanswered wake at arm
    time.
  - This costs about +6 lines in `error.clj` (P2) and +1 optional member in `seon.error.edn`.
- C03 exhaustion runs inside the turn step, so it calls `fault!`. A panic throws into `var-process`, which stops the
  graph.

**3. Migration.** R1 and R1t retire the fault channel. Each producer gets an explicit execution owner:

| Producer | Execution owner and panic action |
|---|---|
| `turn.clj:5466-5489` write-refusal exhaustion | turn proc: `fault!` throws, and `var-process` stops the agent graph |
| `turn.clj:5491-5561` backstop (A10) | agent graph: the step passes `::stop!` (0.1/A.2) to `arm-turn-completion-backstop!`. The runnable calls `fault!`; on panic it calls `::stop!` and puts the failure on `failure-channel`. The runnable's outermost `try` stops as well |
| `web.clj:2322-2345` page derivation | render proc: `fault!`; a panic stops the render graph |
| `web.clj:2925-2945` feed writer (A09) | SSE feed: `fault!` unless the failure is the declared client close (`painting` false and an `IOException` on write); on panic, `close-sse!` and a failed feed status |
| `web.clj:3540-3556` data read | HTTP request: `fault!`; a panic becomes the declared error response |
| launcher join `agent.clj:1153-1160`, `boot.clj:28-47`; wiring `cluster.clj:3341-3364`, `:3542-3600`, `:3620-3630`, `:3711-3712` | deleted. The report mult and monitor taps survive as `start-report-fanout!` |

The `flow.clj` deletions:
- `:742-900`, 159 lines;
- `:933-980`, 48 lines;
- the error halves of `:982-1108`, about 55 lines.

What stays is `monitor-graph` (`:902-931`, report view only) and the report fan-out, about 40 lines. The gross
deletion is **−262**; the disposer and `::stop!` add about +25.

The schema deletions total about −70 lines:
- the fan-out and committer members in `seon.flow.edn:28-116` and `:190-196`;
- `dropped-fault` in `seon.error.edn:108`, `:144-149`, `:272` and `seon.error.occurrence.edn:40-41`;
- `seon.boot.edn:75-77`, `seon.cluster.wake.edn:25`, `seon.render.web.edn:21` and `:117`.

The committer and overflow rows in `flow_test.clj` are deleted. The `record!` callers at `flow_test.clj:698`, `:793`,
`:1136`, `cluster_test.clj:121` and `cluster/fault_message_test.clj:23` are converted.

| ID | Conversion | − / + |
|---|---|---|
| C01, C02 | `turn.clj:2739`, `:2793`, `:2913`; the obligation on the request | −2 / +12 |
| C03 | R1 as above; `turn.clj` offers become `fault!` | −24 / +10 |
| C04 | `test.clj:2308`: typed await timeout on the run row; `plan.clj:716-725` propagates an incomplete result | −3 / +8 |
| C06 | `issue.clj:77-88`: a nonzero exit returns the process refusal | −1 / +3 |

**4. Class regression.** Rows go in `test/seon/cluster/turn_test.clj`, on canonical branch worlds with no-provider:
- missing dial;
- pre-exhausted agent re-armed;
- **two derivations from one held basis** give occurrence count 1 and one message;
- a derivation whose `old` read refuses panics;
- wake ordering stays correct when the producer clock is set backwards;
- a stalled recipient graph still leaves the fault and message durable;
- a completed episode records no fault.

The fault port rows are in 0.1. The C04 row goes in `test_test.clj` and the C06 row in `issue_test.clj`.

**5. Cost.** Each agent graph loses its fan-out thread, and each fault skips a channel hop. The writer read is the
one it already makes.

**6. Order.** P1 and P2, then R1 and R1t. C01 and C02 wait for the `turn.clj` holders.

## D. Returned value escapes the declared error alternatives (4)

**1. Seam and missing contract.** `compiled-wrapper` (`instrument.clj:624-726`). Under `:record` its outer catch
(`:715-726`) returns the boundary refusal as the function's value. Malli already enforces: `-instrument-f` checks
arity and input before the call and output and guard after it (`malli/core.cljc:2202-2231`), and Seon's `:report`
throws (`:705-713`).

**2. Owner change.**
- **D-delete (P0).**
  - Delete `:715-726` and keep the throwing report.
  - Make `fault/carried-receipt` (`fault.clj:171-177`) public. The one normalizer, `kernel/failure-value`
    (`kernel.clj:526`), rethrows any throwable whose cause chain carries a receipt. It is used by `kernel/invoke`
    (`:658`) and `sci/eval.clj:4122`, `:4442`.
  - The temporary gap is stated: until R3, a host output violation under `:record` is returned to the agent, not
    stored. Under the default `:panic`, behaviour is unchanged.
- **D-classify (R3). Policy RULED by root.** Responsibility comes from stored program call data, and ambiguous
  cases return to the agent. The table has three evidence cases (correction 5):

| Report | Evidence (read in the agent's selected world) | Responsible → disposition |
|---|---|---|
| `:input` or `:arity` on V | **authored**: the evaluated root, meaning the form's analysis or a branch-authored row, whose analysis is current for the exercised definition, calls V directly | agent → returned |
| `:input` or `:arity` on V | **installed, positively**: the root's current analysis has no unresolved references; it reaches V only through installed definitions; each edge is validated against its exercised definition digest; and the root does not call V directly | core fault of the reaching installed caller → `fault!` |
| `:output` or `:guard` on V | **authored**: V's exercised definition digest names a row whose defining transaction's provenance is this agent's branch work | agent → returned |
| `:output` or `:guard` on V | **installed**: V's exercised digest is a row published by boot or adoption provenance, meaning the JVM's loaded program for that cluster, not today's default head | core fault → `fault!` |
| `::m/invalid-schema-at-call` | authored contract row (by provenance, as above) and the engine threw on that schema | agent → returned |
| `::m/invalid-schema-at-call` | installed contract row | core fault |
| anything else: absent, stale or incomplete analysis; mixed direct and installed callers; unknown provenance | **ambiguous** | agent → the complete refusal is returned (root's rule) |
| a throwable carrying a receipt | already disposed | rethrow |
| an interrupt or bound | the evaluation's own bound | the declared timeout |

**Data path.** The review asked that this be named with no ambient reads.
- `failure-value`'s request map (`kernel.clj:534`) gains `:seon.db/db`, the selected agent world, and
  `:seon.fn/root`, the evaluated root's symbol or the form's analysis identity.
- `kernel/invoke` already holds both: `database` at `:590` and `function-symbol`.
- The two `sci/eval.clj` callers hold the evaluation's db and the form's analysis.
- The refusal gains `:seon.program/definition-digest` for V from the wrapper's arm metadata, which it already
  carries because arming records the digest.
- Reach is read through the one call-reach owner (`f24bf9143`, "one call-reach owner"), never through a new walk.
- The +14 line estimate is unproved until R3 measures that read.

**3. Migration.**

| ID | Conversion | − / + |
|---|---|---|
| D01 | delete `instrument.clj:715-726` and the commit-fault! checks at `:470`, `:491-499`, `:860-871`, `:901-902`; `seon.instrument.edn:42-44` | −40 / +1 |
| D02, D03 | `ai.clj:114`, `:120`: declare the callee's missing-root alternative; removed again by render slice 2(i) | +2 |
| D04 | `background.clj:59-72`: return the pull error before success projection | +2 |
| E05, E07 | receipt rethrow in P0 (+4); the table in R3 (+14 unproved, `kernel.clj:526-572`) | +18 |
| tests | `instrument_test.clj:195`, `:344`, `:422`, `:1308` stop calling `record!` and assert the throw (P0) | ±0 |

**4. Class regression.** Rows go in `test/seon/instrument_test.clj`, under both policies:
- authored direct bad call;
- installed nested bad call;
- **missing analysis**, which is ambiguous and returned;
- **mixed direct and installed callers**, returned;
- **cluster retains an older installed program while default advances**, where installed is judged by the cluster's
  loaded digest;
- authored bad output;
- host bad output;
- validator engine throws on an installed schema;
- invalid authored schema;
- receipt-carrying panic;
- the rootless attempt unit;
- the background read error.

The same case also runs through the MCP SCI entrance and `seon.test/run` as thin adapter checks.

**5. Cost.** The `try` frame leaves every armed call. Classification runs on refusals only.

**6. Order.** P0 is independent. R3 comes after P0 and P2 (P2's writer stays compatible), and before R4. That
removes round 2's cycle (correction 4).

## E. Fault classification, custody or delivery is wrong (9)

**1. Seam and missing contract.** `commit-call` (`error.clj:1329-1453`) is a Datahike transaction function returning
transaction data (`:1331-1335`), invoked by `:db.fn/call` (`:1550-1551`). It infers responsibility from
`interrupted?` (`:1393`) and routes a core throwable to the executing agent as `:your-run` (`:1433`). With
`escalate-to` absent (`:1436-1442`), faults reach no one. The receipt checks storage only (`fault.clj:212`).

**2. Owner change, as preparation and then activation** (correction 4, acyclic).

- **P2, a compatible preparation.** It does *not* complete routing.
  - `commit-call`'s return contract is unchanged. `record!` reads the **committed recipient evidence** from each
    target's `:db-after`: `[?m :seon.message/about signature] [?m :seon.message/to ?a] [?a :seon.agent/id ?to]`.
    Message ids are `[signature recipient opening]` (`error.clj:1313-1326`), so this also finds a notification
    already open for the signature.
  - The recipients go into the recording tuple and into the receipt as `:seon.fault/delivered-to`, an optional
    member of `seon.fault.edn`.
  - Also in P2: the shipped `escalate-to "root"` (the owner's ruling); the obligation member (C.2); and F01, F07,
    F22 and F08 in a second commit.
  - The `interrupted?` and `:your-run` branches survive P2, so routing stays **explicitly incomplete** until R4.
- **R4, E-activation. It is atomic and comes last**, after R1, R1t, R2 and R3. It holds whole-file grants for
  `fault.clj`, `error.clj`, `seon.fault.edn` and `test/seon/turn_test.clj`.
  - `record!` becomes `defn-`.
  - The `interrupted?` and `:your-run` branches are deleted (`:1393`, `:1433`), so the writer handles core faults
    only.
  - `policy` requires `committed?` and a non-empty `delivered-to`.
  - The last test caller, `test/seon/turn_test.clj:251`, is converted.
  - Precondition: `rg "fault/record!" src script test` returns only `fault.clj`.

**3. Migration.**

| ID | State | Conversion |
|---|---|---|
| E01 | confirmed | P2 + R4: −6 / +16 source, +1 config, +2 in `seon.fault.edn` |
| E02, E03, E04 | superseded (`test.clj:2192-2203`; `sci/eval.clj:2481-2482`) | none |
| E05, E07 | confirmed / D | P0 and R3 |
| E06 | confirmed (`boot.clj:342-381`) | After A.2 and C.3, no Seon task reaches this handler: the launcher, the backstop and the proc loop each contain their failures (correction 8). The handler therefore serves only **genuinely ownerless threads**, calling `fault!` with the carried environment, or printing stderr emergency evidence when there is none. An agent-attributed throwable reaching it is itself a named owner defect. −14 / +6 |
| E08 | confirmed (`turn.clj:3406-3443`; `call_preparation.clj:1272`, `:1291`; `test.clj:1469`) | delete `analyze-submitted`'s per-site classifier. Each boundary returns its declared agent-mistake alternatives and passes everything else to `fault!` |
| E09 | confirmed (`sci/eval.clj:2437-2496`) | R2, §9.2. Authored refusals return in the opening. Installed, compiler and configuration failures go to `fault!`. The direct `commit-tx` writer (`:2443-2486`), the `commit-fault!` branch (`:2487-2495`) and the `config/defaults` fallback (`:2448-2450`) are deleted, along with 19 lines matching `:seon.flow/commit-fault!` and `seon.sci.eval.edn:244-246`. −70 / +15 |

**4. Class regression.** Rows go in `test/seon/cluster/fault_message_test.clj`:
- Throwable and flat core error, each with and without attribution, give the steward or root in `delivered-to`;
- a notification already open gives the same recipient and no second message;
- a removed steward falls back to root;
- a missing root means the fault committed but is undeliverable, which panics under R4, and the committed facts
  remain;
- storage refusal panics;
- a receipt-carrying failure is not recounted;
- under `:panic` the graph stops;
- no paid turn occurs, with the root provider read on the target branch;
- an invalid authored call is returned with no occurrence.

**5–6.** The fault path gains one indexed query per target. Order: P2, then R1, R1t, R2 and R3 in any order, then R4.

## M. Faults in the agent's MCP replies (owner ruling 20:50Z; revised per the review's §M)

**What exists (M1).** Round 2 wrongly said MCP replies carry no faults. Three owners already produce fault windows:
- **The bridge.** `script/seon/dev/mcp.clj:511-562` evaluates a second form, `window-form`, through
  `seon.error/occurred-since`. It keeps a per-session `window-bases` cursor (`:500-503`, `:542`, `:558`), clips with
  `take max-window-errors` (`:533`), builds its own requery text (`:562`), and appends a second text block
  (`:629-635`).
- **The shared history.** `render/transcript.clj:346-402` (`branch-errors`) already derives each settled evaluation's
  window, from the previous settlement transaction to this one, with `occurred-since` as-of that settlement. Later
  recurrences therefore never rewrite an earlier response, and a refused read is a typed unknown.
- **The query owner.** `seon.error/occurred-since` (`error.clj:1571-1616`) returns unresolved signatures touched in
  the window, with their message, operation, branches and summed cumulative counts.

**Design.**

1. **One observation, recorded with the evaluation.** An MCP interaction's reply shows the evaluation that the
   agent's pipeline records. Its fault observation is taken **at that evaluation's settlement**, which is the
   existing recording seam that `transcript.clj:369-377` already reads.
   - The settlement transaction records the upper bound it observed: `:seon.cluster.eval/fault-basis`, the
     cluster-branch basis-t of the captured cluster database value. This adds one attribute to `seon.cluster.eval`'s
     schema, owned by the settlement writer.
   - The lower bound is the same attribute on **that agent's previous settled evaluation**.
   - If that attribute is absent (a first evaluation, or a history compacted away), the observation is the declared
     unknown `:seon.error/window-unknown`. It names its upper bound and the requery, and replays nothing.
   - Every settled reply unit is an evaluation. System, status-only and failed evaluations follow the existing rule
     at `transcript.clj:374-376`, which covers shown, error or interrupted. A multi-form request settles in one
     transaction, whose window belongs to its last form (`:379-383`). Compaction clears evaluations, and the
     previous basis is then absent, so the result is unknown rather than a replay.
2. **The cluster's database, supplied explicitly** (M2). The agent's world carries the cluster connection
   (`:seon.turn.loop/cluster`), and the settlement captures `(db/db cluster-connection)` once.
   - `fault/record!` copies every core fault to the cluster branch (`fault.clj:107-145`), so one cluster value
     covers faults from every branch.
   - The agent's own-branch window stays `branch-errors`'s filter (`transcript.clj:397-400`), and the cluster window
     is the whole cluster value.
   - Both are **one immutable value per settlement**, so AI and HTML render from the same saved observation.
3. **Query semantics (M3).** `occurred-since` already selects occurrences by a transaction predicate on
   `last-at`'s tx against the **full** database (`:1587-1588`). It does not use a since view, so relations resolve
   against unchanged datoms. The window is `(lower, upper]` read on `(db/as-of cluster-db upper)`.
   - **Counts, defined.** `new-signatures` is the exact number of signatures touched in the window.
     `:seon.error.occurrence/count` remains labelled cumulative per occurrence. It is not an event count in the
     window: count and last-at are no-history attributes (`seon.error.occurrence.edn:3-5`), so event deltas cannot be
     promised.
   - **Delivery, from the same committed evidence as P2.** `occurred-since` adds each signature's recipients through
     the `:seon.message/about`/`:seon.message/to` relation read by the same query. That includes a prior open
     notification, so a recurrence that reuses an old message is delivered. A signature with a committed occurrence
     and no recipient is **undelivered**. That can happen: before R4, and after R4 when the delivery check panics
     after commit, since the facts remain. So `undelivered` is an honest count, not zero by definition.
   - **Cost.** One indexed tx-range scan over the `last-at` attribute plus the relation joins, proportional to the
     occurrences in the window. The history transcript already runs this query. The cut times it.
   - **Refusal.** A refused read returns `:seon.db/error-result`, which becomes the observation's typed unknown,
     never an empty healthy window.
4. **One render path, continuation and error outcome** (M4).
   - The observation is an ordinary unit rendered through the render path's one `units` entrance, sharing the
     reply's budget B.
   - Its continuation is the render owner's source reference, held basis, typed path and absolute offset
     (render design §3g, `one-render-path-design-2026-09-25.md:451-478`). No fault pagination and no requery text are
     built here.
   - The pipeline's reply outcome is declared data: `:seon.cluster.eval/outcome ∈ {:ok :error}`. It is `:error`
     when the evaluation failed **or** the fault observation holds a new, undelivered or unknown fault.
   - The MCP adapter encodes that outcome as `isError` and renders the same saved text. It does not classify or
     format faults itself.
5. **Retirements in the same cut.** These are counted in M-cut.
   - In `mcp.clj`: `window-form`, `branch-errors!`, `window-bases`, `max-window-errors`, the second evaluation, the
     requery text and the extra text block (`:500-503`, `:511-562`, `:629-635`, about −60 lines).
   - In `transcript.clj`, `branch-errors` (`:346-402`) reads the recorded fault-basis pairs instead of recomputing
     windows from settlement transactions, about −30 / +12.
   - `mcp-runtime-observation`'s `take observation-top` and `bounded-summary` (`cluster.clj:629-647`, `:691`,
     `:719`) render the same observation unit instead, which is render slice 6's row 9.

**Class regression.** Rows go in `test/seon/problems_test.clj`, through the shared agent entrance. Thin MCP and
browser adapter checks assert the same saved text and outcome.
- Two agents on different branches both observe one cluster fault.
- A recurrence that reuses an old notification counts as delivered.
- A committed occurrence whose delivery failed counts as undelivered.
- A fault committed during an evaluation lands in the settling window; one committed after it lands in the next.
- A failed evaluation and a status-only evaluation are both covered.
- A first evaluation, or one after compaction, gives the declared unknown.
- A refused read gives the typed unknown.
- A continuation beyond B returns the omitted remainder.
- The outcome is `:error` when there are new faults, and `isError` agrees.

**Order.** P2 (the delivery evidence) comes first. The M cut then lands **inside the pipeline that owns MCP reply
assembly**, which is design-one-pipeline S2 together with render slice 2(i). It owns `mcp.clj` after
delete-mcp-bridge-opus lands, plus `transcript.clj`, the settlement writer's schema and `error.clj`'s
`occurred-since`, sequenced after R4. The M cut is not startable now.

## F. Arbitrary value expands before the single presentation bound (23)

**1. Seam and missing contract.** `render.value/prepare` (`value.clj:621-668`) bounds only the AI face. HTML visits
the whole value (F02), composite keys go through HTML (F03) and window ordering prints every key (F04). Today's AI
face is not safe either, so no producer row closes F before render slice 2(i).

**2. Owner change: render slice 2(i), plus two rules.**
- **Producer rule.** A diagnostic carries its offending value as a value plus fixed prose, never `pr-str` in a
  message.
- **F01 and the retained evidence producer** (correction 6).
  - `prepare-result` stops producing `:seon.error/data-blob`: its complete-HTML `complete` line and its `blob/put!`
    are deleted (`error.clj:485-487`), and its output contract drops the member. The readers were audited:
    `error.clj:659-660`, `:681`, `:1403-1447` and `cluster/status.clj:141`.
  - The **retained full evidence** is the second producer. It is admitted by `bounded-error-admission` under the
    **result caps** (`error.clj:527-537`, then `admit/admit-partitioned`, then `Throwable->map` and lazy traversal at
    `sci/admit.clj:295-297`, `:345-350`) and staged by `evidence-stage` (`:1455-1473`). `max-evidence-bytes` bounds
    only the **inline** fact, not this retained admission. Round 2's claim that it did was false, and the storage
    dial is `:seon.config.eval.result/max-bytes`.
  - That admission **moves into render slice 2(i)'s safe storage selection** (render design `:466-478`): opaque
    values, terminal cuts, and a storage allowance separate from B. F01 therefore lands **inside** slice 2(i)'s
    conversion of every raw-admission caller. It is not a separate path, and no artifact codec is added.
  - Until then, F is incomplete, and error recording itself can realize unsafe values.
  - `:seon.error/shown` stays the exact saved AI bytes, and `:seon.error/result-id` keeps binding the live subject.
    Its continuation is the render path's, and a subject that has gone answers `:gone`.

**3. Migration.**

| IDs | Conversion | − / + |
|---|---|---|
| F02, F03, F04, F12, F13, F18 | render slices 2(i) and 4 | there |
| F01, and the retained evidence admission (`error.clj:460-492`, `:524-538`, `:657-660`) | the HTML producer is deleted in P2. The admission converts inside render slice 2(i) | −6 / +1 (P2); slice 2(i) |
| F07 (`error.clj:1110`, `:2013`, `:2056`), F22 (`:892`), F08 (`fault.clj:159`) | fixed prose with the value in data (P2) | −11 / +5 |
| F05 `render/hiccup.clj:280` | the head becomes the offending value | −2 / +1 |
| F06 `db.clj`; F09 `sci/eval.clj:3589`, `:3637`, `:3793`; F10 `turn.clj:3704`, `:3753`; F11 `render.clj`; F14 `accretion.clj:136-138`, `:370-375`; F15 `test.clj:1944`, `runner.clj:398`, `:986-988`; F16 `fn.clj:58-59`; F17 `schema/internal.cljc:469-523`, `admission.clj:409-410`; F19 `cluster.clj:3470`, `status.clj:177`; F21 `background.clj:55` (P0), `my/program.clj:265`, `plan.clj:1367` (cut T); F23 `render/ns.clj` | the producer rule, scripted once, then lint, load and hand-fix the residue | about −60 / +30 |
| F20 | retired: the round-1 row cited a function changed by `1ae366d7f` | 0 |

**4. Class regression.** Rows go in `test/seon/render/value_test.clj`, through read → eval → record → print:
- **error recording itself** realizes no unsafe value, and neither do the AI and HTML views;
- a supported, retained, finite artifact round-trips exactly;
- evidence beyond the storage allowance is explicitly unavailable;
- the saved shown bytes are exact.

Selection and storage are timed separately, with allocation.

**5–6.** Recording one error stops rendering the HTML face (P2). Storage stays O(N) under
`:seon.config.eval.result/max-bytes` until slice 2(i). Order: P2's commit, then slice 2(i), then the producer rows.

## G. Unbounded wait, or cancellation mistaken for exit (16) — correction 3 closed

**2. Owner changes** (accepted).
- **G02, cut L.** Launcher tasks run on the launcher graph's own executor (`start-graph!` takes `::executor`, +3),
  and `join-graph!`'s `awaitTermination` proves exit.
  - `boot/stop!` checks the launcher result before releasing anything (`boot.clj:499`).
  - A typed timeout throws into the existing catch (`:512-514`), which retains the instance, branch and store.
- **G03.** Arming and stopping publish completion as a promise.
  - The promise's value is the operation's terminal outcome: the handle, or the failure as a value.
  - The owner delivers it from its `finally`. That marks the **operation's** completion, not proof that the thread
    exited.
  - Waiters `await!` it under `:seon.config.agent/turn-completion-backstop-ms`. On timeout they throw, and the
    release mark stays (`agent.clj:785-791`).
- **G01 and A03** (`effect.clj:472-502`) use the virtual-thread join shape. The claim is held until the watcher has
  joined and consumed the outcome, and a re-request refuses meanwhile.
- **Subprocesses** use `terminate-subprocess!` and `onExit` where that owner is on the classpath. Scripts stay on
  the JDK.

**3. Migration.**

| ID | Conversion | − / + |
|---|---|---|
| G01, A03 | above (G-effect) | −12 / +16 |
| G02 | `flow.clj:575-675` plus `boot.clj:499` (L) | −8 / +10 |
| G03 | `agent.clj:796`, `:1085`, `:1308-1320` (G-agent) | −3 / +10 |
| G06–G10 | `operator.clj:553-580`, `:616-624`, `:901-902`, `:1513-1514`: `.destroyForcibly` then a bounded `.waitFor`; fail naming the survivor; delete files only after proved exit | +12 |
| G12 | `issue.clj:79`, `test/cache.clj:41`, `:190` → `run-process!` (main JVM, after A07) | −8 / +6 |
| G13 | `operator_move_test.clj:24-26`, `:111-112` | −1 / +3 |
| G14, G15 | `sci/eval.clj:3106`, `:3217`; `call_preparation.clj:614-617`; `db.clj:3772` | measure first: how far contended acquisition exceeds its deadline |
| G16 | `flow.clj:278`: `var-process` accepts `:io` only (P1) | −1 / +1 |
| G04, G05, G11 | with A08, A07, A12 | counted there |

**4. Class regression.** Rows go in `boot_test.clj`, `effect_test.clj`, `cluster/agent_test.clj` and
`cluster/subprocess_test.clj`:
- the real `boot/stop!` refuses and retains the instance, branch and store; the latch is released; the late failure
  is recorded once; a second stop then releases;
- an effect re-request refuses while its claim is held, and a late throw is consumed once;
- a blocked arm under stop refuses within its bound and keeps the mark;
- a subprocess survivor keeps its ownership.

The script entrances are proved on their own classpaths.

## 8. Cuts, order and exact file grants (rebased on `431a9b4ee`)

Each cut owns whole files, loads on its own, and needs no later cut to repair it. Root grants files from the current
ledger at launch. The holders listed are from `tmp/orchestrator/file-ownership.md`; they are not permissions.

| Cut | Owns (whole files: source, schema, test) | Lands | After |
|---|---|---|---|
| **S-scripts** | `script/seon/operator.clj`, `script/seon/dev/dependency_digest.clj`, `script/seon/operator_move_test.clj`, `src/seon/shell/jvm.clj`, `test/seon/shell/jvm_test.clj`, `test/seon/dev/dependency_cache_test.clj` | A05, A06, A08, A11–A13, B14, B25, B34 (probe), C05, G04, G06–G11, G13 | — |
| **S-process** | `src/seon/cluster/process.clj`, `src/seon/issue.clj`, `src/seon/test/cache.clj`, `test/seon/cluster/subprocess_test.clj`, `test/seon/issue_test.clj` | A07, G05, then C06, G12 (callers migrate only after A07 in the same cut) | `process.clj` release |
| **P0 D-delete** | `src/seon/instrument.clj`, `resources/seon/schemas/seon.instrument.edn`, `src/seon/sci/kernel.clj`, `src/seon/fault.clj` (the `carried-receipt` visibility token only), `src/seon/ai.clj`, `src/seon/background.clj`, `test/seon/instrument_test.clj`, `test/seon/ai_test.clj`, `test/seon/background_test.clj` | D01–D04, E05, the E07 rethrow, B05, B07, F21 (background) | `instrument.clj` release |
| P1 fault port | `src/seon/flow.clj`, `test/seon/flow_test.clj` | 0.1, `::stop!`, G16 | the 0.1 probe |
| P2 delivery prep | `src/seon/fault.clj`, `src/seon/error.clj`, `resources/seon/schemas/seon.fault.edn`, `resources/seon/schemas/seon.error.edn`, `resources/seon/schemas/seon.config.error.edn`, `test/seon/cluster/fault_message_test.clj`, `test/seon/cluster/fault_storage_test.clj` | E01 prep, the obligation member, F01's HTML deletion, F07, F08, F22 | P0's `fault.clj` token |
| R1 channel retirement | `src/seon/flow.clj`, `src/seon/cluster.clj`, `src/seon/cluster/boot.clj`, `src/seon/cluster/agent.clj`, `src/seon/render/web.clj`, the C.3 schemas, `test/seon/flow_test.clj`, `test/seon/cluster_test.clj`, `test/seon/cluster/fault_message_test.clj` | C03, A04, A09, E06, F19 (cluster) | P1, P2 |
| R1t | `src/seon/turn.clj`, `test/seon/cluster/turn_test.clj` | C01–C03, A10, B04, E08 (turn), F10 | P1, P2 |
| R2 acquisition | `src/seon/sci/eval.clj`, `resources/seon/schemas/seon.sci.eval.edn`, `test/seon/sci/eval_test.clj`, `test/seon/sci/branch_execution_test.clj`, `test/seon/adoption_diagnostic_test.clj` | E09, B31, F09 | P2 |
| R3 D-classify | `src/seon/sci/kernel.clj`, `src/seon/sci/eval.clj` (the two request sites, after R2), `test/seon/instrument_test.clj` | the D table | P0, P2, R2 |
| R4 E-activate | `src/seon/fault.clj`, `src/seon/error.clj`, `resources/seon/schemas/seon.fault.edn`, `test/seon/turn_test.clj` | E01 activation | R1, R1t, R2, R3 |
| L launcher | `src/seon/flow.clj`, `src/seon/cluster/boot.clj`, `test/seon/flow_test.clj`, `test/seon/cluster/boot_test.clj` | A01, G02 | R1 |
| G-agent | `src/seon/cluster/agent.clj`, `test/seon/cluster/agent_test.clj` | G03 | R1 |
| G-effect | `src/seon/effect.clj`, `test/seon/effect_test.clj` | G01, A03 | P2 |
| T test | `src/seon/test.clj`, `src/seon/test/runner.clj`, `src/seon/plan.clj`, `test/seon/test_test.clj`, `test/seon/test_runner_test.clj` | A02, C04, B09, B10, E08 (test), F15, F21 (plan) | P2 |
| B-platform | `src/seon/schema.clj`, `src/seon/call_preparation.clj`, `src/seon/test/accretion.clj`, `src/seon/schema/admission.clj`, `src/seon/cluster/status.clj`, `src/seon/fn/analyzer.clj`, `src/seon/render/transcript.clj` (B30 only; M retires `branch-errors` later), `src/seon/eval/drive.clj`, `src/seon/web/jvm.clj`, `src/seon/bootstrap.clj`, `script/seon/dev/docstring.clj`, and their tests | B rows, E08 (`call_preparation`), F producer rows in these files | P2 |
| F producers | `src/seon/db.clj`, `src/seon/render.clj`, `src/seon/render/hiccup.clj`, `src/seon/render/ns.clj`, `src/seon/fn.clj`, `src/seon/schema/internal.cljc`, `src/my/program.clj`, and their tests | F producer rows | render slice 2(i) |
| bridge | `script/seon/dev/mcp.clj` | B17–B23 survivors | delete-mcp-bridge-opus lands |
| M | the MCP reply pipeline owner's files, `render/transcript.clj`, the `seon.cluster.eval` schema, `error.clj` (`occurred-since` only, after R4), `test/seon/problems_test.clj` | section M | P2, R4, one-pipeline S2, render slice 2(i) |

**Startable now** (subject to root's grant at launch):
- **S-scripts**: its files are free at `431a9b4ee`.
- **S-process**: when delete-duplicates-opus round 3 releases `process.clj`.
- **P0**: when impl-shape-mirror-malli-opus releases `instrument.clj`.
- **P1 and P2 as read-only investigations only**, writing to `tmp/` with no production edits. P1 runs the 0.1 probe.
  P2 probes the `:db-after` recipient read and the obligation upsert on a `d/with` value.

Not startable: R1, R1t, R2, R3, R4, L, G-agent, G-effect, T, B-platform, F producers, bridge and M. They wait for
their predecessors or holders.

Files shared between cuts are sequenced, never shared:
- `flow.clj`: P1, then R1, then L;
- `fault.clj`: P0, then P2, then R4;
- `error.clj`: P2, then R4, then M;
- `boot.clj`: R1, then L;
- `agent.clj`: R1, then G-agent;
- `sci/kernel.clj` and `instrument_test.clj`: P0, then R3;
- `sci/eval.clj`: R2, then R3;
- `flow_test.clj`: P1, then R1, then L;
- `fault_message_test.clj`: P2, then R1.

**Estimates** (planning, unique per cut):

| Cut | source − / + | schema | tests |
|---|---|---|---|
| S-scripts | −10 / +47 | 0 | +30 |
| S-process | −11 / +14 | 0 | +20 |
| P0 | −40 / +9 | −3 | convert 4 rows; +40 |
| P1 | 0 / +25 | 0 | +40 |
| P2 | −17 / +30 | +4 | +45 |
| R1 + R1t | −300 / +40 | −70 | −150 / +45 |
| R2 | −70 / +15 | −3 | +25 |
| R3 | 0 / +14 (unproved) | 0 | +40 |
| R4 | −6 / +4 | 0 | convert 1 |
| L | −20 / +24 | 0 | +40 |
| G-agent, G-effect | −15 / +26 | 0 | +45 |
| T | −5 / +22 | 0 | +30 |
| B-platform, bridge | −75 / +45 | 0 | +40 |
| F producers | −62 / +31 | 0 | +20 |
| M | −90 / +35 | +2 | +45 |
| **Total** | **about −720 / +380, net ≈ −340** | **−70** | about −150 / +545 |

## 9. Questions and rulings

- **9.1** RULED (owner): root is the default recipient, and faults appear in MCP replies (section M).
- **9.2** Adopted from Astra (E09).
- **9.3** The fault port (0.1). No fork is needed. Production use waits for the 0.1 probe.
- **9.4** Adopted from Astra (G02, cut L).
- **9.5** Follow-up. After G-effect and T land, compare the two join-then-consume watcher shapes line by line, and
  extract one shared function only if they are identical.
- **9.6** RULED by root: stored program call data decides responsibility, and the ambiguous case returns to the
  agent. The three evidence cases are in D.2, and no alternatives remain.

## 10. Evidence and limits

Round-3 source reads were at `431a9b4ee`:
- `impl/protocols.clj:31-35`, `impl/channels.clj:283-306`, `impl/protocols.clj:18-19` and `async.clj:200-211`;
- Malli's `generator.cljc:466-504` (no-generator with and without a cause);
- `error.clj:1313-1326` (message identity), `:1329-1453`, `:1484-1552` (occurrence identity) and `:1571-1616`;
- `transcript.clj:346-402` and `mcp.clj:500-562`, `:629-635`;
- `boot.clj:485-515` and `seon.error.occurrence.edn:1-12`.

Earlier probes still stand:
- `sci.core/stacktrace` lists only interpreted frames;
- the shapes of edamame and `clojure.edn` rejections;
- 32 signatures against 9 notifications;
- the F01 face timings.

Not run: every test, the 0.1 disposer probe, the P2 `:db-after` read and obligation upsert, the R3 reach read, the
G14 and G15 contention measurements, and the F storage timings. These are obligations of the implementing cuts, and
unavailable evidence is not a pass. Source delta **0**; only this file changed.
