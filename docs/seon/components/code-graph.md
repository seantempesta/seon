---
type: component
status: production
---
# Code Graph

> Self-introspection engine that indexes function definitions, call edges, namespace dependencies, and schema specs into Datalevin for runtime discovery.

## Purpose

The code graph is Seon's self-awareness layer. It statically analyzes the codebase, stores the results in Datalevin (via [[components/database]]), and exposes query APIs that other components use for discovery. The most important consumer is the [[components/renderer]] system, which uses `functions-with-output-key` to find render functions by their output spec keys — no registration needed, just write a function with the right `:malli/schema` metadata.

The graph also powers AI agent context building: given a seed function, `context.clj` walks the call graph, pulls related entities, topologically sorts them, and renders a compact text block for injection into agent prompts.

## Namespaces

| Namespace | File | Role |
|-----------|------|------|
| `seon.graph.analyzer` | `src/seon/graph/analyzer.clj` | clj-kondo wrapper for project/form analysis |
| `seon.graph.extract` | `src/seon/graph/extract.clj` | Unified pipeline: edamame + clj-kondo, merge, spec-to-fn linking |
| `seon.graph.scanner` | `src/seon/graph/scanner.clj` | Edamame-based `schema/register!` and `def` form extraction |
| `seon.graph.ingest` | `src/seon/graph/ingest.clj` | Transacts entities into Datalevin with upsert + retract-stale |
| `seon.graph.query` | `src/seon/graph/query.clj` | Datalog query API over the graph |
| `seon.graph.context` | `src/seon/graph/context.clj` | Topological context builder for AI agents |

## Public API Surface

### `seon.graph.analyzer`
- **`analyze-project!`** — Full clj-kondo run on `src/` directories. Returns `{::raw-analysis ...}`.
- **`analyze-form`** — Incremental clj-kondo on a source string.
- **`extract-entities`** — Transforms raw clj-kondo output into `{::namespaces ::functions ::var-usages ::namespace-usages}`.

### `seon.graph.extract`
- **`extract-graph`** — Single entry point that runs both edamame (schemas/defs) and clj-kondo (fns/calls/deps), merges results, enriches specs with cross-references, and links functions to their input/output specs.
- **`extract-graph-from-file`** — Convenience wrapper that slurps a file path.

### `seon.graph.scanner`
- **`scan-source`** / **`scan-file`** / **`scan-directory`** — Edamame-based extraction of `schema/register!` calls and `def` forms. Produces `:seon.spec/*` and `:seon.var/*` entities.
- **`scan-fn-schemas`** — Extracts `:malli/schema` metadata from `defn` forms. Returns `{qualified-name -> schema-form}`.

### `seon.graph.ingest`
- **`ingest-analysis!`** — Bulk ingest from analyzer output. Groups by namespace, delegates to `ingest-namespace!`.
- **`ingest-namespace!`** — Per-namespace upsert + retract-stale. The core transact function.
- **`ingest-incremental!`** — Single-namespace incremental ingest (backward compat wrapper).
- **`ingest-file!`** — Extract + ingest for one file (uses `extract-graph-from-file`).

### `seon.graph.query`
- **`dependents-of`** / **`dependencies-of`** — Namespace-level dependency queries.
- **`transitive-dependents-of`** — Full transitive closure walk (iterative BFS).
- **`call-graph`** — Outgoing call edges for a function.
- **`callers-of`** — Incoming call edges for a function.
- **`functions-in-ns`** — All functions defined in a namespace.
- **`search-functions`** — Substring search across all function names.
- **`functions-with-output-key`** — **Critical for renderer discovery.** Finds functions whose output spec contains a given key (e.g. `:seon.render/html`). Cached, invalidated on graph rescan.
- **`invalidate-output-key-cache!`** — Clears the output-key query cache.

### `seon.graph.context`
- **`build`** — Recursive subgraph pull + topological sort + render to text. Starts from a seed function.
- **`build-for-namespace`** — Context for all functions in a namespace.
- **`pull-subgraph`** — Raw subgraph pull (entities tagged with `:context/type`).
- **`toposort`** — Kahn's algorithm over the call graph within a subgraph.

## Dependencies

