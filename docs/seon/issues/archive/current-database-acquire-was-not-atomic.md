---
type: issue
status: resolved
severity: friction
tags: [issue, database, flow]
---

# Acquire the current database route atomically

## Symptom

Protocol v9 acquires a database by logical name and returns its ordinary current
database value. The writer initially resolved the route's internal attachment,
then passed that attachment through a second registry call. A concurrent route
replacement could make this current-value operation fail between those steps.

## Resolution

`seon.db.registry/acquire-database!` now accepts an optional expected
attachment. Descriptor-based reads still pass one and retain exact-route
validation. A current database acquire omits it, so the registry selects and
acquires the current route inside its existing lock. The writer returns the
ordinary database value from that acquired route; neither attachment nor
coordinate crosses the protocol.

The registry contract proves that omitting the expectation returns the current
attachment, while the real UDS contract proves `resolve-head` and explicit
acquire return the same ordinary database value and that it drives a later
query.
