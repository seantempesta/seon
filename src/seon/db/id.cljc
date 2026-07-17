(ns seon.db.id
  "Persistent identity syntax and private package-backed candidates.

   Identity attributes carry `::generator` in their registered Malli
   properties. The collision-safe allocator resolves that policy; these
   adapters only propose values. Agent ids use three readable word segments,
   while every other generated persistent identity uses a 12-character CUID2.
   The database writer remains the authority for uniqueness and commit."
  (:require
   [clojure.string :as str]
   [malli.core :as m]
   #?(:cljs [seon.db :as db]
      :default [seon.db :as-alias db])
   [seon.db.id.schema :as id.schema]
   [seon.schema :as schema]
   #?@(:bb []
       :clj [[datahike.api :as d]
             [datahike.connector :as connector]
             [datahike.db.utils :as dbu]
             [datahike.writer :as writer]
             [datahike.writing :as writing]])
   #?@(:cljs [[seon.db.internal :as db.internal]])
   #?@(:cljs [["@paralleldrive/cuid2" :as cuid2]
              ["human-id" :as human-id]]))
  #?@(:bb []
      :clj [(:import
             [com.github.kkuegler RandomHumanReadableIdGenerator]
             [io.github.thibaultmeyer.cuid CUID])]))

;;; ---------------------------------------------------------------------------
;;; Syntax and policy
;;; ---------------------------------------------------------------------------

;;; ---------------------------------------------------------------------------
;;; Atomic allocation contract
;;; ---------------------------------------------------------------------------

;; `:any` is intentional only at the Datahike boundary: a db value and a
;; transaction item are third-party library values with no honest closed
;; Malli shape in Seon.
(schema/register! ::db-value :any)
(schema/register! ::transaction-data [:vector :any])
(schema/register! ::tempid [:or :string :int])
(schema/register! ::transaction-tempids [:vector ::tempid])
(schema/register! ::allocation-tempids [:map-of ::key ::tempid])
(schema/register! ::report-tempids :map)
(schema/register! ::reserved-fields [:vector :keyword])
(schema/register! ::message :string)
(schema/register! ::status
                  [:enum ::ready ::protocol-error ::candidate-conflict])
(schema/register! ::error-status
                  [:enum ::protocol-error ::candidate-conflict ::unrelated])
(schema/register! ::throwable :any)
(schema/register! ::datahike-config :map)
(schema/register! ::connection :any)

(schema/register!
 ::prepare-request
 [:map
  [::db-value ::db-value]
  [::transaction-data ::transaction-data]
  [::generated-candidates ::generated-candidates]])

(schema/register!
 ::generator-policies-request
 [:map [::db-value ::db-value]])

(schema/register!
 ::transaction-tempids-request
 [:map
  [::db-value ::db-value]
  [::transaction-data ::transaction-data]])

(schema/register!
 ::prepare-response
 [:or
  [:map
   [::status [:= ::ready]]
   [::transaction-data ::transaction-data]
   [::allocation-tempids ::allocation-tempids]]
  [:map
   [::status [:= ::candidate-conflict]]
   [::generated-candidate ::generated-candidate]]
  [:map
   [::status [:= ::protocol-error]]
   [::message ::message]]])

(schema/register!
 ::allocation-error-request
 [:map
  [::generated-candidates ::generated-candidates]
  [::throwable ::throwable]])

(schema/register!
 ::resolve-request
 [:map
  [::allocation-tempids ::allocation-tempids]
  [::report-tempids ::report-tempids]])

(schema/register!
 ::allocation-error-response
 [:or
  [:map
   [::error-status [:= ::candidate-conflict]]
   [::generated-candidate ::generated-candidate]]
  [:map
   [::error-status [:= ::protocol-error]]
   [::message ::message]]
  [:map [::error-status [:= ::unrelated]]]])

;;; ---------------------------------------------------------------------------
;;; Canonical transaction preparation
;;; ---------------------------------------------------------------------------

