---
type: reference
title: Program rows — what they are today and the one query that should define them
date: 2026-09-22
head: 320c73adc
status: research (read-only; every citation verified by reading at 320c73adc)
question: "How are we defining 'program rows'? Is this metadata that's easy to query in the database?" (owner, 2026-09-22)
---

# Program rows — data pack

## Summary (ten lines)

1. "Program row" is ALREADY a declared, queryable fact — but only half of one, and the half that exists is a hand list.
2. The declared half: an attribute carrying `:seon.program/row-schema` + `:seon.program/source-attribute` names a program family; its row schema's entries ARE that family's owned attributes (`program.cljc:126-171`).
3. The hand-list half: `seon.program/identity-attributes` is a literal vector of six (`program.cljc:17-20`), duplicated as two `:enum`s in `seon.program.edn:29-34`. Nothing derives it from the registry.
4. Per-attribute exceptions are already declared, not listed: `:seon.program/written-by` on an entry says "another writer owns this" (12 such entries in `seon.test.edn` alone).
5. There is NO partition fact. `:seon.schema.admission/source` answers `:core`/`:agent` (who admitted), never program/data. `:seon.db/no-history?` is a storage dial on 12 attributes, unrelated.
6. Census on `default` (307,901 datoms, 413 attributes, 543 ms): 11,600 entities carry an identity attribute; their own datoms are 122,117; the component closure (138 component attributes, depth 7) reaches 26,373 entities / 184,615 datoms (60%).
7. By attribute namespace the proposed program set is 231,565 datoms (75%) and the rest 76,348 — dominated by `seon.db` provenance (35,071), `datahike.read` (23,256), `seon.issue` (3,889), `seon.context.contribution` (3,252).
8. A namespace convention fails on real data: `seon.test/usage`, `seon.test/adoption-*`, `seon.test/failures` are run outcomes inside the program namespace, and `seon.schema/shape` reaches 46,143 derived datoms through a NON-component ref.
9. Recommendation: keep the existing entity-schema link, add `:seon.program/partition :seon.program` to every program ENTITY SCHEMA (roots and components), derive `program-attributes` from the compiled registry, and make `program rows on a branch` the single query `[:find ?e :in $ [?a ...] :where [?e ?a]]`.
10. 48 of the 54 installed `:db.unique/identity` attributes sit outside the six-entry list; some (`seon.call-preparation/key`, `seon.schedule.task/id`) are program-shaped. An undeclared partition must REFUSE at the projection validator, not default.

---

## 1. The declaration entities today

Six families are declared program families. Each declares the link ON its identity attribute; the
row schema's own map entries are the owned attribute set — "there is no second list"
(`program.cljc:209-215`).

| Family (row schema) | Identity attribute | Declared at | Source attribute | Producer(s) | Derived or authored |
|---|---|---|---|---|---|
| `:seon.ns/ns` | `:seon.ns/name` | `seon.ns.edn:5-9` | `:seon.ns/source` | `fn.clj:3306` `index!`; agent `ns` form via `sci/eval.clj:1709` | authored (source) + derived (requires/aliases/imports/refers) |
| `:seon.fn/fn` | `:seon.fn/sym` | `seon.fn.edn:189-192` | `:seon.fn/source` | same | authored (source, doc, arglists, spec) + derived (calls, references, call-arities, writes, keywords, arities, workload) |
| `:seon.schema/schema` | `:seon.schema/key` | `seon.schema.edn:76-79` | `:seon.schema/form` | `schema/edn.clj:608,616`; `schema.clj:1403-1449` | authored (form) + derived (shape, references, generatable?, predicate) |
| `:seon.test/test` | `:seon.test/sym` | `seon.test.edn:53-57` | `:seon.test/source` | `fn.clj:3306`; agent `deftest` via `sci/eval.clj` | authored (source, markers) + derived (reach, analyzed digest) |
| `:seon.fn.file/file` | `:seon.fn.file/relative-path` | `seon.fn.file.edn:1-3` | `:seon.fn.file/digest` | `fn.clj:3336-3344` | fully derived from bytes |
| `:seon.lint/finding` | `:seon.lint/id` | `seon.lint.edn:1-3` | `:seon.lint/message` | `fn.clj` analyzer phase | fully derived |

