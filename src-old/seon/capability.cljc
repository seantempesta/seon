(ns seon.capability
  "Canonical installed-leaf inventory derived at capability installation."
  (:require [seon.content-hash :as content-hash]
            [seon.schema :as schema]))

(schema/register! ::tier :keyword)
(schema/register! ::binding :string)
(schema/register! ::effect [:enum :pure :read :idempotent :external])
(schema/register! ::remote? :boolean)
(schema/register!
 ::installed-leaf
 [:map {:closed true}
  [::binding ::binding]
  [::effect ::effect]
  [::remote? ::remote?]])
(schema/register! ::installed-leaves [:vector ::installed-leaf])
(schema/register!
 :seon.execution.inventory/tier
 [:map {:closed true}
  [:seon.execution.inventory/tier ::tier]
  [:seon.execution.inventory/bindings [:set :string]]
  [:seon.execution.inventory/remote-bindings [:set :string]]
  [:seon.execution.inventory/pure-bindings [:set :string]]
  [:seon.execution.inventory/digest :string]])
(schema/register!
 ::available-artifact-inventory
 [:map {:closed true}
  [:seon.execution.inventory/availability [:= :available]]
  [:seon.execution.inventory/exports-by-tier
   [:map-of ::tier [:set :string]]]
  [:seon.execution.inventory/digest :string]])
(schema/register!
 ::artifact-inventory-list
 [:vector {:min 1} ::available-artifact-inventory])

(defn installation-leaves
  "Describe bindings while one namespace installation is performed."
  {:malli/schema
   [:=> [:catn [::library :symbol] [::wrappers :map]]
    ::installed-leaves]}
  [library wrappers]
  (mapv
   (fn [[function-symbol wrapper]]
     {::binding (str (symbol (str library) (str function-symbol)))
      ::effect (or (:seon.capability/effect (meta wrapper)) :external)
      ::remote? false})
   (sort-by (comp str key) wrappers)))

(defn installed-leaf-inventory
  "Enumerate one tier's installed bindings and canonical digest."
  {:malli/schema
   [:=> [:catn [::tier ::tier] [::installed-leaves ::installed-leaves]]
    :seon.execution.inventory/tier]}
  [tier installed-leaves]
  (let [installed-leaves
        (vec (sort-by (juxt ::binding ::effect ::remote?)
                      (set installed-leaves)))
        bindings (into #{} (map ::binding) installed-leaves)
        remote-bindings
        (into #{} (comp (filter ::remote?) (map ::binding)) installed-leaves)
        pure-bindings
        (into #{} (comp (filter #(= :pure (::effect %)))
                        (map ::binding))
              installed-leaves)]
    {:seon.execution.inventory/tier tier
     :seon.execution.inventory/bindings bindings
     :seon.execution.inventory/remote-bindings remote-bindings
     :seon.execution.inventory/pure-bindings pure-bindings
     :seon.execution.inventory/digest
     (content-hash/sha-256
      (pr-str
       (mapv (juxt ::binding ::effect ::remote?) installed-leaves)))}))

(defn installed-artifact-inventory
  "Project the claimant registry inventory as exact artifact exports.

   The wrapper installer is the JVM claimant's one artifact enumerator. A
   separate JVM build analysis is warranted only if a reachable compiled
   terminal is proven absent from these installed bindings."
  {:malli/schema
   [:=> [:cat :seon.execution.inventory/tier]
    ::available-artifact-inventory]}
  [inventory]
  {:seon.execution.inventory/availability :available
   :seon.execution.inventory/exports-by-tier
   {(:seon.execution.inventory/tier inventory)
    (:seon.execution.inventory/bindings inventory)}
   :seon.execution.inventory/digest
   (:seon.execution.inventory/digest inventory)})

(defn merge-artifact-inventories
  "Merge exact per-tier artifact exports into one canonical inventory."
  {:malli/schema
   [:=> [:cat ::artifact-inventory-list]
    ::available-artifact-inventory]}
  [inventories]
  (let [exports
        (apply merge-with
               into
               (map :seon.execution.inventory/exports-by-tier inventories))
        digests
        (mapv (fn [inventory]
                [(:seon.execution.inventory/exports-by-tier inventory)
                 (:seon.execution.inventory/digest inventory)])
              (sort-by
               #(pr-str
                 (:seon.execution.inventory/exports-by-tier %))
               inventories))]
    {:seon.execution.inventory/availability :available
     :seon.execution.inventory/exports-by-tier exports
     :seon.execution.inventory/digest
     (if (= 1 (count inventories))
       (:seon.execution.inventory/digest (first inventories))
       (content-hash/sha-256 (pr-str digests)))}))
