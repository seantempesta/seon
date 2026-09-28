(ns ^{:seon.ns/context-relevant? true} my.task
  "The one work queue (health plan §5): the tasks a fault or a warning signal
  opened that nobody holds, and the claim that puts one on my branch. Call
  preparation supplies my database, connection and identity."
  (:require [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.task :as task]))

(defn open
  "The open tasks nobody is assigned, most occurred first: every task
  answering faults (`:seon.task/faults`) or a warning signal whose derived
  close (`seon.task/resolution`) is still nil and that no assignment names.
  Each offers its claim. Seon agents and outside agents (MCP `eval_clj` with
  an agent) read the same queue.

  Example:
  (my.task/open)

  ## Costs

  One query over the tasks with faults or signals, then per task one pull and
  its resolution (indexed queries per fault)."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or [:vector :seon.task/open-view] :seon.db/error-result]]}
  [database]
  (let [handles (db/q '[:find [?handle ...]
                             :where [?task :seon.task/id ?handle]
                                    (or-join [?task] [?task :seon.task/faults _] [?task :seon.task/signal _])
                                    (not [_ :seon.task/assigned ?handle])]
                           database)
        view (fn [handle]
               (let [task (db/pull database [:seon.task/id :seon.task/name :seon.task/ns :seon.task/intent
                                                  {:seon.task/faults [:seon.error/signature
                                                                      {:seon.error/occurrences [:seon.error.occurrence/count]}]}]
                                        [:seon.task/id handle])
                     closed (task/resolution database handle)]
                 (cond
                   (:seon.error/at task) task
                   (:seon.error/at closed) closed
                   (nil? closed)
                   (cond-> {:seon.task/id handle :seon.task/name (:seon.task/name task) :seon.task/ns (:seon.task/ns task)
                            :seon.task/intent (:seon.task/intent task)
                            :seon.task/occurrences (reduce + 0 (for [fault (:seon.task/faults task)
                                                                     occurrence (:seon.error/occurrences fault)]
                                                                 (:seon.error.occurrence/count occurrence)))
                            :seon.render/operations [(list 'my.task/claim! {:seon.task/assigned handle})]}
                     (seq (:seon.task/faults task))
                     (assoc :seon.task/faults (mapv :seon.error/signature (:seon.task/faults task)))))))]
    (if (map? handles)
      handles
      (let [views (keep view handles)]
        (or (first (filter :seon.error/at views))
            (vec (sort-by (juxt (comp - :seon.task/occurrences) :seon.task/id) views)))))))

(defn claim!
  "Claim the open task `:seon.task/assigned` for me on my branch: the one
  assignment writer (`seon.task/assign!`), answering the assignment with my
  proposal offered. A task another agent holds refuses by name, naming the
  holder.

  Example:
  (my.task/claim! {:seon.task/assigned \"6cc772cb3f60\"})"
  {:malli/schema [:=> [:cat :seon.task/claim-request]
                  [:or :seon.task/assignment-view :seon.task/refused-error :seon.error/value]]}
  [{connection :seon.db/connection agent-id :seon.agent/id handle :seon.task/assigned}]
  (let [branch (get-in (db/db connection) [:config :branch])
        holder (db/q '[:find ?id . :in $ ?handle
                            :where [?assignment :seon.task/assigned ?handle] [?assignment :seon.task/agent ?agent]
                                   [?agent :seon.agent/id ?id]]
                          (db/db (task/home-connection connection)) handle)]
    (cond
      (map? holder) holder
      (and holder (not= holder agent-id))
      (refusal/diagnostic (java.util.Date.) :seon.task/ingestion 'my.task/claim!
                                     {:seon.error/message (str "Task " handle " is held by " holder ".")
                                      :seon.task/refused :claimed :seon.task/holder 'my.task
                                      :seon.error/offending {:seon.task/assigned handle :seon.agent/id holder}})
      :else (task/assign! {:seon.db/connection connection :seon.task/assigned handle
                                :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch branch}))))
