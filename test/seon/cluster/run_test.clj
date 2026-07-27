(ns seon.cluster.run-test
  "Sealed acceptance for the run model (revised 2026-07-27).

  Orchestrator-authored. The implementation lane makes these green by
  implementing the seon.cluster.run `*-call` transitions ONLY — schemas
  and tests are byte-sealed; friction is reported, never resolved by
  weakening. Everything runs against in-memory Datahike in-process.

  The acceptance surface is the state-machine property: generated
  command sequences run against the real database while a pure MODEL
  decides, for every command, whether the transition must commit or
  refuse. A transition that commits when the model says refuse (a
  stolen live lease, a reopened closed run, a second plan) or refuses
  when the model says commit is a counterexample. Invariants over
  durable facts are asserted after every command."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.cluster.run :as run]
            [seon.schema]
            [seon.schema.datahike :as schema.datahike]))

;;; ---------------------------------------------------------------------------
;;; In-memory database fixture
;;; ---------------------------------------------------------------------------

(def ^:private model-attributes
  [:seon.cluster.agent/id
   :seon.cluster.agent/run
   :seon.cluster.run/id
   :seon.cluster.run/agent
   :seon.cluster.run/opened-at
   :seon.cluster.run/closed-at
   :seon.cluster.run/process
   :seon.cluster.run/claim-epoch
   :seon.cluster.run/lease-until
   :seon.cluster.run/plan-digest
   :seon.cluster.run/forms
   :seon.cluster.run.form/id
   :seon.cluster.run.form/run
   :seon.cluster.run.form/ordinal
   :seon.cluster.run.form/source
   :seon.cluster.eval/id
   :seon.cluster.eval/run
   :seon.cluster.eval/ordinal
   :seon.cluster.eval/claim-epoch
   :seon.cluster.eval/at
   :seon.cluster.eval/status
   :seon.cluster.eval/result-edn
   :seon.cluster.eval/error])

