---
type: issue
status: open
severity: blocker
created: 2026-09-16
tags: [issue, database, schema, program-graph, class/retirement-fact]
---

# Retained identities have no declared retirement state

## Problem

Content-removal writers keep identities but leave rows invalid against their
entity schemas. Program rows have a second validator for inferred tombstones;
issues have no such exception. Reference-only minting fabricates live-looking
program rows. These are one missing lifecycle fact, not per-writer failures.

## Evidence

`tmp/orchestrator/gate-results/batch-110.log:272` refuses the archived issue
`bootstrap-analyzer-api-emits-undeclared-var-warnings` for missing title.
`src/seon/db.clj:2913–2973` contains the program-only fallback validator.
`src/seon/program.cljc:11` includes file and lint identities as well as fn,
test, ns and schema. Issue `index-tx` and `adopt-tx` both remove content.

The complete dated inventory, dependency sources, exact schema construction,
and reset boundary are in
[the retirement design](../../prds/steward-platform/research/retirement-is-a-fact-2026-09-16.md).
The earlier resolved issue
[evidence naming deleted declarations](adoption-refuses-when-test-evidence-names-a-deleted-declaration.md)
restored reference resolution; it did not declare the distinction between
retired and referenced-only identities.

## Owner

The existing schema-form inspection, database validator, program exact
replacement, issue replacement and reference-minting owners. Design only is
submitted; implementation awaits orchestrator review. Strict retirement also
needs an explicit decision about runtime evidence and protected issue facts,
as detailed in the design. No alternate validator or migration.

## Acceptance

Every retirement writer leaves the identical identity + retirement transaction
shape while preserving incoming refs. Entity schemas validate retirement
directly and still refuse incomplete definitions. Reference-only identities
carry their own positive fact. Redefinition atomically clears lifecycle facts.
Canonical armed regressions cover all four retiring writers and schema
discovery. The orchestrator performs one batched reset and reindex with the
other incompatible schema slices. The second validator is deleted.
