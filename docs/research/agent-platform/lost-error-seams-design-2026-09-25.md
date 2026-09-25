---
type: design
status: "round 2 (Opus) after Astra REVISE 668d8aa79 and owner ruling 20:50Z on delivery; awaiting Astra re-review and root rulings on §9"
created: 2026-09-25
scope: One owner change per lost-error class A–G from the 2026-09-25 audit, plus fault delivery through the agent's path
---
# Lost-error seams: one owner change per class

**What exists.** Astra's [lost-errors audit](lost-errors-audit-2026-09-25.md) (`64ae7aca5`, pinned `93711ba43`) found
106 path instances in seven classes. The [review](lost-error-seams-design-review-2026-09-25.md) (`668d8aa79`) asked
for nine corrections to this design. All nine are integrated below, in the sections that own them.

**Data flow.** This design fixes three flows:

- *failure → owning boundary → durable fact + delivered message → policy at the execution owner*;
- *task → real exit → exactly one terminal disposition*;
- *value → one floor bound → two faces*.

**Approach.** Make each owner's contract total using the dependency seams that already exist: Flow's `ProcLauncher`
SPI and the `::flow/error` out port; Malli's throwing `:report` in `-instrument`; `Thread.join`/executor termination
and `ProcessHandle.onExit`; the render owner's safe selection. The per-site copies are then deleted in the same cut.
There is no new registry, supervisor, retry, task framework or second printer.

**Pin.** Lines are at HEAD **`14ccb65f0`**. These files were unchanged between `2d23029cb` and `14ccb65f0` and keep
their round-1 lines: `flow.clj`, `fault.clj`, `instrument.clj`, `cluster/boot.clj`, `cluster/agent.clj`,
`cluster.clj`, `sci/kernel.clj`, `sci/eval.clj`, `await.clj`, `render/value.clj`, `render/web.clj`, `shell/jvm.clj`,
`script/seon/operator.clj`. Every other cited file was re-grepped at `14ccb65f0`.

**Status.** Design only. No source was edited and no test was run.

## 0. Findings that reshape the audit's seams

**0.1 The fault channel is a second, lossy fault route.** Delete it, together with the upstream route it drains.

`var-process` already records every step throw on the proc's own thread (`flow.clj:198-232`, `:290-303`). Beside it
runs a second route: the error fan-out (`:982-1081`); the committer graph with its own policy copy (`:839-889`),
which is where B01 comes from; `CountedDroppingBuffer` (`:742-835`), where C03 comes from; four injected callbacks
(`cluster.clj:3549-3571`, `boot.clj:83-94`); and six `offer!`-with-stderr producers (`turn.clj:5484`, `:5507`;
`web.clj:2336`, `:2936`, `:3546`; `agent.clj:1153-1160`).

Deleting the second route does not make Flow's own error channel lossless. The review (finding 1) is right on
that point, and the seam is:

- Flow's proc loop reports every loop-level failure with `(async/>!! (outs ::flow/error) …)` (`impl.clj:313`, `:318`).
- `outs` is the map the **`ProcLauncher` SPI** receives in `start` (`impl.clj:153-164`).
- `var-process` already reifies that SPI and forwards `start-options` to the inner launcher (`flow.clj:310-318`).

So `var-process` replaces `::flow/error` in the `:outs` it forwards with a per-proc **fault port**. The port is a
channel whose `UnblockingBuffer` `add!*` disposes the report synchronously on the reporting thread, through
`proc-failed!` and then `stop-panicked-graph!`. `RefusingBuffer` (`flow.clj:354-380`) already does side-effecting
admission in `add!*` the same way.

This closes both holes the review names:

- **Capacity.** `>!!` onto an unblocking buffer never drops.
- **Shutdown.** Flow's `stop` closes only the graph's `:error` chan (`impl.clj:179`). The per-proc port is never
  closed, so a proc still unwinding after stop reports into a live disposer.

This uses no fork, no `:xform` assumption and no reader thread, and the graph's error chan stays empty. The flow
docstring reserves `::flow/error` against *init-state* out-ports (`flow.clj:220-223`), which this does not use. What
this does use is the SPI's own freedom to choose the channels it hands to the process it launches.

If recording itself fails, `proc-failed!` already treats that as a panic and stops the graph. **Probe first:** assert
at the REPL that a report from `send-outputs` and one racing `flow/stop` both reach the port.

**0.2 Task ownership stays with each existing owner.** There is no new `await/task`. The review (finding 2) showed
that a `finally`-delivered promise proves only that the body reached that point, and that cancellation can prevent
the body from ever running. Exit proof is therefore the real `Thread.join`, executor termination (`join-graph!`,
`flow.clj:134-160`) or `ProcessHandle.onExit` (`process.clj:158-200`). Disposition stays with the existing
submission CAS (`io-terminal!`, `flow.clj:398-424`) or the caller's own join-then-consume.

Two callers do repeat one shape: *run on a virtual thread, bounded join, a watcher joins then consumes*. They are
`test.clj:272-311` (about 25 lines) and `effect.clj:472-502` (about 25 lines). A shared helper is justified only
after both conversions show that shape is identical, so it is recorded as a follow-up rather than a first step
(§9.5). The lifecycle every owner must dispose is data (A.2).

**0.3 Delivery goes through the agent's path (owner ruling 20:50Z).** Core faults go to root by default, and each MCP
interaction carries the faults that are new since that agent's previous interaction, as data rendered by the one
path. This is section M.

## A. Asynchronous results not consumed on every exit (13)

**1. Seam and missing contract.** `seon.flow/io-terminal!` (`flow.clj:398-424`) settles each submission exactly once
by CAS on `::status`. It has three gaps:

- (a) A throwing `complete!` still gets a success terminal offered in `finally` (`:413-424`), and its exception is
  kept only in an unread `FutureTask` (`:444-465`). This is A01, still present at HEAD.
- (b) `stop-work-launcher!` cancels futures (`:628-630`) and settles running work from the stopping thread
  (`:633-644`). A later terminal from the task then loses the CAS, and the task's real outcome is dropped.
- (c) A `.cancel` before the runnable starts leaves ::running work that never reaches `io-terminal!`.

**2. Owner change: the lifecycle table.** `io-terminal!` stays the one disposer. Every outcome has exactly one owner:

| Outcome | Where it is observed | Disposition (once, by the `::status` CAS) |
|---|---|---|
| body returned (a value or a declared refusal) | the task thread, `execute-io-work!` | `io-terminal!` with `::value` |
| body threw | the task thread's body catch (`:460-462`) | `io-terminal!` with `::throwable` → launcher `proc-failed!` (`:518-519`) |
| `complete!` threw | `io-terminal!`'s own catch around `complete!` (new, separate from the body catch) | the completion offered carries `::callback-throwable`; the launcher disposes it with `proc-failed!`, which delivers the fault and stops the graph on panic. No success report |
| executor rejected | `:469-473` (existing) | `io-terminal!` with `::throwable` |
| stopped before entry | the runnable's first form reads `::active?`, which stop clears (`:632`) | `io-terminal!` with the existing launcher-stopped value, and no body |
| stopped while running | stop **interrupts** the entered thread; it never settles running work | the task thread's own terminal, after exit |
| queued, never executed | stop's CAS `::queued → ::completed` (`:404`) | stop settles with launcher-stopped |

