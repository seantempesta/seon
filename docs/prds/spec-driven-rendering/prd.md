---
type: prd
status: active
tags: [prd, database, flow, web]
---
# PRD: Spec-Driven Rendering + Code Index

## Summary

The Seon Datalevin database is the **single source of truth** for all code metadata: what namespaces exist, what functions they contain, what specs they define, and how they relate to each other. Everything queries Datalevin to understand the system.

How data gets into Datalevin is just plumbing — startup scan, Super REPL eval, dev hook, git merge — the details of ingestion don't matter to consumers. The system always asks Datalevin "what renderers match this data?" or "what functions accept this key?" regardless of how the code got there.

Render functions are discovered automatically: any function whose output spec contains `:seon.render/html` or `:seon.render/ai` is a render function. Resolution picks the most specific input match (key-count DESC), with newest timestamp as tiebreaker.

## Motivation

The current `seon.render` system requires explicit `register-renderer!` calls -- a separate registration step that duplicates information already present in `:malli/schema` metadata. This creates friction for agents: they must remember to register renderers after defining functions.

The knowledge graph (`seon.graph.*`) already ingests clj-kondo analysis into Datalevin but uses ad-hoc `:graph/*` keys and lacks Malli spec awareness. Unifying code metadata into a single well-structured index enables:

- Automatic render function discovery (no registration step)
- Cross-namespace function discovery ("what accepts `:seon.health.workout/exercise`?")
- Agent context building from specs + docs (no source code needed)
- Override-by-specificity (agents write more specific functions, no special mechanism)

## Architecture

### Data Model

Four entity types in the master `seon` Datalevin database.

#### Function Entities (`:seon.fn/*`)

Populated from clj-kondo var-definitions + Malli `:malli/schema` var metadata.

```clojure
{:seon.fn/qualified-name  "seon.health.workout/log-workout!"  ; unique identity
 :seon.fn/namespace       "seon.health.workout"
 :seon.fn/name            "log-workout!"
 :seon.fn/doc             "Records a workout set to the database"
 :seon.fn/arglists        "[{::keys [conn exercise sets reps weight]}]"
 :seon.fn/input-spec      [:seon.spec/key :seon.health.workout/log-workout-request]   ; lookup ref
 :seon.fn/output-spec     [:seon.spec/key :seon.health.workout/log-workout-response]  ; lookup ref
 :seon.fn/row             42
 :seon.fn/col             1
 :seon.fn/private         false
 :seon.fn/render-input-keys [:seon.health.workout/exercise   ; pre-computed for render fns
                             :seon.health.workout/sets        ; only present when output-spec
                             :seon.health.workout/reps        ; contains :seon.render/html or /ai
                             :seon.health.workout/weight]
 :seon.fn/updated-at      #inst "2026-02-19"}

```

Notes:

- `:seon.fn/qualified-name` is a string (not symbol) for consistent serialization
- `:seon.fn/render-input-keys` is pre-computed during scan for render functions only, enabling single-query resolution
- Source code is NOT stored -- retrieve via `clojure.repl/source-fn` on demand

#### Spec Entities (`:seon.spec/*`)

Populated from static source analysis of `schema/register!` calls in source files. The scanner parses these calls using edamame/clj-kondo AST — it does NOT read from the Malli global registry at runtime. This is critical because agent JVMs have their own registries, ClojureScript has a different runtime, and code on disk that isn't loaded won't be in any registry.

```clojure
{:seon.spec/key           :seon.health.workout/log-workout-request  ; unique identity
 :seon.spec/namespace     "seon.health.workout"
 :seon.spec/definition    "[:map [::exercise ::exercise] [::sets ::sets] ...]"
 :seon.spec/base-type     :map
 :seon.spec/contains-keys [:seon.health.workout/exercise   ; pre-computed for Datalog joins
                           :seon.health.workout/sets
                           :seon.health.workout/reps
                           :seon.health.workout/weight]
 :seon.spec/updated-at    #inst "2026-02-19"}

```

#### Namespace Entities (`:seon.ns/*`)

Populated from clj-kondo namespace-definitions + file extension analysis.

```clojure
{:seon.ns/name       "seon.health.workout"  ; unique identity
 :seon.ns/doc        "Weightlifting tracking domain"
 :seon.ns/file       "src/seon/health/workout.clj"
 :seon.ns/target     :clj                   ; extracted from file extension (.clj, .cljs, .cljc)
 :seon.ns/requires   ["seon.schema" "seon.db.datalevin"]
 :seon.ns/updated-at #inst "2026-02-19"}

```

