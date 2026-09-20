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
            [seon.error :as error]
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
  ;; `max-bytes` is the ONE bound admission enforces (2026-09-07): the display
  ;; caps below were deleted from `seon.sci.admit` and survive only for owners
  ;; outside that seam — the inbound message length limit, the namespace
  ;; page's query-work bounds, and fault/contract evidence profiles — each of
  ;; which needs its own declared key before they can go.
  [:seon.config.eval.result/max-bytes
   :seon.config.eval.result/max-source
   :seon.config.eval.result/max-depth
   :seon.config.eval.result/max-collection
   :seon.config.eval.result/max-string
   :seon.config.eval.result/max-nodes])

(defn result-caps
  "Derive complete value-admission caps or name the first absent key."
  {:malli/schema
   [:=> [:cat [:or :seon.config/effective :seon.config/error
                :seon.db/error-result]]
    [:or :seon.sci.admit/caps :seon.config/error]]}
  [effective]
  ;; A valid effective configuration has every cap. A missing cap therefore
  ;; names the refused constraint; preserve the input's causal observations.
  (let [reported-missing
        (set (get-in effective [:seon.error/data ::missing]))
        missing
        (or (some reported-missing result-cap-attributes)
            (some #(when-not (contains? effective %) %)
                  result-cap-attributes))
        cluster-less-refusal (when (and missing
                                       (not (:seon.config/missing-effective effective)))
                               effective)]
    (if missing
      (error/diagnostic
       {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.config/read
       :seon.error/operation 'seon.config/result-caps
       :seon.config/error-key missing
       :seon.error/expected-key missing
       :seon.error/diagnostic-layer :seon.config/read
       :seon.error/diagnostic-operation 'seon.config/result-caps
       :seon.error/diagnostic-member missing
       :seon.error/diagnostic-expected missing
       :seon.error/diagnostic-offending effective
       :seon.error/diagnostic-cause :seon.config/missing-result-cap
       :seon.error/diagnostic-evidence {:seon.config/key missing}
       :seon.error/message
       (str "Value-admission caps require config key " missing
            "; a partial caps map cannot be constructed."
            (when cluster-less-refusal
              (str " The configuration itself was refused: "
                   (:seon.error/message cluster-less-refusal))))
       :seon.error/data
       (cond-> {::key missing}
         (:seon.config/missing-effective effective)
         (assoc :seon.config/missing-effective
                (:seon.config/missing-effective effective))
         cluster-less-refusal
         (assoc ::configuration-refusal cluster-less-refusal))})
      (select-keys effective result-cap-attributes))))

;;; Every function below asks the declaration population one question per
;;; config key. The population is resolved ONCE per operation at that
;;; operation's entry point and passed down; asking `schema/schema-definition`
;;; per key re-reads and re-merges every schema resource per key (measured
;;; 2026-08-07: 1,036 ms / 12,616 resource reads for one
;;; `registration-defaults` — issue
;;; packaged-forms-rereads-every-schema-resource-per-call).

