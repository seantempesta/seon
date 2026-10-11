(ns seon.cluster.squash-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.squash :as squash]
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
            [seon.schema :as schema]
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

(deftest ^{:seon/tasks #{'seon.cluster.squash/an-attempts-cluster-facts-copy-onto-main-by-identity}}
  an-attempts-cluster-facts-copy-onto-main-by-identity
  (support/with-database
   (fn [connection]
     (let [handle (task/task-handle 'fixture.tasks "squash-parent" :function)
           child (task/task-handle 'fixture.tasks "squash-child" :function)
           parent-ref [:seon.task/id handle]
           child-ref [:seon.task/id child]
           parent (support/task-row handle "Original")
           _ (support/transacted! connection (into [parent] (support/agent-tx (db/db connection) "squash-end")))
           _ (support/assign-task! connection "squash-end")
           assignment (:e (last (d/datoms (db/db connection) :aevt :seon.task/assignment)))
           base (db/db connection)
           source (db/with base [(support/task-row child "Child")
                                 {:db/id parent-ref :seon.task/intent "Refined"
                                  :seon.task/children #{child-ref}}
                                {:db/id assignment :seon.task.attempt/end :done}])
           target base
           squash-merge (ns-resolve 'seon.cluster.squash 'squash-merge)]
       (is (some? squash-merge))
       (when squash-merge
         (let [picked (squash-merge source base target (schema/handed-projection) nil)
               copied (db/with target {:tx-data (:tx-data picked)})]
           (is (empty? (:conflicts picked)))
           (is (= :done (:seon.task.attempt/end (db/pull copied [:seon.task.attempt/end] assignment))))
           (is (= "Refined" (:seon.task/intent (db/pull copied [:seon.task/intent] parent-ref))))
           (is (= "Child" (:seon.task/intent (db/pull copied [:seon.task/intent] child-ref))))
           (is (= (:db/id (db/pull source [:db/id] child-ref))
                     (:db/id (db/pull copied [:db/id] child-ref))))
           (is (= "Original" (:seon.task/intent (db/pull target [:seon.task/intent] parent-ref))))))))))

(deftest ^{:seon/tasks #{'seon.cluster.squash/an-attempts-cluster-facts-copy-onto-main-by-identity}} a-value-changed-on-both-sides-resolves-by-the-declared-rule
  (support/with-database
   (fn [connection]
     (let [handle (task/task-handle 'fixture.tasks "squash-conflict" :function)
           entity [:seon.task/id handle]
           _ (support/transacted! connection [(assoc (support/task-row handle "Original") :seon.error.occurrence/count 1)])
           base (db/db connection)
           source (checked-value base [{:db/id entity :seon.error.occurrence/count 3 :seon.task/intent "Refined"}])
           target (checked-value base [{:db/id entity :seon.error.occurrence/count 4}])]
       (doseq [[rule expected conflicts] [[:root 4 1] [:ours 4 0] [:theirs 3 0] [:add 6 0]]]
         (let [projection (rule-projection (schema/handed-projection) rule)
               result (squash/squash-merge source base target projection nil)
               copied (checked-value target {:tx-data (:tx-data result)})]
           (is (= expected (:seon.error.occurrence/count (db/pull copied [:seon.error.occurrence/count] entity))))
           (is (= conflicts (count (:conflicts result))))
           (is (= "Refined" (:seon.task/intent (db/pull copied [:seon.task/intent] entity))))
           (when (= :root rule)
             (is (= [{:entity (:db/id (db/pull base [:db/id] entity)) :attribute :seon.error.occurrence/count :base 1 :ours 4 :theirs 3}]
                    (:conflicts result))))))))))

(deftest ^{:seon/tasks #{'seon.cluster.squash/an-attempts-cluster-facts-copy-onto-main-by-identity}} a-copy-of-an-unchanged-branch-is-empty
  (support/with-database
   (fn [connection]
     (let [base (db/db connection)
           projection (schema/handed-projection)]
       (is (empty? (:tx-data (squash/squash-merge base base base projection nil))))))))

(deftest ^{:seon/tasks #{'seon.cluster.squash/an-attempts-cluster-facts-copy-onto-main-by-identity}} program-and-runtime-facts-never-copy
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
           result (squash/squash-merge source base base (schema/handed-projection) nil)]
       (is (empty? (:tx-data result)))
       (is (empty? (:conflicts result)))))))

(deftest ^{:seon/tasks #{'seon.cluster.squash/an-attempts-cluster-facts-copy-onto-main-by-identity}} a-retraction-on-the-branch-retracts-on-an-unchanged-main-only
  (support/with-database
   (fn [connection]
     (let [handle (task/task-handle 'fixture.tasks "squash-retract" :function)
           entity [:seon.task/id handle]
           _ (support/transacted! connection [(assoc (support/task-row handle "Original") :seon.task/evidence #{"docs/a"})])
           base (db/db connection)
           source (checked-value base [[:db/retract entity :seon.task/evidence "docs/a"]])
           projection (schema/handed-projection)
           target (checked-value base [{:db/id entity :seon.task/evidence #{"docs/b"}}
                                       [:db/retract entity :seon.task/evidence "docs/a"]])
           unchanged (squash/squash-merge source base base projection nil)
           concurrent (squash/squash-merge source base target projection nil)]
       (is (nil? (:seon.task/evidence (db/pull (checked-value base {:tx-data (:tx-data unchanged)})
                                             [:seon.task/evidence] entity))))
       (is (empty? (:tx-data concurrent)))
       (is (= 1 (count (:conflicts concurrent))))))))

(deftest ^{:seon/tasks #{'seon.cluster.squash/an-attempts-cluster-facts-copy-onto-main-by-identity}} one-attempts-copy-stays-within-its-cost-bound
  (support/with-database
   (fn [connection]
     (let [handle (task/task-handle 'fixture.tasks "squash-cost" :function)
           entity [:seon.task/id handle]
           _ (support/transacted! connection (into [(support/task-row handle "Original")]
                                                   (support/agent-tx (db/db connection) "squash-cost")))
           base (db/db connection)
           source (checked-value (ten-evaluation-attempt base "squash-cost")
                                 [{:db/id entity :seon.task/intent "Refined"}])
           projection (schema/handed-projection)
           measured (profile/measure #(squash/squash-merge source base base projection nil))]
       (is (<= (:seon.profile/ms measured) 10))
       (is (number? (:seon.profile/alloc-bytes measured)))
       (is (<= (:seon.profile/alloc-bytes measured) 5000000))))))

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
        (try (db/call-with-custody {:seon.db/connection fork} #(proof fork branch))
             (finally (store/release-branch! fork))))
      (finally (registry/retire-branch! (select-keys request [:seon.store/store :seon.store/branch])))))
  nil)

(deftest ^{:seon/tasks #{'seon.cluster.squash/an-attempts-cluster-facts-copy-onto-main-by-identity}}
  a-branch-born-entity-keeps-its-eid-on-main
  (support/with-database
   {:seon.test-support/fresh-store? true}
   (fn [connection]
     (let [base (db/db connection)
           child (task/task-handle 'fixture.tasks "squash-allocator" :function)]
       (support/transacted! connection [(support/task-row child "Child")])
       (let [branch (db/db connection)
             allocator (writing/eid-allocator branch)
             issued (.get ^java.util.concurrent.atomic.AtomicLong allocator)
             result (squash/squash-merge branch base base (schema/handed-projection) nil)
             copied (checked-value base (:tx-data result))]
         (is (= (:db/id (db/pull branch [:db/id] [:seon.task/id child]))
                (:db/id (db/pull copied [:db/id] [:seon.task/id child]))))
         (is (= issued (.get ^java.util.concurrent.atomic.AtomicLong allocator)))))))
  (support/with-database
   (fn [connection]
     (let [parent (task/task-handle 'fixture.tasks "squash-eid-parent" :function)
           child (task/task-handle 'fixture.tasks "squash-eid-child" :function)
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
                  squash (squash/squash-merge branch base (db/db connection) (schema/handed-projection) nil)
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

(deftest ^{:seon/tasks #{'seon.cluster.squash/an-attempts-cluster-facts-copy-onto-main-by-identity}}
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

(deftest ^{:seon/tasks #{'seon.cluster.squash/an-attempts-cluster-facts-copy-onto-main-by-identity}}
  a-natural-identity-born-on-both-sides-maps-to-mains-eid
  (support/with-database
   (fn [connection]
     (let [parent (task/task-handle 'fixture.tasks "squash-collision-parent" :function)
           parent-ref [:seon.task/id parent]
]
       (support/transacted! connection [(support/task-row parent "Parent")])
       (let [base (db/db connection)]
         (with-fork
          connection
          (fn [fork _]
            (support/transacted! fork (occurrence-tx (db/db fork)))
            (db/call-with-custody {:seon.db/connection connection}
                                 #(support/transacted! connection (occurrence-tx (db/db connection))))
            (let [branch (db/db fork)
                  theirs (first (d/datoms branch :aevt :seon.error.occurrence/id))
                  ours (first (d/datoms (db/db connection) :avet :seon.error.occurrence/id (:v theirs)))]
              (is (not= (:e theirs) (:e ours)))
              (support/transacted! fork [{:db/id (:e theirs) :seon.error.occurrence/related (:e theirs)}])
              (let [branch (db/db fork)
                    squash (squash/squash-merge branch base (db/db connection) (schema/handed-projection) nil)
                    copied (checked-value (db/db connection) (:tx-data squash))
                    entity (db/pull copied [:db/id :seon.error.occurrence/count :seon.error.occurrence/related]
                                    [:seon.error.occurrence/id (:v theirs)])]
                (is (= (:e ours) (:db/id entity)))
                (is (= 2 (:seon.error.occurrence/count entity)))
                (is (= (:e ours) (get-in entity [:seon.error.occurrence/related :db/id])))
                (is (= 1 (count (d/datoms copied :avet :seon.error.occurrence/id (:v theirs))))))))))))))

(deftest ^{:seon/tasks #{'seon.cluster.squash/an-attempts-cluster-facts-copy-onto-main-by-identity}}
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
                result (squash/squash-merge candidate base head (schema/handed-projection) nil)
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

(deftest ^{:seon/tasks #{'seon.cluster.squash/an-attempts-cluster-facts-copy-onto-main-by-identity}}
  a-base-ref-keeps-the-fork-commit-only-until-retirement
  (let [root (str "tmp/sol-p1-squash/retention-" (random-uuid))
        opened (store/open-store! {:seon.store/dir (str root "/store")})
        connection (:seon.store/connection-object opened)
        base @connection
        commit (d/commit-id base)
        branch :squash-retention
        cutoff (java.util.Date. (+ (System/currentTimeMillis) 60000))]
    (try
      ;; Exercise the same two roster refs without a schema population: GC
      ;; owns native commits, independently of Seon's entity validation.
      (doseq [ref [branch (registry/base-branch branch)]]
        (registry/branch! {:seon.store/store opened :seon.store/branch ref
                           :seon.cluster.registry/from commit}))
      (registry/retire-unclaimed! opened #{branch})
      (is (contains? (registry/roster opened) (registry/base-branch branch)))
      (d/transact connection [])
      @(d/gc-storage connection cutoff)
      (let [retained (d/commit-as-db connection commit)]
        (try
          (is (= commit (d/commit-id retained)))
          (is (= commit (registry/base-commit-id base branch)))
          (finally (source/release! [retained]))))
      (registry/retire-branch! {:seon.store/store opened :seon.store/branch branch})
      @(d/gc-storage connection cutoff)
      (is (not (contains? (registry/roster opened) branch)))
      (is (not (contains? (registry/roster opened) (registry/base-branch branch))))
      (is (nil? (k/get (:store @connection) commit nil {:sync? true})))
      (is (nil? (d/commit-as-db connection commit)))
      (finally
        (registry/retire-branch! {:seon.store/store opened :seon.store/branch branch})
        (store/release-store! opened)
        (support/delete-recursively! root)))))
