(ns seon.render
  "Multi-format rendering based on schema type metadata.

   This namespace provides a unified rendering system that dispatches on both
   format (:ai, :html, :raw, :human) and schema type (from value metadata).

   Core concepts:
   - Values carry `:seon/schema` metadata identifying their type
   - Renderers are registered per schema-key and inherit from parent schemas
   - Multiple output formats support different contexts (AI agents, web UI, REPL)

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
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Renderer Registry
;;; ---------------------------------------------------------------------------

;; Registry mapping schema keywords to render function maps.
;; Structure: {schema-key {:ai fn, :html fn, :raw fn, :human fn}}
(defonce ^:private *renderers (atom {}))

(defn register-renderer!
  "Register render functions for a schema key.

   The schema must be registered in seon.schema registry.

   Arguments:
     schema-key - Fully-namespaced keyword (e.g., :trading/position)
     render-map - Map of format->function {:ai fn, :html fn, ...}

   Options:
     :inherit - Parent schema key to inherit renderers from

   Returns:
     The schema-key.

   Example:
     (register-renderer! :trading/position
       {:ai (fn [v] (str (:ticker v) \" x\" (:quantity v)))
        :html (fn [v] [:div.position (:ticker v)])}
       :inherit :trading/base-entity)"
  [schema-key render-map & {:keys [inherit]}]
  (when-not (schema/registered? schema-key)
    (throw (ex-info (str "Schema not registered: " schema-key
                         ". Register with seon.schema/register! first.")
                    {:schema-key schema-key})))
  (let [parent-render (when inherit
                        (or (get @*renderers inherit)
                            (throw (ex-info (str "Parent schema not in render registry: " inherit)
                                            {:parent inherit :schema-key schema-key}))))
        final-render (merge parent-render render-map)]
    (swap! *renderers assoc schema-key final-render)
    schema-key))

(defn get-renderer
  "Get the render function for a schema-key and format.

   Returns nil if no renderer is registered."
  [schema-key format]
  (get-in @*renderers [schema-key format]))

(defn registered-renderers
  "Return all registered renderers. Useful for debugging."
  []
  @*renderers)

(defn clear-renderers!
  "Clear all registered renderers. USE WITH CAUTION - only for testing."
  []
  (reset! *renderers {}))

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
;;; Core Rendering
;;; ---------------------------------------------------------------------------

(defn render
  "Render a value for a specific format.

   Uses the `:seon/schema` metadata to dispatch to registered renderers.
   Falls back to default rendering if no renderer is found.

   Arguments:
     value  - The value to render (should have `:seon/schema` metadata)
     format - Output format (:ai, :html, :raw, :human)

   Options:
     default-schema - Schema to use if value has no metadata

   Returns:
     Rendered output appropriate for the format.

   Example:
     (render pos :ai)         ; => \"AAPL x100 @ $150.0\"
     (render pos :html)       ; => [:div.position ...]
     (render pos :raw)        ; => {:ticker \"AAPL\" ...}
     (render pos :human)      ; => pretty-printed string"
  ([value format] (render value format nil))
  ([value format default-schema]
   (let [schema-key (or (schema-of value) default-schema)
         render-fn (when schema-key (get-renderer schema-key format))]
     (cond
       ;; Have a registered renderer
       render-fn (render-fn value)

       ;; Raw format always returns the value
       (= format :raw) value

       ;; Human format uses pprint
       (= format :human) (with-out-str (pp/pprint value))

       ;; AI format falls back to pr-str
       (= format :ai) (pr-str value)

       ;; HTML format falls back to code block
       (= format :html) [:pre [:code (pr-str value)]]

       ;; Unknown format
       :else (pr-str value)))))

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

   Typed values use their registered :ai renderer.
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

    ;; Has explicit schema type - use :ai renderer
    (schema-of v)
    (let [render-fn (get-renderer (schema-of v) :ai)]
      (if render-fn
        (render-fn v)
        (pr-str v)))

    ;; Sequential - recurse
    (sequential? v)
    (str "[" (str/join ", " (map for-ai v)) "]")

    ;; Map - recurse on values
    (map? v)
    (str "{"
         (str/join ", "
                   (map (fn [[k val]]
                          (str (pr-str k) " " (for-ai val)))
                        v))
         "}")

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

  ;; Check registry state
  (registered-renderers)

  ;; Schema must be registered first
  (schema/register! :example/widget [:map [:name :string]])

  ;; Then register renderer
  (register-renderer! :example/widget
                      {:ai (fn [w] (str "Widget: " (:name w)))
                       :html (fn [w] [:div.widget (:name w)])})

  ;; Create typed value
  (def w (typed :example/widget {:name "Foo"}))

  ;; Render in different formats
  (render w :ai)    ; => "Widget: Foo"
  (render w :html)  ; => [:div.widget "Foo"]
  (render w :raw)   ; => {:name "Foo"}
  (render w :human) ; => pretty-printed

  ;; for-ai recursively handles nested structures
  (for-ai {:widgets [w w] :count 2})

  nil)
