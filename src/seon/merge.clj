(ns seon.merge
  "Root's merge of an assignment's branch onto main: one three-way squash merge from its merge base, the
  cluster facts inside main's writer and the requested declarations through the gate, in one transaction."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.error.refusal :as refusal]
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
                                                      " Read the three values with seon.merge/squash-merge and decide.")})]
    (when (:seon.error/at created)
      (throw (ex-info (:seon.error/message created "The conflict's problem task was refused.") created)))
    nil))

(defn- merged
  "Assignment `a`'s three-way on main `database` from its merge base (`:seon.task/merge-base`, else the commit
  its branch forked at, `fork`) to the source commit `commit`, under `projection`'s rules, with the end the
  source holds for `a`: nothing when the base already is `commit`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/entity-id :seon.source/commit-id :seon.schema/projection
                       [:maybe :seon.task/merge-base]]
                  [:map [:tx-data :seon.store/transaction-data] [:conflicts :seon.merge/conflicts]
                   [:end {:optional true} :seon.task.attempt/end]]]}
  [database a commit projection fork]
  ;; read from the datoms: inside the writer the value carries no projection of its own
  (let [base-commit (or (:v (first (d/datoms database :eavt a :seon.task/merge-base))) fork)]
    (when-not (uuid? base-commit)
      (throw (ex-info "The assignment has no merge base." {:seon.error/message "The assignment has no merge base."
                                                          :db/id a :seon.squash/source-commit commit})))
    (if (= base-commit commit)
      {:tx-data [] :conflicts []}
      (let [base (db/commit-database database base-commit)
            source (db/commit-database database commit)]
        (try (let [end (:v (first (d/datoms source :eavt a :seon.task.attempt/end)))]
               (cond-> (squash-merge source base database projection) end (assoc :end end)))
             (finally (source/release! [base source])))))))

(defn merge-tx
  "Assignment `a`'s squash merge of the source commit `commit` onto main, computed inside main's writer on its own
  `database` under main's `projection`: the three-way from its merge base (`:seon.task/merge-base`, else `fork`,
  the commit its branch forked at) to `commit` against `database`, with the base moved to `commit` in the same
  transaction, so a repeat adds nothing; [] when the base already is `commit`. An assignment with no outcome is
  closed in the same transaction: `:closed` when its source ended `:sent-back` (its work goes back to its
  target), else `:merged`. A `:root` conflict keeps main's value (`merge!` answers it)."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value :seon.db/entity-id :seon.source/commit-id :seon.schema/projection]
                   :seon.store/transaction-data]
                  [:=> [:cat :seon.db/database-value :seon.db/entity-id :seon.source/commit-id :seon.schema/projection
                        [:maybe :seon.task/merge-base]]
                   :seon.store/transaction-data]]}
  ([database a commit projection] (merge-tx database a commit projection nil))
  ([database a commit projection fork]
   (let [base-commit (or (:v (first (d/datoms database :eavt a :seon.task/merge-base))) fork)]
     (if (= base-commit commit)
       []
       (let [{:keys [tx-data end]} (merged database a commit projection fork)]
         (cond-> (conj tx-data [:db/add a :seon.task/merge-base commit])
           (empty? (d/datoms database :eavt a :seon.task/outcome))
           (conj [:db/add a :seon.task/outcome (if (= :sent-back end) :closed :merged)])))))))

(defn- merge-request
  "The latest merge request answering assignment `a` that the source `head` holds, with its proofs; nil when
  the agent requested none. Read from the requests' own datoms: a branch head opens with primary indexes only."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/entity-id] [:maybe [:map [:seon.task/proved :seon.task/proved]]]]}
  [head a]
  (when-let [request (->> (d/datoms head :aevt :seon.task/merge-request)
                          (filter #(= a (:v (first (d/datoms head :eavt (:e %) :seon.task/of-assignment)))))
                          (sort-by :tx) last)]
    {:seon.task/proved (set (:seon.task/proved (db/pull head [:seon.task/proved] (:e request))))}))

(defn- assignment-row
  "Assignment `key`'s row on main `database`, with its merge base: the stored one, else the commit its branch
  forked at (`seon.cluster.registry/base-commit-id`), which the merge stores."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/assignment]
                  [:or [:map [:db/id :seon.db/entity-id] [:seon.task/branch :seon.task/branch]
                        [:seon.task/merge-base :seon.task/merge-base]] :seon.error/value]]}
  [database key]
  (let [row (db/pull database [:db/id :seon.task/branch :seon.task/merge-base] [:seon.task/assignment key])
        base (or (:seon.task/merge-base row) (some->> (:seon.task/branch row) (registry/base-commit-id database)))
        refuse #(refusal/diagnostic (java.util.Date.) :seon.program/write 'seon.merge/merge!
                                    {:seon.error/message % :seon.error/member :seon.task/assignment :seon.error/offending key})]
    (cond
      (not (:seon.task/branch row)) (refuse (str "Assignment " key " is not on main."))
      (not base) (refuse (str "Assignment " key "'s branch retains no fork commit to merge from."))
      :else (assoc row :seon.task/merge-base base))))

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

