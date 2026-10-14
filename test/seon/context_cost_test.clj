(ns seon.context-cost-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.context :as context]
            [seon.db :as db]
            [seon.profile :as profile]
            [seon.task :as task]
            [seon.test-support :as support]))

(deftest ^{:seon/tasks #{'seon.context/one-agents-current-state-costs-hundreds-of-megabytes
                       'seon.fn/gate-set-walks-the-whole-transitive-closure-to-select-two-levels}}
  one-agents-current-state-costs-only-its-attachments
  (support/with-running-agent
   (fn [running agent-id connection]
     (support/declare-proof! connection 'context.cost-proof 'pending 'context.cost/read)
     (let [references #{'seon.ref/references 'seon.ref/resolve-path 'seon.context/current-state
                        'seon.task/agent-attempt 'seon.task/agent-task 'seon.db/pull 'seon.db/q}
           id (:seon.task/id
               (task/create! {:seon.db/connection connection :seon.agent/id agent-id
                              :seon.task/ns 'context.cost :seon.task/name "read" :seon.task/plan (str "Read " (str/join ", " (map #(str "`" % "`") (sort references))) ".")}))]
       (is (not (:seon.error/type
                 (task/assign! {:seon.agent/context-source (support/running-cluster running)
                                :seon.db/connection connection :seon.task/assigned id
                                :seon.task/agent [:seon.agent/id agent-id]
                                :seon.task/branch (get-in (db/db connection) [:config :branch])}))))
       (let [database (db/db connection)
             first-block (context/current-state database agent-id)
             measured (profile/measure #(context/current-state database agent-id))]
         (is (= first-block (:seon.profile/value measured)))
         (is (= references (set (map :seon.ref/name (:seon.context/must-reads first-block)))))
         ;; Seven attachments and one proof: the task's budget includes every JVM thread.
         (is (< (get-in measured [:seon.cost/jvm-window :seon.cost/alloc-mb]) 50.0))
         (is (< (:seon.profile/ms measured) 50.0)))))))
