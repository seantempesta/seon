(ns seon.agent-loop-test
  "Loop-economy stop policy (P21/#35 + #22): a reply landed during this
   wake AND no newer inbound message arrived → `run-agentic-loop!`
   STOPS instead of churning verification turns to the 20-turn cap
   (3/5 paid P8 runs answered by turn ~3 then burned ~15 turns). Pins:

     - `replied-since-inbound?` — the pure derivation: outbound (from =
       me, ∃ recipient ≠ me) strictly after the latest live inbound
       (to ∋ me, from ≠ me, hops < cap). Assistant self-messages
       (from = to = me) never count; a NEW inbound after the reply
       re-opens the window.
     - the loop halts with `:seon.agent/halt :replied` after the turn
       whose eval landed a `reply!` — even when the LLM would keep
       emitting forms every turn (the churn shape).
     - zero-forms termination (#22): a prose-only LLM response ends the
       wake cleanly in one turn — no spin to the cap.

   Tests open a FRESH `:memory` conn (via `seon.client/open-agent-conn!`,
   the same boot helper the pod uses) — nothing here touches the live
   agents.

   Run interactively via MCP eval:
     (require 'seon.agent-loop-test :reload)
     (cljs.test/run-tests 'seon.agent-loop-test)"
  (:require
    [cljs.test :refer [deftest is testing async]]
    [seon.agent :as agent]
    [seon.client :as client]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.repl :as repl]))

(defn- with-conn
  "Open a fresh schema-loaded conn, `set!` it as the ROOT `db/*conn*`
   (a plain `binding` does NOT survive Promise/await boundaries in CLJS
   — the same reason the pod boot uses set!), run `body` (0-arg, may
   return a Promise), restore the prior root after. Returns a Promise."
  [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (let [prev db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body))
                     (.finally (fn [] (set! db/*conn* prev)))))))))

(def ^:private agent-id "AGTlooptest001")        ; 14 chars (:seon.db/id)

(defn- t+ [base ms] (js/Date. (+ (.getTime base) ms)))

;; ---------------------------------------------------------------------------
;; The derivation itself — seeded messages with controlled timestamps.
;; ---------------------------------------------------------------------------

(deftest replied-since-inbound?-derivation
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [t0 (js/Date.)]
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.user/id "user"}
                         {:seon.agent/id agent-id
                          :seon.agent/state :idle
                          :seon.agent/sessions
                          [{:seon.agent.session/id "SESlooptest001"
                            :seon.agent.session/at t0}]}]}))
              (testing "no messages at all → false"
                (is (false? (agent/replied-since-inbound?
                              {:seon.agent/id agent-id}))))
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGlooptest001"
                          :seon.agent.message/from {:seon.user/id "user"}
                          :seon.agent.message/to [{:seon.agent/id agent-id}]
                          :seon.agent.message/content "what is 1+1?"
                          :seon.agent.message/at (t+ t0 10)
                          :seon.agent.message/hops 0}]}))
              (testing "inbound only, no reply yet → false"
                (is (false? (agent/replied-since-inbound?
                              {:seon.agent/id agent-id}))))
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGlooptest002"
                          :seon.agent.message/from {:seon.agent/id agent-id}
                          :seon.agent.message/to [{:seon.agent/id agent-id}]
                          :seon.agent.message/content ";; thinking out loud"
                          :seon.agent.message/at (t+ t0 20)
                          :seon.agent.message/hops 0}]}))
              (testing "assistant SELF-message (from = to = me) never counts"
                (is (false? (agent/replied-since-inbound?
                              {:seon.agent/id agent-id}))))
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGlooptest003"
                          :seon.agent.message/from {:seon.agent/id agent-id}
                          :seon.agent.message/to [{:seon.user/id "user"}]
                          :seon.agent.message/content "2"
                          :seon.agent.message/at (t+ t0 30)
                          :seon.agent.message/hops 1}]}))
              (testing "outbound reply after the inbound → true (stop)"
                (is (true? (agent/replied-since-inbound?
                             {:seon.agent/id agent-id}))))
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGlooptest004"
                          :seon.agent.message/from {:seon.user/id "user"}
                          :seon.agent.message/to [{:seon.agent/id agent-id}]
                          :seon.agent.message/content "and 2+2?"
                          :seon.agent.message/at (t+ t0 40)
                          :seon.agent.message/hops 0}]}))
              (testing "NEW inbound after the reply re-opens the window → false"
                (is (false? (agent/replied-since-inbound?
                              {:seon.agent/id agent-id}))))
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGlooptest005"
                          :seon.agent.message/from {:seon.agent/id agent-id}
                          :seon.agent.message/to [{:seon.user/id "user"}]
                          :seon.agent.message/content "4"
                          :seon.agent.message/at (t+ t0 50)
                          :seon.agent.message/hops 1}]}))
              (testing "second reply after the second inbound → true again"
                (is (true? (agent/replied-since-inbound?
                             {:seon.agent/id agent-id})))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; ensure-session! — session scope is the POD PROCESS, not the DB.
;; A session present in the store but opened by a previous pod run
;; (simulated by transacting it directly — it's never in
;; `!boot-sessions`) must NOT be reused; within one run it must.
;; ---------------------------------------------------------------------------

(deftest ensure-session!-fresh-session-per-pod-boot
  (async done
    (-> (with-conn
          (fn ^:async run []
            ;; Resumed agent: entity + a prior-run session already in
            ;; the DB, but THIS process never opened that session.
            (await (db/transact!
                     {:seon.db/tx-data
                      [{:seon.agent/id agent-id
                        :seon.agent/state :idle
                        :seon.agent/sessions
                        [{:seon.agent.session/id "SESpriorboot01"
                          :seon.agent.session/at (js/Date.)}]}]}))
            (let [s1 (await (agent/ensure-session! agent-id))]
              (testing "prior-run session is NOT reused — boot opens fresh"
                (is (some? (:seon.agent.session/id s1)))
                (is (not= "SESpriorboot01" (:seon.agent.session/id s1))))
              (testing "agent entity persists — BOTH sessions on it"
                (is (= 2 (count (:seon.agent/sessions
                                  (db/entity {:seon.db/ref
                                              [:seon.agent/id agent-id]}))))))
              (testing "idempotent within the same pod run — reuses"
                (let [s2 (await (agent/ensure-session! agent-id))]
                  (is (= (:seon.agent.session/id s1)
                         (:seon.agent.session/id s2))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; The loop through the REAL pipeline — bootstrap compile-state, real
;; eval-batch, real reply!. The llm-fn emits a reply! form EVERY turn
;; (the churn shape: under the pre-#35 policy this ran to the 20-turn
;; cap because every turn had forms).
;; ---------------------------------------------------------------------------

(defn- scripted-llm
  "ctx-string -> Promise<{:text text}> — replays `text` on every call."
  [text]
  (fn [_ctx] (js/Promise.resolve {:text text})))

(defn- session-turn-count []
  (count (:seon.agent.session/turns
           (agent/current-session agent-id))))

(defn ^:async ^:private boot-loop-agent!
  "Fresh-conn world for a loop drive: user entity + agent entity +
   initialized home ns. Returns the user message id (the wake anchor)."
  [compile-state question]
  (await (db/transact! {:seon.db/tx-data [{:seon.user/id "user"}]}))
  (await (db/with-agent agent-id
           (fn ^:async boot []
             (await (seval/setup-agent-ns! compile-state
                                           (agent/home-ns agent-id)
                                           agent-id))
             (await (agent/create! {:seon.agent/id agent-id})))))
  (let [env (await (agent/message!
                     {:seon.agent.message/content question
                      :seon.agent.message/from    agent/user-ref
                      :seon.agent.message/to      [[:seon.agent/id agent-id]]}))]
    (is (true? (:seon.agent.message/ok? env)) "user message landed")
    ;; one tick so the reply's :at is strictly after the inbound's
    ;; (the derivation compares with strict >; same-ms = continue).
    (await (js/Promise. (fn [resolve] (js/setTimeout resolve 5))))
    (:seon.agent.message/id env)))

(deftest reply-halts-the-loop-before-the-cap
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [compile-state (await (repl/ensure-bootstrap!))
                  mid (await (boot-loop-agent! compile-state "ping"))
                  result
                  (await
                    (db/with-agent agent-id
                      (fn []
                        (agent/run-agentic-loop!
                          {:seon.agent/id            agent-id
                           :seon.agent/llm-fn
                           (scripted-llm
                             (str ";; answer, then (pre-#35) churn forever\n"
                                  "(seon.agent/reply! "
                                  "{:seon.agent.message/content \"pong\"})\n"))
                           :seon.agent/compile-state compile-state
                           :seon.agent.turn/woken-by
                           [:seon.agent.message/id mid]}))))]
              (is (= :replied (:seon.agent/halt result))
                  "loop halts with :replied, not :cap-hit")
              (is (= 1 (session-turn-count))
                  "ONE turn — answered, stopped; no churn to the cap")
              (is (= :idle (:seon.agent/state
                             (db/entity {:seon.db/ref
                                         [:seon.agent/id agent-id]})))
                  "agent ends :idle — ready for the next wake")
              (is (true? (agent/replied-since-inbound?
                           {:seon.agent/id agent-id}))
                  "the reply row is live-observable in the message log"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest zero-forms-terminates-cleanly
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [compile-state (await (repl/ensure-bootstrap!))
                  mid (await (boot-loop-agent! compile-state "ping"))
                  result
                  (await
                    (db/with-agent agent-id
                      (fn []
                        (agent/run-agentic-loop!
                          {:seon.agent/id            agent-id
                           :seon.agent/llm-fn
                           (scripted-llm ";; prose only — nothing to eval\n")
                           :seon.agent/compile-state compile-state
                           :seon.agent.turn/woken-by
                           [:seon.agent.message/id mid]}))))]
              (is (nil? (:seon.agent/halt result))
                  "zero-forms stop, not a halt marker")
              (is (= 1 (session-turn-count))
                  "ONE turn — prose-only ends the wake, no spin (#22)")
              (is (= :idle (:seon.agent/state
                             (db/entity {:seon.db/ref
                                         [:seon.agent/id agent-id]})))
                  "agent ends :idle"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
