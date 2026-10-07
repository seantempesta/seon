(ns seon.agent.loop-test
  "The context producers: the system message and the task-seeded context."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.agent.loop :as loop]
            [seon.cluster.agent :as agent]
            [seon.cluster.instruction :as instruction]
            [seon.db :as db]
            [seon.task :as task]
            [seon.test-support :as support]))

(defn- with-context-agent
  "Run `body` with a fixture connection holding a seeded cluster and the agent \"context\"."
  {:malli/schema [:=> [:cat [:fn clojure.core/ifn?]] :seon.schema/value]}
  [body]
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "context")
     (support/transacted! connection (agent/creation-tx {:seon.agent/id "context"
                                                         :seon.ns/name 'my.agents.context
                                                         :seon.cluster/name "context"}))
     (body connection))))

(deftest the-system-message-carries-help-and-every-toolkit-namespace
  (with-context-agent
   (fn [connection]
     (let [database (db/db connection)
           message (loop/system-message database "context")
           toolkit (instruction/toolkit-namespaces database)]
       (is (seq toolkit) "the fixture program declares context-relevant namespaces")
       (is (str/includes? message "(my.test/check {:seon.test/changed") "the protocol says how to run named tests")
       (is (every? #(str/includes? message (str % ": ")) toolkit) "each toolkit namespace heads its tools")))))

(deftest a-task-context-follows-the-tasks-refs
  (with-context-agent
   (fn [connection]
     (let [created (task/create! {:seon.db/connection connection :seon.agent/id "context"
                                  :seon.task/ns 'seon.id :seon.task/name "context fixture" :seon.task/kind :function
                                  :seon.task/intent "Fixture task naming one function."
                                  :seon.task/must-read #{'seon.task/task-edges}})
           units (loop/task-context (db/db connection) (:seon.task/id created))
           by-form (into {} (map (juxt :seon.agent.loop/form :seon.agent.loop/value)) units)]
       (is (= "Fixture task naming one function." (:seon.task/intent (:seon.agent.loop/value (first units)))))
       (is (seq (:summary (by-form '(doc seon.task/task-edges)))) "the must-read function's documentation is shown")
       (is (string? (by-form '(clojure.repl/source-fn 'seon.task/task-edges))) "its stored source is shown")
       (is (some #(= :seon.schema/key (first (nth (:seon.agent.loop/form %) 3 nil))) units)
           "a schema its contract names is shown")))))
