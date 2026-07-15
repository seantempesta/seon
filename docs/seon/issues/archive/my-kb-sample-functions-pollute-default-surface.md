---
type: issue
status: resolved
severity: friction
tags: [issue, agent, capability]
---

# Keep sample knowledge recipes out of the standing tool surface

## Problem

`my.kb` advertised eleven functions for its worked sample-source domain beside
the two general knowledge operations. Every ordinary agent therefore received
source-title, author, rating, and topic operations even when its task had
nothing to do with that example.

## Evidence

The database-derived namespace audit found thirteen positive
`:seon.fn/agent-facing?` facts in `my.kb`: general `remember` and `recall`, plus
eleven functions that operate only on the colocated `:my.kb.source/*` example.
Because `my.kb` is a home-required namespace, all thirteen appeared in every
standing compact namespace surface.

## Owner

Positive metadata on each function in `src/my/kb.cljs` owns agent-facing
eligibility. The analyzer-backed program graph and namespace renderer already
derive the surface from that metadata; no downstream filter is needed.

## Acceptance

- The indexed, database-derived eligible symbol set for `my.kb` is exactly
  `my.kb/remember` and `my.kb/recall`.
- All eleven sample functions remain public, indexed, schema'd, callable by
  existing examples and tests, and present in the full namespace source.
- Deliberately selecting `my.kb` still exposes the complete worked source and
  its colocated `:my.kb.source/*` schema.
- No blocklist, wrapper namespace, benchmark rule, or context prose is added.

## Resolution

Resolved by removing only the positive agent-facing metadata from the eleven
sample functions. The general operations retain their positive metadata.
Focused index tests derive the exact eligible set from the production core
index, prove every sample function remains indexed without an eligibility fact,
and prove the full stored namespace source still contains every recipe and the
sample schema. The two exact selectors pass 37 assertions with no failures.
