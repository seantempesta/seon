(ns ^{:seon.ns/context-relevant? true} my.task
  "My task: the one I hold with its proofs (`current`), its goal or my step's
  instructions changed as the work clarifies them (`update!`), and how I give it back when I cannot make
  its proofs green (`send-back!`). Success has no verb: my
  task ends when its proofs are green. Call preparation supplies my database
  and identity.

  ## Example

  ```clojure
  (my.task/current {})
  ```"
  (:require [seon.context :as context]
            [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.ref :as ref]
            [seon.schema :as schema]
            [seon.run :as run]
            [seon.task :as task]
            [seon.turn :as turn]))

(defn current
  "My task, its proofs with their status on my branch, my step as judged and every reference it makes, resolved.

  The block's own task sections (`seon.context/task-sections`): the task, its proofs counts first, each with its
  status, its source and, when red, its failures; my step: its kind and status, the tests my change broke and,
  for a Prove step, the product I owe; the latest send-back; then every other reference my task makes. Holding
  no task answers my agent id alone.

  ## Example

  ```clojure
  (my.task/current {})
  ```
  ## Costs

  One assignment query and one task pull, then the proofs' reach digests and latest results on my branch and
  one resolution per reference."
  {:malli/schema [:=> [:cat :seon.agent/db-request] [:or :my.task/current :seon.error/value]]}
  [{database :seon.db/db agent-id :seon.agent/id}]
  (let [sections (context/task-sections database agent-id)]
    (if (:seon.error/at sections)
      sections
      (assoc sections :seon.agent/id agent-id))))

(defn- member-updated
  "The task `:my.task/task` with its member `:my.task/member` replaced by `(apply function value arguments)`, written
  on the connection's branch.

  `update!` names the task, member, function and arguments; call preparation supplies the connection and agent from
  the evaluation's world by their declared schema names."
  {:malli/schema [:=> [:cat :my.task/update-request]
                  [:or :my.task/updated :my.task/unknown-task-error :my.task/invalid-member-error :seon.task/refused-error
                   :seon.task/conflict-error :seon.error/value]]}
  [{connection :seon.db/connection agent-id :seon.agent/id sym :my.task/task k :my.task/member f :my.task/function args :my.task/arguments}]
  (let [database (db/db connection)
        handle (task/task-handle (symbol (namespace sym)) (name sym))
        held (task/task-declaration database handle)]
    (cond
      (:seon.error/at held) held
      (nil? held) (refusal/error :my.task/unknown-task-error {:seon.error/operation 'my.task/update! :my.task/task sym
                                                              :seon.error/message "I named a task that does not exist."})
      :else
      (let [value (apply f (get held k) args)
            valid? (schema/projection-validator (db/carried-projection database) :seon.task/declaration)]
        (cond
          (:seon.error/at value) value
          (not (valid? (if (nil? value) (dissoc held k) (assoc held k value))))
          (refusal/error :my.task/invalid-member-error {:seon.error/operation 'my.task/update! :my.task/member k
                                                        :seon.error/message "The new value does not satisfy the member's schema."})
          :else
          (let [written (task/create! (assoc (if (nil? value) (dissoc held k) (assoc held k value))
                                             :seon.db/connection connection :seon.agent/id agent-id
                                             :seon.task/basis {handle held}))
                resolved (when (and (string? value) (not (:seon.error/at written)))
                           (ref/references (db/db connection) {:seon.ref/text value}))
                problems (filterv :seon.ref/problem-kind resolved)]
            (or (when (:seon.error/at written) written)
                (some #(when (:seon.error/at %) %) resolved)
                (cond-> written (seq problems) (assoc :my.task/problems problems)))))))))

(defn update!
  "Change one member of task `sym` as `clojure.core/update` does.

  `f` is applied to the member's value and any `args`. `k` is `:seon.task/plan`,
  `:seon.task/instructions` or `:seon.task/proofs`; `f` runs here, in my context, on the declaration I read,
  and the new value is written on my branch through the task writer with that declaration as its basis. A
  basis that changed meanwhile is refused with base, current and proposed; nothing retries. An error value
  from `f` is the answer and nothing is written; an unchanged value writes nothing. Answers the task's
  handle, whether a declaration changed, and each reference in the new text that does not resolve as its
  problem; an unresolved reference never rejects the write.

  ## Example

  ```clojure
  (my.task/update! 'seon.ref/fix-pathless-section :seon.task/proofs (fnil into #{}) '[seon.ref-test/a-case])
  (my.task/update! 'seon.ref/fix-pathless-section :seon.task/plan my.edit/text \"throws on\" \"answers a missing-file error for\")
  ```"
  {:malli/schema [:=> [:cat :seon.task/sym :my.task/content-key [:fn clojure.core/ifn?] [:* :seon.schema/value]]
                  :my.task/update-answer]}
  [sym k f & args]
  (member-updated {:my.task/task sym :my.task/member k :my.task/function f :my.task/arguments (vec args)}))

(defn send-back!
  "Give my task back without green proofs.

  `:my.task/reason` says why, `:my.task/target` optionally names the task that owns the mistake and
  `:my.task/counterexample` optionally carries the data showing it; call preparation supplies my connection and
  identity, and holding no task answers `:my.task/no-task-error`. Writes my task's end `:sent-back` on my
  attempt on my branch, with the reason and the target (`seon.turn/record-end!`), whether a model reply or an
  eval request called it, and closes me. Answers the completed disposition whose reply is the reason, with the
  target and counterexample beside it and the end as recorded (`:seon.turn.loop/end`); root's next pass bails
  the task to root, which decides what runs next.

  ## Example

  ```clojure
  (my.task/send-back! {:my.task/reason \"The proof asserts the old shape; the intent asks for the new one.\"
                       :my.task/target 'seon.task/an-example-task
                       :my.task/counterexample {:expected 1 :actual 2}})
  ```"
  {:malli/schema [:=> [:cat :my.task/send-back-request] [:or :my.task/sent-back :my.task/no-task-error :seon.error/value]]}
  [{connection :seon.db/connection agent-id :seon.agent/id reason :my.task/reason target :my.task/target :as request}]
  (let [held (task/agent-task (db/db connection) agent-id)
        completed (run/complete reason)
        recorded (when (and (string? held) (not (:seon.error/at completed)))
                   (turn/record-end! connection agent-id :sent-back target reason))]
    (cond
      (:seon.error/at held) held
      (nil? held) (refusal/error :my.task/no-task-error {:seon.error/operation 'my.task/send-back! :my.task/reason reason
                                                         :seon.error/message "I hold no task, so there is nothing to give back."})
      (:seon.error/at completed) completed
      (:seon.error/at recorded) recorded
      :else (merge completed (select-keys request [:my.task/target :my.task/counterexample])
                   {:seon.turn.loop/end recorded}))))
