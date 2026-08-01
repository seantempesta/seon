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
