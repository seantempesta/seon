---
type: prd
status: completed
tags: [prd, database]
---
# Phase 2 Design: Documentation Rendering

---

## Graph Statistics (Phase 1 Verification)

*Note: These queries should be run in the orchestrator session to verify Phase 1 success. Agent session cannot access Datalevin directly due to classpath isolation.*

**Recommended verification queries:**

```clojure
;; 1. Total functions in graph
(d/q '[:find (count ?e) :where [?e :seon.fn/qualified-name _]] @graph-conn)

;; 2. Functions with input-spec links (ALL functions with -request specs)
(d/q '[:find (count ?e) :where [?e :seon.fn/input-spec _]] @graph-conn)

;; 3. Functions with output-spec links (ALL functions with -response specs)
(d/q '[:find (count ?e) :where [?e :seon.fn/output-spec _]] @graph-conn)

;; 4. HTML renderers (functions with :seon.render/html in output spec)
(count (gq/functions-with-output-key {::gq/conn graph-conn ::gq/output-key :seon.render/html}))

;; 5. AI renderers (functions with :seon.render/ai in output spec)
(count (gq/functions-with-output-key {::gq/conn graph-conn ::gq/output-key :seon.render/ai}))

;; 6. Namespaces with most linked functions
(d/q '[:find ?ns (count ?e)
       :where
       [?e :seon.fn/input-spec _]
       [?e :seon.fn/namespace ?ns]]
     @graph-conn)
```

---

## Discovery: Context Building Already Exists

**Key finding:** `seon.graph.context` already implements much of what Phase 2's `context-for-agent` would need:

| Function | What It Does |
|----------|--------------|
| `build` | Builds linearized context from a seed function, follows call graph, includes specs |
| `build-for-namespace` | Builds context for all functions in a namespace |
| `toposort` | Topologically sorts entities (specs first, then leaves, then callers) |
| `render-entity` | Renders fn/ns/spec entities to compact text blocks |

**This means Phase 2's `context-for-agent` can simply wrap or extend `seon.graph.context`** rather than building from scratch.

---

## Proposed Architecture

### New Namespace: `seon.render.code`

A single file handling code/documentation rendering. Minimal scope.

```
src/seon/render/code.clj
```

### Function Signatures

#### 1. `compatible-functions`

Find functions that can consume the given data keys. This is the inverse of `functions-with-output-key` - instead of "find functions that produce X", it's "find functions that can consume my data".

```clojure
(defn compatible-functions
  "Find functions whose required input keys are a subset of available-keys.

   This discovers functions that CAN be called with the given data,
   regardless of what they produce.

   Request keys:
     ::conn           - Required. Datalevin connection
     ::available-keys - Required. Set of available data keys
     ::output-filter  - Optional. Only return fns whose output contains this key

   Returns:
     Vector of function entities with :required-keys, :optional-keys computed.

   Example:
     (compatible-functions {::conn conn
                            ::available-keys #{:seon.foo/x :seon.foo/y}})
     ;; => [{:seon.fn/qualified-name \"seon.foo/bar\"
     ;;      :required-keys #{:seon.foo/x}
     ;;      :optional-keys #{}} ...]"
  [{::keys [conn available-keys output-filter]}]
  ...)
```

**Implementation approach:**

1. Query all functions with input-spec links
2. For each, pull input-spec contains-keys and optional-keys
3. Compute required-keys = contains - optional
4. Filter: required-keys ⊆ available-keys
5. Optionally filter by output key (if output-filter provided)
6. Sort by specificity (most required keys first)

#### 2. `render-ns-docs`

Default documentation renderer for any namespace. Queries the graph for function info.

```clojure
(defn render-ns-docs
  "Render documentation for a namespace's public API.

   Uses the graph to discover functions, specs, and dependencies.
   No namespace cooperation required - works with any namespace that
   has functions with docstrings or schemas.

   Request keys:
     ::conn     - Required. Datalevin connection
     ::ns-name  - Required. Namespace name (string)
     ::detail   - Optional. :summary, :interface (default), or :deep-dive

   Returns:
     {:seon.render/documentation \"...text...\"
      :seon.render/html [:div ...hiccup...]}

   Example:
     (render-ns-docs {::conn conn ::ns-name \"seon.graph.query\"})
     ;; => {:seon.render/documentation \"## seon.graph.query\\n...\"}"
  [{::keys [conn ns-name detail] :or {detail :interface}}]
  ...)
```

