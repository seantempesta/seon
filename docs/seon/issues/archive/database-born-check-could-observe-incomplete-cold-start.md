---
type: issue
status: resolved
severity: blocker
tags: [issue, database, cljs, flow]
---

# Database-born check could observe incomplete cold start

## Evidence

Program initialization and config reconciliation were separate committed
transitions, but the operator treated any database files as evidence that no
startup manifest was needed. A process failure after program admission and
before config reconciliation could therefore leave a database without the
config singleton. Config-free retry then had no retained configuration from
which to rebuild startup.

## Resolution

The already-resolved config singleton is now part of the authority's one
atomic program and initial-data transaction, alongside the user and shared
instruction identities. Once an initialized database is published, it always
contains the configuration needed for config-free reopen. The later config
reconciliation remains the one owner for routes, skills, and managed drift.

Focused client initialization proof passes 7 tests and 22 assertions.

## Acceptance

- Program schema, program facts, config, user, and shared-instruction
  identities commit atomically.
- A published fresh database always supports config-free retry.
- Reopen does not implicitly reapply the checkout manifest.
- Route and skill reconciliation remains idempotent and independently
  retryable.
