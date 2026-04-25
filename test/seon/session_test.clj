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

(deftest auto-checkpoint-on-ctx-change
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
