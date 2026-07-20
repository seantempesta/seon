(ns seon.db.internal
  "Transform transaction and schema data behind `seon.db`.

   This internal namespace normalizes public requests and process-local scope
   into protocol data; transport and authoritative execution live elsewhere."
  (:require
   [clojure.string :as str]
   [seon.ai.tokens :as tokens]
   [seon.db :as-alias db]
   [seon.error :as error]
   [seon.schema :as schema]))

;;; Process-local execution context. Ordinary database descriptors may pin
;;; reads; native Datahike database values never enter these scopes.

(defonce ^:private tx-context
  (let [ctor (.-AsyncLocalStorage (js/require "node:async_hooks"))]
    (ctor.)))

(defonce ^:private agent-context
  (let [ctor (.-AsyncLocalStorage (js/require "node:async_hooks"))]
    (ctor.)))

(defonce ^:private read-evidence-context
  (let [ctor (.-AsyncLocalStorage (js/require "node:async_hooks"))]
    (ctor.)))

(defn current-tx-context
  "The current fiber-local transaction context."
  []
  (some-> tx-context .getStore))

(defn current-agent-id
  "The current fiber-local agent id."
  []
  (some-> agent-context .getStore))

(defn record-read-evidence!
  "Retain one ordinary Datahike read-evidence entry in the current fiber."
  [evidence]
  (when-let [entries (some-> read-evidence-context .getStore)]
    (swap! entries conj evidence))
  nil)

(defn run-with-read-evidence
  "Run `f` in a fresh fiber-local evidence scope and return value + evidence."
  [f]
  (let [entries (atom [])]
    (.run
     read-evidence-context entries
     (fn []
       (-> (js/Promise.resolve nil)
           (.then (fn [_] (f)))
           (.then (fn [value]
                    {::db/value value
                     ::db/read-evidence (vec (distinct @entries))})))))))

(defn run-with-tx-context
  "Run `f` with `context` merged into the current transaction context."
  [context f]
  (.run tx-context (merge (current-tx-context) context) f))

(defn enter-tx-context!
  "Make `context` available to async work created from the current fiber."
  [context]
  (.enterWith tx-context (merge (current-tx-context) context))
  nil)

(defn run-with-agent
  "Run `f` with `agent-id` as the current agent."
  [agent-id f]
  (.run agent-context agent-id f))

(defn run-without-agent
  "Run `f` without an inherited agent id."
  [f]
  (.exit agent-context f))

;;; Malli to Datahike schema data. The authority installs the returned maps.

