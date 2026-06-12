(ns seon.agent.turns-test
  "The <turns> countdown section (opus-live-tests 2026-06-12 L11):
   renders ONE budget line while the agent is MID-TASK
   (`seon.ctx/task-in-progress?` — a live inbound message the agent
   has not REPLIED to; the per-turn self-fold does NOT close the
   window, finding 1 of the same doc), derived per render from the
   message + turn log and the entity's `:seon.agent/turns-cap`
   (default `seon.ctx/default-turns-cap` — the cap `run-agentic-loop!`
   actually enforces); renders NOTHING when idle. All on a FRESH
   :memory conn — never the live agent conn."
  (:require
    [cljs.test :refer [deftest is testing async]]
    [clojure.string :as str]
    [seon.agent.turns :as turns]
    [seon.client :as client]
    [seon.ctx :as ctx]
    [seon.db :as db]))

(defn- with-conn
  "Open a fresh schema-loaded conn, `set!` it as the ROOT `db/*conn*`
   (a plain `binding` does NOT survive Promise/await boundaries in
   CLJS), run `body` (0-arg, may return a Promise), restore after."
  [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (let [prev db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body))
                     (.finally (fn [] (set! db/*conn* prev)))))))))

(defn- ^:async seed-mid-task!
  "An agent (optional `cap`) + an inbound user message + ONE turn
   opened AFTER it — the exact shape of an agent whose SECOND working
   turn is about to render (the prompt renders before `with-turn!`
   opens the next turn, so the open turn count is 1). Returns
   {::now <Date> ::session-id <id>} so callers can append later turns
   to the SAME session (the multi-turn wake simulation)."
  [id cap]
  (let [now (js/Date.)
        t   (fn [ms] (js/Date. (+ (.getTime now) ms)))
        sid (db/new-id!)
        env (await
              (db/transact!
                {:seon.db/tx-data
                 [(cond->
                    {:seon.agent/id    id
                     :seon.agent/state :idle
                     :seon.agent/sessions
                     [{:seon.agent.session/id sid
                       :seon.agent.session/at (t 0)
                       :seon.agent.session/turns
                       [{:seon.agent.turn/id           (db/new-id!)
                         :seon.agent.turn/at           (t 20)
                         :seon.agent.turn/status       :done
                         :seon.agent.turn/prompt-chars 100}]}]}
                    cap (assoc :seon.agent/turns-cap cap))
                  {:seon.agent.message/id      (db/new-id!)
                   :seon.agent.message/from    {:seon.user/id "user"}
                   :seon.agent.message/to      [{:seon.agent/id id}]
                   :seon.agent.message/content "do the thing"
                   :seon.agent.message/at      (t 10)
                   :seon.agent.message/hops    0}]}))]
    (when-not (:seon.db/ok? env)
      (throw (ex-info "turns-test: seed transact failed" env)))
    {::now now ::session-id sid}))

(defn- ^:async transact-ok!
  "Transact `tx-data`, assert the envelope landed."
  [tx-data]
  (let [env (await (db/transact! {:seon.db/tx-data tx-data}))]
    (is (true? (:seon.db/ok? env)) "transact lands")))

(deftest mid-task-capped-agent-sees-the-meter
  (async done
    (-> (with-conn
          (fn ^:async run []
            (await (seed-mid-task! "AGTturnscap001" 5))
            (let [text (turns/turns-block @db/*conn* "AGTturnscap001")]
              (testing "one <turns> line, naming the turn about to run"
                (is (str/includes? text "<turns>"))
                (is (str/includes? text "turn 2 of 5")
                    "one turn opened since the inbound → the render is
                     for turn 2; cap = the entity's :seon.agent/turns-cap")
                (is (str/includes?
                      text "an incomplete honest answer beats a capped silence")
                    "the closure teaching rides the meter"))
              (testing "the section fn matches the block (same derivation)"
                (is (= text (turns/turns-section
                              {:seon.db/db    @db/*conn*
                               :seon.agent/id "AGTturnscap001"})))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest uncapped-agent-sees-the-loops-default-cap
  (async done
    (-> (with-conn
          (fn ^:async run []
            (await (seed-mid-task! "AGTturnsdef001" nil))
            (let [text (turns/turns-block @db/*conn* "AGTturnsdef001")]
              (is (str/includes?
                    text (str "turn 2 of " ctx/default-turns-cap))
                  "no :seon.agent/turns-cap attr → the meter shows the
                   default cap run-agentic-loop! ACTUALLY enforces —
                   the meter never lies about the real budget"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest countdown-survives-the-self-fold-through-a-wake
  ;; THE finding-1 regression (opus-live-tests 2026-06-12): in s12 the
  ;; <turns> section rendered in turn 1 of 19 ONLY — the per-turn
  ;; self-fold outbound (from = to = me) closed the inbox window. The
  ;; gate is now task-in-progress? (reply-aware, mirrors the loop's
  ;; :replied stop), so the meter renders EVERY turn until the reply.
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [id  "AGTturnswak001"
                  {::keys [now session-id]} (await (seed-mid-task! id 5))
                  t   (fn [ms] (js/Date. (+ (.getTime now) ms)))
                  me  {:seon.agent/id id}]
              (testing "turn 2 renders the meter (pre-fold, as before)"
                (is (str/includes? (turns/turns-block @db/*conn* id)
                                   "turn 2 of 5")))
              ;; The self-fold: the agent's per-turn assistant
              ;; self-message — from = to = me, the outbound that used
              ;; to close the unanswered-inbox window after turn 1.
              (await (transact-ok!
                       [{:seon.agent.message/id      (db/new-id!)
                         :seon.agent.message/from    me
                         :seon.agent.message/to      [me]
                         :seon.agent.message/content "[fold] still researching…"
                         :seon.agent.message/at      (t 25)
                         :seon.agent.message/hops    1}]))
              (testing "the self-fold does NOT kill the countdown"
                (is (str/includes? (turns/turns-block @db/*conn* id)
                                   "turn 2 of 5")
                    "fold lands between turns — same turn about to run"))
              ;; A second eval turn opens (no reply yet).
              (await (transact-ok!
                       [{:seon.agent.session/id session-id
                         :seon.agent.session/turns
                         [{:seon.agent.turn/id           (db/new-id!)
                           :seon.agent.turn/at           (t 30)
                           :seon.agent.turn/status       :done
                           :seon.agent.turn/prompt-chars 100}]}]))
              (testing "turn 3 renders with N incremented"
                (is (str/includes? (turns/turns-block @db/*conn* id)
                                   "turn 3 of 5")
                    "the countdown pressures LATE turns, not just turn 1"))
              ;; The reply — outbound to a NON-self recipient: the
              ;; loop's :replied halt; the wake is over.
              (await (transact-ok!
                       [{:seon.agent.message/id      (db/new-id!)
                         :seon.agent.message/from    me
                         :seon.agent.message/to      [{:seon.user/id "user"}]
                         :seon.agent.message/content "done — here it is"
                         :seon.agent.message/at      (t 40)
                         :seon.agent.message/hops    1}]))
              (testing "reply + idle → the meter vanishes"
                (is (= "" (turns/turns-block @db/*conn* id))))
              ;; A NEW inbound re-arms the window.
              (await (transact-ok!
                       [{:seon.agent.message/id      (db/new-id!)
                         :seon.agent.message/from    {:seon.user/id "user"}
                         :seon.agent.message/to      [me]
                         :seon.agent.message/content "one more thing"
                         :seon.agent.message/at      (t 50)
                         :seon.agent.message/hops    0}]))
              (testing "a new inbound re-arms the countdown at turn 1"
                (is (str/includes? (turns/turns-block @db/*conn* id)
                                   "turn 1 of 5")
                    "zero turns opened since the new inbound")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest idle-agent-renders-nothing
  (async done
    (-> (with-conn
          (fn ^:async run []
            (testing "agent with NO messages at all → \"\""
              (let [env (await (db/transact!
                                 {:seon.db/tx-data
                                  [{:seon.agent/id    "AGTturnsidl001"
                                    :seon.agent/state :idle}]}))]
                (is (true? (:seon.db/ok? env)))
                (is (= "" (turns/turns-block @db/*conn* "AGTturnsidl001"))
                    "no inbound → no budget burning → section vanishes")))
            (testing "agent that REPLIED since the inbound → \"\""
              (let [{::keys [now]} (await (seed-mid-task! "AGTturnsrep001" 5))
                    t   (fn [ms] (js/Date. (+ (.getTime now) ms)))
                    env (await
                          (db/transact!
                            {:seon.db/tx-data
                             [{:seon.agent.message/id      (db/new-id!)
                               :seon.agent.message/from    {:seon.agent/id "AGTturnsrep001"}
                               :seon.agent.message/to      [{:seon.user/id "user"}]
                               :seon.agent.message/content "done — here it is"
                               :seon.agent.message/at      (t 30)
                               :seon.agent.message/hops    1}]}))]
                (is (true? (:seon.db/ok? env)))
                (is (= "" (turns/turns-block @db/*conn* "AGTturnsrep001"))
                    "outbound reply after the inbound = idle — derived,
                     nothing stored, nothing to acknowledge")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
