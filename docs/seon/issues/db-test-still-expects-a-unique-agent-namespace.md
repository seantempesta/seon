---
type: issue
status: open
severity: friction
tags: [issue, test, database, schema]
---

# `seon.db-test` still expects a unique agent namespace

## Problem

`seon.db-test/unique-rejection-names-the-existing-owner-as-data`
(`test/seon/db_test.clj:841-846`) transacts a second agent with an
already-assigned `:seon.cluster.agent/namespace` and asserts the write is
refused: `:seon.error/kind :seon.db/rejected`,
`:seon.db/transaction-refused true`, and a conflict map naming
`{:error :transact/unique, :attribute :seon.cluster.agent/namespace}` with a
`datahike.datom.Datom`.

That constraint is gone. Commit `daf551e6c` ("Make stewardship a namespace
fact and un-unique the agent's namespace") removed it, and
`resources/seon/schemas/seon.cluster.agent.edn:105` now documents the
attribute as "not unique — several agents may share one". So the transaction
succeeds and every assertion in the test reads `nil`.

Measured 2026-09-07 at `21b849816`: `bin/test seon.db-test
seon.cluster.loop-test` — **61 tests, 422 assertions, 7 failures, 2 errors**,
all nine inside this one test. `seon.cluster.loop-test` is green.

## Owner

`test/seon/db_test.clj`, with the agent-record wave that removed the
constraint
([PRD §3](../../prds/context-generation/plan/agent-record-and-repl-response-prd-2026-09-07.md)).

## Direction

The test's subject is `seon.db`'s unique-conflict rendering
(`unique-conflict`/`rejection-message`, `src/seon/db.clj:1985-2016`), not the
agent's namespace. Re-point it at an attribute that IS still
`:db.unique/identity` — `:seon.cluster.agent/id` is the obvious one, and it is
what the surrounding fixture already builds — so the assertion keeps proving
the rendering and stops asserting a deleted constraint.

## Acceptance

The test refuses a duplicate on a genuinely unique attribute and names the
existing owner; `bin/test seon.db-test` is green; no test anywhere asserts a
unique `:seon.cluster.agent/namespace`.
