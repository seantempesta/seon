(ns seon.derive-test
  "Pure-logic tests for the derived-state rule — `seon.derive/state-from-primitives`,
   the ONE projection every consumer shares. No db, no async: a function of its
   args. (The db-arity readers `derive-state` / `current-run` / turn-counts are
   exercised end-to-end through the run + loop + lifecycle tests, which open a
   real `:memory` conn.) The transition TABLE lives with the loop that folds it
   and is tested in `seon.agent-loop-test`."
  (:require
    [cljs.test :refer [deftest is]]
    [seon.derive :as derive]))

(deftest state-from-primitives-projects-the-rule
  (is (= :idle       (derive/state-from-primitives {})) "no open run ⇒ idle")
  (is (= :idle       (derive/state-from-primitives {:seon.agent.run/open? false})))
  (is (= :running    (derive/state-from-primitives {:seon.agent.run/open? true})))
  (is (= :paused     (derive/state-from-primitives {:seon.agent.run/open? true
                                                    :seon.agent.run/paused-at (js/Date.)})))
  (is (= :terminated (derive/state-from-primitives {:seon.agent/terminated-at (js/Date.)})))
  (is (= :terminated (derive/state-from-primitives {:seon.agent/terminated-at (js/Date.)
                                                    :seon.agent.run/open? true
                                                    :seon.agent.run/paused-at (js/Date.)}))
      "terminated dominates every other primitive"))
