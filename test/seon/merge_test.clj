(ns seon.merge-test
  (:require [clojure.test :refer [deftest is]]
            [seon.merge :as merge]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.message :as message]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [datahike.api :as d]
            [datahike.writing :as writing]
            [konserve.core :as k]
            [seon.id :as id]
            [seon.error :as error]
            [seon.config :as config]
            [seon.db :as db]
            [seon.profile :as profile]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.render.value :as value]
            [seon.task :as task]
            [seon.turn :as turn]
            [seon.test-support :as support]))

(defn- checked-value
  "The writer-validated immutable result of transaction data."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/transaction]
                  :seon.db/database-value]}
  [database transaction]
  (let [result (db/with database transaction)]
    (is (not (:seon.error/at result)))
    result))

(defn- rule-projection
  "A synthetic declaration of task call counts for concurrent rule examples."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.cluster/conflict]
                  :seon.schema/projection]}
  [projection rule]
  (let [projection (schema/projection-with-schema
                    projection :seon.error.occurrence/occurrence
                    (mapv (fn [entry]
                            (if (and (vector? entry) (= :seon.error.occurrence/count (first entry)))
                              [:seon.error.occurrence/count {:seon.cluster/conflict rule} :seon.error.occurrence/count]
                              entry))
                          (get (:seon.schema.projection/forms projection) :seon.error.occurrence/occurrence))
                    {:seon.schema.admission/source :agent})]
    (schema/projection-with-schema
     projection :seon.task/task
     (conj (get (:seon.schema.projection/forms projection) :seon.task/task)
           [:seon.error.occurrence/count {:optional true :seon.cluster/conflict rule} :seon.error.occurrence/count])
     {:seon.schema.admission/source :agent})))

(defn- ten-evaluation-attempt
  "An immutable value holding the turn owner's ten evaluation facts."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  :seon.db/database-value]}
  [database agent-id]
  (let [run-id (turn/next-id database "squash-fixture" agent-id)
        opened (checked-value database
                              (turn/open-tx {:seon.turn/id run-id :seon.turn/agent [:seon.agent/id agent-id]
                                             :seon.turn/opened-tx "datomic.tx"}))]
    (checked-value
     opened
     (into [] (mapcat #(turn/receipt-start-tx
                       {:seon.turn/id run-id :seon.cluster.eval/ordinal %
                        :seon.cluster.eval/at #inst "2026-10-11T00:00:00Z"
                        :seon.cluster.eval/source "(+ 1 1)"})) (range 10)))))

