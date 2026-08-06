---
type: issue
status: open
severity: cleanup
tags: [issue, deletion, cluster]
---

# Delete the reset-only instruction migration

## Problem

Source population still retracts four instruction identities from the deleted
context system. The rename pass ended with a destructive reset and explicitly
forbids migration or old spellings; every surviving branch is either freshly
forked from the current source or sovereign older data. Re-running a
compatibility cleanup on every population is therefore a superseded mechanism.

## Evidence

- `src/seon/cluster/instruction.clj:13-15` hard-codes
  `:reply-grammar`, `:messaging`, `:declining`, and `:global` solely as
  `superseded-instruction-ids`.
- `src/seon/cluster.clj:719-745` queries and retracts those identities before
  adding current instruction rows.
- `test/seon/cluster/instruction_test.clj:83-105` deliberately preserves the
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
