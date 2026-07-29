---
type: issue
status: open
severity: friction
tags: [issue, schema, database]
---

# Name database-value and transaction-data contracts

## Problem

Public database transformations describe database values as anonymous `:any`
and repeatedly inline `[:vector :any]` for transaction data. These are known
dependency boundaries with predicates/shapes, not unproven polymorphism.

## Evidence

Seventy-four source contract occurrences begin with `[:cat :any ...]`; most
database-domain functions in `cluster/run.cljc`, `cluster/message.cljc`,
`cluster/work.cljc`, render, reconcile, and problems use it for a Datahike
database value. The schema maps repeat `[:seon.db/db :any]` in
`block.edn:208,233`, `walk.edn:73`, `web.edn:101`, and `prompt.edn:33`.

Datahike itself provides `datahike.db.utils/db?`
(`reference-code/datahike/src/datahike/db/utils.cljc:101-104`), including the
protocol test for all database-value variants. A JVM probe resolved that
predicate successfully.

Transaction-data output is independently inlined at
`reconcile.cljc:313,433`, `context.clj:296`, `error.clj:767-768`,
`render/agent.clj:506`, `render/root.clj:269`, and
`render/block.clj:1070-1072`, despite `src/seon/schema/store.edn:11` already
owning the transaction boundary.

Genuinely open values remain legitimate: SCI admission source/value, generic
render unit values, arbitrary error sources, and recursive data-browser
entries are proven polymorphic boundaries and are not part of this finding.

## Owner

The fresh schema EDN population: one named Datahike database-value predicate
schema and one named transaction-data schema, referenced by public contracts.

## Acceptance

Database-taking public functions reference the named database-value schema;
pure transaction producers reference the named transaction-data schema; the
predicate covers current/as-of/since/history Datahike values; and remaining
`:any` sites each document a genuinely open boundary.