(defn- with-model-database [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (d/transact connection
                  (schema.datahike/malli->datahike-schema model-attributes))
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

;; Deterministic clock: every generated time is an offset from t0.
(def ^:private t0-ms 1785000000000)
(defn- at [offset-ms] (java.util.Date. (long (+ t0-ms offset-ms))))
(def ^:private t0 (at 0))
(def ^:private t1 (at 300000))
(def ^:private t2 (at 1800000))

(defn- transact-or-refusal
  "Commit tx-data; a refusal (any throw) returns its ex-data as a value."
  [connection tx-data]
  (try
    (d/transact connection tx-data)
    ::committed
    (catch Exception e
      (or (ex-data e) {::opaque (ex-message e)}))))

(defn- run-entity [connection run-id]
  (d/pull (d/db connection) '[*] [:seon.cluster.run/id run-id]))

(defn- agent-pointer [connection agent-id]
  (get-in (d/pull (d/db connection)
                  [{:seon.cluster.agent/run [:seon.cluster.run/id]}]
                  [:seon.cluster.agent/id agent-id])
          [:seon.cluster.agent/run :seon.cluster.run/id]))

;;; ---------------------------------------------------------------------------
;;; Derivations — state is computed from primitives, never stored
;;; ---------------------------------------------------------------------------

(deftest state-is-derived-from-primitives
  (testing "open is the absence of closed-at"
    (is (true? (run/open? {})))
    (is (false? (run/open? {::run/closed-at t2}))))
  (testing "claimed means a holder under a live lease"
    (is (true? (run/claimed? {::run/process "p1" ::run/lease-until t2} t1)))
    (is (false? (run/claimed? {::run/process "p1" ::run/lease-until t0} t1)))
    (is (false? (run/claimed? {} t1))))
  (testing "expired means open with a lapsed holder"
    (is (true? (run/expired? {::run/process "p1" ::run/lease-until t0} t1)))
    (is (false? (run/expired? {::run/process "p1" ::run/lease-until t2} t1)))
    (is (false? (run/expired? {::run/closed-at t2
                               ::run/process "p1"
                               ::run/lease-until t0} t1)))))

(deftest interrupted-warning-is-one-derived-value
  (let [forms [{:seon.cluster.run.form/ordinal 0}
               {:seon.cluster.run.form/ordinal 1}
               {:seon.cluster.run.form/ordinal 2}]]
    (testing "clean receipts derive no warning at all"
      (is (nil? (run/interrupted-warning
                 forms
                 [{:seon.cluster.eval/ordinal 0
                   :seon.cluster.eval/status :done}]))))
    (testing "an interrupted receipt derives exactly one warning naming
              the first interrupted ordinal and the missing tail"
      (let [warning (run/interrupted-warning
                     forms
                     [{:seon.cluster.eval/ordinal 0
                       :seon.cluster.eval/status :done}
                      {:seon.cluster.eval/ordinal 1
                       :seon.cluster.eval/status :interrupted}])]
        (is (= 1 (:seon.cluster.eval/ordinal warning)))
        (is (= 2 (:seon.cluster.run/missing-results warning)))))))

;;; ---------------------------------------------------------------------------
;;; Teaching examples — the call shapes, one committed lifecycle
;;; ---------------------------------------------------------------------------

(deftest one-run-lifecycle-teaches-the-call-shapes
  (with-model-database
    (fn [connection]
      (d/transact connection [{:seon.cluster.agent/id "teacher"}])
      (testing "open: run entity + agent pointer from ONE agent ref"
        (is (= ::committed
               (transact-or-refusal
                connection
                (run/open-tx {::run/id "lesson"
                              ::run/agent [:seon.cluster.agent/id "teacher"]
                              ::run/opened-at t0}))))
        (is (= "lesson" (agent-pointer connection "teacher"))))
      (testing "claim an unheld open run"
        (is (= ::committed
               (transact-or-refusal
                connection
                (run/claim-tx {::run/id "lesson"
                               ::run/process "p1"
                               ::run/lease-until t2
                               ::run/now t1}))))
        (is (= 1 (::run/claim-epoch (run-entity connection "lesson")))))
      (testing "plan freezes once, with its ordered owned forms"
        (is (= ::committed
               (transact-or-refusal
                connection
                (run/plan-tx {::run/id "lesson"
                              ::run/process "p1"
                              ::run/claim-epoch 1
                              ::run/plan-digest "digest-a"
                              ::run/sources ["(+ 1 1)" "(+ 2 2)"]}))))
        (is (= ["(+ 1 1)" "(+ 2 2)"]
               (->> (d/q '[:find ?ordinal ?source
                           :in $ ?run-id
                           :where
                           [?run :seon.cluster.run/id ?run-id]
                           [?form :seon.cluster.run.form/run ?run]
                           [?form :seon.cluster.run.form/ordinal ?ordinal]
                           [?form :seon.cluster.run.form/source ?source]]
                         (d/db connection) "lesson")
                    (sort-by first)
                    (mapv second)))))
      (testing "heartbeat renews the holder's lease under its epoch"
        (is (= ::committed
               (transact-or-refusal
                connection
                (run/heartbeat-tx {::run/id "lesson"
                                   ::run/process "p1"
                                   ::run/claim-epoch 1
                                   ::run/lease-until (at 3600000)})))))
      (testing "close settles the run and retracts the pointer it
                derived from the run's own agent connection"
        (is (= ::committed
               (transact-or-refusal
                connection
                (run/close-tx {::run/id "lesson"
                               ::run/process "p1"
                               ::run/claim-epoch 1
                               ::run/closed-at t2}))))
        (let [entity (run-entity connection "lesson")]
          (is (false? (run/open? {::run/closed-at
                                  (::run/closed-at entity)})))
          (is (nil? (::run/process entity)))
          (is (nil? (::run/lease-until entity))))
        (is (nil? (agent-pointer connection "teacher")))))))

;;; ---------------------------------------------------------------------------
;;; The state machine — generated command sequences against a pure model
;;; ---------------------------------------------------------------------------

(def ^:private agent-ids ["a1" "a2"])
(def ^:private process-ids ["p1" "p2" "p3"])
(def ^:private run-ids ["r1" "r2" "r3"])

(def ^:private command-gen
  "One generated command. Times are monotonic per sequence position:
  the runner assigns now = (at (* index 60000)) so eligibility windows
  are decided by the generated lease spans, not wall clocks."
  (gen/one-of
   [(gen/tuple (gen/return :open)
               (gen/elements run-ids)
               (gen/elements agent-ids))
    (gen/tuple (gen/return :claim)
               (gen/elements run-ids)
               (gen/elements process-ids)
               ;; lease span in minutes ahead of now: 0 = already lapsed
               ;; at the next step, longer spans stay live across steps
               (gen/choose 0 5))
    (gen/tuple (gen/return :heartbeat)
               (gen/elements run-ids)
               (gen/elements process-ids)
               ;; epoch guess: :current resolves from the model, the
               ;; others probe stale and absurd fences
               (gen/elements [:current :stale :absurd])
               (gen/choose 1 5))
    (gen/tuple (gen/return :release)
               (gen/elements run-ids)
               (gen/elements process-ids)
               (gen/elements [:current :stale :absurd]))
    (gen/tuple (gen/return :close)
               (gen/elements run-ids)
               (gen/elements process-ids)
               (gen/elements [:current :stale :absurd]))
    (gen/tuple (gen/return :plan)
               (gen/elements run-ids)
               (gen/elements process-ids)
               (gen/elements ["digest-a" "digest-b"]))
    (gen/tuple (gen/return :receipt)
               (gen/elements run-ids)
               (gen/choose 0 3)
               (gen/elements [:running :done :error]))
    (gen/tuple (gen/return :recover)
               (gen/set (gen/elements process-ids)))]))

(defn- resolve-epoch [model run-id guess]
  (let [current (get-in model [:runs run-id :epoch] 0)]
    (case guess
      :current (max current 1)
      :stale (max (dec current) 1)
      :absurd 99)))

(defn- model-eligible?
  "The pure oracle: must this command COMMIT against `model` at `now`?"
  [model [op & args] now]
  (let [run-of #(get-in model [:runs %])]
    (case op
      :open (let [[run-id agent-id] args]
              (and (nil? (run-of run-id))
                   (nil? (get-in model [:pointers agent-id]))))
      :claim (let [[run-id _process _span] args
                   {:keys [closed process lease]} (run-of run-id)]
               (and (some? (run-of run-id))
                    (not closed)
                    (or (nil? process)
                        (<= (inst-ms lease) (inst-ms now)))))
      (:heartbeat :release :close)
      (let [[run-id process guess] args
            {:keys [closed] :as entry} (run-of run-id)]
        (and (some? entry)
             (not closed)
             (= process (:process entry))
             (= (resolve-epoch model run-id guess) (:epoch entry))))
      :plan (let [[run-id process _digest] args
                  {:keys [closed digest] :as entry} (run-of run-id)]
              (and (some? entry)
                   (not closed)
                   (= process (:process entry))
                   (nil? digest)))
      :receipt (some? (run-of (first args)))
      :recover true)))

(defn- model-apply
  "Advance the pure model by one COMMITTED command."
  [model [op & args] now]
  (case op
    :open (let [[run-id agent-id] args]
            (-> model
                (assoc-in [:runs run-id]
                          {:agent agent-id :epoch 0})
                (assoc-in [:pointers agent-id] run-id)))
    :claim (let [[run-id process span] args]
             (update-in model [:runs run-id]
                        #(-> %
                             (update :epoch inc)
                             (assoc :process process
                                    :lease (at (+ (inst-ms now)
                                                  (* span 60000)
                                                  (- t0-ms)))))))
    :heartbeat (let [[run-id _ _ span] args]
                 (assoc-in model [:runs run-id :lease]
                           (at (+ (inst-ms now) (* span 60000) (- t0-ms)))))
    :release (let [[run-id] args]
               (update-in model [:runs run-id] dissoc :process :lease))
    :close (let [[run-id] args
                 agent-id (get-in model [:runs run-id :agent])]
             (-> model
                 (update-in [:runs run-id]
                            #(-> % (assoc :closed true)
                                 (dissoc :process :lease)))
                 (update :pointers dissoc agent-id)))
    :plan (let [[run-id _ digest] args]
            (assoc-in model [:runs run-id :digest] digest))
    :receipt (let [[run-id ordinal status] args]
               (assoc-in model [:receipts [run-id ordinal]] status))
    :recover (let [[live] args]
               (-> model
                   (update :receipts
                           #(into {} (map (fn [[k v]]
                                            [k (if (= :running v)
                                                 :interrupted v)]))
                                  %))
                   (update :runs
                           #(into {}
                                  (map (fn [[id entry]]
                                         [id (if (and (:process entry)
                                                      (not (contains?
                                                            live
                                                            (:process entry))))
                                               (dissoc entry :process :lease)
                                               entry)]))
                                  %))))))

