(ns seon.program
  "Pure declaration identities and exact-row ownership for the program graph.")

(def identity-attributes
  "Program-row identity attributes in deterministic admission order."
  [:seon.ns/name :seon.fn/sym :seon.schema/key :seon.test/sym])

(def shapes
  "Program-row shapes keyed by their database identity attribute."
  {:seon.ns/name
   {:seon.program/identity-attribute :seon.ns/name
    :seon.program/source-attribute :seon.ns/source
    :seon.program/owned-attributes
    [:seon.ns/name :seon.ns/source :seon.ns/doc
     :seon.ns/require-edges]}
   :seon.fn/sym
   {:seon.program/identity-attribute :seon.fn/sym
    :seon.program/source-attribute :seon.fn/source
    :seon.program/owned-attributes
    [:seon.fn/sym :seon.fn/ns :seon.fn/source :seon.fn/arglists
     :seon.fn/doc :seon.fn/private? :seon.fn/spec :seon.fn/workload]}
   :seon.schema/key
   {:seon.program/identity-attribute :seon.schema/key
    :seon.program/source-attribute :seon.schema/form
    :seon.program/owned-attributes
    [:seon.schema/key :seon.schema/ns :seon.schema/form]}
   :seon.test/sym
   {:seon.program/identity-attribute :seon.test/sym
    :seon.program/source-attribute :seon.test/source
    :seon.program/owned-attributes
    [:seon.test/sym :seon.test/ns :seon.test/source]}})

(defn shape
  "The program shape owned by `identity-attribute`."
  {:malli/schema [:=> [:cat :keyword] [:maybe :map]]}
  [identity-attribute]
  (get shapes identity-attribute))

(defn row-identity
  "The `[identity-attribute value]` pair carried by `row`."
  {:malli/schema
   [:=> [:cat [:maybe :map]]
    [:maybe [:tuple :keyword :seon.schema/value]]]}
  [row]
  (some (fn [identity-attribute]
          (when-some [value (get row identity-attribute)]
            [identity-attribute value]))
        identity-attributes))

(defn canonical-row
  "The exact non-nil attributes owned by one declaration row."
  {:malli/schema [:=> [:cat [:maybe :map]] [:maybe :map]]}
  [row]
  (when-let [[identity-attribute _] (row-identity row)]
    (into {}
          (remove (fn [[attribute value]]
                    (or (nil? value)
                        (and (= :seon.ns/require-edges attribute)
                             (empty? value)))))
          (select-keys row
                       (:seon.program/owned-attributes
                        (shape identity-attribute))))))

(defn declaration-row
  "Canonical declaration row for a reader event under a function policy.

  `:all` indexes every build-time function for graph reachability;
  `:contracted` admits only runtime functions carrying a complete contract."
  {:malli/schema
   [:=> [:cat :map [:enum :all :contracted]] [:maybe :map]]}
  [event function-policy]
  (canonical-row
   (cond
     (:seon.ns/name event) event
     (and (:seon.fn/sym event)
          (or (= :all function-policy)
              (and (= :contracted function-policy)
                   (:seon.fn/spec event))))
     event
     (:seon.schema/key event) event
     (:seon.test/sym event) event
     :else nil)))

(defn changed-attributes
  "Owned non-identity attributes whose exact values differ."
  {:malli/schema [:=> [:cat :map :map] [:vector :keyword]]}
  [current desired]
  (if-let [[identity-attribute _]
           (or (row-identity desired) (row-identity current))]
    (into
     []
     (filter #(not= (get current %) (get desired %)))
     (disj (set (:seon.program/owned-attributes
                 (shape identity-attribute)))
           identity-attribute))
    []))

(defn deletion-row
  "Typed function and test identities removed by one `ns-unmap` event."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [event]
  (let [form (:seon.sci.reader/form event)
        quoted-symbol
        (fn [value]
          (when (and (seq? value)
                     (= 'quote (first value))
                     (= 2 (count value))
                     (symbol? (second value)))
            (second value)))]
    (when (and (seq? form)
               (= 'ns-unmap (first form))
               (= 3 (count form)))
      (when-let [namespace-name (quoted-symbol (second form))]
        (when-let [declaration-name (quoted-symbol (nth form 2))]
          (let [qualified (str (symbol (str namespace-name)
                                       (str declaration-name)))]
            {:seon.program/delete-identities
             [[:seon.fn/sym qualified]
              [:seon.test/sym qualified]]
             :seon.program/source (:seon.sci.reader/source event)
             :seon.program/ns [:seon.ns/name namespace-name]}))))))
