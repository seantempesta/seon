---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database, cljs]
---

# my.ns functions omitted the current database

## Problem

All three live application agents called the documented
`(my.ns/functions {:my.ns/ns 'some.namespace})` form. The implementation passed
the absent optional database as a positional query input and later passed nil
to `seon.db/pull`. Instrumentation returned an ordinary invalid-input error, so
the agents abandoned program-graph discovery and searched the filesystem.

## Resolution

`my.ns/functions` now captures the current immutable database value when the
map omits `:seon.db/db`, then uses fully namespaced map requests for its query
and pull. Database acquisition, query, and pull failures each return the
existing `:seon.result/ok? false` response instead of cascading into a later
invalid call.

Focused proof passes 5 tests/37 assertions, including omitted-database use. A
live omitted-database call for `my.orders` returns an ordinary successful empty
program view rather than an instrumentation failure.
