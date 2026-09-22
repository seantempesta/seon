(ns seon.config
  "The one compiler and database reconcile boundary for configuration.

  `config/default.edn` makes one explicit decision for every registered config
  attribute. A selected manifest is a sparse overlay. The caller may pass one
  more explicit, typed environment map; compilation applies exactly the
  precedence defaults → overlay → environment, validates every declared key,
  and derives one canonical effective map and desired row.

  Runtime consumers read only the database row. Omission from a sparse overlay
  inherits the shipped decision; it does not retract a defaulted optional
  attribute. `:seon.config/absent` is the one explicit retraction form, is
  refused for required attributes, and never becomes nil or a datom."
  (:require
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [seon.db :as db]
            [seon.error :as error]
            [seon.reconcile :as reconcile]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.schema.internal :as internal]))

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

(defn render-ai
  "`:seon.render/ai` — the bounded decision face of one effective config."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (when-let [cluster (:seon.config/cluster unit)]
    (str
     "Configuration " cluster ".\n"
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
      {:seon.error/at (java.util.Date.)
        :seon.error/layer :seon.config/read
        :seon.error/operation 'seon.config/result-caps
        :seon.config/error-key missing
        :seon.error/expected-key missing
        :seon.error/message "Value-admission caps require every declared configuration bound."
        :seon.error/offending effective
        :seon.error/data (cond-> {::key missing}
         (:seon.config/missing-effective effective)
         (assoc :seon.config/missing-effective
                (:seon.config/missing-effective effective))
         cluster-less-refusal
         (assoc ::configuration-refusal cluster-less-refusal))}
      (select-keys effective result-cap-attributes))))

;;; Every function below asks the declaration population one question per
;;; config key. The population is resolved ONCE per operation at that
;;; operation's entry point and passed down; asking `schema/schema-definition`
;;; per key re-reads and re-merges every schema resource per key (measured
;;; 2026-08-07: 1,036 ms / 12,616 resource reads for one
;;; `registration-defaults` — issue
;;; packaged-forms-rereads-every-schema-resource-per-call).

(defn dial-attributes
  "Config membership declared by retained leaf schemas."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:set :qualified-keyword]]}
  [projection]
  (into #{}
        (filter (fn [attribute]
                  (true? (:seon.config/dial
                          (m/properties (mr/schema (:seon.schema.projection/registry projection)
                                                   attribute))))))
        (keys (:seon.schema.projection/forms projection))))

(defn- required-dial-attributes
  [projection]
  (set (internal/map-required-attrs
        (mr/schema (:seon.schema.projection/registry projection) :seon.config/effective))))

(defn- registration-defaults
  [projection]
  (let [required (required-dial-attributes projection)]
    (into {}
          (keep
           (fn [attribute]
             (let [properties
                   (m/properties (mr/schema (:seon.schema.projection/registry projection) attribute))]
               (cond
                 (contains? properties :seon.config/default)
                 [attribute (:seon.config/default properties)]

                 (not (contains? required attribute))
                 [attribute absent])))
          (dial-attributes projection)))))

(defn- refuse!
  {:malli/schema [:=> [:cat :seon.config/rule-error [:maybe :seon.error/throwable]] :nil]}
  [observation cause]
  (throw (ex-info (:seon.error/message observation) observation cause)))

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
      (refuse!
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.config/compile
         :seon.error/operation 'seon.config/read-edn-map
         :seon.error/message "Configuration requires exactly one readable EDN map."
         :seon.config/error-key :seon.config/path
         :seon.config/rule ::manifest-unreadable
         :seon.error/expected-key :seon.config/path
         :seon.error/offending path
         :seon.error/data {::path path}
         :seon.error/expected "exactly one readable EDN map"}
       error))))

(defn- validate-layer
  [projection layer]
  (let [forms (:seon.schema.projection/forms projection)
        dials (set (dial-attributes projection))
        declared (select-keys layer dials)]
    (when (contains? layer initialization-key)
      (refuse!
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.config/compile
         :seon.error/operation 'seon.config/validate-layer
         :seon.error/message "Configuration requires an overlay containing only configuration dials."
         :seon.config/error-key initialization-key
         :seon.config/rule ::initialization-not-allowed
         :seon.error/expected-key initialization-key
         :seon.error/offending layer
         :seon.error/data {::key initialization-key}
         :seon.error/expected "an overlay containing only configuration dials"}
       nil))
    (doseq [[config-key value] declared]
      (when-not (or (= absent value)
                    ((schema/projection-validator projection config-key) value))
        (refuse!
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.config/compile
         :seon.error/operation 'seon.config/validate-layer
         :seon.error/message "Configuration requires values satisfying their declared configuration schemas."
         :seon.config/error-key config-key
         :seon.config/rule ::invalid-value
         :seon.error/expected-key config-key
         :seon.error/offending value
         :seon.error/data {::key config-key
          ::explanation ((schema/projection-explainer projection config-key) value)}
         :seon.error/expected "values satisfying their declared configuration schemas"}
       nil)))
    declared))