Notes:

- `:seon.ns/target` indicates the runtime target: `:clj` (JVM), `:cljs` (ClojureScript), `:cljc` (both). Domain code should prefer `:cljc` when possible — no JVM-specific deps, plain data in/out. Seon infrastructure (`seon.web.*`, `seon.db.*`, `seon.flow.*`) is `:clj`-only.
- Target is critical for graduation (writing code back to disk in the right format) and for knowing which sessions can load the code (JVM vs ClojureScript).

#### Call Graph Entities (`:seon.call/*`)

Populated from clj-kondo var-usages. `:seon.call/from-fn` and `:seon.call/to-fn` are `:db.type/ref` pointing at function entities.

```clojure
{:seon.call/from-fn   [:seon.fn/qualified-name "seon.health.workout/log-workout!"]  ; ref via lookup ref
 :seon.call/to-fn     [:seon.fn/qualified-name "datalevin.core/transact!"]           ; ref via lookup ref
 :seon.call/row       45
 :seon.call/col       5}

```

**External functions** (outside the project, e.g. `datalevin.core/transact!`) get minimal stub entities with only `:seon.fn/qualified-name`, `:seon.fn/namespace`, and `:seon.fn/name`. This ensures refs always resolve.

**Ingestion order:** spec entities first, then fn entities (which ref specs), then call graph entities (which ref fns). This guarantees all ref targets exist before they are referenced.

### Datalevin Schema

```clojure
{;; Function entities
 :seon.fn/qualified-name  {:db/valueType :db.type/string :db/unique :db.unique/identity}
 :seon.fn/namespace       {:db/valueType :db.type/string}
 :seon.fn/name            {:db/valueType :db.type/string}
 :seon.fn/doc             {:db/valueType :db.type/string}
 :seon.fn/arglists        {:db/valueType :db.type/string}
 :seon.fn/input-spec      {:db/valueType :db.type/ref}       ; ref to spec entity
 :seon.fn/output-spec     {:db/valueType :db.type/ref}       ; ref to spec entity
 :seon.fn/row             {:db/valueType :db.type/long}
 :seon.fn/col             {:db/valueType :db.type/long}
 :seon.fn/private         {:db/valueType :db.type/boolean}
 :seon.fn/render-input-keys {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
 :seon.fn/updated-at      {:db/valueType :db.type/instant}

 ;; Spec entities
 :seon.spec/key           {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
 :seon.spec/namespace     {:db/valueType :db.type/string}
 :seon.spec/definition    {:db/valueType :db.type/string}
 :seon.spec/base-type     {:db/valueType :db.type/keyword}
 :seon.spec/contains-keys {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
 :seon.spec/updated-at    {:db/valueType :db.type/instant}

 ;; Namespace entities
 :seon.ns/name       {:db/valueType :db.type/string :db/unique :db.unique/identity}
 :seon.ns/doc        {:db/valueType :db.type/string}
 :seon.ns/file       {:db/valueType :db.type/string}
 :seon.ns/target     {:db/valueType :db.type/keyword}  ; :clj, :cljs, :cljc
 :seon.ns/requires   {:db/valueType :db.type/string :db/cardinality :db.cardinality/many}
 :seon.ns/updated-at {:db/valueType :db.type/instant}

 ;; Call graph
 :seon.call/from-fn  {:db/valueType :db.type/ref}          ; ref to fn entity
 :seon.call/to-fn    {:db/valueType :db.type/ref}          ; ref to fn entity
 :seon.call/row      {:db/valueType :db.type/long}
 :seon.call/col      {:db/valueType :db.type/long}}

```

## Renderer Resolution Algorithm

A render function is any function whose `:seon.fn/output-spec` references a spec that contains `:seon.render/html` or `:seon.render/ai` as keys (or IS one of those keywords directly).

### Resolution: Single Query

Pre-computed `:seon.fn/render-input-keys` on function entities enables single-query resolution:

