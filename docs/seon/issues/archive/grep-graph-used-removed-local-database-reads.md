---
type: issue
status: resolved
severity: friction
tags: [issue, database, capability, cljs]
---

# Grep graph used removed local database reads

## Failure

`seon.agent.search/grep-graph` remained synchronous after `seon.db/query`
became an asynchronous operation against the JVM database authority. Its four
program queries treated returned Promises as result collections. The fixture
hid this mismatch by installing an embedded Datahike connection through the
removed `db/*conn*` path.

## Resolution

`grep-graph` is now asynchronous. It captures one immutable database value and
sends the selected function, schema, namespace, and optional eval queries in
one bounded `seon.db/execute-many` request. The JVM can process those
independent queries in parallel at the same database value. ClojureScript keeps
only the pure regex matching and namespace grouping work.

The fixture supplies ordinary query results through the public database seam
and verifies that every selected query receives the same captured database
value. It no longer creates, connects, transacts, or mutates a local Datahike
database.

## Verification

`bin/test-cljs --test=seon.agent.search-test` passes 29 tests and 128
assertions with no warnings.
