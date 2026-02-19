> **Status: ARCHIVED** — Superseded by Datalevin migration

> **Status: ARCHIVED** — Superseded by Datalevin migration

# PRD: XTDB Entity Browser

**Status:** Ready for Implementation
**Priority:** Medium (Phase 4 of namespace-ui)
**Branch:** feature/namespace-ui
**Parent PRD:** `docs/prds/namespace-ui/prd.md`

---

## Summary

A web-based entity browser for navigating XTDB data as a graph. Enables browsing entities across tables with forward reference navigation (click IDs to follow) and reverse reference navigation (see "what references this entity").

**Key Principle:** All table/column discovery is **runtime** via `information_schema`. No hardcoded table names.

---

## Current State

### Database Infrastructure

| Component | Location | Purpose |
|-----------|----------|---------|
| `seon.db.node` | `src/seon/db/node.clj:1-272` | SQL query wrapper (`q`, `entity`, `put!`, `delete!`) |
| `seon.db.queries` | `src/seon/db/queries.clj:1-330` | Domain-specific query builders (options, greeks, IV) |
| XTDB v2 reference | `docs/reference/xtdb-v2-reference.md` | SQL patterns, multi-database, temporal queries |

### Existing Patterns

**Query execution** (`node.clj:38-78`):
```clojure
(defn q
  "Execute a SQL query against XTDB."
  ([node query-or-sql] ...)
  ([node query-or-sql params-or-opts] ...)
  ([node query-or-sql params opts]
   (let [[sql & sql-params] (if (vector? query-or-sql)
                              query-or-sql
                              (into [query-or-sql] (or params [])))
         query-vec (into [sql] sql-params)
         query-opts (cond-> {:key-fn :kebab-case-keyword}
                      (:current-time opts) (assoc :current-time (:current-time opts))
                      ...)]
     (vec (xt/q node query-vec query-opts)))))
```

**Entity lookup** (`node.clj:84-106`):
```clojure
(defn entity
  "Get a single entity by ID from a table."
  ([node table id] (entity node table id {}))
  ([node table id opts]
   (let [table-name (clojure.string/replace (name table) "-" "_")]
     (first (xt/q node
                  [(str "SELECT * FROM " table-name " WHERE _id = ?") id]
                  query-opts)))))
```

### Web Routes

Entity browser will integrate with existing routes (`routes.clj:1-91`):
- `/ns/{namespace}` - Namespace view (existing)
- `/db` - **NEW** Database browser (tables list)
- `/db/{table}` - **NEW** Table view (row list)
- `/db/{table}/{id}` - **NEW** Entity view (single entity with refs)

---

## Goal

Browse XTDB entities with graph-like navigation:

1. **Table Discovery** - List all tables dynamically via `information_schema`
2. **Entity Browsing** - View entities in a table with pagination
3. **Forward References** - Click IDs to navigate to referenced entities
4. **Reverse References** - See "what entities reference this one"
5. **Cross-Database** - Support per-namespace databases (agent isolation)

---

## Implementation Phases

### Phase 0: Table Discovery (1-2 hours)

**Goal:** Query XTDB `information_schema` to list tables and columns at runtime.

**New file:** `src/seon/db/browser.clj`

```clojure
(ns seon.db.browser
  "XTDB entity browser - table discovery and reference navigation.

   All discovery is RUNTIME via information_schema. No hardcoded tables."
  (:require [seon.db.node :as node]
            [xtdb.api :as xt]))

(defn list-tables
  "List all tables in the XTDB node.

   Returns: [{:table-name \"ai_messages\"} {:table-name \"ai_sessions\"} ...]"
  [node]
  (node/q node "SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'"))

(defn table-columns
  "Get columns for a table.

   Returns: [{:column-name \"_id\"} {:column-name \"session_id\"} ...]"
  [node table-name]
  (node/q node
    ["SELECT column_name, data_type
      FROM information_schema.columns
      WHERE table_name = ?" table-name]))

(defn table-row-count
  "Count rows in a table."
  [node table-name]
  (-> (node/q node [(str "SELECT COUNT(*) as cnt FROM " table-name)])
      first
      :cnt))

(defn table-summary
  "Get summary info for a table: columns, row count, sample IDs."
  [node table-name]
  {:table-name table-name
   :columns (table-columns node table-name)
   :row-count (table-row-count node table-name)
   :sample-ids (mapv :xt/id
                     (node/q node [(str "SELECT _id FROM " table-name " LIMIT 5")]))})
```

