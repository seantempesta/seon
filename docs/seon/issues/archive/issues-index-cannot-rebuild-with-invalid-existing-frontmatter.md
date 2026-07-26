---
type: issue
status: resolved
severity: friction
tags: [issue, tooling]
---

Terminology: this note records evidence from before the rename; the process holding a run is now `:seon.agent.run/process`.

# Repair invalid existing issue frontmatter

## Problem

`bin/issues-index` cannot regenerate the derived issue index because seven
existing notes violate the maintained lifecycle, severity, or tag vocabulary.
This prevents unrelated issue closures from refreshing `index.md`.

## Evidence

The 2026-07-24 claimant-host issue closure ran both index commands. The scanner
reported four open notes with `status: active` and three archived notes with a
missing severity and `issue` tag. The claimant-host archive note itself passes
`seon.dev.markdown` validation.

## Owner

The seven reported issue notes and the normal `bin/issues-index` derived
projection workflow.

## Acceptance

- Every existing issue note satisfies the lifecycle, severity, and tag
  vocabulary in `docs/seon/issues/README.md`.
- `bin/issues-index` regenerates `index.md`.
- `bin/issues-index --check` passes immediately afterward.

## Resolution

Resolved by commit `ab23418d7`. The indexer now derives the projection from
every individually valid note, atomically writes that projection, and only
then fails loudly with one file-scoped expected/actual diagnostic per
violation.

Before repairing the six current offenders, `bin/issues-index` exited 1,
reported all nine violations, and still rebuilt an index containing all 124
valid open notes. After the frontmatter repairs, `bin/issues-index` and
`bin/issues-index --check` both reported 127 open and 463 archived notes.
The focused `seon.dev.issues-test` operator gate passed 2 tests and 6
assertions, and Markdown validation passed all eight touched documents.
