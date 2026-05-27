(ns seon.session-test
  "Integration test for the agent-launch demo target.

   Exercises the full Phase 3 lifecycle: spawn an agent JVM, eval forms in
   it, swap! its `*ctx*`, persist the ctx blob to `:seon.session`, and stop.

   Spawns a real JVM on a port outside the pool's default range (7900-7902)
   and inside its agent range (7980-7999). Slow but deterministic — the only
   way to verify the cross-JVM nREPL plumbing actually works.

   Marked `^:integration`: requires the live system (`:seon.db/flow` with
   `:seon.session` registered, `seon.flow/pool` for agent spawning, free
   ports in 7980-7999). Excluded from `bin/test --unit`; run via
   `bin/test --all` or `clj -M:test -m kaocha.runner integration`."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.flow.pool :as pool]
            [seon.session :as session]))

(defn- safe-stop! [session-id]
  (try (session/stop! {::session/session-id session-id})
       (catch Exception _)))

(defn- poll-row
  "Poll `:seon.session` for `session-id` until `pred` matches or `timeout-ms`
   elapses. Returns the matching row, or the last-seen row if it timed out."
  [session-id pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [row (db/pull-by-name :seon.session '[*]
                                [:seon.session/agent session-id])]
      (cond
        (pred row) row
        (>= (System/currentTimeMillis) deadline) row
        :else (do (Thread/sleep 50)
                  (recur (db/pull-by-name :seon.session '[*]
                                          [:seon.session/agent session-id])))))))

