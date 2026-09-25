---
type: design
status: "first pass (Opus); awaiting Astra review and root rulings on §9"
created: 2026-09-25
scope: One owner change per lost-error class A–G from the 2026-09-25 audit
---
# Lost-error seams: one owner change per class — 2026-09-25

What exists: Astra's [lost-errors audit](lost-errors-audit-2026-09-25.md) (`64ae7aca5`, pinned `93711ba43`), which
lists 106 path instances in seven classes, each with an owner seam. The data flow this design fixes is
*failure → owner boundary → durable fact + delivered wake → policy*, plus *task → exit → consumed terminal* and
*value → one floor bound → two faces*. The principal Clojure approach is to make each owner's contract total, so
the class cannot happen. Every per-site copy then becomes dead code, and each lane deletes it in the same cut.
Nothing here is a new registry, supervisor, retry or second printer.

Status: first-pass design (Opus). It needs Astra's review, then root's ruling on the open questions in section 9,
before any implementation lane starts. Only this file is authored here, with no source edits. Line numbers are
**HEAD `2d23029cb`** unless marked *pinned*.

## 0. Two findings that reshape the audit's seams

**0.1 The fault channel is a second, lossy fault route, and it should be deleted, not repaired.** `var-process`
already records every proc step's throw on the proc's own thread through `fault!`, and stops the graph on panic
(`flow.clj:198-232`, `:290-303`). Its docstring gives the reason: Flow's error channel is a sliding buffer
(`reference-code/core.async/.../flow/impl.clj:102`). In parallel, a second route still exists:

- the error fan-out: `start-error-fanout!` / `join-error-fanout!` (`flow.clj:982-1081`);
- the fault-committer graph with its own policy copy, `fault-committer-step` (`flow.clj:839-889`), which is B01's
  swallowing catch;
- `CountedDroppingBuffer` and its overflow summary (`flow.clj:742-835`), which is C03's evidence loss;
- `report-committer-loss!` (`:937-959`);
- four injected callbacks, `:seon.flow/commit-fault!`, `commit-drop!`, `panic!` and `read-core-error-mode`
  (`cluster.clj:3549-3571`, `boot.clj:83-94`), plus `emit-core-fault!` (`cluster.clj:3341-3364`);
- six producers that `offer!` a fault and fall back to stderr: `turn.clj:5484`, `:5507`; `web.clj:2336`, `:2936`,
  `:3546`; and the agent fan-out join `agent.clj:1153-1160`.

No production graph uses a connection xform: `rg ':xform' src` finds nothing. After `var-process`, Flow's error
channel therefore receives only Flow-internal failures (`impl.clj:312-316` send-outputs and the outer loop). The
owner change for B, C and E is **one route**: every producer calls `fault!` on its own thread. `record!` becomes
private, so no caller can record a fault without the policy. A single per-graph reader drains Flow's own error
channel on that graph's own executor, so the graph's join also joins that reader.

**0.2 Every task-owning site hand-rolls the same FutureTask pattern: bounded get, cancel, forget.** The sites are
`flow.clj:465`, `effect.clj:481`, `test.clj:238-311`, `shell/jvm.clj:90-124`, `cluster/process.clj:266`, and five
in `operator.clj`/`dependency_digest.clj`. `FutureTask.cancel(true)` makes `.get` return at once, so it proves
nothing about exit. A and G therefore share one owner: a task whose own `finally` is the exit event and the
exactly-once consumer. This design places that owner in `seon.await`, next to the bound, and keeps `io-terminal!`
as the io work's consumer inside it. Measured cost is a promise delivered in `finally`: **+44 ns per task**
(28 → 72 ns, 100,000 warm iterations, JVM mode on PID 37460).

## A. Asynchronous results not consumed on every exit (13)

**1. Seam and missing contract.** `seon.flow/io-terminal!` (`flow.clj:398-424`) settles once per submission by
CAS, but it has two gaps.
(a) `complete!` runs inside `try/finally` with no catch. A throwing callback removes the submission (`:414-415`),
still offers a success terminal to the launcher (`:416-424`), and leaves the exception in a `FutureTask` nobody
`.get`s (`:444-465`). This is A01, confirmed at HEAD.
(b) The stopping thread may settle *running* work before exit (`:628-644`). The task's later `io-terminal!` then
loses its CAS, and its real result or exception disappears. This is A-class loss created by the G02 path.

**2. Smallest owner change.** Add `seon.await/task` (about +25 lines in `await.clj`). It takes a callable and an
`on-exit` function of `{::value v}` or `{::throwable t}`, and returns `{:seon.await/future f :seon.await/exit p}`.
The task body is `(try (deliver-terminal (body)) (catch Throwable t (deliver-terminal t)) (finally (deliver p)))`.
`on-exit` runs exactly once, on the task's own thread, after the body. If `on-exit` itself throws, that throwable
goes to `fault/fault-detached!` (section B.2), never into the future.
`io-terminal!` becomes the `on-exit` of io work. Its `complete!` throw is caught and faulted, and the completion
offered to the launcher then carries `::throwable`, so the launcher reports a failure, not a success
(`:518-519` already handles `::throwable`). `stop-work-launcher!` only interrupts running work. Queued work still
settles by CAS (`:633`), because queued work never started.

