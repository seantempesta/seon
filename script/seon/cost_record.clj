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
      (doseq [text texts] (println text)))))
  (let [[texts] (operator/prepl-value! (operator/advertisement root "default")
                                     "(#'seon.mcp/eval-clj {:seon.mcp/code \"(let [sink (seon.batch/sink) digest (apply str (repeat 64 \\\"a\\\")) _ (doseq [i (range 700)] (let [c (.computeIfAbsent sink [(symbol \\\"cost.record\\\" (str \\\"f\\\" i)) digest] seon.profile/new-adder)] (.set ^java.util.concurrent.atomic.AtomicLongArray c 0 10) (.set ^java.util.concurrent.atomic.AtomicLongArray c 1 10) (.set ^java.util.concurrent.atomic.AtomicLongArray c 2 10))) _ (seon.profile/live-costs sink {} 100 20) m (seon.profile/measure #(seon.profile/live-costs sink {} 100 20))] {:costs (:seon.profile/value m) :constructed (get-in m [:seon.profile/called 'seon.profile/cost-entity] 0) :ms (:seon.profile/ms m) :alloc-bytes (:seon.profile/alloc-bytes m)})\" :seon.mcp/agent \"root\" :seon.mcp/cluster \"default\" :seon.mcp/mode \"jvm\"})" 30000)]
    (doseq [text texts] (println text))))
