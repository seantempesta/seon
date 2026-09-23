(ns seon.turn-boot-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.wake :as wake]
            [seon.db :as db]
            [seon.error :as error]
            [seon.eval :as evaluation]
            [seon.fn :as function]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(deftest listened-datom-delivers-under-armed-contracts
  (support/with-database
   (fn [connection]
     (support/transacted! connection (support/agent-tx @connection "root"))
     (let [mailbox (async/chan (async/sliding-buffer 1))
           armer (async/chan (async/sliding-buffer 1))
           render (async/chan (async/sliding-buffer 1))
           faults (async/chan (async/sliding-buffer 1))
           recipient (:db/id (db/pull (db/db connection) [:db/id] [:seon.agent/id "root"]))
           listener-key :seon.agent/route]
       (try
         (wake/route! {:seon.cluster.wake/connection connection
                      :seon.cluster.wake/channels (constantly {recipient mailbox})
                      :seon.cluster.wake/fenced? (fn [_ _] false)
                      :seon.cluster.wake/armer-channel armer
                      :seon.cluster.wake/render-channel render
                      :seon.render.web/interest (atom :all)
                      :seon.cluster.wake/fault-channel faults
                      :seon.cluster.wake/key listener-key})
         (support/transacted! connection [{:seon.message/id "boot-wake"
                                           :seon.message/to [:seon.agent/id "root"]
                                           :seon.message/content "Wake root"}])
         (is (int? recipient))
         ;; Datahike settles the report before dispatching listeners.
         (is (= :seon.cluster.wake/wake
                (support/await-event! mailbox :mailbox-delivery (constantly true) 500)))
         (is (= :seon.cluster.wake/wake
                (support/await-event! render :render-delivery (constantly true) 500)))
         (is (nil? (some-> (async/poll! faults) ex-data)))
         (finally
           (wake/unlisten! {:seon.cluster.wake/connection connection :seon.cluster.wake/key listener-key})
           (doseq [channel [mailbox armer render faults]] (async/close! channel))))))))

(deftest root-opening-with-diagnostic-errors-does-not-park
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "boot-proof" {:seon.config.ai/no-provider true})
     (support/transacted! connection
                         (agent/creation-tx {:seon.agent/id "root"
                                             :seon.ns/name 'my.agents.root
                                             :seon.cluster/name "boot-proof"}))
     (let [ctx (support/fork-cluster-ctx connection)
           handle (support/cluster-handle
                   {:seon.env/environment (support/environment "boot-proof" connection)
                    :seon.db/connection connection :seon.cluster/name "boot-proof"
                    :seon.db.process/id cluster/boot-process-identity
                    :seon.sci.eval/ctx ctx})
           source "(+ 1 1)"
           refusal (function/analyze-forms
                    (db/db connection)
                    [{:seon.cluster.eval/source source
                      :seon.cluster.eval/ns [:seon.ns/name 'missing.namespace]}])]
       (try
         (is (true? (:seon.fn/namespace-unresolvable refusal)))
         (support/transacted!
          connection
          (error/commit-tx
           (db/db connection)
           (merge (select-keys handle [:seon.sci.admit/caps :seon.config.error/max-evidence-bytes
                                       :seon.config.error/recurrence-limit])
                  {:seon.error/source refusal :seon.error/id "boot-proof-error"
                   :seon.error/at #inst "2026-09-22T00:00:00Z"
                   :seon.error/process cluster/boot-process-identity :seon.agent/id "root"})))
         (support/transacted! connection [{:seon.message/id "opening-wake"
                                           :seon.message/to [:seon.agent/id "root"]
                                           :seon.message/content "Run the virtual opening"}])
         (with-open [executor-scope (support/closeable
                                    (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
                                    (fn [executor]
                                      (.shutdownNow ^java.util.concurrent.ExecutorService executor)
                                      (.close ^java.util.concurrent.ExecutorService executor)))]
          (async/offer! (:seon.turn.loop/completion handle) :seon.agent/ready)
          (let [[state report] (turn/step {:seon.turn.loop/cluster (assoc handle :seon.flow/executor @executor-scope)
                                           :seon.agent/id "root"}
                                          :seon.agent/episode :seon.agent/wake)
                pass (first (:clojure.core.async.flow/report report))
                entries (evaluation/of-agent (db/db connection) "root")]
           (is (nil? (:seon.turn.loop/parked state)))
           (is (nil? (:seon.turn.loop/refusal pass))
               (pr-str (select-keys (:seon.turn.loop/refusal pass)
                                   [:seon.error/message :seon.turn/generated-read-attributes])))
           (is (string? (:seon.turn/id pass)))
           (is (seq entries))
           (is (some #(seq (:seon.cluster.eval/read-evidence %)) entries))
           (is (some? (turn/open-for-agent (db/db connection) [:seon.agent/id "root"])))
           (is (empty? (filter #(seq (:seon.turn/generated-read-attributes
                                     (#'turn/generated-read-fault (db/db connection) % %))) entries)))))
         (catch clojure.lang.ExceptionInfo failure
           (println :opening-refusal (ex-data failure))
           (throw failure))
         (finally
           (doseq [channel-key [:seon.cluster.wake/channel :seon.render/context-channel
                        :seon.turn.loop/completion]]
             (async/close! (get handle channel-key)))))))))
