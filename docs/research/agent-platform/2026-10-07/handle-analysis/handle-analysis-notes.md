---
title: handle-analysis — a result handle is not a write of the form
type: landing
status: landed; default MCP after-numbers await root restart
date: 2026-10-07
---

# handle-analysis: a result handle is not a write of the form

Task: `seon.turn.batch/every-form-is-analyzed-because-its-result-handle-is-a-binding` (`65f8da37f31c`).
Loop step served: eval → record. The per-form derivation (`seon.turn.batch/derive-form`) ran clj-kondo for every
form, defining or not. Nothing is added; one redundant filter is deleted.

## Cause, confirmed at the REPL

- The turn's show stage (`seon.turn/show-row` → `seon.sci.eval/shown-result`) binds the value under its handle with
  `seon.sci.eval/bind-result!` while the write observer (`seon.sci.observe/observe`) is still bound.
- `sci.core/intern` binds `sci.ctx-store/*ctx*` to the agent's context (sci `core.cljc` `intern`, `store/with-ctx`),
  so the observer's `own?` test is true and the handle's intern is reported as a `:bind :root` write.
- `derive-form`'s `finals` keeps it, so `analyze-form` runs once per form. `seon.turn/entry-bindings` already dropped
  the `result` namespace by name, a second classification downstream of the same bad write.
- Live counts on default (c5a0d4dfe), MCP `(+ 1 1) (+ 2 2) (+ 3 3)` as agent `handle-analysis`:
  `seon.sci.arm/write-event` 3, `seon.sci.arm/arm!` 3, `seon.sci.eval/bind-result!` 6 (show and publish),
  `seon.turn.batch/analyze-form` 3.
- Replaying the fold with show-row in JVM mode gave writes `[result/ehandle0 result/ehandle1]` for two plain forms.

## Fix at the producer

`seon.sci.eval/bind-result!` interns under `(binding [sci.lang/*write-observer* nil] ...)`. This is the mechanism
`seon.sci.observe` already uses for arming's own root replacement. Publication's binding is not a write of the form,
so no consumer has to filter it.

- **EQUIVALENT** for defining forms: their own writes are unchanged, so they get the same `finals`, the same single
  analysis and the same rows.
- **BEHAVIOR CHANGE** for non-defining forms: they observe no write, get no analysis and no `:seon.batch/private`
  entry for the handle. A defn's form also loses its extra private `result/e…` entry.
- `seon.turn/entry-bindings` loses its `(not= "result" (namespace sym))` filter. With no handle write it is redundant.
  An agent's own deliberate `def` inside a namespace named `result` is now a binding like any other.
- Every handle bind goes through `bind-result!`: show, publish, `fork-for-turn` markers and the `seon.error` ctx key.

## Numbers

All runs use the same probe in a `bin/seon check` JVM: the turn path's fold with show-row, the observer, derive and
handles, settle removed. Rounds 2 and 3 are warm. Wall time is measured around one call; MB is
`getTotalThreadAllocatedBytes`, JVM-wide. The probe source is in the lane scratchpad and was not committed.

| probe | parent ms / MB / analyze-form | change ms / MB / analyze-form |
|---|---|---|
| 100 × `(+ 1 1)` | 1,658-1,781 / 1,433-1,474 / 100 | 189-214 / 164 / 0 |
| one contracted defn | 34-38 / 20 / 1 (first round 73 / 41) | 32-33 / 20 / 1 (first round 102 / 81) |

On live default, before the change (MCP SCI, 100 × `(+ 1 1)`, one call each):

- 1,639 ms / 2,410 MB and 1,717 ms / 2,485 MB;
- `analyze-form` 100 calls, 912 ms of `evaluate-batch` 1,124 ms.

The after-number on default needs root's restart (**RESTART NEEDED**, not a reset). Re-run the same `eval_clj`
probe after it.

Remaining: 164 MB and about 2 ms per trivial form are still over the 10 MB budget. That is the per-form stage cost
assigned to S3-S6 in the fold-owned notes, not this defect. This slice does not build S5 (one analysis per pass).

## Regression

`seon.sci.result-handle-test/only-a-defining-form-is-analyzed`, tagged with the task, uses the turn's show stage
with handles:

- three plain forms bind nothing and run 0 analyses, and each value is still bound under its handle;
- one contracted defn binds only `user/twice` and runs 1 analysis.

On the parent it failed: `{:analyzed 3, :bound [result/ehandle0 result/ehandle1 result/ehandle2]}`, and the defn bound
`[user/twice result/ehandle0]`. With the change it passes.

## Other tests, parent vs change (same set)

Test set: `seon.mcp-entrance-test`, `seon.cluster.evaluate-sources-test`, `seon.cluster.program-change-test` and
`seon.repl-grammar-test`.

- Parent run `e420f049d4fc`: 19 fails. Change run `059c32ba5493`: 17 fails, identical to the parent's minus the
  regression's two.
- Red on HEAD alone:
  - `seon.cluster.evaluate-sources-test/ordered-evaluation-retains-one-explicit-basis-without-publication` (existing
    task `seon.cluster.evaluate-sources-test/ordered-evaluation-is-red-on-head`, `6880c0f7c22b`);
  - `seon.repl-grammar-test/prompt-precedes-exact-multiline-agent-input` and
    `stored-help-keeps-its-renderer-and-bare-response` (new task `seon.repl-grammar-test/grammar-tests-red-on-head`,
    `892cda0fa38a`).

## Findings

- `seon.batch/a-show-failure-prints-its-value-as-unprintable` (`e05f76582956`, new). A show stage that threw printed
  the value 2 as `#<unprintable java.lang.Long>`. Seen while building the probe with a frame request that had no
  time limit.
- `seon.cache` discipline warning on `:seon.sci.eval/loaded-function-rows`, from runtime status at start. It holds
  23 MB at a 0% hit rate.
- Several over-bound warnings (`evaluate-batch` max 4.6 s) were open at start. They predate this lane, which made no
  separate measurement of them.

## Design check

- The design held. The bad value's producer is the handle's intern under the observer, and the fix uses the
  observer's own existing unbinding idiom. `derive-form` needs no change.
- Awkward: the task named `derive-form`'s `finals` as the place to filter. Filtering there would have been a third
  name-based classification beside `entry-bindings`, so the fix went to the producer instead and the existing filter
  was deleted.
