(ns seon.agent.ticker-test
  "The one ticker's two data actions, on a FRESH :memory conn seeded like the
   pod boots (never the live agent conn). Covers
   `seon.agent.run/close-overdue-runs!` (the deadline watchdog — close a
   past-deadline run, leave a fresh one, SKIP a paused one) and
   `seon.agent.schedule/fire-due-schedules!` (open a `:schedule` run for an
   :idle agent with a due schedule, the :forbid no-second-run gate, not-due,
   and the same-minute double-fire guard). Explicit `now` everywhere (no
   wall-clock flakiness) except the double-fire case, which needs `now` ≈ the
   run's real `started-at` and so builds its cron from the current minute. The
   real `setInterval` is NOT installed — the two actions are called directly."
  (:require
    [cljs.test :refer [deftest is async]]
    [datahike.api :as d]
    [seon.agent :as agent]
    [seon.agent.run :as run]
    [seon.agent.schedule :as schedule]
    [seon.client :as client]
    [seon.db :as db]))

(def ^:private a-id "ticktest-26062")   ; exactly 14 chars (:seon.db/id)

(def ^:private extra-attrs
  "Run-model + schedule attrs the pod installs — added to the test conn's
   boot schema alongside client/agent-bootstrap-attrs."
  [:seon.agent/run :seon.agent/terminated-at :seon.agent/default-turn-limit
   :seon.agent/default-deadline-ms :seon.agent/schedules
   :seon.agent.run/id :seon.agent.run/agent :seon.agent.run/started-at
   :seon.agent.run/trigger :seon.agent.run/cause :seon.agent.run/turn-limit
   :seon.agent.run/deadline :seon.agent.run/last-beat-at :seon.agent.run/paused-at
   :seon.agent.run/remaining-ms :seon.agent.run/status :seon.agent.run/closed-reason
   :seon.agent.turn/run
   :seon.agent.schedule/id :seon.agent.schedule/cron :seon.agent.schedule/fn
   :seon.agent.schedule/timezone :seon.agent.schedule/concurrency-policy])

(defn- fresh-conn
  "Promise of a fresh :memory conn with the pod's boot schema + run/schedule
   schema + the user entity + an :idle agent A."
  []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (d/transact!
                       conn
                       {:tx-data (into (db/malli->datahike-schema
                                         (into client/agent-bootstrap-attrs extra-attrs))
                                       (db/tx-meta-datahike-schema))})
                     (.then (fn [_]
                              (d/transact!
                                conn
                                {:tx-data [{:seon.user/id "user"}
                                           {:seon.agent/id a-id}]})))
                     (.then (fn [_] conn))))))))

