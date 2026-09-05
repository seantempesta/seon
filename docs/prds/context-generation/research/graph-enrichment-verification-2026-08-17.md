---
type: research
status: current
tags: [research, program-graph, indexing, schema, settlement, verification]
---

# Graph enrichment — feasibility and seam verification

*Verification pass 2026-08-17 against the five edge families ruled into
scope by the [runtime-first PRD](../plan/runtime-first-vision-prd-2026-08-17.md)
"Graph enrichment" section, read end to end, together with
[one-renderer PRD](../plan/one-renderer-prd-2026-08-14.md) §1. Every claim
below is read at the bytes with `file:line`; the three measured numbers
came from probes run against this tree at HEAD (`context-generation-drive`,
32c618b53). No production edits were made.*

## Summary table

| # | Family | Current state | New attributes | Size |
|---|---|---|---|---|
| 1 | Core-call edges | data present in analysis, dropped by one filter | `:seon.fn/fn` entry optionality (accretion) — no new attribute | small–medium |
| 2 | `:seon.schema/references` | graph already computed per projection, never persisted | 1 new (`:seon.schema/references`) | small |
| 3 | Per-arity output refs | **already built and already queried** | none | none (family is done) |
| 4 | Session dataflow edges | 3 of 6 edges already facts; 3 genuinely missing | 1–3 new + one tx-meta attribute | medium–large |
| 5 | Test→schema edges | fully derivable today by a 3-hop join | none | none (write the query + regression) |

Two of the five families are already satisfied at the bytes (3 and 5). One
is a one-line filter relaxation plus a schema accretion (1). One is a
single key added at one existing row-builder (2). The real work is family 4.

---

## 1. Core-call edges

### Current state

The analyzer keeps **everything** clj-kondo resolves. `analyze`
(`src/seon/fn/analyzer.clj:133-174`) runs kondo with `:var-usages true`
(`analyzer.clj:24`) and normalizes every usage through `var-usage`
(`analyzer.clj:75-83`), which retains `:to`, `:name`, `:arity`, `:macro`.
The only discard at that layer is `jvm-entry?` (`analyzer.clj:117-118`),
which drops `:cljs` entries. `clojure.core` targets survive into
`::analyzer/var-usages` intact.

**The exact filter site is `call-targets-by-caller`,
`src/seon/fn.clj:233-251`** — specifically line 247:

```clojure
(if (and (contains? usage ::analyzer/arity)
         (contains? first-party-functions caller)
         (contains? first-party-functions target))   ; <- fn.clj:247
  (update calls caller (fnil conj #{}) target)
  calls)
```

`first-party-functions` is `first-party-function-symbols`
(`fn.clj:223-231`): only vars **defined in the analyzed paths**, and only
non-macro vars carrying arglists. `clojure.core/map` has no var-definition
in `src/`, so every core edge fails line 247's target test. The identical
filter appears a second time in the per-form path: `form-calls`
(`fn.clj:525-533`, `(filter first-party-functions)` at line 531). Both
sites must relax together, or hot-reload edges and index edges disagree.

### Measured (probe over `src`, 2026-08-17)

| quantity | count |
|---|---|
| kondo `var-usages` total | 36,925 |
| usages with a first-party caller and an `:arity` | 31,706 |
| **kept** today as `:seon.fn/calls` | **5,295** |
| dropped at `fn.clj:247` | 26,411 |
| — of which `clojure.core` | 25,307 (8,550 macro) |
| — distinct `clojure.core` targets | 274 |
| — non-core library targets | 1,104 (`clojure.string` 221, `core.async` 135, `malli.core` 113, `clojure.java.io` 100, `sci.core` 90, `datahike.api` 73, …) |
| printing family, dropped today | `pr-str` 275, `println` 65, `print` 5, `prn` 2 |

The last row is the PRD's claim verified: the "no printing outside the
printer" census is vacuous today because all 347 printing call sites are
invisible to `:seon.fn/calls`.

