---
type: prd
status: draft
tags: [prd, database]
---
# PRD: Malli Schema Viewer

**Parent:** Phase 3 of `docs/prds/namespace-ui/prd.md`

---

## Summary

Build a web-based schema browser that displays all Malli schemas registered via `seon.schema/register!`. Users can:

- View schemas grouped by namespace
- Click schema references to navigate to their definitions
- See schema definitions with syntax highlighting

All discovery is **runtime** - no hardcoded schema lists.

---

## Current State

### Schema Registry

Seon uses a centralized mutable registry in `src/seon/schema.clj`:

```clojure
;; Atom holding all registered domain schemas (schema.clj:38)
(defonce ^:private *schemas (atom {}))

;; Composite registry combining Malli defaults + our schemas (schema.clj:42-46)
(defonce ^:private _registry-init
  (mr/set-default-registry!
   (mr/composite-registry
    (m/default-schemas)
    (mr/mutable-registry *schemas))))

```

### Registration Pattern

Namespaces register schemas using `::` auto-namespacing:

```clojure
;; In seon.ai (ai.clj:60-62)
(schema/register! ::type
                  [:enum {:description "AI entity type"}
                   :session :message :tool-call])

;; In seon.dev.hook (hook.clj:43-45)
(schema/register! ::hook-event-name
                  [:enum {:description "The type of hook event"}
                   "PreToolUse" "PostToolUse"])

```

### Introspection API

The registry already provides introspection (`schema.clj:97-128`):

| Function | Purpose |
|----------|---------|
| `(registered-schemas)` | All registered schemas as map |
| `(registered? k)` | Check if key is registered |
| `(schema-definition k)` | Get raw definition for a key |
| `(schemas-in-namespace "seon.ai")` | Schemas for a specific namespace |

### Current Schema Distribution

Grepping for `schema/register!` shows schemas in:

- `src/seon/ai.clj` - 30+ schemas (sessions, messages, tool calls)
- `src/seon/dev/hook.clj` - 10+ schemas (hook events, config)
- `src/seon/dev/context.clj` - edit/review event schemas
- `src/seon/health.clj` - health check schemas
- `src/seon/ai/claude.clj` - Claude-specific schemas
- Others...

---

## Goal

Add a `/schemas` route (or section within namespace views) that:

1. **Lists all registered schemas** grouped by namespace prefix
2. **Renders schema definitions** with syntax highlighting
3. **Makes refs clickable** - clicking `:seon.ai/session-id` navigates to that schema
4. **Works at runtime** - queries `seon.schema/*schemas` atom directly

---

## Implementation Phases

### Phase 0: Schema List API

**Goal:** API endpoint returning all schemas grouped by namespace.

**Duration:** 0.5 day

#### 0.1 Add Discovery Function

**File:** `src/seon/schema.clj`

```clojure
(defn all-namespaces
  "Return sorted list of all namespace prefixes that have schemas."
  []
  (->> @*schemas
       keys
       (map namespace)
       (remove nil?)
       distinct
       sort))

(defn schema-count
  "Return count of registered schemas."
  []
  (count @*schemas))

```

#### 0.2 Schema API Handler

**File:** `src/seon/web/handlers.clj` (add route)

```clojure
(defn schemas-handler [_request]
  (let [by-ns (group-by #(namespace (key %)) (schema/registered-schemas))]
    {:status 200
     :body {:namespaces (schema/all-namespaces)
            :schemas by-ns
            :total (schema/schema-count)}}))

```

**Route:** `GET /api/schemas`

#### Test Criteria

```
1. (schema/all-namespaces) returns ["seon.ai" "seon.dev.hook" ...]
2. (schema/schemas-in-namespace "seon.ai") returns 30+ schemas
3. GET /api/schemas returns JSON with grouped schemas

```

---

### Phase 1: Schema Viewer Page

**Goal:** HTML page displaying schemas with navigation.

**Duration:** 1 day

#### 1.1 Schema Page Route

**File:** `src/seon/web/routes.clj`

```clojure
["/schemas" {:get #'handlers/schemas-page}]
["/schemas/:ns" {:get #'handlers/schemas-ns-page}]

```

#### 1.2 Schema Page Handler

**File:** `src/seon/web/handlers.clj`

```clojure
(defn schemas-page [_request]
  (html/base-page
    {:title "Schemas"}
    (schema-views/schema-index)))

(defn schemas-ns-page [{:keys [path-params]}]
  (let [ns-name (:ns path-params)]
    (html/base-page
      {:title (str "Schemas: " ns-name)}
      (schema-views/namespace-schemas ns-name))))

```

#### 1.3 Schema Views

**File:** `src/seon/web/schema_views.clj` (new)

```clojure
(ns seon.web.schema-views
  (:require [seon.schema :as schema]
            [seon.web.components :as ui]
            [malli.core :as m]))

(defn schema-index []
  (let [namespaces (schema/all-namespaces)]
    [:main
     (ui/page-header "Schemas" :subtitle (str (schema/schema-count) " registered"))
     [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-4
      (for [ns-name namespaces]
        (let [schemas (schema/schemas-in-namespace ns-name)]
          [:a {:href (str "/schemas/" ns-name)
               :class "p-3 bg-base-850 rounded hover:bg-base-800 transition-colors"}
           [:div.font-mono.text-sm.text-text-50 ns-name]
           [:div.text-xs.text-text-400.mt-1
            (str (count schemas) " schemas")]]))]]))

(defn namespace-schemas [ns-name]
  (let [schemas (schema/schemas-in-namespace ns-name)]
    [:main
     (ui/page-header ns-name :subtitle (str (count schemas) " schemas"))
     [:div.space-y-4
      (for [[k v] (sort-by key schemas)]
        (schema-card k v))]]))

```

