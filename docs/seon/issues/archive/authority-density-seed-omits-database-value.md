---
type: issue
status: resolved
severity: friction
tags: [issue, database]
---

# Authority-density seed omitted its database value

## Problem

The selective `seon.authority-density-test` failed before its real Bun-process
query wave because `authority-density/seed` constructed a current transaction
request without `:seon.db/db`. The protocol correctly rejected that stale
pre-database-value shape, leaving the fixture empty. Its later cache assertions
therefore proved sharing only for an empty result.

## Resolution

The fixture now resolves the current database value through the writer's public
protocol and supplies it to the seed transaction. The focused writer gate
passes one test and 51 assertions. One real Bun session reads all 400 rows and
establishes one cache owner. Eight independent Bun sessions then read the same
400 rows with one owner and seven joined callers; every physical session is
released and the test exits cleanly.
