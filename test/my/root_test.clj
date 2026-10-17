(ns my.root-test
  "Root's tools over merge requests, which are assignments: listing the open ones, merging one, closing one
  with its reason. Each test asserts one behavior on data."
  (:require [clojure.test :refer [deftest is]]
            [my.root :as my.root]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.id]
            [seon.profile :as profile]
            [seon.task :as task]
            [seon.test-support :as test-support]))

(defn- with-assignment
  "Run `f` with a connection whose root holds an assigned task on main, and that assignment's key."
  {:malli/schema [:=> [:cat [:fn clojure.core/ifn?]] :nil]}
  [f]
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "my-root" {})
     (cluster/seed-root-agent! connection "my-root" cluster/boot-process-identity)
     (test-support/assign-task! connection "root")
     (let [database (db/db connection)]
       (f connection (:seon.task/key (task/assignment database "root" (get-in database [:config :branch])))))))
  nil)

(defn- root-context
  "Root's supplied world on `connection`, as call preparation hands it to my.root."
  {:malli/schema [:=> [:cat :seon.db/connection] :my.program/context]}
  [connection]
  (let [ctx (test-support/fork-cluster-ctx connection)
        handle (test-support/execution-handle connection)]
    ;; the cluster handle's own members: a merge's gate is a `:seon.agent/context-source`
    {:seon.db/connection connection :seon.sci.eval/ctx ctx :my.program/base-ctx ctx
     :seon.cluster/name (:seon.cluster/name handle) :seon.agent/routing (:seon.agent/routing handle)
     :seon.store/store (:seon.store/store handle)}))

(deftest instructions-are-the-laws-root-applies
  (is (every? string? (:my.root/laws (my.root/instructions)))))

