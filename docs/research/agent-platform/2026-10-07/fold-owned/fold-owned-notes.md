---
title: fold-owned — the fold validates each entry once; a thrown MCP call keeps its request whole
type: landing
status: landed; default MCP after-numbers await root restart
date: 2026-10-07
---

# fold-owned: the fold validates each entry once; a thrown MCP call keeps its request whole

Lane fold-owned, 2026-10-07. Tasks `seon.batch/each-fold-step-validates-every-entry-so-far` (08034d6f4d2b) and
`seon.mcp/a-thrown-call-clips-its-request-text` (c187d12d1e3c).

## Which step of the loop it serves

The one REPL path's fold (read -> eval -> record -> print). The change makes the fold's validation cost proportional to
what each step changes, not to the forms so far. No new mechanism: the step contracts name a smaller schema, and the
entry is validated by the function that places it. Deleted: the per-step re-walk of every entry and write.

## Cause 1: every fold step validated every entry so far

`seon.batch/evaluate-batch` threads the batch through armed private steps (`form-step`, `guard`, `read-step`,
`apply-pending`, `evaluate-step`, `place-step`, `publish-step`, `freeze-step`, `derive-step`, `clock`, `last-entry`),
each contracted `:seon.batch/batch` in and out. `:seon.batch/batch` declares `[:vector :seon.batch/entry]`, and each
entry carries `:seon.batch/owned` (an `AtomicLong`, `[:fn seon.batch/owned?]`). About 53 whole-batch validations
per form, so a pass of n forms validated about 53·n²/2 entries. `seon.batch/owned?` is the visible marker: 7.46 M
calls since default booted, top of health.

Two more of the same class outside the fold, in `seon.turn`:

