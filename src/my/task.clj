(ns ^{:seon.ns/context-relevant? true} my.task
  "My task: the one I hold with its proofs (`current`), and how I give it back
  when I cannot make its proofs green (`send-back!`). Success has no verb: my
  task ends when its proofs are green. Call preparation supplies my database
  and identity.

  Example:
  (my.task/current {})"
  (:require [seon.context :as context]
            [seon.run :as run]))

(defn current
  "My task, its proofs with their status on my branch, and my step as judged.

  The declaration I hold (`seon.context/held-task`) and its proofs section,
  counts first, then each proof with its status and, when red, its failures
  (`seon.context/proofs`); then my step (`seon.context/step-section`): its kind and
  status, the tests my change broke and, for a Prove step, the product I owe, each
  with its failures. Holding no task answers my agent id alone.

  Example:
  (my.task/current {})

  ## Costs

  One assignment query and one task pull at main, then the proofs' reach
  digests and latest results on my branch."
  {:malli/schema [:=> [:cat :seon.agent/db-request] [:or :my.task/current :seon.error/value]]}
  [{database :seon.db/db agent-id :seon.agent/id}]
  (let [held (context/held-task database agent-id)
        proven (when (and held (not (:seon.error/at held))) (context/proofs database (:seon.task/sym held)))
        verdict (when (and held (not (:seon.error/at held))) (context/step-verdict database agent-id))]
    (or (some #(when (:seon.error/at %) %) [held proven verdict])
        (cond-> {:seon.agent/id agent-id}
          held (assoc :seon.context/task held)
          proven (assoc :seon.context/proofs proven)
          verdict (assoc :seon.context/step (context/step-section verdict))))))

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
