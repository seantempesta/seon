---
type: research
status: active
tags: [research, agent, architecture]
---

# The renderable corpus — N5's design plan

**REVISED 2026-07-28 post-midnight.** The first draft (`97d9919c6`) was
falsified by `renderable-corpus-falsification-2026-07-28.md` (10 findings, 6
seal-blocking) and superseded by the owner's post-midnight rulings, the
confinement guardrail, and the distance-is-a-query principle. Every section
carries a dated marker naming what changed. The first draft's shape survives;
five of its mechanisms do not.

The organizing principle is the owner's, verbatim: **"Namespace and distance
centric context for agents."** An agent's context IS `render(its namespace,
distance N)` — every hop rendered by its owner's projection, redirectable at
any slot, distance implied 1. `/data` is re-grounded as `seon.data`, the
namespace whose job is understanding and explaining all the data in the system.

## The three standing constraints this design is answerable to

Recorded here because they are binding on every section below, and because the
first draft violated two of them.

**1. The bar.** *"An elegant solution that is obvious to agents because the
concept is so simple. It's just data in and out. Write a new function to change
it."* This is the graduation criterion, evaluated as an agent eval
(`src-inspect-ai/`), not a code review: a fresh agent, told one sentence,
changes what it sees by writing one `defn`.

**2. The confinement guardrail (owner, binding).** *"I don't want anything that
requires changes all over the system. No forcing every function to identify its
own distance or anything that makes the system weird. A function that takes in
data and processes it is normal Clojure."* Its four implications are contract:

- **renderers keep the plain signature** — one unit map in, data out. Distance
  is an optional key on the unit; a renderer that does not care never mentions
  it, and nothing anywhere declares its own distance;
- **discovery uses only metadata durable defns already carry** —
  `:malli/schema`, docstrings, and the existing `^{:seon.workload …}` leaves.
  **This design adds no new per-function annotation of any kind**;
- **all machinery lives in dedicated namespaces** — traversal in
  `seon.render.walk`, explanation and census in `seon.data`, **the router
  untouched as the one entry**;
- **any element that would touch code outside the render family, `seon.data`,
  schema EDN, and the N5 indexer is misdesigned** — pull it inward or cut it.

**3. Distance is a query, never a decoration (owner).** *"Distance is mostly a
query to the database — we have refs and understand distance between entities;
namespaces are entities and functions have schemas; there are many ways to
interpret this WITHOUT explicitly decorating functions and namespaces."*
Encoded in §4: distance is a walk over the ref graph, the graph is the
database, the interpretation is an **edge-selection argument** to the walk, and
**nothing in this design writes a distance or depth fact anywhere.**

This document sequences nothing. `plan/README.md` §3 remains the only ordering.

## Name table — veto these before contracts seal

**New 2026-07-28 post-midnight**, per the owner's naming-pass instruction.
Every name this plan introduces, with its grounding. Umbrella nouns and
coinages are called out where they were removed.

| name | kind | grounded in | note |
|---|---|---|---|
| `seon.render.walk` | namespace | `clojure.walk` — the language's own name for traversing a structure | owns distance, edge selection, budget, cycle path. The word "care-graph" is **RETIRED as coinage**; it named nothing a dependency names |
| `seon.render.walk/neighbors` | function | ordinary graph vocabulary | what a renderer calls to delegate outward one hop |
| `:seon.render.walk/edges` | request key | the edge-selection argument (§4.2) | the metric; never stored |
| `:seon.render.walk/path` | request key | `expand`'s landed per-path visited set | cycle refusal |
| `:seon.render.walk/remaining` | request key | `expand`'s landed node budget (`block.clj:528`) | shared node budget |
| `:seon.render/distance` | unit / request key | **owner-ruled name** | optional, default 1, 0 = name only. Supersedes the first draft's `:seon.render/depth` |
| `seon.data` | namespace | the owner's own phrasing ("a namespace `seon.data` whose job is understanding and explaining all the data") | census, resolution chain, completeness, entity enrichment, the code floor |
| `seon.data/source (renamed: owner VETOED code-ai, 2026-07-29 — the declaration site names the kind, the fn names the content)` | function | mirrors the landed `seon.context/identity-ai`, `peers-ai`, … naming | the AI floor (§3.4) |
| `:seon.fn/arities` | attribute | Clojure's own word for a function's argument-count variants (`arglists`); Malli's `:function` holds one `:=>` per arity | replaces the first draft's `:seon.fn/arms` — "arm" is neither Clojure's nor Malli's word |
| `:seon.fn.arity/{ordinal,arg-count,variadic?,input-form,input-schema,output-form,output-schema}` | attributes | Clojure arity vocabulary + Malli's `:=>` input/output positions | colocated under the owning code namespace per the 2026-07-28 attribute ruling |
| `:seon.render.kind/{kind,outputs}` | attributes | `render/kinds` is the landed computed kind vocabulary | one row per kind, registered beside the kind's own schema |
| `:seon.ns.require/{target,alias,refers,refer-all?,as-alias?}` | attributes | **the quarry's own registered names** (`src-old/seon/ns/source.cljc:19-31`) | adopted verbatim, not renamed |

**Vocabulary corrections applied throughout this revision:** the first draft
used "lens" as an umbrella noun for a renderer. The landed vocabulary is
**projection** (`:seon.render/projection` is a registered schema key, and the
router's own docstring uses it); every "lens" is now "projection". "Care-graph"
is deleted. "Hop depth" is replaced by the ruled "distance".

## 0. Dependency ledger

**Revised 2026-07-28 post-midnight** — four citations corrected per the
falsification's citation audit; the rulings and guardrails added as governing
authority.

| dependency | selected source | what it establishes |
|---|---|---|
| **the rulings + guardrails** | `plan/README.md` post-midnight batch; the owner's confinement and distance-is-a-query messages | distance call convention; namespace+distance as THE organizing principle; the resolution chain; the bar; confinement; no decoration |
| the router | `src/seon/render.clj:81-174` | `declaration?`, `kinds` (computed), `render` (late var-backed resolution, total, flat error values). **Untouched by this design** |
| block census | `src/seon/render/block.clj:172-321` | the landed derived-membership seam — `derived` returns `[]` pre-N5 and is the function N5 fills |
| bounded expansion | `src/seon/render/block.clj:477-660` | the reusable asset is the **discipline**: deterministic DFS, per-path visited set, shared node + depth budgets. It is a *hiccup slot/ref* walker with a closed request that hardcodes `:seon.render/html` (`:610-621`) — **not** a namespace walker (SB-5) |
| family selection | `src/seon/render/block.clj:744-791` (`select`) | producer-side first-matching-specialist selection, decided where the unit is BUILT |
| generic floor | `block.clj:793-887` (`data-panel`), `src/seon/render/data.clj` | the html floor and the get-in drill. **There is no AI floor**: `seon.error/ai-prose` accepts `:seon.error/notice`, not an arbitrary unit (`error.clj:465-495`) |
| program-graph schema | `src/seon/schema.cljc:509-575` | `:seon.fn/*`, `:seon.ns/*`, `:seon.schema/*` and the `:map` properties that already declare renderers — **six** symbols, all absent from `src/` |
| schema-row derivation | `src/seon/schema.cljc:1858-1872` | `:seon.schema/ns` is assoc'd **only when `(namespace schema-key)` is non-nil** — so `:seon.fn`, `:seon.ns`, `:seon.schema` have no owner ref (SB-4, probe-confirmed) |
| the quarry indexer | `src-old/seon/db/program.clj` | the diff/reconcile discipline. `unchanged-row?` (`:215-226`) compares functions, namespaces and schemas — **not tests** |
| the quarry edge indexer | `src-old/seon/program/edge.cljc:15-25` | `::calls`, `::effect`, and an **eight**-member uncertainty enum (the first draft cited three) |
| the quarry require facts | `src-old/seon/ns/source.cljc:19-31` | `:seon.ns.require/target` / `alias` / `refers` / `refer-all?` / `as-alias?` — **none registered in the fresh tree** |
| the quarry namespace render | `src-old/seon/agent/ctx/namespaces.cljc`, `src-old/seon/render/handlers/ns.cljc` | ruling #3's named default for a namespace-as-data-type: signatures + docstrings, bodies by budget — **mine it, never reinvent** |
| landed oversight | `src/seon/oversight.clj` (`abf8680d7`) | the fleet block is **landed, not queued** |
| error facts | `src/seon/schema/error.edn:43-106`, `src/seon/error.clj:270-347` | kind/message/process/proc/op/cid/basis/run/agent are queryable; **namespace, var, schema key, test symbol and call path are not** — they live inside the `:seon.error/data-edn` string (SB-6) |
| fault routing | `plan/README.md` rulings 2026-07-26 PM | "who should fix this is a query" — a **target ruling**, not landed facts |
| the sealed contract | `plan/context-blocks-contracts-2026-07-28.md` §3.2, §3.9, §10 | the two N5 edges: derived membership, and the invocation seam's SCI half |
| classification research | `research/workload-classification-2026-07-28.md` §2 | the reachability fold over `:seon.fn/calls` + the **existing** `:seon.workload` leaf metadata |
| presence doctrine | `research/state-without-kinds-2026-07-28.md` §1 | no kind discriminators; `:seon.error/kind` is a diagnostic tag (`conventions.md:378`) |
| the architecture target | `docs/seon/architecture/context.md:287-400,388-395` | "derive the derivable, store only the overrides"; the auto-run rule names output schemas that **no landed renderer declares** (§2.1) |

## 1. The corpus indexer

**Revised 2026-07-28 post-midnight** — the two single-name schema attributes
are REPLACED by arity rows (SB-2); attribute count corrected (five, not four);
every quarry field gets a disposition (R-1); and the whole section is confirmed
against the guardrail — it touches only the indexer and schema EDN, and adds
**no per-function annotation**.

### 1.1 What a `:seon.fn` row must carry

Landed already (`schema.cljc:512-536`): sym / ns / source / source-fingerprint
/ arglists / doc / private? / spec / read-attrs. N5 adds **five** attributes
and one component collection:

```clojure
:seon.fn/calls [:set :string]              ; fully qualified callee symbols
:seon.fn/uncertainties [:set :seon.fn/uncertainty]   ; §1.3 — eight members
:seon.fn/workload [:enum :io :compute]     ; lifted from EXISTING ^{:seon.workload …} leaves
:seon.fn/arity-max [:int {:min 0}]
:seon.fn/variadic? :boolean
:seon.fn/arities [:set {:seon.db/component true} :seon.db/ref]
```

Every one of these is **derived from what a defn already carries** — its
`:malli/schema`, its metadata, its analyzed body. Nothing asks an author for
anything new, which is the guardrail's second implication stated as a property
of the row.

`:seon.fn/arities` is a `[:set …]`, not a `[:vector …]` (raised by review,
2026-07-28): **cardinality-many is a SET** — L13 says order is never a
collection-type property, and every landed component-ref collection follows it
(`:seon.cluster.agent/blocks`, `:seon.cluster.run/forms`,
`:seon.context.capture/contributions`, `:seon.ns/require-edges`). Arity order
is carried by each child's own `:seon.fn.arity/ordinal`, exactly as the sealed
contribution rows carry `position`.

### 1.2 Contract arities as data — replacing the two-name projection

The first draft stored one `input-schema` and one `output-schema`, admitting
only `[:=> [:cat S] T]`. A JVM probe over real var metadata returned **nil for
all five real cases** — inline `[:maybe :string]` outputs, `[:or …]` outputs,
`:function` multi-arity, variadic `[:catn [k [:* :any]]]`, `[:or]` inputs
(SB-2). Malli and Seon's own completeness walker (`schema.cljc:777-802`)
support `:=>` and every arm of `:function`; two cardinality-one attributes
cannot.

**One row per arity**, preserving the whole contract:

```clojure
:seon.fn.arity/ordinal      [:int {:min 0}]   ; position within :function
:seon.fn.arity/arg-count    [:int {:min 0}]
:seon.fn.arity/variadic?    :boolean
:seon.fn.arity/input-form   :string           ; the arity's input form, verbatim EDN
:seon.fn.arity/output-form  :string           ; the arity's output form, verbatim EDN
;; NAMES — present only when the form IS a registered keyword. A projection of
;; the form, never a replacement for it.
:seon.fn.arity/input-schema  :keyword
:seon.fn.arity/output-schema :keyword
```

A single-arity `:=>` produces one row; a `:function` produces one per arity;
forms are always complete, so nothing is discarded and multi-arity accretes
with no cardinality change. Renderer eligibility is a **separate pure
projection** over these rows (§2.2) — "complete function" and "discoverable
renderer" stop being the same predicate, which is SB-2's required revision.

### 1.3 Quarry field dispositions (R-1)

The uncertainty enum has **eight** members; dropping five would turn a quarry
uncertainty into an apparently certain call graph. **All eight are adopted, and
every one is fail-closed**: any uncertainty on a row forces `:mixed` and marks
its call edges incomplete.

| member | disposition |
|---|---|
| `:dynamic-call` | adopt — edge unresolved |
| `:open-higher-order` | adopt — edge unresolved |
| `:unresolved-symbol` | adopt — edge unresolved |
| `:constructed-keyword` | adopt — `read-attrs` incomplete; must not read as a complete attribute set |
| `:dynamic-read-attributes` | adopt — same, reads |
| `:dynamic-written-attributes` | adopt — same, writes |
| `:macro-expansion` | adopt — call edges may be synthetic |
| `:value-passed-pattern` | adopt — the edge exists, the call site does not |

If the new analyzer genuinely cannot produce one of these states, N5.1 proves
why rather than silently omitting it.

**Require-edge targets:** register the quarry's `:seon.ns.require/target` /
`alias` / `refers` / `refer-all?` / `as-alias?` verbatim. Without `target`, a
component ref set cannot be walked, so §4's require-hop edge selection has no
edges. (It is *not* needed for the default edge selection — see §4.2.)

**`unchanged-row?` gains a test arm** (`src-old/seon/db/program.clj:215-226`
compares functions, namespaces and schemas only). Without it, every test row is
emitted as changed on every index and N5.1's zero-datom reindex falsifier
fails.

### 1.4 Two producers, one row shape

Unchanged: the build-time indexer bakes rows into the shared database ancestor;
an agent's own `defn` commits the same row shape in the form's terminal
transaction, provenance as minimal tx-meta. A durable defn still REQUIRES a
complete `:malli/schema`. Acquisition at a basis is unchanged (L14: `:load-fn`
cannot resolve a bare same-namespace symbol, so a namespace materializes
whole).

### 1.5 Quarrying `seon.db.program`

**Survives:** identity-attribute reconciliation, the complete-population
assert, deterministic desired ordering, provenance-scoped stale retraction,
zero-write convergence. **Dies:** the `:seon.db.process/boot` provenance
queries (the ancestor's basis is the baseline now), `apply-release-config!`,
`compile-initialization-pages`, the empty-string sentinels,
`src-old/seon/client/indexing.clj`.

## 2. Renderer discovery

**Revised 2026-07-28 post-midnight** — the `*-output` suffix rule is WITHDRAWN
(SB-3), and its replacement is re-cut against the confinement guardrail: the
first revision's tree-wide output migration would have edited `seon.error` and
`seon.oversight`, outside the allowed boundary. The registry adapts to the
tree instead.

### 2.1 The output contract problem, and the confined resolution

There is no canonical render output contract today. Landed projections declare
`[:maybe :string]` (`context.clj:55-65`), `:string`, `[:string {:min 1}]`
(`error.clj:436-494`), `:seon.render/hiccup` (`render/data.clj:170-178`,
`render/root.clj:38`), `[:maybe :seon.render/hiccup]` (`oversight.clj:283`).
The quarried handlers declare `[:maybe :string]` and
`[:maybe :seon.render.canvas/hiccup]`. The architecture
(`context.md:388-395`) says the output schema *is* `:seon.render/ai` /
`:seon.render/html`, which cannot be — those keys are declaration slots holding
symbols. Three conventions, none followed.

**The resolution: the KIND declares which output forms count as its
projections, as one fact, seeded from what the tree already declares.**

```clojure
:seon.render.kind/kind    [:keyword {:seon.db/identity true}]
:seon.render.kind/outputs [:set :string]   ; accepted output forms, verbatim EDN
```

Seeded in schema EDN — one file, **zero code edits anywhere**:

- `:seon.render/ai` → `#{"[:maybe :string]" "[:string {:min 1}]" ":string"
  ":seon.render/prose"}`
- `:seon.render/html` → `#{":seon.render/hiccup" "[:maybe :seon.render/hiccup]"}`

This is the guardrail applied to a real tension: the first revision made
discovery correct by migrating ~15 declarations across four namespaces, two of
them outside the render family. Adapting the registry instead makes discovery
correct **on the tree as it is**. A new kind registers one row beside its own
schema; the census derives the kind set; nothing central is hand-maintained
except each kind's own declaration of what it accepts — which is that kind's
contract, not a list of other people's code.

Convergence on one canonical form per kind (`:seon.render/prose`,
`:seon.render/hiccup`) remains desirable and is offered as an **optional**
later tidy-up, never a precondition (owner decision 8).

**Why `[:maybe …]` forms are accepted here, stated because it reads like a
violation and is not** (raised by review, 2026-07-28): the house ban on
`[:maybe X]` governs **stored attributes**, where the Datahike bridge forces
absence instead of nil. The sealed nil-punning ruling is explicit that
"`[:maybe]` is allowed in in-memory function RETURN contracts", and a
projection returning nil IS the ruled omission mechanism — forbidding
`[:maybe]` in render outputs would forbid omission itself. Two further notes:
`:seon.render.kind/outputs` stores **strings** naming accepted contract forms,
so no `[:maybe]` schema is ever registered as an attribute's own shape; and
`seon.context/identity-ai` already declares `[:maybe :string]` in landed,
sealed code (`context.clj:55-65`).

### 2.2 Eligibility — a total projection over arity rows

An arity row is a **render arity** for `(S, K)` when all of:

1. `arg-count` = 1 and `variadic?` is false;
2. `input-schema` is present (the input IS a registered keyword `S`);
3. `output-form` ∈ the `:seon.render.kind/outputs` of kind `K`.

A function is a **discoverable renderer** for `(S, K)` when it has a render
arity for `(S, K)` **and is not private**. Every other case has a stated
outcome:

| case | outcome |
|---|---|
| no arity matches | not discoverable; still invocable through an explicit declaration |
| several arities, different `(S,K)` | discoverable for each — one function, several data types, legal |
| several arities, same `(S,K)` | indexer refuses `::ambiguous-contract` naming the fn and both ordinals |
| `private?` true | **excluded from discovery** (SB-4); still invocable by explicit declaration from its own namespace |
| input is `[:or …]` or inline | not discoverable — a complete contract with no single named `S` |
| output form outside the kind's accepted set | not discoverable, and **listed by the completeness query** as "renderer-shaped, unaccepted output" — the worklist for the optional convergence |

The query is two clauses over projected names:

```clojure
;; the accepted forms bind as a COLLECTION parameter, never as a set value
;; tested with `contains?` — a sequential binding would silently match
;; nothing, since (contains? ["x"] "x") is false.
'[:find [?sym ...]
  :in $ ?S [?form ...]
  :where
  [?arity :seon.fn.arity/input-schema ?S]
  [?arity :seon.fn.arity/output-form ?form]
  [?fn :seon.fn/arities ?arity]
  (not [?fn :seon.fn/private? true])
  [?fn :seon.fn/sym ?sym]]
```

### 2.3 Never structural value matching

Unchanged and load-bearing. No walker inspects a value's shape to guess a
renderer — that is a kind system recomputed wrongly at every read
(`state-without-kinds-2026-07-28.md` §1; `:seon.error/kind` is a diagnostic
tag, `conventions.md:378`). Matching a declared *contract form* is not value
matching: the form is a name the author wrote. The one sanctioned
value-touching selection stays `seon.render.block/select`, which runs where the
unit is BUILT, in the producer that already knows what it made. **Producers
declare; consumers never classify.**

### 2.4 Defaults on registrations, and the completeness queries

The registration-property mechanism is landed (`schema.cljc:541-575`) and is
the owner's declared default (§3). Two derived completeness queries feed
`seon.problems`, grouped by owner where an owner exists:

1. **schemas with no projection for kind K** — omission-shaped, nil when
   complete;
2. **declarations naming absent functions** — the landed open issue as a
   standing query. Its honest current answer is **six** symbols (ai + html for
   fn, schema and ns), not three (R-3, verified).

The "24 used / 0 registered" `:seon.ai.attempt/*` scar is **deleted**: all
seven distinct used attributes are registered at `src/seon/schema/ai.edn:112-138`
(R-3, verified). The general query — attributes written but never registered —
survives; its example was false.

## 3. Scoped selection — the resolution chain

**Revised 2026-07-28 post-midnight** — re-centered on ruling #3's four steps
with the viewer held CONSTANT through the walk; made total across the six cases
the falsification proved undefined (SB-4); "lens" replaced by "projection"
throughout. Confinement check: the chain is one function in `seon.data`; the
router is untouched.

| step | provider | note |
|---|---|---|
| 1 | **explicit slot redirect** by the delegating renderer | the override point; captured in provenance |
| 2 | **the viewer's local override** for the data type | the viewer `V` is the ORIGINAL agent's namespace and is **constant through the whole walk** — hop 3 renders through hop 0's overrides; perspective never silently shifts |
| 3 | **the owning namespace's default** | registration property first, then a discovered public fn in `O` (§3.2) |
| 4 | **the floor: code/data panels** | "code is a good fallback as it's the truth of the system" |

The constant viewer is the ruling's sharpest clause: `V` rides the request
unchanged at every hop, so selection is `(S, K, V)` where `V` never varies
within one render.

### 3.1 Which schema — value-to-schema, defined before selection (SB-4)

Selection cannot start until `S` is known, and the first draft never said how.
Presence-based, never classification:

| the thing being rendered | how `S` is determined | ambiguity |
|---|---|---|
| a pulled **entity** | its **identity attribute** — `:seon.entity/id-attr` enumerates them, and a unique identity attribute names the entity schema | two present → refuse `::ambiguous-identity` naming both; zero present → no `S`, floor |
| a **namespace** | `S` = the `:seon.ns` entity schema (a namespace IS a rendered data type) | none |
| a **producer-built unit** | the producer declares `:seon.render/schema S` on the request | absent → no `S`, floor |

This dissolves SB-4's multi-schema ambiguity rather than arbitrating it:
identity attributes are unique by construction, so "structurally matches two
entity schemas" stops being a question anyone asks — and §5's entity enrichment
stays total, merging the declarations of exactly one schema.

### 3.2 Which owner — and what happens when there is none

`O` is `:seon.schema/ns`, assoc'd **only for qualified schema keys**
(`schema.cljc:1858-1872`). `:seon.fn`, `:seon.ns`, `:seon.schema` — §2's own
primary examples — have no owner ref (probe-confirmed). Total outcomes:

| case | outcome |
|---|---|
| `O` exists and its registration declares `K` | the **registration property wins** — an explicit declaration outranks inference |
| `O` exists, no property, exactly one public discovered fn | that fn |
| `O` exists, no property, several public discovered fns | refuse `::ambiguous-owner-default` naming all |
| **no `O`** (unqualified key) | rung 3 is the registration property alone; absent → rung 4 |
| a property naming an absent function | rung 3 fails to resolve → rung 4, and the completeness query already names it |

### 3.3 Failure does not fall through — stated plainly (SB-4)

The first draft claimed the floor backstops a broken projection. It does not:
`render` catches a throwing projection and returns `::projection-failed`
(`render.clj:153-169`); `block/surface` preserves it as an error surface
(`block.clj:358-387`). **Nothing retries rung 4.**

That behavior is correct and stays. The plan's language is corrected: **the
floor is a SELECTION fallback, never a FAILURE fallback.** A selected
projection that throws produces an error surface; a selected projection that
does not exist produces the floor.

### 3.4 The AI floor must be built (SB-4)

There is no generic AI floor: `seon.error/ai-prose` accepts
`:seon.error/notice`, not an arbitrary unit. Ruling #3 names what it should
be — **code**. N5 builds `seon.data/source`, the AI twin of `data-panel`:
signatures + docstrings, bodies by budget, mined from the quarry's namespace
context render and never reinvented. With both floors, rung 4 is total for both
kinds — which it is not today.

### 3.5 Multiple projections versus collisions

Selection is scoped, so two renderers for `(S, K)` in different namespaces
answer different keys — two viewers, two points of view, nothing to arbitrate.
Two **public** renderers for `(S, K)` in the same namespace share a key and
nothing orders them: refuse loudly naming both symbols. Block-name collisions
keep their landed refusal (`block.clj:234-261`) — names are an identity
namespace, selection is a scoped lookup, and the two stay separate.

### 3.6 Provenance — and the asymmetry that must be fixed (SB-4)

AI contributions record the qualified projection symbol
(`cluster/prompt.cljc:116-127`). **A successful HTML surface records none**
(`block.clj:354-387`). So the first draft's "the HTML audit is already
answerable" was false, and ruling #1's per-slot override could not be captured.

Named seal revision, inside the render family: `:seon.render/projection` is
recorded on **every** successful surface, and a slot redirect records the
redirecting slot alongside the chosen symbol. Both are already-computed values;
recording them is a field, not a mechanism. Which rung answered stays derived
(compare the symbol's namespace against `V` and `O`).

## 4. Distance — a walk over the ref graph

**Rewritten 2026-07-28 post-midnight.** Three inputs reshaped this section:
ruling #1/#2 (distance, default 1, 0 = name only, an argument TO the renderer,
delegation decrements); SB-5 (`expand` is a hiccup slot walker and cannot be
the traversal); and the owner's distance-is-a-query principle (the metric is an
edge selection over the ref graph, and nothing is decorated). The name
"care-graph" is retired.

### 4.1 Nothing declares its distance; nothing stores it

**Confirmed property of this design: no attribute, unit key, or fact anywhere
records a distance or a depth.** Distance exists in exactly two places — as an
optional key on a render request while a render is in flight, and as the number
of hops a query walked. It is never written, never indexed, never cached.

Renderers keep the plain signature: **one unit map in, data out.** A renderer
that wants deeper neighbors reads `(get unit :seon.render/distance)` and calls
one function; a renderer that does not care never mentions it. That is the
whole author-facing surface, and it is ordinary Clojure.

### 4.2 The metric is an edge selection, not a fixed neighborhood

The owner's principle: *namespaces are entities and functions have schemas;
there are many ways to interpret distance without decorating anything.* So
`seon.render.walk` takes the interpretation as an argument:

```clojure
:seon.render.walk/edges   ; which refs constitute one hop
```

| edge selection | one hop means | available |
|---|---|---|
| **entity refs** (implied default) | follow the entity's `:seon.db/ref` attributes | **now** — needs no new facts at all |
| require-hops | `:seon.ns/require-edges` → `:seon.ns.require/target` | post-N5 (§1.3 registers the targets) |
| call-hops | `:seon.fn/calls` | post-N5 |
| shares-a-schema-with | entities/functions mentioning the same `:seon.schema/key` | post-N5 |

**Code-distance and data-distance are ONE mechanism** the moment N5 makes
namespaces and functions entities with refs. They are not two subsystems to
reconcile later; they are different `edges` arguments to the same query. That
is why the default works today with zero new facts, and why post-N5 selections
are additions to a table rather than a second walker.

### 4.3 Delegation decrements; the walk owns the accounting

The ruling's call convention: distance is an argument to the renderer, and
opting deeper is the renderer's compositional act — delegate neighbors to
**their** renderers through the one router, decrementing per hop, distance 0 =
name only.

Delegation without accounting is the four-million-node OOM again
(`block.clj:500-522`). The confinement guardrail forbids putting that
accounting in the router. So it lives where the machinery is allowed to live:

**`seon.render.walk/neighbors`** — the one function a renderer calls to
delegate. It resolves the neighbors by the `edges` selection, decrements
distance, extends the visited path, decrements the shared node budget, calls
the **untouched** router once per neighbor, and returns their rendered values.
The renderer decides *whether* to delegate; the walk does the arithmetic.

```clojure
:seon.render.walk/path       ; visited path — cycle refusal in place
:seon.render.walk/remaining  ; shared node budget (a volatile, as `expand` already uses)
```

Budgets come from the same `:seon.sci.admit/caps` the eval door and `expand`
already use — **no second set of dials**. At distance 0 a delegation returns
the **name only**; on a path member it is a cycle refusal in place; on an
exhausted budget it is a legible hole.

`expand` keeps its landed job (hiccup slots and refs) as one consumer of the
same discipline. The first draft's "one line to thread depth" is withdrawn;
whether a generic budgeted-walk helper is worth extracting from `expand` is an
N5.6 implementation detail, not a contract claim.

### 4.4 A namespace is a rendered data type

Ruling #3, and the piece that makes "render my namespace at distance N"
concrete:

| distance | a namespace renders as |
|---|---|
| 0 | the **name** only |
| 1 (default) | **signatures + docstrings** |
| deeper | **bodies, by budget** |

The default projection is the quarry's namespace context render — mined, not
reinvented. Edge order is deterministic (sorted by target symbol) so two
renders of one database value are one value.

### 4.5 Vocabulary

| Say | Never | Meaning |
|---|---|---|
| distance, `:seon.render/distance` | depth, hop count, detail level, LOD, zoom, summary kind, care-graph | how many hops to render, each by its owner's projection; an ARGUMENT to the renderer; default 1; 0 = name only; decremented by `seon.render.walk`, never stored |
| edge selection, `:seon.render.walk/edges` | neighborhood, proximity model | which refs constitute one hop; the metric as a query argument |

Banned by construction: a `:seon.render/summary` or `:seon.render/compact`
kind. The first draft's `:seon.render/depth` is superseded.

## 5. `seon.data` — the namespace that explains the system's data

**Revised 2026-07-28 post-midnight** — the false `:seon.ai.attempt/*` scar
removed (R-3); the AI floor added as an owned deliverable (§3.4); entity
enrichment made total by §3.1; boundaries confirmed against the guardrail.

`seon.data` OWNS: the **census** (every registered schema × every kind × every
candidate renderer, as data — itself a renderable unit, rendered lazily); the
**resolution chain** (§3, one function); the **completeness derivations**
(§2.4); **entity enrichment** (project the identity-named schema's render
declarations onto a pulled entity); and **the code floor** (`code-ai`).

It DERIVES everything and STORES nothing. It has no attributes of its own.

It does not absorb the render mechanisms: `data-panel` stays in
`seon.render.block`, the drill stays in `seon.render.data`, traversal is
`seon.render.walk`, and the router stays the one untouched entry.

Ping's place is unchanged and now landed: `src/seon/oversight.clj`
(`abf8680d7`) is the process-local half of the census, ai+html through the one
router, never committing its result.

## 6. What lands when — the N5 order

**Revised 2026-07-28 post-midnight** — falsifiers corrected (R-2, R-3), the
output migration demoted from precondition to optional (§2.1), N5.6 re-scoped
to the walk + edge selection, oversight moved from "queued" to "landed".

**N5.1 — the indexer and its facts.** Five attributes plus arity rows
(§1.1–1.2); eight uncertainty members, all fail-closed (§1.3); require-edge
target attributes; `unchanged-row?`'s test arm; the `:seon.render.kind` rows
seeded from the tree (§2.1).
*Falsifiers:* (a) `:seon.fn` rows exist for every public defn, count matching an
independent `rg` census; (b) arity rows round-trip all five probe cases the
falsification recorded — inline output, `[:or]` output, `:function`
multi-arity, variadic `:catn`, `[:or]` input — with **no arity discarded**;
(c) the classifier returns the research's probed answers, and every row
carrying any of the eight uncertainties classifies `:mixed`; (d) the
declarations-naming-absent-functions query returns exactly **six** symbols,
derived from an independent scan rather than a copied count; (e) a second index
of an unchanged tree — **including test rows** — writes zero datoms; (f) a
whole-tree diff shows changes confined to the indexer, schema EDN, and the
render family.

**N5.2 — the corpus round trip** (old step 4, verbatim). Unchanged.

**N5.3 — renderer discovery.** Eligibility over arity rows, the query, the
registration lookup, the completeness queries, and `block/derived` becoming
non-empty.
*Falsifier — corrected (R-2):* the sealed `membership-collision-property` is
**already non-vacuous** (it constructs derived rows and `with-redefs`es
`block/derived`, `test/seon/context_test.clj:536-582`); it proves the
membership seam and nothing about discovery. It is KEPT, and N5.3 adds a
**separate discovery property** over planted `:seon.fn` / `:seon.fn.arity` /
`:seon.schema` / `:seon.ns` facts covering: named unary (accepted),
multi-arity with one render arity (accepted), multi-arity with two same-`(S,K)`
arities (refused), variadic (excluded), `[:or]` input (excluded), private
(excluded), same-scope collision (refused), missing owner (floor), both kinds,
and an output form outside the kind's accepted set (excluded + listed).

**N5.4 — the invocation seam's SCI half.** Unchanged (sealed named edge).

**N5.5 — the resolution chain.** Ruling #3's four steps, the constant viewer,
the totality cases (§3.1–3.3), the AI floor (§3.4), the surface provenance
revision (§3.6).
*Falsifiers:* an agent defines a renderer in its own namespace and the next
prompt contribution changes with no edit to `seon.cluster.prompt` and no
reinstall; a second public renderer for the same `(S,K)` in the same namespace
refuses naming both; an entity with two identity attributes refuses
`::ambiguous-identity`; a schema with no owner and no property renders through
the floor **for both kinds**; a throwing projection produces an error surface
and **does not** invoke the floor; every successful HTML surface carries its
projection symbol.

**N5.6 — `seon.render.walk`.** `:seon.render/distance` on the unit; the `edges`
selection with entity refs as the implied default; `neighbors` owning
decrement, cycle refusal and budget; the namespace-as-data-type default
projection mined from the quarry.
*Falsifiers:* distance 0 renders the name only; absent distance renders 1; a
cycle refuses at the hole that closes it; a pathological fan-out is bounded by
the shared node budget with legible holes rather than an OOM; the same
renderers at distance 1 and 3 produce different bytes; a slot redirect changes
the bytes AND is captured; switching `edges` from entity refs to require-hops
changes the neighbor set with **no other change**; and a datom census proves
**no distance or depth fact was written**.

**The pilot** (ruling #2): one agent's prompt derived as its namespace view at
entity-graph distance, dispatching when the distance accretion lands. **The
graduation criterion** is the bar, run as an agent eval.

**Already landed:** the fleet-oversight block (`src/seon/oversight.clj`,
`abf8680d7`) proves the invocation and placement halves — one unit, two
projections, one router, installed on root — while the corpus is still empty.
N5.3 adds *discovery* to a working path, never a second path.

## 7. Distributed fix delegation — preconditions before it is a query

**Revised 2026-07-28 post-midnight** — the first draft claimed three landed
mechanisms already made delegation a query and that only `:seon.test` rows were
missing. SB-6 falsified that with two walks. Restated as **target architecture
with named preconditions.**

The direction is unchanged: a strong model takes a global broad-strokes
attempt; it fails in parts; each failing part routes to the namespace agent
that owns it, carrying the vision plus refs to the failures; that agent
localizes the fix; the global view stops iterating once every failure is
bisected.

### 7.1 What is landed, and what is a target

**Landed:** `my.message`; durable error facts with kind, message,
process/proc/op/cid, basis, run, agent (`schema/error.edn:43-106`);
`:seon.cluster.message/about` as a reference to a durable error.

**Target, not landed facts:** "who should fix this is a query" — the joins it
presumes do not exist.

And each namespace agent's context being its own namespace view — what makes
localization work rather than merely route — is precisely what §§1–4 build.
Delegation is downstream of the corpus.

### 7.2 Four preconditions, each a named deliverable

| # | precondition | why (falsification evidence) |
|---|---|---|
| P1 | **failure provenance as queryable refs**, not parsable text: `:seon.error/fn`, `/schema-key`, `/test`, `/ns`, call-root evidence, lifted at normalize time | the normalizer stores the whole source as `:seon.error/data-edn`, a string (`error.clj:270-347`); a schema key printed inside it is not joinable |
| P2 | **test-result ingestion** — a boundary committing outcomes that reference an exact `:seon.test` row, plus the attempt/batch identity whose vision delegation carries | `bin/test` derives namespaces from filenames and exits on counts (`bin/test:15-44`); it transacts nothing |
| P3 | **namespace assignment** — `:seon.cluster.agent/namespace` (unique, at most one) | the fresh schema has `:seon.cluster.agent/id` and no namespace ref; the target is `data-model.md:102-111` |
| P4 | **delegation delivery evidence** — an idempotent multi-failure message shape and a durable record that each message committed | ending the global episode on "every failure has an owner and a message" requires evidence of delivery, not just ownership |

The schema-conflict walk needs one correction: a duplicate schema-file conflict
throws ex-data carrying `:seon.schema.edn/attribute` and the two files
(`schema/edn.clj:148-167`), **not** `:seon.schema/key` as the first draft
claimed — and that refusal is not committed through `seon.error` at all. P1
covers both.

**Confinement note:** P1–P3 touch `seon.error`, `bin/test`, and the agent
schema — outside this design's boundary. That is exactly why §7 is scoped as
*preconditions* rather than N5 work: they are separate units with their own
owners, and N5 does not reach into them.

### 7.3 Ownership, once the preconditions exist

| failing thing | ownership path |
|---|---|
| a **form** | `:seon.error/fn` → `:seon.fn/ns` → `:seon.ns/name` → assigned agent |
| a **schema** | `:seon.error/schema-key` → `:seon.schema/ns` → agent; **unqualified key ⇒ no owner ⇒ escalate**, as §3.2 |
| a **test** | test-result fact → `:seon.test/sym` → `:seon.test/ns` → agent |
| a **call path** | call-root evidence + `:seon.fn/calls` reachability → every namespace on the path |
| **unattributable** | root, loudly, as a problems row — never a name-prefix guess (R34) |

The stopping rule and the no-dispatcher property are unchanged: the global
episode ends when every failure has an owner **and durable delivery evidence**;
a message wakes an agent; one agent per namespace and one open run per agent
make concurrent localized fixes safe by construction.

### 7.4 Falsifier

A deliberately multi-namespace broken change (schema conflict in A, failing
test in B, throwing form in C, one unattributable failure): the bisection
returns three owners and one residue, three idempotent messages commit with
durable evidence, each receiving agent's derived context already contains the
relevant source, the global episode ends without re-attempting the delegated
parts, and the bisection query itself transacts nothing.

## 8. Owner decisions — the post-revision set

**Revised 2026-07-28 post-midnight.** Of the first draft's 12, **six dissolve**
under the rulings, the guardrails, or the falsification; six survive, four
sharpened; four are new. Names are additionally up for veto in the name table
above.

### Dissolved (no decision needed)

- **D1 the suffix rule** — withdrawn; replaced by kind-declared accepted output
  forms seeded from the tree (§2.1). SB-3 + confinement.
- **D5 hop depth as a parameter** — owner-ruled (distance, default 1, 0 = name
  only, an argument to the renderer). The recommendation was right, the
  contract wrong. SB-1.
- **D7 stored input/output name projections** — dissolved with the two-name
  attribute; arity rows store forms verbatim (§1.2). SB-2.
- **D3 the defn's namespace IS the viewing scope** — ruled by ruling #3, which
  fixes the viewer as the original agent's namespace, constant through the
  walk.
- **D10 fan-out to every interested owner** — retained as design, not a live
  decision: unreachable until P1 exists.
- **D11 the global attempt does not wait for delegates** — unchanged as design
  and subsumed by P4, which is the thing that actually needs building.

### Surviving, sharpened

1. **Discovery requires a REGISTERED input schema name** (§2.2) — now one
   eligibility clause among several rather than the whole contract.
   Recommended: yes; naming is the mechanism.
2. **The registration property outranks a discovered function in the owning
   namespace** (§3.2). NEW SHARPNESS: the first draft had inference silently
   outranking an explicit declaration (SB-4). Recommended: declaration wins.
3. **Private functions are excluded from discovery** (§2.2). Recommended: yes.
4. **Same-namespace public duplicates refuse; cross-namespace are separate
   keys** (§3.5). Unchanged.
5. **`seon.data`'s boundary** (§5) — census, resolution, completeness,
   enrichment, code floor; `data-panel`, the drill and the walk stay in the
   render family. Recommended: keep split.
6. **`:seon.test` corpus rows — SCOPE EXPANDED** (§7.2): the row shape **plus**
   test-result ingestion (P2) **plus** the `unchanged-row?` test arm. Without
   all three a broken test has no owner and the zero-datom falsifier fails.
   Recommended: yes, as one unit — and owned outside N5.

### New

7. **The kind declares its accepted output forms, seeded from the tree**
   (§2.1) — chosen over migrating ~15 declarations across four namespaces
   because two of them (`seon.error`, `seon.oversight`) are outside the
   confinement boundary. Recommended: adapt the registry, not the tree.
8. **Convergence on one canonical output form per kind is OPTIONAL and later**
   (§2.1), never a precondition of discovery. Recommended: leave it optional;
   revisit only if the accepted-forms set grows unwieldy.
9. **Value-to-schema is by identity attribute; two identity attributes refuse**
   (§3.1). Recommended: yes — it dissolves multi-schema ambiguity instead of
   arbitrating it.
10. **The floor is a selection fallback, never a failure fallback** (§3.3), and
    the AI floor (`seon.data/source`) is built in N5.5 (§3.4). Recommended:
    yes; a broken projection must stay loud, and code is the ruled floor.
11. **Delegation ships behind its four preconditions** (§7.2), owned outside
    N5. Recommended: yes — saying otherwise is the overclaim SB-6 caught.
12. **The name table** (above) — every introduced name, for veto before
    contracts seal. `seon.render.walk`, `seon.data/source`,
    `:seon.fn/arities` + `:seon.fn.arity/*`, `:seon.render.kind/*`,
    `:seon.render.walk/{edges,path,remaining}`.

## 9. The single riskiest design point

**Revised 2026-07-28 post-midnight** — one advertised mitigation was false and
is replaced by the two that are real.

**The viewer's local override — step 2 — changing what an agent sees, silently,
because someone wrote a `defn`.** That is the design working as intended (the
bar demands exactly this) and it is the sharpest edge: an agent can change how
it sees data it does not own, with no installation, no review and no refusal,
because the chain is total and cross-namespace multiplicity is legal by
construction. Ruling #3's constant viewer *widens* it — one override applies to
every hop of the walk, including hops through namespaces that never consented.

Two mitigations are real, and both are N5 deliverables rather than assumptions:

- **the choice is recorded** — for AI today, and for HTML and slot redirects
  only after §3.6's surface-provenance revision lands. Until then an HTML view
  cannot be audited at all;
- **the scope is bounded to the viewer** — an override applies only to renders
  taken from `V`, never to another agent's view of the same data.

**The mitigation that is NOT real, and was claimed in the first draft:** the
floor does not backstop a broken projection. A throwing projection yields
`::projection-failed` and an error surface; rung 4 is never retried
(`render.clj:153-169`, `block.clj:358-387`). A confidently-wrong projection
degrades its own view loudly; a *plausible* wrong one degrades it quietly, and
the failure still looks like a model mistake rather than a rendering one.

The residual risk is honestly bounded: legibility, not prevention. When an
agent starts being confidently wrong about facts the database plainly holds,
the first thing to check is which projection answered — and after §3.6, that
question has an answer for both kinds.
