(ns seon.agent-driver-writer-test
  "JVM claim-driver reply-policy regressions."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.agent.driver :as driver]
            [seon.db :as db]
            [seon.db.host :as db.host]
            [seon.db.id :as db.id]
            [seon.db.protocol :as protocol]
            [seon.db.writer :as writer]
            [seon.db.writer-test-support :as writer-test]
            [seon.agent.driver.host :as driver.host]
            [seon.agent.turn.core :as turn.core]
            [seon.config.resolve :as config.resolve]
            [seon.error :as error]
            [seon.host.context :as context]
            [seon.host.eval :as host.eval])
  (:import [java.io File]))

(def ^:private ^:dynamic *writer-session* nil)
(def ^:private ^:dynamic *error-persist-reports* nil)

(defn- writer-dependencies []
  {::writer/database-initializer (fn [_connection _database-name] nil)
   ::writer/embedding-enabled? false
   ::writer/embedding-entity-ids (fn [_db-value] [])
   ::writer/embedding-inputs-for-eids (fn [_db-value _entity-ids] [])
   ::writer/embedding-assertions (fn [_inputs] [])
   ::writer/revalidate-embedding-assertions
   (fn [_db-value _assertions] [])
   ::writer/query-vec (fn [_] {:seon.embed/vector [0.0]})
   ::writer/knn (fn [_db-value _vector _k _eids] [])})

(defn- socket-path []
  (let [directory (File. "tmp")]
    (.mkdirs directory)
    (.getAbsolutePath
     (File. directory
            (str "agent-driver-writer-" (random-uuid) ".sock")))))

(use-fixtures
  :once
  (fn [run-tests!]
    (let [database-name (str "agent-driver-" (random-uuid))
          request-path (socket-path)
          server
          (writer-test/start!
           {::writer/dependencies (writer-dependencies)
            ::writer/database-name database-name
            ::writer/backend :memory
            ::writer/request-socket-path request-path})
          writer-session
          (context/writer-session
           {::context/writer-socket-path request-path
            ::context/database-name database-name
            ::context/backend :memory})]
      (try
        (let [seeded
              (writer-test/seed-canonical-schema!
               writer-session database-name
               [{:seon.user/id "user"}
                {:seon.db.process/id :seon.db.process/repl}])]
          (is (true? (::protocol/success? seeded)) (pr-str seeded)))
        (let [error-persist-reports (atom [])]
          (error/set-db-hooks!
           {:seon.error/transact!
            (fn [entities]
              (let [report (context/transact-writer!
                            writer-session entities)]
                (swap! error-persist-reports conj report)
                report))})
          (binding [*writer-session* writer-session
                    *error-persist-reports* error-persist-reports]
          (run-tests!))
          )
        (finally
          (error/set-db-hooks! {})
          (context/close-session! writer-session)
          (writer/stop! server)
          (.delete (File. ^String request-path)))))))

(defn- database-leaf []
  (driver.host/database-leaf *writer-session*))

(defn- transact-generated! [tx-data generated-candidates]
  (db.host/call!
   *writer-session*
   (protocol/transaction-request
    {::protocol/request-id (str (random-uuid))
     :seon.db/db (context/resolve-head! *writer-session*)
     ::protocol/transaction-data tx-data
     ::protocol/generated-candidates generated-candidates})))

