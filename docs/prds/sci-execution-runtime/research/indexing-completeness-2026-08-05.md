---
type: research
status: complete
tags: [prd, program-graph, indexing, malli, clj-kondo, database]
---

# Indexing completeness audit and implementation plan

## Verdict

No: the current index does not keep everything useful from either parse, and
not every useful question about the code is a Datalog query yet.

The current index is strongest at declaration identity and exact source. It
stores namespace, function, schema, and test rows; exact function source;
first-party call edges; literal qualified-keyword membership; complete Malli
contract source; and a component AST. F11 is the precedent for repairing this
class correctly: test call edges were added at the one index pass, after which
`tests-reaching` made “which tests exercise this function?” a query instead of
a naming convention (`AGENTS.md:771-778`; `src/seon/fn.clj:402-439`).

The two blocking compression points are:

1. `seon.fn.analyzer` asks clj-kondo for only part of its analysis and then
   copies only selected fields from that part (`src/seon/fn/analyzer.clj:15-29,35-98`).
   `seon.fn` compresses occurrences again into declaration-level sets and
   first-party-only edges (`src/seon/fn.clj:209-269,292-357`).
2. `seon.program` compiles the complete Malli contract but collapses all named
   input and output occurrences in an arity to bare sets of schema refs
   (`src/seon/program.cljc:93-112,259-281`). Position, argument binding,
   map key, optionality, nesting, and repeated occurrence are gone from that
   query surface even though the component AST retains much of the underlying
   parse as string-valued nodes (`src/seon/program.cljc:116-251`).

Ambient injection therefore cannot distinguish these declarations:

```clojure
[:=> [:cat :seon.db/database-value :string] :string]

[:=>
 [:cat [:map [:seon.db/db :seon.db/database-value]] :string]
 :string]
```

Both reduce to an arity whose `:seon.fn.arity/input-refs` contains the same
schema entity. The existing `[:set :seon.db/ref]` declaration makes that loss
structural (`resources/seon/schemas/seon.fn.arity.edn:1-10,25-33`).

The complete repair is one shared schema-shape graph plus joined per-arity
argument facts. It should replace the lossy ref sets, not sit beside them as a
second authority. The same shape identity should cover named and inline Malli
schemas. Analyzer occurrences should likewise become occurrence facts rather
than declaration-level sets. Then ambient injection, documentation, impact
selection, portability, and code exploration all query the same program graph.

## Reading record and dependency ledger

I read the following named first-party authorities end to end before reaching
the design:

- `docs/prds/sci-execution-runtime/plan/ambient-injection-prd-2026-08-05.md`;
- `src/seon/fn.clj`;
- `src/seon/program.cljc`;
- `src/seon/fn/analyzer.clj`;
- `resources/seon/schemas/seon.fn.edn`;
- `resources/seon/schemas/seon.ns.edn`;
- `resources/seon/schemas/seon.test.edn`;
- `resources/seon/schemas/seon.schema.edn`; and
- root `AGENTS.md` § “Everything is declared, recorded, and queryable”
  (`AGENTS.md:735-778`).

I also read the directly owning component declarations in full:
`resources/seon/schemas/seon.fn.arity.edn`,
`resources/seon/schemas/seon.fn.ast.edn`, and
`resources/seon/schemas/seon.fn.ast.entry.edn`.

The parallel
[naming-coherence audit](/docs/prds/sci-execution-runtime/research/naming-coherence-audit-2026-08-05.md)
landed before this report's commit boundary, and I read all 687 lines. Its R4
connection distinction is incorporated below. Its proposal to replace the map
key `:seon.db/db` with `:seon.db/database-value` is superseded for this lane by
the owner's explicit 2026-08-05 ruling that the single canonical database map
key is `:seon.db/db`.

The clj-kondo pin is `57252e07975710aa579b24f0d1b2b1e04195caa2`. I read its
analysis output specification in full at
`reference-code/clj-kondo/analysis/README.md:1-512`. That specification names
the opt-in categories and their fields, including locals, keyword occurrences,
protocol implementations, quoted symbols, Java definitions/usages, and
instance invocations (`reference-code/clj-kondo/analysis/README.md:14-30,77-223`).

The Malli pin is `80138076960e7820523b4cb932c5b5d1936d4e7f`. I read
`reference-code/malli/src/malli/registry.cljc:1-105` and
`reference-code/malli/src/malli/util.cljc:1-404` in full. In `malli.core` I
read the complete relevant seams: the `Schema`, `RefSchema`, `Walker`, and
`FunctionSchema` protocols (`reference-code/malli/src/malli/core.cljc:23-100`);
registry lookup, form creation, and walking (`reference-code/malli/src/malli/core.cljc:296-411`);
entry parsing (`reference-code/malli/src/malli/core.cljc:440-578`); map schemas
(`reference-code/malli/src/malli/core.cljc:1210-1353`); refs and function
schemas (`reference-code/malli/src/malli/core.cljc:1940-2300`); the public
`schema`, `form`, `properties`, `children`, and `walk` API
(`reference-code/malli/src/malli/core.cljc:2496-2625`); dereferencing and AST
(`reference-code/malli/src/malli/core.cljc:2819-2876`); `schema-walker`
(`reference-code/malli/src/malli/core.cljc:2902-2908`); and
`function-schema` (`reference-code/malli/src/malli/core.cljc:3074-3078`).

The relevant Datahike pin is
`c15272730e74fb3f8bba91f6361c268492a99ba7`. Cardinality-many attributes are
sets, which explains why the present ref projection necessarily collapses
occurrences (`src/seon/fn.clj:264-276`; `resources/seon/schemas/seon.fn.arity.edn:3-10`).

The active runtime boundary says build indexing analyzes first-party `src/`
and `test/` without evaluation and that canonical rows are owned by
`seon.fn.analyzer`, `seon.fn`, and `seon.program`
(`docs/prds/sci-execution-runtime/AGENTS.md:33-52`). Schema-resource changes
select a complete rebuild, while existing clusters remain sovereign
(`docs/prds/sci-execution-runtime/AGENTS.md:69-84`).

## Current pipeline

| Stage | What it has | What reaches the database |
|---|---|---|
| clj-kondo | Definition and usage occurrences with source spans; optional locals, keywords, protocols, symbols, and Java analysis | Nothing directly |
| `seon.fn.analyzer` | A selected, namespaced subset of configured clj-kondo output | Nothing directly |
| `seon.fn` | Exact declaration source plus reconstructed namespace context, selected metadata, first-party calls, and qualified keywords | Canonical namespace/function/test rows |
| `seon.program` | Compiled function arities, Malli AST, input/output/guard schemas and refs | Component arity and AST facts |
| schema population | Every named schema’s canonical EDN form | Global `:seon.schema` rows |

