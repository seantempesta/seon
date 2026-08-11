(ns seon.eval.drive-test
  "Regressions for the fact-space episode grader and its transcript."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.eval.drive]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(deftest transcript-projects-the-evaluation-time-limit
  (support/with-database
    (fn [connection]
      (db/transact!
       connection
       {:tx-data
        [{:seon.ns/name 'my.agents.projection-proof}
         {:seon.cluster.agent/id "projection-proof"
          :seon.cluster.agent/namespace
          [:seon.ns/name 'my.agents.projection-proof]}
         {:seon.cluster.run/id "projection-proof-run"
          :seon.cluster.run/agent
          [:seon.cluster.agent/id "projection-proof"]
          :seon.cluster.run/opened-at (java.util.Date.)}
         {:seon.cluster.run.form/id "projection-proof-form"
          :seon.cluster.run.form/run
          [:seon.cluster.run/id "projection-proof-run"]
          :seon.cluster.run.form/ordinal 0
          :seon.cluster.run.form/source "42"}
         ;; `result-size` is intentionally absent. Receipt rendering derives
         ;; from the declared content and must not require that numeric fact.
         {:seon.cluster.eval/id "projection-proof-receipt"
          :seon.cluster.eval/run
          [:seon.cluster.run/id "projection-proof-run"]
          :seon.cluster.eval/ordinal 0
          :seon.cluster.eval/at (java.util.Date.)
          :seon.cluster.eval/result-edn "42"}]})
      (let [database @connection
            full-transcript (ns-resolve 'seon.eval.drive 'full-transcript)
            instance {:seon.boot/cluster-connection connection
                      :seon.sci.eval/ctx
                      (support/fork-cluster-ctx connection)}
            settings {:seon.config.eval/time-limit-ms 1000
                      :seon.config/on-core-error :record
                      :seon.config.eval.result/max-depth 8
                      :seon.config.eval.result/max-collection 32
                      :seon.config.eval.result/max-string 4096
                      :seon.config.eval.result/max-nodes 4096}]
        (is (= "user=> 42\n42"
               (full-transcript database "projection-proof"
                                instance settings)))))))
