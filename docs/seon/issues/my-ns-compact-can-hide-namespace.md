---
type: issue
status: open
severity: friction
tags: [issue, agent, architecture, cljs]
---

# `my.ns/compact!` can hide the selected namespace

## Problem

`my.ns/compact!` promises to return one indexed namespace to a compact card in
the agent's next context. The namespaces renderer has no explicit compact
selection, however. It selects only the current namespace, real require
targets, and `full-source` pins. Removing an unrelated namespace from
`full-source` therefore removes it from context instead of rendering it
compact.

The same missing representation blocks generate-code retrieval: ranked
namespaces cannot be added as compact cards without either making all of them
full or inventing fake program require edges.

## Evidence

`my.ns/select-source!` implements compacting only by removing the namespace
symbol from `:seon.agent.ctx.namespaces/full-source`. Its focused test verifies
that stored presence-set change but does not render the next namespaces block.

`seon.agent.ctx.namespaces/format-namespaces-block` builds its include set from
the current namespace, persisted require targets, and `full-source`. A symbol
removed by `compact!` has no remaining selection path unless it happens to be
current or required.

## Owner

The one `:namespaces` context block in `seon.agent.ctx.namespaces`, together
with the existing `my.ns/full!` and `my.ns/compact!` operations. The
generate-code roadmap consumes this contract but does not own another source
renderer.

## Acceptance

- The existing namespaces block owns one ordinary presence-set for explicit
  compact inclusion; `full-source` remains the full-detail subset.
- `my.ns/full!` selects the namespace in full and `my.ns/compact!` moves that
  same selection to compact without changing other namespace dials.
- Repeated calls are idempotent, an unknown namespace remains an error value,
  and warm-agent reassignment replaces generated compact/full selections
  rather than accumulating them.
- A rendered-context regression proves that an unrelated indexed namespace is
  visible in full after `full!`, visible as its schema/function card after
  `compact!`, and absent only after an explicit removal operation or assignment
  replacement.
- Embedding-ranked augmentation writes only these existing block selections;
  it adds no renderer, fake require edge, or second source payload.
