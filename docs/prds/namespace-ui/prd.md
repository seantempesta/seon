# PRD: Namespace UI

**Status:** Planning
**Priority:** High
**Branch:** TBD (after agent-observatory completes)

---

## Phase Summary

| Phase | Goal | Days | Test |
|-------|------|------|------|
| 1 | Viewer system + introspection | 2-3 | `/seon.ai.claude` shows functions, vars, atoms |
| 2 | Expand/collapse + styling | 2 | Click `{` to expand maps |
| 3 | Malli schema viewer | 1-2 | Schemas listed with clickable refs |
| 4 | XTDB entity browser | 2-3 | Forward + reverse refs navigable |
| 5 | Live atom updates | 2-3 | REPL change → browser update in 100ms |
| 6 | Dashboard | 2 | `/` shows namespace tree |
| 7 | Custom renderers | 2 | `:seon.ui/render-fn` in ctx works |

**Total: 12-18 days**

All introspection is **runtime** - no hardcoded table names, schema keys, or function names.

---

## Vision

Every Clojure namespace in Seon becomes a viewable, introspectable "app". The system provides:

1. **Default renderer** - Automatically shows functions, vars, atoms, schemas, and DB entities via introspection
2. **Custom renderers** - Agents can override to build tailored UIs for specific domains
3. **Live tiles** - Windows Phone-style dashboard where each namespace is a configurable tile
4. **Session model** - View namespaces read-only, or launch sessions for live interaction

Think of Seon as an OS where namespaces are apps. Users can:
- Browse available namespaces from the dashboard
- Open namespace views (read-only introspection)
- Launch sessions for live REPL + DB access
- Have multiple instances (e.g., workout tracker for different users)
- Direct the orchestrator to create new namespaces and watch agents build them live

---

## Goals

1. **Every namespace viewable** - Any `seon.*` namespace can be inspected via web UI
2. **Zero-config default** - Useful view without any namespace modifications
3. **Agent-customizable** - Agents can override rendering for domain-specific UIs
4. **Session-aware** - Read-only introspection OR live session with ctx + DB
5. **Responsive tiles** - Namespace views adapt to tile/half/full view modes

---

## Problem Statement

Currently, understanding a namespace requires:
- Reading source code directly
- Using REPL to inspect vars/atoms
- Querying XTDB manually for related data

There's no unified view that shows "what's in this namespace and what's its current state?"

The agent observatory (in progress) solves this for agents specifically. This PRD generalizes the pattern to ALL namespaces.

**Impact:** Transforms Seon from a "headless" system into a visual, introspectable platform where both humans and agents can see and interact with any part of the system.

---

## URL Structure

```
/                              -> Orchestrator dashboard (namespace browser)
/seon.ai.claude                -> Namespace view (read-only introspection)
/seon.ai.claude?id=e45gf       -> Session view (live ctx + DB for that session)
```

The namespace IS the route. Session ID is optional query param.

**Rationale:**
- Minimal cruft in URLs
- Namespace names are already unique identifiers
- Query param for session keeps the base route clean
- `/` as dashboard is intuitive starting point

---

## Implementation Phases

Based on research (see `research/viewer-architecture.md`), here are actionable phases.

**Key Principle:** All introspection is RUNTIME. Nothing is hardcoded.
- Namespaces: `ns-publics`, `ns-interns`, var metadata
- Malli schemas: Query `malli.core/default-registry` at runtime
- XTDB tables: Discover via `information_schema` or `xt/q`
- Atoms: Detect via `(instance? clojure.lang.IAtom (var-get v))`

---

## Phase 1: Viewer System + Basic Introspection

**Goal:** Render any Clojure value as styled Hiccup. Introspect namespaces generically.

**Duration:** 2-3 days

### 1.1 Value Viewer (Multimethod Dispatch)

