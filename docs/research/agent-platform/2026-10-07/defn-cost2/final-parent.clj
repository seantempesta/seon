(do
 (require 'seon.pass-probe)
 (let [agent (str "defn-cost2-" (seon.id/id))
       _ ((#'seon.pass-probe/mcp-pass {"code" "(+ 1 2)" "agent" agent "cluster" "default" "namespace" "scratch.defn-cost2"}))
       world (assoc (#'seon.pass-probe/agent-world "default" agent)
                    :seon.pass-probe/stages
                    '#{seon.schema/load-projection seon.schema/projection-from-entities
                       seon.db/declaration-content-key seon.db/report-arity-comparison
                       seon.call-preparation/arity-facts seon.call-preparation/contract-arities
                       seon.test/run seon.test/select seon.test.accretion/generatable?
                       seon.fn/runtime-analysis-batch seon.fn.analyzer/invoke-kondo
                       seon.sci.eval/evaluate seon.sci.eval/install-function-contract!
                       seon.sci.eval/install-evaluated-rows! seon.turn.batch/settle
                       seon.turn/fault-window seon.turn/run-observation
                       seon.render.value/unit seon.schema.datahike/compiled-storage})
       recording (jdk.jfr.Recording.)]
   (try
     (.enable recording "jdk.ObjectAllocationSample")
     (.with (.enable recording "jdk.ObjectAllocationSample") "throttle" "20000/s")
     (.start recording)
     (let [rows (mapv (fn [i]
                        (seon.pass-probe/window world
                         (#'seon.pass-probe/mcp-pass
                          {"code" (#'seon.pass-probe/defn-text (str "f" i)) "agent" agent "cluster" "default" "namespace" "scratch.defn-cost2"})))
                      (range 5))]
       (.stop recording)
       (.dump recording (java.nio.file.Paths/get "/Users/sean/src/seon/tmp/defn-cost2/final-parent.jfr" (make-array String 0)))
       rows)
     (finally (.close recording)
              (some-> (:seon.pass-probe/host (#'seon.pass-probe/agent-world "default" agent)) seon.cluster.host/retire!)))))
