---
type: research
status: draft
tags: [prd, research, web]
---
# Viewer Architecture Research

**Purpose:** Deep dive into Portal, Reveal, and XTDB Inspector to inform Seon's namespace UI design.

---

## Executive Summary

After analyzing Portal, Reveal, and XTDB Inspector source code, the following patterns emerge as most valuable for Seon:

### Key Patterns Worth Adopting

1. **Multimethod Dispatch (Reveal)** - Dispatch viewers by type using `defmethod`. Simple, extensible, JVM-native.

2. **Annotation Threading (Reveal)** - Pass navigation context (`::nav/coll`, `::nav/key`, `::nav/val`) alongside values for bidirectional traversal.

3. **Reverse Lookup Queries (XTDB Inspector)** - Query all entities where any attribute equals a given ID.

4. **IntersectionObserver Lazy Loading (Portal)** - Render placeholders until elements become visible, then hydrate.

5. **Watch-Based Live Updates (Portal/Reveal)** - Add watches to atoms/refs, debounce with 100ms timeout, send invalidation signals.

### Recommended Approach

**Build our own** viewer system using patterns from all three projects, purpose-built for SSE/Datastar. This provides:

- Clean integration with our stack
- No heavy dependencies
- Full control over rendering
- Server-side Hiccup (no browser JS framework)

---

## 1. Datafy/Nav Deep Dive

### How Portal Uses Datafy/Nav

Portal uses `clojure.datafy` and `clojure.core.protocols/nav` for interactive data exploration.

**Source:** `reference-code/portal/src/portal/runtime.cljc`

```clojure
;; Portal imports datafy/nav from standard clojure.datafy
#?(:default [clojure.datafy :refer [datafy nav]])

;; Registered as commands with keyboard shortcuts
(doseq [[var opts] {#'nav    {:name 'clojure.datafy/nav
                              :private true
                              :shortcuts [#{"enter"}]}
                    #'datafy {:name 'clojure.datafy/datafy
                              :shortcuts [#{"shift" "enter"}]}}]
  (register! var opts))

```

**Navigation Flow:**

1. User selects a value and presses Enter
2. Portal calls `(nav coll k v)` where:
   - `coll` = the parent collection
   - `k` = the key/index
   - `v` = the selected value
3. The return value replaces the current view

**Custom nav example** from `reference-code/portal/src/examples/default_visualizer.clj`:

```clojure
(defn nav-dep-anno-tree [coll _ v]
  (let [{:keys [deps-map]} (meta coll)]
    (with-meta
      [:div "Depends on "
       (str/join ", " (map str (get deps-map v)))]
      {:portal.viewer/default :portal.viewer/hiccup})))

;; Usage: attach nav implementation via metadata
(tap>
  (with-meta
    {:a 1 :b 2 :c 3}
    {`nav #'nav-dep-anno-tree
     :deps-map {:c #{:b :a}}}))

```

### How Reveal Uses Datafy/Nav

Reveal implements datafy/nav as "actions" that can be invoked on any value.

**Source:** `reference-code/reveal/src/vlaaad/reveal/action.clj`

```clojure
(defaction ::datafy [x]
  (let [d (d/datafy x)]
    (when-not (= d x)
      (constantly d))))

(defaction ::nav [x {:vlaaad.reveal.nav/keys [coll key val]
                     :or {key ::not-found
                          val ::not-found}}]
  (let [datafied-coll (d/datafy coll)]
    (when (= datafied-coll coll)
      (cond
        (not= key ::not-found) #(d/nav datafied-coll key x)
        (not= val ::not-found) #(d/nav datafied-coll x val)))))

```

**Key Insight:** Reveal threads navigation context via "annotations" alongside values:

```clojure
;; From stream.clj - threading nav context through rendering
(defn entries [m ann]
  (block :vertical
    (streamduce
      (map (fn [e]
             (let [k (key e)
                   v (val e)]
               ;; Thread nav context for keys
               (stream k (assoc ann :vlaaad.reveal.nav/val v
                                    :vlaaad.reveal.nav/coll m))
               ;; Thread nav context for values
               (stream v (assoc ann :vlaaad.reveal.nav/key k
                                    :vlaaad.reveal.nav/coll m)))))
      m)))

```

### How We Could Use Datafy for Seon

**Namespace Introspection:**

```clojure
(extend-protocol clojure.core.protocols/Datafiable
  clojure.lang.Namespace
  (datafy [ns]
    {:name (ns-name ns)
     :doc (-> ns meta :doc)
     :aliases (ns-aliases ns)
     :publics (into {} (map (fn [[k v]]
                               [k (with-meta {:var v}
                                    {`nav (fn [_ _ _] @v)})])
                            (ns-publics ns)))
     :refers (ns-refers ns)
     :imports (ns-imports ns)}))

```

**XTDB Entity Navigation:**

```clojure
(defn entity-datafy [db entity-id]
  (let [entity (xt/entity db entity-id)]
    (with-meta
      entity
      {`nav (fn [coll k v]
              ;; If v looks like an entity ID, navigate to it
              (if (entity-id? db v)
                (entity-datafy db v)
                v))})))

```

**Malli Schema Navigation:**

```clojure
(defn schema-datafy [schema]
  (let [form (m/form schema)]
    (with-meta
      {:form form
       :type (m/type schema)
       :properties (m/properties schema)
       :children (m/children schema)}
      {`nav (fn [_ k v]
              (cond
                ;; Navigate to child schemas
                (and (= k :children) (m/schema? v))
                (schema-datafy v)
                ;; Navigate to referenced schemas
                (keyword? v)
                (when-let [s (m/deref (m/schema v))]
                  (schema-datafy s))
                :else v))})))

```

---

## 2. Bidirectional Reference Traversal

### How XTDB Inspector Does Reverse Lookups

**Source:** `reference-code/xtdb-inspector/src/xtdb_inspector/page/doc.clj`

```clojure
(defn links-to [xtdb id]
  (let [attrs
        ;; Get all attributes in the database
        (disj (into #{}
                    (map key)
                    (xt/attribute-stats xtdb))
              :xt/id)]
    (with-open [db (xt/open-db xtdb)]
      (into []
            (mapcat
             (fn [attr]
               ;; Query: find all entities where this attr = our id
               (for [[from]
                     (xt/q db {:find ['?e]
                               :where [['?e attr 'id]]
                               :in ['id]} id)]
                 [attr from])))
            attrs))))

```

**Rendered as:**

```clojure
(defn render-links-to [links]
  (h/html
   [:div
    [:table.font-mono {:class "w-9/12"}
     [:thead
      [:tr
       [:td "Attribute"]
       [:td "Document"]]]
     [:tbody
      [::h/for [[attr from] links]
       (attr-val-row (pr-str attr)
                     #(format-value (constantly true) from))]]]]))

```

### XTDB v2 Approach for Seon

XTDB v2 uses SQL. Here's how we'd implement bidirectional traversal:

```clojure
(defn references-to
  "Find all entities that reference the given entity ID.

   Returns: [{:table \"ai_messages\"
              :attribute :seon.ai/session-id
              :entity-id \"msg-123\"}]"
  [node target-id]
  (let [tables (db/list-tables node)]
    (->> tables
         (mapcat (fn [table]
                   (let [;; Get sample row to find columns
                         sample (first (db/q node (str "SELECT * FROM " table " LIMIT 1")))
                         columns (keys sample)]
                     (->> columns
                          (filter #(not= % :xt/id))
                          (mapcat (fn [col]
                                    (let [col-str (db/keyword->column col)
                                          results (db/q node
                                                    (str "SELECT xt$id FROM " table
                                                         " WHERE " col-str " = ?")
                                                    [target-id])]
                                      (map (fn [row]
                                             {:table table
                                              :attribute col
                                              :entity-id (:xt/id row)})
                                           results))))))))
         (into []))))

;; Optimized version using prepared queries
(defn references-to-fast
  "Faster version that queries known reference columns only."
  [node target-id ref-columns]
  ;; ref-columns: {\"ai_messages\" [:seon.ai/session-id]
  ;;               \"ai_sessions\" [:seon.ai/parent-session]}
  (->> ref-columns
       (mapcat (fn [[table cols]]
                 (map (fn [col]
                        (let [results (db/q node
                                        (str "SELECT xt$id FROM " table
                                             " WHERE " (db/keyword->column col) " = ?")
                                        [target-id])]
                          {:table table
                           :attribute col
                           :entities (mapv :xt/id results)}))
                      cols)))
       (filter #(seq (:entities %)))
       (into [])))

```

### Performance Considerations

1. **Index Support:** XTDB v2 indexes all columns by default, so reverse lookups are O(log n).

2. **Caching:** Cache `attribute-stats` equivalent and known reference columns.

3. **Lazy Loading:** Show "N references" count first, load details on expand.

4. **Schema-Driven:** Use Malli schemas to identify reference columns (those with `::id` suffix patterns).

---

## 3. Viewer Dispatch Comparison

| Project | Dispatch Mechanism | Extensibility | State Management |
|---------|-------------------|---------------|------------------|
| **Portal** | Type-based + predicate viewers | `api/viewers` atom, register via predicate | Reagent atoms + React context |
| **Reveal** | Multimethod on class/type | `defstream` macro for new types | cljfx state map + event handlers |
| **Clerk** | Predicate-based viewer list | `with-viewer` metadata | SCI + Reagent (browser-side) |
| **XTDB Inspector** | Multimethod on class | `defmethod render Type` | Ripley live sources |

### Portal's Approach

**Source:** `reference-code/portal/src/portal/ui/inspector.cljs`

```clojure
;; Type detection
(defn get-value-type [value]
  (cond
    (tagged-literal? value) :tagged
    (cson/tagged-value? value) (:tag value)
    (long? value)     :number
    (bin? value)      :binary
    (map? value)      :map
    (set? value)      :set
    (vector? value)   :vector
    ;; ... more types
    ))

;; Component lookup by type
(defn- get-inspect-component [type]
  (case type
    (:set :vector :list :coll) inspect-coll
    :map        inspect-map
    :boolean    inspect-boolean
    :string     inspect-string
    ;; ... etc
    inspect-object))  ;; default

;; Viewer selection (viewer atom + predicates)
(defn- get-compatible-viewers-1 [viewers {:keys [value] :as context}]
  (let [by-name        (viewers-by-name viewers)
        default-viewer (get by-name
                            (or (get-in (meta context) [:props :portal.viewer/default])
                                (:portal.viewer/default (meta value))
                                (:portal.viewer/default context)))]
    (filter #(when-let [pred (:predicate %)] (pred value)) viewers)))

```

### Reveal's Approach

**Source:** `reference-code/reveal/src/vlaaad/reveal/stream.clj`

```clojure
;; Multimethod dispatch on class or ::type metadata
(defmulti stream-dispatch (fn [x _]
                            (or (::type (meta x)) (class x))))

;; Macro for defining streamers
(defmacro defstream [dispatch-val bindings sf]
  (let [[x ann] (cond-> bindings (= 1 (count bindings)) (conj (gensym "ann")))]
    `(defmethod stream-dispatch ~dispatch-val [x# ann#]
       (let [~x x#
             ~ann ann#]
         (with-value x# ann# ~sf)))))

;; Usage examples
(defstream nil [x]
  (raw-string (pr-str x) {:fill :scalar}))

(defstream IPersistentMap [m]
  (horizontal
    (raw-string "{" {:fill :object})
    (entries m)
    (raw-string "}" {:fill :object})))

(defstream IRef [*ref]
  (horizontal
    (raw-string "(" {:fill :util})
    (raw-string (.toLowerCase (.getSimpleName (class *ref))) {:fill :object})
    separator
    (stream @*ref)
    (raw-string ")" {:fill :util})))

```

### XTDB Inspector's Approach

**Source:** `reference-code/xtdb-inspector/src/xtdb_inspector/ui/edn.clj`

```clojure
;; Simple multimethod on type
(defmulti render (fn [_ctx item] (type item)))

(defmethod render :default [_ item]
  (let [str (pr-str item)]
    (h/html [:span str])))

(defmethod render java.lang.String [_ctx str]
  (h/html [:span.text-lime-500 "\"" str "\""]))

(defmethod render clojure.lang.Keyword [_ctx kw]
  (h/html [:span.text-emerald-700 (pr-str kw)]))

(defmethod render clojure.lang.IPersistentMap [ctx m]
  (if (empty? m)
    (h/html [:div.inline-block "{}"])
    (h/html
     [:div.inline-block.flex
      "{"
      [:table
       [::h/for [[key val] (seq m)]
        [:tr
         [:td (render ctx key)]
         [:td (render ctx val)]]]]
      "}"])))

```

### Recommended Approach for Seon

Use Reveal's multimethod pattern - it's the most Clojure-idiomatic and works server-side:

```clojure
(ns seon.ui.viewer
  "Server-side viewer dispatch for namespace introspection.")

;; Dispatch multimethod
(defmulti render-value
  "Render a value to Hiccup. Dispatch on type or ::viewer metadata."
  (fn [value _opts]
    (or (::viewer (meta value))
        (type value))))

;; Default fallback
(defmethod render-value :default [value _opts]
  [:code.text-gray-500 (pr-str value)])

;; Primitives
(defmethod render-value nil [_ _]
  [:span.text-gray-400 "nil"])

(defmethod render-value java.lang.Boolean [value _]
  [:span.text-blue-600 (str value)])

(defmethod render-value java.lang.Number [value _]
  [:span.text-green-600 (str value)])

(defmethod render-value java.lang.String [value opts]
  (let [{:keys [max-length] :or {max-length 100}} opts]
    [:span.text-amber-600
     (if (> (count value) max-length)
       (str "\"" (subs value 0 max-length) "...\"")
       (pr-str value))]))

(defmethod render-value clojure.lang.Keyword [value _]
  [:span.text-purple-600 (str value)])

(defmethod render-value clojure.lang.Symbol [value _]
  [:span.text-pink-600 (str value)])

;; Collections with expand/collapse via Datastar
(defmethod render-value clojure.lang.IPersistentMap [m opts]
  (let [id (gensym "map")]
    [:div {:data-signals (str "{" id "_expanded: false}")}
     [:span.cursor-pointer
      {:data-on-click (str "$" id "_expanded = !$" id "_expanded")}
      "{"]
     [:span {:data-show (str "!$" id "_expanded")}
      (when (> (count m) 3)
        [:span.text-gray-400 (str (count m) " entries")])]
     [:div.pl-4 {:data-show (str "$" id "_expanded")}
      (for [[k v] m]
        [:div.flex.gap-2
         [render-value k opts]
         [render-value v opts]])]
     "}"]))

;; Custom viewers via metadata
(defmethod render-value ::function [value opts]
  (render-function-card value opts))

(defmethod render-value ::schema [value opts]
  (render-malli-schema value opts))

```

---

## 4. Atom/Ref Live Updates

### Portal's Watch Mechanism

**Source:** `reference-code/portal/src/portal/runtime.cljc`

```clojure
(defn- atom? [o]
  #?(:clj  (instance? clojure.lang.Atom o)
     :cljs (satisfies? cljs.core/IAtom o)))

(defn- notify [session-id a]
  (when-let [request @request]
    (request session-id {:op :portal.rpc/invalidate :atom a})))

(defn- invalidate [session-id a old new]
  ;; Only invalidate if values actually differ
  (when (or (not= old new)
            (not= (value->key old) (value->key new)))
    ;; Debounce: wait 100ms, only notify if value hasn't changed again
    (set-timeout
     #(when (identical? @a new) (notify session-id a))
     100)))

(defn- watch-atom [a]
  (let [{:keys [session-id watch-registry]} *session*]
    (when-not (contains? @watch-registry a)
      (swap!
       watch-registry
       (fn [atoms]
         (if (contains? atoms a)
           atoms
           (do
             (add-watch a session-id #'invalidate)
             (conj atoms a))))))))

```

### Reveal's Watch Mechanism

**Source:** `reference-code/reveal/src/vlaaad/reveal/view.clj`

```clojure
(defn- watch! [id *ref handler]
  (handler {::event/type ::create-view-state :id id :state (output-panel/make {:autoscroll false})})
  (let [*running (volatile! true)
        out-queue (ArrayBlockingQueue. 1024)
        submit! #(.put out-queue ({nil ::nil} % %))
        watch-key (gensym "vlaaad.reveal.view/watcher")
        f (event/daemon-future
            (while @*running
              ;; Coalesce rapid updates - only take latest value
              (when-some [x (loop [x (.poll out-queue 1 TimeUnit/SECONDS)
                                   found nil]
                              (if (some? x)
                                (recur (.poll out-queue) x)  ;; Keep polling, take latest
                                found))]
                (handler {::event/type ::output-panel/on-clear-lines :id id})
                ;; Re-render with new value
                (runduce! (comp stream/stream-xf ...)
                          #(handler {::event/type ::output-panel/on-add-lines ...})
                          ({::nil nil} x x)))))]
    ;; Initial value
    (submit! @*ref)
    ;; Watch for changes
    (add-watch *ref watch-key #(submit! %4))
    ;; Cleanup function
    #(do
       (remove-watch *ref watch-key)
       (vreset! *running false)
       (future-cancel f)
       (handler {::event/type ::dispose-state :id id}))))

```

### SSE/Datastar Pattern for Seon

```clojure
(ns seon.ui.live
  "Live updates for atoms/refs via SSE."
  (:require [seon.web.sse :as sse]))

(defonce ^:private watch-registry (atom {}))

(defn watch-atom!
  "Start watching an atom and push updates via SSE.
   Returns a cleanup function."
  [session-id atom-ref selector]
  (let [watch-key (keyword (str "seon.ui.live/" session-id))
        last-sent (atom nil)
        send-update! (fn [new-val]
                       ;; Debounce: only send if value actually changed
                       (when (not= @last-sent new-val)
                         (reset! last-sent new-val)
                         (sse/merge-fragment session-id selector
                           (render-value new-val {}))))]
    ;; Send initial value
    (send-update! @atom-ref)
    ;; Watch for changes with debounce
    (add-watch atom-ref watch-key
      (fn [_ _ _ new-val]
        ;; 100ms debounce via core.async or scheduled executor
        (future
          (Thread/sleep 100)
          (when (= @atom-ref new-val)
            (send-update! new-val)))))
    ;; Register for cleanup
    (swap! watch-registry assoc-in [session-id atom-ref] watch-key)
    ;; Return cleanup function
    (fn []
      (remove-watch atom-ref watch-key)
      (swap! watch-registry update session-id dissoc atom-ref))))

(defn stop-all-watches!
  "Stop all watches for a session."
  [session-id]
  (doseq [[atom-ref watch-key] (get @watch-registry session-id)]
    (remove-watch atom-ref watch-key))
  (swap! watch-registry dissoc session-id))

```

**Datastar Integration:**

```clojure
;; In the HTML template
(defn atom-viewer [atom-ref opts]
  (let [id (str "atom-" (hash atom-ref))]
    [:div {:id id
           :data-on-load (str "$$get('/api/watch?atom=" (pr-str atom-ref) "')")}
     [:div.flex.items-center.gap-2
      [:span.text-gray-500 "atom"]
      [:div {:id (str id "-value")}
       [render-value @atom-ref opts]]
      [:span.text-xs.text-green-500 {:data-indicator ""} "live"]]]))

;; SSE endpoint
(defn handle-watch [request]
  (let [atom-ref (-> request :params :atom read-string resolve deref)
        session-id (-> request :session :id)]
    (sse/streaming-response request
      (watch-atom! session-id atom-ref (str "#atom-" (hash atom-ref) "-value")))))

```

---

## 5. Large Data Handling

### Portal's Lazy Sequence Pattern

**Source:** `reference-code/portal/src/portal/ui/lazy.cljs`

```clojure
(defn- observer-visible? [entries]
  (< 0.5 (reduce
          (fn [sum entry]
            (if-not (.-isIntersecting entry)
              sum
              (+ sum (.-intersectionRatio entry)))) 0 entries)))

(defn- observer-visible-sensor [f]
  (let [ref (react/use-ref)]
    (react/use-effect
     #js [(.-current ref) f]
     (when (.-current ref)
       (let [observer
             (js/IntersectionObserver.
              (fn [entries]
                (when (observer-visible? entries) (f)))
              #js {:root nil :rootMargin "0px" :threshold 0.5})]
         (.observe observer (.-current ref))
         (fn [] (.unobserve observer (.-current ref))))))
    [:div {:ref ref :style {:height "0.5em" :width "0.5em"}}]))

(defn lazy-seq [_coll opts]
  (let [{:keys [default-take step]
         :or   {default-take 0 step 10}} opts
        n     (r/atom default-take)]
    (fn [coll _opts]
      (let [[head tail] (split-at (or @n default-take) coll)]
        [:<>
         head
         (when (seq tail)
           [visible-sensor
            (fn [] (swap! n (fnil + default-take) step))])]))))

```

### Reveal's Collection Rendering

Reveal uses a simpler approach - render with preference for horizontal layout unless items are complex:

```clojure
(defn horizontal-item? [x]
  (cond
    (coll? x) false
    (nil? x) true
    (boolean? x) true
    (number? x) true
    (char? x) true
    (or (string? x)
        (keyword? x)
        (symbol? x)) (not (sf-wider-than? (stream x) slim-value-character-limit))
    :else false))

(defn horizontal-coll? [coll]
  (every? horizontal-item? coll))

(defn items [coll ann]
  (if (horizontal-coll? coll)
    (horizontally coll ann)
    (vertically coll ann)))

```

### Server-Side Pagination for Seon

Since we render server-side, we use HTTP pagination rather than JS virtualization:

```clojure
(ns seon.ui.pagination
  "Server-side pagination for large collections.")

(def ^:private default-page-size 20)

(defn paginated-collection
  "Render a collection with pagination.

   Options:
     :page-size - items per page (default 20)
     :page - current page (0-indexed)
     :path - URL path for pagination links"
  [coll {:keys [page-size page path]
         :or {page-size default-page-size page 0}}]
  (let [total (count coll)
        total-pages (Math/ceil (/ total page-size))
        start (* page page-size)
        end (min (+ start page-size) total)
        visible (subvec (vec coll) start end)]
    [:div
     ;; Header with count
     [:div.flex.justify-between.items-center.mb-2
      [:span.text-gray-500 (str total " items")]
      (when (> total-pages 1)
        [:span.text-gray-500 (str "Page " (inc page) " of " total-pages)])]

     ;; Items
     [:div.space-y-1
      (for [[idx item] (map-indexed vector visible)]
        ^{:key idx}
        [:div.flex.gap-2
         [:span.text-gray-400.w-8 (+ start idx)]
         [render-value item {}]])]

     ;; Pagination controls
     (when (> total-pages 1)
       [:div.flex.gap-2.mt-4
        (when (> page 0)
          [:button {:data-on-click (str "$$get('" path "?page=" (dec page) "')")}
           "Previous"])
        (when (< (inc page) total-pages)
          [:button {:data-on-click (str "$$get('" path "?page=" (inc page) "')")}
           "Next"])])]))

(defn truncated-preview
  "Show a truncated preview with 'show more' link."
  [coll {:keys [preview-count path] :or {preview-count 5}}]
  (let [total (count coll)
        preview (take preview-count coll)]
    [:div
     [:div.space-y-1
      (for [[idx item] (map-indexed vector preview)]
        ^{:key idx}
        [render-value item {}])]
     (when (> total preview-count)
       [:button.text-blue-500.text-sm
        {:data-on-click (str "$$get('" path "')")}
        (str "Show all " total " items...")])]))

```

### Datastar Infinite Scroll Pattern

```clojure
(defn infinite-scroll-collection
  "Render collection with infinite scroll using Datastar."
  [coll {:keys [page-size path] :or {page-size 20}}]
  (let [id (gensym "scroll")]
    [:div {:id id
           :data-signals (str "{" id "_loaded: " page-size "}")}
     ;; Initial items
     [:div {:id (str id "-items")}
      (for [[idx item] (map-indexed vector (take page-size coll))]
        ^{:key idx}
        [:div [render-value item {}]])]

     ;; Load more trigger (appears when scrolled into view)
     (when (> (count coll) page-size)
       [:div {:data-intersect (str "$$get('" path "?offset=' + $" id "_loaded + '&limit=" page-size "')"
                                   ".then(() => $" id "_loaded += " page-size ")")
              :data-show (str "$" id "_loaded < " (count coll))}
        [:div.h-8.flex.items-center.justify-center
         [:span.text-gray-400 "Loading..."]]])]))

```

---

## 6. Code Examples: Key Files to Reference

### Portal

| File | What to Study |
|------|---------------|
| `portal/runtime.cljc` | Watch mechanism, datafy/nav registration, value caching |
| `portal/ui/inspector.cljs` | Type dispatch, viewer selection, expand/collapse |
| `portal/ui/lazy.cljs` | IntersectionObserver pattern, lazy-seq component |
| `portal/viewer.cljc` | Viewer API, metadata-based viewer selection |

### Reveal

| File | What to Study |
|------|---------------|
| `vlaaad/reveal/stream.clj` | Multimethod dispatch, annotation threading |
| `vlaaad/reveal/action.clj` | Action registry, datafy/nav as actions |
| `vlaaad/reveal/view.clj` | Atom watching, deref views, tree views |

### XTDB Inspector

| File | What to Study |
|------|---------------|
| `xtdb_inspector/page/doc.clj` | Reverse lookup queries, entity history |
| `xtdb_inspector/ui/edn.clj` | Simple multimethod renderer |
| `xtdb_inspector/core.clj` | Route structure, context passing |

---

## 7. Licensing

All three projects use permissive licenses:

| Project | License | Notes |
|---------|---------|-------|
| Portal | MIT | Can copy code freely |
| Reveal | MIT | Can copy code freely |
| XTDB Inspector | MIT | Can copy code freely |

No licensing concerns for adopting patterns or copying code snippets.

---

## 8. Incremental Implementation Plan

### Phase 1: Basic Viewer System (2-3 days)

**Goal:** Render any Clojure value as styled Hiccup.

**Tasks:**

1. Create `seon.ui.viewer` namespace with multimethod dispatch
2. Implement viewers for primitives: nil, boolean, number, string, keyword, symbol
3. Implement viewers for collections: map, vector, set, list
4. Add basic syntax highlighting via Tailwind classes
5. Test with namespace introspection data

**No external dependencies.** Pure Clojure + Hiccup.

```clojure
;; Phase 1 deliverable
(render-value {:name "test" :count 42 :tags [:a :b]})
;; => [:div ...]  ;; styled Hiccup

```

### Phase 2: Expand/Collapse with Datastar (2 days)

**Goal:** Collections can be expanded/collapsed client-side.

**Tasks:**

1. Add `data-signals` for expand state
2. Add `data-show` for conditional rendering
3. Implement truncation with "show more" for large collections
4. Add click handlers for expand/collapse

**Depends on:** Datastar already in project.

```clojure
;; Phase 2 deliverable - map with expand/collapse
[:div {:data-signals "{map_1_expanded: false}"}
 [:span {:data-on-click "$map_1_expanded = !$map_1_expanded"} "{"]
 [:span {:data-show "!$map_1_expanded"} "3 entries"]
 [:div {:data-show "$map_1_expanded"} ...]
 "}"]

```

### Phase 3: XTDB Entity Viewer (2-3 days)

**Goal:** View XTDB entities with forward references as clickable links.

**Tasks:**

1. Detect entity IDs (convention: keywords ending in `-id` or strings starting with known prefixes)
2. Render entity IDs as links to entity view
3. Add entity history view (using XTDB temporal queries)
4. Integrate with existing `/db` browser

```clojure
;; Phase 3 deliverable
(render-entity db {:xt/id "ses-abc123"
                   :seon.ai/namespace "seon.trading"
                   :seon.ai/status :active})
;; => entity card with clickable session-id link

```

### Phase 4: Bidirectional References (2 days)

**Goal:** Show "what references this entity" in addition to forward refs.

**Tasks:**

1. Implement `references-to` query function
2. Add "Referenced by" section to entity viewer
3. Cache known reference columns for performance
4. Add lazy loading for large reference counts

```clojure
;; Phase 4 deliverable - entity view with reverse refs
[:div.entity-view
 [:h3 "Entity: ses-abc123"]
 [:div.forward-refs ...]
 [:div.reverse-refs
  [:h4 "Referenced by"]
  [:ul
   [:li "47 messages in ai_messages"]
   [:li "3 tool uses in ai_tool_uses"]]]]

```

### Phase 5: Live Atom Updates (2-3 days)

**Goal:** Atoms update in real-time via SSE.

**Tasks:**

1. Implement watch registry with debouncing
2. Create SSE endpoint for atom watching
3. Add Datastar integration for live updates
4. Handle watch cleanup on disconnect
5. Add visual indicator for "live" values

```clojure
;; Phase 5 deliverable - live atom viewer
[:div.atom-viewer
 [:span "agent-registry"]
 [:span.live-indicator "live"]
 [:div#atom-value
  ;; Updated via SSE when atom changes
  ]]

```

### Phase 6: Custom Namespace Renderers (2-3 days)

**Goal:** Namespaces can provide custom rendering.

**Tasks:**

1. Define render-fn protocol/convention
2. Look up `:seon.ui/render-fn` in namespace ctx
3. Support view modes: `:tile`, `:half`, `:full`
4. Document how to write custom renderers

```clojure
;; Phase 6 deliverable - custom renderer support
(defn render-namespace [ns-sym session-id]
  (if-let [render-fn (get-custom-renderer ns-sym)]
    (render-fn {:view-mode :full :session-id session-id})
    (default-namespace-view ns-sym)))

```

### Phase 7: Malli Schema Viewer (1-2 days)

**Goal:** Render Malli schemas with clickable references.

**Tasks:**

1. Create schema-specific viewer
2. Link referenced schemas (click to navigate)
3. Show schema properties and constraints
4. Generate example values on demand

```clojure
;; Phase 7 deliverable
(render-schema ::ai/message-entity)
;; => schema card with tree view, clickable refs

```

---

## Summary

The research reveals clear patterns for each concern:

| Concern | Pattern | Source |
|---------|---------|--------|
| **Viewer dispatch** | Multimethod on type | Reveal |
| **Navigation context** | Thread annotations with values | Reveal |
| **Reverse lookups** | Query all attrs for ID matches | XTDB Inspector |
| **Lazy loading** | IntersectionObserver + pagination | Portal |
| **Live updates** | add-watch + debounce + SSE | Portal/Reveal |
| **Extensibility** | Metadata-based viewer override | Portal/Clerk |

Building our own viewer system is the right choice because:

1. Our stack (SSE/Datastar/Hiccup) differs from all three projects
2. Our needs (namespace introspection) are specific
3. The patterns are straightforward to implement
4. We avoid heavy dependencies

Estimated total effort: **12-18 days** for all 7 phases.