Entity map for `:seon.fn/fn` is `seon.fn.edn:116-162`; for `:seon.ns/ns` `seon.ns.edn:10-33`; for
`:seon.test/test` `seon.test.edn:82-145`; for `:seon.schema/schema` `seon.schema.edn:96-120`.
`:seon.schema/schema` is the one family declaring `:seon.program/projected-properties true`
(`seon.schema.edn:99`), so its owned set is "every qualified key on the row"
(`program.cljc:166-170`).

Program COMPONENTS — rows with no identity attribute of their own, reached from a root:

| Component family | Reached by | Declared at | Producer |
|---|---|---|---|
| `seon.fn.arity` / `seon.fn.argument` / `seon.fn.binding{,.entry,.child}` | `:seon.fn/arities` | `seon.fn.edn` entity map line 150 | `program.cljc:367-560` (`binding-row`, `argument-row`), called from `fn.clj` contract phase |
| `seon.ns.alias` / `.import` / `.refer` | `:seon.ns/aliases`, `:seon.ns/imports`, `:seon.ns/refers` | `seon.ns.edn:2,4,34` (`:seon.db/component true`) | `fn.clj` indexer |
| `seon.schema.shape{,.child,.entry}`, `seon.schema.map-entry` | `:seon.schema/shape` — **a plain `:seon.db/ref`, NOT a component** (`seon.schema.edn:81`); children/entries below it ARE components (`seon.schema.shape.edn:11-14`) | `src/seon/fn/schema_shape.clj:67,147,171,245,257` |
| `seon.fn.contract.finding` | flat finding keys on the fn row | `seon.fn.contract.finding.edn:4-8` | `src/seon/fn.clj` only |

Two declaration-shaped families sit OUTSIDE the six: `seon.call-preparation/key`
(`:seon.db/identity true`, `seon.call-preparation.edn` `:key`; 5 rows on `default`) and
`seon.schedule.task/id` (12 datoms). Neither is reachable through `program/identity-attributes`,
so acquisition, merge and write-back cannot see them today.

**Verdict:** the family link is declared and derivable; the *set of families* is a literal vector
plus two duplicated `:enum`s, and at least two program-shaped families are already missing from it.

---

## 2. Is there a queryable partition fact today?

| Candidate | What it actually says | Line | Usable as the partition? |
|---|---|---|---|
| `:seon.schema.admission/source` | `[:enum :core :agent]` — WHO admitted a declaration | `seon.schema.admission.edn:1` | No. It partitions authorship, and 11,599 datoms carry it; a turn row has none |
| `:seon.db/no-history?` | maps to Datahike `:db/noHistory` | `schema/datahike.clj:180` | No. A storage dial, 12 declarations, all on data attributes (`seon.cluster.eval.edn:7,17,25`, `seon.ai.attempt.edn:9,12`) |
| `:seon.program/row-schema` | identity attribute → entity schema of its family | `seon.program.edn:35-41` | **Closest existing fact.** Already the generic entity-schema resolver in `db/pull` (`db.clj:2131-2162`) |
| `:seon.program/written-by` | one entry is written by another function | `seon.program.edn:42-48` | The per-attribute exception the partition needs; already 12 uses in `seon.test.edn` |
| key-namespace convention | — | — | No. See the `seon.test/*` split below |

### Census on `default` (2026-09-22, live JVM pid 38968, `mcp__seon__eval_clj` mode jvm, read-only)

Probe A — `(seon.db/q '[:find ?a (count ?e) :where [?e ?a]] db)` → **413 attributes, 307,901
datoms, 543 ms.**

Probe B — entities carrying an identity attribute, per attribute:
`seon.fn/sym` 4,462 · `seon.schema/key` 3,277 · `seon.test/sym` 2,056 · `seon.lint/id` 701 ·
`seon.fn.file/relative-path` 697 · `seon.ns/name` 407. Union **11,600 entities, 122,117 datoms**
(39.7%), 349 ms.

