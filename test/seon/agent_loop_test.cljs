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
     - the EMPTY-TURN guard (downstream ask 20): a turn with zero
       evals while the agent has NOT replied since the inbound (the
       deepseek thinking-mode shape — all tokens in the reasoning
       field, visible content empty) re-prompts with a core nudge
       instead of ending the wake silently; bounded at
       `agent/max-empty-reprompts` consecutive re-prompts, then ends
       with a chat-visible system line (turn :error + self-message —
       the ask-6 shape). A replied agent's wake still ends normally;
       the turns-cap still bounds re-prompts.

   Tests open a FRESH `:memory` conn (via `seon.client/open-agent-conn!`,
   the same boot helper the pod uses) — nothing here touches the live
   agents.

   Run interactively via MCP eval:
     (require 'seon.agent-loop-test :reload)
     (cljs.test/run-tests 'seon.agent-loop-test)"
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing async]]
    [seon.agent :as agent]
    [seon.client :as client]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.render.chat :as chat]
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
;; #43 — a :core-origin message (a substrate nudge, e.g. tile recovery,
;; sent FROM the user-ref) must NEVER move the halt baseline. Without the
;; origin exclusion a broken-tile :core message arriving AFTER the reply
;; would push the inbound side past the reply and re-open the window,
;; re-arming the loop. A real :human follow-up still re-opens it.
;; ---------------------------------------------------------------------------

