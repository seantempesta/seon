(ns ^{:seon.ns/context-relevant? true} my.task
  "The one work queue (health plan §5): the tasks a fault or a warning signal
  opened that nobody holds, and the claim that puts one on my branch; and my
  plan, the ordered steps of the task I hold. Call preparation supplies my
  database, connection and identity.

  Example:
  (my.task/steps {})"
  (:require [clojure.string :as str]
            [seon.db :as db]
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

  One query pulling the tasks with faults or signals, then per task its
  resolution from that pull (`seon.task/task-resolution`: indexed queries per
  fault)."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or [:vector :seon.task/open-view] :seon.db/error-result]]}
  [database]
  (let [tasks (db/q '[:find [(pull ?task ?selector) ...]
                      :in $ ?selector
                      :where [?task :seon.task/id ?handle]
                             (or-join [?task] [?task :seon.task/faults _] [?task :seon.task/signal _])
                             (not [_ :seon.task/assigned ?handle])]
                    database
                    (into task/resolution-selector
                          [:seon.task/sym :seon.task/name :seon.task/ns :seon.task/intent
                           {:seon.task/faults [:seon.error/signature
                                               {:seon.error/occurrences [:seon.error.occurrence/count]}]}]))
        view (fn [task]
               (let [closed (task/task-resolution database task)]
                 (cond
                   (:seon.error/at closed) closed
                   (nil? closed)
                   (cond-> {:seon.task/id (:seon.task/id task) :seon.task/name (:seon.task/name task) :seon.task/ns (:seon.task/ns task)
                            :seon.task/intent (:seon.task/intent task)
                            :seon.task/occurrences (reduce + 0 (for [fault (:seon.task/faults task)
                                                                     occurrence (:seon.error/occurrences fault)]
                                                                 (:seon.error.occurrence/count occurrence)))
                            :seon.render/operations [(list 'my.task/claim! {:seon.task/assigned (:seon.task/id task)})]}
                     (:seon.task/sym task) (assoc :seon.task/sym (:seon.task/sym task))
                     (seq (:seon.task/faults task))
                     (assoc :seon.task/faults (mapv :seon.error/signature (:seon.task/faults task)))))))]
    (if (map? tasks)
      tasks
      (let [views (keep view tasks)]
        (or (first (filter :seon.error/at views))
            (vec (sort-by (juxt (comp - :seon.task/occurrences) :seon.task/id) views)))))))

(defn claim!
  "Claim the open task `:seon.task/assigned` for me on my branch: the one
  assignment writer (`seon.task/assign!`), answering the assignment with my
  proposal offered. A task another agent holds refuses by name, naming the
  holder.

  Example:
  (let [created (seon.task/create! {:seon.task/ns 'my.turn :seon.task/name \"titles-trim\"
                                    :seon.task/kind :function :seon.task/intent \"Trim reply titles.\"})]
    (my.task/claim! {:seon.task/assigned (:seon.task/id created)}))"
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

;;; STEPS: my ordered plan is the steps of the task I hold (spec task-steps). With no task, the first
;;; plan! or step! makes and claims my own :work task.

(defn- unassigned
  "The refusal of a step read or write by an agent holding no task."
  {:malli/schema [:=> [:cat :qualified-symbol :seon.agent/id] :seon.task/refused-error]}
  [operation agent-id]
  (refusal/diagnostic (java.util.Date.) :seon.task/ingestion operation
                      {:seon.error/message (str "Agent " agent-id " holds no task, so it has no steps.")
                       :seon.task/refused :unassigned :seon.task/holder 'my.task
                       :seon.error/offending {:seon.agent/id agent-id}
                       :seon.error/fix "Plan first: (my.task/plan! {:seon.task/steps [\"first step\" \"next step\"]}) makes and claims my own work task."}))

(defn- held
  "The handle of the task I hold at the task home, or nil."
  {:malli/schema [:=> [:cat :seon.task/steps-request] [:or :nil :seon.task/handle :seon.db/invalid-read-error]]}
  [{connection :seon.db/connection agent-id :seon.agent/id}]
  (task/agent-task (db/db (task/home-connection connection)) agent-id))

(defn- own-task
  "The handle of the task I hold, else of my own :work task, created and claimed now."
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection] [:seon.agent/id :seon.agent/id]
                                  [:seon.task/intent {:optional true} :seon.task/intent]]]
                  [:or :seon.task/handle :seon.task/refused-error :seon.task/conflict-error :seon.error/value]]}
  [{connection :seon.db/connection agent-id :seon.agent/id intent :seon.task/intent :as request}]
  (or (held request)
      (let [database (db/db connection)
            namespace-name (get-in (db/pull database [{:seon.agent/namespace [:seon.ns/name]}] [:seon.agent/id agent-id])
                                   [:seon.agent/namespace :seon.ns/name] 'user)
            created (task/create! {:seon.db/connection connection :seon.agent/id agent-id :seon.task/ns namespace-name
                                   :seon.task/name (str "work-of-" (str/replace agent-id "/" ".")) :seon.task/kind :work
                                   :seon.task/intent (or intent (str "The work agent " agent-id " plans."))})]
        (if (:seon.error/at created)
          created
          (let [assigned (task/assign! {:seon.db/connection connection :seon.task/assigned (:seon.task/id created)
                                        :seon.task/agent [:seon.agent/id agent-id]
                                        :seon.task/branch (get-in database [:config :branch])})]
            (if (:seon.error/at assigned) assigned (:seon.task/id created)))))))

(defn- steps-of
  "Apply the step `op` to my own task (made when I hold none)."
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection] [:seon.agent/id :seon.agent/id]
                                  [:seon.task/intent {:optional true} :seon.task/intent]] :seon.task/step-op]
                  [:or :seon.task/steps-view :seon.task/refused-error :seon.task/conflict-error :seon.error/value]]}
  [{connection :seon.db/connection :as request} op]
  (let [handle (own-task request)]
    (if (string? handle) (task/steps! connection handle op) handle)))

