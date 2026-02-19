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
            [clojure.string :as str]
            [datalevin.core :as d]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Datalevin Connection
;;; ---------------------------------------------------------------------------

(defonce ^:private *conn (atom nil))

(defn set-conn!
  "Set the Datalevin connection for renderer resolution.
   Called during system startup."
  [conn]
  (reset! *conn conn))

;;; ---------------------------------------------------------------------------
;;; Resolution Cache
;;; ---------------------------------------------------------------------------

(defonce ^:private resolution-cache (atom {}))

(defn invalidate-render-cache!
  "Clear the renderer resolution cache.
   Called by the scanner after code graph updates."
  []
  (reset! resolution-cache {}))

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
      (let [conn @*conn
            result (if conn
                     (if-let [qn (find-renderer conn data format)]
                       (or (requiring-resolve (symbol qn)) ::no-renderer)
                       ::no-renderer)
                     ::no-renderer)]
        (swap! resolution-cache assoc cache-key result)
        result))))

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
