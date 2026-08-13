---
type: issue
status: open
severity: blocker
tags: [issue, sci, eval, agent]
---

# `acquire!` has no per-row containment — one bad row bricks a branch

Live-proven in the 2026-08-01 grader-mechanics grounding
(`research/grader-mechanics-grounding-2026-08-01.md`, probe scripts
`tmp/grader-probe-*.clj`): when an agent-authored program row fails to
install — the falsified case is a rewrite whose source uses its
namespace's aliases, which throws during interpreted install — the
exception escapes `acquire!` (`src/seon/sci/eval.clj:749-890`)
UNCAUGHT, and because `acquire!` runs at the start of every run, EVERY
subsequent evaluation on that branch fails. A single poisoned row
permanently kills all agent evaluation on the branch; recovery is
`refork!` (destructive).

This violates two standing rules at once: nothing throws into the
agent loop (an agent mistake must become a flat `:seon.error` value),
and an agent's mistake must never wedge the system. It is also the
number-one correctness prerequisite for the grader/overseer plan
(generation zero blocks on it) — but it bites ORDINARY agents today:
any agent whose persisted defn trips the alias edge bricks itself.

Fix at the one owner: per-row containment inside `acquire!` — a row
that fails to install becomes a recorded flat error (visible to the
agent as a problems-family fact naming the row), the remaining rows
install, and evaluation continues without the poisoned definition.
The alias-resolution edge itself (interpreted install must resolve the
namespace's aliases the way the door's reader already does) is part of
the same fix.

Acceptance: transact a deliberately-failing program row on a scratch
branch; the next run's `acquire!` succeeds, the agent sees a flat
error naming the row, other corpus functions still evaluate; a
regression covers the class. Related open ruling (owner, from the
same research): whether rewriting CORE-provenanced namespaces is
permitted at all, and the irreversibility of the `:agent` provenance
flip.

## Triage 2026-08-02

**Still real; destination: per-cluster live-graph wave, slice 1B.** At current
HEAD, `src/seon/sci/eval.clj:1075-1087` calls
`install-program-row!` without containment, and the namespace-ordered reduces
at lines 1093-1125 propagate that failure out of `acquire!`. Tonight's
contract, session-image, and settings landings changed what a successful row
installs; none added a per-row error value or allowed the remaining rows to
continue. `plan/refactor-wave-2026-08-01.md` slice 1B still names the poisoned
row plus subsequent real turn as its live exit.

## Resolution evidence 2026-08-13

Resolved in `5b4d98c57` (`Contain cold acquisition failures per program row`).
`acquire!` now contains each `install-row!` failure, turns it into the typed
flat `:seon.sci.eval/acquisition-refused` value naming the program-row
identity and original cause, continues installing later rows, and commits the
refusal through `seon.error/commit-tx`. The acquired result also returns its
contained refusal values and whether durable recording succeeded, so the
context and database fact report the same outcome.

The isolated-root falsifier used cluster `acquire-containment` under
`tmp/acquire-containment-root`. Before the fix, a committed
`acquire.poison/bad` function row with no durable root descriptor made
acquisition throw `:seon.sci.eval/unrestorable-function-root`; the later good
row was never reached and no acquisition-refusal fact existed. After hot
reloading the fix against the same database facts, cold acquisition succeeded,
`(acquire.poison/good 41)` returned `42`, and exactly one durable
`:seon.sci.eval/acquisition-refused` fact was queryable. Its message names
`[:seon.fn/sym "acquire.poison/bad"]` and says that the selected function has
no durable root descriptor.

`one-unloadable-row-cannot-prevent-cold-acquisition` is the single class
regression in `test/seon/sci/eval_test.clj`. It passed during the focused
`bin/test seon.sci.eval-test` run. The namespace-level focused gate remains red
at two unrelated in-flight boundaries: the expected bare `my.message` surface
does not yet include the newly present `decline/inbox` and `decline/read`, and
schema publication refuses `:my.background/invalid-result-error` because its
declared `:seon.render/ai` function currently has no accepting input contract.
The retained run root is `tmp/test-runs/run.Lwi8E7`; no broad gate was run.
