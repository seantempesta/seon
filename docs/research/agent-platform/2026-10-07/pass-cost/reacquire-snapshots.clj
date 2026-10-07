(let [agent-id (System/getProperty "seon.pass-cost.agent")
      w (#'seon.pass-probe/agent-world "default" agent-id)
      h (get-in w [:seon.pass-probe/host :seon.turn.loop/cluster])
      a (seon.cluster.agent/acquire-context! h agent-id)
      root (:seon.turn.loop/cluster (get @seon.operator.runtime/running-instances "default"))]
  (doseq [owner [root h a] k [:seon.sci.eval/ctx :seon.sci.eval/base-ctx]
          :let [ctx (get owner k) snapshot (some-> ctx :seon.sci.kernel/program-snapshot deref)]
          :when snapshot]
    (seon.sci.kernel/cache-program! ctx (:functions snapshot) (:namespaces snapshot)))
  :reacquired)
