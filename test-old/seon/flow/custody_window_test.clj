(ns seon.flow.custody-window-test
  "Simulate pre-plan custody recovery with seeded fake model calls."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.agent.ctx.render-fns]
            [seon.agent.driver :as driver]
            [seon.agent.run.core :as run]
            [seon.ai.attempt]
            [seon.db :as db]
            [seon.schema :as schema])
  (:import [java.util Date]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private event-backstop-seconds 20)
(def ^:private custody-seed 20260726)

;; TEST PROTOTYPE ONLY. Candidate B tests these as proposed connections on the
;; existing :seon.ai.attempt entity; production schema remains untouched.
(schema/register! ::attempt-message :seon.db/ref)
(schema/register! ::attempt-process :string)
(schema/register! ::attempt-claim-epoch [:int {:min 1}])
(schema/register! ::attempt-lease-until :inst)

(schema/register!
 ::recovery-receipt-id
 [:string {:seon.db/identity true}])
(schema/register! ::recovery-receipt-run :seon.db/ref)
(schema/register! ::recovery-receipt-reason :keyword)
(schema/register!
 ::model-call-id
 [:string {:seon.db/identity true}])
(schema/register! ::model-call-message :seon.db/ref)

(def ^:private database-attributes
  [:seon.agent/id
   :seon.agent/run
   :seon.agent.message/id
   :seon.agent.message/to
   :seon.agent.run/id
   :seon.agent.run/agent
   :seon.agent.run/cause
   :seon.agent.run/started-at
   :seon.agent.run/status
   :seon.agent.run/process
   :seon.agent.run/claim-epoch
   :seon.agent.run/lease-until
   :seon.agent.run/closed-reason
   :seon.agent.run/closed-at
   :seon.agent.run/plan-digest
   :seon.agent.run/forms
   :seon.agent.run.form/id
   :seon.agent.run.form/run
   :seon.agent.run.form/ordinal
   :seon.agent.run.form/source
   :seon.ai.attempt/id
   :seon.ai.attempt/ordinal
   :seon.ai.attempt/config-digest
   :seon.ai.attempt/deadline-at
   :seon.ai.attempt/provider
   :seon.ai.attempt/adapter
   :seon.ai.attempt/outer-timeout-ms
   :seon.ai.attempt/stream?
   :seon.ai.attempt/reply-evaluation
   :seon.ai.attempt/outcome
   :seon.ai.attempt/evidence-error
   ::attempt-message
   ::attempt-process
   ::attempt-claim-epoch
   ::attempt-lease-until
   ::recovery-receipt-id
   ::recovery-receipt-run
   ::recovery-receipt-reason
   ::model-call-id
   ::model-call-message])

(def ^:private current-pending-message-query
  '[:find [?message-id ...]
    :where
    [?message :seon.agent.message/id ?message-id]
    [?message :seon.agent.message/to ?agent]
    (not [?run :seon.agent.run/cause ?message])])

(def ^:private current-recoverable-run-query
  '[:find [?run-id ...]
    :where
    [?agent :seon.agent/run ?run]
    [?run :seon.agent.run/id ?run-id]
    [?run :seon.agent.run/status :open]
    [?run :seon.agent.run/plan-digest _]])

(def ^:private candidate-a-planless-query
  '[:find ?run-id ?agent-id ?epoch ?lease
    :in $ ?now
    :where
    [?agent :seon.agent/id ?agent-id]
    [?agent :seon.agent/run ?run]
    [?run :seon.agent.run/id ?run-id]
    [?run :seon.agent.run/status :open]
    [?run :seon.agent.run/process _]
    [?run :seon.agent.run/claim-epoch ?epoch]
    [?run :seon.agent.run/lease-until ?lease]
    (not [?run :seon.agent.run/plan-digest _])
    [(compare ?lease ?now) ?ordering]
    [(<= ?ordering 0)]])

