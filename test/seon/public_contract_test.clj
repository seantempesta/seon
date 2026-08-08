(ns seon.public-contract-test
  "The fresh source tree has no public function outside Malli collection."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.store :as store]
            [seon.flow :as flow]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as sci.eval]))

(def ^:private reader-options
  {:read-cond :allow
   :features #{:clj}
   :eof ::eof})

(defn- source-file?
  [file]
  (and (.isFile file)
       (or (.endsWith (.getName file) ".clj")
           (.endsWith (.getName file) ".cljc"))))

(defn- require-aliases
  [ns-form]
  (into {}
        (comp
         (filter #(and (seq? %) (= :require (first %))))
         (mapcat rest)
         (filter vector?)
         (keep
          (fn [[library & options]]
            (let [options (apply hash-map options)
                  alias (or (:as options) (:as-alias options))]
              (when alias [alias library])))))
        (drop 2 ns-form)))

(defn- defn-contract
  [form]
  (when (and (seq? form) (= 'defn (first form)))
    (let [function-name (second form)
          body (drop 2 form)
          body (if (string? (first body)) (next body) body)
          attributes (when (map? (first body)) (first body))
          metadata (merge (meta function-name) attributes)]
      (when-not (:private metadata)
        {:seon.public-contract/name function-name
         :seon.public-contract/schema (:malli/schema metadata)}))))

(defn- file-contracts
  [file]
  (with-open [file-reader (io/reader file)]
    (let [pushback (reader-types/indexing-push-back-reader file-reader)
          ns-form (reader/read reader-options pushback)
          namespace-name (second ns-form)
          aliases (require-aliases ns-form)
          parsing-ns (or (find-ns namespace-name)
                         (create-ns namespace-name))]
      (binding [*ns* parsing-ns
                reader/*alias-map* aliases]
        (loop [contracts []]
          (let [form (reader/read reader-options pushback)]
            (if (= ::eof form)
              contracts
              (recur
               (cond-> contracts
                 (defn-contract form)
                 (conj
                  (assoc (defn-contract form)
                         :seon.public-contract/file (.getPath file))))))))))))

(deftest every-fresh-public-function-has-a-complete-contract
  (let [missing
        (into []
              (comp
               (filter source-file?)
               (mapcat file-contracts)
               (filter #(nil? (:seon.public-contract/schema %))))
              (file-seq (io/file "src")))]
    (is (empty? missing) (pr-str missing))))

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
