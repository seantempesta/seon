(ns seon.run6-stall-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.agent]
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

(deftest a-stalled-session-header-names-its-provider-refusal
  (support/with-database
   (fn [connection]
     ;; The fixture's own cluster. Its turn bound is the agent's own override:
     ;; the world also carries boot's config entity, and the cluster-default read
     ;; does not select between config entities.
     (support/seed-cluster! connection "run6-stall" {:seon.config.ai/no-provider true})
     (is (:db-after
          (transact! connection
                     (support/agent-tx @connection "run6-stall" 'my.agents.run6-stall))))
     (is (= 3 (:seon.config.run/max-episode-runs
               (seon.agent/settings! {:seon.config.run/max-episode-runs 3} connection "run6-stall"))))
     (is (:db-after
          (transact! connection
                        (conj (turn/open-tx {:seon.turn/id "run6-refusal"
                                              :seon.turn/agent [:seon.agent/id "run6-stall"]
                                              :seon.turn/opened-tx "datomic.tx"
                                              :seon.turn.work/situation :call})
                              (assoc (error/normalize
                                      {:seon.schema/projection (db/carried-projection @connection)
                                       :seon.error/declared-schema :seon.ai/unparseable-body-error
                                       :seon.error/source {
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
                    :seon.render/profile (render/agent-render-profile (support/effective-config))}
           header #(pr-str (transcript/render-agent-header (assoc request :seon.db/db @connection)))]
       (is (= 2 (turn/turns-left @connection "run6-stall")))
       (is (nil? (turn/next-agent-work @connection {:seon.agent/id "run6-stall"})))
       (is (str/includes? (header) "stalled: seon.ai/complete at "))
       (is (str/includes? (header) ", waiting for an outside wake"))
       (is (:db-after (db/transact! connection
                                   [[:db/add [:seon.turn/id "run6-refusal"] :seon.turn/reply ""]
                                    [:db/add [:seon.turn/id "run6-refusal"] :seon.turn/reply-size 0]])))
       (is (not (str/includes? (header) "stalled:")))))))