#?(:bb nil
   :default
   (do
     #?(:clj
        (do

     (defn- tempid?
       [value]
       (or (string? value)
           (and (integer? value) (neg? value))))

     (defn- straight-attr
       [attr]
       (if (and (keyword? attr) (dbu/reverse-ref? attr))
         (dbu/reverse-ref attr)
         attr))

     (defn- ref-attr?
       [datahike-schema attr]
       (= :db.type/ref
          (get-in datahike-schema [(straight-attr attr) :db/valueType])))

     (defn- many-attr?
       [datahike-schema attr]
       (= :db.cardinality/many
          (get-in datahike-schema [(straight-attr attr) :db/cardinality])))

     (defn- identity-attr?
       [datahike-schema attr]
       (= :db.unique/identity (get-in datahike-schema [attr :db/unique])))

     (defn generator-policies
       "Return the generated-identity policy facts stored in one database value.

   Policy is ordinary EAV data on each `:seon.schema/key` entity. The database,
   not a client manifest or process-global Malli registry, is authoritative for
   the serialized writer. Every fact is validated against the installed native
   identity schema and the reserved human-readable agent rule before use."
       {:malli/schema [:=> [:cat ::generator-policies-request]
                       ::generator-policies]}
       [{::keys [db-value]}]
       (let [rows (d/q '[:find ?identity-attr ?generator
                         :where
                         [?schema :seon.schema/key ?identity-attr]
                         [?schema :seon.db.id/generator ?generator]]
                       db-value)
             policies (into {} rows)
             installed-schema (:schema db-value)]
         (when-not (= (count rows) (count policies))
           (throw
            (ex-info "A generated identity attribute has conflicting policy facts."
                     {::error :seon.db.id.error/conflicting-generator-policy
                      :seon.error/kind :core-bug})))
         (doseq [[identity-attr generator] policies]
           (when-not (and (qualified-keyword? identity-attr)
                          (m/validate ::generator generator)
                          (identity-attr? installed-schema identity-attr))
             (throw
              (ex-info "A stored generator policy does not name an installed identity attribute."
                       {::error :seon.db.id.error/invalid-generator-policy
                        ::identity-attr identity-attr
                        ::generator generator
                        :seon.error/kind :core-bug})))
           (when (and (= generator :seon.db.id.generator/human-readable)
                      (not= identity-attr :seon.agent/id))
             (throw
              (ex-info "Human-readable generation is reserved for :seon.agent/id."
                       {::error :seon.db.id.error/human-readable-non-agent
                        ::identity-attr identity-attr
                        ::generator generator
                        :seon.error/kind :core-bug})))
           (when (and (= identity-attr :seon.agent/id)
                      (not= generator :seon.db.id.generator/human-readable))
             (throw
              (ex-info ":seon.agent/id must use the human-readable generator."
                       {::error :seon.db.id.error/agent-generator
                        ::identity-attr identity-attr
                        ::generator generator
                        :seon.error/kind :core-bug}))))
         policies))

     (defn- lookup-ref?
       [datahike-schema value]
       (and (sequential? value)
            (= 2 (count value))
            (keyword? (first value))
            (identity-attr? datahike-schema (first value))))

     (defn- tx-schema-declarations
       [tx-data]
       (into {}
             (keep (fn [item]
                     (when (and (map? item)
                                (qualified-keyword? (:db/ident item)))
                       [(:db/ident item)
                        (select-keys item [:db/valueType :db/cardinality :db/unique
                                           :db/isComponent])])))
             tx-data))

     (defn- effective-schema
       [installed-schema tx-data]
  ;; A current installed fact is authoritative over a declaration repeated in
  ;; the incoming transaction. New declarations still make nested ref walking
  ;; correct for a schema+data transaction.
       (merge (tx-schema-declarations tx-data) installed-schema))

     (declare entity-maps-in)

     (defn- nested-entity-maps
       [datahike-schema attr value]
       (when (ref-attr? datahike-schema attr)
         (cond
           (map? value)
           (entity-maps-in datahike-schema value)

           (and (many-attr? datahike-schema attr) (coll? value))
           (mapcat #(when (map? %) (entity-maps-in datahike-schema %)) value)

           :else
           nil)))

     (defn- entity-maps-in
       [datahike-schema item]
       (when (map? item)
         (cons item
               (mapcat (fn [[attr value]]
                         (when (keyword? attr)
                           (nested-entity-maps datahike-schema attr value)))
                       item))))

     (defn- all-entity-maps
       [datahike-schema tx-data]
       (mapcat #(entity-maps-in datahike-schema %) tx-data))

     (defn- dependent-identity-claims
       "The candidate-owned dependent identity claims as
   `[generated-candidate lookup-ref]` pairs. Internal normalized form: the
   public builder's candidate-key entries have already been attached to their
   generated candidate before the request reaches the serialized writer."
       [candidates]
       (mapcat (fn [candidate]
                 (map (fn [lookup-ref] [candidate lookup-ref])
                      (::dependent-lookup-refs candidate)))
               candidates))

     (defn- dependent-lookup-ref-set
       [candidates]
       (into #{} (map second) (dependent-identity-claims candidates)))

     (declare generated-candidate-valid?)

     (defn- manifest-error
       [installed-schema tx-data candidates policies]
       (let [datahike-schema (effective-schema installed-schema tx-data)
             identity-attr-set (set (keys policies))
             candidate-keys (map ::key candidates)
             candidate-attrs (map ::identity-attr candidates)
             dependent-claims (vec (dependent-identity-claims candidates))
             dependent-lookups (mapv second dependent-claims)]
         (cond
           (not (vector? candidates))
           "generated candidates must be a vector"

           (empty? candidates)
           "generated candidates must not be empty"

           (some (fn [candidate]
                   (or (not (map? candidate))
                       (not (qualified-keyword? (::key candidate)))
                       (not (qualified-keyword? (::identity-attr candidate)))
                       (not (string? (::value candidate)))
                       (str/blank? (::value candidate))
                       (and (contains? candidate ::dependent-lookup-refs)
                            (not (m/validate ::dependent-lookup-refs
                                             (::dependent-lookup-refs candidate))))))
                 candidates)
           (str "each generated candidate must have a key, identity attr, value, "
                "and valid dependent lookup refs")

           (not= (count candidate-keys) (count (distinct candidate-keys)))
           "generated candidate keys must be distinct"

           (some #(not (contains? identity-attr-set %)) candidate-attrs)
           "every candidate identity attr must have a stored generator policy"

           (some (fn [{::keys [identity-attr value]}]
                   (not (generated-candidate-valid? (get policies identity-attr)
                                                    value)))
                 candidates)
           "every generated candidate must match its stored generator policy"

           (some #(not (identity-attr? datahike-schema %)) candidate-attrs)
           "every candidate identity attr must be installed or declared as identity"

           (not= (count dependent-lookups) (count (distinct dependent-lookups)))
           "dependent identity lookup refs must be distinct across candidates"

           (some (fn [[_candidate [attr _value]]]
                   (not (identity-attr? datahike-schema attr)))
                 dependent-claims)
           "every dependent identity attr must be installed or declared as identity"

           :else
           nil)))

     (defn- duplicate-candidate
       [candidates]
       (loop [seen #{}
              [candidate & remaining] candidates]
         (when candidate
           (let [candidate-value (::value candidate)]
             (if (contains? seen candidate-value)
               candidate
               (recur (conj seen candidate-value) remaining))))))

     (defn- matching-candidates
       [candidates entity]
       (filterv (fn [candidate]
                  (let [attr (::identity-attr candidate)]
                    (and (contains? entity attr)
                         (= (::value candidate) (get entity attr)))))
                candidates))

     (defn- identity-assertions
       [datahike-schema tx-data]
       (concat
        (mapcat (fn [entity]
                  (keep (fn [[attr value]]
                          (when (identity-attr? datahike-schema attr)
                            [attr value]))
                        entity))
                (all-entity-maps datahike-schema tx-data))
        (keep (fn [item]
                (when (and (vector? item)
                           (= :db/add (first item))
                           (= 4 (count item))
                           (identity-attr? datahike-schema (nth item 2)))
                  [(nth item 2) (nth item 3)]))
              tx-data)))

     (defn- entity-asserts-identity?
       [entity [attr value]]
       (and (contains? entity attr)
            (= value (get entity attr))))

     (defn- claimed-identity-pairs
       "Every generated or dependent identity pair explicitly claimed on `entity`.
   Other identity assertions are ordinary upserts and therefore disqualify a
   claimed-new entity when they already resolve in the writer's old DB."
       [candidates entity]
       (into (into #{}
                   (map (juxt ::identity-attr ::value))
                   (matching-candidates candidates entity))
             (filter #(entity-asserts-identity? entity %))
             (dependent-lookup-ref-set candidates)))

     (defn- existing-unclaimed-identity?
       [db-value installed-schema candidates entity]
       (let [claimed (claimed-identity-pairs candidates entity)]
         (some (fn [[attr value]]
                 (and (identity-attr? installed-schema attr)
                      (not (contains? claimed [attr value]))
                      (seq (d/datoms db-value :avet attr value))))
               entity)))

     (defn- candidate-entity-error
       [db-value installed-schema datahike-schema tx-data candidates]
       (let [entities (vec (all-entity-maps datahike-schema tx-data))
             assertion-counts (frequencies (identity-assertions datahike-schema
                                                                tx-data))]
         (some (fn [candidate]
                 (let [matches (filterv (fn [entity]
                                          (seq (matching-candidates [candidate]
                                                                    entity)))
                                        entities)
                       entity (first matches)
                       entity-id (:db/id entity)
                       existing-other-identity?
                       (existing-unclaimed-identity? db-value installed-schema
                                                     candidates entity)
                       duplicate-identity-assertion?
                       (some (fn [{attr ::identity-attr value ::value}]
                               (> (get assertion-counts [attr value] 0) 1))
                             (matching-candidates candidates entity))]
                   (cond
                     (not= 1 (count matches))
                     "each generated candidate must occur in exactly one entity map"

                     (and (contains? entity :db/id)
                          (not (or (nil? entity-id) (tempid? entity-id))))
                     "a generated candidate entity must be new or use a tempid"

                     existing-other-identity?
                     "a generated candidate entity resolves an existing identity"

                     duplicate-identity-assertion?
                     "a candidate identity is asserted on multiple transaction entities"

                     :else
                     nil)))
               candidates)))

     (defn- dependent-identity-entity-error
       [db-value installed-schema datahike-schema tx-data candidates]
       (let [entities (vec (all-entity-maps datahike-schema tx-data))]
         (some (fn [[_candidate lookup-ref]]
                 (let [matches (filterv #(entity-asserts-identity? % lookup-ref)
                                        entities)]
                   (cond
                     (empty? matches)
                     "each dependent identity must occur in a transaction entity map"

                     (some (fn [entity]
                             (let [entity-id (:db/id entity)]
                               (and (contains? entity :db/id)
                                    (not (or (nil? entity-id)
                                             (tempid? entity-id))))))
                           matches)
                     "a dependent identity entity must be new or use a tempid"

                     (some #(existing-unclaimed-identity?
                             db-value installed-schema candidates %)
                           matches)
                     "a dependent identity entity resolves an unclaimed existing identity"

                     :else
                     nil)))
               (dependent-identity-claims candidates))))

     (defn- existing-candidate-conflict
       [db-value installed-schema candidates identity-attrs]
       (some (fn [candidate]
               (let [candidate-value (::value candidate)]
                 (some (fn [attr]
                         (when (and (identity-attr? installed-schema attr)
                                    (seq (d/datoms db-value :avet attr
                                                   candidate-value)))
                           candidate))
                       identity-attrs)))
             candidates))

     (defn- existing-dependent-identity-conflict
       [db-value installed-schema candidates]
       (some (fn [[candidate [attr value]]]
               (when (and (identity-attr? installed-schema attr)
                          (seq (d/datoms db-value :avet attr value)))
                 candidate))
             (dependent-identity-claims candidates)))

     (defn- candidate-entities
       [datahike-schema tx-data candidates]
       (keep (fn [entity]
               (when-let [matched (not-empty (matching-candidates candidates entity))]
                 {::entity entity ::matched-candidates matched}))
             (all-entity-maps datahike-schema tx-data)))

     (defn- generated-assertions
       [datahike-schema tx-data identity-attrs]
       (let [identity-attr-set (set identity-attrs)]
         (into []
               (concat
                (mapcat (fn [entity]
                          (keep (fn [[attr value]]
                                  (when (contains? identity-attr-set attr)
                                    [attr value]))
                                entity))
                        (all-entity-maps datahike-schema tx-data))
                (keep (fn [item]
                        (when (and (vector? item)
                                   (= :db/add (first item))
                                   (= 4 (count item))
                                   (contains? identity-attr-set (nth item 2)))
                          [(nth item 2) (nth item 3)]))
                      tx-data)))))

     (defn- incoming-candidate-conflict
       [datahike-schema tx-data candidates identity-attrs]
       (let [assertions (generated-assertions datahike-schema tx-data
                                              identity-attrs)]
         (some (fn [candidate]
                 (let [candidate-value (::value candidate)
                       candidate-attr (::identity-attr candidate)
                       occurrences (filter #(= candidate-value (second %))
                                           assertions)]
                   (when (or (> (count occurrences) 1)
                             (some #(not= candidate-attr (first %)) occurrences))
                     candidate)))
               candidates)))

     (defn- incoming-dependent-identity-conflict
       [datahike-schema tx-data candidates]
       (let [assertion-counts
             (frequencies (identity-assertions datahike-schema tx-data))]
         (some (fn [[candidate lookup-ref]]
                 (when (> (get assertion-counts lookup-ref 0) 1)
                   candidate))
               (dependent-identity-claims candidates))))

     (def ^:private transaction-id-values
       #{:db/current-tx ":db/current-tx" "datomic.tx" "datahike.tx"})

     (defn- external-tempid?
       [value]
       (and (tempid? value)
            (not (contains? transaction-id-values value))))

     (defn- transaction-tempids*
       [datahike-schema tx-data]
       (letfn [(one-ref-tempids [value]
                 (cond
                   (map? value) [value]
                   (lookup-ref? datahike-schema value) []
                   (external-tempid? value) [value]
                   :else []))
               (ref-tempids [attr value]
                 (if (and (many-attr? datahike-schema attr)
                          (coll? value)
                          (not (lookup-ref? datahike-schema value)))
                   (mapcat one-ref-tempids value)
                   (one-ref-tempids value)))
               (entity-tempids [entity]
                 (concat
                  (when (external-tempid? (:db/id entity))
                    [(:db/id entity)])
                  (mapcat (fn [[attr value]]
                            (when (ref-attr? datahike-schema attr)
                              (mapcat entity-or-tempid
                                      (ref-tempids attr value))))
                          entity)))
               (entity-or-tempid [value]
                 (if (map? value)
                   (entity-tempids value)
                   [value]))
               (item-tempids [item]
                 (cond
                   (map? item)
                   (entity-tempids item)

                   (and (sequential? item)
                        (= :db/add (first item))
                        (= 4 (count item)))
                   (let [[_ entity attr value] item]
                     (concat
                      (when (external-tempid? entity) [entity])
                      (when (ref-attr? datahike-schema attr)
                        (mapcat entity-or-tempid
                                (ref-tempids attr value)))))

                   :else []))]
         (second
          (reduce (fn [[seen ordered] tempid]
                    (if (contains? seen tempid)
                      [seen ordered]
                      [(conj seen tempid) (conj ordered tempid)]))
                  [#{} []]
                  (mapcat item-tempids tx-data)))))

     (defn transaction-tempids
       "Return caller-visible Datahike tempids in first-seen order.

   The exact old DB supplies installed ref/cardinality/identity schema; schema
   declarations in the transaction extend that view. Only entity ids and
   schema-declared ref positions are walked, including nested entity maps.
   Lookup refs and Datahike's reserved transaction ids are not tempids. The
   allocator's private replacement tempids are created later and are therefore
   deliberately absent from this result."
       {:malli/schema [:=> [:cat ::transaction-tempids-request]
                       ::transaction-tempids]}
       [{::keys [db-value transaction-data]}]
       (transaction-tempids* (effective-schema (:schema db-value)
                                               transaction-data)
                             transaction-data))

     (defn- fresh-allocator-tempid
       [used-tempids start-index]
       (loop [index start-index]
         (let [candidate (str "seon.db.id.temp/" index)]
           (if (contains? used-tempids candidate)
             (recur (inc index))
             [candidate (inc index)]))))

     (defn- candidate-tempid-plan
       [datahike-schema tx-data candidates]
       (reduce
        (fn [{::keys [used-tempids next-tempid-index]
              :as plan}
             {::keys [entity matched-candidates]}]
          (let [old-id (:db/id entity)
                reused (when (tempid? old-id) old-id)
                [tempid next-index] (if reused
                                      [reused next-tempid-index]
                                      (fresh-allocator-tempid used-tempids
                                                              next-tempid-index))
                plan' (-> plan
                          (assoc ::next-tempid-index next-index)
                          (update ::used-tempids conj tempid))]
            (reduce (fn [result candidate]
                      (assoc-in result [::allocation-tempids (::key candidate)]
                                tempid))
                    plan'
                    matched-candidates)))
        {::used-tempids (set (transaction-tempids* datahike-schema tx-data))
         ::next-tempid-index 0
         ::allocation-tempids {}}
        (candidate-entities datahike-schema tx-data candidates)))

     (declare rewrite-candidate-entity-map)

     (defn- rewrite-candidate-many-ref-values
       [datahike-schema plan values]
       (let [rewritten (mapv #(if (map? %)
                                (rewrite-candidate-entity-map datahike-schema plan %)
                                %)
                             values)]
         (cond
           (vector? values) rewritten
           (set? values)    (into (empty values) rewritten)
           :else            (seq rewritten))))

     (defn- rewrite-candidate-entity-map
       [datahike-schema plan entity]
       (let [matched (matching-candidates (::candidates plan) entity)
             entity' (if-let [candidate (first matched)]
                       (assoc entity :db/id
                              (get-in plan [::allocation-tempids (::key candidate)]))
                       entity)]
         (reduce-kv
          (fn [rewritten attr value]
            (if (or (= :db/id attr) (not (ref-attr? datahike-schema attr)))
              rewritten
              (assoc rewritten attr
                     (if (and (many-attr? datahike-schema attr)
                              (coll? value)
                              (not (lookup-ref? datahike-schema value)))
                       (rewrite-candidate-many-ref-values datahike-schema plan value)
                       (if (map? value)
                         (rewrite-candidate-entity-map datahike-schema plan value)
                         value)))))
          entity'
          entity')))

     (defn- rewrite-generated-transaction
       [datahike-schema tx-data candidates]
       (let [plan (assoc (candidate-tempid-plan datahike-schema tx-data candidates)
                         ::candidates candidates)
             rewritten (mapv (fn [item]
                               (if (map? item)
                                 (rewrite-candidate-entity-map datahike-schema plan
                                                               item)
                                 item))
                             tx-data)]
         [plan rewritten]))

     (defn prepare-transaction
       "Prepare one collision-safe generated-identity transaction."
       {:malli/schema [:=> [:cat ::prepare-request] ::prepare-response]}
       [{::keys [db-value transaction-data generated-candidates]}]
       (let [installed-schema (:schema db-value)
             datahike-schema (effective-schema installed-schema transaction-data)
             policies (generator-policies {::db-value db-value})
             generated-identity-attrs (vec (sort-by str (keys policies)))
             protocol-message (or (manifest-error installed-schema transaction-data
                                                  generated-candidates
                                                  policies)
                                  (candidate-entity-error db-value installed-schema
                                                          datahike-schema
                                                          transaction-data
                                                          generated-candidates)
                                  (dependent-identity-entity-error
                                   db-value installed-schema datahike-schema
                                   transaction-data generated-candidates))]
         (if protocol-message
           {::status ::protocol-error ::message protocol-message}
           (let [conflict (or (duplicate-candidate generated-candidates)
                              (incoming-candidate-conflict
                               datahike-schema transaction-data
                               generated-candidates generated-identity-attrs)
                              (incoming-dependent-identity-conflict
                               datahike-schema transaction-data
                               generated-candidates)
                              (existing-candidate-conflict
                               db-value installed-schema generated-candidates
                               generated-identity-attrs)
                              (existing-dependent-identity-conflict
                               db-value installed-schema generated-candidates))]
             (if conflict
               {::status ::candidate-conflict
                ::generated-candidate conflict}
               (let [[plan rewritten]
                     (rewrite-generated-transaction datahike-schema
                                                    transaction-data
                                                    generated-candidates)]
                 {::status ::ready
                  ::transaction-data rewritten
                  ::allocation-tempids (::allocation-tempids plan)}))))))

     (defn resolve-eids
       "Resolve allocation keys from a committed Datahike tempid report."
       {:malli/schema [:=> [:cat ::resolve-request] ::eids]}
       [{::keys [allocation-tempids report-tempids]}]
       (into {}
             (map (fn [[allocation-key tempid]]
                    (if-let [eid (get report-tempids tempid)]
                      [allocation-key eid]
                      (throw
                       (ex-info "Committed allocation omitted an allocator tempid."
                                {::error :seon.db.id.error/missing-committed-tempid
                                 ::key allocation-key
                                 ::tempid tempid})))))
             allocation-tempids))

     (defn- policy-value-valid?
       [generator value]
       (case generator
         :seon.db.id.generator/human-readable
         (m/validate ::agent-value value)

         :seon.db.id.generator/compact
         (m/validate ::compact-value value)

         false))

     (defn- managed-datoms
       [db-value policies]
       (mapcat (fn [identity-attr]
                 (d/datoms db-value :avet identity-attr))
               (keys policies)))

     (defn- value-occurrences
       [db-value policies value]
       (mapcat (fn [identity-attr]
                 (d/datoms db-value :avet identity-attr value))
               (keys policies)))

     (defn- policy-error!
       [message error data]
       (throw
        (ex-info message
                 (merge {::error error
                         :seon.error/kind :core-bug}
                        data))))

     (defn- assert-policy-transition!
       [db-after policies-before policies-after]
       (let [removed (remove #(contains? policies-after %) (keys policies-before))]
         (when-let [identity-attr
                    (some (fn [attr]
                            (when (seq (d/datoms db-after :avet attr)) attr))
                          removed)]
           (policy-error!
            "A generator policy cannot be removed while its identity values exist."
            :seon.db.id.error/generator-policy-removal-in-use
            {::identity-attr identity-attr})))
       (let [all-datoms (vec (managed-datoms db-after policies-after))]
         (when-let [datom
                    (some (fn [datom]
                            (let [identity-attr (:a datom)
                                  generator (get policies-after identity-attr)]
                              (when-not (policy-value-valid? generator (:v datom))
                                datom)))
                          all-datoms)]
           (policy-error!
            "A stored identity value does not match its generator policy."
            :seon.db.id.error/invalid-managed-identity-value
            {::identity-attr (:a datom)
             ::generator (get policies-after (:a datom))
             ::value (:v datom)}))
         (when-let [[value occurrences]
                    (some (fn [[value datoms]]
                            (when (> (count datoms) 1)
                              [value datoms]))
                          (group-by :v all-datoms))]
           (policy-error!
            "A generated identity value exists under more than one managed attribute."
            :seon.db.id.error/cross-attribute-identity-collision
            {::value value
             ::identity-attr (mapv :a occurrences)}))))

     (defn- assert-generated-identity-report!
       "Validate the complete uncommitted TxReport before Datahike queues it.

   Report validation, rather than only input inspection, includes nested maps,
   transaction-function output, and transaction metadata. Datahike may include
   an exact existing identity reassertion as an added report datom, so the
   before value distinguishes that ordinary upsert from a fresh identity.
   Every fresh current-grammar value must be an exact allocator manifest
   candidate, except the explicit root genesis identity."
       [report candidates]
       (let [db-before (:db-before report)
             db-after (:db-after report)
             policies-before (generator-policies {::db-value db-before})
             policies-after (generator-policies {::db-value db-after})
             policies (if (= policies-before policies-after)
                        policies-before
                        (do (assert-policy-transition! db-after policies-before
                                                       policies-after)
                            policies-after))
             candidate-pairs (into #{}
                                   (map (juxt ::identity-attr ::value))
                                   candidates)
             added-managed
             (filterv (fn [datom]
                        (and (:added datom)
                             (contains? policies (:a datom))))
                      (:tx-data report))]
         (doseq [datom added-managed]
           (let [identity-attr (:a datom)
                 value (:v datom)
                 generator (get policies identity-attr)
                 current-generated? (generated-candidate-valid? generator value)
                 allocated? (contains? candidate-pairs [identity-attr value])
                 preexisting? (seq (d/datoms db-before :avet identity-attr value))
                 root-genesis? (and (= :seon.agent/id identity-attr)
                                    (= "root" value))]
             (when-not (policy-value-valid? generator value)
               (policy-error!
                "A generated identity value does not match its stored policy."
                :seon.db.id.error/invalid-managed-identity-value
                {::identity-attr identity-attr
                 ::generator generator
                 ::value value}))
             (when (and current-generated?
                        (not allocated?)
                        (not preexisting?)
                        (not root-genesis?))
               (policy-error!
                "A new generated identity must be created through seon.db.id/allocate!."
                :seon.db.id.error/unallocated-generated-identity
                {::identity-attr identity-attr
                 ::value value}))
             (let [occurrences (vec (value-occurrences db-after policies value))]
               (when (> (count occurrences) 1)
                 (policy-error!
                  "A generated identity value collides across managed attributes."
                  :seon.db.id.error/cross-attribute-identity-collision
                  {::value value
                   ::identity-attr (mapv :a occurrences)})))))
         report))

     (defn- transact-with-generated-ids*
  ;; This private leaf is deliberately not Malli-instrumented. Datahike keeps
  ;; the function object in the connection's writer config; a public var that
  ;; instrumentation later replaces would make an identity guard lie about
  ;; the writer actually installed on that connection.
       [old arg-map]
       (let [generated? (contains? arg-map ::generated-candidates)
             candidates (or (::generated-candidates arg-map) [])]
         (if generated?
           (let [prepared (prepare-transaction
                           {::db-value old
                            ::transaction-data (:tx-data arg-map)
                            ::generated-candidates candidates})]
             (case (::status prepared)
               :seon.db.id/ready
               (let [report (writing/transact!
                             old
                             (-> arg-map
                                 (assoc :tx-data (::transaction-data prepared))
                                 (dissoc ::generated-candidates)))
                     _ (assert-generated-identity-report! report candidates)
                     eids (resolve-eids
                           {::allocation-tempids (::allocation-tempids prepared)
                            ::report-tempids (:tempids report)})]
                 (assoc report ::generated-eids eids))

               :seon.db.id/candidate-conflict
               (throw
                (ex-info "Generated identity candidate is already in use."
                         {::error :seon.db.id.error/candidate-conflict
                          ::generated-candidate (::generated-candidate prepared)
                          :seon.error/kind :user-input}))

               :seon.db.id/protocol-error
               (throw
                (ex-info (::message prepared)
                         {::error :seon.db.id.error/invalid-allocation-transaction
                          ::message (::message prepared)
                          :seon.error/kind :core-bug}))))
           (assert-generated-identity-report!
            (writing/transact! old arg-map)
            candidates))))

     (def ^:private allocation-writer-backend
       :seon.db.id.writer/serialized)

     (defmethod writer/create-writer :seon.db.id.writer/serialized
       [writer-config connection]
  ;; Datahike persists the connection config on every commit. Keep the live
  ;; function out of that config: this backend keyword is durable data, while
  ;; the method supplies the private operation only to the runtime writer.
       (writer/create-writer
        (-> writer-config
            (assoc :backend :self)
            (assoc :write-fn-map {'transact! transact-with-generated-ids*}))
        connection))

     (defmethod connector/-connect* :seon.db.id.writer/serialized
       [config opts]
       (connector/-connect-impl* config opts))

     (defn allocation-connect-config
       "Add atomic generated-id allocation to a Datahike connect config.

   Use the returned config only for `datahike.api/connect`, and make it the
   first connection for that store and branch. Never pass it to
   `datahike.api/create-database`. The durable backend keyword installs the
   private operation at writer creation without placing a live function in the
   persisted database config. Remote configs already
   have their own serialized writer."
       {:malli/schema [:=> [:cat ::datahike-config] ::datahike-config]}
       [config]
       (let [backend (get-in config [:writer :backend] :self)]
         (when (contains? (get config :writer) :write-fn-map)
           (throw
            (ex-info "Allocation writer config cannot persist live write functions."
                     {::error :seon.db.id.error/runtime-write-functions
                      ::datahike-config config
                      :seon.error/kind :core-bug})))
         (when-not (#{:self allocation-writer-backend} backend)
           (throw
            (ex-info "Allocation connect config requires Datahike's self writer."
                     {::error :seon.db.id.error/non-local-allocation-writer
                      ::datahike-config config
                      :seon.error/kind :core-bug})))
         (assoc-in config [:writer :backend] allocation-writer-backend)))))

     (defn- throwable-cause
       [throwable]
       #?(:clj  (.getCause ^Throwable throwable)
          :cljs (.-cause throwable)))

     (defn- structured-conflict-data
       [throwable]
       (loop [current throwable
              seen #{}]
         (when (and current (not (contains? seen current)))
           (let [data (ex-data current)]
             (if (or (= "seon.db.id.error" (some-> (::error data) namespace))
                     (#{:transact/unique :transact/upsert} (:error data)))
               data
               (recur (throwable-cause current) (conj seen current)))))))

     (defn- matching-conflict-candidate
       [generated-candidates data]
       (if (= :seon.db.id.error/candidate-conflict (::error data))
         (let [candidate (::generated-candidate data)]
           (some #(when (= candidate %) %) generated-candidates))
         (let [error (:error data)
               [attr candidate-value]
               (case error
                 :transact/unique
                 (let [datom (:datom data)]
                   [(:attribute data) (:v datom)])

                 :transact/upsert
                 (let [[_ assertion-attr assertion-value] (:assertion data)]
                   [assertion-attr assertion-value])

                 [nil nil])]
           (when (#{:transact/unique :transact/upsert} error)
             (some (fn [attempt]
                     (when (or (and (= attr (::identity-attr attempt))
                                    (= candidate-value (::value attempt)))
                               (contains? (set (::dependent-lookup-refs attempt))
                                          [attr candidate-value]))
                       attempt))
                   generated-candidates)))))

     (defn classify-allocation-error
       "Classify a writer error without mistaking unrelated uniqueness failures."
       {:malli/schema
        [:=> [:cat ::allocation-error-request] ::allocation-error-response]}
       [{::keys [generated-candidates throwable]}]
       (let [data (structured-conflict-data throwable)
             candidate (matching-conflict-candidate generated-candidates data)]
         (cond
           candidate
           {::error-status ::candidate-conflict
            ::generated-candidate candidate}

           (= "seon.db.id.error" (some-> (::error data) namespace))
           {::error-status ::protocol-error
            ::message (or (::message data)
                          (some-> throwable ex-message)
                          "invalid generated identity transaction")}

           :else
           {::error-status ::unrelated})))

;;; ---------------------------------------------------------------------------
;;; Private package adapters
;;; ---------------------------------------------------------------------------

     (def ^:private compact-length 12)

     #?(:clj
        (defonce ^:private human-readable-generator
          (RandomHumanReadableIdGenerator.)))

     #?(:cljs
        (defonce ^:private compact-generator
          (cuid2/init #js {:length compact-length})))

     (defn- human-readable-candidate []
       #?(:clj  (.generate ^RandomHumanReadableIdGenerator
                 human-readable-generator)
          :cljs (human-id/humanId #js {:separator "-"
                                       :capitalize false})))

     (defn- compact-candidate []
       #?(:clj  (str (CUID/randomCUID2 compact-length))
          :cljs (compact-generator)))

     (defn- generate-candidate [generator]
       (case generator
         :seon.db.id.generator/human-readable (human-readable-candidate)
         :seon.db.id.generator/compact        (compact-candidate)
         (throw
          (ex-info "Identity attribute has no supported generator policy."
                   {::generator generator
                    ::error     :seon.db.id.error/unsupported-generator}))))

;;; ---------------------------------------------------------------------------
;;; Schema-driven candidate rounds
;;; ---------------------------------------------------------------------------

     (def ^:private max-attempts 16)

     (defn- failure
       [message kind data]
       {:seon.db/ok? false
        :seon.db/error
        {:seon.error/message message
         :seon.error/kind kind
         :seon.error/data data}})

     (defn- generated-candidate-valid?
       [generator candidate]
       (case generator
         :seon.db.id.generator/human-readable
         (boolean (and (string? candidate)
                       (re-matches id.schema/word-pattern candidate)
                       (not= "root" candidate)))

         :seon.db.id.generator/compact
         (boolean (and (string? candidate)
                       (re-matches id.schema/compact-pattern candidate)))

         false))

     (defn- candidate-round!
       [policies allocations]
       (mapv
        (fn [{allocation-key ::key identity-attr ::identity-attr}]
          (let [generator (get policies identity-attr)
                _ (when-not generator
                    (throw
                     (ex-info "Generated identity attribute has no stored generator policy."
                              {::error :seon.db.id.error/missing-generator-policy
                               ::identity-attr identity-attr
                               :seon.error/kind :core-bug})))
                candidate (generate-candidate generator)]
            (when-not (generated-candidate-valid? generator candidate)
              (throw
               (ex-info "Identity package emitted a value outside its registered syntax."
                        {::error :seon.db.id.error/invalid-package-output
                         ::identity-attr identity-attr
                         ::generator generator
                         ::value candidate
                         :seon.error/kind :core-bug})))
            {::key allocation-key
             ::identity-attr identity-attr
             ::value candidate}))
        allocations))

     (defn- candidate-map
       [manifest]
       (into {} (map (juxt ::key ::value)) manifest))

     (defn- attach-dependent-identities!
       "Attach the builder's public candidate-key claims to the private generated
   manifest sent through the existing serialized-writer field. This keeps the
   transaction shape unchanged: `seon.db.internal` already forwards the
   generated manifest, and the writer strips that whole manifest before
   Datahike sees the domain transaction."
       [manifest dependent-identities]
       (if (nil? dependent-identities)
         manifest
         (do
           (when-not (m/validate ::dependent-identities dependent-identities)
             (throw
              (ex-info "Allocation builder returned invalid dependent identities."
                       {::error :seon.db.id.error/invalid-dependent-identities
                        :seon.error/kind :core-bug})))
           (let [candidate-keys (set (map ::key manifest))]
             (when-let [unknown
                        (some (fn [{::keys [candidate-key]}]
                                (when-not (contains? candidate-keys candidate-key)
                                  candidate-key))
                              dependent-identities)]
               (throw
                (ex-info "Dependent identity names an unknown allocation candidate."
                         {::error :seon.db.id.error/unknown-dependent-candidate
                          ::candidate-key unknown
                          :seon.error/kind :core-bug})))
             (reduce
              (fn [candidates {::keys [candidate-key lookup-ref]}]
                (mapv (fn [candidate]
                        (if (= candidate-key (::key candidate))
                          (update candidate ::dependent-lookup-refs
                                  (fnil conj []) lookup-ref)
                          candidate))
                      candidates))
              manifest
              dependent-identities)))))

     (defn- exact-generated-conflict?
       [envelope manifest]
       (boolean
        (matching-conflict-candidate
         manifest
         (get-in envelope [:seon.db/error :seon.error/data]))))

     #?(:clj
        (defn- candidate-conflict-envelope
          [candidate]
          (failure
           "Generated identity candidate is already in use."
           :user-input
           {::error :seon.db.id.error/candidate-conflict
            ::generated-candidate candidate})))

     (defn- validate-request!
       [{::keys [allocations transaction-builder generator-policies]}]
       (when-not (m/validate ::allocate-request
                             (cond->
                               {::allocations allocations
                                ::transaction-builder transaction-builder}
                               generator-policies
                               (assoc ::generator-policies
                                      generator-policies)))
         (throw
          (ex-info "Invalid seon.db.id/allocate! request."
                   {::error :seon.db.id.error/invalid-request
                    :seon.error/kind :core-bug})))
       (when-not (= (count allocations) (count (set (map ::key allocations))))
         (throw
          (ex-info "Allocation keys must be distinct within one request."
                   {::error :seon.db.id.error/duplicate-allocation-key
                    ::allocations allocations
                    :seon.error/kind :core-bug})))
       nil)

     #?(:cljs
        (do
          (def generator-policy-query
            "Stored identity-generator policies, parameterized by identity attributes."
            '[:find ?identity-attr ?generator
              :in $ [?identity-attr ...]
              :where
              [?schema :seon.schema/key ?identity-attr]
              [?schema :seon.db.id/generator ?generator]])

          (defn- validate-generator-policies!
            [allocations policies]
            (let [required (set (map ::identity-attr allocations))]
              (when-not (m/validate ::generator-policies policies)
                (throw
                 (ex-info "Database returned invalid generated identity policies."
                          {::error :seon.db.id.error/invalid-generator-policies
                           ::generator-policies policies
                           :seon.error/kind :core-bug})))
              (doseq [identity-attr required]
                (let [generator (get policies identity-attr)]
                  (when-not generator
                    (throw
                     (ex-info "Generated identity attribute has no stored generator policy."
                              {::error :seon.db.id.error/missing-generator-policy
                               ::identity-attr identity-attr
                               :seon.error/kind :core-bug})))
                  (when (and (= generator :seon.db.id.generator/human-readable)
                             (not= identity-attr :seon.agent/id))
                    (throw
                     (ex-info "Human-readable generation is reserved for :seon.agent/id."
                              {::error :seon.db.id.error/human-readable-non-agent
                               ::identity-attr identity-attr
                               ::generator generator
                               :seon.error/kind :core-bug})))
                  (when (and (= identity-attr :seon.agent/id)
                             (not= generator
                                   :seon.db.id.generator/human-readable))
                    (throw
                     (ex-info ":seon.agent/id must use the human-readable generator."
                              {::error :seon.db.id.error/agent-generator
                               ::identity-attr identity-attr
                               ::generator generator
                               :seon.error/kind :core-bug})))))
              (select-keys policies required)))

          (defn- generator-policy-request
            [database allocations]
            {::db/query generator-policy-query
             ::db/db database
             ::db/args [(->> allocations
                             (map ::identity-attr)
                             distinct
                             (sort-by str)
                             vec)]})

          (defn- ^:async acquire-generator-policies!
            [database allocations]
            (let [rows (await
                        (db/query
                         (generator-policy-request database allocations)))]
              (when (and (map? rows) (:seon.error/message rows))
                (throw
                 (ex-info "Generated identity policy acquisition failed."
                          {:seon.db/error rows
                           :seon.error/kind :core-bug})))
              (validate-generator-policies! allocations (into {} rows))))))

     #?(:clj
        (defn assert-allocation-writer!
          "Assert that a connection routes allocations through one serialized writer.

   Remote connections delegate to the configured JVM authority.
   Local self-writer connections must be the first connection opened with
   `allocation-connect-config`; a later connect cannot upgrade Datahike's
   cached writer for the same store and branch. The check reads Datahike's
   wrapped runtime db, not the public deref: a non-streaming remote writer's
   public deref reloads the persisted db value, whose config correctly names
   the remote JVM authority's writer rather than this connection's writer."
          {:malli/schema [:=> [:cat ::connection] :nil]}
          [conn]
          (let [derefable? (instance? clojure.lang.IDeref conn)]
    ;; Non-derefable test doubles exercise the wire retry state machine. Every
    ;; real Datahike connection is derefable and must name an atomic writer.
         (when derefable?
           (let [wrapped-atom (try
                                (:wrapped-atom conn)
                                (catch Throwable _ nil))
                 runtime-db   (if (and wrapped-atom
                                       (instance? clojure.lang.IDeref
                                                  wrapped-atom))
                                @wrapped-atom
                                @conn)
                 writer (get-in runtime-db [:config :writer])
                 backend (or (:backend writer) :self)]
             (when-not (or (= :seon.db.writer/remote backend)
                           (= allocation-writer-backend backend))
               (throw
                (ex-info "Allocation requires the generated-id writer wrapper."
                         {::error :seon.db.id.error/unconfigured-allocation-writer
                          :seon.error/kind :core-bug}))))))
          nil))

     (defn- validate-built-transaction!
       [built]
       (when-not (and (map? built) (vector? (:seon.db/tx-data built)))
         (throw
          (ex-info "Allocation builder must return a transaction request map."
                   {::error :seon.db.id.error/invalid-builder-output
                    :seon.error/kind :core-bug})))
       (when (or (contains? built ::db/db)
                 (contains? built ::generated-candidates)
                 (contains? built ::dependent-lookup-refs))
         (throw
          (ex-info "The allocation builder may not set allocator-owned fields."
                   {::error :seon.db.id.error/reserved-request-field
                    :seon.error/kind :core-bug})))
       (let [opts (:seon.db/opts built)
             reserved-native-fields
             (when (map? opts)
               (filterv #(contains? opts %)
                        [:tx-data ::generated-candidates
                         ::dependent-identities
                         ::dependent-lookup-refs]))]
         (when (and (contains? built ::dependent-identities)
                    (not (m/validate ::dependent-identities
                                     (::dependent-identities built))))
           (throw
            (ex-info "Allocation builder returned invalid dependent identities."
                     {::error :seon.db.id.error/invalid-dependent-identities
                      :seon.error/kind :core-bug})))
         (when (and (some? opts) (not (map? opts)))
           (throw
            (ex-info "Allocation transaction options must be a map."
                     {::error :seon.db.id.error/invalid-builder-options
                      :seon.error/kind :core-bug})))
         (when (seq reserved-native-fields)
           (throw
            (ex-info "Allocation options may not override writer-owned fields."
                     {::error :seon.db.id.error/reserved-request-field
                      ::reserved-fields reserved-native-fields
                      :seon.error/kind :core-bug}))))
       nil)

     (defn- normalize-built-allocation!
       [manifest built]
       (validate-built-transaction! built)
       [(dissoc built ::dependent-identities)
        (attach-dependent-identities! manifest (::dependent-identities built))])

     #?(:cljs
        (defn- allocation-transaction-request
          [database built manifest]
          (-> built
              (assoc ::db/db database)
              (assoc ::generated-candidates manifest))))

     #?(:cljs
        (defn ^:async ^:private allocate-attempt!
          [{::keys [allocations transaction-builder generator-policies]
            database ::db/db
            :as request}
           attempt]
          (let [candidate-manifest (candidate-round! generator-policies
                                                     allocations)
                ids            (candidate-map candidate-manifest)
                raw-built      (transaction-builder ids)]
            (when (instance? js/Promise raw-built)
              (throw
               (ex-info "The allocation transaction builder must be pure and synchronous."
                        {::error :seon.db.id.error/async-builder
                         :seon.error/kind :core-bug})))
            (let [[built manifest]
                  (normalize-built-allocation! candidate-manifest raw-built)
                  _ (db.internal/assert-invocation-shape! built)
                  transaction-request
                  (allocation-transaction-request database built manifest)
                  envelope (await (db/transact! transaction-request))]
              (cond
                (:seon.db/ok? envelope)
                (let [eids (::eids envelope)]
                  (if (= (set (keys ids)) (set (keys eids)))
                    (assoc envelope ::ids ids)
                    (failure
                     "The sole writer committed an allocation without returning every eid."
                     :core-bug
                     {::error :seon.db.id.error/incomplete-writer-response
                      ::ids ids
                      ::eids eids})))

                (exact-generated-conflict? envelope manifest)
                (if (< attempt max-attempts)
                  (await (allocate-attempt! request (inc attempt)))
                  (failure
                   "Generated identity allocation exhausted its bounded collision retries."
                   :core-bug
                   {::error :seon.db.id.error/exhausted
                    ::attempts attempt
                    ::allocations allocations}))

                :else envelope)))))

     #?(:clj
        (do
          (defn- transact-jvm-allocation!
            [conn built manifest]
            (try
              (let [report (d/transact
                            conn
                            (merge (:seon.db/opts built)
                                   {:tx-data (:seon.db/tx-data built)
                                    ::generated-candidates manifest}))
                    datoms (:tx-data report)]
                {:seon.db/ok? true
                 :seon.db/tempids (or (:tempids report) {})
                 :seon.db/tx (:max-tx (:db-after report))
                 :seon.db/tx-count (count datoms)
                 :seon.db/added (count (filter :added datoms))
                 :seon.db/retracted (count (remove :added datoms))
                 ::eids (::generated-eids report)})
              (catch Throwable throwable
                (let [classified (classify-allocation-error
                                  {::generated-candidates manifest
                                   ::throwable throwable})
                      candidate (when (= ::candidate-conflict
                                         (::error-status classified))
                                  (::generated-candidate classified))]
                  (if candidate
                    (candidate-conflict-envelope
                     candidate)
                    (throw throwable))))))

          (defn- allocate-jvm-attempt!
            [{::keys [allocations transaction-builder]
              conn :seon.db/conn
              :as request}
             attempt]
            (let [policies (generator-policies {::db-value (d/db conn)})
                  candidate-manifest (candidate-round! policies allocations)
                  ids (candidate-map candidate-manifest)
                  raw-built (transaction-builder ids)
                  [built manifest]
                  (normalize-built-allocation! candidate-manifest raw-built)]
              (let [envelope (transact-jvm-allocation! conn built manifest)]
                (cond
                  (:seon.db/ok? envelope)
                  (let [eids (::eids envelope)]
                    (if (= (set (keys ids)) (set (keys eids)))
                      (assoc envelope ::ids ids)
                      (failure
                       "The committed allocation did not resolve every eid."
                       :core-bug
                       {::error :seon.db.id.error/incomplete-writer-response
                        ::ids ids
                        ::eids eids})))

                  (exact-generated-conflict? envelope manifest)
                  (if (< attempt max-attempts)
                    (allocate-jvm-attempt! request (inc attempt))
                    (failure
                     "Generated identity allocation exhausted its collision retries."
                     :core-bug
                     {::error :seon.db.id.error/exhausted
                      ::attempts attempt
                      ::allocations allocations}))

                  :else
                  envelope))))))

     #?(:cljs
        (defn ^:async allocate!
          "Allocate all requested persistent identities inside one domain commit.

      Declarations name allocation keys and identity attributes; persisted
      generator-policy facts choose the private package adapter. The pure
      builder is re-run from scratch after an exact generated-candidate
      conflict. Values return only after the wire writer or configured local
      Datahike writer commits and returns every allocated entity id."
          {:malli/schema [:=> [:cat ::allocate-request] ::allocate-response]}
          [request]
          (try
            (validate-request! request)
            (let [database (or (::db/db request) (await (db/db)))
                  policies (or (::generator-policies request)
                               (await
                                (acquire-generator-policies!
                                 database
                                 (::allocations request))))]
              (validate-generator-policies! (::allocations request) policies)
              (await
               (allocate-attempt!
                (assoc request
                       ::db/db database
                       ::generator-policies policies)
                1)))
            (catch :default e
              (failure
               (or (.-message e) (str e))
               (or (:seon.error/kind (ex-data e)) :core-bug)
               (or (ex-data e) {::error :seon.db.id.error/allocation-failed})))))
        :clj
        (defn allocate!
          "Allocate identities through a configured serialized Datahike writer.

      Configure the first local connection with `allocation-connect-config`.
      The writer validates every managed identity assertion before commit;
      this entry point additionally owns generation, collision retry, and the
      returned allocation-key-to-entity mapping."
          {:malli/schema [:=> [:cat ::allocate-request] ::allocate-response]}
          [request]
          (try
            (validate-request! request)
            (assert-allocation-writer! (:seon.db/conn request))
            (allocate-jvm-attempt! request 1)
            (catch Throwable throwable
              (failure
               (or (.getMessage throwable) (str throwable))
               (or (:seon.error/kind (ex-data throwable)) :core-bug)
               (or (ex-data throwable)
                   {::error :seon.db.id.error/allocation-failed}))))))))
