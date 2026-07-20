---
type: issue
status: resolved
severity: blocker
tags: [issue, database, pod, flow]
---

# Shared-writer cluster did not select fresh config

## Evidence

An autonomous shared-writer cluster launched with no ambient `SEON_CONFIG`.
Unlike the default `up` path, its target selection did not choose
`config/system.edn` for a fresh database. The pod therefore opened without
initialization, then correctly failed when no config singleton existed.

## Resolution

Config selection now belongs to `seon.dev.config` and uses the database path
from the selected launch descriptor. Both the default operator and a
shared-writer cluster use that one function. A fresh database selects the
shipped manifest; reopening an existing database applies no ambient config;
an explicitly inherited or requested manifest remains authoritative.

Focused cluster, CLI, and config proof passes 43 tests and 143 assertions.

## Acceptance

- Every fresh autonomous cluster receives deterministic initialization.
- Config-free reopen preserves the database singleton.
- Default and shared-writer launches use one config-selection owner.
- The selected database path, not the source cluster path, determines whether
  a database is fresh.
