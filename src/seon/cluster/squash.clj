(ns seon.cluster.squash
  "Pure three-way squash merge of declared cluster facts."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [seon.cluster.source :as source]
            [seon.db :as db]
            [seon.id :as id]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.task :as task]))

(defn- collision-remap
  "Target eids for branch-born entities sharing a natural identity with target."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value
                       :seon.db/database-value [:set :seon.db/entity-id]]
                  [:map-of :seon.db/entity-id :seon.db/entity-id]]}
  [source base target entities]
  (let [identities (set (db/identity-attributes source))]
    (into {}
          (keep (fn [entity]
                  (when-not (seq (d/datoms base :eavt entity))
                    (some (fn [datom]
                            (when (identities (:a datom))
                              (let [other (:e (first (d/datoms target :avet (:a datom) (:v datom))))]
                                (when (and other (not= entity other)) [entity other]))))
                          (d/datoms source :eavt entity)))))
          entities)))

(defn- selected-coordinates
  "Changed coordinates whose entity carries one of the selected identities."
  {:malli/schema [:=> [:cat :seon.db/database-value [:set :seon.program/identity]
                       [:maybe [:set :seon.program/identity]]]
                  [:set :seon.program/identity]]}
  [source coordinates selection]
  (if (nil? selection)
    coordinates
    (let [past (d/history source)
          entities (into #{} (mapcat (fn [[attribute value]]
                                      (map :e (d/datoms past :avet attribute value)))) selection)]
      (into #{} (filter #(entities (second %))) coordinates))))

(defn add-count
  "Transaction data adding the source delta to the writer's current count."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/entity-id
                       :qualified-keyword :int] :seon.store/transaction-data]}
  [database entity attribute delta]
  [[:db/add entity attribute (+ (get (d/entity database entity) attribute 0) delta)]])

