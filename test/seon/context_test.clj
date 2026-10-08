(ns seon.context-test
  "Contract proof for context derivation between runs."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [seon.context :as context]
            [seon.db :as db]
            [seon.instrument :as instrument]
            [seon.mcp :as mcp]
            [seon.task :as task]
            [seon.test-support :as test-support]))

(def ^:dynamic ^:private *connection* nil)

(defn- with-connection
  [body]
  (test-support/with-database
   (fn [connection]
     (binding [*connection* connection]
       (body)))))

(use-fixtures :each with-connection)

(deftest message-custody-accepts-an-absent-run-when-instrumented
  ;; Boot armed `message-custody`; the test re-arms nothing (a whole-JVM
  ;; `apply!` strips every production wrapper's supplied-default plan).
  (is (contains? (instrument/instrumented) #'context/message-custody))
  (is (= :seon.context/history
         (context/message-custody (db/db *connection*) nil "agent" 1))))

(defn- block
  "The current-state block of `agent-id` as `seon.mcp/call` answers it, read back as data."
  {:malli/schema [:=> [:cat :string :seon.agent/id] :seon.schema/value]}
  [running agent-id]
  (let [answer (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                         "arguments" {"code" "(seon.context/current-state)"
                                                                      "agent" agent-id "cluster" running}})))]
    (:seon.repl/value (test-support/reply (get (last (get answer "content")) "text")))))

(deftest current-state-shows-the-current-task-declaration
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (let [declaration {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.cs
                        :seon.task/name "shape" :seon.task/kind :function}
           id (:seon.task/id (task/create! (assoc declaration :seon.task/intent "First.")))
           task-section #(:seon.context/task (block running agent-id))]
       (is (nil? (:seon.context/task (block running agent-id))) "an agent holding no task has no task section")
       (task/assign! {:seon.db/connection connection :seon.task/assigned id
                      :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch :main})
       (is (= {:seon.task/sym 'tq.cs/shape :seon.task/intent "First."} (task-section)))
       (is (= :working (:seon.task/status (:seon.context/turns (block running agent-id)))))
       (is (int? (:my.agent/turns-left (:seon.context/turns (block running agent-id)))))
       (task/create! (assoc declaration :seon.task/intent "Second."
                            :seon.task/basis {id (#'task/declared (db/carried-projection @connection)
                                                                  (db/pull @connection '[*] [:seon.task/id id]))}))
       (is (= "Second." (:seon.task/intent (task-section))) "the revision shows without a reassignment")))))

(defn- evaluate
  "Evaluates `code` as the agent through `seon.mcp/call`."
  {:malli/schema [:=> [:cat :string :seon.agent/id :string] :string]}
  [running agent-id code]
  (mcp/call (json/write-str {"name" "eval_clj" "arguments" {"code" code "agent" agent-id "cluster" running}})))

(defn- proof-task
  "Creates and assigns the task `tq.cs/shape` for `agent-id`."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.agent/id] :nil]}
  [connection agent-id]
  (let [id (:seon.task/id (task/create! {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns 'tq.cs
                                         :seon.task/name "shape" :seon.task/kind :function :seon.task/intent "First."}))]
    (task/assign! {:seon.db/connection connection :seon.task/assigned id
                   :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch :main})
    nil))

(def ^:private proof-test
  "(ns tq.cs-test (:require [clojure.test :refer [deftest is]] [tq.cs])) (deftest ^{:seon/tasks #{'tq.cs/shape}} shape (is (= 4 (tq.cs/f 2)) \"doubles\"))")

(deftest a-red-proof-shows-expected-actual-and-message-as-data
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (proof-task connection agent-id)
     (evaluate running agent-id "(ns tq.cs) (defn f [x] (inc x))")
     (evaluate running agent-id proof-test)
     (evaluate running agent-id "(my.test/check {})")
     (let [proofs (:seon.context/proofs (block running agent-id))
           failure (:seon.context/proof-failure (first (:seon.context/proof-list proofs)))]
       (is (= {:red 1} (:seon.context/counts proofs)))
       (is (= "doubles" (:message failure)))
       (is (re-find #"\(= 4" (:expected failure)))))))

(deftest a-proof-result-before-the-latest-reached-definition-is-pending
  (test-support/with-running-agent
   (fn [running agent-id connection]
     (proof-task connection agent-id)
     (evaluate running agent-id "(ns tq.cs) (defn f [x] (* 2 x))")
     (evaluate running agent-id proof-test)
     (evaluate running agent-id "(my.test/check {})")
     (is (= {:green 1} (:seon.context/counts (:seon.context/proofs (block running agent-id)))))
     (evaluate running agent-id "(ns tq.cs) (defn f [x] (* 3 x))")
     (is (= {:pending 1} (:seon.context/counts (:seon.context/proofs (block running agent-id))))))))