Probe C — component closure from those roots over the 138 installed `:db/isComponent` attributes:
converged at **depth 7, 26,373 entities, 184,615 datoms (60.0%)**, 673 ms. Adding
`:seon.schema/shape` and the shape child/entry refs: depth 7, 29,650 entities, **201,000 datoms**,
1,189 ms — and 29,758 `seon.schema.shape*` datoms STILL unreached, because shapes are interned by
fingerprint and shared.

Probe D — datoms by attribute namespace, split into the proposed program set vs the rest
(`:program-total` 231,565 / `:other-total` 76,348 of 307,913):

| Program set | datoms | | Rest | datoms |
|---|---|---|---|---|
| `seon.fn` | 70,972 | | `seon.db` (provenance) | 35,071 |
| `seon.schema.shape` | 29,029 | | `datahike.read` | 23,256 |
| `seon.fn.arity` | 17,967 | | `db` (`:db/ident`, tx) | 4,086 |
| `seon.fn.argument` | 17,019 | | `seon.issue` | 3,889 |
| `seon.schema` | 14,974 | | `seon.context.contribution` | 3,252 |
| `seon.schema.shape.child` | 14,217 | | `seon.issue.citation` | 2,601 |
| `seon.fn.binding` | 12,419 | | `seon.render.block` | 813 |
| `seon.schema.admission` | 11,599 | | `seon.cluster.eval` | 764 |
| `seon.ns.alias` 6,948 · `seon.test` 6,546 · `seon.program` 6,531 · `seon.schema.map-entry` 5,544 · `seon.lint` 5,523 · `seon.schema.shape.entry` 2,897 · `seon.ns` 2,239 · `seon.fn.binding.entry` 2,121 · `seon.ns.refer` 2,106 · `seon.fn.file` 1,800 · `seon.ns.import` 882 · `seon.fn.binding.child` 228 · `seon.source` 4 | | | `seon.render` 673 · `seon.turn` 313 · `seon.config` 240 · `seon.eval` 170 · `seon.ai.attempt` 168 · `seon.context.capture` 120 · `seon.ai.usage` 96 · `seon.wake` 77 · `seon.sci.eval` 74 · `seon.ai.model` 73 · `seon.dev.mcp.artifact` 42 · `seon.turn.work` 25 | |

The `seon.test` namespace alone refutes the convention: `:seon.test/sym` 2,056, `:seon.test/ns`
2,056, `:seon.test/source` 2,056, `:seon.test/long` 119, `:seon.test/platform` 89 are program —
while `:seon.test/usage` 5, `:seon.test/adoption-inputs` 5, `:seon.test/adoption-identities` 5,
`:seon.test/adoption-cluster` 5 and `:seon.test/failures` are run outcomes, each already carrying
`:seon.program/written-by seon.test.runner/record-tx` (`seon.test.edn:94,100,103,106,122,125,128,131,134,137,140,143`).

**Verdict:** no partition fact exists; the nearest is `:seon.program/row-schema`, which already
reaches 60% of `default`'s datoms through components and 75% by namespace.

---

## 3. The proposed definition, as a query

Add ONE property to the ENTITY SCHEMA declaration (not the identity attribute, not each attribute):

```clojure
:seon.program/partition [:= {:description "Declared on an entity map: its rows are program, inherited by every attribute the map declares."} :seon.program]
```

Then three derivations, none of them a list:

```clojure
;; 1. The program families — replaces seon.program/identity-attributes (program.cljc:17-20)
(defn program-row-schemas [projection]
  (into #{} (keep (fn [k] (when (= :seon.program (:seon.program/partition
                                                  (internal/entity-properties
                                                   (mr/schema registry k))))
                            k)))
        (keys (:seon.schema.projection/forms projection))))

;; 2. The program attributes — the union of each family's entries, minus written-by
;;    (the derived-shape filter already at program.cljc:158-160)
(defn program-attributes [projection] ...)

;; 3. The rows on a branch — ONE query, no per-consumer list
'[:find [?e ...] :in $ [?a ...] :where [?e ?a]]
```

