---
type: research
status: active
tags: [research, agent, architecture]
---

# The renderable corpus — N5's design plan

The owner's late-night direction (2026-07-28) is one sentence with a lot
inside it: **make everything in the site effortlessly compose rendering
together.** Its mechanism is the program graph. Every namespace owns its data
and publishes the functions that explain that data; a function whose declared
INPUT is a schema and whose declared OUTPUT is a render projection *is* the
renderer for that data; requiring another namespace pulls that namespace's own
view of itself; and hops out from the viewing point decide how much detail you
get. `/data` stops being a page and becomes `seon.data` — the namespace whose
job is understanding and explaining all the data in the system.

The same facts answer a second question the owner raised in the same breath:
when a broad global attempt fails in parts, **which namespace owns each part**.
Ownership is a corpus query, failures are durable facts with provenance, and
delivery is `my.message` — so distributed fix delegation (§7) is the same
mechanism read from the other side.

Nothing here is a new mechanism. The router (`src/seon/render.clj`), the
census/membership seam (`src/seon/render/block.clj`), the bounded expansion
budgets, the schema-registration properties that already name renderers, and
the quarry's call-edge indexer are all landed or written. N5 supplies the one
missing half — **the facts** — and then discovery, scoped selection, and composition
are queries over them.

This document sequences nothing. `plan/README.md` §3 remains the only
ordering; this is the design the N5 rung's contract packages are authored
from.

## 0. Dependency ledger

| dependency | selected source | what it establishes |
|---|---|---|
| the router | `src/seon/render.clj:81-174` | `declaration?` (symbol / string / vector), `kinds` (computed, never listed), `render` (late var-backed resolution, total, flat error values) |
| block census | `src/seon/render/block.clj:172-321` (`blocks`/`derived`/`membership`/`required-inputs`/`unit`) | the landed derived-membership seam — `derived` returns `[]` "by construction" pre-N5 and is the exact function N5 fills |
| bounded expansion | `src/seon/render/block.clj:477-660` (`expand`) | depth-first left-to-right, per-path visited set, NODE budget + DEPTH budget from `:seon.sci.admit/caps` (`:seon.config.eval.result/max-nodes` / `max-depth`); measured OOM at depth 22 without the node budget |
| family selection | `src/seon/render/block.clj:744-791` (`select`) | generic default + producer-chosen specialists, decided WHERE THE UNIT IS BUILT; late-resolved rules; a throwing rule costs its own specialist |
| generic floor | `src/seon/render/block.clj:793-887` (`data-panel`), `src/seon/render/data.clj` (the get-in drill) | the process-level generic render and the windowed drill; both bounded by the SAME admission caps |
| program-graph schema | `src/seon/schema.cljc:509-575` | `:seon.fn/*`, `:seon.ns/*`, `:seon.schema/*` already registered — including `:seon.ns/require-edges` (component set) and the `:map` properties that ALREADY declare `:seon.render/ai`/`:seon.render/html` for `:seon.fn`, `:seon.ns`, `:seon.schema` |
| the quarry indexer | `src-old/seon/db/program.clj` (581 lines) | the diff/reconcile discipline: identity attributes, `unchanged-row?`, complete-population assert, stale-entity retraction |
| the quarry edge indexer | `src-old/seon/program/edge.cljc:11-30` | `::calls [:set :string]`, `::uncertainties` (`:dynamic-call`, `:open-higher-order`, `:unresolved-symbol`), `::effect` |
| the quarry family renderers | `src-old/seon/render/handlers/{fn,ns,schema}.cljc` | the ai/html twins the fresh `schema.cljc` properties already name — and which do not exist in `src/` (open issue `program-graph-render-declarations-name-absent-functions`) |
| the sealed contract | `plan/context-blocks-contracts-2026-07-28.md` §3.2, §3.9, §10 | the two N5 edges this plan must serve: derived membership, and the invocation seam's SCI half |
| classification research | `research/workload-classification-2026-07-28.md` §2 | the ~15-line reachability fold over `:seon.fn/calls` + tags; the probe results |
| presence doctrine | `research/state-without-kinds-2026-07-28.md` §1 | no kind discriminators; `:seon.error/kind` is a DIAGNOSTIC tag (`docs/conventions.md:378`) |
| the fault-routing ruling | `plan/README.md`, rulings 2026-07-26 PM ("Errors, the flow way") | "who should fix this is a query": committed provenance + `:seon.agent/namespace` (unique, at most one) wakes the owning agent; a call path spanning namespaces derives multiple interested owners; no dispatch table |
| messaging + the error system | landed (`my.message` subagents; one normalizer, fault consumer, projections-per-consumer) | delegation's transport and its durable failure facts — §7 adds no mechanism to either |
| the architecture target | `docs/seon/architecture/context.md:287-400` | "derive the derivable, store only the overrides"; the auto-run rule — "the render pass QUERIES THE PROGRAM GRAPH for fns in the current namespace whose OUTPUT SCHEMA is a render type" |

