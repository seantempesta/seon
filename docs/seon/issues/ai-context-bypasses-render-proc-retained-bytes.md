---
type: issue
status: open
severity: friction
tags: [issue, render, context, performance]
---

# Give AI context rendering the retained-bytes render path

## Problem

The `:seon.render/ai` projection uses the same namespace-owned renderer
selection as HTML, but prompt construction performs a fresh uncached walk. Two
identical context renders at one database value execute every renderer twice.

## Evidence

- A context probe rendered an agent whose walk reached data owned by another
  namespace. Both calls produced identical context bytes and the same renderer
  sequence, but each call invoked all 12 renderers again.
- Removing the reached namespace's renderer caused its declared schema
  producer/floor to serve the value; the referring namespace's renderer never
  ran. Ownership is shared correctly, while retained-byte economics are not.
- `src/seon/cluster/prompt.clj:1-8,40-69` explicitly describes and performs one
  fresh, deliberately uncached `seon.render/walk` for every prompt.

## Owner

The shared render-proc retained-fragment boundary and
`seon.cluster.prompt/prompt`.

## Acceptance

Two context renders at an unchanged database value return identical retained
bytes without invoking any renderer a second time. A one-block fact change
invokes and serializes only that block while preserving the namespace-owned
selection and ambiguity rules used by HTML.
