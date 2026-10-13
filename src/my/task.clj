(ns ^{:seon.ns/context-relevant? true} my.task
  "My task: the one I hold with its proofs (`current`), its goal or my step's
  instructions rewritten as the work clarifies them (`refine!`), and how I give it back when I cannot make
  its proofs green (`send-back!`). Success has no verb: my
  task ends when its proofs are green. Call preparation supplies my database
  and identity.

  Example:
  (my.task/current {})"
  (:require [clojure.string :as str]
            [seon.context :as context]
            [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.ref :as ref]
            [seon.run :as run]
            [seon.task :as task]))

(defn current
  "My task, its proofs with their status on my branch, my step as judged and every reference it makes, resolved.

  The block's own task sections (`seon.context/task-sections`): the task, its proofs counts first, each with its
  status, its source and, when red, its failures; my step: its kind and status, the tests my change broke and,
  for a Prove step, the product I owe; the latest send-back; then every other reference my task makes. Holding
  no task answers my agent id alone.

  Example:
  (my.task/current {})

  ## Costs

  One assignment query and one task pull, then the proofs' reach digests and latest results on my branch and
  one resolution per reference."
  {:malli/schema [:=> [:cat :seon.agent/db-request] [:or :my.task/current :seon.error/value]]}
  [{database :seon.db/db agent-id :seon.agent/id}]
  (let [sections (context/task-sections database agent-id)]
    (if (:seon.error/at sections)
      sections
      (assoc sections :seon.agent/id agent-id))))

(defn refine!
  "Rewrite my chain's goal or my step's own work on my branch; the merge carries it to main.

  `:seon.task/intent` is the goal: it is written on the subject, the task that declares the goal my step
  reads (`seon.task/goal-owner`), so every step of my chain reads it next (`seon.task/effective`).
  `:seon.task/product` names the deftests my chain's Prove step supplies, written on the subject beside the goal.
  `:seon.task/instructions` is my step's own work, written on the task I hold. Both are prose naming their
  references inline: backticked fully qualified symbols and keywords, tasks as `ns/task-name`, and paths
  with `#heading` anchors. My next context resolves them. Answers the handle of the task I hold, whether a
  declaration changed, and each reference that does not resolve as its problem.

  Example:
  (my.task/refine! {:seon.task/intent \"Make `seon.db/q` answer its refusal as data; see docs/seon/specs/merge.md#1-the-merge-request.\"})
  (my.task/refine! {:seon.task/instructions \"The rows are in `seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart`.\"})"
  {:malli/schema [:=> [:cat :my.task/refine-request]
                  [:or :my.task/refined :seon.task/refused-error :seon.task/conflict-error :my.task/no-task-error :seon.error/value]]}
  [{connection :seon.db/connection agent-id :seon.agent/id intent :seon.task/intent instructions :seon.task/instructions
    product :seon.task/product}]
  (let [database (db/db connection)
        handle (task/agent-task database agent-id)
        subject (when (and (or intent product) (string? handle)) (task/goal-owner database handle))
        ;; each task the refinement writes, with the members written on it
        writes (when (string? handle)
                 (cond-> {}
                   (string? subject) (assoc subject (cond-> {} intent (assoc :seon.task/intent intent)
                                                      product (assoc :seon.task/product product)))
                   instructions (update handle merge {:seon.task/instructions instructions})))
        write! (fn [target changes]
                 (let [held (db/pull (db/db connection) [:seon.task/ns] [:seon.task/id target])
                       ;; the declarations of the task's namespace as stored now: the basis the writer checks
                       basis (when (:seon.task/ns held)
                               (task/task-basis (db/db connection) {(str "resources/seon/tasks/" (:seon.task/ns held) ".edn") "[]"}))]
                   (cond (:seon.error/at held) held
                         (:seon.error/at basis) basis
                         :else (task/create! (merge (get basis target) changes
                                                    {:seon.db/connection connection :seon.agent/id agent-id
                                                     :seon.task/basis (select-keys basis [target])})))))]
    (cond
      (:seon.error/at handle) handle
      (nil? handle) (refusal/error :my.task/no-task-error {:seon.error/operation 'my.task/refine! :my.task/holder agent-id
                                                           :seon.error/message "I hold no task to refine."})
      (:seon.error/at subject) subject
      :else
      (let [written (reduce (fn [answers [target changes]]
                              (let [answer (write! target changes)]
                                (if (:seon.error/at answer) (reduced answer) (conj answers answer))))
                            [] (sort-by key writes))
            resolved (when-not (:seon.error/at written)
                       (ref/references (db/db connection) {:seon.ref/text (str/join "\n\n" (keep identity [intent instructions]))}))
            problems (filterv :seon.ref/problem-kind resolved)]
        (or (when (:seon.error/at written) written)
            (some #(when (:seon.error/at %) %) resolved)
            (cond-> {:seon.task/id handle :seon.task/changed? (boolean (some :seon.task/changed? written))}
              (seq problems) (assoc :my.task/problems problems)))))))

(defn send-back!
  "Give my task back without green proofs.

  `:my.task/reason` says why, `:my.task/target` optionally names the task that owns the mistake and
  `:my.task/counterexample` optionally carries the data showing it. Answers
  the completed disposition whose reply is the reason, with the target and
  counterexample beside it; as my reply's last form it ends my turn, my
  task's end is derived `:sent-back` to the target, recorded on my attempt as
  `:seon.task.attempt/target`, or `:given-back` without one
  (`seon.turn/task-end`), and root hears it in my end message.

  Example:
  (my.task/send-back! {:my.task/reason \"The proof asserts the old shape; the intent asks for the new one.\"
                       :my.task/target 'seon.task/an-example-task
                       :my.task/counterexample {:expected 1 :actual 2}})"
  {:malli/schema [:=> [:cat :my.task/send-back-request] [:or :my.task/sent-back :seon.error/value]]}
  [{reason :my.task/reason :as request}]
  (let [completed (run/complete reason)]
    (if (:seon.error/at completed)
      completed
      (merge completed (select-keys request [:my.task/target :my.task/counterexample])))))
