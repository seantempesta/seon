(ns seon.cluster.squash
  "Three-way squash merge of declared cluster facts onto main, computed inside main's writer."
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

(defn- member-merge
  "The value set of a cardinality-many coordinate merged member by member, as git merges
  lines: (ours - (base - theirs)) union (theirs - base). A member either side removed is
  removed and a member either side added is added, so two sets never conflict."
  {:malli/schema [:=> [:cat [:maybe [:set :seon.schema/value]] [:maybe [:set :seon.schema/value]]
                       [:maybe [:set :seon.schema/value]]]
                  [:set :seon.schema/value]]}
  [base ours theirs]
  (let [base (or base #{}) theirs (or theirs #{})]
    (set/union (set/difference (or ours #{}) (set/difference base theirs))
               (set/difference theirs base))))

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
                       :seon.db/database-value :seon.schema/projection]
                  [:map [:tx-data :seon.store/transaction-data]
                   [:conflicts [:vector [:map [:entity :seon.db/entity-id]
                                         [:attribute :qualified-keyword]
                                         [:base :seon.schema/value]
                                         [:ours :seon.schema/value]
                                         [:theirs :seon.schema/value]]]]]]}
  [source base target projection]
  (let [rules (program/cluster-attributes projection)
        attributes (set (keys rules))
        coordinates (program/changed-identities base source {} attributes)
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
        ;; a count the branch moved adds its delta to main's, even when main moved it to the same value
        counted (into #{} (filter (fn [[attribute :as coordinate]]
                                    (and (= :add (rules attribute)) (not (contains? remap (second coordinate)))
                                         (not= (get before coordinate) (get theirs coordinate)))))
                      coordinates)
        changes (set/union collisions counted (:seon.program/added comparison) (:seon.program/retracted comparison)
                           (:seon.program/changed-on-branch comparison) (:seon.program/conflict comparison))]
    (reduce
     (fn [result [attribute entity :as coordinate]]
       (let [b (get before coordinate)
             t (get theirs coordinate)
             o (get ours coordinate)
             conflict? ((:seon.program/conflict comparison) coordinate)
             rule (cond
                    (counted coordinate) :add
                    (and conflict? (= :db.cardinality/many (get-in source [:schema attribute :db/cardinality]))) :members
                    (or conflict? (collisions coordinate)) (rules attribute)
                    :else :theirs)]
         (case rule
           :ours result
           :members (update result :tx-data into (attribute-tx entity attribute (or o #{}) (member-merge b o t)))
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

(defn- merged
  "Assignment `a`'s three-way on main `database` from its merge base (`:seon.task/merge-base`) to the source
  commit `commit`, under `projection`'s rules: nothing when the base already is `commit`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/entity-id :seon.source/commit-id :seon.schema/projection]
                  [:map [:tx-data :seon.store/transaction-data] [:conflicts :seon.squash/conflicts]]]}
  [database a commit projection]
  ;; read from the datoms: inside the writer the value carries no projection of its own
  (let [base-commit (:v (first (d/datoms database :eavt a :seon.task/merge-base)))]
    (when-not (uuid? base-commit)
      (throw (ex-info "The assignment has no merge base." {:seon.error/message "The assignment has no merge base."
                                                          :db/id a :seon.squash/source-commit commit})))
    (if (= base-commit commit)
      {:tx-data [] :conflicts []}
      (let [base (db/commit-database database base-commit)
            source (db/commit-database database commit)]
        (try (squash-merge source base database projection)
             (finally (source/release! [base source])))))))

(defn merge-tx
  "Assignment `a`'s squash merge of the source commit `commit` onto main, computed inside main's writer on its own
  `database` under main's `projection`: the three-way from its merge base (`:seon.task/merge-base`) to `commit`
  against `database`, with the base moved to `commit` in the same transaction, so a repeat adds nothing; []
  when the base already is `commit`. A `:root` conflict keeps main's value (`merge!` answers it)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/entity-id :seon.source/commit-id :seon.schema/projection]
                  :seon.store/transaction-data]}
  [database a commit projection]
  (let [base-commit (:v (first (d/datoms database :eavt a :seon.task/merge-base)))]
    (if (= base-commit commit)
      []
      (conj (:tx-data (merged database a commit projection)) [:db/add a :seon.task/merge-base commit]))))

(defn merge!
  "Squash-merge `branch`'s head onto main through `connection` for assignment `a`: ONE transaction of
  `merge-tx`, which runs the three-way inside the writer and advances the merge base, carrying `meta` and the
  provenance (source branch and commit) as transaction metadata. Each `:root` conflict, kept as main's value,
  is written as root's problem task. Never a Datahike merge: a parented commit would pin the branch's lineage
  to main for collection. Answers what it merged and the conflicts the writer met."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.db/entity-id :seon.store/branch :map]
                  :seon.squash/copy]}
  [connection a branch meta]
  (let [target (db/db connection)
        head (db/branch-database target branch)
        _ (when-not head
            (throw (ex-info "The branch to merge has no head." {:seon.error/message "The branch to merge has no head."
                                                               :seon.store/branch branch})))
        commit (try (db/commit-id head) (finally (source/release! [head])))
        projection (or (db/carried-projection target) (schema/handed-projection))
        report (db/transact! connection {:tx-data [[:db.fn/call merge-tx a commit projection]]
                                         :tx-meta (assoc meta :seon.squash/source-branch branch
                                                         :seon.squash/source-commit commit)})
        _ (when-not (:db-after report)
            (throw (ex-info (:seon.error/message report "The merge was refused.") report)))
        ;; the conflicts merge-tx met, derived again from the value it read
        conflicts (:conflicts (merged (:db-before report) a commit projection))]
    (run! #(conflict-task! connection branch %) conflicts)
    {:seon.squash/source-branch branch :seon.squash/source-commit commit
     ;; the merged facts: neither the transaction's own metadata nor the moved merge base
     :seon.squash/datoms (count (remove #(or (= (:tx %) (:e %)) (= :seon.task/merge-base (:a %))) (:tx-data report)))
     :seon.squash/conflicts conflicts}))
