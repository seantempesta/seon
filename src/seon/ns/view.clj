(ns seon.ns.view
  "Namespace-based view system for rendering Clojure values in multiple formats.

   This module provides a multimethod-based rendering system that dispatches on
   [format view-type], where:
   - format: :ai, :html, :human, :raw
   - view-type: from :seon/view metadata, Malli schema properties, or inferred

   Values carry view hints via metadata (:seon/view). Malli schemas can specify
   view type in their properties. Defaults work without explicit registration.

   ## Public API

   All public functions follow map-in/map-out conventions:

     (typed {::view-type :trading/position ::value {:symbol \"AAPL\"}})
     (render-value {::value {:symbol \"AAPL\"} ::format :html})
     (detail-url {::view-type :seon.ai.agent/summary ::id \"fa5d\"})

   ## Extending for Custom Types

     (defmethod render* [:html :my-domain/widget]
       [value _format]
       [:div.widget (pr-str value)])

   Note: The `render*` multimethod uses positional args for dispatch efficiency.
   Use the public `render-value` function for the map-in/map-out API."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::view-type
                  [:qualified-keyword
                   {:description "View type keyword like :seon.ai.agent/summary"}])

(schema/register! ::format
                  [:enum {:description "Output format for rendering"}
                   :html :ai :human :raw])

(schema/register! ::id
                  [:string {:min 1
                            :description "Entity ID for detail/instance views"}])

(schema/register! ::url
                  [:string {:description "URL string"}])

;; Request/Response schemas
(schema/register! ::typed-request
                  [:map
                   [::view-type ::view-type]
                   [::value :any]])

(schema/register! ::typed-response
                  [:any {:description "Value with :seon/view metadata attached"}])

(schema/register! ::detail-url-request
                  [:map
                   [::view-type ::view-type]
                   [::id ::id]])

(schema/register! ::list-url-request
                  [:map
                   [::view-type ::view-type]])

(schema/register! ::render-request
                  [:map
                   [::value :any]
                   [::format ::format]])

(schema/register! ::view-type-request
                  [:map
                   [::value :any]])

;;; ---------------------------------------------------------------------------
;;; View Type Extraction
;;; ---------------------------------------------------------------------------

(defn- extract-view-type
  "Extract view type from a value (internal helper).

   Checks in order:
   1. :seon/view metadata on the value
   2. :seon/view property from Malli schema (if :seon/schema metadata present)
   3. :default"
  [value]
  (or
   ;; Check direct metadata
   (:seon/view (meta value))
   ;; Check Malli schema properties
   (when-let [schema-key (:seon/schema (meta value))]
     (try
       (when-let [schema (m/schema schema-key)]
         (-> schema m/properties :seon/view))
       (catch Exception _ nil)))
   ;; Default
   :default))

;;; ---------------------------------------------------------------------------
;;; Core Multimethod (Internal)
;;; ---------------------------------------------------------------------------

(defmulti render*
  "Internal multimethod for rendering. Dispatches on [format view-type].

   Use the public `render-value` function for the map-in/map-out API.

   format: :ai, :html, :human, :raw
   view-type: from :seon/view metadata, schema properties, or :default

   Returns:
   - :html format -> Hiccup data structure
   - :ai format -> String optimized for LLM consumption
   - :human format -> String for human reading
   - :raw format -> pr-str representation"
  (fn [value format] [format (extract-view-type value)]))

;;; ---------------------------------------------------------------------------
;;; Default Renderers - :raw format (pr-str for all types)
;;; ---------------------------------------------------------------------------

(defmethod render* [:raw :default] [value _format]
  (pr-str value))

;;; ---------------------------------------------------------------------------
;;; Default Renderers - :human format (readable strings)
;;; ---------------------------------------------------------------------------

(defmethod render* [:human :default] [value _format]
  (cond
    (nil? value)
    "nil"

    (string? value)
    (if (> (count value) 100)
      (str (subs value 0 97) "...")
      value)

    (keyword? value)
    (str value)

    (symbol? value)
    (str value)

    (number? value)
    (str value)

    (boolean? value)
    (str value)

    (map? value)
    (let [ks (keys value)]
      (if (> (count ks) 5)
        (str "{" (count ks) " keys: " (str/join ", " (take 5 (map str ks))) ", ...}")
        (str "{" (str/join ", " (map #(str % " " (render* (get value %) :human)) ks)) "}")))

    (vector? value)
    (if (> (count value) 5)
      (str "[" (count value) " items]")
      (str "[" (str/join ", " (map #(render* % :human) value)) "]"))

    (set? value)
    (str "#{" (count value) " items}")

    (seq? value)
    (let [realized (take 6 value)]
      (if (= 6 (count realized))
        (str "(" (count (take 100 value)) "+ items)")
        (str "(" (str/join " " (map #(render* % :human) realized)) ")")))

    (instance? clojure.lang.IAtom value)
    (str "Atom<" (render* @value :human) ">")

    :else
    (let [s (pr-str value)]
      (if (> (count s) 100)
        (str (subs s 0 97) "...")
        s))))

;;; ---------------------------------------------------------------------------
;;; Default Renderers - :ai format (LLM-optimized)
;;; ---------------------------------------------------------------------------

(defmethod render* [:ai :default] [value _format]
  (cond
    (nil? value)
    "nil"

    (string? value)
    (cond
      ;; Detect JSON
      (and (str/starts-with? value "{") (str/ends-with? value "}"))
      (str "JSON: " (if (> (count value) 500)
                      (str (subs value 0 500) "... [truncated]")
                      value))

      ;; Detect markdown
      (or (str/starts-with? value "#")
          (str/includes? value "```"))
      (str "Markdown content (" (count value) " chars)")

      ;; Regular string
      (> (count value) 200)
      (str "\"" (subs value 0 200) "...\" [" (count value) " chars total]")

      :else
      (pr-str value))

    (keyword? value)
    (str value)

    (symbol? value)
    (str value)

    (number? value)
    (str value)

    (boolean? value)
    (str value)

    (map? value)
    (let [ks (keys value)
          n (count ks)]
      (if (> n 10)
        (str "Map with " n " keys: " (str/join ", " (take 10 (map str ks))) ", ...")
        (str "{"
             (str/join ", " (map (fn [[k v]] (str k ": " (render* v :ai))) value))
             "}")))

    (vector? value)
    (let [n (count value)]
      (if (> n 10)
        (str "Vector[" n "]: first 3 = " (str/join ", " (map #(render* % :ai) (take 3 value))))
        (str "[" (str/join ", " (map #(render* % :ai) value)) "]")))

    (set? value)
    (str "Set{" (count value) " items}")

    (seq? value)
    (let [realized (vec (take 11 value))]
      (if (= 11 (count realized))
        (str "LazySeq with 10+ items, first 3: " (str/join ", " (map #(render* % :ai) (take 3 realized))))
        (str "(" (str/join " " (map #(render* % :ai) realized)) ")")))

    (instance? clojure.lang.IAtom value)
    (str "Atom containing: " (render* @value :ai))

    (instance? clojure.lang.Var value)
    (str "#'" (.-ns value) "/" (.-sym value))

    (fn? value)
    "<function>"

    :else
    (let [s (pr-str value)]
      (if (> (count s) 300)
        (str (subs s 0 300) "... [truncated, " (count s) " chars total]")
        s))))

;;; ---------------------------------------------------------------------------
;;; Default Renderers - :html format (Hiccup)
;;; ---------------------------------------------------------------------------

(defn- safe-map?
  "Check if v is safe to recursively render as a map.
   Returns false for Java objects that implement map interfaces but aren't true Clojure maps.
   This prevents stack overflow when rendering complex Java objects."
  [v]
  (and (map? v)
       (let [class-name (.getName (class v))]
         (or (str/starts-with? class-name "clojure.lang.")
             ;; Also allow user-defined records (namespaced class names)
             (and (str/includes? class-name ".")
                  (not (str/starts-with? class-name "java."))
                  (not (str/starts-with? class-name "javax."))
                  (not (str/starts-with? class-name "io."))
                  (not (str/starts-with? class-name "org.")))))))

(defmethod render* [:html :default] [value _format]
  (cond
    (nil? value)
    [:span {:class "text-text-400 italic"} "nil"]

    (string? value)
    (if (> (count value) 120)
      [:details {:class "inline" :data-preserve-attr "open"}
       [:summary {:class "cursor-pointer text-warning"}
        (pr-str (subs value 0 120))]
       [:pre {:class "text-warning text-xs mt-1 whitespace-pre-wrap"} (pr-str value)]]
      [:span {:class "text-warning"} (pr-str value)])

    (keyword? value)
    [:span {:class "text-eval"} (str value)]

    (symbol? value)
    [:span {:class "text-error"} (str value)]

    (number? value)
    [:span {:class "text-success"} (str value)]

    (boolean? value)
    [:span {:class "text-info font-medium"} (str value)]

    (safe-map? value)
    (if (empty? value)
      [:span {:class "text-text-400"} "{}"]
      [:div {:class "pl-3 border-l border-base-700 ml-1"}
       [:span {:class "text-text-400"} "{"]
       (for [[k v] (take 20 value)]
         [:div {:class "flex gap-2 items-start"}
          (render* k :html)
          (render* v :html)])
       (when (> (count value) 20)
         [:div {:class "text-text-400 italic"} (str "... " (- (count value) 20) " more")])
       [:span {:class "text-text-400"} "}"]])

    (vector? value)
    (if (empty? value)
      [:span {:class "text-text-400"} "[]"]
      [:div {:class "pl-3 border-l border-base-700 ml-1"}
       [:span {:class "text-text-400"} "["]
       (for [item (take 20 value)]
         [:div (render* item :html)])
       (when (> (count value) 20)
         [:div {:class "text-text-400 italic"} (str "... " (- (count value) 20) " more")])
       [:span {:class "text-text-400"} "]"]])

    (set? value)
    (if (empty? value)
      [:span {:class "text-text-400"} "#{}"]
      [:div {:class "pl-3 border-l border-base-700 ml-1"}
       [:span {:class "text-text-400"} "#{"]
       (for [item (take 20 value)]
         [:div (render* item :html)])
       (when (> (count value) 20)
         [:div {:class "text-text-400 italic"} (str "... " (- (count value) 20) " more")])
       [:span {:class "text-text-400"} "}"]])

    (seq? value)
    (let [realized (vec (take 21 value))]
      (if (empty? realized)
        [:span {:class "text-text-400"} "()"]
        [:div {:class "pl-3 border-l border-base-700 ml-1"}
         [:span {:class "text-text-400"} "("]
         (for [item (take 20 realized)]
           [:div (render* item :html)])
         (when (= 21 (count realized))
           [:div {:class "text-text-400 italic"} "..."])
         [:span {:class "text-text-400"} ")"]]))

    (instance? clojure.lang.IAtom value)
    [:div {:class "border border-warning/30 rounded p-2 bg-warning/5"}
     [:span {:class "text-xs text-warning mr-2"} "Atom"]
     (render* @value :html)]

    (instance? clojure.lang.Var value)
    [:span {:class "text-log-launch"} (str "#'" (.-ns value) "/" (.-sym value))]

    (fn? value)
    [:span {:class "text-text-400 italic"} "<fn>"]

    (instance? java.util.regex.Pattern value)
    [:span {:class "text-signal"} (str "#\"" (.pattern value) "\"")]

    (instance? java.time.Instant value)
    [:span {:class "text-log-result"} (str value)]

    (instance? java.util.Date value)
    [:span {:class "text-log-result"} (str value)]

    (instance? java.util.UUID value)
    [:span {:class "text-log-hook font-mono text-xs"} (str value)]

    (instance? Class value)
    [:span {:class "text-error"} (.getName value)]

    :else
    [:code {:class "text-text-200 text-xs"} (let [s (pr-str value)]
                                               (if (> (count s) 200)
                                                 (str (subs s 0 200) "...")
                                                 s))]))

;;; ---------------------------------------------------------------------------
;;; Public API - Map In / Map Out
;;; ---------------------------------------------------------------------------

(defn typed
  "Attach view type metadata to a value.

   Request keys:
     ::view-type - Required. View type keyword like :seon.ai.agent/summary
     ::value     - Required. Value to attach metadata to

   Returns the value with :seon/view metadata attached.

   Example:
     (typed {::view-type :trading/position
             ::value {:symbol \"AAPL\" :qty 100}})
     ;; => {:symbol \"AAPL\" :qty 100} with ^{:seon/view :trading/position}

     ;; From outside namespace:
     (view/typed {::view/view-type :trading/position
                  ::view/value {:symbol \"AAPL\"}})"
  {:malli/schema [:=> [:cat ::typed-request] ::typed-response]}
  [{::keys [view-type value]}]
  (vary-meta value assoc :seon/view view-type))

(defn with-schema
  "Attach schema key metadata to a value.
   View type will be extracted from schema properties at render time.

   Request keys:
     ::schema-key - Required. Schema key to attach
     ::value      - Required. Value to attach metadata to

   Example:
     (with-schema {::schema-key ::my-schema ::value {:data 123}})"
  [{::keys [schema-key value]}]
  (vary-meta value assoc :seon/schema schema-key))

(defn view-type
  "Extract view type from a value.

   Request keys:
     ::value - Required. Value to extract view type from

   Returns:
     ::view-type - The view type keyword, or :default if none found

   Example:
     (view-type {::value (typed {::view-type :my/widget ::value {:x 1}})})
     ;; => :my/widget"
  {:malli/schema [:=> [:cat ::view-type-request] [:or ::view-type [:= :default]]]}
  [{::keys [value]}]
  (extract-view-type value))

(defn render-value
  "Render a value in the specified format.

   Request keys:
     ::value  - Required. Value to render
     ::format - Required. One of :html, :ai, :human, :raw

   Returns format-specific output:
   - :html   -> Hiccup data structure
   - :ai     -> String optimized for LLM consumption
   - :human  -> String for human reading
   - :raw    -> pr-str representation

   Example:
     (render-value {::value {:a 1} ::format :html})
     (render-value {::value [1 2 3] ::format :ai})"
  {:malli/schema [:=> [:cat ::render-request] :any]}
  [{::keys [value format]}]
  (render* value format))

(defn detail-url
  "Build a detail URL for the namespace view system.

   Given a view type keyword and an ID, returns the URL for the detail view.
   The namespace is extracted from the view type keyword.

   Request keys:
     ::view-type - Required. View type keyword like :seon.ai.agent/summary
     ::id        - Required. Entity ID for detail view

   Returns:
     URL string

   Example:
     (detail-url {::view-type :seon.ai.agent/summary ::id \"fa5d\"})
     ;; => \"/ns/seon.ai.agent?id=fa5d\"

     (detail-url {::view-type :trading.signals/row ::id \"sig-123\"})
     ;; => \"/ns/trading.signals?id=sig-123\""
  {:malli/schema [:=> [:cat ::detail-url-request] ::url]}
  [{::keys [view-type id]}]
  (let [ns-str (namespace view-type)]
    (str "/ns/" ns-str "?id=" id)))

(defn list-url
  "Build a list URL for the namespace view system.

   Given a view type keyword, returns the URL for the list view.
   The namespace is extracted from the view type keyword.

   Request keys:
     ::view-type - Required. View type keyword like :seon.ai.agent/detail

   Returns:
     URL string

   Example:
     (list-url {::view-type :seon.ai.agent/detail})
     ;; => \"/ns/seon.ai.agent\""
  {:malli/schema [:=> [:cat ::list-url-request] ::url]}
  [{::keys [view-type]}]
  (let [ns-str (namespace view-type)]
    (str "/ns/" ns-str)))

;;; ---------------------------------------------------------------------------
;;; Backwards Compatibility / Convenience Aliases
;;; ---------------------------------------------------------------------------

;; These call the internal multimethod directly for use in view implementations
;; where the overhead of map construction isn't warranted.

(defn render
  "Render value in format. Convenience wrapper around render*.

   This is a shorthand for view implementations that need direct access.
   For the full map-in/map-out API, use `render-value`.

   Args:
     value  - Value to render (may have :seon/view metadata)
     format - One of :html, :ai, :human, :raw"
  [value format]
  (render* value format))

;;; ---------------------------------------------------------------------------
;;; REPL Exploration
;;; ---------------------------------------------------------------------------

(comment
  ;; Basic rendering with new API
  (render-value {::value 42 ::format :human})
  (render-value {::value {:a 1 :b 2} ::format :ai})
  (render-value {::value [1 2 3] ::format :html})

  ;; Typed values
  (render-value {::value (typed {::view-type :my/widget ::value {:name "test"}})
                 ::format :html})
  (view-type {::value (typed {::view-type :my/widget ::value {:name "test"}})})

  ;; URL helpers
  (detail-url {::view-type :seon.ai.agent/summary ::id "fa5d"})
  ;; => "/ns/seon.ai.agent?id=fa5d"

  (list-url {::view-type :seon.ai.agent/detail})
  ;; => "/ns/seon.ai.agent"

  ;; Large values truncate appropriately
  (render-value {::value (range 100) ::format :human})
  (render-value {::value (range 100) ::format :ai})
  (render-value {::value (zipmap (range 50) (range 50)) ::format :ai})

  nil)