(defn- attribute-tx
  "Datoms replacing one attribute's value set while preserving entity ids."
  {:malli/schema [:=> [:cat :seon.db/entity-id :qualified-keyword
                       [:set :seon.schema/value] [:set :seon.schema/value]]
                  :seon.store/transaction-data]}
  [entity attribute ours theirs]
  (into (mapv #(vector :db/retract entity attribute %) (set/difference ours theirs))
        (map #(vector :db/add entity attribute %)) (set/difference theirs ours)))

(defn- shown-state
  "A conflict's scalar or cardinality-many value, with named absence."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword
                       [:maybe [:set :seon.schema/value]]] :seon.schema/value]}
  [database attribute values]
  (cond
    (nil? values) :seon.program/absent
    (= :db.cardinality/many (get-in database [:schema attribute :db/cardinality])) values
    :else (first values)))

(defn squash-merge
  "Transaction data and conflicts for source relative to its merge base and target."
  {:seon/tasks #{'seon.cluster.squash/an-attempts-cluster-facts-copy-onto-main-by-identity}
   :malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value
                       :seon.db/database-value :seon.schema/projection
                       [:maybe [:set :seon.program/identity]]]
                  [:map [:tx-data :seon.store/transaction-data]
                   [:conflicts [:vector [:map [:entity :seon.db/entity-id]
                                         [:attribute :qualified-keyword]
                                         [:base :seon.schema/value]
                                         [:ours :seon.schema/value]
                                         [:theirs :seon.schema/value]]]]]]}
  [source base target projection selection]
  (let [rules (program/cluster-attributes projection)
        attributes (set (keys rules))
        coordinates (selected-coordinates source (program/changed-identities base source {} attributes) selection)
        remap (collision-remap source base target (into #{} (map second) coordinates))
        mapped (into #{} (map (fn [[attribute entity]] [attribute (get remap entity entity)])) coordinates)
        before (program/projected-values base coordinates {})
        theirs (program/projected-values source coordinates remap)
        ours (program/projected-values target mapped {})
        comparison (program/three-way
                    (program/digest-map base {:seon.program/identities coordinates} attributes)
                    (program/digest-map source {:seon.program/identities coordinates :seon.program/remap remap} attributes)
                    (program/digest-map target {:seon.program/identities mapped} attributes))
        collisions (into #{} (keep (fn [[attribute entity]]
                                     (when (and (= :add (rules attribute)) (contains? remap entity))
                                       [attribute (get remap entity)]))) coordinates)
        changes (set/union collisions (:seon.program/added comparison) (:seon.program/retracted comparison)
                           (:seon.program/changed-on-branch comparison) (:seon.program/conflict comparison))]
    (reduce
     (fn [result [attribute entity :as coordinate]]
       (let [b (get before coordinate)
             t (get theirs coordinate)
             o (get ours coordinate)
             conflict? ((:seon.program/conflict comparison) coordinate)
             rule (if (or conflict? (collisions coordinate)) (rules attribute) :theirs)]
         (case rule
           :ours result
           :theirs (update result :tx-data into (attribute-tx entity attribute (or o #{}) (or t #{})))
           :add (update result :tx-data conj
                        [:db.fn/call add-count entity attribute (- (or (first t) 0) (or (first b) 0))])
           :root (update result :conflicts conj
                         {:entity entity :attribute attribute
                          :base (shown-state source attribute b)
                          :ours (shown-state target attribute o)
                          :theirs (shown-state source attribute t)}))))
     {:tx-data [] :conflicts []} changes)))

(defn- conflict-task!
  "Write root's problem task for one `:root` `conflict` of `branch`, named by its
  identity `seon.id/id [:squash-conflict entity attribute]`, so a repeat writes nothing."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.store/branch :seon.squash/conflict] :nil]}
  [connection branch {:keys [entity attribute]}]
  (let [created (task/create! {:seon.db/connection connection :seon.agent/id "root" :seon.task/ns 'seon.task
                               :seon.task/name (str "squash-conflict-" (id/id [:squash-conflict entity attribute]))
                               :seon.task/kind :work
                               :seon.task/intent (str "Branch " (name branch) " and main both changed " attribute
                                                      " of entity " entity " since their merge base; main kept its value."
                                                      " Read the three values with seon.cluster.squash/squash-merge and decide.")})]
    (when (:seon.error/at created)
      (throw (ex-info (:seon.error/message created "The conflict's problem task was refused.") created)))
    nil))

(defn copy!
  "Squash `branch` onto main through `connection` as ONE transaction carrying `meta`
  and the provenance (source branch and commit) as transaction metadata.
  Each `:root` conflict is first written as root's problem task. Never a Datahike
  merge: a parented commit would pin the branch's lineage to main for collection.
  Answers what it copied."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.store/branch [:maybe [:set :seon.program/identity]] :map]
                  :seon.squash/copy]}
  [connection branch selection meta]
  (let [target (db/db connection)
        main-branch (get-in target [:config :branch])
        source (db/branch-database target branch)
        _ (when-not source
            (throw (ex-info "The branch to squash has no head." {:seon.store/branch branch})))
        base (source/merge-base source target main-branch branch)]
    (when (:seon.error/at base) (throw (ex-info (:seon.error/message base "The branch has no merge base.") base)))
    (try
      (let [commit (db/commit-id source)
            {:keys [tx-data conflicts]} (squash-merge source base target
                                                      (or (db/carried-projection target) (schema/handed-projection))
                                                      selection)
            _ (run! #(conflict-task! connection branch %) conflicts)
            report (db/transact! connection {:tx-data tx-data
                                             :tx-meta (assoc meta :seon.squash/source-branch branch
                                                             :seon.squash/source-commit commit)})]
        (when-not (:db-after report)
          (throw (ex-info (:seon.error/message report "The end copy was refused.") report)))
        {:seon.squash/source-branch branch :seon.squash/source-commit commit
         :seon.squash/datoms (count tx-data) :seon.squash/conflicts conflicts})
      (finally (source/release! [base source])))))

(defn squash-merge!
  "Copy `branch`'s cluster facts onto main by hand, as an attempt's successful end
  does (`copy!`), restricted to the identities `selection` names (nil: all).
  Root's way to land an error end's branch; a second copy writes nothing new.

  Example:
  (seon.cluster.squash/squash-merge! {} :attempt-0123456789ab nil)"
  {:malli/schema [:=> [:cat :seon.squash/context :seon.store/branch [:maybe [:set :seon.program/identity]]]
                  :seon.squash/copy]}
  [{connection :seon.db/connection} branch selection]
  (db/call-with-custody {:seon.db/connection connection} #(copy! connection branch selection {})))
