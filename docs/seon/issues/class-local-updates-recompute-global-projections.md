---
type: issue
status: open
severity: friction
tags: [issue, performance, render, schema, class/n9, class-kill, wave/class-kill-queue]
---

# Make local updates unable to recompute global projections

## Problem

Several local operations reconstruct or rerun a whole schema, source,
namespace, render, or run projection. Costs that should scale with the changed
dependency closure instead scale with the complete database/program graph.

## Evidence

Six open issues span 2026-08-01 through 2026-08-10:
[[a-run-pays-two-and-a-half-seconds-between-every-form]],
[[ai-context-bypasses-render-proc-retained-bytes]],
[[complete-publication-takes-seventy-seconds]],
[[contracted-defn-rebuilds-the-whole-schema-projection]],
[[core-namespace-pages-spend-seven-seconds-without-declaration-fallbacks]], and
[[render-package-proc-reruns-unchanged-renderers]].

The archive repeats render-owner bypass and redundant derivation on
2026-08-11 in [[archive/transcript-history-render-bypassed-retained-call]].

## Owner

The immutable-basis projection caches and recorded dependency edges at schema,
publication, run-loop, and render boundaries.

## Acceptance

- Derived state rides the database value/program generation that determines
  it, and invalidation is the recorded transitive dependency closure.
- Local constructors cannot call a global rebuild or full render walk.
- Cost properties show unchanged work remains constant while total graph size
  grows, and changed work scales with the affected closure.
