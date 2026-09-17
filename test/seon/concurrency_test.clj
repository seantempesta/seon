(ns seon.concurrency-test
  "Concurrent source turns through real per-agent graphs on two branches."
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.wake :as wake]
            [seon.db :as db]
            [seon.config :as config]
            [seon.flow :as flow]
            [seon.schema :as schema]
            [seon.test-support :as support]
            [seon.turn :as turn])
  (:import [java.util.concurrent CountDownLatch]))

(defn- with-message-route [connection body]
  (support/transacted! connection [{:seon.agent/id "recipient"}])
  (let [recipient (:db/id (db/pull @connection [:db/id]
                                 [:seon.agent/id "recipient"]))
        mailbox (async/chan (async/sliding-buffer 1))
        armer (async/chan (async/sliding-buffer 1))
        render (async/chan (async/sliding-buffer 1))
        faults (async/chan (async/sliding-buffer 1))
        listener-key (wake/route!
             {:seon.cluster.wake/connection connection
              :seon.cluster.wake/channels (constantly {recipient mailbox})
              :seon.cluster.wake/fenced? (fn [_ _] false)
              :seon.cluster.wake/armer-channel armer
              :seon.cluster.wake/render-channel render
              :seon.render.web/interest (atom :all)
              :seon.cluster.wake/fault-channel faults
              :seon.cluster.wake/key ::message-isolation})]
    (try
      (body mailbox)
      (is (nil? (async/poll! faults)) "the real listener reported no fault")
      (finally
        (wake/unlisten! {:seon.cluster.wake/connection connection
                        :seon.cluster.wake/key listener-key})
        (doseq [channel [mailbox armer render faults]] (async/close! channel))))))

(deftest messages-stay-on-their-connection
  (support/with-database
   (fn [left]
     (support/with-database
      (fn [right]
        (with-message-route
          left
          (fn [left-mailbox]
            (with-message-route
              right
              (fn [right-mailbox]
                (let [result
                      (db/transact!
                       right
                       [{:seon.message/id "connection-isolation" :seon.message/to [:seon.agent/id "recipient"] :seon.message/content "only the right recipient"}])]
                  (is (not (:seon.error/kind result)))
                  (is (some? (support/await-event! right-mailbox "right message wake")))
                  ;; Datahike has synchronously delivered the committed report.
                  ;; The positive right wake proves this was an observed event.
                  (is (nil? (async/poll! left-mailbox)))
                  (is (nil? (db/pull @left [:seon.message/id]
                                    [:seon.message/id "connection-isolation"])))))))))))))

(defn- await-closed! [connection run-id]
  (let [event (async/promise-chan)
        listener (random-uuid)
        closed (fn [database]
                 (:seon.turn/closed-tx
                  (db/pull database [:seon.turn/closed-tx]
                           [:seon.turn/id run-id])))]
    (d/listen connection listener
              #(when (closed (:db-after %)) (async/offer! event true)))
    (try
      (when (closed @connection) (async/offer! event true))
      (support/await-event! event (str "closed source turn " run-id))
      (finally (d/unlisten connection listener)))))

(defn- create-agent! [instance agent-id]
  (let [handle (:seon.turn.loop/cluster instance)
        connection (:seon.db/connection handle)]
    (schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       (let [created
             (db/transact! connection
              (agent/creation-tx
               {:seon.agent/id agent-id
               :seon.cluster/name (:seon.cluster/name handle)
               :seon.ns/name (symbol (str "my.agents.concurrency." agent-id))}))]
         (when (:seon.error/kind created)
           (throw (ex-info "Agent creation refused" created)))
         (agent/arm! {:seon.turn.loop/cluster handle
                      :seon.agent/routing
                      (:seon.agent/routing instance)
                      :seon.agent/id agent-id}))))))

(defn- concurrent-wave! [subjects source-fn]
  (let [start (CountDownLatch. 1)
        began (System/nanoTime)
        submitted
        (mapv
         (fn [[instance agent-id]]
           (future
             (support/await-event! start "concurrent source submission release")
             (let [handle (:seon.turn.loop/cluster instance)
                   connection (:seon.db/connection handle)
                   source (source-fn instance agent-id)
                   result (turn/virtual-turn!
                           {:seon.turn.loop/cluster handle
                            :seon.agent/routing
                            (:seon.agent/routing instance)
                            :seon.agent/id agent-id
                            :seon.cluster.reply/text source})
                   run-id (:seon.turn/id result)]
               (when-not run-id
                 (throw (ex-info "Concurrent source submission refused" result)))
               (schema/call-with-projection-state
                (:seon.sci.eval/projection-state handle)
                (fn []
                  (await-closed! connection run-id)
                  {::agent agent-id
                   ::cluster (:seon.cluster/name handle)
                   ::run run-id
                   ::turn
                   (db/pull @connection
                            [:seon.turn/opened-tx :seon.turn/closed-tx
                             :seon.turn/reply
                             {:seon.turn/agent [:seon.agent/id]}]
                            [:seon.turn/id run-id])
                   ::attempts
                   (db/q '[:find (count ?attempt) . :in $ ?id
                           :where [?turn :seon.turn/id ?id]
                           [?turn :seon.turn/attempts ?attempt]]
                         @connection run-id)
                   ::evaluations
                   (db/q '[:find (pull ?e [*])
                           :in $ ?run-id
                           :where [?r :seon.turn/id ?run-id]
                           [?e :seon.cluster.eval/run ?r]]
                         @connection run-id)})))))
         subjects)]
    (.countDown start)
    (try
      (let [results (mapv #(support/await-event! % "concurrent source turn") submitted)]
        {::results results
         ::turns-per-second (/ (* 1.0e9 (count results))
                               (- (System/nanoTime) began))})
      (finally
        (doseq [task submitted]
          (when-not (future-done? task) (future-cancel task)))))))

