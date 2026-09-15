(ns seon.eval.drive-test
  "Regressions for the fact-space episode grader and its transcript."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [seon.db :as db]
            [seon.eval.drive]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(deftest drive-contracts-refuse-incomplete-requests-before-starting-work
  (support/with-database
    (fn [_]
      (let [options {:registry (:seon.schema.projection/registry (schema/handed-projection))}
            episode {:seon.eval.drive/id "audit"
                     :seon.eval.drive/objective "Verify a total"
                     :seon.eval.drive/agent-ids ["juniper"]
                     :seon.eval.drive/run-cap 1
                     :seon.eval.drive/remote-timeout-ms 1000}]
        (is (m/validate :seon.eval.drive/episode-request episode options))
        (doseq [invalid [(dissoc episode :seon.eval.drive/objective)
                         (assoc episode :seon.eval.drive/objective " \n ")
                         (assoc episode :seon.eval.drive/agent-ids [])
                         (assoc episode :seon.eval.drive/run-cap 0)]]
          (is (false? (m/validate :seon.eval.drive/episode-request invalid options))))
        (is (false? (m/validate :seon.eval.drive/episode-result {} options)))))))

(deftest transcript-projects-the-evaluation-time-limit
  (support/with-database
    (fn [connection]
      (db/transact!
       connection
       {:tx-data
        [{:seon.ns/name 'my.agents.projection-proof}
         {:seon.agent/id "projection-proof"
          :seon.agent/namespace
          [:seon.ns/name 'my.agents.projection-proof]}
         {:seon.turn/id "projection-proof-run" :seon.turn/agent [:seon.agent/id "projection-proof"] :seon.turn/opened-tx "datomic.tx"}
         ;; `result-size` is intentionally absent. Evaluation rendering derives
         ;; from the declared content and must not require that numeric fact.
         {:seon.cluster.eval/id "projection-proof-receipt"
          :seon.cluster.eval/run
          [:seon.turn/id "projection-proof-run"]
          :seon.cluster.eval/ordinal 0
          :seon.cluster.eval/source "42"
          :seon.cluster.eval/at (java.util.Date.)
          :seon.eval/shown "42"}]})
      (let [database @connection
            full-transcript (ns-resolve 'seon.eval.drive 'full-transcript)
            instance {:seon.boot/cluster-connection connection
                      :seon.sci.eval/ctx
                      (support/fork-cluster-ctx connection)}
            settings (assoc (support/effective-config)
                            :seon.config.eval/time-limit-ms 1000
                            :seon.config/on-core-error :record
                            :seon.config.eval.result/max-depth 8
                            :seon.config.eval.result/max-collection 32
                            :seon.config.eval.result/max-string 4096
                            :seon.config.eval.result/max-nodes 4096)]
        ;; ONE ENTITY PER (run, ordinal), ONE GRAMMAR: the evaluation names
        ;; its own namespace through the run's agent instead of falling back
        ;; to `user`, and the settled value is the one REPL response map.
        (is (= "my.agents.projection-proof=> 42\n#:seon.repl{:value 42, :result result/eprojection-proof-receipt}"
               (full-transcript database "projection-proof"
                                instance settings)))))))
