# The SCI arity refusal sentence had two composers — 2026-09-17

Lane: `sci-arity-message-parity`. Subject:
`seon.sci.eval-test/an-instrumented-multi-arity-miss-reads-like-clojure`,
four failing assertions in `tmp/orchestrator/gate-results/batch-119.log` and
`batch-120.log`.

## What the gate red actually is — NOT a message drift

The test asserts that an instrumented multi-arity function called with the
wrong arity produces the rich refusal (`:seon.instrument/contract-violated`,
the offending count, the declared arglists in the shown text). **That
assertion is correct and the code satisfies it.** Neither gate failure was a
message-shape diff:

| batch | what the test saw | what it means |
|---|---|---|
| 120 | `:seon.sci.eval/evaluation-failed`, shown text = `The renderer seon.error/render-ai did not return: time-limit.`, `:refusal :seon.sci.kernel/time-limit` | the evaluation deadline latched |
| 119 | shown text = `:evaluation refused :throwable at []: … Fix: Wrong number of args (0) passed to: seon.db/as-of` | that sentence is `seon.instrument`'s `minimal-violation` fallback, reached only from `violation`'s `(catch Throwable …)` — the same deadline interrupt, caught and re-reported as a contract violation |

One cause, two shapes: the 2000 ms evaluation deadline latching around a path
that does not need it.

### Measured cost of that path

Probe in a candidate SCI context (canonical `test-support/with-database`
fixture, real `test-support/fork-cluster-ctx`, contracts armed by
`bin/test-fast`), 20 iterations each:

- JVM-direct instrumented arity refusal (no SCI, no render): **0–2 ms**
- the whole SCI evaluation the test performs, including admission and the
  value render: **98 ms first, then 8–12 ms**
- `seon.error/render-ai` on the refusal value alone: **<1 ms**
- the evaluation's own record: `:seon.eval/fn-entries 0`,
  `:seon.eval/duration-ms 5`

The bound is 2000 ms. The work is 10 ms — a 200× margin, so the red is not
this path being slow and is not a constant to widen. The test is green in
`bin/test-fast` at every run of this lane (52–62 ms). Filed separately:
[the evaluation deadline latches around a 10 ms refusal under gate load](../../../seon/issues/the-evaluation-deadline-latches-around-a-ten-millisecond-refusal.md).

## The real defect this found: two composers, one sentence

The refusal sentence
`OP refused ARG at PATH: expected DESC (VALUE), got DESC2 VALUE2. Fix: FIX`
was composed in **three** places: `seon.error/refusal-text` (the render pair,
which prints both values under the profile), `seon.instrument/violation`'s
flat `:seon.error/message` (which printed neither), and
`seon.db`'s `::invalid-write` message (likewise).

`:seon.error/actual-description` is PROSE by contract — `value-description`
never prints a value, because the composer prints it next. `3e41a5d22`
repaired the flat message's dangling `got an argument count of.` by putting
the count INTO the description, which is the only composer that does not
print the value. Every rendered arity refusal has since read:

```text
seon.db/as-of refused argument count at []: expected the declared arglists
(([time-point] [database time-point])), got an argument count of 0 0. Fix:
Call one of the declared arglists. Example: No docstring example is available.
```

Measured verbatim in the probe above. UGLY OUTPUT IS A DEFECT.

## The repair

`seon.error/problem-sentence` is now the ONE composer; `seon.error/scalar-text`
prints an offending value whose printed form is bounded by what the value IS
(number, keyword, symbol, boolean) and answers nil otherwise, which is how a
flat message with no render profile can carry a value at all. All three call
sites use them; `:seon.error/actual-description` for arity is prose again.

Exact bytes, same probe, before → after:

| | before | after |
|---|---|---|
| shown | `… got an argument count of 0 0. Fix: …` | `… got an argument count of 0. Fix: …` |
| `:seon.error/message` | `… got an argument count of 0. Fix: …` (`3e41a5d22`); `… got an argument count of. Fix: …` before it | `… got an argument count of 0. Fix: …` |

After, verbatim:

```text
seon.db/as-of refused argument count at []: expected the declared arglists
(([time-point] [database time-point])), got an argument count of 0. Fix: Call
one of the declared arglists. Example: No docstring example is available.
```

```text
seon.db/as-of refused argument count at []: expected the declared arglists,
got an argument count of 0. Fix: Call one of the declared arglists.
```

`seon.db/transact!`'s message gains the same scalar when it has one; a missing
key's offending value is the parent map, so those messages are unchanged
(`seon.db-test:1085`, `seon.transact-feedback-test:69` assert only the prefix).

The test now asserts the wanted shape: the count appears once in both
sentences and `an argument count of 0 0` never appears.

## Fast tally (lane iteration, NOT the cold gate)

`bin/test-fast --paths src/seon/error.clj src/seon/instrument.clj
src/seon/db.clj test/seon/sci/eval_test.clj -- seon.sci.eval-test
seon.instrument-test`

**102 tests, 527 assertions, 1 failure, 0 errors.** The subject test passes.
The one failure is
`seon.sci.eval-test/schema-and-contract-declarations-have-bounded-allocation`
(`223605840` bytes against a `67108864` limit), which failed identically on
this lane's FIRST run before any edit (`223466344`) and does not appear in
either cold gate batch — a fast-loop-only red, not this change.

Cold proof still owed to the orchestrator: `bin/test --paths src/seon/error.clj
src/seon/instrument.clj src/seon/db.clj test/seon/sci/eval_test.clj --
seon.sci.eval-test seon.instrument-test seon.db-test` plus `--platform`.

## Boundaries met, not crossed

- `bin/test --prepare-head-base` refused twice before any of this ran: once
  on a transient Maven `HashMap$Node/TreeNode` cast under concurrent
  resolution, then with `Could not locate edamame/core` from
  `seon.test.selection`. That is a second instance of the resolved
  [dev-cache tool classpath](../../../seon/issues/dev-cache-tool-classpath-omits-the-selectors-dependencies.md)
  class: `6f80d1a4d` gave the selector an `edamame.core` require and the
  `:dev-cache` alias does not carry it. A neighbouring lane held
  `src/seon/test/selection.clj` and `bin/test` uncommitted while this lane
  ran, so this lane did not touch either; every cold gate is refused until
  that lands. Iteration here used the working tree instead.
- The canonical fixture base refuses construction when static analysis finds
  blocking errors anywhere in the tree — a literal `(db/as-of)` in a probe is
  one. The probe called through `(apply (var-get #'db/as-of) [])`.
