(ns loop-live-probe-2026-09-09
  (:require [clojure.core.async :as async]
            [datahike.api :as d]
            [seon.ai :as ai]
            [seon.config :as config]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.operator.runtime :as runtime]
            [seon.schema :as schema]
            [seon.turn :as turn]))

(defn wake!
  "Send one real message; observe closure or report the exact missing facts."
  [cluster-name message-id bound-ms]
  (let [handle (:seon.turn.loop/cluster (get @runtime/running-instances cluster-name))
        connection (:seon.db/connection handle)]
    (schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       (let [events (async/chan (async/sliding-buffer 1))
             listener (Object.)
             started (System/nanoTime)
             deadline (async/timeout bound-ms)
             before @connection
             fault-ids #(set (db/q '[:find [?id ...] :where [?e :seon.error/id ?id]] %))]
         (assert (true? (:seon.config.ai/no-provider (ai/agent-overlay before "juniper"))))
         (assert (nil? (:seon.config.ai/no-provider (config/effective before cluster-name))))
         (d/listen connection listener (fn [_] (async/offer! events true)))
         (try
           (let [written (db/transact!
                          connection
                          [{:seon.cluster.message/id message-id
                            :seon.cluster.message/to [:seon.agent/id "juniper"]
                            :seon.cluster.message/content (str "Observe " message-id)
                            :seon.cluster.message/at (java.util.Date.)}])]
             (assert (:db-after written) (pr-str written))
             (let [basis (db/basis-t (:db-after written))
                   closed #(db/q '[:find ?id . :in $ ?basis :where
                                    [?a :seon.agent/id "juniper"]
                                    [?t :seon.turn/agent ?a]
                                    [?t :seon.turn/id ?id ?tx]
                                    [(>= ?tx ?basis)]
                                    [?t :seon.turn/reply ""]
                                    [?t :seon.turn/closed-at]] % basis)]
               (loop []
                 (let [database @connection]
                   (if-let [id (closed database)]
                     (let [saved (filterv #(>= (:t %) basis)
                                          (evaluation/of-agent database "juniper"))
                           result
                           {:seon.probe/message message-id
                            :seon.probe/elapsed-ms (/ (- (System/nanoTime) started) 1e6)
                            :seon.turn/id id
                            :seon.probe/turns-left-before (turn/turns-left (:db-after written) "juniper")
                            :seon.probe/turns-left-after (turn/turns-left database "juniper")
                            :seon.probe/evaluations (count saved)
                            :seon.probe/sources (mapv :seon.cluster.eval/source saved)
                            :seon.probe/evaluation-errors (vec (keep :seon.cluster.eval/error saved))
                            :seon.probe/new-faults (vec (remove (fault-ids before) (fault-ids database)))
                            :seon.probe/provider-attempts
                            (count (db/q '[:find ?e :where [?e :seon.ai.attempt/id]] database))
                            :seon.probe/unanswered (count (turn/unanswered-wakes database "juniper" {}))}]
                       (assert (= 20 (:seon.probe/turns-left-before result)) (pr-str result))
                       (assert (= 19 (:seon.probe/turns-left-after result)) (pr-str result))
                       (assert (pos? (count saved)) (pr-str result))
                       (assert (empty? (:seon.probe/new-faults result)) (pr-str result))
                       (assert (empty? (:seon.probe/evaluation-errors result)) (pr-str result))
                       (assert (zero? (:seon.probe/provider-attempts result)) (pr-str result))
                       result)
                     (let [[_ selected] (async/alts!! [events deadline] :priority true)]
                       (when (= selected deadline)
                         (throw (ex-info "Ordinary turn closure did not arrive."
                                         {:seon.probe/message message-id
                                          :seon.probe/bound-ms bound-ms
                                          :seon.probe/work (turn/next-agent-work database {:seon.agent/id "juniper"})
                                          :seon.probe/new-faults (vec (remove (fault-ids before) (fault-ids database)))})))
                       (recur)))))))
           (finally
             (d/unlisten connection listener)
             (async/close! events))))))))
