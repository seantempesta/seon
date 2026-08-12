(ns seon.config
  "The one compiler and database reconcile boundary for configuration.

  `config/default.edn` makes one explicit decision for every registered config
  attribute. A selected manifest is a sparse overlay. The caller may pass one
  more explicit, typed environment map; compilation applies exactly the
  precedence defaults → overlay → environment, validates every declared key,
  and derives one canonical effective map, digest, and desired row.

  Runtime consumers read only the database row. Omission from a sparse overlay
  inherits the shipped decision; it does not retract a defaulted optional
  attribute. `:seon.config/absent` is the one explicit retraction form, is
  refused for required attributes, and never becomes nil or a datom."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [seon.db :as db]
            [seon.reconcile :as reconcile]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form])
  (:import [java.nio.charset StandardCharsets]))

(schema.edn/load! {})

(def default-manifest-path
  "The one repository/artifact-relative shipped defaults document."
  "config/default.edn")

(def initialization-key
  "The reserved shipped-default key carrying desired initialization rows."
  :seon.config/initialization)

(def managing-process-identity
  "The opaque reconcile scope owned by configuration."
  "seon.db.process/config")

(def absent
  "The sole explicit retraction decision for an optional config attribute.

  Omitting an overlay entry inherits its shipped decision. This marker instead
  removes a defaulted optional attribute from the effective map and desired
  database row; the marker itself is never stored."
  :seon.config/absent)

(defn- short-digest
  [digest]
  (when digest
    (subs digest 0 (min 12 (count digest)))))

(defn render-ai
  "`:seon.render/ai` — the bounded decision face of one effective config."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (when-let [cluster (:seon.config/cluster unit)]
    (str
     "Configuration " cluster " · manifest "
     (short-digest (:seon.config/applied-manifest-digest unit)) ".\n"
     "Model " (:seon.config.ai/model unit)
     " (thinking " (name (:seon.config.ai/thinking unit))
     ", max " (:seon.config.ai/max-tokens unit) " output tokens); "
     "evaluation " (:seon.config.eval/time-limit-ms unit) " ms; Flow "
     (:seon.config.flow.compute/concurrency unit) " compute / "
     (:seon.config.flow.io/concurrency unit) " I/O; core faults "
     (name (:seon.config/on-core-error unit)) ".")))

(defn render-html
  "`:seon.render/html` — one readable effective-configuration card."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (when-let [cluster (:seon.config/cluster unit)]
    [:article {:class "seon-family-entry seon-config-entry"}
     [:h3 (str "Configuration " cluster)]
     [:dl
      [:div [:dt "Manifest digest"]
       [:dd [:code (:seon.config/applied-manifest-digest unit)]]]
      [:div [:dt "Model"] [:dd (:seon.config.ai/model unit)]]
      [:div [:dt "Thinking"]
       [:dd (name (:seon.config.ai/thinking unit))]]
      [:div [:dt "Maximum output"]
       [:dd (str (:seon.config.ai/max-tokens unit) " tokens")]]
      [:div [:dt "Evaluation limit"]
       [:dd (str (:seon.config.eval/time-limit-ms unit) " ms")]]
      [:div [:dt "Flow concurrency"]
       [:dd (str (:seon.config.flow.compute/concurrency unit)
                 " compute / "
                 (:seon.config.flow.io/concurrency unit) " I/O")]]
      [:div [:dt "Core faults"]
       [:dd (name (:seon.config/on-core-error unit))]]]]))

(def ^:private available-processors
  :seon.config/available-processors)

(def ^:private result-cap-attributes
  [:seon.config.eval.result/max-depth
   :seon.config.eval.result/max-collection
   :seon.config.eval.result/max-string
   :seon.config.eval.result/max-nodes])

(defn result-caps
  "Derive complete value-admission caps or name the first absent key."
  {:malli/schema
   [:=> [:cat [:or :seon.config/effective
                :seon.config/missing-effective-error]]
    [:or :seon.sci.admit/caps
     :seon.error/value]]}
  [effective]
  (let [reported-missing
        (set (get-in effective [:seon.error/data ::missing]))
        missing
        (or (some reported-missing result-cap-attributes)
            (some #(when-not (contains? effective %) %)
                  result-cap-attributes))]
    (if missing
      {:seon.error/kind ::missing-result-cap
       :seon.error/message
       (str "Value-admission caps require config key " missing
            "; a partial caps map cannot be constructed.")
       :seon.error/data
       (cond-> {::key missing}
         (:seon.config/missing-effective effective)
         (assoc :seon.config/missing-effective
                (:seon.config/missing-effective effective)))}
      (select-keys effective result-cap-attributes))))

