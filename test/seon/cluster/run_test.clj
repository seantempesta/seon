(ns seon.cluster.run-test
  "Sealed acceptance for the run model (revised 2026-07-28, twice).

  Orchestrator-authored. The implementation lane makes these green by
  implementing the seon.cluster.run `*-call` transitions ONLY — schemas
  and tests are byte-sealed; friction is reported, never resolved by
  weakening. Everything runs against in-memory Datahike in-process.

  CONTRACT REVISION 2026-07-28 morning (owner ruling: STATE IS
  PRESENCE): `:seon.cluster.eval/status` is DELETED. A receipt is
  running exactly when it carries none of
  `result-edn`/`error`/`interrupted-at`.

  CUSTODY REVISION 2026-07-28 (custody-revision-contracts-2026-07-28):
  CUSTODY IS PRESENCE. `:seon.cluster.run/process` present = held,
  absent = unheld; claiming is CAS-on-absence inside the transaction;
  claiming from a DEAD holder is TAKEOVER = RECOVERY, one shape (stamp
  that custody's running receipts `interrupted-at`, then retract/assert
  the process — one transaction). There is no claim epoch and no lease:
  every behavioral assertion the epoch/lease arms carried is KEPT below,
  re-expressed against the surviving fences — `::not-the-holder` (the
  one loud custody refusal), settle-once by presence, `(run, ordinal)`
  receipt identity, and recovery idempotence.

  The acceptance surface is the state-machine property: generated
  command sequences run against the real database while a pure MODEL
  decides, for every command, whether the transition must commit or
  refuse. A transition that commits when the model says refuse (a
  stolen held run, a reopened closed run, a second plan) or refuses
  when the model says commit is a counterexample. Invariants over
  durable facts are asserted after every command."
  (:require [clojure.main :as main]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.cluster.run :as run]
            [seon.schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.test-support :as test-support]))

(deftest receipt-ai-is-only-repl-output
  (is (= "42"
         (run/render-receipt-ai {:seon.cluster.eval/result-edn "42"})))
  (is (= "side effect\nnil"
         (run/render-receipt-ai
          {:seon.cluster.eval/output "side effect\n"
           :seon.cluster.eval/result-edn "nil"})))
  (is (nil? (run/render-receipt-ai {})))
  (is (nil? (run/render-receipt-ai
             {:seon.cluster.eval/interrupted-at true})))
  (let [triage-edn
        (try
          (/ 1 0)
          (catch Throwable throwable
            (pr-str (main/ex-triage (Throwable->map throwable)))))
        rendered
        (run/render-receipt-ai
         {:seon.cluster.eval/error "Divide by zero"
          :seon.cluster.eval/triage-edn triage-edn})]
    (is (str/starts-with? rendered
                          "Execution error (ArithmeticException) at"))
    (is (str/ends-with? rendered "Divide by zero"))
    (is (= 2 (count (str/split-lines rendered))))
    (is (not (str/includes? rendered "Form ")))
    (is (not (str/includes? rendered "failed:")))))

;;; ---------------------------------------------------------------------------
;;; In-memory database fixture
;;; ---------------------------------------------------------------------------

(def ^:private model-attributes
  [:seon.cluster.agent/id
   :seon.cluster.agent/run
   :seon.cluster.agent/namespace
   :seon.ns/name
   :seon.cluster.run/id
   :seon.cluster.run/agent
   :seon.cluster.run/opening-commit-id
   :seon.cluster.run/opened-at
   :seon.cluster.run/closed-at
   :seon.cluster.run/interrupted-at
   :seon.cluster.run/process
   :seon.cluster.run/plan-digest
   :seon.cluster.work/situation
   :seon.cluster.run/starting-ns
   :seon.cluster.run/forms
   :seon.cluster.run.form/id
   :seon.cluster.run.form/run
   :seon.cluster.run.form/ordinal
   :seon.cluster.run.form/author
   :seon.cluster.run.form/source
   :seon.cluster.run.form/ns
   :seon.cluster.run.form/refreshes
   :seon.cluster.eval/id
   :seon.cluster.eval/run
   :seon.cluster.eval/ordinal
   :seon.cluster.eval/at
   :seon.cluster.eval/interrupted-at
   :seon.cluster.eval/result-edn
   :seon.cluster.eval/result-blob
   :seon.cluster.eval/result-size
   :seon.cluster.eval/error
   :seon.cluster.eval/triage-edn
   :seon.cluster.eval/read-evidence
   :seon.error/kind
   :seon.schema.admission/source
   :seon.def/key
   :seon.def/id
   :seon.def/agent
   :seon.def/ns
   :seon.def/name
   :seon.def/value-edn
   :seon.def/blob
   :seon.def/size
   :seon.def/unrestorable-reason
   :seon.def/atom?
   :seon.def/ordinal
   :seon.fn/sym
   :seon.fn/ns
   :seon.fn/source
   :seon.fn/arglists
   :seon.fn/private?
   :seon.fn/spec])

