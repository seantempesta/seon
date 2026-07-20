---
type: issue
status: resolved
severity: friction
tags: [issue, database, flow]
---

# Query-admission proof sampled before worker yield

## Problem

The joined-query admission test waited for Datahike to register its final
logical caller, then immediately sampled Seon's executor. Registration happens
inside the short acquisition job, so that job could still be physically
running for a few instructions. The test intermittently observed two read
workers even though the joiner then yielded and no duplicate query computation
ran.

## Resolution

The proof now waits for the physical invariant it claims: one running read job
and zero queued jobs. It then verifies that an unrelated database completes
while the shared owner remains blocked. This preserves the causal assertion
without treating logical caller registration as worker termination.

## Evidence

The database authority mesh focused coordinate, writer integration, and query
admission gate is the owning regression proof.