(def ^:private candidate-a-pending-message-query
  '[:find [?message-id ...]
    :where
    [?message :seon.agent.message/id ?message-id]
    (not-join [?message]
      [?run :seon.agent.run/cause ?message]
      [?run :seon.agent.run/status :open])
    (not-join [?message]
      [?run :seon.agent.run/cause ?message]
      [?run :seon.agent.run/plan-digest _])])

(def ^:private candidate-b-recoverable-attempt-query
  '[:find ?attempt-id ?epoch ?lease
    :in $ ?now
    :where
    [?attempt :seon.ai.attempt/id ?attempt-id]
    [?attempt :seon.ai.attempt/outcome :open]
    [?attempt :seon.flow.custody-window-test/attempt-process _]
    [?attempt :seon.flow.custody-window-test/attempt-claim-epoch ?epoch]
    [?attempt :seon.flow.custody-window-test/attempt-lease-until ?lease]
    [(compare ?lease ?now) ?ordering]
    [(<= ?ordering 0)]])

(def ^:private candidate-b-pending-message-query
  '[:find [?message-id ...]
    :where
    [?message :seon.agent.message/id ?message-id]
    (not-join [?message]
      [?attempt :seon.flow.custody-window-test/attempt-message ?message]
      [?attempt :seon.ai.attempt/outcome :open])
    (not-join [?message]
      [?run :seon.agent.run/cause ?message])])

(def ^:private run-without-plan-query
  '[:find [?run-id ...]
    :where
    [?run :seon.agent.run/id ?run-id]
    [?run :seon.agent.run/status :open]
    (not [?run :seon.agent.run/plan-digest _])])

(defn- await-latch!
  [^CountDownLatch latch event]
  (when-not (.await latch event-backstop-seconds TimeUnit/SECONDS)
    (throw
     (ex-info
      "The custody-window simulation did not observe its event."
      {::event event}))))

(defn- take-report!
  [report-channel event]
  (let [[value selected]
        (async/alts!!
         [report-channel
          (async/timeout
           (.toMillis TimeUnit/SECONDS event-backstop-seconds))])]
    (when-not (= selected report-channel)
      (throw
       (ex-info
        "The custody-window model proc did not report completion."
        {::event event})))
    value))