**Test criteria:**
```clojure
;; In REPL:
(require '[seon.db.browser :as browser])
(browser/list-tables (user/xtdb-node))
;; => [{:table-name "ai_messages"} {:table-name "ai_sessions"} ...]

(browser/table-columns (user/xtdb-node) "ai_messages")
;; => [{:column-name "_id"} {:column-name "session_id"} ...]
```

---

### Phase 1: Entity Viewer (2-3 hours)

**Goal:** View a single entity with all columns, detect forward references.

```clojure
(defn entity-view
  "Get entity with metadata about which values look like IDs.

   Returns: {:entity {...}
             :id-columns [:session-id :parent-id]}"
  [node table-name entity-id]
  (let [entity (first (node/q node
                         [(str "SELECT * FROM " table-name " WHERE _id = ?")
                          entity-id]))
        ;; Heuristic: IDs are UUIDs, UUID strings, or strings matching patterns
        id-columns (->> (keys entity)
                        (filter #(looks-like-id-column? (get entity %)))
                        vec)]
    {:entity entity
     :id-columns id-columns
     :table-name table-name}))

(defn looks-like-id-column?
  "Heuristic: Is this value likely an entity ID?"
  [v]
  (or (uuid? v)
      (and (string? v)
           (or (re-matches #"^[a-f0-9]{4}$" v)           ; Session IDs
               (re-matches #"^[a-f0-9-]{36}$" v)         ; UUIDs
               (re-matches #"^[a-z]+-[a-f0-9]+" v)))))   ; Prefixed IDs

(defn resolve-forward-ref
  "Try to find which table contains the referenced entity."
  [node entity-id]
  (let [tables (list-tables node)]
    (some (fn [{:keys [table-name]}]
            (let [result (node/q node
                           [(str "SELECT _id FROM " table-name " WHERE _id = ? LIMIT 1")
                            entity-id])]
              (when (seq result)
                {:table table-name :id entity-id})))
          tables)))
```

**UI Component** (`src/seon/web/browser.clj`):

```clojure
(defn render-entity-view
  "Render entity as a table with clickable ID links."
  [{:keys [entity id-columns table-name]}]
  [:div.entity-view
   [:h2.text-sm.font-semibold.text-text-200.mb-2
    (str table-name " / " (:xt/id entity))]
   [:table.w-full.text-xs.font-mono
    [:tbody
     (for [[k v] (sort-by key entity)]
       [:tr {:class "hover:bg-base-800"}
        [:td.py-1.pr-4.text-text-400.align-top (name k)]
        [:td.py-1
         (if (some #(= k %) id-columns)
           ;; Clickable link to referenced entity
           [:a {:href (str "/db/_/" v)  ; Resolve table dynamically
                :class "text-signal hover:underline"}
            (str v)]
           ;; Regular value
           (render-value v))]])]]])
```

**Test criteria:**
```clojure
;; Click on session-id value → navigates to that session entity
;; Click on parent-id value → navigates to parent entity
```

---

### Phase 2: Reverse References (2-3 hours)

**Goal:** Show "what entities reference this one" - the key to graph navigation.

