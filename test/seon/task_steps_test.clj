(ns seon.task-steps-test
  "A task's ordered steps: the agent's plan, persistent facts at the task home. Each test asserts
  one behavior on data."
  (:require [clojure.test :refer [deftest is]]
            [my.task :as my.task]
            [seon.cluster :as cluster]
            [seon.db :as db]
            [seon.fn]
            [seon.task :as task]
            [seon.test-support :as test-support]))

(defn- world
  "The request every `my.task` step call takes for root on `connection`."
  [connection]
  {:seon.db/connection connection :seon.agent/id "root"})

(defn- with-root
  "Run `f` with a connection holding a seeded cluster and its root agent, which holds a task with an unrun proof."
  [f]
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "task-steps" {})
     (cluster/seed-root-agent! connection "task-steps" cluster/boot-process-identity)
     (test-support/assign-task! connection "root")
     (f connection))))

(deftest planning-with-no-task-is-refused-for-want-of-a-proof
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "task-steps" {})
     (cluster/seed-root-agent! connection "task-steps" cluster/boot-process-identity)
     (is (= :no-proofs (:seon.task/refused (my.task/plan! (assoc (world connection) :seon.task/steps ["x"])))))
     (is (nil? (task/agent-task (db/db connection) "root")) "root holds no task"))))

(deftest planning-writes-steps-on-the-held-task
  (with-root
   (fn [connection]
     (let [view (my.task/plan! (assoc (world connection) :seon.task/steps ["read the test" "fix the producer"]))
           handle (:seon.task/id view)]
       (is (= handle (task/agent-task (db/db connection) "root")) "root holds it")
       (is (= ["read the test" "fix the producer"] (mapv :seon.step/text (:seon.task/steps view))))
       (is (= [:current :open] (mapv :seon.step/status (:seon.task/steps view))))
       (is (= "read the test" (:seon.task/current-step view)))
       (is (= "0/2" (:seon.task/progress view)))))))

(deftest done-completes-the-current-step-and-advances
  (with-root
   (fn [connection]
     (my.task/plan! (assoc (world connection) :seon.task/steps ["one" "two"]))
     (let [after-one (my.task/done! (world connection))
           after-two (my.task/done! (world connection))]
       (is (= "two" (:seon.task/current-step after-one)))
       (is (= "1/2" (:seon.task/progress after-one)))
       (is (nil? (:seon.task/current-step after-two)) "no step is current once all are done")
       (is (= "2/2" (:seon.task/progress after-two)))
       (is (every? #(integer? (:seon.step/done-tx %)) (:seon.task/steps after-two)) "each done step names its transaction")
       (is (= :no-current-step (:seon.task/refused (my.task/done! (world connection)))))))))

(deftest step-appends-and-is-current-when-none-is
  (with-root
   (fn [connection]
     (my.task/plan! (assoc (world connection) :seon.task/steps ["only"]))
     (my.task/done! (world connection))
     (let [view (my.task/step! (assoc (world connection) :seon.step/text "later"))]
       (is (= [0 1] (mapv :seon.step/position (:seon.task/steps view))))
       (is (= "later" (:seon.task/current-step view)))))))

(deftest a-step-write-never-revises-the-task
  (with-root
   (fn [connection]
     (let [handle (:seon.task/id (my.task/plan! (assoc (world connection) :seon.task/steps ["a"])))
           before (task/task-revision (db/db connection) handle)]
       (my.task/step! (assoc (world connection) :seon.step/text "b"))
       (my.task/done! (world connection))
       (is (= before (task/task-revision (db/db connection) handle)))))))

(deftest the-task-render-shows-the-current-step
  (with-root
   (fn [connection]
     (let [handle (:seon.task/id (my.task/plan! (assoc (world connection) :seon.task/steps ["first" "second"])))
           unit (assoc (db/pull (db/db connection) [:seon.task/id :seon.task/name :seon.task/kind :seon.task/ns] [:seon.task/id handle])
                       :seon.db/db (db/db connection) :seon.render/distance 0)
           shown ((requiring-resolve 'seon.render.ns/task-ai) unit)]
       (is (= "first" (:seon.task/current-step shown)))
       (is (= "0/2" (:seon.task/progress shown)))))))

(deftest a-step-on-an-unknown-task-is-a-named-error
  (with-root
   (fn [connection]
     (is (= :unknown-task (:seon.task/refused (task/steps! connection "0123456789ab" [:append "x"])))))))

(deftest reads-and-completion-with-no-task-are-named-errors
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "task-steps" {})
     (cluster/seed-root-agent! connection "task-steps" cluster/boot-process-identity)
     (is (= :unassigned (:seon.task/refused (my.task/steps (world connection)))))
     (is (= :unassigned (:seon.task/refused (my.task/done! (world connection))))))))
