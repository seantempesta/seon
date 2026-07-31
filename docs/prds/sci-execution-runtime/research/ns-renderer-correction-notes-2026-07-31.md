---
type: research
status: complete
tags: [research, render, context, schema, proof]
---

# Namespace renderer depth and schema-closure correction

## Result

The namespace family now implements the owner-ruled inverse detail gradient:

- distance zero renders only the namespace name;
- distance one renders the stored `:seon.ns/source` verbatim as the
  authoritative body, followed by one referenced-schema section; a source-less
  row or exact bare `(ns x)` stub instead renders its complete stored members;
  and
- distance two and deeper render the compact public card: own schema records,
  one referenced-schema section, then public function contract/docline records.

The correction also consumes landed `:seon.ns/requires` refs through the
concrete nested selector `{:seon.ns/requires [:seon.ns/name]}`. No rendered
require contains an eid map.

## Dependency ledger

- Malli 0.20.0, vendored commit
  `80138076960e7820523b4cb932c5b5d1936d4e7f`: `RefSchema` and `m/-ref` are
  defined in `reference-code/malli/src/malli/core.cljc:67-69,102`; direct
  default ref walking is implemented at `:2009-2016`; `m/walk` is the public
  traversal at `:2612-2625`; registry composition is
  `reference-code/malli/src/malli/registry.cljc:17-22,54-59`.
- Datahike fork commit
  `9b3be9d59cb07d9c895af280e60eb074bb57a400`: the renderer uses synchronous
  `d/q` and concrete `d/pull` selectors over one immutable database value.
  Global schema definitions resolve by the identity lookup-ref
  `[:seon.schema/key key]`; the identity and adjacent form are declared at
  `src/seon/schema.cljc:733-734` and
  `resources/seon/schema/program.edn:27-34`.
- Requires-to-refs integration landed in `762b2482c` and `b4b3f0f5a`.
  `resources/seon/schema/program.edn:42` declares the ref set; the indexer
  mints name-only external rows and resolves all requires before this renderer
  reads them.
- The old mechanism and adopt/drop rulings are fully inventoried in
  `old-namespace-schema-lookup-quarry-2026-07-31.md`, especially sections 3,
  7, and 8. W2a's inverted baseline and original proof conditions are in
  `w2a-namespace-renderer-notes-2026-07-31.md`.

## Implementation

`src/seon/render/ns.clj` remains the one namespace-family owner.

The schema closure:

- seeds from persisted `:seon.fn/spec` strings of exactly the rendered
  function set;
- detects only schema-position qualified refs with Malli's own walk against an
  isolated registry of built-ins plus inert qualified-keyword placeholders;
- resolves definitions from the database, never Malli's live registry;
- expands transitively in deterministic key order with a `seen` set;
- traverses own-namespace keys but omits them from the referenced section;
- omits missing or unreadable definitions rather than synthesizing rows;
- emits at most 40 referenced definitions and one ASCII honesty line only upon
  finding a 41st resolvable non-own definition; and
- uses one invocation-local key cache, including negative lookups, across the
  compact renderer's grow-one-function budget candidates.

The compact card retains every public function, including rows without a
contract. It renders the stored Malli contract unchanged, uses
`<no contract>` only when absent, and clips only the first docline at 78
characters with ` [clipped]`. The ellipsis glyph is replaced before clipping.
Private functions and stored bodies do not enter the card. Own schemas derive
from the schema key's namespace rather than a stored ownership connection.

Both AI and HTML projections use the same selected rows and section order.
HTML retains stable per-function element ids. Distance-one source is
indivisible and therefore is not member-truncated to satisfy a smaller token
budget; the grow-one-function loop applies to the compact tier, where each
candidate recomputes and counts exactly its own schema closure.

## Recurring proof

`bin/test seon.render.ns-test` passed 4 tests and 61 assertions with zero
failures and zero errors. The recurring cases prove:

- nested required namespace names, with no `#:db` eid map leakage;
- distance-zero name-only output;
- distance-one byte-preserving source prefix and GI-1 no-duplication;
- source-less namespace family routing and honest empty ownership;
- distance-two own schemas plus public contracted and uncontracted functions,
  with private functions and bodies absent;
- direct/transitive/cyclic closure, own-key traversal without emission,
  missing and malformed input degradation, and non-ref `:catn`/`:enum`
  positions;
- output independence from Malli's bound live registry;
- exactly 40 emitted definitions plus one cap line for 41 reachable schemas,
  without a false cap for 40 definitions plus a missing reference;
- explicit doc clipping without an ellipsis glyph; and
- AI and HTML budget prefixes containing function A and A's schema while
  omitting function B and B-only schemas.

The combined `bin/test seon.render.ns-test seon.render.walk-test` checkpoint
ran 10 tests and 72 assertions with zero failures and one error outside the
owned renderer paths. The protected
`seon.render.walk-test/reverse-reads-never-match-equal-non-ref-longs` fixture
transacts a run-form lookup-ref to `[:seon.cluster.run/id "long-run"]` before
that identity exists in `db-before`; Datahike refuses it as
`:entity-id/missing`. The renderer focus is green, and this note makes no green
claim for the combined gate. The original reverse-ref defect is archived at
`docs/seon/issues/archive/render-walk-reverse-refs-matches-non-ref-longs.md`;
repairing its newly invalid fixture ordering belongs to that protected owner.

## Live proof and measurement

The edit hook published code/test state through `current-src` commit
`6a6cf047-8e8f-51f2-a4b9-9ef6adc4d7b5`. The
`ns-renderer-correction` cluster forked that published database state and
nested-pulled eleven resolved `seon.flow` required namespace names.

The operator added the cluster to the already-running PID 35516, whose
application Vars predated this edit. The first probe therefore reproduced the
old renderer against the new ref facts. `load-file` then hot-reloaded only
`seon.render.ns` in that JVM; the identical probe exercised the fresh cluster
database value plus the corrected Var. This is a hot-reloaded-Var proof, not a
claim that the long-running JVM reloaded application namespaces at cluster
fork.

`seon.ai.tokens/estimate` results:

| Distance | Projection | Before | After | Semantic after-check |
|---:|---|---:|---:|---|
| 1 | AI | 1,010 | 470 | exact source prefix; no duplicated member block |
| 1 | HTML | 2,322 | 539 | source node; no definition list beside real source |
| 2 | AI | 7,879 | 3,331 | 65 own schemas; public contracted + uncontracted; no private/body/eid map |
| 2 | HTML | 10,536 | 4,586 | compact schema/function membership with stable ids |

The before values are W2a's recorded `seon.flow` measurement. The owner
explicitly accepted the 1,010/7,879 AI values as this correction's baseline.
The after values come from the live hot-reloaded probe described above with a
100,000-token ceiling, so no budget omission affected the comparison.
