---
type: prd
status: planned
tags: [prd, web, database, flow]
---

# Reactive render units roadmap

## Outcome

One normalized unit engine derives every active UI projection from current
database facts, recomputes only units whose observed reads can change, shares
equivalent work across subscribers, and pays no detail/render cost while a unit
is closed.

## Current state

The shared gzip Datastar feed, normalized subscriptions, runtime read capture,
identical-output suppression, bounded recent database reads, and several lazy
debug/data shells exist. The live baseline still proves page-specific
transition logic and a non-transitive declared-attribute gate can skip exact
helper-indirected reads, leaving already-open root, plan, and transcript units
stale while a fresh render is correct. Root, agent, debug, and data projections
also retain different activation/detail paths, and open debug broadcasts can
cost hundreds of milliseconds. A 2026-07-14 read-only default-cluster probe
found one open root-debug subscription retaining 657 observations, all marked
non-replayable because its render thunk re-dereferences a different immutable
database value. Lazy unit activation also invokes its producer outside read
capture and rebinds with the inactive subscription's old dependencies, so a
newly open detail can miss a change read only by that producer.

Completed earlier audits establish two constraints: active-unit reuse needs no
new library, and a Node `lru-cache` layer is justified only by measured reopen
reuse. [[research/reactive-render-source-audit-2026-07-14]] reconciles those
reports with current owners, tests, dependency sources, and the live default
baseline. It also records that the selected ClojureScript `1.12.145` source is
not yet mirrored exactly: the current reference checkout identifies itself as
`1.12.41`, so analyzer-sensitive implementation must close that grounding gap.

## Research evidence

- [[research/reactive-render-source-audit-2026-07-14]] — current dependency
  ledger, live observations, ownership graph, deletion map, and regression
  matrix.
- [[research/reactive-ui-dependency-routing-2026-07-12]] — observed-read
  invalidation design and stale-unit evidence.
- [[research/sci-render-cache-source-audit-2026-07-12]] — SCI execution and
  render-cache boundary.
- [[research/datastar-sse-render-allocation-profile-2026-07-12]] — feed/render
  allocation and latency baseline.
- [[research/clj-cljs-bounded-cache-library-audit-2026-07-14]] — active-unit
  reuse and optional bounded recent-cache decision.
- [[research/root-reactive-system-view-audit-2026-07-14]] — root layout,
  reactive correctness, and browser acceptance evidence.

## Ordered work

1. Define the fully namespaced unit coordinate and pure lifecycle data for
   activate, observe, index, invalidate, render, serialize, suppress, and close.
2. Make runtime-observed database read requests derive the conservative reverse
   candidate index; remove declared-attribute vetoes and page-specific routing.
3. Retain one active unit's plain inputs, renderer/source digest, captured read
   results, and last serialized output until its final consumer closes.
4. Normalize equivalent subscribers across tabs and prove single execution.
   Add recent-output LRU reuse only if profiling crosses its acceptance gate.
5. Move root, agent, canvas/context, debug, and data detail bodies onto the one
   lifecycle and delete every superseded transition/feed/cache path.
6. Attribute query, SCI, Hiccup, serialization, gzip, and drain cost; mechanize
   omission and latency evidence for closed, unchanged, and changed units.

## Graduation

- A helper-indirected read updates an already-open unit; an unknown read widens
  conservatively; declared attrs cannot suppress correctness.
- Unrelated transactions invoke zero corresponding queries, renderers, SCI, or
  serialization, while an affected change updates only the owning units.
- Equivalent tabs share work; final close releases active data; eviction may
  change latency but never output.
- Closed debug/data/root details construct no body or source/token work.
- Root, agent, canvas, debug, and data use one transition/feed mechanism and no
  cache retains a database/entity value.
- Focused gates plus real-browser static/interaction checks and server-side
  gzip frames prove initial render, live morph, reconnect, and cleanup.
