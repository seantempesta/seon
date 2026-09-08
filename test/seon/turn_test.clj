(ns seon.turn-test
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.flow :as flow]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(defn- evaluations [database agent-id]
  (db/q '[:find [?evaluation ...] :in $ ?id
          :where [?agent :seon.cluster.agent/id ?id]
          [?turn :seon.cluster.run/agent ?agent]
          [?evaluation :seon.cluster.eval/run ?turn]] database agent-id))

(deftest compaction-refuses-an-open-turn-at-the-writer
  (support/with-database
   (fn [connection]
     (db/transact!
      connection
      [{:seon.cluster.agent/id "busy"}
       {:seon.cluster.run/id "open"
        :seon.cluster.run/agent [:seon.cluster.agent/id "busy"]
        :seon.cluster.run/opened-at (java.util.Date.)}
       {:seon.cluster.eval/id "unfinished"
        :seon.cluster.eval/run [:seon.cluster.run/id "open"]
        :seon.cluster.eval/ordinal 0
        :seon.cluster.eval/source "(+ 1 1)"}])
     (let [before (db/basis-t @connection)
           result (turn/compact! {:seon.db/connection connection
                                  :seon.cluster.agent/id "busy"})]
       (is (some? (:seon.error/kind result)) (pr-str result))
       (is (= before (db/basis-t @connection)))
       (is (= 1 (count (evaluations @connection "busy"))))))))

