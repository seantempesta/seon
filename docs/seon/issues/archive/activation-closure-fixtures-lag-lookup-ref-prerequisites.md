---
type: issue
status: resolved
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

## Resolution

Resolved by `5ebcb6ca2`. Canonical complete publication and the standalone
artifact installer now name both the population and activation owners. The
synthetic edit fixture derives every `:seon.fn/sym` lookup prerequisite from
`config/default-population`, so a new initialization supplier accretes into
the fixture without another symbol list. The partial-cluster proof accepts
the declared union of executable-symbol and lookup-ref missing members.

Evidence on 2026-08-10:

- `seon.schema.admission-test`: 4 tests, 12 assertions, zero failures/errors;
- `incremental-source-refresh-preserves-agreement-across-real-edits`: green
  when selected directly;
- `partial-clusters-refuse-and-fresh-clusters-are-current`: 1 test, 80
  assertions, zero failures/errors; and
- `bin/test --changed src/seon/artifact.clj --changed
  test/seon/cluster/boot_test.clj --changed
  test/seon/schema/admission_test.clj`: 85 tests, 427 assertions, zero
  failures/errors.

One earlier partial-cluster rerun had 79 passing assertions and one source
digest mismatch. The docs-only commits that landed nearby did not cause it:
the `fault-facts` lane modified `src/seon/flow.clj` at 17:11:17 while that
publication proof was running. The stable-tree rerun above closes that
unrelated attribution.
