(ns seon.agent.turns-test
  "The <turns> countdown section (opus-live-tests 2026-06-12 L11):
   renders ONE budget line while the agent is MID-TASK (an unanswered
   inbound message), derived per render from the message + turn log
   and the entity's `:seon.agent/turns-cap` (default
   `seon.ctx/default-turns-cap` — the cap `run-agentic-loop!` actually
   enforces); renders NOTHING when idle. All on a FRESH :memory conn —
   never the live agent conn."
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
   opens the next turn, so the open turn count is 1)."
  [id cap]
  (let [now (js/Date.)
        t   (fn [ms] (js/Date. (+ (.getTime now) ms)))
        env (await
              (db/transact!
                {:seon.db/tx-data
                 [(cond->
                    {:seon.agent/id    id
                     :seon.agent/state :idle
                     :seon.agent/sessions
                     [{:seon.agent.session/id (db/new-id!)
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
    now))

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
              (let [now (await (seed-mid-task! "AGTturnsrep001" 5))
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