(deftest ^:integration launch-eval-checkpoint-stop-roundtrip
  (testing "end-to-end agent JVM lifecycle"
    (let [launched (atom nil)]
      (try
        ;; 1. Launch -- spawns a JVM, writes a :running row.
        (let [{::session/keys [session-id nrepl-port pid] :as res}
              (session/launch! {::session/namespace 'seon.session-test.agent})]
          (reset! launched session-id)
          (is (string? session-id) "launch! returns a session id")
          (is (re-matches #"[A-Za-z0-9]{4,6}" session-id)
              "session id is a base62 token")
          (is (<= 7980 nrepl-port 7999)
              "port is allocated from the launch range")
          (is (pos-int? pid))

          ;; 2. Eval forms in the agent JVM via the pool's nREPL primitive
          ;;    (this is what mcp__seon__eval does under the hood).
          (is (= "{:scratch 42}"
                 (pool/nrepl-eval! nrepl-port "(swap! *ctx* assoc :scratch 42)"))
              "agent can swap! its *ctx* atom")
          (pool/nrepl-eval! nrepl-port
                            "(defn double-scratch [] (* 2 (:scratch @*ctx*)))")
          (is (= "84" (pool/nrepl-eval! nrepl-port "(double-scratch)"))
              "agent can call functions defined in its own namespace")

          ;; 3. Row is visible from the orchestrator before checkpoint.
          (let [row (db/pull-by-name :seon.session '[*]
                                     [:seon.session/agent session-id])]
            (is (= :running (:seon.session/status row)))
            (is (= 'seon.session-test.agent (:seon.session/namespace row)))
            (is (nil? (:seon.session/ctx row))
                "ctx is absent until checkpoint! runs"))

          ;; 4. Checkpoint -- pulls @*ctx* via nREPL, persists pr-str blob.
          (let [{::session/keys [checkpointed-at]}
                (session/checkpoint! {::session/session-id session-id})]
            (is (inst? checkpointed-at)))
          (let [row (db/pull-by-name :seon.session '[*]
                                     [:seon.session/agent session-id])]
            (is (= "{:scratch 42}" (:seon.session/ctx row))
                "ctx is persisted as the agent's pr-str of @*ctx*"))

          ;; 5. Stop -- terminates JVM, marks :stopped, sets :stopped-at.
          (let [{::session/keys [status]}
                (session/stop! {::session/session-id session-id})]
            (is (= :stopped status)))
          (reset! launched nil)
          (let [row (db/pull-by-name :seon.session '[*]
                                     [:seon.session/agent session-id])]
            (is (= :stopped (:seon.session/status row)))
            (is (inst? (:seon.session/stopped-at row)))))
        (finally
          (when-let [sid @launched]
            (safe-stop! sid)))))))

(deftest ^:integration agent-jvm-relays-seon-db-to-orchestrator
  (testing "agent JVM -> relay -> orchestrator -> datahike round-trip"
    (let [launched (atom nil)
          test-id "rly001"]
      (try
        (let [{::session/keys [session-id nrepl-port]}
              (session/launch! {::session/namespace 'seon.session-test.relay})]
          (reset! launched session-id)

          ;; 1. Agent transacts a row into :seon.orchestrator via the relay.
          (let [tx-result-str
                (pool/nrepl-eval!
                 nrepl-port
                 (str "(seon.db/transact! :seon.orchestrator "
                      "[{:seon.orchestrator.session/id \"" test-id "\""
                      " :seon.orchestrator.session/namespace \"relay-test\""
                      " :seon.orchestrator.session/status :running}])"))]
            (is (re-find #":tx-data" (str tx-result-str))
                "agent-side transact! returned a tx report (not an error)"))

          ;; 2. Orchestrator-side query sees the row the agent wrote.
          (let [q '[:find ?ns ?status
                    :in $ ?id
                    :where
                    [?e :seon.orchestrator.session/id ?id]
                    [?e :seon.orchestrator.session/namespace ?ns]
                    [?e :seon.orchestrator.session/status ?status]]
                rows (db/query :seon.orchestrator q test-id)]
            (is (= #{["relay-test" :running]} rows)
                "orchestrator sees the agent's write through datahike"))

          ;; 3. Agent reads the same row back via pull-by-name (also through
          ;; the relay), proving the read path round-trips.
          (let [pulled-str
                (pool/nrepl-eval!
                 nrepl-port
                 (str "(seon.db/pull-by-name :seon.orchestrator '[*] "
                      "[:seon.orchestrator.session/id \"" test-id "\"])"))]
            (is (re-find #"relay-test" pulled-str))
            (is (re-find #":running" pulled-str)
                "agent's read sees its own earlier write")))
        (finally
          (when-let [sid @launched]
            (safe-stop! sid))
          ;; Clean the row regardless of outcome.
          (try
            (db/transact! :seon.orchestrator
                          [[:db/retractEntity
                            [:seon.orchestrator.session/id test-id]]])
            (catch Exception _)))))))

(deftest ^:integration auto-checkpoint-on-ctx-change
  (testing "launch! schedules a watcher that auto-checkpoints on *ctx* change"
    (let [launched (atom nil)]
      (try
        (let [{::session/keys [session-id nrepl-port]}
              (session/launch! {::session/namespace 'seon.session-test.autockpt
                                ::session/checkpoint-interval-ms 200})]
          (reset! launched session-id)
          ;; Sanity: pre-swap, no ctx is persisted yet.
          (let [row (db/pull-by-name :seon.session '[*]
                                     [:seon.session/agent session-id])]
            (is (nil? (:seon.session/ctx row))))
          ;; Swap *ctx* in the agent JVM. The :seon.session/version watcher
          ;; should bump *ctx-version*; within ~200ms the scheduler should
          ;; observe the bump and persist the blob.
          (pool/nrepl-eval! nrepl-port "(swap! *ctx* assoc :hello :world)")
          (let [row (poll-row session-id
                              #(= "{:hello :world}" (:seon.session/ctx %))
                              2000)]
            (is (= "{:hello :world}" (:seon.session/ctx row))
                "auto-checkpoint persisted the ctx within 2s of the swap!"))
          ;; A second swap! triggers a second checkpoint with new contents.
          (pool/nrepl-eval! nrepl-port "(swap! *ctx* assoc :phase 3)")
          (let [row (poll-row session-id
                              #(re-find #":phase 3" (or (:seon.session/ctx %) ""))
                              2000)]
            (is (re-find #":hello :world" (:seon.session/ctx row)))
            (is (re-find #":phase 3" (:seon.session/ctx row))
                "second swap! triggered another auto-checkpoint"))
          ;; stop! cancels the scheduled task and clears the live-sessions slot.
          (session/stop! {::session/session-id session-id})
          (reset! launched nil)
          (let [live @@(resolve 'seon.session/live-sessions)]
            (is (not (contains? live session-id))
                "stop! removed the live-sessions entry (and cancelled future)")))
        (finally
          (when-let [sid @launched]
            (safe-stop! sid)))))))
