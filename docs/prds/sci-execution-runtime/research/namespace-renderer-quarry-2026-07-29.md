---
type: research
status: complete
tags: [prd, agent, context, render, quarry]
---

# Namespace renderer quarry

## Dependency ledger

- Seon source at `4193c3f919ab54d31bdfc4208953ccb4ef8297b0`; the surviving birth owner is `seon.cluster.agent/creation-tx`, and the landed distance view is `seon.render.agent/namespace-ai`.
- Datahike source at `reference-code/datahike` revision `9a7a9ef10a954c32075e60d929f9101a9ac8abd9`; creation relies only on one transaction's tempid/ref semantics, not on quarry acquisition machinery.
- Quarry history: `844ec4483` first derived required-namespace API views from real require edges, `4b46a2cb2` made full source authoritative and kept an empty current namespace visible, and `2eeb3bd95` collapsed density to current-full plus required-compact.

## Findings

The old block selected the current namespace and its real require edges, with `:refer` narrowing the callable surface and aliases selecting the whole public surface; its final density rule was exactly current namespace full and required namespaces compact (`src-old/seon/agent/ctx/namespaces.cljc:169-201`, `:695-709`, `:954-989`).

Full meant the stored namespace source without duplicated member listings, bounded by explicit namespace demarcations and a never-omit empty-workspace form; compact meant inert schema definitions plus public, schema-complete callable contracts and first doc lines, never pretend executable bodies (`src-old/seon/agent/ctx.cljc:1405-1459`; `src-old/seon/agent/ctx/namespaces.cljc:739-823`, `:1008-1023`, `:1240-1292`).

The reusable idea is distance, not the old hand-built split: the agent's own namespace is the focal value rendered in full, each real require edge spends one hop and reaches that namespace through its partial/default lens, and deeper code appears only when further distance is deliberately spent; the quarry's async acquisition, stored density dials, catalog overlay, clipping, and separate compact renderer are old-system machinery and are not carried forward.
