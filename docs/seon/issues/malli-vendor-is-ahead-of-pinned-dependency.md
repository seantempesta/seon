---
type: issue
status: open
severity: friction
tags: [issue, dependency, malli, source-grounding]
---

# Align vendored Malli source with the pinned dependency

## Problem

The source used to ground Malli behavior is ahead of the artifact the system
runs. Reading `reference-code/malli` can therefore support behavior that is not
present in the pinned release.

## Evidence

`deps.edn:14` pins `metosin/malli` `0.20.0`.
`reference-code/malli/CHANGELOG.md:17-21` has an `UNRELEASED` section above the
`0.20.0` release. Both facts remain current after commits `21215ce28`,
`ba723b2d1`, and `a6d426983`.

## Owner

The root dependency ledger and the maintained `reference-code/malli` source
checkout.

## Acceptance

- The vendored Malli revision exactly matches the pinned artifact, or the
  runtime coordinate is deliberately advanced to the vendored revision.
- The selected revision or tag is recorded where the dependency ledger can
  verify it.
- Malli behavior used by Seon's schemas and instrumentation is tested against
  the same revision whose source is read.