(defn- execute!
  "Run one command against the real database. Returns ::committed or
  refusal data. `:receipt` and `:recover` are direct machinery, not
  transitions."
  [connection model [op & args :as command] now]
  (case op
    :open (let [[run-id agent-id] args]
            (transact-or-refusal
             connection
             (run/open-tx {::run/id run-id
                           ::run/agent [:seon.cluster.agent/id agent-id]
                           ::run/opened-at now})))
    :claim (let [[run-id process span] args]
             (transact-or-refusal
              connection
              (run/claim-tx {::run/id run-id
                             ::run/process process
                             ::run/lease-until (at (+ (inst-ms now)
                                                      (* span 60000)
                                                      (- t0-ms)))
                             ::run/now now})))
    :heartbeat (let [[run-id process guess span] args]
                 (transact-or-refusal
                  connection
                  (run/heartbeat-tx
                   {::run/id run-id
                    ::run/process process
                    ::run/claim-epoch (resolve-epoch model run-id guess)
                    ::run/lease-until (at (+ (inst-ms now)
                                             (* span 60000)
                                             (- t0-ms)))})))
    :release (let [[run-id process guess] args]
               (transact-or-refusal
                connection
                (run/release-tx
                 {::run/id run-id
                  ::run/process process
                  ::run/claim-epoch (resolve-epoch model run-id guess)})))
    :close (let [[run-id process guess] args]
             (transact-or-refusal
              connection
              (run/close-tx
               {::run/id run-id
                ::run/process process
                ::run/claim-epoch (resolve-epoch model run-id guess)
                ::run/closed-at now})))
    :plan (let [[run-id process digest] args]
            (transact-or-refusal
             connection
             (run/plan-tx
              {::run/id run-id
               ::run/process process
               ::run/claim-epoch (get-in model [:runs run-id :epoch] 1)
               ::run/plan-digest digest
               ::run/sources ["(+ 1 1)"]})))
    :receipt (let [[run-id ordinal status] args]
               (transact-or-refusal
                connection
                [{:seon.cluster.eval/id (pr-str [run-id ordinal])
                  :seon.cluster.eval/run [:seon.cluster.run/id run-id]
                  :seon.cluster.eval/ordinal ordinal
                  :seon.cluster.eval/claim-epoch 1
                  :seon.cluster.eval/at now
                  :seon.cluster.eval/status status}]))
    :recover (let [[live] args
                   tx (into []
                            (mapcat
                             (fn [run-id]
                               (when (some? (get-in model [:runs run-id]))
                                 (run/recover-tx
                                  {::run/run (run-entity connection run-id)
                                   ::run/receipts
                                   (mapv #(d/pull (d/db connection) '[*] %)
                                         (d/q '[:find [?r ...]
                                                :in $ ?run-id
                                                :where
                                                [?run :seon.cluster.run/id
                                                 ?run-id]
                                                [?r :seon.cluster.eval/run
                                                 ?run]]
                                              (d/db connection) run-id))
                                   ::run/live-processes live}))))
                            run-ids)]
               (if (seq tx)
                 (transact-or-refusal connection tx)
                 ::committed))))

