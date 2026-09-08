;;; Probe: the gate's armed set covers a BOOTED CLUSTER's instrumented set.
;;; Run inside a live cluster JVM (through the operator prepl / eval_clj).
(let [instrumented (seon.instrument/instrumented)
      program (into [] (map (comp symbol str))
                    ((requiring-resolve 'seon.test.runner/declared-program-namespaces)))]
  {:cluster-instrumented (count instrumented)
   :cluster-loaded-namespaces (count (all-ns))
   :program-namespaces (count program)
   :cluster-program-instrumented
   (count (filter #(contains? (set program)
                              (ns-name (:ns (meta %))))
                  instrumented))
   :armable-in-program (count (seon.instrument/armable program))
   :armable-not-armed
   (sort (map #(symbol (str (ns-name (:ns (meta %)))) (str (:name (meta %))))
              (clojure.set/difference (seon.instrument/armable program)
                                      instrumented)))})