(def tx-meta-attrs #{::db/user ::db/process})

(defn form-properties
  "The property map in one Malli form, when present."
  [form]
  (when (vector? form)
    (some #(when (map? %) %) (rest form))))

(defn form-children
  "The non-property children of one Malli form."
  [form]
  (if (vector? form)
    (into [] (remove map?) (rest form))
    []))

(defn resolve-malli-form
  "Resolve registered Malli aliases without crossing into compiled schemas."
  [form]
  (cond
    (= :seon.db/ref form) form
    (and (keyword? form) (schema/registered? form))
    (let [definition (schema/schema-definition form)]
      (if (or (keyword? definition) (vector? definition))
        (resolve-malli-form definition)
        form))
    :else form))

(def malli-type->datahike-type
  {:string :db.type/string
   :int :db.type/long
   :double :db.type/double
   :float :db.type/float
   :keyword :db.type/keyword
   :qualified-keyword :db.type/keyword
   :boolean :db.type/boolean
   :inst :db.type/instant
   :uuid :db.type/uuid
   :symbol :db.type/symbol
   :qualified-symbol :db.type/symbol})

(defn form-head
  "The head of one Malli form."
  [form]
  (if (vector? form) (first form) form))

(declare form->datahike-value-type)

(defn form->datahike-value-type
  "The Datahike value-type keyword represented by a Malli form."
  [form]
  (let [resolved (resolve-malli-form form)
        head (form-head resolved)]
    (cond
      (= :seon.db/ref head) :db.type/ref
      (= :enum head)
      (if (every? keyword? (form-children resolved))
        :db.type/keyword
        (throw (ex-info "Only keyword Malli enums are storable."
                        {::db/form resolved :seon.error/kind :user-input})))
      (= :and head)
      (form->datahike-value-type (first (form-children resolved)))
      (= :or head)
      (let [types (into #{} (map #(try
                                    (form->datahike-value-type %)
                                    (catch :default _ ::unmappable)))
                        (form-children resolved))]
        (if (and (= 1 (count types)) (not (contains? types ::unmappable)))
          (first types)
          :db.type/string))
      (= :maybe head)
      (throw (ex-info "Stored attributes cannot use `:maybe`; omit absent values."
                      {::db/form resolved :seon.error/kind :user-input}))
      :else
      (or (malli-type->datahike-type head)
          (throw (ex-info "The Malli form has no Datahike value type."
                          {::db/form resolved :seon.error/kind :user-input}))))))

(defn form->cardinality
  "The Datahike cardinality represented by one Malli form."
  [form]
  (if (and (vector? form) (#{:vector :set :sequential} (first form)))
    :db.cardinality/many
    :db.cardinality/one))

(defn form->child-form
  "The stored child form for a collection schema, or the scalar form."
  [form]
  (if (and (vector? form) (#{:vector :set :sequential} (first form)))
    (first (form-children form))
    form))

(defn malli->datahike-attr
  "Derive one ordinary Datahike attribute declaration."
  [attr]
  (let [raw (or (schema/schema-definition attr)
                (throw (ex-info "The attribute has no registered schema."
                                {::db/attr attr :seon.error/kind :user-input})))
        props (form-properties raw)
        value-form (-> raw resolve-malli-form form->child-form resolve-malli-form)
        secondary? (boolean (:db.secondary/only props))]
    (when (and secondary?
               (not (contains? #{:float :double} (form-head value-form))))
      (throw (ex-info "A secondary-only attribute must contain floats."
                      {::db/attr attr :seon.error/kind :user-input})))
    (cond-> {:db/ident attr
             :db/valueType (if secondary?
                             :db.type/tuple
                             (form->datahike-value-type value-form))
             :db/cardinality (if secondary?
                               :db.cardinality/one
                               (form->cardinality (resolve-malli-form raw)))}
      secondary? (assoc :db.secondary/only true)
      (:seon.db/identity props) (assoc :db/unique :db.unique/identity)
      (:seon.db/unique props) (assoc :db/unique :db.unique/value)
      (:seon.db/index props) (assoc :db/index true)
      (:seon.db/component props) (assoc :db/isComponent true))))

(defn malli->datahike-schema
  "Derive ordered Datahike attribute declarations."
  [attrs]
  (mapv malli->datahike-attr attrs))

(defn tx-meta-datahike-schema
  "Derive the two transaction-provenance declarations."
  []
  (malli->datahike-schema (sort tx-meta-attrs)))

(defn edn-encoded-attr?
  "True when a mixed Malli union is stored as an EDN string."
  [attr]
  (when (and (keyword? attr) (schema/registered? attr))
    (let [form (resolve-malli-form (schema/schema-definition attr))]
      (and (vector? form)
           (= :or (first form))
           (= :db.type/string (form->datahike-value-type form))))))

(defn encode-edn-slot-values
  "Encode mixed-union attribute values before transport."
  [tx-data]
  (letfn [(encode-entity [entity]
            (reduce-kv (fn [result attr value]
                         (assoc result attr
                                (cond
                                  (edn-encoded-attr? attr) (pr-str value)
                                  (map? value) (encode-entity value)
                                  (and (vector? value) (some map? value))
                                  (mapv #(if (map? %) (encode-entity %) %) value)
                                  :else value)))
                       {} entity))]
    (mapv (fn [item]
            (cond
              (map? item) (encode-entity item)
              (and (vector? item) (= :db/add (first item))
                   (edn-encoded-attr? (nth item 2 nil)))
              (update item 3 pr-str)
              :else item))
          tx-data)))

;;; Pure transaction normalization and validation.

(defn system-attr?
  "True for Datahike's `:db/*` and `:db.*/*` attributes."
  [attr]
  (let [n (and (keyword? attr) (namespace attr))]
    (boolean (and n (or (= "db" n) (str/starts-with? n "db."))))))

(defn ref-attr-arity
  "The registered ref cardinality, or nil for a non-ref attribute."
  [form]
  (let [resolved (resolve-malli-form form)
        head (form-head resolved)]
    (cond
      (= :seon.db/ref resolved) :one
      (and (#{:vector :set :sequential} head)
           (= :seon.db/ref
              (some-> resolved form-children first resolve-malli-form))) :many
      :else nil)))

(defn ref-slot?
  "True when `attr` names a registered ref attribute."
  [attr]
  (and (qualified-keyword? attr)
       (not (system-attr? attr))
       (schema/registered? attr)
       (some? (ref-attr-arity (schema/schema-definition attr)))))

(defn extract-tx-attrs
  "Collect every attribute named by transaction data."
  [tx-data]
  (letfn [(entity-attrs [attrs entity]
            (reduce-kv (fn [result attr value]
                         (cond-> (conj result attr)
                           (and (ref-slot? attr) (map? value))
                           (entity-attrs value)
                           (and (ref-slot? attr) (sequential? value))
                           (into (mapcat #(when (map? %) (entity-attrs #{} %)) value))))
                       attrs entity))]
    (reduce (fn [attrs item]
              (cond
                (map? item) (entity-attrs attrs item)
                (and (vector? item) (<= 3 (count item))) (conj attrs (nth item 2))
                :else attrs))
            #{} tx-data)))

(defn validate-attrs!
  "Reject transaction attributes absent from the schema registry."
  [attrs]
  (let [unknown (into [] (remove #(or (system-attr? %) (schema/registered? %))) attrs)]
    (when (seq unknown)
      (throw (ex-info "Transaction data names unregistered attributes."
                      {::db/unregistered unknown :seon.error/kind :user-input}))))
  nil)

(defn truncate-value
  "A bounded printable transaction value."
  [value]
  (tokens/bounded-pr-str value 25))

(declare validate-entity-values!)

(defn- validate-ref! [attr value]
  (cond
    (map? value) (validate-entity-values! value)
    (schema/valid-candidate-value? :seon.db/ref value) nil
    :else (throw (ex-info "A ref attribute contains an invalid reference."
                          {::db/attr attr ::db/actual-value value
                           :seon.error/kind :user-input}))))

(defn validate-entity-values!
  "Validate one transaction entity using registered Malli forms."
  [entity]
  (doseq [[attr value] entity
          :when (and (not (system-attr? attr)) (schema/registered? attr))]
    (case (ref-attr-arity (schema/schema-definition attr))
      :one (validate-ref! attr value)
      :many (doseq [child value] (validate-ref! attr child))
      (when-not (schema/valid-candidate-value? attr value)
        (throw (ex-info "Transaction data fails its registered schema."
                        {::db/attr attr ::db/actual-value value
                         ::db/expected-schema (schema/schema-definition attr)
                         :seon.error/kind :user-input})))))
  nil)

(defn validate-values!
  "Validate every entity map in transaction data."
  [tx-data]
  (doseq [item tx-data :when (map? item)]
    (validate-entity-values! item))
  nil)

(defn keyword-identity-ident?
  "True for a keyword-valued identity attribute."
  [attr]
  (and (qualified-keyword? attr)
       (schema/registered? attr)
       (schema/identity-attr? attr)
       (= :keyword
          (-> attr schema/schema-definition resolve-malli-form
              form->child-form resolve-malli-form form-head))))

(defn coerce-lookup-ref-symbol
  "Coerce a symbol value in a keyword identity lookup ref."
  [value]
  (if (and (vector? value) (= 2 (count value))
           (keyword-identity-ident? (first value)) (symbol? (second value)))
    [(first value) (keyword (second value))]
    value))

(defn coerce-identity-symbol-idents
  "Coerce symbols only in keyword-valued identity positions."
  [tx-data]
  (letfn [(ref-value [value]
            (cond
              (map? value) (entity value)
              (vector? value) (coerce-lookup-ref-symbol value)
              (sequential? value) (mapv ref-value value)
              :else value))
          (entity [value]
            (reduce-kv (fn [result attr item]
                         (assoc result attr
                                (cond
                                  (and (keyword-identity-ident? attr) (symbol? item))
                                  (keyword item)
                                  (= :db/id attr) (coerce-lookup-ref-symbol item)
                                  (ref-slot? attr) (ref-value item)
                                  :else item)))
                       {} value))]
    (mapv (fn [item]
            (cond
              (map? item) (entity item)
              (and (vector? item) (#{:db/add :db/retract} (first item)))
              (let [attr (nth item 2 nil)]
                (cond-> (update item 1 coerce-lookup-ref-symbol)
                  (and (<= 4 (count item)) (ref-slot? attr)) (update 3 ref-value)
                  (and (<= 4 (count item)) (keyword-identity-ident? attr)
                       (symbol? (nth item 3))) (update 3 keyword)))
              :else item))
          tx-data)))

(defn normalize-entity-ref-keys
  "Translate entity-map `:seon.db/ref` keys to native `:db/id` keys."
  [tx-data]
  (letfn [(ref-value [value]
            (cond
              (map? value) (entity value)
              (sequential? value) (mapv ref-value value)
              :else value))
          (entity [value]
            (let [normalized (reduce-kv (fn [result attr item]
                                          (assoc result attr
                                                 (if (ref-slot? attr)
                                                   (ref-value item)
                                                   item)))
                                        {} value)]
              (if-not (contains? normalized ::db/ref)
                normalized
                (let [ref (::db/ref normalized)]
                  (when-not (schema/valid-candidate-value? :seon.db/ref ref)
                    (throw (ex-info "`:seon.db/ref` contains an invalid entity reference."
                                    {::db/actual-value ref
                                     :seon.error/kind :user-input})))
                  (when (and (contains? normalized :db/id)
                             (not= (:db/id normalized) ref))
                    (throw (ex-info "An entity map names two different entities."
                                    {::db/actual-value normalized
                                     :seon.error/kind :user-input})))
                  (-> normalized (dissoc ::db/ref) (assoc :db/id ref))))))]
    (mapv (fn [item]
            (cond
              (map? item) (entity item)
              (and (vector? item) (<= 4 (count item)) (ref-slot? (nth item 2)))
              (update item 3 ref-value)
              :else item))
          tx-data)))

(defn assert-invocation-shape!
  "Require one namespaced transaction request with sequential data."
  [request]
  (when-not (and (map? request)
                 (contains? request ::db/tx-data)
                 (sequential? (::db/tx-data request)))
    (throw (ex-info "`seon.db/transact!` requires `:seon.db/tx-data`."
                    {::db/actual-value request :seon.error/kind :user-input})))
  nil)

(defn selected-provenance
  "Select the two durable provenance references."
  [context agent-id]
  {::db/user (or (::db/user context)
                 (if agent-id [:seon.agent/id agent-id] [:seon.user/id "user"]))
   ::db/process (or (::db/process context)
                    [:seon.db.process/id :seon.db.process/repl])})

(defn merge-tx-context-into-opts
  "Merge selected provenance into transaction metadata."
  [opts]
  (let [provenance (selected-provenance (or (current-tx-context) {})
                                        (current-agent-id))
        explicit (into {} (remove #(= "seon.db" (namespace (key %))))
                       (or (:tx-meta opts) {}))]
    (assoc (or opts {}) :tx-meta (merge explicit provenance))))

(defn error-envelope
  "Convert an exception to the canonical transaction failure envelope."
  [exception]
  (let [value (error/->map exception)]
    {::db/ok? false
     ::db/error (cond-> value
                  (nil? (:seon.error/kind value))
                  (assoc :seon.error/kind :core-bug))}))

(defn commit-error-envelope
  "Convert a transaction failure to ordinary data."
  [exception]
  (error-envelope exception))