(defn- with-custody-database
  [body]
  (let [configuration
        {:store {:backend :memory :id (random-uuid)}
         :schema-flexibility :write
         :keep-history? true}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (d/transact
       connection
       (db/malli->datahike-schema database-attributes))
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(defn- seeded-model-outcome
  [seed ordinal]
  (if (even? (+ seed ordinal))
    ::kill
    ::reply))

(defn- fake-model-proc
  [{::keys [seed ordinal entered release finished before-call! reply!]}]
  (flow/process
   (flow/map->step
    {:describe
     (fn []
       {:ins {::model-request "One seeded fake provider request."}
        :workload :io})
     :init (fn [_] {::completed 0})
     :transform
     (fn [state _input request]
       (try
         (before-call! request)
         (.countDown ^CountDownLatch entered)
         (await-latch! release ::release-model-call)
         (case (seeded-model-outcome seed ordinal)
           ::kill
           (throw
            (ex-info
             "Injected process death inside the provider custody window."
             {::seed seed
              ::ordinal ordinal}))

           ::reply
           (do
             (reply! request)
             [(update state ::completed inc)
              {::flow/report
               [{::event ::model-replied
                 ::seed seed
                 ::ordinal ordinal}]}]))
         (finally
           (.countDown ^CountDownLatch finished))))})))

(defn- run-fake-model!
  [{::keys [seed ordinal before-call! reply!]}]
  (let [entered (CountDownLatch. 1)
        release
        (CountDownLatch.
         (if (= ::kill (seeded-model-outcome seed ordinal)) 1 0))
        finished (CountDownLatch. 1)
        graph
        (flow/create-flow
         {:procs
          {::model
           {:proc
            (fake-model-proc
             {::seed seed
              ::ordinal ordinal
              ::entered entered
              ::release release
              ::finished finished
              ::before-call! before-call!
              ::reply! reply!})}}
          :conns []})
        {:keys [report-chan]} (flow/start graph)
        expected (seeded-model-outcome seed ordinal)]
    (try
      (flow/resume graph)
      @(flow/inject
        graph [::model ::model-request]
        [{::seed seed ::ordinal ordinal}])
      (await-latch! entered ::entered-model-call)
      (if (= ::kill expected)
        (do
          (flow/stop graph)
          (.countDown release)
          (await-latch! finished ::killed-model-call-finished))
        (do
          (take-report! report-chan ::model-replied)
          (await-latch! finished ::successful-model-call-finished)))
      expected
      (finally
        (.countDown release)
        (flow/stop graph)))))

(defn- seed-message!
  [connection agent-id message-id]
  (d/transact
   connection
   [{:seon.agent/id agent-id}
    {:seon.agent.message/id message-id
     :seon.agent.message/to [[:seon.agent/id agent-id]]}]))

(defn- model-call-count
  [database message-id]
  (d/q
   '[:find (count ?call) .
     :in $ ?message-id
     :where
     [?message :seon.agent.message/id ?message-id]
     [?call :seon.flow.custody-window-test/model-call-message ?message]]
   database message-id))

(defn- open-run-count
  [database]
  (or
   (d/q
    '[:find (count ?run) .
      :where [?run :seon.agent.run/status :open]]
    database)
   0))

(defn- recovery-receipt-count
  [database run-id]
  (d/q
   '[:find (count ?receipt) .
     :in $ ?run-id
     :where
     [?run :seon.agent.run/id ?run-id]
     [?receipt
      :seon.flow.custody-window-test/recovery-receipt-run ?run]]
   database run-id))

(defn- transact-logical-model-call!
  [connection message-id]
  (d/transact
   connection
   [{::model-call-id (str message-id "/model")
     ::model-call-message [:seon.agent.message/id message-id]}]))

(defn- recover-planless-run!
  [connection agent-id run-id observed-lease]
  (let [closed-at (Date.)
        next-lease (Date. (+ (.getTime closed-at) 60000))]
    (d/transact
     connection
     (into
      (run/steal-tx-data
       agent-id run-id 1 observed-lease "recovery-process" next-lease)
      (concat
       [{::recovery-receipt-id (str run-id "/recovery/2")
         ::recovery-receipt-run [:seon.agent.run/id run-id]
         ::recovery-receipt-reason :failed-before-plan}]
       (run/finish-tx-data
        agent-id run-id 2 :failed-before-plan closed-at))))))

(defn- attempt-row
  [attempt-id message-id ordinal process lease-until]
  {:seon.ai.attempt/id attempt-id
   :seon.ai.attempt/ordinal (long ordinal)
   :seon.ai.attempt/config-digest (apply str (repeat 64 "a"))
   :seon.ai.attempt/deadline-at lease-until
   :seon.ai.attempt/provider :stub
   :seon.ai.attempt/adapter :stub
   :seon.ai.attempt/outer-timeout-ms 60000
   :seon.ai.attempt/stream? false
   :seon.ai.attempt/reply-evaluation :batch
   :seon.ai.attempt/outcome :open
   ::attempt-message [:seon.agent.message/id message-id]
   ::attempt-process process
   ::attempt-claim-epoch 1
   ::attempt-lease-until lease-until})

(defn- recover-attempt!
  [connection attempt-id observed-lease]
  (let [attempt-ref [:seon.ai.attempt/id attempt-id]]
    (d/transact
     connection
     [[:db.fn/cas attempt-ref ::attempt-lease-until
       observed-lease observed-lease]
      [:db.fn/cas attempt-ref ::attempt-claim-epoch 1 2]
      [:db.fn/cas attempt-ref :seon.ai.attempt/outcome :open :crashed]
      [:db/add attempt-ref :seon.ai.attempt/evidence-error
       "The provider holder died before publishing a reply."]
      [:db/retract attempt-ref ::attempt-process]])))