(defn- invariants-hold?
  "Durable-fact invariants checked after EVERY command."
  [connection model]
  (every?
   identity
   (for [run-id run-ids
         :let [entry (get-in model [:runs run-id])]
         :when (some? entry)
         :let [entity (run-entity connection run-id)]]
     (and
      ;; the database agrees with the model on custody and closure
      (= (:process entry) (::run/process entity))
      (= (boolean (:closed entry))
         (some? (::run/closed-at entity)))
      (= (or (:epoch entry) 0) (or (::run/claim-epoch entity) 0))
      ;; a closed run holds no custody
      (or (not (:closed entry))
          (and (nil? (::run/process entity))
               (nil? (::run/lease-until entity))))
      ;; the agent pointer exists exactly while its run is open
      (let [agent-id (:agent entry)
            pointer (agent-pointer connection agent-id)]
        (if (:closed entry)
          (not= run-id pointer)
          (= run-id pointer)))
      ;; plan digest is write-once
      (= (:digest entry) (::run/plan-digest entity))))))

(deftest transitions-agree-with-the-model
  ;; One FRESH database per trial (and per shrink step): the pure model
  ;; resets every trial, so the world it reasons about must too.
  (let [check
        (tc/quick-check
         60
         (prop/for-all [commands (gen/vector command-gen 1 15)]
           (with-model-database
             (fn [connection]
               (d/transact connection
                           (mapv (fn [id] {:seon.cluster.agent/id id})
                                 agent-ids))
               (loop [commands commands
                      model {:runs {} :pointers {} :receipts {}}
                      index 0]
                 (if (empty? commands)
                   true
                   (let [command (first commands)
                         now (at (* (inc index) 60000))
                         expected (model-eligible? model command now)
                         result (execute! connection model command now)
                         committed? (= ::committed result)]
                     (cond
                       (not= expected committed?)
                       (do (println "ORACLE DISAGREEMENT"
                                    {:command command :now now
                                     :expected (if expected
                                                 :commit :refuse)
                                     :actual result
                                     :model model})
                           false)

                       :else
                       (let [model' (if committed?
                                      (model-apply model command now)
                                      model)]
                         (if (invariants-hold? connection model')
                           (recur (rest commands) model' (inc index))
                           (do (println "INVARIANT VIOLATION"
                                        {:command command :model model'})
                               false))))))))))
         :seed 20260727)]
    (is (true? (:result check))
        (str "state-machine property failed: " (pr-str check)))))

;;; ---------------------------------------------------------------------------
;;; Recovery preserves every terminal receipt byte-for-byte
;;; ---------------------------------------------------------------------------

(def ^:private receipt-status-gen
  (gen/elements [:running :done :error]))

(deftest recovery-preserves-terminal-receipts-exactly
  (with-model-database
    (fn [connection]
      (d/transact connection [{:seon.cluster.agent/id "keeper"}])
      (let [pull-terminals
            (fn [run-id]
              (->> (d/q '[:find [?r ...]
                          :in $ ?run-id
                          :where
                          [?run :seon.cluster.run/id ?run-id]
                          [?r :seon.cluster.eval/run ?run]]
                        (d/db connection) run-id)
                   (mapv #(d/pull (d/db connection) '[*] %))
                   (filterv #(contains? #{:done :error}
                                        (:seon.cluster.eval/status %)))
                   (sort-by :seon.cluster.eval/ordinal)
                   vec))
            check
            (tc/quick-check
             30
             (prop/for-all [statuses (gen/vector receipt-status-gen 1 5)
                            dead? gen/boolean
                            round gen/nat]
               (let [run-id (str "keep-" round "-" (random-uuid))
                     agent-id (str "keeper-" run-id)
                     holder (if dead? "dead-process" "live-process")]
                 (d/transact connection
                             [{:seon.cluster.agent/id agent-id}])
                 (d/transact connection
                             (run/open-tx
                              {::run/id run-id
                               ::run/agent [:seon.cluster.agent/id agent-id]
                               ::run/opened-at t0}))
                 (d/transact connection
                             (run/claim-tx {::run/id run-id
                                            ::run/process holder
                                            ::run/lease-until t2
                                            ::run/now t1}))
                 (d/transact
                  connection
                  (vec (map-indexed
                        (fn [ordinal status]
                          {:seon.cluster.eval/id (pr-str [run-id ordinal 1])
                           :seon.cluster.eval/run
                           [:seon.cluster.run/id run-id]
                           :seon.cluster.eval/ordinal ordinal
                           :seon.cluster.eval/claim-epoch 1
                           :seon.cluster.eval/at t1
                           :seon.cluster.eval/status status
                           :seon.cluster.eval/result-edn (str ordinal)})
                        statuses)))
                 (let [terminals-before (pull-terminals run-id)
                       recovery
                       (run/recover-tx
                        {::run/run (run-entity connection run-id)
                         ::run/receipts
                         (mapv #(d/pull (d/db connection) '[*] %)
                               (d/q '[:find [?r ...]
                                      :in $ ?run-id
                                      :where
                                      [?run :seon.cluster.run/id ?run-id]
                                      [?r :seon.cluster.eval/run ?run]]
                                    (d/db connection) run-id))
                         ::run/live-processes #{"live-process"}})
                       _ (when (seq recovery)
                           (d/transact connection recovery))
                       entity (run-entity connection run-id)
                       statuses-after
                       (set (d/q '[:find [?status ...]
                                   :in $ ?run-id
                                   :where
                                   [?run :seon.cluster.run/id ?run-id]
                                   [?r :seon.cluster.eval/run ?run]
                                   [?r :seon.cluster.eval/status ?status]]
                                 (d/db connection) run-id))]
                   (and
                    ;; no receipt stays :running
                    (not (contains? statuses-after :running))
                    ;; terminal receipts are IDENTICAL, whole entities
                    (= terminals-before (pull-terminals run-id))
                    ;; dead holders released; live holders keep custody
                    (if dead?
                      (nil? (::run/process entity))
                      (= "live-process" (::run/process entity)))))))
             :seed 20260727)]
        (is (true? (:result check))
            (str "recovery property failed: " (pr-str check)))))))

;;; ---------------------------------------------------------------------------
;;; Schema admissibility — the model refuses what it must
;;; ---------------------------------------------------------------------------

(deftest run-schema-admits-and-refuses
  (is (seon.schema/valid-candidate-value?
       :seon.cluster.run/run
       {::run/id "r1"
        ::run/agent [:seon.cluster.agent/id "runner"]
        ::run/opened-at t0}))
  (is (not (seon.schema/valid-candidate-value?
            :seon.cluster.run/run
            {::run/agent [:seon.cluster.agent/id "runner"]
             ::run/opened-at t0}))
      "identity is required")
  (is (not (seon.schema/valid-candidate-value?
            :seon.cluster.run/run
            {::run/id ""
             ::run/agent [:seon.cluster.agent/id "runner"]
             ::run/opened-at t0}))
      "a blank identity is refused"))

(deftest close-refuses-a-broken-agent-pointer
  ;; quality-review-2 blocker: a broken relation is settled loudly,
  ;; never by silently omitting the retraction
  (with-model-database
    (fn [connection]
      (d/transact connection [{:seon.cluster.agent/id "breaker"}])
      (d/transact connection
                  (run/open-tx {::run/id "broken"
                                ::run/agent [:seon.cluster.agent/id "breaker"]
                                ::run/opened-at t0}))
      (d/transact connection
                  (run/claim-tx {::run/id "broken"
                                 ::run/process "p1"
                                 ::run/lease-until t2
                                 ::run/now t1}))
      ;; sever the relation out from under the run
      (d/transact connection
                  [[:db/retract [:seon.cluster.agent/id "breaker"]
                    :seon.cluster.agent/run [::run/id "broken"]]])
      (is (thrown? Exception
                   (d/transact connection
                               (run/close-tx {::run/id "broken"
                                              ::run/process "p1"
                                              ::run/claim-epoch 1
                                              ::run/closed-at t2})))
          "close refuses ::agent-pointer-broken")
      (is (nil? (::run/closed-at (run-entity connection "broken")))
          "the refused close committed nothing"))))