### Derivation seam and the one real decision

Relaxing line 247 is trivial; landing the edge is not, because
`:seon.fn/calls` is `[:set :seon.db/ref]` (`resources/seon/schemas/seon.fn.edn:5`)
resolving through `[:seon.fn/sym …]` lookup refs
(`fn.clj:343-345`, `fn.clj:587-589`, `run.clj:1293-1295`). A ref needs a
target entity, and `clojure.core/map` has none. `:seon.fn/fn`
(`seon.fn.edn:14-44`) currently requires `:seon.fn/ns`, `:seon.fn/source`
and `:seon.schema.admission/source`.

**The precedent already exists in this exact indexer.** `desired-rows`
already materializes name-only rows for external namespaces
(`fn.clj:1604-1612`), and `:seon.ns/ns` makes `:seon.ns/source` and
`:seon.schema.admission/source` optional precisely so those rows validate
(`resources/seon/schemas/seon.ns.edn:7-22`). The same accretion on
`:seon.fn/fn` — `/ns`, `/source`, `/admission-source` optional — lets a
target row be `{:seon.fn/sym "clojure.core/map" :seon.fn/ns [:seon.ns/name clojure.core]}`.
That is widening (accretion), not breakage: no key changes meaning and no
render function promises less. The alternative — a second attribute such as
`:seon.fn/calls-external` holding bare symbols — creates two call
mechanisms for one noun and is refused by §2.5.

Cost note to price deliberately: ~26k additional cardinality-many datoms
on the `:seon.fn/calls` AVET/EAVT indexes, plus ~1.4k new name-only fn
entities, on every `bin/seon init`. That is a 5× growth of the call
relation. Recommend measuring index publish time before and after in the
same change, and recording it here.

### Query unlocked

"Which functions print outside the one printer owner?" — non-vacuous:

```clojure
'[:find ?caller-symbol ?printer
  :in $ [?printer ...]
  :where
  [?function :seon.fn/sym ?caller-symbol]
  [?function :seon.fn/calls ?target]
  [?target :seon.fn/sym ?printer]
  (not [?function :seon.fn/projection-boundary])]
;; ?printer bound to ["clojure.core/println" "clojure.core/prn"
;;                    "clojure.core/pr-str" "clojure.core/print"]
```

---

## 2. `:seon.schema/references`

### Current state

The parser exists, is exercised on every projection activation, and its
result is thrown away at the database boundary.

- `direct-references*` (`src/seon/schema.clj:31-49`) walks a **compiled**
  Malli schema with Malli's own walker, records canonical registry keys
  behind `m/-ref-schema?`, and deliberately does **not** expand canonical
  refs transitively (`::m/walk-schema-refs`/`::m/walk-refs` guards at
  `schema.clj:47-48`). Local property registries ARE followed, so a
  canonical ref nested behind a recursive local registry is still seen.
- `canonical-reference-graph` (`schema.clj:402-431`) maps that over the
  complete forms map, returning a sorted `key -> #{key}` graph.
- It is computed at projection build (`schema.clj:1646-1648`) and at
  incremental activation (`schema.clj:1101-1103`), stored **in the
  in-memory projection** as `:seon.schema.projection/schema-dependencies`
  and its reverse (`schema.clj:1816-1819`), and consumed by
  `dependent-schema-keys` (`schema.clj:578-588`) and the cycle gate
  (`schema.clj:79-98`). `direct-references` is even public already
  (`schema.clj:554-576`).
- **Nothing writes it to the database.** The indexed schema row is
  `:seon.schema/schema` (`resources/seon/schemas/seon.schema.edn`, the
  `:schema` entry): `:seon.schema/key` (identity), `:seon.schema/form`
  (a string), `:seon.schema/generatable?`, optional `:seon.schema/shape`,
  `:seon.schema.admission/source`. No reference attribute exists.

### Registry versus indexed row (the distinction the task asked for)

