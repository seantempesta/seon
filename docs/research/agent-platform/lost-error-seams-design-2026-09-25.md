---
type: design
status: "round 5 (Opus): Astra ACCEPT-AFTER-FIXES 9b0e5e813, five corrections folded; owner authorized implementation; P0 implementing"
created: 2026-09-25
scope: One owner change per lost-error class A–G from the 2026-09-25 audit, plus fault observation in the agent's MCP replies
---
# Lost-error seams: one owner change per class

**What exists.** Astra's [lost-errors audit](lost-errors-audit-2026-09-25.md) (`64ae7aca5`, pinned `93711ba43`) found
106 path instances in seven classes.

The [review](lost-error-seams-design-review-2026-09-25.md) (`dc0597005`) records what is closed:
- **Closed:** corrections 3 (the stop caller), 6 (safe storage), 7 (bootstrap), M1 (reuse and retire) and M4
  (continuation and outcome).
- **Still open:** 1, 2, 4, 5, 8, 9, M2 and M3. Each is corrected below, in the section that owns it.

The owner's rule of 23:00Z is applied throughout (§0.4). The cut **S-scripts has landed** (`298753fc2`, `81f78faa3`;
landing `d318e0269`).

**Data flow.** Three flows are repaired:
- a failure goes to its owning boundary, which writes one durable fact that *references* its world, plus the
  current opening's committed message; the policy is then applied at the execution owner;
- a task goes to its real exit, and exactly one owner disposes of each distinct failure;
- a value goes through the one floor bound to both faces.

**Principal Clojure approach.** Each owner's contract is made total, using dependency seams that already exist:
- Flow's `ProcLauncher` SPI and core.async's `WritePort`/`Handler`;
- Malli's throwing `:report` in `-instrument`;
- the serialized upsert in the Datahike writer;
- `Thread.join`, executor termination and `ProcessHandle.onExit`;
- the render owner's safe selection and continuation;
- the existing `seon.error/occurred-since` window;
- `seon.id` for identities.

Each cut deletes its per-site copies. Nothing is added as a registry, supervisor, retry, task framework, second
printer or transport cursor.

**Pin.** Lines are at HEAD **`e6066f091`**. Every cited owner is unchanged from `431a9b4ee` except the
`instrument.clj` shape change (C4 option B, re-landed at `e6066f091`); lines cited there are re-checked in D.
This document changes no source and runs no tests.

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

The disposer is therefore **a `WritePort` whose `put!` disposes of the report on the reporting thread, then returns
a completed box**. So `>!!` returns *after* disposal, not at once: `impl/protocols.clj:18-19` defines `put!` as
answering a "derefable boolean … if handled", and `async.clj:200-211` shows `>!!` doing `(if ret @ret (deref p))`.

The port holds no channel mutex, buffer or reader. Flow's `stop` closes only the graph's `:error` channel
(`impl.clj:179`), so a proc still unwinding after stop reports into a live disposer.

**Narrowed guarantee** (correction 1). The port serves only Flow's synchronous `>!!` on `::flow/error`. `put!` honors
the `Handler` protocol (`impl/protocols.clj:25-29`): it commits the handler under that handler's lock, and disposes
of the report only when that commit succeeds. An inactive handler, such as one from an `alts!` that was never
issued here, gets `nil` and no disposal. The port is never handed out as a general channel.

The disposer's contract:

| Step | Value or action |
|---|---|
| input | Flow's report map: `::flow/pid`, `::flow/status`, `::flow/cid`, `::flow/op`, `::flow/msg`, `::flow/state`, `::flow/count`, `::flow/ex` |
| failure value | the owner's rule (§0.4), references not copies. `(ex-info "Flow proc loop failure" {pid status cid op count, :seon.agent/id, :seon.flow/graph <graph identity>} (::flow/ex report))`. `::flow/state` is never copied: the pid and graph identity reference it. `::flow/msg` is referenced by the blob digest of its safe-storage selection (render slice 2(i)); until that lands, by its class and count only. The original throwable stays the cause. The declared schema is the existing `:seon.flow/exception-error` |
| 1. stop request | the proc's **own control tap** `put!` of `::flow/stop` (the first form of `stop-panicked-graph!`, `flow.clj:245`; an async put, which enqueues or parks without blocking the caller) is requested **first**. This is a *request* only. The graph-wide `flow/stop` that follows (`:246`) takes Flow's `ReentrantLock` and a blocking `>!!` onto a control channel of capacity 10 (`impl.clj:71-75`, `:99`, `:174-183`), so it is **not** claimed nonblocking or bounded; it runs after recording, and actual exit is `join-graph!`'s. Until a saturated-control and contended-stop probe passes, P1 claims no bounded stop. The stop request is made when the cluster's `:seon.config/on-core-error` reads `:panic` or cannot be read. This is the one config read `fault!` already makes (`fault.clj:213-216`). **P2 moves it ahead of recording inside `fault.clj`**: `fault!` accepts a declared context member `:seon.fault/stop-request!`, which is the execution owner's stop request, calls it first under `:panic`, and then records. There is no second config read, and P1 passes its request through that member. Under `:record`, the stop is requested when recording throws or does not commit. Either way, a stalled or failing later step cannot be the only thing standing between a panic and the stop |
| 2. recording | `proc-failed!` → `fault!` with the proc's carried environment |
| 3. emergency | only if 2 threw or did not commit, and only best effort: `prn` of pid, op and the throwable's `floor`, inside its own `try`. It always runs **after** the stop request, so a blocked or throwing stderr cannot prevent the stop. `proc-failed!`'s existing print-then-return ordering (`flow.clj:220-232`) is inverted in P1 to request stop, then record, then print |
| totality | steps 1 to 3 sit in one `try`. `put!` always returns the box, so nothing propagates into Flow's catch (`impl.clj:312-319`) |

**Bound and owner of the whole fault path** (correction 1). `fault!` owns it, and there are three terms:
- **Preparation** (`error/prepare`, `evidence-stage`; `fault.clj:97-104`) is bounded by what it is given. Under the
  reference rule, evidence is references and a bounded throwable chain, never a world value. That bound becomes
  real when **fix-unbounded-capture-opus** stops producers from copying worlds into error data, and render slice
  2(i) replaces raw admission with safe storage selection (F.2). **P1's production guarantee depends on both.**
  Until then a stall in preparation is possible. It is visible, because the proc's ping reads unknown and never
  healthy, and the graph-stop request of step 1 has already been made.
