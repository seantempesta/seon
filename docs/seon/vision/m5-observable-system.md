---
type: milestone
status: partial
order: 5
---
# M5: Observable System

When this milestone is crossed, any part of the running system is visible in real time -- agents, namespaces, schemas, data, and health -- through a unified, terminal-dense interface. Humans see it in the browser. Agents see it through rendered context. The same data, two formats, one discovery mechanism. There is no "hidden state" in Seon.

An operator opens the dashboard and sees everything: which namespaces are alive, which agents are working, what schemas exist, what data is stored, and whether the system is healthy. They can drill into any namespace and see its functions, its ctx atom, its dependencies, and its test results. They can browse schemas by namespace, see which functions consume them, and inspect entity relationships. They can explore live data in Datalevin -- expanding nested structures, following entity references, searching by attribute.

None of this is hardcoded. Every view is a function discovered by schema shape. Adding a new view for a new data type means writing a function with the right `:malli/schema` -- the system finds it.

## The Scenario

An agent writes a new function in `seon.trading.signals`. The code graph scanner runs. The dashboard updates: the function count for `seon.trading.signals` increments. The operator clicks into the namespace page and sees the new function with its schema, its test status (untested), and its callers (none yet).

The operator clicks "Schemas" in the nav. The schema browser shows all registered schemas grouped by namespace. Under `seon.trading.signals`, the new function's input and output schemas appear. Clicking a schema shows its Malli definition, which functions accept it as input, which produce it as output, and which entity attributes use it.

The operator clicks "Data" and queries `:seon.trading.signals/ticker`. Datalevin results appear as an interactive table -- nested maps collapse, entity references are clickable links that navigate to the referenced entity. Large collections paginate.

Throughout all of this, SSE pushes updates through a single flow-based mechanism. No polling. No three-way split between ctx watches, content-hash handlers, and flow SSE. One push path.

## What This Requires

**Single rendering system.** One dispatch mechanism (specificity-based schema discovery) for all views -- namespace pages, dashboard, schema browser, data explorer. The multimethod-based `ns/view.clj` is retired. All rendering goes through `seon.render/find-renderer`.

**Single SSE push mechanism.** All browser updates route through flow. Ctx atom changes emit flow events, not fire atom watches directly. Content-hash deduplication is preserved as a flow step. Client tracking is unified.

**Schema browser.** Routes at `/schemas` and `/schemas/{namespace}`. Data API already exists (`registered-schemas`, `schemas-in-namespace`). Web layer renders schema definitions, consumer functions, and entity relationships.

**Data explorer.** Routes at `/data` and `/data/{db-name}`. Interactive rendering: expand/collapse for nested structures, truncation with expand-on-demand, clickable entity references. Datalevin query interface for ad-hoc exploration.

**Dashboard density.** The dashboard at `/` shows system liveness, namespace tree with inline status indicators, agent activity summary, and health for all components. Follows the Phosphor Terminal design system: `text-xs`, `p-3`, monospace, warm colors.

**Status badge unification.** One `status-dot` component in `web/components.clj`. All callers use it.

## What Already Exists

- [[vision/capabilities/agent-observatory]] -- complete. Live agent monitoring with full conversation logs.
- [[vision/capabilities/reactive-ui]] -- complete. SSE push on ctx changes, cache invalidation on code changes.
- [[vision/capabilities/renderer-discovery]] -- complete. Specificity-based function discovery for rendering.
- [[vision/capabilities/namespace-introspection]] -- partial. Runtime introspection works, content negotiation works, but dead code remains and two rendering systems overlap.
- [[vision/capabilities/system-dashboard]] -- proof-of-concept. Exists but sparse, does not match design system.
- [[vision/capabilities/schema-browser]] -- not started. Data API exists, no web layer.
- [[vision/capabilities/data-explorer]] -- not started. No interactive data rendering.
- [[vision/capabilities/agent-log-access]] -- not started. Log parsing exists, no agent-facing API.

## How to Verify

```clojure
;; Schema browser serves all registered schemas
(let [resp (http/get "http://localhost:8080/schemas")]
  (assert (= 200 (:status resp)))
  (assert (str/includes? (:body resp) "seon.trading.signals/ticker")))

;; Data explorer renders Datalevin query results
(let [resp (http/get "http://localhost:8080/data/seon")]
  (assert (= 200 (:status resp))))

;; Single SSE push path -- no atom watches doing direct SSE
;; grep for add-watch in ctx.clj should find only flow-based watches
(assert (zero? (count (grep "send-event!" "src/seon/ctx.clj"))))

;; Single rendering system -- no multimethod dispatch for views
(assert (not (resolve 'seon.ns.view/render-view)))

;; Dashboard shows live system state
(let [resp (http/get "http://localhost:8080/")]
  (assert (str/includes? (:body resp) "running")))

;; Status badge: one component, no duplicates
(assert (= 1 (count (grep "defn status-dot" "src/seon/web/"))))

```

## Dependencies

**Requires M3 (Discoverable Codebase)** to be at least partial -- the code graph must store function schemas for renderer discovery and schema browsing to work. M3 provides the queryable index; M5 makes it visible.

**Requires M4 (not yet written)** for the rendering unification -- collapsing three rendering systems into one is a prerequisite for the schema browser and data explorer to use the same mechanism.

**Enables M6 (Eval Pipeline)** -- the observable system is the foundation for the agent cockpit. Agents need to see their namespace state rendered in real time. The composable AI renderers in M6 are the same discovery mechanism applied to agent context instead of browser views.

Blocking issues: [[orchestrator/issues/overlap-three-sse-push]], [[orchestrator/issues/overlap-three-rendering]], [[orchestrator/issues/overlap-three-status-badges]].
