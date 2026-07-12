(ns seon.session-with-agent-test
  "End-to-end smoke tests for `seon.session/with-agent` — the JVM helper
   that scopes an MCP eval to a specific `:seon.agent/<id>` by binding
   `*conn*` and `*current-agent-id*`.

   Verifies:
   - Two sessions × two agents per session route to the correct DB.
   - Datoms written from agent A1 land in session A's DB and NOT in
     session B's DB (and vice versa).
   - Unknown agent-id throws a clear `ex-info` mentioning `Unknown
     agent-id`.
   - `with-agent` cleans up dynamic bindings on exit (normal + throw).

   This is the Wave 4.5 Item E gate: without these passing, you can't
   trust MCP eval routing by agent-id."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [seon.session :as session]
            [seon.server.registry :as server.session]))

(defn ^:private clean-registry [t]
  (let [{snap :seon.server.registry/snapshot}
        (server.session/snapshot-registry {})]
    (try
      ;; Start from a clean slate so prior test pollution can't leak in —
      ;; empty registry + agents, but the LIVE hooks kept (restoring an
      ;; empty hook vector would strand the JVM's ::raw-broadcast/::reactive
      ;; hooks for the duration of the test).
      (server.session/restore-registry!
       {:seon.server.registry/snapshot
        (assoc snap
               :seon.server.registry/registry {}
               :seon.server.registry/agents {})})
      (t)
      (finally
        (server.session/restore-registry!
         {:seon.server.registry/snapshot snap})))))

(use-fixtures :each clean-registry)

(defn ^:private setup-fixture!
  "Create two sessions, register two agents per session, install a
   minimal `:who` schema on each conn. Returns the conns + agent-ids."
  []
  (server.session/ensure-db!
   {:seon.server.registry/db-name :test.with-agent/sA
    :seon.server.registry/backend :memory})
  (server.session/ensure-db!
   {:seon.server.registry/db-name :test.with-agent/sB
    :seon.server.registry/backend :memory})
  (doseq [[aid db] [["wa-A1" :test.with-agent/sA]
                    ["wa-A2" :test.with-agent/sA]
                    ["wa-B1" :test.with-agent/sB]
                    ["wa-B2" :test.with-agent/sB]]]
    (server.session/register-agent!
     {:seon.agent/id aid
      :seon.server.registry/db-name  db}))
  (let [cA (:seon.server.registry/conn
            (server.session/get-conn
             {:seon.server.registry/db-name :test.with-agent/sA}))
        cB (:seon.server.registry/conn
            (server.session/get-conn
             {:seon.server.registry/db-name :test.with-agent/sB}))]
    (d/transact cA [{:db/ident :who
                     :db/valueType :db.type/string
                     :db/cardinality :db.cardinality/one}])
    (d/transact cB [{:db/ident :who
                     :db/valueType :db.type/string
                     :db/cardinality :db.cardinality/one}])
    {:cA cA :cB cB}))

(deftest with-agent-routes-by-agent-id
  (testing "two sessions × two agents — each eval lands in the correct DB"
    (let [{:keys [cA cB]} (setup-fixture!)]
      (session/with-agent "wa-A1"
        (d/transact session/*conn* [{:who "A1"}]))
      (session/with-agent "wa-A2"
        (d/transact session/*conn* [{:who "A2"}]))
      (session/with-agent "wa-B1"
        (d/transact session/*conn* [{:who "B1"}]))
      (session/with-agent "wa-B2"
        (d/transact session/*conn* [{:who "B2"}]))
      (let [a-data (sort (d/q '[:find [?v ...] :where [_ :who ?v]] @cA))
            b-data (sort (d/q '[:find [?v ...] :where [_ :who ?v]] @cB))]
        (is (= ["A1" "A2"] a-data)
            "session A's DB should hold exactly the datoms from agents A1 + A2")
        (is (= ["B1" "B2"] b-data)
            "session B's DB should hold exactly the datoms from agents B1 + B2")))))

(deftest with-agent-binds-current-agent-id
  (testing "*current-agent-id* is bound to the agent-id inside the body"
    (setup-fixture!)
    (is (= "wa-A1"
           (session/with-agent "wa-A1" session/*current-agent-id*)))
    (is (= "wa-B2"
           (session/with-agent "wa-B2" session/*current-agent-id*)))))

(deftest with-agent-restores-bindings-on-exit
  (testing "bindings revert after normal exit + after throw"
    (setup-fixture!)
    (is (nil? session/*conn*))
    (is (nil? session/*current-agent-id*))
    (session/with-agent "wa-A1" :body-ran)
    (is (nil? session/*conn*) "*conn* must revert after normal exit")
    (is (nil? session/*current-agent-id*))
    (is (thrown? Exception
                 (session/with-agent "wa-A1" (throw (ex-info "boom" {})))))
    (is (nil? session/*conn*) "*conn* must revert after throw")
    (is (nil? session/*current-agent-id*))))

(deftest unknown-agent-id-throws-clearly
  (testing "with-agent on an unknown agent-id throws ex-info with a clear message"
    (setup-fixture!)
    (let [thrown (try (session/with-agent "ghost-agent" :body)
                      ::no-throw
                      (catch clojure.lang.ExceptionInfo e
                        {:msg (.getMessage e) :data (ex-data e)}))]
      (is (not= ::no-throw thrown) "must throw")
      (is (re-find #"Unknown agent-id" (:msg thrown)))
      (is (= "ghost-agent" (-> thrown :data :agent-id))))))

(deftest with-agent-fails-when-session-removed
  (testing "if the agent's session was removed, throw a clear error"
    (setup-fixture!)
    (server.session/remove-db!
     {:seon.server.registry/db-name :test.with-agent/sA})
    ;; remove-db! also drops agent mappings, so this is just 'unknown agent'
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown agent-id"
                          (session/with-agent "wa-A1" :body)))))