The **registry** is the in-process merged Malli registry — the `forms` map
of `key -> definition` (`schema/registered-schemas`, `packaged-forms`) plus
compiled schemas and predicate Vars, held on the projection value. The
**indexed schema-row entity family** is one Datahike entity per registry
keyword, identified by `:seon.schema/key`
(`:seon.db/identity true`, `seon.schema.edn` `:key`), built by
`canonical-schema-rows` (`src/seon/schema.clj:2816-2838`) and admitted with
`:seon.schema.admission/source :core` — agent-registered schemas take
`:agent` (`resources/seon/schemas/seon.schema.admission.edn`). These rows
are *derived from* the registry, one-to-one on keyword keys, and carry only
the durable projection of a definition. The new edge belongs on **this row
family**, as a cardinality-many `:seon.db/ref` to other rows of the same
family through `[:seon.schema/key …]` — exactly the shape
`:seon.fn.arity/input-refs`/`output-refs` already uses
(`resources/seon/schemas/seon.fn.arity.edn`, and the lookup-ref
construction at `src/seon/program.cljc:276-280`).

### Derivation seam

One seam, one call. `canonical-schema-rows` (`schema.clj:2816-2838`)
already iterates `forms` and already merges derived facts per row
(`storable-properties-in`). Compute `canonical-reference-graph forms
predicate-functions` once outside the `keep`, then per row
`(when-let [refs (seq (get graph schema-key))]
   {:seon.schema/references (into #{} (map #(vector :seon.schema/key %)) refs)})`.
Both callers get it for free: `desired-rows` (`src/seon/fn.clj:1590-1596`)
builds `canonical-schemas` from exactly this function and already holds
`canonical-keys` at line 1595, and the agent-side registration path lands
on the same rows.

Ordering caveat: `desired-rows` commits schema rows in one phase
(`fn.clj:1650-1655` region, `commit-index-phase!`), and a lookup ref must
resolve to an entity present earlier in the same tx-data vector or already
committed. Follow the pattern already used for `:seon.ns/requires`
(`fn.clj:1687-1697`: bases first, relations second) — emit reference
assertions as a separate `commit-phase!` after the schema bases, alongside
`:seon.fn/calls` and `:seon.fn/keywords`.

Cycle safety is already guaranteed upstream: `assert-acyclic-references!`
(`schema.clj:79-98`) refuses a cyclic canonical graph before any row is
built, so the persisted edge set is a DAG by construction.

### Measured (probe over `packaged-forms`, 2026-08-17)

2,360 canonical schemas; 39 predicate symbols, all resolving; **1,325
schemas carry at least one direct canonical reference; 3,655 direct
schema→schema edges total**; 1,035 schemas reference nothing. So the
persisted relation is ~3.7k datoms — negligible beside the 26k of family 1.

### New attribute

```clojure
;; resources/seon/schemas/seon.schema.edn
:references [:set {:description "Canonical registry keys this schema's
                                 authored form references DIRECTLY. Derived
                                 by Malli's own walker; canonical refs are
                                 recorded, never expanded, so transitive
                                 closure is a Datalog walk, not a stored
                                 fact."}
             :seon.db/ref]
;; plus an optional entry in the :seon.schema/schema map
```

### Query unlocked

"What is the complete closure of a schema, and who is impacted by changing
it?" — both directions of one recursive rule:

```clojure
'[[(schema-reaches ?from ?to)
   [?from :seon.schema/references ?to]]
  [(schema-reaches ?from ?to)
   [?from :seon.schema/references ?next]
   (schema-reaches ?next ?to)]]

;; impact of changing :seon.blob/digest — every schema and every function
;; contract that transitively depends on it
'[:find ?dependent-key ?function-symbol
  :in $ % ?changed-key
  :where
  [?changed :seon.schema/key ?changed-key]
  (schema-reaches ?dependent ?changed)
  [?dependent :seon.schema/key ?dependent-key]
  [?arity :seon.fn.arity/input-refs ?dependent]
  [?function :seon.fn/arities ?arity]
  [?function :seon.fn/sym ?function-symbol]]
```

