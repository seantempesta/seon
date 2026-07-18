(ns seon.agent.run-test
  "Focused owner tests for the async run lifecycle boundary."
  (:require
   [cljs.test :refer [async deftest is]]
   [seon.agent.message :as message]
   [seon.agent.run :as run]
   [seon.config :as config]
   [seon.db :as db]
   [seon.db.id :as db.id]
   [seon.db.protocol :as db.protocol]
   [seon.error :as error]))

(def ^:private database
  {:db-name "run-test" :t 7 :as-of nil :since nil :history false
   :datahike/commit-id #uuid "00000000-0000-0000-0000-000000000007"})

(def ^:private database-after
  (assoc database :t 8
         :datahike/commit-id #uuid "00000000-0000-0000-0000-000000000008"))

(def ^:private native-report
  {:db-before database :db-after database-after
   :tx-data [] :tempids {} :tx-meta {}})

(defn- query-result [value]
  {::db.protocol/success? true :datahike.query/result value})

(defn- pull-result [value]
  {::db.protocol/success? true ::db.protocol/result value})

(defn- finish! [done originals work]
  (-> (work)
      (.catch (fn [cause] (is false (str "unexpected rejection: " cause))))
      (.finally
       (fn []
         (doseq [[restore value] originals] (restore value))
         (done)))))

(deftest pure-bounds
  (is (false? (run/turn-limit-reached? 2 3)))
  (is (true? (run/turn-limit-reached? 3 3)))
  (let [deadline (js/Date. 100)]
    (is (false? (run/deadline-passed? deadline (js/Date. 100))))
    (is (true? (run/deadline-passed? deadline (js/Date. 101))))))

(deftest current-run-and-quiescence-thread-one-database-value
  (async done
    (let [original-db db/db
          original-pull db/pull
          original-execute db/execute-many
          pull-request (atom nil)
          execute-request (atom nil)]
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/pull
            (fn
              ([request]
               (reset! pull-request request)
               (js/Promise.resolve
                {:seon.agent/run {:seon.agent.run/id "run-a"
                                  :seon.agent.run/status :open}}))
              ([_ _] (js/Promise.resolve nil))
              ([_ _ _] (js/Promise.resolve nil))))
      (set! db/execute-many
            (fn [request]
              (reset! execute-request request)
              (js/Promise.resolve
               {::db/results
                [(query-result #{["agent-b" "run-b"] ["agent-a" "run-a"]})
                 (query-result #{["run-b" "turn-b"]})]})))
      (finish!
       done
       [[#(set! db/db %) original-db]
        [#(set! db/pull %) original-pull]
        [#(set! db/execute-many %) original-execute]]
       (fn ^:async test-read []
         (let [current (await (run/current-run {:seon.agent/id "agent-a"}))
               work (await (run/quiescence-work!))]
           (is (= "run-a" (:seon.agent.run/id current)))
           (is (identical? database (::db/db @pull-request)))
           (is (identical? database (::db/db work)))
           (is (= ["agent-a" "agent-b"]
                  (mapv :seon.agent/id (::run/current-runs work))))
           (is (every? #(identical? database (::db/db %))
                       (::db/members @execute-request)))))))))

(deftest quiescence-returns-member-failure-as-a-direct-error
  (async done
    (let [original-db db/db
          original-execute db/execute-many]
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/execute-many
            (fn [_]
              (js/Promise.resolve
               {::db/results
                [(query-result #{})
                 {::db.protocol/success? false
                  ::db.protocol/error "running turns unavailable"}]})))
      (finish!
       done
       [[#(set! db/db %) original-db]
        [#(set! db/execute-many %) original-execute]]
       (fn ^:async test-error []
         (let [result (await (run/quiescence-work!))]
           (is (= "Quiescence acquisition failed: running turns unavailable"
                  (:seon.error/message result)))))))))

(deftest open-run-returns-the-built-run-without-a-reread
  (async done
    (let [original-db db/db
          original-execute db/execute-many
          original-allocate db.id/allocate!
          acquisition-request (atom nil)
          allocation-request (atom nil)
          built (atom nil)]
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/execute-many
            (fn [request]
              (reset! acquisition-request request)
              (js/Promise.resolve
               {::db/results
                [(pull-result {:seon.agent/default-turn-limit 4})
                 (pull-result {:seon.config/repl-mode :batch
                               :seon.config.run/batch-turn-limit 100
                               :seon.config.run/stream-form-limit 300
                               :seon.config.run/deadline-ms 60000})]})))
      (set! db.id/allocate!
            (fn [request]
              (reset! allocation-request request)
              (reset! built ((::db.id/transaction-builder request)
                             {:seon.agent.run/id "run-a"}))
              (js/Promise.resolve
               (assoc native-report ::db.id/ids
                      {:seon.agent.run/id "run-a"}))))
      (finish!
       done
       [[#(set! db/db %) original-db]
        [#(set! db/execute-many %) original-execute]
        [#(set! db.id/allocate! %) original-allocate]]
       (fn ^:async test-open []
         (let [result (await (run/open-run!
                              {:seon.agent/id "agent-a"
                               :seon.agent.run/trigger :message}))
               cas (second (:seon.db/tx-data @built))]
           (is (= "run-a" (:seon.agent.run/id result)))
           (is (= 4 (:seon.agent.run/turn-limit result)))
           (is (= [:seon.agent/default-turn-limit
                   :seon.agent/default-deadline-ms]
                  (get-in @acquisition-request
                          [::db/members 0 ::db.protocol/selector])))
           (is (identical? database (::db/db @allocation-request)))
           (is (= :db.fn/cas (first cas)))
           (is (nil? (nth cas 3)))))))))

(deftest renew-pause-and-resume-use-targeted-cas
  (async done
    (let [original-db db/db
          original-execute db/execute-many
          original-pull db/pull
          original-transact db/transact!
          deadline (js/Date. (+ (.getTime (js/Date.)) 60000))
          paused-at (js/Date. 1000)
          pulls (atom [{:seon.agent.run/deadline deadline}
                       {:seon.agent.run/paused-at paused-at
                        :seon.agent.run/remaining-ms 5000}])
          transactions (atom [])]
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/execute-many
            (fn [_]
              (js/Promise.resolve
               {::db/results
                [(pull-result {:seon.agent.run/turn-limit 9})
                 (pull-result {:seon.agent/default-deadline-ms 1000})
                 (pull-result {:seon.config.run/deadline-ms 2000})]})))
      (set! db/pull
            (fn
              ([_]
               (let [value (first @pulls)]
                 (swap! pulls subvec 1)
                 (js/Promise.resolve value)))
              ([_ _] (js/Promise.resolve nil))
              ([_ _ _] (js/Promise.resolve nil))))
      (set! db/transact!
            (fn [& requests]
              (swap! transactions conj (first requests))
              (js/Promise.resolve native-report)))
      (finish!
       done
       [[#(set! db/db %) original-db]
        [#(set! db/execute-many %) original-execute]
        [#(set! db/pull %) original-pull]
        [#(set! db/transact! %) original-transact]]
       (fn ^:async test-cas []
         (await (run/renew! {:seon.agent/id "agent-a"
                             :seon.agent.run/id "run-a"}))
         (await (run/pause! {:seon.agent/id "agent-a"
                             :seon.agent.run/id "run-a"}))
         (await (run/resume! {:seon.agent/id "agent-a"
                              :seon.agent.run/id "run-a"}))
         (let [renew-tx (::db/tx-data (nth @transactions 0))
               pause-tx (::db/tx-data (nth @transactions 1))
               resume-tx (::db/tx-data (nth @transactions 2))]
           (is (= [:db.fn/cas [:seon.agent.run/id "run-a"]
                   :seon.agent.run/turn-limit 9 10]
                  (second renew-tx)))
           (is (= [:db.fn/cas [:seon.agent.run/id "run-a"]
                   :seon.agent.run/deadline deadline deadline]
                  (second pause-tx)))
           (is (= [:db.fn/cas [:seon.agent.run/id "run-a"]
                   :seon.agent.run/paused-at paused-at paused-at]
                  (second resume-tx)))
           (is (= :seon.agent.run/remaining-ms
                  (nth (last resume-tx) 2)))))))))

(deftest close-pause-and-resume-reuse-an-explicit-database-value
  (async done
    (let [original-db db/db
          original-pull db/pull
          original-transact db/transact!
          db-calls (atom 0)
          deadline (js/Date. (+ (.getTime (js/Date.)) 60000))
          paused-at (js/Date. 1000)
          pulls
          (atom
           [{:seon.agent.run/deadline deadline}
            {:seon.agent.run/paused-at paused-at
             :seon.agent.run/remaining-ms 5000}
            {:seon.agent.run/id "run-a"
             :seon.agent.run/agent
             {:seon.agent/id "agent-a"
              :seon.agent/run {:seon.agent.run/id "run-a"}}}])
          transactions (atom [])]
      (set! db/db
            (fn
              ([]
               (swap! db-calls inc)
               (js/Promise.resolve database-after))
              ([_]
               (swap! db-calls inc)
               (js/Promise.resolve database-after))))
      (set! db/pull
            (fn
              ([request]
               (is (identical? database (::db/db request)))
               (let [value (first @pulls)]
                 (swap! pulls subvec 1)
                 (js/Promise.resolve value)))
              ([_ _] (js/Promise.reject (js/Error. "unexpected pull arity")))
              ([_ _ _] (js/Promise.reject (js/Error. "unexpected pull arity")))))
      (set! db/transact!
            (fn [& call-args]
              (swap! transactions conj (first call-args))
              (js/Promise.resolve native-report)))
      (finish!
       done
       [[#(set! db/db %) original-db]
        [#(set! db/pull %) original-pull]
        [#(set! db/transact! %) original-transact]]
       (fn ^:async test-explicit-database []
         (await
          (run/pause!
           {:seon.agent/id "agent-a"
            :seon.agent.run/id "run-a"
            ::db/db database}))
         (await
          (run/resume!
           {:seon.agent/id "agent-a"
            :seon.agent.run/id "run-a"
            ::db/db database}))
         (await
          (run/close-run!
           {:seon.agent.run/id "run-a"
            :seon.agent.run/closed-reason :waited
            ::db/db database}))
         (is (zero? @db-calls))
         (is (= 3 (count @transactions)))
         (is (every? #(identical? database (::db/db %)) @transactions)))))))

(deftest close-fences-owned-pointer-and-notifies-from-db-after
  (async done
    (let [original-db db/db
          original-pull db/pull
          original-transact db/transact!
          original-execute db/execute-many
          original-message message/message!
          transaction (atom nil)
          outcome-request (atom nil)
          sent (atom nil)
          run-row {:seon.agent.run/id "run-a"
                   :seon.agent.run/status :open
                   :seon.agent.run/agent
                   {:seon.agent/id "agent-a"
                    :seon.agent/purpose "check the result"
                    :seon.agent/run {:seon.agent.run/id "run-a"}}}]
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/pull
            (fn
              ([_] (js/Promise.resolve run-row))
              ([_ _] (js/Promise.resolve nil))
              ([_ _ _] (js/Promise.resolve nil))))
      (set! db/transact!
            (fn [& requests]
              (reset! transaction (first requests))
              (js/Promise.resolve native-report)))
      (set! db/execute-many
            (fn [request]
              (reset! outcome-request request)
              (js/Promise.resolve
               {::db/results [(pull-result run-row) (pull-result nil)]})))
      (set! message/message!
            (fn [request]
              (reset! sent request)
              (js/Promise.resolve
               {:seon.agent.message/id "message-a"
                :seon.agent.message/hops 1})))
      (finish!
       done
       [[#(set! db/db %) original-db]
        [#(set! db/pull %) original-pull]
        [#(set! db/transact! %) original-transact]
        [#(set! db/execute-many %) original-execute]
        [#(set! message/message! %) original-message]]
       (fn ^:async test-close []
         (let [result
               (await
                (run/close-run!
                 {:seon.agent.run/id "run-a"
                  :seon.agent.run/closed-reason :turn-limit}))
               tx (::db/tx-data @transaction)]
           (is (= native-report result))
           (is (= :db.fn/cas (ffirst tx)))
           (is (= :seon.agent/run (nth (first tx) 2)))
           (is (= :db/retract (first (last tx))))
           (is (identical? database-after (::db/db @outcome-request)))
           (is (every? #(identical? database-after (::db/db %))
                       (::db/members @outcome-request)))
           (is (identical? database-after (:seon.db/db @sent)))
           (is (nil? (:seon.agent.message/origin @sent)))))))))

(deftest unowned-close-never-touches-the-current-pointer
  (async done
    (let [original-db db/db
          original-pull db/pull
          original-transact db/transact!
          transaction (atom nil)]
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/pull
            (fn
              ([_]
               (js/Promise.resolve
                {:seon.agent.run/id "run-old"
                 :seon.agent.run/agent
                 {:seon.agent/id "agent-a"
                  :seon.agent/run {:seon.agent.run/id "run-new"}}}))
              ([_ _] (js/Promise.resolve nil))
              ([_ _ _] (js/Promise.resolve nil))))
      (set! db/transact!
            (fn [& requests]
              (reset! transaction (first requests))
              (js/Promise.resolve native-report)))
      (finish!
       done
       [[#(set! db/db %) original-db]
        [#(set! db/pull %) original-pull]
        [#(set! db/transact! %) original-transact]]
       (fn ^:async test-unowned []
         (await
          (run/close-run!
           {:seon.agent.run/id "run-old"
            :seon.agent.run/closed-reason :superseded}))
         (let [tx (::db/tx-data @transaction)]
           (is (= 1 (count tx)))
           (is (map? (first tx)))))))))

(deftest watchdog-scans-once-and-closes-each-candidate
  (async done
    (let [original-db db/db
          original-execute db/execute-many
          original-close run/close-run!
          original-record error/record!
          acquisitions (atom [])
          closes (atom [])
          rows #{["run-b" "agent-b" (js/Date. 0) (js/Date. 100)]
                 ["run-a" "agent-a" (js/Date. 0) (js/Date. 200)]}
          now (js/Date. 10000)]
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/execute-many
            (fn [request]
              (swap! acquisitions conj request)
              (js/Promise.resolve
               {::db/results
                [(query-result rows)
                 (pull-result
                  {:seon.config/id config/cluster-config-id
                   :seon.config.watchdog/stale-ms 1000})]})))
      (set! run/close-run!
            (fn [request]
              (swap! closes conj request)
              (js/Promise.resolve native-report)))
      (set! error/record! (fn [_] nil))
      (finish!
       done
       [[#(set! db/db %) original-db]
        [#(set! db/execute-many %) original-execute]
        [#(set! run/close-run! %) original-close]
        [#(set! error/record! %) original-record]]
       (fn ^:async test-watchdog []
         (is (= [] (run/stale-run-ids #{} now 1000)))
         (is (= ["run-a" "run-b"] (run/stale-run-ids rows now 1000)))
         (let [result
               (await
                (run/close-stale-runs!
                 {:seon.agent/now now}))]
           (is (= ["run-a" "run-b"] (:seon.agent.run/closed result)))
           (is (= 1 (count @acquisitions)))
           (let [acquisition (first @acquisitions)
                 [stale-member config-member] (::db/members acquisition)]
             (is (identical? database (::db/db acquisition)))
             (is (= 2 (count (::db/members acquisition))))
             (is (every? #(identical? database (::db/db %))
                         (::db/members acquisition)))
             (is (= db.protocol/query-operation
                    (::db.protocol/operation stale-member)))
             (is (= db.protocol/pull-operation
                    (::db.protocol/operation config-member)))
             (is (= [:seon.config/id :seon.config.watchdog/stale-ms]
                    (::db.protocol/selector config-member)))
             (is (= [:seon.config/id config/cluster-config-id]
                    (::db.protocol/entity-id config-member))))
           (is (= ["run-a" "run-b"]
                  (mapv :seon.agent.run/id @closes)))))))))
