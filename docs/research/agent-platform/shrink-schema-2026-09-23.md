---
type: research
status: read-only shrink research; no source edited
created: 2026-09-23
lane: shrink-schema (Opus 5.5)
tags: [agent-platform, shrink, malli, schema, projection, datahike, hasch, core.cache]
---

# Shrinking `seon.schema`: what Malli, Datahike and hasch already do

Subject: `src/seon/schema.clj` (4,398 lines) and `src/seon/schema/{admission,datahike,edn}.clj`,
`internal.cljc` (487 + 565 + 659 + 521). Total **6,630 lines**. Line spans come from the clj-kondo
`var-definitions` pass over HEAD `0d1bbfc5a`. Caller counts come from clj-kondo `var-usages`
over `src` and `test`. Seams are cited at the pinned forks: malli `25710a67`
(`reference-code/malli`), datahike `684d3290` (`reference-code/datahike`), and
`org.replikativ/hasch 0.4.100`, which is already on the classpath (`clojure -Spath`).

**Verification boundary.** The probes below ran read-only through MCP `eval_clj` (JVM mode, `default`)
in a throwaway namespace `shrink-schema-probe`. They redefined nothing and wrote nothing. **The
`default` JVM does not run HEAD's `seon.schema`.** `#'seon.schema/build-projection` is loaded
from line 2071, but it sits at line 2172 at HEAD. `projection-with-declarations` and
`deleted-predicate-functions` do not resolve, and `load-projection` has one arity only. So
`default` runs a `schema.clj` from before `1625fb9bc`, although its boot (03:16:52Z) came after
`ea048d617`. The measured costs therefore describe the pre-`1625fb9bc` code, under armed contracts.
Every "after" number is a from-parts measurement of the proposed composition. None is a measurement
of an implemented slice. The orchestrator should check which published commit `default` adopted,
because a lane may be reading an old projection builder.

## 0. The one finding that changes the design

The projection is `forms` + `contracts` + a compiled registry. On `default`:

| Operation | Measured | Notes |
|---|---:|---|
| `load-projection` from rows (cold, whole build + validation + indexes) | **1,012 ms** | profile: `canonical-value-string` ×157,755 = 1,072 ms inclusive; `assert-complete-contract!` ×3,388 = 952 ms inclusive; `build-projection` 924 ms |
| `projection-fingerprint` alone over the carried projection | **364 ms** | a 32-bit `String.hashCode` XOR over a hand canonical string |
| Two `d/q` for `[key form]` and `[sym spec]` rows | 12 + 11 ms | 5,356 rows |
| `edn/read-string` of all 5,356 row strings | 25 ms | result `=` the carried forms and contracts (both `true`) |
| `compilable-form` over all 5,356 (armed) | 66 ms | predicate binding + component widening |
| A plain `mr/lazy-registry` realising **all** 5,356 declarations | **25 ms** | `(mr/lazy-registry (m/default-schemas) provider)`, `registry.cljc:81` |
| The same registry answering ONE key (`:seon.error/base`) plus its validator | **0.55 ms** | realises 179 of 5,504 entries |
| `mr/fast-registry` copy of 5,504 entries | 0.88 ms | the A1 spec's "HashMap copy" is not a cost |
| Reverse dependents of `:seon.error/base` by `d/q` over the stored `:seon.schema/references` | 3.2 ms | 278 dependents, `=` the carried reverse index |
| `canonical-reference-graph` recomputed from forms | 49 ms | `=` the stored graph |
| `hasch.core/uuid` of the whole forms map | 16 ms | cross-platform EDN content hash, already loaded by Datahike |
| `(hash forms)` fresh / cached | 0.84 / 0.38 ms | Clojure caches `hasheq` on the persistent map |

**Compilation is under 3 % of a cold build.** The time goes into the fingerprint (about 36 %) and a
validation walk re-run at *load* over rows the writer already admitted. HEAD's own
`materialize-projection` states the rule: "Population policy was proved before publication"
(`schema.clj:2646`). A projection built from committed rows therefore needs no validation, no
fingerprint and no incremental replacement. It needs a lazy registry over the parsed rows:

```clojure
(defn load-projection [db]
  (let [forms     (into {} (map (fn [[k s]] [k (edn/read-string s)]))
                        (d/q '[:find ?k ?s :where [?e :seon.schema/key ?k] [?e :seon.schema/form ?s]] db))
        contracts (into {} (map (fn [[f s]] [f (edn/read-string s)]))
                        (d/q '[:find ?f ?s :where [?e :seon.fn/sym ?f] [?e :seon.fn/spec ?s]] db))
        decls     (merge forms contracts)]
    {:seon.schema.projection/forms forms
     :seon.schema.projection/function-contracts contracts
     :seon.schema.projection/registry
     (mr/lazy-registry (m/default-schemas)
       (fn [id scope] (when-let [d (get decls id)]
                        ((if (symbol? id) m/function-schema m/schema)
                         (widen-component-children d) {:registry scope ::m/disable-sci true}))))}))
```

That is about 50 ms cold, in proportion to the row count. A key costs nothing until it is asked
for, and then it costs about 0.5 ms. Nothing needs to be retained across commits, because rebuilding
costs about as much as replacing. The db.clj memo, keyed by Datahike's per-attribute revisions,
makes an unrelated commit reuse the same value (`db.clj:1286-1309`, over
`DH/db.cljc:423-441`). **The 642 lines `1625fb9bc` added, and the incremental family around them
(about 620 lines, R19 + R18), answer a question this shape does not ask.** Malli's `RefSchema`
resolves its target lazily through `-memoize` at first use (`core.cljc:1953-2025`, `-deref`/`rf`).
So a fresh lazy registry per population is correct by construction, with no reverse-closure
invalidation.

Validation stays, but at the **writer**: an admitted declaration, or one being removed,
validates itself and its reverse dependents. The dependents come from the stored
`:seon.schema/references` (`db/index true`, cardinality many; verified in `d/schema`) and
`:seon.fn.arity/input-refs`. Both are facts the program already writes.

## 1. Region table

Line counts are file spans (docstrings included). Callers are external `src`/`test` usages from
clj-kondo; "dead" means zero first-party callers in `src` and `test` (SCI agents may still call a
public var).

| # | Region (vars) | Lines | What it does | Malli / Datahike / library seam | Replacement | Deletable | Callers | Risk |
|---|---|---:|---|---|---|---:|---|---|
| R1 | load-cycle `delay`s `:40-59` | 24 | late-resolves 8 `seon.schema.datahike`/`edn` vars | — | goes with the regions that call them | 20 | internal | none |
| R2 | reference graph: `direct-references*`, `reference-cycle`, `assert-acyclic-references!`, `reference-registry`, `canonical-reference-graph`, `reference-candidate-keys`, `direct-reference-keys-in`, `direct-references`, `dependent-schema-keys`, `schema-removal-blockers` | 225 | the canonical-key dependency graph, its reverse closure, cycle refusal and removal blockers | `m/walk` with `::m/walk-refs`/`::m/walk-schema-refs` (`core.cljc:2611`, `:2012`, `:2086`) already does the walk. **The graph is stored**: `:seon.schema/references` is written by `canonical-schema-rows` (`schema.clj:4020`); `d/q` answers dependents in 3.2 ms | keep `direct-references*` (19) for candidate declarations; dependents and blockers become one `d/q` over `:seon.schema/references` + `:seon.fn.arity/input-refs`/`output-refs`; the cycle check runs only at the writer, over the changed key's closure | ~170 | `direct-references` 3 src; `dependent-schema-keys` 1 src (`turn`); blockers 0 src | recursive reverse closure needs a Datalog rule or loop; the cycle check must keep its "names the path" refusal |
| R3 | predicate resolution: `predicate-symbols-in`, `runtime-predicate`, `loaded-predicate-var`, `converged-predicate-var`, `predicate-functions-in`, `with-predicate-functions`, `bound-forms`, `register-core-predicate!`, `core-predicate-registered?`, `deleted-predicate-functions` | 208 | replaces `[:fn sym]` symbols with callables before compile; refuses unresolved ones; reloads a stale namespace; supplies refusing callables for deleted predicates | **the fork already does it**: `m/eval` resolves a loaded qualified symbol without SCI and without loading code (`core.cljc:2889-2894`, `:2898-2909`, fork commit `3517a3cd`); with `::m/disable-sci true` an unloaded or deleted predicate refuses `::m/sci-not-available`. Probe: `m/form` keeps the symbol, validator works, `seon.nope/x?` and `zzz.unloaded/x?` refuse, nothing loads | fork one-liner: `-loaded-qualified-value` returns `[target-var]` not `[@target-var]` (the Var is `IFn`, so a re-evaluated `defn` stays live, the property `runtime-predicate`'s docstring requires); compile with `{::m/disable-sci true}` | ~190 + the 48 `register-core-predicate!` src sites (mechanical sweep, separate) | `predicate-functions-in` 14 src; `register-core-predicate!` 48 src | `converged-predicate-var`'s reload-on-miss goes: a predicate added after boot resolves after publication's `require :reload` (AGENTS "a reload is `require :reload`"), which must precede recompilation |
| R4 | `widen-component-children`, `compilable-form`, `canonical-definition` | 234 | preparation choke point (predicate binding, `:gen/gen` binding, component widening) and its inverse back to durable EDN | fork resolves a symbol `:gen/gen` itself (`generator.cljc:466-484`, commit `25710a67`); with R3 the stored form is never rewritten, so no inverse is needed: `m/form` of a symbol-predicate schema IS the authored form (probe) | `compilable-form` becomes `widen-component-children` alone (32); `canonical-definition` (111) is deleted | ~200 | `compilable-form` 6 src; `canonical-definition` 2 src (`fn`, `fn.schema-shape`) | `fn.clj` uses `canonical-definition` to store contracts: it must store the authored form it already has |
| R5 | `compiled-function-arities`, `with-compiled-cache`, `projection-cache`, `projection-cache-value`, `function-arities-in` | 75 | per-projection holder for derived products | the audit (`dependency-already-does-it-audit-2026-09-23.md` row 22) rules it KEEP as Malli's own pattern (`-cached`, `core.cljc:353`) | keep; the `validation-node-limit` read (`:377-381`) moves to its reader | ~8 | 23 src | none |
| R6 | refusal builders: `unresolved-reference-in`, `unresolved-reference-refusal`, `refuse-unresolved-reference!`, `assert-config-display!`, `render-contract-refusal!`, `refuse-projection-source`, `pulled-selector-refusal` | 196 | hand-built flat refusal maps with steering prose | Malli supplies the cause: `::m/invalid-schema {:schema k}` for a missing ref (read by `internal/missing-schema-reference`), and explain/`me/error-message` (`error.cljc:288`) for value errors; `seon.error.refusal/diagnostic` already exists | one `(refuse! operation member expected offending message)` over `refusal/diagnostic`; the referrer scan in `unresolved-reference-refusal` becomes the `:seon.schema/references` query | ~110 | internal + `cluster-test` | refusal shapes are asserted by `refusal_grammar_test`: keep the keys, drop the duplicated `:seon.schema/error`+member twins |
| R7 | `projection-registry` | 56 | serial eager compile of every non-retained declaration, then `fast-registry` copy | `mr/lazy-registry` (`registry.cljc:81-94`) + `composite-registry` (`:54`); eager compile exists to validate, which belongs to the writer | the 8-line lazy registry in §0 | ~48 | internal | a malformed stored form refuses at first use, not at load; admission prevents it being stored |
| R8 | `canonical-value-string`, `canonical-data-string`, `canonical-data-fingerprint`, `portable-string-hash`, `projection-fingerprint*`, `replace-fingerprint-entry`, `fingerprint-with`, `reusable-projection-fingerprint`, `byte-array?`, `sha-256` | 153 | a hand canonical EDN encoder, a 32-bit XOR fingerprint of the population, and wrappers of `seon.id/sha-256` | **hasch** (Datahike's own dependency, `DH/writing.cljc:21`, `DH/tools.cljc:5`) is a cross-platform, order-independent EDN content hash (`hasch.core/uuid`, 16 ms vs 72 ms for `canonical-data-string` over the forms map); a cache key needs no content hash at all: Datahike's attribute revisions (`DH/db.cljc:423-441`) or the forms map itself (`hash` is cached) | fingerprint readers (`render/walk.clj:388`, `sci/eval.clj:2354`, `render/web.clj:843,908,1535`) key by the projection value or the revision key; content digests call `hasch.core/uuid`; `sha-256`/`byte-array?` → `seon.id/sha-256`/`bytes?` | ~140 | `canonical-data-string` 8 src; `sha-256` 15 src; fingerprint 5 src sites | RESET NEEDED if any stored digest was computed with `canonical-data-string` (`program`, `schema.edn/declaration-digest`); a 32-bit XOR is collision-prone today, so the change is a correctness gain |
| R9 | ambient transport + registration: `*candidate-forms-overlay*`, `*projection*`, `*projection-state*`, `*packaged-forms*`, `*registration-admission-source*`, `candidate-forms`, `declaration-population`, `call-with-forms`/`-projection`/`-projection-state`, `active-projection`, `update-candidate-forms!`, `register!`, `unregister!`, `contribute-candidate-forms!`, `form-string`, `activate-projection!`, `activate!`, `current-projection`, `handed-projection`, `entity-catalog`, `current-keys`, `snapshot`, registration delta (7 vars), `restore!`, `register-all!`, `registered-schemas`, `registered?`, `schema-definition`, `shape-projection`, `identity-only-projection`, `candidate-shapes`, `matching-shapes`, `explain-shape`, `schemas-in-namespace`, `clear-all!` | 441 | thread-bound projection transport and the `register!` overlay | "values carry their world"; the audit's row 20 and A1-12 already schedule it; the value carries the projection (`db/carried-projection`) | arguments; `register!`/`unregister!` are pure `(projection-with-schema p k form)` | ~400 | `handed-projection` 21 src/492 test; `call-with-projection` 32 src; `call-with-projection-state` 11 src; `current-projection` 4 src | a large mechanical sweep (A1-12, 75 src sites); **the `default` profile shows `call-with-projection-state` at 647,134 ms max, 822,228 ms total ×82, 1 threw**. The time is the body it wraps (a turn), not its own, but the profiler attributes it to a transport binding, which hides the real owner |
| R10 | classpath fallback: `!fallback-counts`, frame walking, `clojure.basis` roots, `decade?`, `warn-classpath-fallback!`, `packaged-forms` | 160 | stack-walks to name a caller that resolved the population with none in hand | none needed: `declaration-population` already refuses with no projection (`:1242-1256`); the fallback is reached only through `candidate-forms` | delete; absence refuses (A1-4) | ~155 | internal | bootstrap `register!` before any projection: hand it the packaged forms explicitly |
| R11 | `admission-from-asserting-transaction` | 31 | admission source of a row by its asserting tx | one `d/q` | move to its only callers (tests) or keep | 0–31 | 0 src | — |
| R12 | `declaration-projection` | 31 | projection over forms without contracts | the §0 lazy registry over `forms` | one constructor for both | ~25 | 18 src | — |
| R13 | `structural-registry`, `structural-schema`, `malli-form?`, `pull-selector?` | 105 | syntax-only compile with opaque refs; two core predicates | audit row 24: KEEP (a Malli `Registry` reify, not a parallel registry); `pull-selector?` asks Datahike's parser (`DH/pull_api.cljc:58`) | keep; `malli-form?` loses its `compilable-form` call with R3 | ~5 | 7 src | none |
| R14 | contract validation: `assert-entity-partition!`, `assert-complete-contract!` (173 lines), `*contract-validation-fold-size*`, `validate-contracts!`, `validate-declarations!` (108) | 318 | walks every declaration and its references for completeness, agent strictness, purity and storability, with a shared advisory memo and a `reducers/fold` | `m/walk` (`core.cljc:2611`) is the walk; the rules in `internal/assert-complete-schema!` are Seon semantics and stay | run only at the writer for changed keys + stored dependents; the `prepared?` dual mode (`:1542-1606`) and the advisory memo (`:2091-2121`) exist to make a whole-population load affordable and go with load-time validation | ~200 | `assert-complete-contract!` 2 src (`schema.edn`) | the agent-strictness and partition rules must keep one regression each |
| R15 | render-contract coherence: `render-declarations-in`, `map-shaped-schema?`, `required-map-entries`, `map-schema-accepts-schema?`, `schema-accepts-schema?`, `arity-render-input`, `render-contract-observation`, `assert-render-contracts!` | 194 | proves a named renderer's first input accepts the declaring schema | no library decides Malli subtyping (web search: none maintained; Malli has no subsumption API) | keep, run at the writer; drop the double `(= form form)` arm (`:1907-1908`) | ~10 | internal | — |
| R16 | shape index: `shape-row-in`, `shape-projections`, `replace-shape-rows`, `shape-index-attributes`, `shape-projections-with`, `shape-rank`, `*-present-attrs`, `diagnostic-schema-keys`, `candidate-shapes-in`, `matching-shapes-in`, `explain-shape-in`, limits | 249 | required-attr → schema index for render matching and bounded diagnostics; maintained incrementally | a function of the registry, memoized on the projection's holder (R5); measured 50 ms scan + 19 ms index, once per projection value | one `(shape-index p)` via `projection-cache-value`; `build-projection`'s inline copy (`:2316-2350`) and `shape-projections-with` go; `catalog`/`required-by-key` have no reader outside this file | ~150 | `matching-shapes-in` 10 src; `candidate-shapes-in` 1 src; shape-rows 1 src (`render/transcript`); shape-index 1 src (`call_preparation`) | first render after a declaration change pays ~70 ms armed; the per-attribute-revision memo keeps it at zero for unrelated commits |
| R17 | `build-projection` | 215 | whole build: predicate binding, graph, cycles, registry, validation, indexes, fingerprint | the §0 composition | `load-projection` (§0) + writer validation | ~190 | 10 src (`db`, `fn`, `schema.edn`, `program`), 101 test | test fixtures call it with forms; keep a `(projection forms contracts)` constructor |
| R18 | `projection-runtime-keys`, `projection-pure-data`, `compose-projection-data`, `projection-delta`, `projection-delta-identities`, `maintain-projection-delta` (dead), `materialize-projection` | 206 | divergence deltas and rematerialisation for a preproved base | not needed when load is ~50 ms: the delta exists to avoid a whole build | delete with `db.clj:297-300` (A2's file) | ~200 | `db.clj:297-300` only | A2 owns the caller: one slice with A2 |
| R19 | incremental replacement: `reverse-dependencies`, `replace-reverse-dependencies`, `function-dependents-of`, `declaration-changes`, `set-changes`, `incremental-projection-keys`, `incremental-base?`, `population-options`, `projection-with-declarations` (240), `projection-with-schema`, `projection-without-schema`, `projection-with-function-contract` | 413 | `1625fb9bc`: recompiles only changed declarations and their reverse closure against retained Malli objects | Malli's lazy registry + `RefSchema`'s lazy deref (`core.cljc:1953-2025`) make a fresh registry correct and 25 ms; nothing to retain | `projection-with-schema` = `(assoc forms k v)` → new lazy registry → validate `k` + stored dependents: about 25 lines for all three | ~385 | `projection-with-schema` 7 src; `-without-` 5 src; `-function-contract` 4 src | the equality test `an-incremental-projection-equals-its-full-build` becomes vacuous and is deleted with the mechanism |
| R20 | `projection-rows`, `projection-admissions`, `projection-from-rows` (221), `projection-ranges`, `projection-attributes`, `load-projection`, `database-projection`, `projection-from-database` | 308 | row loader with malformed/duplicate/identity refusals and a string-reuse table | **Datahike guarantees every refused case**: `:seon.schema/key` is `db.type/keyword db.unique/identity`, `:seon.fn/sym` is `db.type/symbol db.unique/identity`, and `form`/`spec`/`source` are `db.type/string` cardinality one (probe of `d/schema`). A duplicate identity, a non-string form and a non-keyword key cannot be stored | the §0 `load-projection` (~20); `projection-attributes` stays as the memo's read set (`db.clj:1309`) | ~280 | `projection-from-database` 38 src; `load-projection` 1 src | none beyond R7's |
| R21 | pulled forms: `pulled-schema-key`, `entity-entry-map`, `reverse-target-schema`, `selector-target-schema`, `pulled-attribute-entry` (116), `pulled-form-from-spec`, `projection-with-pulled-form-in`, `pulled-form-in` | 267 | derives the Malli shape of a Datahike pull result from a parsed pull spec | Datahike's parser supplies the spec (`pull-api/pull-plan-spec`); no library derives a result schema from a pull (web search: none) | keep; it registers through `projection-with-schema`, which becomes a cheap `assoc` | ~20 | 3 src (`db`) | none |
| R22 | identity-only descriptors | 66 | reference values projected to identity data | — | keep; its hand holder (`:4233-4239`) becomes `projection-cache-value` | ~8 | 2 src | none |
| R23 | `identity-attr?`, `enum-members`, `dependency-first-schema-keys`, `canonical-schema-rows`, `canonical-database-attributes`, `valid-candidate-value?`, `explain-candidate-value`, `projection-validator`, `function-*-in?`, `projection-explainer` | 203 | reads over the retained registry | `m/validator`/`m/explainer` cache on the Schema (`core.cljc:2626`, `:2642`, `-cached :353`) | keep; `dependency-first-schema-keys` becomes a topological order over the stored references | ~20 | 15–38 src | none |
| E1 | `schema/edn.clj` packaged population cache (`declaration-stamp`, atom, `packaged-population`, `forget-packaged-population!`) `:344-404` | 60 | an LRU of one keyed by file stamps, with a forget hatch | `clojure.core.cache.wrapped/lookup-or-miss`, used the same way in `DH/schema_cache.cljc:8-29` (audit row 7) | one `cw/lookup-or-miss` | ~30 | internal + 2 tests | — |
| E2 | `schema/edn.clj` admission (`predicate-declarations`, `assert-predicates!`, `unresolved-candidate-reference`, `admit-changed-identities`, `admit`) `:438-659` | 222 | the bootstrap gate plus a recursive re-admit loop that resolves references across one registration | with R19 gone, one `projection-with-schema` per changed identity over a lazy registry resolves references without the retry loop; predicate existence is R3's `::m/sci-not-available` | `admit` = derive config composites + validate the changed keys | ~130 | `admit` 1 src (`schema.admission`) | config composites keep one regression |
| A1 | `schema/admission.clj` file walker (`schema-files`, `request-units`, `default-schema-directory`, `canonical-path`, `read-declarations`, `default-registry-excluding`) + `-main` `:120-201`, `:482-487` | ~95 | re-reads every schema resource from disk beside the live registry | A1-9 (plan): the carried forms are the registry | the request carries sources + the carried forms | ~90 | `bin/seon-hook:481-506` | one slice with B1 (hook) |
| D1 | `schema/datahike.clj` codec (`encoded-operation*`, storage readers, `encode-*`, `decode-*`) `:299-565` | 266 | EDN string codec for heterogeneous attributes | `DH/schema.cljc:87` `:db.type/any` (audit row 3) | A2 c2 | ~250 | A2 | A2's file |
| D2 | `schema/datahike.clj` Malli→Datahike mapping `:16-297` | 280 | value type, cardinality, uniqueness and component from compiled Malli | web: `licht1stein/malli-datomic` (4 commits, "EARLY DEVELOPMENT STAGE", no type table) and `Provisdom/spectomic` (spec, not Malli) do not cover refs, components, tuples or unions; no maintained library does it | keep; `compiled-attribute-selection`'s repeated `declared-storable?` (a throw/catch per property ×10,961 calls, 216 ms in the cold profile) becomes one memoized `(storable? p attr)` on the holder | ~20 | 21 src | none |

Sum of deletable estimates in `schema.clj`: about **3,050 of 4,398**. In the four sub-namespaces:
about 520 of 2,232, most of which is A2's codec.

## 2. First slices (each ≤ ~100 added lines, net far negative)

Order: each slice leaves HEAD loadable, and each has one REPL proof. A slice that touches another
lane's file is marked with the owner it needs.

**S1: the fingerprint leaves (about −140, +15).** Delete R8's population fingerprint and its
incremental maintenance (`projection-fingerprint`, `replace-fingerprint-entry`, `fingerprint-with`,
`-from-data`, `reusable-`, `-version`, `portable-string-hash`, dead `canonical-data-fingerprint`,
the fingerprint branch in `projection-from-rows` `:3338-3348`). The five readers key their caches by
the projection value; Clojure's cached `hash` of `forms` answers in 0.38 ms. The debug display
(`render/web.clj:843,908`) shows `hasch.core/uuid` of forms, computed lazily. Files: `schema.clj`,
`render/walk.clj`, `render/web.clj`, `sci/eval.clj` (owners: render lane, B2). Proof: cold
`load-projection` drops by the measured 364 ms (1,012 → ~650 ms); the `acquisition-attribute-cache`
and root-pull-plan cache still hit across an unrelated commit (`cache/has?` before and after one
`transact!` on a scratch branch).

**S2: dead vars (about −180, +0).** `maintain-projection-delta`, `projection-delta-identities`,
`activate!`, `activate-projection!`, `current-keys`, `form-string`, `register-all!`,
`registered?`, `schemas-in-namespace`, `clear-all!`, `candidate-shapes`, `explain-shape`,
`explain-shape-in`, `canonical-data-fingerprint`. clj-kondo shows zero `src` or `test` callers
for each. Proof: `clj-kondo --lint src test` shows no new unresolved var, and `(require 'seon.schema :reload)` succeeds.

**S3: predicates belong to the fork (about −390, +10 including one fork line).** Fork first
(pushed): `core.cljc:2894` returns `[target-var]`, with a fork test that a re-`def` changes an
already-compiled validator. Then delete R3 and R4 except `widen-component-children`: the forms are
compiled as authored with `{::m/disable-sci true}`. `canonical-definition`'s callers in `fn.clj` and
`fn/schema_shape.clj` store the authored form (owner: B1/A1-13). `deleted-predicate-functions`
leaves, because the lazy registry refuses on first use, which is what `ea048d617` wanted.
Proof, already observed on `default`: `m/form` of `[:fn 'seon.schema/malli-form?]` keeps the
symbol, the validator is correct, and a deleted or unloaded predicate refuses
`::m/sci-not-available` without loading. After the slice: `seon.schema-test/one-predicate-symbol-cannot-name-two-environments-callables`
and `canonical-definition-keeps-admitted-predicate-symbols` green, or deleted as tests of the
removed inverse.

**S4: projection = lazy registry over rows (about −700, +60).** `load-projection` becomes the
§0 form. `projection-from-rows`, `projection-rows`, `projection-admissions`, `projection-registry`'s
eager pass and fast copy, and `build-projection`'s load-time validation and index building all go.
`declaration-projection` becomes the same constructor over `forms`. Test fixtures keep one
`(projection forms contracts)` constructor with an explicit `validate` call. Proof: the forms and
contracts probe (`=` carried: true, true); cold `load-projection` ≤ 150 ms (target from parts:
23 query + 25 parse + ≤ 66 widen, measured armed); `projection-validator` for `:seon.error/base`
≤ 1 ms on a fresh projection. Row 1 of `dependency-already-does-it` (the metadata stamp → memo by
revision key) is the A2 half; `db.clj`'s `nearest-base` (`:1363-1380`) and
`declaration-content-key` exist to feed a base and leave in A2's paired commit.

**S5: writer-side replacement without retention (about −420, +40).** `projection-with-schema`,
`-without-schema` and `-with-function-contract` become: new forms map → §0 registry → validate the
changed identity and its dependents, read from `:seon.schema/references` / `:seon.fn.arity/input-refs`
on the writer's database value. For a pre-write candidate, `direct-references*` supplies them.
R19 and R18 are deleted, with `db.clj:297-300`'s compose/materialize (A2). Proof: one
`projection-with-schema` on a scratch branch (`bin/seon --root tmp/shrink-root`) under 20 ms, and
removing a key with a dependent still refuses naming it (`schema-in-use` with the stored blockers).

**S6: shape index as a memoized function (about −150, +15).** R16 becomes
`(projection-cache-value p ::shape-index derive)`. The duplicate in `build-projection`,
`shape-projections-with`, `replace-shape-rows`, `catalog`, `required-by-key` and `entity-catalog`
go. Proof: `matching-shapes-in` results `=` before and after on 20 sampled render values; the first
call after a new projection is under 100 ms armed, and a second call takes one map lookup.

**S7: dependency graph from stored facts (about −170, +25).** `dependent-schema-keys`,
`schema-removal-blockers`, `canonical-reference-graph`, `reference-registry` and
`dependency-first-schema-keys` become `d/q` over `:seon.schema/references` (3.2 ms for 278
dependents, `=` the carried index). The cycle check runs at the writer over the candidate's closure
only. Callers: `instrument.clj` and `fn/schema_shape.clj` read `schema-dependencies` (A1). Proof: for 10
sampled keys, the query's reverse closure `=` the carried `reverse-schema-dependencies` closure.

**S8: classpath fallback and ambient transport (about −550, mostly mechanical).** R10 and R9 are
A1-4 and A1-12 as planned. With S4, the projection is a value from `db/carried-projection`, so the
sweep is one script over the 75 sites (AGENTS "mechanical edits are scripted"). It lands after A2
c2.

## 3. Target size and reasoning

| File | Now | After S1–S8 | Floor | What remains |
|---|---:|---:|---:|---|
| `schema.clj` | 4,398 | ~1,300 | ~700 | the lazy-registry projection (~40), writer validation over the changed closure (~120), `direct-references*` + cycle refusal (~50), structural registry and the two predicates (~100), render coherence (~180), pulled forms (~250), identity-only (~55), validators and function queries (~120), one refusal helper (~30) |
| `schema/internal.cljc` | 521 | ~450 | ~350 | the completeness rules are Seon semantics |
| `schema/edn.clj` | 659 | ~420 | ~250 | resource read and placement, config composites, one gate |
| `schema/admission.clj` | 487 | ~390 | ~250 | house findings; the walker leaves with A1-9 |
| `schema/datahike.clj` | 565 | ~300 | ~250 | the Malli→Datahike mapping; the codec leaves with A2 c2 |
| **Total** | **6,630** | **~2,860 (−57 %)** | **~1,800** | |

Reasoning: the owner's target is at most 10,000 lines for all of `src` (84,928 today), which is
11.8 % of the current size. This area at 11.8 % would be about 780 lines. The floor of about 1,800
keeps render-contract coherence, pulled-form derivation and the completeness rules, because no library
found by web search does any of them. Reaching 780 requires the owner to rule that pulled-form
derivation and render coherence move to their consumers (`db`, `render`) or leave. Every
other region is either a Malli/Datahike/hasch/core.cache seam or a consequence of load-time
validation, which the writer's admission makes redundant.

## 4. Web sources

- Malli README, registries (lazy, composite, mutable are "a dev-time abstraction"), humanize,
  function schemas: https://github.com/metosin/malli (read from the fork's `README.md` §"Registry
  implementations" `:3192-3404`, §"Humanized error messages" `:810`).
- Malli blog, registries as values vs spec's global registry: https://www.metosin.fi/blog/malli
- hasch, cross-platform EDN hashing (`hasch.core` merkle HashRef, `hasch.fast` binary digest,
  UUID5): https://github.com/replikativ/hasch ; https://cljdoc.org/d/org.replikativ/hasch/0.4.104/doc/readme
- malli-datomic (rejected: early stage, 4 commits, no type mapping documented):
  https://github.com/licht1stein/malli-datomic
- spectomic (rejected: clojure.spec, not Malli): https://github.com/Provisdom/spectomic
- Datahike schema documentation (schema-on-write value types and uniqueness):
  https://cljdoc.org/d/io.replikativ/datahike/0.3.2/doc/schema
- No maintained library was found for Malli schema subsumption (R15) or pull-result schema derivation
  (R21). Searches: "malli schema to datahike schema library clojure", "generate datomic schema from
  malli schema github", "malli registry incremental lazy registry database backed schemas".

## 5. Timings of this lane's operations

| Operation | ms | Over 1 s? |
|---|---:|---|
| clj-kondo var-definitions, schema files | 652 | no |
| clj-kondo var-usages, `src` + `test` (parallel) | 16,256 | **yes, and over 10 s**: proportional to the whole `src`+`test` tree (≈ 190 k lines), one pass for the caller census; the same analysis cache the hook keeps would make it incremental. Filed as a tooling observation below, not rerun |
| probe 1 (`load-projection` cold + registry copies) | 1,021 | **yes**: the measured subject itself (the cold build, 1,012 ms), the defect §0 names |
| probe 2 (fingerprint, hash, validators) | 441 | no |
| probe 3 (shape scan, lazy compile) | 173 | no |
| probe 4 (reference graph, stored references) | 71 | no |
| probe 5 (row query + parse) | 58 | no |
| probe 6 (hasch vs canonical string) | 92 | no |
| fork predicate probe | 5 | no |

## 6. Out-of-scope findings

- **`default` runs a pre-`1625fb9bc` `seon.schema`** (line 2071 for `build-projection`; no
  `projection-with-declarations`). A lane reading `default` for projection behaviour is not reading
  HEAD. For the orchestrator: check adoption, and do not treat this as a lane defect.
- **`call-with-projection-state` profile: 647,134 ms max, 822,228 ms total ×82, 1 threw** on
  `default` (`runtime_status` profile). The binding wraps whole turns, so the transport takes the
  blame for its body. It is a reason to land A1-12, not a separate defect.
- The 16 s whole-tree clj-kondo usage pass has no incremental cache for research lanes.
