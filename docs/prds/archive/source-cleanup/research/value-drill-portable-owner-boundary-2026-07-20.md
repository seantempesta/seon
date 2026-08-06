---
type: research
status: complete
tags: [research, rendering, runtime, architecture]
---

# Value-drill portable owner boundary (2026-07-20)

## Decision

Unit 1G cannot call the Unit 1F producer from the JVM today. The producer is
`seon.render.value/drill-value` in a `.cljs` file, while `seon.host` is a
Clojure process. Copying descent, paging, validation, or sampling into
`seon.host` would create two projection mechanisms; returning the raw value to
the pod would violate the local-identity and bounded-work laws.

The smallest one-mechanism repair is to **promote the existing
`seon.render.value` owner from `value.cljs` to `value.cljc`**, not extract a
second “kernel” namespace and not translate the algorithm inside the host.
The file keeps the same namespace, public schema keys, marker vocabulary, and
function names. Platform differences are direct reader-conditional branches
inside the one owner, following the repository's `retry.cljc`,
`db/transport/uds.cljc`, and `schema.cljc` precedents.

The promotion needs one contract adjustment before Unit 1G: the portable drill
producer must consume a fully resolved pure-data sampling policy, not call the
CLJS-only `seon.config` namespace. Extend the existing
`:seon.render.value/effective-limits` map with the display bounds needed by the
sampler (`value-max-depth`, `value-max-string`, and `value-shape-sample`, in
addition to page size and the three drill caps). The existing
`seon.config/effective-value-drill-limits` remains the sole normalizer. It
derives and monotonically narrows the complete policy in the pod; the exact
ordinary map crosses the protocol and both runtimes validate it before work.
`drill-value` then takes the live value plus the decoded drill request and has
no runtime configuration dependency.

The ordinary AI/HTML render entrypoints may continue to accept the config
singleton. Their thin policy-resolution step is CLJS-only; the structural
walker, drill producer, deep validators, schema projection, and public drill
schemas compile on both platforms in the same namespace. This is not a wrapper
or a second algorithm: configuration resolves data once, and the one portable
owner performs all projection work.

## Why the alternatives fail

### Convert the file without removing the config edge

`src/seon/config.cljs` has no JVM sibling. It owns Aero/Node manifest reading,
platform state, database projections, and UI render configuration; promoting
that whole namespace merely to make three sampler getters available would
greatly widen Unit 1G. Adding a small `config.clj` sibling with copied defaults
would create two configuration authorities. The portable producer therefore
accepts resolved policy data.

### Move drill code into an existing portable namespace

No existing `.cljc` owner has the right semantics:

- `seon.schema` owns schema registration and activated projections, not raw
  value traversal;
- `seon.ai.tokens` owns token estimation and bounded printing, not collection
  paging or opaque markers;
- `seon.db.protocol` owns wire admissibility, not rendering; and
- `seon.render.schema` is currently a dependency-light CLJS data-form owner
  for canvas/render attributes and is explicitly not a behavior engine.

Putting Unit 1F into any of them would blur ownership and leave the existing
`seon.render.value/sample` walker in place as a second mechanism.

### Add `seon.render.value.core` or translate into `seon.host`

A new portable leaf would either own old `:seon.render.value/*` schemas from a
different code namespace, violating schema colocation, or force an atomic
public-key migration across the renderer and UI for no semantic gain. Leaving
`value.cljs` as a facade would also preserve two places that appear to own the
walker. A direct host translation is worse: work bounds, marker bytes, schema
status, and omission behavior could drift independently.

### Send the raw value to the parent

This materializes potentially hostile data before sampling, cannot preserve
SCI/JavaScript object identity, cannot carry host objects through Transit, and
turns the pod into a second live-value authority. It remains forbidden.

## Dependency ledger

| Dependency or mechanism | Selected revision | Grounding | Portability consequence |
|---|---|---|---|
| Unit 1F | `9c22de90`; graduation record `cf083352` | `src/seon/render/value.cljs:1025-1520`; focused tests in `test/seon/render/value_test.cljs` | One current descent/page/validation implementation exists, but only CLJS can load it. Move it; do not reproduce it. |
| Clojure | `1.12.0` | `deps.edn:6,20`; core `take`, transducers, `reduce`, `reduced`, immutable maps/vectors | The traversal and paging primitives are portable without a dependency adapter. |
| ClojureScript | `1.12.145`; vendored head `946d75f3483c0c8e784e6668bff2c71a25619a77` | `deps.edn:128`; `reference-code/clojurescript/` | Numeric negative-zero/finite checks and JS object/error inspection need explicit CLJS branches. |
| Malli | `0.20.0` | `deps.edn:7,21`; `malli.error` use in `value.cljs` | Schemas and humanized explanation code are available on both platforms. Registration stays in `seon.render.value`. |
| Orchard inspect | `c462a25d97988f1af51e8181265c43ec9b7d3d6f` | `reference-code/orchard/src/orchard/inspect.clj:44,96-141,150-200` | `drop` plus head-one paging and path descent are already expressed in portable Clojure; no platform copy is necessary. |
| Token owner | repository HEAD `793a8ea6`; `src/seon/ai/tokens.cljc` | `clip-str`, `bounded-pr-str`, `chars->tokens` | Existing dependency is portable. Drill ranking prints only already-bounded sampled nodes; JVM's current full-`pr-str` branch must never receive an unsampled hostile raw value. |
| Schema owner | repository HEAD `793a8ea6`; `src/seon/schema.cljc:587-765` | `shape-candidate-limit`, `candidate-shapes`, `matching-shapes`, `explain-shape` | The same schema projection API compiles in Bun and JVM. Projection-population parity remains an integration proof requirement. |
| Config owner | Unit 1D plus `c1618e22`; `src/seon/config.cljs:1164-1271` | Render cap getters and `effective-value-drill-limits` | CLJS-only. It resolves one complete effective policy; it is not required by the portable producer. |
| CLJC precedent | `src/seon/retry.cljc`; `src/seon/db/transport/uds.cljc`; `src/seon/schema.cljc` | Reader-conditional numeric, exception, interop, and platform-import branches | Proves direct platform translation inside one maintained namespace is the repository idiom. |

