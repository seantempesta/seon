---
type: issue
status: resolved
severity: blocker
tags: [issue, testing, sci]
---

# Keep writer fixture schema data outside SCI source

## Problem

Several JVM host fixtures serialized canonical schema transaction data into
strings evaluated by SCI. Symbol values such as
`:seon.ns/name 'seon.host.context` consequently became code positions during
analysis and failed as unresolved agent symbols. The same fixtures also
depended on arbitrary persisted generated identities and on the process-global
test schema registry, making focused and full-suite initialization populations
different.

## Resolution

The affected fixtures now send the verified compiled program rows through
`seon.db.protocol/initialization-pages` via
`seon.db.writer-test-support/seed-canonical-schema!`. Fixture-owned schema rows
are explicit supplemental transaction data; they are never inferred from the
ambient registry. Generated agent and turn identities are allocated through
the maintained candidate transaction contract. Evaluated transactions remain
only where the test is exercising agent-authored data.

The schema-form owner now exposes the pure database-attribute derivation used
both by production registration and by the compiled fixture population, so
rows and declared attributes come from the same immutable forms.

## Evidence

The focused cancellation gate passes 4 tests and 31 assertions with zero
failures and zero errors.

The complete writer gate passes 641 tests and 4,339 assertions with zero
failures and zero errors. The same full-process run covers the authored
invocation, cancellation, graduation, hostile battery, instrumentation,
interrupt, registry, and planning fixtures, proving the repair is not
dependent on focused namespace load order.