Clj-kondo supplies exact definition and usage spans
(`reference-code/clj-kondo/analysis/README.md:81-142`). The indexer uses those
spans to slice exact declaration source (`src/seon/fn.clj:94-111`) but does not
store the filename or source coordinates on the declaration row
(`src/seon/fn.clj:292-355`; `resources/seon/schemas/seon.fn.edn:13-38`). Thus
even data that helped build a fact is often unavailable to a later query.

## Gap audit: clj-kondo analysis

The dispositions below use:

- **KEEP** — the database retains the useful information as typed facts;
- **LOSE FIDELITY** — some projection survives, but occurrence, field, or
  relationship information is collapsed; and
- **DROP** — clj-kondo can emit it, but the current database graph does not.

### Priority 0 — blocks platform behavior or truthful dependency queries

| Analysis surface | Current disposition | Concrete questions currently unanswerable |
|---|---|---|
| Function arglists and arities | **LOSE FIDELITY.** Clj-kondo emits written arglists plus fixed and variadic arity data (`reference-code/clj-kondo/analysis/README.md:102-117`). The analyzer retains them (`src/seon/fn/analyzer.clj:65-73`), and `seon.fn` stores only one serialized arglist vector (`src/seon/fn.clj:325-331`). No argument entity or binding tree exists. | Which binding is argument 2 of arity 3? Is it a plain symbol, map destructure, sequential destructure, or rest binding? Which source binding corresponds to one Malli input slot? |
| Var usages / call sites | **LOSE FIDELITY.** The analyzer retains call occurrence spans, caller, target, arity, alias, and target arity metadata (`src/seon/fn/analyzer.clj:75-83`). `seon.fn` keeps only a set of first-party caller→callee refs, only when the usage has `:arity` (`src/seon/fn.clj:219-237`). | At which file:line and arity is this function called? Is the var passed as data instead of invoked? Which callers use an external function? Which call site selected a variadic arm? |
| Keyword occurrences | **LOSE FIDELITY.** Clj-kondo reports each occurrence, resolved namespace, alias, auto-resolution, and destructuring status (`reference-code/clj-kondo/analysis/README.md:158-179`). The analyzer retains most but not `:namespace-from-prefix`, `:reg`, or hook context (`src/seon/fn/analyzer.clj:85-98`). `seon.fn` then keeps only a set of qualified keywords per declaration and drops every unqualified occurrence (`src/seon/fn.clj:239-269`). | Where exactly is key X read? Is it a destructuring key? How many occurrences exist? Which alias spelling resolved to it? Which metadata declaration—not just namespace—contains it? |
| Exact file/location facts | **DROP.** Locations drive `exact-source`, then disappear (`src/seon/fn.clj:94-111,292-355`). File digests exist only in the transient manifest artifact (`src/seon/fn.clj:387-398,787-805`). | Which file and span defines a row? Which definitions changed when one file digest changed? What exact source occurrence produced this edge? |
| Non-function vars and macros | **DROP.** Clj-kondo var definitions include `:defined-by`, `:macro`, and all ordinary vars (`reference-code/clj-kondo/analysis/README.md:102-117`). `seon.fn` indexes only non-macro entries with arglists, plus tests (`src/seon/fn.clj:209-217,313-357`). | What constants, multimethods, macros, or other vars exist? What metadata and source define them? Which macro expansion boundary explains a call? |

These are Priority 0 because callability, placement, impact selection, ambient
injection, and source navigation all depend on the missing relationships. F11
shows the repair pattern: retain occurrence-derived edges once, then query the
transitive result (`src/seon/fn.clj:402-439,716-770`).

### Priority 1 — blocks understanding of code structure and portability

| Analysis surface | Current disposition | Concrete questions currently unanswerable |
|---|---|---|
| Locals and local usages | **DROP.** Clj-kondo can return declaration ids, written names, scopes, usage ids, and spans (`reference-code/clj-kondo/analysis/README.md:22-24,144-157`), but `analysis-config` does not enable them (`src/seon/fn/analyzer.clj:15-29`). | Which destructured local corresponds to a source binding? Is an injected value used? Where does an argument flow inside its function? Which local shadows another? |
| Namespace usages | **DROP after normalization.** The analyzer retains usage occurrences (`src/seon/fn/analyzer.clj:59-63,157-159`), but `seon.fn` reconstructs only declaration-level requires/aliases/refers/imports from the exact `ns` form (`src/seon/fn.clj:118-207`) and never consumes analyzer namespace usages (`src/seon/fn.clj:371-385`). | Where is an alias or referred namespace used? Which namespace dependency is declared but unused? Which source occurrence introduced a dependency? |
| Protocol implementations | **DROP.** The output is opt-in and includes protocol, method, implementation namespace, defining form, and exact spans (`reference-code/clj-kondo/analysis/README.md:25,181-189`). It is not requested (`src/seon/fn/analyzer.clj:15-29`). | Which vars implement protocol P/method M? Which defining form owns an implementation? Which implementation is portable? |
| Java class/member definitions and usages; instance invocations | **DROP.** All are opt-in (`reference-code/clj-kondo/analysis/README.md:27-30,201-223`) and none is requested (`src/seon/fn/analyzer.clj:15-29`). | Which functions depend on which JVM classes or methods? Which `.cljc` function has platform residue? Which Java member is called at a source span? |
| Quoted/data symbols | **DROP.** `:symbols` identifies namespaced symbols in quoted forms and EDN (`reference-code/clj-kondo/analysis/README.md:26,191-200`). | Which program rows name a render producer, provider, or other function as data rather than invoke it? Which source forms refer to a qualified symbol without a call edge? |

### Priority 2 — loses diagnostics, metadata, and future extensibility

| Analysis surface | Current disposition | Concrete questions currently unanswerable |
|---|---|---|
| Findings | **DROP after admission.** Findings are normalized (`src/seon/fn/analyzer.clj:120-121,169-174`); error-level findings refuse publication (`src/seon/fn.clj:359-369`), while warnings are not indexed. | What warnings belong to the currently published source? Which warning classes increased? What evidence justified admitting a warning? |
| Complete namespace/var metadata | **LOSE FIDELITY.** Clj-kondo can return all requested user metadata and common metadata (`reference-code/clj-kondo/analysis/README.md:32-43,85-117`). The analyzer retains `:meta`, doc, and a few direct flags (`src/seon/fn/analyzer.clj:53-73`). `seon.fn` stores only Malli schema, workload, sinks, projection boundary, capability, doc, and privacy (`src/seon/fn.clj:292-355`). | What other declared metadata does a var carry? Which metadata key occurs where? Which definitions are deprecated or added in a release? |
| Hook context and keyword registrations | **DROP.** Clj-kondo can attach configured hook context to usages and keywords (`reference-code/clj-kondo/analysis/README.md:44-49`) and keyword registrations under `:reg` (`reference-code/clj-kondo/analysis/README.md:175-179`). | Which hook-produced semantic fact explains an occurrence? Which declaration registered a keyword? |
| Defmethod dispatch facts | **DROP.** Var usages can carry `:defmethod` and `:dispatch-val-str` (`reference-code/clj-kondo/analysis/README.md:135-142`), but the analyzer’s selected keys omit them (`src/seon/fn/analyzer.clj:75-83`). | Which multimethod implementation handles a dispatch value? Which source span owns it? |

