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

## Design authority

[[research/universal-data-browser-design-2026-07-20]] — settled contract,
fingerprinting unification (`schema/matching-shapes`), validation pipeline
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
deliberately-invalid value; drill-through paging on a large structure.
Full CLJS suite plus live cluster proof.

## Sequencing

Implements after stage 1 (it touches render/schema files stage 1 also
touches); the stage-2 rename sweep then retires "panel" prose. Adversarial
review gates implementation start.