This removes the FutureTask. `execute-io-work!` calls `(.execute executor ^Runnable runnable)`, so there is no
future left unread. Anything escaping the runnable's two catches reaches the thread's uncaught handler, which is E06,
and that handler then reports an owner defect. `::task` and `.cancel` are deleted. Exit is proved by the launcher's
executor terminating (G02).

**3. Migration.** `−` and `+` are physical source lines, estimated. Each range has one owning cut in §8.

| ID | Site at HEAD | Conversion | − / + |
|---|---|---|---|
| A01 | `flow.clj:404-424`, `:444-473`, `:513-524` | the table above; the launcher reads `::callback-throwable` | −10 / +8 |
| A02 | `test.clj:282-311` | keep the thread join as the exit proof. The watcher does `.join`, then `.get`s the task and disposes a late throw through the runner's existing `core-fault!` (`test.clj:2192`), then `release!`. The timely path `.get`s before `release!` (`:290-291` reordered) | −2 / +6 |
| A03 | `effect.clj:472-502` | see G01; one cut | in G01 |
| A04 | `flow.clj:1055-1081`, `agent.clj:1153-1160` | deleted in cut R1 (section C) | counted in C |
| A05, A06 | `operator.clj:553-580` | JDK only, on the operator's own classpath (review finding 7). The losing `CompletableFuture` is consumed with `.handle` into the same terminal map, and joined after the socket closes | −0 / +8 |
| A07 | `process.clj:266-274` | the streamed-output future is joined before `run-process!` returns or throws, including on timeout, after `terminate-subprocess!` | −2 / +5 |
| A08 | `shell/jvm.clj:87-124`, `:330-408` | every capture thread's promise is consumed on every exit; a stdout timeout still reads stderr's terminal. JDK threads are kept | −4 / +8 |
| A09 | `web.clj:2925-2945` | see C.3 (web producers) | in C |
| A10 | `turn.clj:5545-5561` | see C.3 (backstop) | in C |
| A11 | `operator.clj:757-759` | `@census` in `finally`, with the extraction failure as primary and the census failure suppressed | −1 / +3 |
| A12 | `script/seon/dev/dependency_digest.clj:69-83` | stays on JDK primitives (tool classpath, `dev_cache.clj:208-221`); join the output future after the child's exit; an output timeout throws instead of returning `::unavailable` | −3 / +5 |
| A13 | `script/seon/operator_move_test.clj:23-26` | `.waitFor` before `.exitValue`; join the output future with a bound | −1 / +3 |

**4. Class regression.** Rows are added to the existing launcher suite in `test/seon/flow_test.clj`, using the real
`start-work-launcher!`/`submit!`/`stop-work-launcher!`: body value, body throw, `complete!` throw, rejection,
stopped before entry, stopped while running (the host body ignores its first interrupt and waits on an injected
latch), and queued-never-run. Each row asserts one disposition, no success report for a failure, and no release
before the executor terminates. The A02 rows go in `test/seon/test_test.clj` through a real `seon.test/run`: a late
throw after the bound is stored once, and custody is released only after the join. There is no Var redefinition.

**5. Cost.** Deleting the FutureTask removes one allocation per submission. The new `complete!` catch costs nothing
on success. This is not measured yet; the implementing cut times `submit!` → terminal on parent and change (the
round-1 44 ns promise figure measured something else and is withdrawn).

**6. Order.** A01 goes into cut L (the launcher cut) together with G02, after R1 has removed the fan-out from
`flow.clj`. A02 waits for the `test.clj` holder. A05–A08 and A11–A13 are independent of the other classes.

**7. Files.** `flow.clj` (launcher half), `test.clj`, `process.clj`, `shell/jvm.clj`, `operator.clj`,
`dependency_digest.clj`, `operator_move_test.clj`, `test/seon/flow_test.clj`, `test/seon/test_test.clj`.

## B. Catch converts failure to absence, fallback or incomplete evidence (35)

**1. Seam, and what an owner cannot do.** `seon.fault/fault!` (`fault.clj:179-234`) never swallows its own failure:
if the recording does not commit, it panics with the outcome suppressed (`:224-234`). Its gaps are that `record!`
(`:33-160`) is public and total, that its last resort prints `(str fault)` (`:159`, F08), and that its recipient is
not evidence.

The review (finding 8) is right that **no owner change can make an arbitrary catch-and-default impossible**. B is
closed three ways: by deleting each listed catch, by making `record!` private in the E-activation cut (so no caller
can record a fault without applying the policy), and by the class regression below. B's guarantee is therefore per
producer and proved producer by producer; it is not a single owner guarantee.

**2. Per-site rule, with the rejection shapes probed at HEAD.**

- **Core operation.** Delete the catch. The throw reaches its owning boundary: `var-process`, the evaluation
  boundary, or the writer's `fault!`.
- **Declared rejection.** The `try` wraps only the rejecting call and matches that call's actual rejection:
  - edamame: `ExceptionInfo` with `:type :edamame/error` (probed);
  - `clojure.edn`: a bare `RuntimeException` (probed), so the `try` covers `edn/read-string` alone;
  - Malli generators: `:type :malli.generator/no-generator` (`malli/generator.cljc:475-482`, `:501`).
- **Typed unknown.** Keep the unknown and add `:seon.error/throwable failure`; `refusal/diagnostic`
  (`error/refusal.clj:91`) keeps class, message, `ex-data`, chain and Seon frames.
- **Scripts with no connection.** Carry the same `diagnostic` to the operator boundary; cleanup failures become
  `.addSuppressed` on the primary failure.

**3. Migration.** B01 is deleted in R1. Rows marked *probe* are PLAUSIBLE: before editing, the lane shows their
declared rejection case at the REPL, or refutes the finding with evidence.

| Rule | ID and site at HEAD | Transformed producer: typed rejection vs machinery failure |
|---|---|---|
| delete | B02 `schema.clj:247` | a reload failure propagates; no stale predicate is reused |
| delete | B03 `schema.clj:1122-1131` | a basis or canonicalization failure propagates out of the delay |
| delete | B06 `call_preparation.clj:489` | a validator compilation failure keeps its cause as `:seon.error/throwable` in the incoherent-row refusal |
| delete | B07 `ai.clj:868` | a sink failure fails the attempt |
| narrow | B08 `test/accretion.clj:185-192` | only `:type :malli.generator/no-generator` becomes `:skipped-non-generatable`; everything else propagates |
| split | B11 `schema/admission.clj:100` (read: narrow to edamame), `:366` and `:493` (engine: delete) | a lint finding only for declared schema shapes |
| split | B12 `render/ns.clj:134-141` (`read-edn`: narrow to `RuntimeException` around `edn/read-string`), `:142-155` (`schema-form-refs`: **delete** the Malli-walk catch; a failed walk propagates rather than leaving a partial set) | the review's finding 8 split |
| narrow | B14 `shell/jvm.clj:388` | only the broken-pipe `IOException` after reaping |
| throwable | B04 `turn.clj:3406-3416`, B05 `sci/kernel.clj:543-553` (existing-refusal branch), B09 `test/runner.clj:949-954`, B10 `test.clj:105-106` and `:1469`, B13 `render/value.clj:309` and `:341`, B15 `web/jvm.clj:349`, B16 `cluster/status.clj:44`, `:92`, `:162` | a typed unknown with the full cause |
| delete | B24 `script/seon/dev/docstring.clj:303-307` | a parse failure is a finding, never `clean? true` |
| probe | B26, B27 `fn/analyzer.clj:807`, `:852`; B28 `print.cljc:108`, `:1081`; B29 `repl.clj:89`, `:124`, `:166`, `:463`; B30 `render/transcript.clj:1201`; B31 `sci/eval.clj:1199`; B32 `eval/drive.clj:125`; B33 `web/jvm.clj:427`; B34 `operator.clj:466`; B35 `bootstrap.clj:94` | narrow to the declared reader rejection if one exists; otherwise delete |
| script | B17–B23 `script/seon/dev/mcp.clj` (HEAD: `:113`, `:201`, `:221-222`, `:327`, `:363`, `:405`, `:555`, `:566`, `:607`, `:645`, `:707`, `:754`, `:807`, `:951`, `:982`, `:1012`, `:1018`), B25 `operator.clj:295` | whole cause to the bridge's or operator's boundary. **`mcp.clj` is held by delete-mcp-bridge-opus**, whose rewrite deletes or moves most of these catches; its lane converts the survivors |

