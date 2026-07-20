---
type: prd
status: active
tags: [prd, web, agent, architecture]
---

# Universal data browser PRD

## Owner rulings (2026-07-20)

- One schema-aware rendering mechanism for every value shown to a person or
  agent; the generic schema-aware tree is the DEFAULT for any value without
  an explicit `:seon.render/html` (or `:seon.render/ai`) registration, and
  an explicit registration always overrides — extension is pure addition.
- Renderers register as schema `:properties` (metadata travels with the
  schema; symbol indirection keeps code hot-swappable).
- Validation status renders green/red; hover reveals the humanized
  explanation with zero round-trips; click dives via the existing
  `<details>` + path re-sample.
- Validation confirm-dial ON at the top level; hover payloads share the
  standard token cap; the new read-only value route also serves `/data`
  entity drill-down (one transport).
- The generic bounded tree is unconditional: a value with no registered
  schema match still renders and drills normally. Schema matches only add
  labels, validation, and an optional custom renderer.

## Corrections from adversarial review (2026-07-20)

1. **Matching and diagnosis are different operations.**
   `schema/matching-shapes` returns only schemas the value validates against
   and is the only input to custom-render dispatch. A separate
   `schema/candidate-shapes` returns bounded structural near-matches for the
   generic browser's diagnostics. This makes a missing-required-key value
   show the intended red explanation without invoking a renderer that expects
   valid data. No match is an ordinary generic-browser state, not an error.
2. **A live eval value belongs to its execution child.** The web UI host is
   the parent Bun client process. Bun child IPC can clone supported values,
   but it does not share object identity or a child's `globalThis.result`
   slot. Seon's stricter existing execution IPC sends Transit strings and
   accepts only eager ordinary data. Deep eval-value drill therefore becomes
   a bounded child request/response over the existing execution protocol; the
   child runs `lookup-result` plus path sampling and returns only the ordinary
   `render-html-data` projection. The parent never dereferences
   `result/<id>` itself.
3. **Unavailable is honest.** If the owning child was retired, restarted, or
   evicted the result, the route renders the existing prior-session/eviction
   error value and offers the recorded eval source for recomputation. It does
   not persist arbitrary live values merely to make browsing appear durable.
4. **Entity drill is authority-owned.** `/data` entity paths sample from an
   acquired immutable database value in the parent; eval-result paths sample
   in the owning child. They share one browser projection and HTTP route but
   translate directly to their two concrete producers rather than inventing
   one umbrella value store.
5. **Authorization is explicit.** An eval drill request must join the eval to
   the route's agent id before the parent addresses that agent's child. A
   read-only route does not waive cross-agent ownership checks.

## Design authority

[[research/universal-data-browser-design-2026-07-20]] — grounded design,
amended by the corrections above where they disagree. Fingerprinting
unification (`schema/matching-shapes`), validation pipeline
(explain only on invalid, memoized on projection fingerprint, malli
`dev.pretty` + spell-checking recipe), the 8-step path-limited migration,
and the corrected expansion transport: `/call` capability gate stays
closed; expansion uses one new core read-only route
`/agent/{id}/value?path=&offset=` with orchard-style elided-tail paging.
Prior grounding: [[research/schema-aware-inspector-2026-07-20]].

## Problems this closes

- Three value-rendering leaks bypassing the one mechanism (unbounded
  pprint fallback `render.cljs:691`, `/data` raw `pr-str`, eval result
  cards as EDN strings).
- `my/plan/internal.cljs:1831,1940,1970` hand-built tree — becomes a
  two-property registration + one fn; bespoke path deleted.
- "panel" vocabulary dissolves into the one mechanism (vocabulary PRD).
- Future custom renders no longer require machinery changes.

## Acceptance

The design doc's §D extensibility example implemented as the proof: the
plan tree registered, the bespoke path deleted, and one NEW schema given a
custom renderer in a few lines with no machinery edit. Validation status
visible on the agent page and `/data`; hover explanation for one
deliberately-invalid value; a no-schema value rendered and drilled through
the same generic tree; parent-to-child drill-through paging on a large live
eval value; honest unavailable rendering after that child is retired.
Full CLJS suite plus live cluster proof.

## Sequencing

Implements after stage 1 (it touches render/schema files stage 1 also
touches); the stage-2 rename sweep then retires "panel" prose. Adversarial
review gates implementation start.
