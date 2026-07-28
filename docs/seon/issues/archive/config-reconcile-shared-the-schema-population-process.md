---
type: issue
status: resolved
severity: blocker
tags: [issue, database, config, test]
---

# Config reconciliation shared the schema population process

## Problem

The production ancestor asserted canonical schema rows with
`"seon.db.process/config"`, the same provenance identity configuration uses
for exact reconciliation. A canonical database fixture therefore made the
first one-row config apply plan 465 operations: every schema row looked like a
stale entity in the config-managed population.

Hand-installed fixture subsets hid the interaction.

## Resolution

Boot and config are separate genesis producers. The ancestor asserts both
process entities before its seal, stamps canonical schema rows with
`"seon.db.process/boot"`, and leaves configuration reconciliation scoped to
`"seon.db.process/config"`.

`seon.test-support/with-database` invokes the production ancestor population.
Its recurring composition regression proves one config apply has one operation
and preserves the complete schema-row population.

## Evidence

- Before the fix,
  `bin/test seon.test-support-test seon.config-test seon.reconcile-test`
  failed with expected operation count 1, actual 465.
- Resolved by the Unit 2 commit which archives this note.
- The owning focused gate passed 24 tests and 124 assertions, including the
  ancestor, shared fixture, config, and reconcile suites.
