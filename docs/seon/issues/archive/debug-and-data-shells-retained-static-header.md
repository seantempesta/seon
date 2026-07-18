---
type: issue
status: resolved
severity: bug
tags: [issue, web, database]
---

# Debug and data shells retained static header

## Problem

The debug and database page shells rendered a zero-agent header outside
`#app-view`, while their feed morph rendered a second zero-agent header inside
`#app-view`. The duplicate `#system-header` IDs made the first header both stale
and impossible for Datastar to replace.

## Resolution

The shell now owns only the loading `#app-view`. Each feed acquires the fleet
summary at the same immutable database value as its page content and includes
the sole database-derived header inside the complete morph. Debug and database
acquisitions run concurrently.

## Evidence

Focused system-render and web-serve proof passes 18 tests and 75 assertions.
The live gzip feed repeat is recorded in the database-authority roadmap.