This is the join family 3 needs to become useful (see below), which is why
the PRD calls it "the keyword graph's spine".

---

## 3. Per-arity output refs — already built

### Current state

`:seon.fn.arity/output-refs` **already exists in the schema and is already
populated and already queried.**

- Declared: `resources/seon/schemas/seon.fn.arity.edn` — `:output-refs
  [:set :seon.db/ref]`, and an optional entry in `:seon.fn.arity/row`.
  `:input-refs` and `:guard-refs` sit beside it.
- Populated: `arity-row` (`src/seon/program.cljc:513-556`) computes
  `input-refs`, `output-refs` and `guard-refs` from the same
  `schema-references` walker (`program.cljc:261-280`, the twin of
  `schema.clj:31-49`) and asserts each when non-empty
  (`program.cljc:554-556`).
- Also stored per arity: `:seon.fn.arity/output` (an AST component ref) and
  `:seon.fn.arity/return-schema` (a `seon.schema.shape` row).
- Queried in production today: `output-schema-refs`
  (`src/seon/db.clj:1672-1684`, used by `seon.db/diff` to derive a result
  row identity), and `seon.sci.eval` reads both ref sets
  (`src/seon/sci/eval.clj:989-991`, `1060-1063`).
- Regressions already assert it: `test/seon/fn_test.clj:604-614` selects
  functions by `:input`/`:output` role for a given schema key, and
  `test/seon/custody_stability_test.clj:48-54` is *literally* the
  "which functions produce a value carrying key K" query.

**Nothing is missing at the arity layer.** The PRD's item 3 is satisfied by
code that predates it.

### What IS missing, precisely

`schema-references` records **direct** references only — the walker
explicitly refuses to expand a canonical ref
(`program.cljc:272-273`). So a function declared
`[:=> [:cat …] :my.note/note]` yields `output-refs #{:my.note/note}` and
nothing about `:my.note/text` inside it. The question "which functions
produce a value **carrying key K**" is therefore answerable today only when
K is named *directly* in the contract. Making it answerable for a nested
key requires the transitive schema→schema closure — i.e. **family 2**.
Families 2 and 3 compose; 3 alone was never the gap.

Second, smaller gap: an entry-key-level index exists structurally in the
shape rows (`resources/seon/schemas/seon.schema.shape.entry.edn` carries
`:seon.schema.map-entry/key-keyword`), reachable from
`:seon.fn.arity/return-schema`. That is a second, deeper path to the same
answer and should NOT be built as a third mechanism — prefer the family-2
closure over the registry graph and leave shape rows to the shape owner.

### Query unlocked (after family 2 lands)

```clojure
'[:find ?function-symbol
  :in $ % ?carried-key
  :where
  [?carrier :seon.schema/key ?carried-key]
  (or [?produced :seon.schema/references ?carrier]
      (schema-reaches ?produced ?carrier))
  [?arity :seon.fn.arity/output-refs ?produced]
  [?function :seon.fn/arities ?arity]
  [?function :seon.fn/sym ?function-symbol]]
```

Recommendation: retitle this family in the execution plan from "add output
refs" to "**close the output-ref join over the schema reference graph**",
and land the drift regression against the composed query, not against
`output-refs` (already covered twice).

---

## 4. Session dataflow edges

The six proposed edges are not one change. Three are already facts, one is
already computed and stored under a different shape, and two need a new
carrier. Per edge, the settlement site that has the data:

| Edge | Site holding the data | Fact today? |
|---|---|---|
| uses-var | `analyze-settlement`, `src/seon/cluster/run.clj:1076-1093` | **yes**, partially |
| uses-result | nowhere — no result-identity mechanism exists | **no** |
| reads | `evaluate`, `src/seon/cluster/loop.clj:1568-1587` | **yes**, coarse |
| writes | agent-side `transact!` call sites (`src/my/note.clj:199`, `src/my/plan.clj:220`) | **no** |
| defines | `def-rows`, `src/seon/cluster/loop.clj:319+` | **yes** |
| requires | `src/seon/sci/eval.clj:499`, `545`; agent ns row | partial, not per-receipt |

