(ns seon.merge
  "Root's merge of an assignment's branch onto main: one three-way squash merge from its merge base, the
  cluster facts inside main's writer and the requested declarations through the gate, in one transaction."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.error.refusal :as refusal]
            [seon.fn :as fn]
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

(defn- cluster-entity?
  "Whether `entity` holds, in `source` or `base`, the identity of a cluster entity schema: the
  partition is declared per entity schema, and an identity-less entity sharing one of its attributes
  (a component of a non-cluster value) is not one."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value [:set :qualified-keyword]
                       :seon.db/entity-id] :boolean]}
  [source base identities entity]
  (boolean (some (fn [attribute] (or (seq (d/datoms source :eavt entity attribute))
                                     (seq (d/datoms base :eavt entity attribute))))
                 identities)))

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
  {:malli/schema [:=> [:cat [:set :seon.schema/value] [:set :seon.schema/value] [:set :seon.schema/value]]
                  [:set :seon.schema/value]]}
  [base ours theirs]
  (set/union (set/difference ours (set/difference base theirs))
             (set/difference theirs base)))

(defn- shown-state
  "A conflict's scalar or cardinality-many value, with named absence."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value :qualified-keyword] :seon.schema/value]
                  [:=> [:cat :seon.db/database-value :qualified-keyword [:set :seon.schema/value]] :seon.schema/value]]}
  ([_database _attribute] :seon.program/absent)
  ([database attribute values]
   (if (= :db.cardinality/many (get-in database [:schema attribute :db/cardinality]))
     values
     (first values))))

(defn squash-merge
  "Transaction data and conflicts for source relative to its merge base and target."
  {:seon/tasks #{'seon.merge/an-attempts-cluster-facts-copy-onto-main-by-identity}
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
        identities (set/intersection attributes (set (db/identity-attributes source)))
        coordinates (into #{} (filter #(cluster-entity? source base identities (second %)))
                          (program/changed-identities base source {} attributes))
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
           :members (update result :tx-data into (attribute-tx entity attribute (or o #{}) (member-merge (or b #{}) (or o #{}) (or t #{}))))
           :theirs (update result :tx-data into (attribute-tx entity attribute (or o #{}) (or t #{})))
           :add (update result :tx-data conj
                        [:db.fn/call add-count entity attribute (- (or (first t) 0) (or (first b) 0))])
           :root (update result :conflicts conj
                         {:entity entity :attribute attribute
                          :base (apply shown-state source attribute (some-> b vector))
                          :ours (apply shown-state target attribute (some-> o vector))
                          :theirs (apply shown-state source attribute (some-> t vector))}))))
     {:tx-data [] :conflicts []} changes)))

(defn- conflict-task!
  "Write root's problem task for one `:root` `conflict` of `branch`, named by its
  identity `seon.id/id [:squash-conflict entity attribute]`, so a repeat writes nothing."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.store/branch :seon.squash/conflict] :nil]}
  [connection branch {:keys [entity attribute]}]
  (let [created (task/create! {:seon.db/connection connection :seon.agent/id "root" :seon.task/ns 'seon.task
                               :seon.task/name (str "squash-conflict-" (id/id [:squash-conflict entity attribute]))
                               :seon.task/plan (str "Branch " (name branch) " and main both changed " attribute
                                                      " of entity " entity " since their merge base; main kept its value."
                                                      " Read the three values with seon.merge/squash-merge and decide.")})]
    (when (:seon.error/at created)
      (throw (ex-info (:seon.error/message created "The conflict's problem task was refused.") created)))
    nil))

(defn- merged
  "Assignment `a`'s three-way on main `database` from its branch's fork point `base-commit`
  (`seon.cluster.registry/fork-point`) to the source commit `commit`, under `projection`'s rules, with the end the
  source holds for `a`: nothing when the fork point already is `commit`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/entity-id :seon.source/commit-id :seon.schema/projection
                       :seon.merge/fork-point]
                  [:map [:tx-data :seon.store/transaction-data] [:conflicts :seon.merge/conflicts]
                   [:end {:optional true} :seon.task.attempt/end] [:task {:optional true} :seon.db/entity-id]]]}
  [database a commit projection base-commit]
  ;; read from the datoms: inside the writer the value carries no projection of its own
  (if (= base-commit commit)
    {:tx-data [] :conflicts []}
    (let [base (db/commit-database database base-commit)
          source (db/commit-database database commit)]
      (try (let [end (:v (first (d/datoms source :eavt a :seon.task.attempt/end)))
                 task (:v (first (d/datoms source :eavt a :seon.task/attempt-of)))]
             (cond-> (squash-merge source base database projection)
               end (assoc :end end)
               task (assoc :task task)))
           (finally (source/release! [base source]))))))

