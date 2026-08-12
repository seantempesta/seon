---
type: issue
status: open
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
