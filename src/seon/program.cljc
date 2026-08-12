(ns seon.program
  "Pure declaration identities and exact-row ownership for program rows."
  (:require [malli.core :as m]
            [seon.fn.schema-shape :as schema-shape]
            [seon.fn.signature :as signature]
            [seon.schema :as schema]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])))

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
     :seon.ns/requires :seon.ns/aliases :seon.ns/imports :seon.ns/refers
     :seon.schema.admission/source]}
   :seon.fn/sym
   {:seon.program/identity-attribute :seon.fn/sym
    :seon.program/source-attribute :seon.fn/source
    :seon.program/owned-attributes
    [:seon.fn/sym :seon.fn/ns :seon.fn/source :seon.fn/arglists
     :seon.fn/arglists-override?
     :seon.fn/doc :seon.fn/private? :seon.fn/spec :seon.test/subject
     :seon.fn/arities
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
     :seon.fn/keywords :seon.test/usage :seon.test/subject
     :seon.schema.admission/source]}})

(defn shape
  "The program shape owned by `identity-attribute`."
  {:malli/schema
   [:=> [:cat :seon.program/identity-attribute]
    [:maybe :seon.program/shape]]}
  [identity-attribute]
  (get shapes identity-attribute))

(defn row-identity
  "The `[identity-attribute value]` pair carried by `row`."
  {:malli/schema
   [:=> [:cat [:maybe :map]]
    [:maybe :seon.program/identity]]}
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

(defn- binding-id
  [function-symbol arity-order argument-index path]
  (component-id function-symbol
                [:arity arity-order :argument argument-index :binding path]))

(declare binding-row)

(defn- binding-child
  [function-symbol arity-order argument-index path order role binding]
  {:db/id (binding-id function-symbol arity-order argument-index
                      (conj path :child order))
   :seon.fn.binding.child/order (long order)
   :seon.fn.binding.child/role role
   :seon.fn.binding.child/binding
   (binding-row function-symbol arity-order argument-index
                (conj path :child order :binding) binding)})

(defn- shorthand-source-key
  [directive spelling binding-symbol]
  (let [binding-namespace (or (namespace binding-symbol)
                              (namespace directive))
        binding-name (name binding-symbol)]
    (case spelling
      :keys (if binding-namespace
              (keyword binding-namespace binding-name)
              (keyword binding-name))
      :strs binding-name
      :syms (if binding-namespace
              (symbol binding-namespace binding-name)
              (symbol binding-name)))))

(defn- shorthand-binding
  [binding-symbol]
  (symbol (name binding-symbol)))

(defn- map-binding-entries
  [binding]
  (let [directive-name (fn [value]
                         (when (keyword? value) (keyword (name value))))
        defaults (some (fn [[directive value]]
                         (when (= :or (directive-name directive)) value))
                       binding)
        as-binding (some (fn [[directive value]]
                           (when (= :as (directive-name directive)) value))
                         binding)
        explicit
        (for [[local source-key] binding
              :when (not (contains? #{:keys :strs :syms :or :as}
                                    (directive-name local)))]
          {:seon.fn.binding.entry/spelling :explicit
           :seon.fn.binding.entry/source-key source-key
           :seon.fn.binding.entry/local-binding local})
        shorthand
        (for [[directive binding-symbols] binding
              :let [spelling (directive-name directive)]
              :when (contains? #{:keys :strs :syms} spelling)
              binding-symbol binding-symbols]
          {:seon.fn.binding.entry/spelling spelling
           :seon.fn.binding.entry/source-key
           (shorthand-source-key directive spelling binding-symbol)
           :seon.fn.binding.entry/local-binding
           (shorthand-binding binding-symbol)})]
    (->> (concat explicit shorthand)
         (sort-by (juxt (comp schema/canonical-data-string
                             :seon.fn.binding.entry/source-key)
                        (comp str :seon.fn.binding.entry/spelling)
                        (comp pr-str :seon.fn.binding.entry/local-binding)))
         (map-indexed
          (fn [order entry]
            (let [local-binding (:seon.fn.binding.entry/local-binding entry)]
              (cond-> (assoc entry :seon.fn.binding.entry/order (long order))
                (and (symbol? local-binding)
                     (contains? defaults local-binding))
                (assoc :seon.fn.binding.entry/default-edn
                       (pr-str (get defaults local-binding)))))))
         (#(with-meta % {:seon.fn.binding/as as-binding})))))

(defn- binding-entry-row
  [function-symbol arity-order argument-index path
   {order :seon.fn.binding.entry/order
    spelling :seon.fn.binding.entry/spelling
    source-key :seon.fn.binding.entry/source-key
    local-binding :seon.fn.binding.entry/local-binding
    default-edn :seon.fn.binding.entry/default-edn}]
  (cond->
   (merge
    {:db/id (binding-id function-symbol arity-order argument-index
                        (conj path :entry order))
     :seon.fn.binding.entry/order order
     :seon.fn.binding.entry/spelling spelling
     :seon.fn.binding.entry/binding
     (binding-row function-symbol arity-order argument-index
                  (conj path :entry order :binding) local-binding)}
    (schema-shape/typed-key-facts source-key))
    default-edn
    (assoc :seon.fn.binding.entry/default-edn default-edn)))

(defn- sequential-binding-children
  [function-symbol arity-order argument-index path binding]
  (loop [remaining (seq binding), order 0, children []]
    (if-not remaining
      children
      (let [value (first remaining)]
        (cond
          (= '& value)
          (if (and (next remaining) (nil? (next (next remaining))))
            (conj children
                  (binding-child function-symbol arity-order argument-index
                                 path order :rest (second remaining)))
            (throw
             (ex-info "Sequential destructuring has a malformed rest binding."
                      {:seon.error/kind :seon.fn.binding/unsupported
                       :seon.fn.binding/form (pr-str binding)})))

          (= :as value)
          (if (and (next remaining) (nil? (next (next remaining))))
            (conj children
                  (binding-child function-symbol arity-order argument-index
                                 path order :as (second remaining)))
            (throw
             (ex-info "Sequential destructuring has a malformed :as binding."
                      {:seon.error/kind :seon.fn.binding/unsupported
                       :seon.fn.binding/form (pr-str binding)})))

          :else
          (recur (next remaining) (inc order)
                 (conj children
                       (binding-child function-symbol arity-order argument-index
                                      path order :element value))))))))

(defn- binding-row
  [function-symbol arity-order argument-index path binding]
  (cond
    (symbol? binding)
    {:db/id (binding-id function-symbol arity-order argument-index path)
     :seon.fn.binding/form (pr-str binding)
     :seon.fn.binding/shape :symbol
     :seon.fn.binding/symbol binding}

    (map? binding)
    (let [entries (map-binding-entries binding)
          as-binding (:seon.fn.binding/as (meta entries))]
      (cond->
       {:db/id (binding-id function-symbol arity-order argument-index path)
        :seon.fn.binding/form (pr-str binding)
        :seon.fn.binding/shape :map}
        (seq entries)
        (assoc :seon.fn.binding/entries
               (mapv #(binding-entry-row function-symbol arity-order
                                         argument-index path %)
                     entries))
        as-binding
        (assoc :seon.fn.binding/children
               [(binding-child function-symbol arity-order argument-index
                               path 0 :as as-binding)])))

    (sequential? binding)
    (let [children (sequential-binding-children
                    function-symbol arity-order argument-index path binding)]
      (cond->
       {:db/id (binding-id function-symbol arity-order argument-index path)
        :seon.fn.binding/form (pr-str binding)
        :seon.fn.binding/shape :sequential}
        (seq children) (assoc :seon.fn.binding/children children)))

    :else
    (throw
     (ex-info "Function declaration has an unsupported binding form."
              {:seon.error/kind :seon.fn.binding/unsupported
               :seon.fn.binding/form (pr-str binding)}))))

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

(defn- signature-key
  [signature]
  (if (:seon.fn.signature/variadic? signature)
    [:variadic (:seon.fn.signature/min signature)]
    [:fixed (:seon.fn.signature/min signature)]))

(defn- malli-arity-key
  [info]
  (if (= :varargs (:arity info))
    [:variadic (:min info)]
    [:fixed (:min info)]))

(defn- one-by-key
  [values key-fn reason]
  (let [grouped (group-by key-fn values)]
    (when-let [[join-key matches]
               (some (fn [[join-key matches]]
                       (when (not= 1 (count matches)) [join-key matches]))
                     grouped)]
      (throw
       (ex-info "Function source and Malli contract are not bijective."
                {:seon.error/kind :seon.fn/signature-refused
                 :seon.fn.signature/reason reason
                 :seon.fn.signature/join-key join-key
                 :seon.fn.signature/count (count matches)})))
    (into {} (map (fn [[join-key matches]]
                    [join-key (first matches)])) grouped)))

(defn- input-slots
  [input]
  (case (m/type input)
    :cat
    (mapv (fn [child] {:seon.fn.argument/compiled-schema child})
          (m/children input))

    :catn
    (mapv (fn [[label _properties child]]
            {:seon.fn.argument/label label
             :seon.fn.argument/compiled-schema child})
          (m/children input))

    (throw
     (ex-info "Function input contract must be a :cat or :catn schema."
              {:seon.error/kind :seon.fn/signature-refused
               :seon.fn.signature/reason :unsupported-input-schema
               :seon.fn.signature/input (pr-str (m/form input))}))))

(defn- label-facts
  [label]
  (cond-> {:seon.fn.argument/label-edn (pr-str label)}
    (keyword? label) (assoc :seon.fn.argument/label-keyword label)
    (string? label) (assoc :seon.fn.argument/label-string label)
    (symbol? label) (assoc :seon.fn.argument/label-symbol label)))

(defn- rest-element-schema
  [tail]
  (when (contains? #{:* :+ :repeat} (m/type tail))
    (let [children (m/children tail)]
      (when (and (= 1 (count children)) (m/schema? (first children)))
        (first children)))))

(defn- argument-row
  [function-symbol arity-order source-signature order
   {compiled :seon.fn.argument/compiled-schema
    label :seon.fn.argument/label}
   schema-forms predicate-functions]
  (let [bindings (:seon.fn.signature/bindings source-signature)
        rest-index (:seon.fn.signature/rest-index source-signature)
        rest? (= order rest-index)
        shape (schema-shape/shape-row compiled schema-forms predicate-functions)
        repeated (when rest? (rest-element-schema compiled))]
    (cond->
     {:db/id (component-id function-symbol [:arity arity-order :argument order])
      :seon.fn.argument/order (long order)
      :seon.fn.argument/index (long order)
      :seon.fn.argument/rest? rest?
      :seon.fn.argument/binding
      (binding-row function-symbol arity-order order [] (nth bindings order))
      :seon.fn.argument/schema shape}
      rest? (assoc :seon.fn.argument/rest-tail-schema shape)
      repeated
      (assoc :seon.fn.argument/rest-element-schema
             (schema-shape/shape-row repeated schema-forms predicate-functions))
      (some? label) (merge (label-facts label)))))

(defn- arity-row
  [function-symbol order root-ast info canonical-keys source-signature
   schema-forms predicate-functions]
  (let [ast-path (arity-ast-path root-ast order)
        input-refs (schema-references (:input info) canonical-keys)
        output-refs (schema-references (:output info) canonical-keys)
        guard-refs (when-let [guard (:guard info)]
                     (schema-references guard canonical-keys))
        slots (input-slots (:input info))
        expected-count (count (:seon.fn.signature/bindings source-signature))
        _ (when-not (= expected-count (count slots))
            (throw
             (ex-info "A source binding has no matching Malli input slot."
                      {:seon.error/kind :seon.fn/signature-refused
                       :seon.fn.signature/reason :unmatched-slot
                       :seon.fn.signature/source-count expected-count
                       :seon.fn.signature/malli-count (count slots)})))
        arguments (mapv (fn [argument-order slot]
                          (argument-row function-symbol order source-signature
                                        argument-order slot schema-forms
                                        predicate-functions))
                        (range) slots)]
    (cond->
     {:db/id (component-id function-symbol [:arity order])
      :seon.fn.arity/order (long order)
      :seon.fn.arity/arity (pr-str (:arity info))
      :seon.fn.arity/min (long (:min info))
      :seon.fn.arity/input
      (component-id function-symbol (conj ast-path :input))
      :seon.fn.arity/output
      (component-id function-symbol (conj ast-path :output))
      :seon.fn.arity/arguments arguments
      :seon.fn.arity/argument-count (long (count arguments))
      :seon.fn.arity/return-schema
      (schema-shape/shape-row (:output info) schema-forms predicate-functions)}
      (contains? info :max) (assoc :seon.fn.arity/max (long (:max info)))
      (:guard info)
      (assoc :seon.fn.arity/guard
             (component-id function-symbol (conj ast-path :guard))
             :seon.fn.arity/guard-schema
             (schema-shape/shape-row (:guard info) schema-forms
                                     predicate-functions))
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
      [:seon.program/source [:string {:min 1}]]
      [:seon.program/arglists {:optional true} :string]
      [:seon.program/compile-options :map]
      [:seon.program/predicate-functions :map]
      [:seon.program/schema-keys [:set :keyword]]]]
    :map]}
  [{function-symbol :seon.program/function-symbol
    spec :seon.program/spec
    source :seon.program/source
    arglists :seon.program/arglists
    compile-options :seon.program/compile-options
    predicate-functions :seon.program/predicate-functions
    canonical-keys :seon.program/schema-keys
    schema-forms :seon.program/schema-forms
    reader-namespace :seon.program/reader-namespace
    reader-aliases :seon.program/reader-aliases}]
  (let [compiled (m/function-schema
                  (schema/compilable-form (read-edn spec)
                                          predicate-functions)
                  compile-options)
        arities (m/-function-schema-arities compiled)
        root-ast (m/ast compiled)
        references (schema-reference-keys compiled canonical-keys)
        {source-signatures :seon.fn.signature/signatures
         override? :seon.fn.signature/arglists-override?}
        (signature/function-signatures
         (cond-> {:seon.fn/source source
                  :seon.fn.signature/namespace reader-namespace
                  :seon.fn.signature/aliases (or reader-aliases {})}
           arglists (assoc :seon.fn/arglists arglists)))
        source-by-key (one-by-key source-signatures signature-key
                                  :duplicate-source-arity)
        malli-by-key (one-by-key (mapv m/-function-info arities)
                                 malli-arity-key :duplicate-malli-arity)
        source-keys (set (keys source-by-key))
        malli-keys (set (keys malli-by-key))]
    (when-not (= source-keys malli-keys)
      (throw
       (ex-info "Source and Malli arity sets do not match."
                {:seon.error/kind :seon.fn/signature-refused
                 :seon.fn.signature/reason :unmatched-arity
                 :seon.fn.signature/source-arities source-keys
                 :seon.fn.signature/malli-arities malli-keys})))
    (cond->
     {:seon.fn/arities
      (mapv (fn [order arity]
              (let [info (m/-function-info arity)]
                (arity-row function-symbol order root-ast info canonical-keys
                           (get source-by-key (malli-arity-key info))
                           schema-forms predicate-functions)))
            (range)
            arities)
      :seon.fn/ast (ast-node function-symbol [] root-ast references)}
      override? (assoc :seon.fn/arglists-override? true))))

(defn with-contract-facts
  "Add parsed facts to one canonical contracted function row."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [:seon.program/row :seon.program/declaration-row]
      [:seon.program/compile-options :map]
      [:seon.program/predicate-functions :map]
      [:seon.program/schema-keys [:set :keyword]]]]
    :seon.program/declaration-row]}
  [{row :seon.program/row
    compile-options :seon.program/compile-options
    predicate-functions :seon.program/predicate-functions
    canonical-keys :seon.program/schema-keys
    schema-forms :seon.program/schema-forms
    reader-aliases :seon.program/reader-aliases}]
  (cond
    (:seon.fn/spec row)
    (merge row
           (contract-facts
            (cond->
             {:seon.program/function-symbol (:seon.fn/sym row)
              :seon.program/spec (:seon.fn/spec row)
              :seon.program/source (:seon.fn/source row)
              :seon.program/compile-options compile-options
              :seon.program/predicate-functions predicate-functions
              :seon.program/schema-keys canonical-keys
              :seon.program/schema-forms
              (or schema-forms (schema/registered-schemas))
              :seon.program/reader-namespace
              (second (:seon.fn/ns row))
              :seon.program/reader-aliases (or reader-aliases {})}
              (:seon.fn/arglists row)
              (assoc :seon.program/arglists (:seon.fn/arglists row)))))

    (:seon.schema/key row)
    (assoc row :seon.schema/shape
           (schema-shape/shape-row
            (m/schema (:seon.schema/key row) compile-options)
            (or schema-forms (schema/registered-schemas))
            predicate-functions))

    :else row))

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

(defn- canonical-namespace-components
  "Namespace components in the one shape `:seon.ns/ns` declares.

  A reader event names required namespaces as bare symbols in reading order
  and carries its component collections as vectors; the persisted row holds
  the same facts as sets, with each requirement a `:seon.ns/name` lookup ref.
  The evaluator also builds these components directly from SCI's namespace
  table, so this is idempotent over an already canonical row."
  [row]
  (cond-> row
    (:seon.ns/requires row)
    (assoc :seon.ns/requires
           (into #{}
                 (map (fn [required]
                        (if (symbol? required)
                          [:seon.ns/name required]
                          required)))
                 (:seon.ns/requires row)))
    (:seon.ns/aliases row) (update :seon.ns/aliases set)
    (:seon.ns/imports row) (update :seon.ns/imports set)
    (:seon.ns/refers row) (update :seon.ns/refers set)))

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
  `:contracted` admits only runtime functions carrying a complete contract.

  `admission-source` is who admitted the declaration, and this function
  stamps it. Every declaration row family REQUIRES it, so it is an argument
  rather than an event key a caller can forget: a row with no admission
  source is not constructable here."
  {:malli/schema
   [:=> [:cat :map [:enum :all :contracted]
         :seon.schema.admission/source]
    [:maybe :seon.program/declaration-row]]}
  [event function-policy admission-source]
  (let [event (assoc event :seon.schema.admission/source admission-source)
        candidate
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
        candidate
        (cond
          (:seon.ns/name candidate)
          (canonical-namespace-components candidate)

          (and (:seon.schema/key candidate)
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

          :else candidate)
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
  {:malli/schema
   [:=> [:cat :map] [:maybe :seon.program/deletion-row]]}
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