(deftest ^{:seon/tasks #{'seon.merge/an-attempts-cluster-facts-copy-onto-main-by-identity}}
  an-attempts-cluster-facts-copy-onto-main-by-identity
  (support/with-database
   (fn [connection]
     (let [handle (task/task-handle 'fixture.tasks "squash-parent")
           child (task/task-handle 'fixture.tasks "squash-child")
           parent-ref [:seon.task/id handle]
           child-ref [:seon.task/id child]
           parent (support/task-row handle "Original")
           _ (support/transacted! connection (into [parent] (support/agent-tx (db/db connection) "squash-end")))
           _ (support/assign-task! connection "squash-end")
           assignment (:e (last (d/datoms (db/db connection) :aevt :seon.task/assignment)))
           base (db/db connection)
           source (db/with base [(support/task-row child "Child")
                                 {:db/id parent-ref :seon.task/plan "Refined"
                                  :seon.task/children #{child-ref}}
                                {:db/id assignment :seon.task.attempt/end :done}])
           target base
           squash-merge (ns-resolve 'seon.merge 'squash-merge)]
       (is (some? squash-merge))
       (when squash-merge
         (let [picked (squash-merge source base target (schema/handed-projection))
               copied (db/with target {:tx-data (:tx-data picked)})]
           (is (empty? (:conflicts picked)))
           (is (= :done (:seon.task.attempt/end (db/pull copied [:seon.task.attempt/end] assignment))))
           (is (= "Refined" (:seon.task/plan (db/pull copied [:seon.task/plan] parent-ref))))
           (is (= "Child" (:seon.task/plan (db/pull copied [:seon.task/plan] child-ref))))
           (is (= (:db/id (db/pull source [:db/id] child-ref))
                     (:db/id (db/pull copied [:db/id] child-ref))))
           (is (= "Original" (:seon.task/plan (db/pull target [:seon.task/plan] parent-ref))))))))))

(deftest ^{:seon/tasks #{'seon.merge/an-attempts-cluster-facts-copy-onto-main-by-identity}} a-value-changed-on-both-sides-resolves-by-the-declared-rule
  (support/with-database
   (fn [connection]
     (let [handle (task/task-handle 'fixture.tasks "squash-conflict")
           entity [:seon.task/id handle]
           _ (support/transacted! connection [(assoc (support/task-row handle "Original") :seon.error.occurrence/count 1)])
           base (db/db connection)
           source (checked-value base [{:db/id entity :seon.error.occurrence/count 3 :seon.task/plan "Refined"}])
           target (checked-value base [{:db/id entity :seon.error.occurrence/count 4}])]
       (doseq [[rule expected conflicts] [[:root 4 1] [:ours 4 0] [:theirs 3 0] [:add 6 0]]]
         (let [projection (rule-projection (schema/handed-projection) rule)
               result (merge/squash-merge source base target projection)
               copied (checked-value target {:tx-data (:tx-data result)})]
           (is (= expected (:seon.error.occurrence/count (db/pull copied [:seon.error.occurrence/count] entity))))
           (is (= conflicts (count (:conflicts result))))
           (is (= "Refined" (:seon.task/plan (db/pull copied [:seon.task/plan] entity))))
           (when (= :root rule)
             (is (= [{:entity (:db/id (db/pull base [:db/id] entity)) :attribute :seon.error.occurrence/count :base 1 :ours 4 :theirs 3}]
                    (:conflicts result))))))))))

(deftest ^{:seon/tasks #{'seon.merge/an-attempts-cluster-facts-copy-onto-main-by-identity}} a-copy-of-an-unchanged-branch-is-empty
  (support/with-database
   (fn [connection]
     (let [base (db/db connection)
           projection (schema/handed-projection)]
       (is (empty? (:tx-data (merge/squash-merge base base base projection))))))))

(deftest ^{:seon/tasks #{'seon.merge/an-attempts-cluster-facts-copy-onto-main-by-identity}} program-and-runtime-facts-never-copy
  (support/with-database
   (fn [connection]
     (support/transacted! connection (support/agent-tx (db/db connection) "squash-runtime"))
     (let [base (db/db connection)
           runtime (ten-evaluation-attempt base "squash-runtime")
           function (support/program-fn-entity base 'fixture.squash/f
                                               "(defn f \"Return one.\" {:malli/schema [:=> [:cat] :int]} [] 1)")
           source (checked-value runtime
                                 (into [(support/namespace-entity base 'fixture.squash) function]
                                       (message/send-call runtime
                                                          (message/candidate {:my.message/to "squash-runtime"
                                                                              :my.message/content "Hello"})
                                                          "squash-runtime")))
           result (merge/squash-merge source base base (schema/handed-projection))]
       (is (empty? (:tx-data result)))
       (is (empty? (:conflicts result)))))))

(deftest ^{:seon/tasks #{'seon.merge/an-attempts-cluster-facts-copy-onto-main-by-identity}} a-retraction-on-the-branch-retracts-on-an-unchanged-main-only
  (support/with-database
   (fn [connection]
     (let [handle (task/task-handle 'fixture.tasks "squash-retract")
           entity [:seon.task/id handle]
           _ (support/transacted! connection [(assoc (support/task-row handle "Original") :seon.task/references #{'fx/a})])
           base (db/db connection)
           source (checked-value base [[:db/retract entity :seon.task/references 'fx/a]])
           projection (schema/handed-projection)
           target (checked-value base [{:db/id entity :seon.task/references #{'fx/b}}
                                       [:db/retract entity :seon.task/references 'fx/a]])
           unchanged (merge/squash-merge source base base projection)
           concurrent (merge/squash-merge source base target projection)]
       (doseq [[from to] [[base source]
                         [(db/basis-t base) source]
                         [(db/as-of base (db/basis-t base)) source]
                         [base (db/as-of source (db/basis-t source))]]]
         (is (= #{[:seon.task/references (:db/id (db/pull base [:db/id] entity))]}
                (program/changed-identities from to {} #{:seon.task/references :seon.task/plan}))))
       (is (nil? (:seon.task/references (db/pull (checked-value base {:tx-data (:tx-data unchanged)})
                                             [:seon.task/references] entity))))
       (is (empty? (:tx-data concurrent)))
       (is (empty? (:conflicts concurrent)))))))

(deftest ^{:seon/tasks #{'seon.merge/disjoint-set-additions-merge-as-their-union}}
  disjoint-set-additions-merge-as-their-union
  (support/with-database
   (fn [connection]
     (let [handle (task/task-handle 'fixture.tasks "squash-members")
           entity [:seon.task/id handle]
           _ (support/transacted! connection [(assoc (support/task-row handle "Original")
                                                     :seon.task/references #{'fx/a 'fx/b})])
           base (db/db connection)
           projection (schema/handed-projection)
           change (fn [adds removes]
                    (checked-value base (into (mapv #(vector :db/add entity :seon.task/references %) adds)
                                              (map #(vector :db/retract entity :seon.task/references %)) removes)))
           ;; [ours-adds ours-removes theirs-adds theirs-removes merged]
           rows [[['fx/y] [] ['fx/z] [] #{'fx/a 'fx/b 'fx/y 'fx/z}]
                 [[] ['fx/a] ['fx/z] [] #{'fx/b 'fx/z}]
                 [[] [] [] ['fx/a] #{'fx/b}]
                 [['fx/y] [] [] [] #{'fx/a 'fx/b 'fx/y}]
                 [[] ['fx/a] [] ['fx/a] #{'fx/b}]
                 [[] ['fx/a] [] ['fx/b] #{}]]]
       (doseq [[oa orr ta tr merged] rows]
         (let [target (change oa orr)
               result (merge/squash-merge (change ta tr) base target projection)
               copied (checked-value target {:tx-data (:tx-data result)})]
           (is (= [] (:conflicts result)))
           (is (= merged (set (:seon.task/references (db/pull copied [:seon.task/references] entity)))))))))))

(deftest ^{:seon/tasks #{'seon.merge/an-attempts-cluster-facts-copy-onto-main-by-identity}} one-attempts-copy-stays-within-its-cost-bound
  (support/with-database
   (fn [connection]
     (let [handle (task/task-handle 'fixture.tasks "squash-cost")
           entity [:seon.task/id handle]
           _ (support/transacted! connection (into [(support/task-row handle "Original")]
                                                   (support/agent-tx (db/db connection) "squash-cost")))
           base (db/db connection)
           source (checked-value (ten-evaluation-attempt base "squash-cost")
                                 [{:db/id entity :seon.task/plan "Refined"}])
           projection (schema/handed-projection)
           measured (profile/measure #(merge/squash-merge source base base projection))]
       (spit (str (System/getenv "SEON_CHECKOUT") "/tmp/astra-cost-handoff-squash.edn")
             (value/core-text (dissoc measured :seon.profile/value)))
       (is (<= (:seon.profile/ms measured) 10))
       (is (number? (:seon.profile/alloc-bytes measured)))
       (is (<= (:seon.profile/alloc-bytes measured) 5000000))))))

(deftest ^{:seon/tasks #{'seon.merge/disjoint-set-additions-merge-as-their-union}}
  the-three-way-on-one-changed-set-stays-within-its-cost-bound
  (support/with-database
   (fn [connection]
     (let [handle (task/task-handle 'fixture.tasks "squash-set-cost")
           entity [:seon.task/id handle]
           _ (support/transacted! connection [(assoc (support/task-row handle "Original")
                                                     :seon.task/references #{'fx/a 'fx/b})])
           base (db/db connection)
           source (checked-value base [[:db/add entity :seon.task/references 'fx/z]])
           target (checked-value base [[:db/add entity :seon.task/references 'fx/y]])
           projection (schema/handed-projection)
           run #(merge/squash-merge source base target projection)
           _ (run)
           measured (profile/measure run)]
       (is (<= (:seon.profile/ms measured) 5))))))

(defn- with-fork
  "Run a proof on a store fork, releasing its connection and branch afterwards."
  {:malli/schema [:=> [:cat :seon.db/connection [:fn clojure.core/ifn?]] :nil]}
  [connection proof]
  (let [base (db/db connection)
        held-store (:seon.store/store (support/execution-handle connection))
        branch (keyword (str "squash-" (id/id)))
        request {:seon.store/store held-store :seon.store/branch branch
                 :seon.cluster.registry/from (db/commit-id base) :seon.db/db base}]
    (try
      (is (:seon.cluster/created? (registry/fork! request)))
      (let [fork (db/carry-connection-projection-state!
                  (store/open-branch! held-store branch)
                  (:seon.sci.eval/projection-state (meta connection)))]
        (try (db/with-branch {:seon.db/connection fork} #(proof fork branch))
             (finally (store/release-branch! fork))))
      (finally (registry/retire-branch! (select-keys request [:seon.store/store :seon.store/branch])))))
  nil)

(deftest ^{:seon/tasks #{'seon.merge/an-attempts-cluster-facts-copy-onto-main-by-identity}}
  a-branch-born-entity-keeps-its-eid-on-main
  (support/with-database
   {:seon.test-support/fresh-store? true}
   (fn [connection]
     (let [base (db/db connection)
           child (task/task-handle 'fixture.tasks "squash-allocator")]
       (support/transacted! connection [(support/task-row child "Child")])
       (let [branch (db/db connection)
             allocator (writing/eid-allocator branch)
             issued (.get ^java.util.concurrent.atomic.AtomicLong allocator)
             result (merge/squash-merge branch base base (schema/handed-projection))
             copied (checked-value base (:tx-data result))]
         (is (= (:db/id (db/pull branch [:db/id] [:seon.task/id child]))
                (:db/id (db/pull copied [:db/id] [:seon.task/id child]))))
         (is (= issued (.get ^java.util.concurrent.atomic.AtomicLong allocator)))))))
  (support/with-database
   (fn [connection]
     (let [parent (task/task-handle 'fixture.tasks "squash-eid-parent")
           child (task/task-handle 'fixture.tasks "squash-eid-child")
           parent-ref [:seon.task/id parent]
           child-ref [:seon.task/id child]]
       (support/transacted! connection [(support/task-row parent "Parent")])
       (let [base (db/db connection)]
         (with-fork
          connection
          (fn [fork _]
            (support/transacted! fork [(support/task-row child "Child")
                                      {:db/id parent-ref :seon.task/children #{child-ref}}])
            (let [branch (db/db fork)
                  eid (:db/id (db/pull branch [:db/id] child-ref))
                  squash (merge/squash-merge branch base (db/db connection) (schema/handed-projection))
                  copied (checked-value (db/db connection) (:tx-data squash))
                  next-before (:db/id (db/pull (checked-value (db/db connection) [{:db/ident :fixture.squash/next}]) [:db/id] :fixture.squash/next))
                  replayed (checked-value copied (:tx-data squash))
                  next-after (:db/id (db/pull (checked-value replayed [{:db/ident :fixture.squash/next}]) [:db/id] :fixture.squash/next))]
              (is (= eid (:db/id (db/pull copied [:db/id] child-ref))))
              (is (= #{eid} (set (map :db/id (:seon.task/children (db/pull copied [:seon.task/children] parent-ref))))))
              (is (= (inc eid) next-after))
              (is (= (:db/id (db/pull (checked-value copied [{:db/ident :fixture.squash/next}]) [:db/id] :fixture.squash/next))
                     next-after))
              (is (< next-before next-after))))))))))

(deftest ^{:seon/tasks #{'seon.merge/an-attempts-cluster-facts-copy-onto-main-by-identity}}
  the-merge-base-is-the-merge-requests
  (support/with-database
   (fn [connection]
     (let [base (db/db connection)
           target (get-in base [:config :branch])]
       (with-fork
        connection
        (fn [fork branch]
          (let [head (db/db connection)
                candidate (db/db fork)
                merge-base (source/merge-base candidate head target branch)
                basis (source/branch-base-t candidate head target branch)]
            (is (= (db/basis-t base) basis))
            (try
              (is (= (db/commit-id base) (db/commit-id merge-base)))
              (is (= (registry/base-commit-id candidate branch) (db/commit-id merge-base)))
              (is (= (set (d/datoms base :eavt)) (set (d/datoms merge-base :eavt))))
              (finally (source/release! [merge-base]))))))))))

(defn- occurrence-tx
  "The error owner's transaction data for one repeatable fixture occurrence."
  {:malli/schema [:=> [:cat :seon.db/database-value] :seon.store/transaction-data]}
  [database]
  (:seon.db/tx-data
   (error/recording database
                    {:seon.error/source {:seon.error/operation 'fixture.squash/f
                                         :seon.error/message "A fixture failure."}
                     :seon.error/at #inst "2026-10-11T00:00:00Z"
                     :seon.error/process "squash-fixture"
                     :seon.error/id (str (random-uuid))
                     :seon.error/declared-schema :seon.error/normalization-error
                     :seon.sci.admit/caps (config/result-caps config/defaults)
                     :seon.config.error/max-evidence-bytes (:seon.config.error/max-evidence-bytes config/defaults)})))

(deftest ^{:seon/tasks #{'seon.merge/an-attempts-cluster-facts-copy-onto-main-by-identity}}
  a-natural-identity-born-on-both-sides-maps-to-mains-eid
  (support/with-database
   (fn [connection]
     (let [parent (task/task-handle 'fixture.tasks "squash-collision-parent")
           parent-ref [:seon.task/id parent]
]
       (support/transacted! connection [(support/task-row parent "Parent")])
       (let [base (db/db connection)]
         (with-fork
          connection
          (fn [fork _]
            (support/transacted! fork (occurrence-tx (db/db fork)))
            (db/with-branch {:seon.db/connection connection}
                                 #(support/transacted! connection (occurrence-tx (db/db connection))))
            (let [branch (db/db fork)
                  theirs (first (d/datoms branch :aevt :seon.error.occurrence/id))
                  ours (first (d/datoms (db/db connection) :avet :seon.error.occurrence/id (:v theirs)))]
              (is (not= (:e theirs) (:e ours)))
              (support/transacted! fork [{:db/id (:e theirs) :seon.error.occurrence/related (:e theirs)}])
              (let [branch (db/db fork)
                    squash (merge/squash-merge branch base (db/db connection) (schema/handed-projection))
                    copied (checked-value (db/db connection) (:tx-data squash))
                    entity (db/pull copied [:db/id :seon.error.occurrence/count :seon.error.occurrence/related]
                                    [:seon.error.occurrence/id (:v theirs)])]
                (is (= (:e ours) (:db/id entity)))
                (is (= 2 (:seon.error.occurrence/count entity)))
                (is (= (:e ours) (get-in entity [:seon.error.occurrence/related :db/id])))
                (is (= 1 (count (d/datoms copied :avet :seon.error.occurrence/id (:v theirs))))))))))))))

(deftest ^{:seon/tasks #{'seon.merge/an-attempts-cluster-facts-copy-onto-main-by-identity}}
  a-refused-write-recorded-on-the-branch-merges-as-its-occurrence-alone
  ;; The occurrence is a cluster entity; the refused transaction's evidence is an identity-less
  ;; component (`:seon.db.write/attempt`) that shares two attributes with it and stays on the branch.
  (support/with-database
   (fn [connection]
     (let [base (db/db connection)]
       (with-fork
        connection
        (fn [fork _]
          (let [refused (db/transact! fork [[:db.fn/call (fn [_] (throw (ex-info "fixture refusal" {})))]])
                recording (error/recording
                           (db/db fork)
                           {:seon.error/source refused
                            :seon.error/declared-schema :seon.db.write/validation-refusal
                            :seon.error/at #inst "2026-10-12T00:00:00Z"
                            :seon.error/process "squash-fixture"
                            :seon.error/id (str (random-uuid))
                            :seon.sci.admit/caps (config/result-caps config/defaults)
                            :seon.config.error/max-evidence-bytes (:seon.config.error/max-evidence-bytes config/defaults)})]
            (support/transacted! fork (:seon.db/tx-data recording))
            (let [branch (db/db fork)
                  occurrence (:v (first (d/datoms branch :aevt :seon.error.occurrence/id)))
                  main (db/db connection)
                  squash (merge/squash-merge branch base main (schema/handed-projection))
                  merged (checked-value main {:tx-data (:tx-data squash)})]
              (is (seq (d/datoms branch :aevt :seon.db.write.attempt/operations))
                  "the branch holds the refused write's evidence")
              (is (= 1 (count (d/datoms merged :avet :seon.error.occurrence/id occurrence))))
              (is (empty? (d/datoms merged :aevt :seon.db.write.attempt/operations)))))))))))

(deftest ^{:seon/tasks #{'seon.merge/an-attempts-cluster-facts-copy-onto-main-by-identity}}
  the-merge-base-retains-the-count-needed-by-add
  (support/with-database
   (fn [connection]
     (support/transacted! connection (occurrence-tx (db/db connection)))
     (let [target (get-in (db/db connection) [:config :branch])]
       (with-fork
        connection
        (fn [fork branch]
          (support/transacted! fork (occurrence-tx (db/db fork)))
          (let [candidate (db/db fork)
                head (db/db connection)
                base (source/merge-base candidate head target branch)
                entity (:e (first (d/datoms candidate :aevt :seon.error.occurrence/id)))
                result (merge/squash-merge candidate base head (schema/handed-projection))
                copied (checked-value head (:tx-data result))]
            (is (= 1 (:seon.error.occurrence/count (db/pull base [:seon.error.occurrence/count] entity))))
            (try
              (is (= 2 (:seon.error.occurrence/count (db/pull copied [:seon.error.occurrence/count] entity))))
              (registry/advance-base! candidate branch)
              (let [advanced (source/merge-base candidate head target branch)]
                (try
                  (is (= 2 (:seon.error.occurrence/count (db/pull advanced [:seon.error.occurrence/count] entity))))
                  (finally (source/release! [advanced]))))
              (finally (source/release! [base]))))))))))

(deftest ^{:seon/tasks #{'seon.merge/an-attempts-cluster-facts-copy-onto-main-by-identity}}
  a-base-ref-keeps-the-fork-commit-only-until-retirement
  (let [root (str "tmp/sol-p1-squash/retention-" (random-uuid))
        opened (store/open-store! {:seon.store/dir (str root "/store")})
        connection (:seon.store/connection-object opened)
        base @connection
        commit (d/commit-id base)
        branch :squash-retention
        cutoff (java.util.Date. (+ (System/currentTimeMillis) 60000))]
    (try
      ;; Exercise the same two branches refs without a schema population: GC
      ;; owns native commits, independently of Seon's entity validation.
      (doseq [ref [branch (registry/base-branch branch)]]
        (registry/branch! {:seon.store/store opened :seon.store/branch ref
                           :seon.cluster.registry/from commit}))
      (registry/retire-unclaimed! opened #{branch})
      (is (contains? (registry/branches opened) (registry/base-branch branch)))
      (d/transact connection [])
      @(d/gc-storage connection cutoff)
      (let [retained (d/commit-as-db connection commit)]
        (try
          (is (= commit (d/commit-id retained)))
          (is (= commit (registry/base-commit-id base branch)))
          (finally (source/release! [retained]))))
      (registry/retire-branch! {:seon.store/store opened :seon.store/branch branch})
      @(d/gc-storage connection cutoff)
      (is (not (contains? (registry/branches opened) branch)))
      (is (not (contains? (registry/branches opened) (registry/base-branch branch))))
      (is (nil? (k/get (:store @connection) commit nil {:sync? true})))
      (is (nil? (d/commit-as-db connection commit)))
      (finally
        (registry/retire-branch! {:seon.store/store opened :seon.store/branch branch})
        (store/release-store! opened)
        (support/delete-recursively! root)))))

;;; M3: the merge runs in main's writer and advances the assignment's merge base in the same transaction.

(defn- with-assigned-fork
  "Run `proof` on a fork of main whose assignment main holds with the fork commit as its merge base:
  task `handle` (intent \"a\") and its agent on main, then the fork, then the assignment written on main.
  Calls (proof fork branch a) with `a` the assignment's entity id on main."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.task/handle [:fn clojure.core/ifn?]] :nil]}
  [connection handle proof]
  (let [agent-id (str "merge-" handle)]
    (support/transacted! connection (into [(support/task-row handle "a")]
                                          (support/agent-tx (db/db connection) agent-id)))
    ;; a conflict is written as root's problem task (`seon.merge/conflict-task!`)
    (when-not (:db/id (db/pull (db/db connection) [:db/id] [:seon.agent/id "root"]))
      (support/transacted! connection (support/agent-tx (db/db connection) "root")))
    (with-fork
     connection
     (fn [fork branch]
       (let [[row] (task/attempt-tx (db/db connection) handle agent-id branch)
             based (assoc row :seon.task/merge-base (registry/base-commit-id (db/db fork) branch))]
         (db/with-branch {:seon.db/connection connection} #(support/transacted! connection [based]))
         (proof fork branch (:db/id (db/pull (db/db connection) [:db/id]
                                             [:seon.task/assignment (:seon.task/assignment row)]))))))))

(defn- merged!
  "Merge assignment `a`'s branch onto main through `seon.merge/merge!`, under main's custody."
  {:malli/schema [:=> [:cat :seon.db/connection :int] [:or :seon.merge/merged :seon.error/value]]}
  [connection a]
  (let [key (:seon.task/assignment (db/pull (db/db connection) [:seon.task/assignment] a))]
    (db/with-branch {:seon.db/connection connection}
                    #(merge/merge! {:seon.db/connection connection
                                    :seon.store/store (:seon.store/store (support/execution-handle connection))}
                                   key))))

(defn- plan-on
  "The intent of task `handle` on `connection`'s head."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.task/handle] [:maybe :seon.task/plan]]}
  [connection handle]
  (:seon.task/plan (db/pull (db/db connection) [:seon.task/plan] [:seon.task/id handle])))

(defn- merge-base-on
  "The merge base main holds on assignment `a`."
  {:malli/schema [:=> [:cat :seon.db/connection :int] [:maybe :uuid]]}
  [connection a]
  (:seon.task/merge-base (db/pull (db/db connection) [:seon.task/merge-base] a)))

(deftest a-repeated-merge-adds-nothing
  (support/with-database
   (fn [connection]
     (let [handle (task/task-handle 'fixture.tasks "merge-repeat")]
       (with-assigned-fork
        connection handle
        (fn [fork branch a]
          (support/transacted! fork [{:db/id [:seon.task/id handle] :seon.task/plan "b"}])
          (let [commit (db/commit-id (db/db fork))
                first-merge (merged! connection a)]
            (is (uuid? (:seon.task/merge-commit first-merge)) "the merge transacted")
            (is (= "b" (plan-on connection handle)))
            (is (= commit (merge-base-on connection a)) "the merge base moved to the merged source commit")
            (is (= [] (merge/merge-tx (db/db connection) a commit (db/carried-projection (db/db connection))))
                "the writer's transaction data for a repeat is empty")
            (let [main (db/commit-id (db/db connection))
                  second-merge (merged! connection a)]
              (is (nil? (:seon.task/merge-commit second-merge)) "a repeat transacts nothing")
              (is (= main (db/commit-id (db/db connection))) "main is unchanged")
              (is (= commit (merge-base-on connection a)))))))))))

(deftest a-facts-only-merge-is-never-due-for-publication
  (support/with-database
   (fn [connection]
     (let [handle (task/task-handle 'fixture.tasks "merge-facts-only")]
       (with-assigned-fork
        connection handle
        (fn [fork _ a]
          (support/transacted! fork [{:db/id [:seon.task/id handle] :seon.task/plan "b"}])
          (let [merged (merged! connection a)
                main (db/db connection)
                row (some #(when (= a (:db/id %)) %)
                          (cluster.agent/lifecycle-facts main (:seon.store/store (support/execution-handle connection))
                                                         {:seon.flow.actions/started #{} :seon.flow.actions/paused #{}}))]
            (is (uuid? (:seon.task/merge-commit merged)) "the facts merged")
            (is (nil? (:seon.task/outcome (db/pull main [:seon.task/outcome] a)))
                "a source not yet ended merges its facts and completes nothing")
            (is (nil? (:seon.task/merged-declarations (db/pull main [:seon.task/merged-declarations] a)))
                "a merge of facts alone records no moved declarations")
            (is (map? row) "the assignment is held")
            (is (not (:seon.flow.actions/publish? row)) "and its publication is never due"))))))))

(deftest a-root-edit-after-capture-keeps-roots-value
  (support/with-database
   (fn [connection]
     (let [handle (task/task-handle 'fixture.tasks "merge-root-edit")]
       (with-assigned-fork
        connection handle
        (fn [fork branch a]
          (support/transacted! fork [{:db/id [:seon.task/id handle] :seon.task/plan "b"}])
          (db/with-branch {:seon.db/connection connection}
                          #(support/transacted! connection [{:db/id [:seon.task/id handle] :seon.task/plan "root edited"}]))
          (let [merged (merged! connection a)]
            (is (= "root edited" (plan-on connection handle)) "main keeps root's value")
            (is (= [{:entity (:db/id (db/pull (db/db connection) [:db/id] [:seon.task/id handle]))
                     :attribute :seon.task/plan :base "a" :ours "root edited" :theirs "b"}]
                   (:seon.merge/conflicts merged))
                "the writer's conflict is answered with its three values"))))))))

(deftest a-reverted-branch-value-reverts-on-main
  (support/with-database
   (fn [connection]
     (let [handle (task/task-handle 'fixture.tasks "merge-revert")]
       (with-assigned-fork
        connection handle
        (fn [fork branch a]
          (support/transacted! fork [{:db/id [:seon.task/id handle] :seon.task/plan "b"}])
          (merged! connection a)
          (is (= "b" (plan-on connection handle)))
          (support/transacted! fork [{:db/id [:seon.task/id handle] :seon.task/plan "a"}])
          (merged! connection a)
          (is (= "a" (plan-on connection handle)) "the revert since the last merge reaches main")))))))

(deftest an-occurrence-counted-on-the-branch-adds-once-on-main
  (support/with-database
   (fn [connection]
     (let [handle (task/task-handle 'fixture.tasks "merge-count")]
       (support/transacted! connection (occurrence-tx (db/db connection)))
       (with-assigned-fork
        connection handle
        (fn [fork branch a]
          (support/transacted! fork (occurrence-tx (db/db fork)))
          (db/with-branch {:seon.db/connection connection}
                          #(support/transacted! connection (occurrence-tx (db/db connection))))
          (let [occurrence (:v (first (d/datoms (db/db fork) :aevt :seon.error.occurrence/id)))
                count-on #(:seon.error.occurrence/count
                           (db/pull (db/db %) [:seon.error.occurrence/count] [:seon.error.occurrence/id occurrence]))]
            (is (= [2 2] [(count-on fork) (count-on connection)]) "base 1, branch 2, main 2")
            (merged! connection a)
            (is (= 3 (count-on connection)) "the branch's one occurrence adds once")
            (merged! connection a)
            (is (= 3 (count-on connection)) "a repeated merge adds nothing"))))))))

(deftest a-merge-stays-within-its-cost-bound
  ;; the writer's work, merge-tx, is bounded at 20 ms (hybrid-done-and-merge M3); the transaction's own write
  ;; validation is the writer's and is the same for any write of these facts
  (support/with-database
   (fn [connection]
     (let [handle (task/task-handle 'fixture.tasks "merge-cost")]
       (with-assigned-fork
        connection handle
        (fn [fork _ a]
          (support/transacted! fork [{:db/id [:seon.task/id handle] :seon.task/plan "b"}])
          (let [main (db/db connection)
                measured (profile/measure #(merge/merge-tx main a (db/commit-id (db/db fork))
                                                            (db/carried-projection main)))]
            (is (= 3 (count (:seon.profile/value measured))) "the intent's retraction and assertion and the moved base: an unended source writes no outcome")
            (is (<= (:seon.profile/ms measured) 20)))))))))

(defn- maybe-schemas
  "The symbols of the vars in namespaces `names` whose declared contract holds a `:maybe`."
  {:malli/schema [:=> [:cat [:fn seq?]] [:vector :qualified-symbol]]}
  [names]
  (vec (sort (for [n names
                   [_ v] (ns-interns n)
                   :let [schema (:malli/schema (meta v))]
                   :when (some #{:maybe} (tree-seq coll? seq schema))]
               (symbol v)))))

(deftest merge-and-agent-facing-contracts-declare-absence-as-no-key
  (let [agent-facing (filter #(.startsWith (str (ns-name %)) "my.") (all-ns))]
    (is (= [] (maybe-schemas (concat [(the-ns 'seon.merge)] agent-facing))))
    (is (seq agent-facing) "the my.* namespaces were loaded")))
