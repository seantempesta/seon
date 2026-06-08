---
type: research
status: active
tags: [research, agent, flow, schema]
---

# MVP recon: render resolution, require→context, schema catalog, code-as-data

Recon for the part of the MVP I own: the mechanism by which (a) an agent's
`require` of a namespace renders THAT namespace's context into the requiring
agent, and (b) a boot agent gets a catalog of all schemas/namespaces (with
brief descriptions) in its context.

## TL;DR

- **Two render systems exist, on two tracks.** The MVP runs in the **CLJS pod**
  (`src/seon/*.cljs`). The render system that matters for the MVP is
  `src/seon/render.cljs` (symbol-slot resolution + schemas-as-queryable-data
  kind dispatch + `assemble-ai-context` tx-log assembler). The `.clj` siblings
  (`render.clj`, `graph/query.clj`, `ns/routes.clj`) are the **JVM/platform**
  versions — specificity-by-required-keys resolution over a code graph. They are
  NOT what the live pod agent uses. Don't build the MVP on the `.clj` path.
- **Code-as-data is REAL and shipped in the pod.** Agent-defined fns/schemas/ns
  are persisted as `:seon.fn` / `:seon.schema` / `:seon.ns` entities via
  detect-and-tee in `seon.eval/eval-batch!` (`build-tee-entities`,
  eval.cljs:715). Bulk-load resume re-evals them (`replay-program-graph!`,
  client.cljs:495). The analyzer read-side is `seon.analyzer-info`.
- **The schema catalog: the raw materials EXIST, the catalog rendering DOES NOT.**
  `seon.schema` can enumerate all registered schemas and group by namespace
  (`registered-schemas`, `schemas-in-namespace`, `schema-definition`). Malli
  schemas carry `{:description "..."}` props in several registrations. But NO fn
  assembles "all schemas/namespaces with brief descriptions" into a context
  section, and the boot agent's six default sections do not include one.
