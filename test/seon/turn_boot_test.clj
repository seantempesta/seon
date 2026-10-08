(ns seon.turn-boot-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [seon.cluster.wake :as wake]
            [seon.db :as db]
            [seon.test-support :as support]))

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
