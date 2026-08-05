(ns seon.program
  "Pure declaration identities and exact-row ownership for program rows."
  (:require [malli.core :as m]
            [seon.schema :as schema]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])))

(def identity-attributes
  "Program-row identity attributes in deterministic admission order."
  [:seon.ns/name :seon.fn/sym :seon.schema/key :seon.test/sym
   :seon.def/id])

(def shapes
  "Program-row shapes keyed by their database identity attribute."
  {:seon.ns/name
   {:seon.program/identity-attribute :seon.ns/name
    :seon.program/source-attribute :seon.ns/source
    :seon.program/owned-attributes
    [:seon.ns/name :seon.ns/source :seon.ns/doc
     :seon.ns/requires :seon.ns/aliases :seon.ns/imports :seon.ns/refers
     :seon.schema.admission/source]}
   :seon.fn/sym
   {:seon.program/identity-attribute :seon.fn/sym
    :seon.program/source-attribute :seon.fn/source
    :seon.program/owned-attributes
    [:seon.fn/sym :seon.fn/ns :seon.fn/source :seon.fn/arglists
     :seon.fn/doc :seon.fn/private? :seon.fn/spec :seon.fn/arities
     :seon.fn/ast :seon.fn/calls :seon.fn/keywords :seon.fn/workload
     :seon.fn/external-sink :seon.fn/projection-boundary
     :seon.effect/capability
     :seon.schema.admission/source]}
   :seon.schema/key
   {:seon.program/identity-attribute :seon.schema/key
    :seon.program/source-attribute :seon.schema/form
    :seon.program/owned-attributes :seon.program/schema-row-properties}
   :seon.test/sym
   {:seon.program/identity-attribute :seon.test/sym
    :seon.program/source-attribute :seon.test/source
    :seon.program/owned-attributes
    [:seon.test/sym :seon.test/ns :seon.test/source :seon.fn/calls
     :seon.fn/keywords :seon.test/subject
     :seon.schema.admission/source]}
   :seon.def/id
   {:seon.program/identity-attribute :seon.def/id
    :seon.program/source-attribute :seon.def/source
    :seon.program/owned-attributes
    [:seon.def/id :seon.def/ns :seon.def/name
     :seon.def/value-edn :seon.def/blob :seon.def/size
     :seon.def/source :seon.def/unrestorable-reason
     :seon.def/ordinal :seon.schema.admission/source]}})

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

