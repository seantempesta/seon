---
type: issue
status: open
severity: cleanup
tags: [issue, test, schema, agent, wave/verification-audit]
---

# Executable-example test discovers APIs from directory names and copies launcher config members

## Problem

The new examples test discovers namespaces by listing only immediate `.clj` children of `src/my`, parses each file's first form, and re-parses a `Tools: … . Inspect` prose sentence. This misses nested or differently packaged callable namespaces even though `dir` already queries program facts. Its work launcher selects a copied five-key configuration roster. The touched plan API test still requires a fixed first-four directory list, and edit/fs tests retain explicit public-entry rosters; these latter rosters predate the day, but the refactor preserved them.

## Evidence

Audit-1, 2026-09-15; committed snapshot `0c70a1cb4b14a391935d47762580abe02c235cc4`. Line numbers below refer to that snapshot, not concurrent working-tree edits.

- `test/my/examples_test.clj:75–81, 121–140`
- `test/my/plan_api_test.clj:46–47`
- `test/my/edit_test.clj:31`
- `test/my/fs_test.clj:38–44`

## Owner and deletion

Derive the tested API population from declared program/namespace facts, compare help against that data before rendering prose, and hand the launcher's declared config/environment through its existing owner. Replace whole-surface rosters with graph-derived assertions while keeping concrete example cases.

Estimated change: 20–40 lines replaced/removed. Audit classes: 3. No production edits for this finding were made by the audit lane.

## Acceptance

A public function in a newly declared nested namespace is tested without editing the discovery code. Adding an admitted launcher input does not require extending a test-only key list. Renaming unrelated directory entries does not invalidate a fixed first-four assertion.

See [the audit](../../prds/context-generation/research/audit-1-2026-09-15.md) for scope, change counts, and verification limits.
