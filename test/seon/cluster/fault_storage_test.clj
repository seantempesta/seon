(ns seon.cluster.fault-storage-test
  "The fault path stores bounded evidence and never a second copy of the value."
 (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.blob :as blob]
            [seon.cluster :as cluster]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.fault :as fault]
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

(defn- fault-source
  "A bulky failure: its ex-data carries the diagnostic payload."
  []
  (ex-info "diagnostic content" {:diagnostic-content diagnostic-payload}))

(defn- lossy-fault-source
  "An error base whose data is over the fact's inline bound but under the blob
  threshold, so the fact's face is lossy."
  []
  {:seon.error/at (java.util.Date.)
   :seon.error/layer ::storm
   :seon.error/operation `lossy-fault-source
   :seon.error/message "diagnostic content"
   :seon.error/data {:fault-message (apply str (repeat 2000 "diagnostic-content"))}})

(defn- fault-world
  "The environment a boundary holds for `cluster-name` on `connection`, with
  root, the shipped escalation owner, seeded as boot does."
  [connection cluster-name]
  (cluster/seed-root-agent! connection cluster-name cluster/boot-process-identity)
  (env/environment {:seon.boot/cluster-name cluster-name
                    :seon.db/connection connection
                    :seon.sci.admit/caps (config/result-caps
                                          (config/effective (db/db connection) cluster-name))}))

(defn- fault-evidence
  "The fault this committed fact names, with the occurrences carrying its
  evidence.

  THE FAULT ENTITY IS EVIDENCE-FREE: it aggregates one failure class and
  carries signature, id, kind, frame and exception class only. `data-edn`,
  `data-size` and `capped?` ride the OCCURRENCE (`seon.error/commit-call`). The
  lookup is by signature, so a model change becomes a named refusal here
  instead of an arbitrary member of an unordered result."
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
              :seon.error/shown-blob]}]
           [:seon.error/signature (:seon.error/signature fact)]))

(defn- blob-digests
  "Every blob digest an error occurrence of this branch names."
  [connection]
  (db/q '[:find [?digest ...]
          :where [_ :seon.error/shown-blob ?digest]]
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
      ;; The one core-fault route (`seon.fault/fault!`; the cluster's own
      ;; committer was deleted in 5d8e803f7).
      (let [world (fault-world connection cluster-name)
            source (fault-source)
            ;; Establish the comparison point after the same collector policy
            ;; that the repeated-fault measurement uses.
            _ (registry/collect! opened (java.util.Date.))
            baseline (store-bytes directory)
            invoke! #(fault/fault! world source {:seon.error/layer ::storm
                                                 :seon.error/operation `oversized-fault-evidence-is-bounded-and-retrievable
                                                 :seon.db.process/id process})
            fact (invoke!)]
        (testing "the occurrence keeps a bounded face and no copy of the value"
          (let [fault (fault-evidence connection fact)
                occurrences (:seon.error/occurrences fault)]
            (is (true? (:seon.fault/committed? fact)))
            (is (= (:seon.error/signature fact) (:seon.error/signature fault))
                "the receipt names the fault the signature reached")
            (is (= 1 (count occurrences))
                "one payload is one occurrence of its failure class")
            (let [occurrence (first occurrences)
                  inline (:seon.error/data-edn occurrence)]
              (is (<= (count inline) 4096))
              (is (pos? (:seon.error/data-size occurrence)))
              (is (:seon.error/capped? occurrence)
                  "an inline face that omitted evidence says so")
              (is (not (str/includes? inline diagnostic-payload))
                  "the inline face omits the bulky payload"))))

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
            (is (empty? (filter #(nil? (blob/get connection %)) (blob-digests connection)))
                "every blob a fact names survives collection")
            ;; The measured growth is evidence for the owner to evaluate
            ;; against Datahike history/index copy-on-write; this test does not
            ;; pretend a fixed filesystem overhead is a correctness invariant.
            (is (pos? storm-growth)))))
      (finally
        (d/release connection)
        (store/release-store! opened)
        (support/delete-recursively! root)))))

(deftest ^{:seon.test/fixture-observation "A lossy fitted face of fault evidence says it omitted evidence."} lossy-subthreshold-fault-evidence-says-it-is-capped
  (let [root (str "tmp/fault-storage-test/" (random-uuid))
        cluster-name (str "fault-storage-lossy-" (random-uuid))
        process (str "fault-storage-process-" (random-uuid))
        [_directory opened] (source-store root cluster-name)
        branch (registry/cluster-branch cluster-name)
        connection (store/open-branch! opened branch)]
    (try
      (config/apply! {:seon.db/connection connection
                      :seon.boot/cluster-name cluster-name})
      (let [source (lossy-fault-source)
            fact (fault/fault! (fault-world connection cluster-name) source
                               {:seon.error/layer ::storm
                                :seon.error/operation `lossy-subthreshold-fault-evidence-says-it-is-capped
                                :seon.db.process/id process})
            fault (fault-evidence connection fact)
            occurrences (:seon.error/occurrences fault)
            occurrence (first occurrences)
            fitted (:seon.error/data-edn occurrence)]
        (is (true? (:seon.fault/committed? fact)))
        (is (= (:seon.error/signature fact) (:seon.error/signature fault))
            "the receipt names the fault the signature reached")
        (is (= 1 (count occurrences))
            "one payload is one occurrence of its failure class")
        (is (string? fitted)
            "the occurrence carries the face the agent was shown")
        (is (true? (:seon.error/capped? occurrence))
            "a lossy fitted face says it omitted evidence")
        (is (not (str/includes? fitted (get-in source [:seon.error/data :fault-message])))))
      (finally
        (d/release connection)
        (store/release-store! opened)
        (support/delete-recursively! root)))))
