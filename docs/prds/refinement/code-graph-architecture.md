# Code Graph Architecture

## Problem

We need a complete, linked code graph that works on two input types:
1. **Files on disk** -- startup, after edits
2. **In-memory forms** -- agent pipeline stages before persistence

The graph must capture: namespaces, functions (with arglists/docs), vars, specs (actual schema data), and all relationships between them.

## REPL Test Results (2026-02-23)

All tests run against `seon.health.workout.render` -- a real namespace that references specs from `seon.health.workout`, calls functions from `seon.web.components`, and registers Malli schemas.

### Test 1: clj-kondo on a single file, NO cache

```clojure
(with-in-str source
  (clj-kondo.core/run! {:lint ["-"]
                         :filename "src/seon/health/workout/render.clj"
                         :config {:output {:analysis {:var-definitions {:meta true}
                                                      :var-usages true
                                                      :namespace-usages true
                                                      :keywords true}
                                           :format :edn}}
                         :cache false}))
```

**Results:**
- **var-definitions** (2 found): Includes `:arglist-strs`, `:doc`, `:row`, `:end-row`, `:fixed-arities`, `:defined-by`. Example: `{:name page-render, :arglist-strs ["[{workout-ctx ::workout/*ctx*}]"], :doc "Page renderer for ..."}`. Complete function signatures with no runtime needed.
- **var-usages** (22 total, 9 cross-ns): Fully resolves aliased calls. `schema/register!` becomes `{:name register!, :to seon.schema}`. `ui/section-header` becomes `{:name section-header, :to seon.web.components}`. Self-calls (`workout-set` from `page-render`) also captured.
- **namespace-usages** (4): All requires with aliases. `{:alias workout, :to seon.health.workout}`, `{:alias ui, :to seon.web.components}`, etc.
- **keywords** (critical finding): `::workout/*ctx*` resolves to `{:ns seon.health.workout, :name "*ctx*", :auto-resolved true, :alias workout}`. ALL auto-namespaced keywords are fully resolved, including aliased ones.

**Conclusion: clj-kondo WITHOUT cache provides full cross-namespace resolution for a single file.** The earlier doc was wrong about this -- cache is NOT needed for alias resolution. clj-kondo reads the `ns` form and resolves everything internally.

### Test 2: clj-kondo WITH cache

Same 22 var-usages, same cross-ns resolution. Cache adds lint findings (arity errors, undefined vars) but does NOT improve the analysis output. Cache is useful for validation, not for graph extraction.

### Test 3: In-memory forms (no file on disk)

```clojure
(with-in-str "(ns seon.test.example
  (:require [seon.health.workout :as workout]))
(defn my-fn [{::keys [foo]}] (workout/some-call foo))"
  (clj-kondo.core/run! {:lint ["-"] :filename "<agent-forms>" ...}))
```

**Results:** Cross-ns resolution works perfectly. `workout/some-call` resolves to `{:to seon.health.workout}`. Keywords resolve too. Filename `<agent-forms>` is fine -- kondo doesn't need the file to exist.

### Test 4: Runtime introspection

```clojure
(meta #'seon.health.workout.render/page-render)
```

**Results:** `:malli/schema` is present: `[:=> [:cat :seon.health.workout.render/page-render-request] :seon.health.workout.render/page-render-response]`. Also `:arglists`, `:doc`, `:line`, `:file`. `ns-aliases` gives the alias map.

**Unique value:** The `:malli/schema` metadata. This is the only way to get compiled schema references (not string representations). Everything else (arglists, docs, line) is also available from clj-kondo.

### Test 5: edamame scanner

```clojure
(scanner/scan-file {:seon.graph.scanner/file-path "src/seon/health/workout/render.clj"})
```

**Results:** 4 specs, 2 fns. Specs include `:seon.spec/definition` (the actual schema form as string: `"[:map [:seon.health.workout/exercise :string] ...]"`), `:seon.spec/base-type`, `:seon.spec/contains-keys`.

**Unique value:** The actual schema data. clj-kondo knows `register!` was called at row 14 with 2 args, but cannot tell you the schema was `[:map [:seon.health.workout/exercise :string]]`. edamame gives you the literal data structure.

### Test 6: Keyword row-range correlation

