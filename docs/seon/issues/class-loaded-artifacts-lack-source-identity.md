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

Five open issues span 2026-07-29 through 2026-08-08:
[[a-schema-resource-edit-bricks-value-admission-in-every-running-cluster]],
[[live-publication-has-a-hand-maintained-predicate-owner-reload]],
[[partial-hot-reload-produces-mixed-code-with-no-warning]],
[[publication-reload-hand-lists-namespaces-and-misses-dependencies]], and
[[stale-language-specific-kondo-cache-blocks-correct-code]]. The latter two
notes each record two separate occurrences on 2026-08-07 or 2026-08-08.

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