(deftest merge-requests-lists-the-open-assignment-with-its-merge-and-close-offered
  (with-assignment
   (fn [connection assignment]
     (let [[request :as open] (my.root/merge-requests {:seon.db/db (db/db connection)})]
       (is (= [assignment] (mapv :seon.task/assignment open)))
       (is (= (list 'my.root/merge! {:seon.task/assignment assignment}) (first (:seon.render/operations request))))))))

(deftest close-records-closed-with-its-reason-and-the-assignment-leaves-the-open-list
  (with-assignment
   (fn [connection assignment]
     (let [closed (my.root/close! {:my.program/context (root-context connection) :seon.task/assignment assignment
                                   :seon.task/reason "The change belongs in seon.db."})]
       (is (= {:seon.task/assignment assignment :seon.task/outcome :closed :seon.task/reason "The change belongs in seon.db."} closed))
       (is (= [] (my.root/merge-requests {:seon.db/db (db/db connection)})))))))

(deftest merging-an-assignment-that-is-not-open-answers-the-refusal-naming-it
  (with-assignment
   (fn [connection _]
     (let [refused (my.root/merge! {:my.program/context (root-context connection) :seon.task/assignment "000000000000"})]
       (is (= "000000000000" (:seon.error/offending refused)))))))

(defn- ended-attempt!
  "Write on main `connection` an attempt of a fresh task by a fresh agent on a new branch of `held`, and on that
  branch the task's changed plan and the attempt's `end`: the assignment key and the branch's open connection."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.store/store :seon.task.attempt/end]
                  [:tuple :seon.task/assignment :seon.db/connection :seon.store/branch]]}
  [connection held end]
  (let [id (seon.id/id)
        handle (task/task-handle 'fixture.tasks (str "first-merge-" id))
        agent-id (str "first-merge-agent-" id)
        branch (keyword (str "first-merge-" id))]
    (test-support/transacted! connection (into [(test-support/task-row handle "Do it.")]
                                               (test-support/agent-tx (db/db connection) agent-id)))
    (let [[row] (task/attempt-tx (db/db connection) handle agent-id branch)
          _ (test-support/transacted! connection [row])
          a (:db/id (db/pull (db/db connection) [:db/id] [:seon.task/assignment (:seon.task/assignment row)]))
          _ (registry/fork! {:seon.store/store held :seon.store/branch branch
                             :seon.cluster.registry/from (db/commit-id (db/db connection))
                             :seon.db/db (db/db connection)})
          fork (db/carry-connection-projection-state! (store/open-branch! held branch)
                                                      (:seon.sci.eval/projection-state (meta connection)))]
      (db/with-branch {:seon.db/connection fork}
                      #(test-support/transacted! fork [{:db/id [:seon.task/id handle] :seon.task/plan "Done."}
                                                       [:db/add a :seon.task.attempt/end end]]))
      [(:seon.task/assignment row) fork branch])))

(deftest a-jvms-first-facts-merge-follows-the-changed-facts
  (test-support/with-database
   (fn [connection]
     (let [held (:seon.store/store (test-support/execution-handle connection))
           attempts [(ended-attempt! connection held :sent-back) (ended-attempt! connection held :sent-back)]]
       (try
         (let [[first-merge second-merge]
               (mapv (fn [[assignment]]
                       (profile/measure #(my.root/merge! {:my.program/context (root-context connection)
                                                          :seon.task/assignment assignment})))
                     attempts)]
           (is (= [:closed :closed] (mapv (comp :seon.task/outcome :seon.profile/value) [first-merge second-merge]))
               "each sent-back attempt's facts merge closes it")
           ;; allocation on the merging thread, not wall time: a concurrent check cannot move it
           (is (<= (:seon.profile/alloc-bytes first-merge) (* 64 1024 1024))
               "the JVM's first facts merge allocates under 64 MB on its thread")
           (is (<= (:seon.profile/alloc-bytes second-merge) (* 16 1024 1024))
               "a warm facts merge allocates what its changed facts need, under 16 MB, never main's whole history"))
         (finally
           (doseq [[_ fork branch] attempts]
             (store/release-branch! fork)
             (registry/retire-branch! {:seon.store/store held :seon.store/branch branch}))))))))

(deftest a-merge-is-keyed-by-the-assignment
  ;; an agent's branch ended :done with its task refined; root merges it by its assignment
  (test-support/with-database
   (fn [connection]
     (let [handle (task/task-handle 'fixture.tasks "merge-keyed")
           agent-id "merge-keyed-agent"
           held (:seon.store/store (test-support/execution-handle connection))
           branch (keyword (str "merge-keyed-" (random-uuid)))]
       (test-support/transacted! connection (into [(assoc (test-support/task-row handle "Do it.")
                                                          :seon.task/proofs #{'my.root-test/instructions-are-the-laws-root-applies})]
                                                  (test-support/agent-tx (db/db connection) agent-id)))
       ;; root assigns the task, then the agent's branch forks main (its merge base, written by the merge)
       (let [[row] (task/attempt-tx (db/db connection) handle agent-id branch)
             assignment (:seon.task/assignment row)
             _ (test-support/transacted! connection [row])
             a (:db/id (db/pull (db/db connection) [:db/id] [:seon.task/assignment assignment]))
             _ (is (:seon.cluster/created? (registry/fork! {:seon.store/store held :seon.store/branch branch
                                                            :seon.cluster.registry/from (db/commit-id (db/db connection))
                                                            :seon.db/db (db/db connection)})))
             fork (db/carry-connection-projection-state! (store/open-branch! held branch)
                                                         (:seon.sci.eval/projection-state (meta connection)))]
         (try
           (db/with-branch {:seon.db/connection fork}
                           #(test-support/transacted! fork [{:db/id [:seon.task/id handle] :seon.task/plan "Done."}
                                                            [:db/add a :seon.task.attempt/end :done]]))
           (let [commit (db/commit-id (db/db fork))
                 open (my.root/merge-requests {:seon.db/db (db/db connection)})
                 merged (my.root/merge! {:my.program/context (root-context connection) :seon.task/assignment assignment})
                 row (db/pull (db/db connection) [:db/id :seon.task/outcome :seon.task.attempt/end]
                              [:seon.task/assignment assignment])]
             (is (some #{assignment} (map :seon.task/assignment open)) "the open assignment is a merge request")
             (is (nil? (:seon.error/message merged)) "the merge answers no refusal")
             (is (= :merged (:seon.task/outcome row)) "the done assignment closes merged")
             (is ((requiring-resolve 'seon.merge/merged?) (db/db connection) commit) "main records the merge of the commit")
             (is (uuid? ((requiring-resolve 'seon.merge/merge-commit) (db/db connection) (:db/id row))) "main derives the merge commit")
             (is (= :done (:seon.task.attempt/end row)) "the end reached main in the merge")
             (is (= "Done." (:seon.task/plan (db/pull (db/db connection) [:seon.task/plan] [:seon.task/id handle]))))
             (is (not-any? #{assignment} (map :seon.task/assignment (my.root/merge-requests {:seon.db/db (db/db connection)})))
                 "and it leaves the open list"))
           (finally (store/release-branch! fork)
                    (registry/retire-branch! {:seon.store/store held :seon.store/branch branch}))))))))

(deftest a-running-chain-attempt-is-not-a-merge-request
  ;; TASK-CHAIN §2: root merges at the chain's end; an attempt is a merge request only once it has ended
  (let [target (test-support/execution-handle nil)
        store (:seon.store/store target)
        connection (:seon.db/connection target)
        _ (test-support/transacted! connection (test-support/agent-tx (db/db connection) "root"))
        chain (task/chain! {:seon.db/connection connection :seon.store/store store}
                           [{:seon.task/ns 'tq.rootmr :seon.task/name "fix" :seon.task/plan "Fix." :seon.task/proofs '#{tq.rootmr/proof}}])
        branch (:seon.chain/branch chain)
        [handle] (:seon.task/handles chain)
        on-chain (cluster.agent/acquire-context! target nil {:seon.agent/branch branch})
        chained (:seon.db/connection on-chain)
        agent-id (str "mr-agent-" (random-uuid))
        listed #(into #{} (map :seon.task/assignment)
                      (my.root/merge-requests {:seon.db/db (db/db connection) :seon.store/store store}))]
    (try
      (test-support/transacted! chained (test-support/agent-tx (db/db chained) agent-id))
      (test-support/transacted! chained (task/attempt-tx (db/db chained) handle agent-id branch))
      (let [key (:seon.task/assignment (db/pull (db/db chained) [:seon.task/assignment]
                                                [:seon.task/assignment (seon.id/id [handle 1])]))]
        (is (not (contains? (listed) key)) "a running attempt is not listed")
        (test-support/transacted! chained [[:db/add [:seon.task/assignment key] :seon.task.attempt/end :done]])
        (is (contains? (listed) key) "an attempt that ended is"))
      (finally (cluster.agent/release-context! on-chain)
               (registry/retire-branch! {:seon.store/store store :seon.store/branch branch})))))
