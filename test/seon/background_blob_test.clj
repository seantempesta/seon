(ns seon.background-blob-test
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as datahike-api]
            [datahike.core :as datahike]
            [seon.blob :as blob]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.flow :as flow]
            [seon.test-support :as support])
  (:import [java.io ByteArrayOutputStream]
           [java.util Arrays]))

(def ^:private test-environment
  ;; The subset environment (store layer only) every crossing this
  ;; namespace constructs names; boot's own constructor, fewer layers.
  (delay (support/environment "seon.background-blob-test")))


(defn- with-file-effect-store
  [body]
  (let [root (io/file "tmp/background-effect-binary-test")]
    (when (.exists root)
      (support/delete-recursively! root))
    (let [opened (store/open-store!
                  {:seon.store/dir (str root "/store")})]
      (try
        ((ns-resolve 'seon.test-support 'populate-database!)
         (:seon.store/connection-object opened))
        (registry/branch! {:seon.store/store opened
                           :seon.cluster.registry/from :db
                           :seon.store/branch :background-effect-binary-test})
        (let [connection
              (store/open-branch! opened :background-effect-binary-test)]
          (try
            (body connection)
            (finally
              (datahike-api/release connection))))
        (finally
          (store/release-store! opened)
          (support/delete-recursively! root))))))

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

(deftest ^{:seon.test/long
           "Uses a real file store and work launcher to cover durable background binary receipts."}
  background-binary-results-remain-exact-across-the-inline-threshold
  (with-file-effect-store
    (fn [connection]
      (db/transact!
       connection
       [(assoc (config/defaults)
               :seon.config/cluster "default"
               :seon.config.eval.result/blob-threshold 8)
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
             {:seon.env/environment @test-environment
              ::flow/configuration
              {:seon.config.flow.compute/queue-depth 1
               :seon.config.flow.compute/concurrency 1
               :seon.config.flow.io/queue-depth 2
               :seon.config.flow.io/concurrency 2}})
            context
            {:seon.env/environment @test-environment
             :seon.db/connection connection
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
              (let [expected-ref
                    [:seon.effect/id
                     (pr-str ["binary-run" 0 ordinal])]
                    result-ref
                    (binding [effect/*request-context* context]
                      (effect/request!
                       #'binary-capability
                       {:seon.background-blob-test/size size}
                       {:seon.effect/background? true}))]
                (is (= expected-ref result-ref) (pr-str result-ref))
                (when (= expected-ref result-ref)
                  (let [effect-id (second expected-ref)]
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
                      (is (Arrays/equals expected actual))))))))
          (finally
            (datahike/unlisten! connection listener-key)
            (async/close! events)
            (flow/stop-work-launcher! launcher)))))))
