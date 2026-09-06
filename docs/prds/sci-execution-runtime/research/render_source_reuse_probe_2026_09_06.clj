(ns render-source-reuse-probe-2026-09-06
  (:require [clojure.core.async.flow :as flow]
            [seon.db :as db]
            [seon.operator]
            [seon.operator.runtime :as runtime]
            [seon.render.web]))

; Load through MCP JVM evaluation on the explicitly selected owned cluster.
; The MCP return itself requests runtime refresh; compare subsequent snapshots
; while the namespace debug page remains watched. This function only reads.
(defn snapshot
  "Read source run identities, a terminal example, and stale read dependencies."
  [cluster-name agent-id]
  (let [database (db/db (seon.operator/connection cluster-name))
        runs (db/q '[:find [(pull ?run
                                 [:seon.cluster.run/id
                                  :seon.cluster.run/closed-at
                                  :seon.cluster.run/error
                                  {:seon.cluster.run/forms
                                   [:seon.cluster.run.form/source]}
                                  {:seon.cluster.eval/_run
                                   [:seon.cluster.eval/id
                                    :seon.cluster.eval/result-edn
                                    :seon.cluster.eval/error]}]) ...]
                     :in $ ?agent-id
                     :where
                     [?agent :seon.cluster.agent/id ?agent-id]
                     [?run :seon.cluster.run/agent ?agent]
                     [?run :seon.cluster.run/forms _]]
                   database agent-id)
        first-run (first (sort-by :seon.cluster.run/id runs))
        evidence (when first-run
                   ((ns-resolve 'seon.render.web 'evaluation-read-evidence)
                    database (:seon.cluster.run/id first-run)))]
    {:seon.proof/agent (db/pull database [:seon.cluster.agent/id]
                              [:seon.cluster.agent/id agent-id])
     :seon.proof/run-count (count runs)
     :seon.proof/run-ids (set (map :seon.cluster.run/id runs))
     :seon.proof/terminal-count (count (filter :seon.cluster.run/closed-at runs))
     :seon.proof/example first-run
     :seon.proof/read-count (count evidence)
     :seon.proof/stale-reads
     (mapv :seon.db/read-request
           (remove #(db/read-evidence-current? database [%]) evidence))}))

(defn after-refresh
  "Wait under a caller bound for a later render pass and drained runtime input."
  [cluster-name agent-id previous-passes timeout-ms]
  (let [graph (:seon.flow/graph (get @runtime/running-instances cluster-name))
        deadline (+ (System/nanoTime) (* 1000000 timeout-ms))]
    (loop []
      (let [ping (get (flow/ping graph :timeout-ms 1000) :seon.render.web/render)
            passes (get-in ping [:clojure.core.async.flow/state
                                 :seon.render.web/passes])
            buffered (get-in ping [:clojure.core.async.flow/ins
                                   :seon.render.web/runtime-eval
                                   :buffer :count])]
        (cond
          (and passes (> passes previous-passes) (= 0 buffered))
          {:seon.proof/passes passes
           :seon.proof/runtime-buffer buffered
           :seon.proof/watched
           (get-in ping [:clojure.core.async.flow/state
                         :seon.render.web/watched-agents])
           :seon.proof/snapshot (snapshot cluster-name agent-id)}

          (< (System/nanoTime) deadline) (recur)

          :else
          {:seon.error/kind :seon.proof/render-timeout
           :seon.error/message "The watched render did not drain runtime input."
           :seon.proof/passes passes
           :seon.proof/runtime-buffer buffered})))))
