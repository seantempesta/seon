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

(defn- fault-evidence
  "The fault this committed fact names, with the occurrences carrying its
  evidence.

  THE FAULT ENTITY IS EVIDENCE-FREE: it aggregates one failure class and
  carries signature, id, kind, frame and exception class only. `data-edn`,
  `data-size` and `capped?` ride the OCCURRENCE, and the digest rides the blob
  row the occurrence refers to through `:seon.error.occurrence/data-blob`
  (`seon.error/commit-call`). The lookup is by signature, so a model change
  becomes a named refusal here instead of an arbitrary member of an unordered
  result silently handing `nil` to a blob read."
  [connection fact]
  (db/pull @connection
           [:seon.error/id
            :seon.error/signature
            {:seon.error/occurrences
             [:seon.error.occurrence/id
              :seon.error.occurrence/count
              :seon.error/data-edn
              :seon.error/data-size
              :seon.error/capped?
              {:seon.error.occurrence/data-blob [:seon.error/data-blob]}]}]
           [:seon.error/signature (:seon.error/signature fact)]))

(defn- occurrence-digest
  [occurrence]
  (get-in occurrence [:seon.error.occurrence/data-blob :seon.error/data-blob]))

(defn- published-blob-digests
  "Every blob row this branch holds, as the store-global deduplication fact."
  [connection]
  (db/q '[:find [?digest ...]
          :where [?blob :seon.error.occurrence/blob-digest ?digest]]
        @connection))

(deftest ^{:seon.test/fixture-observation "The test measures physical store growth and blob deduplication during a repeated oversized-fault storm."} oversized-fault-evidence-is-bounded-and-retrievable
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
            caps (config/result-caps config/defaults)
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
                                       source)))
            [fact outcome] (invoke!)]
        (testing "the occurrence keeps a bounded face and publishes the blob"
          (let [fault (fault-evidence connection fact)
                occurrences (:seon.error/occurrences fault)]
            (is (= :seon.flow/committed outcome))
            (is (= (:seon.error/id fact) (:seon.error/id fault))
                "the returned fact names the fault the signature reached")
            (is (= 1 (count occurrences))
                "one payload is one occurrence of its failure class")
            (let [occurrence (first occurrences)
                  digest (occurrence-digest occurrence)
                  inline (:seon.error/data-edn occurrence)]
              (is (string? digest)
                  "the occurrence refers to the published blob")
              (is (<= (count inline) 4096))
              (is (pos? (:seon.error/data-size occurrence)))
              (is (:seon.error/capped? occurrence)
                  "an inline face that omitted evidence says so")
              (is (not (str/includes? inline state-marker)))
              (let [evidence (when (string? digest)
                               (blob/get connection digest))]
                (is (string? evidence))
                (is (some? (edn/read-string evidence))
                    "the published evidence reads back as data")
                (is (str/includes? evidence diagnostic-payload)
                    "the complete diagnostic payload remains retrievable")
                (is (not (str/includes? evidence state-marker)))))))

        (testing "repeated identical faults aggregate on one bounded occurrence"
          (let [before baseline
                _ (invoke!)
                after-one (store-bytes directory)
                _ (dotimes [_ 498] (invoke!))
                after-500 (store-bytes directory)
                collected (registry/collect! opened (java.util.Date.))
                after-gc (store-bytes directory)
                fault (fault-evidence connection fact)
                occurrences (:seon.error/occurrences fault)
                occurrence (first occurrences)
                occurrence-count (:seon.error.occurrence/count occurrence)
                inline-max (count (:seon.error/data-edn occurrence))
                blob-digests (published-blob-digests connection)
                retained-digest (occurrence-digest occurrence)
                one-growth (- after-one before)
                storm-growth (- after-500 before)]
            (let [measurement {:fault-storage/bytes-before before
                               :fault-storage/bytes-after-one after-one
                               :fault-storage/bytes-after-500 after-500
                               :fault-storage/bytes-after-gc after-gc
                               :fault-storage/collected collected
                               :fault-storage/one-growth one-growth
                               :fault-storage/storm-growth storm-growth
                               :fault-storage/occurrences (count occurrences)
                               :fault-storage/occurrence-count occurrence-count
                               :fault-storage/max-inline inline-max}]
              (println (pr-str measurement))
              (spit "tmp/fault-storage-last-measurement.edn"
                    (pr-str measurement)))
            (is (= 1 (count occurrences))
                "500 identical faults are one failure class, one occurrence")
            (is (= 500 occurrence-count)
                "every fault of the class is counted on that occurrence")
            (is (<= inline-max 4096))
            (is (= 1 (count blob-digests))
                "500 identical payloads publish exactly one blob row")
            (is (string? (when (string? retained-digest)
                           (blob/get connection retained-digest)))
                "the referenced blob survives collection")
            ;; The measured growth is evidence for the owner to evaluate
            ;; against Datahike history/index copy-on-write; this test does not
            ;; pretend a fixed filesystem overhead is a correctness invariant.
            (is (pos? storm-growth)))))
      (finally
        (d/release connection)
        (store/release-store! opened)
        (support/delete-recursively! root)))))

(deftest ^{:seon.test/fixture-observation "Fault evidence must publish complete store-global blob content even when the displayed evidence is lossy."} lossy-subthreshold-fault-evidence-is-retrievable
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
            caps (config/result-caps config/defaults)
            commit-fault! (var-get (ns-resolve 'seon.cluster 'commit-fault!))
            source (lossy-fault-source)
            [fact outcome] (schema/call-with-projection
                            projection
                            #(commit-fault! connection cluster-name process caps
                                            source))
            fault (fault-evidence connection fact)
            occurrences (:seon.error/occurrences fault)
            occurrence (first occurrences)
            digest (occurrence-digest occurrence)
            evidence (when (string? digest) (blob/get connection digest))
            fitted (:seon.error/data-edn occurrence)
            full-evidence? (and (string? evidence)
                                (< (count evidence) 262144)
                                (str/includes? evidence
                                                (:fault-message (::flow/msg source))))]
        (is (= :seon.flow/committed outcome))
        (is (= (:seon.error/id fact) (:seon.error/id fault))
            "the returned fact names the fault the signature reached")
        (is (= 1 (count occurrences))
            "one payload is one occurrence of its failure class")
        (is (string? digest)
            "a lossy fitted face still publishes complete evidence")
        (is (string? fitted)
            "the occurrence carries the face the agent was shown")
        (is (not= evidence fitted))
        (is full-evidence?))
      (finally
        (d/release connection)
        (store/release-store! opened)
        (support/delete-recursively! root)))))
