(ns render-source-reuse-probe-2026-09-06
  (:require [seon.db :as db]
            [seon.operator]
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