(defn merge!
  "Merge assignment `key`'s branch head C onto main through `execution` (root's world: main's connection, and
  the store it holds or its SCI context's environment holds) as ONE
  transaction from its merge base (`:seon.task/merge-base`, else the commit its branch forked at): the cluster
  facts by `merge-tx`, inside the writer, with the base moved to C and the assignment closed by its end; and,
  when the agent requested the merge (its merge request on the branch names the proofs), the declarations
  it changed since the base, gated first: `seon.cluster.source/prepare-merge!` tests them with the proofs and
  the tests reaching them on a fresh merge branch of main, `seon.cluster.source/accept-merge!` carries
  `merge-tx` and `:seon.task/merged-declarations` (how many it moved) in its transaction, then the merge
  branch is deleted; the files the checkout `root` would hold
  are proven before any test runs. Main's commit is recorded as `:seon.task/merge-commit`; publishing the
  merged declarations into the checkout is the next pass's due work (`publish!`), never a step of the merge.
  A base already at C merges nothing and transacts nothing. Answers the merge, or the first refusal as data
  with nothing merged. A `:root` conflict of the facts keeps main's value and is answered beside them.

  Example:
  (seon.merge/merge! execution \"0123456789ab\" \"/Users/me/src/project\")"
  {:malli/schema [:=> [:cat :seon.merge/execution :seon.task/assignment [:maybe :seon.fn/root]]
                  [:or :seon.merge/merged :seon.source/conflict-error :seon.error/value]]}
  [execution key root]
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
      (let [{a :db/id branch :seon.task/branch base :seon.task/merge-base} row
            commit (registry/branch-commit-id {:seon.store/store store :seon.store/branch branch})
            target (db/db connection)
            head (when (uuid? commit) (db/commit-database target commit))
            request (when head (try (merge-request head a) (finally (source/release! [head]))))
            projection (or (db/carried-projection target) (schema/handed-projection))
            meta {:seon.squash/source-branch branch :seon.squash/source-commit commit}
            merge-call [[:db.fn/call merge-tx a commit projection base]]
            answer (fn [before after]
                     (let [recorded (db/transact! connection [[:db/add a :seon.task/merge-commit (db/commit-id after)]])]
                       (if-not (:db-after recorded)
                         recorded
                         (let [outcome (:seon.task/outcome (db/pull after [:seon.task/outcome] a))]
                           (cond-> {:seon.task/assignment key :seon.task/merge-base commit
                                    :seon.task/merge-commit (db/commit-id after)
                                    :seon.merge/conflicts (:conflicts (merged before a commit projection base))}
                             outcome (assoc :seon.task/outcome outcome))))))]
        (cond
          (not (uuid? commit)) commit
          (= base commit) {:seon.task/assignment key :seon.task/merge-base commit :seon.merge/conflicts []}
          (nil? request)
          (let [report (db/transact! connection {:tx-data merge-call :tx-meta meta})]
            (if (:db-after report)
              (let [result (answer (:db-before report) (:db-after report))]
                (when-not (:seon.error/at result) (run! #(conflict-task! connection branch %) (:seon.merge/conflicts result)))
                result)
              report))
          :else
          (let [proofs (into #{} (map first) (:seon.task/proved request))
                prepared (source/prepare-merge! context store branch proofs nil root)
                ;; the merge records, in its own transaction, how many declarations it moved: what publication reads
                moved [:db/add a :seon.task/merged-declarations (count (:seon.source/expected-digests prepared))]
                accepted (when (:seon.source/candidate prepared)
                           (source/accept-merge! context store prepared {:tx-data (conj merge-call moved) :tx-meta meta}))
                _ (when (:seon.source/candidate prepared)
                    (registry/retire-branch! {:seon.store/store store :seon.store/branch (:seon.source/candidate prepared)}))
                result (when (:seon.source/commit-id accepted)
                         (let [after (db/db connection)
                               before (db/commit-database after (:seon.source/base-commit-id accepted))
                               done (db/commit-database after (:seon.source/commit-id accepted))]
                           (try (answer before done) (finally (source/release! [before done])))))]
            (cond
              (seq (:seon.program/conflict prepared))
              (refusal/error :seon.source/conflict-error
                             {:seon.error/layer :seon.source/merge :seon.error/operation 'seon.merge/merge!
                              :seon.error/message "The source branch and main changed the same declarations since the merge base; resolve on the branch and merge again."
                              :seon.task/assignment key :seon.program/conflict-digests (:seon.program/conflict-digests prepared)})
              (not (:seon.source/candidate prepared)) prepared
              (not (:seon.source/commit-id accepted)) accepted
              (:seon.error/at result) (assoc result :seon.source/accepted accepted)
              :else (do (run! #(conflict-task! connection branch %) (:seon.merge/conflicts result))
                        (assoc result :seon.source/accepted accepted)))))))))

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

(defn- merged-request
  "The run id of the merge request on main `database` answering assignment `a`; {} when it names none."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/entity-id] [:map [:seon.task/run-id {:optional true} :seon.task/run-id]]]}
  [database a]
  (or (db/q '[:find (pull ?r [:seon.task/run-id]) . :in $ ?a
              :where [?r :seon.task/of-assignment ?a] [?r :seon.task/merge-request _]]
            database a)
      {}))

