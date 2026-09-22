(ns seon.run6-stall-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.render :as render]
            [seon.render.transcript :as transcript]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(defn- transact! {:malli/schema [:=> [:cat :seon.db/connection :seon.store/transaction] :seon.db/transaction-report]}
  [connection data]
  (let [result (db/transact! connection data)]
    (when (:seon.db.write.attempt/request-id result)
      (throw (ex-info (:seon.error/message result) result)))
    result))

(deftest an-open-session-provider-refusal-is-the-first-problem
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "run6-stall"
                    :seon.config/manifest {:seon.config.ai/no-provider true
                                           :seon.config.run/max-episode-runs 3}})
     (is (:db-after
          (transact! connection
                        [{:seon.agent/id "run6-stall"
                          :seon.agent/namespace {:seon.ns/name 'my.agents.run6-stall}
                          :seon.agent/plan {:my.plan/objective "Finish the work"
                                            :my.plan/steps [{:my.plan.item/id "run6-step"
                                                             :my.plan.item/title "Read"
                                                             :my.plan.item/position 0}]}}])))
     (is (:db-after
          (transact! connection
                        (conj (turn/open-tx {:seon.turn/id "run6-refusal"
                                              :seon.turn/agent [:seon.agent/id "run6-stall"]
                                              :seon.turn/opened-tx "datomic.tx"
                                              :seon.turn.work/situation :call})
                              (assoc (error/normalize
                                      {:seon.error/source {
    :seon.ai/unreadable-response-member "body"
    :seon.error/at #inst "2026-09-15T14:46:11Z"
    :seon.error/layer :seon.ai/completion
    :seon.error/operation 'seon.ai/complete
                                                           :seon.error/message "Malformed JSON"}
                                       :seon.error/id "run6-fault"
                                       :seon.error/at #inst "2026-09-15T14:46:11Z"
                                       :seon.error/process "run6-fixture"
                                       :seon.sci.admit/caps (config/result-caps (support/effective-config))
                                       :seon.config.error/max-evidence-bytes
                                       (:seon.config.error/max-evidence-bytes (support/effective-config))})
                                     :db/id "run6-fault")
                              {:db/id "run6-attempt" :seon.ai.attempt/id "run6-attempt"
                               :seon.ai.attempt/at #inst "2026-09-15T14:46:11Z"
                               :seon.ai/endpoint "http://localhost/run6-fixture"
                               :seon.ai/model "fixture"
                               :seon.ai.attempt/settings-edn "{}"
                               :seon.ai.attempt/ordinal 0 :seon.ai.attempt/error "run6-fault"}
                              [:db/add [:seon.turn/id "run6-refusal"] :seon.turn/attempts "run6-attempt"]
                              [:db/add [:seon.turn/id "run6-refusal"] :seon.turn/closed-tx "datomic.tx"]))))
     (let [ctx (support/fork-cluster-ctx connection)
           request {:seon.agent/id "run6-stall" :seon.cluster/name "run6-stall"
                    :seon.db/connection connection :seon.sci.eval/ctx ctx
                    :seon.sci.admit/caps (config/result-caps (support/effective-config))
                    :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms (support/effective-config))
                    :seon.config/on-core-error :panic
                    :seon.render/profile (render/agent-render-profile (support/effective-config))}
           snapshot (fn []
                      (let [unit (assoc request :seon.db/db @connection)
                            rows (#'transcript/ledger-rows @connection
                                   (#'transcript/turn-rows @connection "run6-stall") {})
                            problems (#'transcript/session-problems unit rows {})]
                        {:header (pr-str (transcript/render-agent-header unit))
                         :problems problems
                         :html (#'transcript/problems-html unit problems)}))
           before (snapshot)
           first-rule (first (:seon.render.transcript/rules (:problems before)))
           active-panels (filter #(and (vector? %) (:data-problem (second %)))
                                 (tree-seq coll? seq (:html before)))]
       (is (= 2 (turn/turns-left @connection "run6-stall")))
       (is (nil? (turn/next-agent-work @connection {:seon.agent/id "run6-stall"})))
       (is (str/includes? (:header before) "stalled: :seon.ai/unparseable-body at "))
       (is (str/includes? (:header before) ", waiting for an outside wake"))
       (is (= "Session stalled" (:seon.render.transcript/label first-rule)))
       (is (= 1 (:seon.render.transcript/count first-rule)))
       (is (= "stalled" (:data-problem (second (first active-panels)))))
       (is (:db-after (db/transact! connection
                                   [[:db/add [:my.plan.item/id "run6-step"]
                                     :my.plan.item/completed-tx "datomic.tx"]])))
       (is (not (str/includes? (:header (snapshot)) "stalled:")))
       (is (:db-after (db/transact! connection
                                   [[:db/retract [:my.plan.item/id "run6-step"]
                                     :my.plan.item/completed-tx]
                                    [:db/add [:seon.turn/id "run6-refusal"] :seon.turn/reply ""]
                                    [:db/add [:seon.turn/id "run6-refusal"] :seon.turn/reply-size 0]])))
       (is (not (str/includes? (:header (snapshot)) "stalled:")))
       (is (zero? (:seon.render.transcript/count
                    (first (:seon.render.transcript/rules (:problems (snapshot)))))))))))
