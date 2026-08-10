---
type: issue
status: open
severity: blocker
tags: [issue, testing, boot, source]
---

# Keep source-publication fixtures complete under activation lookup refs

## Problem

Three complete-suite proofs disagree with the current activation-closure
contract. Synthetic publication lacks required lookup-ref owners, a partial
cluster test assumes every missing member is an executable symbol, and the
canonical publication fixture supplies an activation derivation that resolves
as an empty symbol.

## Evidence

Clean commit `48eb25ab7` produced one failure and two errors:

- `incremental-source-refresh-preserves-agreement-across-real-edits` —
  `Initialization lookup refs do not resolve` at `cluster.clj:443`;
- `partial-clusters-refuse-and-fresh-clusters-are-current` — missing members
  included lookup refs for `seon.db/supplied-database-value` and
  `seon.db/supplied-connection`, contradicting the test's executable-only
  assertion; and
- `source-publication-records-core-on-every-row` — `the activation derivation
   does not resolve` at `source.clj:76`.

The archived issue
[[archive/new-cluster-boot-fails-on-a-stale-published-source]] records why
initialization lookup refs became explicit prerequisites; these failures show
the recurring fixtures did not all accrete that contract. Evidence:
`tmp/full-gate-2026-08-10b.log:1101-1160,4038-4080`.

## Owner

Suspected owner: canonical source-publication test fixtures and the activation
derivation request at `seon.cluster.source`; production activation closure is
the contract, not the compatibility target.

## Acceptance

- Every publication fixture supplies resolvable population and activation
  owners plus all initialization lookup refs.
- Missing activation evidence is asserted as the declared union of executable
  symbols and lookup refs.
- Complete, incremental, and partial-cluster proofs pass together without
  weakening production preflight.