```clojure
(defn find-renderer
  "Find the best render function for the given data and format.

   Resolution order:
   1. Most input keys matched (specificity)
   2. Newest updated-at (recency)
   3. Alphabetical qualified-name (deterministic tiebreaker)"
  [conn data format]
  (let [data-keys (set (keys data))
        format-key (case format :html :seon.render/html :ai :seon.render/ai)
        ;; Find all render functions for this format whose input keys
        ;; are a subset of the data's keys
        candidates
        (d/q '[:find ?qn ?updated (count ?k)
               :in $ [?dk ...]
               :where
               [?fn :seon.fn/render-input-keys ?k]
               [(contains? ?dk-set ?k)]  ;; pseudo -- actual impl filters in Clojure
               [?fn :seon.fn/qualified-name ?qn]
               [?fn :seon.fn/updated-at ?updated]]
             @conn (vec data-keys))]
    ;; In practice: pull all render fn entities, filter in Clojure
    ;; for subset check, sort by key-count DESC, updated-at DESC, name ASC
    (->> candidates
         (filter (fn [{:seon.fn/keys [render-input-keys]}]
                   (every? data-keys render-input-keys)))
         (sort-by (juxt (comp - count :seon.fn/render-input-keys)
                        (comp - #(.toEpochMilli %) :seon.fn/updated-at)
                        :seon.fn/qualified-name))
         first)))

```

### Practical Implementation Note

For real-time rendering (SSE polling at 1-2s intervals), cache resolution results keyed by `[format (set (keys data))]`. Invalidate cache when scanner updates function entities. Expected cache hit rate is very high since the same data shapes render repeatedly.

### Override Mechanism: Turtles All the Way Down

No special override mechanism. An agent that wants a custom renderer writes a function with the same (or more specific) input spec and a newer timestamp. The resolution algorithm picks it up automatically.

```clojure
;; Default renderer in seon.health.workout.render
(defn workout-set ...)  ; updated-at T1

;; Agent debug override -- same input spec, newer timestamp
(defn debug-workout-view ...)  ; updated-at T2 > T1, wins
;; Delete the function -> scanner removes it -> workout-set wins again

```

**Cleanup:** Agent-defined temporary functions should be tracked by session. When an agent session ends, the orchestrator can optionally clean up functions defined during that session (by checking `:seon.fn/updated-at` range and namespace ownership). This is not required for correctness -- stale overrides just add candidates that may or may not win.

## Scanner Design

### Extending Existing Infrastructure

The scanner extends `seon.graph.analyzer` and `seon.graph.ingest` rather than building from scratch. The existing code already handles clj-kondo analysis and Datalevin ingestion with retract-then-insert semantics.

**Migration:** Rename `:graph/*` entity keys to `:seon.fn/*`, `:seon.spec/*`, `:seon.ns/*`, `:seon.call/*`. This is a one-time migration since the graph is not yet in production use.

### Data Sources

All data lives in **Datalevin**. How it gets there (source file parsing, Super REPL eval, etc.) is an implementation detail of the ingestion pipeline. No consumer ever reads runtime state directly — they always query Datalevin.

| What's Stored | Source | Method |
|---------------|--------|--------|
| Namespace entities (name, doc, file, target, requires) | clj-kondo namespace-definitions + file extension | `seon.graph.analyzer` (existing) |
| Function entities (name, arglists, doc, line, specs) | clj-kondo var-definitions + `:malli/schema` metadata | `seon.graph.analyzer` (existing) + static metadata parsing |
| Spec entities (key, definition, base-type, contains-keys) | Static parsing of `schema/register!` calls from source | edamame parse of source → extract keyword + schema form |
| Call graph (from-fn → to-fn) | clj-kondo var-usages | `seon.graph.analyzer` (existing) |
| `:seon.ns/target` (`:clj`, `:cljs`, `:cljc`) | File extension | Trivial — derive from file path |

### Ingestion (How Data Gets Into Datalevin)

Multiple paths feed the code index. Consumers don't care which path was used — they always query Datalevin.

| Path | When | Scope |
|------|------|-------|
| Startup scan | System boot | Full project — all source files on disk |
| Dev hook | After file edit (PostToolUse) | Changed files only |
| Super REPL eval | Agent writes code in isolated JVM | Single form |
| Git merge / graduation | Code moves from branch/REPL to disk | Changed namespaces |

**Debounce:** Scanner debounces at 100ms to batch rapid edits. All paths converge on the same `ingest!` functions — the code index doesn't know or care where the analysis came from.

### Spec Key Extraction (Static — From Source)

For `schema/register!` calls parsed from source files, extract contained keys from the schema definition form (which is just data — a vector):

```clojure
(defn extract-contains-keys
  "Extract all top-level keys from a schema definition form (parsed as data).
   Works on the raw EDN form, NOT the Malli runtime registry."
  [schema-form]
  (when (and (vector? schema-form) (= :map (first schema-form)))
    (->> (rest schema-form)
         (filter vector?)
         (map first)
         (filter keyword?)
         vec)))

;; Example: parsing (schema/register! ::workout [:map [::exercise ...] [::sets ...]])
;; schema-form = [:map [::exercise ::exercise] [::sets ::sets]]
;; => [::exercise ::sets]

```

