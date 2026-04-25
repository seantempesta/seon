(ns seon.session-test
  "Integration test for the agent-launch demo target.

   Exercises the full Phase 3 lifecycle: spawn an agent JVM, eval forms in
   it, swap! its `*ctx*`, persist the ctx blob to `:seon.session`, and stop.

   Spawns a real JVM on a port outside the pool's default range (7900-7902)
   and inside its agent range (7980-7999). Slow but deterministic — the only
   way to verify the cross-JVM nREPL plumbing actually works."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.flow.pool :as pool]
            [seon.session :as session]))

(defn- safe-stop! [session-id]
  (try (session/stop! {::session/session-id session-id})
       (catch Exception _)))

(deftest launch-eval-checkpoint-stop-roundtrip
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
