(ns seon.blob-threshold-test
  "The measured storage crossover behind the shipped blob threshold."
  (:require [clojure.test :refer [deftest is testing]]
            [org.replikativ.persistent-sorted-set.fressian :as pss-fress]
            [seon.blob :as blob]
            [seon.cluster.loop :as loop]
            [seon.config :as config]
            [seon.db :as db]
            [seon.test-support :as support])
  (:import [datahike.datom Datom]
           [org.replikativ.persistent_sorted_set ANode PersistentSortedSet]))

(def ^:private byte-array-class (class (byte-array 0)))

(declare raw-payload-bytes)

(defn- raw-payload-bytes
  [value]
  (cond
    (string? value) (alength (.getBytes ^String value "UTF-8"))
    (instance? byte-array-class value) (alength ^bytes value)
    (instance? Datom value) (raw-payload-bytes (.-v ^Datom value))
    (instance? PersistentSortedSet value)
    (reduce + 0 (map raw-payload-bytes (seq value)))
    (instance? ANode value) (raw-payload-bytes (pss-fress/node->map value))
    (map? value) (reduce + 0 (map raw-payload-bytes (vals value)))
    (coll? value) (reduce + 0 (map raw-payload-bytes value))
    :else 0))

(defn- memory-state-size
  [connection]
  (reduce-kv
   (fn [total _key [metadata value]]
     (+ total (raw-payload-bytes metadata) (raw-payload-bytes value)))
   0
   @(-> @connection :store :state)))

(defn- exact-result-edn
  [target-size marker]
  (let [prefix (str marker "-")
        content (str prefix
                     (apply str
                            (repeat (max 0 (- target-size 2 (count prefix)))
                                    \r)))
        serialized (pr-str content)]
    (assert (= target-size (count serialized)))
    serialized))

(def ^:private measured-file-boundary
  [{:seon.blob-threshold/characters 343
    :seon.blob-threshold/inline-growth 6157
    :seon.blob-threshold/blob-growth 6159}
   {:seon.blob-threshold/characters 344
    :seon.blob-threshold/inline-growth 6163
    :seon.blob-threshold/blob-growth 6160}])

(defn- derived-threshold
  [measurements]
  (->> measurements
       (filter
        (fn [{inline :seon.blob-threshold/inline-growth
              blob :seon.blob-threshold/blob-growth}]
          (<= inline blob)))
       (map :seon.blob-threshold/characters)
       (apply max)))

(defn- measured-memory-cell
  [threshold marker]
  (support/with-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.config.eval.result/blob-threshold (long threshold)
         :seon.render.value/max-collection 8}])
      (let [result-edn (exact-result-edn 4096 marker)
            before (memory-state-size connection)
            settlement
            (#'loop/settlement-result
             {:seon.store/branch-connection connection
              :seon.sci.admit/caps (config/result-caps (config/defaults))}
             {:seon.cluster.eval/result-edn result-edn})]
        (db/transact!
         connection
         [(assoc
           (select-keys
            settlement
            [:seon.cluster.eval/result-edn
             :seon.cluster.eval/result-blob
             :seon.cluster.eval/result-size])
           :seon.cluster.eval/id (str "blob-threshold-" marker))])
        {:seon.blob-threshold/growth (- (memory-state-size connection) before)
         :seon.blob-threshold/settlement settlement
         :seon.blob-threshold/result-edn result-edn
         :seon.blob-threshold/restored
         (when-let [digest (:seon.cluster.eval/result-blob settlement)]
           (blob/get connection digest))}))))

(deftest shipped-threshold-follows-the-measured-store-crossover
  (let [default-threshold
        (:seon.config.eval.result/blob-threshold (config/defaults))]
    (is (= 343 (derived-threshold measured-file-boundary)))
    (is (= (derived-threshold measured-file-boundary) default-threshold))
    (support/with-database
      (fn [connection]
        (db/transact!
         connection
         [{:seon.config.eval.result/blob-threshold default-threshold}
          {:seon.render.value/max-collection 8}])
        (let [caps (config/result-caps (config/defaults))
              inline
              (#'loop/settlement-result
               {:seon.store/branch-connection connection
                :seon.sci.admit/caps caps}
               {:seon.cluster.eval/result-edn (exact-result-edn 343 "a")})
              blobbed
              (#'loop/settlement-result
               {:seon.store/branch-connection connection
                :seon.sci.admit/caps caps}
               {:seon.cluster.eval/result-edn (exact-result-edn 344 "b")})]
          (is (contains? inline :seon.cluster.eval/result-edn))
          (is (not (contains? inline :seon.cluster.eval/result-blob)))
          (is (contains? blobbed :seon.cluster.eval/result-blob))
          (is (not= (exact-result-edn 344 "b")
                    (:seon.cluster.eval/result-edn blobbed))))))))

(deftest current-default-reverses-the-4096-character-wrong-decision
  (let [inline (measured-memory-cell Long/MAX_VALUE "inline")
        blobbed (measured-memory-cell 343 "blob")]
    (testing "the cheap in-memory model reproduces the physical ordering"
      (is (< (:seon.blob-threshold/growth blobbed)
             (:seon.blob-threshold/growth inline))))
    (testing "the production splitter preserves the full value through the blob"
      (let [settlement (:seon.blob-threshold/settlement blobbed)]
        (is (= 4096 (:seon.cluster.eval/result-size settlement)))
        (is (= (:seon.blob-threshold/result-edn blobbed)
               (:seon.blob-threshold/restored blobbed)))))))
