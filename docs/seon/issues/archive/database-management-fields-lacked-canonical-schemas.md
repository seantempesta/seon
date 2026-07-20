---
type: issue
status: resolved
tags:
  - database
  - schema
  - initialization
severity: friction
tags: [issue]
---

# Database management fields lacked canonical schemas

## Failure

Fresh program initialization rejected `:seon.state/reconcile-request` because
`:seon.db/managed-scope` and `:seon.db/managed-identity-attrs` appeared in its
registered map form without their own canonical registered schemas.

## Resolution

`seon.db` now registers both database-management attributes as sets of fully
qualified keywords. They travel with the complete program schema population
and can be resolved consistently by the JVM writer.

## Evidence

- `bin/test-cljs --test=seon.state-test`: 13 tests, 45 assertions, zero failures
  under Bun.
