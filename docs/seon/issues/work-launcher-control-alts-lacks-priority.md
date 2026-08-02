---
type: issue
status: open
severity: friction
tags: [issue, flow, lifecycle, architecture]
---

# Give the work launcher's control read Flow's required priority

## Problem

`seon.flow/work-launcher-proc` is a hand-written `spi/ProcLauncher` that
re-implements Flow's control protocol inline. Its `alts!!` over
`[control completion submission]` omits `:priority true`.

`core.async.flow`'s SPI states the requirement plainly: "Whenever it is reading
or writing to any channel a process must use `alts!!` and include a read of the
`::flow/control` channel, giving it priority." Without priority, `alts!!`
chooses randomly among ready channels, so under sustained submission pressure a
`::flow/pause` or `::flow/stop` can be starved behind queued work — the exact
failure the priority rule exists to prevent. This is the one proc in the system
that can be saturated by design, which makes it the worst place to drop the
rule.

The same launcher also never invokes a `transition`, so `::flow/stop` simply
returns nil with no orderly-stop completion and no compute accounting cleanup,
while every other Seon proc publishes a completion from its `::flow/stop`
transition.

## Evidence

- `src/seon/flow.clj:314-319` — `(async/alts!! channels)` with no `:priority`.
- `src/seon/flow.clj:331-357` — the hand-rolled command handling.
- `src/seon/flow.clj:339-340` — `::flow/stop` returns nil, no transition.
- `reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj:32-34`
  — the priority requirement.
- `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:295`
  and `:232-234` — the stock proc supplies `:priority true` at both its read
  and its write.
- Protocol grounding and the full verb table:
  `docs/prds/sci-execution-runtime/research/flow-control-protocol-2026-07-31.md`.

## Owner

`src/seon/flow.clj` (the work launcher).

## Acceptance criteria

The preferred fix is to stop hand-rolling the launcher at all: Flow already
supports its two requirements. `::flow/in-ports` carries the completion channel
into an ordinary proc's input set, and `::flow/input-filter` drops
`::compute-submission` from the read set while at capacity — which is exactly
what the conditional `cond->` on the channel vector is doing by hand. Rebuilt
as a `var-process`, the proc inherits control priority, transitions,
hot-reloadable step logic, and the standard ping shape for free, and the
duplicate command handling is deleted in the same change.

The minimal fix, if the rebuild is deferred, is `:priority true` at
`src/seon/flow.clj:319` plus a `::flow/stop` completion.

Proof: a regression that saturates the submission channel to its configured
queue depth and shows `flow/stop` taking effect without draining the queue.

## Implementation evidence — 2026-08-02, pending orchestrator review

This issue and
[[work-submission-can-block-before-its-time-limit]] have different immediate
causes: this issue is a hand-written control loop that violates Flow's SPI;
the blocker is a blocking full-buffer admission. One simplification serves
both.

`work-launcher-proc` is now a `var-process` over `#'work-launcher-step`
(`src/seon/flow.clj:324-379`). Its completion channel enters through
`::flow/in-ports`, and `::flow/input-filter` removes compute submissions while
all compute slots are occupied. The custom `alts!!`, ping construction,
command dispatch, and status loop are deleted. The ordinary Flow proc now
supplies `:priority true` at its read selection and calls the step transition
on stop from the pinned dependency
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:243-323`).
The stop transition interrupts the launcher's owned task executor.

`launcher-stop-precedes-ready-submissions`
(`test/seon/flow_test.clj:520-577`) acknowledges pause, fills the configured
queue depth, orders resume then stop on Flow's control channel, observes the
standard step's stop transition, and proves the queued submission remains
queued and unrealized. The proof waits on the transition event with only the
shared loud backstop; it has no sleep or wall-duration assertion.

Focused gate: `bin/test seon.flow-test` ran 21 tests containing 126 assertions
with zero failures and zero errors. The implementation evidence functionally
closes this issue together with the bounded-submission blocker; its status
remains open for orchestrator review as requested.
