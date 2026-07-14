---
type: issue
status: open
severity: friction
tags: [issue, database, agent]
---

# Database query tuple results are hard for agents to read

## Problem

Agents can confuse Datalog tuple/set results with entity maps and then write
invalid follow-up code.

## Evidence

The archived dual-path audit's C26 row records this legibility failure. The
query API currently returns the database's tuple shape without a discoverable
result explanation at the call boundary.

## Owner

`seon.db/query` schemas, examples, and result rendering.

## Acceptance

Behavioral agent probes distinguish scalar, tuple, collection, and relation
query shapes from self-describing schemas/examples or compact result rendering,
without adding a large prose context block or query-specific coercion.
