(require '[clojure.java.io :as io])

;; The cold root was built before the final empty-request correction. Reload
;; precisely that Clojure definition for the live algorithm measurement;
;; the edit under measurement still uses real publication and adoption.
(with-open [reader (java.io.PushbackReader.
                     (io/reader "/Users/sean/src/seon/src/seon/cluster.clj"))]
  (binding [*ns* (the-ns 'seon.cluster)]
    (loop []
      (let [form (read {:eof ::end} reader)]
        (cond
          (= ::end form) (throw (ex-info "Publication definition absent" {}))
          (and (seq? form) (= 'defn- (first form))
               (= 'full-source-refresh! (second form))) (eval form)
          :else (recur))))))

(fn [cluster-root cluster-name output]
  (let [instance (get @seon.operator.runtime/running-instances cluster-name)
        store (:seon.store/store instance)
        before (seon.cluster.source/current store)
        calls (atom [])
        original seon.cluster.source/path-digests
        start (System/nanoTime)
        result (with-redefs [seon.cluster.source/path-digests
                             (fn [directory paths]
                               (swap! calls conj paths)
                               (original directory paths))]
                 (seon.cluster/refresh-source! cluster-root [] cluster-name))
        report {:seon.source/elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)
                :seon.source/hash-requests @calls
                :seon.source/built? (:seon.source/built? result)
                :seon.source/head-unmoved? (= before (seon.cluster.source/current store))}]
    (spit output (pr-str report))
    report))
