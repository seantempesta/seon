---
type: research
status: active
tags: [research, schema, prd]
---

# Keyword Namespace Audit: Unified Namespace Flow

## Rule

From CLAUDE.md: "Keyword namespaces = real code namespaces." Use `::subject` freely -- it correctly expands to `:seon.email.message/subject` when you're in `seon.email.message`. Never invent keyword namespace prefixes that don't correspond to actual code namespaces.

From the user: "Try not to use `:seon/` keywords. It should be at least one level more focused and ideally match where the code that primarily processes the attributes lives."

---

## Existing `:seon/` Keywords in the Codebase

Before auditing the design doc proposals, note what already exists and needs attention:

| Keyword | Used In | Processing Code | Verdict |
|---------|---------|-----------------|---------|
| `:seon/id` | `seon.ai`, `seon.ai.claude`, `seon.ai.datalevin` | `seon.ai.datalevin` (persistence), `seon.ai` (entity schemas) | **Rename.** This is an entity identity attribute. Processed by `seon.db` layer. Should be `:seon.db/id` -- the DB layer owns identity. |
| `:seon/view` | `seon.ns.view`, `seon.ns.example`, `seon.ai.agent` | `seon.ns.view` (resolution, multimethod dispatch) | **Rename to `:seon.ns.view/type`** or `::type` inside `seon.ns.view`. The view module processes it. |
| `:seon/schema` | `seon.render`, `seon.ns.view` | `seon.render` (typed dispatch), `seon.ns.view` (schema lookup) | **Rename to `:seon.render/schema`**. The render module is the primary consumer of schema metadata on values. |
| `:seon/component` | `seon.config`, `seon.system` | `seon.system` (Integrant derive hierarchy) | **Acceptable exception.** Integrant derive keys are a framework convention. `:seon/component` is the derive root for all `ig/assert-key` dispatch. Renaming would fight the framework. |
| `:seon/runtime-db` | `seon.system` | `seon.system` (Integrant component key) | **Same exception.** Integrant component keys use the top-level project namespace by convention. |

---

## Design Doc: Proposed Keywords Audit

### 1. `:seon/to` -- send! targeting

**Proposed in:** `seon/send!` function

**Processing code:** The dispatch layer (`seon.dispatch` per the rename proposal) strips `:seon/to` from the data map, uses it to filter candidate functions.

**Recommendation:** `:seon.dispatch/to`

**Rationale:** The dispatch layer is the code that reads and processes this key. It strips it before passing data to subscribers. The keyword should live where the processing code lives.

### 2. `:seon/subscribe` -- var metadata for subscription opt-in

**Proposed in:** Var metadata on subscriber functions

**Processing code:** The dispatch layer reads this from var metadata to determine which functions are eligible for broadcast routing.

**Recommendation:** `:seon.dispatch/subscribe`

**Rationale:** Same as above -- the dispatch layer is the consumer. This is var metadata, not data flowing through the system, but the same principle applies: the namespace that reads and acts on it should own the keyword.

### 3. `:seon/queue`, `:seon/queue-size`, `:seon/batch-interval-ms` -- subscription queue config

**Proposed in:** Var metadata on subscriber functions

```clojure
{:seon/subscribe true
 :seon/queue :sliding
 :seon/queue-size 10
 :seon/batch-interval-ms 60000}

```

**Processing code:** The dispatch layer configures buffering behavior based on these values.

**Recommendation:** `:seon.dispatch/queue`, `:seon.dispatch/queue-size`, `:seon.dispatch/batch-interval-ms`

**Rationale:** These configure dispatch layer behavior. The dispatch layer is the sole reader. Keeping them all under `:seon.dispatch/` makes it obvious where to look when debugging queue behavior.

### 4. `:seon.conn/*` -- connection model keywords

**Proposed in:** Connection model schema

```clojure
:seon.conn/id, :seon.conn/origin, :seon.conn/namespace,
:seon.conn/instance-id, :seon.conn/buffer, :seon.conn/buffer-size

```

**Processing code:** There is no `seon.conn` namespace in the codebase. Connections are currently managed by `seon.web.sse` (browser SSE connections) and `seon.flow.harness.channel` (TCP connections).