;;; Every function below asks the declaration population one question per
;;; config key. The population is resolved ONCE per operation at that
;;; operation's entry point and passed down; asking `schema/schema-definition`
;;; per key re-reads and re-merges every schema resource per key (measured
;;; 2026-08-07: 1,036 ms / 12,616 resource reads for one
;;; `registration-defaults` — issue
;;; packaged-forms-rereads-every-schema-resource-per-call).

(defn- map-attributes
  [forms schema-key]
  (into #{}
        (comp (filter vector?) (map first))
        (get forms schema-key)))

(defn- dial-attributes
  [forms]
  (map-attributes forms :seon.config/manifest))

(defn- required-dial-attributes
  [forms]
  (into #{}
        (comp
         (filter vector?)
         (keep
          (fn [entry]
            (when-not (and (map? (second entry))
                           (:optional (second entry)))
              (first entry)))))
        (get forms :seon.config/effective)))

(defn- registration-defaults
  [forms]
  (let [required (required-dial-attributes forms)]
    (into {}
          (keep
           (fn [attribute]
             (let [properties
                   (schema.form/attr-form-properties (get forms attribute))]
               (cond
                 (contains? properties :seon.config/default)
                 [attribute (:seon.config/default properties)]

                 (not (contains? required attribute))
                 [attribute absent])))
          (dial-attributes forms)))))

(defn- refuse!
  [rule data cause]
  (throw
   (ex-info
    (str "Configuration refused: " (name rule) ".")
    (merge {:seon.error/kind ::refused
            ::rule rule}
           data)
    cause)))

(defn- read-edn-map
  [path]
  (try
    (with-open [reader (java.io.PushbackReader. (io/reader path))]
      (let [eof (Object.)
            value (edn/read {:eof eof} reader)
            trailing (edn/read {:eof eof} reader)]
        (when-not (and (map? value)
                       (identical? eof trailing))
          (throw
           (ex-info
            "A manifest must contain exactly one EDN map."
            {::path path})))
        value))
    (catch Throwable error
      (refuse! ::manifest-unreadable {::path path} error))))

(defn- validate-layer
  [forms layer]
  (let [dials (set (dial-attributes forms))
        declared (select-keys layer dials)]
    (when (contains? layer initialization-key)
      (refuse! ::initialization-not-allowed
               {::key initialization-key}
               nil))
    (doseq [[key value] declared]
      (when-not (or (= absent value)
                    (schema/valid-candidate-value? forms key value))
        (refuse!
         ::invalid-value
         {::key key
          ::explanation (schema/explain-candidate-value forms key value)}
         nil)))
    declared))

