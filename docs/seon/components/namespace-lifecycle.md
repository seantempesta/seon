---
type: component
status: stable
---
# Namespace Lifecycle

> Manages how dynamic namespaces come alive at runtime — creating ctx instances, injecting vars, resolving renderers, and persisting state across restarts.

## Purpose

Every namespace in Seon is browsable at `/ns/{namespace}`. Some namespaces are **dynamic** — they declare a `*ctx*` spec (detected by the scanner as `:seon.ns/dynamic? true`), which means they carry mutable state. This component manages the full lifecycle of those dynamic namespaces: creating validated ctx atoms, injecting dynamic vars (`*ctx*`, `*conn*`), discovering page renderers from the code graph, persisting state to Datalevin on shutdown, and restoring it on startup.

The routes layer (`seon.ns.routes`) is the HTTP-facing surface; this component is the machinery underneath.

## Namespaces

| Namespace | File | Role |
|-----------|------|------|
| `seon.ns.lifecycle` | `src/seon/ns/lifecycle.clj` | Instance creation, var injection, backup/restore |
| `seon.ns.introspect` | `src/seon/ns/introspect.clj` | Runtime discovery of functions, vars, atoms, multimethods, requires |
| `seon.ns.routes` | `src/seon/ns/routes.clj` | HTTP handlers: page rendering, SSE, function calls, content negotiation |
| `seon.ns.view` | `src/seon/ns/view.clj` | Multimethod-based view rendering dispatching on `[format view-type]` |

## Public API Surface

### lifecycle.clj — Core Lifecycle

| Function | Schema | Purpose |
|----------|--------|---------|
| `ensure-instance!` | `::ensure-instance-request -> ::ensure-instance-response` | Main entry point. Checks in-memory registry first, then Datalevin persistence, then creates fresh. Returns `{::instance-id, ::ctx-atom}` |
| `dynamic-namespace?` | `::dynamic-namespace-request -> ::dynamic-namespace-response` | Query Datalevin for `:seon.ns/dynamic?` flag |
| `ctx-spec-key` | `::ctx-spec-key-request -> ::ctx-spec-key-response` | Convert ns symbol to `*ctx*` spec keyword (`'seon.health.workout` -> `:seon.health.workout/*ctx*`) |
| `initial-value` | `::initial-value-request -> ::initial-value-response` | Get initial ctx: tries `ns/initial-state` fn, falls back to `mg/generate` from spec |
| `find-page-render-fn` | `::find-page-render-fn-request -> ::find-page-render-fn-response` | Find page renderer via graph (functions with `:seon.render/html` output whose required-keys include `*ctx*`) |
| `make-render-fn` | `::make-render-fn-request -> :any` | Wrap renderer: `(ctx-value) -> {ctx-key ctx-value} -> renderer -> :seon.render/html` |
| `inject-vars!` | `::inject-vars-request -> :boolean` | Intern `*ctx*` and `*conn*` dynamic vars into namespace |
| `backup-all-instances!` | `::backup-all-instances-request -> ::backup-all-instances-response` | Persist all ctx atoms to Datalevin (shutdown hook) |
| `restore-instances!` | `::restore-instances-request -> ::restore-instances-response` | Restore persisted instances on startup, validating against current specs |

### introspect.clj — Runtime Discovery

| Function | Purpose |
|----------|---------|
| `introspect` | Returns map of `:functions`, `:vars`, `:atoms`, `:multimethods`, `:requires` for any loaded namespace |
| `list-seon-namespaces` | All loaded `seon.*` namespaces, sorted |

### routes.clj — HTTP Surface

| Function | Purpose |
|----------|---------|
| `namespace-page` | Ring handler for `/ns/:namespace`. Dynamic ns: ensure-instance + redirect. Non-dynamic: introspection view |
| `namespace-sse` | SSE handler. Instance-based: push updates via ctx. Introspection: polling (2s) |
| `function-call-handler` | POST `/ns/:namespace/:function`. Form parsing, Malli coercion, auto-injection of `*ctx*`/`*conn*` |
| `function-get-handler` | GET version with query params |
| `after-ns-reload` | Clears SSE handler cache on hot reload |
| `route-patterns` | Data var consumed by `seon.web.routes` for dynamic route registration |

### view.clj — Value Rendering

| Function | Purpose |
|----------|---------|
| `render-value` | Map-in API: `{::value v ::format :html}` -> rendered output |
| `typed` | Attach `:seon/view` metadata to value for dispatch |
| `view-type` | Extract view type from value metadata |
| `detail-url` / `list-url` | Build `/ns/` URLs from view-type keywords |
| `render*` | Internal multimethod dispatching on `[format view-type]` |

## Dependencies

**Uses:**
- [[components/context]] — `seon.ctx` for atom creation, persistence, SSE client management, instance registry
- [[components/renderer]] — `seon.render` for `resolve-renderer`, `namespace-web-params`, `for-html`, `humanize`
- [[components/code-graph]] — `seon.graph.query/functions-with-output-key` for renderer discovery
- [[components/database]] — `seon.db` for Datalog queries (dynamic? flag, instance persistence)
- [[components/schema-system]] — `seon.schema` for registration and validation
- `seon.runtime` — ID generation, merged schema

**Used by:**
- `seon.web.routes` — sole consumer of `route-patterns` data var
- `seon.web.reactive.actions` — function resolution for Datastar actions
- `seon.web.reactive.transform` — hiccup transformation for Datastar attributes

## How Data Flows

### ensure-instance! (the main entry point)

