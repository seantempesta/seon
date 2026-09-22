(ns seon.operator.prepl
  "Core-only PREPL lifecycle handoff for the launched operator JVM."
  (:require [clojure.core.server :as server]))

(defn io-prepl
  "Print PREPL events, then perform a requested process exit after the flush."
  [& {:keys [cluster-name]}]
  (let [out *out*
        lock (Object.)]
    (server/prepl
     *in*
     (fn [event]
       (binding [*out* out *flush-on-newline* true *print-readably* true]
         (locking lock
           (prn
            (if (#{:ret :tap} (:tag event))
              (assoc event :val
                     (if-let [project (some-> (find-ns 'seon.cluster)
                                              (ns-resolve 'mcp-valf))]
                       (project cluster-name
                                (deref (ns-resolve 'seon.config 'defaults))
                                (:val event)
                                (true? (:exception event)))
                       (pr-str (:val event))))
              event))
           (flush)
           (when (and (= :ret (:tag event))
                      (:seon.operator/process-exit? (:val event)))
             (.halt (Runtime/getRuntime) 0))))))))