(defn dial-attributes
  "Config membership declared by leaf schemas, independent of their names."
  {:malli/schema [:=> [:cat :map] [:set :qualified-keyword]]}
  [forms]
  (into #{}
        (keep (fn [[attribute definition]]
                (when (true? (:seon.config/dial
                              (schema.form/attr-form-properties definition)))
                  attribute)))
        forms))

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
            ::rule rule :seon.config/refused rule}
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
  [projection layer]
  (let [forms (:seon.schema.projection/forms projection)
        dials (set (dial-attributes forms))
        declared (select-keys layer dials)]
    (when (contains? layer initialization-key)
      (refuse! ::initialization-not-allowed
               {::key initialization-key}
               nil))
    (doseq [[key value] declared]
      (when-not (or (= absent value)
                    ((schema/projection-validator projection key) value))
        (refuse!
         ::invalid-value
         {::key key
          ::explanation ((schema/projection-explainer projection key) value)}
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
  [projection population]
  (let [forms (:seon.schema.projection/forms projection)
        database-attributes (set (schema/canonical-database-attributes forms))]
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

           (not ((schema/projection-validator projection attribute) value))
           (refuse! ::invalid-initialization-value
                    {::key attribute
                     ::explanation
                     ((schema/projection-explainer projection attribute) value)}
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
  [projection population]
  (when-not (vector? population)
    (refuse! ::invalid-initialization
             {::explanation {:seon.config/expected :vector-of-maps}}
             nil))
  (admit-initialization-rows projection population))

(defn- default-document
  []
  (read-edn-map
   (or (io/resource default-manifest-path)
       default-manifest-path)))

(defn- admitted-default-document
  [projection document]
  {:seon.config/decisions (dissoc document initialization-key)
   :seon.config/initialization
   (admit-initialization projection (get document initialization-key []))})

(defn default-population
  "Read and admit the shipped initialization entity rows."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  (let [projection (schema/declaration-projection (schema.edn/packaged-forms))]
    (:seon.config/initialization
     (admitted-default-document projection (default-document)))))

(defn- validate-default-decisions
  [projection document]
  (let [forms (:seon.schema.projection/forms projection)
        dials (dial-attributes forms)
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
                    ((schema/projection-validator projection key) decision))
        (refuse!
         ::invalid-value
         {::key key
          ::explanation ((schema/projection-explainer projection key) decision)}
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
  (let [projection (schema/declaration-projection (schema.edn/packaged-forms))]
    (validate-default-decisions
     projection
     (:seon.config/decisions
      (admitted-default-document projection (default-document))))))

(defn read-manifest
  "Read and validate one sparse plain-EDN overlay without compiling it."
  {:malli/schema [:=> [:cat :string] :seon.config/manifest]}
  [path]
  (validate-layer (schema/declaration-projection (schema.edn/packaged-forms))
                  (read-edn-map path)))

(defn- resolve-smart-decision
  [key decision]
  (if (and (= key :seon.config.flow.compute/concurrency)
           (= decision available-processors))
    (long (.availableProcessors (Runtime/getRuntime)))
    decision))

(defn- compile-settings
  [request]
  (let [projection (schema/declaration-projection (schema.edn/packaged-forms))
        forms (:seon.schema.projection/forms projection)
        {:seon.config/keys [decisions initialization]}
        (admitted-default-document projection (default-document))
        manifest (validate-layer projection (or (:seon.config/manifest request) {}))
        environment
        (validate-layer projection (or (:seon.config/environment request) {}))
        defaults (validate-default-decisions projection decisions)
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
      (when-not ((schema/projection-validator projection :seon.config/effective) effective)
        (refuse!
         ::invalid-value
         {::explanation
          ((schema/projection-explainer projection :seon.config/effective) effective)}
         nil))
      (let [digest
            (schema/sha-256
             [(.getBytes
               ^String (schema/canonical-data-string effective)
               StandardCharsets/UTF_8)])]
        {:seon.config/effective effective
         :seon.config/applied-manifest-digest digest
         :seon.config/initialization initialization
         :seon.config/resolved-attributes (set (keys decisions))}))))

(defn compile-manifest
  "Compile settings and a desired row for the explicitly named cluster.

  The digest covers only effective config, so equal configs in distinct
  clusters have the same digest."
  {:malli/schema
   [:=> [:cat :seon.config/compile-request] :seon.config/compiled]}
  [request]
  (let [cluster-name (:seon.boot/cluster-name request)]
    (when-not (and (string? cluster-name) (seq cluster-name))
      (refuse! ::required-absent
               {::key :seon.boot/cluster-name
                :seon.error/diagnostic-operation 'seon.config/compile-manifest}
               nil))
    (let [compiled (compile-settings request)]
      (assoc compiled :seon.config/desired-row
             (assoc (:seon.config/effective compiled)
                    :seon.config/cluster cluster-name
                    :seon.config/applied-manifest-digest
                    (:seon.config/applied-manifest-digest compiled))))))

(defn defaults
  "Compile the zero-overlay shipped defaults into one effective config."
  {:malli/schema [:=> [:cat] :seon.config/effective]}
  []
  (:seon.config/effective (compile-settings {})))

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
                 ;; A refused read is not "no such entity". Minting a tempid
                 ;; for an identity the database already holds produces a
                 ;; conflicting upsert; refuse with the read as the cause.
                 (let [pulled (db/pull database [:db/id] identity)]
                   (when (:seon.error/kind pulled)
                     (refuse! ::read-refused
                              {:seon.config/identity identity
                               ::read-error pulled}
                              nil))
                   [identity (or (:db/id pulled) (desired-tempid identity))])))
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
  (let [database (db/db connection)
        projection (or (db/carried-projection database)
                       (schema/handed-projection)
                       (do (db/projection-fallback 'seon.config/apply-compiled!)
                           (schema/projection-from-database database)))
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
                    database))
        identities (into inherited-config-identities
                         (keep #(row-identity forms %))
                         desired)
        request
        {::reconcile/desired desired
         ::reconcile/process managing-process-identity
         ::reconcile/adopt-identities identities}
        ;; The digest covers config dials, not initialization rows or later
        ;; hand edits. Exact reconciliation must still observe those facts.
        operations (count (reconcile/plan database request))
        result
        (if (zero? operations)
          {::reconcile/converged? true
           ::reconcile/operations 0}
          (let [transaction-result
                (db/transact!
                 connection
                 {:tx-data
                  (conj
                   (population-transaction-data forms database desired)
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
  "Compile once and exact-reconcile the one desired config row.

  With a path, read one selected EDN document. The shipped document selects
  defaults and their initialization rows; any other document is a sparse
  overlay and obeys the same admission rules as an in-memory manifest."
  {:malli/schema
   [:function
    [:=> [:cat :seon.config/apply-request] :seon.reconcile/result]
    [:=> [:cat :seon.config/apply-request :seon.config/path]
     :seon.reconcile/result]]}
  ([request]
   (apply-compiled!
    (:seon.db/connection request)
    (compile-manifest
     (select-keys
      request
      [:seon.config/manifest
       :seon.config/environment
       :seon.boot/cluster-name]))))
  ([request path]
   (let [document (read-edn-map path)
         manifest (if (= document (default-document))
                    {}
                    document)]
     (apply! (assoc request :seon.config/manifest manifest)))))

(declare effective-in)

(defn effective
  "Read one cluster's effective config from its carried projection."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.boot/cluster-name]
    [:or :seon.config/effective :seon.config/error
     :seon.schema/validation-refusal :seon.db/error-result]]}
  [db cluster-name]
  (if-let [projection (or (db/carried-projection db)
                          (schema/handed-projection))]
    (effective-in db cluster-name projection)
    (db/projection-fallback 'seon.config/effective)))

(defn- effective-in
  {:malli/schema
   [:=> [:cat [:or :seon.db/database-value :seon.db/error-result]
         :seon.boot/cluster-name :seon.schema/projection]
    [:or :seon.config/effective :seon.config/error :seon.db/error-result]]}
  [db cluster-name projection]
  (let [forms (:seon.schema.projection/forms projection)
        row (db/pull db '[*] [:seon.config/cluster cluster-name])]
    ;; A refused read is not an absent row. Reading the refusal's own keys as
    ;; the config row reports every dial missing and blames the facts; the
    ;; cause here is the read, so return the read's refusal unchanged.
    (if (or (:seon.db/read-operation row)
            (:seon.db.availability/connection row)
            (:seon.schema/expected-value row))
      row
      (let [effective (select-keys row (dial-attributes forms))
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
            (error/diagnostic
             {:seon.error/at (java.util.Date.)
             :seon.error/layer :seon.config/read
             :seon.error/operation 'seon.config/effective-in
             :seon.config/error-key :seon.config/cluster
             :seon.error/expected-key :seon.config/effective
             :seon.error/diagnostic-layer :seon.config/read
             :seon.error/diagnostic-operation 'seon.config/effective-in
             :seon.error/diagnostic-member :seon.config/cluster
             :seon.error/diagnostic-expected :seon.config/effective
             :seon.error/diagnostic-offending cluster-name
             :seon.error/diagnostic-cause :seon.config/missing-effective
             :seon.error/diagnostic-evidence {:seon.config/missing missing}
             :seon.config/missing-effective cluster-name
             :seon.error/data {::missing missing}
             :seon.error/message
             (if row
               (str "Effective configuration for cluster " (pr-str cluster-name)
                    " is missing required facts " (pr-str (vec shown))
                    (when (pos? remaining)
                      (str " and " remaining " more")) ".")
               (str "No effective configuration facts match cluster "
                    (pr-str cluster-name) "; available clusters "
                    (pr-str available) "."))})))))))
