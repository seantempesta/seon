(ns seon.step.design
  "The Design stage's handoff (`docs/seon/schedule/cards/TASK-CHAIN.md#design-seonstepdesigntemplate`)."
  (:require [clojure.string :as str]
            [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.ref :as ref]
            [seon.step :as step]))

(defn created-problem
  "The `problem` section of `task`'s plan as it stood in the transaction that created the task as a chain member
  (`:seon.task/created-by`, written with its plan by `seon.task/chain!`; Datahike history on `database`); empty
  when none."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/task] :string]}
  [database task]
  (let [history (db/history database)
        [created] (sort (db/q '[:find [?tx ...] :in $ ?id
                                :where [?e :seon.task/id ?id] [?e :seon.task/created-by _ ?tx true]]
                              history (:seon.task/id task)))
        [_ plan] (last (sort-by first (db/q '[:find ?tx ?plan :in $ ?id ?created
                                              :where [?e :seon.task/id ?id] [?e :seon.task/plan ?plan ?tx true]
                                              [(<= ?tx ?created)]]
                                            history (:seon.task/id task) (or created 0))))]
    (str/trim (get (step/sections (or plan "")) "problem" ""))))

(defn handoff
  "Every `:seon.step.design/refusal` of Design `task` on `database`, one per failed check, empty when satisfied:
  `:sections` (What exists or Approach absent or blank), `:problem-changed` (Problem differs from the chain's creating
  transaction), `:references` (one does not resolve), `:producer-not-cited` (What exists cites no first-party function
  or no deftest, or Approach no function) and `:data-not-modeled` (no schema key changed on the branch and none
  resolved in What exists or Approach)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/task] :seon.source/refusals]}
  [database task]
  (let [plan (or (:seon.task/plan task) "")
        sections (step/sections plan)
        exists (get sections "what-exists" "")
        approach (get sections "approach" "")
        refuse (fn [check offending]
                 (refusal/error :seon.step.design/refusal
                                {:seon.error/operation 'seon.step.design/handoff :seon.step.design/check check
                                 :seon.error/message "A Design step's handoff check failed."
                                 :seon.error/offending offending}))
        missing (filterv #(str/blank? (get sections % "")) ["what-exists" "approach"])
        problems (step/problems database plan)
        uncited (cond-> []
                  (empty? (step/cited-fns database exists)) (conj :what-exists-function)
                  (not-any? #(step/deftest? database %) (ref/names exists)) (conj :what-exists-test)
                  (empty? (step/cited-fns database approach)) (conj :approach-function))
        modeled? (or (seq (step/changed database :seon.schema/key))
                     (some :key (mapcat #(ref/references database {:seon.ref/text %}) [exists approach])))]
    (cond-> []
      (seq missing) (conj (refuse :sections missing))
      (not= (created-problem database task) (str/trim (get sections "problem" ""))) (conj (refuse :problem-changed "problem"))
      (seq problems) (conj (refuse :references (mapv :seon.ref/name problems)))
      (seq uncited) (conj (refuse :producer-not-cited uncited))
      (not modeled?) (conj (refuse :data-not-modeled :seon.schema/key)))))