```clojure
(defn references-to
  "Find all entities that reference the given ID.

   Scans all tables/columns for matching values.

   Returns: [{:table \"ai_messages\"
              :column \"session_id\"
              :count 15}
             {:table \"edit_event\"
              :column \"session_id\"
              :count 3}]"
  [node target-id]
  (let [tables (list-tables node)]
    (->> tables
         (mapcat (fn [{:keys [table-name]}]
                   (let [columns (table-columns node table-name)]
                     (->> columns
                          ;; Skip system columns
                          (remove #(#{"_id" "_valid_from" "_valid_to"
                                      "_system_from" "_system_to"}
                                    (:column-name %)))
                          (keep (fn [{:keys [column-name]}]
                                  (let [cnt (-> (node/q node
                                                  [(str "SELECT COUNT(*) as cnt FROM "
                                                        table-name
                                                        " WHERE " column-name " = ?")
                                                   target-id])
                                                first :cnt)]
                                    (when (pos? cnt)
                                      {:table table-name
                                       :column column-name
                                       :count cnt}))))))))
         (into []))))

(defn references-to-entities
  "Get actual entities that reference the target.

   For when count is small enough to display inline."
  [node target-id table column limit]
  (node/q node
    [(str "SELECT _id FROM " table " WHERE " column " = ? LIMIT ?")
     target-id limit]))
```

**UI Component:**

```clojure
(defn render-reverse-refs
  "Render 'Referenced by' section."
  [refs]
  (when (seq refs)
    [:div.mt-4
     [:h3.text-xs.font-semibold.text-text-400.uppercase.tracking-wide.mb-2
      "Referenced by"]
     [:table.w-full.text-xs.font-mono
      [:tbody
       (for [{:keys [table column count]} refs]
         [:tr {:class "hover:bg-base-800"}
          [:td.py-1.pr-4
           [:a {:href (str "/db/" table "?filter=" column "=" target-id)
                :class "text-signal hover:underline"}
            table]]
          [:td.py-1.pr-4.text-text-400 column]
          [:td.py-1.text-right count]])]]]))
```

**Test criteria:**
```clojure
;; View an agent session
;; See "Referenced by: ai_messages.session_id (47 entities)"
;; Click → shows filtered table view
```

---

### Phase 3: Table Browser UI (2-3 hours)

**Goal:** Web UI for browsing tables and entities.

**Routes** (add to `routes.clj`):

```clojure
;; Database browser routes
[:get "/db"]              #'browser/tables-page      ; List all tables
[:post "/db"]             #'browser/tables-sse       ; SSE updates
[:get "/db/:table"]       #'browser/table-page       ; Table rows
[:post "/db/:table"]      #'browser/table-sse
[:get "/db/:table/:id"]   #'browser/entity-page      ; Single entity
[:post "/db/:table/:id"]  #'browser/entity-sse
[:get "/db/_/:id"]        #'browser/resolve-entity   ; Resolve ID to table
```

**Table List Page:**

```clojure
(defn tables-page [request]
  (let [node (get-xtdb-node request)
        tables (browser/list-tables node)]
    (html/base-page {:title "XTDB Browser"}
      [:main.p-4
       [:h1.text-lg.font-semibold.mb-4 "Tables"]
       [:table.w-full.text-xs.font-mono
        [:thead
         [:tr.text-text-400.uppercase
          [:th.text-left.py-2 "Table"]
          [:th.text-right.py-2 "Rows"]]]
        [:tbody
         (for [{:keys [table-name]} tables]
           (let [cnt (browser/table-row-count node table-name)]
             [:tr {:class "hover:bg-base-800 cursor-pointer"
                   :data-on-click (str "window.location='/db/" table-name "'")}
              [:td.py-2 [:a {:href (str "/db/" table-name)
                             :class "text-signal hover:underline"}
                         table-name]]
              [:td.py-2.text-right.text-text-400 cnt]]))]]])))
```

**Entity Page with References:**

```clojure
(defn entity-page [request]
  (let [node (get-xtdb-node request)
        table (get-in request [:path-params :table])
        id (get-in request [:path-params :id])
        entity-data (browser/entity-view node table id)
        refs (browser/references-to node id)]
    (html/base-page {:title (str table "/" id)}
      [:main.p-4
       ;; Breadcrumb
       [:nav.text-xs.text-text-400.mb-4
        [:a {:href "/db" :class "hover:text-text-200"} "db"]
        [:span.mx-2 "/"]
        [:a {:href (str "/db/" table) :class "hover:text-text-200"} table]
        [:span.mx-2 "/"]
        [:span.text-text-200 id]]

       ;; Entity view
       (render-entity-view entity-data)

       ;; Reverse references
       (render-reverse-refs refs)])))
```