(defn- with-conn
  "Fresh seeded conn `set!` as the ROOT db/*conn* for `body` (conn → Promise),
   prior root restored after. Root set!, not binding (CLJS dynamic bindings
   pop at the first await)."
  [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(defn- seed-schedule!
  "Transact ONE schedule (cron + policy) onto agent A. Returns a Promise."
  [conn cron policy]
  (d/transact! conn
    {:tx-data [{:seon.agent/id a-id
                :seon.agent/schedules
                [{:seon.agent.schedule/id                 "sched-2606260a"
                  :seon.agent.schedule/cron               cron
                  :seon.agent.schedule/fn                 'my.demo/tick
                  :seon.agent.schedule/concurrency-policy policy}]}]}))

(defn- hour-ahead
  "A Date one hour past the current instant (past any fresh 10-min deadline)."
  []
  (js/Date. (+ (.getTime (js/Date.)) (* 60 60 1000))))

;; ── close-overdue-runs! — the deadline watchdog ──────────────────────────

(deftest close-overdue-runs!-closes-a-past-deadline-run
  (async done
    (let [!rid (atom nil)]
      (-> (with-conn
            (fn [_]
              (-> (run/open-run! {:seon.agent/id a-id :seon.agent.run/trigger :message})
                  (.then (fn [snap]
                           (reset! !rid (:seon.agent.run/id snap))
                           (run/close-overdue-runs! {:seon.agent/now (hour-ahead)})))
                  (.then (fn [res]
                           (is (= [@!rid] (:seon.agent.run/closed res)) "the overdue run was closed")
                           (let [r (db/entity {:seon.db/ref [:seon.agent.run/id @!rid]})]
                             (is (= :closed (:seon.agent.run/status r)))
                             (is (= :deadline-exceeded (:seon.agent.run/closed-reason r))))
                           (is (nil? (run/current-run {:seon.agent/id a-id})) "run pointer cleared")
                           (is (= :idle (:seon.agent/state
                                          (agent/state-snapshot {:seon.agent/id a-id})))
                               "derived state falls to idle"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest close-overdue-runs!-leaves-a-fresh-run-untouched
  (async done
    (let [!rid (atom nil)]
      (-> (with-conn
            (fn [_]
              (-> (run/open-run! {:seon.agent/id a-id :seon.agent.run/trigger :message})
                  (.then (fn [snap]
                           (reset! !rid (:seon.agent.run/id snap))
                           ;; deadline is now+10min; now = now ⇒ not overdue
                           (run/close-overdue-runs! {:seon.agent/now (js/Date.)})))
                  (.then (fn [res]
                           (is (= [] (:seon.agent.run/closed res)) "nothing closed")
                           (is (= :open (:seon.agent.run/status
                                          (db/entity {:seon.db/ref [:seon.agent.run/id @!rid]})))
                               "the fresh run is still open")
                           (is (some? (run/current-run {:seon.agent/id a-id}))
                               "the agent still owns its run"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest close-overdue-runs!-skips-a-paused-run
  (async done
    (let [!rid (atom nil)]
      (-> (with-conn
            (fn [_]
              (-> (run/open-run! {:seon.agent/id a-id :seon.agent.run/trigger :message})
                  (.then (fn [snap]
                           (reset! !rid (:seon.agent.run/id snap))
                           (run/pause! {:seon.agent/id a-id :seon.agent.run/id @!rid})))
                  (.then (fn [_]
                           (run/close-overdue-runs! {:seon.agent/now (hour-ahead)})))
                  (.then (fn [res]
                           (is (= [] (:seon.agent.run/closed res))
                               "a paused run is NOT deadline-killed")
                           (let [r (db/entity {:seon.db/ref [:seon.agent.run/id @!rid]})]
                             (is (= :open (:seon.agent.run/status r)) "still open")
                             (is (inst? (:seon.agent.run/paused-at r)) "still paused")))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; ── fire-due-schedules! — the schedule half ──────────────────────────────

(deftest fire-due-schedules!-opens-a-schedule-run-for-an-idle-agent
  (async done
    (let [now (js/Date. 2026 5 25 14 5 0)]   ; 14:05 matches */5
      (-> (with-conn
            (fn [conn]
              (-> (seed-schedule! conn "*/5 * * * *" :forbid)
                  ;; no :drive! ⇒ open-only (no real loop kicked)
                  (.then (fn [_] (schedule/fire-due-schedules! {:seon.agent/now now})))
                  (.then (fn [res]
                           (let [fired (:seon.agent.schedule/fired res)]
                             (is (= 1 (count fired)) "one run fired")
                             (is (= a-id (:seon.agent/id (first fired))))
                             (let [cur (run/current-run {:seon.agent/id a-id})]
                               (is (some? cur) "the agent now has an open run")
                               (is (= :schedule (:seon.agent.run/trigger cur))
                                   "trigger :schedule"))))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest fire-due-schedules!-does-not-open-a-second-run-when-running
  (async done
    (let [now  (js/Date. 2026 5 25 14 5 0)
          !rid (atom nil)]
      (-> (with-conn
            (fn [conn]
              (-> (seed-schedule! conn "*/5 * * * *" :forbid)
                  (.then (fn [_] (run/open-run! {:seon.agent/id a-id
                                                 :seon.agent.run/trigger :message})))
                  (.then (fn [snap]
                           (reset! !rid (:seon.agent.run/id snap))
                           (schedule/fire-due-schedules! {:seon.agent/now now})))
                  (.then (fn [res]
                           (is (= [] (:seon.agent.schedule/fired res))
                               "no second run while :running (concurrency :forbid)")
                           (is (= @!rid (:seon.agent.run/id
                                          (run/current-run {:seon.agent/id a-id})))
                               "still the original :message run, not superseded"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest fire-due-schedules!-does-nothing-when-not-due
  (async done
    (let [now (js/Date. 2026 5 25 14 5 0)]   ; 09:00 daily; 14:05 ⇒ not due
      (-> (with-conn
            (fn [conn]
              (-> (seed-schedule! conn "0 9 * * *" :forbid)
                  (.then (fn [_] (schedule/fire-due-schedules! {:seon.agent/now now})))
                  (.then (fn [res]
                           (is (= [] (:seon.agent.schedule/fired res)) "nothing fired")
                           (is (nil? (run/current-run {:seon.agent/id a-id}))
                               "no run opened"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest fire-due-schedules!-does-not-double-fire-within-a-minute
  (async done
    ;; The double-fire guard compares a :schedule run's real started-at to
    ;; `now`, so `now` must be ≈ wall-clock: build the cron from the current
    ;; minute and fire twice (ms apart).
    (let [now  (js/Date.)
          cron (str (.getMinutes now) " " (.getHours now) " "
                    (.getDate now) " " (inc (.getMonth now)) " *")]
      (-> (with-conn
            (fn [conn]
              (-> (seed-schedule! conn cron :forbid)
                  (.then (fn [_] (schedule/fire-due-schedules! {:seon.agent/now (js/Date.)})))
                  (.then (fn [res1]
                           (is (= 1 (count (:seon.agent.schedule/fired res1))) "tick 1 fires")
                           ;; close the run → idle again, but a :schedule run
                           ;; STARTED this minute now exists.
                           (run/close-run!
                             {:seon.agent.run/id (:seon.agent.run/id
                                                   (first (:seon.agent.schedule/fired res1)))
                              :seon.agent.run/closed-reason :completed})))
                  (.then (fn [_]
                           (is (nil? (run/current-run {:seon.agent/id a-id})) "idle again")
                           ;; tick 2, same minute, idle → guard blocks re-fire
                           (schedule/fire-due-schedules! {:seon.agent/now (js/Date.)})))
                  (.then (fn [res2]
                           (is (= [] (:seon.agent.schedule/fired res2))
                               "tick 2 in the same minute does NOT re-fire"))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))