(defn- read-edn
  [source]
  (#?(:clj edn/read-string :cljs reader/read-string) source))

(defn- component-id
  [function-symbol path]
  (str "seon.fn.contract/" function-symbol "/" (pr-str path)))

(defn- schema-reference-keys
  [compiled canonical-keys]
  (let [!references (volatile! #{})]
    (m/walk
     compiled
     (fn [schema _path _children _options]
       (when (m/-ref-schema? schema)
         (let [reference (m/-ref schema)]
           (when (contains? canonical-keys reference)
             (vswap! !references conj reference))))
       schema)
     {::m/walk-schema-refs #(not (contains? canonical-keys %))
      ::m/walk-refs #(not (contains? canonical-keys %))})
    @!references))

(defn- schema-references
  [compiled canonical-keys]
  (into #{}
        (map (fn [reference] [:seon.schema/key reference]))
        (schema-reference-keys compiled canonical-keys)))

(declare ast-node)

(defn- property-entries
  [function-symbol path properties]
  (into []
        (map-indexed
         (fn [order [k value]]
           {:db/id (component-id function-symbol
                                 (conj path :properties order))
            :seon.fn.ast.entry/order (long order)
            :seon.fn.ast.entry/key (pr-str k)
            :seon.fn.ast.entry/value-edn (pr-str value)}))
        (sort-by (comp pr-str key) properties)))

(defn- ast-entry
  [function-symbol path order entry-key ast properties references]
  (cond->
   {:db/id (component-id function-symbol path)
    :seon.fn.ast.entry/value
    (ast-node function-symbol (conj path :value) ast references)}
    (some? order) (assoc :seon.fn.ast.entry/order (long order))
    (some? entry-key) (assoc :seon.fn.ast.entry/key (pr-str entry-key))
    (seq properties)
    (assoc :seon.fn.ast.entry/properties
           (property-entries function-symbol path properties))))

(defn- scalar-entry
  [function-symbol path order entry-key value]
  (cond->
   {:db/id (component-id function-symbol path)
    :seon.fn.ast.entry/value-edn (pr-str value)}
    (some? order) (assoc :seon.fn.ast.entry/order (long order))
    (some? entry-key)
    (assoc :seon.fn.ast.entry/key (pr-str entry-key))))

(defn- ordered-ast-entries
  [function-symbol path asts references]
  (mapv (fn [order ast]
          (ast-entry function-symbol
                     (conj path order)
                     order nil ast nil references))
        (range)
        asts))

(defn- keyed-ast-entries
  [function-symbol path entries references]
  (->> entries
       (sort-by (fn [[k entry]] [(:order entry) (pr-str k)]))
       (mapv (fn [[k {:keys [order value properties]}]]
               (ast-entry function-symbol
                          (conj path order (pr-str k))
                          order k value properties references)))))

(defn- registry-entries
  [function-symbol path registry references]
  (->> registry
       (sort-by (comp pr-str key))
       (map-indexed
        (fn [order [k ast]]
          (ast-entry function-symbol
                     (conj path order (pr-str k))
                     order k ast nil references)))
       vec))

(defn- scalar-entries
  [function-symbol path values]
  (mapv (fn [order value]
          (scalar-entry function-symbol (conj path order) order nil value))
        (range)
        values))

(defn- ast-node
  [function-symbol path ast references]
  (let [properties (:properties ast)
        children (:children ast)
        ast-keys (:keys ast)
        registry (:registry ast)
        values (:values ast)
        scalar-or-schema-value (:value ast)]
    (cond->
     {:db/id (component-id function-symbol path)
      :seon.fn.ast/type (pr-str (:type ast))}
      (contains? ast :value)
      (assoc :seon.fn.ast/value
             (if (and (map? scalar-or-schema-value)
                      (contains? scalar-or-schema-value :type))
               (ast-entry function-symbol
                          (conj path :value)
                          nil nil scalar-or-schema-value nil references)
               (scalar-entry function-symbol
                             (conj path :value)
                             nil nil scalar-or-schema-value)))
      (:input ast)
      (assoc :seon.fn.ast/input
             (ast-node function-symbol (conj path :input)
                       (:input ast) references))
      (:output ast)
      (assoc :seon.fn.ast/output
             (ast-node function-symbol (conj path :output)
                       (:output ast) references))
      (:guard ast)
      (assoc :seon.fn.ast/guard
             (ast-node function-symbol (conj path :guard)
                       (:guard ast) references))
      (:child ast)
      (assoc :seon.fn.ast/child
             (ast-node function-symbol (conj path :child)
                       (:child ast) references))
      (:key ast)
      (assoc :seon.fn.ast/key
             (ast-node function-symbol (conj path :key)
                       (:key ast) references))
      (and (= :malli.core/schema (:type ast))
           (contains? references (:value ast)))
      (assoc :seon.fn.ast/ref [:seon.schema/key (:value ast)])
      (seq properties)
      (assoc :seon.fn.ast/properties
             (property-entries function-symbol path properties))
      (seq children)
      (assoc :seon.fn.ast/children
             (ordered-ast-entries function-symbol
                                  (conj path :children)
                                  children references))
      (seq ast-keys)
      (assoc :seon.fn.ast/keys
             (keyed-ast-entries function-symbol
                                (conj path :keys)
                                ast-keys references))
      (seq registry)
      (assoc :seon.fn.ast/registry
             (registry-entries function-symbol
                               (conj path :registry)
                               registry references))
      (seq values)
      (assoc :seon.fn.ast/values
             (scalar-entries function-symbol
                             (conj path :values)
                             values)))))

(defn- arity-ast-path
  [root-ast order]
  (if (= :function (:type root-ast))
    [:children order :value]
    []))

(defn- arity-row
  [function-symbol order root-ast info canonical-keys]
  (let [ast-path (arity-ast-path root-ast order)
        input-refs (schema-references (:input info) canonical-keys)
        output-refs (schema-references (:output info) canonical-keys)
        guard-refs (when-let [guard (:guard info)]
                     (schema-references guard canonical-keys))]
    (cond->
     {:db/id (component-id function-symbol [:arity order])
      :seon.fn.arity/order (long order)
      :seon.fn.arity/arity (pr-str (:arity info))
      :seon.fn.arity/min (long (:min info))
      :seon.fn.arity/input
      (component-id function-symbol (conj ast-path :input))
      :seon.fn.arity/output
      (component-id function-symbol (conj ast-path :output))}
      (contains? info :max) (assoc :seon.fn.arity/max (long (:max info)))
      (:guard info)
      (assoc :seon.fn.arity/guard
             (component-id function-symbol (conj ast-path :guard)))
      (seq input-refs) (assoc :seon.fn.arity/input-refs input-refs)
      (seq output-refs) (assoc :seon.fn.arity/output-refs output-refs)
      (seq guard-refs) (assoc :seon.fn.arity/guard-refs guard-refs))))

(defn contract-facts
  "Parsed query facts derived from one canonical function contract."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [:seon.program/function-symbol [:string {:min 1}]]
      [:seon.program/spec [:string {:min 1}]]
      [:seon.program/compile-options :map]
      [:seon.program/predicate-functions :map]
      [:seon.program/schema-keys [:set :keyword]]]]
    :map]}
  [{function-symbol :seon.program/function-symbol
    spec :seon.program/spec
    compile-options :seon.program/compile-options
    predicate-functions :seon.program/predicate-functions
    canonical-keys :seon.program/schema-keys}]
  (let [compiled (m/function-schema
                  (schema/bind-predicates (read-edn spec)
                                          predicate-functions)
                  compile-options)
        arities (m/-function-schema-arities compiled)
        root-ast (m/ast compiled)
        references (schema-reference-keys compiled canonical-keys)]
    {:seon.fn/arities
     (mapv (fn [order arity]
             (arity-row function-symbol order root-ast
                        (m/-function-info arity) canonical-keys))
           (range)
           arities)
     :seon.fn/ast (ast-node function-symbol [] root-ast references)}))

(defn with-contract-facts
  "Add parsed facts to one canonical contracted function row."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [:seon.program/row :map]
      [:seon.program/compile-options :map]
      [:seon.program/predicate-functions :map]
      [:seon.program/schema-keys [:set :keyword]]]]
    :map]}
  [{row :seon.program/row
    compile-options :seon.program/compile-options
    predicate-functions :seon.program/predicate-functions
    canonical-keys :seon.program/schema-keys}]
  (if-let [spec (:seon.fn/spec row)]
    (merge row
           (contract-facts
            {:seon.program/function-symbol (:seon.fn/sym row)
             :seon.program/spec spec
             :seon.program/compile-options compile-options
             :seon.program/predicate-functions predicate-functions
             :seon.program/schema-keys canonical-keys}))
    row))

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
      (let [owned-attributes
            (:seon.program/owned-attributes (shape identity-attribute))
            owned-attributes
            (if (= :seon.program/schema-row-properties owned-attributes)
              (into [] (filter qualified-keyword?) (keys row))
              owned-attributes)]
        (into {}
              (remove (fn [[attribute value]]
                        (or (nil? value)
                            (and (contains? #{:seon.ns/requires
                                              :seon.ns/aliases
                                              :seon.ns/imports
                                              :seon.ns/refers}
                                            attribute)
                                 (empty? value)))))
              (select-keys row owned-attributes))))))

