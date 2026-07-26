(ns seon.agent.driver-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.agent.ctx.render-fns]
            [seon.agent.driver :as driver]
            [seon.agent.run.core :as run]
            [seon.db :as db]
            [seon.db.host :as db.host])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private event-backstop-seconds
  ;; A latch publishes completion; this clock only turns a missing event into
  ;; an immediate, named test failure instead of wedging the writer runner.
  5)

(defn- await-event! [^CountDownLatch latch event]
  (when-not (.await latch event-backstop-seconds TimeUnit/SECONDS)
    (throw
     (ex-info "The driver test did not observe its required event."
              {:seon.agent.driver-test/event event}))))

(def ^:private wake-schema-attributes
  [:seon.agent/id
   :seon.agent/run
   :seon.agent.message/id
   :seon.agent.message/from
   :seon.agent.message/to
   :seon.agent.message/content
   :seon.agent.message/at
   :seon.agent.message/origin
   :seon.agent.run/id
   :seon.agent.run/agent
   :seon.agent.run/cause
   :seon.agent.run/started-at
   :seon.agent.run/status
   :seon.agent.run/process
   :seon.agent.run/claim-epoch
   :seon.agent.run/lease-until])

(defn- with-wake-database [body]
  (let [configuration
        {:store {:backend :memory :id (random-uuid)}
         :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (d/transact connection
                  (db/malli->datahike-schema wake-schema-attributes))
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(defn- transact-agent-message!
  [connection recipients]
  (let [at #inst "2026-07-26T12:00:00.000-00:00"]
    (d/transact
     connection
     [{:seon.agent/id "sender"}
      {:seon.agent/id "recipient"}
      {:seon.agent.message/id "message-a"
       :seon.agent.message/from [:seon.agent/id "sender"]
       :seon.agent.message/to
       (mapv (fn [agent-id] [:seon.agent/id agent-id]) recipients)
       :seon.agent.message/content "Do the work."
       :seon.agent.message/at at
       :seon.agent.message/origin :agent}])
    at))

(defn- pending-wakes [connection]
  (#'driver/pending-messages
   {'query
    (fn [{query :seon.db/query}]
      (d/q query @connection))}))

(deftest agent-origin-message-makes-only-its-recipient-claimable
  (with-wake-database
    (fn [connection]
      (let [at (transact-agent-message!
                connection ["sender" "recipient"])]
        (is (= [["message-a" "recipient" "Do the work." at]]
               (pending-wakes connection))
            "the one inbound rule admits the recipient and rejects the sender")))))

(deftest processed-wake-cannot-reenumerate-the-same-message
  (with-wake-database
    (fn [connection]
      (let [at (transact-agent-message! connection ["recipient"])]
        (is (= 1 (count (pending-wakes connection)))
            "the message is claimable before run admission")
        (d/transact
         connection
         (driver/open-run-tx-data
          "run-a" "host-1" "message-a" "recipient" at
          #inst "2026-07-26T12:20:00.000-00:00"))
        (is (empty? (pending-wakes connection))
            "the run cause damps the processed message without another wake")))))

(defn- in-flight-scan-measurement [agent-count]
  (let [messages
        (mapv
         (fn [ordinal]
           [{:seon.agent.message/id (str "message-" ordinal)
             :seon.agent.message/content "Complete."
             :seon.agent.message/at
             #inst "2026-07-26T12:00:00.000-00:00"
             :seon.agent.message/origin :human
             :seon.agent.message/from {:db/id 1}}
            {:db/id (+ 2 ordinal)
             :seon.agent/id (str "agent-" ordinal)}])
         (range agent-count))
        processing-started (CountDownLatch. agent-count)
        processing-finished (CountDownLatch. agent-count)
        release-processing (CountDownLatch. 1)
        scheduled-tasks (atom [])
        run-open-transaction-calls (atom 0)
        database-functions
        {'query (constantly messages)}
        process-message!
        (fn [_allocate! _database-functions _llm-transport! _process-id _message]
          (swap! run-open-transaction-calls inc)
          (.countDown processing-started)
          (await-event! release-processing :processing-release)
          (.countDown processing-finished))]
    (with-redefs-fn
      {#'db.host/listen! (fn [_writer request] request)
       #'driver/process-message! process-message!
       #'driver/configured-lease-duration-ms (constantly 3000)
       #'driver/recoverable-runs (constantly [])
       #'driver/start-virtual-thread!
       (fn [task]
         (swap! scheduled-tasks conj task)
         ::scheduled)}
      (fn []
        (let [listener
              (driver/start!
               ::writer nil database-functions ::llm-transport!)
              initial-tasks @scheduled-tasks
              _ (reset! scheduled-tasks [])
              threads
              (mapv #(Thread/startVirtualThread ^Runnable %)
                    initial-tasks)]
          (is (= agent-count (count initial-tasks))
              "the initial scan schedules every distinct pending message")
          (await-event! processing-started :initial-processing)
          ((:seon.db/handler listener) {:seon.db/datoms []})
          (is (= 1 (count @scheduled-tasks))
              "one matching commit schedules one serialized scan")
          (let [[scan-task] @scheduled-tasks]
            (reset! scheduled-tasks [])
            (scan-task))
          (let [duplicate-run-open-calls (count @scheduled-tasks)]
            (swap! run-open-transaction-calls + duplicate-run-open-calls))
          (.countDown release-processing)
          (await-event! processing-finished :processing-completion)
          (doseq [^Thread thread threads]
            (.join thread))
          {:seon.agent.driver-test/agent-count agent-count
           :seon.agent.driver-test/run-open-transaction-calls-per-useful-run
           (/ (double @run-open-transaction-calls) agent-count)
           :seon.agent.driver-test/losing-cas-count
           (- @run-open-transaction-calls agent-count)
           :seon.agent.driver-test/listener-patterns
           (:seon.db/datom-patterns listener)})))))

(deftest wake-rescans-submit-one-run-open-transaction-per-in-flight-message
  (doseq [agent-count [1 5 10 25]]
    (let [measurement (in-flight-scan-measurement agent-count)]
      (is (= 1.0
             (:seon.agent.driver-test/run-open-transaction-calls-per-useful-run
              measurement))
          (str "N=" agent-count " keeps run-open transaction calls O(1)"))
      (is (zero?
           (:seon.agent.driver-test/losing-cas-count measurement))
          (str "N=" agent-count " has no same-process losing run-open CAS"))
      (is (= [{:seon.db/a :seon.agent.message/to}]
             (:seon.agent.driver-test/listener-patterns measurement))
          "the wake interest excludes attributes committed by driver work"))))

(deftest terminal-step-renews-the-held-lease-without-an-extra-transaction
  (let [lease-duration-ms 3000
        clock (atom (java.util.Date. 2000))
        transactions (atom [])
        transact!
        (fn [tx-data]
          (swap! transactions conj tx-data)
          {:db-after {:t (count @transactions)}})
        request
        {:seon.agent/id "agent-a"
         :seon.agent.run/id "run-a"
         :seon.agent.run/claim-epoch 1
         :seon.agent.turn/id "turn-a"
         :seon.eval/at (java.util.Date. 1000)
         :seon.eval/ordinal 0
         :seon.eval/total 2
         :seon.eval/source "(identity :first)"
         :seon.eval/ns 'user
         ::driver/lease-duration-ms lease-duration-ms
         :seon.sci.interrupt/time-limit-ms 100}
        result
        (binding [driver/*now* #(deref clock)]
          (driver/execute-form!
           transact!
           (constantly
            {:seon.sci.eval/value :first
             :seon.sci.eval/record
             {:seon.eval/duration-ms 1
              :seon.eval/fn-entries 1
              :seon.eval/allocated-bytes 1}})
           (constantly [])
           request))
        renewed-lease (:seon.agent.driver/lease-until result)
        held-run
        {:seon.agent/id "agent-a"
         :seon.agent.run/id "run-a"
         :seon.agent.run/status :open
         :seon.agent.run/process "process-a"
         :seon.agent.run/claim-epoch 1
         :seon.agent.run/lease-until renewed-lease}]
    (is (= (java.util.Date. 5000) renewed-lease)
        "a step settling at 2s publishes the next exact 3s lease")
    (is (= 2 (count @transactions))
        "renewal rides the existing terminal-receipt transaction")
    (is (some
         #{[:db/add
            [:seon.agent.run/id "run-a"]
            :seon.agent.run/lease-until
            (java.util.Date. 5000)]}
         (second @transactions)))
    (is (run/live-process? held-run (java.util.Date. 4000))
        "the chain remains held after crossing its original 3s lease")
    (is (nil?
         (run/claim-plan
          held-run "process-b"
          (java.util.Date. 4000)
          (java.util.Date. 7000)))
        "another process cannot steal between renewed step boundaries")))

(deftest replacement-arms-the-committed-lease-and-resumes-without-a-commit
  (let [lease-duration-ms 3000
        started-at (java.util.Date.)
        original-lease
        (java.util.Date. (+ (.getTime started-at) lease-duration-ms))
        forms
        (mapv
         (fn [ordinal]
           {:seon.agent.run.form/id (str "form-" ordinal)
            :seon.agent.run.form/ordinal ordinal
            :seon.agent.run.form/source (str "(identity " ordinal ")")})
         (range 7))
        run-state
        (atom
         {:seon.agent/id "agent-a"
          :seon.agent.run/id "run-a"
          :seon.agent.run/status :open
          :seon.agent.run/process "host-dead"
          :seon.agent.run/claim-epoch 1
          :seon.agent.run/lease-until original-lease
          :seon.agent.run/forms forms})
        turn
        {:seon.agent.turn/id "turn-a"
         :seon.agent.turn/status :running
         :seon.agent.turn/evals
         [{:seon.eval/id "old-eval"
           :seon.eval/ordinal 0
           :seon.eval/status :done}]}
        lease-armed (CountDownLatch. 1)
        drive-settled (CountDownLatch. 1)
        armed-at (atom nil)
        first-transaction-at (atom nil)
        transactions (atom [])
        evaluations (atom 0)
        transact-request!
        (fn [{tx-data :seon.db/tx-data}]
          (compare-and-set! first-transaction-at nil (java.util.Date.))
          (swap! transactions conj tx-data)
          (doseq [item tx-data]
            (cond
              (and (vector? item)
                   (= :seon.agent.run/claim-epoch (nth item 2 nil)))
              (swap! run-state assoc
                     :seon.agent.run/claim-epoch (nth item 4))

              (and (vector? item)
                   (= :seon.agent.run/process (nth item 2 nil))
                   (= :db/add (first item)))
              (swap! run-state assoc :seon.agent.run/process (nth item 3))

              (and (vector? item)
                   (= :seon.agent.run/process (nth item 2 nil))
                   (= :db/retract (first item)))
              (swap! run-state dissoc :seon.agent.run/process)

              (and (vector? item)
                   (= :seon.agent.run/lease-until (nth item 2 nil))
                   (= :db/add (first item)))
              (swap! run-state assoc
                     :seon.agent.run/lease-until (nth item 3))

              (and (map? item) (:seon.agent.run/status item))
              (swap! run-state merge
                     (select-keys
                      item
                      [:seon.agent.run/status
                       :seon.agent.run/closed-reason
                       :seon.agent.run/closed-at]))))
          (when (some :seon.agent.turn/timings (filter map? tx-data))
            (.countDown drive-settled))
          {:db-after {:t (count @transactions)}})
        database-functions
        {'db (constantly {})
         'transact! transact-request!}
        allocate!
        (fn [{allocations :seon.db.id/allocations
              transaction-builder :seon.db.id/transaction-builder}]
          (let [key (:seon.db.id/key (first allocations))
                ids {key "reply-a"}]
            (merge
             {:seon.db.id/ids ids}
             (transact-request! (transaction-builder ids)))))
        production-await driver/*await-lease!*]
    (with-redefs-fn
      {#'db.host/listen! (fn [_writer request] request)
       #'driver/pending-messages (constantly [])
       #'driver/recoverable-runs
       (fn [_]
         (if (= :open (:seon.agent.run/status @run-state))
           [@run-state]
           []))
       #'driver/running-turn (fn [_ _] turn)
       #'driver/configured-lease-duration-ms (constantly lease-duration-ms)
       #'driver/*await-lease!*
       (fn [wake-at task]
         (reset! armed-at wake-at)
         (.countDown lease-armed)
         (production-await wake-at task))
       #'driver/evaluate!
       (fn [_]
         (let [evaluation (swap! evaluations inc)]
           {:seon.sci.eval/value
            (if (= 6 evaluation)
              {:seon.agent.lifecycle/disposition :completed
               :seon.agent.lifecycle/result "resumed"}
              evaluation)
            :seon.sci.eval/record
            {:seon.eval/duration-ms 0
             :seon.eval/fn-entries 1
             :seon.eval/allocated-bytes 1}}))}
      (fn []
        (driver/start! ::writer allocate! database-functions ::llm-transport!)
        (await-event! lease-armed :lease-readiness-armed)
        (is (= original-lease @armed-at)
            "replacement startup derives one exact wake from the committed lease")
        (is (empty? @transactions)
            "no unrelated commit is needed before the lease becomes ready")
        (await-event! drive-settled :replacement-drive-settled)
        (let [claim-delay-ms
              (- (.getTime ^java.util.Date @first-transaction-at)
                 (.getTime started-at))]
          (is (<= lease-duration-ms
                  claim-delay-ms
                  (* event-backstop-seconds 1000))
              (str "the lease-expiry claim was the first transaction at "
                   claim-delay-ms " ms")))
        (is (= 2 (:seon.agent.run/claim-epoch @run-state)))
        (is (= :closed (:seon.agent.run/status @run-state)))
        (is (= :completed (:seon.agent.run/closed-reason @run-state)))
        (is (= 6 @evaluations)
            "the terminal ordinal from the dead process is not re-evaluated")
        (is (= 14 (count @transactions))
            "one claim, six receipt pairs, and one timing settlement commit")))))

(def plan-request
  {:seon.agent/id "agent-a"
   :seon.agent.run/id "run-a"
   :seon.agent.run/claim-epoch 3
   :seon.agent.run/plan-digest "reply-a"
   ::driver/sources ["(+ 1 2)" "(clojure.string/upper-case \"x\")"]})

(deftest ordered-plan-is-one-cas-fenced-transaction
  (let [tx-data (driver/plan-tx-data plan-request)
        run-row (last tx-data)]
    (is (= [:db.fn/cas
            [:seon.agent.run/id "run-a"]
            :seon.agent.run/plan-digest nil "reply-a"]
           (nth tx-data 2)))
    (is (= [0 1]
           (mapv :seon.agent.run.form/ordinal
                 (:seon.agent.run/forms run-row))))
    (is (= ["(+ 1 2)" "(clojure.string/upper-case \"x\")"]
           (mapv :seon.agent.run.form/source
                 (:seon.agent.run/forms run-row))))))

(deftest resume-uses-first-nonterminal-ordinal
  (let [forms (:seon.agent.run/forms
               (last (driver/plan-tx-data plan-request)))]
    (is (= 0 (:seon.agent.run.form/ordinal
              (driver/next-form forms []))))
    (is (= 1
           (:seon.agent.run.form/ordinal
            (driver/next-form
             forms
             [{:seon.eval/ordinal 0 :seon.eval/status :done}
              {:seon.eval/ordinal 1 :seon.eval/status :running}]))))
    (is (nil?
         (driver/next-form
          forms
          [{:seon.eval/ordinal 0 :seon.eval/status :done}
           {:seon.eval/ordinal 1 :seon.eval/status :error}])))))

(deftest allocated-run-and-pointer-share-the-generated-identity
  (let [[run cas]
        (driver/open-run-tx-data
         "run-a" "host-1" "message-a" "agent-a"
         #inst "2026-07-25T22:00:00.000-00:00"
         #inst "2026-07-25T22:02:00.000-00:00")]
    (is (= [:seon.agent.run/id (:seon.agent.run/id run)]
           (last cas)))
    (is (= [:seon.agent.message/id "message-a"]
           (:seon.agent.run/cause run)))
    (is (= 1 (:seon.agent.run/claim-epoch run)))))

(deftest completion-value-closes-run-and-delivers-once
  (let [request {:seon.agent/id "agent-a"
                 :seon.agent.run/id "run-a"
                 :seon.agent.run/claim-epoch 3
                 :seon.agent.turn/id "turn-a"
                 :seon.eval/ordinal 2
                 :seon.eval/at
                 #inst "2026-07-25T22:00:00.000-00:00"}
        tx-data
        (driver/lifecycle-tx-data
         request
         {:seon.agent.lifecycle/disposition :completed
          :seon.agent.lifecycle/result "X"})
        message
        (some #(when (:seon.agent.message/id %) %) tx-data)]
    (is (= :closed
           (:seon.agent.run/status
            (some #(when (:seon.agent.run/status %) %) tx-data))))
    (is (= "X" (:seon.agent.message/content message)))
    (is (= "seon.agent.driver/message"
           (:seon.agent.message/id message))
        "the allocation transaction replaces this local placeholder")))

(deftest rejected-agent-value-terminalizes-receipt-alone
  (let [transactions (atom [])
        transact!
        (fn [tx-data]
          (swap! transactions conj tx-data)
          (when (some #{[:poison]} tx-data)
            (throw (ex-info "poison" {})))
          {:db-after {}})
        result
        (driver/execute-form!
         transact!
         (constantly
          {:seon.sci.eval/value :value
           :seon.sci.eval/record
           {:seon.eval/duration-ms 1
            :seon.eval/fn-entries 1
            :seon.eval/allocated-bytes 1}})
         (fn [_ _] [[:poison]])
         {:seon.agent/id "agent-a"
          :seon.agent.run/id "run-a"
          :seon.agent.run/claim-epoch 3
          :seon.agent.turn/id "turn-a"
          :seon.eval/at #inst "2026-07-25T22:00:00.000-00:00"
          :seon.eval/ordinal 0
          :seon.eval/total 2
          :seon.eval/source "(identity :value)"
          :seon.eval/ns 'my.agent.a
          :seon.sci.interrupt/time-limit-ms 100})]
    (is (= :error (:seon.eval/status result)))
    (is (= 3 (count @transactions)))
    (testing "the recovery transaction contains no rejected value"
      (is (not-any? #{[:poison]} (last @transactions))))
    (is (some #(and (map? %)
                    (= "The evaluated value was not admitted."
                       (:seon.eval/error %)))
              (last @transactions)))))

(deftest rejected-plan-transaction-refuses-evaluation
  (let [evaluations (atom 0)
        transactions (atom [])
        allocate!
        (fn [{allocations :seon.db.id/allocations}]
          (let [key (:seon.db.id/key (first allocations))]
            {:seon.db.id/ids
             {key (case key
                    :seon.agent.run/id "run-a"
                    :seon.agent.turn/id "turn-a")}}))
        database-functions
        {'db (constantly {})
         'pull (constantly {})
         'transact!
         (fn [{tx-data :seon.db/tx-data}]
           (swap! transactions conj tx-data)
           (if (some #(and (vector? %)
                           (= :seon.agent.run/plan-digest (nth % 2 nil)))
                     tx-data)
             {:seon.error/message "The plan schema is absent."
              :seon.error/kind :core-bug}
             {:db-after {}}))}
        message ["message-a" "agent-a"
                 "Return one harmless form."
                 #inst "2026-07-25T22:00:00.000-00:00"]
        result
        (with-redefs
          [driver/evaluate!
           (fn [_]
             (swap! evaluations inc)
             {:seon.sci.eval/value :evaluated
              :seon.sci.eval/record
              {:seon.eval/duration-ms 1
               :seon.eval/fn-entries 1
               :seon.eval/allocated-bytes 1}})]
          (#'driver/process-message!
           allocate! database-functions
           (constantly {:seon.ai/text "(identity :must-not-run)"})
           "host-1"
           message))]
    (is (= 0 @evaluations)
        "no form crosses the SCI boundary after the plan write fails")
    (is (= {:seon.error/message "The plan schema is absent."
            :seon.error/kind :core-bug}
           result)
        "the driver surfaces the writer's flat error value")
    (is (some
         (fn [tx-data]
           (and (some #(= :error (:seon.agent.turn/status %))
                      (filter map? tx-data))
                (some #(= :error (:seon.agent.run/closed-reason %))
                      (filter map? tx-data))))
         @transactions)
        "the same failure closes the turn and run durably")))

(deftest completed-turn-persists-a-self-attributing-waterfall
  (let [transactions (atom [])
        next-t (atom 100)
        transact-report!
        (fn [tx-data]
          (let [t (swap! next-t inc)]
            (swap! transactions conj {:tx-data tx-data :t t})
            {:db-after {:t t}}))
        allocate!
        (fn [{allocations :seon.db.id/allocations
              transaction-builder :seon.db.id/transaction-builder}]
          (let [key (:seon.db.id/key (first allocations))
                id (case key
                     :seon.agent.run/id "run-a"
                     :seon.agent.turn/id "turn-a"
                     :seon.agent.message/id "reply-a")
                ids {key id}]
            (merge
             {:seon.db.id/ids ids}
             (when transaction-builder
               (transact-report!
                (:seon.db/tx-data (transaction-builder ids)))))))
        database-functions
        {'db (constantly {})
         'pull (constantly {})
         'transact!
         (fn [{tx-data :seon.db/tx-data}]
           (transact-report! tx-data))}
        message-at (java.util.Date.)
        evaluations (atom 0)
        clock (atom 0)
        result
        (binding [driver/*nano-time* #(swap! clock + 1000)]
          (with-redefs
            [driver/evaluate!
             (fn [_]
               (let [ordinal (swap! evaluations inc)]
                 {:seon.sci.eval/value
                  (if (= 1 ordinal)
                    :first
                    {:seon.agent.lifecycle/disposition :completed
                     :seon.agent.lifecycle/result "done"})
                  :seon.sci.eval/record
                  {:seon.eval/duration-ms 0
                   :seon.eval/fn-entries 1
                   :seon.eval/allocated-bytes 1}}))]
            (#'driver/process-message!
             allocate! database-functions
             (constantly
              {:seon.ai/text
               "(identity :first)\n(seon.agent.lifecycle/complete \"done\")"
               :seon.ai/provider-duration-ns 500})
             "host-1"
             ["message-a" "agent-a" "Finish." message-at])))
        timing-row
        (some
         (fn [{:keys [tx-data]}]
           (some #(when (:seon.agent.turn/timings %) %)
                 (filter map? tx-data)))
         @transactions)
        timings (:seon.agent.turn/timings timing-row)
        total-ns (:seon.agent.turn/duration-ns timing-row)
        attributed-ns
        (reduce + (map :seon.agent.turn.timing/duration-ns timings))
        remainder-ns (- total-ns attributed-ns)
        tolerance-ns (max 5000000 (quot total-ns 100))
        settlement-t (:t (last @transactions))
        measured-transaction-refs
        (into #{}
              (keep :seon.agent.turn.timing/transaction)
              timings)]
    (is (= :done (:seon.eval/status result)))
    (is (= #{[:run-admission-transaction-call 0]
             [:turn-transaction-call 0]
             [:context-derivation 0]
             [:provider-request-response 0]
             [:model-envelope-overhead 0]
             [:reply-derivation 0]
             [:plan-transaction-call 0]
             [:eval-admission-transaction-call 0]
             [:eval 0]
             [:eval-terminal-transaction-call 0]
             [:eval-admission-transaction-call 1]
             [:eval 1]
             [:publish-transaction-call 1]}
           (set
            (map
             (juxt :seon.agent.turn.timing/name
                   :seon.agent.turn.timing/ordinal)
             timings)))
        "every transaction/eval is separate and only the final close publishes")
    (is (<= 0 remainder-ns tolerance-ns)
        "derived unexplained wall stays within 5ms or 1%, whichever is larger")
    (is (not (contains? measured-transaction-refs settlement-t))
        "the timing-settlement transaction is an explicit unmeasured artifact")
    (is (every? pos-int?
                (map :seon.agent.turn.timing/duration-ns timings))
        "nanosecond measurements never turn missing evidence into zero")))