Estimate: about −75 / +45, excluding the render-slice rows (B13, B28, B29), which are counted in that slice.

**4. Class regression.** Parameter rows in `test/seon/cluster/fault_storage_test.clj`. A real core operation throws a
nested cause with namespaced `ex-data` and a Seon frame. Assert that the full chain is stored, or that an explicit
storage-failed panic occurs. The other rows are: a declared edamame rejection stays a rejection; a Malli
`no-generator` stays a skip while a generator machinery throw stays red; a typed unknown carries the chain; and a
cleanup failure is suppressed on the primary. Every changed producer adds one row there or to its own existing suite.

**5. Cost.** Only failure paths change.

**6. Order.** After cut P2, which makes the receipt carry delivery. The rows in held files (`turn.clj`,
`sci/eval.clj`, `test.clj`, `runner.clj`, `mcp.clj`, `call_preparation.clj`) wait for their holders.

**7. Files.** Those named in the table.

## C. Exhaustion becomes no work, or its terminal fault is not durable (6)

**1. Seam and missing contract.** The owner is `fault!` again; these producers bypass it.

- **C01, C02.** `opening-deferred?` answers `true` when the dial is absent (`turn.clj:2739`), and both of its
  consumers treat that as ordinary deferral. `:2793` branches on any truthy answer, while `:2913` propagates an error
  only if one is returned.
- **C03.** Exhaustion and backstop faults are offered to the dropping channel (`turn.clj:5466-5512`).
- **C04.** A request deadline before an unadmitted batch leaves only pending members (`test.clj:2308`), and
  `plan.clj:716-725` drops the incomplete result.
- **C05, C06.** Process refusals become nil (`dependency_digest.clj:78-82`, `issue.clj:77-88`).

**2. Owner change.** The typed error is one value at its producer, and every consumer handles it:

- `opening-deferred?` answers the declared missing-dial error. `:2793` and `:2913` both branch on it.
- **The first deferral records the fault once.** The writer's `opening` stops a second *message* per signature, but
  every call still increments the occurrence count (`error.clj:1392`). The evidence that prevents re-disposition is
  therefore a fact the scheduler already holds, the newest unanswered wake. The scheduler faults only when no
  occurrence of this agent's `:seon.turn.loop/episode-limit-missing-error` has `:seon.error.occurrence/last-at` at or
  after that wake's transaction instant. That is one indexed query over the agent's occurrences. The same rule
  covers a pre-exhausted agent at arm (C02), keyed on the exhaustion error.
- C03 exhaustion runs inside the turn step on the proc thread, so it calls `fault!` directly. A panic throws into
  `var-process`, whose `proc-failed!` stops the graph.

**3. Migration.** Cut R1 retires the fault channel, which gives every producer its own execution owner:

| Producer at HEAD | Its execution owner and panic action |
|---|---|
| `turn.clj:5466-5489` write-refusal exhaustion | the turn proc: `fault!` throws → `var-process` stops the agent graph |
| `turn.clj:5491-5561` backstop (A10) | the agent graph. `var-process` adds `::stop!` (its `stop-panicked-graph!` closure over control and graph-ref) to init args; the step hands it to `arm-turn-completion-backstop!`; the backstop runnable calls `fault!`, and on panic calls `::stop!` and puts the failure on `failure-channel` |
| `web.clj:2322-2345` page derivation | the render proc: `fault!`; a panic stops the render graph, per the law |
| `web.clj:2925-2945` feed writer (A09) | the SSE feed: `fault!` unless the failure is the declared client close (`painting` false *and* an `IOException` on write); on panic `close-sse!` (the existing `finally`) and a failed feed status |
| `web.clj:3540-3556` data read | the HTTP request: `fault!`; a panic becomes the declared error response |
| `agent.clj:1153-1160`, `boot.clj:28-47` launcher join | deleted: per-proc fault ports need no join |
| `cluster.clj:3341-3364`, `:3542-3600`, `:3620-3630`, `:3711-3712` wiring and fan-out stop | deleted; the report mult and monitor taps survive as `start-report-fanout!` |

Deleted from `flow.clj`: `:742-900` (dropped-fault family, buffer, committer, 159 lines), `:933-980` (loss reporter,
committer error join and graph definition, 48), and the error halves of `:982-1108` (about 55 of 127). Retained:
`monitor-graph` (`:902-931`, 30 lines, report view only) and the report fan-out (about 40). **Gross −262 in
`flow.clj`**, plus about +18 for the fault port in `var-process` (0.1). Net about −244. That replaces round 1's
"−370", which the review showed was not what the named interval can yield.

Schemas deleted: `seon.flow.edn:28-116` (fan-out and committer members) and `:190-196`; the `dropped-fault` members
in `seon.error.edn:108`, `:144-149` and `:272`, and in `seon.error.occurrence.edn:40-41`; plus `seon.boot.edn:75-77`,
`seon.cluster.wake.edn:25`, and `seon.render.web.edn:21`, `:117`. That is about −70 lines.

Tests converted or deleted in the same cut: the committer and overflow rows in `test/seon/flow_test.clj` (the
`:698`, `:793` and `:1136` `record!` callers), `test/seon/cluster_test.clj:121`, and
`test/seon/cluster/fault_message_test.clj:23`.

| ID | Conversion | − / + |
|---|---|---|
| C01, C02 | `turn.clj:2739`, `:2793`, `:2913`: typed error; faulted once per unanswered wake | −2 / +14 |
| C03 | R1 as above; `turn.clj` offers → `fault!` | −24 / +10 |
| C04 | `test.clj:2308`: the typed await timeout goes on the run row; `plan.clj:716-725` propagates an incomplete result | −3 / +8 |
| C05 | with A12 | counted there |
| C06 | `issue.clj:77-88`: a nonzero exit returns the process refusal | −1 / +3 |

**4. Class regression.** Parameter rows in `test/seon/cluster/turn_test.clj`, run on canonical branch worlds with
no-provider:

- a missing dial with an unanswered wake;
- a pre-exhausted agent re-armed;
- repeated derivation over the same wake, asserting occurrence count 1 and one message;
- a stalled owner: the recipient's graph is paused, and the fault is still durable with its message committed;
- a completed episode, which produces no fault.

In `test/seon/flow_test.clj`: more than 100 finite reports from one proc, a `send-outputs` failure, a report racing
`flow/stop`, and a stalled or failing recorder. Each must either be stored or leave a panicked, stopped graph with
emergency evidence.

C04 and C06 rows go in `test/seon/test_test.clj` and `test/seon/issue_test.clj`.

**5. Cost.** Each agent graph loses its blocked fan-out thread, and each fault skips a channel hop. The deferral
query runs once per deferral, not per pass.

**6. Order.** P1 (the fault port) and P2 (delivery) come before R1. C01 and C02 wait for the `turn.clj` holders.

**7. Files.** `flow.clj` (fault half), `cluster.clj`, `boot.clj` (the join and the arm-request callback), `agent.clj`
(the join), `web.clj` (three producers), `turn.clj`, `test.clj`, `plan.clj`, `issue.clj`, the schema files above,
and the named tests.

## D. Returned value escapes the declared error alternatives (4)

**1. Seam and missing contract.** `seon.instrument/compiled-wrapper` (`instrument.clj:624-726`). Under `:record`, its
outer catch returns the boundary refusal as the function's value (`:715-726`), outside Malli's output check. D01 is
still present at HEAD. Malli already enforces refusal: `-instrument-f` checks input before entry and output after
the body (`malli/core.cljc:2202-2231`, `befcd302a`), and the wrapper's throwing `:report` (`:705-714`) makes a
violation throw. Nothing else is needed for enforcement.

**2. Owner change.** Delete the outer catch (`:715-726`) and keep the throwing report. The wrapper's `policy` then
needs only `:seon.config.error/max-evidence-bytes`. Deciding **who is responsible** for a refusal is a separate,
harder problem. The review (finding 5) is right that "input means agent" is wrong, and at the REPL I refuted the
provenance source I would have reached for first. On a scratch `sci.core/init` context, `sci.core/stacktrace` lists
only interpreted frames: a direct `(h/f 1)` and a nested `(h/g)` → `h/f` both end at the interpreted caller (probe
in §10). So D is split:

- **D-delete** (cut P0). Delete the catch; D02–D04; and the evaluation boundary rethrows a throwable carrying a
  receipt anywhere in its cause chain, using `fault/carried-receipt` (`fault.clj:171-177`, made public), at the one
  normalizer `kernel/failure-value` (`kernel.clj:526`), which `kernel/invoke` (`:658`) and `sci/eval.clj:4122`,
  `:4442` all use. Until D-classify lands, a host output violation under `:record` is **returned** to the agent
  rather than stored, which is a named, temporary gap. Under the default `:panic` the wrapper already threw, so
  nothing changes there.
- **D-classify** (cut R3, after §9.6 is ruled). The responsibility table, applied once in `failure-value`, which
  every view reaches (MCP, tests and web enter through `sci/eval`):

| Malli report (`:seon.instrument/check`, `:seon.error/member`) | Evidence | Responsible → disposition |
|---|---|---|
| `:input`/`:arity` on V | V is called by the agent's own code (§9.6: V ∈ `:seon.fn/calls` of the evaluated form's analysis or of branch-authored rows) | the agent → returned value |
| `:input`/`:arity` on V | otherwise (a host caller supplied bad arguments) | core fault of the caller → `fault!` |
| `:output`/`:guard` on V | V's row differs on the agent's branch (authored there) | the agent → returned |
| `:output`/`:guard` on V | V's row is the default program row | core fault → `fault!` |
| `::m/invalid-schema-at-call` (validator threw) | the contract's schema row is branch-authored | the agent → returned |
| `::m/invalid-schema-at-call` | otherwise | core fault |
| any throwable carrying `:seon.fault/recorded` | already disposed | rethrow, never recount |
| an interrupt or bound firing | the evaluation's own bound | the declared timeout |

The provenance is Seon's existing program data (row identity per branch, and stored `:seon.fn/calls`). It is read
only on the failure path, and no call site is marked. This is the same boundary for every view; there is no
transport-specific classifier.

**3. Migration.**

| ID | Conversion | − / + |
|---|---|---|
| D01 | delete `instrument.clj:715-726` and the commit-fault! checks at `:470`, `:491-499`, `:860-871`, `:901-902`; `seon.instrument.edn:42-44` | −40 / +1 |
| D02, D03 | `ai.clj:114`, `:120`: declare the callee's `:seon.render.value/missing-root-identity-error` alternative; delete it again when render slice 2(i) removes that arm | +2 |
| D04 | `background.clj:59-72`: return the `db/pull` error before success projection | +2 |
| E05, E07 | receipt rethrow (P0) and the table (R3) in `kernel.clj:526-572` | +4 / +14 |
| test | `test/seon/instrument_test.clj:195`, `:344`, `:422`, `:1308` stop calling `record!` and assert the throw | ±0 |

**4. Class regression.** In `test/seon/instrument_test.clj`, one real armed composition under both policies: an
authored direct bad call, a nested host bad call, authored bad output, host bad output, a validator that throws, and
a receipt-carrying panic. Parameter rows cover the rootless attempt unit and the background read error. The same
case runs through the MCP SCI entrance and `seon.test/run` as thin adapter checks, asserting an identical
disposition (review finding 5 and its view proof).

**5. Cost.** The `try` frame goes from every armed call. Classification runs only on refusals: one set lookup and
one row-identity comparison.

**6. Order.** P0 is independent. `instrument.clj` is held by impl-shape-mirror-malli-opus (shape callers only), so
the whole file is granted after that lane releases it. R3 comes after §9.6 and after E-activation's writer.

**7. Files.** `instrument.clj`, `seon.instrument.edn`, `sci/kernel.clj`, `fault.clj` (`carried-receipt` public: one
token), `ai.clj`, `background.clj`, `test/seon/instrument_test.clj`.

## E. Fault classification, custody or delivery is wrong (9)

**1. Seam and missing contract.** `seon.error/commit-call` (`error.clj:1329-1453`). It infers responsibility from
`interrupted? (some? :seon.error/exception-class)` (`:1393`) and routes a core throwable to the executing agent as
`:your-run` (`:1433`). With `escalate-to` absent (`:1436-1442`), unattributed faults are delivered to nobody. The
receipt checks storage only (`fault.clj:212`), and the docstring claim "root by default" (`:188`) is false.

Live evidence (round 1, PID 37460): `escalate-to` is absent, and there are 32 signatures but 9 notification ids.
The review notes that this count is not a per-fault delivery proof. Cut P2's regression gives that proof per fault.

**2. Owner change, as preparation then activation** (review finding 4). Every cut loads without a later cut's repair.

- **P2, compatible.**
  - `commit-call` also returns the recipient it messaged, or the recipient already open for that signature.
  - `record!`'s tuple accretes that recipient, and `fault!`'s receipt accretes `:seon.fault/delivered-to` (an
    optional member in `seon.fault.edn:1-34`).
  - Ship `:seon.config.error/escalate-to "root"` (owner ruling) and make `fault!`'s docstring true.
  - `seon.fault/fault-detached!` is **not** added. Every detached producer has a named execution owner (C.3).
  - The policy is unchanged.
