(ns thinking-primer-probe-2026-09-06
  "Ordinary reply-source primer experiment in one disposable turn fork."
  (:require [seon.cluster.reply :as reply]
            [seon.db :as db]
            [seon.operator :as operator]
            [seon.operator.runtime :as runtime]
            [seon.sci.eval :as evaluation]))

(defn- example-reply
  [agent-id]
  (str
   ";; Planning lives in a namespace like everything else. First discover\n"
   ";; its public names together.\n"
   "(dir my.plan)\n\n"
   ";; Read the plan and render function descriptions and argument lists in\n"
   ";; one bounded query over their function entities.\n"
   "(seon.db/q\n"
   " {:query '[:find [(pull ?function\n"
   "                         [:seon.fn/sym :seon.fn/doc\n"
   "                          :seon.fn/arglists]) ...]\n"
   "           :in $ [?symbol ...]\n"
   "           :where [?function :seon.fn/sym ?symbol]]\n"
   "  :args [(seon.db/db)\n"
   "         [\"my.plan/plan\" \"my.plan/render-plan-ai\"]]\n"
   "  :max-work 4000\n"
   "  :max-results 8\n"
   "  :max-result-weight 4000})\n\n"
   ";; Derive this agent's current plan from ordinary database facts, then use\n"
   ";; its declared AI render. The computed value, including any refusal, is\n"
   ";; the result that follows this source; no result text is executable input.\n"
   "(my.plan/render-plan-ai\n"
   " (my.plan/plan (seon.db/db) " (pr-str agent-id) "))"))

(defn- result-entry
  [request source]
  (let [started (System/nanoTime)
        namespace-name (or (:seon.ns/name source) 'user)
        result
        (evaluation/evaluate
         (assoc request
                :seon.cluster.run.form/source
                (:seon.cluster.run.form/source source)
                :seon.cluster.run.form/ns
                [:seon.ns/name namespace-name]))]
    (cond-> {:experiment/source source
             :experiment/elapsed-ms
             (/ (- (System/nanoTime) started) 1000000.0)
             :experiment/result (:seon.sci.admit/value result)
             :experiment/capped? (:seon.sci.admit/capped? result)}
      (:seon.cluster.eval/error result)
      (assoc :experiment/error (:seon.cluster.eval/error result))

      (:seon.cluster.eval/output result)
      (assoc :experiment/output (:seon.cluster.eval/output result)))))

(defn- run-example
  [cluster-name agent-id]
  (let [instance (get @runtime/running-instances cluster-name)
        connection (operator/connection cluster-name)
        database @connection
        caps (:seon.sci.admit/caps instance)
        namespace-name (or (evaluation/agent-namespace database agent-id) 'user)
        authored-source (example-reply agent-id)
        sources (reply/sources
                 authored-source namespace-name
                 (:seon.config.eval.result/max-source caps))
        ctx (:seon.sci.eval/ctx
             (evaluation/fork-for-turn
              {:seon.sci.eval/ctx (:seon.sci.eval/ctx instance)
               :seon.db/db database
               :seon.db/connection connection
               :seon.cluster.agent/id agent-id}))
        request {:seon.sci.eval/ctx ctx
                 :seon.cluster.agent/id agent-id
                 :seon.sci.admit/caps caps
                 :seon.sci.eval/time-limit-ms
                 (:seon.config.eval/time-limit-ms instance)
                 :seon.config/on-core-error :panic}]
    (if (:seon.error/kind sources)
      {:experiment/database (db/database-value-identity database)
       :experiment/authored-source authored-source
       :experiment/error sources}
      {:experiment/database (db/database-value-identity database)
       :experiment/authored-source authored-source
       :experiment/sources sources
       :experiment/results (mapv #(result-entry request %) sources)})))

(run-example "default" "root")