(defn- freeze-run-and-plan!
  [connection agent-id message-id attempt-id run-id]
  (let [attempt-ref [:seon.ai.attempt/id attempt-id]
        run-ref [:seon.agent.run/id run-id]
        at (Date.)
        lease-until (Date. (+ (.getTime at) 60000))]
    (d/transact
     connection
     [[:db.fn/cas attempt-ref ::attempt-claim-epoch 1 1]
      [:db.fn/cas attempt-ref :seon.ai.attempt/outcome :open :success]
      {:seon.agent.run/id run-id
       :seon.agent.run/agent [:seon.agent/id agent-id]
       :seon.agent.run/cause [:seon.agent.message/id message-id]
       :seon.agent.run/started-at at
       :seon.agent.run/status :open
       :seon.agent.run/process "model-process-2"
       :seon.agent.run/claim-epoch 1
       :seon.agent.run/lease-until lease-until
       :seon.agent.run/plan-digest
       "0000000000000000000000000000000000000000000000000000000000000000"
       :seon.agent.run/forms
       #{{:seon.agent.run.form/id (driver/form-id run-id 0)
          :seon.agent.run.form/run run-ref
          :seon.agent.run.form/ordinal 0
          :seon.agent.run.form/source "(+ 1 1)"}}}
      [:db.fn/cas [:seon.agent/id agent-id] :seon.agent/run nil run-ref]
      [:db/retract attempt-ref ::attempt-process]])))

(deftest candidate-a-recovers-a-planless-run-and-retries-once
  (with-custody-database
    (fn [connection]
      (let [agent-id "custody-agent-a"
            message-id "custody-message-a"
            failed-run-id "custody-run-a1"
            retry-run-id "custody-run-a2"
            expired-lease (Date. 1000)
            invocations (atom 0)]
        (seed-message! connection agent-id message-id)
        (d/transact
         connection
         (driver/open-run-tx-data
          failed-run-id "model-process-1" message-id agent-id
          (Date. 0) expired-lease))

        (is (= ::kill
               (run-fake-model!
                {::seed custody-seed
                 ::ordinal 0
                 ::before-call!
                 (fn [_]
                   (swap! invocations inc)
                   (transact-logical-model-call!
                    connection message-id))
                 ::reply! (fn [_])})))

        (testing "the current two queries both miss the killed run"
          (is (empty? (d/q current-recoverable-run-query @connection)))
          (is (empty? (d/q current-pending-message-query @connection))))

        (let [planless
              (vec
               (d/q
                candidate-a-planless-query @connection (Date. 2000)))
              [_ _ _ observed-lease] (first planless)]
          (is (= [[failed-run-id agent-id 1 expired-lease]] planless))
          (recover-planless-run!
           connection agent-id failed-run-id observed-lease))

        (testing "closing failed custody re-derives exactly one wake"
          (is (= [message-id]
                 (d/q candidate-a-pending-message-query @connection)))
          (is (= 1
                 (recovery-receipt-count
                  @connection failed-run-id))))

        (d/transact
         connection
         (driver/open-run-tx-data
          retry-run-id "model-process-2" message-id agent-id
          (Date. 2000) (Date. 62000)))
        (is (= ::reply
               (run-fake-model!
                {::seed custody-seed
                 ::ordinal 1
                 ::before-call!
                 (fn [_]
                   (swap! invocations inc)
                   (transact-logical-model-call!
                    connection message-id))
                 ::reply!
                 (fn [_]
                   (d/transact
                    connection
                    (driver/plan-tx-data
                     {:seon.agent/id agent-id
                      :seon.agent.run/id retry-run-id
                      :seon.agent.run/claim-epoch 1
                      :seon.agent.run/plan-digest
                      "1111111111111111111111111111111111111111111111111111111111111111"
                      :seon.agent.driver/sources ["(+ 1 1)"]}))
                   (d/transact
                    connection
                    (run/finish-tx-data
                     agent-id retry-run-id 1 :completed (Date.))))})))

        (testing "one physical retry remains one logical model-call fact"
          (is (= 2 @invocations))
          (is (= 1 (dec @invocations)) "exactly one retry occurred")
          (is (= 1 (model-call-count @connection message-id)))
          (is (zero? (open-run-count @connection)))
          (is (empty?
               (d/q candidate-a-pending-message-query @connection))))))))