(deftest replied-since-inbound?-ignores-core-origin
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
                          [{:seon.agent.session/id "SEScoretest001"
                            :seon.agent.session/at t0}]}
                         ;; human asks
                         {:seon.agent.message/id "MSGcore0000001"
                          :seon.agent.message/from {:seon.user/id "user"}
                          :seon.agent.message/to [{:seon.agent/id agent-id}]
                          :seon.agent.message/content "what is 1+1?"
                          :seon.agent.message/at (t+ t0 10)
                          :seon.agent.message/hops 0
                          :seon.agent.message/origin :human}
                         ;; agent replies
                         {:seon.agent.message/id "MSGcore0000002"
                          :seon.agent.message/from {:seon.agent/id agent-id}
                          :seon.agent.message/to [{:seon.user/id "user"}]
                          :seon.agent.message/content "2"
                          :seon.agent.message/at (t+ t0 20)
                          :seon.agent.message/hops 1
                          :seon.agent.message/origin :agent}]}))
              (testing "after the reply the loop halts → true"
                (is (true? (agent/replied-since-inbound?
                             {:seon.agent/id agent-id}))))
              ;; a :core nudge lands AFTER the reply (sent from the user-ref)
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGcore0000003"
                          :seon.agent.message/from {:seon.user/id "user"}
                          :seon.agent.message/to [{:seon.agent/id agent-id}]
                          :seon.agent.message/content "your tile broke (substrate)"
                          :seon.agent.message/at (t+ t0 30)
                          :seon.agent.message/hops 0
                          :seon.agent.message/origin :core}]}))
              (testing ":core message does NOT move the baseline → still true"
                (is (true? (agent/replied-since-inbound?
                             {:seon.agent/id agent-id}))))
              ;; a real :human follow-up DOES re-open the window
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGcore0000004"
                          :seon.agent.message/from {:seon.user/id "user"}
                          :seon.agent.message/to [{:seon.agent/id agent-id}]
                          :seon.agent.message/content "and 2+2?"
                          :seon.agent.message/at (t+ t0 40)
                          :seon.agent.message/hops 0
                          :seon.agent.message/origin :human}]}))
              (testing ":human follow-up re-opens the window → false"
                (is (false? (agent/replied-since-inbound?
                              {:seon.agent/id agent-id})))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; #49 drain-on-exit derivation — `undrained-inbound-since?`. Closes the
;; tail window: a /chat that landed after the final turn's close-tx
;; flipped :idle but while the kick latch still held id is DROPPED by the
;; handler; the loop must re-query the log at exit and recur (latch held
;; across the drain) while a STRICTLY-newer-than-baseline UNANSWERED
;; inbound exists. The baseline (latest inbound AT LOOP ENTRY) is what
;; keeps the SAME message the wake processed from re-draining forever
;; (the :cap-hit infinite-loop hazard).
;; ---------------------------------------------------------------------------

(deftest undrained-inbound-since?-derivation
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
                          [{:seon.agent.session/id "SESdrain00001"
                            :seon.agent.session/at (t+ t0 -1000)}]}
                         ;; inbound #1 at t0 — the wake anchor / baseline
                         {:seon.agent.message/id "MSGdrain000001"
                          :seon.agent.message/from {:seon.user/id "user"}
                          :seon.agent.message/to [{:seon.agent/id agent-id}]
                          :seon.agent.message/content "q1"
                          :seon.agent.message/at t0
                          :seon.agent.message/hops 0
                          :seon.agent.message/origin :human}]}))
              ;; baseline = latest inbound at loop ENTRY = inbound#1's :at
              (testing "the same baseline message is NOT undrained (no :cap-hit spin)"
                (is (false? (agent/undrained-inbound-since?
                              {:seon.agent/id agent-id
                               :seon.agent/baseline-at t0}))))
              ;; agent replies to inbound#1
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGdrain000002"
                          :seon.agent.message/from {:seon.agent/id agent-id}
                          :seon.agent.message/to [{:seon.user/id "user"}]
                          :seon.agent.message/content "a1"
                          :seon.agent.message/at (t+ t0 10)
                          :seon.agent.message/hops 1}]}))
              (testing "replied, nothing new → not undrained"
                (is (false? (agent/undrained-inbound-since?
                              {:seon.agent/id agent-id
                               :seon.agent/baseline-at t0}))))
              ;; THE TAIL WINDOW: a NEW inbound lands after the reply,
              ;; strictly after the baseline, unanswered.
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGdrain000003"
                          :seon.agent.message/from {:seon.user/id "user"}
                          :seon.agent.message/to [{:seon.agent/id agent-id}]
                          :seon.agent.message/content "q2"
                          :seon.agent.message/at (t+ t0 20)
                          :seon.agent.message/hops 0
                          :seon.agent.message/origin :human}]}))
              (testing "NEW unanswered inbound after baseline → UNDRAINED (recur)"
                (is (true? (agent/undrained-inbound-since?
                             {:seon.agent/id agent-id
                              :seon.agent/baseline-at t0}))))
              ;; agent replies to the new inbound → drained
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGdrain000004"
                          :seon.agent.message/from {:seon.agent/id agent-id}
                          :seon.agent.message/to [{:seon.user/id "user"}]
                          :seon.agent.message/content "a2"
                          :seon.agent.message/at (t+ t0 30)
                          :seon.agent.message/hops 1}]}))
              (testing "new inbound now answered → not undrained"
                (is (false? (agent/undrained-inbound-since?
                              {:seon.agent/id agent-id
                               :seon.agent/baseline-at t0}))))
              ;; a :core substrate nudge after baseline must NOT count (#43)
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGdrain000005"
                          :seon.agent.message/from {:seon.user/id "user"}
                          :seon.agent.message/to [{:seon.agent/id agent-id}]
                          :seon.agent.message/content "tile broke (substrate)"
                          :seon.agent.message/at (t+ t0 40)
                          :seon.agent.message/hops 0
                          :seon.agent.message/origin :core}]}))
              (testing ":core nudge never re-drains (origin gating preserved)"
                (is (false? (agent/undrained-inbound-since?
                              {:seon.agent/id agent-id
                               :seon.agent/baseline-at t0}))))
              ;; nil baseline (a manual drive opened with no inbound):
              ;; ANY live unanswered inbound is undrained. Here inbound#3
              ;; was answered, so still false; assert the nil-baseline path
              ;; works by checking the unanswered-inbound case directly.
              (testing "nil baseline + unanswered inbound → undrained"
                (await (db/transact!
                         {:seon.db/tx-data
                          [{:seon.agent.message/id "MSGdrain000006"
                            :seon.agent.message/from {:seon.user/id "user"}
                            :seon.agent.message/to [{:seon.agent/id agent-id}]
                            :seon.agent.message/content "q3"
                            :seon.agent.message/at (t+ t0 50)
                            :seon.agent.message/hops 0
                            :seon.agent.message/origin :human}]}))
                (is (true? (agent/undrained-inbound-since?
                             {:seon.agent/id agent-id
                              :seon.agent/baseline-at nil})))))))
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

;; ---------------------------------------------------------------------------
;; The EMPTY-TURN guard (downstream ask 20) — a completion with EMPTY
;; visible content (the deepseek thinking-mode shape: every token in
;; the reasoning field) yields 0 forms; pre-fix the loop closed
;; `done [0 "ok"]` and the wake ended with NO reply — the agent looked
;; dead until a manual "continue". Now: nudge + re-prompt (bounded),
;; then a chat-visible system line.
;; ---------------------------------------------------------------------------

(defn- nudge-count
  "How many core empty-completion nudges are in the message log."
  []
  (count (db/query
           {:seon.db/query '[:find ?m
                             :in $ ?content
                             :where [?m :seon.agent.message/content ?content]]
            :seon.db/args  [agent/empty-completion-nudge]})))

