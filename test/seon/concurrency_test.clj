(ns seon.concurrency-test
  "Concurrent source turns through real per-agent graphs on two branches."
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.test-support :as support])
  (:import [java.util.concurrent CountDownLatch]))

(defn- await-closed! [connection run-id]
  (let [event (async/promise-chan)
        listener (random-uuid)
        closed (fn [database]
                 (:seon.cluster.run/closed-at
                  (db/pull database [:seon.cluster.run/closed-at]
                           [:seon.cluster.run/id run-id])))]
    (d/listen connection listener
              #(when (closed (:db-after %)) (async/offer! event true)))
    (try
      (when (closed @connection) (async/offer! event true))
      (support/await-event! event (str "closed source turn " run-id))
      (finally (d/unlisten connection listener)))))

(defn- create-agent! [instance agent-id]
  (let [handle (:seon.cluster.loop/cluster instance)
        connection (:seon.db/connection handle)]
    (schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       (let [created
             (cluster/ensure-entity!
              connection (:seon.cluster.run/process handle)
              {:seon.cluster.agent/id agent-id
               :seon.cluster/name (:seon.cluster/name handle)
               :seon.ns/name (symbol (str "my.agents.concurrency." agent-id))})]
         (when (:seon.error/kind created)
           (throw (ex-info "Agent creation refused" created)))
         (when-let [opening (:seon.cluster.run/id created)]
           (await-closed! connection opening)))))))

(defn- concurrent-wave! [subjects source-fn]
  (let [start (CountDownLatch. 1)
        began (System/nanoTime)
        submitted
        (mapv
         (fn [[instance agent-id]]
           (future
             (support/await-event! start "concurrent source submission release")
             (let [handle (:seon.cluster.loop/cluster instance)
                   connection (:seon.db/connection handle)
                   source (source-fn instance agent-id)
                   result (agent/submit-source!
                           {:seon.cluster.loop/cluster handle
                            :seon.cluster.agent/routing
                            (:seon.cluster.agent/routing instance)
                            :seon.cluster.agent/id agent-id
                            :seon.cluster.reply/text source})
                   run-id (:seon.cluster.run/id result)]
               (when-not run-id
                 (throw (ex-info "Concurrent source submission refused" result)))
               (schema/call-with-projection-state
                (:seon.sci.eval/projection-state handle)
                (fn []
                  (await-closed! connection run-id)
                  {::agent agent-id
                   ::cluster (:seon.cluster/name handle)
                   ::run run-id
                   ::evaluations
                   (db/q '[:find (pull ?e [*])
                           :in $ ?run-id
                           :where [?r :seon.cluster.run/id ?run-id]
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

(deftest two-clusters-run-concurrent-agent-turns
  (let [root (str "tmp/concurrency-test/" (random-uuid))]
    (support/populate-published-root! root)
    (let [a (cluster/start! {:seon.boot/root root
                             :seon.boot/cluster-name "concurrency-left"})]
      (try
        (let [b (cluster/start! {:seon.boot/root root
                                 :seon.boot/cluster-name "concurrency-right"})]
          (try
            (let [subjects (vec (for [instance [a b] id ["a" "b"]]
                                  [instance id]))]
              (is (not (identical? (:seon.sci.eval/ctx a) (:seon.sci.eval/ctx b))))
              (doseq [[instance id] subjects] (create-agent! instance id))
              (dotimes [ordinal 3]
                (let [wave (concurrent-wave!
                            subjects
                            (fn [instance id]
                              (pr-str [(:seon.cluster/name
                                        (:seon.cluster.loop/cluster instance))
                                       id ordinal])))]
                  (is (= 4 (count (::results wave))))
                  (doseq [result (::results wave)]
                    (let [evaluations (map first (::evaluations result))]
                      (is (= 1 (count evaluations)))
                      (is (= (pr-str [(::cluster result) (::agent result) ordinal])
                             (:seon.cluster.eval/result-edn (first evaluations))))
                      (is (not-any? :seon.cluster.eval/error evaluations)))))))
            (finally (cluster/stop! b))))
        (finally
          (cluster/stop! a)
          (support/delete-recursively! root))))))
