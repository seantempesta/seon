(ns seon.config
  "The one compiler and database reconcile boundary for configuration.

  `config/default.edn` makes one explicit decision for every registered config
  attribute. A selected manifest is a sparse overlay. The caller may pass one
  more explicit, typed environment map; compilation applies exactly the
  precedence defaults → overlay → environment, validates every declared key,
  and derives one canonical effective map and desired row.

  Runtime consumers read only the database row. Omission from a sparse overlay
  inherits the decision beneath it: the cluster's held overlay decisions
  (`:seon.config/overlay-keys`), else the shipped decision; it does not retract
  a defaulted optional attribute. `:seon.config/absent` is the one explicit retraction form, is
  refused for required attributes, and never becomes nil or a datom."
  (:require [seon.print :as print]
            [seon.error.refusal]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [seon.cache]
            [seon.db :as db]
            [seon.reconcile :as reconcile]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.datahike :as schema.datahike]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.schema.internal :as internal]))

(schema.edn/load! {})

(def default-manifest-path
  "The one repository/artifact-relative shipped defaults document."
  "config/default.edn")

(def initialization-key
  "The reserved shipped-default key carrying desired initialization entities."
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
     (:seon.config.flow.io/concurrency unit) " I/O.")))

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
                 (:seon.config.flow.io/concurrency unit) " I/O")]]]]))

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
        cluster-name (:seon.config/missing-effective effective)]
    ;; `:seon.error/offending` already carries a cluster-less refusal whole.
    (if missing
      (seon.error.refusal/error :seon.config/error
       (cond-> {:seon.error/layer :seon.config/read
                :seon.error/operation 'seon.config/result-caps
                :seon.config/error-key missing
                :seon.error/expected-key missing
                :seon.error/message "Value-admission caps require every declared configuration bound."
                :seon.error/offending effective
                ::key missing}
         cluster-name (assoc :seon.config/missing-effective cluster-name)))
      (select-keys effective result-cap-attributes))))

;;; Every function below asks the declaration population one question per
;;; config key. The population is resolved ONCE per operation at that
;;; operation's entry point and passed down; asking `schema/schema-definition`
;;; per key re-reads and re-merges every schema resource per key (measured
;;; 2026-08-07: 1,036 ms / 12,616 resource reads for one
;;; `registration-defaults` — issue
;;; packaged-forms-rereads-every-schema-resource-per-call).

(defn dial-attributes
  "Config membership declared by retained leaf schemas.

  A function of the projection alone, so the projection's own holder keeps
  the answer (`schema/projection-cache-value`): the walk visits every form in
  the registry, and every config read on a fresh database value asked it again
  (1.9 ms per call, three per agent form, perf-eval-overhead 2026-09-26)."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:set :qualified-keyword]]}
  [projection]
  (schema/projection-cache-value
   projection ::dial-attributes
   #(into #{}
          (filter (fn [attribute]
                    (true? (:seon.config/dial
                            (m/properties (mr/schema (:seon.schema.projection/registry projection)
                                                     attribute))))))
          (keys (:seon.schema.projection/forms projection)))))

(defn- required-dial-attributes
  [projection]
  (schema/projection-cache-value
   projection ::required-dial-attributes
   #(set (internal/map-required-attrs
          (mr/schema (:seon.schema.projection/registry projection) :seon.config/effective)))))

(defn- registration-defaults
  "Return the defaults derived from this immutable declaration projection."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:map-of :qualified-keyword :seon.schema/value]]}
  [projection]
  (schema/projection-cache-value
   projection ::registration-defaults
   #(let [required (required-dial-attributes projection)]
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
            (dial-attributes projection))))))

(defn- refuse!
  {:malli/schema [:=> [:cat :seon.config/rule-error [:maybe :seon.error/throwable]]
                  [:or :nil :seon.config/rule-error]]}
  [members cause]
  ;; THE constructor builds the error against `:seon.config/rule-error` (the
  ;; declared registry answers here, at boot and load included), deriving the
  ;; whole cause chain and frame; the boundary throws that value.
  (let [built (seon.error.refusal/error
               :seon.config/rule-error
               (cond-> members cause (assoc :seon.error/throwable cause)))]
    (throw (ex-info (:seon.error/message built) built cause))))

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
         ::path path
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
         ::key initialization-key
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
         ::key config-key
         :seon.error/data {::explanation ((schema/projection-explainer projection config-key) value)}
         :seon.error/expected "values satisfying their declared configuration schemas"}
       nil)))
    declared))