**Implementation approach:**

1. Use `gq/functions-in-ns` to get all functions
2. For each function with input/output spec, pull spec data
3. Filter to public functions only (unless detail is :deep-dive)
4. Format based on detail level:
   - `:summary` - names only
   - `:interface` - names, arglists, input/output spec keys
   - `:deep-dive` - full docstrings, spec definitions, examples

#### 3. `resolve-docs`

Find the best documentation renderer for a namespace. Enables custom doc renderers.

```clojure
(defn resolve-docs
  "Find the best documentation renderer for a namespace.

   Looks for functions whose output spec contains :seon.render/documentation.
   If found and the function's required keys match the available data,
   uses that custom renderer. Otherwise falls back to render-ns-docs.

   Request keys:
     ::conn           - Required. Datalevin connection
     ::ns-name        - Required. Namespace name (string)
     ::available-keys - Optional. Data keys to match against custom renderers

   Returns:
     The resolved var, or nil (use default)."
  [{::keys [conn ns-name available-keys]}]
  ...)
```

**Implementation approach:**

1. Call `functions-with-output-key` with `:seon.render/documentation`
2. Filter candidates by namespace proximity (prefer same ns or .render child)
3. If available-keys provided, filter by required-keys subset
4. Return best match or nil

#### 4. `context-for-agent` (wrapper)

Thin wrapper around `seon.graph.context/build-for-namespace` with documentation integration.

```clojure
(defn context-for-agent
  "Build context for an AI agent working on a namespace.

   Combines:
   - Namespace documentation (via resolve-docs or render-ns-docs)
   - Function signatures with specs
   - Call graph (who calls what)
   - Dependency information

   Request keys:
     ::conn          - Required. Datalevin connection
     ::ns-name       - Required. Target namespace
     ::depth         - Optional. Call graph depth (default 2)
     ::max-entities  - Optional. Entity cap (default 50)

   Returns:
     {:seon.render/documentation \"...text...\"
      ::entity-count N}

   Example:
     (context-for-agent {::conn conn ::ns-name \"seon.health.workout\"})"
  [{::keys [conn ns-name depth max-entities]}]
  ...)
```

**Implementation approach:**

1. Try `resolve-docs` for custom documentation
2. If nil, use `render-ns-docs` with `:interface` detail
3. Call `seon.graph.context/build-for-namespace` for call graph context
4. Combine documentation + context text

---

## Open Question Recommendations

### Q1: `::ns` Schema vs Runtime `resolve`?

**Recommendation: Runtime `resolve` is simpler and sufficient.**

Rationale:

- Namespace docstrings already exist in the graph (`:seon.ns/doc`)
- Function schemas/docs already exist in the graph
- The unified pattern (output key = `:seon.render/documentation`) handles custom renderers
- No new machinery needed - just register a `-response` spec with the right output key

If a namespace wants custom documentation, it can define:

```clojure
(schema/register! ::ns-docs-request [:map [::*ctx* ::*ctx*]])
(schema/register! ::ns-docs-response [:map [:seon.render/documentation :string]])

(defn ns-docs
  {:malli/schema [:=> [:cat ::ns-docs-request] ::ns-docs-response]}
  [{ns-ctx ::*ctx*}]
  {:seon.render/documentation (custom-doc-format ns-ctx)})
```

The graph scanner will link this automatically, and `resolve-docs` will find it.

### Q2: Should `functions-with-output-key` cache results?

**Recommendation: Yes, add caching with rescan invalidation.**

The current `find-renderer` uses `resolution-cache` invalidated on rescan. `functions-with-output-key` should do the same:

```clojure
(defonce ^:private output-key-cache (atom {}))

(defn invalidate-output-key-cache! []
  (reset! output-key-cache {}))

(defn functions-with-output-key [{::keys [conn output-key]}]
  (let [cache-key output-key
        cached (get @output-key-cache cache-key ::miss)]
    (if (not= cached ::miss)
      cached
      (let [result (...existing implementation...)]
        (swap! output-key-cache assoc cache-key result)
        result))))
```