- **E-activate (cut R4), atomic, with whole-file grants for `fault.clj`, `error.clj`, `instrument.clj` and the tests
  below.**
  - `record!` becomes `defn-`.
  - `commit-call` becomes core-only: the `:your-run` arm and the `interrupted?` inference are deleted (`:1393`,
    `:1433`).
  - `policy` requires `committed? ∧ delivered-to`; an undeliverable fault panics under both modes.
  - The remaining `record!` callers are converted in this cut: `test/seon/turn_test.clj:251`, and whatever
    `instrument_test.clj` rows are left.
  - Precondition: R1 (cluster and boot callers), R2 (`sci/eval` acquisition) and R3 have landed. `rg
    "fault/record!" src script test` must return only `fault.clj`.

**3. Migration.**

| ID | State at `14ccb65f0` | Conversion |
|---|---|---|
| E01 | confirmed (`error.clj:1393`, `:1430-1442`) | P2 + R4: about −6 / +16 in `error.clj`/`fault.clj`, +1 in `seon.config.error.edn`, +2 in `seon.fault.edn` |
| E02, E03 | superseded: `test.clj:2192-2203`, `:2253` use `fault!` (`4c8e3588e`) | none (A02 remains) |
| E04 | superseded: `sci/eval.clj:2481-2482` binds its own custody (`e8cada147`) | none |
| E05, E07 | confirmed / resolved in D | P0 / R3 |
| E06 | confirmed (`boot.clj:342-381`) | `record-uncaught!` calls `fault!` with the carried environment. A panic **cannot** stop a graph here: the failing thread belongs to no graph, and it is already dead. After A/G, a Seon task reaching this handler is itself an owner defect, so the stored fault names the thread and the missing owner. Without an environment, stderr emergency evidence stays, per the storage-fails-panics law. About −14 / +6 |
| E08 | confirmed (`turn.clj:3406-3443`; `call_preparation.clj:1272`, `:1291`; `test.clj:1469`) | the per-site classifier `analyze-submitted` (`turn.clj:3434-3442`) is deleted. The phase boundary returns only declared agent-mistake alternatives and hands every other failure to `fault!`. The `call_preparation.clj` default-supplier failure and the `test.clj` LinkageError follow the same rule at their producers. About −9 / +8, across three files |
| E09 | confirmed (`sci/eval.clj:2437-2496`) | cut R2, §9.2 option 1 (the review's opinion, adopted). `::agent-mistakes` return in the acquiring agent's opening. `::acquisition-refusals` are classified at their producer: an authored row that fails to acquire is the agent's, while an installed-program, compiler or configuration failure goes to `fault!`. Deleted: the direct `commit-tx` writer (`:2443-2486`), the `commit-fault!` branch (`:2487-2495`), the `config/defaults` fallback (`:2448-2450`), and the callback key. The count unit is lines matching the literal `:seon.flow/commit-fault!`: **19 in `sci/eval.clj`**, plus `seon.sci.eval.edn:244-246`. About −70 / +15 |

**4. Class regression.** Rows in `test/seon/cluster/fault_message_test.clj`, on a disposable branch with a root agent:

- one nested platform failure as a Throwable and as a flat core error, each with and without executing-agent
  attribution; assert the steward or root recipient in the receipt, the occurrence and message in one transaction,
  and the full cause;
- a notification already open for the signature, which gives the same recipient and no second message;
- a removed steward, which falls back to root; a missing root, which panics as undeliverable;
- storage refused, which panics;
- a receipt-carrying throwable, which is not recounted;
- under `:panic`, the failing proc's graph is stopped (via `var-process`);
- **no paid turn**, with the effective root provider read on the target branch (review opinion on 9.1);
- an invalid authored call returns with no occurrence.

**5. Cost.** On the fault path only, one lookup of the recipient in transaction data already built.

**6. Order.** P2, then R1 ∥ R2 ∥ R3, then R4. `sci/eval.clj` is held by fix-read-sink-opus and
fix-one-reader-errors-opus.

**7. Files.** `fault.clj`, `seon.fault.edn`, `error.clj` (`commit-call` only), `seon.config.error.edn`, `boot.clj`
(`record-uncaught!` and the arm-request callback, in R1), `sci/eval.clj` and `seon.sci.eval.edn` (R2), `turn.clj`,
`call_preparation.clj`, `test.clj` (E08), and the tests named above.

## M. Delivery through the agent's path: faults in MCP replies (owner ruling 20:50Z)

**What exists.** Core faults are durable occurrences with a message to their recipient (E). The MCP `runtime_status`
shows open faults through `mcp-runtime-observation` (`cluster.clj:659-740`). It clips with `take observation-top`
and `bounded-summary` (`:629-647`, `:691`, `:719`), a presentation cut that render slice 6 already deletes. MCP
evaluation replies carry no faults.

**Design.** An MCP reply is a view of the calling Seon agent's evaluation (AGENTS.md: the Seon agent is the
perspective). The faults it shows are therefore *the core faults committed since this agent's previous recorded
evaluation*, derived from facts. Nothing is held per session:

1. **Data function** `seon.problems/faults-since` (+about 20 lines, `seon.problems.edn` +8). Input: the database and
   a basis-t. Output: `{:seon.problems/basis-t t, :seon.problems/new-fault-count n,
   :seon.problems/undelivered-count u, :seon.problems/new-faults [<error notice units>]}`.
   - It reads occurrences committed after `t` through `seon.db`'s since view (`d/since`, which `db.clj:134` already
     serves), so its cost follows new occurrences.
   - Each fault is the existing `seon.error/notice` unit (`error.clj`, declared AI renderer), carrying its signature,
     operation, message, last-at and `delivered-to`.
   - `undelivered-count` counts new occurrences with no message. After R4 it is always 0, because such a fault
     panics, so a non-zero value shows faults recorded before R4.
2. **Basis.** The agent's previous recorded evaluation's `basis-t`. The one REPL path records every evaluation
   (AGENTS "the one REPL path"). If there is none, the basis is the agent's branch creation. A lost or first basis
   shows faults again, which is safe.
3. **Rendering.** The reply renders the evaluation unit and the faults unit together through the one `units`
   entrance of the [render path](one-render-path-design-2026-09-25.md), sharing its one budget B:
   - the counts are exact integers;
   - the vector is cut by the floor, with its elision naming the bound, the count and the requery
     `(seon.problems/faults-since db t)`;
   - an empty result renders as absence, with no key.
   `runtime_status` renders the same unit over all open faults, which replaces `observation-top`/`bounded-summary`
   (render slice 6 row 9).
4. **Placement.** The reply assembly's owner is being moved: `mcp-project`/`mcp-valf` (`cluster.clj:388-535`) is
   rewritten by render slice 2(i), and design-one-pipeline-opus (S2) routes MCP SCI and JVM evaluations through the
   agent pipeline. M therefore lands as **one line in that pipeline's reply unit list**, plus the data function.
   It adds no bridge code: `script/seon/dev/mcp.clj` renders what it receives.

**Class regression.** Rows in `test/seon/problems_test.clj`:

- two evaluations by one agent with a fault committed between them; the second reply's unit names it and the first's
  does not;
- more faults than B holds, which gives exact counts, a cut, and a requery that returns the remainder;
- an agent with no prior evaluation.