### uses-var — already a fact, first-party only

`analyze-settlement` (`run.clj:1076-1093`) calls `seon.fn/analyze-form`
(`fn.clj:552-604`) on the settled form's source, in the form's namespace,
and lands the returned `form-facts` on the **`:seon.cluster.run.form`
entity** (`run.clj:1088-1091`). Those facts are `:seon.fn/calls`,
`:seon.fn/keywords` and `:seon.test/subject` — all three declared on
`:seon.cluster.run.form/form`
(`resources/seon/schemas/seon.cluster.run.form.edn:23-31`) and asserted by
`relation-assertions` (`run.clj:1287-1300`). The loop's install gate calls
the same function (`loop.clj:248-251`).

So per-receipt uses-var **already exists** for first-party program symbols
and for qualified keywords. Its blind spot is identical to family 1: core
and library targets are filtered at `form-calls` (`fn.clj:531`). Fixing
family 1 fixes this edge with no additional work — one relaxation, two
consumers. No new attribute.

What is NOT covered: references to the agent's own `:seon.def` names.
`form-calls` resolves against `first-party-functions` seeded from
`runtime-analysis` rows, not from the agent's defs, so `(def x 1)` followed
by `(inc x)` produces no edge to the def. `seon.render.walk/form-symbols`
(`src/seon/render/walk.clj:737-746`) is the symbol extractor that generation
already uses for exactly this purpose. Landing a
`:seon.cluster.run.form/uses-def` ref set (targets `[:seon.def/key …]`) at
`analyze-settlement` is the honest way to close it — the def rows for the
same receipt are in hand one layer up (`loop.clj:602`, `evaluation-receipt`
`loop.clj:229`).

### uses-result — genuinely absent, and the PRD overstates it

The PRD says `result/<id>` references are "extractable by the reader". They
are not, because **no `result/<id>` binding exists**. The REPL entry grammar
(`resources/seon/schemas/seon.repl.edn`) has `:seon.repl/form`,
`/comment`, `/key`, `/subject` — no result identifier. Nothing in `src`
mints or resolves a result handle.

What DOES exist is the *other* direction the PRD names: `seon.print/references`
(`src/seon/print.cljc:614-637`) walks a settled **print node** and returns
every symbol plus every entity identity (lookup refs and identity-bearing
maps) structurally present in the printed value, using caller-supplied
identity attributes. Its single caller is `ordered-episode`
(`src/seon/render/walk.clj:812`), where it extends the generation frontier.
It is a pure function over a print node, and the print node is in hand at
settlement (`:seon.sci.admit/print-node`, `walk.clj:781`).

So the cheap, honest version of this edge is **not** "uses-result" but
**produces-identity**: at settlement, run `print/references` over the
receipt's print node and assert the entity identities it exposed as
`:seon.cluster.eval/produces` refs. Reachability then joins a later
receipt's `reads` (below) or `uses-var` against an earlier receipt's
`produces` — the dataflow chain the compaction algorithm needs, without
inventing a result-handle namespace. If the owner does want literal
`result/<id>` handles, that is a **new agent-facing surface** (a binding the
reader resolves), not an indexing edge, and should be priced separately.

### reads — stored, but as opaque replay evidence

`evaluate` (`loop.clj:1564-1587`) binds `db/*read-evidence-sink*`
(`src/seon/db.clj:77`, appended at `db.clj:210-213`) around the whole eval
and attaches `:seon.cluster.eval/read-evidence` plus
`:seon.cluster.eval/read-basis-transaction` to the evaluation
(`loop.clj:1583-1587`). Those ride into the receipt
(`evaluation-receipt`, `loop.clj:184-190`) and are stored as **component
entities** (`resources/seon/schemas/seon.cluster.eval.edn`,
`:read-evidence [:vector {:seon.db/component true} :seon.db/ref]`), each
carrying `:datahike.read/dependency-plan`, `:datahike.read/revision`, and
optionally the request and result.