- **The write** is bounded by `db/transact!`'s declared deadline. An unknown or refused outcome panics
  (`fault.clj:163-169`).
- **Delivery** is read from the committed transaction (E.2).

**P1 probe result (2026-09-25, `tmp/impl-seams-p1/probe.clj`, default JVM, stub recorder).** All six cases behave
as designed:
- a `send-outputs` failure and a transform failure are each disposed once, with pid, cid and count;
- a report racing `flow/stop` (a stop transition that throws) is disposed;
- 150 of 150 reports are disposed, and the graph's own error channel stays empty;
- a stalled recorder blocks only its proc (`:p` gives no ping answer while `:q` is `:running`), and under `:panic`
  the stop request lands before recording;
- a failing recorder gives the order stop request, record, emergency;
- a failing emergency printer is contained after the stop request.

**Finding: saturated control deadlocks graph stop.** A proc that exits on its **own** stop request never reads
its control tap again. Each tap is buffered to 10 (`impl.clj:150-165`) and the control channel to 10 (`:99`).
After about 20 more graph commands, such as pings, Flow's control mult is stuck on the dead proc's tap, the
control channel is full, and every `send-command` blocks forever. That includes `flow/stop` (`:71-75`), which
also holds Flow's lock. Reproduced: 10 command threads blocked in `>!!` and the stopping thread blocked on the
lock. They were recovered by interrupting the probe's own threads.

So the per-proc stop request must be followed promptly by a graph-wide stop, or the proc must not exit alone.
**P1 production must fix this ordering before it lands.** The candidate fix issues the graph-wide stop request
at the same point, on a thread that does not hold the reporting proc. This stays open for Astra review. P2 is
unaffected: it only calls whatever `:seon.fault/stop-request!` the owner supplies.

P1 is this change. The review accepts it as an investigation now, and as production work after two things:
1. A scratch-graph probe must show six behaviours:
   - a `send-outputs` failure is disposed with its report's references;
   - a report racing `flow/stop` is disposed;
   - more than 100 reports are all disposed;
   - a stalled recorder blocks only its proc, and the stop request still lands first;
   - a failing recorder leaves the graph stopped, with emergency evidence;
   - a failing emergency printer still leaves the graph stopped;
  - **a throwing or stalled emergency output** still leaves the stop requested;
  - **a saturated control channel (10 pending) and a contended `flow/stop`** are observed, not assumed bounded.
2. The two dependencies above must have landed.

**0.2 Task ownership stays with each existing owner.** There is no `await/task`. Exit is proven by `Thread.join`,
executor termination or `ProcessHandle.onExit`. Disposition goes through the existing `io-terminal!` CAS or the
caller's join-then-consume (section A.2).

**0.3 Delivery through the agent's path.** The owner ruled at 20:50Z that faults go to root by default and appear
as errors in the agent's MCP interactions. This is section M.

**0.4 Errors and facts reference the world; they never copy it** (owner, 23:00Z). Every error and fact this design
produces carries *references*:

| World | Reference |
|---|---|
| a database value | its commit id and basis-t |
| an SCI context | the agent and branch identity |
| a request | the evaluation or turn reference |
| a stored value | its blob digest |
| a live value | its result handle (`:seon.error/result-id`, which already exists) |

It follows that no fault carries a proc state, a work map, a context, a connection or a request map. Four sections
apply the rule:
- the fault port references proc state by pid and graph (0.1);
- the launcher references work by submission id and effect id (A.2);
- the MCP observation records commit ids and bases, not rows (M);
- the F producer rule becomes *reference*, not *inline* (F.2).

**Coordination with fix-unbounded-capture-opus.** That lane is the root-cause fix for this rule. It holds
`sci/kernel.clj` (admission), `error.clj` with `error/*.clj` (commit and offending producers), and
`render/value.clj`'s `:offending` producer at `:249`. It names the other producers but does not edit them. The two
lanes divide the work as follows:
- **It owns** every change to how `error.clj` and `error/*.clj` build and admit evidence, including F01's
  retained-evidence admission path and F07/F22 if they fall inside its producers.
- **This design does not duplicate its census.** Class F's producer rows (F.3) take their migration list from that
  lane's named producers, and convert producers outside its files only after it lands.
- **Its files are held.** This design's cuts that touch them (P0's `kernel.clj`; P2, R4 and M's `error.clj`) wait
  for it to release them, then rebase on its result.
- **P1's production bound depends on it** (0.1).

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

- **The world and the panic action exist before any entrant.** `start-work-launcher!` builds one `::stop!` from
  `start-graph!`'s graph-ref (`flow.clj:117-122`); it is a non-blocking `poll!` followed by `flow/stop`. The same
  value goes into the launcher map, its step args, and every work item at `submit!`, so a submission refused before
  any claim already carries it. `var-process` gives every other proc the same shape of closure (C.3).
  `io-terminal!` already has `(env/of work)` (`:410-412`).
- **One containing terminal owner for every entrant** (correction 2). `io-terminal!` wraps its whole body in one
  `try`. Its entrants are:
  - the task thread;
  - executor rejection (`:467-473`);
  - queued-stop settlement (`:633-644`);
  - capacity refusal.

  Capacity refusal needs one change. Today `refuse!` runs inside `RefusingBuffer.add!*`, under the channel mutex,
  where a throw is caught by the channel's exception handler (`impl/channels.clj:283-306`). So `refuse!` now only
  CASes `::status` from `::queued` to `::refused`, a new value of the existing status. `submit!`, whose `inject` put
  has returned by then, sees `::refused` and calls `io-terminal!` on its own thread, outside every mutex.
