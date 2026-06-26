(ns seon.agent-loop-test
  "The RUN-MODEL loop — `seon.agent.loop/run-loop!` as a fold of
   `fsm/transition` over events derived from the run's data. Drives the REAL
   loop (real eval-batch, real run mutations — nothing mocked but the LLM
   text) and pins:

     - a trigger OPENS a run → derived `:running`; an LLM that `(complete …)`
       closes the run `:completed` → derived `:idle`.
     - the WORK bound: a run opened with `turn-limit 1` and an LLM that never
       completes runs exactly ONE turn, then the loop closes it `:turn-limit`
       (derived `:idle`).
     - FENCING: a run-loop on a SUPERSEDED run (the agent's `:seon.agent/run`
       points at a newer run) bails without running a turn.
     - `renew!` bumps the work bound (the sliding window = lease renewal).

   Tests open a FRESH `:memory` conn (via `seon.client/open-agent-conn!`) —
   nothing here touches the live agents."
  (:require
    [cljs.test :refer [deftest is testing async]]
    [seon.agent :as agent]
    [seon.agent.loop :as loop]
    [seon.agent.run :as run]
    [seon.client :as client]
    [seon.ctx :as ctx]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.repl :as repl]))

(defn- with-conn
  [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (let [prev db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body))
                     (.finally (fn [] (set! db/*conn* prev)))))))))

(def ^:private agent-id "AGTlooprun0001")          ; 14 chars (:seon.db/id)

(defn- scripted-llm
  "ctx-string -> Promise<{:text text}> — replays `text` on every call."
  [text]
  (fn [_ctx] (js/Promise.resolve {:text text})))

(defn ^:async ^:private boot-agent!
  "Fresh-conn world: user entity + home ns + agent entity. Returns the
   compile-state."
  []
  (let [cs (await (repl/ensure-bootstrap!))]
    (await (db/transact! {:seon.db/tx-data [{:seon.user/id "user"}]}))
    (await (db/with-agent agent-id
             (fn ^:async boot []
               (await (seval/setup-agent-ns! cs (ctx/home-ns agent-id) agent-id))
               (await (agent/create! {:seon.agent/id agent-id})))))
    cs))

(defn- derived [id]
  (:seon.agent/state (agent/state-snapshot {:seon.agent/id id})))

(defn- turn-count [run-id]
  (or (db/query {:seon.db/query
                 '[:find (count ?t) . :in $ ?r
                   :where
                   [?run :seon.agent.run/id ?r]
                   [?t :seon.agent.turn/run ?run]]
                 :seon.db/args [run-id]})
      0))

;; ============================================================
;; A trigger opens a run → :running; (complete …) closes it → :idle.
;; ============================================================

(deftest open-run-runs-then-complete-parks-idle
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [cs (await (boot-agent!))]
              (let [opened (await (run/open-run! {:seon.agent/id agent-id
                                                  :seon.agent.run/trigger :message}))
                    run-id (:seon.agent.run/id opened)]
                (testing "an open run ⇒ derived :running"
                  (is (= :running (derived agent-id))))
                (let [final (await (db/with-agent agent-id
                                     (fn ^:async drive []
                                       (await (loop/run-loop!
                                                {:seon.agent/id            agent-id
                                                 :seon.agent/llm-fn        (scripted-llm "(complete \"done\")")
                                                 :seon.agent/compile-state cs}
                                                run-id)))))]
                  (testing "the loop returns the terminal FSM state :idle"
                    (is (= :idle final)))
                  (testing "the run closed :completed and the agent is derived :idle"
                    (is (= :completed (:seon.agent.run/closed-reason
                                        (run/snapshot {:seon.agent.run/id run-id}))))
                    (is (= :idle (derived agent-id))))
                  (testing "exactly one turn ran (it completed on turn 1)"
                    (is (= 1 (turn-count run-id)))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; The WORK bound — turn-limit 1 + an LLM that never completes runs ONE turn,
;; then the loop closes the run :turn-limit (derived :idle).
;; ============================================================

(deftest turn-limit-closes-the-run
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [cs (await (boot-agent!))]
              (let [opened (await (run/open-run! {:seon.agent/id agent-id
                                                  :seon.agent.run/trigger :message
                                                  :seon.agent.run/turn-limit 1}))
                    run-id (:seon.agent.run/id opened)
                    final  (await (db/with-agent agent-id
                                    (fn ^:async drive []
                                      (await (loop/run-loop!
                                               {:seon.agent/id            agent-id
                                                ;; a benign form — never completes,
                                                ;; so the WORK bound is the stopper
                                                :seon.agent/llm-fn        (scripted-llm "(+ 1 1)")
                                                :seon.agent/compile-state cs}
                                               run-id)))))]
                (testing "the loop closes on the work bound and returns :idle"
                  (is (= :idle final)))
                (testing "the run closed :turn-limit after exactly one turn"
                  (is (= 1 (turn-count run-id)) "one turn — the cap was 1")
                  (is (= :turn-limit (:seon.agent.run/closed-reason
                                       (run/snapshot {:seon.agent.run/id run-id})))))
                (testing "the agent ends derived :idle"
                  (is (= :idle (derived agent-id))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; FENCING — a run-loop on a SUPERSEDED run runs NO turn (the run-id fence).
;; ============================================================

(deftest superseded-run-bails-without-running
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [cs (await (boot-agent!))]
              ;; open run A, then open run B — B becomes the agent's current
              ;; run (the fencing pointer), orphaning A open.
              (let [a (await (run/open-run! {:seon.agent/id agent-id
                                             :seon.agent.run/trigger :message}))
                    a-id (:seon.agent.run/id a)
                    _ (await (run/open-run! {:seon.agent/id agent-id
                                             :seon.agent.run/trigger :message}))]
                (testing "run A no longer owns the agent (B does)"
                  (is (false? (run/owns-run? {:seon.agent/id agent-id
                                              :seon.agent.run/id a-id}))))
                (let [final (await (db/with-agent agent-id
                                     (fn ^:async drive []
                                       (await (loop/run-loop!
                                                {:seon.agent/id            agent-id
                                                 :seon.agent/llm-fn        (scripted-llm "(+ 1 1)")
                                                 :seon.agent/compile-state cs}
                                                a-id)))))]
                  (testing "the superseded loop bails (returns a terminal state)"
                    (is (= :idle final)))
                  (testing "the fence held — run A ran ZERO turns"
                    (is (= 0 (turn-count a-id))))))))
            )
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; renew! bumps the work bound (the sliding window = lease renewal).
;; ============================================================

(deftest renew-bumps-the-turn-limit
  (async done
    (-> (with-conn
          (fn ^:async run []
            (await (boot-agent!))
            (let [opened (await (run/open-run! {:seon.agent/id agent-id
                                                :seon.agent.run/trigger :message
                                                :seon.agent.run/turn-limit 2}))
                  run-id (:seon.agent.run/id opened)]
              (is (= 2 (:seon.agent.run/turn-limit
                         (run/snapshot {:seon.agent.run/id run-id})))
                  "the run opened with turn-limit 2")
              (await (run/renew! {:seon.agent/id agent-id :seon.agent.run/id run-id}))
              (is (= 3 (:seon.agent.run/turn-limit
                         (run/snapshot {:seon.agent.run/id run-id})))
                  "renew! bumped the work bound to 3 (the sliding window)")))
          )
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