**3. Migration** (sites at HEAD; each loses its hand-rolled FutureTask, watcher or unread future):

| ID | Site | Conversion | − / + |
|---|---|---|---|
| A01 | `flow.clj:444-473` | the runnable becomes `await/task` with `on-exit` = `io-terminal!` | −14 / +4 |
| A02 | `test.clj:272-311` | the body becomes `await/task`; `on-exit` interprets the result and calls `release!`; delete `watch-exit!` (`:282-284`) and the release-before-`.get` ordering (`:290-291`) | −18 / +6 |
| A03 | `effect.clj:481-512` | `await/task`; a late handler failure reaches `on-exit` → `fault-detached!` and marks handler exit on the effect row | −8 / +6 |
| A04 | `flow.clj:1055-1081`, `agent.clj:1153-1160` | deleted with the fan-out (section C); the per-graph error reader runs on the source graph's executor | −35 / 0 |
| A05, A06 | `operator.clj:553-580` | the accept and read tasks become `await/task`; the losing task is consumed by `on-exit` after the socket closes | −6 / +6 |
| A07 | `process.clj:266-274` | the output reader becomes `await/task`; `run-process!` awaits its exit before returning or throwing | −4 / +4 |
| A08 | `shell/jvm.clj:87-124`, `:330-408` | the capture threads become `await/task`; delete the promise-per-stream plumbing | −20 / +8 |
| A09 | `web.clj:2889-2945` | the feed writer's catch calls `fault-detached!` unless the failure is the declared client close (`painting` false *and* an `IOException` from the SSE write) | −10 / +5 |
| A10 | `turn.clj:5545-5561` | the backstop runnable becomes `await/task`; the fault goes through `fault-detached!` (section C) | −6 / +3 |
| A11 | `operator.clj:757-759` | the census becomes `await/task`, consumed in `finally` | −2 / +3 |
| A12 | `dependency_digest.clj:69-83` | use `process/run-process!` (the existing owner) | −14 / +4 |
| A13 | `operator_move_test.clj:23-26` | use `process/run-process!` | −4 / +2 |

**4. Class regression** (`test/seon/await_task_test.clj`). One table over the real `submit!`/`io-terminal!` and one
`await/task`. Each row is a finite task that crosses its deadline, then throws a nested `ex-info`, plus a
`complete!` that throws. Assert exactly one stored, delivered fault per failure, and no `::work-complete` success
report. Assert the submission is retained until the task's `finally` has run, and that the normal, interrupted
and timeout rows share the same terminal assertions. No Var redefinition.

**5. Cost.** +44 ns per task, plus one closure. The launcher step itself is unchanged.

**6. Order.** After E0, so that `fault-detached!` exists and delivers. G0 (`await/task`) is this class's first
slice.

**7. Files.** `await.clj`, `flow.clj` (launcher half), `test.clj`, `effect.clj`, `process.clj`, `shell/jvm.clj`,
`operator.clj`, `dependency_digest.clj`, `web.clj` (A09 only), `turn.clj` (A10 only).

## B. Catch converts failure to absence, fallback or incomplete evidence (35)

**1. Seam and missing contract.** `seon.fault/fault!` (`fault.clj:179-234`) itself never swallows. A recording that
does not commit becomes a panic with the outcome suppressed (`:224-234`). Its gap is structural: `record!`
(`:33-160`) is public and *total*, so any caller can record and ignore the tuple. Callers do exactly that, at the
committer (`flow.clj:883`), `boot.clj:84`, `boot.clj:362`, instrumentation (`instrument.clj:721`) and
`sci/eval.clj:2488`. Beyond that, nothing distinguishes a catch that handles a declared rejection from one that
disguises a machinery fault. Also, `record!`'s last resort prints `(str fault)` (`:159`, F08).

**2. Smallest owner change.**
(a) Make `record!` private (`defn-`). Every recording then passes the policy.
(b) Move `proc-failed!`'s body (`flow.clj:212-232`) into `seon.fault/fault-detached!`. This is "fault! on a thread
nobody awaits": record, deliver, and on panic print the receipt and return true, so the caller can stop its graph.
`proc-failed!` calls it (−20 lines in `flow.clj`), and so do the A/C detached producers.
(c) Replace `(str fault)` with a fixed message; the fault itself stays in the tuple as a value.
(d) A lint row in the existing clj-kondo config forbids direct `fault/record!`. No new tool is needed; the
`defn-` alone is enough.

The per-site rule has exactly three forms:
- **Core operation:** delete the catch. The throw reaches its owning boundary (`var-process`, the evaluation
  boundary, `fault!` at the request writer).
- **Declared rejection:** the `try` wraps only the parse call and matches its declared rejection. For edamame that
  is `ExceptionInfo` with `:type :edamame/error`, probed at HEAD. `clojure.edn` throws a bare `RuntimeException`,
  probed at HEAD, so its try must cover the read call alone.