- **Each distinct failure is recorded through the existing owner** (correction 2). `Throwable->map` and
  `refusal/chain` follow only `ex-cause` (`error/refusal.clj:75-89`, `clojure/core_print.clj:473-507`), so a
  suppressed exception never reaches the durable record. Each failure therefore gets its own `fault!`:
  - **Body failure**: `(fault/fault! (env/of work) body-failure ctx)`. A body failure that already carries a receipt
    is not recounted; `fault!` rethrows it (`fault.clj:194`), and `io-terminal!` keeps that receipt.
  - **Callback or release failure** (`complete!`, the `release-arm!` inside it, and `io-terminal!`'s own cleanup):
    a separate `fault!`. **The references must reach durable data** (correction 3). P2 (landed) makes `fault!`
    forward exactly three declared context members, through `:seon.error/references` on the recording
    observation, and nothing else:
    - `:seon.effect/id`;
    - `:seon.error/related-occurrence`, the body's occurrence id;
    - `:seon.error/obligation` (C.2).

    The first two are stored on the occurrence as the refs `:seon.error.occurrence/effect` and
    `:seon.error.occurrence/related`. The submission id is **not** stored: it is an in-process identity of type
    `[:or :uuid :keyword :string]`, which has no single storable type. The effect is the durable reference.
    A context is never merged wholesale. L, T and R1t use these members only after P2.
  - **Panic in either**: `(::stop! work)` runs, and the other failure is still recorded.
  - **Recording itself throws**: `::stop!`'s *request* first, then the emergency `prn` of the ids, best effort.
    This is the only case with emergency-only handling.
  - **Ordering**: all of this happens before `(swap! submissions dissoc id)`. Removal stays in `finally`.
  - **Accounting**: the completion offered while `::active?` holds carries the receipts. The launcher's
    `proc-failed!` call at `:518-519` is deleted.
- **Thread handle.** The runnable's first `active-work` swap (`:445-450`) also stores `::thread`. Stop clears
  `::active?`, reads `active-work`, and interrupts each recorded thread. A task that entered late is still disposed
  and joined. Interruption is best effort; the executor join is the proof of exit.

Each lifecycle outcome, with its one owner and its disposal. Every disposal happens once, through the `::status`
CAS, inside `io-terminal!`'s containing `try`:

| Outcome | Disposal |
|---|---|
| the body returned a value or a declared refusal | settled as `::value`; a declared refusal is an ordinary result |
| the body threw | `fault!` (body), then `::stop!` on panic |
| the body returned and `complete!` threw | `fault!` (callback) |
| the body threw and `complete!` threw | two faults: body, then callback, the callback's context referencing the body's occurrence |
| a receipt-bearing body panic plus a `complete!` throw | the body receipt is kept without recounting; the callback gets its own `fault!` |
| the executor rejected the work | `fault!` (rejection), with the same callback rule |
| capacity refusal | `submit!` sees `::refused` outside the mutex and settles with the capacity error; the callback rule applies |
| stopped before entry | the runnable reads `::active?`, finds it false, and settles launcher-stopped; the callback rule applies |
| queued and never executed | stop's CAS settles launcher-stopped; the callback rule applies |
| stopped while running | interrupted only; the task disposes its own outcome, and the join proves exit |
| a failure to record or contain | `::stop!`'s request first, then the emergency `prn`, best effort |

No Seon task reaches the uncaught handler (E06).

The FutureTask, `::task` and `.cancel` are deleted, and `execute-io-work!` becomes `(.execute executor ^Runnable
runnable)`.

**3. Migration.** The minus and plus figures are estimated physical source lines. Each range has one owning cut in §8.

| ID | Site | Conversion | − / + |
|---|---|---|---|
| A01 | `flow.clj:398-473`, `:513-524` | the lifecycle table above | −12 / +16 |
| A02 | `test.clj:282-311` | the thread join stays the exit proof. The watcher joins, then `.get`s the task, then disposes a late throw through `core-fault!` (`:2192`), then calls `release!`. On the timely path, `.get` moves before `release!`. A `release!` failure goes to its own `core-fault!`, whose context references the late failure's occurrence; suppression is not persisted (§A.2) | −2 / +8 |
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
- no release before the executor terminates;
- **a receipt-bearing body panic plus a `complete!` throw** leaves two durable faults, the body not recounted;
- **executor rejection, capacity refusal and queued-stop settlement each with a throwing `complete!`** give a durable callback fault, with capacity refusal settled outside the channel mutex;
- a body plus callback failure gives two faults, the second referencing the first's occurrence, and both survive a restart.

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
- a runtime cleanup failure is recorded as its own fault, referencing the primary; a script cleanup failure is printed as suppressed with the primary, because the JVM stack print includes suppressed exceptions.

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
  - The deferral fault's commit request names `:seon.error/obligation <wake message id>`. It gets there because
    `fault!` forwards the declared context member `:seon.error/obligation` (P2, `seon.fault.edn`) into the
    recording request field that controls occurrence identity. A caller setting it on some other map would never
    reach the writer.
  - **The obligation has its own identity, stable across attempts** (correction 5). For a request that names an
    obligation, `recording` (`error.clj:1537-1544`) builds the occurrence id from
    `{signature, obligation, agent-id, origin branch}` through the same `id/id`. It **excludes** `turn-id` and
    `process-id`, because those change when the same wake is seen in a new turn, after a re-arm, or after a
    process restart. Requests with no obligation keep the existing key and ordinary recurrence counting. The key is
    content: it copies no world into the fault (§0.4).
  - `commit-call` already reads `old` under the serialized writer (`:1341`). When the request names an obligation
    and `old` exists, it returns only the process row: no count increment and no message. Two derivations, from one
    basis or from different turns, processes or re-arms, therefore record exactly once. A different wake is a
    different obligation, and it records on its own.
  - A read refusal of `old` returns as the transaction's refusal (`:1351-1361`), and `fault!` turns it into a panic.
    A refused read is never taken to mean "no prior occurrence".
  - C02, a pre-exhausted agent at arm, uses the same rule, with the unanswered wake at arm as its obligation.
  - Cost: about +8 lines in `error.clj` and +1 optional member in `seon.error.edn`. This lands in P2, after
    fix-unbounded-capture-opus releases `error.clj`.
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
- **the same wake seen in a new turn, after a re-arm and after a process restart** still gives count 1;
- **a different wake** records its own occurrence;
- a derivation whose `old` read refuses panics;
- a producer clock set backwards changes nothing, because no timestamp is compared;
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
| `::m/invalid-schema-at-call` | authored contract row (by provenance) **and** positive rejection evidence (Malli's `::m/invalid-schema` while compiling that schema) | agent → returned |
| `::m/invalid-schema-at-call` | installed contract row | core fault |
| anything else: absent, stale or incomplete analysis; mixed direct and installed callers; unknown provenance | **ambiguous** | agent → the complete refusal is returned (root's rule) |
| a throwable carrying a receipt | already disposed | rethrow |
| an interrupt or bound | the evaluation's own bound | the declared timeout |

**Validator-engine faults** (correction 5). `::m/invalid-schema-at-call` counts as *authored invalid* only when there
is positive rejection evidence: Malli's `::m/invalid-schema` error, raised while compiling the authored schema.
Any other throw while validating against an authored schema is **ambiguous**, and returns with its report and cause
under the ruled rule.

**Data path** (correction 5). No existing key's meaning changes, and nothing is read from ambient state.

1. **Root identity.** `failure-value`'s request (`kernel.clj:531-536`; schema `seon.sci.kernel.edn:7-11`) gains the
   existing keys `:seon.fn/sym`, for the evaluated root symbol, and `:seon.program/analyzed-source-digest`
   (`seon.fn.edn:137`), for the form's analysis identity. `:seon.fn/root` is a source-root string
   (`seon.fn.edn:14`) and is not used.
   - The database arrives as an argument, `:seon.db/db`: a value used for the lookup and never stored (§0.4).
   - `kernel/invoke` already holds `database` (`:590`) and `function-symbol`.
   - The two `sci/eval.clj` callers (`:4122`, `:4442`) hold the evaluation's database and its analysis.
2. **The exercised digest reaches the refusal.** `compiled-wrapper` (`instrument.clj:624-626`) receives no digest
   today. `wrap-interpreted` removes it from the arm request (`:477-489`), and `arm-var!` holds it only in the outer
   profiling cell (`:779-808`).
   - R3 adds one argument, `definition-digest`, to `compiled-wrapper`, supplied by both callers at arm time.
   - The `:report` closure (`:705-713`) puts it into the refusal as `:seon.program/definition-digest`.
   - This counts as +6, and R3 therefore owns `instrument.clj`, `seon.instrument.edn` and `instrument_test.clj`.
3. **The world for `fault!` stays at the boundary that owns it.**
   - `failure-value` stays pure. It returns the refusal with a declared `:seon.error/responsible ∈ {:agent :core}`
     member (+1 schema member).
   - The boundary that owns the evaluation world calls `(fault/fault! (env/of ctx) throwable context)` when that
     member is `:core`. Those boundaries are the catch at `kernel/invoke` (`:653`, which has `ctx`) and the two
     `sci/eval.clj` sites.
   - No connection is looked up from ambient state.
4. **Reach** is read through the one call-reach owner (`f24bf9143`). The +14 line estimate for R3 remains unproved
   until that read is measured.

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
- **a validator throw while checking an authored schema, with no `::m/invalid-schema` rejection, is ambiguous and returned with its cause**;
- the exercised definition digest is present in the refusal, for both host and interpreted arming;
- a `:core` classification is recorded by the owning boundary with `(env/of ctx)`, never by `failure-value`;
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
  - `commit-call`'s return contract is unchanged. **Delivery evidence identifies the current opening**
    (correction 4).
    - The writer records its own selection, so the receipt never re-derives it. `opening`
      (`error.clj:1293-1311`) answers whether the *next* occurrence opens: 0 for the first, a resolution token
      before a reopening, and **nil while already open**. Calling it on `:db-after` therefore cannot identify the
      opening this transaction selected. `:db-after` is also batch-wide: Datahike's writer replaces each report's
      `:db-after` with the batch's database (`datahike/writer.cljc:229-264`).
    - So `commit-call` writes the selected opening's notification messages onto the **error entity**, as
      `:seon.error/notified`, a cardinality-many ref declared in `seon.error.edn`. It goes on the error entity and
      not the occurrence because a recurrence in a new turn or process is a *new* occurrence of the same open
      signature (P2 REPL evidence), so the selection must outlive any one occurrence.
      - On a **new opening** it retracts the previous set and asserts the new message refs.
      - On a **recurrence within the open opening** it keeps the set.
      - Message ids come from one shared function, `notification-id`, extracted from `message-tx` (`:1319`). It
        keeps the first-opening special case: `[signature recipient]` for opening 0, with the opening appended only
        when it is nonzero.
    - `record!` derives the receipt from **this transaction's own** `:tx-data` together with its `:db-before`, both of
      which are per transaction. The set is the `notified` datoms asserted here, plus the prior set as of
      `:db-before` that this transaction did not retract. It never reads `:db-after`, and never looks up the latest
      message.
    - `:seon.fault/delivered-to` is the recipients of that set. An empty set means **committed but undeliverable**.
    - Cost: one datom filter over the transaction's own data, plus one `:db-before` pull. The schema grant is
      `seon.error.occurrence.edn`, +1 attribute, in P2.
    - Required cases (P2 and R4):
      - first delivery;
      - a recurrence in the same opening;
      - resolved, then reopened with only the old message present;
      - a delivered opening resolved before observation;
      - another resolution and reopening in the same writer batch.

      They must distinguish undelivered from delivered without recounting.
  - The kept recipients go into the tuple and into the receipt as `:seon.fault/delivered-to`, which references
    agent ids, never copies of agents. It is an optional member of `seon.fault.edn`, taken per target.
  - Also in P2:
    - the shipped `escalate-to "root"`. **Not landed in P2:** the shipped value lives in `config/default.edn`
      (`:235-243` holds the error dials), not in the schema `seon.config.error.edn`, so it needs its own grant, and
      adopting it into the live store must be proved on a branch first (owner, 23:00Z);
    - the obligation identity (C.2);
    - F08, a fixed message in `fault.clj`.

    F01's HTML deletion has **landed** in fix-unbounded-capture-opus (`e62d8489c`: `prepare-result` stores the
    value's admitted artifact, not the full HTML face). F07 and F22 stay in F producers, after that lane (§9.7).
  - Routing stays **explicitly incomplete** until R4.
- **R4, E-activation**, atomic and last (after R1, R1t, R2, R3). It holds the whole files `fault.clj`, `error.clj`,
  `seon.fault.edn`, `test/seon/turn_test.clj` and `test/seon/cluster/fault_message_test.clj`.
  - `record!` becomes `defn-`.
  - **The recipient decision becomes one rule, independent of attribution** (correction 4): the present steward;
    otherwise the present `escalate-to` agent, root by default; otherwise **committed but undeliverable**, which
    panics. R4 deletes `interrupted?` (`:1393`), the `:your-run` arm (`:1433`) **and** the `(nil? agent-id)`
    condition on the escalation arm (`:1441`). An attributed core fault with no steward reaches root.
  - `policy` requires `committed?` and a non-empty current-opening `delivered-to` on every required target.
  - The last test caller, `test/seon/turn_test.clj:251`, is converted.
  - Precondition: `rg "fault/record!" src script test` returns only `fault.clj`.

**3. Migration.**

| ID | State | Conversion |
|---|---|---|
| E01 | confirmed | P2 (current-opening evidence) + R4 (one recipient rule, `:1441` included): −8 / +18 source, +1 config, +2 in `seon.fault.edn` |
| E02, E03, E04 | superseded (`test.clj:2192-2203`; `sci/eval.clj:2481-2482`) | none |
| E05, E07 | confirmed / D | P0 and R3 |
| E06 | confirmed (`boot.clj:342-381`) | After A.2 and C.3, no Seon task reaches this handler: the launcher, the backstop and the proc loop each contain their failures (correction 8). The handler therefore serves only **genuinely ownerless threads**, calling `fault!` with the carried environment, or printing stderr emergency evidence when there is none. An agent-attributed throwable reaching it is itself a named owner defect. −14 / +6 |
| E08 | confirmed (`turn.clj:3406-3443`; `call_preparation.clj:1272`, `:1291`; `test.clj:1469`) | delete `analyze-submitted`'s per-site classifier. Each boundary returns its declared agent-mistake alternatives and passes everything else to `fault!` |
| E09 | confirmed (`sci/eval.clj:2437-2496`) | R2, §9.2. Authored refusals return in the opening. Installed, compiler and configuration failures go to `fault!`. The direct `commit-tx` writer (`:2443-2486`), the `commit-fault!` branch (`:2487-2495`) and the `config/defaults` fallback (`:2448-2450`) are deleted, along with 19 lines matching `:seon.flow/commit-fault!` and `seon.sci.eval.edn:244-246`. −70 / +15 |

**4. Class regression.** Rows go in `test/seon/cluster/fault_message_test.clj`:
- Throwable and flat core error, each with and without attribution, give the steward or root in `delivered-to`;
- a notification already open **in the current opening** gives the same recipient and no second message;
- **a signature resolved and then reopened, with only the old opening's message present, is not delivered**: undelivered under P2, and a panic under R4;
- **an attributed core fault with no steward reaches root** (R4);
- a removed steward falls back to root;
- a missing root means the fault committed but is undeliverable, which panics under R4, and the committed facts
  remain;
- storage refusal panics;
- a receipt-carrying failure is not recounted;
- under `:panic` the graph stops;
- no paid turn occurs, with the root provider read on the target branch;
- an invalid authored call is returned with no occurrence.

**5–6.** The fault path gains, per target, one opening read and one read of messages about the signature on `:db-after`. Order: P2, then R1, R1t, R2 and R3 in any order, then R4.

## M. Faults in the agent's MCP replies (owner ruling 20:50Z; revised per the review's §M)

**What exists (M1).** Round 2 wrongly said MCP replies carry no faults. Three owners already produce fault windows:
- **The bridge.** `script/seon/dev/mcp.clj:511-562` evaluates a second form, `window-form`, through
  `seon.error/occurred-since`. It keeps a per-session `window-bases` cursor (`:500-503`, `:542`, `:558`), clips with
  `take max-window-errors` (`:533`), builds its own requery text (`:562`), and appends a second text block
  (`:629-635`).
- **The shared history.** `render/transcript.clj:346-402` (`branch-errors`) already derives each settled evaluation's
  window, from the previous settlement transaction to this one, with `occurred-since` as-of that settlement. That
  recomputation is **not** stable: a later recurrence advances the no-history `last-at` and `count`, and the
  earlier window can lose it (M.1). A refused read is a typed unknown.
- **The query owner.** `seon.error/occurred-since` (`error.clj:1571-1616`) returns unresolved signatures touched in
  the window, with their message, operation, branches and summed cumulative counts.

**Design.**

1. **The observation is computed once, then saved as shown text** (M2).
   - At settlement, the agent pipeline captures the cluster database value once and computes the fault
     observation from it **before** the settlement transaction.
   - The observation's exact shown text is saved through the ordinary evaluation recording path, as part of the
     reply units' saved bytes, like every other shown value.
   - The live observation stays reachable through the existing live-result mechanism, its result handle. After
     that, the render owner answers `:gone`.
   - History renders the **saved text**. It never recomputes from today's rows: occurrence count and last-at are
     no-history attributes (`seon.error.occurrence.edn:3-5`; Datahike excludes them from temporal retention,
     `db/transaction.cljc:448`, `:547-566`), so an `as-of` recomputation can lose an occurrence that has advanced
     since.
   - This retires `branch-errors`'s recomputation from `as-of` (`transcript.clj:346-402`), which has the same flaw.
2. **References, not copies (§0.4).** The settlement records only the observation's evidence:
   - `:seon.cluster.eval/fault-window`, a component holding three things. `cluster` is the cluster branch identity
     plus the commit id of the captured value. `lower` is the basis-t it starts after. `upper` is the captured
     value's basis-t.
   - It is +1 attribute with its component schema in `seon.cluster.eval.edn`, owned by the settlement writer.
   - **The settlement writer** is the transaction that writes `:seon.eval/shown`, `:seon.cluster.eval/error` or
     `:seon.cluster.eval/interrupted-at`: the facts `transcript.clj:374-376` already reads.
   - **Failure ordering.** Only a committed settlement advances the lower bound, so a failed settlement leaves the
     next observation starting at the previous committed `upper`.
3. **The lower bound.** It is the `upper` of **that agent's previous committed fault-window**, if that window
   exists and names the same cluster identity and lineage.
   - If there is no previous window (a first evaluation, or one after compaction), the observation is the declared
     unknown `:seon.error/window-unknown`. It names `upper`, carries no replay, and needs +1 error schema.
   - If the previous window names a different cluster identity, as after a store nuke, the result is also
     `:seon.error/window-unknown`. The two bases are not comparable, so the design refuses to compare them.
   - **Covered interactions.** System, failed and interrupted evaluations settle through the same writer.
     Multi-form requests settle once, and the window belongs to the last form (`transcript.clj:379-383`).
     Status-only MCP requests are not evaluations and record no window.
4. **The cluster's database is supplied explicitly.** The agent's world carries the cluster connection
   (`:seon.turn.loop/cluster`).
   - `record!` copies a core fault to the cluster branch **only when this process holds the home connection**
     (`fault.clj:142-145`).
   - The copy's outcome is one of `record!`'s outcomes (`:146`), so a failed copy already fails the recording.
   - An absent home connection means there is no cluster world. The observation is then a typed unknown, not an
     empty window.
5. **Window semantics are defined at the query owner** (M3).
   - `occurred-since` changes in place, so there is still one query owner. Its only other callers, the bridge and
     `branch-errors`, are retired by this cut.
   - It returns **every signature touched in `(lower, upper]`**, carrying `:seon.error/resolved-tx` as data. It no
     longer filters resolved signatures (`:1595-1597`), so a fault that occurred and resolved before settlement is
     still shown, marked resolved.
   - Relations resolve against the full captured value (`:1587-1593`).
   - **Counts.** `touched-signatures` is exact. `:seon.error.occurrence/count` stays labelled as cumulative per
     occurrence and is not an event count.
   - **Delivery** uses P2's current-opening rule, the same query. A committed occurrence whose opening has no
     current message is **undelivered**, which can happen before R4 and after a delivery panic. The undelivered
     count is honest.
   - **Cost** (correction M3). `[(> ?tx ?since)]` is a filter over the attribute's AEVT range
     (`datahike/db/search.cljc:152-153`), not an indexed transaction range. The work is therefore a scan of every
     `:seon.error.occurrence/last-at` datom, plus joins, plus the group and sort (`:1608-1611`).
   - The cut measures that cost on the live store; today it holds 32 signatures. If the scan exceeds the reply's
     bound, the smaller read reuses a proved dependency seam. No speculative index is added. The round-3 claim of
     O(window) cost is withdrawn.
   - **Refusal.** A refused read is the observation's typed unknown.
6. **One render path** (M4, closed). The observation is one unit of the reply. It shares the reply's B and uses the
   render owner's continuation. The reply outcome is `:error` when the evaluation failed, or when the observation
   contains a new, undelivered or unknown fault. MCP encodes that outcome as `isError`.
7. **Retirements** (M1, closed):
   - from `mcp.clj`: `window-form`, `branch-errors!`, `window-bases`, `max-window-errors`, the second evaluation,
     the requery text and the extra block (about −60 lines);
   - `transcript.clj` `branch-errors` becomes a read of the saved text plus `fault-window` (about −45 / +10);
   - `mcp-runtime-observation`'s `observation-top` and `bounded-summary` become render slice 6, row 9.

**Class regression.** Rows go in `test/seon/problems_test.clj`, through the shared agent entrance, plus thin MCP
and browser adapter checks:
- two agents on different branches observe one cluster fault;
- **an occurrence that resolves before settlement is shown, marked resolved**;
- **a recurrence after an earlier saved observation leaves the earlier saved text unchanged**;
- a recurrence within the current opening is delivered;
- **a message left from a resolved opening is not delivery**;
- a failed delivery counts as undelivered;
- a fault committed during the evaluation, and one committed after it;
- failed, interrupted and multi-form evaluations;
- first evaluation, compaction and a different cluster identity all give the declared unknown;
- an absent home connection gives a typed unknown;
- a refused read gives a typed unknown;
- a continuation beyond B;
- the `:error` outcome, and `isError`.

**Order and handoff** (correction 5). P2's delivery evidence comes first, then R4. M lands at the **P3m
boundary of the one-pipeline plan**. Its recording owners are `turn/settle!` and `turn/evaluation-facts`
(`one-repl-language-2026-09-25.md:20-26`).

That plan, at revision 5 (`60e060b52`), still specifies `:seon.cluster.eval/fault-basis` and a history rebuilt from
basis pairs (`:329`, `:484`, `:898`). That is the rejected design. Its owner must replace those rows with this
section's contract before M starts:
- the single captured observation;
- the exact saved shown text;
- the live result handle;
- the `fault-window` references (cluster, commit, lower, upper);
- advancement only on commit.

The M cut's grant is then concrete:
- `src/seon/turn.clj` (`settle!` and `evaluation-facts` only);
- `src/seon/render/transcript.clj`;
- `src/seon/error.clj` (`occurred-since` only);
- `script/seon/dev/mcp.clj` (the retirements);
- `resources/seon/schemas/seon.cluster.eval.edn` and `seon.error.edn`;
- `test/seon/problems_test.clj` and `test/seon/dev/mcp_bridge_test.clj`.

It carries one estimate for the shared work. This lane does not edit that plan. M is not startable until that
plan's owner reconciles its rows and P2 supplies delivery evidence.

## F. Arbitrary value expands before the single presentation bound (23)

**1. Seam and missing contract.** `render.value/prepare` (`value.clj:621-668`) bounds only the AI face. HTML visits
the whole value (F02), composite keys go through HTML (F03) and window ordering prints every key (F04). Today's AI
face is not safe either, so no producer row closes F before render slice 2(i).

**2. Owner change: render slice 2(i), plus two rules.**
- **Producer rule: a reference, not an inline copy** (owner, 23:00Z). A diagnostic states fixed prose and
  references the offending world:

  | Offending value | Reference |
  |---|---|
  | a database value | its commit id and basis |
  | an SCI context | agent and branch |
  | a request | evaluation or turn ref |
  | a live value | its result handle |
  | a stored or retained value | the blob digest of its safe-storage selection (render slice 2(i)) |
  | small declared scalars (a symbol, keyword, bounded integer or member name) | inline, as the declared value |

  A diagnostic never uses `pr-str`, and never carries an inline copy of an arbitrary value. The floor renders the
  reference, and exploration goes through the render path's continuation. This is fix-unbounded-capture-opus's
  rule, applied to class F's producers. That lane's named-producer list is the migration input (§0.4).
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
| F01 (`error.clj:469-525`) | **one allocation** (correction 4). The HTML deletion **landed** in fix-unbounded-capture-opus `e62d8489c`: the blob is the value's admitted artifact, and its readers are unchanged, since `data-blob` is still a digest of stored evidence. The remaining raw admission (`admit/admit-value` in `prepare-result`, and `bounded-error-admission`) converts once, inside render slice 2(i)'s safe-selection activation. No row here | 0 |
| F07 (`error.clj:1110`, `:2013`, `:2056`), F22 (`:892`), F08 (`fault.clj:159`) | fixed prose with the value in data (P2) | −11 / +5 |
| F05 `render/hiccup.clj:280` | the head becomes the offending value | −2 / +1 |
| F06 `db.clj`; F09 `sci/eval.clj:3589`, `:3637`, `:3793`; F10 (owned by the one-pipeline P1 slice, which retires `settle-batch-refusal!`'s `pr-str`; not this design's); F11 `render.clj`; F14 `accretion.clj:136-138`, `:370-375`; F15 `test.clj:1944`, `runner.clj:398`, `:986-988`; F16 `fn.clj:58-59`; F17 `schema/internal.cljc:469-523`, `admission.clj:409-410`; F19 `cluster.clj:3470`, `status.clj:177`; F21 `background.clj:55` (P0), `my/program.clj:265`, `plan.clj:1367` (cut T); F23 `render/ns.clj` | the producer rule, scripted once, then lint, load and hand-fix the residue | about −60 / +30 |
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

## 8. Cuts, order and exact file grants (rebased on `e6066f091`)

Each cut owns whole files, loads on its own, and needs no later cut to repair it. Root grants files from the current
ledger at launch. The holders below are the ledger rows at `e6066f091`; they are facts, not permissions. "Tests" in
a row means exactly the named files.

| Cut | Owns: source, then schema, then test | Lands | After |
|---|---|---|---|
| ~~S-scripts~~ | — | **landed** (`298753fc2`, `81f78faa3`): A05, A06, A08, A11, B14, B25, G04, G06–G10 per its landing; the rest of that row per the landing's own report | — |
| **S-process** | `src/seon/cluster/process.clj`, `src/seon/issue.clj`, `src/seon/test/cache.clj`; — ; `test/seon/cluster/subprocess_test.clj`, `test/seon/issue_test.clj` | A07, G05, then C06 and G12 | `process.clj` released by delete-duplicates-opus round 3 |
| **P0 D-delete** | `src/seon/instrument.clj`, `src/seon/sci/kernel.clj`, `src/seon/fault.clj` (visibility token), `src/seon/ai.clj`, `src/seon/background.clj`; `resources/seon/schemas/seon.instrument.edn`; `test/seon/instrument_test.clj`, `test/seon/ai_test.clj`, `test/seon/background_test.clj` | D01–D04, E05, the E07 rethrow, B05, B07, F21 (background) | `kernel.clj` released by fix-unbounded-capture-opus; `instrument.clj` confirmed free after impl-shape-mirror-malli round 3 |
| P1 fault port | `src/seon/flow.clj`; `resources/seon/schemas/seon.flow.edn` (the `::stop!` member of the proc args schema, and the port's report-reference members); `test/seon/flow_test.clj` | 0.1, G16 | the 0.1 probe; fix-unbounded-capture-opus and render slice 2(i), for the production bound |
| P2 delivery prep | `src/seon/fault.clj`, `src/seon/error.clj`; `resources/seon/schemas/seon.fault.edn` (context members `:seon.fault/stop-request!`, `:seon.flow/submission-id`, `:seon.effect/id`, `:seon.error/related-occurrence`, `:seon.error/obligation`; receipt `delivered-to`), `seon.error.occurrence.edn` (`notified`, `submission-id`, `effect`, `related`), `seon.error.edn` (obligation member), `seon.config.error.edn`; `test/seon/cluster/fault_message_test.clj`, `test/seon/cluster/fault_storage_test.clj` | E01 preparation (current-opening evidence), the C.2 obligation identity, F08 | P0's `fault.clj` token; `error.clj` released by fix-unbounded-capture-opus |
| R1 retirement | `src/seon/flow.clj`, `src/seon/cluster.clj`, `src/seon/cluster/boot.clj`, `src/seon/cluster/agent.clj`, `src/seon/render/web.clj`; the C.3 schemas; `test/seon/flow_test.clj`, `test/seon/cluster_test.clj`, `test/seon/cluster/fault_message_test.clj` | C03, A04, A09, E06, F19 (cluster) | P1, P2 |
| R1t | `src/seon/turn.clj`; — ; `test/seon/cluster/turn_test.clj` | C01–C03, A10, B04, E08 (turn). **F10 is owned by the one-pipeline P1 slice**, which retires `settle-batch-refusal!`'s `pr-str`; R1t does not edit it | P1, P2 |
| R2 acquisition | `src/seon/sci/eval.clj`; `resources/seon/schemas/seon.sci.eval.edn`; `test/seon/sci/eval_test.clj`, `test/seon/sci/branch_execution_test.clj`, `test/seon/adoption_diagnostic_test.clj` | E09, B31, F09 | P2 |
| R3 D-classify | `src/seon/sci/kernel.clj`, `src/seon/sci/eval.clj` (its two request sites), `src/seon/instrument.clj` (the digest argument); `resources/seon/schemas/seon.sci.kernel.edn` (request keys, `:seon.error/responsible`), `seon.instrument.edn`; `test/seon/instrument_test.clj` | the D table and data path | P0, P2, R2 |
| R4 E-activate | `src/seon/fault.clj`, `src/seon/error.clj`; `resources/seon/schemas/seon.fault.edn`; `test/seon/turn_test.clj`, `test/seon/cluster/fault_message_test.clj` | E01 activation and the one recipient rule | R1, R1t, R2, R3 |
| L launcher | `src/seon/flow.clj`, `src/seon/cluster/boot.clj`; `resources/seon/schemas/seon.flow.edn` (`::thread`, `::refused`, the receipts on the completion); `test/seon/flow_test.clj`, `test/seon/cluster/boot_test.clj` | A01, G02 | R1 |
| G-agent | `src/seon/cluster/agent.clj`; `resources/seon/schemas/seon.agent.edn` (the promise-valued arming member, if declared); `test/seon/cluster/agent_test.clj` | G03 | R1 |
| G-effect | `src/seon/effect.clj`; — ; `test/seon/effect_test.clj` | G01, A03 | P2 |
| T test | `src/seon/test.clj`, `src/seon/test/runner.clj`, `src/seon/plan.clj`; — ; `test/seon/test_test.clj`, `test/seon/test_runner_test.clj` | A02, C04, B09, B10, E08 (test), F15, F21 (plan) | P2 |
| B-platform | `src/seon/schema.clj`, `src/seon/call_preparation.clj`, `src/seon/test/accretion.clj`, `src/seon/schema/admission.clj`, `src/seon/cluster/status.clj`, `src/seon/fn/analyzer.clj`, `src/seon/eval/drive.clj`, `src/seon/web/jvm.clj`, `src/seon/bootstrap.clj`, `src/seon/render/ns.clj` (**B12, moved here**), `script/seon/dev/docstring.clj`; — ; `test/seon/schema_test.clj`, `test/seon/call_preparation_test.clj`, `test/seon/test/accretion_test.clj`, `test/seon/schema/admission_test.clj`, `test/seon/dev/docstring_test.clj`, `test/seon/render/ns_test.clj` | the B rows, E08 (call_preparation), and F producer rows in these files | P2 |
| F producers | `src/seon/db.clj`, `src/seon/render.clj`, `src/seon/render/hiccup.clj`, `src/seon/fn.clj`, `src/seon/schema/internal.cljc`, `src/my/program.clj`; — ; their existing suites, named at launch | F producer rows, using fix-unbounded-capture-opus's named list | fix-unbounded-capture-opus lands; render slice 2(i); B-platform (for `render/ns.clj`, if F23 needs it) |
| bridge | `script/seon/dev/mcp.clj`; — ; `test/seon/dev/mcp_bridge_test.clj` | the surviving B17–B23 | delete-mcp-bridge-opus lands |
| M | at the one-pipeline S2 integration boundary: the reply-assembly owner's files; `src/seon/render/transcript.clj`; `src/seon/error.clj` (`occurred-since` only); `resources/seon/schemas/seon.cluster.eval.edn` (`fault-window` and its component, the reply outcome), `seon.error.edn` (`window-unknown`); `test/seon/problems_test.clj` | section M | P2, R4, one-pipeline S2, render slice 2(i). The owner files are named at S2 integration, not here |

**Startable now** (subject to root's grant at launch):
- **S-process**, when delete-duplicates-opus round 3 releases `process.clj`. Its other files are free.
- **P0**, when fix-unbounded-capture-opus releases `sci/kernel.clj` and root confirms `instrument.clj` is free.
  The review names P0 as startable; its `kernel.clj` hold is new since that review.
- **P1 and P2**, as read-only investigations in `tmp/` only:
  - P1: the 0.1 probe on a scratch graph;
  - P2: on a `d/with` value, the current-opening recipient read and the obligation upsert across two turn ids.

Nothing else can start: R1, R1t, R2, R3, R4, L, G-agent, G-effect, T, B-platform, F producers, bridge and M each
wait on predecessors or holders. **Default is being nuked.** Every cut's installed proof waits for the rebooted
default, and grants are rechecked then.

**Shared files, in order:**
- `flow.clj` and `flow_test.clj`: P1, then R1, then L.
- `seon.flow.edn`: P1, then R1, then L.
- `fault.clj`: P0, then P2, then R4.
- `error.clj`: fix-unbounded-capture-opus, then P2, then R4, then M.
- **The capture lane's expanded holds** (ledger rows 446 and 448; correction 4) come before these cuts:
  - `seon.sci.eval.edn` → R2;
  - `src/seon/schema.clj` → B-platform;
  - `src/seon/sci/admit.clj` → render slice 2(i);
  - `src/seon/test.clj` → T;
  - `seon.env.edn` and `seon.schema.edn` → whichever cut first needs them;
  - `render.clj` (render-result admission) → F producers.

  None of these cuts is independent of the root-cause cut. The capture landing is PARTIAL, and a released hold is
  not proof that its safety dependency is installed.
- `boot.clj`: R1, then L.
- `agent.clj`: R1, then G-agent.
- `kernel.clj`: fix-unbounded-capture-opus, then P0, then R3.
- `instrument.clj`, `seon.instrument.edn` and `instrument_test.clj`: P0, then R3.
- `sci/eval.clj`: R2, then R3.
- `fault_message_test.clj`: P2, then R1, then R4.

**Estimates** (planning figures, not measured; unique per cut):

| Cut | Source − / + | Schema | Tests |
|---|---|---|---|
| S-process | −11 / +14 | 0 | +20 |
| P0 | −40 / +9 | −3 | convert 4 rows; +40 |
| P1 | 0 / +30 | +3 | +45 |
| P2 | −4 / +32 | +4 | +50 |
| R1 + R1t | −300 / +40 | −70 | −150 / +45 |
| R2 | −70 / +15 | −3 | +25 |
| R3 | 0 / +21 (unproved) | +3 | +45 |
| R4 | −8 / +6 | 0 | +15 |
| L | −22 / +34 | +3 | +50 |
| G-agent, G-effect | −15 / +26 | 0–1 | +45 |
| T | −5 / +22 | 0 | +30 |
| B-platform, bridge | −75 / +45 | 0 | +40 |
| F producers | −55 / +28 | 0 | +20 |
| M | −105 / +45 | +4 | +55 |
| **Total (S-scripts excluded; it has landed)** | **about −710 / +367, net ≈ −343** | **about −59** | about −150 / +525 |

## 9. Questions and rulings

- **9.1** RULED by the owner: root by default, and faults surface in MCP replies (section M).
- **9.2, 9.4** Adopted from Astra.
- **9.3** The fault port (0.1). Its production guarantee depends on the probe and on the two named dependencies.
- **9.5** Follow-up. After G-effect and T land, compare the two join-then-consume watchers line by line, and extract
  a shared helper only if they are identical.
- **9.6** RULED by root. Responsibility comes from stored program call data, and ambiguous cases return to the
  agent. The three evidence cases and the data path are in D.2.
- **9.7** Resolved by evidence. F01's HTML deletion landed in `e62d8489c` (the capture lane), and retained admission converts in render slice 2(i). F07/F22 stay in F producers after the capture lane; root confirms from that lane's final diff.

## 10. Evidence and limits

Round 4 read these sources at `e6066f091`:
- `impl/protocols.clj:18-29` (WritePort and Handler) and `async.clj:200-211`;
- `error/refusal.clj:75-89`, `sci/admit.clj:295-297` and `clojure/core_print.clj:473-507`, via the review; none
  reads `.getSuppressed`;
- `error.clj:1293-1326` (opening and message identity), `:1441`, `:1537-1544` and `:1571-1616`;
- `seon.fn.edn:14`, `:137`, and `instrument.clj:477-489`, `:624-626`, `:705-713`, `:779-808`;
- `seon.error.occurrence.edn:3-5`;
- the ledger row for fix-unbounded-capture-opus;
- the owner rule at `c29e5f040`.

Evidence from earlier rounds still stands: `sci.core/stacktrace` shows interpreted frames only; the edamame and
`clojure.edn` rejection shapes; 32 signatures against 9 notifications; and the F01 face timings.

Not run, because default is being nuked and none of it is read-only: every test, the 0.1 probe, the P2
investigation, the R3 reach read, the G14/G15 measurements, and the F storage and M scan timings. These are
obligations of the implementing cuts. Unavailable evidence is not a pass.

Source delta **0**; only this file changed.