### Function Schema Extraction (Static — From Source)

For functions with `:malli/schema` metadata, extract input and output spec references from the parsed source form:

```clojure
(defn extract-fn-specs
  "Extract input/output spec keywords from :malli/schema metadata form.
   Parses the raw EDN form from source, NOT runtime var metadata."
  [schema-form]
  (when (and (vector? schema-form) (= :=> (first schema-form)))
    (let [[_ [_ input-spec] output-spec] schema-form]
      {:input-spec (when (keyword? input-spec) input-spec)
       :output-spec (when (keyword? output-spec) output-spec)})))

```

## Render Agent Pattern

### Convention: `.render` Companion Namespaces

Domain namespaces define specs and business logic. Companion `.render` namespaces define render functions that reference domain specs as input. This is convention, not requirement.

```
seon.health.workout          -- domain: specs, business logic
seon.health.workout.render   -- rendering: HTML, AI output

```

Benefits:

- Domain namespaces stay free of UI dependencies (Hiccup, Datastar)
- Render namespaces can be worked on by a separate "render agent"
- Clear separation of concerns

### Render Function Shape

Render functions follow standard map-in/map-out convention:

```clojure
;; Takes domain spec as input, returns render output
(defn workout-set
  "Renders a workout set for both HTML and AI."
  {:malli/schema [:=> [:cat :seon.health.workout/log-workout-request]
                      ::workout-html-and-ai]}
  [{:seon.health.workout/keys [exercise sets reps weight]}]
  {:seon.render/html [:tr [:td exercise] [:td (str sets)] ...]
   :seon.render/ai   (str exercise " -- " sets "x" reps ...)})

```

Combined render functions (returning both `:seon.render/html` and `:seon.render/ai`) are preferred over separate functions, reducing the number of entities and simplifying resolution.

### Fallback

When no render function matches, fall back to `pprint-clipped` -- a truncated pretty-print. This ensures everything is renderable even without custom renderers.

## Agent Context Building

### Default AI View

The code index enables compact function signatures for agent context:

```
seon.health.workout/log-workout!
  Records a workout set to the database.
  Input:  [:map [::exercise [:string {:min 1}]] [::sets pos-int?] ...]
  Output: [:map [::id uuid?] [::created-at inst?]]

```

This is generated from `:seon.fn/doc`, `:seon.fn/input-spec`, `:seon.fn/output-spec` -- all in Datalevin. No source code needed for basic context.

### Cross-Namespace Discovery

With ref-based schema, spec navigation is a direct pull -- no string-based joins needed:

```clojure
;; "What functions accept :seon.health.workout/exercise?"
(d/q '[:find ?qn ?doc
       :in $ ?target-key
       :where
       [?s :seon.spec/contains-keys ?target-key]
       [?fn :seon.fn/input-spec ?s]               ; ref navigation, no join on keyword
       [?fn :seon.fn/qualified-name ?qn]
       [?fn :seon.fn/doc ?doc]]
     @conn :seon.health.workout/exercise)

;; Recursive pull: get a function with its full spec + call graph in one query
(d/pull @conn
  [:seon.fn/qualified-name :seon.fn/doc
   {:seon.fn/input-spec [:seon.spec/key :seon.spec/definition :seon.spec/contains-keys]}
   {:seon.fn/output-spec [:seon.spec/key :seon.spec/definition]}]
  [:seon.fn/qualified-name "seon.health.workout/log-workout!"])

```

## Context Building: Topological Ordering

The ref-based schema enables recursive pull to extract a complete dependency subgraph in one query. The topological sort then linearizes it into dependency order for AI consumption.

### How It Works

1. **Recursive pull** -- Starting from a target function, pull refs transitively: the function's input/output specs, all functions it calls (via call graph), their specs, and so on to a configurable depth.

2. **Topological sort** -- Reuse the DFS pattern from `seon.flow.topology` to order the subgraph: specs with no dependencies first, then leaf functions (no outgoing calls), then composed functions, then the target. Cycle detection (already proven in the flow harness) handles circular dependencies gracefully.

3. **Render to text** -- Each entity in topological order is rendered via its `:seon.render/ai` renderer (or the default signature format). The result is a single linear context string.

### Example Output

For a target function `seon.trading.signals/generate-signal!`:

```
;; === Specs ===

:seon.trading/price-bar
  [:map [::open pos-number?] [::high pos-number?] [::low pos-number?] [::close pos-number?] [::volume nat-int?]]

:seon.trading/signal
  [:map [::type [:enum :long :short :flat]] [::confidence [:double {:min 0 :max 1}]]]

;; === Functions (leaf → composed) ===

seon.trading.indicators/sma
  Simple moving average over price bars.
  Input: [:map [::bars [:vector :seon.trading/price-bar]] [::period pos-int?]]
  Output: :double

seon.trading.indicators/rsi
  Relative strength index.
  Input: [:map [::bars [:vector :seon.trading/price-bar]] [::period pos-int?]]
  Output: :double

seon.trading.signals/generate-signal!   ← TARGET
  Produces a trading signal from recent price data.
  Input: [:map [::bars [:vector :seon.trading/price-bar]] [::strategy keyword?]]
  Output: :seon.trading/signal
  Calls: seon.trading.indicators/sma, seon.trading.indicators/rsi

```

Specs appear first because everything depends on them. Leaf functions (`sma`, `rsi`) appear before the target that calls them. An agent reading top-to-bottom encounters each concept before it is used.

## Agent Context Cockpit

**Key insight: the context cockpit IS the render pipeline applied to the `:seon.render/ai` output format.** HTML goes to browsers, AI context goes to agents. Same resolution, same override mechanism.

### How It Works

1. **Agent starts** -- The orchestrator pulls the topological subgraph for the agent's target namespace(s), resolves `:seon.render/ai` renderers for each entity, and concatenates the results as the agent's initial context.

2. **Context levels are just render functions.** A detailed dev view, a compact review view, and a high-level onboarding view are different render functions with different input specs. Resolution picks the best match as usual -- no configuration flags, no context-level enums.

3. **Agents can reshape their own context at runtime.** An agent defines a more specific render function in their JVM (e.g., one that omits verbose spec definitions and focuses on call relationships). The scanner picks it up. Newest wins. The next context refresh uses the agent's preferred view.

4. **Session ends, context resets.** When the agent session ends, its custom render functions are gone. The scanner removes them. The default renderers win again. No cleanup configuration needed.

### Why This Matters

This is a core Seon capability: agents don't just receive a fixed context dump -- they start with a curated cockpit and can reshape it as they work. An agent debugging a performance issue might define a render function that highlights call counts and latencies. An agent doing a security review might define one that emphasizes input validation and external calls. The system adapts because render functions ARE the context, and agents can write render functions.

No special configuration system. Agents write functions, the system discovers them.

## Phases

### Phase 1: Data Model + Scanner — ~95% COMPLETE

**What's done:**

- [x] Datalevin schema with all entity types (`seon.fn/*`, `seon.spec/*`, `seon.ns/*`, `seon.call/*`) — `src/seon/graph/ingest.clj` lines 39-78
- [x] Migrated from `:graph/*` to `:seon.fn/*` etc.
- [x] `:seon.ns/target` on namespace entities (`:clj`, `:cljs`, `:cljc`)
- [x] Static spec extraction via edamame — `src/seon/graph/scanner.clj` parses `schema/register!` calls, extracts `:seon.spec/contains-keys`
- [x] Pre-compute `:seon.fn/render-input-keys` for render functions — `scanner.clj:link-fns-to-specs`
- [x] Ingestion pipeline wired: startup scan + dev hook + Super REPL all feed same `ingest-analysis!` / `ingest-incremental!`
- [x] `:seon.fn/input-spec` and `:seon.fn/output-spec` as `:db.type/ref` to spec entities
- [x] Stub entities for external functions (ensures refs resolve)
- [x] Ingestion order enforced: specs → fns → calls

**What's missing:**

- [ ] Static `:malli/schema` metadata extraction from source (edamame parse of `(defn ^{:malli/schema [...]} ...)` forms). Currently `link-fns-to-specs` uses naming convention (`fn-name-request`/`fn-name-response`) as the only matching strategy. This works but doesn't scale to functions that don't follow the convention.

**Key files:** `src/seon/graph/analyzer.clj`, `src/seon/graph/scanner.clj`, `src/seon/graph/ingest.clj`, `src/seon/graph/query.clj`

### Phase 2: Renderer Resolution — ~50% COMPLETE

**What's done:**

- [x] `find-renderer` algorithm implemented — `src/seon/render.clj` lines 244-293
- [x] Resolution order correct: specificity (key-count DESC), recency (updated-at DESC), name (ASC)
- [x] Queries Datalevin for functions with `:seon.fn/render-input-keys`

