(ns seon.program
  "Pure declaration identities and exact-row ownership for program rows.")

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
     :seon.ns/requires :seon.ns/aliases :seon.ns/refers]}
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
    [:seon.schema/key :seon.schema/form]}
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

(defn- row-identities
  [row]
  (into []
        (keep (fn [identity-attribute]
                (when-some [value (get row identity-attribute)]
                  [identity-attribute value])))
        identity-attributes))

(defn- declaration-refused!
  [message identities data]
  (throw
   (ex-info message
            (merge {:seon.error/kind :seon.program/declaration-refused
                    :seon.program/identities identities}
                   data))))

(defn canonical-row
  "The exact non-nil attributes owned by one declaration row."
  {:malli/schema [:=> [:cat [:maybe :map]] [:maybe :map]]}
  [row]
  (let [identities (row-identities row)]
    (when (> (count identities) 1)
      (declaration-refused!
       "A declaration row carries more than one identity family."
       identities
       {}))
    (when-let [[identity-attribute _] (first identities)]
      (into {}
            (remove (fn [[attribute value]]
                      (or (nil? value)
                          (and (contains? #{:seon.ns/requires
                                            :seon.ns/aliases
                                            :seon.ns/refers}
                                          attribute)
                               (empty? value)))))
            (select-keys row
                         (:seon.program/owned-attributes
                          (shape identity-attribute)))))))

(def ^:private declaration-required-attributes
  {:seon.ns/name [:seon.ns/source]
   :seon.fn/sym [:seon.fn/ns :seon.fn/source :seon.fn/arglists
                 :seon.fn/private?]
   :seon.schema/key [:seon.schema/form]
   :seon.test/sym [:seon.test/ns :seon.test/source]})

(defn declaration-row
  "Canonical declaration row for a reader event under a function policy.

  `:all` indexes every directly read top-level build function as future graph
  input;
  `:contracted` admits only runtime functions carrying a complete contract."
  {:malli/schema
   [:=> [:cat :map [:enum :all :contracted]] [:maybe :map]]}
  [event function-policy]
  (let [candidate
        (cond
          (:seon.ns/name event) event
          (and (:seon.fn/sym event)
               (or (= :all function-policy)
                   (and (= :contracted function-policy)
                        (:seon.fn/spec event))))
          event
          (:seon.schema/key event) event
          (:seon.test/sym event) event
          :else nil)
        row (canonical-row candidate)]
    (when row
      (let [[identity-attribute _ :as identity] (row-identity row)
            missing
            (into []
                  (remove #(contains? row %))
                  (get declaration-required-attributes identity-attribute))]
        (when (seq missing)
          (declaration-refused!
           "A declaration row is missing required attributes."
           [identity]
           {:seon.program/missing-attributes missing}))))
    row))

(defn changed-attributes
  "Owned non-identity attributes whose exact values differ."
  {:malli/schema [:=> [:cat :map :map] [:vector :keyword]]}
  [current desired]
  (if-let [[identity-attribute _]
           (or (row-identity desired) (row-identity current))]
    (into
     []
     (comp
      (remove #{identity-attribute})
      (filter #(not= (get current %) (get desired %))))
     (:seon.program/owned-attributes (shape identity-attribute)))
    []))

(defn deletion-row
  "Typed identities removed by one explicit REPL deletion event.

  `ns-unmap` removes the matching function and test identities. A reader-
  resolved `seon.schema/unregister!` removes one global schema identity."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [event]
  (let [form (:seon.sci.reader/form event)
        schema-key (:seon.sci.reader/schema-unregister-key event)
        removed-identities (:seon.sci.reader/ns-unmap-identities event)
        quoted-symbol
        (fn [value]
          (when (and (seq? value)
                     (= 'quote (first value))
                     (= 2 (count value))
                     (symbol? (second value)))
            (second value)))]
    (cond
      schema-key
      {:seon.program/delete-identities [[:seon.schema/key schema-key]]
       :seon.program/source (:seon.sci.reader/source event)}

      (seq removed-identities)
      (cond-> {:seon.program/delete-identities (vec removed-identities)
               :seon.program/source (:seon.sci.reader/source event)}
        (:seon.sci.reader/ns event)
        (assoc :seon.program/ns
               [:seon.ns/name (:seon.sci.reader/ns event)]))

      (:seon.sci.reader/ns-unmap? event)
      (when (and (seq? form)
                 (= 3 (count form)))
        (when-let [namespace-name (quoted-symbol (second form))]
          (when-let [declaration-name (quoted-symbol (nth form 2))]
            (let [qualified (str (symbol (str namespace-name)
                                         (str declaration-name)))]
              {:seon.program/delete-identities
               [[:seon.fn/sym qualified]
                [:seon.test/sym qualified]]
               :seon.program/source (:seon.sci.reader/source event)
               :seon.program/ns [:seon.ns/name namespace-name]}))))

      :else nil)))
