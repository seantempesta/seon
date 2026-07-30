---
type: issue
status: open
severity: blocker
tags: [issue, source, indexing, clj-kondo]
---

# Refuse error-level clj-kondo findings during repository indexing

## Problem

Repository indexing blocks only `:syntax` and `:analysis-error` finding types,
so ordinary error-level findings such as unresolved symbols, invalid arities,
and namespace mismatches can enter the packaged program graph. Runtime
per-form admission already rejects every error-level finding; the policies
disagree.

## Evidence

The probe in
`docs/prds/sci-execution-runtime/research/current-src-adversarial-review-2026-07-30.md`
analyzed `(defn broken [] missing)`. clj-kondo emitted
`:namespace-name-mismatch` and `:unresolved-symbol` at error level, while
`seon.fn/build-manifest` succeeded and emitted rows. The under-strict predicate
is `src/seon/fn.clj:260-271`; runtime's level-based boundary is
`src/seon/cluster/loop.cljc:80-98`.

## Owner

The one clj-kondo admission predicate shared by complete, changed-file, and
runtime analysis.

## Acceptance

- Every error-level finding refuses publication of the affected repository
  source; warnings, including type-mismatch context, remain advisory.
- Complete and per-file builders use the same admission predicate as runtime.
- An error-bearing packaged base is never published.
- Unresolved-symbol, invalid-arity, and namespace-mismatch regressions exercise
  real clj-kondo output.