## Current call graph and platform obstacles

The current graph is:

```text
config singleton
  -> seon.render.value/render-options
  -> sample/sample*
  -> drill-value
  -> schema-projection + bounded-drill-result?

seon.execution child -> seon.eval/lookup-result -> drill-value (planned)
seon.host JVM        -> retained SCI value      -> cannot require namespace

```

The Unit 1F pure region is `drill-failure` through `drill-value`, but it calls
the pre-existing `sample`, `opaque?`, marker projection, `truncated?`, and
schema projection. Extracting only descent/paging would still force a second
sampler. The promotion must therefore include the full bounded structural
walker and its marker/deep-validator helpers.

The source audit found a small, explicit platform surface:

- `js/Number.isSafeInteger`, `js/Number.isFinite`, and negative-zero checks;
- record constructor fields `.-cljs$lang$ctorStr`/`.-name`;
- `object?` and the literal `"js/Object"` marker;
- `catch :default` and JavaScript error `.-message`; and
- the compile-time require and calls to `seon.config`.

The first four get reader-conditional direct implementations:

- JVM safe integers require integer semantics within JavaScript's safe range
  for wire equality; floating path keys must be finite; JVM has no distinct
  negative-zero integer, while `Double/-0.0` is rejected by raw-bit or
  reciprocal sign detection;
- JVM record/class labels come from bounded class names without invoking
  arbitrary printing;
- JVM non-ordinary host detection names records, functions, Datahike handles,
  SCI vars, and other non-collection objects with bounded fixed markers; and
- exceptions use `Exception`/`.getMessage` versus CLJS `:default`/`.-message`.

The config edge is removed from the portable drill path by data, not by a
platform stub. CLJS-only text formatting (`visible-whitespace`, verbatim AI
formatting, width selection) may retain a reader-conditional config require
and CLJS-only policy resolver, because JVM Unit 1G never calls those views.

## Contract change before transport

The current `effective-limits` contains path segments, raw encoded path bytes,
maximum realized items, and page size. `drill-value` still accepts the full
config singleton because `sample` additionally reads depth, string, and shape
sample caps. That hidden dependency prevents identical producer calls.

Add these existing configuration keys to the closed effective map:

```clojure
:seon.config.render/value-max-depth
:seon.config.render/value-max-string
:seon.config.render/value-shape-sample

```

The normalizer copies the host values and may only narrow operation-provided
values. The resulting request is the complete immutable sampling policy.
`drill-value` becomes a two-argument portable function:

```clojure
(drill-value live-value drill-request) ; -> closed drill-result

```

It constructs `sample*` options solely from `effective-limits`: page size owns
the page's max items/keys/map visits; the three added fields own depth, string,
and shape work. No route or runtime invents defaults. Both protocol peers
validate the exact closed map, and a child/JVM policy mismatch refuses rather
than silently reclamping.

The HTTP-only encoded-byte cap remains in the map because the child repeats
the decoded path-size surrogate and frame consistency checks, but only
`seon.web.serve` measures raw percent-encoded UTF-8 bytes.

## Schema registration and cycles

Renaming `src/seon/render/value.cljs` to `.cljc` preserves
`seon.render.value` as the registration namespace. Public keys do not move and
there is no same-name `.cljs`/`.cljc` pair.

Portable requires are acyclic:

```text
seon.render.value.cljc
  -> seon.schema.cljc
  -> seon.ai.tokens.cljc
  -> malli.error / clojure.string

```

`seon.schema` and `seon.ai.tokens` do not require `seon.render.value`.
`seon.config.cljs` references the value schema keys symbolically in Malli
forms but already does not require `seon.render.value`; value's portable core
must likewise not require config. The CLJS-only view branch can receive
already-resolved display policy from its caller or use a reader-conditional
CLJS require without entering the JVM graph.

The JVM `seon.host` then requires `seon.render.value` directly. It must not
copy registrations into `seon.host`, and the execution frame schemas should
reference the one registered `:seon.render.value/drill-request` and
`:seon.render.value/drill-result` keys.

