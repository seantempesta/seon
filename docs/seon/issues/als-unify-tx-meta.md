---
type: issue
status: open
severity: cleanup
tags: [issue, database, architecture]
---

# Unify agent and operation AsyncLocalStorage

## Triage — 2026-07-23

REAL+INDEPENDENT (M), owned by `seon.db.fiber`. Current
`src/seon/db/fiber.cljs:8-18` still creates separate transaction, agent, and
read-evidence stores, and lines 50-64 still expose separate transaction and
agent scopes. The loop-migration slice does not subsume this carrier cleanup.

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

## Corrected owner ruling (2026-07-20)

[[../../prds/source-cleanup/research/als-tx-meta-unification-boundary-2026-07-20]]
(`5c140e9a`) confirms the carrier duplication but rejects the literal
`with-tx-meta` rename. The current ambient map also carries a pinned database
value, full configuration, eval namespace, turn, branch head, commit callback,
and test state. Datahike persists every transaction-metadata entry, so naming
or treating that entire process-local map as tx metadata would erase a safety
boundary.

The implementation instead collapses `tx-context` and `agent-context` into one
closed operation-context ALS, keeps the invocation-local read-evidence ALS
separate, and derives only registered transaction metadata at submission.
`without-agent` removes identity while preserving other operation facts. The
source cut follows Stage-4 full-config propagation and a mandatory post-U4
inventory, then atomically deletes all old carrier symbols and proves async
isolation plus exact persisted provenance.
