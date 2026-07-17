(ns seon.agent.ticker-test
  "The deadline watchdog over the public asynchronous database authority.

   Schedule acquisition and firing live in `seon.agent.schedule-test`; this
   namespace keeps the ticker's distinct overdue-run selection and close
   behavior without manufacturing an embedded Datahike connection."
  (:require
    [cljs.test :refer [async deftest is]]
    [seon.agent.run :as run]
    [seon.db :as db]))

(defn- with-watchdog-authority
  "Run `body` with one database value and supplied watchdog query rows."
  [rows body]
  (let [database ::database
        requests (atom [])
        closes (atom [])
        original-db db/db
        original-query db/query
        original-close run/close-run!]
    (set! db/db
          (fn
            ([] (js/Promise.resolve database))
            ([_] (js/Promise.resolve database))))
    (set! db/query
          (fn [request]
            (swap! requests conj request)
            (js/Promise.resolve rows)))
    (set! run/close-run!
          (fn [request]
            (swap! closes conj request)
            (js/Promise.resolve {:db-after database})))
    (-> (js/Promise.resolve (body database requests closes))
        (.finally
          (fn []
            (set! db/db original-db)
            (set! db/query original-query)
            (set! run/close-run! original-close))))))

(deftest close-overdue-runs!-closes-only-past-deadlines
  (async done
    (let [now (js/Date. 2000)]
      (let [result
            (with-watchdog-authority
              [["late" (js/Date. 1000)]
               ["fresh" (js/Date. 3000)]]
              (fn [database requests closes]
                (-> (run/close-overdue-runs! {:seon.agent/now now})
                    (.then
                      (fn [closed]
                        (is (= ["late"] (:seon.agent.run/closed closed)))
                        (is (= [{:seon.agent.run/id "late"
                                 :seon.agent.run/closed-reason
                                 :deadline-exceeded}]
                               @closes))
                        (is (= 1 (count @requests)))
                        (is (identical? database
                                        (:seon.db/db (first @requests)))))))))]
        (-> result
            (.then (fn [_] (done)))
            (.catch (fn [error]
                      (is false (str "watchdog rejected — " error))
                      (done))))))))

(deftest close-overdue-runs!-query-excludes-paused-runs
  (async done
    (let [result
          (with-watchdog-authority
            []
            (fn [_database requests closes]
              (-> (run/close-overdue-runs! {:seon.agent/now (js/Date. 2000)})
                  (.then
                    (fn [closed]
                      (let [query (:seon.db/query (first @requests))]
                        (is (= [] (:seon.agent.run/closed closed)))
                        (is (empty? @closes))
                        (is (some #{'(not [?r :seon.agent.run/paused-at _])}
                                  (drop-while #(not= :where %) query))
                            "the authority excludes paused runs")))))))]
      (-> result
          (.then (fn [_] (done)))
          (.catch (fn [error]
                    (is false (str "watchdog rejected — " error))
                    (done)))))))