(defn- entity-identity
  [projection entity]
  (let [identities
        (into []
              (comp
               (filter #(schema/identity-attr? projection %))
               (map (fn [attribute] [attribute (get entity attribute)])))
              (keys entity))]
    (when (= 1 (count identities))
      (first identities))))

(defn- admit-initialization-entities
  [projection population]
  (let [forms (:seon.schema.projection/forms projection)
        database-attributes (set (schema/canonical-database-attributes projection))]
    (mapv
     (fn [entity]
       (when-not (map? entity)
         (refuse!
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.config/compile
         :seon.error/operation 'seon.config/admit-initialization-entities
         :seon.error/message "Configuration requires an initialization entity map."
         :seon.config/error-key :seon.config/initialization
         :seon.config/rule ::invalid-initialization-row
         :seon.error/expected-key :seon.config/initialization
         :seon.error/offending entity
         :seon.error/expected "an initialization entity map"}
       nil))
       (doseq [[attribute value] entity]
         (cond
           (not (qualified-keyword? attribute))
           (refuse!
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.config/compile
         :seon.error/operation 'seon.config/admit-initialization-entities
         :seon.error/message "Configuration requires qualified attribute keys."
         :seon.config/error-key :seon.config/initialization
         :seon.config/rule ::invalid-initialization-attribute
         :seon.error/expected-key :seon.config/initialization
         :seon.error/offending attribute
         ::key attribute
         :seon.error/expected "qualified attribute keys"}
       nil)

           (not (contains? database-attributes attribute))
           (refuse!
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.config/compile
         :seon.error/operation 'seon.config/admit-initialization-entities
         :seon.error/message "Configuration requires declared database attributes."
         :seon.config/error-key attribute
         :seon.config/rule ::unknown-initialization-attribute
         :seon.error/expected-key attribute
         :seon.error/offending value
         ::key attribute
         :seon.error/expected "declared database attributes"}
       nil)

           (not ((schema/projection-validator projection attribute) value))
           (refuse!
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.config/compile
         :seon.error/operation 'seon.config/admit-initialization-entities
         :seon.error/message "Configuration requires values satisfying their declared attribute schemas."
         :seon.config/error-key attribute
         :seon.config/rule ::invalid-initialization-value
         :seon.error/expected-key attribute
         :seon.error/offending value
         ::key attribute
         :seon.error/data {::explanation
                     ((schema/projection-explainer projection attribute) value)}
         :seon.error/expected "values satisfying their declared attribute schemas"}
       nil)))
       (when-not (entity-identity projection entity)
         (refuse!
       {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.config/compile
         :seon.error/operation 'seon.config/admit-initialization-entities
         :seon.error/message "Configuration requires an installed entity identity attribute."
         :seon.config/error-key :seon.config/initialization
         :seon.config/rule ::invalid-initialization-identity
         :seon.error/expected-key :seon.config/initialization
         :seon.error/offending entity
         :seon.error/data {::explanation
                   {:seon.config/identity-attributes
                    (into []
                          (filter #(schema/identity-attr? projection %))
                          (keys entity))}}
         :seon.error/expected "an installed entity identity attribute"}
       nil))
       entity)
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
  (admit-initialization-entities projection population))

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

(defn- declared-projection
  "The declaration projection of this JVM's schema resources: the one population
  config compiles rows from, writes them with and reads them by. One process
  cache entry, the declaration-projection kind's own."
  {:malli/schema [:=> [:cat] :seon.schema/projection]}
  []
  (schema/declaration-projection (schema.edn/packaged-forms)))

