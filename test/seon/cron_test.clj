(ns seon.cron-test
  "The cron proc (seon.cron): the next time of day after a moment, the date of a moment, and the
  proc's step: one full-run request for main per time, the next day armed, nothing when never.
  Run: bin/test seon.cron-test"
  (:require [clojure.test :refer [deftest is testing]] [clojure.core.async :as a] [seon.cron :as cron]))

(def ^:private day 86400000)

(deftest the-next-time-of-day-is-the-first-strictly-after-now
  (let [at-3 (* 3 3600000)]
    (is (= (+ 1761523200000 at-3) (cron/next-fire-ms 1761523200000 "03:00")) "from midnight: the same day's 03:00")
    (is (= (+ 1761523200000 at-3 day) (cron/next-fire-ms (+ 1761523200000 at-3) "03:00")) "at the time itself: the next day's")
    (is (= (+ 1761523200000 at-3 day) (cron/next-fire-ms (+ 1761523200000 at-3 1) "03:00")) "a moment after: the next day's")
    (is (= (+ 1761523200000 (* 23 3600000) (* 59 60000)) (cron/next-fire-ms 1761523200000 "23:59")))
    (is (nil? (cron/next-fire-ms 1761523200000 "")) "\"\": never")))

(deftest a-moment-is-a-utc-date
  (is (= "1970-01-01" (cron/day-of 0)))
  (is (= "2025-10-27" (cron/day-of 1761523200000)))
  (is (= "2025-10-27" (cron/day-of (+ 1761523200000 (dec day)))) "to the last millisecond of the day")
  (is (= "2025-10-28" (cron/day-of (+ 1761523200000 day))))
  (is (= "2024-02-29" (cron/day-of 1709164800000)) "a leap day")
  (is (= "2000-03-01" (cron/day-of 951913800000))))

(deftest the-proc-asks-the-full-run-of-main-once-per-time-and-arms-the-next-day
  (let [tick (a/chan 1)
        st (cron/cron-proc {:tick tick :at ""})]
    (is (nil? (:due st)) "never: nothing armed")
    (is (= [st nil] (cron/cron-proc st :tick {})) "and a tick asks nothing")
    (let [due 1761534000000
          armed (assoc st :at "03:00" :due due)
          [st2 outs] (cron/cron-proc armed :tick {})]
      (is (= {:to-prove [{:branch :main :digest "full-2025-10-27" :mode :full}]} outs)
          "one request for main, named for the day it was due")
      (is (< due (:due st2)) "the next time armed")
      (is (= "03:00" (:at st2)))
      (is (= 1 (:fired st2))))))