```
1. Check in-memory registry (ctx/get-atom) for existing instance
   -> If found, return immediately (preserves live state)

2. Derive ctx-spec-key: 'seon.health.workout -> :seon.health.workout/*ctx*

3. Check Datalevin persistence (resolve-instance)
   -> If found, validate against current Malli spec
   -> If valid, use persisted data; if invalid, log warning and create fresh

4. Find page render function via code graph
   -> functions-with-output-key {:seon.render/html}
   -> Filter for required-keys containing *ctx*
   -> Wrap: (ctx-value) -> {ctx-key ctx-value} -> renderer -> :seon.render/html

5. Create ctx atom via ctx/create! with options:
   - ::persist? true (if db-name provided)
   - ::sse-push? true
   - ::track-clients? true
   - ::render-fn (if discovered)
   - ::ctx-schema (if spec registered)

6. Inject vars: intern *ctx* (atom) and *conn* (Datalevin conn) into namespace

7. Return {::instance-id, ::ctx-atom}
```

### HTTP Request Flow (namespace-page)

```
Request: GET /ns/seon.health.workout

1. Parse namespace from path, detect format (html/ai/raw)
2. Non-HTML fast path: render-for-format -> return immediately

3. Check if dynamic namespace (query Datalevin for :seon.ns/dynamic?)

4a. Dynamic, no ?instance param:
    -> ensure-instance! -> 302 redirect to ?instance=<id>

4b. Dynamic, with ?instance param:
    -> ensure-instance! (resume or create)
    -> reactive-instance-page: get ctx atom, get render-fn from registry,
      render ctx value, transform hiccup for Datastar, wrap in base-page

4c. Non-dynamic:
    -> Serve skeleton page with SSE connection
    -> SSE polls render-namespace-content every 2s
```

### SSE Split

Two SSE modes based on namespace type:

| Mode | Trigger | Mechanism |
|------|---------|-----------|
| **Push** | Dynamic ns with `?instance=` | `ctx/register-client!` -> ctx atom watch -> push on state change |
| **Poll** | Introspection view, non-dynamic | `sse/render-handler` with 2s poll interval, re-renders content |

### Function Call Flow (POST /ns/:ns/:fn)

```
1. Parse namespace + function from path
2. Special cases: create-instance!, destroy-instance!
3. Parse form body (URL-encoded or JSON signals)
4. Resolve function via seon.web.reactive.actions
5. Extract input schema from function's :malli/schema metadata
6. Coerce string values to correct types (Malli string-transformer)
7. Auto-inject *ctx* value and *conn* from namespace vars
8. Validate input against schema
9. Call function
```

### Var Injection

`inject-vars!` uses `intern` + `.setDynamic` to create proper dynamic vars:
- `*ctx*` — the ctx atom (deref to get state, swap! to update)
- `*conn*` — raw Datalevin connection (for namespace code that queries directly)

These vars are accessible from within the namespace's functions without explicit passing.

### Backup/Restore Cycle

```
Shutdown:
  backup-all-instances! -> iterate ctx/list-instances -> ctx/persist! each

Startup:
  restore-instances! -> query Datalevin for all persisted instances
    -> validate each against current Malli spec
    -> ensure-instance! for valid ones (creates atom, injects vars)
    -> skip invalid ones with warning
```

## Design Decisions

1. **In-memory first.** `ensure-instance!` checks the in-memory atom registry before Datalevin. This prevents duplicate atoms when the same instance is requested multiple times (e.g., SSE reconnect).

2. **Spec validation on restore.** Persisted state is validated against the current Malli spec. If the schema evolved since the state was saved, the old state is discarded rather than loaded with mismatched shape.

3. **Initial state hierarchy.** Namespace can define `initial-state` function (preferred) or the system generates from Malli spec via `mg/generate` (fallback). This gives domain namespaces control over defaults.

4. **Renderer discovery, not registration.** Page renderers are found by querying the code graph for functions with `:seon.render/html` in output and `*ctx*` in required input keys. No explicit registration step.

5. **Routes.clj is the sole HTTP surface.** All namespace HTTP handling lives in one file (~940 lines). While large, this keeps the routing logic cohesive. `seon.web.routes` consumes only the `route-patterns` data var.

6. **Content negotiation.** `?format=ai` or `Accept: text/plain` returns AI-optimized text. `?format=raw` or `Accept: application/edn` returns EDN. Default is HTML.

## Refactoring Opportunities

- **routes.clj is ~940 lines** — the introspection view HTML (~250 lines of hiccup) should move to `seon.ns.introspect.view`. Function dispatch machinery (`parse-form-body`, `coerce-with-schema`, `resolve-and-call`) could become `seon.ns.dispatch`.
- **`namespace-handlers` atom uses plain symbol keys** — should be namespaced keywords per project conventions.
- **`resolve-and-call` discards return value** in POST handler — only GET handler returns the function result to the client.
- **Multiple `:any` schemas in lifecycle.clj** — `::ctx-atom`, `::render-fn`, `::data` are all registered as `:any`, which violates the no-`:any` convention. These are difficult to type precisely (atoms, function vars, arbitrary maps) but could use more specific shapes.
- **`[:maybe ::render-fn]`** in `find-page-render-fn-response` — project convention prefers `{:optional true}` over `[:maybe ...]`.
- **view.clj and render.clj overlap** — both render values in multiple formats. view.clj uses multimethods (`render*`), render.clj uses Datalevin resolution. A unified dispatch path would reduce confusion about which to use when.