---

### Phase 4: Multi-Database Support (1-2 hours)

**Goal:** Support per-namespace databases (agent isolation).

Per the XTDB v2 reference (`docs/reference/xtdb-v2-reference.md:125-176`), each namespace can have its own attached database:

```clojure
(defn list-databases
  "List all attached databases."
  [node]
  ;; Query information_schema for database names
  (node/q node "SELECT database_name FROM information_schema.schemata"))

(defn browser-for-database
  "Create browser functions scoped to a specific database."
  [node database-name]
  {:list-tables (fn [] (list-tables-in-db node database-name))
   :entity (fn [table id] (entity-in-db node database-name table id))
   :references-to (fn [id] (references-to-in-db node database-name id))})
```

**Routes:**

```clojure
;; Database-scoped routes
[:get "/db/:database/:table"]       #'browser/db-table-page
[:get "/db/:database/:table/:id"]   #'browser/db-entity-page
```

---

## Performance Considerations

### 1. Lazy Reference Counting

`references-to` can be expensive. Show count lazily:

```clojure
(defn references-to-count
  "Fast count of references (for display before expanding)."
  [node target-id]
  ;; Cache column types to avoid repeated introspection
  ...)
```

### 2. Index Support

XTDB v2 indexes all columns by default. Reverse lookups are O(log n).

### 3. Caching

Cache `information_schema` results per-request or with short TTL:

```clojure
(def ^:private schema-cache (atom {}))

(defn cached-table-columns [node table-name]
  (if-let [cached (get @schema-cache table-name)]
    cached
    (let [cols (table-columns node table-name)]
      (swap! schema-cache assoc table-name cols)
      cols)))
```

---

## Test Criteria

### Phase 0: Table Discovery
```
1. (browser/list-tables node) returns all tables
2. (browser/table-columns node "ai_messages") returns columns
3. No hardcoded table names in browser.clj
```

### Phase 1: Entity Viewer
```
1. GET /db/ai_messages/{id} shows entity
2. Session ID values are clickable links
3. Click link → navigates to referenced entity
```

### Phase 2: Reverse References
```
1. Entity page shows "Referenced by" section
2. Shows table/column/count for each reference
3. Click reference → shows filtered table view
```

### Phase 3: Table Browser UI
```
1. GET /db shows all tables with row counts
2. Click table → shows paginated rows
3. Click row → shows entity detail with forward/reverse refs
```

### Phase 4: Multi-Database
```
1. GET /db shows database selector (if multiple attached)
2. GET /db/seon_trading/signals works
3. Cross-database references resolve correctly
```

---

## Files to Create/Modify

| File | Action | Purpose |
|------|--------|---------|
| `src/seon/db/browser.clj` | Create | Table discovery, reference queries |
| `src/seon/web/browser.clj` | Create | UI handlers and components |
| `src/seon/web/routes.clj` | Modify | Add `/db` routes |
| `src/seon/web/html.clj` | Modify | Add browser-specific styles |

---

## Out of Scope

- Editing entities (read-only browser)
- Temporal queries UI (valid-time/system-time navigation)
- Full-text search within entities
- Export/import functionality

---

## Related Documents

| Document | Relevance |
|----------|-----------|
| `docs/prds/namespace-ui/prd.md` | Parent PRD (Phase 4) |
| `docs/prds/namespace-ui/research/viewer-architecture.md` | Reverse lookup research |
| `docs/reference/xtdb-v2-reference.md` | SQL patterns, multi-database |
| `src/seon/db/node.clj` | Existing query infrastructure |
| `src/seon/ns/introspect.clj` | Runtime introspection patterns |