One MCP adapter check asserts that the reply contains the same unit text.

**Cost.** One since-read per reply, proportional to occurrences committed since the basis (usually zero). Render cost
stays under B.

**Order.** P2 (so `delivered-to` exists), then the data function; the reply line waits for the MCP reply owner (render
slice 2(i) and one-pipeline S2).

## F. Arbitrary value expands before the single presentation bound (23)

**1. Seam and missing contract.** `seon.render.value/prepare` (`value.clj:621-668`) bounds only the AI face. HTML
visits the whole value (`:446-576`), map keys are rendered with the HTML face (`:537-541`, F03), and window ordering
prints every key (`:297-304`, `:413`, F04). **Today's AI face is not wholly safe either:** composite keys and full
printed ordering both run before the budget. So no producer row closes F before render slice 2(i). The review is
right here (finding 6).

F01 at HEAD (`error.clj:460-492`): `prepare-result` renders the HTML face into text as `complete` (`:485`) and
stores it as `:seon.error/data-blob`. Round 1 measured that face at 2,113 ms and 1.94 M characters for a 50,000-entry
vector, against 3.4 ms and 1,391 characters for AI. That measures the *old* detour, not any replacement.

**2. Owner change.** Render slice 2(i) is the owner: one held source, safe structural selection under one B, one tree
and two faces (render design §3, §6 row 2). This class adds two things.

- **Producer rule.** A diagnostic carries its offending value as a value (`:seon.error/offending` or its declared
  member) plus fixed prose, never `pr-str` inside a message. The floor bounds the value whenever anything prints it.
- **F01, settled by evidence ownership rather than a new artifact path** (finding 6).
  - `:seon.error/data-blob` has **two producers with different content**: `prepare-result`'s complete-HTML text
    (`:485-487`) and `evidence-stage`'s whole admitted evidence (`:1455-1473`, staged when the fact is capped). Its
    readers are `error.clj:659-660` (reuse), `:681` (`value`), `:1403-1447` (the occurrence link) and
    `cluster/status.clj:141` (size accounting). None reads the HTML text as HTML.
  - So `prepare-result` stops producing `data-blob`: its `complete` line and `blob/put!` are deleted, and its
    contract output drops the member. `evidence-stage` becomes the one producer of `data-blob`, under its existing
    contract. `:seon.error/shown` stays the floor's AI text (exact saved bytes). `:seon.error/result-id` still binds
    the live subject through `bind-result!`, so exploration of a live subject goes through the render path's
    continuation, and a gone subject answers `:gone` (render design §3g).
  - No raw admission is added. `evidence-stage`'s own `full-edn` admission (`:640-664`) is O(N) storage work under
    `max-evidence-bytes`. It is measured separately (time and allocation) and is not called presentation.

**3. Migration.**

| IDs at HEAD | Conversion | − / + |
|---|---|---|
| F02, F03, F04, F12 (`web.clj:795-798`), F13 (`repl.clj:491`), F18 (`problems.clj:587-633`) | render slices 2(i) and 4, counted there | 0 here |
| F01 `error.clj:460-492`, `:657-660` | as above | −6 / +1 |
| F07 `error.clj:1110`, `:2013`, `:2056`; F22 `:892` | fixed prose, value in data; the elision HTML goes through the floor | −10 / +4 |
| F08 `fault.clj:159` | a fixed message; the fault stays in the tuple | −1 / +1 |
| F05 `render/hiccup.clj:280` | the invalid tag's head becomes the offending value | −2 / +1 |
| F06 `db.clj` (9 sites), F09 `sci/eval.clj:3589`, `:3637`, `:3793`, F10 `turn.clj:3704`, `:3753` (keep `:2200`: exact source bytes), F11 `render.clj`, F14 `accretion.clj:136-138`, `:370-375`, F15 `test.clj:1944`, `runner.clj:398`, `:986-988`, F16 `fn.clj:58-59`, F17 `schema/internal.cljc:469-523`, `admission.clj:409-410`, F19 `cluster.clj:3470`, `status.clj:177`, F21 `background.clj:55`, `my/program.clj:265`, `plan.clj:1367`, F23 `render/ns.clj` (re-grep: that file changed in `a2ee4f38f`) | the producer rule, scripted once across the list (`pr-str` inside `str` inside an error constructor → a value member), then linted and loaded, with the residue fixed by hand | about −60 / +30 |
| F20 | **retired**. After `1ae366d7f`, `mcp.clj` passes strings through and JSON-encodes other content; the round-1 row cited a different function | 0 |

**4. Class regression.** The render path's §3h rows, plus these rows in `test/seon/render/value_test.clj`, in one
suite:

- no unsafe realization across the error, AI and HTML views of one nested failure value (a large string, a composite
  key, and a finite lazy tail that throws past its prefix);
- an exact round trip for a supported, retained, finite evidence blob;
- explicit unavailable evidence beyond `max-evidence-bytes`;
- exact saved shown bytes.

These go through the agent's read → eval → record → print path, with one MCP adapter check. Selection and storage
are timed separately, with allocation.

**5. Cost.** F01 removes one HTML-face render per recorded error (the probe figure is an upper bound on what is
removed). The storage cost stays O(N), is bounded by `max-evidence-bytes`, and is measured.

**6. Order.** F02–F04 go in render slice 2(i). The producer rows land with or after it, and are not claimed as
closing F earlier. F01, F07 and F22 in `error.clj` follow P2 in the same file-owner sequence.

**7. Files.** `error.clj`, `db.clj`, `render/hiccup.clj`, `render.clj`, `accretion.clj`, `fn.clj`,
`schema/internal.cljc`, `admission.clj`, `status.clj`, `background.clj`, `my/program.clj`, `plan.clj`, `render/ns.clj`,
`cluster.clj`, `sci/eval.clj`, `turn.clj`, `test.clj`, `runner.clj`. Many of these are held (§8).

## G. Unbounded wait, or cancellation mistaken for exit (16)

**1. Seam and missing contract.** `seon.await/await!` (`await.clj:106-153`) bounds an *observation*, and its
docstring leaves cleanup to the caller (`:122-123`). It stays an observation operation (review finding 2).
Cancellation and exit belong to the execution owner.

The review found two gaps in round 1 (finding 3):

- **The real stop caller discards the launcher's result.** `boot/stop!` calls `(some-> … flow/stop-work-launcher!)`
  at `boot.clj:499`, ignores the returned timeout, then releases the branch and store (`:500-502`).
- **A `Delay` has no timed deref.** Delays are `IDeref`/`IPending` only (`clojure/lang/Delay.java:18`), so
  `agent.clj:1313-1320` cannot be bounded through `await!`.

**2. Owner changes.**

- **G02 (cut L).** Launcher tasks run on the launcher graph's own thread-per-task executor, which `start-graph!` is
  given as `::executor` (+3 lines; today they use the root `:io` executor, `flow.clj:608`). `join-graph!`'s
  `awaitTermination` (`:151-159`) then proves every handler thread has returned. Stop only interrupts (A.2).
  **`boot/stop!` checks the launcher's result before any release.** On a typed timeout it throws a refusal carrying
  the timeout, which the existing catch (`boot.clj:512-514`) turns into instance retention. The branch and store are
  not released, and a restart refuses. A late handler therefore never uses a released connection.
