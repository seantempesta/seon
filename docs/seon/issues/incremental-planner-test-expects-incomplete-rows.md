---
type: issue
status: open
severity: friction
tags: [issue, test, operator, wave/publication-velocity]
date: 2026-09-09
---

# Incremental planner test expects incomplete rows

## Problem

`test/seon/fn_test.clj`, `changed-file-planning-is-conservative-and-explicit`,
expects a source edit to emit only function identity and source. The authored
entity validator requires namespace and admission provenance too. The writer
repair retains the changed row's scalar declaration, so this exact-map
expectation must change with that contract.

## Evidence

The expectation at lines 993–994 is
`{:seon.fn/sym "sample/value", :seon.fn/source "(defn value [] 2)"}`.
Its authored input omits admission provenance. The real artifact producer
already supplies `:seon.schema.admission/source :core`.
The publication-provenance lane's real-file regression runs with the canonical
database fixture and verifies admitted publication, required fields, and
preserved component identities. Its landing note is
`docs/prds/context-generation/research/publication-provenance-landing-2026-09-09.md`.

## Owner

`test/seon/fn_test.clj` is protected by data-lane's in-flight edits. The
publication-provenance assignment explicitly forbids editing that lane's
files or contacting its session. This note records the integration boundary;
it is not a claim that the complete function test namespace was run.

## Acceptance

After data-lane releases the file, supply provenance in the synthetic planner
inputs and expect complete scalar rows. Run `seon.fn-test` with contracts
armed. Keep its conservative rebuild assertions.