The implementation need not turn every clj-kondo scalar into a bespoke top-level
attribute. It does need occurrence entities whose typed core fields and
canonical extra-field facts make the whole configured output queryable. An
unknown field must fail the completeness gate, never disappear.

## Gap audit: Malli contracts

Malli accepts a `Schema` instance, an `IntoSchema`, vector syntax, or a
qualified registry reference (`reference-code/malli/src/malli/core.cljc:2551-2573`).
An inline anonymous schema is therefore already legal. `m/form` returns its
ordinary form (`reference-code/malli/src/malli/core.cljc:2575-2580`), and a
named registry lookup returns a pointer whose form is the name while `m/deref`
exposes its definition (`reference-code/malli/src/malli/core.cljc:2063-2104,2819-2833`).

A load-only probe against the pinned Malli verified the key behavior:

```clojure
{:named-form :example/database,
 :named-deref-form [:map [:seon.db/db :int]],
 :inline-form [:map [:seon.db/db :int]],
 :deref-equals-inline? true}
```

### What the current Malli index keeps

| Malli information | Current disposition | Consequence |
|---|---|---|
| Canonical authored function form | **KEEP.** `:seon.fn/spec` stores canonical EDN (`src/seon/fn.clj:333-339`; `src/seon/schema.clj:189-277`). | The contract can be recompiled exactly. |
| Function arity order, fixed/variadic classification, min/max | **KEEP.** Malli exposes these through `FunctionSchema` (`reference-code/malli/src/malli/core.cljc:2192-2202,2276-2295`); `seon.program` stores them (`src/seon/program.cljc:259-281`). | “What arities are declared?” is queryable. |
| Full input/output/guard AST | **KEEP structurally, LOSE query fidelity.** `m/ast` exposes typed nodes (`reference-code/malli/src/malli/core.cljc:2865-2876`), and `ast-node` handles current `:type`, `:value`, `:input`, `:output`, `:guard`, `:child`, `:key`, `:properties`, `:children`, `:keys`, `:registry`, and `:values` fields (`src/seon/program.cljc:185-251`). Generic keys and values are stored as `pr-str` strings (`src/seon/program.cljc:116-147`). | The data is recoverable, but Datalog cannot directly ask for a qualified map key, property, scalar, or schema path without parsing strings outside the query. |
| Named schema references | **LOSE FIDELITY.** Walking finds canonical refs (`src/seon/program.cljc:93-112`), but each arity stores only set membership (`src/seon/program.cljc:259-281`). | Key, argument position, nested path, optionality, repetition, and multiple occurrences are unanswerable. |
| Map entries | **LOSE FIDELITY.** Malli’s entry parser retains key, entry properties, child schema, and order (`reference-code/malli/src/malli/core.cljc:444-578`); map validation distinguishes required/optional keys (`reference-code/malli/src/malli/core.cljc:1265-1287`). The AST serializes them generically. | “Which functions take a db under key `:seon.db/db` in arity N?” is not a query. |
| Positional slot ↔ source binding | **DROP.** Malli knows the ordered `:cat`/`:catn` slots; clj-kondo knows written arglists. No join exists. | “Argument 2 of arity 3 is a map declaring `:seon.db/db` and is destructured as `{db :seon.db/db}`” is unanswerable. |
| Return shape identity | **LOSE FIDELITY.** The output AST and named output refs exist, but anonymous returns have no shared identity. | “Which functions return this exact unnamed shape?” and “has anyone named this return shape?” are unanswerable. |
| Inline schema identity | **DROP.** Inline forms stay embedded in the contract/AST but have no identity row. | Equal inline shapes cannot be joined across functions or to a registered schema. |
| Schema and entry properties | **KEEP structurally, LOSE query fidelity.** Malli preserves original properties (`reference-code/malli/src/malli/core.cljc:38-43,2582-2587`); `seon.program` stringifies property keys and values (`src/seon/program.cljc:116-126`). | Optionality, bounds, generators, render producers, and other properties cannot be joined as typed values. |
| Registry refs and local registries | **KEEP structurally.** Malli registries expose lookup and enumeration (`reference-code/malli/src/malli/registry.cljc:11-34,97-105`); the AST stores registry children (`src/seon/program.cljc:167-175,242-246`). | Exact recovery is possible, but equivalent named/inline shapes are not recognized. |
| Compile options | **DROP from the row.** The projection owns compile options and predicate bindings (`src/seon/schema.clj:1177-1201,1228-1235`); contract facts receive them transiently (`src/seon/program.cljc:283-313`). | The row cannot independently state the projection generation under which its fingerprint was computed. |

## Complete argument and return facts

### One joined model

Add one component vector `:seon.fn.arity/arguments`. Each
`:seon.fn.argument` row represents one Clojure argument binding in one arity
and carries:

- `:seon.fn.argument/order` — order in the source arg vector;
- `:seon.fn.argument/index` — zero-based runtime argument position;
- `:seon.fn.argument/rest?` — true only for the binding after `&`;
- `:seon.fn.argument/binding` — component ref to a faithful binding tree;
- `:seon.fn.argument/schema` — ref to the shared schema-shape row for the
  complete Malli slot;
- optional `:seon.fn.argument/rest-element-schema` — the repeated child shape
  for a variadic regex slot; and
- optional `:seon.fn.argument/label` — the declared `:catn` entry label.

Add `:seon.fn.arity/return-schema` and optional
`:seon.fn.arity/guard-schema`, both refs to shared schema-shape rows. These
replace `input-refs`, `output-refs`, and `guard-refs`; named schemas are reached
through each shape row rather than copied into three lossy sets.

The binding tree is source syntax, not a second schema. A
`:seon.fn.binding` component row carries:

- the exact canonical EDN in `:seon.fn.binding/form`;
- `:seon.fn.binding/shape` in `#{:symbol :map :sequential}`;
- optional `:seon.fn.binding/symbol` for a plain binding or `:as` binding;
- ordered component children for sequential destructuring and nested
  destructuring;
- component map-binding entries carrying the source key, local symbol or
  nested binding, and whether the spelling was explicit, `:keys`, `:strs`, or
  `:syms`;
- the `:or` defaults as canonical EDN and the `:as` binding as a child fact;
  and
- a rest child for nested sequential destructuring.

This is enough to query both the runtime contract and the source binding
without parsing source text. Local usage facts then link clj-kondo’s local id
to the corresponding binding leaf by source span. The full binding form stays
as the fidelity oracle; the child facts are the query index.

### Deterministic join algorithm

For every contracted function:

1. Parse each stored clj-kondo arglist under `*read-eval* false`. The written
   arglists are already retained (`src/seon/fn/analyzer.clj:65-73`;
   `src/seon/fn.clj:325-331`).
2. Derive a source signature: fixed argument count, or variadic minimum and
   the source index after `&`.
3. Compile `:seon.fn/spec` once. Malli returns each arity’s `:input`, `:output`,
   `:guard`, `:min`, `:max`, and fixed/variadic classification
   (`reference-code/malli/src/malli/core.cljc:2192-2202,2276-2295`).
4. Match source and Malli arities by fixed count or variadic minimum, not by
   incidental vector order. Both Clojure and Malli reject duplicate fixed
   arities and multiple variadic arms; Malli’s grouping check is explicit
   (`reference-code/malli/src/malli/core.cljc:272-286`). Refuse indexing unless
   the join is one-to-one.
5. Walk the input `:cat` or `:catn` in order. Join each fixed slot to the source
   binding at the same runtime index. Join the regex tail to the binding after
   `&`, retaining both the regex slot and its repeated element shape.
6. Emit argument rows, binding trees, return/guard refs, and every reachable
   schema-shape row in the same transaction as `:seon.fn/spec`. The existing
   recurring proof already requires spec, arities, and AST to share an
   assertion transaction (`test/seon/fn_test.clj:308-353`); extend that invariant.

This directly answers:

```clojure
;; Which arity-3 functions have argument index 1 whose map schema declares
;; :seon.db/db with the database-value shape?
[:find ?function-symbol
 :in $ ?db-key ?database-fingerprint
 :where
 [?function :seon.fn/sym ?function-symbol]
 [?function :seon.fn/arities ?arity]
 [?arity :seon.fn.arity/min 3]
 [?arity :seon.fn.arity/max 3]
 [?arity :seon.fn.arity/arguments ?argument]
 [?argument :seon.fn.argument/index 1]
 [?argument :seon.fn.argument/schema ?map-shape]
 [?map-shape :seon.schema.shape/entries ?entry]
 [?entry :seon.schema.shape.entry/key ?db-key]
 [?entry :seon.schema.shape.entry/schema ?value-shape]
 [?value-shape :seon.schema.shape/fingerprint ?database-fingerprint]]
```

No source parsing or naming convention is required at query time.

## Inline schemas and stable shape fingerprints

### Data model

Add a globally identified `:seon.schema.shape` row for every distinct schema
subtree reached from a registered schema or function contract:

- `:seon.schema.shape/fingerprint` — unique identity, lowercase SHA-256 hex;
- `:seon.schema.shape/form` — the canonical normalized form as EDN text;
- `:seon.schema.shape/comparison` — `:exact` or `:structural-only`;
- `:seon.schema.shape/entries` — component entry rows for entry schemas;
- `:seon.schema.shape/children` — ordered component edges for positional
  children; and
- `:seon.schema.shape/named-schemas` — refs to every global
  `:seon.schema/key` row with that fingerprint.

Every named schema row also points to its shape. Every argument, return,
guard, and nested map entry points to a shape. A named and inline declaration
with the same normalized form therefore converge on one entity automatically.
An unmatched inline form still has an entity, so these become queries:

- which functions accept this exact shape;
- which functions return it;
- which schema keys name it;
- which inline shapes have no name; and
- after a global schema is added, which prior inline uses now share its
  fingerprint.

Promotion is not an archaeological search. Adding a schema key attaches one
more name to the existing shape; later source edits may replace the inline
form with that key without changing the shape identity.

### Precise normalization

Normalization must be versioned by the Malli pin and must never assert more
semantic equality than the source establishes.

1. **Canonical EDN first.** Run `seon.schema/canonical-definition` so named
   predicate and generator callables become qualified symbols and anonymous or
   non-EDN callables refuse (`src/seon/schema.clj:189-277`).
2. **Compile against the complete immutable projection.** Use the same registry,
   predicate bindings, and options as contract admission
   (`src/seon/schema.clj:1177-1201,1228-1235`). Fingerprints from different
   projection generations are recomputed, never mixed.
3. **Use Malli’s parsed form.** `m/schema` accepts inline and registered forms,
   while `m/form` removes syntax-only wrappers such as `[:int]` and
   `[:int nil]` (`reference-code/malli/src/malli/core.cljc:2551-2580`). A probe
   against this pin returned `:int` for all three spellings.
4. **Resolve global registered references consistently.** A named pointer’s
   `m/form` is its key, while `m/deref` exposes the definition
   (`reference-code/malli/src/malli/core.cljc:2063-2104,2819-2833`). Recursively
   substitute global schema keys with their normalized definitions. Seon
   already builds the global reference graph and rejects cycles before
   compilation (`src/seon/schema.clj:279-331,1173-1176`), so this expansion is
   finite for admitted global forms.
5. **Walk all children through Malli.** Use `m/walk` and `schema-walker`, which
   rebuild parsed schemas from walked children
   (`reference-code/malli/src/malli/core.cljc:2612-2625,2902-2908`). This
   ensures entry properties and child schemas come from Malli’s parse rather
   than a second handwritten grammar.
6. **Canonicalize only proved spelling equivalences.** Sort `:map` entries by
   the canonical encoding of their unique key; remove entry
   `{:optional false}` because Malli treats only true as optional
   (`reference-code/malli/src/malli/util.cljc:14-23`); and remove map
   `{:closed false}` because the map validator activates closedness only when
   truthy (`reference-code/malli/src/malli/core.cljc:1223-1229,1282-1287`).
   Preserve order for `:cat`, `:catn`, `:tuple`, `:and`, `:or`, `:orn`, and
   every other sequence schema. Preserve labels.
7. **Retain every property by default.** Bounds, optionality, closedness,
   generators, predicate symbols, render producers, transform-related data,
   and even documentary properties remain in the exact shape. There is no
   hand-maintained “semantic property” allowlist. Maps and sets are serialized
   in canonical order; vectors and sequences retain order. Seon already has a
   canonical ordinary-data serializer with exactly those rules
   (`src/seon/schema.clj:333-381`).
8. **Hash collision cannot merge shapes.** SHA-256 the UTF-8 bytes of the
   canonical data string using the existing SHA-256 owner
   (`src/seon/schema.clj:389-399`). On an existing fingerprint, compare the
   stored normalized form byte-for-byte. A mismatch is a loud core fault, not
   a merge.

### What equality does and does not mean

Fingerprint equality is decidable and honest for finite canonical EDN forms:
same normalized structure, same properties, same qualified predicate/generator
symbols, and the same referenced global definitions.

It is not a proof of extensional semantic equivalence:

- two different `:fn` predicate symbols may accept the same values, but deciding
  that for arbitrary functions is not possible; they keep different forms;
- two generator symbols may generate the same distribution, but structural
  equality recognizes only the same canonical declaration;
