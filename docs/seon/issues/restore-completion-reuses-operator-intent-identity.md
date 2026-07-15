---
type: issue
status: open
severity: blocker
tags: [issue, database, flow]
---

# Separate restore intent from completion identity

## Problem

The first completion implementation copied `:seon.dev.restore/intent-id` into
`:seon.db.restore/id`. That bypassed the sole generated-identity allocator and
made an operator file identity pretend to be a database entity identity.
Retry also looked up completion by that copied id, so the immutable plan itself
was not a durable natural key.

## Owner

`seon.db.restore` owns completion claims and facts. `seon.db.id/allocate!` is
the sole generated-identity allocator. `seon.dev.restore` and the retained
operator own intent UUIDs and plan derivation.

## Current state

The database owner now accepts a closed claim containing the unique plan
digest and payload but no generated id. It prequeries by plan digest, then uses
the existing allocator's dependent-identity transaction builder to commit one
current 12-character completion id, plan digest, payload, and provenance at the
exact expected predecessor. Exact retry, lost reply, and a concurrent winner
adopt only byte-equal facts and the original completion coordinate. Lifecycle
observation rejects any current completion whose facts no longer share its
single original transaction.

Legacy completions without a plan digest remain valid read/undo facts and are
never backfilled.

The issue remains open until every operator and cold-runtime caller uses the
new relationship: active intent is matched by plan digest, completion
coordinate maps remain keyed by generated completion id, and readiness carries
the exact returned completion plus coordinate.

## Acceptance

- New operator intent uses a UUID and is never copied into completion id.
- New completion claims contain plan digest and payload but no id.
- The sole allocator atomically commits a current compact id and all facts at
  the exact expected forced head.
- Exact/concurrent/lost-reply retries adopt by unique plan digest without a
  reservation transaction or second RPC.
- Every current completion fact retains one original transaction; later
  mutation or retraction fails lifecycle observation.
- Legacy rows remain read/undo-only and are not backfilled.
- Operator planning, abort, resume, undo, cold record, and web readiness callers
  consume the new plan-digest/generated-id split with focused and live proof.