(defn- row-identity
  [forms row]
  (let [identities
        (into []
              (comp
               (filter #(schema/identity-attr? forms %))
               (map (fn [attribute] [attribute (get row attribute)])))
              (keys row))]
    (when (= 1 (count identities))
      (first identities))))

(defn- admit-initialization-rows
  [forms population]
  (let [database-attributes (set (schema/canonical-database-attributes forms))]
    (mapv
     (fn [row]
       (when-not (map? row)
         (refuse! ::invalid-initialization-row {} nil))
       (doseq [[attribute value] row]
         (cond
           (not (qualified-keyword? attribute))
           (refuse! ::invalid-initialization-attribute
                    {::key attribute}
                    nil)

           (not (contains? database-attributes attribute))
           (refuse! ::unknown-initialization-attribute
                    {::key attribute}
                    nil)

           (not (schema/valid-candidate-value? forms attribute value))
           (refuse! ::invalid-initialization-value
                    {::key attribute
                     ::explanation
                     (schema/explain-candidate-value forms attribute value)}
                    nil)))
       (when-not (row-identity forms row)
         (refuse! ::invalid-initialization-identity
                  {::explanation
                   {:seon.config/identity-attributes
                    (into []
                          (filter #(schema/identity-attr? forms %))
                          (keys row))}}
                  nil))
       row)
     population)))

(defn- admit-initialization
  [forms population]
  (when-not (vector? population)
    (refuse! ::invalid-initialization
             {::explanation {:seon.config/expected :vector-of-maps}}
             nil))
  ;; Admission reaches Malli predicates — `seon.schema/malli-form?` among them
  ;; — that take only the value and therefore resolve the declaration
  ;; population themselves. They cannot be handed the value, so the operation
  ;; SUPPLIES it for the extent of the admission instead. Measured live on a
  ;; booted cluster 2026-08-07: 82,992 resource reads / 6,495 ms without this,
  ;; 0 reads / 11.6 ms with it, identical result.
  (schema/call-with-forms forms #(admit-initialization-rows forms population)))

(defn- default-document
  []
  (read-edn-map
   (or (io/resource default-manifest-path)
       default-manifest-path)))

(defn- admitted-default-document
  [forms document]
  {:seon.config/decisions (dissoc document initialization-key)
   :seon.config/initialization
   (admit-initialization forms (get document initialization-key []))})

(defn default-population
  "Read and admit the shipped initialization entity rows."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  (let [forms (schema.edn/packaged-forms)]
    (:seon.config/initialization
     (admitted-default-document forms (default-document)))))

(defn- validate-default-decisions
  [forms document]
  (let [dials (dial-attributes forms)
        decisions (merge (registration-defaults forms)
                         (select-keys document dials))
        missing (set/difference dials (set (keys decisions)))]
    (when (seq missing)
      (refuse!
       ::missing-default
       {::explanation {:seon.config/missing missing}}
       nil))
    (doseq [[key decision] decisions]
      (when-not (or (= absent decision)
                    (and (= key :seon.config.flow.compute/concurrency)
                         (= available-processors decision))
                    (schema/valid-candidate-value? forms key decision))
        (refuse!
         ::invalid-value
         {::key key
          ::explanation (schema/explain-candidate-value forms key decision)}
         nil)))
    decisions))

(defn default-decisions
  "Read the complete shipped decision map.

  An optional registration's generic floor is explicit absence; a
  registration-attached default overrides that floor. The shipped EDN document
  must decide every production attribute explicitly; symbolic machine and
  absence decisions are resolved only by `compile-manifest`."
  {:malli/schema [:=> [:cat] :map]}
  []
  (let [forms (schema.edn/packaged-forms)]
    (validate-default-decisions
     forms
     (:seon.config/decisions
      (admitted-default-document forms (default-document))))))

(defn read-manifest
  "Read and validate one sparse plain-EDN overlay without compiling it."
  {:malli/schema [:=> [:cat :string] :seon.config/manifest]}
  [path]
  (validate-layer (schema.edn/packaged-forms) (read-edn-map path)))

(defn- resolve-smart-decision
  [key decision]
  (if (and (= key :seon.config.flow.compute/concurrency)
           (= decision available-processors))
    (long (.availableProcessors (Runtime/getRuntime)))
    decision))

(defn compile-manifest
  "Compile defaults + sparse manifest + explicit typed environment map once.

  The optional cluster name defaults to `default`. The digest covers only the
  canonical effective config, so equal configs in distinct clusters have the
  same digest."
  {:malli/schema
   [:=> [:cat :seon.config/compile-request] :seon.config/compiled]}
  [request]
  (let [forms (schema.edn/packaged-forms)
        {:seon.config/keys [decisions initialization]}
        (admitted-default-document forms (default-document))
        manifest (validate-layer forms (or (:seon.config/manifest request) {}))
        environment
        (validate-layer forms (or (:seon.config/environment request) {}))
        defaults (validate-default-decisions forms decisions)
        decisions (merge defaults manifest environment)
        required (required-dial-attributes forms)]
    (doseq [[key decision] decisions]
      (when (and (= absent decision) (contains? required key))
        (refuse! ::required-absent {::key key} nil)))
    (let [effective
          (into {}
                (comp
                 (remove (comp #{absent} val))
                 (map
                  (fn [[key decision]]
                    [key (resolve-smart-decision key decision)])))
                decisions)]
      (when-not (schema/valid-candidate-value?
                 forms :seon.config/effective effective)
        (refuse!
         ::invalid-value
         {::explanation
          (schema/explain-candidate-value
           forms :seon.config/effective effective)}
         nil))
      (let [digest
            (schema/sha-256
             [(.getBytes
               ^String (schema/canonical-data-string effective)
               StandardCharsets/UTF_8)])
            row
            (assoc effective
                   :seon.config/cluster
                   (or (:seon.boot/cluster-name request) "default")
                   :seon.config/applied-manifest-digest digest)]
        {:seon.config/effective effective
         :seon.config/applied-manifest-digest digest
         :seon.config/desired-row row
         :seon.config/initialization initialization
         :seon.config/resolved-attributes (set (keys decisions))}))))

(defn defaults
  "Compile the zero-overlay shipped defaults into one effective config."
  {:malli/schema [:=> [:cat] :seon.config/effective]}
  []
  (:seon.config/effective (compile-manifest {})))

(defn- desired-tempid
  [identity]
  (str "seon.config.initialization/" (pr-str identity)))

(defn- population-transaction-data
  [forms database desired]
  (let [identities (mapv #(row-identity forms %) desired)
        entity-ids
        (into {}
              (map
               (fn [identity]
                 [identity
                  (or (:db/id (db/pull database [:db/id] identity))
                      (desired-tempid identity))]))
              identities)
        ref-value
        (fn [value]
          (if-let [entity-id (and (vector? value) (get entity-ids value))]
            entity-id
            value))]
    (mapv
     (fn [row]
       (into {:db/id (get entity-ids (row-identity forms row))}
             (map
              (fn [[attribute value]]
                (let [attribute-schema (get-in database [:schema attribute])]
                  [attribute
                   (if (= :db.type/ref (:db/valueType attribute-schema))
                     (if (= :db.cardinality/many
                            (:db/cardinality attribute-schema))
                       (into (empty value) (map ref-value) value)
                       (ref-value value))
                     value)])))
             row))
     desired)))

(defn apply-compiled!
  "Exact-reconcile one already-compiled desired config row."
  {:malli/schema
   [:=> [:cat :seon.db/connection :seon.config/compiled]
    :seon.reconcile/result]}
  [connection compiled]
  (let [projection (schema/projection-from-database @connection)
        forms (:seon.schema.projection/forms projection)
        desired
        (into [(:seon.config/desired-row compiled)]
              (:seon.config/initialization compiled))
        inherited-config-identities
        (into #{}
              (map (fn [cluster-name]
                     [:seon.config/cluster cluster-name]))
              (db/q '[:find [?cluster-name ...]
                      :where
                      [_ :seon.config/cluster ?cluster-name]]
                    @connection))
        identities (into inherited-config-identities
                         (keep #(row-identity forms %))
                         desired)
        request
        {::reconcile/desired desired
         ::reconcile/process managing-process-identity
         ::reconcile/adopt-identities identities}
        operations (count (reconcile/plan @connection request))
        result
        (if (zero? operations)
          {::reconcile/converged? true
           ::reconcile/operations 0}
          (let [transaction-result
                (db/transact!
                 connection
                 {:tx-data
                  (conj
                   (population-transaction-data forms @connection desired)
                   [:db.fn/call #'reconcile/reconcile-call request])
                  :tx-meta
                  {:seon.db/process
                   [:seon.db.process/id managing-process-identity]}})]
            (if (:seon.error/kind transaction-result)
              transaction-result
              {::reconcile/converged? false
               ::reconcile/operations operations})))]
    (when (:seon.error/kind result)
      (refuse! ::reconcile-refused
               {:seon.config/reconcile-result result}
               nil))
    result))

(defn apply!
  "Compile once and exact-reconcile the one desired config row."
  {:malli/schema
   [:=> [:cat :seon.config/apply-request] :seon.reconcile/result]}
  [request]
  (apply-compiled!
   (:seon.db/connection request)
   (compile-manifest
    (select-keys
     request
     [:seon.config/manifest
      :seon.config/environment
      :seon.boot/cluster-name]))))

(declare effective-in)

(defn effective
  "Read one cluster's effective config or return one bounded error value."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value]
     [:or :seon.config/effective
      :seon.config/missing-effective-error]]
    [:=> [:cat :seon.db/database-value :seon.boot/cluster-name]
     [:or :seon.config/effective
      :seon.config/missing-effective-error]]]}
  ([db]
   (effective db "default"))
  ([db cluster-name]
   ;; ONE population for the whole read. `db/pull` deliberately takes no
   ;; population argument and resolves its own, so this operation supplies the
   ;; one it already resolved for its extent — measured live 2026-08-07:
   ;; 84,664 resource reads before the read seam was repaired, 1,216 after it,
   ;; and 152 (one population, 12.6 ms) once supplied.
   (let [projection (schema/projection-from-database db)]
     (schema/call-with-projection
      projection
      #(effective-in db (or cluster-name "default"))))))

(defn- effective-in
  [db cluster-name]
  (let [forms (:seon.schema.projection/forms
               (schema/projection-from-database db))
        row (db/pull db '[*] [:seon.config/cluster cluster-name])
        effective (select-keys row (dial-attributes forms))
        missing (vec (sort (set/difference (required-dial-attributes forms)
                                           (set (keys effective)))))]
     (if (and row (empty? missing))
       effective
       (let [shown (take 6 missing)
             remaining (- (count missing) (count shown))
             available
             (vec
              (sort
               (db/q '[:find [?available ...]
                       :where
                       [_ :seon.config/cluster ?available]]
                     db)))]
         {:seon.config/missing-effective cluster-name
          :seon.error/kind ::missing-effective
          :seon.error/data {::missing missing}
          :seon.error/message
          (if row
            (str "Effective configuration for cluster " (pr-str cluster-name)
                 " is missing required facts " (pr-str (vec shown))
                 (when (pos? remaining)
                   (str " and " remaining " more")) ".")
            (str "No effective configuration facts match cluster "
                 (pr-str cluster-name) "; available clusters "
                 (pr-str available) "."))}))))