The dependency plan is `[:or [:= :all] {:datahike.query.dependency/sources
[…{:datahike.query.source/attributes …}]}]`
(`resources/seon/schemas/seon.db.edn:26-37`). The attribute set is
therefore **already inside a stored value** but is not an indexed edge: you
cannot ask "which receipts read `:my.note/text`" without pulling and
walking every plan, and `:all` erases the answer entirely.

Recommended shape: derive at settlement, from the same plan the code
already builds, a flat `:seon.cluster.eval/reads-attribute` cardinality-many
`:qualified-keyword`, plus an explicit `:seon.cluster.eval/reads-all?`
marker for the `:all` plan. The second half matters more than the first —
this is precisely the project's recurring failure class: without the marker,
a `:all` plan reads as "read nothing", and an absent-signal check reports a
dead subtree as collectable. **The `:all` case must be loud, not empty.**

### writes — no receipt→transaction fact exists

Verified by census: `tx-meta` appears at 19 sites in `src`
(`rg -n "tx-meta" src`), and the two agent-facing writers stamp only the
agent — `src/my/note.clj:199` and `src/my/plan.clj:220`, both
`{:seon.db/user [:seon.cluster.agent/id agent-id]}`. `seon.db` itself
(`src/seon/db.clj`) sets no tx-meta at all. Provenance attributes are
`:seon.db/user` and `:seon.db/process`
(`resources/seon/schemas/seon.db.edn:146,188`), both indexed refs. Neither
names a receipt.

There is one narrow existing derivation — `declaration-written-by-run?`
(`run.clj:1250-1259`) joins a datom's `?tx` back to the receipt that
settled in that transaction — but that only reaches facts written *by the
settlement transaction itself*, not the transactions the agent's form ran
during evaluation.

So `writes` needs a carrier. The smallest correct one is a new provenance
attribute `:seon.db/receipt` (a ref to the `:seon.cluster.eval/receipt`
entity) stamped in `:tx-meta` at the agent write seams, exactly beside
`:seon.db/user`. That keeps provenance as minimal tx metadata (the standing
rule: never copied onto domain entities) and makes "what did this receipt
write" a history query with no new domain attribute:

```clojure
'[:find ?entity ?attribute ?value
  :in $ ?receipt-id
  :where
  [?receipt :seon.cluster.eval/id ?receipt-id]
  [?tx :seon.db/receipt ?receipt]
  [?entity ?attribute ?value ?tx true]]
```

Note this requires the receipt entity to exist **before** the agent's form
runs. It does: `run/receipt-start-tx` commits at `loop.clj:1617-1622`,
before evaluation. Good.

### defines — already a fact, one hop short

`def-rows` (`loop.clj:319-345+`) builds the agent's def rows at settlement,
stamping `:seon.def/key`, `:seon.def/agent`, `:seon.def/ordinal` and
`:seon.schema.admission/source :agent`. They ride the receipt as
`:seon.def/rows` (`loop.clj:229`, `loop.clj:602`) and land through
`def-rows-tx` (`run.clj:1513-1531`). `:seon.def/def`
(`resources/seon/schemas/seon.def.edn`) carries `/ordinal` but **no ref to
the receipt that defined it** — the ordinal is a number, not an edge, and
recovering the receipt means re-joining ordinal to run. One optional
`:seon.def/receipt` ref closes it; the data is in hand at
`run.clj:1513` (the request carries `:seon.cluster.eval/ordinal` and run id).
This is the edge the liveness roots need ("a def still standing is alive by
definition" — the root set is `[?def :seon.def/agent ?agent]`, and the
receipt ref is what lets liveness propagate *backwards* to the defining
entry).

