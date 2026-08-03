(ns seon.background-blob-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [datahike.core :as datahike]
            [seon.blob :as blob]
            [seon.config :as config]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.flow :as flow]
            [seon.test-support :as support])
  (:import [java.io ByteArrayOutputStream]
           [java.util Arrays]))

(defn- invalid-utf8
  [size]
  (byte-array
   (map unchecked-byte
        (take size (cycle [195 40 255 128 0])))))

(defn- binary-handler
  {:malli/schema [:=> [:cat
                       [:map [:seon.background-blob-test/size :int]]
                       :seon.config/effective]
                  :seon.blob/octet-array]}
  [request _effective]
  (invalid-utf8 (:seon.background-blob-test/size request)))

(defn binary-capability
  {:malli/schema [:=> [:cat
                       [:map [:seon.background-blob-test/size :int]]]
                  :seon.blob/octet-array]}
  [request]
  request)

(defn- install-capability!
  [connection]
  (let [handler-meta (meta #'binary-handler)]
    (db/transact!
     connection
     [{:seon.schema/key :seon.background-blob-test/request
       :seon.schema/form
       (pr-str [:map [:seon.background-blob-test/size :int]])}
      {:seon.fn/sym "seon.background-blob-test/binary-capability"
       :seon.fn/spec
       (pr-str [:=> [:cat :seon.background-blob-test/request]
                :seon.blob/octet-array])
       :seon.fn/workload :io
       :seon.effect/capability
       (symbol (str (ns-name (:ns handler-meta)))
               (str (:name handler-meta)))}])))

(defn- exact-bytes
  [connection digest size chunk-size]
  (let [output (ByteArrayOutputStream.)]
    (loop [offset 0]
      (when (< offset size)
        (let [octets (blob/read-chunk connection digest offset chunk-size)]
          (.write output ^bytes octets)
          (recur (+ offset (alength ^bytes octets))))))
    (.toByteArray output)))

(deftest background-binary-results-remain-exact-across-the-inline-threshold
  (support/with-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.config/cluster "default"}
        {:seon.cluster.agent/id "binary-agent"}
        {:seon.cluster.run/id "binary-run"
         :seon.cluster.run/agent
         [:seon.cluster.agent/id "binary-agent"]}])
      (install-capability! connection)
      (let [threshold
            (db/q '[:find ?threshold .
                    :where
                    [_ :seon.config.eval.result/blob-threshold ?threshold]]
                  @connection)
            sizes [(dec threshold) (inc threshold)]
            events (async/chan 8)
            listener-key (random-uuid)
            _ (datahike/listen! connection listener-key
                                #(async/put! events %))
            launcher
            (flow/start-work-launcher!
             {::flow/configuration
              {:seon.config.flow.compute/queue-depth 1
               :seon.config.flow.compute/concurrency 1
               :seon.config.flow.io/queue-depth 2
               :seon.config.flow.io/concurrency 2}})
            context
            {:seon.store/branch-connection connection
             :seon.cluster.agent/id "binary-agent"
             :seon.cluster.run/id "binary-run"
             :seon.cluster.run.form/ordinal 0
             :seon.boot/cluster-name "default"
             :seon.flow/work-launcher launcher
             :seon.sci.admit/caps (config/result-caps (config/defaults))
             :seon.config/on-core-error :record
             :seon.effect/counter (atom -1)}]
        (try
          (doseq [[ordinal size] (map-indexed vector sizes)]
            (testing (str size " invalid UTF-8 bytes")
              (let [result-ref
                    (binding [effect/*context* context]
                      (effect/request!
                       #'binary-capability
                       {:seon.background-blob-test/size size}
                       {:seon.effect/background? true}))
                    effect-id (second result-ref)]
                (support/await-event!
                 events
                 [::settled ordinal]
                 #(:seon.effect/to
                   (db/pull (:db-after %)
                            [:seon.effect/to]
                            [:seon.effect/id effect-id])))
                (let [receipt
                      (db/pull @connection
                               [:seon.effect/result-blob
                                :seon.effect/result-size]
                               result-ref)
                      expected (invalid-utf8 size)
                      actual
                      (exact-bytes connection
                                   (:seon.effect/result-blob receipt)
                                   (:seon.effect/result-size receipt)
                                   7)]
                  (is (= size (:seon.effect/result-size receipt)))
                  (is (string? (:seon.effect/result-blob receipt)))
                  (is (Arrays/equals expected actual))))))
          (finally
            (datahike/unlisten! connection listener-key)
            (async/close! events)
            (flow/stop-work-launcher! launcher)))))))