(defn- scripted-seq-llm
  "ctx-string -> Promise<{:text t}> — replays `texts` in order, then
   repeats the last one."
  [texts]
  (let [!n (atom -1)]
    (fn [_ctx]
      (let [i (swap! !n inc)]
        (js/Promise.resolve
          {:text (nth texts (min i (dec (count texts))))})))))

(def ^:private reply-form
  "(seon.agent/reply! {:seon.agent.message/content \"pong\"})\n")

(defn ^:async ^:private drive-loop! [compile-state mid llm-fn]
  (await
    (db/with-agent agent-id
      (fn []
        (agent/run-agentic-loop!
          {:seon.agent/id            agent-id
           :seon.agent/llm-fn        llm-fn
           :seon.agent/compile-state compile-state
           :seon.agent.turn/woken-by [:seon.agent.message/id mid]})))))

(deftest empty-completions-reprompt-then-system-line
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [compile-state (await (repl/ensure-bootstrap!))
                  mid    (await (boot-loop-agent! compile-state "ping"))
                  result (await (drive-loop! compile-state mid
                                             (scripted-llm "")))]
              (is (= :no-visible-output (:seon.agent/halt result))
                  "wake ends with the empty-turn halt marker, not silence")
              (is (= (inc agent/max-empty-reprompts) (session-turn-count))
                  "1 + max-empty-reprompts turns — re-prompts consumed turns")
              (is (= agent/max-empty-reprompts (nudge-count))
                  "one core nudge per re-prompt landed in the message log")
              (is (= :error (:seon.agent.turn/status
                              (db/entity {:seon.db/ref
                                          [:seon.agent.turn/id
                                           (:seon.agent.turn/id result)]})))
                  "final turn flipped to :error (the ask-6 chat-visible shape)")
              (let [{::chat/keys [messages]}
                    (chat/conversation {:seon.agent/id agent-id})]
                (is (some #(and (= ::chat/system (::chat/kind %))
                                (re-find #"no visible output" (::chat/content %)))
                          messages)
                    "the human's chat shows a system line — agent never looks dead"))
              (is (= :idle (:seon.agent/state
                             (db/entity {:seon.db/ref
                                         [:seon.agent/id agent-id]})))
                  "agent ends :idle — the next message resumes it"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest empty-completion-recovers-on-reprompt
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [compile-state (await (repl/ensure-bootstrap!))
                  mid    (await (boot-loop-agent! compile-state "ping"))
                  result (await (drive-loop! compile-state mid
                                             (scripted-seq-llm ["" reply-form])))]
              (is (= :replied (:seon.agent/halt result))
                  "second completion replied — wake completes normally")
              (is (= 2 (session-turn-count))
                  "empty turn + recovered turn")
              (is (= 1 (nudge-count))
                  "exactly ONE nudge — the streak ended on recovery"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest replied-agent-empty-completion-ends-normally
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [compile-state (await (repl/ensure-bootstrap!))
                  mid (await (boot-loop-agent! compile-state "ping"))]
              ;; The agent already replied to the inbound (e.g. via a
              ;; prior turn of this wake) — transacted directly with an
              ;; :at strictly after the inbound's.
              (let [seeded
                    (await (db/transact!
                             {:seon.db/tx-data
                              [{:seon.agent.message/id      "MSGloopreply01"
                                :seon.agent.message/from    {:seon.agent/id agent-id}
                                :seon.agent.message/to      [{:seon.user/id "user"}]
                                :seon.agent.message/content "already answered"
                                :seon.agent.message/at      (js/Date.)
                                :seon.agent.message/hops    1}]}))]
                (is (not (false? (:seon.db/ok? seeded)))
                    "seeded reply transact landed"))
              (let [result (await (drive-loop! compile-state mid
                                               (scripted-llm "")))]
                (is (= :replied (:seon.agent/halt result))
                    "replied agent's wake ends normally on an empty completion")
                (is (= 1 (session-turn-count)) "ONE turn, no spin")
                (is (zero? (nudge-count))
                    "no spurious re-prompt for an agent that already replied")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest turns-cap-bounds-reprompts
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [compile-state (await (repl/ensure-bootstrap!))
                  mid (await (boot-loop-agent! compile-state "ping"))]
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent/id agent-id
                          :seon.agent/turns-cap 1}]}))
              (let [result (await (drive-loop! compile-state mid
                                               (scripted-llm "")))]
                (is (= :cap-hit (:seon.agent/halt result))
                    "cap checked BEFORE the empty-turn guard — re-prompts
                     can never push past the turns-cap")
                (is (= 1 (session-turn-count)) "cap 1 → one turn")
                (is (zero? (nudge-count))
                    "no nudge once the cap is reached")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; #49 drain-on-exit through the REAL loop. The tail-window race: a /chat
;; lands AFTER the final turn's close-tx flipped :idle but while the kick
;; latch still held id — the handler drops the wake. The loop must catch
;; that dropped message at its terminal halt and recur (latch held across
;; the drain) so the message gets a turn, then halt normally once
;; answered. We simulate the tail-window arrival by transacting a NEW
;; inbound during the wake (a side-effect of the give-up turn's llm call —
;; the SAME relative ordering the dropped /chat lands in), then asserting
;; the loop did NOT halt :no-visible-output but ran a make-good turn and
;; ended :replied with the new message answered.
;; ---------------------------------------------------------------------------

(defn- inbound-content-count
  "How many INBOUND messages (from the user) carry `content`."
  [content]
  (count (db/query
           {:seon.db/query '[:find ?m
                             :in $ ?content
                             :where
                             [?m :seon.agent.message/content ?content]
                             [?m :seon.agent.message/from ?f]
                             [?f :seon.user/id _]]
            :seon.db/args  [content]})))

(defn- drain-injecting-llm
  "ctx -> Promise<{:text …}> (the same non-async, Promise-returning shape
   as `scripted-llm` — the llm-fn contract). Empty completions drive the
   loop to its empty-turn give-up; the FIRST call also transacts a NEW
   inbound (`q2-tail-window`, unanswered, :at strictly after the wake
   anchor) — simulating a /chat that races the tail window. Call
   sequence: calls 0..max-reprompts → \"\"; the final call → a reply that
   answers q2. The transact resolves before the {:text} so the new
   inbound is live by the time the give-up's drain re-queries the log."
  [_compile-state]
  (let [!n      (atom -1)
        n-empty (inc agent/max-empty-reprompts)]   ; 1 + reprompts → give-up
    (fn llm [_ctx]
      (let [i (swap! !n inc)
            resp {:text (if (< i n-empty) "" reply-form)}]
        (if (zero? i)
          ;; tail-window arrival on the first turn, resolved before resp.
          (-> (db/transact!
                {:seon.db/tx-data
                 [{:seon.agent.message/id      "MSGdraininj001"
                   :seon.agent.message/from    {:seon.user/id "user"}
                   :seon.agent.message/to      [{:seon.agent/id agent-id}]
                   :seon.agent.message/content "q2-tail-window"
                   :seon.agent.message/at      (js/Date.)
                   :seon.agent.message/hops    0
                   :seon.agent.message/origin  :human}]})
              (.then (fn [_] resp)))
          (js/Promise.resolve resp))))))

