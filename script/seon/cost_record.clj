#!/usr/bin/env bb
(ns seon.cost-record
  "Measure a repeated MCP request on the named running root."
  (:require [seon.operator :as operator]))

(defn -main
  "Print two measurements through the running root's MCP value printer."
  {:malli/schema [:=> [:cat :string] :nil]}
  [root]
  (let [advertisement (operator/advertisement root "default")
      form "(#'seon.mcp/eval-clj {:seon.mcp/code \"(let [m (seon.profile/measure #(#'seon.mcp/eval-clj {:seon.mcp/code \\\"(+ 1 2)\\\" :seon.mcp/agent \\\"cost-probe\\\" :seon.mcp/cluster \\\"default\\\" :seon.mcp/mode \\\"sci\\\"}))] (assoc (select-keys m [:seon.profile/ms :seon.profile/alloc-bytes :seon.profile/reads]) :calls (select-keys (:seon.profile/called m) ['seon.db/q 'seon.db/pull 'seon.config/effective 'seon.turn/agent-dial 'seon.profile/cost-entity]) :reply (:seon.profile/value m)))\" :seon.mcp/agent \"root\" :seon.mcp/cluster \"default\" :seon.mcp/mode \"jvm\"})"]
  (dotimes [_ 2]
    (let [[texts] (operator/prepl-value! advertisement form 30000)]
      (doseq [text texts] (println text))))))