**Recommendation:** Depends on where the unified connection model code lives.

**Option A:** If a new `seon.conn` namespace is created to own connection lifecycle (register, lookup, close), then `:seon.conn/*` is correct -- the keyword namespace matches the code namespace. This is the cleanest option.

**Option B:** If connections remain split across `seon.web.sse` and the process channel layer, don't invent `:seon.conn`. Instead, use `:seon.web.sse/id`, `:seon.web.sse/origin` etc. for browser connections, and `:seon.process.channel/id` etc. for TCP connections.

**Recommendation: Option A** -- create `seon.conn` as a thin unified connection registry. The design doc's connection model (browser, REPL, agent, timer sharing one shape) strongly suggests a single namespace owning connection lifecycle.

### 5. `:seon.flow/dynamic` -- boundary type for structurally dynamic data

**Current:** Defined in `seon.schema` (lines 53-66), used in `seon.flow.msg` for wire protocol fields.

**Proposed rename context:** `seon.flow.*` becomes `seon.wire.*` / `seon.dispatch.*` / `seon.process.*`

**Recommendation:** `:seon.wire/dynamic`

**Rationale:** This type exists specifically for wire protocol boundaries -- fields whose content type depends on the target function. The `seon.wire` namespace (proposed rename of `seon.flow.msg`) is where this concept belongs. The definition should move from `seon.schema` to `seon.wire` (registered via `schema/register!` from there).

**Note on hiccup:** The design doc proposes `(schema/register! :seon.render/hiccup :seon.flow/dynamic)`. After renaming, this becomes `(schema/register! :seon.render/hiccup :seon.wire/dynamic)`. But hiccup is not really a wire protocol boundary -- it's a render boundary. Consider whether `:seon.render/hiccup` needs its own simple schema (a `some?` predicate schema registered in `seon.render`) rather than aliasing the wire dynamic type. Hiccup polymorphism is a render concern, not a wire concern.

### 6. `:seon.render/html`, `:seon.render/ai`, `:seon.render/hiccup`

**Current:** Already in use throughout the codebase. `:seon.render/html` and `:seon.render/ai` are output keys from render functions. `:seon.render/hiccup` is proposed in the design doc.

**Processing code:** `seon.render` (resolution, format dispatch), `seon.ns.routes` (extracting HTML from results), `seon.ns.lifecycle` (SSE push).

**Recommendation:** Keep as-is. **These are correct.**

**Rationale:** `seon.render` exists as a code namespace and is the primary processor of these keys. Render functions in domain namespaces produce these keys; `seon.render` consumes them. The convention is satisfied.

### 7. `:seon.render/documentation`

**Current:** Used in `seon.render.code` for documentation rendering output.

**Recommendation:** Keep as-is. **Correct.**

**Rationale:** `seon.render` is the parent namespace. `seon.render.code` is a child that owns the documentation rendering. Using `:seon.render/documentation` (parent namespace) rather than `:seon.render.code/documentation` is acceptable because `seon.render` is the namespace that does output-key resolution across all render types.

---

## Design Doc: Namespace Renaming Audit

The design doc proposes renaming several namespaces. Validating each:

### Correct Renames

| Current | Proposed | Verdict |
|---------|----------|---------|
| `seon.flow.harness` | `seon.process` | **Good.** "Process" is clearer than "harness". The code manages JVM processes. |
| `seon.flow.harness.bridge` | `seon.process.bridge` | **Good.** Follows parent. |
| `seon.flow.harness.channel` | `seon.process.channel` | **Good.** Follows parent. |
| `seon.flow.harness.proxy` | `seon.process.proxy` | **Good.** Follows parent. |
| `seon.flow.pool` | `seon.process.pool` | **Good.** JVM pool is a process concern. |
| `seon.flow.msg` | `seon.wire` | **Good.** "Wire" clearly communicates serialization boundary. |
| `seon.flow.trace` | `seon.trace` | **Good.** Tracing is a cross-cutting concern, not flow-specific. |
| `seon.flow.status` | `seon.trace.status` | **Good.** Health/observability is a facet of tracing. |
| `seon.web.reactive.transform` | `seon.render.transform` | **Good.** Transform is part of the render pipeline. |

