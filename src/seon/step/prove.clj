(ns seon.step.prove
  "The Prove stage's handoff (`docs/seon/schedule/cards/TASK-CHAIN.md#prove-seonstepprovetemplate`)."
  (:require [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.step :as step]
            [seon.test.runner :as runner]))

(defn- refuse
  "The `:seon.step.prove/refusal` of the failed `check`, naming what failed it."
  {:malli/schema [:=> [:cat :seon.step.prove/check :seon.schema/value] :seon.step.prove/refusal]}
  [check offending]
  (refusal/error :seon.step.prove/refusal
                 {:seon.error/operation 'seon.step.prove/handoff :seon.step.prove/check check
                  :seon.error/message "A Prove step's handoff check failed." :seon.error/offending offending}))

(defn handoff
  "Each failed check of Prove `task` on `database`, as a `:seon.step.prove/refusal`; empty when satisfied.
  Checks its next task's proofs: `:no-proofs`, `:not-a-deftest`, `:not-designed-red`, and `:unreached` for a
  changed or Approach-cited function in none of the proofs' tested closures (`seon.test.runner/tests-reaching`),
  however many callers up."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/task] :seon.source/refusals]}
  [database task]
  (let [proofs (vec (sort (:seon.task/proofs (some->> (:seon.task/next task) (db/pull database [:seon.task/proofs])))))]
    (if (empty? proofs)
      [(refuse :no-proofs (:seon.task/sym task))]
      (let [tests (filterv #(step/deftest? database %) proofs)
            results (step/results database tests)
            not-red (vec (remove #(some-> (get results %) runner/designed-red?) tests))
            approach (get (step/sections (:seon.task/plan task "")) "approach" "")
            targets (into (step/changed database :seon.fn/sym) (step/cited-fns database approach))
            reached? (fn [target]
                       (let [reaching (runner/tests-reaching database tests target)]
                         (when (refusal/error? reaching) (throw (ex-info (:seon.error/message reaching) reaching)))
                         (seq reaching)))
            unreached (vec (sort (remove reached? targets)))]
        (cond-> []
          (not= (count tests) (count proofs)) (conj (refuse :not-a-deftest (vec (remove (set tests) proofs))))
          (seq not-red) (conj (refuse :not-designed-red not-red))
          (seq unreached) (conj (refuse :unreached unreached)))))))
