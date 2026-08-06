---
type: issue
status: resolved
severity: cleanup
tags: [issue, deletion, cluster]
---

# Delete the reset-only instruction migration

## Problem

Source population still retracted four instruction identities from the deleted
context system. The rename pass ended with a destructive reset and explicitly
forbids migration or old spellings; every surviving branch is either freshly
forked from the current source or sovereign older data. Re-running a
compatibility cleanup on every population was therefore a superseded
mechanism.

## Evidence

- `src/seon/cluster/instruction.clj` hard-coded `:reply-grammar`, `:messaging`,
  `:declining`, and `:global` solely as `superseded-instruction-ids`.
- `src/seon/cluster.clj` queried and retracted those identities before adding
  current instruction rows.
- `test/seon/cluster/instruction_test.clj` deliberately preserved the
  migration by installing one obsolete row and asserting its retraction.
- The reset contract in
  `docs/prds/sci-execution-runtime/plan/rename-pass-2026-08-05.md:8-14`
  requires zero migration and zero parallel old spellings after the reset.

## Owner

The additive source-population owner in `seon.cluster` and the current
instruction declaration in `seon.cluster.instruction`.

## Acceptance

Delete the obsolete identity roster, its retraction pass, and the test that
manufactures old data. Current instruction population remains additive and
idempotent, and a repository search finds no live reference to the four old
instruction identities outside historical documents.

## Resolution

Resolved by audit-finding-3 commit `a79699eab`. Population
now submits only absent current instruction rows, the test proves an existing
owner revision remains untouched, and exact searches find none of the four old
instruction identities in maintained source, tests, scripts, binaries,
resources, or skills. Proof: `seon.cluster.instruction-test` ran 4 tests / 16
assertions with zero failures and errors.