(defn- with-cluster [cluster-name body]
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection cluster-name)
     (config/apply! {:seon.db/connection connection
                    :seon.boot/cluster-name cluster-name})
     (let [ctx (support/fork-cluster-ctx connection)
           environment (support/environment cluster-name connection)
           launcher (flow/start-work-launcher!
                     {:seon.env/environment environment
                      :seon.flow/configuration
                      (select-keys (support/effective-config)
                                   flow/flow-workload-attributes)})
           routing (agent/routing)
           faults (async/chan (async/sliding-buffer 16))
           handle (support/cluster-handle
                   {:seon.env/environment environment
                    :seon.db/connection connection
                    :seon.cluster/name cluster-name
                    :seon.flow/work-launcher launcher
                    :seon.flow/executor
                    (cluster/projection-executor
                     (:seon.sci.eval/projection-state ctx))
                    :seon.sci.eval/ctx ctx
                    :seon.sci.eval/projection-state
                    (:seon.sci.eval/projection-state ctx)
                    :seon.db.process/id cluster/boot-process-identity
                    :seon.turn.loop/stream-channel
                    (async/chan (async/sliding-buffer 1))})]
       (swap! routing assoc :seon.agent/fault-channel faults)
       (try
         (body {:seon.turn.loop/cluster handle
                :seon.sci.eval/ctx ctx
                :seon.agent/routing routing})
         (finally
           (doseq [id (keys (:seon.agent/armed @routing))]
             (agent/disarm! {:seon.agent/routing routing
                            :seon.agent/id id}))
           (flow/stop-work-launcher! launcher)
           (doseq [channel [faults (:seon.cluster.wake/channel handle)
                            (:seon.render/context-channel handle)
                            (:seon.turn.loop/completion handle)
                            (:seon.turn.loop/stream-channel handle)]]
             (async/close! channel))))))))

(deftest two-clusters-run-concurrent-agent-turns
  (with-cluster "concurrency-left"
    (fn [a]
      (with-cluster "concurrency-right"
        (fn [b]
            (let [subjects (vec (for [instance [a b] id ["a" "b" "c"]]
                                  [instance id]))]
              (is (not (identical? (:seon.sci.eval/ctx a) (:seon.sci.eval/ctx b))))
              (doseq [[instance id] subjects] (create-agent! instance id))
              (concurrent-wave!
               subjects
               (fn [instance id]
                 (if (and (identical? instance a) (= "a" id))
                   "(def isolation-marker 739)"
                   "(+ 1 1)")))
              (let [wave (concurrent-wave!
                          subjects
                          (fn [_ _]
                            "(boolean (resolve 'my.agents.concurrency.a/isolation-marker))"))]
                (doseq [result (::results wave)]
                  (is (= (and (= "concurrency-left" (::cluster result))
                              (= "a" (::agent result)))
                         (:seon.print/value
                          (edn/read-string
                           (:seon.cluster.eval/result-edn
                            (ffirst (::evaluations result)))))))))
              (dotimes [ordinal 3]
                (let [wave (concurrent-wave!
                            subjects
                            (fn [instance id]
                              (pr-str (list 'str (str (:seon.cluster/name
                                            (:seon.turn.loop/cluster instance))
                                           "/" id "/" ordinal)))))]
                  (is (= 6 (count (::results wave))))
                  (doseq [result (::results wave)]
                    (let [evaluations (map first (::evaluations result))]
                      (is (zero? (or (::attempts result) 0)))
                      (is (some? (:seon.turn/closed-tx (::turn result))))
                      (is (some? (:seon.turn/opened-tx (::turn result))))
                      (is (string? (:seon.turn/reply (::turn result))))
                      (is (= (::agent result)
                             (get-in result [::turn :seon.turn/agent
                                             :seon.agent/id])))
                      (is (= 1 (count evaluations)))
                      (is (= (str (::cluster result) "/" (::agent result) "/" ordinal)
                             (:seon.print/value
                              (edn/read-string
                               (:seon.cluster.eval/result-edn (first evaluations))))))
                      (is (not-any? :seon.cluster.eval/error evaluations))))))))))))