- local `:registry` / `:ref` recursion is a finite declaration graph, but
  alpha-equivalence after renaming local ref labels is not claimed in the first
  implementation. Exact forms with the same labels match; forms containing a
  recursive local registry are marked `:structural-only` until a separately
  proved canonical graph-labeling algorithm exists; and
- properties are never discarded merely because they appear documentary.
  Two declarations with different properties remain different shapes unless
  Malli itself and a dedicated proof establish that property as spelling-only.

This conservative boundary permits false negatives for renamed local recursive
labels; it forbids false positives between genuinely different declarations.

## Canonical database and connection keys

### Ruling reconciliation

The registry confirms the immutable database-value schema is named
`:seon.db/database-value` (`resources/seon/schemas/seon.db.edn:20-26`). The
canonical ambient map key is separately `:seon.db/db`. Existing request schemas
already express the correct key/schema pair
(`resources/seon/schemas/seon.sci.eval.edn:1-4,42-50`). Key and schema should
not be forced to share a spelling: the key names the value’s role in a map;
the schema names the Datahike value shape.

The connection registry currently declares three shapes:

- `:seon.db/connection` — a live unreleased Datahike connection with an
  identity-only projection (`resources/seon/schemas/seon.db.edn:8-19`);
- `:seon.store/branch-connection` — another live unreleased branch connection
  (`resources/seon/schemas/seon.store.edn:1-7`); and
- `:seon.store/connection` — a process-root store connection that may already
  be released (`resources/seon/schemas/seon.store.edn:8-25`).

The first two are the same ambient live branch-connection concept. Keep only
`:seon.db/connection` as both its map key and schema. The third is a different
role owned by the process-root store value and must remain
`:seon.store/connection`; collapsing a possibly released root connection into
the ambient-live contract would change its definition.

| Concept | Canonical key | Canonical schema | Action |
|---|---|---|---|
| Immutable Datahike database value in an argument map | `:seon.db/db` | `:seon.db/database-value` | Keep; never use `:seon.db/database-value` as a map key |
| Live ambient cluster branch connection | `:seon.db/connection` | `:seon.db/connection` | Replace `:seon.store/branch-connection` and request-local aliases when they require a live connection |
| Boot-instance connection, which may survive into teardown after release | `:seon.boot/cluster-connection` | `:seon.store/connection` | Keep the role key; it is not the live ambient contract (`resources/seon/schemas/seon.boot.edn:37-55`) |
| Process-root store’s main connection, live or released | `:seon.store/connection` | `:seon.store/connection` | Keep; it is a lifecycle role, not an ambient battery |
| Dynamic runtime source for the live ambient connection | `seon.db/*conn*` | n/a | Keep Datahike/Clojure’s conventional connection abbreviation; after general injection, only the provider/binding seam reads it (`src/seon/db.clj:65-67`) |

### Complete live-source rename census

The census below is over current `resources/`, `src/`, and `test/` at
`c95958432`. Dated research and archived issues are evidence and should not be
mechanically rewritten; current authorities should receive a supersession note
or direct correction.

#### `:seon.store/branch-connection` → `:seon.db/connection`

There are 174 occurrences on 173 lines. Every listed live line changes in the
same atomic naming wave:

- `resources/seon/schemas/seon.cluster.loop.edn` — 3, 4
- `resources/seon/schemas/seon.cluster.wake.edn` — 14, 32
- `resources/seon/schemas/seon.effect.edn` — 53, 54
- `resources/seon/schemas/seon.fn.edn` — 41, 43
- `resources/seon/schemas/seon.render.walk.edn` — 25, 27
- `resources/seon/schemas/seon.render.web.edn` — 50, 52
- `resources/seon/schemas/seon.sci.eval.edn` — 121, 123
- `resources/seon/schemas/seon.source.edn` — 18, 19
- `src/seon/blob.clj` — 141, 158, 216, 226
- `src/seon/cluster.clj` — 758, 759, 761, 785, 1180, 1294, 1578, 1810
- `src/seon/cluster/agent.clj` — 236, 416, 501, 653
- `src/seon/cluster/curate.clj` — 180
- `src/seon/cluster/loop.clj` — 541, 714, 761, 970, 1157, 1183, 1222, 1499, 1521, 1698
- `src/seon/cluster/source.clj` — 169
- `src/seon/cluster/store.clj` — 383
- `src/seon/config.clj` — 414
- `src/seon/effect.clj` — 410
- `src/seon/error.clj` — 871
- `src/seon/eval/drive.clj` — 279
- `src/seon/fn.clj` — 1070, 1073, 1159
- `src/seon/reconcile.cljc` — 415
- `src/seon/render.clj` — 88, 223, 514, 516, 522
- `src/seon/render/transcript.clj` — 570
- `src/seon/render/web.clj` — 324, 347, 514, 638, 679, 861, 1259
- `src/seon/schedule.clj` — 231, 347, 373
- `src/seon/sci/eval.clj` — 1281, 1339, 1346, 1360, 1590, 1602
- `src/seon/sci/kernel.clj` — 338
- `src/seon/search.clj` — 265
- `src/seon/shell/jvm.clj` — 286
- `test/seon/ai_stream_fold_test.clj` — 335, 362
- `test/seon/background_blob_test.clj` — 121
- `test/seon/blob_settlement_test.clj` — 37, 41
- `test/seon/blob_threshold_test.clj` — 26
- `test/seon/cluster/agent_test.clj` — 115, 143
- `test/seon/cluster/armed_test.clj` — 292
- `test/seon/cluster/curate_test.clj` — 61
- `test/seon/cluster/loop_test.clj` — 188, 760
- `test/seon/cluster/prompt_test.clj` — 71
- `test/seon/cluster/source_test.clj` — 31, 41
- `test/seon/cluster/turn_test.clj` — 179, 253, 267, 283, 300, 345, 369,
  425, 467, 521, 554, 584, 609, 641, 735, 752, 791, 942, 1088, 1131,
  1164, 1188, 1248, 1287, 1319, 1375, 1414, 1434, 1481, 1522, 1586,
  1724, 1777, 1832, 1850, 1932, 1949, 1995, 2011, 2093, 2217, 2247,
  2284, 2317, 2498, 2593, 2662, 2720, 2752, 2798, 2843, 2981, 3070
- `test/seon/concurrency_independence_test.clj` — 418
- `test/seon/custody_stability_test.clj` — 20, 24
- `test/seon/effect_test.clj` — 54
- `test/seon/error_test.clj` — 76, 229
- `test/seon/fn_test.clj` — 378, 383, 605, 637, 894, 916
- `test/seon/gen/loop_test.clj` — 92, 121, 267, 428, 467
- `test/seon/reconcile_test.clj` — 46
- `test/seon/render/transcript_test.clj` — 616
- `test/seon/render/web_test.clj` — 109
- `test/seon/schedule_test.clj` — 161
- `test/seon/sci/eval_test.clj` — 1153
- `test/seon/sci/session_image_child.clj` — 30
- `test/seon/shell/jvm_test.clj` — 79, 276
- `test/seon/test_support.clj` — 68
- `test/seon/web/jvm_test.clj` — 199

