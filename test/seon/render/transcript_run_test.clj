(ns seon.render.transcript-run-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.render.transcript :as transcript]
            [seon.render.value :as value]
            [seon.test-support :as support]))

(def ^:private caps
  (assoc (config/result-caps (support/effective-config))
         :seon.config.eval.result/max-string 4096
         :seon.config.eval.result/max-collection 64
         :seon.config.eval.result/max-depth 12
         :seon.config.eval.result/max-nodes 4096))

(deftest render-run-selects-only-the-requested-run
  (support/with-database
   (fn [connection]
     (support/transacted!
             connection
             (into (support/agent-tx @connection "run-render-agent")
               [{:seon.turn/id "run-a" :seon.turn/agent [:seon.agent/id "run-render-agent"] :seon.turn/opened-tx "datomic.tx" :seon.turn/closed-tx "datomic.tx"}
                {:seon.turn/id "run-b" :seon.turn/agent [:seon.agent/id "run-render-agent"] :seon.turn/opened-tx "datomic.tx"}
                ;; ONE ENTITY PER (run, ordinal): the frozen source and the settled
                ;; result are attributes of the same evaluation. Ordinal 1 of run-a
                ;; is frozen and never started, which is exactly "pending".
                {:seon.cluster.eval/id "eval-a"
                 :seon.cluster.eval/run [:seon.turn/id "run-a"]
                 :seon.cluster.eval/ordinal 0
                 :seon.cluster.eval/author :agent
                 :seon.cluster.eval/source "(+ 1 1)"
                 :seon.cluster.eval/at #inst "2026-09-06T00:00:01.000-00:00"
                 :seon.eval/shown "2"}
                {:seon.cluster.eval/id "form-a-pending"
                 :seon.cluster.eval/run [:seon.turn/id "run-a"]
                 :seon.cluster.eval/ordinal 1
                 :seon.cluster.eval/author :agent
                 ;; Frozen and never started still has a freeze instant.
                 :seon.cluster.eval/at #inst "2026-09-06T00:00:02.000-00:00"
                 :seon.cluster.eval/source "pending-form"}
                {:seon.cluster.eval/id "eval-b"
                 :seon.cluster.eval/run [:seon.turn/id "run-b"]
                 :seon.cluster.eval/ordinal 0
                 :seon.cluster.eval/author :agent
                 :seon.cluster.eval/source "(* 9 9)"
                 :seon.cluster.eval/at #inst "2026-09-06T00:01:01.000-00:00"
                 :seon.eval/shown "81"}]))
     (let [database (db/db connection)
           run (db/pull database '[*] [:seon.turn/id "run-a"])
           ctx (support/fork-cluster-ctx connection)
           profile (render/agent-render-profile (support/effective-config))
           unit (merge (value/transacted run database)
                       {:seon.db/db database
                        :seon.db/connection connection
                        :seon.sci.eval/ctx ctx
                        :seon.render/profile profile
                        :seon.sci.admit/caps caps
                        :seon.sci.eval/time-limit-ms 30000
                        :seon.config/on-core-error :panic})
           ai (transcript/render-run-ai unit)
           html (transcript/render-run-html unit)
           request {:seon.db/db database
                    :seon.db/connection connection
                    :seon.sci.eval/ctx ctx
                    :seon.render/namespace 'seon.flow
                    :seon.render/value run
                    :seon.render/profile profile
                    :seon.sci.admit/caps caps
                    :seon.sci.eval/time-limit-ms 30000
                    :seon.config/on-core-error :panic}]
       (is (integer? (:seon.turn/agent unit))
           "generic producer normalization carries the agent as a database ref")
       (is (str/includes? ai "interrupted before the reply arrived"))
       (is (str/includes? ai "(+ 1 1)"))
       (is (str/includes? ai "2"))
       (is (str/includes? ai "pending-form"))
       (is (not (str/includes? ai "(* 9 9)")))
       (is (not (str/includes? ai "81")))
       (is (str/includes? (pr-str html) "interrupted before the reply arrived"))
       (is (str/includes? (pr-str html) "pending-form"))
       (is (not (str/includes? (pr-str html) "(* 9 9)")))
       (let [generic-ai (render/render-call
                         (assoc request :seon.render/output :seon.render/ai))
             generic-html (render/render-call
                           (assoc request :seon.render/output :seon.render/html))]
         (is (str/includes? generic-ai "(+ 1 1)"))
         (is (str/includes? generic-ai "pending-form"))
         (is (not (str/includes? generic-ai "(* 9 9)")))
         (is (str/includes? (pr-str generic-html)
                            "interrupted before the reply arrived"))
         (is (str/includes? (pr-str generic-html) "2")))
       (let [missing (transcript/render-run-ai
                      (dissoc unit :seon.turn/id))]
         (is (= :seon.turn/turn
                (:seon.render.transcript/refused-member missing)))
         (is (= :seon.turn/turn
                (get-in missing [:seon.error/data
                                 :seon.error/member]))))))))
