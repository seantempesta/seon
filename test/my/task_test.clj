(ns my.task-test
  "An agent's task verbs: its task with its proofs, and giving the task back. Each test asserts one
  behavior on data."
  (:require [clojure.test :refer [deftest is]]
            [my.task :as my.task]
            [seon.cluster :as cluster]
            [seon.db :as db]
            [seon.task :as task]
            [seon.test-support :as test-support]))

(defn- with-root
  "Run `f` with a connection holding a seeded cluster and its root agent; `assigned?` gives root a task
  with an unrun proof."
  [assigned? f]
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "my-task" {})
     (cluster/seed-root-agent! connection "my-task" cluster/boot-process-identity)
     (when assigned? (test-support/assign-task! connection "root"))
     (f connection))))

(deftest current-answers-the-held-task-with-its-proofs
  (with-root true
   (fn [connection]
     (let [database (db/db connection)
           current (my.task/current {:seon.db/db database :seon.agent/id "root"})]
       (is (= (task/agent-task database "root") (get-in current [:seon.context/task :seon.task/id]))
           "the task root holds")
       (is (seq (get-in current [:seon.context/proofs :seon.context/proof-list])) "its proofs, each with its status")))))

(deftest current-with-no-task-answers-the-agent-alone
  (with-root false
   (fn [connection]
     (is (= {:seon.agent/id "root"} (my.task/current {:seon.db/db (db/db connection) :seon.agent/id "root"}))))))

(deftest send-back-is-a-completed-disposition-carrying-its-reason-and-data
  (let [sent (my.task/send-back! {:my.task/reason "The proof contradicts the intent."
                                  :my.task/target 'tq.assigned/work-root :my.task/counterexample {:expected 1 :actual 2}})]
    (is (= :completed (:my.turn/disposition sent)) "the disposition the turn derives :given-back from")
    (is (= "The proof contradicts the intent." (:my.turn/result sent)) "the reason is the reply")
    (is (= 'tq.assigned/work-root (:my.task/target sent)))
    (is (= {:expected 1 :actual 2} (:my.task/counterexample sent)))))

(deftest a-send-back-with-positional-arguments-is-answered-with-its-example
  ;; chain experiment 2: an Implement agent called (my.task/send-back! target reason); the refusal named only the
  ;; destructured arglist, so its last calls were spent and the send-back never happened.
  (let [refusal (try (apply my.task/send-back! ['tq.assigned/work-root "The proof contradicts the intent."])
                     (catch Exception thrown (ex-data thrown)))]
    (is (= :seon.instrument/arity-error (:seon.error/type refusal)) "the call is refused by its contract")
    (is (= 'my.task/send-back! (first (first (:seon.instrument/examples refusal))))
        "the refusal offers the docstring's Example as a form to run")))

(deftest send-back-with-a-blank-reason-answers-its-error
  (is (:seon.error/at (my.task/send-back! {:my.task/reason "  "}))))

(deftest a-refined-goal-is-every-steps-goal
  (with-root false
   (fn [connection]
     (test-support/declare-proof! connection 'tq.chain-test 'rows 'tq.chain/implement)
     (task/index-tasks!
      {:seon.db/connection connection
       :seon.task/files {"resources/seon/tasks/tq.chain.edn"
                         (str "[#:seon.task{:name \"subject\" :intent \"Scan.\" :children #{[:seon.task/sym tq.chain/prove] [:seon.task/sym tq.chain/implement]}}"
                              " #:seon.task{:name \"prove\" :step :prove :instructions \"Add rows.\"}"
                              " #:seon.task{:name \"implement\" :needs #{[:seon.task/sym tq.chain/prove]} :instructions \"Fix it.\"}]")}})
     (task/assign! {:seon.db/connection connection :seon.task/assigned (task/task-handle 'tq.chain "implement")
                    :seon.task/agent [:seon.agent/id "root"] :seon.task/branch (get-in (db/db connection) [:config :branch])
                    :seon.agent/context-source (test-support/context-source connection)})
     (let [declared #(task/task-declaration (db/db connection) (task/task-handle 'tq.chain %))
           content #(select-keys (task/effective (db/db connection) (task/task-handle 'tq.chain %)) [:seon.task/intent :seon.task/instructions])
           prove (declared "prove")]
       (is (true? (:seon.task/changed? (my.task/refine! {:seon.db/connection connection :seon.agent/id "root"
                                                          :seon.task/intent "Scan twice; the rows are `tq.chain-test/rows`."}))))
       (is (= "Scan twice; the rows are `tq.chain-test/rows`." (:seon.task/intent (declared "subject"))) "the goal is written on the subject")
       (is (= {:seon.task/intent "Scan twice; the rows are `tq.chain-test/rows`." :seon.task/instructions "Add rows."} (content "prove"))
           "every step of the chain reads it")
       (is (= prove (declared "prove")) "no other step's declaration changes")
       (my.task/refine! {:seon.db/connection connection :seon.agent/id "root" :seon.task/instructions "Fix the body only."})
       (is (= "Fix the body only." (:seon.task/instructions (declared "implement"))) "instructions are the held step's own")
       (is (= [prove "Scan twice; the rows are `tq.chain-test/rows`."] [(declared "prove") (:seon.task/intent (declared "subject"))])
           "refining instructions changes only the held step")))))