(defn- row-identity
  [projection row]
  (let [identities
        (into []
              (comp
               (filter #(schema/identity-attr? projection %))
               (map (fn [attribute] [attribute (get row attribute)])))
              (keys row))]
    (when (= 1 (count identities))
      (first identities))))

(defn- admit-initialization-rows
  [projection population]
  (let [forms (:seon.schema.projection/forms projection)
        database-attributes (set (schema/canonical-database-attributes projection))]
    (mapv
     (fn [row]
       (when-not (map? row)
         (refuse!
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.config/compile
         :seon.error/operation 'seon.config/admit-initialization-rows
         :seon.error/message "Configuration requires an initialization entity map."
         :seon.config/error-key :seon.config/initialization
         :seon.config/rule ::invalid-initialization-row
         :seon.error/expected-key :seon.config/initialization
         :seon.error/offending row
         :seon.error/data {}
         :seon.error/expected "an initialization entity map"}
       nil))
       (doseq [[attribute value] row]
         (cond
           (not (qualified-keyword? attribute))
           (refuse!
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.config/compile
         :seon.error/operation 'seon.config/admit-initialization-rows
         :seon.error/message "Configuration requires qualified attribute keys."
         :seon.config/error-key :seon.config/initialization
         :seon.config/rule ::invalid-initialization-attribute
         :seon.error/expected-key :seon.config/initialization
         :seon.error/offending attribute
         :seon.error/data {::key attribute}
         :seon.error/expected "qualified attribute keys"}
       nil)

           (not (contains? database-attributes attribute))
           (refuse!
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.config/compile
         :seon.error/operation 'seon.config/admit-initialization-rows
         :seon.error/message "Configuration requires declared database attributes."
         :seon.config/error-key attribute
         :seon.config/rule ::unknown-initialization-attribute
         :seon.error/expected-key attribute
         :seon.error/offending value
         :seon.error/data {::key attribute}
         :seon.error/expected "declared database attributes"}
       nil)

           (not ((schema/projection-validator projection attribute) value))
           (refuse!
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.config/compile
         :seon.error/operation 'seon.config/admit-initialization-rows
         :seon.error/message "Configuration requires values satisfying their declared attribute schemas."
         :seon.config/error-key attribute
         :seon.config/rule ::invalid-initialization-value
         :seon.error/expected-key attribute
         :seon.error/offending value
         :seon.error/data {::key attribute
                     ::explanation
                     ((schema/projection-explainer projection attribute) value)}
         :seon.error/expected "values satisfying their declared attribute schemas"}
       nil)))
       (when-not (row-identity projection row)
         (refuse!
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.config/compile
         :seon.error/operation 'seon.config/admit-initialization-rows
         :seon.error/message "Configuration requires an installed entity identity attribute."
         :seon.config/error-key :seon.config/initialization
         :seon.config/rule ::invalid-initialization-identity
         :seon.error/expected-key :seon.config/initialization
         :seon.error/offending row
         :seon.error/data {::explanation
                   {:seon.config/identity-attributes
                    (into []
                          (filter #(schema/identity-attr? projection %))
                          (keys row))}}
         :seon.error/expected "an installed entity identity attribute"}
       nil))
       row)
     population)))

