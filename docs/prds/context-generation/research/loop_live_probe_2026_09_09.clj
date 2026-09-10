(ns loop-live-probe-2026-09-09
  (:require [clojure.core.async :as async]
            [datahike.api :as d]
            [seon.ai :as ai]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.operator.runtime :as runtime]
            [seon.schema :as schema]
            [seon.turn :as turn]))

(defn wake!
  "Send one real message; observe closure or report the exact missing facts."
  [cluster-name message-id bound-ms]
  (let [instance (get @runtime/running-instances cluster-name)
        handle (:seon.turn.loop/cluster instance)
        connection (:seon.db/connection handle)]
    (schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       (let [events (async/chan (async/sliding-buffer 1))
             listener (Object.)
             started (System/nanoTime)
             deadline (async/timeout bound-ms)
             before @connection
             armed-before (keys (:seon.agent/armed @(:seon.agent/routing instance)))
             fault-ids #(set (db/q '[:find [?id ...] :where [?e :seon.error/id ?id]] %))]
         (assert (true? (:seon.config.ai/no-provider (ai/agent-overlay before "juniper"))))
         (assert (nil? (:seon.config.ai/no-provider (config/effective before cluster-name))))
         (d/listen connection listener (fn [_] (async/offer! events true)))
         (try
           (let [written (db/transact!
                          connection
                          [{:seon.message/id message-id
                            :seon.message/to [:seon.agent/id "juniper"]
                            :seon.message/inbox [:seon.agent/id "juniper"]
                            :seon.message/content (str "Observe " message-id)}])]
             (assert (:db-after written) (pr-str written))
             (let [basis (db/basis-t (:db-after written))
                   closed #(db/q '[:find ?id . :in $ ?basis :where
                                    [?a :seon.agent/id "juniper"]
                                    [?a :seon.agent/runtime ?runtime]
                                    [?runtime :seon.runtime/turns ?t]
                                    [?t :seon.turn/id ?id ?tx]
                                    [(>= ?tx ?basis)]
                                    [?t :seon.turn/reply ""]
                                    [?t :seon.turn/closed-tx]] % basis)]
               (loop []
                 (let [database @connection]
                   (if-let [id (closed database)]
                     (let [saved (filterv #(>= (:t %) basis)
                                          (evaluation/of-agent database "juniper"))
                           result
                           {:seon.probe/message message-id
                            :seon.probe/armed-before (vec armed-before)
                            :seon.probe/open-before (turn/open-for-agent before [:seon.agent/id "juniper"])
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

(defn withheld-permit!
  "On scratch only, verify a real open turn faults under its evaluation limit."
  [cluster-name limit-ms]
  (let [instance (get @runtime/running-instances cluster-name)
        handle (:seon.turn.loop/cluster instance)
        connection (:seon.db/connection handle)
        entry (agent/armed (:seon.agent/routing instance) "juniper")
        completion (:seon.turn.loop/completion entry)]
    (schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       (let [database @connection
             settings (:seon.agent/settings
                       (db/pull database '[{:seon.agent/settings [*]}] [:seon.agent/id "juniper"]))
             previous (:seon.config.eval/time-limit-ms settings)
             events (async/chan (async/sliding-buffer 1))
             listener (Object.)
             run-id (turn/next-id database cluster-name "juniper")
             [permit selected] (async/alts!! [completion (async/timeout previous)])]
         (assert (and (= completion selected) permit) "The idle permit did not arrive")
         (d/listen connection listener (fn [_] (async/offer! events true)))
         (try
           (assert (:db-after (db/transact! connection [[:db/add (:db/id settings) :seon.config.eval/time-limit-ms limit-ms]])))
           (let [opened (db/transact! connection
                                     (turn/open-tx {:seon.turn/id run-id
                                                    :seon.turn/agent [:seon.agent/id "juniper"]
                                                    :seon.turn/opened-tx "datomic.tx"
                                                    :seon.turn.work/situation :call}))
                 basis (db/basis-t (:db-after opened))
                 start (System/nanoTime)
                 deadline (async/timeout 5000)]
             (assert (:db-after opened) (pr-str opened))
             (async/offer! (:seon.cluster.wake/channel entry) :seon.agent/wake)
             (loop []
               (if-let [fault-id (db/q '[:find ?id . :in $ ?basis :where
                                         [?e :seon.error/id ?id ?tx] [(>= ?tx ?basis)]
                                         [?e :seon.error/kind :seon.agent/turn-completion-backstop]]
                                       @connection basis)]
                 {:seon.probe/elapsed-ms (/ (- (System/nanoTime) start) 1e6)
                  :seon.probe/evaluation-limit-ms limit-ms
                  :seon.turn/id run-id
                  :seon.probe/fault (db/pull @connection
                                            [:seon.error/id :seon.error/kind :seon.error/message :seon.error/at]
                                            [:seon.error/id fault-id])}
                 (do
                   (when (= deadline (second (async/alts!! [deadline events] :priority true)))
                     (throw (ex-info "The open turn did not publish its fault" {:seon.turn/id run-id})))
                   (recur)))))
           (finally
             (db/transact! connection [[:db/add (:db/id settings) :seon.config.eval/time-limit-ms previous]])
             (async/offer! completion :seon.agent/ready)
             (async/offer! (:seon.cluster.wake/channel entry) :seon.agent/wake)
             (d/unlisten connection listener)
             (async/close! events))))))))

(defn observe-message
  "Read terminal evidence for an already-sent message; never send a second one."
  [cluster-name message-id]
  (let [handle (:seon.turn.loop/cluster (get @runtime/running-instances cluster-name))]
    (schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       (let [database @(:seon.db/connection handle)
             basis (db/q '[:find ?tx . :in $ ?message
                           :where [?m :seon.message/id ?message ?tx]] database message-id)
             _ (assert basis "The probe message is absent")
             turn-id (ffirst (sort-by second (db/q '[:find ?id ?tx :in $ ?basis :where
                              [?a :seon.agent/id "juniper"] [?a :seon.agent/runtime ?r]
                              [?r :seon.runtime/turns ?t] [?t :seon.turn/id ?id ?tx]
                              [(>= ?tx ?basis)] [?t :seon.turn/reply ""]
                              [?t :seon.turn/closed-tx]] database basis)))
             _ (assert turn-id "The probe turn has not closed")
             row (db/pull database
                          '[:seon.turn/id {:seon.turn/opened-tx [:db/id :db/txInstant]}
                            {:seon.turn/closed-tx [:db/id :db/txInstant]}]
                          [:seon.turn/id turn-id])
             woke (:db/txInstant (db/pull database [:db/txInstant] basis))
             closed (get-in row [:seon.turn/closed-tx :db/txInstant])
             closed-basis (get-in row [:seon.turn/closed-tx :db/id])
             at-close (db/as-of database closed-basis)]
         {:seon.probe/message message-id :seon.probe/wake-tx basis
          :seon.probe/woke-at woke :seon.probe/turn row
          :seon.probe/wake-to-closed-ms (- (.getTime ^java.util.Date closed)
                                          (.getTime ^java.util.Date woke))
          :seon.probe/evaluations
          (mapv #(select-keys % [:seon.cluster.eval/id :seon.cluster.eval/source :seon.cluster.eval/error])
                (filter #(<= basis (:t %) closed-basis) (evaluation/of-agent database "juniper")))
          :seon.probe/turns-left (turn/turns-left at-close "juniper")
          :seon.probe/new-faults (db/q '[:find ?id :in $ ?basis :where
                                       [?e :seon.error/id ?id ?tx] [(>= ?tx ?basis)]] at-close basis)})))))