#### Other connection spellings

To satisfy “single canonical key per concept,” the request-local live aliases
are part of the same wave, while lifecycle role keys remain distinct:

- **KEEP `:seon.boot/cluster-connection`.** It is the optional connection held
  by a boot instance and may be observed during teardown after release, so it
  is not the live ambient value (`resources/seon/schemas/seon.boot.edn:37-55`).
  Its 77 current occurrences are: schema
  `resources/seon/schemas/seon.boot.edn:49`; production
  `src/seon/cluster.clj:155,185,259,334,361,1889,2123,2220`,
  `src/seon/cluster/curate.clj:158,307`,
  `src/seon/eval/drive.clj:279,301`, `src/seon/oversight.clj:69`, and
  `src/seon/test/runner.clj:583`; operator/script consumers
  `script/seon/fresh_operator.clj:1456,1481,1517,1925`; inspect-AI consumers
  `src-inspect-ai/src/seon_inspect/tasks/mvp_graduation.py:266,475,510,541,617`;
  tests in
  `test/seon/cluster/armed_test.clj:74,121,185,246,312,330,450,480`,
  `test/seon/cluster/boot_test.clj:210,642,717,760,785,792,808,827,856,893,926,1080,1098,1105,1117,1124,1129,1181,1197,1202,1231,1258`,
  `test/seon/cluster/curate_test.clj:26,53,78`,
  `test/seon/cluster/mcp_test.clj:147,212,251,282`,
  `test/seon/cluster/program_restart_test.clj:176,279`,
  `test/seon/concurrency_independence_test.clj:80,158,419,485`,
  `test/seon/config_application_test.clj:246,336`,
  `test/seon/dev/fresh_operator_test.clj:1146,1178,1290,1304`,
  `test/seon/eval/drive_test.clj:38`, `test/seon/oversight_test.clj:50,81`,
  and `test/seon/sci/eval_instrumentation_test.clj:72`.
- **REPLACE `:seon.config/connection` with `:seon.db/connection`.** Its live
  occurrences are:
  `resources/seon/schemas/seon.config.edn:7`, `src/seon/config.clj:466`,
  `script/seon/fresh_operator.clj:1924`,
  `test/seon/cluster/loop_test.clj:185,536,790,863`,
  `test/seon/cluster/mcp_test.clj:140,247,278`,
  `test/seon/config_application_test.clj:302`,
  `test/seon/config_test.clj:118,121,315,321,351,371,376`,
  `test/seon/render/value_options_test.clj:16`,
  `test/seon/repl_parity_test.clj:212`, and
  `test/seon/test_support_test.clj:83`.
- **REPLACE `:seon.cluster.wake/connection` with `:seon.db/connection`.** Its
  26 live occurrences are:
  `resources/seon/schemas/seon.cluster.wake.edn:13,31`,
  `src/seon/cluster.clj:1743,1809`, `src/seon/cluster/wake.clj:199,252`,
  `test/seon/cluster/agent_test.clj:594,656,1502,1565`,
  `test/seon/cluster/message_test.clj:616,631`,
  `test/seon/cluster/wake_test.clj:76,117,150,177,248,274,297,329,365,385`,
  `test/seon/render/web_test.clj:132,161`, and
  `test/seon/schedule_test.clj:183,201`.
- **REPLACE `:seon.test-support/connection` with
  `:seon.db/connection`.** Its two fixture-state occurrences are at
  `test/seon/test_support.clj:105,260`.