### Needs Discussion

| Current | Proposed | Concern |
|---------|----------|---------|
| `seon.flow.topology` | `seon.dispatch` | **Partially good.** The topology code does two things: (1) core.async.flow topology management (building the flow graph, start/stop) and (2) request routing (`request!`, reply-router). The dispatch rename fits (2) but not (1). Consider splitting: `seon.dispatch` for routing logic, keep topology management as an implementation detail inside `seon.dispatch` or in `seon.dispatch.topology`. |
| `seon.render` (specificity part) | `seon.dispatch.resolve` | **Good concept, verify scope.** Specificity resolution is general-purpose, not render-specific. But the code must actually exist as `seon.dispatch.resolve` -- don't create the namespace until the generalization is implemented. |
| `seon.ns.routes` (dispatch part) | `seon.dispatch.http` | **Good.** HTTP is an entry point into dispatch. |
| `seon.ns.routes` (introspection part) | `seon.ns.introspect.view` | **Acceptable.** But `seon.ns.introspect` already exists as a namespace. Consider `seon.ns.introspect` (merge into existing) rather than creating a new `seon.ns.introspect.view`. |
| `seon.web.reactive.actions` | `seon.dispatch.actions` | **Good.** Signal actions are dispatch-layer concerns. |

---

## Summary: Complete Keyword Mapping

| Design Doc Keyword | Recommended Name | Rationale |
|--------------------|------------------|-----------|
| `:seon/to` | `:seon.dispatch/to` | Dispatch layer processes targeting |
| `:seon/subscribe` | `:seon.dispatch/subscribe` | Dispatch layer reads subscription metadata |
| `:seon/queue` | `:seon.dispatch/queue` | Dispatch layer configures buffering |
| `:seon/queue-size` | `:seon.dispatch/queue-size` | Dispatch layer configures buffering |
| `:seon/batch-interval-ms` | `:seon.dispatch/batch-interval-ms` | Dispatch layer configures buffering |
| `:seon.conn/*` | `:seon.conn/*` | Create `seon.conn` namespace to own connection model |
| `:seon.flow/dynamic` | `:seon.wire/dynamic` | Wire protocol boundary type |
| `:seon.render/html` | `:seon.render/html` | Already correct |
| `:seon.render/ai` | `:seon.render/ai` | Already correct |
| `:seon.render/hiccup` | `:seon.render/hiccup` | Already correct (but define its own schema in `seon.render`, not alias `:seon.wire/dynamic`) |

### Existing Codebase Keywords to Fix

| Current | Recommended | Rationale |
|---------|-------------|-----------|
| `:seon/id` | `:seon.db/id` | DB layer owns entity identity |
| `:seon/view` | `:seon.ns.view/type` | `seon.ns.view` processes view dispatch |
| `:seon/schema` | `:seon.render/schema` | `seon.render` is primary consumer of schema metadata on values |
| `:seon/component` | `:seon/component` (keep) | Integrant framework convention, acceptable exception |
| `:seon/runtime-db` | `:seon/runtime-db` (keep) | Integrant component key, acceptable exception |

---

## Open Questions

1. **`:seon.render/hiccup` independence from `:seon.wire/dynamic`:** Should hiccup have its own `some?` predicate schema in `seon.render` rather than aliasing the wire dynamic type? Hiccup polymorphism is a render concern. The wire dynamic type carries semantics about cross-boundary serialization that don't apply to hiccup trees within a single JVM.

2. **`:seon.db/id` migration scope:** `:seon/id` is used extensively in `seon.ai`, `seon.ai.claude`, and `seon.ai.datalevin`. Renaming it is a mechanical but wide-reaching change. Should be done as part of Phase 5 (namespace renaming) to batch the churn.

3. **`:seon.conn` namespace:** The design doc assumes a unified connection model but no `seon.conn` namespace exists yet. This namespace should be created in Phase 2 or 3, before keywords are registered against it.