```clojure
(ns seon.ui.viewer)

;; Dispatch on type or ::viewer metadata
(defmulti render-value (fn [v _opts] (or (::viewer (meta v)) (type v))))

(defmethod render-value :default [v _] [:code (pr-str v)])
(defmethod render-value nil [_ _] [:span.text-gray-400 "nil"])
(defmethod render-value Boolean [v _] [:span.text-blue-600 (str v)])
(defmethod render-value Number [v _] [:span.text-green-600 (str v)])
(defmethod render-value String [v _] [:span.text-amber-600 (pr-str v)])
(defmethod render-value clojure.lang.Keyword [v _] [:span.text-purple-600 (str v)])
(defmethod render-value clojure.lang.IPersistentMap [m opts] ...)
(defmethod render-value clojure.lang.IPersistentVector [v opts] ...)
```

### 1.2 Namespace Introspection (Generic, Runtime)

```clojure
(ns seon.ns.introspect)

(defn introspect
  "Introspect ANY loaded namespace at runtime."
  [ns-sym]
  (when-let [ns (find-ns ns-sym)]
    (let [publics (ns-publics ns)]
      {:ns-name ns-sym
       :doc (-> ns meta :doc)
       :functions (->> publics
                       (filter (fn [[_ v]] (fn? (var-get v))))
                       (map (fn [[k v]] {:name k
                                         :arglists (:arglists (meta v))
                                         :doc (:doc (meta v))})))
       :vars (->> publics
                  (filter (fn [[_ v]] (not (fn? (var-get v)))))
                  (filter (fn [[_ v]] (not (instance? clojure.lang.IAtom (var-get v)))))
                  (map (fn [[k v]] {:name k :value (var-get v)})))
       :atoms (->> publics
                   (filter (fn [[_ v]] (instance? clojure.lang.IAtom (var-get v))))
                   (map (fn [[k v]] {:name k :atom v})))
       :requires (ns-aliases ns)})))
```

### 1.3 Dynamic Route Handler

```clojure
;; Route: GET /{namespace}
(defn namespace-handler [{:keys [path-params query-params]}]
  (let [ns-sym (symbol (:namespace path-params))
        session-id (:id query-params)]
    (if-let [data (introspect ns-sym)]
      (render-namespace-view data session-id)
      {:status 404 :body "Namespace not found"})))
```

### Test

```clojure
;; In REPL:
(introspect 'seon.ai.claude)
;; => {:ns-name seon.ai.claude, :functions [...], :atoms [...], ...}

;; In browser:
;; GET /seon.ai.claude -> renders basic HTML
```

### Deliverables

- [ ] `seon.ui.viewer` namespace with multimethod dispatch
- [ ] `seon.ns.introspect/introspect` function (runtime, generic)
- [ ] Route handler at `/{namespace}`
- [ ] Basic unstyled HTML output

---

## Phase 2: Expand/Collapse + Styling

**Goal:** Collections expand/collapse, styled with Tailwind.

**Duration:** 2 days

### 2.1 Datastar Expand/Collapse

```clojure
;; Map viewer with expand/collapse
(defmethod render-value clojure.lang.IPersistentMap [m opts]
  (let [id (gensym "map")]
    [:div {:data-signals (str "{" id ": false}")}
     [:span.cursor-pointer
      {:data-on-click (str "$" id " = !$" id "}")}
      "{"]
     ;; Collapsed: show count
     [:span {:data-show (str "!$" id)}
      [:span.text-gray-400 (str (count m) " entries")]]
     ;; Expanded: show entries
     [:div.pl-4 {:data-show (str "$" id)}
      (for [[k v] m]
        [:div.flex.gap-2
         (render-value k opts)
         (render-value v opts)])]
     "}"]))
```

### 2.2 Truncation for Large Values

```clojure
(defn render-with-truncation [coll {:keys [limit] :or {limit 20}}]
  (let [total (count coll)
        visible (take limit coll)]
    [:div
     (for [item visible] (render-value item {}))
     (when (> total limit)
       [:button.text-blue-500
        {:data-on-click "..."}
        (str "+" (- total limit) " more")])]))
```

### Test

