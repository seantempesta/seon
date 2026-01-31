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

   Uses VALUE SYNTAX (data-bind=\"name\") instead of KEY SYNTAX (data-bind:name)
   because Datastar's key syntax applies camelCase conversion (item-name → itemName)
   but value syntax preserves the name exactly.

   Returns map of attributes to merge."
  [field-name other-attrs]
  (let [field-str (if (qualified-keyword? field-name)
                    ;; Preserve namespace: :seon.trading/symbol → \"seon.trading/symbol\"
                    (str (namespace field-name) "/" (name field-name))
                    ;; Simple keyword: :item-name → \"item-name\"
                    (name field-name))]
    (merge
     {:name field-str
      :data-bind field-str}  ; Value syntax - no camelCase conversion
     ;; Preserve other attributes like :type, :placeholder, etc.
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

(defn transform-hiccup
  "Transform hiccup tree, converting reactive attributes to Datastar format.

   ns-sym      - The namespace symbol for action URLs (e.g., 'seon.trading)
   hiccup      - The hiccup data structure to transform
   instance-id - Optional instance ID to include in action URLs

   Returns transformed hiccup with Datastar attributes."
  {:malli/schema [:=> [:cat NamespaceSymbol :any [:? [:maybe :string]]] :any]}
  ([ns-sym hiccup]
   (transform-hiccup ns-sym hiccup nil))
  ([ns-sym hiccup instance-id]
   (walk/postwalk
    (fn [form]
      (if (and (hiccup-element? form) (has-attrs? form))
        (let [[tag attrs & children] form
              new-attrs (transform-attrs ns-sym attrs instance-id)]
          (into [tag new-attrs] children))
        form))
    hiccup)))

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
