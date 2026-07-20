---
type: issue
status: resolved
severity: cleanup
tags: [issue, pod, database]
---

# record-error warning check reads a dead attribute

## Problem

`seon.warn/check-record-errors` and the warnings context block
(`src/seon/agent/ctx/warnings.cljs`) queried `:seon.eval/record-error`, but
nothing wrote that attribute anymore. Commit `346e70fa` ("Keep eval
publication atomic", 2026-07-17) deliberately deleted `record-eval!`'s
two-stage fallback that stamped it, along with its `schema/register!`. The
check could never fire; its docstring ("Stamped … by seon.eval/record-eval!")
was false. A comment in `src/seon/repair.cljc:102` also cited the removed
precedent.

## Resolution — commit `0887b1ea` (2026-07-20)

`check-record-errors`, its registry entry, and the ctx/warnings
`:seon.eval/record-error` query member are DELETED (deleted rather than
redesigned): the partial-record class died with atomic eval publication —
a dropped eval record now persists NOTHING transcript-shaped, and
`record-eval!`'s tx-failure branch records the drop through the one fault
path (`seon.error/record!`, fault `:core`, commit `b109266e`). Those fault
datoms already have their one derived surface,
`seon.agent.ctx.warnings/core-faults-block`, which is root-only by the
2026-07-04 error-blame ruling (core faults are ours to fix, never an
agent's task) — a second warn-registry surface over the same fault datoms
would duplicate that mechanism and contradict the ruling, so none was
added. A NOTE comment at the former registry position records this
decision in source.

The stale `repair.cljc` comment is updated in the same commit.
`rg ':seon.eval/record-error' src/ test/` returns zero readers. Full
`bin/test-cljs` green (1290 tests / 5881 assertions / 0 failures).
