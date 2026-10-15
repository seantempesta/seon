(ns seon.step.merge
  "The merge step's gate (`docs/seon/schedule/cards/MERGE-STEP.md`): what a refused final merge left to repair on its
  merge branch."
  (:require [seon.error.refusal :as refusal]
            [seon.step :as step]
            [seon.test.runner :as runner]))

(defn- refuse
  "The `:seon.step.merge/refusal` of the failed `check`, naming what failed it."
  {:malli/schema [:=> [:cat :seon.step.merge/check :seon.schema/value] :seon.step.merge/refusal]}
  [check offending]
  (refusal/error :seon.step.merge/refusal
                 {:seon.error/operation 'seon.step.merge/gate :seon.step.merge/check check
                  :seon.error/message "A merge step's gate check failed." :seon.error/offending offending}))

(defn gate
  "Each failed check of merge step `task` on `database`, its merge branch, as a `:seon.step.merge/refusal`; empty
  when satisfied. `:red`: a test the refused merge found red (`:seon.task/refused-tests`) with no green current
  result here (`seon.step/results`), never run included. Conflicts and files that do not analyze are judged again by
  root's merge of this branch, which runs the whole gate on main's latest head."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/task] :seon.source/refusals]}
  [database task]
  (let [tests (vec (sort (:seon.task/refused-tests task)))
        results (if (seq tests) (step/results database tests) {})
        _ (when (:seon.error/at results) (throw (ex-info (:seon.error/message results) results)))
        green? (fn [test] (when-let [result (get results test)]
                            (and (not (runner/red-result? result)) (pos? (:seon.test/pass-count result 0)))))
        red (vec (remove green? tests))]
    (cond-> [] (seq red) (conj (refuse :red red)))))
