(ns seon.runtime-test
  "Tests for the unified runtime registry.

   Tests ID generation, instance registration, and crash detection."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [datalevin.core :as d]
            [seon.graph.ingest :as ingest]
            [seon.runtime :as runtime]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(def ^:private test-dir (atom nil))
(def ^:private test-conn (atom nil))

(defn- temp-dir []
  (str "tmp/test-runtime-" (System/currentTimeMillis) "-" (rand-int 10000)))

(defn- setup-datalevin! []
  (let [dir (temp-dir)
        ;; Merge graph schema with runtime schema (like production)
        merged-schema (merge ingest/datalevin-schema runtime/runtime-schema)
        conn (d/get-conn dir merged-schema)]
    (reset! test-dir dir)
    (reset! test-conn conn)
    ;; Set test connection override (replaces old init! pattern)
    (runtime/set-test-conn! conn)
    conn))

(defn- teardown-datalevin! []
  ;; Reset registry first
  (runtime/reset-registry! {})
  ;; Clear test conn override
  (runtime/set-test-conn! nil)
  ;; Close connection
  (when-let [conn @test-conn]
    (try (d/close conn) (catch Exception _)))
  ;; Clean up temp dir
  (when-let [dir @test-dir]
    (try
      (let [f (java.io.File. dir)]
        (doseq [child (reverse (file-seq f))]
          (.delete child)))
      (catch Exception _))))

(use-fixtures :each
  (fn [f]
    (setup-datalevin!)
    (try (f) (finally (teardown-datalevin!)))))

;;; ---------------------------------------------------------------------------
;;; ID Generation Tests
;;; ---------------------------------------------------------------------------

(deftest generate-id-test
  (testing "generate-id produces 6-char base62 strings"
    (let [{::runtime/keys [id]} (runtime/generate-id {})]
      (is (string? id))
      (is (= 6 (count id)))
      (is (re-matches #"[A-Za-z0-9]{6}" id))))

  (testing "generate-id with prefix"
    (let [{::runtime/keys [id]} (runtime/generate-id {::runtime/prefix "ses"})]
      (is (string? id))
      (is (re-matches #"ses-[A-Za-z0-9]{6}" id))))

  (testing "generate-id produces unique values with no collisions"
    (let [ids (set (map ::runtime/id (repeatedly 100 #(runtime/generate-id {}))))]
      (is (= 100 (count ids)) "all 100 IDs should be unique (collision checked)"))))

;;; ---------------------------------------------------------------------------
;;; Registration Tests
;;; ---------------------------------------------------------------------------

(deftest register-basic-test
  (testing "register! creates an instance in memory"
    (let [result (runtime/register! {::runtime/namespace "test.ns"
                                     ::runtime/status :running
                                     ::runtime/location :in-process})]
      (is (= "test.ns" (::runtime/namespace result)))
      (is (= :running (::runtime/status result)))
      (is (= :in-process (::runtime/location result)))
      (is (inst? (::runtime/started-at result)))))

  (testing "registered instance is queryable"
    (runtime/register! {::runtime/namespace "test.query"
                        ::runtime/status :running
                        ::runtime/location :in-process})
    (let [instance (runtime/instance {::runtime/namespace "test.query"})]
      (is (some? instance))
      (is (= "test.query" (::runtime/namespace instance)))
      (is (= :running (::runtime/status instance))))))

(deftest register-with-optional-fields-test
  (testing "register! with session-id and nrepl-port"
    (let [result (runtime/register! {::runtime/namespace "test.external"
                                     ::runtime/status :running
                                     ::runtime/location :external
                                     ::runtime/session-id "abc123"
                                     ::runtime/nrepl-port 7901})]
      (is (= "abc123" (::runtime/session-id result)))
      (is (= 7901 (::runtime/nrepl-port result)))))

  (testing "register! with component-key"
    (let [result (runtime/register! {::runtime/namespace "test.component"
                                     ::runtime/status :running
                                     ::runtime/location :in-process
                                     ::runtime/component-key :seon/test-component})]
      (is (= :seon/test-component (::runtime/component-key result))))))

(deftest register-upsert-test
  (testing "register! upserts existing instance"
    ;; Register first time
    (runtime/register! {::runtime/namespace "test.upsert"
                        ::runtime/status :running
                        ::runtime/location :in-process})

    ;; Register again with different status
    (runtime/register! {::runtime/namespace "test.upsert"
                        ::runtime/status :paused
                        ::runtime/location :in-process})

    ;; Should have updated status
    (let [instance (runtime/instance {::runtime/namespace "test.upsert"})]
      (is (= :paused (::runtime/status instance))))))

;;; ---------------------------------------------------------------------------
;;; Unregister Tests
;;; ---------------------------------------------------------------------------

(deftest unregister-test
  (testing "unregister! sets status to stopped"
    (runtime/register! {::runtime/namespace "test.unregister"
                        ::runtime/status :running
                        ::runtime/location :in-process})

    (let [result (runtime/unregister! {::runtime/namespace "test.unregister"})]
      (is (= :stopped (::runtime/status result)))
      (is (inst? (::runtime/stopped-at result)))))

  (testing "unregister! returns nil for non-existent instance"
    (let [result (runtime/unregister! {::runtime/namespace "nonexistent"})]
      (is (nil? result)))))

;;; ---------------------------------------------------------------------------
;;; Query Tests
;;; ---------------------------------------------------------------------------

(deftest instance-test
  (testing "instance returns nil for non-existent namespace"
    (is (nil? (runtime/instance {::runtime/namespace "does.not.exist"}))))

  (testing "instance returns registered instance"
    (runtime/register! {::runtime/namespace "test.instance"
                        ::runtime/status :running
                        ::runtime/location :in-process})
    (let [instance (runtime/instance {::runtime/namespace "test.instance"})]
      (is (some? instance))
      (is (= "test.instance" (::runtime/namespace instance))))))

(deftest instances-test
  (testing "instances returns empty vector when no registrations"
    (is (= [] (runtime/instances {}))))

  (testing "instances returns all registered instances"
    (runtime/register! {::runtime/namespace "test.list.1"
                        ::runtime/status :running
                        ::runtime/location :in-process})
    (runtime/register! {::runtime/namespace "test.list.2"
                        ::runtime/status :running
                        ::runtime/location :external})

    (let [all (runtime/instances {})]
      (is (= 2 (count all)))
      (is (some #(= "test.list.1" (::runtime/namespace %)) all))
      (is (some #(= "test.list.2" (::runtime/namespace %)) all)))))

;;; ---------------------------------------------------------------------------
;;; Crash Detection Tests
;;; ---------------------------------------------------------------------------

(deftest mark-crashed-test
  (testing "mark-crashed! marks running instances in Datalevin as crashed"
    ;; Register an instance (persists to Datalevin)
    (runtime/register! {::runtime/namespace "test.crash"
                        ::runtime/status :running
                        ::runtime/location :in-process})

    ;; Reset the in-memory registry (simulates restart)
    (runtime/reset-registry! {})

    ;; Now mark crashed
    (let [{::runtime/keys [crashed-count]} (runtime/mark-crashed! {})]
      (is (= 1 crashed-count)))

    ;; Verify in Datalevin
    (let [results (d/q '[:find ?status
                         :in $ ?ns
                         :where
                         [?e :seon.runtime/namespace ?ns]
                         [?e :seon.runtime/status ?status]]
                       @@test-conn "test.crash")]
      (is (= #{[:crashed]} results))))

  (testing "mark-crashed! returns 0 when no running instances"
    (runtime/reset-registry! {})
    (let [{::runtime/keys [crashed-count]} (runtime/mark-crashed! {})]
      (is (= 0 crashed-count)))))

;;; ---------------------------------------------------------------------------
;;; Persistence Tests
;;; ---------------------------------------------------------------------------

(deftest persistence-test
  (testing "registered instance is persisted to Datalevin"
    (runtime/register! {::runtime/namespace "test.persist"
                        ::runtime/status :running
                        ::runtime/location :in-process
                        ::runtime/component-key :seon/test})

    ;; Query Datalevin directly
    (let [results (d/q '[:find ?ns ?status ?location ?key
                         :in $ ?ns
                         :where
                         [?e :seon.runtime/namespace ?ns]
                         [?e :seon.runtime/status ?status]
                         [?e :seon.runtime/location ?location]
                         [?e :seon.runtime/component-key ?key]]
                       @@test-conn "test.persist")]
      (is (= 1 (count results)))
      (is (= ["test.persist" :running :in-process :seon/test] (first results)))))

  (testing "unregister updates status in Datalevin"
    (runtime/register! {::runtime/namespace "test.persist.unreg"
                        ::runtime/status :running
                        ::runtime/location :in-process})
    (runtime/unregister! {::runtime/namespace "test.persist.unreg"})

    (let [results (d/q '[:find ?status
                         :in $ ?ns
                         :where
                         [?e :seon.runtime/namespace ?ns]
                         [?e :seon.runtime/status ?status]]
                       @@test-conn "test.persist.unreg")]
      (is (= #{[:stopped]} results)))))

;;; ---------------------------------------------------------------------------
;;; Hydrate Cache Tests
;;; ---------------------------------------------------------------------------

(deftest hydrate-cache-test
  (testing "hydrate-cache! populates registry-cache from Datalevin"
    ;; Register two instances (persists to Datalevin + cache)
    (runtime/register! {::runtime/namespace "test.hydrate.a"
                        ::runtime/status :running
                        ::runtime/location :in-process
                        ::runtime/component-key :seon/a})
    (runtime/register! {::runtime/namespace "test.hydrate.b"
                        ::runtime/status :running
                        ::runtime/location :external
                        ::runtime/session-id "abcdef"
                        ::runtime/nrepl-port 9999})

    ;; Clear cache (simulates restart)
    (runtime/reset-registry! {})
    (is (= 0 (count (runtime/instances {}))))

    ;; Hydrate from Datalevin
    (let [{::runtime/keys [hydrated-count]} (runtime/hydrate-cache! {})]
      (is (= 2 hydrated-count)))

    ;; Verify instances are back in cache with correct keys
    (let [a (runtime/instance {::runtime/namespace "test.hydrate.a"})
          b (runtime/instance {::runtime/namespace "test.hydrate.b"})]
      (is (some? a))
      (is (= :running (::runtime/status a)))
      (is (= :in-process (::runtime/location a)))
      (is (= :seon/a (::runtime/component-key a)))
      (is (some? b))
      (is (= :external (::runtime/location b)))
      (is (= "abcdef" (::runtime/session-id b)))
      (is (= 9999 (::runtime/nrepl-port b))))))

(deftest mark-crashed-then-hydrate-test
  (testing "mark-crashed! + hydrate-cache! shows crashed instances in cache"
    ;; Register a running instance
    (runtime/register! {::runtime/namespace "test.crash.hydrate"
                        ::runtime/status :running
                        ::runtime/location :in-process})

    ;; Simulate restart: clear cache, mark crashed, hydrate
    (runtime/reset-registry! {})
    (runtime/mark-crashed! {})
    (runtime/hydrate-cache! {})

    ;; Instance should be in cache with :crashed status
    (let [inst (runtime/instance {::runtime/namespace "test.crash.hydrate"})]
      (is (some? inst))
      (is (= :crashed (::runtime/status inst)))
      (is (inst? (::runtime/stopped-at inst))))))

;;; ---------------------------------------------------------------------------
;;; Reset Tests
;;; ---------------------------------------------------------------------------

(deftest reset-registry-test
  (testing "reset-registry! clears in-memory state"
    (runtime/register! {::runtime/namespace "test.reset"
                        ::runtime/status :running
                        ::runtime/location :in-process})

    (is (= 1 (count (runtime/instances {}))))

    (runtime/reset-registry! {})

    (is (= 0 (count (runtime/instances {}))))))

;;; ---------------------------------------------------------------------------
;;; Agent Run Tests
;;; ---------------------------------------------------------------------------

(deftest start-agent-run-test
  (testing "start-agent-run! creates an entity in Datalevin"
    (let [result (runtime/start-agent-run! {::runtime/agent-run-id "test1234"
                                            ::runtime/namespace "seon.test.agent"
                                            ::runtime/provider :claude})]
      (is (= "test1234" (::runtime/agent-run-id result)))
      (is (= :running (::runtime/status result)))

      ;; Verify in Datalevin
      (let [runs (d/q '[:find ?status ?ns ?provider
                         :in $ ?id
                         :where
                         [?e :seon.agent.run/id ?id]
                         [?e :seon.agent.run/status ?status]
                         [?e :seon.agent.run/namespace ?ns]
                         [?e :seon.agent.run/provider ?provider]]
                       @@test-conn "test1234")]
        (is (= #{[:running "seon.test.agent" :claude]} runs)))))

  (testing "start-agent-run! links to runtime instance if present"
    ;; Register a runtime instance first
    (runtime/register! {::runtime/namespace "seon.linked.agent"
                        ::runtime/status :running
                        ::runtime/location :external})
    (runtime/start-agent-run! {::runtime/agent-run-id "linked01"
                               ::runtime/namespace "seon.linked.agent"
                               ::runtime/provider :claude})
    ;; Verify ref exists
    (let [refs (d/q '[:find ?ref
                       :in $ ?id
                       :where
                       [?e :seon.agent.run/id ?id]
                       [?e :seon.agent.run/runtime ?ref]]
                     @@test-conn "linked01")]
      (is (= 1 (count refs))))))

(deftest complete-agent-run-test
  (testing "complete-agent-run! updates status and stats"
    (runtime/start-agent-run! {::runtime/agent-run-id "comp1234"
                               ::runtime/namespace "seon.test.complete"
                               ::runtime/provider :claude})
    (let [result (runtime/complete-agent-run! {::runtime/agent-run-id "comp1234"
                                              ::runtime/status :completed
                                              ::runtime/cost-usd 0.45
                                              ::runtime/num-turns 8
                                              ::runtime/duration-ms 30000})]
      (is (= "comp1234" (::runtime/agent-run-id result)))
      (is (= :completed (::runtime/status result)))

      ;; Verify in Datalevin
      (let [runs (d/q '[:find ?status ?cost ?turns ?dur
                         :in $ ?id
                         :where
                         [?e :seon.agent.run/id ?id]
                         [?e :seon.agent.run/status ?status]
                         [?e :seon.agent.run/cost-usd ?cost]
                         [?e :seon.agent.run/num-turns ?turns]
                         [?e :seon.agent.run/duration-ms ?dur]]
                       @@test-conn "comp1234")]
        (is (= #{[:completed 0.45 8 30000]} runs))))))

(deftest agent-runs-test
  (testing "agent-runs returns all runs"
    (runtime/start-agent-run! {::runtime/agent-run-id "run-a"
                               ::runtime/namespace "seon.test.a"
                               ::runtime/provider :claude})
    (runtime/start-agent-run! {::runtime/agent-run-id "run-b"
                               ::runtime/namespace "seon.test.b"
                               ::runtime/provider :claude})

    (let [runs (runtime/agent-runs {})]
      (is (= 2 (count runs)))
      (is (every? #(= :running (:seon.agent.run/status %)) runs))))

  (testing "agent-runs filters by namespace"
    (let [runs (runtime/agent-runs {::runtime/namespace "seon.test.a"})]
      (is (= 1 (count runs)))
      (is (= "seon.test.a" (:seon.agent.run/namespace (first runs)))))))

;;; ---------------------------------------------------------------------------
;;; Flow Snapshot Tests
;;; ---------------------------------------------------------------------------

(deftest snapshot-persistence-test
  (testing "snapshot entity can be written and queried back"
    (let [now (java.util.Date.)
          snap-id (str "test-flow/" (.toInstant now))
          data-str (pr-str {:proc-a {:state 42} :proc-b {:state "ok"}})]
      ;; Write directly to Datalevin (no real flow needed)
      (d/transact! @test-conn [{:seon.flow.snap/id snap-id
                                 :seon.flow.snap/label "test-flow"
                                 :seon.flow.snap/created-at now
                                 :seon.flow.snap/reason :shutdown
                                 :seon.flow.snap/data data-str}])

      ;; Query back via latest-snapshot
      (let [snap (runtime/latest-snapshot {::runtime/label "test-flow"})]
        (is (some? snap))
        (is (= snap-id (:seon.flow.snap/id snap)))
        (is (= "test-flow" (:seon.flow.snap/label snap)))
        (is (= :shutdown (:seon.flow.snap/reason snap)))
        (is (= data-str (:seon.flow.snap/data snap)))))))

(deftest latest-snapshot-returns-most-recent-test
  (testing "latest-snapshot returns the newest snapshot by created-at"
    (let [old-time (java.util.Date. (- (System/currentTimeMillis) 60000))
          new-time (java.util.Date.)
          old-id (str "multi-flow/" (.toInstant old-time))
          new-id (str "multi-flow/" (.toInstant new-time))]
      (d/transact! @test-conn [{:seon.flow.snap/id old-id
                                 :seon.flow.snap/label "multi-flow"
                                 :seon.flow.snap/created-at old-time
                                 :seon.flow.snap/reason :backup
                                 :seon.flow.snap/data (pr-str {:old true})}
                                {:seon.flow.snap/id new-id
                                 :seon.flow.snap/label "multi-flow"
                                 :seon.flow.snap/created-at new-time
                                 :seon.flow.snap/reason :manual
                                 :seon.flow.snap/data (pr-str {:new true})}])

      (let [snap (runtime/latest-snapshot {::runtime/label "multi-flow"})]
        (is (= new-id (:seon.flow.snap/id snap)))
        (is (= :manual (:seon.flow.snap/reason snap)))))))

(deftest latest-snapshot-nil-when-none-test
  (testing "latest-snapshot returns nil for unknown label"
    (is (nil? (runtime/latest-snapshot {::runtime/label "nonexistent"})))))

;;; ---------------------------------------------------------------------------
;;; Flow Handle Tests
;;; ---------------------------------------------------------------------------

(deftest flow-handle-roundtrip-test
  (testing "register-flow!/get-flow/list-flows/unregister-flow! round-trip"
    (runtime/register-flow! {::runtime/flow-id :test/flow-a
                             ::runtime/flow :fake-flow-obj
                             ::runtime/chans {:error-chan :fake-err :report-chan :fake-rep}
                             ::runtime/label "Flow A"})
    (let [handle (runtime/get-flow {::runtime/flow-id :test/flow-a})]
      (is (some? handle))
      (is (= :fake-flow-obj (:flow handle)))
      (is (= "Flow A" (:label handle)))
      (is (inst? (:started-at handle))))

    (runtime/register-flow! {::runtime/flow-id :test/flow-b
                             ::runtime/flow :fake-flow-b
                             ::runtime/chans {:error-chan :e :report-chan :r}
                             ::runtime/label "Flow B"})
    (let [flows (runtime/list-flows {})]
      (is (= 2 (count flows)))
      (is (contains? flows :test/flow-a))
      (is (contains? flows :test/flow-b)))

    (let [removed (runtime/unregister-flow! {::runtime/flow-id :test/flow-a})]
      (is (some? removed))
      (is (= :fake-flow-obj (:flow removed))))
    (is (nil? (runtime/get-flow {::runtime/flow-id :test/flow-a})))
    (is (= 1 (count (runtime/list-flows {}))))))

(deftest flow-handle-overwrite-test
  (testing "re-registering same flow-id overwrites"
    (runtime/register-flow! {::runtime/flow-id :test/dup
                             ::runtime/flow :old
                             ::runtime/chans {}
                             ::runtime/label "Old"})
    (runtime/register-flow! {::runtime/flow-id :test/dup
                             ::runtime/flow :new
                             ::runtime/chans {}
                             ::runtime/label "New"})
    (is (= :new (:flow (runtime/get-flow {::runtime/flow-id :test/dup}))))
    (is (= 1 (count (runtime/list-flows {}))))))

(deftest flow-handle-unregister-nonexistent-test
  (testing "unregistering non-existent flow-id returns nil"
    (is (nil? (runtime/unregister-flow! {::runtime/flow-id :test/nope})))))

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.runtime-test)
  nil)
