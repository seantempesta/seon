(ns seon.agent.fsm-test
  "Pure-logic tests for the dual-track FSM — the transition table + the
   derived-state projection. No db, no async: a function of its args."
  (:require
    [cljs.test :refer [deftest is]]
    [seon.agent.fsm :as fsm]))

(deftest derive-state-projects-the-primitives
  (is (= :idle       (fsm/derive-state {})) "no open run ⇒ idle")
  (is (= :idle       (fsm/derive-state {:seon.agent.run/open? false})))
  (is (= :running    (fsm/derive-state {:seon.agent.run/open? true})))
  (is (= :paused     (fsm/derive-state {:seon.agent.run/open? true
                                        :seon.agent.run/paused-at (js/Date.)})))
  (is (= :terminated (fsm/derive-state {:seon.agent/terminated-at (js/Date.)})))
  (is (= :terminated (fsm/derive-state {:seon.agent/terminated-at (js/Date.)
                                        :seon.agent.run/open? true
                                        :seon.agent.run/paused-at (js/Date.)}))
      "terminated dominates every other primitive"))

(deftest transition-follows-the-table
  (is (= :running    (fsm/transition :idle :trigger)))
  (is (= :running    (fsm/transition :running :turn-ok)))
  (is (= :idle       (fsm/transition :running :wait)))
  (is (= :idle       (fsm/transition :running :complete)))
  (is (= :idle       (fsm/transition :running :turn-limit)))
  (is (= :idle       (fsm/transition :running :deadline)))
  (is (= :idle       (fsm/transition :running :superseded)))
  (is (= :idle       (fsm/transition :running :error)))
  (is (= :paused     (fsm/transition :running :pause)))
  (is (= :terminated (fsm/transition :running :terminate)))
  (is (= :running    (fsm/transition :paused :resume)))
  (is (= :terminated (fsm/transition :paused :terminate))))

(deftest unknown-event-leaves-the-state-unchanged
  (is (= :running    (fsm/transition :running :resume)) "resume isn't valid in running")
  (is (= :idle       (fsm/transition :idle :complete)) "complete isn't valid in idle")
  (is (= :paused     (fsm/transition :paused :turn-ok)) "turn-ok isn't valid in paused")
  (is (= :terminated (fsm/transition :terminated :trigger)) "terminal absorbs everything")
  (is (= :terminated (fsm/transition :terminated :resume))))
