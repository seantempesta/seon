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
  {::db/results [(member run)
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
    (let [admission-state admission/state
          db-fn db/db
          execute-many db/execute-many
          !requests (atom [])
          database {:db-name "default" :t 1 :as-of nil :since nil
                    :history false
                    :datahike/commit-id
                    #uuid "10000000-0000-0000-0000-000000000001"}
          run {:seon.agent.run/id "run-a"
               :seon.agent.run/status :closed
               :seon.agent.run/closed-reason :completed}
          _ (set! admission/state
                  (fn [] {::admission/status :available}))
          _ (set! db/db
                  (fn
                    ([] (js/Promise.resolve database))
                    ([_request] (js/Promise.resolve database))))
          _ (set! db/execute-many
                  (fn [request]
                    (swap! !requests conj request)
                    (js/Promise.resolve (loop-read run nil :batch 0 0))))
          result (loop/run-loop! {:seon.agent/id "agent-a"} "run-a")]
      (-> result
          (.then
            (fn [result]
              (is (= :idle result))
              (is (= 1 (count @!requests)))
              (let [members (::db/members (first @!requests))]
                (is (= 5 (count members)))
                (is (= database (::db/db (first members))))
                (is (every? #(= database
                                (first (::db.protocol/arguments %)))
                            (rest members))))))
          (.catch (fn [error]
                    (is false (str "run-loop! rejected: " error))))
          (.finally
           (fn []
             (set! db/db db-fn)
             (set! db/execute-many execute-many)
             (set! admission/state admission-state)
             (done)))))))

(deftest wake-handler-reads-one-committed-database-value-once
  (async done
    (let [available? admission/available?
          pull-many db/pull-many
          database {:db-name "default" :t 2 :as-of nil :since nil
                    :history false
                    :datahike/commit-id
                    #uuid "10000000-0000-0000-0000-000000000002"}
          !requests (atom [])
          handler (loop/wake-handler {:seon.agent/id "agent-a"})
          _ (set! admission/available? (constantly true))
          _ (set! db/pull-many
                  (fn
                    ([_request]
                     (js/Promise.reject (js/Error. "unexpected map pull")))
                    ([db-value selector refs]
                     (swap! !requests conj [db-value selector refs])
                     (js/Promise.resolve
                      [{:db/id 7 :seon.agent/terminated-at (js/Date.)}
                       {:db/id 11
                        :seon.agent.message/id "message-a"
                        :seon.agent.message/hops 0
                        :seon.agent.message/origin :human
                        :seon.agent.message/from {:db/id 9}}]))))
          result
          (handler
           {:db-after database
            :tx-data [[11 :seon.agent.message/to 7 536870914 true]
                      [11 :seon.agent.message/content "hello"
                       536870914 true]]})]
      (-> result
          (.then
           (fn [_]
             (is (= 1 (count @!requests)))
             (let [[db-value selector refs] (first @!requests)]
               (is (= database db-value))
               (is (= [[:seon.agent/id "agent-a"] 11] refs))
               (is (some #(and (map? %)
                               (contains? % :seon.agent.message/from))
                         selector)))))
          (.catch (fn [error]
                    (is false (str "wake handler rejected: " error))))
          (.finally
           (fn []
             (set! db/pull-many pull-many)
             (set! admission/available? available?)
             (done)))))))

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
