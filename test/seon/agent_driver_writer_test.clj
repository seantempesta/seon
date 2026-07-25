(ns seon.agent-driver-writer-test
  "JVM claim-driver reply-policy regressions."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.agent.driver :as driver]
            [seon.agent.lifecycle :as lifecycle]
            [seon.agent.run.core :as run.core]
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
            [seon.db.leaf :as db.leaf]
            [seon.host.context :as context]
            [seon.host.eval :as host.eval]
            [seon.host.invoke :as invoke]
            [seon.host.session.leaf :as session]
            [seon.program.edge :as edge])
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
        requests (atom [])
        config-row
        {:seon.config.claim-driver/llm-attempt-timeout-ms 32100
         :seon.config.llm-retry/base-wait-ms 500
         :seon.config.llm-retry/growth-factor 2.0
         :seon.config.llm-retry/jitter-fraction 0.5
         :seon.config.llm-retry/maximum-wait-ms 20000
         :seon.config.llm-retry/maximum-total-wait-ms 60000
         :seon.config.llm-retry/default-retries 4}
        resolve-context
        (fn []
          (with-redefs
            [db/pull
             (fn [{ref ::db/ref :as request}]
               (swap! requests conj request)
               (if (= config.resolve/cluster-config-lookup-ref ref)
                 config-row
                 @agent-row))]
            (#'driver.host/resolve-llm-context! {:t 7} "agent")))]
    (is (= 32100
           (get-in (resolve-context)
                   [:seon.ai/config-resolution
                    :seon.ai/agent-attempt-timeout-ms])))
    (is (= (config.resolve/llm-retry-configuration config-row)
           (select-keys
            (resolve-context)
            config.resolve/llm-retry-attributes)))
    (is (some
         #(and (= config.resolve/cluster-config-lookup-ref (::db/ref %))
               (some #{:seon.config.claim-driver/llm-attempt-timeout-ms}
                     (::db/pull-pattern %)))
         @requests))
    (reset! agent-row {:seon.ai/agent-attempt-timeout-ms 65400})
    (is (= 65400
           (get-in (resolve-context)
                   [:seon.ai/config-resolution
                    :seon.ai/agent-attempt-timeout-ms])))))