(defn- with-model-database [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (db/transact! connection
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

(defn- deepest-ex-data [error]
  (loop [throwable error
         found nil]
    (if throwable
      (recur (ex-cause throwable)
             (or (not-empty (ex-data throwable)) found))
      found)))

(defn- transact-or-refusal
  "Commit tx-data; a refusal returns its deepest ex-data as a value."
  [connection tx-data]
  (try
    (let [result (db/transact! connection tx-data)]
      (if (:seon.error/kind result)
        result
        ::committed))
    (catch Exception e
      (or (deepest-ex-data e)
          {::opaque (ex-message e)}))))

(defn- run-entity [connection run-id]
  (db/pull (db/db connection) '[*] [:seon.cluster.run/id run-id]))

(defn- agent-pointer [connection agent-id]
  (get-in (db/pull (db/db connection)
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
  (testing "held is the PRESENCE of a process — custody is presence,
            and there is no lease clock to consult"
    (is (true? (run/held? {::run/process "p1"})))
    (is (false? (run/held? {})))))

(deftest interrupted-warning-is-one-derived-value
  (let [forms [{:seon.cluster.run.form/ordinal 0}
               {:seon.cluster.run.form/ordinal 1}
               {:seon.cluster.run.form/ordinal 2}]]
    (testing "clean receipts derive no warning at all"
      (is (nil? (run/interrupted-warning
                 forms
                 [{:seon.cluster.eval/ordinal 0
                   :seon.cluster.eval/result-edn "1"}]))))
    (testing "an interrupted receipt derives exactly one warning naming
              the first interrupted ordinal and the missing tail"
      (let [warning (run/interrupted-warning
                     forms
                     [{:seon.cluster.eval/ordinal 0
                       :seon.cluster.eval/result-edn "1"}
                      {:seon.cluster.eval/ordinal 1
                       :seon.cluster.eval/interrupted-at t1}])]
        (is (= 1 (:seon.cluster.eval/ordinal warning)))
        (is (= 2 (:seon.cluster.run/missing-results warning)))))))

;;; ---------------------------------------------------------------------------
;;; Teaching examples — the call shapes, one committed lifecycle
;;; ---------------------------------------------------------------------------

(deftest one-run-lifecycle-teaches-the-call-shapes
  (with-model-database
    (fn [connection]
      (db/transact! connection [{:seon.cluster.agent/id "teacher"}])
      (testing "open: run entity + agent pointer from ONE agent ref"
        (is (= ::committed
               (transact-or-refusal
                connection
                (run/open-tx {::run/id "lesson"
                              ::run/agent [:seon.cluster.agent/id "teacher"]
                              ::run/opened-at t0}))))
        (is (= "lesson" (agent-pointer connection "teacher"))))
      (testing "claim an unheld open run — CAS-on-absence"
        (is (= ::committed
               (transact-or-refusal
                connection
                (run/claim-tx {::run/id "lesson"
                               ::run/process "p1"
                               ::run/live-processes #{"p1"}
                               ::run/now t1}))))
        (is (= "p1" (::run/process (run-entity connection "lesson")))))
      (testing "a held run refuses a second live claim"
        (is (= ::run/run-held
               (::run/rule (transact-or-refusal
                            connection
                            (run/claim-tx {::run/id "lesson"
                                           ::run/process "p2"
                                           ::run/live-processes #{"p1" "p2"}
                                           ::run/now t1})))))
        (is (= "p1" (::run/process (run-entity connection "lesson")))
            "the refusal leaves custody unchanged"))
      (testing "plan freezes once, with its ordered owned forms"
        (is (= ::committed
               (transact-or-refusal
                connection
                (run/plan-tx {::run/id "lesson"
                              ::run/process "p1"
                              ::run/starting-ns [:seon.ns/name 'user]
                              ::run/plan-digest "digest-a"
                              ::run/sources
                              [{:seon.cluster.run.form/source "(+ 1 1)"}
                               {:seon.cluster.run.form/source "(+ 2 2)"}]}))))
        (is (= ["(+ 1 1)" "(+ 2 2)"]
               (->> (db/q '[:find ?ordinal ?source
                           :in $ ?run-id
                           :where
                           [?run :seon.cluster.run/id ?run-id]
                           [?form :seon.cluster.run.form/run ?run]
                           [?form :seon.cluster.run.form/ordinal ?ordinal]
                           [?form :seon.cluster.run.form/source ?source]]
                         (db/db connection) "lesson")
                    (sort-by first)
                    (mapv second)))))
        (is (= #{:agent}
               (set
                (db/q '[:find [?author ...]
                        :in $ ?run-id
                        :where
                        [?run :seon.cluster.run/id ?run-id]
                        [?form :seon.cluster.run.form/run ?run]
                        [?form :seon.cluster.run.form/author ?author]]
                      @connection "lesson"))))
      (testing "close settles the run and retracts the pointer it
                derived from the run's own agent connection"
        (is (= ::committed
               (transact-or-refusal
                connection
                (run/close-tx {::run/id "lesson"
                               ::run/process "p1"
                               ::run/closed-at t2}))))
        (let [entity (run-entity connection "lesson")]
          (is (false? (run/open? {::run/closed-at
                                  (::run/closed-at entity)})))
          (is (nil? (::run/process entity))
              "a closed run holds no custody"))
        (is (nil? (agent-pointer connection "teacher")))))))

(deftest generated-system-runs-grow-only-after-their-settled-prefix
  (with-model-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.ns/name 'my.agents.generated}
        {:seon.cluster.agent/id "generated-agent"
         :seon.cluster.agent/namespace
         [:seon.ns/name 'my.agents.generated]}])
      (is (= ::committed
             (transact-or-refusal
              connection
              (run/generated-run-tx
               @connection
               {:seon.cluster.agent/id "generated-agent"
                ::run/id "generated-run"
                ::run/process "generated-process"
                ::run/opened-at t0
                ::run/starting-ns [:seon.ns/name 'my.agents.generated]
                :seon.cluster.run.form/source "(help)"}))))
      (is (= ::run/generated-prefix-unsettled
             (::run/rule
              (transact-or-refusal
               connection
               (run/append-generated-tx
                {::run/id "generated-run"
                 ::run/process "generated-process"
                 :seon.cluster.run.form/ordinal 1
                 :seon.cluster.run.form/source "(dir 'my.run)"
                 :seon.ns/name 'my.agents.generated})))))
      (db/transact!
       connection
       (run/receipt-start-tx
        {::run/id "generated-run"
         :seon.cluster.eval/ordinal 0
         :seon.cluster.eval/at t0}))
      (db/transact!
       connection
       (run/receipt-settle-tx
        {::run/id "generated-run"
         :seon.cluster.eval/ordinal 0
         :seon.cluster.eval/result-edn "{:introduced 'my.run}"}))
      (is (= ::committed
             (transact-or-refusal
              connection
              (run/append-generated-tx
               {::run/id "generated-run"
                ::run/process "generated-process"
                :seon.cluster.run.form/ordinal 1
                :seon.cluster.run.form/source "(dir 'my.run)"
                :seon.ns/name 'my.agents.generated}))))
      (is (= [[0 :system "(help)"]
              [1 :system "(dir 'my.run)"]]
             (db/q {:query
                    '[:find ?ordinal ?author ?source
                      :where
                      [?run :seon.cluster.run/id "generated-run"]
                      [?form :seon.cluster.run.form/run ?run]
                      [?form :seon.cluster.run.form/ordinal ?ordinal]
                      [?form :seon.cluster.run.form/author ?author]
                      [?form :seon.cluster.run.form/source ?source]]
                    :args [@connection]
                    :order-by '[?ordinal :asc]})))
      (is (nil? (::run/plan-digest
                 (run-entity connection "generated-run")))))))

(deftest an-agent-plan-appends-after-the-settled-generated-prefix
  (with-model-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.ns/name 'my.agents.appended}
        {:seon.cluster.agent/id "appended-agent"
         :seon.cluster.agent/namespace
         [:seon.ns/name 'my.agents.appended]}])
      (db/transact!
       connection
       (run/generated-run-tx
        @connection
        {:seon.cluster.agent/id "appended-agent"
         ::run/id "appended-run"
         ::run/process "appended-process"
         ::run/opened-at t0
         ::run/starting-ns [:seon.ns/name 'my.agents.appended]
         :seon.cluster.run.form/source "(help)"}))
      (db/transact!
       connection
       (run/receipt-start-tx
        {::run/id "appended-run"
         :seon.cluster.eval/ordinal 0
         :seon.cluster.eval/at t0}))
      (db/transact!
       connection
       (run/receipt-settle-tx
        {::run/id "appended-run"
         :seon.cluster.eval/ordinal 0
         :seon.cluster.eval/result-edn "{:introduced 'my.run}"}))
      (db/transact!
       connection
       (run/generation-complete-tx
        {::run/id "appended-run"
         ::run/process "appended-process"}))
      (is (= ::committed
             (transact-or-refusal
              connection
              (run/plan-tx
               {::run/id "appended-run"
                ::run/process "appended-process"
                ::run/plan-digest "agent-reply-digest"
                ::run/sources
                [{:seon.cluster.run.form/source "(+ 1 2)"}
                 {:seon.cluster.run.form/source "(+ 3 4)"}]}))))
      (is (= [[0 :system "(help)"]
              [1 :agent "(+ 1 2)"]
              [2 :agent "(+ 3 4)"]]
             (db/q {:query
                    '[:find ?ordinal ?author ?source
                      :where
                      [?run :seon.cluster.run/id "appended-run"]
                      [?form :seon.cluster.run.form/run ?run]
                      [?form :seon.cluster.run.form/ordinal ?ordinal]
                      [?form :seon.cluster.run.form/author ?author]
                      [?form :seon.cluster.run.form/source ?source]]
                    :args [@connection]
                    :order-by '[?ordinal :asc]})))
      (is (= {:seon.cluster.eval/result-edn "{:introduced 'my.run}"
              :seon.cluster.run/plan-digest "agent-reply-digest"}
             (merge
              (db/pull @connection [:seon.cluster.eval/result-edn]
                       [:seon.cluster.eval/id
                        (run/receipt-identity "appended-run" 0)])
              (db/pull @connection [::run/plan-digest]
                       [::run/id "appended-run"])))))))

(deftest run-records-its-opening-commit-and-starting-namespace
  (with-model-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.ns/name 'my.agents.replay}
        {:seon.cluster.agent/id "replay-agent"
         :seon.cluster.agent/namespace
         [:seon.ns/name 'my.agents.replay]}])
      (let [opening-commit-id (db/commit-id @connection)]
        (db/transact!
         connection
         (run/system-run-tx
          @connection
          {:seon.cluster.agent/id "replay-agent"
           ::run/id "replay-run"
           ::run/process "replay-process"
           ::run/opened-at t0
           ::run/starting-ns [:seon.ns/name 'replay.start]
           ::run/plan-digest "replay-digest"
           ::run/sources
           [{:seon.cluster.run.form/source "(def replayed 1)"}]}))
        (let [run (db/pull
                   @connection
                   '[* {:seon.cluster.run/starting-ns [:seon.ns/name]}]
                   [::run/id "replay-run"])]
          (is (= opening-commit-id (::run/opening-commit-id run)))
          (is (uuid? (::run/opening-commit-id run)))
          (is (= 'replay.start
                 (get-in run [::run/starting-ns :seon.ns/name])))))
      (is (= :system
             (db/q '[:find ?author .
                     :where
                     [?run :seon.cluster.run/id "replay-run"]
                     [?form :seon.cluster.run.form/run ?run]
                     [?form :seon.cluster.run.form/author ?author]]
                   @connection)))
      (is (= 'replay.start
             (db/q '[:find ?namespace-name .
                     :where
                     [?run :seon.cluster.run/id "replay-run"]
                     [?form :seon.cluster.run.form/run ?run]
                     [?form :seon.cluster.run.form/ordinal 0]
                     [?form :seon.cluster.run.form/ns ?namespace]
                     [?namespace :seon.ns/name ?namespace-name]]
                   @connection))))))

(deftest refreshes-only-terminal-system-reads-once
  (test-support/with-database
    (fn [connection]
      (let [namespace-name 'my.refresh
            agent-namespace-name 'my.refresh.agent
            process "refresh-process"
            evidence
            [{:seon.db/source-argument-position 0
              :datahike.read/dependency-plan :all
              :datahike.read/revision
              {:datahike.read/attributes :all
               :datahike.read/cache-eligible? false}}]
            settle!
            (fn [run-id]
              (db/transact!
               connection
               (run/receipt-start-tx
                {::run/id run-id
                 :seon.cluster.eval/ordinal 0
                 :seon.cluster.eval/at t0}))
              (db/transact!
               connection
               (run/receipt-settle-tx
                {::run/id run-id
                 :seon.cluster.eval/ordinal 0
                 :seon.cluster.eval/result-edn "42"
                 :seon.cluster.eval/read-evidence evidence})))
            close!
            (fn [run-id]
              (db/transact!
               connection
               (run/close-tx {::run/id run-id
                              ::run/process process
                              ::run/closed-at t1})))]
        (db/transact!
         connection
         [{:seon.ns/name namespace-name}
          {:seon.ns/name agent-namespace-name}
          {:seon.cluster.agent/id "system-refresh"
           :seon.cluster.agent/namespace
           [:seon.ns/name namespace-name]}
          {:seon.cluster.agent/id "agent-refresh"
           :seon.cluster.agent/namespace
           [:seon.ns/name agent-namespace-name]}])
        (db/transact!
         connection
         (run/system-run-tx
          @connection
          {:seon.cluster.agent/id "system-refresh"
           ::run/id "system-source"
           ::run/process process
           ::run/opened-at t0
           ::run/starting-ns [:seon.ns/name namespace-name]
           ::run/plan-digest "system-source-digest"
           ::run/sources
           [{:seon.cluster.run.form/source
             "{:my.refresh/value 42}"}]}))
        (settle! "system-source")
        (close! "system-source")
        (let [prior-id (run/form-identity "system-source" 0)]
          (is (= evidence
                 (mapv #(dissoc % :db/id)
                       (:seon.cluster.eval/read-evidence
                        (db/pull @connection
                                 '[{:seon.cluster.eval/read-evidence [*]}]
                                 [:seon.cluster.eval/id
                                  (run/receipt-identity
                                   "system-source" 0)])))))
          (is (= ::committed
                 (transact-or-refusal connection (run/refresh-tx prior-id))))
          (let [successor
                (db/pull
                 @connection
                 '[:seon.cluster.run.form/source
                   :seon.cluster.run.form/author
                   {:seon.cluster.run.form/ns [:seon.ns/name]}
                   {:seon.cluster.run.form/refreshes
                    [:seon.cluster.run.form/id]}]
                 (db/q '[:find ?successor .
                         :in $ ?prior
                         :where
                         [?prior-form :seon.cluster.run.form/id ?prior]
                         [?successor :seon.cluster.run.form/refreshes
                          ?prior-form]]
                       @connection prior-id))]
            (is (= :system (:seon.cluster.run.form/author successor)))
            (is (= "{:my.refresh/value 42}"
                   (:seon.cluster.run.form/source successor)))
            (is (= namespace-name
                   (get-in successor
                           [:seon.cluster.run.form/ns :seon.ns/name])))
            (is (= prior-id
                   (get-in successor
                           [:seon.cluster.run.form/refreshes
                            :seon.cluster.run.form/id]))))
          (is (= ::run/refresh-successor-exists
                 (::run/rule
                  (transact-or-refusal connection
                                       (run/refresh-tx prior-id))))))
        (db/transact!
         connection
         (run/open-tx {::run/id "agent-source"
                       ::run/agent
                       [:seon.cluster.agent/id "agent-refresh"]
                       ::run/opened-at t0}))
        (db/transact!
         connection
         (run/claim-tx {::run/id "agent-source"
                        ::run/process process
                        ::run/live-processes #{process}
                        ::run/now t0}))
        (db/transact!
         connection
         (run/plan-tx {::run/id "agent-source"
                       ::run/process process
                       ::run/starting-ns
                       [:seon.ns/name agent-namespace-name]
                       ::run/plan-digest "agent-source-digest"
                       ::run/sources
                       [{:seon.cluster.run.form/source "(+ 1 1)"}]}))
        (settle! "agent-source")
        (close! "agent-source")
        (is (= ::run/refresh-agent-authored
               (::run/rule
                (transact-or-refusal
                 connection
                 (run/refresh-tx
                  (run/form-identity "agent-source" 0))))))))))

(deftest a-non-holder-refuses-every-held-run-transition
  ;; the surviving custody assertion, re-expressed from the lease-era
  ;; expiry test: a process that does not hold the run cannot act on
  ;; it, and the refusal leaves every fact unchanged. `::not-the-holder`
  ;; is the ONE loud custody refusal (custody revision, kept fences).
  (doseq [[operation tx]
          [[:release
            #(run/release-tx {::run/id "held"
                              ::run/process "p2"})]
           [:close
            #(run/close-tx {::run/id "held"
                            ::run/process "p2"
                            ::run/closed-at t1})]
           [:plan
            #(run/plan-tx {::run/id "held"
                           ::run/process "p2"
                           ::run/starting-ns [:seon.ns/name 'user]
                           ::run/plan-digest "held-digest"
                           ::run/sources
                           [{:seon.cluster.run.form/source "(+ 1 1)"}]})]]]
    (with-model-database
      (fn [connection]
        (db/transact! connection [{:seon.cluster.agent/id "held-agent"}])
        (db/transact! connection
                    (run/open-tx {::run/id "held"
                                  ::run/agent
                                  [:seon.cluster.agent/id "held-agent"]
                                  ::run/opened-at (at -120000)}))
        (db/transact! connection
                    (run/claim-tx {::run/id "held"
                                   ::run/process "p1"
                                   ::run/live-processes #{"p1"}
                                   ::run/now (at -60000)}))
        (testing (str (name operation) " requires holding the run")
          (is (= ::run/not-the-holder
                 (::run/rule (transact-or-refusal connection (tx)))))
          (let [entity (run-entity connection "held")]
            (is (= "p1" (::run/process entity))
                "the refusal leaves custody unchanged")
            (is (nil? (::run/closed-at entity)))
            (is (nil? (::run/plan-digest entity)))))))))

(deftest receipt-transitions-preserve-one-terminal-outcome
  (let [start-tx (ns-resolve 'seon.cluster.run 'receipt-start-tx)
        settle-tx (ns-resolve 'seon.cluster.run 'receipt-settle-tx)]
    (is (some? start-tx) "the run owner provides the receipt-start transition")
    (is (some? settle-tx) "the run owner provides the receipt-settle transition")
    (when (and start-tx settle-tx)
      (with-model-database
        (fn [connection]
          (db/transact! connection [{:seon.cluster.agent/id "receipt-agent"}])
          (db/transact! connection
                      (run/open-tx {::run/id "receipts"
                                    ::run/agent
                                    [:seon.cluster.agent/id "receipt-agent"]
                                    ::run/opened-at (at -120000)}))
          (db/transact! connection
                      (run/claim-tx {::run/id "receipts"
                                     ::run/process "p1"
                                     ::run/live-processes #{"p1"}
                                     ::run/now (at -60000)}))
          (let [start {::run/id "receipts"
                       :seon.cluster.eval/ordinal 0
                       :seon.cluster.eval/at t0}
                settle {::run/id "receipts"
                        :seon.cluster.eval/ordinal 0
                        :seon.cluster.eval/result-edn "42"
                        :seon.cluster.eval/triage-edn
                        "{:clojure.error/cause \"triage evidence\"}"
                        :seon.cluster.eval/result-blob
                        (apply str (repeat 64 "a"))
                        :seon.cluster.eval/result-size 100}]
            (is (= ::committed
                   (transact-or-refusal connection (start-tx start))))
            (is (not= ::committed
                      (transact-or-refusal connection (start-tx start)))
                "duplicate start is refused")
            (is (not= ::committed
                      (transact-or-refusal
                       connection
                       (settle-tx (dissoc settle
                                          :seon.cluster.eval/result-edn))))
                "a settle carrying no terminal fact is refused")
            (is (= ::committed
                   (transact-or-refusal connection (settle-tx settle))))
            (doseq [terminal [settle
                              (assoc settle
                                     :seon.cluster.eval/result-edn
                                     "{:seon.error/kind :x}"
                                     :seon.cluster.eval/error "changed")]]
              (is (not= ::committed
                        (transact-or-refusal connection
                                             (settle-tx terminal)))
                  "a terminal receipt cannot settle again"))
            (let [receipt (db/pull @connection
                                  '[*]
                                  [:seon.cluster.eval/id
                                   (pr-str ["receipts" 0])])]
              (is (= "42" (:seon.cluster.eval/result-edn receipt))
                  "the first terminal outcome is preserved")
              (is (= 100 (:seon.cluster.eval/result-size receipt)))
              (is (= "{:clojure.error/cause \"triage evidence\"}"
                     (:seon.cluster.eval/triage-edn receipt)))
              (is (= (apply str (repeat 64 "a"))
                     (:seon.cluster.eval/result-blob receipt)))
              (is (nil? (:seon.cluster.eval/error receipt))
                  "and the refused re-settle changed nothing")))
          ;; the takeover interleaving, re-expressed from the epoch era:
          ;; a running receipt under a DEAD holder is stamped by the
          ;; takeover itself, so the dead pass's late settle refuses by
          ;; PRESENCE — the fence the epoch used to claim to be.
          (let [start {::run/id "receipts"
                       :seon.cluster.eval/ordinal 1
                       :seon.cluster.eval/at t0}]
            (is (= ::committed
                   (transact-or-refusal connection (start-tx start))))
            (is (= ::committed
                   (transact-or-refusal
                    connection
                    (run/claim-tx {::run/id "receipts"
                                   ::run/process "p2"
                                   ;; p1 is DEAD at this instant
                                   ::run/live-processes #{"p2"}
                                   ::run/now t1})))
                "a run held by a dead process is taken over")
            (is (= "p2" (::run/process (run-entity connection "receipts"))))
            (is (= t1 (:seon.cluster.eval/interrupted-at
                       (db/pull @connection '[*]
                               [:seon.cluster.eval/id
                                (pr-str ["receipts" 1])])))
                "the takeover stamped the dead custody's running receipt
                 in the SAME transaction — the intermediate state never
                 exists")
            (is (not= ::committed
                      (transact-or-refusal
                       connection
                       (settle-tx {::run/id "receipts"
                                   :seon.cluster.eval/ordinal 1
                                   :seon.cluster.eval/result-edn "1"})))
                "the dead pass's late settle refuses — by presence")
            (is (= ::run/receipt-exists
                   (::run/rule (transact-or-refusal
                                connection
                                (start-tx start))))
                "and `(run, ordinal)` identity makes re-execution
                 unrepresentable: an ordinal that ever had a receipt
                 refuses forever, across any custody change")))))))

(deftest receipt-settlement-owns-agent-scoped-def-facts
  (with-model-database
    (fn [connection]
      (let [namespace-name 'my.defs.shared
            agent-a "def-agent-a"
            agent-b "def-agent-b"
            run-a "defs-run-a"
            run-b "defs-run-b"
            qualified-id "my.defs.shared/scratch"
            agent-ref (fn [agent-id]
                        [:seon.cluster.agent/id agent-id])
            def-key (fn [agent-id id]
                       (pr-str [agent-id id]))
            def-row
            (fn [agent-id value]
              (merge
               {:seon.def/key (def-key agent-id qualified-id)
                :seon.def/id qualified-id
                :seon.def/agent (agent-ref agent-id)
                :seon.schema.admission/source :agent
                :seon.def/ns [:seon.ns/name namespace-name]
                :seon.def/name 'scratch
                :seon.def/ordinal 0}
               value))
            function-row
            (fn [result]
              {:seon.fn/sym qualified-id
               :seon.fn/ns [:seon.ns/name namespace-name]
               :seon.fn/source
               (str "(defn ^{:malli/schema [:=> [:cat] :int]} "
                    "scratch [] " result ")")
               :seon.fn/arglists "([])"
               :seon.fn/private? false
               :seon.fn/spec "[:=> [:cat] :int]"})
            rows-for
            (fn [agent-id]
              (->> (db/q '[:find [?definition ...]
                           :in $ ?agent-id
                           :where
                           [?agent :seon.cluster.agent/id ?agent-id]
                           [?definition :seon.def/agent ?agent]]
                         (db/db connection) agent-id)
                   (mapv #(db/pull (db/db connection) '[*] %))))
            start!
            (fn [run-id ordinal]
              (db/transact!
               connection
               (run/receipt-start-tx
                {::run/id run-id
                 :seon.cluster.eval/ordinal ordinal
                 :seon.cluster.eval/at (at ordinal)})))
            settle!
            (fn [run-id ordinal request]
              (transact-or-refusal
               connection
               (run/receipt-settle-tx
                (merge {::run/id run-id
                        :seon.cluster.eval/ordinal ordinal
                        :seon.cluster.eval/result-edn "nil"}
                       request))))]
        (db/transact!
         connection
         [{:seon.ns/name namespace-name
           :seon.schema.admission/source :agent}
          {:seon.cluster.agent/id agent-a}
          {:seon.cluster.agent/id agent-b}])
        (doseq [[run-id agent-id] [[run-a agent-a] [run-b agent-b]]]
          (db/transact!
           connection
           (run/open-tx {::run/id run-id
                         ::run/agent (agent-ref agent-id)
                         ::run/opened-at t0})))

        (testing "the same qualified id is isolated by agent"
          (start! run-a 0)
          (start! run-b 0)
          (is (= ::committed
                 (settle! run-a 0
                          {:seon.def/rows
                           [(def-row agent-a
                                      {:seon.def/value-edn "1"})]})))
          (is (= ::committed
                 (settle! run-b 0
                          {:seon.def/rows
                           [(def-row agent-b
                                      {:seon.def/value-edn "2"})]})))
          (is (= ["1"] (mapv :seon.def/value-edn (rows-for agent-a))))
          (is (= ["2"] (mapv :seon.def/value-edn (rows-for agent-b)))))

        (testing "replacement is exact, including omitted old attributes"
          (start! run-a 1)
          (is (= ::committed
                 (settle! run-a 1
                          {:seon.def/rows
                           [(def-row
                             agent-a
                             {:seon.def/unrestorable-reason
                              "host value has no faithful representation"})]})))
          (let [row (first (rows-for agent-a))]
            (is (nil? (:seon.def/value-edn row)))
            (is (= "host value has no faithful representation"
                   (:seon.def/unrestorable-reason row)))))

        (testing "a receipt cannot write another agent's defs"
          (start! run-a 2)
          (let [before (rows-for agent-b)
                refusal
                (settle! run-a 2
                         {:seon.def/rows
                          [(def-row agent-b
                                     {:seon.def/value-edn "stolen"})]})]
            (is (= ::run/def-agent-mismatch (::run/rule refusal)))
            (is (= before (rows-for agent-b)))
            (is (not (run/terminal?
                      (db/pull
                       (db/db connection) '[*]
                       [:seon.cluster.eval/id (pr-str [run-a 2])]))))))

        (testing "a contracted function retracts only its agent's matching def"
          (start! run-a 3)
          (is (= ::committed
                 (settle!
                  run-a 3
                  {:seon.program/row
                   (function-row 1)})))
          (is (empty? (rows-for agent-a)))
          (is (= ["2"] (mapv :seon.def/value-edn (rows-for agent-b)))))

        (testing "a divergent definition from an older opening basis refuses"
          (start! run-b 1)
          (is (= ::run/program-row-changed-after-open
                 (::run/rule
                  (settle! run-b 1
                           {:seon.program/row (function-row 2)}))))
          (is (= (:seon.fn/source (function-row 1))
                 (:seon.fn/source
                  (db/pull @connection '[*]
                           [:seon.fn/sym qualified-id]))))
          (is (= ::committed
                 (settle! run-b 1
                          {:seon.program/row (function-row 1)}))
              "an identical declaration is an assertion-free success"))

        (testing "clearing is explicit, agent-local, and idempotent"
          (start! run-a 4)
          (is (= ::committed
                 (settle! run-a 4
                          {:seon.def/rows
                           [(def-row agent-a
                                      {:seon.def/value-edn "kept"})]})))
          (is (= ::committed
                 (transact-or-refusal
                  connection
                  (run/clear-defs-tx
                   {:seon.def/agent (agent-ref agent-b)}))))
          (is (empty? (rows-for agent-b)))
          (is (= ["kept"]
                 (mapv :seon.def/value-edn (rows-for agent-a))))
          (is (= ::committed
                 (transact-or-refusal
                  connection
                  (run/clear-defs-tx
                   {:seon.def/agent (agent-ref agent-b)}))))
          (is (empty? (rows-for agent-b))))))))

;;; ---------------------------------------------------------------------------
;;; The state machine — generated command sequences against a pure model
;;; ---------------------------------------------------------------------------

(def ^:private agent-ids ["a1" "a2"])
(def ^:private process-ids ["p1" "p2" "p3"])
(def ^:private run-ids ["r1" "r2" "r3"])

(def ^:private command-gen
  "One generated command. Times are monotonic per sequence position:
  the runner assigns now = (at (* index 60000)). A claim carries the
  set of processes ALIVE at that instant (the claimant is always in
  it); whether the current holder is in it decides refuse vs takeover."
  (gen/one-of
   [(gen/tuple (gen/return :open)
               (gen/elements run-ids)
               (gen/elements agent-ids))
    (gen/tuple (gen/return :claim)
               (gen/elements run-ids)
               (gen/elements process-ids)
               (gen/set (gen/elements process-ids)))
    (gen/tuple (gen/return :release)
               (gen/elements run-ids)
               (gen/elements process-ids))
    (gen/tuple (gen/return :close)
               (gen/elements run-ids)
               (gen/elements process-ids))
    (gen/tuple (gen/return :plan)
               (gen/elements run-ids)
               (gen/elements process-ids)
               (gen/elements ["digest-a" "digest-b"]))
    (gen/tuple (gen/return :receipt-start)
               (gen/elements run-ids)
               (gen/choose 0 3))
    ;; the settled kind names WHICH terminal fact the settle asserts:
    ;; :done → result-edn, :error → error, :interrupted → interrupted-at
    (gen/tuple (gen/return :receipt-settle)
               (gen/elements run-ids)
               (gen/choose 0 3)
               (gen/elements [:done :error :interrupted]))
    (gen/tuple (gen/return :recover)
               (gen/set (gen/elements process-ids)))]))

(def ^:private commands-gen
  (gen/one-of
   [(gen/vector command-gen 1 15)
    ;; the claim/takeover regression under presence: a live holder
    ;; refuses the steal; a dead holder is taken over with its running
    ;; receipt stamped in the same transaction
    (gen/return [[:open "r1" "a1"]
                 [:claim "r1" "p1" #{"p1"}]
                 [:receipt-start "r1" 0]
                 [:claim "r1" "p2" #{"p1" "p2"}]
                 [:claim "r1" "p2" #{"p2"}]
                 [:receipt-settle "r1" 0 :done]])]))

(defn- claim-live-set
  "The live set a claim executes with: the claimant is always alive."
  [process live]
  (conj (set live) process))

(defn- model-eligible?
  "The pure oracle: must this command COMMIT against `model`?"
  [model [op & args]]
  (let [run-of #(get-in model [:runs %])]
    (case op
      :open (let [[run-id agent-id] args]
              (and (nil? (run-of run-id))
                   (nil? (get-in model [:pointers agent-id]))))
      :claim (let [[run-id process live] args
                   {:keys [closed] :as entry} (run-of run-id)
                   holder (:process entry)]
               (and (some? entry)
                    (not closed)
                    (or (nil? holder)
                        (not (contains? (claim-live-set process live)
                                        holder)))))
      (:release :close)
      (let [[run-id process] args
            {:keys [closed] :as entry} (run-of run-id)]
        (and (some? entry)
             (not closed)
             (= process (:process entry))))
      :plan (let [[run-id process _digest] args
                  {:keys [closed digest] :as entry} (run-of run-id)]
              (and (some? entry)
                   (not closed)
                   (= process (:process entry))
                   (nil? digest)))
      :receipt-start
      (let [[run-id ordinal] args
            entry (run-of run-id)]
        (and (some? entry)
             (not (:closed entry))
             (nil? (get-in model [:receipts [run-id ordinal]]))))
      :receipt-settle
      (let [[run-id ordinal _kind] args
            entry (run-of run-id)
            receipt (get-in model [:receipts [run-id ordinal]])]
        (and (some? entry)
             (not (:closed entry))
             ;; running IS the absence of a settled fact
             (some? receipt)
             (nil? (:settled receipt))))
      :recover true)))

(defn- stamp-running-receipts
  "Settle every running receipt of `run-id` as :interrupted."
  [model run-id]
  (update model :receipts
          #(into {}
                 (map (fn [[[receipt-run ordinal :as k] receipt]]
                        [k (if (and (= receipt-run run-id)
                                    (nil? (:settled receipt)))
                             (assoc receipt :settled :interrupted)
                             receipt)]))
                 %)))

(defn- model-apply
  "Advance the pure model by one COMMITTED command."
  [model [op & args]]
  (case op
    :open (let [[run-id agent-id] args]
            (-> model
                (assoc-in [:runs run-id] {:agent agent-id})
                (assoc-in [:pointers agent-id] run-id)))
    :claim (let [[run-id process _live] args
                 holder (get-in model [:runs run-id :process])]
             (cond-> model
               ;; TAKEOVER = RECOVERY, one shape: a dead holder's
               ;; running receipts AND its run are stamped in the same
               ;; transition — a run with no receipt row still carries
               ;; the evidence that a dead process's custody was cut
               (some? holder) (stamp-running-receipts run-id)
               (some? holder) (assoc-in [:runs run-id :interrupted] true)
               true (assoc-in [:runs run-id :process] process)))
    :release (let [[run-id] args]
               (update-in model [:runs run-id] dissoc :process))
    :close (let [[run-id] args
                 agent-id (get-in model [:runs run-id :agent])]
             (-> model
                 (update-in [:runs run-id]
                            #(-> % (assoc :closed true)
                                 (dissoc :process)))
                 (update :pointers dissoc agent-id)))
    :plan (let [[run-id _ digest] args]
            (assoc-in model [:runs run-id :digest] digest))
    :receipt-start (let [[run-id ordinal] args]
                     ;; no :settled key: a started receipt is running
                     ;; by the absence of any terminal fact
                     (assoc-in model [:receipts [run-id ordinal]]
                               {:id (pr-str [run-id ordinal])
                                :run run-id
                                :ordinal ordinal}))
    :receipt-settle (let [[run-id ordinal kind] args]
                      (assoc-in model
                                [:receipts [run-id ordinal] :settled]
                                kind))
    :recover (let [[live] args]
               (reduce
                (fn [acc [run-id entry]]
                  (let [holder (:process entry)]
                    (cond
                      (:closed entry) acc
                      ;; a live holder's run needs nothing — its
                      ;; running receipts are its own business
                      (contains? live holder) acc
                      :else
                      (let [agent-id (:agent entry)]
                        (-> (stamp-running-receipts acc run-id)
                            (update-in [:runs run-id]
                                       #(-> %
                                            (assoc :closed true)
                                            ;; recovery marks what it cut
                                            (assoc :interrupted true)
                                            (dissoc :process)))
                            (update :pointers dissoc agent-id))))))
                model
                (:runs model)))))

(defn- execute!
  "Run one command against the real database. Returns ::committed or
  refusal data."
  [connection [op & args] now]
  (case op
    :open (let [[run-id agent-id] args]
            (transact-or-refusal
             connection
             (run/open-tx {::run/id run-id
                           ::run/agent [:seon.cluster.agent/id agent-id]
                           ::run/opened-at now})))
    :claim (let [[run-id process live] args]
             (transact-or-refusal
              connection
              (run/claim-tx {::run/id run-id
                             ::run/process process
                             ::run/live-processes
                             (claim-live-set process live)
                             ::run/now now})))
    :release (let [[run-id process] args]
               (transact-or-refusal
                connection
                (run/release-tx
                 {::run/id run-id
                  ::run/process process})))
    :close (let [[run-id process] args]
             (transact-or-refusal
              connection
              (run/close-tx
               {::run/id run-id
                ::run/process process
                ::run/closed-at now})))
    :plan (let [[run-id process digest] args]
            (transact-or-refusal
             connection
             (run/plan-tx
              {::run/id run-id
               ::run/process process
               ::run/starting-ns [:seon.ns/name 'user]
               ::run/plan-digest digest
               ::run/sources
                [{:seon.cluster.run.form/source "(+ 1 1)"}]})))
    :receipt-start (let [[run-id ordinal] args]
                     (transact-or-refusal
                      connection
                      (run/receipt-start-tx
                       {::run/id run-id
                        :seon.cluster.eval/ordinal ordinal
                        :seon.cluster.eval/at now})))
    :receipt-settle (let [[run-id ordinal kind] args]
                      (transact-or-refusal
                       connection
                       (run/receipt-settle-tx
                        (merge {::run/id run-id
                                :seon.cluster.eval/ordinal ordinal}
                               ;; the settle IS the terminal fact
                               (case kind
                                 :done {:seon.cluster.eval/result-edn "42"}
                                 :error {:seon.cluster.eval/error "boom"}
                                 :interrupted
                                 {:seon.cluster.eval/interrupted-at now})))))
    :recover (let [[live] args]
               (transact-or-refusal
                connection
                (into []
                      (mapcat
                       (fn [run-id]
                         ;; recover-call reads the run and its receipts
                         ;; AT TRANSACTION TIME; a missing or closed
                         ;; run contributes nothing
                         (run/recover-tx
                          {::run/id run-id
                           ::run/live-processes live
                           ::run/now now})))
                      run-ids)))))

(defn- invariants-hold?
  "Durable-fact invariants checked after EVERY command."
  [connection model]
  (let [database-receipts
        ;; the settled kind is DERIVED from which terminal fact is
        ;; present — the same derivation every reader now performs
        (into #{}
              (map (fn [eid]
                     (let [receipt (db/pull @connection '[*] eid)
                           run-id (:seon.cluster.run/id
                                   (db/pull @connection
                                           [:seon.cluster.run/id]
                                           (:db/id
                                            (:seon.cluster.eval/run
                                             receipt))))]
                       (cond-> {:id (:seon.cluster.eval/id receipt)
                                :run run-id
                                :ordinal (:seon.cluster.eval/ordinal receipt)}
                         (:seon.cluster.eval/result-edn receipt)
                         (assoc :settled :done)
                         (:seon.cluster.eval/error receipt)
                         (assoc :settled :error)
                         (:seon.cluster.eval/interrupted-at receipt)
                         (assoc :settled :interrupted)))))
              (db/q '[:find [?receipt ...]
                     :where [?receipt :seon.cluster.eval/id _]]
                   @connection))]
    (and
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
         ;; RECOVERY MARKS WHAT IT INTERRUPTED, and only that: a run a
         ;; dead process's custody was recovered from carries
         ;; `::interrupted-at`, a normally closed run never does. This
         ;; is the only distinction for a run whose dead process left
         ;; no receipt row to stamp
         (= (boolean (:interrupted entry))
            (some? (::run/interrupted-at entity)))
         ;; a closed run holds no custody
         (or (not (:closed entry))
             (nil? (::run/process entity)))
         ;; the agent pointer exists exactly while its run is open
         (let [agent-id (:agent entry)
               pointer (agent-pointer connection agent-id)]
           (if (:closed entry)
             (not= run-id pointer)
             (= run-id pointer)))
         ;; plan digest is write-once
         (= (:digest entry) (::run/plan-digest entity)))))
     (= (set (vals (:receipts model))) database-receipts))))

(deftest transitions-agree-with-the-model
  ;; One FRESH database per trial (and per shrink step): the pure model
  ;; resets every trial, so the world it reasons about must too.
  (let [check
        (tc/quick-check
         60
         (prop/for-all [commands commands-gen]
           (with-model-database
             (fn [connection]
               (db/transact! connection
                           (mapv (fn [id] {:seon.cluster.agent/id id})
                                 agent-ids))
               (loop [commands commands
                      model {:runs {} :pointers {} :receipts {}}
                      index 0]
                 (if (empty? commands)
                   true
                   (let [command (first commands)
                         now (at (* (inc index) 60000))
                         expected (model-eligible? model command)
                         result (execute! connection command now)
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
                                      (model-apply model command)
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

(def ^:private receipt-state-gen
  ;; which terminal fact (if any) each seeded receipt carries: running
  ;; is the ABSENCE of all three — never a stored label
  (gen/elements [:running :done :error]))

(deftest recovery-preserves-terminal-receipts-exactly
  ;; One FRESH database per trial (the shared-database class, already
  ;; fixed once in the state machine, does not get a second life), and
  ;; ids derive from generated values only — replayable under the seed.
  (let [pull-receipts
        (fn [connection run-id]
          (->> (db/q '[:find [?r ...]
                      :in $ ?run-id
                      :where
                      [?run :seon.cluster.run/id ?run-id]
                      [?r :seon.cluster.eval/run ?run]]
                    (db/db connection) run-id)
               (mapv #(db/pull (db/db connection) '[*] %))))
        pull-terminals
        (fn [connection run-id]
          (->> (pull-receipts connection run-id)
               (filterv #(or (:seon.cluster.eval/result-edn %)
                             (:seon.cluster.eval/error %)))
               (sort-by :seon.cluster.eval/ordinal)
               vec))
        check
        (tc/quick-check
         30
         (prop/for-all [states (gen/vector receipt-state-gen 1 5)
                        dead? gen/boolean
                        round gen/nat]
           (with-model-database
             (fn [connection]
               (let [run-id (str "keep-" round "-" (count states)
                                 "-" dead?)
                     agent-id (str "keeper-" run-id)
                     holder (if dead? "dead-process" "live-process")]
                 (db/transact! connection
                             [{:seon.cluster.agent/id agent-id}])
                 (db/transact! connection
                             (run/open-tx
                              {::run/id run-id
                               ::run/agent [:seon.cluster.agent/id agent-id]
                               ::run/opened-at t0}))
                 (db/transact! connection
                             (run/claim-tx {::run/id run-id
                                            ::run/process holder
                                            ::run/live-processes #{holder}
                                            ::run/now t1}))
                 (db/transact!
                  connection
                  (vec (map-indexed
                        (fn [ordinal state]
                          (cond-> {:seon.cluster.eval/id
                                   (pr-str [run-id ordinal])
                                   :seon.cluster.eval/run
                                   [:seon.cluster.run/id run-id]
                                   :seon.cluster.eval/ordinal ordinal
                                   :seon.cluster.eval/at t1}
                            (= :done state)
                            (assoc :seon.cluster.eval/result-edn
                                   (str ordinal))
                            (= :error state)
                            (assoc :seon.cluster.eval/error
                                   (str "boom-" ordinal))))
                        states)))
                 (let [receipts-before (pull-receipts connection run-id)
                       terminals-before (pull-terminals connection run-id)
                       recovery
                       (run/recover-tx
                        {::run/id run-id
                         ::run/live-processes #{"live-process"}
                         ::run/now t2})
                       _ (db/transact! connection recovery)
                       ;; recovery is IDEMPOTENT: running it again from
                       ;; current facts commits nothing new
                       _ (db/transact! connection recovery)
                       entity (run-entity connection run-id)]
                   (and
                    ;; settled receipts are IDENTICAL, whole entities
                    (= terminals-before (pull-terminals connection run-id))
                    (if dead?
                      ;; a dead holder's running receipts are stamped
                      ;; and the interrupted run is ended atomically
                      (and (every? run/terminal?
                                   (pull-receipts connection run-id))
                           (nil? (::run/process entity))
                           (some? (::run/closed-at entity))
                           (nil? (agent-pointer connection agent-id)))
                      ;; a live holder's run needs NOTHING: custody
                      ;; kept, running receipts still running
                      (and (= receipts-before
                              (pull-receipts connection run-id))
                           (= "live-process" (::run/process entity))))))))))
         :seed 20260727)]
    (is (true? (:result check))
        (str "recovery property failed: " (pr-str check)))))

(deftest recovery-cannot-stamp-a-settled-receipt
  ;; the zombie audit's order-B soft spot, made unrepresentable
  ;; (custody revision, Revision 4): `recover-call` reads the receipt
  ;; AT TRANSACTION TIME, so there is no stale caller basis from which
  ;; a settled receipt could be stamped `interrupted-at`.
  (with-model-database
    (fn [connection]
      (db/transact! connection [{:seon.cluster.agent/id "orderer"}])
      (db/transact! connection
                  (run/open-tx {::run/id "order-b"
                                ::run/agent [:seon.cluster.agent/id "orderer"]
                                ::run/opened-at t0}))
      (db/transact! connection
                  (run/claim-tx {::run/id "order-b"
                                 ::run/process "dead-process"
                                 ::run/live-processes #{"dead-process"}
                                 ::run/now t0}))
      (db/transact! connection
                  (run/receipt-start-tx {::run/id "order-b"
                                         :seon.cluster.eval/ordinal 0
                                         :seon.cluster.eval/at t0}))
      ;; the settle lands FIRST; recovery then runs against whatever
      ;; the transaction sees — which includes that settle
      (db/transact! connection
                  (run/receipt-settle-tx {::run/id "order-b"
                                          :seon.cluster.eval/ordinal 0
                                          :seon.cluster.eval/result-edn "2"}))
      (db/transact! connection
                  (run/recover-tx {::run/id "order-b"
                                   ::run/live-processes #{"live-process"}
                                   ::run/now t2}))
      (let [receipt (db/pull @connection '[*]
                            [:seon.cluster.eval/id (pr-str ["order-b" 0])])]
        (is (= "2" (:seon.cluster.eval/result-edn receipt)))
        (is (nil? (:seon.cluster.eval/interrupted-at receipt))
            "the settled receipt is byte-untouched — no contradiction
             fact can exist"))
      (is (nil? (::run/process (run-entity connection "order-b")))
          "and the dead custody is released")
      (is (some? (::run/closed-at (run-entity connection "order-b")))
          "the interrupted run is ended")
      (is (nil? (agent-pointer connection "orderer"))
          "and the agent pointer is retracted"))))

(deftest recovery-marks-a-run-that-settled-no-receipt
  ;; THE CLASS: a run whose dead process settled no receipt row was
  ;; indistinguishable by query from a run that closed normally, so
  ;; "which runs did the last recovery interrupt?" was unanswerable
  ;; from the database (whole-system-arc observer, 2026-08-08 —
  ;; `945f3226`: one form, zero receipts, no error, no marker). The run
  ;; stamp cannot be derived from receipts, because there are none.
  (with-model-database
    (fn [connection]
      (db/transact! connection [{:seon.cluster.agent/id "cut"}
                                {:seon.cluster.agent/id "clean"}])
      ;; a run a dead process was holding, with no receipt at all
      (db/transact! connection
                    (run/open-tx {::run/id "cut-run"
                                  ::run/agent [:seon.cluster.agent/id "cut"]
                                  ::run/opened-at t0}))
      (db/transact! connection
                    (run/claim-tx {::run/id "cut-run"
                                   ::run/process "dead-process"
                                   ::run/live-processes #{"dead-process"}
                                   ::run/now t0}))
      ;; and a run that closes NORMALLY, the same shape otherwise
      (db/transact! connection
                    (run/open-tx {::run/id "clean-run"
                                  ::run/agent [:seon.cluster.agent/id "clean"]
                                  ::run/opened-at t0}))
      (db/transact! connection
                    (run/claim-tx {::run/id "clean-run"
                                   ::run/process "live-process"
                                   ::run/live-processes #{"live-process"}
                                   ::run/now t0}))
      (db/transact! connection
                    (run/close-tx {::run/id "clean-run"
                                   ::run/process "live-process"
                                   ::run/closed-at t1}))
      (db/transact! connection
                    (run/recover-tx {::run/id "cut-run"
                                     ::run/live-processes #{"live-process"}
                                     ::run/now t2}))
      (let [cut (run-entity connection "cut-run")
            clean (run-entity connection "clean-run")]
        (is (= t2 (::run/interrupted-at cut))
            "recovery records the interruption it performed")
        (is (some? (::run/closed-at cut)))
        (is (nil? (::run/interrupted-at clean))
            "a normal close stays unmarked — presence is the whole state")
        (is (empty? (db/q '[:find [?r ...]
                            :where [?r :seon.cluster.eval/id _]]
                          @connection))
            "and no receipt was invented for a form that never started"))
      (is (= ["cut-run"]
             (db/q '[:find [?id ...]
                     :where
                     [?run :seon.cluster.run/interrupted-at _]
                     [?run :seon.cluster.run/id ?id]]
                   @connection))
          "\"which runs did recovery interrupt?\" is one query")
      (is (str/includes?
           (run/render-ai (assoc (run-entity connection "cut-run")
                                 :seon.db/db @connection))
           "It was interrupted at")
          "and the run says so rather than reporting \"It completed.\""))))

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
      (db/transact! connection [{:seon.cluster.agent/id "breaker"}])
      (db/transact! connection
                  (run/open-tx {::run/id "broken"
                                ::run/agent [:seon.cluster.agent/id "breaker"]
                                ::run/opened-at t0}))
      (db/transact! connection
                  (run/claim-tx {::run/id "broken"
                                 ::run/process "p1"
                                 ::run/live-processes #{"p1"}
                                 ::run/now t1}))
      ;; sever the relation out from under the run
      (db/transact! connection
                  [[:db/retract [:seon.cluster.agent/id "breaker"]
                    :seon.cluster.agent/run [::run/id "broken"]]])
      (is (= ::run/agent-pointer-broken
             (::run/rule
              (db/transact! connection
                            (run/close-tx {::run/id "broken"
                                           ::run/process "p1"
                                           ::run/closed-at t2}))))
          "close refuses ::agent-pointer-broken")
      (is (nil? (::run/closed-at (run-entity connection "broken")))
          "the refused close committed nothing"))))