(defn publish!
  "Publish assignment `key`'s merged declarations into the cluster's checkout (`checkout`) through
  `execution` (root's world: main's connection and `:seon.cluster/name`): derived from main alone
  (`seon.cluster.source/merge-accepted` of its `:seon.task/merge-commit`), written back
  (`seon.cluster.source/write-back!`, which proves and reindexes each file) and committed as one git
  commit of exactly those paths (`seon.cluster.source/commit!`) under the merge's own claim in `bin/lane`'s
  ledger (lane `merge-<key>`), then `:seon.task/git-commit` written. A path another lane claims, or one
  with uncommitted changes the merge's claim does not cover, waits: nothing is written and the
  publication stays due (`:seon.merge/waiting`). A refusal is recorded on the assignment as
  `:seon.task/refusal`, its error type, and answered. A crash anywhere leaves it due: a write-back finding
  the merged bytes on disk installs them again unchanged, and paths HEAD already holds answer the commit
  that names this merge.

  Example:
  (seon.merge/publish! execution \"0123456789ab\")"
  {:malli/schema [:=> [:cat :seon.merge/publication :seon.task/assignment]
                  [:or :seon.merge/published :seon.error/value]]}
  [{connection :seon.db/connection cluster-name :seon.cluster/name} key]
  (let [database (db/db connection)
        {a :db/id commit :seon.task/merge-commit} (db/pull database [:db/id :seon.task/merge-commit] [:seon.task/assignment key])
        lane (str "merge-" key)
        root (checkout database cluster-name)
        accepted (if (:seon.error/at root) root (source/merge-accepted connection commit))
        paths (when-not (:seon.error/at accepted) (source/merge-paths connection accepted))
        waiting (when paths (source/waiting-paths root lane paths))
        refuse (fn [refused]
                 (let [recorded (db/transact! connection [[:db/add a :seon.task/refusal (:seon.error/type refused)]])]
                   (if (:db-after recorded) refused recorded)))]
    (cond
      (:seon.error/at accepted) (refuse accepted)
      (seq waiting) {:seon.task/assignment key :seon.merge/waiting waiting}
      :else
      (let [_ (source/claim-paths! root lane paths)
            written (source/write-back! {:seon.db/connection connection :seon.source/accepted accepted :seon.fn/root root})
            run-id (:seon.task/run-id (merged-request database a))
            committed (when-not (:seon.error/at written)
                        (source/commit! root written (acceptance database key (cond-> accepted run-id (assoc :seon.test.run/id run-id)))))
            outcome (cond (:seon.error/at written) (refuse written)
                          (:seon.error/at committed) (refuse committed)
                          :else (let [recorded (db/transact! connection [[:db/add a :seon.task/git-commit (:seon.git/commit committed)]])]
                                  (if (:db-after recorded)
                                    {:seon.task/assignment key :seon.source/written written
                                     :seon.git/commit (:seon.git/commit committed)}
                                    recorded)))]
        ;; the claim outlives only a crash: a refusal recorded or a commit recorded both release it
        (source/release-claim! root lane)
        outcome))))
