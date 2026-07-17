(ns seon.agent.schedule-test
  "Pure cron-logic tests — parse / due? / next-fire-at against FIXED local
   instants (no system clock). June 25 2026 is a Thursday; June 26 2026 a
  Friday — used for the workday-cron cases."
  (:require
    [cljs.test :refer [async deftest is]]
    [seon.agent.run :as run]
    [seon.agent.schedule :as sched]
    [seon.config :as config]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.runtime.admission :as admission]))

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

(deftest firing-acquires-one-database-value-and-one-query-batch
  (async done
    (let [database ::database
          now (js/Date. 2026 5 25 9 0 0)
          requests (atom [])
          effects (atom [])
          originals [[#(set! admission/available? %) admission/available?]
                     [#(set! db/db %) db/db]
                     [#(set! db/execute-many %) db/execute-many]
                     [#(set! run/open-run! %) run/open-run!]]]
      (set! admission/available? (constantly true))
      (set! db/db (fn ([] (swap! effects conj :db)
                          (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/execute-many
            (fn [request]
              (swap! requests conj request)
              (js/Promise.resolve
               {::db/results
                [{::protocol/success? true
                  :datahike.query/result
                  [["agent-a" {} "0 9 * * *" 'my.jobs/run]]}
                 {::protocol/success? true :datahike.query/result []}
                 {::protocol/success? true :datahike.query/result []}
                 {::protocol/success? true
                  ::protocol/result
                  {:seon.config/id config/cluster-config-id
                   :seon.config.breaker/crash-count 4
                   :seon.config.breaker/window-ms 60000}}]})))
      (set! run/open-run!
            (fn [_]
              (swap! effects conj :open)
              (js/Promise.resolve
               {:seon.agent.run/id "run-a"
                :seon.agent.run/status :open
                :seon.agent.run/trigger :schedule
                :seon.agent.run/started-at now
                :seon.agent.run/turn-limit 20
                :seon.agent.run/deadline
                (js/Date. (+ (.getTime now) 60000))})))
      (-> (sched/fire-due-schedules!
             {:seon.agent/now now
              :seon.agent.schedule/exec-fn!
              (fn [request]
                (swap! effects conj [:exec (:seon.agent.schedule/fns request)])
                (js/Promise.resolve request))
              :seon.agent.schedule/drive!
              (fn [request] (swap! effects conj [:drive request]))})
            (.then
             (fn [result]
               (is (= [{:seon.agent/id "agent-a"
                        :seon.agent.run/id "run-a"}]
                      (:seon.agent.schedule/fired result)))
               (is (= 1 (count @requests)))
               (let [request (first @requests)]
                 (is (identical? database (::db/db request)))
                 (is (= 4 (count (::db/members request))))
                 (is (every? #(identical? database (::db/db %))
                             (::db/members request)))
                 (let [configuration-member (last (::db/members request))]
                   (is (= protocol/pull-operation
                          (::protocol/operation configuration-member)))
                   (is (= [:seon.config/id
                           :seon.config.breaker/crash-count
                           :seon.config.breaker/window-ms]
                          (::protocol/selector configuration-member)))
                   (is (= [:seon.config/id config/cluster-config-id]
                          (::protocol/entity-id configuration-member)))))
               (is (= [:db :open [:exec ['my.jobs/run]]
                       [:drive {:seon.agent/id "agent-a"}]]
                      @effects))))
            (.catch (fn [exception]
                      (is false (str "fire-due-schedules! threw: " exception))))
            (.finally
             (fn []
               (doseq [[restore value] originals] (restore value))
               (done)))))))