The last row matters most: the owner's direction is not new architecture. It is
`context.md`'s auto-run rule, already written in present tense, finally given
its facts — plus two accretions the architecture does not yet state (the
scoped-selection model, §3, and hop-depth, §4). §7's delegation is likewise
the fault-routing ruling applied deliberately rather than only on faults.

## 1. The corpus indexer

### 1.1 What a `:seon.fn` row must carry

The landed schema (`schema.cljc:509-536`) already has sym / ns / source /
source-fingerprint / arglists / doc / private? / spec / read-attrs. N5 adds
four, all lifted at index time exactly the way `:seon.fn/doc` already is:

```clojure
;; the call graph — the quarry's own attribute, re-landed
:seon.fn/calls [:set :string]              ; fully qualified callee symbols
:seon.fn/uncertainties [:set [:enum :dynamic-call :open-higher-order
                              :unresolved-symbol]]
;; scheduling: EXPLICIT metadata only where the graph cannot prove purity
:seon.fn/workload [:enum :io :compute]     ; from ^{:seon.workload …} defn meta
;; the DECLARED contract's schema NAMES — not the forms, the names
:seon.fn/input-schema :keyword
:seon.fn/output-schema :keyword
```

`input-schema` / `output-schema` are populated only when `:malli/schema` has
the shape `[:=> [:cat S] T]` (or `[:catn [_ S]] T`) **and `S`/`T` are
registered schema keys** — a keyword the global schema population answers for.
An inline anonymous form populates neither: an inline shape is not a name, and
naming is the whole discovery mechanism. This is deliberately narrow. A
function that wants to be discoverable registers its shapes; that is the
existing house rule (`data-modeling`: register shared shapes once and
reference them), not a new tax.

`:seon.fn/spec` keeps the whole `:malli/schema` form string as today, so
nothing is lost and the two name attributes are a projection of it — a
derivation the indexer performs ONCE at index time rather than every reader
re-parsing a string. (This is the one stored derivation in the design and it
earns its place: the alternative is `edn/read-string` inside every discovery
query.)

`:seon.ns` rows are unchanged and already sufficient: `name`, `source`, `doc`,
`summary`, `require-edges` (a component set of require edges — the care-graph's
edges, §4).

### 1.2 What the terminal transaction commits

Two producers, ONE row shape — that is the accretion test.

