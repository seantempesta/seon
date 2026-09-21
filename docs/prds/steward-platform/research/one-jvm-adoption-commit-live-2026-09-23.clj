(require '[clojure.java.io :as io])

;; Reload the two edited Clojure definitions in the existing own-root host.
;; All measured file publication/adoption operations remain real operations.
(with-open [reader (java.io.PushbackReader.
                     (io/reader "/Users/sean/src/seon/src/seon/cluster.clj"))]
  (binding [*ns* (the-ns 'seon.cluster)]
    (loop [remaining #{'full-source-refresh! 'development-source-refresh!}]
      (when (seq remaining)
        (let [form (read {:eof ::end} reader)]
          (when (= ::end form)
            (throw (ex-info "Publication definitions absent" {:seon.source/missing remaining})))
          (if (and (seq? form) (remaining (second form)))
            (do (eval form) (recur (disj remaining (second form))))
            (recur remaining)))))))

(fn [root cluster-name operation output]
  (let [calls (atom 0)
        snapshot seon.cluster.source/snapshot
        result (with-redefs [seon.cluster.source/snapshot
                            (fn [request]
                              (swap! calls inc)
                              (snapshot request))]
                 ((load-file "/Users/sean/src/seon/docs/prds/steward-platform/research/one-jvm-slice4-adoption-2026-09-22.clj")
                  root cluster-name operation output))]
    (assert (zero? @calls) "A changed-path publication/adoption walked the whole source tree.")
    (let [report (assoc result :seon.source/snapshot-calls @calls)]
      (spit output (pr-str report))
      report)))
