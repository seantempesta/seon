---
type: issue
status: resolved
severity: cleanup
tags: [issue, testing]
---

# Reconcile fresh-rung issue notes with the issue lifecycle

## Problem

Issue notes created or resolved by the reviewed fresh-tree wave do not follow
the localized issue lifecycle and severity vocabulary. The derived issue index
cannot validate, so the repository's issue authority is not a trustworthy
projection of open work.

## Evidence

`bin/issues-index --check` reports a stale index and, among broader pre-existing
violations, these in-scope notes:

- `mixed-union-datahike-declaration-lacks-fresh-edn-codec.md` uses status
  `active` and severity `high`;
- `and-wrapped-secondary-datahike-attribute-is-rejected.md` uses status
  `active` and severity `medium`;
- `schema-alias-hides-datahike-attribute-properties.md` is `resolved` but
  remains at the open-issue root and uses severity `high`;
- `store-flock-fcntl-close-hazard-has-no-recurring-falsifier.md`,
  `fresh-flow-source-is-not-covered-by-bin-test.md`, and
  `run-state-machine-property-shares-one-database-across-trials.md` use the
  unsupported status `closed` and remain at the open-issue root.

`docs/seon/issues/README.md` permits only open/resolved/superseded status,
blocker/friction/cleanup severity, and requires resolved notes under
`archive/`.

## Owner

The issue notes owned by each landing wave, followed by the derived
`docs/seon/issues/index.md`.

## Acceptance

- Every in-scope open note uses `status: open` and an allowed severity.
- Every resolved or superseded note records its fixing commit and behavioral
  proof, then lives under `docs/seon/issues/archive/`.
- `bin/issues-index` regenerates the projection and
  `bin/issues-index --check` passes without lifecycle or severity violations.

## Resolution

Resolved by `90a3cac60`: all open and archived notes now obey the lifecycle,
severity, and placement contract; the regenerated index validates cleanly.
