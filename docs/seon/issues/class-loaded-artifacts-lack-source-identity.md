---
type: issue
status: open
severity: blocker
tags: [issue, operator, runtime, class/n3, class-kill, wave/class-kill-queue]
---

# Give every loaded artifact enforced source identity

## Problem

A running JVM, analysis cache, or live-publication reload can serve code whose
source generation differs from the tree or published program generation
without a typed refusal. Hand-maintained reload sets make a mixed generation
constructible whenever an owner or dependency moves.

## Evidence

Current open members carry `class/n3` and are derived with
`bin/issues-index --class class/n3`.

## Owner

The source-publication generation, namespace acquisition/reload boundary, and
language-specific analysis-cache constructors.

## Acceptance

- Every loaded namespace and analysis artifact carries the digest/generation
  of the source and dependency closure that produced it.
- Reload and invalidation derive transitive owners from recorded facts; no
  namespace reload roster is accepted as input.
- A stale or mixed generation is refused before execution with both expected
  and loaded identities, and a recurring live-JVM proof moves an owner without
  editing a reload list.

## Re-verified at HEAD (2026-09-15)

UNVERIFIABLE-WITHOUT-GATE (`seon.cluster.source-test`, `seon.dev.source-instrumentation-test`). Audited HEAD `7e35df213:src/seon/cluster.clj:1956-1999` derives reload order, reloads Vars, acquires SCI, arms contracts, then records the accepted source commit. Thus the old claim of no source identity is too broad, but a failed multi-namespace reload may precede the marker update; the source is not an atomic Var swap. The concrete residual owner is `partial-hot-reload-produces-mixed-code-with-no-warning.md`; this slice's handle-shape note is another lifecycle boundary. Need controlled mid-reload failure and old/new callable evidence on a disposable cluster. No such mutation was performed on default. Keep blocker; neither old prose nor the live health timeout confirms mixed code at audited HEAD.

surface: adoption-publication
