(ns seon.ns.source
  "Parse namespace source into persisted namespace facts.

   This namespace owns the source-side contract for `:seon.ns/*` namespace
   facts and their `:seon.ns.require/*` component attributes. The persisted
   require keys retain the `seon.ns.require` namespace because they describe
   one require edge; the functions that parse and validate those edges live
   here at the namespace-source boundary."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [seon.schema :as schema]))

(schema/register! :seon.ns.require/target :symbol)
(schema/register! :seon.ns.require/alias :symbol)
(schema/register! :seon.ns.require/refers [:set :symbol])
(schema/register! :seon.ns.require/refer-all? :boolean)
(schema/register! :seon.ns.require/as-alias? :boolean)

(schema/register! ::require-edge
                  [:map {:seon.db/entity true}
                   [:seon.ns.require/target :seon.ns.require/target]
                   [:seon.ns.require/alias {:optional true} :seon.ns.require/alias]
                   [:seon.ns.require/refers {:optional true} :seon.ns.require/refers]
                   [:seon.ns.require/refer-all? {:optional true} :seon.ns.require/refer-all?]
                   [:seon.ns.require/as-alias? {:optional true} :seon.ns.require/as-alias?]])
(schema/register! ::require-edges [:set ::require-edge])

(schema/register! :seon.ns/doc :string)
(schema/register! :seon.ns/summary [:string {:min 1}])
(schema/register! ::namespace-info
                  [:map
                   [:seon.ns/doc {:optional true} :seon.ns/doc]
                   [:seon.ns/summary {:optional true} :seon.ns/summary]
                   [:seon.ns/require-edges ::require-edges]])

(defn- require-edges-from-form
  "Return the reified require edges declared by one parsed namespace form."
  [form]
  (let [reqs (->> form
                  (filter seq?)
                  (some #(when (= :require (first %)) (rest %))))]
    (into #{}
          (keep (fn [r]
                  (cond
                    (symbol? r)
                    {:seon.ns.require/target r}

                    (and (vector? r) (symbol? (first r)))
                    (let [tns  (first r)
                          opts (try (apply hash-map (rest r))
                                    (catch :default _ {}))
                          as   (:as opts)
                          asa  (:as-alias opts)
                          refr (:refer opts)]
                      (cond-> {:seon.ns.require/target tns}
                        (symbol? as) (assoc :seon.ns.require/alias as)
                        (and (symbol? asa) (not (symbol? as)))
                        (assoc :seon.ns.require/alias asa
                               :seon.ns.require/as-alias? true)
                        (sequential? refr)
                        (assoc :seon.ns.require/refers (set refr))
                        (= :all refr)
                        (assoc :seon.ns.require/refer-all? true)))

                    :else nil)))
          (or reqs []))))

(defn namespace-info-from-source
  "Derive namespace documentation and require edges from source."
  {:malli/schema [:=> [:cat :string] ::namespace-info]}
  [source]
  (try
    (let [form (reader/read-string source)]
      (if (and (seq? form) (= 'ns (first form)))
        (let [doc (when (string? (nth form 2 nil)) (nth form 2))
              summary (some-> doc str/split-lines first str/trim not-empty)]
          (cond-> {:seon.ns/require-edges (require-edges-from-form form)}
            (some? doc) (assoc :seon.ns/doc doc)
            summary (assoc :seon.ns/summary summary)))
        {:seon.ns/require-edges #{}}))
    (catch :default _
      {:seon.ns/require-edges #{}})))

(defn require-edges-from-source
  "Parse namespace source into its reified require-edge set."
  {:malli/schema [:=> [:cat :string] ::require-edges]}
  [source]
  (:seon.ns/require-edges (namespace-info-from-source source)))
