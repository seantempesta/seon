---
type: issue
status: resolved
severity: bug
tags: [issue, web, agent]
---

# Unknown agent page served unreplaceable loading shell

## Problem

A well-formed unknown `/agent/{id}` returned a 200 loading shell, while its
feed truthfully returned 404. The browser therefore remained on a page whose
loading state could never be replaced.

## Resolution

The page handler now acquires one database value and reuses the feed's existing
agent-existence query. Known agents receive the shell, unknown agents redirect
to `/`, database errors return 503, and `/agent/root` retains its canonical
redirect without a database read.

## Evidence

The focused Datastar contract passes 11 tests and 34 assertions. Live route
proof is recorded in the database-authority roadmap.