## Recommended implementation sequence

1. Freeze and review Unit 1F at `9c22de90` as the behavior baseline. Record
   exact CLJS projection bytes and work counters for its canonical fixtures.
2. Extend the existing effective-limit schema and
   `seon.config/effective-value-drill-limits` with the three sampling fields;
   prove normalization, monotone narrowing, idempotence, and closed-map
   rejection.
3. Change `drill-value` to consume only live value plus the complete request.
   Keep one `sample*` call and construct its options only from effective data.
4. Rename `src/seon/render/value.cljs` to
   `src/seon/render/value.cljc` atomically. Add the minimal reader conditionals
   for numeric identity, opaque classification/labels, and exception messages.
   Do not leave a `.cljs` sibling.
5. Run the identical Unit 1F fixture table as CLJS tests and a new focused JVM
   test namespace. Compare complete returned data/`pr-str` bytes for ordinary
   portable values; separately assert honest fixed-marker behavior for
   platform-native opaque values.
6. Require the portable owner from `seon.host` and add the bounded
   process-local eval-id slot ruled in
   [[../../../seon/issues/retain-live-eval-values-in-the-owning-jvm-host]].
   Only then add Unit 1G frames and dispatch.
7. Freeze Bun and JVM artifacts together and run protocol, lifecycle,
   eviction, tier-change, and live paging proofs before the route unit starts.

Steps 2-5 are a portability prerequisite unit, not transport implementation.
They should commit coherently before execution/host files are opened.

## Owned and protected paths

Portability prerequisite ownership:

- atomic rename `src/seon/render/value.cljs` to
  `src/seon/render/value.cljc`;
- `test/seon/render/value_test.cljs` for preserved CLJS behavior;
- one focused JVM test under the existing writer/host test surface;
- `src/seon/config.cljs` and `test/seon/config_test.cljs` only for the complete
  effective-policy normalizer; and
- localized `src/seon/render/AGENTS.md` only to update the durable filename if
  required by the rename.

Protected until the portability commit is frozen:

- `src/seon/execution.cljs`, `src/seon/execution/host.cljs`, `src/seon/host.clj`,
  and host context: Unit 1G transport/retention must consume the portable
  owner, not influence its design;
- `src/seon/web/**`, route data, and UI: no HTTP codec or controls;
- `seon.schema`, `seon.ai.tokens`, and `seon.db.protocol`: reuse as-is;
- `src/seon/repl/internal.cljc`, all unrelated SCI/AI work, and the B2 cache
  directories; and
- any new render-value namespace, compatibility facade, platform copy, or raw
  parent sampling path.

Because the rename changes a widely required build input, the orchestrator
must freeze render/config editors, stage the exact old/new paths explicitly,
and run both CLJS and JVM gates before releasing Unit 1G.

## Shortest falsifiers

1. Require `seon.render.value` under `clojure -M:writer:host` and under the
   Shadow CLJS build. Assert one source file and one registration population;
   fail if both `.cljs` and `.cljc` exist.
2. Feed the same ordinary nested map/vector/sequence/set values and complete
   request to both platforms. Assert equal result data and identical `pr-str`
   bytes, including status order and every omission marker.
3. Instrument a million/logical-infinite input. Both platforms visit no more
   than `offset + page-size + 1`; poison the next item and every rejected
   request's first lookup/realization.
4. Table-test zero, negative zero, finite fractional/negative map keys, unsafe
   integers, NaN, and infinities through EDN/Transit identity. Both platforms
   admit or refuse the same path value; vector indices remain non-negative
   safe integers only.
5. Exercise a CLJS object/record/Datahike handle and JVM SCI var/record/Datahike
   handle. Each becomes a bounded fixed marker without invoking its printer;
   ordinary portable children remain byte-identical.
6. Put a 100 MiB string inside one value and a hostile `toString`/`toString`
   equivalent on an opaque object. Assert neither platform materializes or
   invokes it before the cap and the work counter, not only output length,
   stays bounded.
7. Give the normalizer absent, equal, narrower, wider, and unknown operation
   fields. Assert one closed complete policy, monotone clamping, idempotence,
   and byte identity across the transmitted Transit map.
8. Remove or skew one schema projection in the JVM fixture and prove the
   parity gate fails rather than silently emitting different schema statuses.
9. Compile the focused JVM test through `bin/test-writer`, the focused CLJS
   value/config tests through `bin/test-cljs`, then run the relevant complete
   suites. A CLJS-only green result does not close portability.
10. After Unit 1G consumes the owner, sample one retained Bun value and one
    retained JVM value with parent lookup/raw-transfer spies fixed at zero;
    retire both and observe the same honest unavailable result.

## Earliest implementation boundary

The earliest dependency-ready boundary is **portable-owner promotion before
transport**: extend the complete effective sampling policy, make
`drill-value` configuration-independent, atomically promote
`seon.render.value` to `.cljc`, and prove cross-platform output/work parity.
Only that frozen commit makes the recommended Unit 1G implementation honest.
