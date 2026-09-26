---
type: review
status: complete
date: 2026-09-26
reviewer: gpt-6-astra
source-pin: 5bed888d02c300ca3dc798109687f2b88471a436
verdict: REVISE
blocking-findings: 2
---

# Lost-error R1 fault route: independent diff review

Deleting the lossy fault channel, committer graph and fan-out is a substantial simplification. Recording on the producing thread through `fault!` is the right composition. The turn timeout backstop is an execution boundary outside Flow's proc error port, and its new direct call does not finish the required stop-and-report protocol.

## Scope and evidence

Read `CLAUDE.md`, `lost-error-seams-design-2026-09-25.md`, and `docs/prds/agent-platform/landing/lane-impl-seams-r1-adoption-2026-09-25.md`. Reviewed actual source changes in `a7f612dc4` and `5d8e803f7`, sibling test conversions `aa5b8c5cd`, `9f3aa905d`, `f5788a9f4`, and adoption report `e2780cdce`, discovered through the requested R1 history. Checked final committed callers and `fault!` at the source pin above, including its later stop-request seam.

All line references are at that pin unless a dependency pin is stated. No JVM, tests or fault injection were run. The adoption report's `d/with` evidence is historical schema evidence, not this review's live proof.

## Findings

### R1 — blocking: panic on an active turn's backstop does not request that graph to stop

**Location:** `src/seon/turn.clj:5599`, `:5648`; `src/seon/fault.clj:237`.

**Failure scenario:** a turn remains stuck in a host call until the active completion bound fires, under default panic policy. The executor's separate backstop thread calls `fault!` without `:seon.fault/stop-request!`. It catches the returned panic receipt and places the original timeout failure in a promise channel. It neither runs inside `var-process` nor throws to the stuck turn's caller. Until something independently disarms the agent, that graph has received no panic stop request; if the host call later returns, execution can continue. If fault recording stalls, even publishing the failure is delayed behind storage.

**Smallest fix:** carry the existing execution owner's stop capability into the turn bound and pass it to `fault!`, so panic requests stop before recording. Preserve actual-exit ownership and join behavior after cancellation; do not equate requesting stop with termination. This implements the design's backstop boundary without another watcher or fault channel.

### R2 — blocking: the backstop discards recording failure evidence and can leave its join channel unresolved

**Location:** `src/seon/turn.clj:5604`, `:5650`; `src/seon/fault.clj:255`–`:272`.

**Failure scenario:** fault persistence refuses or throws. `fault!` deliberately produces a panic receipt with `:seon.fault/committed? false`, carrying a refused outcome or suppressed recording cause. `record-turn-backstop-fault!` catches *any* carried receipt, discards that wrapper and returns only the original timeout exception. Disarm therefore loses the storage failure and the evidence that no fault was committed. Separately, a failure before a receipt exists escapes the executor runnable while evaluating the argument to `async/put!`; no outer catch settles or closes the promise channel. The consumer must then diagnose a second timeout rather than receiving the original recording failure.

**Smallest fix:** settle the existing failure channel exactly once with the full terminal throwable, including the receipt/outcome/suppressed causes. Guard the outer runnable so pre-receipt failures take the same path and request stop. Do not catch a receipt merely to turn it back into an unrecorded original exception, and do not retry recording.

### R3 — should-fix: the revised web regression still replaces the production Var globally

**Location:** `test/seon/render/web_test.clj:2292` (`f5788a9f4`).

**Failure scenario:** the revised regression runs alongside the real renderer or another test in the shared JVM. `with-redefs-fn` replaces `web/derive-page!` globally with a throwing function, so unrelated execution can see the test fault and armed instrumentation is bypassed. The fixture also hand-builds mutable render state instead of exercising the admitted owner. Counting a matching message does not prove the production fault path under its real contracts.

**Smallest fix:** create the failing declared renderer in the test's isolated program/world and drive the existing render owner with that world. Inject only the external effect when needed. Keep the one recurrence behavior regression, using occurrence identity and delivery evidence; remove the Var replacement.

## Dependencies and law assessment

The primary cut removes duplicate machinery rather than renaming it. Flow reports proc failures to its `::flow/error` port (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:312`, pin `dc35f3e0d`). Its outer catch also reports there (`:317`). Supplying the existing proc port with a synchronous disposer fits that seam; it must remain total because a second throw from error handling is itself outside the failed transform. Flow's `stop` at `:174` closes channels after sending stop; it is not a proof of proc or host-call exit. R1's deletion does not eliminate that obligation.

Datahike executes transaction functions against its in-transaction value (`reference-code/datahike/src/datahike/db/transaction.cljc:1171`, pin `f3a3cb593`), the proper place for deduplication and durable delivery decisions. There is no reason to restore a lossy transport queue to perform those writes. Native fault data and stored occurrence references are preferable to the deleted overflow summaries. Malli input/output instrumentation (`reference-code/malli/src/malli/core.cljc:2202`, pin `befcd302a`) does not prove that an asynchronous runnable ever settles its channel; R2 requires the producer's explicit terminal handling.

This review does not demand that R1 alone finish every later lost-error slice. It does reject treating a discarded receipt or absent terminal event as health. The web request boundary can end its own request; the active-turn backstop observes another still-owned execution and therefore cannot use that rationale to swallow panic.

## Size, costs and evidence limits

Across the two production commits and three sibling test commits: source **+75/−563 (net −488)**; schema **+1/−84**; tests **+124/−941 (net −817)**. This is summed commit churn. Removed owners include the fault committer, channel/overflow machinery and fan-out joins. The reduction is well justified; no parallel recorder should return in the fix.

The adoption landing reports that schema retirement works under `d/with`, but also reports fourteen retired core schema rows surviving adoption. That is an explicit publication limit, not an installed pass. Current fault latency, slow/failing storage behavior, allocated memory and armed end-to-end delivery are unmeasured by this review. Root's acceptance evidence needs panic stop before a blocked writer, successful record-mode delivery, refused recording with its whole cause, and eventual real execution exit. The revised tests' positive occurrence counts alone do not prove those cases.

**REVISE**
