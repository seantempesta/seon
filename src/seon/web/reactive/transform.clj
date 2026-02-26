(ns seon.web.reactive.transform
  "Hiccup transformation for reactive UI.

   Transforms clean, agent-friendly hiccup into Datastar-compatible HTML.

   Agent writes:
     [:button {:on:click :increment} \"Add\"]
     [:input {:field :user-name}]

   Framework transforms to:
     [:button {:data-on:click \"@post('/ns/seon.example/increment')\"} \"Add\"]
     [:input {:name \"user-name\" :data-bind:user-name true}]

   This is a pure transformation layer - no side effects, no state.

   Note: This namespace uses positional arguments rather than map-in/map-out
   because it's a pure transformation library where (transform-hiccup ns hiccup)
   is more natural than (transform-hiccup {:ns ns :hiccup hiccup})."
  (:require [clojure.walk :as walk]
            [clojure.string :as str]
            [malli.core :as m]))

;;; ---------------------------------------------------------------------------
;;; Schemas
;;; ---------------------------------------------------------------------------

(def Hiccup
  "Schema for hiccup data structures.
   Hiccup is recursive: vectors with keyword tags containing attrs and children."
  [:schema {:registry {::hiccup [:or
                                 :keyword
                                 :string
                                 :int
                                 :nil
                                 [:sequential [:ref ::hiccup]]
                                 [:vector [:cat :keyword [:? :map] [:* [:ref ::hiccup]]]]]}}
   [:ref ::hiccup]])

(def NamespaceSymbol
  "Schema for namespace symbols used in action URLs."
  :symbol)

(def AttrMap
  "Schema for hiccup attribute maps."
  [:map-of :keyword :any])

;;; ---------------------------------------------------------------------------
;;; Attribute Transformations
;;; ---------------------------------------------------------------------------

(defn- event-attr?
  "Check if a keyword is an event attribute like :on:click, :on:submit."
  [k]
  (and (keyword? k)
       (str/starts-with? (name k) "on:")))

(defn- extract-event-name
  "Extract event name from :on:click -> \"click\"."
  [k]
  (subs (name k) 3))

(defn- transform-event-attr
  "Transform {:on:click :fn-name} to Datastar format.

   Returns [new-key new-value] pair.
   Uses /ns/:namespace/:function URL pattern.
   If instance-id is provided, appends ?instance=id to URL."
  [ns-sym instance-id k v]
  (let [event (extract-event-name k)
        fn-name (if (keyword? v) (name v) (str v))
        base-url (str "/ns/" ns-sym "/" fn-name)
        url (if instance-id
              (str base-url "?instance=" instance-id)
              base-url)
        datastar-key (keyword (str "data-on:" event))]
    [datastar-key (str "@post('" url "')")]))

(defn- transform-field-attr
  "Transform {:field :name} to Datastar bind format.

   Uses KEY SYNTAX (data-bind:signalName) because Datastar's value syntax
   evaluates as a JS expression, which breaks with namespaced keys containing
   dots and slashes (e.g. seon.getting-started/exercise).

   For qualified keywords like :seon.getting-started/exercise, we use only
   the name part ('exercise') as the signal name. The server re-namespaces
   signals based on the target function's namespace when handling POSTs.

   Key syntax applies camelCase conversion (user-input → userInput) which
   is reversed by the server's camel->kebab in extract-signals.

   Returns map of attributes to merge."
  [field-name other-attrs]
  (let [signal-name (name field-name)
        bind-key (keyword (str "data-bind:" signal-name))]
    (merge
     {:name signal-name
      bind-key true}
     (dissoc other-attrs :field))))

(defn transform-attrs
  "Transform a single attribute map.

   ns-sym      - The namespace symbol for action URLs
   attrs       - The attribute map to transform
   instance-id - Optional instance ID to include in action URLs

   Returns transformed attribute map."
  {:malli/schema [:=> [:cat NamespaceSymbol [:maybe AttrMap] [:? [:maybe :string]]] [:maybe AttrMap]]}
  ([ns-sym attrs]
   (transform-attrs ns-sym attrs nil))
  ([ns-sym attrs instance-id]
   (if-not (map? attrs)
     attrs
     (if (contains? attrs :field)
       ;; Field attribute gets special handling
       (transform-field-attr (:field attrs) (dissoc attrs :field))
       ;; Process other attributes
       (reduce-kv
        (fn [m k v]
          (if (event-attr? k)
            (let [[new-k new-v] (transform-event-attr ns-sym instance-id k v)]
              (assoc m new-k new-v))
            (assoc m k v)))
        {}
        attrs)))))

;;; ---------------------------------------------------------------------------
;;; Hiccup Walking
;;; ---------------------------------------------------------------------------

(defn- hiccup-element?
  "Check if form is a hiccup element vector like [:div ...] or [:button {:class \"x\"} ...]."
  [form]
  (and (vector? form)
       (not (map-entry? form))
       (keyword? (first form))))

(defn- has-attrs?
  "Check if hiccup element has an attribute map."
  [form]
  (and (> (count form) 1)
       (map? (second form))))

(defn- collect-field-defaults
  "Walk hiccup and collect :field names with their default :value attrs.
   Uses only the name part of qualified keywords (namespace is implicit from URL).
   Returns a map of {signal-name default-value-str}."
  [hiccup]
  (let [fields (atom {})]
    (walk/postwalk
     (fn [form]
       (when (and (hiccup-element? form) (has-attrs? form))
         (let [attrs (second form)]
           (when-let [field (:field attrs)]
             (let [signal-name (name field)
                   default (get attrs :value "")]
               (swap! fields assoc signal-name (str default))))))
       form)
     hiccup)
    @fields))

(defn- inject-data-signals
  "Wrap transformed hiccup with a data-signals container if any fields exist.
   Datastar requires data-signals on a parent element to initialize the reactive store
   that data-bind attributes read from and write to."
  [hiccup field-defaults]
  (if (empty? field-defaults)
    hiccup
    (let [signals-json (str "{"
                            (str/join ", "
                                      (map (fn [[k v]]
                                             (str "\"" k "\": \"" v "\""))
                                           field-defaults))
                            "}")]
      ;; If hiccup is a vector starting with a tag, wrap its content
      ;; by adding data-signals to the root element's attrs
      (if (hiccup-element? hiccup)
        (if (has-attrs? hiccup)
          (let [[tag attrs & children] hiccup]
            (into [tag (assoc attrs :data-signals signals-json)] children))
          ;; Element without attrs map - insert one
          (let [[tag & children] hiccup]
            (into [tag {:data-signals signals-json}] children)))
        ;; Otherwise wrap in a div
        (into [:div {:data-signals signals-json}] [hiccup])))))

(defn transform-hiccup
  "Transform hiccup tree, converting reactive attributes to Datastar format.
   Also injects data-signals on the root element to initialize Datastar's
   reactive store for any fields found in the tree.

   ns-sym      - The namespace symbol for action URLs (e.g., 'seon.trading)
   hiccup      - The hiccup data structure to transform
   instance-id - Optional instance ID to include in action URLs

   Returns transformed hiccup with Datastar attributes."
  {:malli/schema [:=> [:cat NamespaceSymbol :any [:? [:maybe :string]]] :any]}
  ([ns-sym hiccup]
   (transform-hiccup ns-sym hiccup nil))
  ([ns-sym hiccup instance-id]
   (let [field-defaults (collect-field-defaults hiccup)
         transformed (walk/postwalk
                      (fn [form]
                        (if (and (hiccup-element? form) (has-attrs? form))
                          (let [[tag attrs & children] form
                                new-attrs (transform-attrs ns-sym attrs instance-id)]
                            (into [tag new-attrs] children))
                          form))
                      hiccup)]
     (inject-data-signals transformed field-defaults))))

;;; ---------------------------------------------------------------------------
;;; Convenience Functions
;;; ---------------------------------------------------------------------------

(defn make-transformer
  "Create a transformer function bound to a specific namespace.

   Returns a function that takes hiccup and returns transformed hiccup."
  {:malli/schema [:=> [:cat NamespaceSymbol] [:=> [:cat :any] :any]]}
  [ns-sym]
  (fn [hiccup]
    (transform-hiccup ns-sym hiccup)))

(comment
  ;; Example usage:

  (transform-hiccup 'seon.trading
                    [:div
                     [:h1 "Trading Signals"]
                     [:ul (for [s ["AAPL" "GOOG"]] [:li s])]
                     [:form {:on:submit :add-signal!}
                      [:input {:field :symbol :placeholder "Symbol"}]
                      [:input {:field :price :type "number"}]
                      [:button {:on:click :add-signal!} "Add"]]])

  ;; =>
  ;; [:div
  ;;  [:h1 "Trading Signals"]
  ;;  [:ul ([:li "AAPL"] [:li "GOOG"])]
  ;;  [:form {:data-on:submit "@post('/ns/seon.trading/add-signal!')"}
  ;;   [:input {:name "symbol" :data-bind:symbol true :placeholder "Symbol"}]
  ;;   [:input {:name "price" :data-bind:price true :type "number"}]
  ;;   [:button {:data-on:click "@post('/ns/seon.trading/add-signal!')"} "Add"]]]
  )