clj-kondo keywords within a `register!` call's row range DO give us the resolved keywords used in that schema. But this only gives keywords, not schema structure (`:map`, `:vector`, property maps, validators). edamame remains necessary for full schema extraction.

## What Each Tool Provides (Revised)

### edamame (scanner.clj)

**Unique capability:** Extracting actual schema data from `schema/register!` calls. The literal `[:map [:seon.foo/bar :string]]` vector. No other tool provides this.

**Also provides:** `def` value types, two-pass keyword resolution. But these overlap with clj-kondo.

**Does NOT provide:** arglists, docstrings, call graph, error detection.

### clj-kondo

**Provides everything except schema data:**
- Function signatures: arglists, docs, row numbers, private flags, arity sets
- Call graph: var-usages with full cross-ns resolution (even without cache)
- Namespace dependencies: aliases, requires
- Keyword resolution: `::workout/*ctx*` -> `{:ns seon.health.workout, :name "*ctx*"}`
- Error detection: undefined vars, wrong arity (with cache)
- Works on in-memory strings with `with-in-str` and arbitrary filenames

**Does NOT provide:** The actual values passed to function calls (schema forms).

### Runtime introspection

**Unique capability:** Compiled Malli schema references via `(meta #'fn)` -> `:malli/schema`.

**Everything else** (arglists, docs, line numbers) is also available from clj-kondo static analysis.

**Limitation:** Requires loadable code with all dependencies. Side effects execute.

## Revised Architecture

### The Minimum Viable Split

| Concern | Tool | Why |
|---------|------|-----|
| Function entities (arglists, docs, row, private) | clj-kondo | Handles all defn variants, destructuring, multi-arity |
| Call graph (fn A calls fn B) | clj-kondo | Only tool that provides this |
| Namespace dependencies + aliases | clj-kondo | Full resolution even without cache |
| Cross-namespace keyword resolution | clj-kondo | `::workout/*ctx*` -> `:seon.health.workout/*ctx*` |
| Schema definitions (actual form data) | edamame | Only tool that extracts the schema vector itself |
| Def value types | edamame | Inspects the value form directly |
| Compiled Malli schemas | Runtime | `(meta #'fn)` -> `:malli/schema` after eval |

### Key Insight: clj-kondo Is Nearly Sufficient Alone

The original architecture assumed clj-kondo needed cache for cross-ns resolution. **This is wrong.** clj-kondo reads the `ns` form and resolves all aliases internally. For a single file or in-memory string, you get:
- All function definitions with full metadata
- All cross-namespace calls fully resolved
- All keywords fully resolved (including `::alias/name` forms)
- Namespace dependency graph

edamame is only needed for ONE thing: extracting the literal schema data from `schema/register!` arguments. If we ever move to a model where schemas are registered at runtime (not via source-level `register!` calls), edamame becomes unnecessary entirely.

### Extraction Pipeline

```
Input: source string (file or in-memory forms)
  |
  +--[parallel]--+
  |               |
  | edamame       | clj-kondo
  | (scanner)     | (analyzer)
  |               |
  | schema/       | var-definitions -> fn entities
  | register!     |   (arglists, docs, row, private)
  | calls ->      |
  | spec entities | var-usages -> call edges
  | (actual       |   (from-fn, to-fn, fully resolved)
  | schema data)  |
  |               | namespace-usages -> ns deps
  | def forms ->  |   (from, to, alias)
  | var entities  |
  | (value types) | keywords -> cross-ns refs
  |               |   (resolved ns + name for all :: keywords)
  +-------+-------+
          |
      merge + link
          |
          +-> fn entities from kondo (authoritative for signatures)
          +-> spec entities from edamame (authoritative for schema data)
          +-> var entities from edamame (authoritative for value types)
          +-> call edges from kondo
          +-> ns deps from kondo
          +-> fn<->spec links via naming convention
          +-> spec<->spec refs via keyword walking
          +-> cross-ns keyword links via kondo keywords analysis
```

### Merge Rules

When both tools produce data for the same entity:
1. **Functions:** clj-kondo is authoritative. edamame's `extract-defn` is a subset of what kondo provides. Use kondo's `var-definitions`.
2. **Specs:** edamame is authoritative. kondo knows `register!` was called but not with what data.
3. **Vars (defs):** edamame is authoritative for value types. kondo provides row/col but not value inspection.
4. **Namespaces:** kondo is authoritative. It captures docs, aliases, full require structure.