(defn default-population
  "Read and admit the shipped initialization entity entities, or those of the
  default `document` handed in its place."
  {:malli/schema [:function
                  [:=> [:cat] [:vector :map]]
                  [:=> [:cat :seon.config/default-document] [:vector :map]]]}
  ([] (default-population (default-document)))
  ([document]
   (let [projection (declared-projection)]
     (:seon.config/initialization
      (admitted-default-document projection document)))))

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
         ::key config-key
         :seon.error/data {::explanation ((schema/projection-explainer projection config-key) decision)}
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
  (let [projection (declared-projection)]
    (validate-default-decisions
     projection
     (:seon.config/decisions
      (admitted-default-document projection (default-document))))))

(defn read-manifest
  "Read and validate one sparse plain-EDN overlay without compiling it."
  {:malli/schema [:=> [:cat :string] :seon.config/manifest]}
  [path]
  (validate-layer (declared-projection)
                  (read-edn-map path)))

(def development-manifest-path
  "The development cluster's sparse overlay, relative to the JVM's source directory.
  Boot applies it to the cluster the JVM was launched for; tests never read it."
  "config/development.edn")

(defn development-manifest
  "Read the development cluster's sparse overlay."
  {:malli/schema [:=> [:cat] :seon.config/manifest]}
  []
  (read-manifest development-manifest-path))

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
         ::key config-key
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
  "Compile settings and a desired entity for the explicitly named cluster."
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
         ::key :seon.boot/cluster-name
         :seon.error/expected "a value for each required configuration key"}
       nil))
    (let [document (or (:seon.config/default-document request) (default-document))
          projection (declared-projection)
          initialization (admit-initialization projection (get document initialization-key []))
          compiled (compile-settings projection document request)
          ;; The entity records which dials a layer above the shipped defaults
          ;; decided, so a later apply can hold them underneath its own keys.
          overlay-keys (into #{} (filter (dial-attributes projection))
                             (keys (merge (:seon.config/manifest request)
                                          (:seon.config/environment request))))]
      (assoc compiled
             :seon.config/initialization initialization
             :seon.config/desired-row
             (cond-> (assoc (:seon.config/effective compiled)
                            :seon.config/cluster cluster-name)
               (seq overlay-keys) (assoc :seon.config/overlay-keys overlay-keys))))))

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
  (let [identities (mapv #(entity-identity projection %) desired)
        entity-ids
        (into {}
              (map
               (fn [config-identity]
                 ;; A refused read is not "no such entity". Minting a tempid
                 ;; for an identity the database already holds produces a
                 ;; conflicting upsert; refuse with the read as the cause.
                 (let [pulled (db/pull database [:db/id] config-identity)]
                   (when (seon.error.refusal/error? pulled) ; debt: seon.db/pull and transact! declare seon.db/error-result with :seon.error/value.

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
     (fn [entity]
       (into {:db/id (get entity-ids (entity-identity projection entity))}
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
             entity))
     desired)))

(defn require-functions!
  "Refuse every manifest symbol lacking a current program function, in one query."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :map]] :nil]}
  [database entities]
  (letfn [(references [attribute value]
            (cond
              (qualified-symbol? value) [[attribute value]]
              (map? value) (mapcat (fn [[key child]] (references (if (qualified-keyword? key) key attribute) child)) value)
              (coll? value) (mapcat #(references attribute %) value)
              :else []))]
    (let [named (vec (distinct (mapcat #(references :seon.config/initialization %) entities)))
          missing (db/q '[:find ?key ?symbol
                          :in $ [[?key ?symbol]]
                          :where (not-join [?symbol] [_ :seon.fn/sym ?symbol])]
                        database named)]
      (when (seon.error.refusal/error? missing)
        (throw (ex-info (:seon.error/message missing) missing)))
      (when (seq missing)
        (let [missing (vec (sort print/compare-values missing))]
          (refuse!
           {:seon.error/at (java.util.Date.)
             :seon.error/layer :seon.config/compile
             :seon.error/operation 'seon.config/require-functions!
             :seon.error/message "Configuration names functions (:seon.error/offending) with no program entity."
             :seon.config/error-key (ffirst missing)
             :seon.config/rule ::missing-function
             :seon.error/expected-key :seon.fn/sym
             :seon.error/offending missing
             :seon.error/data {:seon.config/missing-functions missing}}
           nil)))))
  nil)

(defn- reconcile-call
  "Validate the manifest's function names against the writer's database, whose
  own declarations shape the rows (`db/carried-projection`): the transaction
  names its inputs, never a copy of a projection."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:vector :map] :seon.reconcile/request] :seon.db/tx-data]}
  [database desired request]
  (require-functions! database desired)
  (let [tx-data (reconcile/plan database request)
        entities (filterv map? tx-data)
        replacements (zipmap entities (population-transaction-data
                                   (db/carried-projection database) database entities))]
    (mapv #(get replacements % %) tx-data)))

(defn- hold-retired-attributes
  "Each desired row with its entity's current values of attributes the checkout
  no longer declares.

  Rows compile from the checkout's declarations onto a branch that may not have
  adopted them yet; `accrete-row-attributes!` is the added-attribute side. A
  retired attribute is not configuration's to remove: adoption retires it with
  its data (`seon.cluster/declaration-changes`), while until then the branch's
  own declarations may still require it."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection [:vector :map]]
                  [:vector :map]]}
  [database projection entities]
  (let [declared (schema.edn/packaged-forms)]
    (mapv (fn [entity]
            (let [identity (entity-identity projection entity)
                  current (when identity (db/pull database '[*] identity))]
              (when (:seon.db/invalid-read current)
                (throw (ex-info (str (:seon.error/message current)) current)))
              (into entity
                    (remove (fn [[attribute _]]
                              (or (= :db/id attribute) (contains? declared attribute))))
                    current)))
          entities)))

(defn- accrete-entity-attributes!
  "Install each attribute the desired rows name that the branch has not installed.

  Rows compile from the checkout's declarations, but boot writes them onto a
  branch whose program may not have adopted those declarations yet, and
  Datahike's `:write` schema mode refuses an uninstalled attribute. Each
  missing declaration is derived from the same packaged projection the rows
  compiled from and transacted before the row. Adoption later installs the
  declaration row itself; a converged branch pays one schema lookup per key."
  {:malli/schema [:=> [:cat :seon.db/connection [:vector :map]] :nil]}
  [connection entities]
  (let [installed (:schema (db/db connection))
        missing (into (sorted-set)
                      (comp (mapcat keys) (filter qualified-keyword?)
                            (remove #(contains? installed %)))
                      entities)]
    (when (seq missing)
      (let [projection (declared-projection)
            result (db/transact!
                    connection
                    ;; The schema population's own accretion shape (`seon.cluster/accrete-schema-population!`).
                    {:tx-data (mapv #(schema.datahike/malli->datahike-attr-in projection %) missing)})]
        (when (seon.error.refusal/error? result)
          (throw (ex-info (str (:seon.error/message result)) result))))))
  nil)

(defn apply-compiled!
  "Exact-reconcile one already-compiled desired config entity."
  {:malli/schema
   [:=> [:cat :seon.db/connection :seon.config/compiled]
    :seon.reconcile/result]}
  [connection compiled]
  (accrete-entity-attributes! connection (into [(:seon.config/desired-row compiled)]
                                           (:seon.config/initialization compiled)))
  (let [database (db/db connection)
        projection (or (db/carried-projection database)
                       (schema/handed-projection)
                       (do (db/projection-fallback 'seon.config/apply-compiled!)
                           (schema/projection-from-database database)))
        forms (:seon.schema.projection/forms projection)
        desired
        (hold-retired-attributes
         database projection
         (into [(:seon.config/desired-row compiled)]
               (:seon.config/initialization compiled)))
        desired-identities (into #{} (keep #(entity-identity projection %)) desired)
        ;; A config entity another cluster's required `:seon.cluster/config`
        ;; names is that cluster's: retracting it would invalidate its
        ;; referrer, so reconciliation never manages it, whichever process
        ;; wrote it. An inherited entity no cluster owns is reconciled away.
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
        ;; Both config dials and initialization entities are compared as facts.
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
                  [[:db.fn/call #'reconcile-call desired request]]
                  :tx-meta
                  {:seon.db/process
                   [:seon.db.process/id managing-process-identity]}})]
            (if (seon.error.refusal/error? transaction-result) ; debt: seon.db/pull and transact! declare seon.db/error-result with :seon.error/value.

              transaction-result
              {::reconcile/converged? false
               ::reconcile/operations operations})))]
    (when (seon.error.refusal/error? result)
      (throw (ex-info (:seon.error/message result) result)))
    result))

(defn- held-overlay
  "The overlay decisions one cluster's row records, as a sparse manifest.

  A key `:seon.config/overlay-keys` lists but the row lacks was decided absent."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.boot/cluster-name]
                  :seon.config/manifest]}
  [database cluster-name]
  (let [entity (db/pull database '[*] [:seon.config/cluster cluster-name])]
    (when (:seon.db/invalid-read entity)
      (throw (ex-info (str (:seon.error/message entity)) entity)))
    (into {} (map (fn [config-key] [config-key (get entity config-key absent)]))
          (:seon.config/overlay-keys entity))))

(defn apply!
  "Compile once and exact-reconcile the one desired config row.

  The request's manifest layers over the decisions the cluster's row already
  holds (boot's overlays, earlier applies, the environment), so an apply
  changes only its own keys; a key returns to the shipped decision only by
  applying the shipped document. Undecided dials follow the shipped defaults,
  and a hand edit of one is reconciled away.

  With a path, read one selected EDN document. The shipped document selects
  defaults and their initialization rows and replaces every held decision; any
  other document is a sparse overlay and obeys the same admission rules as an
  in-memory manifest."
  {:malli/schema
   [:function
    [:=> [:cat :seon.config/apply-request] :seon.reconcile/result]
    [:=> [:cat :seon.config/apply-request :seon.config/path]
     :seon.reconcile/result]]}
  ([request]
   (let [connection (:seon.db/connection request)
         cluster-name (:seon.boot/cluster-name request)
         ;; An omitted cluster is refused by name in `compile-manifest`.
         held (when (and (string? cluster-name) (seq cluster-name))
                (held-overlay (db/db connection) cluster-name))]
     (apply-compiled!
      connection
      (compile-manifest
       (cond-> (select-keys request [:seon.config/environment :seon.boot/cluster-name
                                     :seon.config/default-document])
         (or (seq held) (:seon.config/manifest request))
         (assoc :seon.config/manifest (merge held (:seon.config/manifest request))))))))
  ([request path]
   (let [document (read-edn-map path)]
     (if (= document (default-document))
       (apply-compiled! (:seon.db/connection request)
                        (compile-manifest (select-keys request [:seon.boot/cluster-name])))
       (apply! (assoc request :seon.config/manifest document))))))

(defn agent-cluster
  "The cluster `agent-id` was created in (`:seon.agent/cluster`), whose
  config is the agent's config; for an agent created before agents named
  their cluster, the branch's one config row.

  Several clusters' config rows may share one branch (`apply-compiled!`
  keeps every row another cluster's `:seon.cluster/config` names), so an
  agent's dial is read through its own cluster, never from whichever row a
  query meets first. An unnamed agent on a branch holding several rows is
  refused by name."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or :seon.cluster/name :seon.config/error :seon.db/invalid-read-error]]}
  [database agent-id]
  (let [named (when (get (:schema database) :seon.agent/cluster)
                (db/q '[:find ?name . :in $ ?id
                        :where [?agent :seon.agent/id ?id] [?agent :seon.agent/cluster ?name]]
                      database agent-id))
        rows (when-not named (db/q '[:find [?name ...] :where [_ :seon.config/cluster ?name]] database))]
    (cond
      named named
      (db/read-refusal? rows) rows
      (= 1 (count rows)) (first rows)
      :else
      (seon.error.refusal/diagnostic
       (java.util.Date.) :seon.config/read 'seon.config/agent-cluster
       {:seon.config/error-key :seon.agent/cluster
        :seon.error/expected-key :seon.agent/cluster
        :seon.error/offending agent-id
        :seon.error/message (str "Agent " agent-id " names no cluster (:seon.agent/cluster) and its branch holds " (count rows) " cluster config rows, so none is its own.")
        :seon.error/fix "Create the agent through seon.cluster.agent/creation-tx, which names its cluster."}))))

(defn agent-cluster-dial
  "`dial` of `agent-id`'s own cluster config (`agent-cluster`): its value,
  nil when that config leaves it undecided, or the refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id :qualified-keyword]
                  [:or :nil :seon.schema/value :seon.config/error]]}
  [database agent-id dial]
  (let [cluster-name (agent-cluster database agent-id)]
    (if (string? cluster-name)
      (let [row (db/pull database [dial] [:seon.config/cluster cluster-name])]
        (if (db/read-refusal? row) row (get row dial)))
      cluster-name)))

(declare effective-in)

(defn effective
  "Read one cluster's effective config row, as this JVM's code reads it.

  The row keeps its branch's own vocabulary: every dial its carried projection
  declares (a branch-local dial included) and every dial this JVM declares
  ([[declared-projection]], whose `:seon.config/effective` contract the answer
  is checked against). A required dial the row lacks because an older program
  wrote it takes its shipped decision ([[defaults]]), whether the schema or
  the shipped document declares it: a dial added later requires nothing of
  rows written before it (accretion), so an agent branch forked before the
  dial existed reads it at its first read. Boot's applied row
  holds the dials the checkout added before the branch adopts their
  declarations (`apply-compiled!` installs the attribute), so they are read
  from the row, never from the default. A missing row refuses, naming the
  cluster in `:seon.config/missing-effective`; a required dial no decision
  covers is named in `:seon.error/offending`."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.boot/cluster-name]
    [:or :seon.config/effective :seon.config/error
     :seon.schema/validation-refusal :seon.db/invalid-read-error]]}
  [db cluster-name]
  (let [carried (db/carried-projection db)
        commit (when carried (:datahike.value/commit-id (db/committed-value-identity db)))]
    (cond
      ;; A committed value fixes its config entity and carried projection; the
      ;; JVM's declarations are named by their digest.
      commit (seon.cache/lookup ::effective [commit cluster-name (schema.edn/declaration-digest)]
                                #(* 128 (count %))
                                #(effective-in db cluster-name carried (declared-projection))
                                #(not (contains? % :seon.error/operation)))
      :else (if-let [projection (or carried (schema/handed-projection))]
              (effective-in db cluster-name projection (declared-projection))
              (db/projection-fallback 'seon.config/effective)))))

(defn- effective-in
  {:malli/schema
   [:=> [:cat [:or :seon.db/database-value :seon.db/error-result]
         :seon.boot/cluster-name :seon.schema/projection :seon.schema/projection]
    [:or :seon.config/effective :seon.config/error :seon.db/invalid-read-error]]}
  [db cluster-name projection declared]
  (let [entity (db/pull db '[*] [:seon.config/cluster cluster-name])]
    ;; A refused read is not an absent entity. Reading the refusal's own keys as
    ;; the config entity reports every dial missing and blames the facts; the
    ;; cause here is the read, so return the read's refusal unchanged.
    (if (:seon.db/invalid-read entity)
      entity
      (let [required (required-dial-attributes declared)
            config-effective
            ;; A required dial the row lacks was never decided for it: it
            ;; reads the shipped decision, what `apply!` writes for an
            ;; undecided dial, so a dial added after the row was written
            ;; exists at its first read on every branch.
            (merge (select-keys defaults required)
                   (select-keys entity (into (dial-attributes declared) (dial-attributes projection))))
            missing (vec (sort (set/difference required (set (keys config-effective)))))]
        (if (and entity (empty? missing))
          config-effective
          (let [available
                  (db/q '[:find [?available ...]
                          :where
                          [_ :seon.config/cluster ?available]]
                        db)]
            (if (:seon.db/invalid-read available)
              available
            (seon.error.refusal/error :seon.config/error
             (cond->
             {:seon.error/layer :seon.config/read
              :seon.error/operation 'seon.config/effective-in
              :seon.config/error-key :seon.config/cluster
              :seon.error/expected-key :seon.config/effective
              :seon.config/missing-effective cluster-name
              :seon.error/message "Effective configuration requires a matching cluster entity with every required dial."
              :seon.error/data {::missing missing ::available (vec (sort available))}}
              (seq missing) (assoc :seon.error/offending missing))))))))))
