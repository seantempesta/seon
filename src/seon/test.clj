(ns seon.test
  "Agent-facing test execution over the one JVM test runner."
  (:require [seon.db :as db]
            [seon.test.runner :as runner]))

(defn run
  "Run one declared test Var, commit its result facts, and return them.

  The connection is ordinarily supplied by call preparation from the calling
  agent's environment. The returned value is pulled from the transaction's
  `:db-after`, so it cannot disagree with the facts that were committed."
  {:malli/schema
   [:=> [:cat :seon.test/var :seon.db/connection]
    [:or :seon.test/result :seon.error/value]]}
  [test-var connection]
  (let [database (db/db connection)]
    (if (:seon.error/kind database)
      database
      (let [result (runner/run-var! test-var)]
        (if (:seon.error/kind result)
          result
          (let [committed
                (runner/commit-results!
                 connection
                 {:seon.test.runner/results [result]
                  :seon.test/run-basis-t (db/basis-t database)
                  :seon.test/run-at (java.util.Date.)})]
            (if (:seon.error/kind committed)
              committed
              (first committed))))))))

(defn owned-symbols
  "Read the test symbols declared in the calling agent's assigned namespace."
  {:malli/schema [:=> [:cat :my.plan/request] [:vector :seon.test/sym]]}
  [{database :seon.db/db agent-id :seon.agent/id}]
  (vec (sort (db/q '[:find [?symbol ...] :in $ ?agent-id
                     :where [?agent :seon.agent/id ?agent-id]
                            [?agent :seon.agent/namespace ?namespace]
                            [?test :seon.test/ns ?namespace]
                            [?test :seon.test/sym ?symbol]]
                   database agent-id))))
