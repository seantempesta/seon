---
type: architecture
status: design
---
# Problems & Gaps in Namespace Architecture

> Identified problems in the current namespace system. Each item describes what's wrong, where it manifests, and what's affected. See [[architecture/cleanup]] for concrete duplication/dead-code inventory.

## Architectural Friction

### State is managed in three separate mechanisms that don't stay in sync
[[components/context]] stores namespace state in atoms. [[components/harness]] manages request/response over TCP. Flow topology manages process lifecycle. These three systems each hold partial truths about what a namespace is doing. When one updates, the others don't know.
- **Where**: `ctx.clj` (atom registry), `flow/harness.clj` (TCP proxy), `flow/topology.clj` (process graph)
- **Affected**: Any component that needs to know "what is namespace X doing right now?" must consult multiple sources
- **Severity**: Architectural — root cause of several downstream problems

### Namespace behavior is split between harness and ctx with no unified model
The harness handles requests (what a namespace *does*), while ctx holds state (what a namespace *knows*). There is no single component that represents "a namespace as a running entity." Behavior and state are defined in separate systems with separate lifecycles.
- **Where**: `flow/harness.clj` (behavior), `ctx.clj` (state)
- **Affected**: [[components/namespace-lifecycle]], anything that creates or restores a namespace instance
- **Severity**: Architectural — forces every consumer to coordinate two unrelated systems

### Atom watches bypass flow, making state changes invisible to other processes
When namespace state changes in [[components/context]], atom watches fire persistence and SSE push directly. These side effects happen outside flow, so no other flow process can observe, intercept, or react to state transitions.
- **Where**: `ctx.clj:285-355` (watch-based persistence and SSE push)
- **Affected**: Flow topology has no visibility into namespace state changes; monitoring, debugging, and cross-namespace reactivity are all blocked
- **Severity**: Architectural — undermines flow as the routing backbone

### Namespace lifecycle is a coupling bottleneck
`ns/lifecycle.clj` `ensure-instance!` depends on 7 other components: ctx, db, graph, runtime, render, schema, web. Any change to any of these components risks breaking namespace startup. Testing lifecycle in isolation is effectively impossible.
- **Where**: [[components/namespace-lifecycle]] (`ns/lifecycle.clj`)
- **Affected**: System startup reliability, test isolation, refactoring safety
- **Severity**: Friction — makes changes to any downstream component risky

## Duplication

The codebase has a "3-of-everything" pattern across rendering, SSE push, AI context building, status badges, and shared utilities. Full inventory in [[architecture/cleanup]].

The duplication is not just wasted code — it means bug fixes and behavior changes must be applied in multiple places, and consumers must know which variant to use.

## Missing Capabilities

### No mechanism for namespaces to subscribe to live data from other namespaces
Namespaces can query the [[components/code-graph]] on demand (pull), but there is no way to say "notify me when this query result changes." Every consumer must poll or rely on manual refresh.
- **Where**: `graph/query.clj` (pull-only API)
- **Affected**: Any namespace view that displays data from another namespace shows stale information until manually refreshed
- **Severity**: Missing capability — blocks reactive cross-namespace UIs

### No mechanism for namespaces to broadcast signals about their state changes
When a namespace's state changes, only atom watches (internal to ctx) know about it. There is no general-purpose signal or event that other namespaces or system components can subscribe to.
- **Where**: State changes are trapped inside `ctx.clj` atom watches
- **Affected**: Cross-namespace coordination, system-wide event monitoring
- **Severity**: Missing capability — no way to build reactive workflows across namespaces

### No way for a namespace to define custom behavior beyond basic state storage
All namespaces get the same ctx atom + harness proxy. A namespace that needs custom request handling, derived state computation, or specialized lifecycle behavior has no extension point to define it.
- **Where**: `flow/harness.clj` (fixed proxy behavior), `ctx.clj` (fixed atom storage)
- **Affected**: Domain namespaces that need richer behavior patterns
- **Severity**: Missing capability — forces all namespaces into one behavioral mold