(deftest candidate-b-keeps-runs-unrepresentable-until-plan-freeze
  (with-custody-database
    (fn [connection]
      (let [agent-id "custody-agent-b"
            message-id "custody-message-b"
            first-attempt-id "custody-attempt-b0"
            retry-attempt-id "custody-attempt-b1"
            run-id "custody-run-b"
            expired-lease (Date. 1000)
            observed-values (atom [])]
        (seed-message! connection agent-id message-id)
        (is (= [message-id]
               (d/q candidate-b-pending-message-query @connection)))

        (is (= ::kill
               (run-fake-model!
                {::seed custody-seed
                 ::ordinal 0
                 ::before-call!
                 (fn [_]
                   (d/transact
                    connection
                    [(attempt-row
                      first-attempt-id message-id 0
                      "model-process-1" expired-lease)])
                   (swap! observed-values conj @connection))
                 ::reply! (fn [_])})))

        (testing "attempt custody damps the wake without creating a run"
          (is (empty?
               (d/q candidate-b-pending-message-query @connection)))
          (is (every?
               empty?
               (map #(d/q run-without-plan-query %)
                    @observed-values))))

        (let [recoverable
              (vec
               (d/q
                candidate-b-recoverable-attempt-query
                @connection (Date. 2000)))
              [_ _ observed-lease] (first recoverable)]
          (is (= [[first-attempt-id 1 expired-lease]] recoverable))
          (recover-attempt!
           connection first-attempt-id observed-lease))

        (is (= [message-id]
               (d/q candidate-b-pending-message-query @connection))
            "a crashed attempt makes the same message eligible for retry")

        (is (= ::reply
               (run-fake-model!
                {::seed custody-seed
                 ::ordinal 1
                 ::before-call!
                 (fn [_]
                   (d/transact
                    connection
                    [(attempt-row
                      retry-attempt-id message-id 1
                      "model-process-2" (Date. 62000))])
                   (swap! observed-values conj @connection))
                 ::reply!
                 (fn [_]
                   (freeze-run-and-plan!
                    connection agent-id message-id
                    retry-attempt-id run-id)
                   (swap! observed-values conj @connection))})))

        (testing "run identity and plan digest first appear in one transaction"
          (is (every?
               empty?
               (map #(d/q run-without-plan-query %)
                    @observed-values)))
          (is (= 1 (open-run-count @connection)))
          (is (= [run-id]
                 (d/q current-recoverable-run-query @connection)))
          (is (empty?
               (d/q candidate-b-pending-message-query @connection)))
          (is
           (let [[id-tx plan-tx]
                 (d/q
                  '[:find [?id-tx ?plan-tx]
                    :in $ ?run-id
                    :where
                    [?run :seon.agent.run/id ?run-id ?id-tx]
                    [?run :seon.agent.run/plan-digest _ ?plan-tx]]
                  @connection run-id)]
             (= id-tx plan-tx))))

        (testing "attempt outcomes preserve the recoverable provider history"
          (is (= #{:crashed :success}
                 (set
                  (d/q
                   '[:find [?outcome ...]
                     :where
                     [?attempt :seon.ai.attempt/outcome ?outcome]]
                   @connection))))
          (is (empty?
               (d/q candidate-b-recoverable-attempt-query
                    @connection (Date. 63000)))))))))
