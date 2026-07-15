---
type: issue
status: open
severity: blocker
tags: [issue, agent, database, flow]
---

# Prove database workflow answers from retained query evidence

## Problem

The strengthened database-workflow scorer proves the requested schemas,
transaction contents, later query shape, and both reports. The composition door
intentionally omits eval result bodies, so native `.eval` evidence cannot yet
distinguish an answer computed from the real query result from the same answer
re-derived directly from the prompt.

## Owner

The pod composition door, immutable database coordinate, blob evidence, and
native Inspect scorer own one bounded proof. Do not restore arbitrary writer
REPL forms, expose answer keys to the pod, or copy unbounded eval results into
the response.

## Acceptance

- The scorer consumes a bounded, retained proof derived from the exact final
  database coordinate and request eval set.
- The proof establishes all five stored facts and the value returned by the
  later threshold query, with exact eval/turn identity and ordering.
- Large values remain blob-addressed and agent-visible diagnostics stay
  bounded; no stack, source dump, or general database backdoor is introduced.
- Offline good/bad fixtures and one admitted live sample prove a correct query
  result passes while prompt-only arithmetic, wrong stored facts, or a query
  result from another turn fails.
