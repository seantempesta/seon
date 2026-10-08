---
type: research
status: landed
date: 2026-10-08
lane: led-stale2
---

# led-stale2: an inherited caller sees a same-pass redefinition

Ledger row LR-STALE2, task `seon.sci.eval/an-inherited-caller-sees-a-redefinition-only-after-the-pass` (5e72a9c98bf9,
copied into `resources/seon/tasks/seon.sci.eval.edn`). Root chose option 2. Base 7a9ceb44a.

## Broken things first

- Thirteen tests are red at HEAD and red with this change, with the same names: parent pass 551 / fail 13 / error 4;
  change pass 553 / fail 13 / error 4 (the extra test is the new one). Ten of them had no task, so they are filed as
  `seon.sci.eval/tests-reaching-turns-and-acquisition-are-red-at-head`. The other three are
  `seon.cluster.turn-test/reasoning-starvation-persists-usage-finish-and-the-named-error` (a seon.error task) and two
  `seon.sci.branch-execution-test` tests that have their own EDN tasks.
- HEAD answers a stale value across passes as well as within the redefining pass. The parent measurement below shows
  it. When another writer defined `c0..cN` calling `g`, the carried agent's later redefinitions `(defn g [] 10..14)`
  answered `[(cN) (g)]` = `[4 10] .. [4 14]`: the inherited caller stayed frozen at the fresh agents' last value. The
  change answers `[10 10] .. [14 14]`.
- A failure inside the write-time step reaches the reply only as the batch note `:seon.batch/arm-error`, a string. My
  first draft passed a vector where `seon.fn/reverse-closure` needs a set. The contract refused the call, and the
  test showed only the stale value. The error was visible only in the note. This is the existing carrier; I filed no
  task because it is delivered.
- The redefining pass costs more than the row's ~10 ms bound at 50 callers. See the measurements and
  `seon.sci.eval/a-redefinition-walks-the-reverse-closure-with-its-test-reach`.

## Cause

A fork's `defn` of a name the base holds copies the base Var into the fork's generation (SCI `eval-def`,
`reference-code/sci/src/sci/impl/evaluator.cljc`, generation check). It can also rebind the agent's own Var, which
settlement already transferred under another base Var. Either way, the base's callers resolved the base's Var at
analysis and keep calling it until settlement.

## Change (BEHAVIOR CHANGE, with its regression)

- `seon.sci.eval/reinterpret-inherited-callers!` runs on a function write. When the fork's Var differs from the
  base's Var at that path (`::base-ctx`), it reads the transitive callers (`seon.fn/reverse-closure` on the batch's
  working value). It keeps the agent-authored ones that the fork holds as the base's Var, or as its own Var still
  holding the base's root. It copies them all into the fork's generation first, the same step `install-selected!`
  takes, and then re-interprets each one with `install-function-from-database!`. The observers are unbound, so this
  step is not the form's program change.
- `seon.sci.arm/arm!`, the step at the write, calls it after arming. A failure is the write's `:seon.batch/arm-error`,
  and the first error is kept.
- What it does not touch: the agent's own definitions from this pass whose roots differ from the base (unsettled
  edits) are skipped. Compiled and first-party callers are excluded by the agent-authored filter, so a branch never
  changes the platform.

## Proof

- New `seon.repl-differential-test/an-inherited-caller-sees-a-same-pass-redefinition`, through `seon.mcp/call`:
  - another writer's `h7 -> g7` and `k7 -> h7`, then the agent's `(defn g7 [] 2) [(k7) (h7) (g7)]`: HEAD answers
    `[1 1 2]`, the change `[2 2 2]`;
  - a fresh agent's first pass `(defn g7 [] 3) ...`: HEAD answers `[1 1 3]`, the change `[3 3 3]`.
- `seon.repl-differential-test` is green: 4 tests, 15 assertions.
- Broad reaching set, run with `--include-long` on both parent and change, has the same reds:
  `seon.repl-differential-test`, `seon.repl-differential-eval-test`, `seon.cluster.program-change-test`,
  `seon.sci.fork-isolation-test`, `seon.sci.branch-execution-test`, `seon.sci.lazy-acquisition-test`,
  `seon.sci.program-change-test`, `seon.sci.eval-instrumentation-test`, `seon.cluster.turn-test`.

## Measurements

Script: `stale2-measure.clj` (copy to `test/`). A check JVM runs MCP SCI passes. For each n, it takes the median of
five redefining passes of `g`, where n callers were defined by another writer. "Fresh" is a new agent each pass, so
every caller is re-interpreted. "Carried" is the fixture agent's later passes. The reinterpret and closure times are
the armed profile's mean per call.

| n | parent fresh ms / MB | change fresh ms / MB | parent carried ms / MB | change carried ms / MB | reinterpret µs fresh / carried | reverse-closure µs |
|---|---|---|---|---|---|---|
| 0 | 138.5 / 140.1 | 123.7-137.4 / 145-148 | 95.3 / 73.3 | 77.5-87.8 / 77-79 | 1,315 / 1,383 | 1,365-1,504 |
| 1 | 120.3 / 146.4 | 117.1-126.5 / 146-153 | 79.6 / 75.2 | 74.2-76.4 / 82-85 | 2,089 / 1,868 | 1,502-1,684 |
| 10 | 116.7 / 159.4 | 118.7-130.7 / 170-177 | 72.6 / 81.3 | 73.0-77.0 / 88-91 | 7,095 / 3,680 | 3,076-3,885 |
| 50 | 164.8 / 223.9 | 153.0-181.4 / 257-260 | 76.8 / 96.7 | 84.1-89.8 / 120-121 | 17,084 / 11,107 | 9,697-10,811 |

- Pass wall time stays within the run-to-run spread. The direct cost is the profiled re-interpretation: 1.3-2.1 ms at
  0-1 callers, 3.7-7.1 ms at 10, and 11-17 ms at 50.
- About 85% of the carried cost is `seon.fn/reverse-closure`. It also computes call-reach tests, which this step
  never reads. Re-interpretation itself costs about 0.14 ms per caller.
- Allocation grows with callers: +10 MB at 10 fresh callers, and +33 MB fresh / +23 MB carried at 50.
- Boundary: under 10 ms up to about 10-20 callers; over it at 50. Filed
  `seon.sci.eval/a-redefinition-walks-the-reverse-closure-with-its-test-reach`.

## Design check

- The design held. The write is the right moment, because arming already acts there on the exact Var, with the write
  observer unbound.
- The existing owners covered the rest: closure (`seon.fn/reverse-closure`), copy-then-interpret
  (`install-selected!`'s order) and interpretation (`install-function-from-database!`).
- What I had to add was the expansion observer unbinding. Without it, the callers' `defn` macro expansions would count
  as this form's expansions.
- Awkward 1: the base is reached through the fork's `::base-ctx`, which `fork-for-turn` sets only for a custody base.
  Without one, nothing happens.
- Awkward 2: a carried copy keeps a re-interpreted root. If another writer later changes that caller's row,
  `regenerate-agent-context!` adopts the base's root, which closes over base Vars. The next redefinition by the agent
  re-interprets the caller again. Between those two events, the caller calls the base's callee, whose root settlement
  keeps current.
- PB-02b ("fork once, advance in place") would make both points moot.

Verification limits: no restart of default and no live default probe; all evidence comes from throwaway check JVMs.
Default needs a restart to run this code (root's batched restart).
