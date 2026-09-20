(require '[clojure.edn :as edn] '[clojure.java.io :as io]
         '[clojure.string :as str] '[seon.cluster :as cluster]
         '[seon.cluster.source :as source] '[seon.db :as db]
         '[seon.operator.runtime :as runtime] '[seon.schema :as schema] '[seon.fs :as fs])

; Invoke through the scratch cluster's advertised prepl. The caller supplies
; the operator root, cluster, case (:unchanged-seal, :publication or :docstring), and output.
(fn [root cluster-name operation output]
  (let [cluster-root (str (io/file root "data" "clusters"))
        instance (get @runtime/running-instances cluster-name)
        store (:seon.store/store instance)
        before (source/current store)
        roots (into {} (for [namespace (all-ns)
                             candidate (vals (ns-interns namespace))
                             :when (and (bound? candidate)
                                        (:seon.instrument/var (meta @candidate)))]
                         [candidate @candidate]))
        phases (atom [])
        clock (volatile! [(System/nanoTime) "request"])
        progress (fn [next-phase]
                   (let [now (System/nanoTime)
                         [start phase] @clock
                         row {:seon.source/completed-phase phase
                              :seon.source/elapsed-ms (/ (- now start) 1000000.0)}]
                     (vreset! clock [now next-phase])
                     (swap! phases conj row)
                     (prn row) (flush)))
        result (binding [cluster/*source-progress!* progress]
                 (schema/call-with-projection-state
                  (get-in instance [:seon.sci.eval/ctx :seon.sci.eval/projection-state])
                  (fn []
                 (case operation
                   :unchanged-seal
                   (let [artifact (edn/read-string (slurp (cluster/source-artifact-file cluster-root)))]
                     (progress "unchanged seal")
                     (source/upsert!
                      {:seon.store/store store
                       :seon.source/expected-commit-id (:seon.source/commit-id before)
                       :seon.source/digest (:seon.source/digest artifact)
                       :seon.source/upsert-rows []
                       :seon.source/progress! progress
                       :seon.source/activation 'seon.cluster/derive-activation}))
                   :docstring
                   (let [file (io/file (fs/source-directory) "src/my/note.clj")
                         original (slurp file)
                         changed (str/replace-first original "Read my current notes in identity order."
                                                    "Read my current notes in identity order (slice 4 measurement).")]
                     (assert (not= original changed))
                     (try
                       (spit file changed)
                       (cluster/refresh-source! cluster-root ["src/my/note.clj"] cluster-name)
                       (finally (when (= changed (slurp file)) (spit file original)))))
                   :publication (cluster/refresh-source! cluster-root ["src/my/note.clj"] cluster-name)))))]
    (progress "complete")
    (let [evidence {:seon.source/before before
                    :seon.source/after (source/current store)
                    :seon.source/result (dissoc result :seon.program/unresolved-report)
                    :seon.source/progress @phases
                    :seon.reconcile/adopt-identities
                    (into #{} (keep (fn [[candidate before]]
                                      (when-not (identical? before @candidate)
                                        [:seon.fn/sym
                                         (symbol (str (ns-name (:ns (meta candidate))))
                                                 (str (:name (meta candidate))))]))) roots)}]
      (spit output (pr-str evidence))
      evidence)))
