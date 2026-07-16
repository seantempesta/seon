(ns seon.agent-loop-test
  "Focused contracts for the authority-backed agent loop."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [seon.agent.loop :as loop]
    [seon.db :as db]
    [seon.db.protocol :as db.protocol]
    [seon.runtime.admission :as admission]))

(defn- member [result]
  {::db.protocol/success? true
   ::db.protocol/result result})

(defn- query-member [result]
  {::db.protocol/success? true
   :datahike.query/result result})

(defn- loop-read [run current-run-id mode turn-count form-count]
  {::db/coordinate {:test/database-value true}
   ::db/results [(member run)
                 (query-member current-run-id)
                 (query-member mode)
                 (query-member turn-count)
                 (query-member form-count)]})

(deftest transition-is-the-whole-fsm
  (testing "known events move through the declared table"
    (is (= :running (loop/transition :idle :trigger)))
    (is (= :paused (loop/transition :running :pause)))
    (is (= :running (loop/transition :paused :resume)))
    (is (= :terminated (loop/transition :running :terminate))))
  (testing "unknown transitions preserve state"
    (is (= :idle (loop/transition :idle :deadline)))
    (is (= :terminated (loop/transition :terminated :resume)))))

(deftest next-event-is-pure-over-one-database-projection
  (let [next-event @#'loop/next-event
        future (js/Date. (+ (.now js/Date) 60000))
        base {::loop/run {:seon.agent.run/id "run-a"
                          :seon.agent.run/status :open
                          :seon.agent.run/turn-limit 4
                          :seon.agent.run/deadline future}
              ::loop/current-run-id "run-a"
              ::loop/repl-mode :batch
              ::loop/turn-count 0
              ::loop/form-count 0}]
    (is (= :turn-ok (next-event base 0)))
    (is (= :superseded
           (next-event (assoc base ::loop/current-run-id "run-b") 0)))
    (is (= :pause
           (next-event (assoc-in base [::loop/run :seon.agent.run/paused-at]
                                 (js/Date.))
                       0)))
    (is (= :turn-limit (next-event (assoc base ::loop/turn-count 4) 0)))
    (is (= :turn-limit
           (next-event (assoc base ::loop/repl-mode :stream
                                   ::loop/form-count 4)
                       0)))
    (is (= :no-forms
           (next-event base loop/no-forms-streak-limit)))
    (is (= :complete
           (next-event
             (assoc base ::loop/run
                    {:seon.agent.run/id "run-a"
                     :seon.agent.run/status :closed
                     :seon.agent.run/closed-reason :completed})
             0)))))

(deftest run-loop-acquires-one-batched-database-value
  (async done
    (let [!requests (atom [])
          run {:seon.agent.run/id "run-a"
               :seon.agent.run/status :closed
               :seon.agent.run/closed-reason :completed}
          result
          (with-redefs
            [admission/state (fn [] {::admission/status :available})
             db/execute-many
             (fn [request]
               (swap! !requests conj request)
               (js/Promise.resolve (loop-read run nil :batch 0 0)))]
            (loop/run-loop! {:seon.agent/id "agent-a"} "run-a"))]
      (-> result
          (.then
            (fn [result]
              (is (= :idle result))
              (is (= 1 (count @!requests)))
              (is (= 5 (count (::db/members (first @!requests)))))))
          (.catch (fn [error]
                    (is false (str "run-loop! rejected: " error))))
          (.finally done)))))

(deftest activity-log-is-one-authority-query
  (async done
    (let [!requests (atom [])
          started (js/Date. 1000)
          result
          (with-redefs
            [db/query
             (fn [request]
               (swap! !requests conj request)
               (js/Promise.resolve
                 [[{:seon.agent.run/status :closed
                    :seon.agent.run/closed-reason :completed
                    :seon.agent.run/cause
                    {:seon.agent.message/content "done"}}
                   started]]))]
            (loop/activity-log {:seon.agent/id "agent-a"}))]
      (-> result
          (.then
            (fn [result]
              (is (= 1 (count @!requests)))
              (is (= [{:seon.agent.loop/at started
                       :seon.agent/state :idle
                       :seon.agent.loop/stop-reason :completed
                       :seon.agent.loop/cause "done"}]
                     (:seon.agent.loop/entries result)))))
          (.catch (fn [error]
                    (is false (str "activity-log rejected: " error))))
          (.finally done)))))
