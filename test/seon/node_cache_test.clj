(ns seon.node-cache-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.mcp :as mcp]
            [seon.render.value :as value]
            [seon.test-support :as support]))

(deftest ^{:seon/tasks #{'seon.cluster.store/the-node-cache-counts-nodes-not-bytes}}
  a-warm-redefinition-loads-only-changed-index-paths
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [call (fn [code]
                  (json/read-str (mcp/call (json/write-str
                                            {"name" "eval_clj" "arguments"
                                             {"cluster" running "agent" agent-id "mode" "sci" "code" code}}))))
           storage (:storage (:store (db/db connection)))
           first-reply (call "(in-ns 'node.cache.budget) (defn work {:malli/schema [:=> [:cat :int] :int]} [n] (reduce + (range n))) (dotimes [_ 40] (work 100))")
           warm-reply (call "(defn work {:malli/schema [:=> [:cat :int] :int]} [n] (+ 1 -1 (reduce + (range n)))) (dotimes [_ 40] (work 100))")
           before (:reads @(:stats storage))
           reply (call "(defn work {:malli/schema [:=> [:cat :int] :int]} [n] (+ 2 -2 (reduce + (range n)))) (dotimes [_ 40] (work 100))")
           reads (- (:reads @(:stats storage)) before)
           cost (fn [reply]
                  (let [c (support/mcp-request reply)]
                    (assoc (select-keys c [:seon.cost/step :seon.cost/entrance :seon.cost/writer :seon.cost/commit :seon.cost/reads])
                           :seon.cost/stages (select-keys (:seon.cost/stages c) [:custody :test-step]))))]
         (when-let [path (System/getenv "SEON_NODE_CACHE_EVIDENCE")]
           (spit path (value/core-text
                        {:first (cost first-reply)
                         :warm (cost warm-reply)
                         :redefinition (cost reply)
                         :node-loads reads
                         :cache (let [cache (:cache storage)]
                                  (if (instance? com.github.benmanes.caffeine.cache.Cache cache)
                                    (let [cache ^com.github.benmanes.caffeine.cache.Cache cache
                                          _ (.cleanUp cache)
                                          eviction (.get (.eviction (.policy cache)))]
                                      {:nodes (.estimatedSize cache)
                                       :weighted-bytes (.orElse (.weightedSize eviction) 0)
                                       :max-bytes (.getMaximum eviction)})
                                    {:nodes (count @cache)}))})))
         (is (false? (get reply "isError")))
         ;; One declaration changes paths in six indexes; each path has at most
         ;; four levels in this fixture. Both old and new paths may be read.
         (is (<= reads (* 6 4 2)))))))