- **require → context: ENTIRELY TO-BE-BUILT.** Nothing today turns a `require`
  into "render the required namespace's context into me." `require` in agent
  code only loads code into the analyzer/runtime. The closest seams: the
  `current-ns-section` (renders ONE ns's owned fns/schemas — the requiring
  agent's own home ns, not required nses) and the `:seon.ns.dep/*` dependency
  edges (which exist only on the **JVM** graph, NOT in the pod).
- **For the FIRST MVP** ("just basic context rendering based on the namespaces
  required is fine"): the minimal version is a single new ctx section function
  in `seon.agent` that (1) reads the requiring agent's required nses from the
  analyzer's `:requires` map for its home ns, and (2) for each, queries the
  `:seon.ns` / `:seon.fn` / `:seon.schema` entities and renders their digested
  API. The `current-ns-section` reverse-ref pull is the template — generalize it
  from "the current ns" to "each required ns."

## What exists (file:line anchors)

| Capability | Status | Where |
|---|---|---|
| CLJS render: symbol-slot resolution (`:seon.render/ai`/`/html`) | EXISTS | `render.cljs:136` `ai-render`, `render.cljs:145` `html-render` |
| CLJS render: late-bound symbol lookup (substrate + agent fns) | EXISTS | `eval.cljs:279` `lookup-value` (walks globalThis munged paths) |
| CLJS render: kind dispatch via schemas-as-queryable-data | EXISTS | `render.cljs:202` `renderable-kinds`, `render.cljs:238` `entity-primary-kind` |
| CLJS render: tx-log-as-context assembler (window + sticky) | EXISTS | `render.cljs:406` `assemble-ai-context` |
| Per-agent ctx composer (six default sections) | EXISTS | `agent.cljs:1298` `assemble-ctx`, `agent.cljs:1330` `substrate-default-ctx` |
| Section fn: renders ONE ns's owned fns+schemas (current ns) | EXISTS | `agent.cljs:1110` `current-ns-section` |
| Code-as-data: detect-and-tee → `:seon.fn`/`:seon.schema`/`:seon.ns` | EXISTS | `eval.cljs:715` `build-tee-entities`, called eval.cljs:1012 |
| Code-as-data: analyzer read-side (defs diff, var projection, ns-deps) | EXISTS | `analyzer_info.cljs` (`snapshot-defs`, `defs-since`, `var-projection`, `ns-deps`) |
| Code-as-data: bulk-load resume (re-eval persisted source, tx-order) | EXISTS | `client.cljs:495` `replay-program-graph!` |
| Substrate seed: curated `:seon.ns`+`:seon.fn` core API rows at boot | EXISTS | `client.cljs:611` `core-fn-curated`, `client.cljs:652` `seed-core-fns!` |
| Schema enumeration: all registered schemas | EXISTS | `schema.cljc:377` `registered-schemas`, `:262` `current-keys` |
| Schema enumeration: schemas grouped by namespace | EXISTS | `schema.cljc:396` `schemas-in-namespace` |
| Schema enumeration: one schema's raw definition (carries `:description`) | EXISTS | `schema.cljc:389` `schema-definition` |
| Entity-schema decomposition into DB rows (kind/id-attr/render-fn) | EXISTS | `schema.cljc:318` `entity-schema-tx-data`, `:358` `all-entity-schemas-tx-data` |
| **Boot schema catalog ("all schemas by ns + descriptions" section)** | **MISSING** | — (no section fn; not in `substrate-default-ctx`) |
| **require → render required ns's context into requiring agent** | **MISSING** | — (no mechanism; `require` only loads code) |
| **Required-ns digest section (renders deps' digested API)** | **MISSING** | — (`current-ns-section` does only the agent's own ns) |
| **`(seon.ai/new-namespace …)` capability** | **MISSING** | — (no `seon.ai` ns in the pod; no such fn) |
| ns dependency edges (`:seon.ns.dep/*`, `dependents-of`) | EXISTS but **JVM-ONLY** | `graph/query.clj:61` — NOT populated in the pod DB |

### 1. The render system (CLJS pod — the one that matters)

`render.cljs` is NOT the specificity-by-required-keys resolver in `render.clj`.
It is two thinner mechanisms:

- **Symbol-slot resolution.** Each renderable entity (or its entity-kind schema)
  carries a `:seon.render/ai` / `:seon.render/html` symbol. `ai-render` /
  `html-render` resolve the symbol via `eval/lookup-value` (walks `globalThis`
  munged paths — works for both shadow-precompiled substrate fns and
  agent-defined fns written by `cljs.js/eval-str`) and call it; miss falls
  through to `seon.render.default/pretty-*`. No code-graph query needed.
- **Kind dispatch via schemas-as-queryable-data.** `renderable-kinds` /
  `entity-primary-kind` (render.cljs:202–267) read the `:seon.schema` entities
  (decomposed from registered `:map` schemas at boot by
  `schema/all-entity-schemas-tx-data`) to find the most-specific entity kind
  whose required-attrs are all present, then look up that kind's render symbols.
  This is how `:seon.eval` / `:seon.message` / `:seon.fn` rows get their
  renderers (declared on the `:map` schema props in agent.cljs:306–360).

**What renders "a namespace's context" today:** only `current-ns-section`
(agent.cljs:1110). It derives the agent's OWN current ns (latest successful
eval's `:seon.eval/ns`), then does a reverse-ref pull
(`:seon.fn/_ns` + `:seon.schema/_ns`) of every fn/schema owned by that one ns
and joins their `:source` strings. **This is the template for the MVP** — it
already does "given a ns, render all its owned program-graph entities." It just
does it for exactly one ns (the requiring agent's own), not for required nses.

The JVM `render.clj` `find-renderer`/`resolve-renderer`/`find-page-renderer`
(specificity over `:seon.fn/output-spec` → `:seon.spec/contains-keys` via
`graph/query.clj functions-with-output-key`) is a different, richer resolution
path. It is irrelevant to the pod MVP and operates on a `:seon.runtime` JVM DB
the pod does not have.

### 2. The analyzer / code-as-data — REAL and shipped in the pod

The principle in `docs/seon/concepts/code-as-data-runtime.md` is implemented:

- **Detect-and-tee** (`build-tee-entities`, eval.cljs:715, invoked at
  eval.cljs:1012 only on successful eval): snapshots the analyzer's `:defs` and
  the schema registry keyset before each form (`snapshot-defs`,
  `schema/current-keys`), diffs after (`defs-since`, `set/difference`), and emits
  `:seon.fn` entities (with `var-projection` projections: `:fn-var?`, `:arglists`,
  `:doc`, `:private?`, `:specced?`), `:seon.schema` entities, and an `:seon.ns`
  entity when the form is an `(ns …)`. These ride in the SAME tx as the
  `:seon.eval` entity (`record-eval!`, eval.cljs:794).
- **Analyzer read-side** is `seon.analyzer_info` — reads
  `(:cljs.analyzer/namespaces @compile-state)` directly (self-host CLJS lacks
  `find-ns`/`ns-resolve`). `ns-deps` (analyzer_info.cljs:134) already computes
  "the set of agent-nses this ns requires" from `:requires` + `:uses` +
  `:require-macros` — **this is the read primitive the require→context MVP
  needs** (it is currently used only for resume topo-sort).
- **Bulk-load resume** (`replay-program-graph!`, client.cljs:495): re-evals every
  persisted source in tx-id order on boot. `:ns`→eval from `cljs.user` (source is
  the `(ns …)` form), `:fn`/`:schema`→eval in the owning ns.
- **Substrate seed:** `seed-core-fns!` (client.cljs:652) seeds a tiny curated
  table (`core-fn-curated`, client.cljs:611 — `seon.db/transact!`, `query`,
  `pull`, `schema/register!`, `test.runner/run!`) as `:seon.ns`+`:seon.fn` rows
  with synthesized `(defn … ,,,)` source shells + faithful arglists/doc. Comment
  notes the curated table is the fallback because only `seon.schema` is in
  `out/bootstrap`'s analyzer cache; the rest live only in precompiled pod JS.

### 3. require → context: ENTIRELY TO-BE-BUILT

There is no mechanism that turns a `require` into context injection. In the pod,
`require` (inside an agent `(ns …)` form, evaled via `cljs.js`) only loads code
into the analyzer + runtime. Nothing reads the requiring ns's `:requires` to
render the required nses' digested API into the prompt.

Closest existing seams:

- `analyzer_info/ns-deps` — already reads the `:requires` map. The exact read the
  MVP needs, currently used only for resume topo-sort.
- `current-ns-section` — already renders "given a ns, all its owned program-graph
  entities." Generalize from one ns to each required ns.
- `:seon.ns.dep/*` edges + `dependents-of`/`dependencies-of` (graph/query.clj) —
  these model ns dependency edges but live on the **JVM `:seon.runtime` graph**
  populated by `seon.graph.ingest` (static analysis of `.clj` source). The pod
  DB has NO `:seon.ns.dep/*` datoms. Do not reach for this path.

### 4. The schema catalog: raw materials exist, assembly does not

`seon.schema` (the single source of truth via `register!`) can already:

- list all registered schema keys (`current-keys`, `registered-schemas`),
- group by namespace (`schemas-in-namespace` — returns `{kw definition}` for one
  ns string),
- return one schema's raw Malli definition (`schema-definition`).

Many registrations carry human descriptions in Malli props, e.g.
`[:keyword {:description "Database name keyword, e.g. :seon.runtime"}]`
(graph/query.clj:46) and `[:int {:description "..."}]`. A catalog can pull
`(:description (m/properties (m/schema def)))` per key.

What's MISSING: a fn that assembles "every schema, grouped by namespace, each
with its brief description" into a render string, AND a ctx section that places
it in the boot agent's context. The six default sections
(`substrate-default-ctx`, agent.cljs:1330) are `system`, `messages`,
`current-ns`, `warnings`, `recent-evals`, `prompt` — no catalog section.

Note the DB already holds `:seon.schema` rows (kind/id-attr/required-attrs/
render-fn) from `all-entity-schemas-tx-data`, but those are the **entity-kind**
schemas only (`:map` shapes with an id-attr), not the full attribute-level
registry. The full registry lives in the in-memory `seon.schema/*schemas` atom.
A boot catalog has two possible sources: the in-memory atom (full, all attrs) or
the `:seon.schema` DB rows (entity kinds only). For a catalog of "schemas
organized by namespace with descriptions," the in-memory atom is the richer
source and is process-local (fine for a render-time read).

### 5. `(seon.ai/new-namespace …)` — MISSING

No `seon.ai` namespace exists in the pod (`src/seon/ai.cljs` absent;
`seon.ai.deepseek` is the LLM adapter only). The "propose a new namespace" verb
is unbuilt. Out of scope for the first MVP per the brief, but flagged: when
built, it is just a `db/transact!` of a `:seon.ns` entity (+ description) — the
same shape detect-and-tee already produces.

## What's missing for MVP (precise)

For **(a) a namespace having a renderable "context"** (its fns + schemas + render
hints):

- The data already exists per ns: `:seon.ns/source`, and reverse-ref
  `:seon.fn/_ns` (with `:seon.fn/doc`, `:arglists`, `:source`) +
  `:seon.schema/_ns` (with `:seon.schema/source`). `current-ns-section` already
  pulls exactly this for one ns.
- MISSING: a reusable `render-ns-context` helper parameterized by ns-keyword
  (factor it out of `current-ns-section`), returning the digested API string for
  any ns — substrate-seeded nses (`seon.db`, etc. from `seed-core-fns!`) AND
  agent-defined nses.

For **(b) requiring a namespace injecting that context**:

- MISSING: a section fn that reads the requiring agent's required nses and renders
  each one's context. The read primitive (`ns-deps`) and the per-ns renderer
  (factored `current-ns-section`) both nearly exist; the glue does not.

For the **boot schema catalog**:

- MISSING: a `catalog-section` fn + its entry in `substrate-default-ctx`,
  enumerating registered schemas grouped by namespace with `:description` props.

## Minimal design — require→context render (first MVP)

Goal (user-scoped): "just basic context rendering based on the namespaces
required is fine." Build it as ONE new section function in `seon.agent`, reusing
the two existing primitives. No new mechanism, no new storage — pure reactive
section (per `reactive-context.md`).

### Step 1 — factor a per-ns renderer out of `current-ns-section`

Extract the reverse-ref pull body of `current-ns-section` (agent.cljs:1110) into:

```
(defn render-ns-context [db ns-kw] -> string | "")
  ;; one reverse-ref pull:
  ;;   [:seon.ns/source
  ;;    {:seon.schema/_ns [:seon.schema/source]
  ;;     :seon.fn/_ns     [:seon.fn/sym :seon.fn/arglists :seon.fn/doc]}]
  ;; render: ns name + each fn's (sym arglists + first doc line) + schema sources
  ;; guard with (db/entity [:seon.ns/name ns-kw]) so missing ns => "" (no throw)
```

Rewrite `current-ns-section` to call `(render-ns-context db (current-ns …))`.
This is the "namespace HAS a renderable context" piece, and it works for any ns
already in the program graph (substrate-seeded or agent-defined).

### Step 2 — read the requiring agent's required nses from the analyzer

The agent's home ns is `(home-ns id)` (e.g. `seon.agent.<id>`). After the agent
evals `(ns seon.agent.<id> (:require [seon.db] [seon.user.email]))`, the
analyzer's `:requires` for that ns carries the dep set. Reuse
`analyzer_info/ns-deps`:

```
(let [home (home-ns id)
      ;; known-ns-set = nses that have a :seon.ns row (i.e. have renderable ctx).
      known  (set of :seon.ns/name syms present in DB)
      deps   (analyzer-info/ns-deps @compile-state home known)]
  ...)
```

`ns-deps` already intersects with a known-ns set and drops cljs.core/clojure.*.
Passing the set of nses that have `:seon.ns` rows naturally limits rendering to
nses that actually carry seon context (substrate API + agent-authored).

Note: the section fn receives `{:seon.db/db db :seon.agent/id id}` today; it does
NOT receive `compile-state`. Two options: (i) read compile-state from the shared
`seon.repl/!compile-state` atom inside the section (simplest, matches how other
substrate state is reached), or (ii) skip the analyzer entirely and derive
required nses from the agent's persisted home-ns source — pull
`[:seon.ns/source]` for `(home-ns id)`, read-string it, and pull the
`(:require …)` forms. Option (ii) keeps the section a pure DB read (no analyzer
coupling) and is the more "reactive-context" choice. **Recommend (ii)** for the
first MVP: the home-ns source is already a `:seon.ns` row (detect-and-tee writes
it when the agent evals its `(ns …)` form), so the requires are queryable
without touching `@compile-state`.

### Step 3 — new section fn `required-context-section`

```
(defn required-context-section
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  ;; 1. pull (home-ns id)'s :seon.ns/source, parse its (:require …) → dep ns-kws
  ;; 2. keep deps that have a :seon.ns row in db
  ;; 3. (map #(render-ns-context db %) deps), remove blanks, join
  ;; 4. wrap in <required-namespaces> … </required-namespaces>; "" when none
```

Add it to `substrate-default-ctx` (agent.cljs:1330) at a priority between
`current-ns` (30) and `warnings` (40), e.g. `:required-context` priority 35.

### Why this is the right minimal shape

- **One mechanism, reused.** Same reverse-ref pull as `current-ns-section`; same
  composer; same `:seon.ctx` section model. No RAG, no retrieval, no new store —
  context arrives through the require graph exactly as the vision states, and the
  require graph is read from the agent's own persisted home-ns source (already a
  code-as-data entity).
- **Reactive + self-healing.** When the agent removes a `require`, the next
  render's parse drops that ns and its context vanishes. When a required ns gains
  a fn (the agent or another agent defines one), detect-and-tee adds the
  `:seon.fn` row and it appears on the next render. Nothing stored, nothing to
  invalidate.
- **Cross-agent for free.** `render-ns-context` queries `:seon.fn/_ns` without an
  agent filter, so if agent B authored `seon.user.email`, agent A requiring it
  sees B's fns — the substrate is shared.

### Deferred (NOT first MVP)

- Boot schema **catalog** section (separate `catalog-section` over
  `schema/registered-schemas` grouped by ns + `:description`). Independent of
  require→context; can land in the same patch but is its own section fn.
- `(seon.ai/new-namespace …)` verb.
- Specificity / `:seon.render/ai` per-fn digest renderers (the section renders
  raw `:source` + arglists/doc; richer per-fn AI renderers are v2).
- Transitive require closure (render only direct requires first; `ns-deps` /
  parsed-requires give direct deps).

## Code smells / flags

- **Two render namespaces named `seon.render`** (`render.clj` and `render.cljs`)
  with substantially different resolution models (JVM specificity-over-code-graph
  vs CLJS symbol-slot + kind-dispatch). This is the documented `.clj`/`.cljs`
  lane split, but a reader grepping `seon.render` will conflate them. The MVP is
  pod-only → `render.cljs`.
- **`current-ns-section` docstring** (agent.cljs:1114) still says it renders empty
  "because eval-batch!'s detect-and-tee step (Platform's Patch 2) hasn't
  shipped." Detect-and-tee HAS shipped (`build-tee-entities` is wired at
  eval.cljs:1012). The docstring is stale; the section is live. Flagging, not
  fixing (out of recon scope).
- **`:seon.ns.dep/*` dependency edges exist only on the JVM graph.** If a future
  task wants "who requires this ns" in the pod, it must be derived from the
  parsed `:seon.ns/source` `(:require …)` forms (as the MVP above does) — there
  is no pod-side dep-edge index.
