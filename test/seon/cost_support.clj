(ns seon.cost-support
  "Reads a reply's whole request cost: the value bound under the handle the
  reply names (`:seon.repl/cost-result`) in the agent's context."
  (:require [seon.cluster.agent :as agent]
            [seon.mcp :as mcp]
            [seon.operator.runtime :as runtime]
            [seon.test-support :as support]))

(defn bound-cost
  "The whole request cost of the parsed MCP `reply` of `agent-id` on `running`,
  read from its result handle in the agent's context; no request is made."
  {:malli/schema [:=> [:cat :string :string :map] :map]}
  [running agent-id reply]
  (let [handle (some #(:seon.repl/cost-result (second %)) (#'support/mcp-parsed reply))
        found (#'mcp/instance @runtime/running-instances running)
        custody (#'mcp/execution found agent-id nil)
        ;; a JVM request of an agent whose graph has not started ran in the context its handle acquires
        ctx (or (get-in (agent/started (:seon.agent/routing custody) agent-id)
                        [:seon.turn.loop/cluster :seon.sci.eval/agent-ctx])
                (:seon.sci.eval/ctx (agent/acquire-context! (:seon.turn.loop/cluster custody) agent-id)))]
    (get @(:seon.sci.eval/result-objects ctx) (subs (name handle) 1))))
