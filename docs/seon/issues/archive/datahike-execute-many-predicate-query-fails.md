---
type: issue
status: superseded
severity: friction
tags: [issue, database]
---

# Datahike execute-many predicate query fails

## Triage — 2026-07-23

REAL+INDEPENDENT (M), owned by maintained Datahike compiled-query execution.
`src/seon/db.cljc:657-713` still exposes `execute-many`; the note's predicate
failure is independent of the agent loop and child topology.

## Evidence

On 2026-07-18, the same query and immutable database value produced eight
expected rows through `seon.db/query` but failed as a member of
`seon.db/execute-many`:

```clojure
[:find ?e ?tx
 :in $ ?identity-attrs
 :where
 [?e ?identity-attr _]
 [(contains? ?identity-attrs ?identity-attr)]
 [?e _ _ ?tx]]

```

The member returned `:seon.db.protocol.error/database` with `Cannot read field
"sym" because "o" is null`. The scalar collection-binding alternative is not
valid: maintained Datahike's
`test-input-bound-uninstalled-attribute-does-not-crash-planner` explicitly
documents that attribute-position variables do not resolve keyword-to-ref
values from collection inputs.

Config reconciliation does not depend on the broken predicate path. Its three
managed identity attributes are submitted as separate indexed scalar queries
inside one `execute-many` request against one database value.

## Acceptance

- Reduce the failure to the maintained Datahike compiled-query or Seon
  `execute-many` owner.
- Add the regression at that owner.
- Make the predicate query return the same rows through `query` and
  `execute-many` without weakening query caching or changing database-value
  semantics.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