(def ^:private declaration-required-attributes
  {:seon.ns/name [:seon.ns/source]
   :seon.fn/sym [:seon.fn/ns :seon.fn/source :seon.fn/arglists
                 :seon.fn/private?]
   :seon.schema/key [:seon.schema/form]
   :seon.test/sym [:seon.test/ns :seon.test/source]
   :seon.def/id [:seon.def/ns :seon.def/name
                      :seon.def/ordinal]})

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
          (:seon.def/id event) event
          :else nil)
        candidate
        (if (and (:seon.schema/key candidate)
                 (:seon.schema/form candidate))
          (let [schema-key (:seon.schema/key candidate)
                definition (read-edn (:seon.schema/form candidate))
                row (some #(when (= schema-key (:seon.schema/key %)) %)
                          (schema/canonical-schema-rows
                           (assoc (schema/registered-schemas)
                                  schema-key definition)))]
            (assoc row
                   :seon.schema.admission/source
                   (:seon.schema.admission/source candidate)))
          candidate)
        row (canonical-row candidate)]
    (when row
      (let [[identity-attribute _ :as program-identity] (row-identity row)
            missing
            (into []
                  (remove #(contains? row %))
                  (get declaration-required-attributes identity-attribute))]
        (when (seq missing)
          (declaration-refused!
           "A declaration row is missing required attributes."
           [program-identity]
           {:seon.program/missing-attributes missing}))))
    row))

(defn changed-attributes
  "Owned non-identity attributes whose exact values differ."
  {:malli/schema [:=> [:cat :map :map] [:vector :keyword]]}
  [current desired]
  (if-let [[identity-attribute _]
           (or (row-identity desired) (row-identity current))]
    (let [owned-attributes
          (:seon.program/owned-attributes (shape identity-attribute))
          owned-attributes
          (if (= :seon.program/schema-row-properties owned-attributes)
            (into #{}
                  (filter qualified-keyword?)
                  (concat (keys current) (keys desired)))
            owned-attributes)]
      (into
       []
       (comp
        (remove #{identity-attribute})
        (filter #(not= (get current %) (get desired %))))
       owned-attributes))
    []))

(def ^:private component-owned-attributes
  #{:seon.fn/arities :seon.fn/ast})

(defn exact-replacement-tx
  "Replace one declaration row, retracting owned component trees exactly."
  {:malli/schema
   [:=> [:cat [:map [:db/id :int]] :map]
    [:vector :seon.schema/value]]}
  [current desired]
  (let [entity-id (:db/id current)
        changed (changed-attributes current desired)]
    (into
     []
     (concat
      (map (fn [attribute]
             (if (contains? component-owned-attributes attribute)
               [:db.fn/retractAttribute entity-id attribute]
               [:db/retract entity-id attribute]))
           (sort (filter #(contains? current %) changed)))
      [(assoc desired :db/id entity-id)]))))

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
