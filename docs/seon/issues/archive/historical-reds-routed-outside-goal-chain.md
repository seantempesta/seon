---
type: issue
status: resolved
severity: blocker
tags: [issue, agents, database]
---

# Historical reds routed outside their goal chain

## Failure

`seon.problems/form-problem` routed every red evaluation that was not a resume
artifact. A triggerless or direct historical run could therefore assign its
old failure after a namespace owner appeared, even though no live planner goal
caused that run.

## Resolution

`seon.cluster.work/planner-scoped-attempt?` derives scope through the existing
transaction-metadata chain: the run's opening transaction must point at a
message in the goal chain. The depth-zero goal itself and later caused-by
messages qualify; a triggerless historical run does not. `form-problem` now
fails closed outside that relation.

## Evidence

- `historical-reds-are-outside-the-live-attempt-chain` plants an old
  triggerless run and proves its red form yields no problem or assignment.
- `arming-does-not-route-a-triggerless-historical-red` resumes that class
  through the real per-agent graph: the red receipt commits and assignment
  count remains zero.
- The existing routing and seven-state settlement tests run against a
  two-message goal chain.
