(ns seon.agent-loop-test
  "Loop stop-policy — ONE predicate, `unanswered-live-inbound?` (the
   stabilized core, 2026-06-22). The loop keeps running while a LIVE
   inbound message (to ∋ me, from ≠ me, hops < cap, origin ∉ {:core},
   handled? ≠ true) has NO outbound strictly after it; it halts
   `:replied` the moment one does. This subsumes the old
   not-yet-replied / drain / empty-retry phrasings — a message that
   arrives mid-wake is simply an unanswered live inbound at the next
   halt check, with no baseline/latch/drain bookkeeping. Pins:

     - `unanswered-live-inbound?` — the pure derivation: TRUE when a
       live inbound exists with no outbound (from = me, ∃ recipient ≠
       me) strictly after it. Assistant self-messages (from = to = me)
       never count; :core nudges and handled? messages never anchor it.
     - the loop halts `:replied` after the turn whose eval landed a
       `reply!` — even when the LLM keeps emitting forms (the churn
       shape the #35 economy fights).
     - the EMPTY-TURN guard: a turn with zero evals WHILE a live inbound
       is still unanswered re-prompts with a core nudge, bounded at
       `agent/max-empty-reprompts`, then ends with a chat-visible system
       line (turn :error + self-message). A replied agent's empty
       completion halts :replied (no re-prompt); the turns-cap still
       bounds re-prompts.

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
;; `unanswered-live-inbound?` is the inverse of "replied": TRUE while
;; work remains (a live inbound with no outbound after it).
;; ---------------------------------------------------------------------------

(deftest unanswered-live-inbound?-derivation
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
              (testing "no messages at all → false (nothing to answer)"
                (is (false? (agent/unanswered-live-inbound?
                              {:seon.agent/id agent-id}))))
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGlooptest001"
                          :seon.agent.message/from {:seon.user/id "user"}
                          :seon.agent.message/to [{:seon.agent/id agent-id}]
                          :seon.agent.message/content "what is 1+1?"
                          :seon.agent.message/at (t+ t0 10)
                          :seon.agent.message/hops 0}]}))
              (testing "inbound only, no reply yet → TRUE (keep working)"
                (is (true? (agent/unanswered-live-inbound?
                             {:seon.agent/id agent-id}))))
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGlooptest002"
                          :seon.agent.message/from {:seon.agent/id agent-id}
                          :seon.agent.message/to [{:seon.agent/id agent-id}]
                          :seon.agent.message/content ";; thinking out loud"
                          :seon.agent.message/at (t+ t0 20)
                          :seon.agent.message/hops 0}]}))
              (testing "assistant SELF-message (from = to = me) is not an answer"
                (is (true? (agent/unanswered-live-inbound?
                             {:seon.agent/id agent-id}))))
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGlooptest003"
                          :seon.agent.message/from {:seon.agent/id agent-id}
                          :seon.agent.message/to [{:seon.user/id "user"}]
                          :seon.agent.message/content "2"
                          :seon.agent.message/at (t+ t0 30)
                          :seon.agent.message/hops 1}]}))
              (testing "outbound reply after the inbound → false (halt :replied)"
                (is (false? (agent/unanswered-live-inbound?
                              {:seon.agent/id agent-id}))))
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGlooptest004"
                          :seon.agent.message/from {:seon.user/id "user"}
                          :seon.agent.message/to [{:seon.agent/id agent-id}]
                          :seon.agent.message/content "and 2+2?"
                          :seon.agent.message/at (t+ t0 40)
                          :seon.agent.message/hops 0}]}))
              (testing "NEW inbound after the reply → TRUE again (mid-wake subsumed)"
                (is (true? (agent/unanswered-live-inbound?
                             {:seon.agent/id agent-id}))))
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGlooptest005"
                          :seon.agent.message/from {:seon.agent/id agent-id}
                          :seon.agent.message/to [{:seon.user/id "user"}]
                          :seon.agent.message/content "4"
                          :seon.agent.message/at (t+ t0 50)
                          :seon.agent.message/hops 1}]}))
              (testing "second reply after the second inbound → false again"
                (is (false? (agent/unanswered-live-inbound?
                              {:seon.agent/id agent-id})))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; The COUNT regression (live-acme message-drop, 4/4 trials) — the bug a
;; TIMESTAMP comparison cannot catch. Two inbounds arrive concurrently;
;; the reply to the FIRST is emitted at a time AFTER the SECOND's
;; timestamp. The old predicate (`is there an outbound STRICTLY AFTER
;; the latest inbound?`) read that one reply as answering BOTH → halted
;; → the 2nd was silently dropped forever. The count predicate (inbounds
;; > replies) keeps the loop alive: 2 > 1 ⇒ recur; only when the 2nd
;; reply balances it (2 > 2 false) does it halt — no drop, no duplicate.
;; ---------------------------------------------------------------------------

(deftest concurrent-inbounds-one-reply-keeps-loop-running
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
                          [{:seon.agent.session/id "SEScount000001"
                            :seon.agent.session/at t0}]}]}))
              ;; TWO concurrent inbounds, no replies yet
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGcount000001"
                          :seon.agent.message/from {:seon.user/id "user"}
                          :seon.agent.message/to [{:seon.agent/id agent-id}]
                          :seon.agent.message/content "q1"
                          :seon.agent.message/at (t+ t0 10)
                          :seon.agent.message/hops 0
                          :seon.agent.message/origin :human}
                         {:seon.agent.message/id "MSGcount000002"
                          :seon.agent.message/from {:seon.user/id "user"}
                          :seon.agent.message/to [{:seon.agent/id agent-id}]
                          :seon.agent.message/content "q2"
                          :seon.agent.message/at (t+ t0 20)
                          :seon.agent.message/hops 0
                          :seon.agent.message/origin :human}]}))
              (testing "2 inbounds, 0 replies → TRUE (both unanswered)"
                (is (true? (agent/unanswered-live-inbound?
                             {:seon.agent/id agent-id}))))
              ;; ONE reply, emitted at a time AFTER q2's timestamp — the
              ;; exact shape the old timestamp policy mis-read as answering
              ;; both (and so dropped q2).
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGcount000003"
                          :seon.agent.message/from {:seon.agent/id agent-id}
                          :seon.agent.message/to [{:seon.user/id "user"}]
                          :seon.agent.message/content "a1"
                          :seon.agent.message/at (t+ t0 30)
                          :seon.agent.message/hops 1
                          :seon.agent.message/origin :agent}]}))
              (testing "2 inbounds, 1 reply (emitted after q2's :at) → TRUE — recur, no drop"
                (is (true? (agent/unanswered-live-inbound?
                             {:seon.agent/id agent-id}))))
              ;; the SECOND reply balances it
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGcount000004"
                          :seon.agent.message/from {:seon.agent/id agent-id}
                          :seon.agent.message/to [{:seon.user/id "user"}]
                          :seon.agent.message/content "a2"
                          :seon.agent.message/at (t+ t0 40)
                          :seon.agent.message/hops 1
                          :seon.agent.message/origin :agent}]}))
              (testing "2 inbounds, 2 replies → false — balanced, halt"
                (is (false? (agent/unanswered-live-inbound?
                              {:seon.agent/id agent-id}))))
              ;; skew attempts: a self-note, a :core outbound, a :core
              ;; inbound, a handled? inbound, a hop-exhausted inbound —
              ;; NONE may change the balanced count.
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGcount000005"
                          :seon.agent.message/from {:seon.agent/id agent-id}
                          :seon.agent.message/to [{:seon.agent/id agent-id}]
                          :seon.agent.message/content "self note"
                          :seon.agent.message/at (t+ t0 50)
                          :seon.agent.message/hops 0
                          :seon.agent.message/origin :agent}
                         {:seon.agent.message/id "MSGcount000006"
                          :seon.agent.message/from {:seon.agent/id agent-id}
                          :seon.agent.message/to [{:seon.user/id "user"}]
                          :seon.agent.message/content "core out"
                          :seon.agent.message/at (t+ t0 55)
                          :seon.agent.message/hops 0
                          :seon.agent.message/origin :core}
                         {:seon.agent.message/id "MSGcount000007"
                          :seon.agent.message/from {:seon.user/id "user"}
                          :seon.agent.message/to [{:seon.agent/id agent-id}]
                          :seon.agent.message/content "core in"
                          :seon.agent.message/at (t+ t0 60)
                          :seon.agent.message/hops 0
                          :seon.agent.message/origin :core}
                         {:seon.agent.message/id "MSGcount000008"
                          :seon.agent.message/from {:seon.user/id "user"}
                          :seon.agent.message/to [{:seon.agent/id agent-id}]
                          :seon.agent.message/content "handled in"
                          :seon.agent.message/at (t+ t0 70)
                          :seon.agent.message/hops 0
                          :seon.agent.message/origin :human
                          :seon.agent.message/handled? true}
                         {:seon.agent.message/id "MSGcount000009"
                          :seon.agent.message/from {:seon.user/id "user"}
                          :seon.agent.message/to [{:seon.agent/id agent-id}]
                          :seon.agent.message/content "hop dead"
                          :seon.agent.message/at (t+ t0 80)
                          :seon.agent.message/hops 99
                          :seon.agent.message/origin :human}]}))
              (testing "self/core/handled?/hop-exhausted do NOT skew the count → still false"
                (is (false? (agent/unanswered-live-inbound?
                              {:seon.agent/id agent-id})))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; #43 — a :core-origin message (a substrate nudge, e.g. tile recovery,
;; sent FROM the user-ref) must NEVER anchor the stop-policy. Without the
;; origin exclusion a broken-tile :core message arriving AFTER the reply
;; would re-open the window and re-arm the loop. A real :human follow-up
;; still re-opens it.
;; ---------------------------------------------------------------------------