#### Test Criteria

```
1. GET /schemas shows namespace list with counts
2. Click namespace → shows all schemas in that namespace
3. Schema definitions render with proper formatting

```

---

### Phase 2: Clickable References

**Goal:** Schema refs become navigable links.

**Duration:** 0.5 day

#### 2.1 Schema Renderer with Ref Detection

**File:** `src/seon/web/schema_views.clj`

```clojure
(defn render-schema-form
  "Render a Malli schema form with clickable refs."
  [form]
  (cond
    ;; Registered schema ref - make clickable
    (and (keyword? form) (schema/registered? form))
    [:a {:href (str "/schemas/" (namespace form) "#" (name form))
         :class "text-signal hover:underline"}
     (str form)]

    ;; Plain keyword
    (keyword? form)
    [:span.text-purple-400 (str form)]

    ;; Vector (schema definition) - recurse
    (vector? form)
    [:span
     [:span.text-text-400 "["]
     (interpose " " (map render-schema-form form))
     [:span.text-text-400 "]"]]

    ;; Map (properties) - render as {:key val}
    (map? form)
    [:span
     [:span.text-text-400 "{"]
     (for [[k v] form]
       [:span (render-schema-form k) " " (render-schema-form v) " "])
     [:span.text-text-400 "}"]]

    ;; String
    (string? form)
    [:span.text-green-400 (pr-str form)]

    ;; Default
    :else
    [:span (pr-str form)]))

(defn schema-card [schema-key schema-def]
  [:div {:id (name schema-key)
         :class "p-3 bg-base-850 rounded border border-base-700"}
   [:div.font-mono.text-sm.text-signal (str schema-key)]
   [:pre.mt-2.text-xs.overflow-x-auto
    [:code (render-schema-form schema-def)]]])

```

#### 2.2 Add Anchor Links

Schema cards have `id` attributes matching the schema name. Links use `#name` fragments for in-page navigation.

#### Test Criteria

```
1. :seon.ai/session-id in a schema definition is a clickable link
2. Clicking navigates to /schemas/seon.ai#session-id
3. Browser scrolls to that schema card

```

---

### Phase 3: Schema Detail View

**Goal:** Full detail page for individual schemas.

**Duration:** 0.5 day

#### 3.1 Schema Detail Route

**Route:** `GET /schemas/:ns/:name`

#### 3.2 Detail Page Content

Show:

- Full schema definition
- **Where Used** - other schemas that reference this one
- **References** - schemas this one references
- **Description** - from schema properties if available

```clojure
(defn find-references
  "Find all schemas that reference the given schema key."
  [target-key]
  (let [all-schemas (schema/registered-schemas)]
    (->> all-schemas
         (filter (fn [[_ def]]
                   (contains-ref? def target-key)))
         (map key))))

(defn schema-detail [ns-name schema-name]
  (let [schema-key (keyword ns-name schema-name)
        schema-def (schema/schema-definition schema-key)
        references (find-references schema-key)]
    [:main
     (ui/page-header (str schema-key))

     ;; Definition
     (ui/section-header "DEFINITION")
     [:pre.p-3.bg-base-900.rounded.text-sm
      [:code (render-schema-form schema-def)]]

     ;; Description (if available)
     (when-let [desc (-> (m/schema schema-def) m/properties :description)]
       [:div
        (ui/section-header "DESCRIPTION")
        [:p.text-sm.text-text-200 desc]])

     ;; Where used
     (when (seq references)
       [:div
        (ui/section-header "USED BY")
        [:ul.space-y-1
         (for [ref references]
           [:li
            [:a {:href (str "/schemas/" (namespace ref) "/" (name ref))
                 :class "text-signal hover:underline text-sm"}
             (str ref)]])]])]))

```

#### Test Criteria

```
1. GET /schemas/seon.ai/session-id shows detail page
2. "Used By" section lists schemas that reference :seon.ai/session-id
3. Description from schema properties is displayed

```

---

## Code References

| File | Line | Purpose |
|------|------|---------|
| `src/seon/schema.clj` | 38 | `*schemas` atom - the source of truth |
| `src/seon/schema.clj` | 97-102 | `registered-schemas` - returns all schemas |
| `src/seon/schema.clj` | 116-128 | `schemas-in-namespace` - filter by ns |
| `src/seon/ai.clj` | 60-306 | Example schema registrations |
| `src/seon/dev/hook.clj` | 43-100 | Example schema registrations |

---

## Design Considerations

### Terminal Aesthetic

Follow `docs/prds/namespace-ui/design-system.md`:

- `bg-base-850` for cards
- `text-signal` (#f0b429) for clickable links
- `font-mono text-xs` for schema definitions
- Dense layout, minimal padding

### No SSE Needed

Schemas change only when code is reloaded. Static page is fine.

### Integration with Namespace Views

Future enhancement: Show schemas section in `/ns/{namespace}` views, linking to full schema browser.

---

## Success Criteria

1. **Phase 0:** `GET /api/schemas` returns grouped schema data
2. **Phase 1:** `/schemas` page lists namespaces, click navigates to namespace detail
3. **Phase 2:** Schema refs (`:seon.ai/session-id`) are clickable links
4. **Phase 3:** Individual schema pages show usage and cross-references

---

## Out of Scope

- Schema editing UI
- Schema generation playground
- Malli default schemas (`:string`, `:int`, etc.) - only show registered schemas
- Schema validation testing UI

---

## Future Enhancements

- **Search** - Filter schemas by name or content
- **Graph view** - Visualize schema dependencies
- **Inline expand** - Expand refs inline instead of navigating
- **Copy button** - Copy schema definition to clipboard
