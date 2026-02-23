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
            [datalevin.core :as d]
            [integrant.repl.state]
            [seon.schema :as schema]))

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
   then looks up from running Integrant system."
  []
  (or @*conn-override
      (some-> integrant.repl.state/system :seon/runtime-db :conn)))

;;; ---------------------------------------------------------------------------
;;; Resolution Cache
;;; ---------------------------------------------------------------------------

(defonce ^:private resolution-cache (atom {}))

(defn invalidate-render-cache!
  "Clear the renderer resolution cache.
   Called by the scanner after code graph updates."
  []
  (reset! resolution-cache {}))

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

(defn find-renderer
  "Find the best render function for the given data and format.

   Queries Datalevin for functions with :seon.fn/render-input-keys
   that are a subset of the data's keys.

   Resolution order:
   1. Most input keys matched (specificity)
   2. Newest updated-at (recency)
   3. Alphabetical qualified-name (deterministic tiebreaker)

   Arguments:
     conn   - Datalevin connection
     data   - Map of data to render
     format - :html or :ai

   Returns the qualified-name string of the best renderer, or nil."
  [conn data format]
  (let [data-keys (set (keys data))
        format-key (case format :html :seon.render/html :ai :seon.render/ai)
        ;; Pull all fn entities that have render-input-keys
        candidates (d/q '[:find ?e
                          :where
                          [?e :seon.fn/render-input-keys]]
                        @conn)
        entities (map (fn [[eid]]
                        (d/pull @conn
                                [:seon.fn/qualified-name
                                 :seon.fn/render-input-keys
                                 :seon.fn/updated-at
                                 {:seon.fn/output-spec [:seon.spec/contains-keys]}]
                                eid))
                      candidates)
        ;; Filter: input keys must be subset of data keys
        ;; AND output spec must contain the format key
        matching (->> entities
                      (filter (fn [e]
                                (let [rkeys (:seon.fn/render-input-keys e)
                                      out-keys (set (get-in e [:seon.fn/output-spec :seon.spec/contains-keys]))]
                                  (and (every? data-keys rkeys)
                                       (contains? out-keys format-key))))))]
    (when (seq matching)
      (->> matching
           (sort-by (juxt (comp - count :seon.fn/render-input-keys)
                          (comp - (fn [e] (if-let [t (:seon.fn/updated-at e)]
                                            (.getTime ^java.util.Date t)
                                            0)))
                          :seon.fn/qualified-name))
           first
           :seon.fn/qualified-name))))

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
        (let [conn (get-conn)
              result (if conn
                       (if-let [qn (find-renderer conn data format)]
                         (or (requiring-resolve (symbol qn)) ::no-renderer)
                         ::no-renderer)
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
                       (map (fn [[k val]]
                              (str (pr-str k) " " (for-ai val)))
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
    (keyword? v) [:span {:class "text-amber-400 font-mono"} (str v)]
    (number? v) [:span {:class "text-cyan-400 font-mono"} (str v)]
    (boolean? v) [:span {:class "text-purple-400 font-mono"} (str v)]
    (symbol? v) [:span {:class "text-text-200 font-mono"} (str v)]

    ;; Map - try Datalevin renderer first, then recurse as table
    (map? v)
    (let [html-result (call-datalevin-renderer v :html)]
      (if html-result
        html-result
        [:table {:class "w-full text-sm"}
         [:tbody
          (for [[k val] v]
            [:tr {:class "border-b border-base-700/50"}
             [:td {:class "py-1 px-2 text-text-400 font-mono text-xs align-top whitespace-nowrap"}
              (pr-str k)]
             [:td {:class "py-1 px-2"} (for-html val)]])]]))

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

   Queries Datalevin for functions with :seon.fn/render-input-keys and
   output specs containing :seon.render/html or :seon.render/ai.
   The function with the MOST key overlap wins.

   Arguments:
     conn    - Datalevin connection
     ns-data - Map of namespace data (keys to match against)

   Returns:
     The qualified-name string of the best page renderer, or nil."
  [conn ns-data]
  (let [data-keys (set (keys ns-data))
        candidates (d/q '[:find ?e
                          :where
                          [?e :seon.fn/render-input-keys]]
                        @conn)
        entities (map (fn [[eid]]
                        (d/pull @conn
                                [:seon.fn/qualified-name
                                 :seon.fn/render-input-keys
                                 :seon.fn/updated-at
                                 {:seon.fn/output-spec [:seon.spec/contains-keys]}]
                                eid))
                      candidates)
        matching (->> entities
                      (filter (fn [e]
                                (let [rkeys (set (:seon.fn/render-input-keys e))
                                      out-keys (set (get-in e [:seon.fn/output-spec :seon.spec/contains-keys]))
                                      overlap (count (cset/intersection rkeys data-keys))]
                                  (and (pos? overlap)
                                       (or (contains? out-keys :seon.render/html)
                                           (contains? out-keys :seon.render/ai)))))))]
    (when (seq matching)
      (->> matching
           (sort-by (juxt (comp - (fn [e]
                                    (count (cset/intersection
                                            (set (:seon.fn/render-input-keys e))
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
  (let [conn (get-conn)
        page-renderer-name (when conn (find-page-renderer conn ns-data))]
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
