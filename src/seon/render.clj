(ns seon.render
  "Multi-format rendering with Datalevin-based renderer resolution.

   Two dispatch paths:
   1. Metadata-based: Values with `:seon/schema` metadata use the typed dispatch
   2. Datalevin-based: Plain data maps are matched to render functions by key shape

   Core concepts:
   - Values carry `:seon/schema` metadata identifying their type
   - Render functions live in `.render` companion namespaces
   - Scanner discovers render functions by naming convention (input/output specs)
   - Resolution is cached and invalidated when the code graph updates

   Usage:
     (require '[seon.render :as render])

     ;; Attach schema type to a value
     (def pos (render/typed :trading/position {:ticker \"AAPL\" :quantity 100}))

     ;; Render for different contexts
     (render/render pos :ai)    ; => \"AAPL x100\"
     (render/render pos :html)  ; => [:div.position ...]
     (render/render pos :human) ; => formatted pretty-print

     ;; For AI agents in nREPL - recursively renders nested structures
     (render/for-ai {:positions [pos pos2] :total 25000.0})"
  (:require [clojure.pprint :as pp]
            [clojure.set :as cset]
            [clojure.string :as str]
            [integrant.repl.state]
            [seon.db.datalevin.conn :as dl-conn]
            [seon.graph.query :as gq]
            [seon.runtime :as runtime]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:refer-clojure :exclude [format]))

;;; ---------------------------------------------------------------------------
;;; Specs
;;; ---------------------------------------------------------------------------

(schema/register! ::html :any)
(schema/register! ::ai :string)
(schema/register! ::ns-vars :map)
(schema/register! ::format [:enum :html :ai :raw])
(schema/register! ::ns-data [:map
                             [::ns-vars {:optional true} ::ns-vars]
                             [::format {:optional true} ::format]])

;;; ---------------------------------------------------------------------------
;;; Datalevin Connection
;;; ---------------------------------------------------------------------------

(defonce ^:private *conn-override (atom nil))

(defn- get-conn
  "Get Datalevin connection. Checks override first (for tests),
   then gets from connection manager (handles staleness/auto-reconnect)."
  []
  (or @*conn-override
      (when-let [mgr (:seon.db.datalevin/connections integrant.repl.state/system)]
        (try
          (dl-conn/get-conn! {::dl-conn/manager mgr
                             ::dl-conn/db :seon.runtime
                             ::dl-conn/schema (runtime/runtime-merged-schema)})
          (catch Exception _ nil)))))

;;; ---------------------------------------------------------------------------
;;; Resolution Cache
;;; ---------------------------------------------------------------------------

(defonce ^:private resolution-cache (atom {}))

(defn invalidate-render-cache!
  "Clear the renderer resolution cache and output-key query cache.
   Called by the scanner after code graph updates."
  []
  (reset! resolution-cache {})
  (gq/invalidate-output-key-cache!))

(defn set-conn!
  "Override the Datalevin connection for renderer resolution.
   Invalidates the render cache. Use only in tests."
  [conn]
  (reset! *conn-override conn)
  (invalidate-render-cache!))

;;; ---------------------------------------------------------------------------
;;; Value Typing
;;; ---------------------------------------------------------------------------

(defn typed
  "Attach schema type as metadata to a value.

   The value must support metadata (maps, vectors, lists, etc.).

   Arguments:
     schema-key - Fully-namespaced keyword identifying the schema
     value      - The value to attach metadata to

   Returns:
     The value with `:seon/schema` metadata attached.

   Example:
     (typed :trading/position {:ticker \"AAPL\" :quantity 100})"
  [schema-key value]
  (with-meta value {:seon/schema schema-key}))

(defn schema-of
  "Get the schema type from a value's metadata.

   Returns nil if no schema type is attached."
  [value]
  (when (instance? clojure.lang.IMeta value)
    (:seon/schema (meta value))))

;;; ---------------------------------------------------------------------------
;;; Datalevin-Based Renderer Resolution
;;; ---------------------------------------------------------------------------

(defn namespace-proximity
  "Score namespace proximity for tiebreaking.
   Same ns = 0 (best), .render child = 1, sibling = 2, distant = 3.

   Takes a qualified function name (e.g. \"seon.foo/bar\") and a target
   namespace string. Extracts the namespace from the qualified name."
  [renderer-qname target-ns]
  (let [renderer-ns (when renderer-qname
                      (first (str/split renderer-qname #"/")))]
    (cond
      (= renderer-ns target-ns) 0
      (and renderer-ns target-ns
           (str/starts-with? renderer-ns (str target-ns "."))) 1
      (and renderer-ns target-ns
           (let [target-parent (when (str/includes? target-ns ".")
                                 (subs target-ns 0 (str/last-index-of target-ns ".")))
                 renderer-parent (when (str/includes? renderer-ns ".")
                                   (subs renderer-ns 0 (str/last-index-of renderer-ns ".")))]
             (and target-parent renderer-parent (= target-parent renderer-parent)))) 2
      :else 3)))

(defn find-renderer
  "Find the best render function for the given data and format.

   Uses functions-with-output-key to find candidates via ref join,
   then filters by required keys subset of data keys.

   Resolution order:
   1. Most required keys matched (specificity)
   2. Newest updated-at (recency)
   3. Alphabetical qualified-name (deterministic tiebreaker)

   Arguments:
     db-name - Database name keyword (e.g. :seon.runtime)
     data    - Map of data to render
     format  - :html or :ai

   Returns the qualified-name string of the best renderer, or nil."
  [db-name data format]
  (let [data-keys (set (keys data))
        format-key (case format :html :seon.render/html :ai :seon.render/ai)
        ;; Use unified helper to find candidates via ref join
        candidates (gq/functions-with-output-key {::gq/db-name db-name ::gq/output-key format-key})
        ;; Filter: required keys must be subset of data keys
        matching (->> candidates
                      (filter (fn [e]
                                (let [rkeys (:required-keys e)]
                                  (every? data-keys rkeys)))))]
    (when (seq matching)
      (->> matching
           (sort-by (juxt (comp - count :required-keys)
                          (comp - (fn [e] (if-let [t (:seon.fn/updated-at e)]
                                            (.getTime ^java.util.Date t)
                                            0)))
                          :seon.fn/qualified-name))
           first
           :seon.fn/qualified-name))))

;;; ---------------------------------------------------------------------------
;;; Web Parameter Namespacing
;;; ---------------------------------------------------------------------------

(def ^:private system-params
  "Query params reserved by the system, never injected as namespace data."
  #{"instance" "format" "view"})

(defn namespace-web-params
  "Auto-namespace query params under a target namespace.
   ?sort-by=weight on /ns/seon.health.workout
   => {:seon.health.workout/sort-by \"weight\"}

   System-reserved params (instance, format, view) are excluded.

   Arguments:
     params - Map of string key -> string value (from query string)
     ns-str - Target namespace string

   Returns map of namespaced keyword -> string value."
  [params ns-str]
  (when (seq params)
    (into {}
          (comp
           (remove (fn [[k _]] (system-params k)))
           (map (fn [[k v]] [(keyword ns-str k) v])))
          params)))

;;; ---------------------------------------------------------------------------
;;; Specificity-Based Renderer Resolution (PRD algorithm)
;;; ---------------------------------------------------------------------------

(defn resolve-renderer
  "Find the best renderer for the given available keys.

   This is the specificity-based resolution algorithm:
   1. Find all functions with :seon.render/html in output spec
   2. Filter: ALL required input keys must be present in available-keys
   3. Rank: most required keys wins (more specific = better)
   4. Tiebreak: namespace proximity (same ns > .render child > sibling)

   Arguments:
     db-name        - Database name keyword (e.g. :seon.runtime)
     available-keys - Set of available data keys
     target-ns      - Target namespace string (for proximity tiebreaking)

   Returns the resolved var, or nil."
  [db-name available-keys target-ns]
  (let [;; Use unified helper to find candidates via ref join
        candidates (gq/functions-with-output-key {::gq/db-name db-name ::gq/output-key :seon.render/html})
        ;; Filter: required keys must be subset of available-keys
        matching (->> candidates
                      (filter (fn [e]
                                (cset/subset? (:required-keys e) available-keys))))]
    (when (seq matching)
      (let [best (->> matching
                      (sort-by (juxt (comp - count :required-keys)
                                     (fn [e] (namespace-proximity
                                              (:seon.fn/qualified-name e)
                                              target-ns))
                                     :seon.fn/qualified-name))
                      first)
            qname (:seon.fn/qualified-name best)]
        (try
          (requiring-resolve (symbol qname))
          (catch Exception e
            (log/warn "Failed to resolve renderer" {:fn qname :error (.getMessage e)})
            nil))))))

;;; ---------------------------------------------------------------------------
;;; Cached Resolution + Requiring-Resolve
;;; ---------------------------------------------------------------------------

(defn- pprint-clipped
  "Pretty-print truncated to max-chars (default 500)."
  ([v] (pprint-clipped v 500))
  ([v max-chars]
   (let [s (with-out-str (pp/pprint v))]
     (if (> (count s) max-chars)
       (str (subs s 0 max-chars) "...")
       s))))

(defn- resolve-renderer-from-datalevin
  "Look up a render function from Datalevin, using cache.
   Returns the resolved var or ::no-renderer."
  [data format]
  (let [cache-key [format (set (keys data))]
        cached (get @resolution-cache cache-key ::miss)]
    (if (not= cached ::miss)
      cached
      (try
        (let [result (if-let [qn (find-renderer :seon.runtime data format)]
                       (or (requiring-resolve (symbol qn)) ::no-renderer)
                       ::no-renderer)]
          (swap! resolution-cache assoc cache-key result)
          result)
        (catch Exception _
          ;; Conn may be closed/suspended (e.g. during system reload or tests).
          ;; Don't cache — let next call retry with a fresh conn.
          ::no-renderer)))))

(defn- call-datalevin-renderer
  "Try to render data via Datalevin-discovered render function.
   Returns rendered value for the format, or nil if no renderer found."
  [data format]
  (let [resolved (resolve-renderer-from-datalevin data format)]
    (when (not= resolved ::no-renderer)
      (let [result (resolved data)
            format-key (case format :html :seon.render/html :ai :seon.render/ai)]
        (get result format-key)))))

;;; ---------------------------------------------------------------------------
;;; Public API: try-render and has-renderer?
;;; ---------------------------------------------------------------------------

(defn try-render
  "Try to render data using a registered Datalevin renderer.

   Unlike `render`, this returns nil if no renderer is found instead of
   falling back to pprint. Use this when you want to know if a specific
   renderer exists without fallback behavior.

   Arguments:
     data   - Map of data to render
     format - :html or :ai

   Returns:
     Rendered value if renderer found, nil otherwise.

   Example:
     (try-render {:seon.health.workout/exercise \"Squat\" ...} :ai)
     ;; => \"Squat 5x5 @ 100kg\" or nil"
  [data format]
  (when (and (map? data) (#{:html :ai} format))
    (call-datalevin-renderer data format)))

(defn has-renderer?
  "Check if a registered renderer exists for the given data and format.

   Arguments:
     data   - Map of data to check
     format - :html or :ai

   Returns:
     true if a renderer is registered, false otherwise.

   Example:
     (has-renderer? {:seon.health.workout/exercise \"Squat\" ...} :ai)
     ;; => true"
  [data format]
  (when (and (map? data) (#{:html :ai} format))
    (let [resolved (resolve-renderer-from-datalevin data format)]
      (not= resolved ::no-renderer))))

;;; ---------------------------------------------------------------------------
;;; Core Rendering
;;; ---------------------------------------------------------------------------

(defn render
  "Render a value for a specific format.

   Dispatch:
   1. If value has `:seon/schema` metadata, try Datalevin resolution for that schema
   2. If value is a plain map, try Datalevin resolution by data keys
   3. Fall back to format-appropriate default

   Arguments:
     value  - The value to render
     format - Output format (:ai, :html, :raw, :human)

   Returns:
     Rendered output appropriate for the format.

   Example:
     (render pos :ai)         ; => \"AAPL x100 @ $150.0\"
     (render pos :html)       ; => [:div.position ...]
     (render pos :raw)        ; => {:ticker \"AAPL\" ...}
     (render pos :human)      ; => pretty-printed string"
  [value format]
  (let [;; Try Datalevin resolution for maps
        datalevin-result (when (and (map? value)
                                    (#{:html :ai} format))
                           (call-datalevin-renderer value format))]
    (cond
      ;; Datalevin renderer found
      datalevin-result datalevin-result

      ;; Raw format always returns the value
      (= format :raw) value

      ;; Human format uses pprint
      (= format :human) (pprint-clipped value)

      ;; AI format falls back to pprint-clipped
      (= format :ai) (pprint-clipped value)

      ;; HTML format falls back to code block
      (= format :html) [:pre [:code (pprint-clipped value)]]

      ;; Unknown format
      :else (pr-str value))))

;;; ---------------------------------------------------------------------------
;;; Humanize: Keyword/String → Human-Readable Labels
;;; ---------------------------------------------------------------------------

(def ^:private special-abbreviations
  "Abbreviations that should be uppercased, not title-cased."
  #{"id" "url" "sse" "api" "db" "ui" "html" "css" "js" "http"
    "https" "sql" "json" "xml" "csv" "uri" "ip" "dns" "tcp" "udp"
    "ai" "llm" "jwt" "uuid" "edn" "cli" "ux" "pr" "ci" "cd"})

(defn humanize
  "Transform a keyword or string into a human-readable label.

   - Strips namespace from keywords
   - Converts kebab-case to Title Case
   - Handles special abbreviations (ID, URL, SSE, etc.)
   - Strips leading/trailing asterisks (e.g. *ctx* → Ctx)

   Examples:
     (humanize :seon.health.workout/total-volume) => \"Total Volume\"
     (humanize :proposed-schema)                  => \"Proposed Schema\"
     (humanize :api-key)                          => \"API Key\"
     (humanize \"step-title\")                      => \"Step Title\"
     (humanize :*ctx*)                             => \"Ctx\""
  [k]
  (let [s (cond
            (nil? k) ""
            (keyword? k) (name k)
            (string? k) k
            :else (str k))
        ;; Strip leading/trailing asterisks
        s (str/replace s #"^\*+|\*+$" "")
        parts (str/split s #"-")]
    (str/join " " (keep (fn [part]
                          (when (seq part)
                            (if (special-abbreviations (str/lower-case part))
                              (str/upper-case part)
                              (str (str/upper-case (subs part 0 1))
                                   (subs part 1)))))
                        parts))))

;;; ---------------------------------------------------------------------------
;;; Malli Schema → Human-Readable Table
;;; ---------------------------------------------------------------------------

(def ^:private malli-type-labels
  "Map Malli type keywords to human-readable names."
  {:string "Text"
   :int "Number"
   :double "Decimal"
   :float "Decimal"
   :boolean "Yes/No"
   :keyword "Category"
   :symbol "Symbol"
   :uuid "ID"
   :any "Any"
   :map "Object"
   :vector "List"
   :set "Set"
   :sequential "List"
   :enum "Choice"
   :nil "Empty"})

(defn- malli-schema?
  "Check if a value looks like a Malli schema form.
   Malli schemas are vectors starting with a keyword like :map, :string, etc."
  [v]
  (and (vector? v)
       (keyword? (first v))
       (contains? #{:map :string :int :double :float :boolean :keyword
                    :vector :set :sequential :enum :cat :tuple :or :and
                    :maybe :re :fn} (first v))))

(defn- resolve-type-label
  "Get a human-readable type label for a Malli type form."
  [type-form]
  (cond
    (keyword? type-form)
    (get malli-type-labels type-form (humanize type-form))

    (= type-form 'number?)
    "Number"

    (= type-form 'string?)
    "Text"

    (= type-form 'int?)
    "Number"

    (symbol? type-form)
    (str (name type-form))

    (and (vector? type-form) (keyword? (first type-form)))
    (let [base (first type-form)]
      (case base
        :maybe (str (resolve-type-label (last type-form)) " (optional)")
        :enum (str "One of: " (str/join ", " (map #(humanize (str %)) (rest type-form))))
        :vector (str "List of " (resolve-type-label (last type-form)))
        (get malli-type-labels base (humanize base))))

    :else "Value"))

(defn- schema-field-entries
  "Extract field entries from a :map schema form.
   Returns seq of {:field-name :type-label :required?}."
  [schema-form]
  (when (and (vector? schema-form) (= :map (first schema-form)))
    (let [entries (rest schema-form)
          ;; Skip map-level properties if present
          entries (if (and (seq entries) (map? (first entries)))
                    (rest entries)
                    entries)]
      (for [entry entries
            :when (vector? entry)]
        (let [field-key (first entry)
              ;; Check for optional property map
              has-props? (and (>= (count entry) 3) (map? (second entry)))
              props (when has-props? (second entry))
              type-form (if has-props? (nth entry 2) (second entry))
              optional? (when props (:optional props))]
          {:field-name (humanize field-key)
           :type-label (resolve-type-label type-form)
           :required? (not optional?)})))))

(defn render-schema
  "Render a Malli schema as a human-readable field specification table.

   Arguments:
     schema-form - A Malli schema vector (e.g. [:map [:name :string] ...])

   Returns:
     Hiccup table showing Field | Type | Required."
  [schema-form]
  (if-let [fields (schema-field-entries schema-form)]
    [:div {:class "overflow-x-auto"}
     [:table {:class "w-full text-xs border-collapse"}
      [:thead
       [:tr {:class "border-b border-base-700 bg-base-900"}
        [:th {:class "py-1.5 px-2 text-left text-text-400 font-semibold uppercase tracking-wider"} "Field"]
        [:th {:class "py-1.5 px-2 text-left text-text-400 font-semibold uppercase tracking-wider"} "Type"]
        [:th {:class "py-1.5 px-2 text-left text-text-400 font-semibold uppercase tracking-wider"} "Required"]]]
      [:tbody
       (map-indexed
        (fn [idx {:keys [field-name type-label required?]}]
          [:tr {:class "border-b border-base-800/50"
                :style (str "--i:" idx)}
           [:td {:class "py-1 px-2 text-text-200"} field-name]
           [:td {:class "py-1 px-2 text-text-300"} type-label]
           [:td {:class "py-1 px-2 text-text-400"} (if required? "Yes" "No")]])
        fields)]]]
    ;; Not a :map schema, render as a type badge
    [:span {:class "inline-block px-2 py-0.5 text-xs rounded bg-base-800 text-text-300"}
     (resolve-type-label schema-form)]))

;;; ---------------------------------------------------------------------------
;;; Collection Rendering
;;; ---------------------------------------------------------------------------

(defn render-seq
  "Render a sequence of typed values.

   Arguments:
     values - Sequence of values (each should have `:seon/schema` metadata)
     format - Output format

   Returns:
     Vector of rendered values."
  [values format]
  (mapv #(render % format) values))

;;; ---------------------------------------------------------------------------
;;; for-ai: AI-Friendly Recursive Rendering
;;; ---------------------------------------------------------------------------

(defn for-ai
  "Render any value for AI consumption.

   Recursively renders nested structures, producing concise text output
   suitable for AI agents in nREPL sessions.

   Typed values use Datalevin-resolved :ai renderers.
   Collections are rendered with their contents.
   Primitives are converted to strings.

   Arguments:
     v - Any value

   Returns:
     Concise string representation.

   Example:
     (for-ai {:positions [pos1 pos2] :total 25000.0})
     ;; => \"{:positions [AAPL x100, GOOGL x50], :total 25000.0}\""
  [v]
  (cond
    (nil? v) "nil"
    (string? v) v
    (keyword? v) (str v)
    (number? v) (str v)
    (boolean? v) (str v)
    (symbol? v) (str v)

    ;; Map - try Datalevin renderer first, then recurse
    (map? v)
    (let [ai-result (call-datalevin-renderer v :ai)]
      (if ai-result
        ai-result
        (str "{"
             (str/join ", "
                       (map (fn [[k v*]]
                              (str (pr-str k) " " (for-ai v*)))
                            v))
             "}")))

    ;; Sequential - recurse
    (sequential? v)
    (str "[" (str/join ", " (map for-ai v)) "]")

    ;; Set
    (set? v)
    (str "#{" (str/join ", " (map for-ai v)) "}")

    ;; Anything else
    :else (pr-str v)))

;;; ---------------------------------------------------------------------------
;;; for-html: Recursive HTML Rendering
;;; ---------------------------------------------------------------------------

(defn for-html
  "Render any value as hiccup HTML.

   Recursively renders nested structures, producing hiccup data
   suitable for browser display.

   Maps render as definition-list tables.
   Vectors/seqs render as ordered lists.
   Primitives render as text spans.
   If a map value has a Datalevin renderer, uses it.

   Arguments:
     v - Any value

   Returns:
     Hiccup data structure."
  [v]
  (cond
    (nil? v) [:span {:class "text-text-400 italic"} "nil"]
    (string? v) [:span {:class "text-text-200"} v]
    (keyword? v) [:span {:class "text-text-200"} (humanize v)]
    (number? v) [:span {:class "text-signal font-mono"} (str v)]
    (boolean? v) [:span {:class "text-eval font-mono"} (str v)]
    (symbol? v) [:span {:class "text-text-200 font-mono"} (str v)]

    ;; Malli schema forms - render as field specification table
    (malli-schema? v)
    (render-schema v)

    ;; Map - try Datalevin renderer first, then recurse as table
    (map? v)
    (let [html-result (call-datalevin-renderer v :html)]
      (if html-result
        html-result
        [:table {:class "w-full text-sm"}
         [:tbody
          (for [[k v*] v]
            [:tr {:class "border-b border-base-700/50"}
             [:td {:class "py-1 px-2 text-text-400 text-xs align-top whitespace-nowrap"}
              (humanize k)]
             [:td {:class "py-1 px-2"} (for-html v*)]])]]))

    ;; Vector of maps - render as table with humanized headers
    (and (sequential? v) (seq v) (every? map? v))
    (let [all-keys (distinct (mapcat keys v))]
      [:div {:class "overflow-x-auto"}
       [:table {:class "w-full text-xs border-collapse"}
        [:thead
         [:tr {:class "border-b border-base-700 bg-base-900"}
          (for [k all-keys]
            [:th {:class "py-1.5 px-2 text-left text-text-400 font-semibold uppercase tracking-wider"}
             (humanize k)])]]
        [:tbody
         (map-indexed
          (fn [idx row]
            [:tr {:class "border-b border-base-800/50 hover:bg-base-800/30"
                  :style (str "--i:" idx)}
             (for [k all-keys]
               [:td {:class "py-1 px-2 text-text-200"}
                (for-html (get row k))])])
          v)]]])

    ;; Sequential - render as list
    (sequential? v)
    [:ul {:class "list-disc list-inside text-sm"}
     (for [item v]
       [:li (for-html item)])]

    ;; Set
    (set? v)
    [:ul {:class "list-disc list-inside text-sm"}
     (for [item v]
       [:li (for-html item)])]

    ;; Anything else
    :else [:span {:class "font-mono text-text-300"} (pr-str v)]))

;;; ---------------------------------------------------------------------------
;;; Page Renderer Resolution
;;; ---------------------------------------------------------------------------

(defn find-page-renderer
  "Find a page render function whose input spec keys overlap most with ns-data keys.

   Uses functions-with-output-key to find HTML renderers via ref join.
   The function with the MOST key overlap wins.

   Arguments:
     db-name - Database name keyword (e.g. :seon.runtime)
     ns-data - Map of namespace data (keys to match against)

   Returns:
     The qualified-name string of the best page renderer, or nil."
  [db-name ns-data]
  (let [data-keys (set (keys ns-data))
        ;; Use unified helper to find HTML renderers via ref join
        candidates (gq/functions-with-output-key {::gq/db-name db-name ::gq/output-key :seon.render/html})
        ;; Filter: at least one required key must overlap with data keys
        matching (->> candidates
                      (filter (fn [e]
                                (let [rkeys (:required-keys e)
                                      overlap (count (cset/intersection rkeys data-keys))]
                                  (pos? overlap)))))]
    (when (seq matching)
      (->> matching
           (sort-by (juxt (comp - (fn [e]
                                    (count (cset/intersection
                                            (:required-keys e)
                                            data-keys))))
                          (comp - (fn [e] (if-let [t (:seon.fn/updated-at e)]
                                            (.getTime ^java.util.Date t)
                                            0)))
                          :seon.fn/qualified-name))
           first
           :seon.fn/qualified-name))))

;;; ---------------------------------------------------------------------------
;;; Namespace Rendering
;;; ---------------------------------------------------------------------------

(defn default-namespace-render
  "Generic fallback renderer for namespace data.

   Renders ns-data using the recursive renderers based on format:
     :html - for-html
     :ai   - for-ai
     :raw  - identity

   Arguments:
     ns-data - Map of namespace data
     format  - :html, :ai, or :raw

   Returns:
     Rendered output for the requested format."
  [ns-data format]
  (case format
    :html (for-html ns-data)
    :ai (for-ai ns-data)
    :raw ns-data))

(defn render-namespace
  "Main entry point for rendering a namespace.

   Takes a map with ::ns-data and ::format. Finds the best page renderer
   via Datalevin. If found, calls it and extracts the format key from the
   result. If not found, uses default-namespace-render.

   Arguments:
     request - Map with keys:
       ::ns-data - Map of namespace data (vars, ctx, etc.)
       ::format  - :html, :ai, or :raw (default :html)

   Returns:
     Rendered output for the requested format."
  [{:keys [::ns-data ::format] :or {format :html}}]
  (let [page-renderer-name (find-page-renderer :seon.runtime ns-data)]
    (if page-renderer-name
      (let [renderer-fn (requiring-resolve (symbol page-renderer-name))
            result (renderer-fn ns-data)
            format-key (case format
                         :html ::html
                         :ai ::ai
                         :raw nil)]
        (if format-key
          (get result format-key)
          ns-data))
      (default-namespace-render ns-data format))))

;;; ---------------------------------------------------------------------------
;;; REPL / Development
;;; ---------------------------------------------------------------------------

(comment
  ;; REPL exploration

  ;; Create typed value
  (schema/register! :example/widget [:map [:name :string]])
  (def w (typed :example/widget {:name "Foo"}))

  ;; Render in different formats
  (render w :ai)    ; => pprint-clipped fallback
  (render w :html)  ; => [:pre [:code ...]]
  (render w :raw)   ; => {:name "Foo"}
  (render w :human) ; => pretty-printed

  ;; for-ai recursively handles nested structures
  (for-ai {:widgets [w w] :count 2})

  nil)