**What's missing:**

- [ ] Resolution cache keyed by `[format (set (keys data))]` with scanner-triggered invalidation
- [ ] Wire `find-renderer` into SSE rendering pipeline — currently NOT called by any rendering code
- [ ] Remove old manual registry (`*renderers` atom, `register-renderer!`, `get-renderer`, `clear-renderers!`) — superseded by Datalevin discovery
- [ ] `pprint-clipped` fallback (current fallback is `[:pre [:code (pr-str value)]]`)
- [ ] **No render functions exist** in the codebase — nobody has written a function whose output spec contains `:seon.render/html` or `:seon.render/ai`. The pipeline has nothing to discover.

### Phase 3: Topological Context Builder — ~80% COMPLETE

**What's done:**

- [x] Recursive pull from seed function — `src/seon/graph/context.clj:pull-subgraph`
- [x] Topological sort via Kahn's algorithm — `src/seon/graph/context.clj:toposort`
- [x] Cycle detection (appends remaining if cycle exists)
- [x] Entity rendering to compact text (`render-fn-entity`, `render-ns-entity`, `render-spec-entity`)
- [x] Public API: `build` (from seed fn) and `build-for-namespace` (all fns in ns)

**What's missing:**

- [ ] Wire into agent launch — `seon.graph.context/build-for-namespace` should be called during agent startup to provide topological context
- [ ] Use `:seon.render/ai` resolution for entity rendering (currently uses hardcoded text format)

### Phase 4: Agent Context Cockpit — NOT STARTED

- [ ] Context cockpit = render pipeline with `:seon.render/ai` output format
- [ ] Different context levels as different render functions (dev detail, review compact, onboarding)
- [ ] Agent runtime override: agent defines more specific render fn, scanner picks it up, next refresh uses it
- [ ] Session cleanup: agent session ends, custom renderers gone, defaults win

### Phase 5: Render Agent Pattern — NOT STARTED

- [ ] Create first `.render` companion namespace with real render functions
- [ ] Document the convention
- [ ] Verify scanner picks up render functions automatically
- [ ] Verify `find-renderer` discovers them end-to-end

## Related PRDs

- **Datalevin Migration** (`docs/prds/datalevin-migration/prd.md`) -- master DB infrastructure, connection pooling
- **Super REPL** (`docs/prds/super-repl/prd.md`) -- flow harness, agent JVMs return data, orchestrator resolves renderer
- **Namespace UI** (`docs/prds/namespace-ui/prd.md`) -- UI rendering consumes spec-driven resolution

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Datalevin is the single source of truth** | All code metadata queries go to Datalevin. How data gets in (scan, REPL, merge) is plumbing. No runtime registry reads. |
| Strings not symbols for qualified names | Consistent serialization, Datalevin compatibility |
| Pre-computed render-input-keys | Avoids N+1 queries during resolution |
| No source code storage by default | Large, changes often, available via `source-fn` |
| Extend `seon.graph.*` not new scanner | Avoid duplication, reuse clj-kondo + Datalevin infra |
| Combined render fns preferred | Fewer entities, one function handles both HTML + AI |
| `.render` namespaces as convention | Separation of concerns without enforcement |
| Deterministic tiebreaker (alpha name) | Prevents non-determinism when key-count and timestamp tie |
| Cache resolution results | SSE polling renders same shapes repeatedly |
| No special override mechanism | Turtles all the way down -- specificity + recency |
| `:db.type/ref` for all cross-entity relationships | Enables recursive pull, eliminates string-based joins |
| Lookup refs in transactions | `[:seon.fn/qualified-name "foo/bar"]` -- upsert-friendly, no entity ID management |
| Stub entities for external functions | Ensures refs always resolve; minimal data (name + namespace only) |
| Ingestion order: specs -> fns -> calls | Guarantees ref targets exist before referencing |
| Topological sort reuses `seon.flow.topology` | Proven DFS + cycle detection, no new algorithm needed |
| Context cockpit = render pipeline | No separate config system; agents write functions, system discovers them |

## Open Questions

1. **Scanner debounce timing** -- 100ms feels right for dev hook, needs validation
2. **Render agent auto-spawning** -- On first browser view? On user request? Manual for now.
3. **Agent cleanup** -- Track temporary functions by session? Or let them accumulate?
4. **Resolution cache TTL** -- Invalidate on scan, but also TTL as safety net?