(defn plan!
  "Replace the steps of my task with `:seon.task/steps`, in order: the first is current, the rest
  open. With no task I first make and claim my own :work task (`:seon.task/intent` names it).
  Answers my steps.

  Example:
  (my.task/plan! {:seon.task/steps [\"read the failing test\" \"fix the producer\"]})

  ## Costs

  One transaction at the task home (measured in the landing note)."
  {:malli/schema [:=> [:cat :seon.task/plan-request]
                  [:or :seon.task/steps-view :seon.task/refused-error :seon.task/conflict-error :seon.error/value]]}
  [request]
  (steps-of request [:plan (:seon.task/steps request)]))

(defn step!
  "Append `:seon.step/text` to my steps: current when I have no current step, else open.

  Example:
  (my.task/step! {:seon.step/text \"run the focused tests\"})"
  {:malli/schema [:=> [:cat :seon.task/step-request]
                  [:or :seon.task/steps-view :seon.task/refused-error :seon.task/conflict-error :seon.error/value]]}
  [request]
  (steps-of request [:append (:seon.step/text request)]))

(defn done!
  "Complete my current step and make the next open one current. Refuses `:no-current-step` when
  none is current, `:unassigned` when I hold no task.

  Example:
  (my.task/plan! {:seon.task/steps [\"read the failing test\" \"fix the producer\"]})
  (my.task/done! {})"
  {:malli/schema [:=> [:cat :seon.task/steps-request]
                  [:or :seon.task/steps-view :seon.task/refused-error :seon.error/value]]}
  [{connection :seon.db/connection agent-id :seon.agent/id :as request}]
  (let [handle (held request)]
    (cond (nil? handle) (unassigned 'my.task/done! agent-id)
          (string? handle) (task/steps! connection handle [:done])
          :else handle)))

(defn steps
  "My steps in order with the current one's text and progress; the calls my state offers.
  Refuses `:unassigned` when I hold no task.

  Example:
  (my.task/steps {})

  ## Costs

  One indexed query for my task and one pull of its steps."
  {:malli/schema [:=> [:cat :seon.task/steps-request]
                  [:or :seon.task/steps-view :seon.task/refused-error :seon.error/value]]}
  [{connection :seon.db/connection agent-id :seon.agent/id :as request}]
  (let [handle (held request)]
    (cond (nil? handle) (unassigned 'my.task/steps agent-id)
          (string? handle) (task/steps-view (db/db (task/home-connection connection)) handle)
          :else handle)))