### requires — persisted, but as agent-namespace state

`seon.sci.eval` reads and writes `:seon.ns/requires` on the agent's
namespace row (`src/seon/sci/eval.clj:499`, `545`, pulled at `729` and
`1336`; `src/seon/cluster/agent.clj:106`). The receipt records only
`:seon.cluster.eval/ns` and `:seon.sci.eval/ending-ns`
(`loop.clj:191-196`). So "this namespace is required" survives rebirth (as
the PRD says), but "**which entry introduced it**" does not. Closing it is
the same one-hop move as `defines`: assert the newly-introduced namespaces
as `:seon.cluster.run.form/introduces` refs at `analyze-settlement`, where
the before/after require sets are both derivable from the namespace row and
the analyzed form.

### Size

Medium–large, and it is the only family that touches the run loop. It
decomposes cleanly into four independently landable pieces, in this order:
(a) the `:all`-loud reads attribute, (b) the `:seon.db/receipt` tx-meta
stamp, (c) the two one-hop refs (`:seon.def/receipt`,
`:seon.cluster.run.form/introduces`), (d) `produces` from
`print/references`. Piece (b) is the one that must not be skipped: without
it, compaction's write-liveness has no signal at all, and per the standing
rule an absent signal would read as "nothing written."

### Query unlocked

Last-referenced-at-basis for one receipt — the collectability test:

```clojure
'[:find (max ?t) .
  :in $ ?receipt-id
  :where
  [?receipt :seon.cluster.eval/id ?receipt-id]
  [?receipt :seon.cluster.eval/run ?run]
  (or-join [?receipt ?later ?t]
    (and [?receipt :seon.cluster.eval/produces ?entity]
         [?later :seon.cluster.eval/reads-entity ?entity]
         [?later :seon.cluster.eval/at _ ?t])
    (and [?definition :seon.def/receipt ?receipt]
         [?definition :seon.def/key _ ?t]))]
;; nil => nothing later referenced this entry; it is a dead leaf.
```

The drift regression for this family must assert the **`:all` case**: a
receipt whose read plan is `:all` reports "reads everything", never an
empty attribute set.

---

## 5. Test→schema edges

### How `:seon.test/subject` is derived

By **declared metadata**, not by inference. `test-subject`
(`src/seon/fn.clj:286-291`) reads `:seon.test/subject` from the deftest's
metadata and accepts it only when it is a qualified symbol (or a string
that reads as one), returning the lookup ref `[:seon.fn/sym …]`. It is
applied at `fn.clj:344-345` (test branch of `var-row`), at `fn.clj:585` in
the per-form path, and committed as its own indexing phase
(`fn.clj:1699-1707`, `commit-phase! :seon.test/subject`). When the subject
is not yet indexed, `:seon.test/pending-subject` holds the symbol and is
resolved later by `pending-subject-resolution-tx` (`run.clj:1304-1316`) —
a genuine derived repair, not a stale mirror.

Reachability beyond the direct subject is already a rule set:
`test-reach-rules` (`fn.clj:669-697`) defines `test-reaches` transitively
over `:seon.fn/calls` *and* `:seon.test/subject`, and
`seon.fn/tests-reaching` / `gate-set` consume it.

### Is the subject's contract reachable at test-indexing time?

**Yes, in the same pass and in the same transaction sequence.**
`desired-rows` (`fn.clj:1588-1620`) builds test rows and calls
`add-contract-facts` on the complete row set at `fn.clj:1616-1620`;
`add-contract-facts` produces the `:seon.fn/arities` component rows through
`seon.program/contract-facts` (`program.cljc:560+`), whose `arity-row`
attaches `input-refs`/`output-refs`. By the time the phases commit
(`fn.clj:1687-1717`), test rows, subject refs and the subject's arity refs
are all present in one branch.

### Therefore: no new attribute is needed

"Which schemas does this test exercise" is already a three-hop join over
installed facts:

