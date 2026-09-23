(ns seon.operator.prepl
  "Core-only PREPL lifecycle handoff for the launched operator JVM."
  (:require [clojure.core.server :as server]))

(defn io-prepl
  "Print PREPL events, retaining no evaluation result in *1/*2/*3/*e, then
  perform a requested process exit after the flush."
  [& {:keys [cluster-name]}]
  (let [out *out*
        lock (Object.)]
    (server/prepl
     *in*
     (fn [event]
       ;; Our functions name what they return (`seon.sci.admit/result-handle`,
       ;; blob digests), so no session keeps a result: `prepl` has just
       ;; `set!` *1/*2/*3 or *e on this thread (clojure/core/server.clj:236-238,
       ;; 251, 257) and these clear its `with-bindings` frame again.
       (when (= :ret (:tag event))
         (set! *1 nil) (set! *2 nil) (set! *3 nil) (set! *e nil))
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
