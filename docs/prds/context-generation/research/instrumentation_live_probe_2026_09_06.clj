; Evaluate this file through MCP JVM evaluation on the owned Juniper cluster.
; Read actual Var wrappers rather than trusting the registered schema count.
; The deliberately invalid function is a local value, never installed or stored.
(let [instance (get @seon.operator.runtime/running-instances "juniper-context")
      ctx (:seon.sci.eval/ctx instance)
      projection (seon.sci.kernel/context-projection ctx)
      caps (:seon.sci.admit/caps (:seon.cluster.loop/cluster instance))
      eligible (into #{}
                     (comp (mapcat ns-publics)
                           (map val)
                           (filter #(and (bound? %)
                                         (:malli/schema (meta %))
                                         (ifn? @%))))
                     (all-ns))
      wrapped (seon.instrument/instrumented)
      observe (fn [f argument]
                (try
                  {:seon.dev.probe/returned (f argument)}
                  (catch Throwable failure
                    (select-keys (ex-data failure)
                                 [:seon.error/kind :seon.error/message]))))
      invalid-output
      (seon.instrument/wrap-interpreted
       'seon.dev.probe/contract "[:=> [:cat :int] :int]"
       projection :panic caps (fn [_] "invalid output"))]
  {:seon.dev.probe/eligible (count eligible)
   :seon.dev.probe/wrapped (count wrapped)
   :seon.dev.probe/missing
   (mapv str (clojure.set/difference eligible wrapped))
   :seon.dev.probe/jvm-input
   (observe seon.cluster.agent/whoami :invalid)
   :seon.dev.probe/interpreted-input
   (observe invalid-output :invalid)
   :seon.dev.probe/interpreted-output
   (observe invalid-output 1)})
