---
type: issue
status: open
severity: friction
tags: [seon.operator, collect, gc-storage, datahike, maintenance, class/absence-as-health]
opened: 2026-09-17
---

# The collector reports "incomplete" when writers are live, and then skips root verification

## Problem

`seon.operator/collect!` decides completeness as
`(and (zero? verification-swept) (evidence-reopens? …))`
(`src/seon/operator.clj:907-909`). The first conjunct requires a second
collection pass to sweep nothing. With live writers (two lanes publishing,
agents taking turns) and `remove-before` = now (`:890`, `:904`), every write
that lands during the first sweep is garbage by the time the second pass runs,
so the fixed point is unreachable by construction. On 2026-09-17 the first
real collection on `default` swept 23,449 objects (3.98 GB → 238 MB) and the
verification pass swept 1,229 more; the call threw
`:seon.operator/collection-incomplete` with the message "Collection did not
preserve and verify every recorded root."

Two defects in one:

1. The message names root preservation, but the failing conjunct was the
   fixed point; and because `and` short-circuits, `evidence-reopens?` — the
   check that every branch commit reopens and every referenced blob reads —
   NEVER RAN. A caller reading the refusal believes roots were checked and
   found missing; in fact they were not checked at all. That is the
   absence-as-health class inverted: a loud refusal about the wrong thing.
2. "Second pass sweeps zero" is not a property a live store can have. The
   collector's own correctness comes from Datahike's safe point
   (`reference-code/datahike/src/datahike/gc_guard.cljc`), not from a quiet
   store.

Roots were verified by hand afterwards (both agents, 5,003 function
entities, history readable, Juniper's evaluations present).

## Wanted

- Completeness = every recorded root reopens (branches by commit id, blobs by
  a physical read). Evaluate it unconditionally and report it as its own
  fact.
- The second pass's count is reported as `:seon.operator.collect/verification-pass-swept`
  and never decides completeness; under live writers it is expected to be
  non-zero.
- The refusal, when roots genuinely fail to reopen, names the branch or digest
  that failed.

Owned by S8 of `docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md`.