(defn- pull! [pattern ref]
  (binding [db/*leaf* (database-leaf)]
    (db/pull
     {::db/db (context/resolve-head! *writer-session*)
      ::db/pull-pattern pattern
      ::db/ref ref})))

(defn- fault-rows [message]
  (binding [db/*leaf* (database-leaf)]
    (db/query
     {::db/db (context/resolve-head! *writer-session*)
      ::db/query
      '[:find ?fault ?message
        :in $ ?message
        :where
        [?error :seon.error/message ?message]
        [?error :seon.error/fault ?fault]]
      ::db/args [message]})))

(defn- all-fault-rows []
  (binding [db/*leaf* (database-leaf)]
    (db/query
     {::db/db (context/resolve-head! *writer-session*)
      ::db/query
      '[:find ?fault ?message
        :where
        [?error :seon.error/message ?message]
        [?error :seon.error/fault ?fault]]})))

(deftest successful-attempt-freezes-the-reply-evaluation
  (let [reply-evaluation #'driver.host/successful-reply-evaluation]
    (is (= :first-form
           (reply-evaluation
            {:seon.agent.turn/llm-attempts
             [{:seon.ai.attempt/ordinal 0
               :seon.ai.attempt/outcome :success
               :seon.ai.attempt/reply-evaluation :first-form}
              {:seon.ai.attempt/ordinal 1
               :seon.ai.attempt/outcome :outer-timeout
               :seon.ai.attempt/reply-evaluation :batch}]})))
    (is (= :batch
           (reply-evaluation
            {:seon.agent.turn/llm-attempts
             [{:seon.ai.attempt/ordinal 0
               :seon.ai.attempt/outcome :transport-error
               :seon.ai.attempt/reply-evaluation :first-form}
              {:seon.ai.attempt/ordinal 1
               :seon.ai.attempt/outcome :success
               :seon.ai.attempt/reply-evaluation :batch}]})))))

(deftest claimant-inherits-attempt-timeout-when-agent-override-is-absent
  (let [agent-row (atom {})
        resolve-context
        (fn []
          (with-redefs
            [db/pull
             (fn [{ref ::db/ref}]
               (if (= [:seon.config/id "cluster"] ref)
                 {}
                 @agent-row))
             config.resolve/llm-attempt-timeout-ms (constantly 32100)]
            (#'driver.host/resolve-llm-context! {:t 7} "agent")))]
    (is (= 32100
           (get-in (resolve-context)
                   [:seon.ai/config-resolution
                    :seon.ai/agent-attempt-timeout-ms])))
    (reset! agent-row {:seon.ai/agent-attempt-timeout-ms 65400})
    (is (= 65400
           (get-in (resolve-context)
                   [:seon.ai/config-resolution
                    :seon.ai/agent-attempt-timeout-ms])))))

(deftest reply-program-receives-the-successful-attempts-frozen-mode
  (let [received (atom nil)
        turn {:seon.agent.turn/reply-blob {:my.blob/hash "reply-hash"}
              :seon.agent.turn/llm-attempts
              [{:seon.ai.attempt/ordinal 0
                :seon.ai.attempt/outcome :success
                :seon.ai.attempt/reply-evaluation :batch}]}]
    (with-redefs-fn
      {#'driver.host/read-blob
       (fn [_storage-view _hash]
         {:my.blob/ok? true :my.blob/content "(+ 1 2)"})
       #'host.eval/agent-home-ns (constantly 'my.agent)
       #'turn.core/reply-program
       (fn [raw-reply reply-evaluation starting-ns]
         (reset! received [raw-reply reply-evaluation starting-ns])
         {:seon.repl/eval-entries [] :seon.repl/errors []})}
      (fn []
        (#'driver.host/reply-program nil turn "agent")))
    (is (= ["(+ 1 2)" :batch 'my.agent] @received))))

(deftest invocation-limits-come-from-the-config-singleton-entity
  (let [request (atom nil)
        configuration
        {:seon.config.claim-driver/invocation-deadline-ms 120000
         :seon.config.claim-driver/invocation-result-maximum-bytes 1048576}]
    (with-redefs [db/pull
                  (fn [value]
                    (reset! request value)
                    configuration)]
      (is (= configuration
             (#'driver.host/invocation-configuration! ::database))))
    (is (= [:seon.config/id "cluster"] (::db/ref @request)))
    (is (= (into [:seon.config/id]
                 config.resolve/claim-driver-attributes)
           (::db/pull-pattern @request)))))

(deftest unplannable-reply-does-not-open-the-eval-phase
  (let [transactions (atom [])
        steering
        {:seon.error/message "No exact execution plan."
         :seon.error/kind :agent
         :seon.error/data
         {:seon.execution/roots ['(unknown/call)]
          :seon.execution/missing-capability-leaves #{"unknown/call"}
          :seon.execution/missing-artifact-exports #{}
          :seon.execution/missing-schema-keys #{}
          :seon.execution/unresolved
          [{:seon.execution/reason :unresolved-symbol}]
          :seon.execution/planned-basis {:t 7}
          :seon.execution/observed-basis {:t 7}
          :seon.execution/planned-generation "graph-7"
          :seon.execution/observed-generation "graph-7"
          :seon.execution/eligible-tiers #{}
          :seon.execution/inspected-tiers #{:jvm}}}
        claim
        {:seon.db/db {:t 7}
         :seon.agent.run/claim-epoch 2
         :seon.agent.driver/run
         {:seon.agent/id "agent"
          :seon.agent.run/id "run"
          :seon.agent.run/current-turn
          {:seon.agent.turn/id "turn"}}}]
    (with-redefs-fn
      {#'driver.host/reply-program
       (fn [& _] {:seon.repl/eval-entries
                  [{:seon.repl/kind :form
                    :seon.repl/form '(unknown/call)}]})
       #'driver.host/invocation-configuration! (constantly {})
       #'driver.host/parsed-reply-plan
       (fn [& _]
         {:seon.execution/plan {}
          :seon.agent.driver/disposition
          {:seon.agent.driver/disposition :steering
           :seon.agent.driver/error steering}})
       #'db/transact!
       (fn [request]
         (swap! transactions conj request)
         {:db-after {:t 8}})}
      (fn []
        (is (= steering (#'driver.host/eval-step! {} nil claim)))))
    (is (empty? @transactions)
        "planning steering precedes phase and receipt transactions")))

(defn- phase-error-case!
  [attempt-open?]
  (let [allocations
        (cond->
         [{::db.id/key ::agent-id
           ::db.id/identity-attr :seon.agent/id}
          {::db.id/key ::run-id
           ::db.id/identity-attr :seon.agent.run/id}
          {::db.id/key ::turn-id
           ::db.id/identity-attr :seon.agent.turn/id}]
          attempt-open?
          (conj
           {::db.id/key ::attempt-id
            ::db.id/identity-attr :seon.ai.attempt/id}))
        candidates
        (db.id/candidate-manifest
         (cond->
          {:seon.agent/id :seon.db.id.generator/human-readable
           :seon.agent.run/id :seon.db.id.generator/compact
           :seon.agent.turn/id :seon.db.id.generator/compact}
           attempt-open?
           (assoc :seon.ai.attempt/id :seon.db.id.generator/compact))
         allocations)
        ids
        (into {}
              (map (juxt ::db.id/key ::db.id/value))
              candidates)
        agent-id (::agent-id ids)
        run-id (::run-id ids)
        turn-id (::turn-id ids)
        attempt-id (::attempt-id ids)
        phase (if attempt-open? :attempt-open :rendered)
        message
        (str "claim phase failed "
             (if attempt-open? "after receipt" "before receipt")
             " "
             turn-id)
        now (java.util.Date.)
        rows
        (cond->
         [{:db/id "agent"
           :seon.agent/id agent-id
           :seon.agent/run "run"}
          {:db/id "run"
           :seon.agent.run/id run-id
           :seon.agent.run/status :open
           :seon.agent.run/claimant driver/claimant
           :seon.agent.run/claim-epoch 2
           :seon.agent.run/last-beat-at now}
          {:db/id "turn"
           :seon.agent.turn/id turn-id
           :seon.agent.turn/run "run"
           :seon.agent.turn/status :running
           :seon.agent.turn/phase phase}]
          attempt-open?
          (into
           [{:db/id "attempt"
             :seon.ai.attempt/id attempt-id
             :seon.ai.attempt/ordinal 0
             :seon.ai.attempt/outcome :open
             :seon.ai.attempt/partial-text "unfinished"}
            [:db/add "turn" :seon.agent.turn/llm-attempts "attempt"]]))
        seeded (transact-generated! rows candidates)
        turn
        (cond->
         {:seon.agent.turn/id turn-id
          :seon.agent.turn/status :running
          :seon.agent.turn/phase phase}
          attempt-open?
          (assoc :seon.agent.turn/llm-attempts
                 [{:seon.ai.attempt/id attempt-id
                   :seon.ai.attempt/outcome :open}]))
        held
        {:seon.db/db (context/resolve-head! *writer-session*)
         :seon.agent.run/claim-epoch 2
         :seon.agent.driver/run
         {:seon.agent/id agent-id
          :seon.agent.run/id run-id
          :seon.agent.run/status :open
          :seon.agent.run/claimant driver/claimant
          :seon.agent.run/claim-epoch 2
          :seon.agent.run/current-turn turn}}
        platform-leaf
        {:seon.agent.driver/capabilities
         #{:seon.agent.driver.capability/llm}
         :seon.agent.driver/now (constantly now)
         :seon.agent.driver/execute-step!
         (fn [_]
           {:seon.error/message message
            :seon.error/kind :configuration})}
        result
        (driver/call-with-leaf
         platform-leaf (database-leaf)
         #(driver/drive-claim! held))
        run
        (pull! [:seon.agent.run/status
                :seon.agent.run/closed-reason
                :seon.agent.run/claimant]
               [:seon.agent.run/id run-id])
        agent
        (pull! [:seon.agent/id :seon.agent/run]
               [:seon.agent/id agent-id])
        turn
        (pull! [:seon.agent.turn/status
                :seon.agent.turn/phase
                :seon.agent.turn/error]
               [:seon.agent.turn/id turn-id])]
    (is (true? (::protocol/success? seeded)) (pr-str seeded))
    (is (true? (:seon.agent.driver/closed? result)) (pr-str result))
    (is (= :error (:seon.agent.run/closed-reason result)))
    (is (= {:seon.agent.run/status :closed
            :seon.agent.run/closed-reason :error}
           run))
    (is (= {:seon.agent/id agent-id} agent))
    (is (= {:seon.agent.turn/status :error
            :seon.agent.turn/phase :published
            :seon.agent.turn/error message}
           turn))
    (is (every? :seon.db/ok? @*error-persist-reports*)
        (pr-str @*error-persist-reports*))
    (is (= #{[:core message]} (set (fault-rows message)))
        (pr-str (all-fault-rows)))
    (when attempt-open?
      (is (= {:seon.ai.attempt/outcome :crashed}
             (pull! [:seon.ai.attempt/outcome
                     :seon.ai.attempt/partial-text]
                    [:seon.ai.attempt/id attempt-id]))))))

(deftest phase-errors-persist-faults-and-release-custody
  (testing "before an attempt receipt is admitted"
    (phase-error-case! false))
  (testing "after an attempt receipt is durable"
    (phase-error-case! true)))
