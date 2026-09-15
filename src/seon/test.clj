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
      (let [provenance (runner/provenance database)
            result (if (:seon.error/kind provenance)
                     provenance
                     (runner/run-var! test-var))]
        (if (:seon.error/kind result)
          result
          (let [committed
                (runner/commit-results!
                 connection
                 {:seon.test.runner/results [result]
                  :seon.test/run-basis-t (db/basis-t database)
                  :seon.test/run-at (:seon.test.run/at provenance)
                  :seon.test.run/provenance provenance})]
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

(defn verified?
  "True when a source-bearing test passed on the specified tested program.
  Missing subjects, results, runs, or assertions return false. A database
  refusal remains an error value; callers must require true, not truthiness."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.test/sym :seon.test.run/program-digest]
    [:or :boolean :seon.error/value]]}
  [database test-symbol program-digest]
  (let [result (db/q
                '[:find ?test .
                  :in $ ?symbol ?digest
                  :where
                  [?test :seon.test/sym ?symbol]
                  [?test :seon.test/source]
                  [?test :seon.test/pass-count ?passes]
                  [(pos? ?passes)]
                  [?test :seon.test/fail-count 0]
                  [?test :seon.test/error-count 0]
                  [?test :seon.test/run ?run]
                  [?run :seon.test.run/id]
                  [?run :seon.test.run/program-digest ?digest]]
                database test-symbol program-digest)]
    (if (:seon.error/kind result) result (boolean result))))