- **G03.** The arm and stop owners publish completion as a `promise`, delivered in the owning operation's `finally`
  by the thread that runs it, instead of a `delay` that any dereferencer may end up running. Waiters `await!` that
  promise under `:seon.config.agent/turn-completion-backstop-ms`. On timeout they throw with the release mark
  retained, as `agent.clj:785-791` already promises.
- **G01 / A03 (`effect.clj:472-502`).** The handler runs on a virtual thread (the `test.clj` shape). The
  bounded join returns the typed timeout, and the effect row keeps its claim. A watcher joins the thread, then
  consumes the outcome: a late throw goes to `fault!`, a late value to `settle-value!`'s interrupted-late branch.
  Only then is the claim released, and a re-request on the same effect id refuses while it is held.
- **Subprocesses** use `process/terminate-subprocess!` and `onExit` (`process.clj:158-200`) where that owner is on
  the classpath. Survivors keep their resources, and the caller fails naming them. The tool-classpath and operator
  scripts stay on JDK `Process.waitFor`/`onExit` (finding 7). A07/G05 must land before any caller migrates onto
  `run-process!`.

**3. Migration.**

| ID | Conversion | − / + |
|---|---|---|
| G01, A03 | as above | −12 / +16 |
| G02 | `flow.clj:575-675` + `boot.clj:499` in cut L | −8 / +10 |
| G03 | `agent.clj:796`, `:1085`, `:1308-1320` delay → promise; the waiters `await!` it | −3 / +10 |
| G04 | with A08 | counted there |
| G05 | with A07 | counted there |
| G06–G10 | `operator.clj:553-580`, `:616-624`, `:901-902`, `:1513-1514`: `.destroyForcibly` then `(.waitFor child bound unit)`; fail naming the survivor; delete the output file only after proved exit. JDK only | −0 / +12 |
| G11 | with A12 (JDK) | counted there |
| G12 | `issue.clj:79`, `test/cache.clj:41`, `:190` → `run-process!` (these run in the main JVM) | −8 / +6 |
| G13 | `operator_move_test.clj:24-26`, `:111-112` | −1 / +3 |
| G14, G15 | `sci/eval.clj:3106`, `:3217`; `call_preparation.clj:614-617`; `db.clj:3772` | **measure first**, as the review asked: contended acquisition's excess over the declared evaluation deadline, not only a hang. Convert only if it exceeds the deadline |
| G16 | `flow.clj:278`: no production proc declares `:compute` (`rg` at HEAD), so `var-process` accepts only `:io`, making Flow's timed-get compute path (`impl.clj:258-260`) unreachable | −1 / +1 |

**4. Class regression.**

- `test/seon/cluster/boot_test.clj` exercises the real stop caller: a launcher handler ignores its first interrupt
  and waits on an injected latch; `boot/stop!` refuses and the instance, branch and store are retained; the latch is
  released; the late failure is stored once; a second stop then releases.
- `test/seon/effect_test.clj` rows: a re-request refused while held; a late throw consumed once; no double settlement
  by the waiter and the watcher.
- `test/seon/cluster/agent_test.clj` rows: a blocked arm under stop, which gives a bounded refusal with the mark
  retained.
- `test/seon/cluster/subprocess_test.clj` rows: a survivor keeps its ownership.
- The script entrances are proved on their own classpaths: `bin/seon` operator paths, and `dev_cache` loading
  `dependency_digest`.

**5. Cost.** Stop pays one extra `awaitTermination`, bounded by the existing backstop. Arming delivers one promise.

**6. Order.** Cut L (G02 + A01) comes after R1 in `flow.clj`, and waits on the `boot.clj` holders (fix-boot-cost-opus,
fix-custody-round4-opus). G03 waits for `agent.clj` (fix-custody-round4-opus). G12 waits for `process.clj`
(delete-duplicates round 3).

**7. Files.** `flow.clj`, `boot.clj`, `agent.clj`, `effect.clj`, `shell/jvm.clj`, `process.clj`, `operator.clj`,
`issue.clj`, `test/cache.clj`, `dependency_digest.clj`, `operator_move_test.clj`, and the test files named above.

## 8. Cuts, order and file ownership (rebased on `14ccb65f0`)

Each cut owns whole files for its duration (there is no hunk sharing), loads on its own, and needs no later cut's
repair. Root grants files from the current ledger just before each launch. "Holder" lists the rows that held a file
at `14ccb65f0` according to `tmp/orchestrator/file-ownership.md`.

| Cut | Owns (whole files) | Lands | After | Holder at writing |
|---|---|---|---|---|
| P0 D-delete | `instrument.clj`, `seon.instrument.edn`, `sci/kernel.clj`, `fault.clj` (one token), `ai.clj`, `background.clj`, `instrument_test.clj` | D01, D02–D04, E05, the E07 rethrow, B05, B07, F21 (background) | — | `instrument.clj`: impl-shape-mirror-malli-opus |
| P1 fault port | `flow.clj` | 0.1 (the fault port in `var-process`), G16 | — | free |
| P2 delivery prep | `fault.clj`, `error.clj`, `seon.fault.edn`, `seon.config.error.edn`, `fault_message_test.clj` | E01 prep, F08, and F01/F07/F22 in a second commit | — | free |
| M1 | `problems.clj`, `seon.problems.edn`, `problems_test.clj` | `faults-since` | P2 | `problems.clj`: fix-boot-cost-opus |
| R1 channel retirement | `flow.clj`, `cluster.clj`, `boot.clj`, `agent.clj`, `web.clj`, the seven schema files in C.3, `flow_test.clj`, `cluster_test.clj` | C03, A04, A09, A10 (needs `turn.clj`: split R1t), E06, the `cluster.clj` F19 row | P1, P2 | `boot.clj`, `agent.clj`: fix-custody-round4, fix-boot-cost |
| R1t | `turn.clj`, `turn_test.clj` (cluster) | C01–C03, A10, B04, E08 (turn), F10 | P1, P2 (R1 and R1t land together or R1t first: the offers call `fault!` directly) | fix-read-sink, fix-turn-hang r5 |
| R2 acquisition | `sci/eval.clj`, `seon.sci.eval.edn` | E09, B31, F09 | P2 | fix-read-sink, fix-one-reader-errors |
| R3 D-classify | `sci/kernel.clj`, `instrument_test.clj` | the D table | P0, §9.6 ruling | — |
| R4 E-activate | `fault.clj`, `error.clj`, `turn_test.clj` (root test dir) | E01 activation | R1, R1t, R2, R3 | — |
| L launcher | `flow.clj`, `boot.clj`, `boot_test.clj`, `flow_test.clj` | A01, G02 | R1 | as R1 |
| G-agent | `agent.clj`, `agent_test.clj` | G03 | R1 | fix-custody-round4 |
| G-effect | `effect.clj`, `effect_test.clj` | G01, A03 | P2 | fix-cost-capabilities, delete-closure-walks |
| T test | `test.clj`, `test/runner.clj`, `plan.clj`, `test_test.clj`, `test_runner_test.clj` | A02, C04, B09, B10, E08 (test), F15 | P2 | impl-world-injection, fix-test-failure-facts, fix-boot-cost (runner) |
| S process | `process.clj`, `issue.clj`, `test/cache.clj`, `subprocess_test.clj` | A07, G05, C06, G12 | — | `process.clj`: delete-duplicates r3 |
| S scripts | `shell/jvm.clj`, `operator.clj`, `dependency_digest.clj`, `operator_move_test.clj` | A05, A06, A08, A11–A13, B14, B25, B34, C05, G04, G06–G11, G13 | — | free |
| B platform | `schema.clj`, `call_preparation.clj`, `accretion.clj`, `admission.clj`, `status.clj`, `analyzer.clj`, `transcript.clj`, `drive.clj`, `web/jvm.clj`, `bootstrap.clj`, `docstring.clj` | B rows, E08 (call_preparation), F producer rows in these files | P2 | `call_preparation.clj`: impl-shape-mirror-malli; `bootstrap.clj`, `render/ns.clj`: check render lanes |
| F producers | `db.clj`, `render.clj`, `render/hiccup.clj`, `render/ns.clj`, `fn.clj`, `schema/internal.cljc`, `my/program.clj` | F producer rows | render slice 2(i) | `db.clj`, `render.clj`: delete-closure-walks, fix-cost-db; `fn.clj`: several |
| bridge | `script/seon/dev/mcp.clj` | B17–B23 survivors | delete-mcp-bridge-opus lands | delete-mcp-bridge-opus |
| M2 | the MCP reply owner at that time | the reply unit line | M1, render slice 2(i), one-pipeline S2 | render and one-pipeline lanes |

