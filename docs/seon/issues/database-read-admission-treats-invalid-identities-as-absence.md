---
type: issue
status: open
severity: friction
tags: [issue, database, schema, agent]
---

# Refuse invalid database read identities instead of returning absence

## Problem

`seon.db` does not admit query attributes or lookup-ref values against the
installed schema before reading. A misspelled query attribute returns an empty
relation, and a wrong-typed lookup-ref value returns nil. Both faces are
indistinguishable from valid absence, so a data agent can confidently conclude
that existing data is missing.

## Evidence

Scratch cluster `codex-repl-dogfood-0804`, MCP `eval_clj`, `door` mode, after
committing `:seon.test.run/id "dogfood-run-001"`:

```clojure
(seon.db/q '[:find ?e :where [?e :seon.test.run/idd _]])
;; => #{}

(seon.db/pull '[*] [:seon.test.run/id 'dogfood-run-001])
;; => nil
```

The installed declaration queried immediately beforehand states that
`:seon.test.run/id` is a string identity. The correct string lookup-ref returns
the full entity, proving both absence faces are caller mistakes rather than
missing data.

This is the fresh-tree recurrence of the failure class preserved in the
superseded quarry note
[[archive/read-side-attribute-admission-fails-open]]. Current read owners are
`src/seon/db.clj:540-592` (`q`), `:594-643` (`pull`), and `:691-711`
(`entity`).

## Owner

`seon.db` owns read admission using the database value's installed schema and
the already parsed query/pull representation. It must not add a text parser or
attribute hand list.

## Acceptance

- A query naming an uninstalled attribute returns one flat error naming that
  attribute and useful registered candidates.
- A lookup-ref value that does not satisfy its identity attribute's stored
  type returns one flat error naming the attribute, expected type, and supplied
  value.
- A correctly typed lookup-ref that has no entity still returns nil, and a
  valid attribute-presence query may still return an empty relation.

## N5 disposition — deferred 2026-08-12

`src/seon/db.clj` is protected by its live lane. After handoff, make `q` derive
the supplied attribute set from its already parsed query and compare it with
the database value's installed declaration projection before calling
Datahike. Make `pull` and `entity` validate a lookup ref's attribute and value
against that same projection. Construct every refusal with
`seon.error/diagnostic`: operation is the public Var, member is the attribute,
expected is the installed declaration (plus query-derived candidates for an
unknown attribute), offending is the supplied attribute/value, cause is the
validation result, and evidence is the projection query result. Preserve nil
only for a valid lookup ref with no entity. Add the three focused
`seon.db-test` cases in Acceptance.