(defn merge-tx
  "Assignment `a`'s squash merge of the source commit `commit` onto main, computed inside main's writer on its own
  `database` under main's `projection`: the three-way of its cluster facts from `fork`, the commit its branch forked
  at (`seon.cluster.registry/fork-point`; a forward merge never moves these facts, so main's changes to them since
  are kept), to `commit` against `database`; [] when `fork` is `commit`. An assignment with no outcome is
  closed in the same transaction by its source's end: `:closed` when it ended `:sent-back` (its work goes back to
  its target), `:merged` when it ended `:done`, which `merge!` carries only inside an accepted candidate's
  transaction (`seon.cluster.source/accept-merge!`); any other source, one not yet ended among them, merges its
  facts and stays open. Closed `:merged`, the merge ends its work: the task it assigns and every task its facts carry
  in `:running` read `:stopped`, so no later pass starts a merged task again (TASK-CHAIN 2.6). A `:root` conflict
  keeps main's value (`merge!` answers it)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/entity-id :seon.source/commit-id :seon.schema/projection
                       :seon.merge/fork-point]
                  :seon.store/transaction-data]}
  [database a commit projection fork]
  (if (= fork commit)
    []
    (let [{:keys [tx-data end task]} (merged database a commit projection fork)
          outcome (when (empty? (d/datoms database :eavt a :seon.task/outcome)) ({:sent-back :closed :done :merged} end))
          ended (fn [[op e attribute v :as datom]]
                  (if (and (= :db/add op) (= :seon.task/execution attribute) (= :running v)) [op e attribute :stopped] datom))]
      (cond-> tx-data
        (= :merged outcome) (->> (mapv ended))
        (and (= :merged outcome) task) (conj [:db/add task :seon.task/execution :stopped])
        outcome (conj [:db/add a :seon.task/outcome outcome])))))

(defn- assigned-task
  "The symbol of the task assignment `a` assigns, read on the source head `database`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/entity-id] :seon.task/sym]}
  [database a]
  (db/q '[:find ?sym . :in $ ?a :where [?a :seon.task/assigned ?h] [?t :seon.task/id ?h] [?t :seon.task/sym ?sym]]
        database a))

