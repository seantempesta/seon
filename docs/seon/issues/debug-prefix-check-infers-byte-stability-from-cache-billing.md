---
type: issue
status: open
severity: friction
tags: [issue, render, ai, wave/verification-audit]
---

# Prefix stability is inferred from cache billing rather than captured prompt bytes

## Problem

`prefix-problem` calculates current cache misses minus prompt-token growth, compares that number with the literal tolerance `128`, and reports `Prefix changed` or `Prefix stable`. Those counters do not encode prefix identity. For equal prompt lengths of 1000 tokens, miss=0 yields stable regardless of the actual text; miss=1000 yields changed even if the text is identical. This is a direct consequence of the implemented formula, not an observation about a particular provider cache. Stored context captures already supply the actual byte authority.

## Evidence

Audit-1, 2026-09-15; committed snapshot `0c70a1cb4b14a391935d47762580abe02c235cc4`. Line numbers below refer to that snapshot, not concurrent working-tree edits.

- `src/seon/render/transcript.clj:1817–1834, 1970`
- `src/seon/render/transcript.clj:1589–1592`
- `docs/prds/context-generation/research/debug-product-landing-2026-09-14.md:27`

## Owner and deletion

Delete the billing-derived prefix verdict and tolerance. Compare the applicable captured prompt prefixes using the declared additive-context boundary. Keep cache hit/miss statistics labelled as billing/cache observations.

Estimated change: 18–35 lines replaced. Audit classes: 1, 3. No production edits for this finding were made by the audit lane.

## Acceptance

Equal prompt bytes with different cache counters have the same stability verdict. Different applicable prefix bytes with equal counters do not pass. Missing captures explicitly make byte stability unavailable.

See [the audit](../../prds/context-generation/research/audit-1-2026-09-15.md) for scope, change counts, and verification limits.