- **Build-time (rung "-1", the publish command).** The JVM indexer walks
  `src/`, produces the desired program rows, and the result is baked into the
  shared database ancestor (`plan/README.md`: "one deliberate build indexes ALL
  code and produces the bootstrap"). A fresh cluster forks that ancestor; it
  never re-indexes.
- **Runtime (the agent's own defn).** When an eval's form defines a var, the
  form's TERMINAL transaction — the same transaction that commits the eval
  receipt — carries the `:seon.fn` row for each defined var, with the same
  attributes derived from the same analysis. An agent override of a core
  function is a later transaction on the same facts (`README.md` rulings,
  2026-07-26 PM: "no disk write-back, ever"). Provenance is minimal tx-meta
  (`:seon.db/user` + `:seon.db/process`), so "who wrote this function" is a
  join on the datom's transaction, never a `created-by` attribute.

Requirement carried forward unchanged: **a durable defn REQUIRES a complete
`:malli/schema`** (no `:any` without a proven polymorphic boundary). N5's
admission refuses the commit otherwise, with a flat error value the agent
sees — which is also what makes `input-schema`/`output-schema` reliably
present on agent-authored renderers.

### 1.3 Acquisition at a basis

Unchanged from the sealed model: a fresh SCI fork materializes namespaces from
facts at a database value — `:namespaces` for what is pre-resolved, `:load-fn`
over `:seon.ns/source` for the rest. **L14 stands**: `:load-fn` cannot resolve
a bare same-namespace symbol and `:namespaces` is consulted first, so a
namespace materialized from source must be materialized whole, never
symbol-by-symbol. Loading a namespace never publishes schema; registrations are
committed `:seon.schema` facts that a tier ACQUIRES at a basis.

### 1.4 Quarrying `seon.db.program` honestly

**Survives — this is genuinely earned knowledge, and re-deriving it would cost
a week:**

- *identity-attribute reconciliation* (`program.clj:38-39,132-143`): a program
  row is identified by whichever of `:seon.ns/name` / `:seon.fn/sym` /
  `:seon.schema/key` / `:seon.test/sym` it carries. No kind field, no row type —
  the presence doctrine, already applied.
- *the complete-population assert* (`program.clj:205-213`): an index run that
  produced zero functions must REFUSE rather than retract the corpus. This is
  a real scar: a partial index is indistinguishable from a deletion, and only
  the refusal tells them apart.
- *deterministic ordering of the desired rows* (`desired-sort-key`), so two
  index runs of one tree produce one transaction.
- *stale-entity retraction scoped by provenance* (`stale-entity-tx`), with the
  agent-home carve-out — the shape survives; its provenance key changes (below).
- *`unchanged-row?` diffing*, so a reindex of an unchanged tree writes nothing.
  Converged = zero writes is the same discipline config reconciliation already
  proved at B2.

**Dies:**

- *the `:seon.db.process/boot` provenance queries* (`program.clj:59-114`). The
  bootstrap is no longer "what the boot process wrote"; it is a shared database
  ancestor every cluster forks. The diff's baseline becomes the ancestor's own
  basis, not a process tag. Three queries and their `or-join` collapse.
- *`apply-release-config!`* (`program.clj:486-581`) — config→facts is B2's,
  landed. It is a second config writer.
- *`compile-initialization-pages`* (`program.clj:427-484`) — page compilation is
  B2's. Its schema-dependency-order logic is worth READING before the ancestor
  build is written, then leaving where it is.
- *the empty-string sentinels* (`get-else … :seon.fn/spec ""`,
  `:seon.fn/private? false`, `:seon.db.id.generator/absent`). Absent is absent;
  a stored `""` for "no spec" is stored nil wearing a hat. Presence ruling,
  2026-07-28.
- *`src-old/seon/client/indexing.clj`* — the pod half. O13; deleted.

**Adopted from elsewhere in the quarry:** `src-old/seon/program/edge.cljc`'s
`::calls` and `::uncertainties` (the fail-closed signal the classification
research depends on), and `src-old/seon/render/handlers/{fn,ns,schema}.cljc` as
the first three family specialists — which is not optional work: the fresh
`schema.cljc` already names those symbols in its `:map` properties and they do
not exist, which is exactly the filed issue
`program-graph-render-declarations-name-absent-functions`. N5 either lands them
or the declarations lie.

## 2. Renderer discovery

### 2.1 The rule

> A renderer for schema `S` and kind `K` is a function whose declared INPUT
> schema is `S` and whose declared OUTPUT schema is `K`'s output schema.

Both halves are NAMES, and the query is two clauses:

```clojure
'[:find [?sym ...]
  :in $ ?input-schema ?output-schema
  :where
  [?fn :seon.fn/input-schema ?input-schema]
  [?fn :seon.fn/output-schema ?output-schema]
  [?fn :seon.fn/sym ?sym]]
```

**`K`'s output schema is computed, never tabled.** One derivation:

```clojure
(defn output-schema-for-kind [kind]           ; :seon.render/ai → :seon.render/ai-output
  (keyword "seon.render" (str (name kind) "-output")))
```

`:seon.render/ai-output` is `[:maybe :string]`; `:seon.render/html-output` is
`[:maybe :seon.render/hiccup]`. A new kind registers its own `<kind>-output`
schema and becomes discoverable with no edit anywhere — the same
no-hand-maintained-lists property `render/kinds` already has on the unit side.
The kind keyword itself cannot double as the output schema key: on a unit
`:seon.render/ai` holds a *declaration* (symbol | string | vector), so one
global key would have two contradictory shapes. The suffix rule is a computed
structural rule (L17), not a registry.

A kind whose `<kind>-output` schema is not registered is discoverable but
unverifiable; the completeness query (§2.3) names it as a problem rather than
silently producing nothing.

### 2.2 What discovery must NEVER be

**Never structural value matching.** There is no walker that inspects a value's
shape and guesses which renderer fits. That is a kind system with the label
moved from the datom to the classifier, and the doctrine forbids it on both
ends: `state-without-kinds-2026-07-28.md` §1 (an entity is its attributes; a
stored discriminator that restates other facts is recording data incorrectly)
and `docs/conventions.md:378` (`:seon.error/kind` is a **diagnostic tag** on a
flat error value — it names what happened for a human, and nothing dispatches
on it). A structural classifier is worse than a stored kind: it is a kind
recomputed, wrongly, at every read.

The one sanctioned value-touching selection stays exactly where it is: the
landed `seon.render.block/select` (`block.clj:744-791`) runs **where the unit
is built**, in the producer, which already knows what it made. That is a
producer expressing intent from its own attributes, not a consumer inferring a
type. The distinction is the whole of the rule: **producers declare, consumers
never classify.**

### 2.3 Where defaults attach, and the completeness check

The default renderer for a schema is a property on its **registration** — the
mechanism already landed in `schema.cljc:544-575`:

```clojure
:seon.fn
[:map {:seon.db/entity true
       :seon.render/ai   'seon.render.handlers.fn/render-ai
       :seon.render/html 'seon.render.handlers.fn/render-html}
 …]
```

These properties travel with the schema into the global population as part of
`:seon.schema/form`, so "what renders a `:seon.fn`?" is answerable from facts
with no code loaded. Note what this makes true: **a pulled entity IS a unit**
(`block.clj:433-458` says so already), so an entity whose schema carries these
properties renders through the router with no extra step — provided the
properties are projected onto the pulled map. That projection is one function
in `seon.data` (§5): pull the entity, look up the schemas its attributes belong
to, merge their render declarations. Nothing is stored.

Two derived completeness queries, both feeding `seon.problems` and therefore
each namespace agent's own context:

1. **Schemas without renderers** — registered entity schemas for which neither
   a registration property nor a discovered `:seon.fn` renderer answers kind
   `K`. Grouped by `:seon.schema/ns`, so the namespace's own agent sees its own
   gaps and nobody else's nagging.
2. **Declarations naming absent functions** — every render declaration
   (property or `:seon.fn` row) whose symbol does not resolve through the one
   invocation seam. This is the landed open issue turned into a standing
   query; the three `seon.render.handlers.*` symbols are its first rows.

Both are omission-shaped: no gaps ⇒ nil ⇒ the block contributes nothing
(the sealed nil-punning ruling). Neither stores a thing.

## 3. Scoped selection — rendering is always from a point of view

**Owner ruling, 2026-07-28 late night: rendering is ALWAYS scoped from the
point of view of the VIEWING namespace.** A namespace controls how *it* sees
the world. Another namespace legitimately sees the same data differently, and
that is not a conflict to resolve — it is the model.

This reframes the whole section, and the word "override" is the wrong noun for
what happens. There is no canonical view being overridden. There is no global
renderer table. **The schema owner's renderer is simply the default lens** —
the view you get when you have not said how you look at this data — and a
viewing namespace's own renderer is not a special case granted an exception,
it is the ordinary answer to the ordinary question *"how does `V` see an `S`?"*.

Two consequences to hold on to, because they are what make the design simple
rather than clever:

- **Specialization is by construction, not by precedence.** The key contains
  `V`. Different viewers ask different questions and get different answers. No
  arbitration runs, because nothing is in conflict.
- **There is nothing to be canonical.** "The renderer for `:seon.cluster.run`"
  is not a well-formed question; "how does `my.agents.foo` see a
  `:seon.cluster.run`?" is. The owner's lens answers it when the viewer has no
  opinion, which is most of the time and is why the system feels like it has
  defaults.

What follows is therefore a **total lookup with one refusal** — not a merge,
and not a precedence contest.

### 3.1 The lookup

Selection is keyed by `(schema S, kind K, viewing-namespace V)`. Four rungs,
first hit wins, no merge at any rung:

| rung | provider | meaning |
|---|---|---|
| 1 | a discovered renderer defined **in `V`** | how the viewing namespace looks at this data — the ordinary answer |
| 2 | a discovered renderer defined **in `O`**, the schema's owning namespace (`:seon.schema/ns`) | **the default lens**: how the data's owner presents it to a viewer with no opinion |
| 3 | the **registration property** on `S` (`:seon.render/ai` / `:seon.render/html` in the `:map` props) | the lens that ships with the schema itself |
| 4 | the **kind's generic**: `seon.render.block/data-panel` for html, the `seon.error` steering default for ai | the floor — nothing is ever unrenderable |

**The scope IS the defn's namespace.** No `renders-for` attribute, no scope
declaration, no registry: putting the function in your namespace is how you
say "this is how I look at that", which is exactly the owner's "all namespaces
self-focused". It is visible by reading `ls` of a namespace's functions, and it
disappears when the defn does.

The lookup is total — rung 4 always answers — so selection returns a symbol,
never nil, and no consumer needs a fallback branch.

### 3.2 Multiple lenses versus collisions — the structural distinction

The sealed contract rules that name collisions **REFUSE loudly naming both
sources** (ruling 3), so the line between "two legitimate lenses" and "an
accident" has to be structural rather than judged. Asking what the key contains
decides it:

- **Selection is scoped, so multiplicity across namespaces is not conflict.**
  The key includes `V`. Two renderers for `(S, K)` in *different* namespaces
  answer *different keys* — two viewers, two points of view. There is no
  collision to detect because there is no shared key, and nothing arbitrates.
- **Two renderers for `(S, K)` in the SAME namespace is a collision.** The key
  is genuinely shared and nothing orders them. It REFUSES loudly, naming both
  symbols, in the house `refuse!` shape:

```clojure
{:seon.error/kind :seon.data/refused
 :seon.data/rule :seon.data/renderer-collision
 :seon.schema/key S
 :seon.render/kind K
 :seon.ns/name V
 :seon.data/renderers [sym-a sym-b]}
```

- **Block-name collisions keep the landed refusal unchanged**
  (`block.clj:234-261`). Block names are an *identity* namespace: two things
  claiming one name is unresolvable by construction, and the sealed ruling
  stands. Renderer selection is not an identity namespace; it is a scoped
  lookup. **These are two mechanisms and both are correct** — the confusion to
  avoid is trying to make one of them serve the other.

One consequence to state plainly so nobody "fixes" it later: an installed
block that names the same block-name as a derived one still refuses, *even
though* the scoped lookup would happily have answered two viewers. Names and
lookups are different questions.

### 3.3 Provenance — which renderer produced what was seen

**Already landed; nothing new is stored.** The context capture writes
`:seon.render/projection` — the exact symbol — on each contribution row
(`plan/context-blocks-contracts-2026-07-28.md` §5). The symbol's namespace
plus `V` and `O` re-derive which rung answered, so the rung is a derivation,
not a field. The same is true of an HTML surface: `:seon.render/surface`
carries the block and the router names the projection in any failure.

The audit question "why did this agent see THAT rendering of this data?" is
therefore already answerable: the capture names the symbol, the corpus at the
captured basis names the rungs, and the two together reproduce the choice.

## 4. The care-graph

### 4.1 Requires are the edges

`:seon.ns/require-edges` is landed schema. Viewing namespace `V` at hop 0; its
requires at hop 1; their requires at hop 2. Each required namespace `N`
contributes **its own view of itself**: the renderer for `(:seon.ns, K, V=N)`
— i.e. `N`'s own namespace renderer if it wrote one, else the `:seon.ns`
registration default (`render-ai`/`render-html`), else the panel. The viewer
does not choose how a required namespace presents itself, which is precisely
the owner's "those namespaces control what they show AIs and users".

This is not a new walk. It is `expand` with require-edges as the edges:
depth-first, left to right, per-path visited set (namespace requires genuinely
cycle in a live corpus), and the **existing** node/depth budgets from
`:seon.sci.admit/caps` — `:seon.config.eval.result/max-nodes` and
`max-depth`. **No new caps machinery, and this is not a preference**: the
measured lesson (`block.clj:500-522`, `tmp/n4_expand_blowup.clj`) is that a
per-path visited set refuses the wrong thing — a graph with no cycle at all
fanned out to four million nodes and OOM'd at depth 22. A require graph fans
out harder than a block graph. The node budget is the load-bearing bound; the
depth budget is the hop horizon.

### 4.2 Hop depth: a request parameter, not a kind

**Recommendation: a parameter on the request — `:seon.render/depth` — never a
new kind.** Three reasons, in order of weight:

1. **Kinds name CONSUMERS** (`ai`, `html`, `log`): who the projection is for.
   Depth names how much of one consumer's output. Making depth a kind
   multiplies the kind set by the depth budget, and `render/kinds` — which
   computes what a unit can become — would start reporting `:seon.render/ai-1`,
   `:seon.render/ai-2` as different things a unit *can become*. It cannot; it
   is one thing at different distances.
2. **Precedent is already sealed.** The presence ruling exempts
   `:seon.render/kind` as "a request argument, never stored". Depth is the same
   species: a request argument, never stored. It rides `:seon.render/unit-request`
   beside `:seon.render/kind` and `:seon.sci.admit/caps`, and `unit` threads it
   onto the unit exactly like caps (one line in `block.clj:313-321`).
3. **Nil-punning gives the default for free.** A projection that does not care
   never mentions it; `(get unit :seon.render/depth)` is nil at hop 0 and a
   projection reads nil as "full detail, you are the subject". A projection
   that does care writes one `cond`. No projection is obliged to change.

Vocabulary-table implications — one row added, one non-word recorded:

| Say | Never | Meaning |
|---|---|---|
| hop depth, `:seon.render/depth` | detail level, LOD, zoom, summary kind | the request's distance in `:seon.ns/require-edges` from the viewing namespace, bounded by the same `:seon.config.eval.result/max-depth` cap the eval door and `expand` already use |

And explicitly banned: a `:seon.render/summary` or `:seon.render/compact`
kind. The quarry already tried density as a taxonomy — `src/seon/render/`'s
"render-prominence law" grew flat presence-sets (`::compact`, `::full-source`,
`::with-tests`) with precedence rules between them. That is the shape this
ruling replaces with one integer.

### 4.3 What composition looks like end to end

Viewing `my.agents.foo`: hop 0 is the namespace's own source and its own
render functions (the auto-run rule, `context.md:388`); hop 1 is one card per
required namespace, each rendered by its owner at depth 1; hop 2 the same at
depth 2 — until the node budget or the depth cap stops the walk, at which point
the hole stays and says so (`expand`'s existing self-healing behaviour). The
agent's context and the human's page are the same walk under two kinds.

## 5. `seon.data` — the namespace that explains the system's data

`/data` is re-grounded as **`seon.data`**, and the re-grounding is a demotion
of the route: the page becomes one html render of a unit, and the unit is a
census.

**`seon.data` OWNS:**

- **the census** — every registered schema × every kind × every candidate
  renderer, as DATA. The owner's ruling stands verbatim: the census enumerates
  all possible views as data and *is itself a renderable unit* (ai + html),
  and rendering is **lazy** — you pay only for the kinds you request. The
  census is a value; asking for a view runs one projection.
- **the scoped selection lookup** (§3.1) and its one refusal (§3.2) — one function,
  the single place selection happens.
- **the completeness derivations** (§2.3): schemas without renderers,
  declarations naming absent functions, and the quarry's oldest scar —
  attributes written but never registered (`:seon.ai.attempt/*` 24 used / 0
  registered). All three are the same query family over the corpus and the
  attribute population.
- **entity-unit enrichment**: projecting a pulled entity's schemas' render
  declarations onto the pulled map, so an entity renders through the router
  with nothing declared at the call site.

**`seon.data` DERIVES everything and STORES nothing.** It has no attributes of
its own. Every question it answers is a query over `:seon.fn` / `:seon.ns` /
`:seon.schema` at a database value. If it ever grows a stored index, that is
the caching decision the plan says to *measure first*, and it needs its own
ruling.

**It does not absorb the render mechanisms.** `data-panel` stays in
`seon.render.block` (it is the html kind's floor, used by the router path), and
the get-in drill stays in `seon.render.data` (it is a render mechanism, and the
attribute-colocation ruling puts `:seon.render.data/*` there). `seon.data` is
the *explanation* layer above them. Whether the drill should move is an owner
decision (§8.6) rather than a thing this plan decides quietly.

Ping's place: **ping is the process-local half of the census** and must tell a
story. The census answers "what views exist over the system's data"; ping
answers "what is this process doing right now" — parked/mid-turn, current run,
episode runs, buffer occupancy. Same unit shape, same two projections, same
router. The fleet-oversight block is that unit (§6).

## 6. What lands when — the N5 order

Each unit names its falsifier. Order is dependency order, not preference.

**N5.1 — the indexer and its facts.** Four new `:seon.fn` attributes (§1.1);
the build-time producer; the diff/reconcile discipline quarried per §1.4.
*Falsifier:* index `src/`, and (a) `:seon.fn` rows exist for every public defn
with a count matching an independent `rg` census; (b) the workload classifier
returns the research's probed answers over the real graph, with `:mixed` for
every row carrying an uncertainty; (c) the declarations-naming-absent-functions
query returns exactly the three `seon.render.handlers.*` symbols the filed
issue names — a query whose right answer is a known-bad state is the honest
first test; (d) a second index of an unchanged tree writes ZERO datoms.

**N5.2 — the corpus round trip** (old step 4, verbatim). *Falsifier:* a `defn`
in form 1 is callable in form 2; another agent calls it after a restart;
acquisition happens at a basis; a durable defn without a complete
`:malli/schema` is refused with a flat error the agent sees.

**N5.3 — renderer discovery.** `output-schema-for-kind`, the two-clause query,
the registration-property lookup, the two completeness queries, and
`seon.render.block/derived` becoming non-empty. *Falsifier:* the sealed
`membership-collision-property` (seed 2026072807) stops being vacuous — it is
already written against a derived side that is currently empty by
construction, so N5.3 is the first thing that can make it fail; plus a datom
census proving a membership derivation transacts nothing.

**N5.4 — the invocation seam's SCI half** (the sealed named edge,
`context-blocks-contracts` §3.9/§10.1), authored WITH the N5 evaluator owner:
acquisition basis, fork, `:interrupt-fn`, admission placement. *Falsifier:* a
projection defined by an agent, resolved through the seam, returns the same
result union as a compiled Var and passes the same admission owner; a
projection that spins is stopped by the one `:interrupt-fn` and its failure is
a flat value naming the projection.

**N5.5 — scoped selection.** Four rungs, one same-namespace refusal.
*Falsifier:* an agent defines a renderer for `(S, :seon.render/ai)` in its own
namespace and the NEXT prompt's contribution changes with no edit to
`seon.cluster.prompt`, no route change and no reinstall (the sealed structural
falsifier, §4.9 of the contract, now exercised from the derived side); a second
renderer for the same `(S, K)` in the SAME namespace refuses loudly naming both
symbols and writes nothing; the capture's `:seon.render/projection` names the
symbol that actually ran.

**N5.6 — care-graph composition.** Require-edge walk on the existing expansion
budgets; `:seon.render/depth` on the request. *Falsifier:* a namespace with a
deliberately pathological require fan-out (the depth-22 construction, ported to
require edges) is bounded by the node budget and leaves legible holes rather
than OOM'ing; a require cycle is refused at the hole that closes it; the same
walk at depth 0 and depth 2 produces different bytes from the SAME renderers.

### What the fleet-oversight block already proves — before any of this

The fleet-oversight block is queued pre-N5 (dispatch after F2; it wants the
render proc and F1's ping states). It is not a warm-up: it proves the
composition's **invocation and placement halves end to end while the corpus is
still empty.**

- one unit, two projections, through the ONE router — so N5.3 adds
  *discovery* to a path that already works, never a second path;
- root carries it by default as installed block data — so the installed and
  derived sides of `membership` are exercised against each other before
  derived is non-empty;
- capture snapshots it as the `:seon.cluster.run/live-processes` trusted
  input — so the declared-inputs refusal (`assert-inputs!`) has a real
  customer;
- and it is the first "ping tells a story" surface visible in html — the
  process-local census, which N5.1's corpus census then extends from the
  process to the code.

If the fleet block is hard to build, that is evidence about the *render*
composition, discovered before the corpus work depends on it.

## 7. Distributed fix delegation along namespace boundaries

**Owner direction, 2026-07-28 late night:** a strong model takes a global,
broad-strokes attempt (the `seon.ai` generate-code shape). It fails **in
parts**. Each failing part routes to the namespace agent that OWNS it — a
schema conflict to the schema's owner, a broken test to the test's owner —
carrying the vision plus the failure details, and that agent localizes the
fix. *"Don't keep iterating from the global view once you have bisected the
problem cleanly along namespace boundaries."*

This is the same corpus, read for a different question. §§1–4 make a namespace
explain itself; this section makes a namespace *answer for* itself. Both are
queries over `:seon.fn` / `:seon.ns` / `:seon.schema` — which is why no new
machinery appears below.

### 7.1 Everything this needs is already ruled

Three landed mechanisms compose into the whole flow, and naming them is the
design:

1. **"Who should fix this is a query."** The error architecture already rules
   it (`plan/README.md`, rulings 2026-07-26 PM): *"Routing is derivation, never
   a router: the committed fault carries its provenance (namespace, var, proc,
   run), so 'who should fix this' is a query — the namespace's assigned agent
   (`:seon.agent/namespace`, unique, at most one) wakes on faults touching its
   namespace exactly the way messages wake … a fault whose program-graph call
   path spans namespaces derives multiple interested owners … No dispatch
   table, no subscription registry — commit good provenance and the routing
   already exists."* Delegation is that ruling used on purpose rather than
   only for faults.
2. **Failures are durable facts with provenance.** The error system landed
   (one normalizer, fault consumer, projections-per-consumer); a failure is a
   committed fact, not a message payload. So the delegating message carries a
   **ref to the fact**, never a copy of it — and the receiving agent derives
   the detail at its own basis, at whatever depth its own view specifies.
3. **Delivery is `my.message`.** Subagent messaging is live and a message is
   the trigger that opens the receiving agent's run (the run-opening
   transaction records the message as `:seon.db/trigger` tx-meta). Delegation
   needs no new transport and no new lifecycle.

And one property from §§1–4 is what makes localization *work* rather than just
route: **each namespace agent's context is its own namespace view.** The
receiving agent already sees its source, its schemas, its renderers, and its
requires at hop 1 — with no briefing assembled by the global attempt, and no
paste of context that would go stale. The message is small precisely because
the view is derived.

### 7.2 What N5 must supply — the ownership query, per failure shape

Bisection is computable exactly when a failure names something whose owner the
corpus knows. Four shapes, three of which are already answerable and one of
which is a concrete N5 gap:

| failing thing | the fact it carries | the ownership path | status |
|---|---|---|---|
| a **form** (an eval that threw, a defn that would not admit) | the receipt's provenance + the symbol being defined or called | `:seon.fn/sym` → `:seon.fn/ns` → `:seon.ns/name` → the agent with that `:seon.agent/namespace` | needs N5.1's rows; the receipt half is landed |
| a **schema** (conflicting registration, a validation failure naming a key) | `:seon.schema/key` on the error fact | `:seon.schema/key` → `:seon.schema/ns` → owner namespace → its agent | **landed attribute** (`schema.cljc:563`); needs only that error facts carry the key |
| a **call path** spanning namespaces | the fault's provenance + `:seon.fn/calls` reachability | every namespace on the path derives as an interested owner (the ruling's own words) | needs `:seon.fn/calls` — N5.1 |
| a **test** | `:seon.test/sym` | `:seon.test/sym` → `:seon.test/ns` → owner namespace → its agent | **GAP**: the quarry registers `:seon.test/sym` / `:seon.test/ns` / `:seon.test/source` (`src-old/seon/db/program.clj:27-36`) and the fresh `schema.cljc` does not. N5.1 must index test rows, or a broken test has no computable owner |

That last row is the one real new requirement this section places on N5, and
it is small: `:seon.test` is already in the quarry's identity-attribute list,
so the indexer's reconcile discipline covers it the moment the rows exist.

**A failure with no computable owner goes to root, loudly, as a problems row.**
Fail-closed: an unattributable failure is a fact about the corpus (something
ran that the graph does not know about), and it is exactly the case where the
global view legitimately keeps working. It must never be silently dropped, and
it must never be guessed at by name prefix — that would be the banned hand
list wearing a heuristic's clothes (R34).

### 7.3 The flow, and the stopping rule

1. The global attempt runs and produces failures — each a committed fact with
   provenance.
2. The bisection is one query: group the failures by derived owner namespace.
   Pure, over a database value, transacting nothing.
3. For each owned group, one `my.message` to that namespace's agent carrying
   **the vision** (what the global attempt was trying to achieve, in prose) and
   **refs to the failure facts**. The receiving agent's next turn derives the
   rest from its own namespace view.
4. Unowned failures stay with the global view (and surface on root's problems
   block).
5. **The stopping rule is structural, not a policy anyone remembers:** the
   global attempt's episode ends when every failure has an owner and a
   message. It continues only while unowned failures remain. So "don't keep
   iterating from the global view" is enforced by there being nothing left for
   that view to iterate on — not by a counter, and not by the episode cap
   (ruled 100), which stays what it is: a runaway backstop.

Two consequences worth stating so they are not re-litigated:

- **No dispatcher, no work queue, no assignment table, no retry orchestrator.**
  Every one of those would be the central-loop shape the agents-are-flows
  ruling already rejected. A message wakes an agent; the agent's own graph does
  the rest.
- **Concurrent localized fixes are safe by construction, and this is not luck.**
  A namespace has at most one assigned agent (`:seon.agent/namespace`, unique),
  and each agent has at most one open run (the transaction fence). Two agents
  fixing two namespaces is ordinary parallelism; two agents fixing one
  namespace cannot be constructed.

### 7.4 Falsifier

A deliberately multi-namespace broken change: one schema conflict in namespace
A, one failing test in namespace B, one throwing form in namespace C, and one
failure whose provenance names nothing indexed. The proof is that (a) the
bisection query returns exactly three owners and one unowned residue, (b)
exactly three messages are sent and each names the right agent, (c) each
receiving agent's derived context already contains the relevant source without
the message carrying it, (d) the global agent's episode ends with the
unowned residue and does not re-attempt the three delegated parts, and (e) the
whole bisection transacts nothing.

## 8. Owner decisions required before the contracts seal

1. **The kind→output-schema suffix rule** (§2.1). `:seon.render/ai` ⇒
   `:seon.render/ai-output`, computed. Recommended over both alternatives: a
   registry row per kind (a hand list), or reusing the kind keyword itself as
   the output schema key (impossible — one global key, two contradictory
   shapes).
2. **Discovery requires REGISTERED schema names on the contract.** An inline
   anonymous `:malli/schema` makes a function undiscoverable. Recommended: yes,
   naming is the mechanism. The cost is that agents must register a shape
   before they can render it; the benefit is that discovery never guesses.
3. **The defn's namespace IS the viewing scope** (§3.1) — no scope attribute.
   Recommended. The alternative (an explicit `renders-for` declaration) buys
   cross-namespace claiming, which is exactly the thing "all namespaces
   self-focused" rejects.
4. **Same-namespace duplicate renderers REFUSE** (§3.2), while cross-namespace
   ones are separate keys answering separate viewers. Recommended; this is the structural line
   between declared intent and accident.
5. **Hop depth is a request parameter, not a kind** (§4.2), with the
   vocabulary row and the explicit ban on a `:seon.render/summary` kind.
   Recommended.
6. **`seon.data`'s boundary** (§5): the census + selection + completeness +
   entity enrichment, with `data-panel` staying in `seon.render.block` and the
   drill staying in `seon.render.data`. Alternative: fold the drill into
   `seon.data` and let `seon.render.data` die. Recommended: keep them split —
   the drill is a render mechanism and its attributes are already colocated —
   but this is a taste call the owner should make before a lane bakes it in.
7. **`:seon.fn/input-schema` / `output-schema` as stored projections of
   `:seon.fn/spec`** (§1.1) — the one stored derivation in the design. Accepted
   because the alternative is parsing an EDN string inside every discovery
   query, but it IS a derived-state exception and deserves an explicit yes.
8. **Implicit `:compute` for computed-pure functions** — already collected as
   plan decision 2 and unchanged here; N5.1 is the rung where it becomes
   answerable from real facts, so it wants an answer at this rung rather than
   later.

Raised by §7 (delegation):

9. **`:seon.test` rows enter the fresh corpus** (§7.2) — adopting the quarry's
   `:seon.test/sym` / `ns` / `source` shape, so a broken test has a computable
   owner. Recommended: yes; without it the test row of the bisection table is
   unanswerable and broken tests fall to root.
10. **A failure whose call path spans namespaces messages EVERY interested
    owner** (the fault ruling's own words), rather than only the deepest
    frame's owner. Recommended: every interested owner — the safety argument
    is structural (one agent per namespace, one run per agent), and the
    alternative silently picks a frame as "the" cause, which is the guess the
    corpus exists to avoid. Cost: N messages for one failure.
11. **The global attempt does not `wait` for its delegates.** Recommended: it
    ends its episode, and a delegate's reply message re-triggers it as an
    ordinary new episode — the messaging model, needing no new state.
    Alternative: `my.run/wait` held open across delegated fixes, which
    reintroduces a coordination state the crash model would then have to
    recover.
12. **Unowned failures escalate to root as a problems row** (§7.2), never a
    name-prefix guess at an owner. Recommended; stated because a heuristic
    here would be very tempting and is the banned hand list.

## 9. The single riskiest design point

**Scoped selection's rung 1 — a renderer in the viewing namespace silently
changing what an agent sees.** Discovery is a query, and a query's answer
changes when someone commits a function. That is the entire point (an agent
improves how its namespace explains itself, and the next turn shows it), and it
is also the sharpest edge in the design: an agent can, by writing one `defn`,
change what it is shown about its own data — including data it does not own —
with no installation, no review, and no refusal anywhere, because the lookup is
total and cross-namespace multiplicity is legal by construction.

Three properties keep it honest, and all three must be in the sealed suite
rather than assumed:

- the choice is **recorded** — the capture names the exact symbol, so a
  confabulating agent's context is reproducible after the fact (§3.3);
- the scope is **bounded** — a rung-1 renderer applies only to views taken
  from that namespace, never to another namespace's view of the same schema,
  which is what makes it a point of view rather than a corruption;
- the floor is **unreachable to break** — rung 4 is a compiled-core generic, so
  a broken or malicious lens costs its own surface (`render` is total) and
  the panel still explains the value.

The residual risk that no property removes: an agent that writes a *plausible
but wrong* renderer for data it does not own degrades its own context quietly,
and the failure looks like a model mistake rather than a rendering one. The
mitigation is legibility, not prevention — the rung is derivable from the
capture, so "why did it think that?" has an answer. Naming this now is
deliberate: it should be the first thing looked at when an agent starts being
confidently wrong about facts the database plainly holds.
