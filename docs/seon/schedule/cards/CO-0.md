---
title: "Card CO-0: A cost proof counts any function's calls"
type: reference
status: active
created: 2026-10-08
---

# CO-0 A cost proof counts any function's calls

Pattern P6 ([merged.md](../../../research/agent-platform/2026-10-08/cost-design/merged.md) §2). Pins: Seon HEAD
`167e89b0d`. Lane: Sonnet. `src/seon/profile.clj` was held by health-self: claim it or send the patch.

## What exists
`seon.profile/measure` returns `:seon.profile/ranked-by-calls` and `:seon.profile/ranked-by-self`, each cut at
`seon.profile/hot-limit`. A proof that a function ran zero times cannot tell "zero" from "below the cut"; every CO
proof needs that answer.

## Change
`measure` also returns `:seon.profile/called`, the complete `{sym calls}` map it already builds as `called`
(no new counting), and one reader `seon.profile/calls-of [measured sym] → nat-int` (0 when absent).

## Functions
- CHANGE `seon.profile/measure`: output schema `:seon.profile/measured-call` gains `:seon.profile/called [:map-of :qualified-symbol pos-int?]`.
- ADD `seon.profile/calls-of` `[:=> [:cat :seon.profile/measured-call :qualified-symbol] nat-int?]`.

## Proof
`seon.profile-test/calls-of-counts-a-function-past-the-hot-limit`: measure a thunk calling `hot-limit` + 5 distinct
armed fixture functions once each and one of them twice; `calls-of` answers 2 for it and 0 for an uncalled one.

## Cost
Expected: the map exists already; returning it costs nothing new. Parent: n/a (capability).

## Commit
`bin/lane commit <lane> -m "CO-0: measure returns every called function's count" --test seon.profile-test src/seon/profile.clj test/seon/profile_test.clj resources/seon/schemas/seon.profile.edn`

## Dependencies
None. Every other CO card's proof uses it.
