---
type: issue
status: resolved
severity: blocker
created: 2026-09-17
tags: [issue, turn, wake, runtime]
---

# Resuming an issue opens a turn without waking its agent

The S7 review live proof called `seon.issue/start!` for issue `65f46c0efa7f`
with budget 4 after budget 2 exhausted. Default PID 53320 returned the same
agent `2393cac275ae` and 2 remaining turns, but turn `7d739af1dc0f` stayed
open at transaction 536871723 with no provider attempt or reply for several
minutes. The agent remained armed. `seon.cluster.wake/wake-matchers` returned
no matcher for `:seon.issue/budget`.

`start-tx` wrote the larger budget and opened a turn. Neither write addressed
the parked graph. The existing runtime listen-pattern mechanism can route a
budget datom on this issue to its assigned agent without a second transport
or a global process lookup. Install that pattern inside the resume transaction;
the listener compiles from `db-after` before dispatching the transaction's
budget datom. An already-open turn must be retained when its budget grows.

Acceptance: one `start!` call both raises the budget and causes the same live
worker to continue; subsequent status evaluations retain their issue origin.
The canonical issue settlement regression must verify the budget datom routes
to the assigned agent, and the existing runtime-listens regression exercises
actual callback delivery. No new fixture or runtime machinery is required.


Resolution (S7 review revision): `start-tx` writes the budget listen pattern
through the existing runtime entity, only if absent, and preserves an open
turn. The one live `start!` call with budget 5 woke the same default worker:
turn 7d739af1dc0f closed at transaction 536871739 and three additional provider
turns completed. Evaluations 80813, 80867, 80914 carry the issue origin and show
2, 1, 0 turns remaining. Exhaustion transaction 536871756 delivered one new
root message for budget 5. No manual wake or graph operation was used.

The canonical settlement regression now checks the committed budget datom
against the production matcher. Its final run/publication boundary is recorded
in [the S7 landing note](../../prds/steward-platform/research/issue-task-loop-s7-2026-09-17.md).