**Startable now, if root grants them:** P1, P2, S scripts. P0 starts when `instrument.clj` frees, S process when
`process.clj` frees. Everything else is ordered as above. The files shared by several cuts are sequenced, never
shared: `flow.clj` (P1 → R1 → L), `fault.clj` (P0 token → P2 → R4), `error.clj` (P2 → R4), `boot.clj` and
`agent.clj` (R1 → L / G-agent), `sci/kernel.clj` (P0 → R3), and `instrument_test.clj` (P0 → R3).

**Estimates.** These are unique physical source lines per cut, planning values that are not measured. Every range is
counted once, in its owning cut; schemas and test conversions are listed separately.

| Cut | src − / + | schema − / + | tests |
|---|---|---|---|
| P0 | −40 / +9 | −3 / 0 | convert 4 `record!` rows; add about +40 |
| P1 | 0 / +18 | 0 | about +35 (flow_test) |
| P2 | −3 / +12 | 0 / +3 | about +40 |
| M1 | 0 / +20 | 0 / +8 | about +30 |
| R1 + R1t | −300 / +40 | −70 / 0 | delete committer/overflow tests (about −150), convert 3 callers, add about +40 |
| R2 | −70 / +15 | −3 / 0 | about +25 |
| R3 | 0 / +14 | 0 | about +30 (rows) |
| R4 | −6 / +4 | 0 | convert 1 caller |
| L | −18 / +18 | 0 | about +35 |
| G-agent, G-effect | −15 / +26 | 0 | about +45 |
| T | −5 / +20 | 0 | about +30 |
| S process, S scripts | −30 / +60 | 0 | about +30 |
| B platform, bridge | −75 / +45 | 0 | about +40 |
| F producers (excluding render slices) | −79 / +37 | 0 | about +20 |
| **Total** | **about −640 / +340, net ≈ −300** | **−76 / +11** | about −150 / +520 |

This is smaller than round 1's claimed −715 because the double counts (A04/C03, B01/C03, E09) are removed, the
`flow.clj` interval is honest (−262 gross, not −370), and the retained per-graph machinery (report fan-out, fault
port) is priced. Several cuts exceed 100 added lines only in tests. R1 is the one large deletion, at −370 source and
schema lines against +40.

## 9. Questions and rulings

- **9.1 Delivery.** RULED (owner 20:50Z). The default is root, and faults are surfaced in MCP replies (section M).
  Proof of no paid turn is in E.4.
- **9.2 Authored acquisition refusals.** Astra's opinion is adopted (option 1, with classification at the producer).
  This is in E.3, E09.
- **9.3 Flow's error channel.** Astra rejected accepting the residual. The fix is §0.1: a per-proc fault port through
  Flow's own `ProcLauncher` SPI. It uses no fork, so there is no dependency pin or publication change. **Probe
  first:** show a `send-outputs` report and a report racing `flow/stop` reaching the port on a scratch graph.
- **9.4 Timeout at stop.** Astra's opinion is adopted. The real caller is `boot/stop!` at `:499`, and it is fixed in
  cut L (G.2).
- **9.5 A shared bounded-thread helper** for `test.clj` and `effect.clj` (0.2). This is a follow-up: after G-effect
  and T land, compare the two watcher shapes line by line. If they are identical, extract one function in the owner
  of both, which would be `seon.await` if it stays an observation plus join. Otherwise leave them.
- **9.6 Call provenance for D-classify** (new; refutation evidence in §10):
  1. **Recommended: program data.** The caller is "the agent" when the refusing Var is in the stored
     `:seon.fn/calls` of the evaluated form's analysis or of a row authored on the agent's branch. Guarantee: data
     only, read on the failure path, with no hot-path cost. Cost: about +14 lines in `failure-value`. Sacrifice: when
     both authored code and a host function call V, a host bad call is attributed to the agent; it is still returned
     visibly, but not stored as a core fault.
  2. **A marker at the SCI binding boundary.** The per-Var SCI binding catches its own Var's input refusal and marks
     it direct. Guarantee: exact for direct calls. Cost: a `try` frame per SCI→host call (to be measured) and a
     change to how host Vars are copied into SCI, which bends the SCI-binding owner. Sacrifice: recursion
     self-attribution.
  3. **Structural JVM frame check.** Test whether the `StackTraceElement` below V's wrapper belongs to an `sci.impl`
     class. Cost: about 8 lines, no hot path. Sacrifice: class-name prefix matching, which needs the owner's
     permission as production pattern matching and breaks if SCI renames its internals.

## 10. Evidence and limits

Round-2 probes, all JVM mode on `default` (PID 37460), read-only and disposable:

- A scratch `sci.core/init` context, with no Seon context touched. `sci.core/stacktrace` returned only interpreted
  frames: `[{:name a …} {:name a …} {:ns user}]` for a direct `(h/f 1)`, and the same shape (`:name b`) for a nested
  `(h/g)` → `h/f`. This refutes stacktrace-based provenance (§9.6).
- Clojure's `edn/read-string` throws a bare `RuntimeException`; edamame throws `ExceptionInfo` with
  `{:type :edamame/error :row :col}`.

Read at HEAD: the Flow source seams (`impl.clj:29-36`, `:94-102`, `:150-165`, `:174-183`, `:258-265`, `:312-318`),
the public docstrings (`flow.clj:116-120`, `:215-229`), Malli's no-generator data (`generator.cljc:475-482`,
`:501`), and `boot.clj:485-515`.

Round-1 evidence still stands: 32 signatures vs 9 notification ids, and the F01 face timings. The 44 ns promise
figure is withdrawn as irrelevant to the revised lifecycle.

Not run: tests, fault-port shutdown probes, the §9.6 option-1 lookup, the G14/G15 contention measurements and the
F storage timings. These are obligations of the implementing cuts. Unavailable evidence is not a pass.

Source delta is **0**; only this file changed.
