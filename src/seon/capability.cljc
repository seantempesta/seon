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
  [:seon.execution.inventory/bindings [:set :string]]
  [:seon.execution.inventory/remote-bindings [:set :string]]
  [:seon.execution.inventory/pure-bindings [:set :string]]
  [:seon.execution.inventory/digest :string]])

(defn installation-leaves
  "Describe bindings while one namespace installation is performed."
  {:malli/schema
   [:=> [:catn [::library :symbol] [::wrappers :map]]
    ::installed-leaves]}
  [library wrappers]
  (mapv
   (fn [[function-symbol wrapper]]
     {::binding (str (symbol (str library) (str function-symbol)))
      ::effect (or (:seon.host.context/effect wrapper) :external)
      ::remote? false})
   (sort-by (comp str key) wrappers)))

(defn installed-leaf-inventory
  "Enumerate one tier's installed bindings and canonical digest."
  {:malli/schema
   [:=> [:catn [::tier ::tier] [::installed-leaves ::installed-leaves]]
    :seon.execution.inventory/tier]}
  [_tier installed-leaves]
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
    {:seon.execution.inventory/bindings bindings
     :seon.execution.inventory/remote-bindings remote-bindings
     :seon.execution.inventory/pure-bindings pure-bindings
     :seon.execution.inventory/digest
     (content-hash/sha-256
      (pr-str
       (mapv (juxt ::binding ::effect ::remote?) installed-leaves)))}))
