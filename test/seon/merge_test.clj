(ns seon.merge-test
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.merge :as merge]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.host :as host]
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
            [seon.test :as test-runner]
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
     (let [base (db/db connection)]
       (with-fork
        connection
        (fn [fork _]
          (let [head (db/db connection)
                candidate (db/db fork)
                merge-base (source/merge-base-database candidate head)]
            (try
              (is (= (db/commit-id base) (db/commit-id merge-base)))
              (is (= (registry/fork-point candidate (db/commit-id candidate) (get-in head [:config :branch]))
                     (db/commit-id merge-base))
                  "with no forward merge, the fork point is the merge base")
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
     (do
       (with-fork
        connection
        (fn [fork _branch]
          (support/transacted! fork (occurrence-tx (db/db fork)))
          (let [candidate (db/db fork)
                head (db/db connection)
                base (source/merge-base-database candidate head)
                entity (:e (first (d/datoms candidate :aevt :seon.error.occurrence/id)))
                result (merge/squash-merge candidate base head (schema/handed-projection))
                copied (checked-value head (:tx-data result))]
            (is (= 1 (:seon.error.occurrence/count (db/pull base [:seon.error.occurrence/count] entity))))
            (try
              (is (= 2 (:seon.error.occurrence/count (db/pull copied [:seon.error.occurrence/count] entity))))
              (finally (source/release! [base]))))))))))

;;; M3: the merge runs in main's writer, three-way from the branch's fork point, as a merge commit with the source as a parent.

(defn- with-assigned-fork
  "Run `proof` on a fork of main whose assignment main holds:
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
       (let [[row] (task/attempt-tx (db/db connection) handle agent-id branch)]
         (db/with-branch {:seon.db/connection connection} #(support/transacted! connection [row]))
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
            (is (merge/merged? (db/db connection) commit) "main's history records the merge of the source commit")
            (let [main (db/commit-id (db/db connection))
                  second-merge (merged! connection a)]
              (is (nil? (:seon.task/merge-commit second-merge)) "a repeat transacts nothing")
              (is (= main (db/commit-id (db/db connection))) "main is unchanged")))))))))

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
                                                         (registry/branches (:seon.store/store (support/execution-handle connection)))
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
                fork-point (:seon.merge/fork-point (#'merge/assignment-row main (:seon.task/assignment (db/pull main [:seon.task/assignment] a))))
                measured (profile/measure #(merge/merge-tx main a (db/commit-id (db/db fork))
                                                            (db/carried-projection main) fork-point))]
            (is (= 2 (count (:seon.profile/value measured))) "the intent's retraction and assertion: an unended source writes no outcome")
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

