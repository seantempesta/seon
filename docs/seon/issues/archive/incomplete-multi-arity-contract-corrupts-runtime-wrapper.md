---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, schema, cljs]
---

# Reject incomplete multi-arity contracts before wrapper mutation

## Problem

`seon.instrument/prepare-target` accepts a live multi-arity ClojureScript
function with a single-arrow `:=>` contract. Malli's multi-arity surgery can
then mark the function instrumented without wrapping every callable arity, and
its later `unstrument!` writes a missing recorded original back as nil. A
previously callable arity becomes a non-function.

This also invalidates full reconstruction as a recovery mechanism for failed
program publication: reconstruction cannot safely unstrument and reinstall a
committed generation until contract/live-arity parity is a proven precondition.

## Evidence

The selected Malli 0.20.0 source at SHA
`80138076960e7820523b4cb932c5b5d1936d4e7f` exposes exact schema arities through
`m/-function-schema-arities` and `m/-function-info`. Its CLJS instrumentation
restores every live fixed-arity accessor from that accessor's
`malli$instrument$original` value without checking that one exists
(`reference-code/malli/src/malli/instrument.cljs:62-75,113-130`).

Before the fix, `seon.instrument/prepare-target` compiled a non-simple function
contract but did not compare those schema arities with the live function's
`cljs$lang$maxFixedArity`, `cljs$core$IFn$_invoke$arity$N`, and variadic
accessors. The prerequisite and missing
regression were already specified in
[[../../prds/agent-runtime-correctness/research/incremental-instrumentation-2026-07-12]]
but were not implemented.

A live default-pod probe on 2026-07-14 supplied one `:=>` contract for an
isolated two-arity function. Instrumentation returned normally and set
`malli$instrument$instrumented?`, while its one-argument call remained
unvalidated. `unstrument!` then replaced
`cljs$core$IFn$_invoke$arity$1` with a non-function and the call crashed. The
probe function was immediately redefined to restore it.

The implemented fix derives fixed accessors and the optional variadic minimum
from the original live function, derives the compiled contract profile through
Malli's function-schema protocol, and rejects unequal profiles as
`:seon.instrument/arity-mismatch`. Both cold reconstruction and delta
replacement stop before invoking Malli when any fatal preparation rejection is
present.

`bin/test-cljs --test=seon.instrument-delta-test` passed on 2026-07-14: 7 tests,
116 assertions, zero failures or errors. Its focused regressions prove a mixed
cold candidate performs no mutation, incomplete two-arity and variadic
replacements preserve the prior wrapper and every accessor by identity, and
complete fixed plus variadic contracts survive three exact
unstrument/reinstrument cycles. Live post-fix proof then submitted the same
incomplete contract for an isolated two-arity function: preparation returned
`:seon.instrument/arity-mismatch` with exact live `#{1 2}` and schema `#{1}`
profiles, performed zero instrumentation, preserved the function and both
arity accessors by identity, and left both calls usable.

## Owner

`seon.instrument/prepare-target` and the one exact-data Malli wrapper
publication path.

## Acceptance

- Candidate preparation derives the complete live fixed and variadic arity set
  and compares it with the compiled contract's exact `m/-function-info` rows.
- A missing, extra, or incompatible arity returns one structured fatal
  rejection before `mi/unstrument!` or `mi/instrument!` runs.
- Complete multi-arity and variadic contracts survive repeated exact
  unstrument/reinstrument with every callable accessor intact and validated.
- The focused regression proves an incomplete two-arity contract leaves the
  prior wrapper and every live accessor byte-identical.
- The fixing commit is recorded in this note's history.