(deftest virtual-turns-use-the-proc-and-compaction-is-agent-scoped
  (support/with-database
   (fn [connection]
     (db/transact!
      connection
      [{:seon.cluster/name "virtual-turns"}
       (:seon.config/desired-row
        (config/compile-manifest {:seon.boot/cluster-name "virtual-turns"
                                  :seon.config/manifest {}}))
       {:seon.cluster.agent/id "a"
        :seon.cluster.agent/namespace {:seon.ns/name 'my.agents.a}}
       {:seon.cluster.agent/id "b"
        :seon.cluster.agent/namespace {:seon.ns/name 'my.agents.b}}])
     (let [ctx (support/fork-cluster-ctx connection)
           environment (support/environment "virtual-turns" connection)
           launcher (flow/start-work-launcher!
                     {:seon.env/environment environment
                      :seon.flow/configuration
                      (select-keys (support/effective-config)
                                   flow/flow-workload-attributes)})
           routing (agent/routing)
           faults (async/chan (async/sliding-buffer 16))
           events (async/chan (async/sliding-buffer 1))
           handle (support/cluster-handle
                   {:seon.env/environment environment
                    :seon.db/connection connection
                    :seon.cluster/name "virtual-turns"
                    :seon.flow/work-launcher launcher
                    :seon.flow/executor
                    (cluster/projection-executor
                     (:seon.sci.eval/projection-state ctx))
                    :seon.sci.eval/ctx ctx
                    :seon.cluster.run/process cluster/boot-process-identity
                    :seon.cluster.loop/stream-channel
                    (async/chan (async/sliding-buffer 1))})
           submit (fn [id & [source]]
                    (let [source (or source "(+ 1 1)")
                          result (turn/virtual-turn!
                                  {:seon.cluster.loop/cluster handle
                                   :seon.cluster.agent/routing routing
                                   :seon.cluster.agent/id id
                                   :seon.cluster.reply/text source})
                          turn-id (:seon.cluster.run/id result)
                          closed? #(some? (:seon.cluster.run/closed-at
                                           (db/pull @connection
                                                    [:seon.cluster.run/closed-at]
                                                    [:seon.cluster.run/id turn-id])))]
                      (is (string? turn-id) (pr-str result))
                      (when-not (closed?)
                        (support/await-event! events ::closed (fn [_] (closed?))))
                      (is (closed?))
                      (is (empty?
                           (db/q '[:find [?attempt ...] :in $ ?id
                                   :where [?turn :seon.cluster.run/id ?id]
                                   [?attempt :seon.ai.attempt/run ?turn]]
                                 @connection turn-id)))
                      (is (= source
                             (:seon.cluster.run/reply
                              (db/pull @connection [:seon.cluster.run/reply]
                                       [:seon.cluster.run/id turn-id]))))
                      turn-id))]
       (swap! routing assoc :seon.cluster.agent/fault-channel faults)
       (d/listen connection ::turns #(async/offer! events %))
       (try
         (doseq [id ["a" "b"]]
           (agent/arm! {:seon.cluster.loop/cluster handle
                        :seon.cluster.agent/routing routing
                        :seon.cluster.agent/id id}))
         (submit "a")
         (submit "b")
         (let [a (evaluations @connection "a")
               b (evaluations @connection "b")]
           (is (= 1 (count a)))
           (is (= 1 (count b)))
           (is (= 2 (:seon.print/value
                     (edn/read-string
                      (:seon.cluster.eval/result-edn
                       (db/pull @connection [:seon.cluster.eval/result-edn]
                                (first a)))))))
           (is (nil? (:seon.error/kind
                      (turn/compact! {:seon.db/connection connection
                                      :seon.cluster.agent/id "a"}))))
           (is (empty? (evaluations @connection "a")))
           (is (= b (evaluations @connection "b")))
           (submit "a")
           (is (= 1 (count (evaluations @connection "a")))))
         (let [request {:seon.cluster.loop/cluster handle
                        :seon.cluster.agent/id "a"
                        :seon.turn/write? true}
               opening (turn/system-turn request)
               opening-sources (mapv :seon.cluster.eval/source
                                     (filter #(= :none (:seon.turn/status %))
                                             (:seon.turn/forms opening)))]
           (is (nil? (:seon.error/kind opening)) (pr-str opening))
           (is (seq opening-sources) (pr-str opening))
           (is (string? (:seon.cluster.run/id opening)))
           (let [basis (db/basis-t @connection)
                 unchanged (turn/system-turn request)]
             (is (seq (:seon.turn/forms unchanged)) (pr-str unchanged))
             (is (every? #(= :unchanged (:seon.turn/status %))
                         (:seon.turn/forms unchanged)))
             (is (nil? (:seon.cluster.run/id unchanged)))
             (is (= basis (db/basis-t @connection))))
           (turn/compact! {:seon.db/connection connection
                           :seon.cluster.agent/id "a"})
           (let [fresh (turn/system-turn request)]
             (is (= opening-sources
                    (mapv :seon.cluster.eval/source (:seon.turn/forms fresh))))
             (is (every? #(= :none (:seon.turn/status %))
                         (:seon.turn/forms fresh))))
            (submit "a" "(my.message/inbox {})")
           ;; Message facts are changed with both procs stopped. Only the
           ;; explicit system walk runs: this proof cannot call a provider.
           (doseq [id ["a" "b"]]
             (agent/disarm! {:seon.cluster.agent/routing routing
                             :seon.cluster.agent/id id}))
           (db/transact! connection
                         [{:seon.cluster.message/id "to-b"
                           :seon.cluster.message/to [:seon.cluster.agent/id "b"]
                           :seon.cluster.message/content "For B"
                           :seon.cluster.message/at (java.util.Date.)}])
           (let [other (turn/system-turn request)]
             (is (seq (:seon.turn/forms other)) (pr-str other))
             (is (every? #(= :unchanged (:seon.turn/status %))
                         (:seon.turn/forms other)))
             (is (nil? (:seon.cluster.run/id other))))
           (db/transact! connection
                         [{:seon.cluster.message/id "to-a"
                           :seon.cluster.message/to [:seon.cluster.agent/id "a"]
                           :seon.cluster.message/content "For A"
                           :seon.cluster.message/at (java.util.Date.)}])
           (let [basis (db/basis-t @connection)
                 preview (turn/system-turn (assoc request :seon.turn/write? false))
                 changed (filterv #(= :changed (:seon.turn/status %))
                                  (:seon.turn/forms preview))]
             (is (= basis (db/basis-t @connection)))
                (is (= ["(my.message/inbox {})"]
                    (mapv :seon.cluster.eval/source changed)) (pr-str preview))
             (is (seq (:seon.turn/changes (first changed))))
             (is (string? (:seon.turn/text (first changed))))
             (let [stored (turn/system-turn request)]
               (is (string? (:seon.cluster.run/id stored)) (pr-str stored))
               (is (= 1 (count (db/q '[:find [?e ...] :in $ ?id
                                      :where [?turn :seon.cluster.run/id ?id]
                                      [?e :seon.cluster.eval/run ?turn]]
                                    @connection (:seon.cluster.run/id stored))))))))
         (finally
           (doseq [id ["a" "b"]]
             (agent/disarm! {:seon.cluster.agent/routing routing
                             :seon.cluster.agent/id id}))
           (d/unlisten connection ::turns)
           (flow/stop-work-launcher! launcher)
           (doseq [channel [events faults
                            (:seon.cluster.wake/channel handle)
                            (:seon.render/context-channel handle)
                            (:seon.cluster.loop/completion handle)
                            (:seon.cluster.loop/stream-channel handle)]]
             (async/close! channel))))))))