(defn- chain-row
  "Assignment `key`'s row on the head of the chain branch that holds it, among the chain facts main `database`
  holds (`:seon.chain/branch`), else {}: a chain's attempts live on its branch, never on main, until its one merge;
  a row naming another branch (a merge step's, MERGE-STEP) is read there, where its agent works."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/assignment]
                  [:map [:db/id {:optional true} :seon.db/entity-id] [:seon.task/branch {:optional true} :seon.task/branch]]]}
  [database key]
  (or (some (fn [branch]
          (when-let [head (db/branch-database database branch)]
            (try (let [row (db/pull head [:db/id :seon.task/branch] [:seon.task/assignment key])
                       works-on (:seon.task/branch row)]
                   ;; a row naming another branch is held there as its agent wrote it (`seon.task/branch-attempts`)
                   (if (and works-on (not= works-on branch))
                     (when-let [there (db/branch-database database works-on)]
                       (try (let [row (db/pull there [:db/id :seon.task/branch] [:seon.task/assignment key])]
                              (when (:seon.task/branch row) row))
                            (finally (source/release! [there]))))
                     (when works-on row)))
                 (finally (source/release! [head])))))
        (sort (db/q '[:find [?b ...] :where [_ :seon.chain/branch ?b]] database)))
      {}))

(defn merged?
  "Whether main `database` records a squash merge of the source commit `commit`: the transaction metadata
  `seon.merge/merge!` writes in the merge's own transaction (`:seon.squash/source-commit`), main's history of what it
  merged, as git's commit message records a squash."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.source/commit-id] :boolean]}
  [database commit]
  (boolean (seq (d/datoms database :avet :seon.squash/source-commit commit))))

(defn- assignment-row
  "Assignment `key`'s row, main's, else, for a chain's attempt, its chain branch head's (`chain-row`), with the base
  of the cluster facts the merge carries: the newest commit of the head's first-parent history main already merged
  (`merged?`), else the commit its branch forked from main at (`seon.cluster.registry/fork-point`); a forward merge
  never moves these facts, so main's changes to them since are kept."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/assignment]
                  [:or [:map [:db/id :seon.db/entity-id] [:seon.task/branch :seon.task/branch]
                        [:seon.merge/fork-point :seon.merge/fork-point]] :seon.error/value]]}
  [database key]
  (let [on-main (db/pull database [:db/id :seon.task/branch] [:seon.task/assignment key])
        row (if (:seon.task/branch on-main) on-main (chain-row database key))
        head (some->> (:seon.task/branch row) (registry/head-commit-id database))
        main (get-in database [:config :branch])
        ;; the branch's facts since main last took them: the newest commit of its first-parent history main merged
        ;; (`merged?`), else the commit it forked from main at (`seon.cluster.registry/fork-point`)
        fork (when (uuid? head)
               (registry/first-parent-where database head (fn [c branch] (or (= branch main) (merged? database c)))))
        refuse #(refusal/diagnostic (java.util.Date.) :seon.program/write 'seon.merge/merge!
                                    {:seon.error/message % :seon.error/member :seon.task/assignment :seon.error/offending key})]
    (cond
      (not (:seon.task/branch row)) (refuse (str "Assignment " key " is neither on main nor on a chain's branch."))
      (not fork) (refuse (str "Assignment " key "'s branch history no longer reaches the commit it forked from main at."))
      :else (assoc row :seon.merge/fork-point fork))))

(defn- acceptance
  "What the git commit of assignment `key`'s merge names: the assignment, its task and agent, the run and the merge commit."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/assignment :seon.source/accepted] :seon.source/acceptance]}
  [database key accepted]
  (let [[task-sym agent-id] (db/q '[:find [?sym ?agent] :in $ ?k
                                   :where [?a :seon.task/assignment ?k] [?a :seon.task/assigned ?h] [?t :seon.task/id ?h]
                                   [?t :seon.task/sym ?sym] [?a :seon.task/agent ?g] [?g :seon.agent/id ?agent]]
                                 database key)]
    (cond-> (assoc (select-keys accepted [:seon.test.run/id :seon.source/commit-id]) :seon.task/assignment key)
      (symbol? task-sym) (assoc :seon.task/sym task-sym)
      (string? agent-id) (assoc :seon.agent/id agent-id))))

