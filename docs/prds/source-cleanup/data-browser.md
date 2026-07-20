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
- Validation confirm-dial ON at the top level, gated by sample
  completeness: full confirm/explain runs only when the bounded sample is
  complete (no elision marker anywhere in the skeleton); an elided value
  reports `:shape-only` with the hollow-dot rendering. Explain is an
  explicit drill action running on the drilled `get-in` slice (bounded by
  the drill contract), never on the raw top value with post-hoc trimming;
  the new read-only value route also serves `/data` entity drill-down
  (one transport), with `?offset` and path length validated against
  config maxima in the parent before the child request.
- **Confirm-ON is measured safe within its measured domain** (live pod,
  2026-07-20, [[research/browser-validation-benchmark-2026-07-20]]): warm
  confirm 4–28 µs per rendered top value, ~12–14 µs per SSE re-render
  (validator memo hit rate 100% between registry changes), 100-value page
  morph 7.7 ms; prefilter-only 3–17 µs; explain+humanize warm ≤52 µs and
  invalid-only. Those numbers were measured only on values ≤ ~4k tokens
  (largest case 85 nodes / 3,956 tokens); they must not be cited against
  unmeasured sizes — full confirm/explain runs ONLY when the bounded
  sample is complete (see the size gate ruling below); an elided value
  reports `:shape-only`. Memo policy: one process-local generation cache
  whose authority is the ACTIVATED projection value itself, compared by
  `identical?` — never the mutable candidate registry, and never keyed by
  the 32-bit fingerprint alone (a hash collision would silently retain a
  stale validator generation; the fingerprint remains a display/debug
  label). No LRU, no value-level result memo. Two measured caveats for
  implementation: `me/with-spell-checking` is a no-op on Seon's open maps
  (needs `:closed`), so do not promise misspelling detection; and
  entity-shaped values validly match many open request schemas (9 for an
  agent value), so specificity ordering among valid matches is
  load-bearing.
- **Validation lifecycle (settled).** Browser validation artifacts derive
  from the last ACTIVATED projection, full stop: `register!` outside an
  eval batch updates candidate forms only; browser status, badges, and
  renderer properties change only at the next admission activation;
  `restore!` requires no cache invalidation because the generation follows
  the projection object identity; mid-batch renders always see the last
  activated projection, never in-flight candidates. The browser never
  calls `candidate-validator`/`candidate-explainer`.
- The generic bounded tree is unconditional: a value with no registered
  schema match still renders and drills normally. Schema matches only add
  labels, validation, and an optional custom renderer. Precondition for
  "unconditional": the `opaque-marker` bounded-summary fix in
  [[research/universal-data-browser-design-2026-07-20]] §6 must land
  before the migration steps that route `/data` entities,
  `generic-default-renderer` output, and every eval card through `sample`
  — today `opaque-marker` materializes the FULL `pr-str`/`str` before
  clipping, so a large record or `clj->js` value can OOM the client
  process, and the migration multiplies that exposure.
- The same precondition includes traversal work: map sampling examines only a
  bounded candidate window plus one unsampled tail sentinel, never every value
  before retaining a page. Elided maps carry renderer metadata outside their
  original `[key value]` entries, opaque or huge keys become safe partial-view
  labels, and datom values pass through the same child sampler. Commit
  `d42a88de` closes this Unit 0 contract with counted/uncounted million-entry,
  poisoned-tail, opaque-printer, huge-key, determinism, and byte-identity
  regressions.

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

## First implementation boundary

[[research/activated-schema-projection-boundary-2026-07-20]] is the
implementation handoff after Stage 1.6 freezes its schema/render overlap. Unit
1A owns only `src/seon/schema.cljc` and `test/seon/schema_test.cljs`: derive the
activated required-attribute index and projection-scoped
`candidate-shapes`/`matching-shapes`, then prove projection-identity cache
rotation, deterministic all-match ordering, bounded near matches, open-map
wrong-type rejection, and elision honesty. It does not reopen Unit 0 or touch
rendering, web routes, database access, or execution-child transport.

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
