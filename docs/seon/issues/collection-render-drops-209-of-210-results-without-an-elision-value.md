---
type: issue
status: resolved
severity: blocker
tags: [issue, render, context, agent]
---

# A collection render drops 209 of 210 results without saying so

## Problem

The bootstrap "requires projection" form
`(db/q '[:find [(pull ?entity [*]) ...] :where [?entity :seon.ns/requires]] db)`
renders into agent context as ONE namespace's card, followed only by
`;; 28 definitions omitted by the namespace render budget.` The omission notice
covers the definitions INSIDE the one rendered namespace. Nothing anywhere says
that the query matched 210 namespaces and that 209 of them were dropped.

An agent reading this concludes the cluster has one namespace. That is not an
ugly render; it is a false one. The elision-value convention exists precisely
for this — omitted count, known total, path, next offset, requery identity — and
it is being applied one level too deep: to the inner definitions, not to the
outer collection that was actually truncated.

## Evidence

Measured on the minimum-context ablation FULL drive root
`tmp/ablation/drive-roots/full-02/clusters`, branch
`:cluster-minimum-context-full` (probe committed at `tmp/ablation/ns_probe.clj`):

```text
namespaces with :seon.ns/requires = 210
namespaces total                  = 298
```

The rendered agent context for the same query is reproduced verbatim in
`tmp/ablation/observer/full-prompt-0.txt` (also in the FLOOR and QUARTER
prompts, where this entry is a large fraction of the whole context). Its entire
collection-level output is the single `(ns seon.bootstrap …)` card.

## Acceptance

- A truncated collection render emits an elision value naming the omitted count,
  the known total, and the requery identity, at the level that was truncated.
- One regression proves the class dead: render a query result whose collection
  exceeds the profile and assert the elision value's count equals
  `total - rendered`, so a silent collection drop cannot be written.

## Owner

`seon.render` / `seon.print/fit` profile owner, with the
[self-generating-context PRD](../../prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md)
context-quality work.

## Resolution

Resolved by `3f6958fc2` and `731958e80`. `seon.print/fit` no longer discards a carried trailing
collection elision and then mistakes the admitted prefix for the source total.
It reads the carried total (or derives it from retained plus omitted), applies
the current child limit, and emits one recalculated trailing elision while
preserving the original requery identity or refusal.

`refitting-a-truncated-collection-preserves-its-honest-elision` fixes the
observed class at its outer boundary: a 210-result collection already admitted
as one rendered child plus an elision is fitted again under a one-token budget,
and the recurring test asserts `rendered + omitted = total` with the requery
identity retained. Even the terminal zero-depth fit therefore reports
`0 + 210 = 210` instead of silently replacing the source total with the two
admitted nodes. The focused regression passed on 2026-08-12. The
repository `bin/test` selection was separately attempted but stopped before
test execution while the concurrently changing shared source scratch
population was refused; the integrated lane owns that publication gate.
