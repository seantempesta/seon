---
type: issue
status: open
severity: friction
tags: [issue, flow, class/n11, wave/flow-protocol]
---

# Adopt flow's read-set control and sanctioned egress

## Problem

Two `core.async.flow` mechanisms our pin ships have zero adoption, and Seon
hand-rolls weaker substitutes for both:

- a proc has **no way to stop reading an input**. Seon's backpressure is a
  bespoke buffer, which drops messages rather than declining to read them.
- the render pipeline leaves the graph through `mult`/`tap`, outside flow's
  lifecycle, instead of through the exit flow defines.

## Evidence

`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:229-233`
— `::flow/input-filter`, a predicate of cid controlling the next read set.
Zero hits in `src/`. Seon's substitute is `CountedDroppingBuffer`
(`src/seon/flow.clj:525-553`), which is a drop policy, not read-set control.

`flow.clj:221-227` — `::flow/out-ports`, external channels in a proc's
output set, which get `transition` lifecycle coordination for free. The key
is mentioned at `src/seon/render/web.clj:582` but is not a proc state key;
the actual SSE exit is `async/mult` + `tap`/`untap` around
`src/seon/flow.clj:717-764`.

Related, smaller, from the same sweep:

- `:chan-opts :xform` (`flow.clj:85`) is half-adopted — `src/seon/flow.clj:391`
  sets `:buf-or-n` only, so per-message filtering that an edge transducer
  could do sits inside `:compute` transforms that must not block.
- `::flow/cast` and `:signal-select` (`flow.clj:161,187`) are both unadopted
  and `src/seon/flow.clj:195` strips `::flow/casts`, so **no Seon proc can
  receive a broadcast at all** — there is no graph-wide quiesce or
  reconfigure signal.

Full sweep:
`docs/prds/sci-execution-runtime/research/upstream-delta-sweep-2026-07-31.md`.

## Owner

`seon.flow` — the graph builder and proc wrappers.

## Acceptance

- A saturated proc declines to read the saturated input rather than dropping
  from it, proven by a test that observes upstream backpressure.
- The render egress leaves through the graph's own output set, and stopping
  the graph tears the egress down through `transition` rather than through
  bespoke `untap` bookkeeping.
- Each item above is either adopted or recorded as a deliberate refusal with
  the reason — an unadopted feature with no stated position is what produced
  this note.