Call `invalidate-output-key-cache!` from `invalidate-render-cache!` so both are cleared on rescan.

### Q3: Default detail level for `render-ns-docs`?

**Recommendation: `:interface` (arglists + key types)**

Rationale:

- `:summary` is too sparse for most use cases
- `:deep-dive` may be too verbose, especially for large namespaces
- `:interface` gives enough to understand how to call functions without overwhelming

Example output at each level:

**`:summary`**

```
## seon.graph.query
- dependents-of
- dependencies-of
- call-graph
- functions-with-output-key
```

**`:interface`** (default)

```
## seon.graph.query

### dependents-of [{::conn ::ns-name}] → [ns-strings]
Find namespaces that depend on the given namespace.

### dependencies-of [{::conn ::ns-name}] → [ns-strings]
Find namespaces that the given namespace depends on.

### functions-with-output-key [{::conn ::output-key}] → [fn-maps]
Find functions whose output spec contains a specific key.
  Input: ::conn, ::output-key
  Output: :seon.fn/qualified-name, :required-keys, :optional-keys
```

**`:deep-dive`**

```
## seon.graph.query

### dependents-of
```clojure
(dependents-of {::conn conn ::ns-name "seon.ai.claude"})
;; => ["seon.ai.agent" "seon.web.agents" ...]
```

Find namespaces that depend on (require) the given namespace.
Returns a vector of namespace name strings that have a :seon.ns.dep/*
edge pointing to the target namespace.

Input spec (::dependents-of-request):
  [:map [::conn ::conn] [::ns-name ::ns-name]]

Output spec (::dependents-of-response):
  [:vector :string]
[...etc...]

```

---

## Test Coverage Plan

### Unit Tests (`test/seon/render/code_test.clj`)

| Test | What It Verifies |
|------|------------------|
| `compatible-functions-basic-test` | Finds functions with matching required keys |
| `compatible-functions-output-filter-test` | Filters by output key when specified |
| `compatible-functions-empty-test` | Returns empty when no matches |
| `render-ns-docs-summary-test` | Summary level renders function names only |
| `render-ns-docs-interface-test` | Interface level includes arglists and key types |
| `render-ns-docs-deep-dive-test` | Deep dive includes docstrings and examples |
| `render-ns-docs-no-functions-test` | Handles namespaces with no functions |
| `resolve-docs-custom-renderer-test` | Finds custom doc renderer when registered |
| `resolve-docs-fallback-test` | Returns nil when no custom renderer |
| `context-for-agent-integration-test` | Combines docs + call graph context |

### Integration Tests

Test with real namespaces from the codebase:
- `seon.graph.query` - has schemas, multiple functions
- `seon.health.workout` - has custom renderer
- `seon.render` - large namespace, good stress test

---

## Scope Estimate

| Item | Files | Complexity |
|------|-------|------------|
| `seon.render.code` | 1 new file | Medium - ~150-200 lines |
| Tests | 1 new file | Medium - ~100-150 lines |
| Cache integration | 1 edit to `query.clj` | Low - ~10 lines |

**Total: 2 new files, 1 small edit. Estimated ~300 lines.**

This is a single-agent task if scoped to just the core functions. The integration with `seon.ai.claude` (Phase 4) should be a separate task.

---

## Implementation Order

1. **Add caching to `functions-with-output-key`** in `query.clj`
2. **Create `seon.render.code`** with:
   - `compatible-functions`
   - `render-ns-docs` (start with `:interface` only)
   - `resolve-docs`
   - `context-for-agent`
3. **Create tests** for all functions
4. **Extend `render-ns-docs`** with `:summary` and `:deep-dive` detail levels
5. **Verify** with real namespace documentation

---

## Deferred to Later Phases

- **Phase 3**: Health check discovery (`:seon.health/status` output key)
- **Phase 4**: Wire `context-for-agent` into `seon.ai.claude/build-agent-prompt`
- **User-facing docs UI**: Web endpoint to browse namespace documentation