The scan in (1) is the pattern already installed at `program.cljc:84-100`
(`base-context-injected-symbols` filters registry properties the same way); the per-family walk is
`derived-shape` (`program.cljc:126-171`) unchanged, and the authored-resources cache keyed by
`schema.edn/declaration-stamp` (`program.cljc:224-253`) carries over as-is.

**Schema files that gain the property** (roots): `seon.fn.edn` `:seon.fn/fn` (116), `seon.ns.edn`
`:seon.ns/ns` (10), `seon.schema.edn` `:seon.schema/schema` (96), `seon.test.edn` `:seon.test/test`
(82), `seon.fn.file.edn` `:seon.fn.file/file`, `seon.lint.edn` `:seon.lint/finding`.
(Components): `seon.fn.arity.edn`, `seon.fn.argument.edn`, `seon.fn.binding{,.entry,.child}.edn`,
`seon.ns.alias.edn`, `seon.ns.import.edn`, `seon.ns.refer.edn`, `seon.schema.shape{,.child,.entry}.edn`,
`seon.schema.map-entry.edn`, `seon.fn.contract{,.finding}.edn`, `seon.fn.output.edn`,
`seon.fn.manifest.edn`. Owner decision pending on two program-shaped strays:
`seon.call-preparation.edn` and `seon.schedule.task.edn`.

**Rejected alternatives**

