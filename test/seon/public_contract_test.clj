(ns seon.public-contract-test
  "The fresh source tree has no public function outside Malli collection."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]
            [seon.cluster :as cluster]
            [seon.cluster.store :as store]
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
