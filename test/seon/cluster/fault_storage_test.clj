(ns seon.cluster.fault-storage-test
  "The fault path keeps bulky evidence in the content-addressed store."
  (:require [clojure.core.async.flow :as-alias flow]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.blob :as blob]
            [seon.cluster :as cluster]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.test-support :as support])
  (:import [java.nio.file Files]))

(defn- store-bytes
  [directory]
  (with-open [paths (Files/walk (.toPath (io/file directory))
                               (make-array java.nio.file.FileVisitOption 0))]
    (reduce
     (fn [total path]
       (if (Files/isRegularFile path (make-array java.nio.file.LinkOption 0))
         (+ total (Files/size path))
         total))
     0
     (iterator-seq (.iterator paths)))))

(defn- source-store
  [root cluster-name]
  (let [directory (:seon.boot/store-dir
                   (cluster/resolve-bootstrap {:seon.boot/root root}))
        _ (support/populate-published-root! root)
        opened (store/open-store! {:seon.store/dir directory})
        source-branch :current-src]
    (registry/ensure-cluster!
     {:seon.store/store opened
      :seon.boot/cluster-name cluster-name
      :seon.source/commit-id
      (registry/branch-commit-id
       {:seon.store/store opened
        :seon.store/branch source-branch})})
    [directory opened]))

(def ^:private diagnostic-payload
  (apply str (repeat 10000 "diagnostic-content")))

(def ^:private state-marker
  "FLOW-STATE-MUTABLE-CACHE")

(defn- fault-source
  []
  {::flow/state {:mutable-cache (apply str (repeat 200000 state-marker))}
   ::flow/msg {:fault-message "diagnostic content"}
   ::flow/ex (ex-info "diagnostic content"
                      {:diagnostic-content diagnostic-payload})})

(defn- lossy-fault-source
  []
  {::flow/msg {:fault-message (apply str (repeat 2000 "diagnostic-content"))}
   ::flow/ex (ex-info "diagnostic content" {})})

(defn- fault-facts
  [connection]
  (mapv (fn [id]
          (db/pull @connection
                   [:seon.error/id :seon.error/data-edn
                    :seon.error/data-blob :seon.error/data-size]
                   [:seon.error/id id]))
        (db/q '[:find [?id ...]
                :where [?fault :seon.error/id ?id]]
              @connection)))

(deftest oversized-fault-evidence-is-bounded-and-retrievable
  (let [root (str "tmp/fault-storage-test/" (random-uuid))
        cluster-name (str "fault-storage-" (random-uuid))
        process (str "fault-storage-process-" (random-uuid))
        [directory opened] (source-store root cluster-name)
        branch (registry/cluster-branch cluster-name)
        connection (store/open-branch! opened branch)]
    (try
      (config/apply! {:seon.db/connection connection
                      :seon.boot/cluster-name cluster-name})
      (let [projection (schema/projection-from-database @connection)
            caps (config/result-caps (config/defaults))
            commit-fault! (var-get (ns-resolve 'seon.cluster 'commit-fault!))
            source (fault-source)
            ;; Establish the comparison point after the same collector policy
            ;; that the repeated-fault measurement uses.
            _ (registry/collect! opened (java.util.Date.))
            baseline (store-bytes directory)
            invoke! (fn []
                      (schema/call-with-projection
                       projection
                       #(commit-fault! connection cluster-name process caps
                                       source)))]
        (testing "the first oversized fault stores a bounded face and a blob"
          (let [[fact outcome] (invoke!)
                stored (first (fault-facts connection))
                digest (:seon.error/data-blob stored)
                evidence (blob/get connection digest)
                _ (edn/read-string evidence)]
            (is (= :seon.flow/committed outcome))
            (is (<= (count (:seon.error/data-edn stored)) 4096))
            (is (string? digest))
            (is (pos? (:seon.error/data-size stored)))
            (let [payload-present? (str/includes? evidence diagnostic-payload)]
              (is payload-present?
                  "the complete diagnostic payload remains retrievable"))
            (is (not (str/includes? (:seon.error/data-edn stored)
                                    state-marker)))
            (is (not (str/includes? evidence state-marker)))
            (is (= (:seon.error/id fact) (:seon.error/id stored)))))

        (testing "repeated identical faults remain measurable and bounded"
          (let [before baseline
                _ (invoke!)
                after-one (store-bytes directory)
                _ (dotimes [_ 498] (invoke!))
                after-500 (store-bytes directory)
                collected (registry/collect! opened (java.util.Date.))
                after-gc (store-bytes directory)
                facts (fault-facts connection)
                inline-max (apply max (map #(count (:seon.error/data-edn %))
                                           facts))
                blob-digests (set (map :seon.error/data-blob facts))
                retained-digest (:seon.error/data-blob (first facts))
                one-growth (- after-one before)
                storm-growth (- after-500 before)]
            (let [measurement {:fault-storage/bytes-before before
                               :fault-storage/bytes-after-one after-one
                               :fault-storage/bytes-after-500 after-500
                               :fault-storage/bytes-after-gc after-gc
                               :fault-storage/collected collected
                               :fault-storage/one-growth one-growth
                               :fault-storage/storm-growth storm-growth
                               :fault-storage/facts (count facts)
                               :fault-storage/max-inline inline-max}]
              (println (pr-str measurement))
              (spit "tmp/fault-storage-last-measurement.edn"
                    (pr-str measurement)))
            (is (= 500 (count facts)))
            (is (<= inline-max 4096))
            (is (= 1 (count blob-digests)))
            (is (string? (blob/get connection retained-digest)))
            ;; The measured growth is evidence for the owner to evaluate
            ;; against Datahike history/index copy-on-write; this test does not
            ;; pretend a fixed filesystem overhead is a correctness invariant.
            (is (pos? storm-growth)))))
      (finally
        (d/release connection)
        (store/release-store! opened)
        (support/delete-recursively! root)))))

(deftest lossy-subthreshold-fault-evidence-is-retrievable
  (let [root (str "tmp/fault-storage-test/" (random-uuid))
        cluster-name (str "fault-storage-lossy-" (random-uuid))
        process (str "fault-storage-process-" (random-uuid))
        [_directory opened] (source-store root cluster-name)
        branch (registry/cluster-branch cluster-name)
        connection (store/open-branch! opened branch)]
    (try
      (config/apply! {:seon.db/connection connection
                      :seon.boot/cluster-name cluster-name})
      (let [projection (schema/projection-from-database @connection)
            caps (config/result-caps (config/defaults))
            commit-fault! (var-get (ns-resolve 'seon.cluster 'commit-fault!))
            source (lossy-fault-source)
            [fact outcome] (schema/call-with-projection
                            projection
                            #(commit-fault! connection cluster-name process caps
                                            source))
            stored (first (fault-facts connection))
            digest (:seon.error/data-blob stored)
            evidence (blob/get connection digest)
            fitted (:seon.error/data-edn stored)
            full-evidence? (and (string? evidence)
                                (< (count evidence) 262144)
                                (str/includes? evidence
                                                (:fault-message (::flow/msg source))))]
        (is (= :seon.flow/committed outcome))
        (is (= (:seon.error/id fact) (:seon.error/id stored)))
        (is (string? digest)
            "a lossy fitted face still publishes complete evidence")
        (is (not= evidence fitted))
        (is full-evidence?))
      (finally
        (d/release connection)
        (store/release-store! opened)
        (support/delete-recursively! root)))))
