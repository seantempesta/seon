;; Probe: how the transcript projection treats two runs for one agent, and
;; whether a fork branch's receipt facts are visible from the parent branch.
;; Evidence for docs/prds/sci-execution-runtime/research/
;;   session-curation-transcript-supersession-opus-2026-08-04.md
(ns curation-supersession-probe
  (:require [seon.db :as db]
            [seon.cluster.run :as run]
            [seon.render.transcript :as transcript]))

(defn seed-run!
  "Open, claim, freeze, and settle one whole run of `sources` on `conn`."
  [conn {:keys [run-id agent-id process sources results at]}]
  (db/transact! conn (run/open-tx {:seon.cluster.run/id run-id
                                   :seon.cluster.run/agent
                                   [:seon.cluster.agent/id agent-id]
                                   :seon.cluster.run/opened-at at}))
  (db/transact! conn (run/claim-tx {:seon.cluster.run/id run-id
                                    :seon.cluster.run/process process
                                    :seon.cluster.run/live-processes #{process}
                                    :seon.cluster.run/now at}))
  (db/transact! conn (run/plan-tx {:seon.cluster.run/id run-id
                                   :seon.cluster.run/process process
                                   :seon.cluster.run/plan-digest
                                   (str "probe-" run-id)
                                   :seon.cluster.run/sources sources}))
  (doseq [[ordinal result] (map-indexed vector results)]
    (db/transact! conn (run/receipt-start-tx
                        {:seon.cluster.run/id run-id
                         :seon.cluster.eval/ordinal ordinal
                         :seon.cluster.eval/at at}))
    (db/transact! conn (run/receipt-settle-tx
                        {:seon.cluster.run/id run-id
                         :seon.cluster.eval/ordinal ordinal
                         :seon.cluster.eval/result-edn result})))
  run-id)

(defn projection-shape
  [unit budget]
  (let [p (#'transcript/projection (assoc unit
                                          :seon.render.transcript/token-budget
                                          budget))]
    {:pinned (mapv :seon.render.transcript/run-id
                   (:seon.render.transcript/pinned p))
     :entries (mapv (juxt :seon.render.transcript/run-id
                          :seon.render.transcript/kind
                          :seon.render.transcript/detail)
                    (:seon.render.transcript/entries p))
     :elided (:seon.render.transcript/elided p)
     :minimum (:seon.render.transcript/minimum-token-budget p)}))