(defn- merge-onto
  "`merge!`'s work: assignment `key`'s merge through `execution`, the checkout `:root` (when given) proving the files."
  {:malli/schema [:=> [:cat :seon.merge/execution :seon.task/assignment [:map [:root {:optional true} :seon.fn/root]]]
                  [:or :seon.merge/merged :seon.source/conflict-error :seon.error/value]]}
  [execution key {:keys [root]}]
  ;; A cluster handle holds its store in its SCI context's environment, where
  ;; `seon.cluster.agent/acquire-context!` reads it; an acquired context holds it directly.
  (let [ctx (or (:seon.sci.eval/base-ctx execution) (:seon.sci.eval/ctx execution))
        environment (or (some-> ctx env/of) (:seon.env/environment execution))
        store (or (:seon.store/store execution) (:seon.store/store environment))
        context (cond-> (assoc execution :seon.store/store store) environment (assoc :seon.env/environment environment))
        connection (:seon.db/connection context)
        row (assignment-row (db/db connection) key)]
    (if (:seon.error/at row)
      row
      (let [{a :db/id branch :seon.task/branch base :seon.merge/fork-point} row
            ;; one read of the store's branches: the head and the comparison below read the same basis
            branches (registry/branches store)
            commit (registry/branch-commit-id {:seon.store/store store :seon.store/branch branch
                                               :seon.cluster.registry/branches branches})
            target (db/db connection)
            ;; main's history records the merge of this commit, or the branch wrote nothing since its fork point
            merged? (and (uuid? commit) (or (= base commit) (merged? target commit)))
            ;; the source head holds the attempt's end and its task (a chain's live only there):
            ;; prepare-merge! selects the proofs of that task over its assigned basis (main, the
            ;; branch head, the merge base), a Prove step's product beside them;
            ;; a source that ended :done is accepted through a candidate, a task-only or empty change included,
            ;; and only so completes its task; a sent-back one merges its facts only; any other source merges
            ;; its facts alone, or its declarations through an accepted candidate when it changed any
            on-head (fn [f] (let [head (db/commit-database target commit)]
                              (try (f head) (finally (source/release! [head])))))
            end (when (and (uuid? commit) (not merged?))
                  (on-head #(:v (first (d/datoms % :eavt a :seon.task.attempt/end)))))
            accepted? (case end
                        :done true
                        :sent-back false
                        (and (uuid? commit) (not merged?) (pos? (source/unmerged-count target store commit))))
            projection (or (db/carried-projection target) (schema/handed-projection))
            meta {:seon.squash/source-branch branch :seon.squash/source-commit commit}
            merge-call [[:db.fn/call merge-tx a commit projection base]]
            ;; one producer counts what publication reads: the declarations and tasks the merge commit moved
            answer (fn [before after]
                     (let [accepted (source/merge-accepted connection (db/commit-id after))
                           moved (count (:seon.program/identities accepted))
                           recorded (when-not (:seon.error/at accepted)
                                      (db/transact! connection (cond-> [[:db/add a :seon.task/merge-commit (db/commit-id after)]]
                                                                 (pos? moved) (conj [:db/add a :seon.task/merged-declarations moved]))))]
                       (cond
                         (:seon.error/at accepted) accepted
                         (not (:db-after recorded)) recorded
                         :else
                         (let [outcome (:seon.task/outcome (db/pull after [:seon.task/outcome] a))]
                           (cond-> {:seon.task/assignment key
                                    :seon.task/merge-commit (db/commit-id after)
                                    :seon.merge/conflicts (:conflicts (merged before a commit projection base))}
                             outcome (assoc :seon.task/outcome outcome))))))]
        (cond
          (not (uuid? commit)) commit
          merged? {:seon.task/assignment key :seon.merge/conflicts []}
          (not accepted?)
          (let [report (db/transact! connection {:tx-data merge-call :tx-meta meta})]
            (if (:db-after report)
              (let [result (answer (:db-before report) (:db-after report))]
                (when-not (:seon.error/at result) (run! #(conflict-task! connection branch %) (:seon.merge/conflicts result)))
                result)
              report))
          :else
          (let [prepared (source/prepare-merge! context store branch (on-head #(assigned-task % a))
                                                 (cond-> {:merging merge-call} root (assoc :root root)))
                ;; only a gate that refused nothing is judged for acceptance
                accepted (when (and (:seon.source/candidate prepared) (not (:seon.error/at prepared))
                                    (empty? (:seon.program/conflict prepared)))
                           (source/accept-merge! context store prepared
                                                 {:tx-data merge-call :tx-meta meta}))
                ;; an accepted candidate has served; a refused one stays, the merge branch its refusal names
                ;; (MERGE-STEP): whoever holds the refusal retires it, or root's merge step works on it
                _ (when (:seon.source/commit-id accepted)
                    (registry/retire-branch! {:seon.store/store store :seon.store/branch (:seon.source/candidate prepared)}))
                result (when (:seon.source/commit-id accepted)
                         (let [after (db/db connection)
                               before (db/commit-database after (:seon.source/base-commit-id accepted))
                               done (db/commit-database after (:seon.source/commit-id accepted))]
                           (try (answer before done) (finally (source/release! [before done])))))]
            (cond
              (seq (:seon.program/conflict prepared))
              (cond-> (refusal/error :seon.source/conflict-error
                                     {:seon.error/layer :seon.source/merge :seon.error/operation 'seon.merge/merge!
                                      :seon.error/message "The source branch and main changed the same declarations since the merge base; resolve on the branch and merge again."
                                      :seon.program/conflict (into #{} (map second) (:seon.program/conflict prepared))
                                      :seon.program/conflict-digests (:seon.program/conflict-digests prepared)})
                (:seon.source/candidate prepared) (merge (select-keys prepared [:seon.source/candidate :seon.source/red])))
              (:seon.error/at prepared) prepared
              (not (:seon.source/candidate prepared)) prepared
              (not (:seon.source/commit-id accepted))
              (cond-> (assoc accepted :seon.source/candidate (:seon.source/candidate prepared))
                (seq (:seon.source/red prepared)) (assoc :seon.source/red (:seon.source/red prepared)))
              (:seon.error/at result) (assoc result :seon.source/accepted accepted)
              :else (do (run! #(conflict-task! connection branch %) (:seon.merge/conflicts result))
                        (assoc result :seon.source/accepted accepted)))))))))

(defn merge!
  "Merge assignment `key`'s branch head C onto main through `execution` (root's world: main's connection, and
  the store it holds or its SCI context's environment holds) as ONE
  transaction whose metadata names C (`merged?`): the cluster facts by `merge-tx`, inside the writer, three-way from the commit its
  branch forked from main at (`seon.cluster.registry/fork-point`), so main's changes to them since are kept, and
  the assignment closed by its end; and,
  when its source ended `:done`, or did not end `:sent-back` and changed declarations main lacks
  (`seon.cluster.source/unmerged-count`), whatever declarations it changed, none included, gated first: `seon.cluster.source/prepare-merge!` tests them with the proofs of the assignment's task
  over main, C and the merge base (`seon.cluster.source/tested-task-basis`), a Prove step's product beside them,
  and the tests reaching them on a fresh merge branch of main; `seon.cluster.source/accept-merge!` judges them by
  `seon.cluster.source/merge-verdict` and carries `merge-tx` in its transaction, so only an accepted candidate
  completes the task, then the merge branch is deleted; a refused one is kept and named on the refusal
  (`:seon.source/candidate`, with its red tests as `:seon.source/red`) for its caller to retire or hold a merge step
  on (MERGE-STEP); the files the checkout `root` would hold are proven
  before any test runs. Main's commit is recorded as `:seon.task/merge-commit`, beside
  `:seon.task/merged-declarations`, how many declarations and tasks it moved
  (`seon.cluster.source/merge-accepted`) when any, on either path; publishing the
  merged declarations into the checkout is the next pass's due work (`publish!`), never a step of the merge.
  A C main already merged (`merged?`) or a branch that wrote nothing merges nothing and transacts nothing. Answers the merge, or the first refusal as data
  with nothing merged. A `:root` conflict of the facts keeps main's value and is answered beside them.

  ## Example

  ```clojure
  (seon.merge/merge! execution \"0123456789ab\" \"/Users/me/src/project\")
  ```"
  {:malli/schema [:function
                  [:=> [:cat :seon.merge/execution :seon.task/assignment]
                   [:or :seon.merge/merged :seon.source/conflict-error :seon.error/value]]
                  [:=> [:cat :seon.merge/execution :seon.task/assignment :seon.fn/root]
                   [:or :seon.merge/merged :seon.source/conflict-error :seon.error/value]]]}
  ([execution key] (merge-onto execution key {}))
  ([execution key root] (merge-onto execution key {:root root})))

(defn- absent-references
  "The identities `entities` reference by lookup ref that no map among them carries and `head` lacks: the entities
  main created since the merge base (a file it added) that these declarations name."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/rows] [:or :seon.fn.file/identities :seon.error/value]]}
  [head entities]
  (let [schema (:schema head)
        identities (db/identity-attributes head)
        lookup? #(and (vector? %) (= 2 (count %)) (qualified-keyword? (first %)))
        maps (filter map? (tree-seq coll? seq entities))
        carried (into #{} (for [m maps a identities :let [e (find m a)] :when e] [a (val e)]))
        named (into #{} (for [m maps [a v] m :when (= :db.type/ref (get-in schema [a :db/valueType]))
                              r (cond (lookup? v) [v] (and (coll? v) (not (map? v))) (filter lookup? v))
                              :when (not (carried r))]
                          r))
        named (vec named)
        held (db/pull-many head [:db/id] named)]
    (if (:seon.error/at held)
      held
      (into [] (keep (fn [[r found]] (when-not (:db/id found) r))) (map vector named held)))))

(defn- carried-entities
  "Main's rows for the declarations `delta` main changed, with every entity they name that `head` lacks, read from
  `main` until none is missing, as `git merge main` brings a file main added: one population, so the one
  transaction creates each before the declarations that name it (`seon.fn/reconcile-tx`'s tempids)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value [:set :seon.program/identity]]
                  [:or :seon.program/rows :seon.error/value]]}
  [main head delta]
  (loop [identities (vec delta)]
    (let [entities (fn/published-index-entities main identities)
          missing (when-not (:seon.error/at entities) (absent-references head entities))]
      (cond
        (:seon.error/at entities) entities
        (:seon.error/at missing) missing
        (empty? missing) entities
        :else (recur (into identities missing))))))

(defn merge-main!
  "Merge main forward into chain `branch` as `git merge main` does, through `execution` (root's world: main's
  connection and its store): the program declarations main changed since the branch's merge base and the branch
  did not (`seon.program/three-way` of the base, the branch head and main's head over what main changed) replace the branch's rows in one
  merge commit on the branch's connection whose parents add main's head (`:parents`, Datahike's `merge-db!`), held
  for this one action (`seon.cluster.registry/on-branch`), whether or not a running graph holds it open. The merge
  base is Datahike's commit graph (`seon.cluster.source/merge-base`), so after the merge it is main's head and
  nothing records it. Only program entities move: tasks, attempts, evaluations, messages and config never do. A
  branch whose merge base is already main's head is up to date and nothing is written. A declaration both sides
  changed differently is a conflict: it stays the branch's (ours), is recorded unmerged in the merge commit
  (`seon.cluster.source/unmerged-tx`, main's version theirs) so the done rule and root's merge refuse it until it is
  resolved, and is answered as `:seon.program/conflict` (by symbol) with its three digests
  (`:seon.program/conflict-digests`). Answers the moved declarations, the conflicts and
  main's commit, or the first refusal as data with nothing written.

  ## Example

  ```clojure
  (seon.merge/merge-main! execution :chain-f1f6dc8e6652)
  ```"
  {:malli/schema [:=> [:cat :seon.merge/execution :seon.chain/branch]
                  [:or [:map [:seon.store/branch :seon.chain/branch] [:seon.source/commit-id :seon.source/commit-id]
                        [:seon.program/identities :seon.program/identity-set]
                        [:seon.program/conflict :seon.program/conflict]
                        [:seon.program/conflict-digests :seon.program/conflict-digests]]
                   :seon.error/value]]}
  [{connection :seon.db/connection store :seon.store/store} branch]
  (registry/on-branch
   store branch
   (fn [chain-connection]
     (let [main (db/db connection)
           main-commit (db/commit-id main)
           head (db/db chain-connection)
           base-commit (source/merge-base main (db/commit-id head) main-commit)
           current? (= base-commit main-commit)
           base (cond (:seon.error/at base-commit) base-commit
                      current? nil
                      :else (db/commit-database head base-commit))
           ;; only what main changed since the base can move or conflict: the three-way over that set alone
           ;; reads the branch's digest of each, which is the conflict test (a branch-only change stays its own)
           changed (when (and base (not (:seon.error/at base)))
                     (program/changed-identities base main source/merge-bound))
           maps (when (set? changed)
                  (mapv #(program/digest-map % (assoc source/merge-bound :seon.program/identities changed)) [base head main]))
           three (when (and maps (not-any? :seon.error/at maps)) (apply program/three-way maps))
           delta (:seon.program/changed-on-head three)
           entities (when (seq delta) (carried-entities main head delta))
           tx (cond (not three) nil (empty? delta) [] (vector? entities) (fn/reconcile-tx head entities (vec delta)) :else nil)
           ;; the branch keeps its own version of a declaration both sides changed (git's ours, the target), and the
           ;; merge commit records it unmerged beside main's (theirs)
           tx (when (vector? tx)
                (into tx (source/unmerged-tx (:seon.program/conflict three) (:seon.program/conflict-digests three)
                                             :seon.program/branch-digest :seon.program/head-digest {} base-commit main-commit)))
           written (when (vector? tx)
                     (db/with-branch {:seon.db/connection chain-connection}
                                     #(db/transact! chain-connection {:tx-data tx :parents #{main-commit}})))]
       (try
         (cond
           current? {:seon.store/branch branch :seon.source/commit-id main-commit :seon.program/identities #{}
                     :seon.program/conflict #{} :seon.program/conflict-digests {}}
           (:seon.error/at base) base
           (not (set? changed)) changed
           (not three) (some #(when (:seon.error/at %) %) maps)
           (and (seq delta) (not (vector? entities))) entities
           (not (vector? tx)) tx
           (not (:db-after written)) written
           :else
           {:seon.store/branch branch :seon.source/commit-id main-commit
            :seon.program/identities delta
            :seon.program/conflict (into #{} (map second) (:seon.program/conflict three))
            :seon.program/conflict-digests (:seon.program/conflict-digests three)})
         (finally (source/release! [base])))))))

(defn checkout
  "The checkout cluster `cluster-name` publishes merged declarations into, as a canonical path: its
  `:seon.config.fs/working-root` on `database`; else the refusal naming the unreadable dial."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.cluster/name] [:or :seon.fn/root :seon.error/value]]}
  [database cluster-name]
  (let [effective (config/effective database cluster-name)
        root (when-not (:seon.error/at effective) (:seon.config.fs/working-root effective))]
    (cond
      (:seon.error/at effective) effective
      (string? root) (.getCanonicalPath (java.io.File. ^String root))
      :else (refusal/error :seon.source/write-back-error
                           {:seon.error/layer :seon.source/write-back :seon.error/operation 'seon.merge/checkout
                            :seon.error/message "The cluster names no :seon.config.fs/working-root to publish into; nothing was written."
                            :seon.source/write-back-refused :root-refused :seon.error/offending cluster-name}))))

(defn- indexed!
  "Main's program after publication `written` installed its files under `root`: each written source file
  main records or this publication created (a namespace main never published), analyzed again
  (`seon.fn/index!` of those paths), so main holds the file's digest and each
  of its declarations' file and span, and a later merge splices into them. Answers the index result, nil
  when no written path is such a source file."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.fn/root :seon.source/written]
                  [:or :nil :seon.reconcile/result]]}
  [connection root written]
  (let [database (db/db connection)
        known (db/q '[:find ?path ?root :in $ [?path ...]
                      :where [?f :seon.fn.file/relative-path ?path] [?f :seon.fn.file/relative-root ?root]]
                    database (:seon.source/changed-paths written))
        ;; a file this publication created: it declares its namespace anew, under the root its path starts with
        created (for [{path :seon.source/path added :seon.source/added} (:seon.source/files written)
                      :when (some (comp #{:seon.ns/name} first) added)]
                  [path (first (str/split path #"/"))])
        files (into (set known) created)]
    (when (seq files)
      (fn/index! {:seon.db/connection connection
                  :seon.schema/projection (or (db/carried-projection database) (schema/handed-projection))
                  :seon.fn/root root :seon.fn/roots (vec (sort (into #{} (map second) files)))
                  :seon.fn/changed-paths (into #{} (map first) files)
                  :seon.source/previous-database database}))))

(defn merge-commit
  "The main commit that merged assignment `a` on main `database`: its recorded `:seon.task/merge-commit`, else the
  commit of the transaction that closed it `:merged` (`seon.db/commit-at-t`), which acceptance writes in the merge's
  own transaction, so a restart between the merge and its record still finds it; nil when it is not merged."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/entity-id] [:or :nil :seon.source/commit-id]]}
  [database a]
  (or (:v (first (d/datoms database :eavt a :seon.task/merge-commit)))
      (when-let [closed (first (filter #(= :merged (:v %)) (d/datoms database :eavt a :seon.task/outcome)))]
        (db/commit-at-t database (:tx closed)))))

(defn publish!
  "Publish assignment `key`'s merged declarations, task entries included, into the cluster's checkout (`checkout`) through
  `execution` (root's world: main's connection and `:seon.cluster/name`): derived from main alone
  (`seon.cluster.source/merge-accepted` of its merge commit, `merge-commit`), written back
  (`seon.cluster.source/write-back!`, which proves and reindexes each file), main's record of those files
  analyzed again (`indexed!`), and committed as one git
  commit of exactly those paths (`seon.cluster.source/commit!`) under the merge's own claim in `bin/lane`'s
  ledger (lane `merge-<key>`), then `:seon.task/git-commit` written. A path another lane claims, or one
  with uncommitted changes the merge's claim does not cover, waits: nothing is written and the
  publication stays due (`:seon.merge/waiting`). A refusal is recorded on the assignment as
  `:seon.task/publication-refusal`, its error type, and answered. A crash anywhere leaves it due: a write-back finding
  the merged bytes on disk installs them again unchanged, and paths HEAD already holds answer the commit
  that names this merge.

  ## Example

  ```clojure
  (seon.merge/publish! execution \"0123456789ab\")
  ```"
  {:malli/schema [:=> [:cat :seon.merge/publication :seon.task/assignment]
                  [:or :seon.merge/published :seon.error/value]]}
  [{connection :seon.db/connection cluster-name :seon.cluster/name} key]
  (let [database (db/db connection)
        a (:db/id (db/pull database [:db/id] [:seon.task/assignment key]))
        commit (merge-commit database a)
        lane (str "merge-" key)
        root (checkout database cluster-name)
        accepted (if (:seon.error/at root) root (source/merge-accepted connection commit))
        paths (when-not (:seon.error/at accepted) (source/merge-paths connection accepted))
        waiting (when paths (source/waiting-paths root lane paths))
        refuse (fn [refused]
                 (let [recorded (db/transact! connection [[:db/add a :seon.task/publication-refusal (:seon.error/type refused)]])]
                   (if (:db-after recorded) refused recorded)))]
    (cond
      (:seon.error/at accepted) (refuse accepted)
      (seq waiting) {:seon.task/assignment key :seon.merge/waiting waiting}
      :else
      (let [_ (source/claim-paths! root lane paths)
            written (source/write-back! {:seon.db/connection connection :seon.source/accepted accepted :seon.fn/root root})
            indexed (when-not (:seon.error/at written) (indexed! connection root written))
            committed (when-not (or (:seon.error/at written) (:seon.error/at indexed))
                        (source/commit! root written (acceptance database key accepted)))
            outcome (cond (:seon.error/at written) (refuse written)
                          (:seon.error/at indexed) (refuse indexed)
                          (:seon.error/at committed) (refuse committed)
                          :else (let [recorded (db/transact! connection [[:db/add a :seon.task/git-commit (:seon.git/commit committed)]])]
                                  (if (:db-after recorded)
                                    {:seon.task/assignment key :seon.source/written written
                                     :seon.git/commit (:seon.git/commit committed)}
                                    recorded)))]
        ;; the claim outlives only a crash: a refusal recorded or a commit recorded both release it
        (source/release-claim! root lane)
        outcome))))