- `seon.turn/publish-row` (the turn's publish stage, once per form) took `:seon.batch/batch`, a whole-batch
  validation per form.
- `seon.turn/entry-bindings` was called two to four times per entry, each call validating the whole batch and walking
  every write.

## Fix

- New schema `:seon.batch/folding` (`resources/seon/schemas/seon.batch.edn`): the batch while the fold builds it,
  `entries`, `writes` and `diagnostics` are `vector?` and `timings` stays declared. Every in-fold step contract
  names it, as does `seon.turn/publish-row`.
- `seon.batch/with-entry` (`[:cat :seon.batch/folding :seon.batch/entry]`) is the one place an entry enters or
  replaces the last one. Publication (`evaluate-step`) and `last-entry` (place, publish, freeze, derive) go through
  it, so each version of the new entry is validated once and nothing before it is validated again.
- `seon.batch/with-writes` validates a form's observed writes once as they join.
- `apply-pending`'s mark used to be a `mapv` over all entries per step. It is now `last-entry`, guarded: the pending
  change is always the last published entry's, because derive leaves it and the next read applies it before another
  entry is published. A violation throws inside the guard and becomes a diagnostic.
- `evaluate-batch`'s output is still `:seon.batch/batch`, so the whole batch is validated once per pass (linear).
  `settled-into` is unchanged.
- `seon.turn/entry-bindings` now answers ordinal -> bindings from one walk of the writes and one validation. The turn
  reads `(get bound ordinal)`. EQUIVALENT: the same `:bind`/`:root`/non-`result` filter, and later writes still
  overwrite earlier ones (`into {}` became `assoc-in`).

## Cause 2 and fix: the 200-character clip in `seon.mcp/call`

`call`'s catch used `(str/trim (subs request 0 (min 200 (count request))))` as the shown source. That is a second
clip outside the value printer. The new `seon.mcp/call-failure` builds the error value (the installed
`refusal/diagnostic`) carrying `:seon.mcp/request`, the whole request, as data. The value printer bounds it. The
render source is now the whole request, as in every other branch of `call`. `clojure.string` is no longer required.

## Numbers

### Before: the parent on default

Measured at MCP `eval_clj` in SCI mode, `(+ 1 1)` per form, with `seon.profile` deltas. The figures cover the whole
MCP call, settlement included, and the allocation is the JVM total:

| forms | ms | MB | owned? calls |
|---|---|---|---|
| 1 | 133 | 146 | 55 |
| 10 | 387 | 298 | 2,935 |
| 40 | 1,185 | 901 | 43,540 |
| 100 | 2,977 | 2,511 | 267,850 |

### Parent and change on the same probe

Check JVM, `bin/seon check`, warm second round, current-thread allocation. The probe was a disposable deftest that
reported through its failing assertion and was not committed; its copy is in the lane scratchpad.

Fold with stage values (`run-with` shape, one symbol per form):

| forms | parent ms / MB / owned? | change ms / MB / owned? |
|---|---|---|
| 1 | 8 / 4 / 33 | 9 / 4 / 5 |
| 10 | 12 / 8 / 1,860 | 14 / 7 / 50 |
| 40 | 33 / 22 / 27,840 | 25 / 18 / 200 |
| 100 | 120 / 66 / 171,600 | 42 / 38 / 500 |
| 200 | 378 / 182 / 683,200 | 68 / 73 / 1,000 |

The one-path fold with the turn's evaluate and show stages (`support/evaluate`, `(+ 1 1)` per form):

| forms | parent ms / MB / owned? | change ms / MB / owned? |
|---|---|---|
| 1 | 9 / 6 / 38 | 11 / 6 / 10 |
| 10 | 20 / 20 / 1,910 | 25 / 20 / 100 |
| 40 | 70 / 72 / 28,040 | 75 / 67 / 400 |
| 100 | 209 / 190 / 172,100 | 157-169 / 161 / 1,000 |

Validation is now linear: 5 owned? calls per form in the fold and 10 per form on the turn path. After the change, the
turn path still costs about 1.6 MB and 1.6 ms per `(+ 1 1)` form. That cost is linear and lies outside the fold's
validation. It is the per-form stage cost the pass-alloc lane assigned to S3-S6 (`ba3b5de0b`).

The default MCP after-numbers need root's restart (RESTART NEEDED, not a reset). Re-run the same `eval_clj` probe
afterwards.

## Regressions

- `seon.run-test/each-form-adds-the-same-validation-whatever-came-before` (tagged with the task). It counts
  `seon.batch/owned?` calls for 10, 20 and 30 forms and asserts that the second difference is zero. On the parent it
  failed with `(not (= 8670 5270))`. With the change it passes.
- `seon.mcp-entrance-test/a-thrown-call-carries-its-whole-request` (tagged) asserts that `:seon.mcp/request` is the
  whole request of about 4,000 characters, and checks the operation.

## Design check

The design held against the real code. Three points were awkward:

- `last-entry`'s function-typed argument is only `ifn?`-checked by instrumentation, because Seon sets no function
  checker. Validation therefore has to happen at `with-entry`'s entry argument, not at `f`'s declared output.
- Diagnostics in the fold are `vector?`, so a hand-built diagnostic is validated only at `evaluate-batch`'s output.
  Most diagnostics come from the contracted `seon.batch/diagnostic`, and the final output still validates all of
  them once.
- The task's intent proposed per-form steps that take only the form's own state. That is a larger restructure. This
  change gets the same complexity with smaller edits, and that restructure is still open if wanted.

## Findings

- Reds at HEAD `6bfdebed8`, proven red on the parent by `bin/seon check` and not caused by this slice:
  - `seon.run-test/d8-a-test-obtains-a-forms-declaration-row-from-the-entrys-change`: task
    `seon.run-test/d8-code-request-derives-without-observing` (62b75907ab25).
  - `seon.turn-test/a-definition-changed-since-the-run-read-it-is-refused-at-the-writer`: the fixture write is
    refused with `Nothing found for entity id [:seon.ns/name user]`. Task
    `seon.turn-test/a-definition-changed-since-the-run-read-it-fixture-names-an-absent-user-namespace`
    (ec9e966a44a9).
  - `seon.turn-test/a-form-whose-facts-are-refused-says-it-was-not-admitted`: a hand-built refusal fails
    `not-admitted`'s input contract. Task `seon.turn-test/not-admitted-test-builds-a-refusal-its-contract-refuses`
    (c1c8c0a34adc).
  - `seon.turn-test/transitions-agree-with-the-model`: 7.5 s against its 5 s bound. Existing issue
    `docs/seon/issues/turn-test-property-members-pay-a-file-store-commit-per-step.md`.
- Default's health is `:faulted`, with 1 open fault and 7 errored receipts. Status at 19:42.
- `seon.test-support/evaluate` returned nil, which its own `:map` output contract refused, when called with
  `:seon.sci.eval/ctx`, `:seon.sci.admit/caps` and the source `(+ 1 1) (+ 1 1)` but without
  `:seon.sci.eval/time-limit-ms`. With the time limit it answered. This was not reduced further and has no task yet.
  Its owner is test support.
- `my.task/open` lists only tasks with faults or signals, so tasks created by lanes do not appear in it. This is by
  its docstring, not a defect, but it misleads a lane that searches it for tasks.
- A JVM-mode probe interned `user/owned-calls` and `user/d` into default's JVM. They are disposable Vars and go away
  on restart.