(deftest reply-program-receives-the-successful-attempts-frozen-mode
  (let [received (atom nil)
        result (atom nil)
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
        (reset! result
                (#'driver.host/reply-program nil turn "agent"))))
    (is (= ["(+ 1 2)" :batch 'my.agent] @received))
    (is (= "(+ 1 2)"
           (:seon.agent.driver/reply-content @result)))))

(deftest invocation-limits-come-from-the-config-singleton-entity
  (let [request (atom nil)
        configuration
        {:seon.config/id config.resolve/cluster-config-id
         :seon.config.claim-driver/invocation-deadline-ms 120000
         :seon.config.claim-driver/invocation-result-maximum-bytes 1048576
         :seon.config.claim-driver/llm-attempt-timeout-ms 120000
         :seon.config.shell/default-timeout-ms 30000
         :seon.config.shell/kill-grace-ms 1000}]
    (with-redefs [db/pull
                  (fn [value]
                    (reset! request value)
                    configuration)]
      (is (= configuration
             (#'driver.host/invocation-configuration! ::database))))
    (is (= config.resolve/cluster-config-lookup-ref (::db/ref @request)))
    (is (= (into [:seon.config/id]
                 (concat config.resolve/claim-driver-attributes
                         config.resolve/shell-attributes))
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

(deftest no-roots-disposition-delivers-the-formless-reply-before-done
  (let [allocations
        [{::db.id/key ::agent-id
          ::db.id/identity-attr :seon.agent/id}
         {::db.id/key ::run-id
          ::db.id/identity-attr :seon.agent.run/id}
         {::db.id/key ::turn-id
          ::db.id/identity-attr :seon.agent.turn/id}]
        candidates
        (db.id/candidate-manifest
         {:seon.agent/id :seon.db.id.generator/human-readable
          :seon.agent.run/id :seon.db.id.generator/compact
          :seon.agent.turn/id :seon.db.id.generator/compact}
         allocations)
        ids (into {} (map (juxt ::db.id/key ::db.id/value)) candidates)
        agent-id (::agent-id ids)
        run-id (::run-id ids)
        turn-id (::turn-id ids)
        now (java.util.Date.)
        content "FORMLESS_FINAL_REPLY"
        seeded
        (transact-generated!
         [{:db/id "agent"
           :seon.agent/id agent-id
           :seon.agent/run "run"}
          {:db/id "run"
           :seon.agent.run/id run-id
           :seon.agent.run/agent "agent"
           :seon.agent.run/status :open
           :seon.agent.run/claimant driver/claimant
           :seon.agent.run/claim-epoch 2
           :seon.agent.run/last-beat-at now}
          {:db/id "turn"
           :seon.agent.turn/id turn-id
           :seon.agent.turn/run "run"
           :seon.agent.turn/status :running
           :seon.agent.turn/phase :reply-ready}]
         candidates)
        database (context/resolve-head! *writer-session*)
        tier-inventory
        {:seon.execution.inventory/tier :jvm
         :seon.execution.inventory/bindings #{}
         :seon.execution.inventory/remote-bindings #{}
         :seon.execution.inventory/pure-bindings #{}
         :seon.execution.inventory/digest "no-roots-jvm"}
        host
        {:seon.execution/artifact-inventories
         {:seon.execution.inventory/availability :available
          :seon.execution.inventory/exports-by-tier {:jvm #{}}
          :seon.execution.inventory/digest "no-roots-artifacts"}
         :seon.host/base {::context/tier-inventory tier-inventory}}
        claim
        {:seon.db/db database
         :seon.agent.run/claim-epoch 2
         :seon.agent.driver/run
         {:seon.agent/id agent-id
          :seon.agent.run/id run-id
          :seon.agent.run/current-turn
          {:seon.agent.turn/id turn-id}}}
        forbidden (fn [& _] (throw (ex-info "eval dispatch was not expected" {})))
        result
        (binding [db/*leaf* (database-leaf)]
          (with-redefs-fn
            {#'driver.host/reply-program
             (fn [& _]
               {:seon.repl/eval-entries
                [{:seon.repl/kind :narration
                  :seon.repl/narration content}]
                :seon.repl/errors []
                :seon.agent.driver/reply-content content})
             #'driver.host/ensure-context! (constantly ::retained)
             #'host.eval/agent-home-ns (constantly 'my.agent)
             #'host.eval/namespace-resolution
             (fn [& _]
               {::edge/namespace 'my.agent
                ::edge/aliases {}
                ::edge/refers {}
                ::edge/current-vars #{}
                ::edge/core-vars #{}
                ::edge/known-namespaces #{'my.agent}
                ::edge/macro-symbols #{}
                ::edge/effects {}})
             #'driver.host/invocation-configuration! forbidden
             #'driver.host/run-eval-batch! forbidden}
            (fn []
              (#'driver.host/eval-step! host nil claim))))
        published
        (binding [db/*leaf* (database-leaf)]
          (db/transact!
           {::db/db (:seon.db/db result)
            ::db/tx-data
            (turn.core/advance-phase-tx-data
             (run.core/run-fence agent-id run-id 2)
             turn-id :evaled :published
             [{:seon.agent.turn/id turn-id
               :seon.agent.turn/status :done}])}))
        turn
        (pull!
         [:seon.agent.turn/status
          :seon.agent.turn/phase
          {:seon.agent.turn/evals [:seon.eval/id]}]
         [:seon.agent.turn/id turn-id])
        messages
        (binding [db/*leaf* (database-leaf)]
          (db/query
           {::db/db (:db-after published)
            ::db/query
            '[:find ?content ?agent-id ?user-id
              :in $ ?content
              :where
              [?message :seon.agent.message/content ?content]
              [?message :seon.agent.message/from ?agent]
              [?agent :seon.agent/id ?agent-id]
              [?message :seon.agent.message/to ?user]
              [?user :seon.user/id ?user-id]]
            ::db/args [content]}))]
    (is (true? (::protocol/success? seeded)) (pr-str seeded))
    (is (= :no-dispatch (:seon.agent.driver/disposition result)))
    (is (= {:seon.agent.turn/status :done
            :seon.agent.turn/phase :published}
           turn))
    (is (= #{[content agent-id "user"]} messages))))

(deftest installed-lazy-capability-namespaces-enter-root-resolution
  (let [tier-inventory
        {:seon.execution.inventory/tier :jvm
         :seon.execution.inventory/bindings
         #{"seon.agent.lifecycle/complete" "seon.db/query"}
         :seon.execution.inventory/remote-bindings #{}
         :seon.execution.inventory/pure-bindings #{}
         :seon.execution.inventory/digest "inventory"}]
    (with-redefs
      [host.eval/namespace-resolution
       (fn [_ _]
         {::edge/namespace 'my.agent.fixture
          ::edge/aliases {}
          ::edge/refers {}
          ::edge/current-vars #{}
          ::edge/core-vars #{}
          ::edge/known-namespaces #{'my.agent.fixture}
          ::edge/macro-symbols #{}
          ::edge/effects {}})]
      (is (= #{'my.agent.fixture 'seon.agent.lifecycle 'seon.db}
             (::edge/known-namespaces
              (#'driver.host/planning-root-resolution
               tier-inventory ::retained 'my.agent.fixture)))))))

(deftest exact-plan-disposition-drives-eval-batch-and-provisions-bindings
  (let [installed (atom [])
        host {:seon.host/base {::context/registry ::registry}}
        host-session {::session/ctx ::retained}
        execution-plan
        {:seon.execution/capability-manifest
         {:seon.execution/required-bindings
          #{"seon.agent.lifecycle/complete" "seon.db/query"}}}]
    (with-redefs
      [context/install-registered-wrappers!
       (fn [request] (swap! installed conj request))]
      (is (nil? (#'driver.host/provision-plan-bindings!
                 host host-session execution-plan))))
    (is (= #{{::context/registry ::registry
              ::context/ctx ::retained
              ::context/lib 'seon.agent.lifecycle}
             {::context/registry ::registry
              ::context/ctx ::retained
              ::context/lib 'seon.db}}
           (set @installed))))
  (let [allocations
        [{::db.id/key ::agent-id
          ::db.id/identity-attr :seon.agent/id}
         {::db.id/key ::run-id
          ::db.id/identity-attr :seon.agent.run/id}
         {::db.id/key ::turn-id
          ::db.id/identity-attr :seon.agent.turn/id}]
        candidates
        (db.id/candidate-manifest
         {:seon.agent/id :seon.db.id.generator/human-readable
          :seon.agent.run/id :seon.db.id.generator/compact
          :seon.agent.turn/id :seon.db.id.generator/compact}
         allocations)
        ids (into {} (map (juxt ::db.id/key ::db.id/value)) candidates)
        agent-id (::agent-id ids)
        run-id (::run-id ids)
        turn-id (::turn-id ids)
        seeded
        (transact-generated!
         [{:db/id "agent"
           :seon.agent/id agent-id
           :seon.agent/run "run"}
          {:db/id "run"
           :seon.agent.run/id run-id
           :seon.agent.run/agent "agent"
           :seon.agent.run/status :open
           :seon.agent.run/claimant driver/claimant
           :seon.agent.run/claim-epoch 2
           :seon.agent.run/last-beat-at (java.util.Date.)}
          {:db/id "turn"
           :seon.agent.turn/id turn-id
           :seon.agent.turn/run "run"
           :seon.agent.turn/status :running
           :seon.agent.turn/phase :reply-ready}]
         candidates)
        database (context/resolve-head! *writer-session*)
        tier-inventory
        {:seon.execution.inventory/tier :jvm
         :seon.execution.inventory/bindings #{}
         :seon.execution.inventory/remote-bindings #{}
         :seon.execution.inventory/pure-bindings #{}
         :seon.execution.inventory/digest "exact-plan-jvm"}
        host
        {:seon.execution/artifact-inventories
         {:seon.execution.inventory/availability :available
          :seon.execution.inventory/exports-by-tier {:jvm #{}}
          :seon.execution.inventory/digest "exact-plan-artifacts"}
         :seon.host/base {::context/tier-inventory tier-inventory}}
        claim
        {:seon.db/db database
         :seon.agent.run/claim-epoch 2
         :seon.agent.driver/run
         {:seon.agent/id agent-id
          :seon.agent.run/id run-id
          :seon.agent.run/current-turn
          {:seon.agent.turn/id turn-id}}}
        eval-batches (atom [])
        result
        (binding [db/*leaf* (database-leaf)]
          (with-redefs-fn
            {#'driver.host/reply-program
             (fn [& _]
               {:seon.repl/eval-entries
                [{:seon.repl/kind :form
                  :seon.repl/form 42}]
                :seon.repl/errors []
                :seon.agent.driver/reply-content "42"})
             #'driver.host/ensure-context! (constantly ::retained)
             #'host.eval/agent-home-ns (constantly 'my.agent)
             #'host.eval/namespace-resolution
             (fn [& _]
               {::edge/namespace 'my.agent
                ::edge/aliases {}
                ::edge/refers {}
                ::edge/current-vars #{}
                ::edge/core-vars #{}
                ::edge/known-namespaces #{'my.agent}
                ::edge/macro-symbols #{}
                ::edge/effects {}})
             #'driver.host/invocation-configuration!
             (constantly ::configuration)
             #'driver.host/run-eval-batch!
             (fn [_host _run _claim-epoch eval-database program
                  configuration exact-plan]
               (swap! eval-batches conj
                      {:seon.db/db eval-database
                       :seon.agent.driver/program program
                       :seon.config/configuration configuration
                       :seon.execution/plan exact-plan})
               {:seon.db/db eval-database
                :seon.agent.driver/eval-batch
                {:seon.eval/n-ok 1
                 :seon.eval/n-fail 0}})}
            (fn []
              (#'driver.host/eval-step! host nil claim))))
        turn
        (pull!
         [:seon.agent.turn/status
          :seon.agent.turn/phase]
         [:seon.agent.turn/id turn-id])]
    (is (true? (::protocol/success? seeded)) (pr-str seeded))
    (is (= {:seon.eval/n-ok 1
            :seon.eval/n-fail 0}
           (:seon.agent.driver/eval-batch result)))
    (is (= 1 (count @eval-batches)))
    (is (= :jvm
           (get-in (first @eval-batches)
                   [:seon.execution/plan
                    :seon.execution/selected-tier])))
    (is (= ::configuration
           (:seon.config/configuration (first @eval-batches))))
    (is (= {:seon.agent.turn/status :running
            :seon.agent.turn/phase :evaling}
           turn))))

(deftest terminal-lifecycle-eval-delivers-and-closes-through-the-driver
  (let [allocations
        [{::db.id/key ::agent-id
          ::db.id/identity-attr :seon.agent/id}
         {::db.id/key ::run-id
          ::db.id/identity-attr :seon.agent.run/id}
         {::db.id/key ::turn-id
          ::db.id/identity-attr :seon.agent.turn/id}]
        candidates
        (db.id/candidate-manifest
         {:seon.agent/id :seon.db.id.generator/human-readable
          :seon.agent.run/id :seon.db.id.generator/compact
          :seon.agent.turn/id :seon.db.id.generator/compact}
         allocations)
        ids (into {} (map (juxt ::db.id/key ::db.id/value)) candidates)
        agent-id (::agent-id ids)
        run-id (::run-id ids)
        turn-id (::turn-id ids)
        now (java.util.Date.)
        content (str "ALIVE_GATE_TERMINAL_" run-id)
        terminal-value (lifecycle/complete content)
        seeded
        (transact-generated!
         [{:db/id "agent"
           :seon.agent/id agent-id
           :seon.agent/run "run"}
          {:db/id "run"
           :seon.agent.run/id run-id
           :seon.agent.run/agent "agent"
           :seon.agent.run/status :open
           :seon.agent.run/started-at now
           :seon.agent.run/claimant driver/claimant
           :seon.agent.run/claim-epoch 1
           :seon.agent.run/last-beat-at now}
          {:db/id "turn"
           :seon.agent.turn/id turn-id
           :seon.agent.turn/run "run"
           :seon.agent.turn/status :running
           :seon.agent.turn/phase :evaling}]
         candidates)
        executor (java.util.concurrent.Executors/newSingleThreadExecutor)
        bindings (atom [])
        invocation (atom nil)
        completed
        (try
          (binding [db/*leaf* (database-leaf)]
            (with-redefs-fn
              {#'driver.host/driver-session (constantly {})
               #'driver.host/provision-plan-bindings!
               (fn [_host _session plan]
                 (swap! bindings conj
                        (get-in plan
                                [:seon.execution/capability-manifest
                                 :seon.execution/required-bindings])))
               #'invoke/execute-invocation!
               (fn [_session request]
                 (reset! invocation request)
                 {:seon.eval/n-ok 1
                  :seon.eval/n-fail 0
                  :seon.host/results
                  [{:seon.eval/ok? true
                    :seon.eval/value terminal-value}]})}
              (fn []
                (#'driver.host/run-eval-batch!
                 {:seon.host/writer *writer-session*
                  :seon.host/eval-pool executor}
                 {:seon.agent/id agent-id
                  :seon.agent.run/id run-id
                  :seon.agent.run/current-turn
                  {:seon.agent.turn/id turn-id}}
                 1 (context/resolve-head! *writer-session*)
                 {:seon.repl/eval-entries
                  [{:seon.repl/kind :form
                    :seon.repl/source
                    (str "(seon.agent.lifecycle/complete "
                         (pr-str content) ")")}]
                  :seon.repl/errors []}
                 {:seon.config.claim-driver/invocation-deadline-ms 60000
                  :seon.config.claim-driver/invocation-result-maximum-bytes
                  65536}
                 {:seon.execution/capability-manifest
                  {:seon.execution/required-bindings #{}}}))))
          (finally
            (.shutdownNow executor)))
        database (context/resolve-head! *writer-session*)
        run
        (binding [db/*leaf* (database-leaf)]
          (db/pull
           {::db/db database
            ::db/pull-pattern
            [:seon.agent.run/status :seon.agent.run/closed-reason
             :seon.agent.run/result :seon.agent.run/claimant]
            ::db/ref [:seon.agent.run/id run-id]}))
        agent
        (pull! [:seon.agent/id :seon.agent/run]
               [:seon.agent/id agent-id])
        turn
        (pull! [:seon.agent.turn/status :seon.agent.turn/phase]
               [:seon.agent.turn/id turn-id])
        completion-txs
        (binding [db/*leaf* (database-leaf)]
          (db/query
           {::db/db (db/history database)
            ::db/query
            '[:find ?message-tx ?close-tx ?turn-status-tx ?turn-phase-tx
              ?from-id ?to-id
              :in $ ?content ?run-id ?turn-id
              :where
              [?message :seon.agent.message/content ?content ?message-tx true]
              [?message :seon.agent.message/from ?from]
              [?from :seon.agent/id ?from-id]
              [?message :seon.agent.message/to ?to]
              [?to :seon.user/id ?to-id]
              [?run :seon.agent.run/id ?run-id]
              [?run :seon.agent.run/status :closed ?close-tx true]
              [?run :seon.agent.run/closed-reason :completed ?close-tx true]
              [?run :seon.agent.run/result ?content ?close-tx true]
              [?turn :seon.agent.turn/id ?turn-id]
              [?turn :seon.agent.turn/status :done ?turn-status-tx true]
              [?turn :seon.agent.turn/phase :published ?turn-phase-tx true]]
            ::db/args [content run-id turn-id]}))]
    (is (true? (::protocol/success? seeded)) (pr-str seeded))
    (is (lifecycle/terminal-value? terminal-value))
    (is (= :completed (:seon.agent.lifecycle/terminal terminal-value)))
    (is (= #{#{}} (set @bindings))
        "terminal eval needs no capability bindings")
    (is (= :completed (:seon.agent.run/closed-reason completed)))
    (is (= agent-id (:seon.execution/agent-id @invocation)))
    (is (= {:seon.agent.run/status :closed
            :seon.agent.run/closed-reason :completed
            :seon.agent.run/result content}
           run))
    (is (= {:seon.agent/id agent-id} agent))
    (is (= {:seon.agent.turn/status :done
            :seon.agent.turn/phase :published}
           turn))
    (is (= 1 (count completion-txs))
        (pr-str completion-txs))
    (let [[message-tx close-tx turn-status-tx turn-phase-tx from-id to-id]
          (first completion-txs)]
      (is (apply = [message-tx close-tx turn-status-tx turn-phase-tx])
          "driver commits message, run completion, and turn publication atomically")
      (is (= agent-id from-id))
      (is (= "user" to-id)))))

(defn- phase-error-case!
  [attempt-open? throw-mid-reply?]
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
        phase (cond
                throw-mid-reply? :reply-ready
                attempt-open? :attempt-open
                :else :rendered)
        message
        (str "claim phase "
             (if throw-mid-reply? "threw mid-reply" "failed")
             " "
             (if attempt-open? "after receipt" "before receipt")
             " "
             turn-id)
        settled-message
        (if throw-mid-reply?
          (str "The claimed eval phase threw: " message)
          message)
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
         #{(if throw-mid-reply?
             :seon.agent.driver.capability/eval
             :seon.agent.driver.capability/llm)}
         :seon.agent.driver/now (constantly now)
         :seon.agent.driver/execute-step!
         (fn [_]
           (if throw-mid-reply?
             (throw (ex-info message {:seon.error/kind :core-bug}))
             {:seon.error/message message
              :seon.error/kind :configuration}))}
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
            :seon.agent.turn/error settled-message}
           turn))
    (is (every? :seon.db/ok? @*error-persist-reports*)
        (pr-str @*error-persist-reports*))
    (is (= #{[:core settled-message]} (set (fault-rows settled-message)))
        (pr-str (all-fault-rows)))
    (when attempt-open?
      (is (= {:seon.ai.attempt/outcome :crashed}
             (pull! [:seon.ai.attempt/outcome
                     :seon.ai.attempt/partial-text]
                    [:seon.ai.attempt/id attempt-id]))))))

(deftest phase-errors-persist-faults-and-release-custody
  (testing "before an attempt receipt is admitted"
    (phase-error-case! false false))
  (testing "after an attempt receipt is durable"
    (phase-error-case! true false)))

(deftest thrown-reply-phase-settles-terminal-in-the-same-drive-step
  (phase-error-case! false true))

(deftest timeout-terminalizes-the-evaling-turn-without-a-second-stale-cas
  (let [allocations
        [{::db.id/key ::agent-id
          ::db.id/identity-attr :seon.agent/id}
         {::db.id/key ::run-id
          ::db.id/identity-attr :seon.agent.run/id}
         {::db.id/key ::turn-id
          ::db.id/identity-attr :seon.agent.turn/id}
         {::db.id/key ::eval-id
          ::db.id/identity-attr :seon.eval/id}]
        candidates
        (db.id/candidate-manifest
         {:seon.agent/id :seon.db.id.generator/human-readable
          :seon.agent.run/id :seon.db.id.generator/compact
          :seon.agent.turn/id :seon.db.id.generator/compact
          :seon.eval/id :seon.db.id.generator/compact}
         allocations)
        ids (into {} (map (juxt ::db.id/key ::db.id/value)) candidates)
        agent-id (::agent-id ids)
        run-id (::run-id ids)
        turn-id (::turn-id ids)
        eval-id (::eval-id ids)
        now (java.util.Date.)
        close-message
        "The run closed :superseded before the active turn published."
        seeded
        (transact-generated!
         [{:db/id "agent"
           :seon.agent/id agent-id
           :seon.agent/run "run"}
          {:db/id "run"
           :seon.agent.run/id run-id
           :seon.agent.run/agent "agent"
           :seon.agent.run/status :open
           :seon.agent.run/claimant driver/claimant
           :seon.agent.run/claim-epoch 2
           :seon.agent.run/last-beat-at now}
          {:db/id "turn"
           :seon.agent.turn/id turn-id
           :seon.agent.turn/run "run"
           :seon.agent.turn/status :running
           :seon.agent.turn/phase :evaling}
          {:db/id "eval"
           :seon.eval/id eval-id
           :seon.eval/source "(seon.schema/register! :my.memory/value :int)"
           :seon.eval/at now
           :seon.eval/status :done
           :seon.eval/ok? true}
          [:db/add "turn" :seon.agent.turn/evals "eval"]]
         candidates)
        database (context/resolve-head! *writer-session*)
        held
        {:seon.db/db database
         :seon.agent.run/claim-epoch 2
         :seon.agent.driver/run
         {:seon.agent/id agent-id
          :seon.agent.run/id run-id
          :seon.agent.run/status :open
          :seon.agent.run/claimant driver/claimant
          :seon.agent.run/claim-epoch 2
          :seon.agent.run/current-turn
          {:seon.agent.turn/id turn-id
           :seon.agent.turn/status :running
           :seon.agent.turn/phase :evaling}}}
        transaction-calls (atom 0)
        base-leaf (database-leaf)
        counting-leaf
        (update
         base-leaf db.leaf/transaction-call!
         (fn [transaction-call!]
           (fn [request recoverable?]
             (swap! transaction-calls inc)
             (transaction-call! request recoverable?))))
        fault-count-before (count (all-fault-rows))
        platform-leaf
        {:seon.agent.driver/capabilities
         #{:seon.agent.driver.capability/eval}
         :seon.agent.driver/now (constantly now)
         :seon.agent.driver/execute-step!
         (fn [_]
           (let [run-fence (turn.core/terminal-close-tx-data
                            (run.core/run-fence agent-id run-id 2)
                            agent-id run-id turn-id :evaling []
                            now :interrupted :superseded close-message)
                 closed (db/transact!
                         {::db/db database
                          ::db/tx-data run-fence})]
             (if (:seon.error/message closed)
               closed
               (db/transact!
                 {::db/db database
                 ::db/tx-data
                 (turn.core/advance-phase-tx-data
                  (run.core/run-fence agent-id run-id 2)
                  turn-id :evaling :evaled [])}))))}
        result
        (driver/call-with-leaf
         platform-leaf counting-leaf
         #(driver/drive-claim! held))
        database-after (:seon.db/db result)
        run
        (binding [db/*leaf* base-leaf]
          (db/pull
           {::db/db database-after
            ::db/pull-pattern
            [:seon.agent.run/status :seon.agent.run/closed-reason
             :seon.agent.run/claimant]
            ::db/ref [:seon.agent.run/id run-id]}))
        agent
        (binding [db/*leaf* base-leaf]
          (db/pull
           {::db/db database-after
            ::db/pull-pattern [:seon.agent/id :seon.agent/run]
            ::db/ref [:seon.agent/id agent-id]}))
        turn
        (binding [db/*leaf* base-leaf]
          (db/pull
           {::db/db database-after
            ::db/pull-pattern
            [:seon.agent.turn/status :seon.agent.turn/phase
             :seon.agent.turn/error
             {:seon.agent.turn/evals
              [:seon.eval/status :seon.eval/ok?]}]
            ::db/ref [:seon.agent.turn/id turn-id]}))
        orphaned
        (binding [db/*leaf* base-leaf]
          (db/query
           {::db/db database-after
            ::db/query
            '[:find ?turn
              :in $ ?run-id
              :where
              [?run :seon.agent.run/id ?run-id]
              [?run :seon.agent.run/status :closed]
              [?turn :seon.agent.turn/run ?run]
              [?turn :seon.agent.turn/status :running]]
            ::db/args [run-id]}))
        closing-transactions
        (binding [db/*leaf* base-leaf]
          (db/query
           {::db/db (db/history database-after)
            ::db/query
            '[:find ?run-tx ?turn-tx
              :in $ ?run-id ?turn-id
              :where
              [?run :seon.agent.run/id ?run-id]
              [?turn :seon.agent.turn/id ?turn-id]
              [?run :seon.agent.run/status :closed ?run-tx true]
              [?turn :seon.agent.turn/status :interrupted ?turn-tx true]]
            ::db/args [run-id turn-id]}))]
    (is (true? (::protocol/success? seeded)) (pr-str seeded))
    (is (true? (:seon.agent.driver/closed? result)) (pr-str result))
    (is (= :superseded (:seon.agent.run/closed-reason result)))
    (is (= 2 @transaction-calls)
        "timeout close plus the displaced phase write; no second settlement CAS")
    (is (= fault-count-before (count (all-fault-rows)))
        "the expected displaced write records no core fault")
    (is (= {:seon.agent.run/status :closed
            :seon.agent.run/closed-reason :superseded}
           run))
    (is (= {:seon.agent/id agent-id} agent))
    (is (= {:seon.agent.turn/status :interrupted
            :seon.agent.turn/phase :published
            :seon.agent.turn/error close-message
            :seon.agent.turn/evals
            [{:seon.eval/status :done :seon.eval/ok? true}]}
           turn))
    (is (empty? orphaned))
    (is (seq closing-transactions))
    (is (every? (fn [[run-tx turn-tx]] (= run-tx turn-tx))
                closing-transactions)
        "run and turn terminal facts share the same committed transaction")))
