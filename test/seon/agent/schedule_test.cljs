(ns seon.agent.schedule-test
  "Pure cron-logic tests — parse / due? / next-fire-at against FIXED local
   instants (no system clock). June 25 2026 is a Thursday; June 26 2026 a
   Friday — used for the workday-cron cases."
  (:require
    [cljs.test :refer [deftest is]]
    [seon.agent.schedule :as sched]))

(deftest parse-expands-fields
  (let [p (sched/parse {:seon.agent.schedule/cron "*/5 * * * *"})]
    (is (true? (:seon.agent.schedule/ok? p)))
    (is (= #{0 5 10 15 20 25 30 35 40 45 50 55} (:seon.agent.schedule/minute p)))
    (is (= 24 (count (:seon.agent.schedule/hour p))) "* = full hour range"))
  (let [p (sched/parse {:seon.agent.schedule/cron "0 9 * * 1-5"})]
    (is (= #{0} (:seon.agent.schedule/minute p)))
    (is (= #{9} (:seon.agent.schedule/hour p)))
    (is (= #{1 2 3 4 5} (:seon.agent.schedule/day-of-week p)) "Mon-Fri range"))
  (let [p (sched/parse {:seon.agent.schedule/cron "0,30 8-10 * * *"})]
    (is (= #{0 30} (:seon.agent.schedule/minute p)) "comma list")
    (is (= #{8 9 10} (:seon.agent.schedule/hour p)) "range")))

(deftest parse-rejects-bad-crons
  (is (false? (:seon.agent.schedule/ok? (sched/parse {:seon.agent.schedule/cron "0 9 * *"})))
      "wrong field count")
  (is (false? (:seon.agent.schedule/ok? (sched/parse {:seon.agent.schedule/cron "99 * * * *"})))
      "out of range")
  (is (false? (:seon.agent.schedule/ok? (sched/parse {:seon.agent.schedule/cron "abc * * * *"})))
      "non-numeric"))

(deftest due?-matches-an-instant
  (is (sched/due? {:seon.agent.schedule/cron "*/5 * * * *"
                   :seon.agent.schedule/now (js/Date. 2026 5 25 14 5 0)}))
  (is (not (sched/due? {:seon.agent.schedule/cron "*/5 * * * *"
                        :seon.agent.schedule/now (js/Date. 2026 5 25 14 7 0)})))
  ;; Thursday June 25 09:00 — a workday
  (is (sched/due? {:seon.agent.schedule/cron "0 9 * * 1-5"
                   :seon.agent.schedule/now (js/Date. 2026 5 25 9 0 0)}))
  (is (not (sched/due? {:seon.agent.schedule/cron "0 9 * * 1-5"
                        :seon.agent.schedule/now (js/Date. 2026 5 25 10 0 0)}))
      "wrong hour")
  (is (not (sched/due? {:seon.agent.schedule/cron "0 9 * * 1-5"
                        :seon.agent.schedule/now (js/Date. 2026 5 27 9 0 0)}))
      "Saturday — not Mon-Fri"))

(deftest next-fire-at-scans-forward
  (let [nf (sched/next-fire-at {:seon.agent.schedule/cron "*/5 * * * *"
                                :seon.agent.schedule/after (js/Date. 2026 5 25 14 7 0)})]
    (is (= 10 (.getMinutes nf)) "next multiple-of-5 minute after :07"))
  ;; Friday June 26 noon → next workday 09:00 = Monday June 29 (skips weekend)
  (let [nf (sched/next-fire-at {:seon.agent.schedule/cron "0 9 * * 1-5"
                                :seon.agent.schedule/after (js/Date. 2026 5 26 12 0 0)})]
    (is (= 1 (.getDay nf)) "Monday")
    (is (= 29 (.getDate nf)))
    (is (= 9 (.getHours nf)))
    (is (= 0 (.getMinutes nf))))
  (is (nil? (sched/next-fire-at {:seon.agent.schedule/cron "bad cron"
                                 :seon.agent.schedule/after (js/Date.)}))
      "unparseable ⇒ nil"))