- **Typed unknown:** keep the unknown and add `:seon.error/throwable failure`. `seon.error.refusal/diagnostic`
  (`error/refusal.clj:91`) already keeps class, message, `ex-data`, chain and Seon frames.

In script processes with no connection (`mcp.clj`, `operator.clj`, `docstring.clj`), the same `diagnostic` travels
to the operator's boundary unchanged. Cleanup failures become `.addSuppressed` on the primary failure.

**3. Migration.** B01 is deleted with the committer (section C). The rest:

| Rule | IDs (HEAD file) | Per site |
|---|---|---|
| delete catch | B02 `schema.clj:247`, B03 `schema.clj:1122-1131`, B06 `call_preparation.clj:486`, B07 `ai.clj:868`, B08 `test/accretion.clj:185` (keep only Malli's declared `::m/no-generator`), B11 `schema/admission.clj:366`, `:493`, B14 `shell/jvm.clj:388` (keep the declared broken-pipe `IOException`) | −3 to −6 each, about −35 total |
| add throwable | B04 `turn.clj:3406-3416`, B05 `sci/kernel.clj:548`, B09 `test/runner.clj:954`, B10 `test.clj:105`, `:1471`, B13 `render/value.clj:309`, `:341` (under the render slice), B15 `web/jvm.clj:349`, B16 `cluster/status.clj:44`, `:92`, `:162` | +1 each, about +12 |
| narrow to declared parse | B11 `admission.clj:100`, B12 `render/ns.clj:134-151`, B24 `dev/docstring.clj:303` (a parse failure becomes a finding, never `clean? true`), B26/B27 `fn/analyzer.clj:807`, `:852`, B28 `print.cljc:108`, `:1081` (render slice), B29 `repl.clj:89`, `:124`, `:166`, `:463`, B30 `render/transcript.clj:1201`, B31 `sci/eval.clj:1199`, B32 `eval/drive.clj:125`, B33 `web/jvm.clj:427`, B35 `bootstrap.clj:94` | ±0 to −2 each |
| script boundary keeps the chain | B17–B23 `script/seon/dev/mcp.clj` (seven boundaries in one bridge loop), B25, B34 `operator.clj:295`, `:466` | −25 / +12 |

Estimated total: B about **−140 / +60**.

**4. Class regression** (`test/seon/fault_route_test.clj`, shared with E). A real core operation throws an
exception with a nested cause, namespaced `ex-data` and a Seon frame, once normally and once during recording.
Assert that the full chain reaches the stored fact, or that an explicit storage-failed panic occurs. Assert that
no nil, default, clean or skip value appears. Assert that edamame input which is not EDN remains a declared
rejection.

**5. Cost.** Deleting catches costs nothing. `refusal/diagnostic` runs only on the failure path.

**6. Order.** After E0 (the private `record!` and `fault-detached!`). The B rows in `turn.clj`, `sci/eval.clj` and
`test.clj` wait for those files' holders.

**7. Files.** The files named in the table, split into two lanes: platform (`schema`, `call_preparation`, `ai`,
`accretion`, `runner`, `admission`, `status`, `analyzer`, `transcript`, `drive`, `web/jvm`, `bootstrap`) and
script (`mcp.clj`, `operator.clj`, `docstring.clj`). The rows in `render/value.clj`, `print.cljc` and `repl.clj`
belong to the render slice (section F).

## C. Exhaustion becomes no work, or its terminal fault is not durable (6)

**1. Seam and missing contract.** `fault!` is the owner again, but these producers bypass it. Exhaustion is
decided by a scheduler that returns nil (`turn.clj:2739`, `(nil? limit) true`, C01, confirmed at HEAD). Terminal
faults are offered to a buffer that reports success while keeping only a digest (`turn.clj:5466-5512`, C03,
confirmed at HEAD).

**2. Smallest owner change.** Delete the fault channel (section 0.1). `offer-write-refusal-fault!` and
`offer-turn-backstop-fault!` call `fault-detached!` with `(env/of cluster)`, and their stderr fallbacks go.
`opening-deferred?` answers the *typed* missing-dial error instead of `true` when the limit is absent. The
scheduler's first deferral then commits that error once, through `fault!`, deduplicated by the writer's existing
signature/`opening` (`error.clj:1363`, `:1443`). The same happens for a pre-exhausted agent at arm (C02). No retry
and no new identity are added.

**3. Migration.**

| ID | Conversion | − / + |
|---|---|---|
| C01, C02 | `turn.clj:2739`, `:2913`: nil limit → the typed error; faulted once at the first deferral | −2 / +10 |
| C03 | `turn.clj:5466-5512` → `fault-detached!`; delete `flow.clj:742-1108` except `monitor-graph` and a 15-line per-graph error reader; delete the `cluster.clj:3341-3364` and `:3542-3600` wiring, `boot.clj:41-47` and `:83-94`, `agent.clj:1153-1160`, `web.clj:2322-2345`, `:2934-2944` and `:3546-3556` (→ `fault-detached!`); in schemas delete `seon.flow.edn:28-116` fan-out/committer members and the `:seon.flow/dropped-fault-*` family (`seon.flow.edn:190-196`, `seon.error.edn:108`, `:144-149`, `:272`, `seon.error.occurrence.edn:40-41`), plus `boot.edn:75-77`, `wake.edn:25` and `render.web.edn:21`, `:117` | about −480 / +30 |
| C04 | `test.clj:2308`: a deadline before an unadmitted batch stores the typed await timeout on the run row; `plan.clj:716-725` propagates an incomplete result | −3 / +8 |
| C05 | `dependency_digest.clj:78-82` → `run-process!` (with A12) | counted in A12 |
| C06 | `issue.clj:77-88`: a nonzero exit returns the process refusal instead of nil | −1 / +3 |

**4. Class regression** (`test/seon/exhaustion_fault_test.clj`). Start from a branch holding an unanswered wake and
a missing episode dial, and another holding an exhausted allowance. Assert one stored, delivered fault naming the
missing dial or `seon.run/complete`, no duplicate on re-derivation, and no paid call (no-provider). A completed
episode produces no fault.

**5. Cost.** Hot paths get cheaper. Every agent graph loses one blocked virtual thread (the fan-out), and every
fault skips a channel hop. `fault!` already runs on the proc's thread for step throws.

**6. Order.** After E0. C03 must land before A09 and A10 (they call `fault-detached!` instead of `offer!`).

**7. Files.** `flow.clj` (fault half), `cluster.clj`, `boot.clj` (wiring only), `agent.clj` (join only), `turn.clj`
(offers and the dial), `web.clj` (three offers), the schema files, `test.clj`/`plan.clj` (C04) and `issue.clj`.

## D. Returned value escapes the declared error alternatives (4)

**1. Seam and missing contract.** `seon.instrument/compiled-wrapper` (`instrument.clj:624-726`). Under `:record`,
its outer catch returns the boundary refusal *as the function's value* after recording it (`:715-726`). That value
is outside Malli's output check (`malli/core.cljc:2202`, `:2227`) and outside the arity's declared output. D01 is
confirmed at HEAD. The same branch sends *invalid caller input* through `commit-fault!` as a core fault (E05).

**2. Smallest owner change.** Delete the catch: the wrapper is `wrapped` (Malli's `-instrument`), which always
throws the marked `ex-info`. Classification then belongs where the throw is caught, as the law requires:
- The **evaluation boundary** (`sci/kernel.clj:653-681`) already turns the throw into the evaluation's returned
  value. An `:input` refusal, and an `:output` refusal of an agent-authored function, are the agent's mistakes and
  return to it.
- An `:output` or `:guard` refusal of a host (default program) function is a core fault. That boundary calls
  `fault!` on it, and rethrows any throwable that carries a `:seon.fault/recorded` receipt (E07).
- A host caller outside SCI gets the throw at its own `var-process` or request boundary, which already calls
  `fault!`.

The wrapper's `policy` then carries only `:seon.config.error/max-evidence-bytes`. That deletes the commit-fault!
requirement and its validation.

**3. Migration.**

| ID | Conversion | − / + |
|---|---|---|
| D01, E05 | delete `instrument.clj:715-726`, the commit-fault! checks `:470`, `:491-499`, `:860-871`, `:901-902` and `seon.instrument.edn:42-44` | −42 / +2 |
| E07 | `kernel.clj:653`: rethrow a receipt-bearing throwable; call `fault!` for a host function's `:output`/`:guard` refusal | +8 |
| D02, D03 | `ai.clj:114`, `:120`: declare the callee's `:seon.render.value/missing-root-identity-error` alternative. §1 of the render path allows any spec'd output. Once render slice 2(i) removes the missing-root arm from `render-ai`, delete it again | +2 |
| D04 | `background.clj:59-72`: return the `db/pull` error before success projection | +2 |

**4. Class regression** (`test/seon/instrument_alternatives_test.clj`). One real armed composition: a `:string`
function whose callee takes a failing branch, run under both `:panic` and `:record`. Assert that no non-string
value escapes, that an input refusal returns to the agent with no fault occurrence, and that a host output
refusal is stored and delivered. Parameter rows cover the rootless attempt unit and the background read error.

**5. Cost.** Negative: one `try` frame per armed call is removed. The evaluation boundary adds one `ex-data` check
on the failure path only.

**6. Order.** Independent of every other class, so it can land first. E07 is in `kernel.clj`, which no running row
holds.

**7. Files.** `instrument.clj`, `seon.instrument.edn`, `sci/kernel.clj`, `ai.clj` (with the B07 row, the same
lane), `background.clj`.

## E. Fault classification, custody or delivery is wrong (9)

**1. Seam and missing contract.** `seon.error/commit-call` (`error.clj:1342-1466`). It infers responsibility from
`interrupted? (some? :seon.error/exception-class)` (`:1406`), routing a core throwable to the *executing* agent as
`:your-run` (`:1446`). With `escalate-to` absent, the shipped default (`:1449-1454`), unattributed faults wake
nobody, and `fault!`'s receipt checks storage only (`fault.clj:212`). `fault!`'s docstring claims "root by
default" (`:188`), which is false.

Live evidence at HEAD (JVM mode, PID 37460, read-only): the effective `:seon.config.error/escalate-to` is absent,
and there are **32 error signatures but 9 notification ids**. So 23 recorded faults were delivered to no agent.

**2. Smallest owner change (E0).**
(a) `commit-call` routes only core faults, because `fault!` is the only route into it (after B.2 and C). Its
recipients are the steward, otherwise `escalate-to`. Delete the `:your-run` arm and the `interrupted?` inference.
(b) `record!` returns the recipient that the transaction opened, or the recipient already open for that signature.
`fault!`'s receipt gains `:seon.fault/delivered-to`.
(c) `policy` takes `committed? ∧ delivered?`. An undeliverable fault panics under both modes, like an unstored one.
(d) The shipped `escalate-to` becomes `"root"` (option 1 in section 9), and `fault!`'s docstring becomes true.

**3. Migration.**

| ID | State at HEAD | Conversion |
|---|---|---|
| E01 | confirmed (`error.clj:1406`, `:1443-1455`) | (a)–(d): about −6 / +14 in `error.clj` and `fault.clj`, +1 in `seon.config.error.edn` |
| E02 | **superseded**: the watcher now calls `fault!` through `core-fault!` (`test.clj:2192-2203`, `2253`) | none; A02 still holds |
| E03 | **superseded** by `4c8e3588e` (`test.clj:2186-2203`) | none |
| E04 | **superseded**: the recording binds its own custody (`sci/eval.clj:2481-2482`, `e8cada147`) | none |
| E05 | confirmed | lands with D01 |
| E06 | confirmed (`boot.clj:342-380`) | `record-uncaught!` calls `fault-detached!`; with an environment that names an agent, a panic stops that agent's graph through routing; without an environment, emergency stderr stays (the storage-fails-panics law): −20 / +6 |
| E07 | plausible → resolved in D | lands with D |
| E08 | confirmed (`turn.clj:3406-3443`, `call_preparation.clj:1269-1303`, `test.clj:1471`) | the per-site classifier `analyze-submitted` (`turn.clj:3434-3442`) is the pattern to delete. The phase boundary calls `fault!` for any failure whose declared schema is not in the phase's declared agent-mistake alternatives, and those are already enumerated in its arity (`:3427-3428`): −9 / +6 |
| E09 | confirmed: second disposition via `commit-fault!` (`sci/eval.clj:2487-2495`), `config/defaults` fallback (`:2448-2450`), and a third writer calling `commit-tx` directly (`:2456-2482`) | see question 9.2. Either way the `config/defaults` fallback is deleted (it violates "`seon.config/defaults` is a compiled constant"), and the `commit-fault!` branch is deleted with the callback key (31 references in `sci/eval.clj`, `seon.sci.eval.edn:244-246`): about −45 / +8 |

**4. Class regression** (the E half of `fault_route_test.clj`). One nested platform failure, sent as a Throwable
and as a flat core error, each with and without executing-agent attribution, on a disposable branch that has a
root agent. Assert identical routing to the steward or root, the full cause, and the occurrence and message
committed in one transaction. Under `:panic`, assert the failing graph stops. On a branch with no root agent,
assert an undeliverable-fault panic. An invalid authored call returns to its agent and creates no occurrence.

**5. Cost.** On the fault path only, one set lookup of the recipient in the transaction data already built.

**6. Order.** E0 is first among B, C and E, because B and C route through `fault!`/`fault-detached!` and their
regressions assert delivery. D can run in parallel.

**7. Files.** `fault.clj`, `error.clj` (`commit-call` only), `seon.config.error.edn`, `flow.clj`
(`proc-failed!` → `fault-detached!`, 20 lines), `boot.clj` (`record-uncaught!` and the arm request), `sci/eval.clj`
(the `commit-fault!` retirement, E09), `turn.clj` (E08).

## F. Arbitrary value expands before the single presentation bound (23)

**1. Seam and missing contract.** `seon.render.value/prepare` (`value.clj:621-668`) bounds only the AI face
(`:512`, `:643-644`). HTML visits the whole value (`:447`, `:512`), the key rendering uses HTML (`:537-541`, F03),
and window ordering prints every key (`:300-301`, `:413`, F04). All three are confirmed at HEAD.

F01 is measured at HEAD (JVM mode, read-only). A 50,000-entry vector of small maps prepares its AI face in
**3.4 ms, 1,391 chars**. The HTML face that `prepare-result` stores as the "complete" blob (`error.clj:498`) takes
**2,113 ms and 1,939,885 chars**, with 2.4 M `hiccup?` calls. Recording the error that explains a large value
costs more than the failure itself.

**2. Smallest owner change.** None is new here. The owner change is render slice 2(i) of the
[one render path](one-render-path-design-2026-09-25.md) (§3, §6 row 2): one held source, safe structural
selection under one budget B, one tree and two faces. It deletes `value-node*`, `window`, `stable-entries` and the
`pr-str` tie-break (§6 row 2 (i)). This class adds only the producer rule that makes the floor the *only*
expansion: **a diagnostic carries its offending value as a value (`:seon.error/offending` or its declared member)
and fixed prose, never `pr-str` inside a message.** The floor then bounds the value whenever anything prints it.
Durable exact evidence stays what it is: blob artifacts of the value, via `artifact`/`artifact-edn`
(`value.clj:683-716`), never a rendered face.

**3. Migration.**

| IDs (HEAD) | Conversion | − / + |
|---|---|---|
| F02, F03, F04, F13 (`repl.clj:491`), F12 (`web.clj:795-798`), F18 (`problems.clj:587-633`) | render slice 2(i) and slice 4, counted there | 0 here |
| F01 `error.clj:498-500` | store the offending value's artifact (`admit` → `artifact-edn` → `blob/put!`) instead of the HTML face's text; `shown` stays the AI face | −3 / +3 |
| F07 `error.clj:1122-1130`, `:2026`, `:2069`; F22 `:905` | fixed prose plus the value in data; the elision HTML goes through the floor | −10 / +4 |
| F08 `fault.clj:159` | lands with B.2(c) | −1 / +1 |
| F05 `render/hiccup.clj:280` | an invalid tag's error carries the head as its offending value; serialization of already prepared trees is unchanged | −2 / +1 |
| F06 `db.clj` (9 sites), F09 `sci/eval.clj:3589`, `:3637`, `:3793`, F10 `turn.clj:3704`, `:3753` (`:2200` is exact source bytes, not presentation; keep), F11 `render.clj:1925`, `:1968`, F14 `accretion.clj:136-138`, `:370-375`, F15 `test.clj:1944`, `runner.clj:398`, F16 `fn.clj:58-59`, F17 `schema/internal.cljc:469-523`, `admission.clj:409-410`, F19 `cluster.clj:3470`, `status.clj:177`, F21 `background.clj:55`, `my/program.clj:265`, `plan.clj:1367`, F23 `render/ns.clj:233-511` | the same rule, scripted once across the list (`pr-str` inside `str` inside an error constructor → value member), then linted and loaded, with the residue fixed by hand | about −60 / +30 |
| F20 `script/seon/dev/mcp.clj:440` | delete the `*print-length*`/`*print-level*` nil binding; non-string content goes through the JVM floor (`value/floor`) before the bridge | −3 / +1 |

**4. Class regression.** This is the render path's §3h row 8 ("one tree across faces"), extended with one error-path
row. The same nested failure value (a large string, a composite map key, and a finite lazy tail that throws if
realized past the permitted prefix) goes through `fault!`, the AI face and the HTML face. Assert that both
terminate within the same fixed profile, that the tail is not forced, that one cut/requery identity is shared, and
that the stored artifact reads back as the value.

**5. Cost.** Error recording drops from O(value) to O(B) (2,113 ms → about 3 ms on the probe). Error construction
stops printing, which is negative cost.

**6. Order.** F02–F04 wait for render slice 2(i). The producer rows can land before it: carrying a value is correct
under today's floor too, since the AI face is already bounded. F01, F07 and F22 in `error.clj` wait for E0.

**7. Files.** `error.clj` (after E0), `db.clj`, `render/hiccup.clj`, `render.clj`, `accretion.clj`, `fn.clj`,
`schema/internal.cljc`, `admission.clj`, `problems.clj` (slice 4), `status.clj`, `background.clj`, `my/program.clj`,
`plan.clj`, `render/ns.clj`, `mcp.clj`, `cluster.clj` (after C), `sci/eval.clj`, `turn.clj`, `test.clj` and
`runner.clj` (after their holders).

## G. Unbounded wait, or cancellation mistaken for exit (16)

**1. Seam and missing contract.** `seon.await/await!` (`await.clj:106-153`) bounds the *observation*. Its docstring
leaves cleanup to the caller (`:122-123`). For a Java future, the timed `.get` (`:144-147`) followed by the
caller's `.cancel` proves nothing about exit. There is also no way to ask for "the exit event".

**2. Smallest owner change (G0).** `await!` accepts the `seon.await/task` value from section A.2:
- **Completion** returns the value, or rethrows the throwable, which is consumed once.
- **Expiry** interrupts the task and returns the typed timeout, whose diagnostic names `::task-exit` as the
  missing event. The caller keeps custody until `:seon.await/exit` is delivered. That is a second bounded await
  that *reports* a missed exit and never releases.

Exit proof for Flow work reuses the existing seam, executor termination (`join-graph!`, `flow.clj:134-160`). The
work launcher's io tasks move from the shared root `:io` executor (`flow.clj:608`) onto the launcher graph's own
executor, so `join-graph!` proves every handler has exited (G02). `start-graph!` accepts a supplied `::executor`
(+3 lines). Subprocess exit reuses `process/run-process!` and `terminate-subprocess!`, which already await
`onExit` and return survivors (`process.clj:175-189`).

**3. Migration.**

| ID | Conversion | − / + |
|---|---|---|
| G01 | `effect.clj:505-512`: with A03 the handler is an `await/task`; on timeout the effect row stays claimed until `exit` fires, and a new effect on that id refuses | −4 / +6 |
| G02 | `flow.clj:608`, `:628-646`: launcher tasks run on the graph's executor; stop interrupts, and `drained` fires from the tasks' own `io-terminal!` | −8 / +5 |
| G03 | `agent.clj:796`, `:1318-1320`: deref the arming/stopping delay through `await!` with the turn-completion backstop bound; a timeout keeps the release mark | −2 / +8 |
| G04 | `shell/jvm.clj` (with A08): capture threads become tasks, and the process result waits for their exits | counted in A08 |
| G05 | `process.clj:289-303` (with A07) | counted in A07 |
| G06–G10 | `operator.clj:553-580`, `:616-624`, `:901-902`, `:1513-1514`: the timed races use `await/task` for the socket tasks; the direct `destroyForcibly` sites call `process/terminate-subprocess!` and fail on survivors | −20 / +8 |
| G11 | `dependency_digest.clj` (with A12) | counted in A12 |
| G12 | `issue.clj:79`, `test/cache.clj:47`, `:196` → `run-process!` | −10 / +6 |
| G13 | `operator_move_test.clj:24-26`, `:111-112` (with A13); cleanup deletes only after a proved exit | −3 / +2 |
| G14, G15 | `sci/eval.clj:3106`, `:3217`; `call_preparation.clj:614-617`; `db.clj:3772` | **refute or measure first**: these are shared `delay`s of finite derivations. A delay forced by a second thread blocks on the owner's monitor, and that is not a new unbounded wait unless the derivation blocks. The lane measures one contended acquisition on the admitted entry path and converts only if it can hang |
| G16 | `flow.clj:278`: no production proc declares `:compute` (`rg` at HEAD), so `var-process` refuses `:compute` and the dependency's timed-get gap becomes unreachable | −1 / +1 |

**4. Class regression** (the G half of `await_task_test.clj`). A finite host task ignores its first interrupt and
waits on an injected latch. The caller crosses its timeout. Assert that a conflicting operation (a re-submit on the
same effect id, a test body on the same member) refuses while resources are still owned. Release the latch.
Assert that the exit is observed, that a late failure is consumed and faulted once, and that only then do release
and re-entry proceed. One row runs a subprocess survivor through `run-process!`. No infinite task.

**5. Cost.** +44 ns per task (measured). `awaitTermination` is already paid at graph stop.

**6. Order.** G0 before A (A.2 uses it). G02 shares `flow.clj` with C and A, so it goes in one `flow.clj` lane
after C.

**7. Files.** `await.clj`, `flow.clj`, `effect.clj`, `agent.clj`, `shell/jvm.clj`, `process.clj`, `operator.clj`,
`issue.clj`, `test/cache.clj`, `dependency_digest.clj`, `operator_move_test.clj`.

## 8. Order, lanes cut by file, and conflicts

The classes overlap heavily by file, so the implementation lanes below are cut **by file ownership**, each landing
its classes' rows for those files.

| # | Lane | Owns | Classes | Waits for |
|---|---|---|---|---|
| 1 | D | `instrument.clj`, `seon.instrument.edn`, `sci/kernel.clj`, `ai.clj`, `background.clj` | D, E05, E07, B07, F21 (background) | nothing |
| 2 | E0 | `fault.clj`, `error.clj`, `seon.config.error.edn`, `flow.clj` (`proc-failed!` only) | E01, B.2, F08 | nothing; D in parallel |
| 3 | G0 | `await.clj` | G.2 / A.2 | nothing |
| 4 | fault channel | `flow.clj` (fault half), `cluster.clj`, `boot.clj`, `agent.clj`, `web.clj`, seven schema files | C03, A04, A09, E06, G03, F19 (cluster) | E0; `boot.clj` held by fix-boot-cost (readiness) |
| 5 | flow tasks | `flow.clj` (launcher half), `effect.clj` | A01, A03, G01, G02, G16 | 3, 4; `effect.clj` held by fix-cost-capabilities |
| 6 | turn | `turn.clj` | C01–C03, A10, B04, E08, F10 | E0, 3, 4; `turn.clj` held by fix-read-sink and fix-turn-hang r5 |
| 7 | eval | `sci/eval.clj`, `seon.sci.eval.edn` | E09, B31, F09, G14 | E0; held by fix-read-sink |
| 8 | test | `test.clj`, `test/runner.clj`, `plan.clj` | A02, C04, B09, B10, F15 | 3; `test.clj` held by impl-world-injection |
| 9 | subprocess | `process.clj`, `shell/jvm.clj`, `issue.clj`, `test/cache.clj`, `dependency_digest.clj`, `operator.clj`, `operator_move_test.clj` | A05–A08, A11–A13, B14, B25, B34, C05, C06, G04–G13 | 3 |
| 10 | platform catches | `schema.clj`, `call_preparation.clj`, `accretion.clj`, `admission.clj`, `status.clj`, `analyzer.clj`, `transcript.clj`, `drive.clj`, `web/jvm.clj`, `bootstrap.clj`, `db.clj`, `render.clj`, `render/hiccup.clj`, `render/ns.clj`, `fn.clj`, `schema/internal.cljc`, `my/program.clj` | B (rest), F (producer rows) | E0; `bootstrap.clj` and `render/ns.clj` must be checked against the render lanes |
| 11 | bridge | `script/seon/dev/mcp.clj`, `script/seon/dev/docstring.clj` | B17–B24, F20 | nothing |
| 12 | render floor | render slice 2(i) and slice 4 files | F02–F04, F12, F13, F18, B13, B28, B29 | its own owner go |

**Same-file conflicts that force a sequence:** `flow.clj` (2 → 4 → 5), `error.clj` (2, then F01/F07/F22 in lane 2's
second cut), `boot.clj` (4 after fix-boot-cost), `ai.clj` (1 only), and `turn.clj`, `sci/eval.clj`, `test.clj`
(one lane each, after their current holders). Lanes 1, 2, 3, 9 and 11 can start together.

**Net source-line estimates** (planning, not measured; tests excluded; regressions add about +40 each, six files):
A about −130 / +50, B −140 / +60, C −485 / +50, D −42 / +14, E −80 / +34, F −80 / +40 (excluding render slice 2),
G −48 / +36 (A/G shared rows counted once). Total about **−1,000 / +285, net ≈ −715**. The largest cut is C: the
fault channel and committer family, about −370 in `flow.clj` alone. Every lane stays under 100 added lines, except
C's lane 4, which is +30 added against −480.

## 9. Open questions (priced; recommendation marked)

**9.1 What "delivered" means for a core fault (E01). This conflicts with the 2026-09-24 ruling "no paid root
turns".**
1. **Recommended.** Ship `escalate-to "root"`. Delivery is the ordinary message fact. Whether it starts a paid turn
   is decided by root's provider configuration: `:no-provider`, the default-cluster policy. Guarantee: every fault
   has a durable addressee. Cost: +1 edn line, and fixtures need a root agent (`seed-root-agent!` exists).
   Sacrifice: root's inbox holds one message per signature.
2. Keep the absent default. Define delivery as open on the problems surface, and amend AGENTS.md "Recording
   without delivery is a defect". Cost: docs only. Sacrifice: nobody is woken; MCP polling is the reader.
3. Require `escalate-to` at config apply and refuse boot without it. Loud. Cost: a config refusal. Sacrifice:
   every cluster must name one.

**9.2 Acquisition refusals of authored rows (E09): returned mistakes, or recorded history?**
1. **Recommended.** Return them in the acquiring agent's opening as declared values, and delete the third writer
   (`sci/eval.clj:2437-2496`, about −55). Sacrifice: no durable history of refused acquisitions beyond the turn.
2. Keep the durable record, add a declared recipient member on the commit request for authored mistakes, and route
   through `commit-call`. Cost: +1 key, +15 lines.
3. Treat them as core faults through `fault!`. Cost: smallest diff. Sacrifice: wrong responsibility; the agent's
   own mistake wakes root.

**9.3 Flow's own error channel still slides (`impl.clj:102`, buffer 100) for Flow-internal failures.**
1. **Recommended.** Accept a documented residual: a per-graph reader calls `fault-detached!` on the graph's
   executor. After `var-process`, only Flow-internal failures arrive there, so more than 100 of them queued behind
   one fault write is not a realistic load. Cost: 0.
2. A fork patch letting `create-flow` take the error buffer, used with a blocking buffer. Cost: about 5 fork lines
   and a gitlink bump. Sacrifice: fork drift.
3. An upstream PR. Cost: time; ruled out by the no-PR standing rule.

**9.4 `stop-work-launcher!` returning a timeout with retained submissions (G02).** Its callers
(`cluster.clj` stop) must then keep the connection. The lane verifies that `cluster.clj:3711` already refuses
through `refuse!` on a timeout, and does not release, before relying on it.

## 10. Evidence and limits

Probes (JVM mode, `default`, PID 37460, read-only, no scratch namespace):
- the effective config (`escalate-to` absent, `:on-core-error :panic`) and the 32 signatures vs 9 notifications;
- the task-ownership overhead (+44 ns);
- the F01 face costs (3.4 ms vs 2,113 ms);
- reader rejection shapes (`clojure.edn` → bare `RuntimeException`; edamame → `ExceptionInfo :type :edamame/error`).

Runtime health was `faulted`, with 21 open faults that belong to other lanes (listed by `runtime_status`), and
nothing was changed.

The HEAD re-verification cited above covers A01–A04, A07, A09, A10, B01, B04, C01, C03, D01–D04, E01, E04, E06,
E08, E09, F01–F04, F13, G01–G03 and G16. E02, E03 and E04 are superseded at HEAD. G14 and G15 remain unproven
triggers and are scoped to measure first. No tests were run, there was no source delta, and line estimates are
planning values.