```clojure
;; In browser:
;; - Click on `{` to expand/collapse maps
;; - Large collections show "20 items" collapsed, expand on click
```

### Deliverables

- [ ] Expand/collapse for maps, vectors, sets
- [ ] Truncation with "show more" for large collections
- [ ] Tailwind styling (colors match Clerk/Portal conventions)
- [ ] Function cards with docstring expand

---

## Phase 3: Malli Schema Viewer (Runtime)

**Goal:** Query and render Malli schemas for any namespace.

**Duration:** 1-2 days

### 3.1 Schema Discovery (Runtime)

Malli schemas are in `malli.core/default-registry`. Query at runtime:

```clojure
(ns seon.ns.introspect)

(defn schemas-for-namespace
  "Find all Malli schemas whose keyword namespace matches ns-sym."
  [ns-sym]
  (let [ns-str (str ns-sym)
        registry (malli.registry/schemas malli.core/default-registry)]
    (->> registry
         (filter (fn [[k _]]
                   (and (keyword? k)
                        (= (namespace k) ns-str))))
         (map (fn [[k schema]]
                {:name k
                 :schema (malli.core/form schema)
                 :type (malli.core/type schema)})))))

;; Usage:
(schemas-for-namespace 'seon.ai)
;; => [{:name :seon.ai/message :schema [:map ...]} ...]
```

### 3.2 Schema Viewer with Clickable Refs

```clojure
(defmethod render-value ::schema [{:keys [name schema]} opts]
  [:div.border.rounded.p-2
   [:div.font-bold (str name)]
   [:pre.text-sm.mt-2
    (render-schema-form schema opts)]])

(defn render-schema-form [form opts]
  (cond
    ;; Keyword refs are clickable
    (keyword? form)
    [:a.text-purple-600.hover:underline
     {:href (str "/" (namespace form) "?schema=" (name form))}
     (str form)]

    ;; Recurse into vectors
    (vector? form)
    [:span "[" (interpose " " (map #(render-schema-form % opts) form)) "]"]

    :else (pr-str form)))
```

### Test

```clojure
;; GET /seon.ai.claude shows schemas section
;; Click on ::ai/node navigates to that schema
```

### Deliverables

- [ ] `schemas-for-namespace` function (runtime query)
- [ ] Schema viewer component
- [ ] Clickable cross-references between schemas

---

## Phase 4: XTDB Entity Browser (Generic)

**Goal:** Browse XTDB entities for any namespace/session.

**Duration:** 2-3 days

### 4.1 Table Discovery (Runtime)

XTDB v2 - discover tables dynamically, no hardcoding:

```clojure
(defn list-tables
  "List all tables in an XTDB node."
  [node]
  (db/q node "SELECT table_name FROM information_schema.tables"))

(defn table-columns
  "Get columns for a table."
  [node table-name]
  (db/q node
    "SELECT column_name FROM information_schema.columns WHERE table_name = ?"
    [table-name]))

(defn table-row-count
  "Count rows in a table."
  [node table-name]
  (-> (db/q node (str "SELECT COUNT(*) as cnt FROM " table-name))
      first :cnt))
```

### 4.2 Entity Viewer with Forward Refs

```clojure
(defn render-entity [node entity]
  [:div.border.rounded.p-2
   (for [[k v] entity]
     [:div.flex.gap-2
      [:span.text-purple-600 (str k)]
      (if (looks-like-entity-id? v)
        ;; Clickable link to referenced entity
        [:a.text-blue-500.hover:underline
         {:href (str "?entity=" v)}
         (str v)]
        (render-value v {}))])])

(defn looks-like-entity-id?
  "Heuristic: UUIDs, strings starting with known prefixes, etc."
  [v]
  (or (uuid? v)
      (and (string? v) (re-matches #"^[a-z]+-[a-f0-9]+" v))))
```

### 4.3 Bidirectional References

Find "what references this entity":

```clojure
(defn references-to
  "Find all entities that reference target-id in any column."
  [node target-id]
  (let [tables (list-tables node)]
    (->> tables
         (mapcat (fn [{:keys [table_name]}]
                   (let [cols (table-columns node table_name)]
                     (->> cols
                          (mapcat (fn [{:keys [column_name]}]
                                    (let [results (db/q node
                                                   (str "SELECT xt$id FROM " table_name
                                                        " WHERE " column_name " = ?")
                                                   [target-id])]
                                      (when (seq results)
                                        [{:table table_name
                                          :column column_name
                                          :count (count results)}]))))))))
         (into []))))
```

### Test

```clojure
;; GET /seon.ai.claude?id=e45gf shows:
;; - Tables in that session's DB with row counts
;; - Click entity -> see details with forward refs
;; - "Referenced by" section shows reverse refs
```

### Deliverables

- [ ] `list-tables`, `table-columns` (generic discovery)
- [ ] Entity viewer with clickable forward refs
- [ ] `references-to` for bidirectional navigation
- [ ] Integration with session-specific DBs

---

## Phase 5: Live Atom Updates

**Goal:** Atoms update in real-time via SSE.

**Duration:** 2-3 days

### 5.1 Watch Registry

```clojure
(ns seon.ui.live)

(defonce watch-registry (atom {}))

(defn watch-atom!
  "Watch an atom and push updates via SSE. Returns cleanup fn."
  [session-id atom-var selector]
  (let [watch-key (keyword "seon.ui.live" session-id)
        debounce-ms 100
        last-sent (atom nil)]

    ;; Initial render
    (sse/merge-fragment session-id selector
      (render-value @atom-var {}))

    ;; Watch with debounce
    (add-watch atom-var watch-key
      (fn [_ _ _ new-val]
        (future
          (Thread/sleep debounce-ms)
          (when (and (= @atom-var new-val)
                     (not= @last-sent new-val))
            (reset! last-sent new-val)
            (sse/merge-fragment session-id selector
              (render-value new-val {}))))))

    ;; Cleanup
    (fn [] (remove-watch atom-var watch-key))))
```

### 5.2 Atom Viewer Component

```clojure
(defn atom-viewer [atom-var]
  (let [id (str "atom-" (hash atom-var))]
    [:div.border.rounded.p-2
     [:div.flex.items-center.gap-2
      [:span.font-bold (str (:name (meta atom-var)))]
      [:span.text-xs.text-green-500 "● live"]]
     [:div {:id id}
      (render-value @atom-var {})]]))
```

### Test

```clojure
;; In browser: view namespace with atom
;; In REPL: (swap! some-atom assoc :new-key "value")
;; Browser updates within 100ms
```

### Deliverables

- [ ] `watch-atom!` with debounce
- [ ] SSE integration for pushing updates
- [ ] Visual "live" indicator
- [ ] Cleanup on session disconnect

---

## Phase 6: Orchestrator Dashboard

**Goal:** `/` shows namespace tree and active sessions.

**Duration:** 2 days

### 6.1 Namespace Discovery

```clojure
(defn all-seon-namespaces
  "Find all loaded seon.* namespaces."
  []
  (->> (all-ns)
       (filter #(str/starts-with? (str (ns-name %)) "seon."))
       (map ns-name)
       (sort)))

(defn namespace-tree
  "Group namespaces into hierarchical tree."
  [namespaces]
  ;; seon.ai.claude -> {:seon {:ai {:claude {:_ns 'seon.ai.claude}}}}
  ...)
```

### 6.2 Dashboard View

```clojure
(defn dashboard-handler [_request]
  (let [namespaces (all-seon-namespaces)
        tree (namespace-tree namespaces)
        sessions (session/list-agent-sessions)]
    (render-dashboard {:tree tree :sessions sessions})))
```

### Deliverables

- [ ] `/` route with dashboard
- [ ] Namespace tree (expandable)
- [ ] Active sessions panel
- [ ] Click to navigate

---

## Phase 7: Custom Renderers

**Goal:** Namespaces can override default rendering.

**Duration:** 2 days

### 7.1 Render Function Convention

If a namespace's ctx atom contains `:seon.ui/render-fn`, use it:

```clojure
;; In agent code for seon.trading namespace:
(swap! *ctx* assoc :seon.ui/render-fn 'seon.trading/render-dashboard)

;; The render function:
(defn render-dashboard
  "Custom renderer for trading namespace.

   Request:
     {:view-mode :tile | :half | :full
      :session-id \"e45gf\" | nil
      :ctx @*ctx*
      :db <xtdb-connection>}

   Returns: Hiccup HTML"
  [{:keys [view-mode session-id ctx db]}]
  (case view-mode
    :tile  (render-tile ctx)
    :half  (render-half-view ctx db)
    :full  (render-full-dashboard ctx db)))
```

### 7.2 View Modes

| Mode | Use Case | Typical Size |
|------|----------|--------------|
| `:tile` | Dashboard mini-view | 200x150 px |
| `:half` | Side-by-side comparison | 50% viewport |
| `:full` | Dedicated view | Full viewport |

### 7.3 Fallback Chain

```
1. Check ctx for :seon.ui/render-fn -> use custom renderer
2. Otherwise -> use default introspection renderer
```

### Deliverables

- [ ] View mode detection and passing
- [ ] Custom renderer lookup from ctx
- [ ] Fallback to default renderer
- [ ] Documentation for writing custom renderers

---

## Phase 8 (Future): Tile System / Window Management

**Goal:** Drag-and-drop tile management, persistent layouts.

**Lower priority** - the basic dashboard covers 80% of use cases.

- Tile size configuration
- Drag-and-drop reordering
- Layout persistence in XTDB
- Eventually: floating windows, minimize/maximize

---

## Technical Constraints

- **Datastar/SSE** - Use existing patterns from agent-observatory
- **Tailwind CSS** - Consistent with current styling
- **No external JS frameworks** - Keep it simple, Datastar handles reactivity
- **REPL-friendly** - All introspection functions usable from REPL
- **Session isolation** - Session views only see their own data

---

## Success Criteria

1. **Phase 1:** Navigate to `/seon.ai.claude` and see functions, vars, atoms listed
2. **Phase 2:** Click on `{` to expand/collapse maps in the viewer
3. **Phase 3:** Navigate to `/seon.ai.claude` and see Malli schemas for that namespace
4. **Phase 4:** Browse XTDB entities, click ID to navigate, see "Referenced by" section
5. **Phase 5:** View atom in browser, change in REPL, see browser update within 100ms
6. **Phase 6:** Navigate to `/` and see tree of all seon.* namespaces
7. **Phase 7:** Set `:seon.ui/render-fn` in ctx, see custom view render

---

## Dependencies

- **agent-observatory** - Phase 2-3 builds on XTDB browser and SSE patterns
- **seon.schema** - Schema introspection depends on registry
- **seon.orchestrator.session** - Session-aware views need session system

---

## Out of Scope (This PRD)

- Multi-user authentication
- Remote access / public URLs
- Mobile-optimized layouts
- Namespace creation UI (orchestrator handles this via REPL)

---

## Future Vision

Once this is working:

1. **Live coding** - Watch namespace update as agent writes code
2. **Entity relationships** - Visualize how namespaces connect via requires
3. **Time travel** - XTDB bitemporal queries to see past states
4. **Agent marketplace** - Share namespace "apps" with others
5. **Voice control** - "Show me the trading dashboard"

---

## Open Questions

1. **Namespace filtering** - Show all `seon.*` or also user namespaces?
2. **Launch session UX** - Button on read-only view? Or separate action?
3. **Tile persistence** - Store in orchestrator ctx or separate XTDB table?

---

## Resources to Study

| Resource | What's There |
|----------|--------------|
| `src/seon/web/handlers.clj` | Existing route patterns |
| `src/seon/orchestrator/session.clj` | Session lifecycle |
| `src/seon/schema.clj` | Schema registry |
| `docs/prds/agent-observatory/` | SSE patterns, Datastar usage |
| `reference-code/datastar-clojure/` | Datastar SDK |

---

## Research Documents (Completed)

| Document | Contents |
|----------|----------|
| `research/clerk-research.md` | Clerk viewer architecture, why we're building our own |
| `research/viewer-architecture.md` | Deep dive on Portal, Reveal, XTDB Inspector patterns |
| `research/chatgpt-research.md` | Surface-level overview (less useful) |

## Reference Code (Git Submodules)

| Repository | Location | What to Study |
|------------|----------|---------------|
| Portal | `reference-code/portal/` | Watch mechanism, datafy/nav, lazy loading |
| Reveal | `reference-code/reveal/` | Multimethod dispatch, annotation threading |
| Clerk | `reference-code/clerk/` | Viewer predicates, pagination |
| XTDB Inspector | `reference-code/xtdb-inspector/` | Reverse lookup queries, entity browser |
