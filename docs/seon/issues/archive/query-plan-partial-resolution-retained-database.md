---
type: issue
status: resolved
severity: friction
tags: [issue, database, flow]
---

# Release an earlier database when later query resolution fails

## Symptom

The ordinary query resolver built its descriptor-to-database map before
entering the `try` that released materialized database values. If the first
historical value resolved and a later source failed, the exception escaped
before cleanup could see the already-materialized value.

The same failure order matters more for `execute-many`, where one aggregate can
name several databases before any member starts.

## Resolution

Resolution records each successfully retained value as it is acquired and
releases the accumulated values from the catch path. `execute-many` validates
and resolves every member's database values atomically, so an invalid later
value starts no member and releases every earlier historical materialization.

The real UDS contract forces that order with a valid historical value followed
by a reachable database descriptor carrying the wrong basis. The aggregate
returns one failure, no member results, and exactly one release of the earlier
materialized database.