```clojure
'[:find ?test-symbol ?schema-key
  :where
  [?test :seon.test/sym ?test-symbol]
  [?test :seon.test/subject ?subject]
  [?subject :seon.fn/arities ?arity]
  (or [?arity :seon.fn.arity/input-refs ?schema]
      [?arity :seon.fn.arity/output-refs ?schema]
      [?arity :seon.fn.arity/guard-refs ?schema])
  [?schema :seon.schema/key ?schema-key]]
```

and the merge-gate criterion the PRD asks for — "which schemas lack
generative coverage" — is its complement, using the `:seon.schema/generatable?`
fact already stored on every schema row (`accretion/schema-row`,
`src/seon/test/accretion.clj:29-39`):

```clojure
'[:find [?schema-key ...]
  :where
  [?schema :seon.schema/key ?schema-key]
  [?schema :seon.schema/generatable? true]
  (not-join [?schema]
    [?arity :seon.fn.arity/input-refs ?schema]
    [?function :seon.fn/arities ?arity]
    [?test :seon.test/subject ?function])]
;; generatable schemas no test's subject contract touches
```

Storing a `:seon.test/schemas` attribute would be exactly the
hand-maintained mirror the derive-or-die law forbids. **Recommendation:
land this family as a named query function in `seon.test.selection` or
`seon.fn` plus one drift regression, and zero schema change.**

One honest caveat to record: coverage measured this way is *contract*
coverage of the declared subject only. A test with no `:seon.test/subject`
metadata contributes nothing, and `test-reaches` (`fn.clj:669-697`) would
widen it transitively at a real query cost. Which of the two definitions
the merge gate uses is an owner decision, not a technical gap — state it in
the execution plan rather than picking silently.

---

## Cross-cutting notes for the execution plan

1. **Family 1 relaxes two sites, not one** — `fn.clj:247` (whole-program
   indexing) and `fn.clj:531` (per-form / hot-reload). Landing only one
   makes indexed edges and settlement edges disagree, and the disagreement
   is silent.
2. **Family 1 is the only one with a real cost** — a 5× growth of
   `:seon.fn/calls` (5,295 → ~31,700 datoms) plus ~1.4k name-only fn rows.
   Measure `bin/seon init` wall time before and after in the same change
   and record the pair here.
3. **Families 2 and 3 are one query, not two features.** Ship family 2 and
   retitle family 3 as the composed join; the arity half is already green
   under two existing regressions
   (`test/seon/fn_test.clj:604-614`, `test/seon/custody_stability_test.clj:48-54`).
4. **Family 4 is the only run-loop change** and the only one where the
   project's recurring failure class bites: `:all` read plans and unstamped
   transactions both read as "nothing" to a naive liveness query. Every
   check written for this family must be asked what it reports when its
   subject is absent.
5. **Family 5 needs no schema at all.** If the execution plan allocates it
   an attribute, that is the derive-or-die defect arriving pre-installed.

## Probe provenance

Three probes were run against this tree at HEAD on 2026-08-17. They were
throwaway one-shot scripts under `/tmp`; their numbers are recorded above
and the derivations they used are named at `file:line` so any of the three
can be re-derived from the cited functions:

- kondo edge census — `seon.fn.analyzer/analyze` over `["src"]`, replaying
  `first-party-function-symbols` (`fn.clj:223-231`) and the
  `call-targets-by-caller` predicate (`fn.clj:246-247`) to split kept from
  dropped edges.
- schema reference census — `(#'seon.schema/canonical-reference-graph
  (seon.schema.edn/packaged-forms) predicate-functions)`, predicate
  functions rebuilt exactly as `schema.clj:1638-1645` does.
- single-file sanity check — the same analyzer over `src/seon/await.clj`,
  confirming `clojure.core` targets carry `:to`, `:name`, `:arity` and
  `:macro` before any Seon filter runs.

Anything here that will run again belongs under `test/`; nothing in this
document is maintained code.
