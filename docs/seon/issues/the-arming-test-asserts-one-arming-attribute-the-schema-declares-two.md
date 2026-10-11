---
type: issue
status: open
severity: cleanup
created: 2026-09-26
tags: [issue, test, wake, schedule]
---

# The arming test asserts one arming attribute; the schema declares two

## Evidence (red pass on archive 65bf078ec, `tmp/orchestrator/verify-1210.out.d/018.txt`)

- `seon.cluster.agent-arming-test/an-agent-created-while-the-cluster-runs-is-started-by-its-own-creation`
  (`test/seon/cluster/agent_arming_test.clj:111`) asserts `(= #{:seon.agent/id} (wake/starting-attributes db))`.
  The actual value is `#{:seon.agent/id :seon.schedule.task/owner}`.
- `:seon.schedule.task/owner` declares `:seon.wake/starts true` (`resources/seon/schemas/seon.schedule.task.edn:2`).
  That landed in f6a463de6 (2026-09-20, "Defer idle agent graphs…"): a scheduled agent needs its armed timer proc
  even without a turn. `wake/starting-attributes` derives the set from the declarations, as designed. The assertion
  predates that declaration and was never updated.

## Owner and acceptance

The owner is the test: the declaration is deliberate, and the set is derived, not listed. Acceptance: the test
asserts what it means, that `:seon.agent/id` is an arming attribute (membership), or compares against the derived
declaration set. It must not pin a hand-written list of every arming attribute.
