(ns seon.store-growth-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.cluster.wake :as wake]
            [seon.db :as db]
            [seon.fs :as fs]
            [seon.test-support :as support]))

(defn- store-bytes
  {:malli/schema [:=> [:cat :seon.db/connection] [:int {:min 0}]]}
  [connection]
  (:seon.operator.footprint/file-bytes
   (fs/footprint (get-in @connection [:store :backing :base]))))

(deftest one-listened-write-produces-one-commit-and-no-fault-write
  (support/with-database
   (fn [connection]
     (support/transacted! connection [{:seon.agent/id "store-growth-agent"}])
     (let [recipient (d/q '[:find ?entity .
                            :where [?entity :seon.agent/id "store-growth-agent"]]
                          @connection)
           mailbox (async/chan (async/sliding-buffer 1))
           armer (async/chan (async/sliding-buffer 1))
           render (async/chan (async/sliding-buffer 1))
           faults (async/chan (async/sliding-buffer 1))
           listener-key :seon.agent/route
           before-t (db/basis-t (db/db connection))
           before-bytes (store-bytes connection)]
       (try
         (wake/route! {:seon.cluster.wake/connection connection
                       :seon.cluster.wake/channels (constantly {recipient mailbox})
                       :seon.cluster.wake/fenced? (fn [_ _] false)
                       :seon.cluster.wake/armer-channel armer
                       :seon.cluster.wake/render-channel render
                       :seon.render.web/interest (atom :all)
                       :seon.cluster.wake/fault-channel faults
                       :seon.cluster.wake/key listener-key})
         (support/transacted!
          connection
          [{:seon.message/id "store-growth-wake"
            :seon.message/to [:seon.agent/id "store-growth-agent"]
            :seon.message/content "Wake once"}])
         (let [after-t (db/basis-t (db/db connection))
               after-bytes (store-bytes connection)]
           (is (int? recipient))
           (is (= (inc before-t) after-t)
               "one listened write produces exactly its one declared commit")
           (is (<= before-bytes after-bytes))
           (is (= :seon.cluster.wake/wake
                  (support/await-event!
                   mailbox :mailbox-delivery (constantly true) 500)))
           (is (= :seon.cluster.wake/wake
                  (support/await-event!
                   render :render-delivery (constantly true) 500)))
           (is (nil? (some-> (async/poll! faults) ex-data)))
           (println {:seon.store-growth/before-bytes before-bytes
                     :seon.store-growth/after-bytes after-bytes
                     :seon.store-growth/delta-bytes (- after-bytes before-bytes)
                     :seon.store-growth/commits (- after-t before-t)}))
         (finally
           (wake/unlisten!
            {:seon.cluster.wake/connection connection
             :seon.cluster.wake/key listener-key})
           (doseq [channel [mailbox armer render faults]]
             (async/close! channel))))))))
