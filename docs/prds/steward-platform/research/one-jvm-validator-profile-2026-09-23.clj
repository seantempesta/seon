(require '[clojure.java.io :as io])

(fn [root cluster-name output operation]
  (let [events (atom [])
        symbols '[seon.db/write-report-error seon.db/write-owned-values-error
                  seon.db/arity-mismatches-with seon.db/write-render-target-error
                  seon.db/write-deletion-error seon.db/retention-report-check
                  seon.fn/reconcile-tx-in seon.fn/compile-index-transaction
                  seon.fn/report-identities seon.schema/projection-from-database
                  seon.db/q seon.db/identity-attributes]
        wrappers (into {}
                       (map (fn [sym]
                              (let [v (find-var sym) original @v]
                                [v (fn [& arguments]
                                     (let [start (System/nanoTime)]
                                       (try (apply original arguments)
                                            (finally
                                              (swap! events conj
                                                     (cond-> {:seon.fn/sym sym
                                                               :seon.source/elapsed-ms
                                                               (/ (- (System/nanoTime) start) 1e6)}
                                                       (= sym 'seon.fn/reconcile-tx-in)
                                                       (assoc :seon.schema/carried? (boolean (seon.db/carried-projection (second arguments))))
                                                       (= sym 'seon.db/write-report-error)
                                                       (assoc :seon.db/attributes
                                                              (frequencies (map :a (:tx-data (second arguments))))
                                                              :seon.db/entities
                                                              (count (set (map :e (:tx-data (second arguments))))))))))))])))
                       symbols)
        measure (load-file (str (io/file "docs/prds/steward-platform/research/one-jvm-slice4-adoption-2026-09-22.clj")))
        result (with-redefs-fn wrappers #(measure root cluster-name operation output))]
    (spit (str output ".profile.edn") (pr-str @events))
    {:seon.source/elapsed-ms (reduce + (map :seon.source/elapsed-ms (:seon.source/progress result)))
     :seon.source/progress @events}))
