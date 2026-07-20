---
type: issue
status: open
severity: friction
tags: [issue, database]
---

# Datahike execute-many predicate query fails

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