(deftest unanswered-live-inbound?-ignores-core-origin
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
              (testing "after the reply the loop halts → false"
                (is (false? (agent/unanswered-live-inbound?
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
              (testing ":core message does NOT re-open the window → still false"
                (is (false? (agent/unanswered-live-inbound?
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
              (testing ":human follow-up re-opens the window → true"
                (is (true? (agent/unanswered-live-inbound?
                             {:seon.agent/id agent-id})))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; I-1 — a `handled?` (tx-hook-consumed) message neither wakes nor
;; anchors the stop-policy. A downstream deterministic chat-control sets
;; handled? in the same tx that processes the command, so the agent is
;; not double-woken and the message does not hold the loop open.
;; ---------------------------------------------------------------------------

(deftest handled?-suppresses-wake-and-stop-policy
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
                          [{:seon.agent.session/id "SEShandled0001"
                            :seon.agent.session/at t0}]}]}))
              ;; an ordinary live inbound holds the loop open
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGhandled0001"
                          :seon.agent.message/from {:seon.user/id "user"}
                          :seon.agent.message/to [{:seon.agent/id agent-id}]
                          :seon.agent.message/content "do a thing"
                          :seon.agent.message/at (t+ t0 10)
                          :seon.agent.message/hops 0
                          :seon.agent.message/origin :human}]}))
              (testing "live inbound → unanswered (loop keeps running)"
                (is (true? (agent/unanswered-live-inbound?
                             {:seon.agent/id agent-id}))))
              ;; a tx-hook consumes the SAME message: handled? true
              (await (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id "MSGhandled0001"
                          :seon.agent.message/handled? true}]}))
              (testing "handled? = true → NOT an anchor (does not hold the loop)"
                (is (false? (agent/unanswered-live-inbound?
                              {:seon.agent/id agent-id}))))
              ;; and a handled? message does not WAKE the agent either
              (let [my-eid (:db/id (db/entity
                                     {:seon.db/ref [:seon.agent/id agent-id]}))
                    m-eid  (:db/id (db/entity
                                     {:seon.db/ref [:seon.agent.message/id
                                                    "MSGhandled0001"]}))]
                (testing "handled? message does NOT wake (inbound-msg-datom? false)"
                  (is (false?
                        (boolean
                          (#'agent/inbound-msg-datom?
                            @db/*conn*
                            {:seon.db/e m-eid :seon.db/v my-eid}
                            my-eid)))))))))
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
              (is (false? (agent/unanswered-live-inbound?
                            {:seon.agent/id agent-id}))
                  "the reply row is live-observable — nothing left unanswered"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; The EMPTY-TURN guard (downstream ask 20) — a completion with EMPTY
;; visible content (the deepseek thinking-mode shape: every token in
;; the reasoning field) yields 0 forms; pre-fix the loop closed
;; `done [0 "ok"]` and the wake ended with NO reply — the agent looked
;; dead until a manual "continue". Now: nudge + re-prompt (bounded),
;; then a chat-visible system line. Standalone — only reached while a
;; live inbound is still unanswered.
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
;; Mid-wake inbound through the REAL loop — the old #49 drain, now plain.
;; A NEW inbound that arrives during the wake is an unanswered live
;; inbound at the next halt check, so the loop keeps running and answers
;; it; nothing special is needed. We simulate the arrival as a
;; side-effect of an empty-completion turn's llm call (the same relative
;; ordering a tail-window /chat lands in), then assert the loop ran a
;; make-good turn and ended :replied with the new message answered.
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

(defn- midwake-injecting-llm
  "ctx -> Promise<{:text …}> (the same non-async, Promise-returning shape
   as `scripted-llm`). Empty completions while the first call also
   transacts a NEW inbound (`q2-tail-window`, unanswered, :at strictly
   after the wake anchor) — simulating a /chat that races the tail
   window. Call sequence: empty until the new inbound is live, then a
   reply that answers it. The transact resolves before the {:text} so
   the new inbound is live when the loop's halt check re-queries."
  []
  (let [!n (atom -1)]
    (fn llm [_ctx]
      (let [i    (swap! !n inc)
            resp {:text (if (< i 1) "" reply-form)}]
        (if (zero? i)
          (-> (db/transact!
                {:seon.db/tx-data
                 [{:seon.agent.message/id      "MSGmidwakeinj1"
                   :seon.agent.message/from    {:seon.user/id "user"}
                   :seon.agent.message/to      [{:seon.agent/id agent-id}]
                   :seon.agent.message/content "q2-tail-window"
                   :seon.agent.message/at      (js/Date.)
                   :seon.agent.message/hops    0
                   :seon.agent.message/origin  :human}]})
              (.then (fn [_] resp)))
          (js/Promise.resolve resp))))))

(deftest midwake-inbound-keeps-the-loop-running-then-replies
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [compile-state (await (repl/ensure-bootstrap!))
                  mid (await (boot-loop-agent! compile-state "ping"))
                  result (await (drive-loop! compile-state mid
                                             (midwake-injecting-llm)))]
              (is (= 1 (inbound-content-count "q2-tail-window"))
                  "the mid-wake inbound landed exactly once")
              (is (= :replied (:seon.agent/halt result))
                  "the loop kept running for the new inbound and replied")
              (is (false? (agent/unanswered-live-inbound?
                            {:seon.agent/id agent-id}))
                  "balanced — every inbound has a reply, nothing left unanswered")
              ;; BOTH the wake anchor (ping) AND the mid-wake inbound
              ;; (q2-tail-window) must be answered — NOT just one. The
              ;; count predicate balances at 2 inbounds = 2 replies; a
              ;; single reply (the dropped-message bug) would leave it at
              ;; 2 inbounds / 1 reply ⇒ still unanswered ⇒ NOT halted.
              (let [me (:db/id (db/entity
                                 {:seon.db/ref [:seon.agent/id agent-id]}))]
                (is (= 2 (#'agent/live-inbound-count me))
                    "both inbounds (ping + mid-wake q2) are live questions")
                (is (= 2 (#'agent/user-facing-reply-count me))
                    "BOTH inbounds answered — two user-facing replies, neither dropped"))
              (is (= :idle (:seon.agent/state
                             (db/entity {:seon.db/ref
                                         [:seon.agent/id agent-id]})))
                  "agent ends :idle — ready for the next wake"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; #49 fail-loud observability — a SKIPPED wake must never be silent. The
;; inbound trigger handler fires SYNCHRONOUSLY during the message's
;; transact. With the !kick-scheduled latch pre-held, the handler skips
;; scheduling a loop — and must emit a loud console.warn naming the skip
;; and the latch reason. We pre-hold the latch, transact an inbound, and
;; assert the warn fired.
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
                      "the warn names the latch as the reason")))))
          (.then (fn [_] (set! js/console.warn orig-warn) (done)))
          (.catch (fn [e]
                    (set! js/console.warn orig-warn)
                    (is false (str "threw — " e)) (done)))))))
