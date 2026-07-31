---
type: research
status: complete
tags: [research, render, context, proof]
---

# W2a namespace-renderer implementation and proof

## Result

Commit `38c46580f` adds the `:seon.ns` family defaults in
`src/seon/render/ns.clj` and attaches them to `:seon.ns/ns` in
`resources/seon/schema/program.edn`. Distance zero renders the namespace name;
distance one renders its `ns` form, require aliases, public function signatures,
and first docstring lines; deeper distances render the exact stored function
source spans. The HTML projection carries the same definitions in a definition
list with stable per-function element ids derived by `block/surface-id`.

The distance-one AI projection of the current `seon.flow` row is 1,010
estimated tokens. Against the context-walk falsification floor measurement of
17,729 tokens, this is a 17.6× reduction and clears the ruled 15× target.

## Dependency ledger

- Datahike fork `9b3be9d59cb07d9c895af280e60eb074bb57a400`:
  concrete `d/q` and `d/pull` reads over the immutable database value. The
  renderer uses no wildcard selector and no `d/entity` access.
- clj-kondo fork `57252e07975710aa579b24f0d1b2b1e04195caa2`:
  the static program rows consumed here are produced by
  `src/seon/fn/analyzer.clj` and published by `src/seon/fn.clj`.
- Program schema: `resources/seon/schema/program.edn` defines the exact
  `:seon.ns/*`, `:seon.ns.alias/*`, and `:seon.fn/*` attributes queried by the
  renderer.
- Existing first-party render idioms: family identification and default
  resolution are `src/seon/render/walk.clj:129-168`; stable element ids are
  `src/seon/render/block.clj:73`; token estimates are
  `src/seon/ai/tokens.cljc:34`; namespace ownership is derived by
  `src/seon/cluster/agent.clj:106`.
- Quarry: the old namespace lens and its synthesis are inventoried in
  `context-walk-synthesis-2026-07-31.md`. W2a retained its compact public
  signature/docstring view, deterministic ordering, exact-source deeper view,
  honest empty namespaces, and persisted binding facts while leaving the old
  source parsing and mutable walk state deleted.

## Recurring proof

`bin/test seon.render.ns-test` ran 3 tests and 28 assertions with 0 failures
and 0 errors. The fixture is the production source population from
`seon.test-support/with-database`, so the examples query real indexed
`seon.flow` rows. A fixed-seed, 100-trial property samples every populated
namespace row, distances zero through two, and explicit token budgets from 64
through 2,048. It requires both projections to be non-empty and within budget;
distance-one AI output must also be reader-valid Clojure.

The name-only agent-namespace example directly proves the renderer emits the
name, `no definitions yet`, and its owner agent in both projections. Its family
routing assertion is deliberately marked `PENDING W1` and records today's
floor: `:seon.ns/ns` still requires `:seon.ns/source`. When W1 makes that child
optional, the assertion must flip to `seon.render.ns/render-ai` and the
route-level no-floor assertions can land.

## Scratch-cluster proof

The edit hook published current-src commit
`6a6ccffd-843f-5728-aca4-300278d66e11` with digest
`f68ac1ed6e5e4df89b88a5cb06ee533b72098b0f484d136157eeebe4ffe5070a`.
`bin/seon start w2a-proof` then forked a fresh cluster from the published
source. One `io-prepl` form obtained that cluster's immutable database value,
pulled `seon.flow` with the renderer's concrete selector, rendered both
projections at distances zero, one, and two, and measured UTF-8 bytes plus
`seon.ai.tokens/estimate`. `bin/seon stop w2a-proof` completed afterwards.

| Distance | Projection | Bytes | Estimated tokens |
|---:|---|---:|---:|
| 0 | AI | 9 | 2 |
| 0 | HTML | 97 | 24 |
| 1 | AI | 4,041 | 1,010 |
| 1 | HTML | 9,290 | 2,322 |
| 2 | AI | 31,525 | 7,879 |
| 2 | HTML | 42,154 | 10,536 |

## Contract pushback and pending edges

- The live published `seon.flow` namespace contains 48 function rows, not the
  brief's stated 104. The proof reports the database as it stands; it does not
  multiply or filter rows to reproduce the historical count. Distance one
  renders its 23 public rows, matching the quarried compact public surface;
  distance two renders all 48 exact source spans.
- The name-only family route remains gated solely by W1 making
  `:seon.ns/source` optional on `:seon.ns/ns`. The renderer itself already
  handles that row honestly and derives the owner from database facts.
- W3 still owns collapsing the legacy direct router and the walk's
  pulled-component-aware family chain. W2a registers one schema-attached
  default and proves it through the existing `seon.render.walk/projection`
  path; it does not add a registry or compatibility route.