### Uses
- [[components/database]] — All storage and queries go through `seon.db` (`db/transact!`, `db/query`, `db/pull-by-name`)
- `seon.db.schema` — `db-schema/register-entity-schema!` and `db-schema/malli-map->datalevin-schema` for entity schema registration
- [[components/schema-system]] — `schema/register!` for attribute type registration
- [[components/renderer]] — `render/invalidate-render-cache!` called after every `ingest-namespace!`
- clj-kondo — Static analysis engine for functions, calls, namespace dependencies
- edamame — Clojure parser for `schema/register!` calls and `def` forms (clj-kondo doesn't see these)

### Used By
- [[components/renderer]] — `gq/functions-with-output-key` is how render functions are discovered at runtime
- [[components/context]] — AI agents get code context via `context/build`
- Integrant scanner — Background scanning triggers `ingest-file!` on file changes
- [[components/dev-tools]] — May trigger incremental ingest after edits

## How Data Flows

### Pipeline: Source Code to Queryable Graph

```
Source files (.clj)
    |
    +---> edamame (scanner.clj)
    |    +-> schema/register! calls -> :seon.spec/* entities
    |    +-> def forms -> :seon.var/* entities
    |    +-> defn :malli/schema metadata -> fn-to-spec links
    |
    +---> clj-kondo (analyzer.clj / extract.clj)
    |    +-> var-definitions -> :seon.fn/* entities
    |    +-> var-usages -> :seon.call/* edges
    |    +-> namespace-definitions -> :seon.ns/* entities
    |    +-> namespace-usages -> :seon.ns.dep/* edges
    |
    +---> extract.clj (merge)
         +- Merge edamame vars + kondo vars (edamame wins on conflict)
         +- Enrich specs with cross-references (walk definitions for keyword refs)
         +- Link functions to specs:
         |   Primary: parse :malli/schema -> extract input/output spec keywords
         |   Fallback: naming convention (fn-name-request / fn-name-response)
         +-> ingest.clj
              +- Per-namespace upsert (identity attrs handle create-or-update)
              +- Retract stale (old entities not in new scan)
              +- Call edges + ns-deps: retract-then-insert (no identity attrs)
              +- Stub entities for external call targets
              +-> Datalevin (:seon.runtime database)
```

### Renderer Discovery Flow

```
render/resolve-renderer
    |
    v
gq/functions-with-output-key {::output-key :seon.render/html}
    |
    v (Datalog: fn -> output-spec ref -> spec contains-keys)
    |
    v Pull input-spec -> compute required-keys vs optional-keys
    |
    v Return [{:seon.fn/qualified-name "seon.foo/render-bar"
               :required-keys #{:seon.foo/x :seon.foo/y}
               :optional-keys #{:seon.foo/z}} ...]
```

The renderer then picks the best match by specificity: most required keys matched, with namespace proximity as tiebreaker.

## Entity Model

Six entity types, all stored in the `:seon.runtime` database:

| Entity | Identity Attr | Key Attributes |
|--------|--------------|----------------|
| Namespace | `:seon.ns/name` | `file`, `doc`, `target`, `dynamic?` |
| Function | `:seon.fn/qualified-name` | `namespace`, `name`, `doc`, `arglists`, `row`, `private`, `input-spec` (ref), `output-spec` (ref) |
| Var | `:seon.var/qualified-name` | `namespace`, `name`, `doc`, `row`, `private`, `value-type` |
| Call Edge | (none — retract/insert) | `from-fn` (ref), `to-fn` (ref), `row` |
| NS Dependency | (none — retract/insert) | `from-ns`, `to-ns`, `alias` |
| Spec | `:seon.spec/key` | `namespace`, `definition`, `base-type`, `contains-keys` (vector), `optional-keys` (vector), `references` (vector) |

Function-to-spec links use Datalevin refs (`:seon.fn/input-spec` and `:seon.fn/output-spec` point at `:seon.spec/key` entities via lookup refs at transact time).

## Design Decisions

### Two-Parser Strategy
clj-kondo is authoritative for functions, calls, and namespace dependencies (it understands macros, aliases, and multi-arity). But it doesn't see `schema/register!` calls or `:malli/schema` metadata values. Edamame fills that gap by parsing source as data. `extract.clj` merges both outputs.

### Upsert + Retract-Stale Pattern
Entities with identity attrs (functions, specs, vars, namespaces) use Datalevin's upsert semantics — transacting an entity with the same identity key updates it. After upserting the new scan, `retract-stale-*` queries for entities in the namespace that weren't in the new scan and retracts them. This is safe for incomplete scans (only explicitly absent entities get removed).

Call edges and ns-deps lack identity attrs, so they use retract-then-insert: delete all existing edges from the namespace, then insert the new ones.

### Output-Key Cache
`functions-with-output-key` is called on every page render, so results are cached in an atom (`output-key-cache`). The cache is invalidated by `invalidate-output-key-cache!`, which is called from `ingest-namespace!` -> `render/invalidate-render-cache!` whenever the graph updates.

### Spec Cross-References
`extract.clj` walks each spec's definition string (parsed back to EDN) and collects all qualified keywords as `:seon.spec/references`. This enables queries like "find all specs that reference this spec."

### Stub Entities for External Calls
When function A calls function B but B hasn't been analyzed yet (external dep, or not yet scanned), `compute-stub-entities` creates a minimal `:seon.fn/*` entity for B so the call edge ref can resolve. The stub gets overwritten when B is properly ingested later.

### Batch Transacting
`transact-in-batches!` processes entities in batches of 500 with error isolation — a single bad entity in a batch fails only that batch, not the entire ingest.

## Refactoring Opportunities

1. **Dual analysis paths** — `analyzer.clj` has its own `extract-*` functions that duplicate work in `extract.clj`. The analyzer's `extract-entities` is used for the legacy bulk path (`ingest-analysis!`), while `extract.clj` is the newer unified path. These should converge so there's one extraction pipeline.

2. **Scanner `::specs` schema uses `inst?`** — Line 52 of `scanner.clj` has `:seon.spec/updated-at inst?` instead of `:inst`. This violates the project convention (`:inst` for all timestamps).

3. **`ingest-file!` uses dynamic require** — `(require 'seon.graph.extract)` and `(resolve 'seon.graph.extract/extract-graph-from-file)` at call time rather than a normal `:require`. This suggests a circular dependency concern that should be investigated.

4. **Context module re-queries call graph** — `context.clj` and `query.clj` both implement call-graph lookups. `context.clj` has its own private `calls-of` and `callers-of-fn` helpers rather than using `gq/call-graph` and `gq/callers-of`. These could be consolidated.

5. **Search is client-side filtering** — `search-functions` pulls ALL functions from Datalevin and filters in Clojure. For large codebases, this should use Datalevin's built-in search or at minimum a server-side filter.

6. **`::raw-analysis` is typed as `:map`** — The analyzer registers `::raw-analysis` as `[:map ...]` with no inner keys specified. This is effectively untyped — it carries the full clj-kondo analysis structure. Not easily fixable (clj-kondo's output shape is complex), but worth noting.