(defn- admit-initialization
  [projection population]
  (when-not (vector? population)
    (refuse!
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.config/compile
         :seon.error/operation 'seon.config/admit-initialization
         :seon.error/message "Configuration requires a vector of initialization entity maps."
         :seon.config/error-key :seon.config/initialization
         :seon.config/rule ::invalid-initialization
         :seon.error/expected-key :seon.config/initialization
         :seon.error/offending population
         :seon.error/data {::explanation {:seon.config/expected :vector-of-maps}}
         :seon.error/expected "a vector of initialization entity maps"}
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
        dials (dial-attributes projection)
        decisions (merge (registration-defaults projection)
                         (select-keys document dials))
        missing (set/difference dials (set (keys decisions)))]
    (when (seq missing)
      (refuse!
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.config/compile
         :seon.error/operation 'seon.config/validate-default-decisions
         :seon.error/message "Configuration requires an explicit default decision for every dial."
         :seon.config/error-key :seon.config/effective
         :seon.config/rule ::missing-default
         :seon.error/expected-key :seon.config/effective
         :seon.error/offending missing
         :seon.error/data {::explanation {:seon.config/missing missing}}
         :seon.error/expected "an explicit default decision for every dial"}
       nil))
    (doseq [[config-key decision] decisions]
      (when-not (or (= absent decision)
                    (and (= config-key :seon.config.flow.compute/concurrency)
                         (= available-processors decision))
                    ((schema/projection-validator projection config-key) decision))
        (refuse!
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.config/compile
         :seon.error/operation 'seon.config/validate-default-decisions
         :seon.error/message "Configuration requires values satisfying their declared configuration schemas."
         :seon.config/error-key config-key
         :seon.config/rule ::invalid-value
         :seon.error/expected-key config-key
         :seon.error/offending decision
         :seon.error/data {::key config-key
          ::explanation ((schema/projection-explainer projection config-key) decision)}
         :seon.error/expected "values satisfying their declared configuration schemas"}
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
  [config-key decision]
  (if (and (= config-key :seon.config.flow.compute/concurrency)
           (= decision available-processors))
    (long (.availableProcessors (Runtime/getRuntime)))
    decision))

(defn- settings-projection
  "Compile the config declarations with their Malli references.

  Loading shipped config must not resolve predicates belonging to unrelated
  program declarations while their namespaces are still loading."
  {:malli/schema [:=> [:cat] :seon.schema/projection]}
  []
  (let [forms (schema.edn/packaged-forms)
        registry (mr/composite-registry (m/default-schemas)
                                        (mr/fast-registry forms))
        projection {:seon.schema.projection/forms forms
                    :seon.schema.projection/registry registry}
        roots #{:seon.config/effective :seon.config/manifest}]
    (loop [pending roots seen #{}]
      (if-let [schema-key (first pending)]
        (let [seen (conj seen schema-key)
              references (when-let [form (get forms schema-key)]
                           (schema/direct-references projection form))]
          (recur (into (disj pending schema-key) (remove seen) references) seen))
        (schema/declaration-projection (select-keys forms seen))))))

(defn- compile-settings
  {:malli/schema
   [:=> [:cat :seon.schema/projection :map :map]
    [:map
     [:seon.config/effective :seon.config/effective]
     [:seon.config/resolved-attributes [:set :qualified-keyword]]]]}
  [projection document request]
  (let [decisions (dissoc document initialization-key)
        manifest (validate-layer projection (or (:seon.config/manifest request) {}))
        environment
        (validate-layer projection (or (:seon.config/environment request) {}))
        defaults (validate-default-decisions projection decisions)
        decisions (merge defaults manifest environment)
        required (required-dial-attributes projection)]
    (doseq [[config-key decision] decisions]
      (when (and (= absent decision) (contains? required config-key))
        (refuse!
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.config/compile
         :seon.error/operation 'seon.config/compile-settings
         :seon.error/message "Configuration requires a value for each required configuration key."
         :seon.config/error-key config-key
         :seon.config/rule ::required-absent
         :seon.error/expected-key config-key
         :seon.error/offending decision
         :seon.error/data {::key config-key}
         :seon.error/expected "a value for each required configuration key"}
       nil)))
    (let [effective
          (into {}
                (comp
                 (remove (comp #{absent} val))
                 (map
                  (fn [[config-key decision]]
                    [config-key (resolve-smart-decision config-key decision)])))
                decisions)]
      (when-not ((schema/projection-validator projection :seon.config/effective) effective)
        (refuse!
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.config/compile
         :seon.error/operation 'seon.config/compile-settings
         :seon.error/message "Configuration requires values satisfying their declared configuration schemas."
         :seon.config/error-key :seon.config/effective
         :seon.config/rule ::invalid-value
         :seon.error/expected-key :seon.config/effective
         :seon.error/offending effective
         :seon.error/data {::explanation
          ((schema/projection-explainer projection :seon.config/effective) effective)}
         :seon.error/expected "values satisfying their declared configuration schemas"}
       nil))
      {:seon.config/effective effective
       :seon.config/resolved-attributes (set (keys decisions))})))

(defn compile-manifest
  "Compile settings and a desired row for the explicitly named cluster."
  {:malli/schema
   [:=> [:cat :seon.config/compile-request] :seon.config/compiled]}
  [request]
  (let [cluster-name (:seon.boot/cluster-name request)]
    (when-not (and (string? cluster-name) (seq cluster-name))
      (refuse!
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.config/compile
         :seon.error/operation 'seon.config/compile-manifest
         :seon.error/message "Configuration requires a value for each required configuration key."
         :seon.config/error-key :seon.boot/cluster-name
         :seon.config/rule ::required-absent
         :seon.error/expected-key :seon.boot/cluster-name
         :seon.error/offending request
         :seon.error/data {::key :seon.boot/cluster-name}
         :seon.error/expected "a value for each required configuration key"}
       nil))
    (let [document (default-document)
          projection (schema/declaration-projection (schema.edn/packaged-forms))
          initialization (admit-initialization projection (get document initialization-key []))
          compiled (compile-settings projection document request)]
      (assoc compiled
             :seon.config/initialization initialization
             :seon.config/desired-row
             (assoc (:seon.config/effective compiled)
                    :seon.config/cluster cluster-name)))))

(def defaults
  "The immutable shipped default configuration, compiled at namespace load.

  This program constant is rebuilt when the namespace reloads. Cluster
  configuration remains explicit data supplied by the caller."
  (:seon.config/effective
   (compile-settings (settings-projection) (default-document) {})))

(defn- desired-tempid
  [config-identity]
  (str "seon.config.initialization/" (pr-str config-identity)))

(defn- population-transaction-data
  [projection database desired]
  (let [identities (mapv #(row-identity projection %) desired)
        entity-ids
        (into {}
              (map
               (fn [config-identity]
                 ;; A refused read is not "no such entity". Minting a tempid
                 ;; for an identity the database already holds produces a
                 ;; conflicting upsert; refuse with the read as the cause.
                 (let [pulled (db/pull database [:db/id] config-identity)]
                   (when (and (map? pulled) (:seon.error/at pulled)
                              (:seon.error/layer pulled) (:seon.error/operation pulled)) ; debt: seon.db/pull and transact! declare seon.db/error-result with :seon.error/value.

                     (refuse!
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.config/compile
         :seon.error/operation 'seon.config/population-transaction-data
         :seon.error/message "Configuration requires a successful population identity read."
         :seon.config/error-key :seon.config/identity
         :seon.config/rule ::read-refused
         :seon.error/expected-key :seon.config/identity
         :seon.error/offending pulled
         :seon.error/data {:seon.config/identity config-identity
                               ::read-error pulled}
         :seon.error/expected "a successful population identity read"}
       nil))
                   [config-identity (or (:db/id pulled) (desired-tempid config-identity))])))
              identities)
        ref-value
        (fn [value]
          (if-let [entity-id (and (vector? value) (get entity-ids value))]
            entity-id
            value))]
    (mapv
     (fn [row]
       (into {:db/id (get entity-ids (row-identity projection row))}
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

(defn require-functions!
  "Refuse every manifest symbol lacking a current program function, in one query."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :map]] :nil]}
  [database rows]
  (letfn [(references [attribute value]
            (cond
              (qualified-symbol? value) [[attribute value]]
              (map? value) (mapcat (fn [[key child]] (references (if (qualified-keyword? key) key attribute) child)) value)
              (coll? value) (mapcat #(references attribute %) value)
              :else []))]
    (let [named (vec (distinct (mapcat #(references :seon.config/initialization %) rows)))
          missing (db/q '[:find ?key ?symbol
                          :in $ [[?key ?symbol]]
                          :where (not-join [?symbol] [_ :seon.fn/sym ?symbol])]
                        database named)]
      (when (:seon.error/at missing)
        (throw (ex-info (:seon.error/message missing) missing)))
      (when (seq missing)
        (let [missing (vec (sort-by pr-str missing))]
          (refuse!
           {:seon.error/at (java.util.Date.)
             :seon.error/layer :seon.config/compile
             :seon.error/operation 'seon.config/require-functions!
             :seon.error/message (str "Configuration names functions with no program row: " (pr-str missing))
             :seon.config/error-key (ffirst missing)
             :seon.config/rule ::missing-function
             :seon.error/expected-key :seon.fn/sym
             :seon.error/offending missing
             :seon.error/data {:seon.config/missing-functions missing}}
           nil)))))
  nil)

(defn- reconcile-call
  "Validate the manifest's function names against the writer's database."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection
                       [:vector :map] :seon.reconcile/request] :seon.db/tx-data]}
  [database projection desired request]
  (require-functions! database desired)
  (let [tx-data (reconcile/plan database request)
        rows (filterv map? tx-data)
        replacements (zipmap rows (population-transaction-data projection database rows))]
    (mapv #(get replacements % %) tx-data)))

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
        desired-identities (into #{} (keep #(row-identity projection %)) desired)
        ;; A config row another cluster's required `:seon.cluster/config`
        ;; names is that cluster's: retracting it would invalidate its
        ;; referrer, so reconciliation never manages it, whichever process
        ;; wrote it. An inherited row no cluster owns is reconciled away.
        owned-config-identities
        (into #{}
              (comp (map (fn [cluster-name] [:seon.config/cluster cluster-name]))
                    (remove desired-identities))
              (db/q '[:find [?cluster-name ...]
                      :where
                      [?config :seon.config/cluster ?cluster-name]
                      [_ :seon.cluster/config ?config]]
                    database))
        inherited-config-identities
        (into #{}
              (map (fn [cluster-name]
                     [:seon.config/cluster cluster-name]))
              (db/q '[:find [?cluster-name ...]
                      :where
                      [?config :seon.config/cluster ?cluster-name]
                      (not [_ :seon.cluster/config ?config])]
                    database))
        identities (into inherited-config-identities desired-identities)
        request
        (cond-> {::reconcile/desired desired
                 ::reconcile/process managing-process-identity
                 ::reconcile/adopt-identities identities}
          (seq owned-config-identities)
          (assoc ::reconcile/retain-identities owned-config-identities))
        ;; Both config dials and initialization rows are compared as facts.
        operations (count (reconcile/plan database request))
        result
        (if (zero? operations)
          (do (require-functions! database desired)
              {::reconcile/converged? true
               ::reconcile/operations 0})
          (let [transaction-result
                (db/transact!
                 connection
                 {:tx-data
                  [[:db.fn/call #'reconcile-call projection desired request]]
                  :tx-meta
                  {:seon.db/process
                   [:seon.db.process/id managing-process-identity]}})]
            (if (and (map? transaction-result) (:seon.error/at transaction-result)
                              (:seon.error/layer transaction-result) (:seon.error/operation transaction-result)) ; debt: seon.db/pull and transact! declare seon.db/error-result with :seon.error/value.

              transaction-result
              {::reconcile/converged? false
               ::reconcile/operations operations})))]
    (when (:seon.error/at result)
      (throw (ex-info (:seon.error/message result) result)))
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
     :seon.schema/validation-refusal :seon.db/invalid-read-error]]}
  [db cluster-name]
  (if-let [projection (or (db/carried-projection db)
                          (schema/handed-projection))]
    (effective-in db cluster-name projection)
    (db/projection-fallback 'seon.config/effective)))

(defn- effective-in
  {:malli/schema
   [:=> [:cat [:or :seon.db/database-value :seon.db/error-result]
         :seon.boot/cluster-name :seon.schema/projection]
    [:or :seon.config/effective :seon.config/error :seon.db/invalid-read-error]]}
  [db cluster-name projection]
  (let [forms (:seon.schema.projection/forms projection)
        row (db/pull db '[*] [:seon.config/cluster cluster-name])]
    ;; A refused read is not an absent row. Reading the refusal's own keys as
    ;; the config row reports every dial missing and blames the facts; the
    ;; cause here is the read, so return the read's refusal unchanged.
    (if (:seon.db/invalid-read row)
      row
      (let [config-effective (select-keys row (dial-attributes projection))
            missing (vec (sort (set/difference (required-dial-attributes projection)
                                               (set (keys config-effective)))))]
        (if (and row (empty? missing))
          config-effective
          (let [available
                  (db/q '[:find [?available ...]
                          :where
                          [_ :seon.config/cluster ?available]]
                        db)]
            (if (:seon.db/invalid-read available)
              available
            {:seon.error/at (java.util.Date.)
              :seon.error/layer :seon.config/read
              :seon.error/operation 'seon.config/effective-in
              :seon.config/error-key :seon.config/cluster
              :seon.error/expected-key :seon.config/effective
              :seon.config/missing-effective cluster-name
              :seon.error/message "Effective configuration requires a matching cluster row with every required dial."
              :seon.error/data {::missing missing ::available (vec (sort available))}})))))))