(deftest drain-on-exit-reruns-for-a-tail-window-message
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [compile-state (await (repl/ensure-bootstrap!))
                  mid (await (boot-loop-agent! compile-state "ping"))
                  result (await (drive-loop! compile-state mid
                                             (drain-injecting-llm compile-state)))]
              (is (= 1 (inbound-content-count "q2-tail-window"))
                  "the tail-window inbound landed exactly once")
              (is (not= :no-visible-output (:seon.agent/halt result))
                  "the loop did NOT strand the new message at the give-up —
                   drain-on-exit recurred instead of halting silently")
              (is (= :replied (:seon.agent/halt result))
                  "after draining, the make-good turn replied → :replied")
              (is (true? (agent/replied-since-inbound?
                           {:seon.agent/id agent-id}))
                  "the tail-window message is answered — log shows the reply")
              (is (= :idle (:seon.agent/state
                             (db/entity {:seon.db/ref
                                         [:seon.agent/id agent-id]})))
                  "agent ends :idle — ready for the next wake"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; #49 drain-on-exit MUST TERMINATE. The drain baseline ADVANCES on every
;; drain to the message just committed to a turn — so a halt over the SAME
;; message the wake already processed never re-drains. Without the advance,
;; a NEVER-answered new inbound would re-drain a give-up (or :cap-hit) halt
;; forever (verified runaway hazard). Here the agent injects a tail-window
;; message but NEVER replies: the loop must drain it ONCE (one make-good
;; pass), then halt :no-visible-output — finite turns, not a spin.
;; ---------------------------------------------------------------------------

(defn- never-reply-injecting-llm
  "ctx -> Promise<{:text \"\"}> — ALWAYS empty (never replies); the first
   call also injects a NEW unanswered inbound. Drives the give-up→drain
   path with a message that is never answered, to prove termination."
  []
  (let [!n (atom -1)]
    (fn llm [_ctx]
      (let [i (swap! !n inc)]
        (if (zero? i)
          (-> (db/transact!
                {:seon.db/tx-data
                 [{:seon.agent.message/id      "MSGdrainhaz001"
                   :seon.agent.message/from    {:seon.user/id "user"}
                   :seon.agent.message/to      [{:seon.agent/id agent-id}]
                   :seon.agent.message/content "q-hazard"
                   :seon.agent.message/at      (js/Date.)
                   :seon.agent.message/hops    0
                   :seon.agent.message/origin  :human}]})
              (.then (fn [_] {:text ""})))
          (js/Promise.resolve {:text ""}))))))

(deftest drain-on-exit-terminates-on-a-never-answered-message
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [compile-state (await (repl/ensure-bootstrap!))
                  mid (await (boot-loop-agent! compile-state "ping"))
                  result (await (drive-loop! compile-state mid
                                             (never-reply-injecting-llm)))]
              (is (= 1 (inbound-content-count "q-hazard"))
                  "the never-answered inbound landed once")
              (is (= :no-visible-output (:seon.agent/halt result))
                  "the loop TERMINATED (advancing baseline stops the
                   re-drain) — it did not spin forever")
              ;; 1+max-reprompts for the anchor → drain → 1+max-reprompts
              ;; for q-hazard → give-up (baseline now = q-hazard, no
              ;; re-drain). Bounded: exactly two give-up passes.
              (is (= (* 2 (inc agent/max-empty-reprompts))
                     (session-turn-count))
                  "exactly two give-up passes (anchor + one drained message)")
              (is (= :idle (:seon.agent/state
                             (db/entity {:seon.db/ref
                                         [:seon.agent/id agent-id]})))
                  "agent ends :idle"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; #49 fail-loud observability — a SKIPPED wake must never be silent. The
;; inbound trigger handler fires SYNCHRONOUSLY during the message's
;; transact. With the !kick-scheduled latch pre-held (the tail-window
;; condition), the handler skips scheduling a loop — and must emit a
;; loud console.warn naming the reason (latch held) and the recovery
;; (drain-on-exit re-runs for it). We pre-hold the latch, transact an
;; inbound, and assert the warn fired with the latch reason.
;; ---------------------------------------------------------------------------

(deftest dropped-wake-is-fail-loud
  (async done
    (let [orig-warn js/console.warn
          warns     (atom [])]
      (set! js/console.warn (fn [& args] (swap! warns conj (apply str args))))
      (-> (with-conn
            (fn ^:async run []
              (let [compile-state (await (repl/ensure-bootstrap!))]
                (await (db/transact! {:seon.db/tx-data [{:seon.user/id "user"}]}))
                (await (db/with-agent agent-id
                         (fn ^:async boot []
                           (await (seval/setup-agent-ns! compile-state
                                                         (agent/home-ns agent-id)
                                                         agent-id))
                           (await (agent/create! {:seon.agent/id agent-id})))))
                ;; wire the inbound trigger (a no-op llm — the loop never runs
                ;; because we keep the latch held)
                (agent/install-user-trigger!
                  {:seon.agent/id            agent-id
                   :seon.agent/llm-fn        (scripted-llm "")
                   :seon.agent/compile-state compile-state})
                ;; pre-hold the latch → the next inbound's wake is DROPPED
                (swap! @#'agent/!kick-scheduled conj agent-id)
                (reset! warns [])
                (await (db/transact!
                         {:seon.db/tx-data
                          [{:seon.agent.message/id      "MSGwarndrop001"
                            :seon.agent.message/from    {:seon.user/id "user"}
                            :seon.agent.message/to      [{:seon.agent/id agent-id}]
                            :seon.agent.message/content "drop-me"
                            :seon.agent.message/at      (js/Date.)
                            :seon.agent.message/hops    0
                            :seon.agent.message/origin  :human}]}))
                ;; release for cleanliness
                (swap! @#'agent/!kick-scheduled disj agent-id)
                (let [w (str/join "\n" @warns)]
                  (is (seq @warns) "a dropped wake emitted a console.warn (not silent)")
                  (is (re-find #"WAKE SKIPPED" w)
                      "the warn names the skip explicitly")
                  (is (re-find #"latch" w)
                      "the warn names the latch as the reason")
                  (is (re-find #"drain-on-exit" w)
                      "the warn names the drain-on-exit recovery")))))
          (.then (fn [_] (set! js/console.warn orig-warn) (done)))
          (.catch (fn [e]
                    (set! js/console.warn orig-warn)
                    (is false (str "threw — " e)) (done)))))))
