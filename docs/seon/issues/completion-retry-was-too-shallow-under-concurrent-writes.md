---
type: issue
status: open
severity: blocker
tags: [issue, agent, database, flow]
---

# Retry completion through concurrent database writes

## Problem

`complete` retries a stale database value only once. Independent writes from
several agents can win both guarded attempts, returning an ordinary stale
database error to the agent even though its run still belongs to it.

## Evidence

- After three concurrent public agent launches succeeded on 2026-07-17, one
  agent's first successful evaluation of `(complete "concurrent 3 done")`
  returned a stale database error after both internal attempts.
- The agent needed a second model turn and a third completion attempt before
  closing the run.
- Its neighboring `message/user` call and the other two agents were committing
  independent transactions during the same interval.

## Owner

The bounded stale-database retry shared by guarded lifecycle operations in
`seon.agent.lifecycle`. Semantic run ownership remains protected by the
existing in-transaction compare-and-set operations.

## Acceptance

- A focused test proves completion reacquires through more than one unrelated
  concurrent write and closes exactly once.
- Three concurrent public agents can each message and complete in one turn.
- A genuinely lost run fence still returns its compare-and-set failure rather
  than being retried as an unrelated database-value conflict.
