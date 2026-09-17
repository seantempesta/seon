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

---

# Addendum — the cold red is a pre-arming SCI copy (2026-09-17, batch 123 B)

The red returned cold at HEAD `312f60560` (`batch-123b.log`, six assertions).
This addendum records what it is, measured, and refutes the deadline
hypothesis this note filed above.

## The discriminating byte

Batch 123's failure carries `:seon.error/message`

```text
Wrong number of args (0) passed to: seon.db/as-of
```

with `:seon.error/kind :seon.sci.eval/evaluation-failed` and NO
`:seon.instrument/arity` or `:seon.instrument/arglists`. That sentence is
Clojure's own `ArityException` for a non-variadic function.
`seon.instrument/minimal-violation` imitates it deliberately, but its value
carries both of those keys — so this is not the fallback, it is the raw host
exception. The evaluation called an UNINSTRUMENTED `seon.db/as-of`.

## The mechanism

`sci/copy-var*` is `(new-var nm @clojure-var new-m)` — one deref, ever
(`reference-code/sci/src/sci/core.cljc:137`), and core admission installs
through it (`src/seon/sci/eval.clj:1248`, `:1266`). An SCI context is a
one-time snapshot of a root that `seon.instrument/apply!` later re-decides.

Probe, `bin/test-fast`, canonical fixture + real `fork-cluster-ctx` + armed
contracts:

```text
PROBE jvm-root-instrumented? true
PROBE bound-identical-to-jvm-root? true
PROBE bound-class clojure.lang.AFunction$1
PROBE program-row [:core "([time-point] [database time-point])"]
PROBE outcome :seon.instrument/contract-violated "seon.db/as-of refused …"
```

and, re-rooting the Var under an already-built context:

```text
PROBE bound-before-identical-to-then-root? true
PROBE bound-after-follows-new-root? false
PROBE bound-after-is-stale-copy? true
```

So a context acquired before arming, or inside an unarmed window left by
`preserving-instrumentation-state` / `seon.instrument/restore!`, calls the
original for the JVM's whole life. Deterministic in the cold worker, absent
in the fast loop — which is exactly the observed distribution (red in 119,
120 and 123; green in every fast run of this lane).

One cause covers the rest of batch 123's cluster too:
`seon.transact-feedback-test/bad-value-type` recorded a write that SUCCEEDED
where the projection should have refused it, and
`seon.sci.documentation-test/a-contract-mistake-carries-the-same-documentation-as-doc`
saw the system-side owner named instead of the agent-facing function.

## Reproduction boundary

Not reproducible in `bin/test-fast`: the arity test is green there in every
combination this lane ran — alone (73 tests), with `seon.instrument-test`
(102), with `seon.error-test seon.db-test seon.instrument-test
seon.program-test seon.sci.documentation-test seon.transact-feedback-test`
(235), and with `my.message-test seon.cluster.message-test seon.operator-test
seon.test.runner-test seon.test-runner-test seon.test.selection-test` (212).
Those runs did reproduce the OTHER members of the cluster (13 failures in
`seon.transact-feedback-test` and `seon.program-test`), which is what first
tied them together. The gate orders tests alphabetically round-robin across
three pools; the arity test was pool-3's fifteenth task.

A leaked arm was falsified directly: an un-stopped arm on the calling thread
makes the next evaluation answer `:seon.sci.kernel/already-armed`, never a
time-limit, because `seon.sci.kernel/arm` compares interpreters
(`src/seon/sci/kernel.clj:276`).

## What landed here, and what is owed elsewhere

The repair belongs in `src/seon/sci/eval.clj`, which the orchestrator's own
pre-arming acquisition work holds (`3170a0060`, `7e04a0bb1` — same root
cause, reached independently). This lane did not touch it.

What landed instead: `an-instrumented-multi-arity-miss-reads-like-clojure`
now asserts, BEFORE it evaluates, that the fork's binding is `identical?` to
the armed root. The cold gate then reports the cause by name instead of four
downstream assertions that read as a message drift.

Fast tally, clean HEAD worktree (`tmp/arity-wt`, removed after):
`bin/test-fast seon.sci.eval-test` → **73 tests, 385 assertions, 1 failure**,
the failure being `schema-and-contract-declarations-have-bounded-allocation`
(223 MB against a 64 MB limit), which fails identically without this change
and appears in no cold gate batch.
