---
type: issue
status: open
severity: friction
tags: [issue, dependency, research]
---

# Fix or record the Posh cardinality-one pull-analysis arity defect

## Problem

The vendored comparative Posh source calls a four-argument helper with three
arguments on the nested forward cardinality-one pull branch. Exercising that
branch raises an arity error instead of producing transaction patterns.

## Evidence

- `reference-code/posh/src/posh/lib/pull_analyze.cljc:131` defines
  `tx-pattern-for-pull` with `schema`, `pull-pattern`, `affected-pull`, and
  `refs-only?`.
- The reverse/cardinality-many branches pass four arguments at
  `reference-code/posh/src/posh/lib/pull_analyze.cljc:151-158`.
- The cardinality-one branch at
  `reference-code/posh/src/posh/lib/pull_analyze.cljc:159-160` passes the result
  of `(ref-key affected-pull refs-only?)` as the third and final argument; the
  helper's fourth argument is absent.

## Owner

The vendored Posh reference checkout. Posh is comparative research only and is
not part of the recommended Seon invalidation mechanism.

## Acceptance

- A focused upstream-compatible regression exercises a nested forward
  cardinality-one pull and returns patterns without an arity error.
- Either update the vendored pin to a revision containing the fix or carry a
  documented fork patch; do not copy the analyzer into Seon.