(deftest root-merges-main-forward-into-a-chain-branch
  ;; a chain made by `seon.task/chain!`, held open by no one while root merges (a held chain has no running graph):
  ;; main changes g, the chain changes f, both change h differently; main also writes a task after the fork
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        main-conn (:seon.db/connection target)
        sym #(symbol "seon.merge-test" %)
        fn-entity (fn [connection n body]
                    (support/program-entity (db/db connection) [:seon.fn/sym (sym n)]
                                            (str "(defn " n " {:malli/schema [:=> [:cat] :int]} [] " body ")")))
        write! (fn [connection entities] (support/transacted! connection (vec entities)))
        digest (fn [database n] (:seon.program/definition-digest
                                 (db/pull database [:seon.program/definition-digest] [:seon.fn/sym (sym n)])))
        handle (task/task-handle 'fixture.tasks "forward-main-task")
        ;; T and U exist at the fork: root changes T on main after it, the chain changes U
        t-handle (task/task-handle 'fixture.tasks (str "forward-t-" (id/id)))
        u-handle (task/task-handle 'fixture.tasks (str "forward-u-" (id/id)))
        plan (fn [database h] (:seon.task/plan (db/pull database [:seon.task/plan] [:seon.task/id h])))
        _ (write! main-conn [(support/task-row t-handle "T v1") (support/task-row u-handle "U v1")])
        _ (when-not (db/pull (db/db main-conn) [:db/id] [:seon.agent/id "root"])
            (write! main-conn (support/agent-tx (db/db main-conn) "root")))
        _ (write! main-conn (map #(fn-entity main-conn % 1) ["forward-f" "forward-g" "forward-h"]))
        fork-commit (db/commit-id (db/db main-conn))
        {chain :seon.chain/branch first-task :seon.chain/first}
        (task/chain! {:seon.db/connection main-conn :seon.store/store store}
                     [{:seon.task/ns 'fixture.forward :seon.task/name (str "chain-" (id/id))
                       :seon.task/plan "Merge main forward into `seon.merge/merge-main!`'s chain."
                       :seon.task/proofs '#{fixture.forward/proof}}])
        on-chain (fn [f] (registry/on-branch store chain f))
        ;; the chain's first attempt, on its branch, as root's run writes it
        attempt (on-chain #(let [tx (task/attempt-tx (db/db %) first-task "root" chain)]
                             (write! % tx)
                             (:seon.task/assignment (first tx))))]
    (try
      (write! main-conn [(fn-entity main-conn "forward-g" 2) (fn-entity main-conn "forward-h" 3)
                         (support/task-row handle "Main's task, written after the fork")
                         {:db/id [:seon.task/id t-handle] :seon.task/plan "T v2, root's on main"}])
      (on-chain #(write! % [(fn-entity % "forward-f" 4) (fn-entity % "forward-h" 5)
                            {:db/id [:seon.task/id u-handle] :seon.task/plan "U, the chain's"}]))
      (let [main (db/db main-conn)
            before (on-chain db/db)
            merged (merge/merge-main! target chain)
            after (on-chain db/db)
            ;; cost entities (and their process) ride on any transaction when the world's observed cost moved:
            ;; evidence stamped by the writer, present in some runs and absent in others, not what the merge wrote
            cost-stamp? #(or (= "seon.fn.cost" (namespace %)) (contains? #{:seon.db/process :seon.db.process/id} %))
            moved (into #{} (comp (remove #(= (:e %) (:tx %))) (map :a) (remove cost-stamp?))
                        (d/datoms (db/since after (db/basis-t before)) :eavt))
            base (db/commit-database after (source/merge-base after (db/commit-id after) (db/commit-id main)))
            forked (db/commit-database main fork-commit)]
        (is (nil? (:seon.error/type merged)) "a chain no process holds open merges forward")
        (is (= (digest main "forward-g") (digest after "forward-g")) "g is main's")
        (is (= (digest before "forward-f") (digest after "forward-f")) "f stays the branch's")
        (is (= (digest before "forward-h") (digest after "forward-h")) "h stays the branch's")
        (is (= #{(sym "forward-h")} (:seon.program/conflict merged)) "h is a listed conflict")
        (is (= {:seon.program/base-digest (digest forked "forward-h")
                :seon.program/branch-digest (digest before "forward-h") :seon.program/head-digest (digest main "forward-h")}
               (get (:seon.program/conflict-digests merged) [:seon.fn/sym (sym "forward-h")]))
            "the conflict keeps its three digests")
        (is (= (db/commit-id main) (:seon.source/commit-id merged)) "the merge names main's commit")
        (is (= (map #(digest main %) ["forward-f" "forward-g" "forward-h"])
               (map #(digest base %) ["forward-f" "forward-g" "forward-h"]))
            "the branch's merge base, from the commit graph, holds main's program")
        (is (= (db/commit-id main) (db/commit-id base)) "the forward merge's base is main's head")
        (let [main (db/db main-conn)
              {a :db/id fork-point :seon.merge/fork-point} (#'merge/assignment-row main attempt)
              ;; the chain's final merge lands on main's latest head: its facts three-way from its fork point
              landed (checked-value main (merge/merge-tx main a (db/commit-id after) (db/carried-projection main) fork-point))]
          (is (not= (db/commit-id base) fork-point)
              "the facts' base is the fork point, not the merge base the forward merge moved to main's head")
          (is (= "T v1" (plan forked t-handle)) "T was v1 at the fork")
          (is (= "T v1" (plan after t-handle)) "the forward merge never moved T onto the chain")
          (is (= "T v2, root's on main" (plan landed t-handle)) "main's change to T since the fork is kept")
          (is (= "U, the chain's" (plan landed u-handle)) "the chain's own change to U lands on main"))
        (is (nil? (db/pull after [:db/id] [:seon.task/id handle])) "main's task never moved")
        (is (seq moved) "the forward merge wrote the branch")
        (is (every? (program/program-attributes (db/carried-projection after))
                    (remove #(= "seon.unmerged" (namespace %)) moved))
            "only program attributes moved, beside the unmerged entry")
        (is (= [{:seon.unmerged/name (sym "forward-h") :seon.unmerged/ours-digest (digest before "forward-h")
                 :seon.unmerged/theirs-digest (digest main "forward-h") :seon.unmerged/theirs-commit (db/commit-id main)}]
               (mapv #(select-keys % [:seon.unmerged/name :seon.unmerged/ours-digest :seon.unmerged/theirs-digest
                                      :seon.unmerged/theirs-commit])
                     (source/unmerged after)))
            "h is recorded unmerged on the branch: ours the branch's, theirs main's, in the merge commit")
        (source/release! (filter some? [base forked])))
      (finally
        (registry/retire-branch! {:seon.store/store store :seon.store/branch chain})))))

(deftest a-merge-carries-only-what-its-tests-reach
  ;; ruling 5a: a tested change and its closure merge; a scratch def, a probe schema and a `user` namespace stay
  (support/with-database
   (fn [connection]
     (let [sym #(symbol "seon.merge-test" %)
           base (db/db connection)
           _ (support/transacted! connection (support/schema-entities base [[:fixture.reach/value :int] [:tmp/probe :string]]))
           program (fn [identity source] (support/program-entity (db/db connection) identity source))
           _ (support/transacted! connection [(program [:seon.fn/sym (sym "reach-f")]
                                                       "(defn reach-f {:malli/schema [:=> [:cat] :fixture.reach/value]} [] 1)")])
           _ (support/transacted! connection [(program [:seon.fn/sym (sym "reach-scratch")]
                                                       "(defn reach-scratch {:malli/schema [:=> [:cat] :int]} [] 2)")])
           _ (support/transacted! connection [(program [:seon.test/sym (sym "reach-check")]
                                                       "(clojure.test/deftest reach-check (clojure.test/is (= 1 (seon.merge-test/reach-f))))")])
           _ (support/transacted! connection [(support/namespace-entity (db/db connection) 'user)])
           after (db/db connection)
           changed (program/changed-identities (db/basis-t base) after source/merge-bound)
           listed (source/merge-list after changed #{(sym "reach-check")})
           will (:seon.merge/will-merge listed)
           stays (:seon.merge/stays listed)]
       (is (every? changed [[:seon.fn/sym (sym "reach-f")] [:seon.fn/sym (sym "reach-scratch")]
                            [:seon.schema/key :tmp/probe] [:seon.ns/name 'user]])
           "the fixture changed each of them")
       (is (= #{} (set (remove will [[:seon.fn/sym (sym "reach-f")] [:seon.test/sym (sym "reach-check")]
                                     [:seon.schema/key :fixture.reach/value]])))
           "the tested change, its test and the schema its contract names merge")
       (is (= #{} (set (remove stays [[:seon.fn/sym (sym "reach-scratch")] [:seon.schema/key :tmp/probe]
                                      [:seon.ns/name 'user]])))
           "the scratch def, the probe schema and the user namespace stay")
       (is (= changed (into will stays)) "every change is listed once")
       (is (= #{} (set (remove (:seon.merge/stays (source/merge-list after changed #{}))
                               [[:seon.fn/sym (sym "reach-f")] [:seon.fn/sym (sym "reach-scratch")]])))
           "with no seed every change that is not a removal stays: an empty closure, never a refusal")))))

(deftest ^{:seon.test/long "One prepare-merge!: a nested seon.test/run on a fresh merge branch of main."
           :seon.test/long-ms 60000}
  a-prepared-merge-carries-its-merge-list-and-a-staying-conflict-refuses
  ;; ruling 5a/5b: the tested change and its proof merge; the scratch def stays. Owner, 2026-10-16: a conflict refuses
  ;; whether or not its seeds reach it, so a change no test covers is never dropped unseen; main wins it on the merge
  ;; branch, recorded unmerged
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        main-conn (:seon.db/connection target)
        suffix (id/id)
        sym #(symbol (str "reachmerge" suffix ".core") %)
        fn-entity (fn [connection n body]
                    (support/program-entity (db/db connection) [:seon.fn/sym (sym n)]
                                            (str "(defn " n " {:malli/schema [:=> [:cat] :int]} [] " body ")")))
        task-ns (str "tq.reachmerge" suffix)
        _ (task/index-tasks! {:seon.db/connection main-conn
                              :seon.task/files {(str "resources/seon/tasks/" task-ns ".edn")
                                                "[#:seon.task{:name \"fix\" :plan \"Merge the tested change.\"}]"}})
        task-sym (symbol task-ns "fix")
        _ (support/transacted! main-conn [(support/namespace-entity (db/db main-conn) (symbol (str "reachmerge" suffix ".core")))])
        _ (support/transacted! main-conn [(fn-entity main-conn "scratch" 1)])
        branch (keyword (str "reachmerge-" suffix))
        _ (registry/fork! {:seon.store/store store :seon.store/branch branch
                           :seon.cluster.registry/from (db/commit-id (db/db main-conn)) :seon.db/db (db/db main-conn)})
        made (volatile! [branch])]
    (try
      (registry/on-branch store branch
                          #(do (support/transacted! % [(fn-entity % "tested" 7)])
                               (support/transacted! % [(fn-entity % "scratch" 2)])
                               (support/transacted! % [(support/program-entity (db/db %) [:seon.test/sym (sym "tested-check")]
                                                                               (str "(clojure.test/deftest ^{:seon/tasks #{'" task-sym "}} tested-check (clojure.test/is (= 7 (" (sym "tested") "))))"))])))
      (support/transacted! main-conn [(fn-entity main-conn "scratch" 3)])
      (let [prepared (source/prepare-merge! target store branch task-sym)
            _ (some->> (:seon.source/candidate prepared) (vswap! made conj))
            carried (set (keys (:seon.source/expected-digests prepared)))]
        (is (= #{[:seon.fn/sym (sym "scratch")]} (:seon.program/conflict prepared))
            "a conflict in a change no seed reaches refuses the merge")
        (let [kept (db/branch-database (db/db main-conn) (:seon.source/candidate prepared))]
          (try
            (is (= [(sym "scratch")] (mapv :seon.unmerged/name (source/unmerged kept)))
                "the merge branch records it unmerged")
            (is (= "(defn scratch {:malli/schema [:=> [:cat] :int]} [] 3)"
                   (:seon.fn/source (db/pull kept [:seon.fn/source] [:seon.fn/sym (sym "scratch")])))
                "main wins it there")
            (is (= "(defn tested {:malli/schema [:=> [:cat] :int]} [] 7)"
                   (:seon.fn/source (db/pull kept [:seon.fn/source] [:seon.fn/sym (sym "tested")])))
                "beside the tested change")
            (finally (source/release! [kept])))))
      (finally
        (doseq [b @made] (registry/retire-branch! {:seon.store/store store :seon.store/branch b}))))))

(defn- run-facts
  "Run `run-id`'s evaluator, input digest and each member's symbol, reach digest and reused run, on `database`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.test.run/id] :map]}
  [database run-id]
  (let [run (db/pull database [:seon.test.run/evaluator :seon.test.run/input-digest
                               {:seon.test.run/members [:seon.test.member/symbol :seon.test.member/reach-digest
                                                        :seon.test.member/reused-run]}]
                     [:seon.test.run/id run-id])]
    (update run :seon.test.run/members #(into {} (map (juxt :seon.test.member/symbol identity)) %))))

(deftest ^{:seon.test/long "One agent test run on a chain branch, then one prepare-merge!: a nested seon.test/run on a fresh merge branch of main."
           :seon.test/long-ms 90000}
  a-merge-reuses-the-chain-branchs-green-at-an-unchanged-reach
  ;; ruling 6: the seed the chain agent ran green, at the same reach digest on the candidate, executes nothing there
  (let [target (support/execution-handle nil)
        store (:seon.store/store target)
        main-conn (:seon.db/connection target)
        suffix (id/id)
        ns-sym (symbol (str "reachreuse" suffix ".core"))
        sym #(symbol (str ns-sym) %)
        task-ns (str "tq.reachreuse" suffix)
        _ (task/index-tasks! {:seon.db/connection main-conn
                              :seon.task/files {(str "resources/seon/tasks/" task-ns ".edn")
                                                "[#:seon.task{:name \"fix\" :plan \"Merge the tested change.\"}]"}})
        task-sym (symbol task-ns "fix")
        seed (sym "tested-check")
        _ (support/transacted! main-conn [(support/namespace-entity (db/db main-conn) ns-sym)])
        branch (keyword (str "reachreuse-" suffix))
        agent-id (str "reachreuse-" suffix)
        _ (registry/fork! {:seon.store/store store :seon.store/branch branch
                           :seon.cluster.registry/from (db/commit-id (db/db main-conn)) :seon.db/db (db/db main-conn)})
        made (volatile! [branch])]
    (try
      (is (nil? (host/register! main-conn agent-id branch ns-sym (:seon.cluster/name target)))
          "main homes the chain agent on its branch")
      (registry/on-branch store branch
                          #(do (support/transacted! % [(support/program-entity (db/db %) [:seon.fn/sym (sym "tested")]
                                                                               "(defn tested {:malli/schema [:=> [:cat] :int]} [] 7)")])
                               (support/transacted! % [(support/program-entity (db/db %) [:seon.test/sym seed]
                                                                               (str "(clojure.test/deftest ^{:seon/tasks #{'" task-sym "}} tested-check (clojure.test/is (= 7 (" (sym "tested") "))))"))])))
      (let [execution (cluster.agent/acquire-context! target agent-id {:seon.agent/branch branch})
            on-branch (try (db/with-branch {:seon.db/connection (:seon.db/connection execution)}
                             #(test-runner/run
                               {:seon.test/policy :named :seon.test/execution execution
                                :seon.test/recording-connection (:seon.db/connection execution)
                                :seon.test/identities #{seed}}))
                           (finally (cluster.agent/release-context! execution)))
            _ (is (true? (:seon.test/passed? on-branch)) "the chain agent's run of its seed is green on its branch")
            branch-run (registry/on-branch store branch #(run-facts (db/db %) (:seon.test.run/id on-branch)))
            measured (profile/measure #(source/prepare-merge! target store branch task-sym))
            prepared (:seon.profile/value measured)
            _ (some->> (:seon.source/candidate prepared) (vswap! made conj))
            candidate-run (registry/on-branch store (:seon.source/candidate prepared)
                                              #(run-facts (db/db %) (:seon.test.run/id prepared)))
            member (get-in candidate-run [:seon.test.run/members seed])
            executed (remove :seon.test.member/reused-run (vals (:seon.test.run/members candidate-run)))]
        (is (nil? (:seon.error/type prepared)) "the merge prepares")
        (is (= (get-in branch-run [:seon.test.run/members seed :seon.test.member/reach-digest])
               (:seon.test.member/reach-digest member))
            "the seed's reach digest on the candidate is the chain branch's")
        (is (= (:seon.test.run/input-digest branch-run) (:seon.test.run/input-digest candidate-run))
            "the candidate's inputs are the chain branch's")
        (is (= (:seon.test.run/id on-branch) (:seon.test.member/reused-run member))
            "the candidate's seed answers from the chain branch's run, executing nothing")
        (is (= [] (mapv :seon.test.member/symbol executed)) "the candidate gate executes no test")
        (is (true? (:seon.test/passed? prepared)) "and the merge is green"))
      (finally
        (doseq [b @made] (registry/retire-branch! {:seon.store/store store :seon.store/branch b}))))))

(deftest a-merge-that-closes-its-attempt-merged-ends-its-task
  ;; A merged task reads as ended on main, so no later pass relaunches it (owner 2026-10-16, live run a9e4e9904:
  ;; the merged task stayed :running). The task's own running fact (here on main) and one the branch wrote both end.
  (support/with-database
   (fn [connection]
     (let [born (task/task-handle 'fixture.tasks "merge-ends-next")]
       (support/transacted! connection (support/agent-tx (db/db connection) "merge-ends"))
       (support/assign-task! connection "merge-ends")
       (let [a (:e (last (d/datoms (db/db connection) :aevt :seon.task/assignment)))
             assigned (:v (first (d/datoms (db/db connection) :eavt a :seon.task/attempt-of)))
             fork (db/commit-id (db/db connection))
             target (db/db connection)
             _ (support/transacted! connection [(assoc (support/task-row born "b") :seon.task/execution :running)
                                                {:db/id a :seon.task.attempt/end :done}])
             commit (db/commit-id (db/db connection))
             merged (db/with target {:tx-data (merge/merge-tx target a commit (schema/handed-projection) fork)})]
         (is (= :merged (:seon.task/outcome (db/pull merged [:seon.task/outcome] a))) "the attempt merged")
         (is (= :stopped (:seon.task/execution (db/pull merged [:seon.task/execution] assigned)))
             "the task it assigns no longer reads :running")
         (is (= :stopped (:seon.task/execution (db/pull merged [:seon.task/execution] [:seon.task/id born])))
             "a task the merge carries in :running ends too"))))))

(deftest a-merge-commit-names-the-agent-and-the-models-it-called
  ;; The merge's git commit names what wrote the change, read on the source branch the assignment names: its agent
  ;; and the distinct models that agent's turns called, one `Model:` trailer each (live run a9e4e9904's commit
  ;; claimed a model no turn called)
  (support/with-database
   (fn [connection]
     (support/transacted! connection (support/agent-tx (db/db connection) "merge-models"))
     (support/assign-task! connection "merge-models")
     (let [key (:v (last (d/datoms (db/db connection) :aevt :seon.task/assignment)))
           run-id (turn/next-id (db/db connection) "merge-models-fixture" "merge-models")
           root (support/temp-root "merge-models")
           git! (fn [& args] (let [{:keys [exit out err]} (apply shell/sh "git" "-C" root args)]
                               (if (zero? exit) (str/trim out) (throw (ex-info err {:git args})))))]
       (support/transacted! connection (turn/open-tx {:seon.turn/id run-id :seon.turn/agent [:seon.agent/id "merge-models"]
                                                      :seon.turn/opened-tx "datomic.tx"}))
       ;; one model attempt of the turn, as `seon.turn` records it
       (support/transacted! connection [{:seon.ai.attempt/id (str run-id "-attempt-0") :seon.turn/_attempts [:seon.turn/id run-id]
                                         :seon.ai.attempt/ordinal 0 :seon.ai.attempt/at #inst "2026-10-16T00:00:00Z"
                                         :seon.ai/endpoint "https://fixture.invalid/v1/chat" :seon.ai/model "fixture-model"
                                         :seon.ai.attempt/settings-edn "{}"}])
       (try
         (git! "init" "-q") (git! "config" "user.email" "mm@test.invalid") (git! "config" "user.name" "mm-test")
         (spit (str root "/a.clj") "(ns a)\n")
         (git! "add" "a.clj") (git! "commit" "-q" "-m" "fixture")
         (spit (str root "/a.clj") "(ns a)\n(def x 1)\n")
         (let [acceptance (#'merge/acceptance (db/db connection) key
                                              {:seon.program/identities #{} :seon.source/base-commit-id (db/commit-id (db/db connection))
                                               :seon.source/commit-id (db/commit-id (db/db connection))})
               written {:seon.source/changed-paths ["a.clj"]
                        :seon.source/files [{:seon.source/path "a.clj" :seon.source/base-digest "x"
                                             :seon.source/candidate-digest "y" :seon.source/replaced []}]}
               message (git! "log" "-1" "--format=%B" (:seon.git/commit (source/commit! root written acceptance)))
               trailers (into #{} (filter (fn [line] (some #(str/starts-with? line %) ["Model: " "Agent: " "Co-Authored-By: "])))
                              (str/split-lines message))]
           (is (= #{"Agent: merge-models" "Model: fixture-model"} trailers)
               "the commit names its agent and the model its turns called, and no other author"))
         (finally (support/delete-root! root)))))))
