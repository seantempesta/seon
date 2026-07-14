---
type: issue
status: open
severity: cleanup
tags: [issue, database, architecture]
---

# Unify the two AsyncLocalStorage stores; rename with-tx-context → with-tx-meta

## Problem

The db layer runs TWO separate AsyncLocalStorage stores: the agent-id ALS and
the tx-context ALS (`internal.cljs:47` — kept distinct so non-DB code can read
the agent-id without the tx-context machinery). They carry overlapping
per-request context and are merged at transact time
(`merge-tx-context-into-opts`). That is more wiring than needed — the agent-id
is just one more tx-meta key. Unify into ONE request-scoped store, and rename
`with-tx-context` → `with-tx-meta` (the map IS the tx-meta), so there is one
mechanism for "context that rides onto a tx."

## Where

- `seon.db/internal` — `current-tx-context` / `run-with-tx-context` /
  `agent-id-als` / `merge-tx-context-into-opts` (~`internal.cljs:47,60,77,1010`).
- `seon.db` — `with-tx-context` / `current-tx-context` public face
  (~`db.cljs:311,340`).
- Callers: `seon.eval`, `seon.agent.turn`, `seon.client`.

## Acceptance Criteria

- One ALS store carries the request-scoped tx-meta (agent-id included).
- `with-tx-context` renamed to `with-tx-meta`; all callers updated.
- tx-meta still validated against the declared/registered set at the boundary
  (pair with the tx-meta-registration tightening — every tx-meta key registered,
  per the `:seon.store.wire/write-id` rename work).

## Related

- [[concepts/reactive-context]]
