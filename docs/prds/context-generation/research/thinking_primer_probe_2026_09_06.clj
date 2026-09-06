(ns thinking-primer-probe-2026-09-06
  "Disposable-fork primer experiment; no database transactions."
  (:require [clojure.edn :as edn]
            [seon.config :as config]
            [seon.db :as db]
            [seon.operator :as operator]
            [seon.operator.runtime :as runtime]
            [seon.print :as print]
            [seon.sci.eval :as evaluation]))

(defn- example-entries
  [agent-id]
  [{:seon.render/thinking
    "Planning lives in a namespace like everything else. First discover the public names together."
    :seon.repl/form '(dir my.plan)}
   {:seon.render/thinking
    "The names distinguish reading a plan from rendering it. Read both descriptions and argument lists in one query over their function entities."
    :seon.repl/form
    '(seon.db/q
      {:query '[:find [(pull ?function
                              [:seon.fn/sym :seon.fn/doc :seon.fn/arglists]) ...]
                :in $ [?symbol ...]
                :where [?function :seon.fn/sym ?symbol]]
       :args [(seon.db/db) ["my.plan/plan" "my.plan/render-plan-ai"]]
       :max-work 4000 :max-results 8 :max-result-weight 4000})}
   {:seon.render/thinking
    "Use those functions together: derive this agent's plan from its database, then render that value. The result tells us whether there is work to pursue."
    :seon.repl/form
    (list
     '(fn [plan]
        {:seon.render/thinking
         (cond
           (:seon.error/kind plan)
           "The plan query refused. Inspect that error before treating the plan as empty."

           (seq (:my.plan/ready plan))
           "There is ready work. Start there; follow a plan item's references when you need its supporting context."

           (seq (:my.plan/blocked plan))
           "The authored work is blocked. Follow its dependency references before adding more work."

           (seq (:my.plan/obligations plan))
           "The system has derived obligations. Read them before creating a separate authored plan."

           :else
           "There is no ready or blocked authored work and no derived obligation. An empty plan is a useful answer; look to the triggering message for the next task.")
         :seon.render/ai
         (if (:seon.error/kind plan)
           (pr-str plan)
           (my.plan/render-plan-ai plan))})
     (list 'my.plan/plan (list 'seon.db/db) agent-id))}])

(defn- run-example
  [cluster-name agent-id]
  (let [instance (get @runtime/running-instances cluster-name)
        connection (operator/connection cluster-name)
        database @connection
        ctx (:seon.sci.eval/ctx
             (evaluation/fork-for-turn
              {:seon.sci.eval/ctx (:seon.sci.eval/ctx instance)
               :seon.db/db database
               :seon.db/connection connection
               :seon.cluster.agent/id agent-id}))
        configuration (config/defaults)
        request {:seon.sci.eval/ctx ctx
                 :seon.cluster.agent/id agent-id
                 :seon.sci.admit/caps (config/result-caps configuration)
                 :seon.sci.eval/time-limit-ms 2000
                 :seon.config/on-core-error :panic}]
    {:experiment/database (db/database-value-identity database)
     :experiment/entries
     (mapv
      (fn [entry]
        (let [started (System/nanoTime)
              result (evaluation/evaluate
                      (assoc request :seon.cluster.run.form/source
                             (pr-str (:seon.repl/form entry))))]
          (cond-> (assoc entry :experiment/elapsed-ms
                         (/ (- (System/nanoTime) started) 1000000.0))
            (:seon.cluster.eval/result-edn result)
            (assoc :experiment/result
                   (print/emit-text
                    (edn/read-string (:seon.cluster.eval/result-edn result))
                    (print/default-options)))
            (:seon.cluster.eval/output result)
            (assoc :experiment/printed (:seon.cluster.eval/output result))
            (:seon.cluster.eval/error result)
            (assoc :experiment/error (:seon.cluster.eval/error result)))))
      (example-entries agent-id))}))

(run-example "default" "root")
