(ns seon.public-contract-test
  "The fresh source tree has no public function outside Malli collection."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.store :as store]
            [seon.flow :as flow]
            [seon.fn.analyzer :as analyzer]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as sci.eval]))

(defn- public-defn?
  [definition]
  (and (= 'clojure.core/defn (::analyzer/defined-by definition))
       (not (::analyzer/private definition))))

(defn- identity-bearing?
  [definition]
  (and (symbol? (::analyzer/ns definition))
       (symbol? (::analyzer/name definition))
       (string? (::analyzer/filename definition))
       (pos-int? (::analyzer/row definition))))

(defn- analyzed-files
  [analysis]
  (into []
        (comp
         (map ::analyzer/filename)
         (remove nil?)
         (distinct))
        (concat (::analyzer/namespace-definitions analysis)
                (::analyzer/var-definitions analysis)
                (::analyzer/findings analysis))))

(defn- public-function-census!
  [paths]
  (let [analysis (analyzer/analyze {::analyzer/paths paths})
        subjects (filterv public-defn? (::analyzer/var-definitions analysis))
        evidence
        {:seon.public-contract/paths paths
         :seon.public-contract/analyzed-files (analyzed-files analysis)
         :seon.public-contract/findings (::analyzer/findings analysis)}]
    (when-not (seq subjects)
      (throw
       (ex-info "Public-contract analysis produced no public function subjects."
                (assoc evidence
                       :seon.error/kind
                       ::no-public-function-subjects))))
    (when-let [subject (first (remove identity-bearing? subjects))]
      (throw
       (ex-info "Public-contract analysis produced an unidentified subject."
                (assoc evidence
                       :seon.error/kind ::unidentified-public-function
                       :seon.public-contract/subject subject))))
    subjects))

(deftest every-fresh-public-function-has-a-complete-contract
  (let [subjects (public-function-census! ["src"])
        missing
        (into []
              (comp
               (filter #(nil? (get-in % [::analyzer/meta :malli/schema])))
               (map #(select-keys % [::analyzer/filename
                                     ::analyzer/row
                                     ::analyzer/ns
                                     ::analyzer/name])))
              subjects)]
    (is (seq subjects) "the production analyzer must find public functions")
    (is (every? identity-bearing? subjects)
        "every analyzed public function must retain source identity")
    (is (empty? missing) (pr-str missing))))

(deftest public-contract-census-refuses-an-absent-source-root
  (let [absent-root (io/file "tmp" "n2-public-contract-absent-source-root")
        _ (is (not (.exists absent-root))
              (str "counterexample root must be absent: " absent-root))
        failure
        (try
          (public-function-census! [(.getPath absent-root)])
          nil
          (catch clojure.lang.ExceptionInfo error error))]
    (is (= ::no-public-function-subjects
           (:seon.error/kind (ex-data failure))))
    (is (= [] (:seon.public-contract/analyzed-files (ex-data failure))))
    (is (= [(.getPath absent-root)]
           (:seon.public-contract/paths (ex-data failure))))))

(deftest opaque-predicate-contracts-construct-real-values
  (doseq [[predicate generator]
          [[cluster/socket-server? cluster/socket-server-generator]
           [store/connection? store/connection-generator]
           [store/connection-object? store/connection-generator]
           [store/file-lock? store/file-lock-generator]
           [store/database-value? store/database-value-generator]
           [admit/interrupt-fn? admit/interrupt-fn-generator]
           [sci.eval/ctx? sci.eval/ctx-generator]]]
    (is (true? (predicate (gen/generate generator)))
        (str predicate " rejected the value from its own generator"))))

(defn- release-sample!
  [sample]
  (cond
    (instance? java.util.concurrent.ExecutorService sample)
    (.shutdownNow ^java.util.concurrent.ExecutorService sample)

    (instance? java.net.ServerSocket sample)
    (.close ^java.net.ServerSocket sample)

    :else nil))

;;; The class: a generator backed by ONE shared process object is green
;;; for the wrong reason — it satisfies its predicate once and then
;;; hands every later check the sample a previous consumer already
;;; mutated or closed. A generator that CONSTRUCTS its value cannot be
;;; written that way. The SCI ctx and the render.web server/mult samples
;;; are still shared and tracked in
;;; docs/seon/issues/opaque-contract-generators-share-live-process-objects.md
(deftest lifecycle-generators-make-a-fresh-sample-each-generation
  (doseq [[label generator]
          [[:seon.flow/executor flow/executor-generator]
           [:seon.flow/atom-reference flow/atom-reference-generator]
           [:seon.flow/java-future flow/java-future-generator]
           [:seon.flow/proc-launcher flow/proc-launcher-generator]
           [:seon.flow/graph flow/graph-generator]
           [:seon.flow/channel flow/channel-generator]
           [:seon.cluster/socket-server cluster/socket-server-generator]]]
    (let [one (gen/generate generator)
          two (gen/generate generator)]
      (try
        (is (not (identical? one two))
            (str label " handed out the same sample twice"))
        (finally
          (release-sample! one)
          (release-sample! two))))))

(deftest an-invalidated-socket-sample-leaves-the-next-sample-usable
  (let [closed (gen/generate cluster/socket-server-generator)]
    (.close ^java.net.ServerSocket closed)
    (let [next-socket (gen/generate cluster/socket-server-generator)]
      (try
        (is (cluster/socket-server? next-socket))
        (is (not (.isClosed ^java.net.ServerSocket next-socket)))
        (finally
          (.close ^java.net.ServerSocket next-socket))))))

(deftest mutable-runtime-generators-do-not-reuse-released-samples
  (let [connection (gen/generate store/connection-generator)]
    (d/release connection)
    (let [next-connection (gen/generate store/connection-generator)]
      (try
        (is (store/connection? next-connection))
        (finally
          (d/release next-connection)))))
  (let [lock (gen/generate store/file-lock-generator)]
    (.release ^java.nio.channels.FileLock lock)
    (.close (.channel ^java.nio.channels.FileLock lock))
    (let [next-lock (gen/generate store/file-lock-generator)]
      (try
        (is (store/file-lock? next-lock))
        (finally
          (.release ^java.nio.channels.FileLock next-lock)
          (.close (.channel ^java.nio.channels.FileLock next-lock)))))))