### Cross-Namespace Linking (Solved)

The original doc had an open question: "Can we link `::workout/*ctx*` back to the spec in `seon.health.workout`?"

**Yes.** Three independent paths, all proven in REPL:

1. **clj-kondo keywords analysis:** `::workout/*ctx*` -> `{:ns seon.health.workout, :name "*ctx*"}`. Direct resolution, no cache needed.
2. **edamame two-pass parse:** Resolves `::workout/*ctx*` to `:seon.health.workout/*ctx*` in the schema form data. Already working in scanner.clj.
3. **Datalevin query:** Look up spec entity with `:seon.spec/key :seon.health.workout/*ctx*`. Works after any prior ingest of that namespace.

All three agree. For the extraction pipeline, we use (1) for the keyword link graph and (2) for the schema data. (3) is for runtime queries by agents.

### Forms-First API

Both tools work on strings. The API accepts source strings:

```clojure
;; From a file
(extract-graph {::source (slurp "src/seon/foo.clj")})

;; From agent-produced forms
(extract-graph {::source (forms->source forms)})
```

clj-kondo accepts any `:filename` value -- it doesn't need the file to exist. Use `"<agent-forms>"` or the intended file path for better error messages.

### Runtime Enrichment (Optional)

After eval in an agent's nREPL:

```clojure
(enrich-from-runtime {::graph static-graph
                      ::namespace 'seon.health.workout.render})
```

Adds:
- `:seon.fn/malli-schema` from `(meta #'fn)` -> `:malli/schema`
- `:seon.fn/loadable? true`
- Override arglists/docs if runtime disagrees with static (rare)

This is enrichment, not required. The static graph from clj-kondo + edamame is sufficient for all graph operations.

## API Surface

### Core extraction

```clojure
(ns seon.graph.extract
  (:require [seon.graph.scanner :as scanner]
            [clj-kondo.core :as clj-kondo]))

(defn extract-graph
  "Extract a complete code graph from source code.
   Runs both edamame (for schemas/defs) and clj-kondo (for fns/calls/deps).

   Request keys:
     ::source    - Clojure source string (required)
     ::file-path - Optional file path for clj-kondo context

   Response keys:
     ::namespaces - namespace entities
     ::functions  - function entities (with spec links)
     ::specs      - spec entities (with schema data and cross-refs)
     ::vars       - var entities
     ::call-edges - call graph edges
     ::ns-deps    - namespace dependency edges
     ::keywords   - resolved keyword usages (cross-ns links)
     ::errors     - any clj-kondo findings"
  [{::keys [source file-path]}]
  ...)
```

### Unchanged: ingest layer

`seon.graph.ingest` stays as-is. It receives entity maps and transacts them.

## Migration Path

### What changes

1. **scanner.clj** -- stays, internal to `extract.clj`. Schema extraction is correct.
2. **New `extract.clj`** -- runs both tools on same input, merges, links.
3. **link-fns-to-specs** -- moves from scanner.clj to extract.clj (linking concern).
4. **clj-kondo config** -- add `:keywords true` to analysis config. Currently missing.

### What stays the same

- Datalevin schema in ingest.clj
- Upsert + retract-stale pattern
- Entity key namespaces (`:seon.fn/*`, `:seon.spec/*`, `:seon.var/*`)

### New Datalevin schema additions

```clojure
:seon.spec/references {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
:seon.fn/loadable?    {:db/valueType :db.type/boolean}
:seon.fn/malli-schema {:db/valueType :db.type/string}  ;; pr-str of compiled schema
```

## Open Questions (Revised)

1. ~~Cross-namespace resolution for stdin clj-kondo~~ **RESOLVED.** Cache is not needed. clj-kondo resolves all aliases from the ns form alone.

2. **Should we store raw forms in Datalevin?** Still no for now. Agents can read files.

3. **pr-str fidelity for agent forms.** Still a non-issue for agent-produced code. For files, always read raw string.

4. **Should extract.clj also run clj-kondo with cache for lint findings?** The cache adds arity checking and undefined-var detection. Worth running at validation time (stage 2) but not needed for graph extraction (stage 1). Keep them separate: `extract-graph` for the graph (no cache), `validate-source` for lint (with cache).