The parallel naming audit independently found that the live schemas are
duplicates and that the boot/store roles have lifecycle semantics
([R4](/docs/prds/sci-execution-runtime/research/naming-coherence-audit-2026-08-05.md#r4--high-cross-owner-cost-a-live-datahike-connection-and-a-database-value-change-names-at-boundaries)).
This report accepts that connection boundary while applying the later owner
ruling for `:seon.db/db`.

#### Keep `seon.db/*conn*`, then narrow its ownership

The dynamic Var is declared and read throughout `src/seon/db.clj:4,65,95-119,142-153,977,1116`.
Other production readers/binders are `src/my/background.clj:54`,
`src/seon/fs/jvm.clj:453`, `src/seon/render.clj:521-523`,
`src/seon/sci/eval.clj:1531,1599`, `src/seon/sci/kernel.clj:335`,
`src/seon/search.clj:411`, and `src/seon/web/jvm.clj:301,303,384,389`.
Tests occur at `test/my/background_test.clj:36`,
`test/seon/db_test.clj:143,146,207,225,234,239,246,249,308,314,350,353,393,450,487,518,531`,
`test/seon/fs/jvm_test.clj:304`,
`test/seon/render_simplification_test.clj:70`,
`test/seon/sci/eval_test.clj:1090,1093,1114`,
`test/seon/search_test.clj:17`, and `test/seon/web/jvm_test.clj:194,296,343`.
Current skill examples also name it at
`.agents/skills/datahike/SKILL.md:196,200,333`; the generic bad/good example
uses an unqualified `*conn*` at
`.agents/skills/data-oriented-clojure/SKILL.md:186-189`.

After ambient injection lands, only the evaluation binding and declared
provider should read `*conn*`. Delete `current-database-value` and
`current-connection` as per-function bespoke elision owners
(`src/seon/db.clj:95-119`); injected explicit arguments then flow through the
ordinary `seon.db` interfaces. Deep calls still receive the same evaluation’s
value through the one general invocation seam, not by adding another resolver.

#### Current authorities and historical evidence

Update current authority occurrences in the same wave:

- root `AGENTS.md:760-762`;
- `docs/prds/sci-execution-runtime/plan/ambient-injection-prd-2026-08-05.md:35-58,71-92,138-140`;
- the current working-edge statements at
  `docs/prds/sci-execution-runtime/plan/unsettled.md:738-739`; and
- the active MCP PRD example at `docs/prds/mcp-surface/README.md:624-632`; and
- any durable architecture or localized instruction that the final naming
  wave’s search finds.

The database skills' `seon.db/*conn*` examples and the Flow degraded-start
skill's `:seon.boot/cluster-connection` examples remain correct and receive no
rename (`.agents/skills/datahike/SKILL.md:196,200,333`;
`.agents/skills/seon-flow-architecture/references/degraded-start.md:18-44`).

Do not mechanically rewrite dated research, archived issues, visual captures,
or old plan evidence. Their old spellings are evidence. Add a concise
supersession sentence only where a dated document is still linked as current
guidance.

### Does existing cluster data require migration?

Yes for program data, no for domain attributes.

A load-only registry probe over `seon.schema/canonical-database-attributes`
reported all four names absent as installed database attributes:

```clojure
{:seon.db/db false,
 :seon.db/database-value false,
 :seon.db/connection false,
 :seon.store/branch-connection false}
```

That result follows the schema owner’s rule that only entity entries and forms
with persistence facets become database attributes
(`src/seon/schema.clj:2419-2432`). Therefore the rename does not migrate
ordinary domain datoms under an old attribute.

Existing populated clusters do carry old names as program data: function specs
are strings, schema rows store forms, and the parsed AST/ref components are
committed beside them (`src/seon/fn.clj:1031-1058,1118-1154`;
`src/seon/program.cljc:259-313`). Existing clusters remain sovereign and are
not source-synchronized (`docs/prds/sci-execution-runtime/AGENTS.md:69-84`).

Migration therefore has two explicit cases:

1. A cluster containing only republished first-party program data is migrated
   by `bin/seon init` for the new `current-src`, followed by explicit
   `bin/seon init CLUSTER --force` for each cluster selected by the operator.
2. A cluster with agent-authored functions must first query affected functions
   through the old `input-refs` plus literal-keyword occurrences, revise their
   source/contract with the Clojure reader and curation proof, and only then
   remove the old schema. Never regex-rewrite a stored source string. A blind
   refork would discard sovereign agent-authored program facts; an in-place
   keyword substitution could change source semantics without proof.

## Ordered implementation plan

### Unit 0 — seal names and executable falsifiers

1. Record the canonical key ruling in the ambient PRD and current architecture.
2. Add focused source fixtures containing:
   - a positional database argument;
   - a database value nested under `:seon.db/db` in argument index 1 of a
     three-argument function;
   - plain-symbol, map, sequential, nested, and rest bindings;
   - fixed and variadic multi-arity contracts;
   - named and equal inline schemas;
   - an unmatched inline schema; and
   - a recursive local-registry schema.
3. The initial falsifiers must show that current `input-refs` returns the same
   answer for positional and map-key cases, and that equal named/inline forms
   have no common database identity.

Blast radius: tests and docs only. Exit: failures demonstrate the exact missing
facts, not a proposed implementation detail.

### Unit 1 — add schema-shape and argument declarations

1. Add `resources/seon/schemas/seon.schema.shape.edn`,
   `seon.schema.shape.entry.edn`, `seon.fn.argument.edn`, and
   `seon.fn.binding.edn` with component connections and open maps.
2. Add `:seon.schema/shape` to named schema rows;
   `:seon.fn.arity/arguments`, `/return-schema`, and `/guard-schema` to arity
   rows; and shape links on entry/argument facts.
3. Add a declared ambient-provider row shape keyed by ambient map key and
   connected to the required schema shape plus qualified provider symbol.
4. Do not remove old ref sets yet; this unit is additive so the new encoder can
   be proven before consumer conversion.

Blast radius: schema resources, Malli→Datahike derivation tests, fixture
population. Exit: the merged schema population compiles and generates; every
new component attribute is installed on a fresh in-memory database.

### Unit 2 — one normalized shape encoder

1. Implement normalization and SHA-256 identity in `seon.schema`, reusing
   `canonical-definition`, the projection registry, `m/form`, `m/deref`,
   `m/walk`, `canonical-data-string`, and `sha-256`.
2. Encode every reachable contract/schema subtree exactly once per projection.
3. Assert stored-form equality on fingerprint reuse.
4. Mark recursive local-registry forms `:structural-only`; do not claim
   alpha-equivalence.
5. Add round-trip properties: shape graph → canonical normalized form equals
   the form that produced the fingerprint.

Blast radius: `src/seon/schema.clj`, schema-shape declarations, focused schema
tests. Exit: equal named/inline forms share a fingerprint; different property,
order-sensitive child, predicate symbol, or generator symbol does not.

### Unit 3 — join every argument and return in `seon.program`

1. Extend `contract-facts` to receive the stored source arglists as well as the
   spec and projection (`src/seon/program.cljc:283-313`).
2. Implement the signature join and binding-tree projection described above.
3. Emit argument, return, guard, and shared shape rows atomically.
4. Add a loud refusal for unpaired source/Malli arities or unmatched fixed/rest
   slots.
5. Extend exact replacement ownership: argument and shape component roots must
   retract with the function row just as arities and AST do now
   (`src/seon/program.cljc:425-466`). Shared shape entities are content-addressed
   and are not component-owned by one function.

Blast radius: `src/seon/program.cljc`, `src/seon/fn.clj`, component schemas,
`test/seon/fn_test.clj`, SCI runtime publication tests. Exit: every contracted
arity has a complete argument vector and one return shape; every source binding
and Malli slot joins exactly once.

### Unit 4 — expand clj-kondo occurrence indexing without silent drops

1. Enable `:locals`, `:keywords`, `:protocol-impls`, `:symbols`, Java
   definitions/usages/member definitions, and `:instance-invocations` from the
   pinned output surface (`reference-code/clj-kondo/analysis/README.md:14-30`).
2. Replace declaration-level call and keyword sets with occurrence component
   rows carrying holder, target/value, occurrence kind, flags, and exact source
   span. Derive simple membership and call reachability from those rows.
3. Index namespace usages, non-function vars, macros, protocol methods,
   defmethod dispatch, local declarations/usages, interop, symbols, warnings,
   and file/digest facts under their owning attributes.
4. Keep exact raw canonical EDN for each occurrence as the fidelity oracle,
   while typed attributes make high-value fields directly queryable.
5. Retain external call targets as symbol facts; first-party refs are an
   additional resolved connection, not a filter that deletes external calls.

Blast radius: analyzer config/normalizers, program schemas, `seon.fn` artifact
construction, index size and publication time. Measure row count, index bytes,
complete-build time, and incremental-file time before accepting. Exit: every
documented category emitted by the pin is either indexed or accompanied by an
explicit disposition fact and reason.

### Unit 5 — convert consumers, then delete lossy facts

1. Convert SCI `doc` from `input-refs`/`output-refs` to argument and return
   shape queries (`src/seon/sci/eval.clj:848-852,912-928`).
2. Convert schema-usage guards and any other consumer found by the final `rg`
   sweep to shape and argument connections.
3. Convert F11/dependent-test logic to derive from occurrence call facts while
   preserving `seon.fn/tests-reaching`’s public answer
   (`src/seon/fn.clj:402-439`).
4. Remove `:seon.fn.arity/input-refs`, `/output-refs`, and `/guard-refs` from
   schemas, `arity-row`, pull patterns, tests, docs, and exact-replacement
   expectations. Remove or radically shrink the generic function AST only
   after a query and round-trip coverage comparison proves the shared shape
   graph retains every useful field. A full duplicate AST is not a permanent
   second authority.

Blast radius: `src/seon/sci/eval.clj`, usage guards, render/doc output, tests,
schema resources. Exit: `rg` finds no live consumer or declaration of the
lossy refs; all previous query answers are derivable from the new facts.

### Unit 6 — canonical database/connection naming wave

1. Rename every live path in the census together. Delete
   `:seon.store/branch-connection` and request-local live-cluster aliases; use
   `:seon.db/connection`.
2. Keep `seon.db/*conn*`, but constrain its reads to the evaluation/provider
   seam.
3. Keep `:seon.boot/cluster-connection` and `:seon.store/connection` for their
   distinct lifecycle roles.
4. Update root/localized instructions, active architecture/PRD text, current
   skills, and tests in the same wave. Dated evidence receives no mechanical
   rewrite.
5. Query affected durable program rows before removing the old schema key.
   Prove agent-authored revisions or explicitly refork selected clusters.

Blast radius: 174 `branch-connection` occurrences, 49 request-local alias
occurrences, schemas, code, tests, operator scripts, the active MCP/ambient
docs, and any current localized authority found by the final sweep. Exit:
current live `resources/`, `src/`, `test/`, active authorities, and skills have
zero superseded live-connection spellings; lifecycle roles and `*conn*` remain
only where the census says they are distinct; schema removal succeeds because
no contract references remain.

### Unit 7 — ambient injection consumes one queryable plan

1. Declare provider rows for:
   - key `:seon.db/db`, schema-shape fingerprint of
     `:seon.db/database-value`, provider returning `@*conn*`; and
   - key `:seon.db/connection`, schema-shape fingerprint of
     `:seon.db/connection`, provider returning `*conn*`.
2. One Datalog query for a function identity returns all matching argument
   positions and nested map entries across its arities. It joins ambient rows
   to shape fingerprints; it does not inspect source or schema strings.
3. Compile the query result into a process-local plan keyed by function
   identity plus the transaction that asserted its contract facts. Runtime
   publication evicts that identity; new acquisition starts with an empty
   cache.
4. Empty result is the hot path: one cache lookup and direct invocation. No
   per-call database query, schema walk, allocation, or map construction.
5. For a present map argument, fill only missing declared ambient keys. A
   supplied key—including supplied nil—wins and then ordinary contract
   validation decides validity.
6. For positional injection, exact full-arity calls always win. A shorter call
   may be expanded only when exactly one target arity and one ordered set of
   ambient positional slots produce a valid arity. Insert values at their
   recorded indexes. Refuse an ambiguous plan at plan compilation rather than
   guessing between overloaded arities.
7. A variadic ambient tail is not auto-filled in the first implementation; it
   is fully indexed, but plan compilation marks it unsupported because “how
   many ambient repetitions?” has no declared answer. A later provider may add
   an explicit cardinality fact.
8. Apply the same invocation owner to scheduled fires. Delete
   `current-database-value` and `current-connection`; no bespoke `seon.db`
   ambient path remains.

Blast radius: SCI invocation, scheduled-fire invocation, provider schema/data,
`seon.db` elision, cache invalidation, performance. Exit: positional and map
database injection both work; explicit caller values win; unavailable
providers return flat errors; empty-plan overhead is measured against current
calls and is statistically indistinguishable at the chosen benchmark scale.

### Unit 8 — complete republication and cluster migration

Schema-resource changes force `:full-rebuild` today
(`src/seon/fn.clj:888-956`). Therefore:

1. freeze source owners;
2. run the focused schema/index/analyzer/runtime gates;
3. run `bin/seon init` for a complete scratch `current-src` publication—never
   `init --changed` as the claimed proof;
4. fork a fresh isolated cluster from that published commit and prove Datalog
   answers, named/inline matching, positional/map injection, and source spans;
5. emit a pre-migration query report for each existing selected cluster;
6. revise/prove agent-authored affected rows or explicitly
   `bin/seon init CLUSTER --force`; and
7. prove reopen/reset behavior because fixture population is not boot
   (`docs/prds/sci-execution-runtime/AGENTS.md:69-98`).

## Standing guard against future silent parse loss

The guard must be generated from actual dependency output and actual encoder
behavior, not a second roster of remembered fields.

### Clj-kondo coverage contract

1. A fixture exercises every configured category and representative form at
   the pinned clj-kondo SHA.
2. The analyzer returns two values from the same traversal:
   `:seon.fn.analysis/facts` and `:seon.fn.analysis/coverage`.
3. Every raw category/field encountered is marked by the encoder that handled
   it as either:
   - `:typed-fact`, naming the emitted attribute;
   - `:canonical-extra`, naming the generic occurrence-field fact; or
   - `:deliberately-dropped`, carrying a qualified reason and evidence.
4. The test computes raw `{category field}` pairs from clj-kondo’s actual
   result and subtracts the coverage produced by that same encoding run. Any
   remainder fails. A new dependency field therefore fails on the first pin
   update instead of disappearing.
5. A second computed check reconstructs each occurrence’s canonical raw EDN
   from its typed plus extra facts and compares it to the analyzer result.

There is no separate hand list. The coverage record is emitted by the code
path that either stores or explicitly rejects each observed field.

### Malli coverage contract

1. Walk every admitted default/project schema kind and the argument fixture
   corpus with `m/ast` and `m/walk`.
2. The shape encoder emits coverage for every AST key/path it visits. Compare
   the actual AST leaf paths with emitted shape/binding facts; an unknown Malli
   AST field fails.
3. Reconstruct every normalized form from the stored shape graph and require
   byte equality with the pre-hash canonical form.
4. Generate contracts at fixed seeds from the admitted registry and assert:
   source arity ↔ Malli arity is bijective, every slot has one argument fact,
   every arity has one return shape, every fingerprint resolves to exactly one
   stored normalized form, and named/inline equal forms converge.
5. Keep one Datalog regression for each failure class, including the F11
   precedent, positional database injection, map-key database injection,
   return lookup, unnamed-shape lookup, and later naming of a prior inline
   shape.

### Graduation query suite

The final recurring suite must answer, from database facts only:

- which functions take `:seon.db/db`, under which argument index, map path,
  and arity;
- which functions take a positional database value;
- which functions require the live connection;
- which functions return a named or anonymous exact shape;
- which source binding receives each schema slot;
- where every call/keyword/local/protocol/interop occurrence appears;
- which tests directly or transitively exercise a function;
- which inline shapes are unnamed; and
- which warnings belong to the published source generation.

If any answer requires parsing `:seon.fn/spec`, `:seon.fn/source`, AST strings,
or a naming convention at query time, the implementation has not graduated.

## Output-quality finding

The live `eval_clj` probe failed before evaluation with a transport-shaped map
whose only actionable detail was:

```clojure
{:seon.dev.mcp/failure "transport"
 :seon.dev.mcp/error
 "Could not locate seon/operator/state.bb, seon/operator/state.clj or seon/operator/state.cljc on classpath."}
```

This is ugly because it exposes a raw classpath search string without the
owning component, expected artifact, or recovery instruction. The repository’s
load-only JVM completed the Malli probes, so this did not block the audit. This
lane made no MCP or operator edits.