| Alternative | Why rejected |
|---|---|
| Attribute-namespace convention (`seon.fn/*`, `seon.test/*` = program) | `seon.test/usage` and `seon.test/adoption-*` are run outcomes in the program namespace; `seon.fn/file` is a ref into a family; AGENTS.md forbids a hand-maintained list, and a convention is a list you cannot query |
| A stamped kind per entity (`:seon.program/kind :program`) | Forbidden: "An entity is its attributes and relations, not a stamped kind" (AGENTS.md, Data). It also duplicates a fact the schema already states, and every writer would have to remember it |
| A hand list of attributes or families (today's `identity-attributes`) | It is exactly what drifted: 48 of 54 installed `:db.unique/identity` attributes are outside it; the same six values are ALSO spelled twice as `:enum`s in `seon.program.edn:29-34`, so adding a family means editing three places |
| Component-reachability walk from roots | Measured: it misses 29,758 `seon.schema.shape*` datoms because `:seon.schema/shape` is a shared non-component ref (`seon.schema.edn:81`), and it costs a bounded graph walk per branch (1.2 s on `default`) where an attribute set costs one index seek |
| Reuse `:seon.schema.admission/source` | Orthogonal: it answers `:core`/`:agent`, and both values appear on program rows |

**What breaks if a schema forgets it.** The projection validator must refuse an undeclared
partition on any entity map, by the same shape as the existing refusals at `program.cljc:144-152`
("A program identity attribute declares no row schema" / "no source attribute"). Silence is the
failure mode AGENTS.md names: an unmarked family would be invisible to acquisition, dropped by
merge, unseen by the test gate and never written back — a program row that silently becomes
disposable. The refusal names the entity schema key and `:seon.program/partition` as the missing
member. `:seon.program/identity-attribute` and `:seon.program/source-attribute` in
`seon.program.edn:29-34` stop being `:enum`s and become `:qualified-keyword`.

---

## 4. Derived vs authored inside the program

| Program attribute | Authored / derived | Recomputable from | Merge? |
|---|---|---|---|
| `:seon.fn/source`, `:seon.test/source`, `:seon.ns/source`, `:seon.schema/form` | authored | — | **must merge** |
| `:seon.fn/doc`, `:seon.fn/arglists`, `:seon.fn/spec`, `:seon.fn/private?`, `:seon.fn/macro?`, `:seon.fn/internal?`, `:seon.fn/doc-order` | authored (read off the form) | the source form | merge (cheap, and identity-bearing) |
| `:seon.test/long`, `:seon.test/long-ms`, `:seon.test/platform`, `:seon.test/fixture` | authored markers, with namespace inheritance (`program.cljc:173-203`) | var + ns metadata | merge |
| `:seon.fn/calls`, `:seon.fn/references`, `:seon.fn/call-arities`, `:seon.fn/writes`, `:seon.fn/keywords`, `:seon.fn/invokes`, `:seon.fn/destroys` | derived (clj-kondo analysis) | source + resolver context | recompute |
| `:seon.fn/arities` and the whole `seon.fn.arity`/`argument`/`binding*` component tree (49,754 datoms) | derived from `:seon.fn/spec` | contract form | recompute (`program.cljc:367-560`) |
| `:seon.schema/shape` and `seon.schema.shape*` (46,143 datoms) | derived, interned by fingerprint | `:seon.schema/form` | recompute (`fn/schema_shape.clj:67,147,171`) |
| `:seon.ns/requires`, `:seon.ns/aliases`, `:seon.ns/imports`, `:seon.ns/refers` (9,936 datoms) | derived | the `ns` form | recompute |
| `:seon.program/definition-digest` | derived, deliberately excluding `:db/id`, file, span, calls, references, keywords, writes, call-arities, analyzed digest and admission (`program.cljc:323-334`) | the authored row | recompute |
| `:seon.program/analyzed-source-digest` | derived; its PRESENCE is the analysis-ran fact (`seon.program.edn:4-6`) | the analyzed input | recompute |
| `:seon.fn/file`, `:seon.fn/form-span`, `:seon.fn.file/*` | derived provenance, absent on agent declarations (`seon.fn.edn:file` description) | the staged file | recompute at write-back |
| `:seon.lint/*` (5,523) | derived findings | analysis | recompute |
| `:seon.ns/steward`, `:seon.test/failures`, `:seon.test/usage`, `:seon.test/adoption-*`, `:seon.test/reach-digest` | NOT indexer-owned — `:seon.program/written-by` (`seon.ns.edn:31`, `seon.test.edn:94-143`) | — | never merged by the indexer; belongs to its writer |

Where the derived set is recomputed today: `seon.fn/index!` (`fn.clj:3306`) runs the ordered phases
— analysis input inventory and digests (`fn.clj:2328,2365`), selected files and artifact replacement
(`fn.clj:2379-2396`), contract projection (`fn.clj:2596-2606`), caller findings (`fn.clj:3431-3451`)
and the final `:seon.fn/population` commit (`fn.clj:3481`). It owns only non-`written-by` entries
(`program.cljc:158-160, 990-995`), so an exact re-index cannot retract another writer's fact.
Cost: a full base publication measures **141 s** (plan README:182); an incremental run is scoped by
`:seon.fn/changed-paths` (`fn.clj:3336-3344`) against the previous manifest.

**Verdict:** authored program rows are roughly the source/contract/doc/marker attributes; ~60% of
program DATOMS on `default` (arity+argument+binding+shape+ns-binding families = 105,000 of 231,565)
are pure functions of them and can be recomputed at merge and at write-back rather than merged.

---

## 5. What each consumer needs

| Consumer | Exact program subset it reads today | Lines | Served by the partition? |
|---|---|---|---|
| Acquisition — `sci/eval.clj` `acquire-program!` | `:seon.ns/name` + `:seon.ns/source` + admission; all namespace names; `:seon.fn/sym`/`:seon.fn/source`/`:seon.fn/private?` + admission; `:seon.test/sym`/`:seon.test/source`/`:seon.test/ns` + admission; then a pull of `:seon.ns/requires` and the alias/import/refer components | `sci/eval.clj:1709,1736-1745,1755-1815` | Yes — it is exactly `program-attributes` filtered to `:seon.schema.admission/source :agent`, plus the ns component closure |
| Merge (D1 §2a–2c) | changed declarations, callers, references, namespace bindings and the schema contract closure; conflicts by identity pair | `plan/lane-d1-isolation-merge-writeback.md:150-186` | Yes — "carry program rows only" becomes `[:find ?e :in $ [?a ...] :where [?e ?a]]` over `program-attributes`, with the derived subset of §4 recomputed rather than transported |
| Test gate — `seon.test/select` | `:seon.test/sym`, `:seon.test/ns`, `:seon.fn/file`, `:seon.schema.admission/source`, `:seon.program/analyzed-source-digest`, `:seon.test/fixture`, `:seon.test/fixture-observation`, `:seon.test/long`, `:seon.test/platform`; plus `:seon.ns/name`, `:seon.fn.file/relative-root`; edge attributes `:seon.fn/calls`, `:seon.fn/references`, `:seon.test/reach` must be indexed symbol/many | `test.clj:830,864-869,1029-1036,1055-1062` | Yes — every member is in the `:seon.test/test`/`:seon.fn/fn`/`:seon.ns/ns` owned sets |
| Write-back — D1 §2d export | accepted functions, tests, schemas, new namespaces, additions and deletions, with file/span provenance; `overrides` (`program.cljc:46-81`) is named a post-publication check, NOT the inventory | `plan/lane-d1-isolation-merge-writeback.md:209-230`; deletions today via `cluster/source.clj:202-222` (`deleted-identities`, which already loops `program/identity-attributes` and reads each family's `:seon.program/source-attribute`) | Yes — `deleted-identities` becomes partition-derived with no edit to its body |
| Retention GC | branch retirement and whole-store mark/sweep; program rows are reachable state that must outlive a retired candidate branch while evidence stays queryable | `cluster/registry.clj:327,472,507,531`; D1 "Evidence retention is a gate before cleanup" (`lane-d1...:200-208`) | Partly — GC is reachability over Datahike commits, not attributes; the partition gives it the *inventory to prove retained*, not the sweep itself |

One more consumer already depends on the same property: `db/pull` resolves an entity's schema by
`:seon.program/row-schema` and refuses when the present attributes declare more than one
(`db.clj:2131-2175`). Widening that property's meaning to a partition keeps that refusal working.

**Verdict:** all five consumers read subsets of one attribute set, and four of them (acquisition,
merge, gate, write-back) already loop `program/identity-attributes` or its shapes — so replacing
the literal vector with a registry-derived set serves every one of them without a per-consumer list.

---

## Probe record

All probes: `mcp__seon__eval_clj`, root `/Users/sean/src/seon`, cluster `default`, mode `jvm`,
`read_only true`, custody `(seon.cluster.boot/connection "default")`, 2026-09-22, pid 38968.

| # | Form (abbreviated) | Result | ms |
|---|---|---|---|
| A | `(seon.db/q '[:find ?a (count ?e) :where [?e ?a]] db)` | 413 attributes, 307,901 datoms | 543 |
| B | per-identity `(count ?e)` over `seon.program/identity-attributes`, then `[:find ?a (count ?e) :in $ [?e ...]]` on the union | 11,600 entities, 122,117 datoms | 349 |
| C | component closure over `(keep :db/isComponent (:schema db))` (138 attrs), fixed point | depth 7, 26,373 entities, 184,615 datoms | 673 |
| C′ | C plus `:seon.schema/shape` and shape child/entry refs | depth 7, 29,650 entities, 201,000 datoms | 1,189 |
| D | attribute-namespace census split by the proposed program namespace set | program 231,565 / other 76,348 | 3 |
| E | `(keep (fn [[k v]] (when (= :db.unique/identity (:db/unique v)) k)) (:schema db))` minus the six | 54 installed, 48 outside the list | 58 |
| F | `(seon.program/shapes)` owned-attribute counts | fn 30, test 20, ns 11, lint 9, file 5, schema `:schema-row-properties` | 128 |

Limits: one cluster, one basis; no write, no test run, no JVM launch. Datom counts are current
datoms on `default`'s head, not history. `seon.schema.shape*` totals are interned rows shared across
schema keys, so they are not per-schema costs.
