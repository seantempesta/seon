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
    [seon.repl :as repl]
    [seon.warn :as warn]))

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

(defn- scripted-llm-seq
  "ctx-string -> Promise<{:text text}> — replays each text in `texts` in turn,
   then repeats the LAST one for every later call (a deterministic multi-turn
   script)."
  [texts]
  (let [!i (atom 0)
        v  (vec texts)]
    (fn [_ctx]
      (let [i (min @!i (dec (count v)))]
        (swap! !i inc)
        (js/Promise.resolve {:text (nth v i)})))))

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
  (:seon.agent/state (agent/derive-status {:seon.agent/id id})))

(defn ^:async supersede!
  "Open a fresh CURRENT run for `id`, leaving the prior run OPEN but no longer
   pointed-at (a 'superseded' run). open-run! is CAS-guarded on an ABSENT
   pointer — a plain second open while a run is pointed-at FAILS — so
   supersede = retract the pointer, then open. Returns the new run's snapshot."
  [id]
  (await (db/transact! {:seon.db/tx-data [[:db/retract [:seon.agent/id id] :seon.agent/run]]}))
  (await (run/open-run! {:seon.agent/id id :seon.agent.run/trigger :message})))

(defn- turn-count [run-id]
  (or (db/query {:seon.db/query
                 '[:find (count ?t) . :in $ ?r
                   :where
                   [?run :seon.agent.run/id ?r]
                   [?t :seon.agent.turn/run ?run]]
                 :seon.db/args [run-id]})
      0))

;; ============================================================
;; Wake-path helpers — the wake handler is fire-and-forget (setTimeout(0)),
;; so the tests transact an inbound datom then POLL the macrotask queue until
;; the handler's open-run!/run-loop! (or renew!) has settled.
;; ============================================================

(defn- runs-for
  "The run-ids of every run belonging to agent `id` (any status)."
  [id]
  (or (db/query {:seon.db/query
                 '[:find [?r ...] :in $ ?aid
                   :where
                   [?a :seon.agent/id ?aid]
                   [?run :seon.agent.run/agent ?a]
                   [?run :seon.agent.run/id ?r]]
                 :seon.db/args [id]})
      []))

(defn- turns-for
  "The turn-ids of every turn the agent drove (across all its runs)."
  [id]
  (or (db/query {:seon.db/query
                 '[:find [?t ...] :in $ ?aid
                   :where
                   [?a :seon.agent/id ?aid]
                   [?run :seon.agent.run/agent ?a]
                   [?t :seon.agent.turn/run ?run]]
                 :seon.db/args [id]})
      []))

(defn ^:async wait-until
  "Poll `pred` (0-arg → truthy) on the macrotask queue every `step-ms` up to
   `max-ms`. Resolves true once pred holds, false on timeout — letting the
   wake-handler's setTimeout(0) re-drive (open-run! + run-loop! / renew!) run
   before the test asserts. Recursive (not loop/recur) so the await transform
   is unambiguous."
  [pred max-ms step-ms]
  (if (pred)
    true
    (if (<= max-ms 0)
      false
      (do
        (await (js/Promise. (fn [res] (js/setTimeout res step-ms))))
        (await (wait-until pred (- max-ms step-ms) step-ms))))))

(defn ^:async send-inbound!
  "Transact a fully-formed inbound message row DIRECTLY (bypassing
   `message!`'s defaulting + todo-minting, so the test controls from / hops /
   origin). `to` is the recipient agent-id; `from` a resolving lookup-ref."
  [from to-agent-id content hops origin]
  (await (db/transact!
           {:seon.db/tx-data
            [{:seon.agent.message/id      (db/new-id!)
              :seon.agent.message/from    from
              :seon.agent.message/to      [[:seon.agent/id to-agent-id]]
              :seon.agent.message/content content
              :seon.agent.message/at      (js/Date.)
              :seon.agent.message/hops    hops
              :seon.agent.message/origin  origin}]})))

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
;; The EMPTY-STREAK bound — an LLM that emits zero actionable forms for a
;; streak of turns closes the run :no-forms (not a spin to the turn-limit).
;; A single empty turn must NOT halt (the streak guard tolerates thinking).
;; ============================================================

(deftest empty-forms-streak-closes-the-run-no-forms
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [cs (await (boot-agent!))]
              ;; turn-limit well above the streak limit, so the EMPTY-STREAK
              ;; guard is the stopper (not the work bound); the LLM replies
              ;; with no parseable forms (empty text) every turn.
              (let [opened (await (run/open-run! {:seon.agent/id agent-id
                                                  :seon.agent.run/trigger :message
                                                  :seon.agent.run/turn-limit 20}))
                    run-id (:seon.agent.run/id opened)
                    final  (await (db/with-agent agent-id
                                    (fn ^:async drive []
                                      (await (loop/run-loop!
                                               {:seon.agent/id            agent-id
                                                :seon.agent/llm-fn        (scripted-llm "")
                                                :seon.agent/compile-state cs}
                                               run-id)))))]
                (testing "the loop halts on the empty-streak and returns :idle"
                  (is (= :idle final)))
                (testing "exactly the streak length of empty turns ran (NOT to turn-limit 20)"
                  (is (= loop/no-forms-streak-limit (turn-count run-id))))
                (testing "the run closed :no-forms and the agent is derived :idle"
                  (is (= :no-forms (:seon.agent.run/closed-reason
                                     (run/snapshot {:seon.agent.run/id run-id}))))
                  (is (= :idle (derived agent-id))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest single-empty-turn-then-productive-does-not-halt-no-forms
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [cs (await (boot-agent!))]
              (let [opened (await (run/open-run! {:seon.agent/id agent-id
                                                  :seon.agent.run/trigger :message
                                                  :seon.agent.run/turn-limit 20}))
                    run-id (:seon.agent.run/id opened)
                    ;; one empty (thinking) turn, THEN a productive (complete …)
                    final  (await (db/with-agent agent-id
                                    (fn ^:async drive []
                                      (await (loop/run-loop!
                                               {:seon.agent/id            agent-id
                                                :seon.agent/llm-fn        (scripted-llm-seq ["" "(complete \"done\")"])
                                                :seon.agent/compile-state cs}
                                               run-id)))))]
                (testing "a single empty turn does NOT trip the streak guard"
                  (is (= :idle final)))
                (testing "the productive turn closed the run :completed, NOT :no-forms"
                  (is (= :completed (:seon.agent.run/closed-reason
                                      (run/snapshot {:seon.agent.run/id run-id})))))
                (testing "exactly two turns ran — the empty one then the completing one"
                  (is (= 2 (turn-count run-id))))
                (is (= :idle (derived agent-id)))))))
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
              ;; open run A, then supersede it (retract pointer + open B, since
              ;; open-run! is CAS-guarded) — B becomes the agent's current run
              ;; (the fencing pointer), orphaning A open.
              (let [a (await (run/open-run! {:seon.agent/id agent-id
                                             :seon.agent.run/trigger :message}))
                    a-id (:seon.agent.run/id a)
                    _ (await (supersede! agent-id))]
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

;; ============================================================
;; WAKE PATH — `install-wake-trigger!` is the agent's real entry point; an
;; inbound datom fires the tx-listener which (fire-and-forget) opens+drives a
;; run on :idle, renews on :running, and refuses a hop-exhausted message.
;; These drive the trigger end to end (transact a message, await the tick).
;; ============================================================

(deftest wake-idle-opens-and-drives-a-run-with-cause
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [cs (await (boot-agent!))]
              (loop/install-wake-trigger!
                {:seon.agent/id            agent-id
                 :seon.agent/llm-fn        (scripted-llm "(complete \"ok\")")
                 :seon.agent/compile-state cs})
              (testing "no run before any message; agent is derived :idle"
                (is (= [] (runs-for agent-id)))
                (is (= :idle (derived agent-id))))
              (await (send-inbound! [:seon.user/id "user"] agent-id "wake up" 0 :human))
              ;; the handler schedules setTimeout(0) → open-run! + run-loop!;
              ;; the scripted (complete …) closes the run on turn 1.
              (let [ok? (await (wait-until
                                 (fn []
                                   (let [rs (runs-for agent-id)]
                                     (and (seq rs)
                                          (= :closed (:seon.agent.run/status
                                                       (run/snapshot {:seon.agent.run/id (first rs)}))))))
                                 8000 25))
                    rids (runs-for agent-id)]
                (testing "the wake opened EXACTLY ONE run, trigger :message"
                  (is ok? "the wake-driven run opened and closed within the window")
                  (is (= 1 (count rids)))
                  (is (= :message (:seon.agent.run/trigger
                                    (run/snapshot {:seon.agent.run/id (first rids)})))))
                (testing "the run's CAUSE ref points at the waking message"
                  (let [run-ent (db/entity {:seon.db/ref [:seon.agent.run/id (first rids)]})
                        cause   (some-> (:db/id (:seon.agent.run/cause run-ent)) db/entity)]
                    (is (= "wake up" (:seon.agent.message/content cause))
                        "cause = the inbound message that woke the run")))
                (testing "the loop actually drove a turn and completed → :idle"
                  (is (pos? (count (turns-for agent-id))))
                  (is (= :completed (:seon.agent.run/closed-reason
                                      (run/snapshot {:seon.agent.run/id (first rids)}))))
                  (is (= :idle (derived agent-id))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest wake-running-renews-without-opening-a-second-run
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [cs (await (boot-agent!))]
              (loop/install-wake-trigger!
                {:seon.agent/id            agent-id
                 :seon.agent/llm-fn        (scripted-llm "(+ 1 1)")
                 :seon.agent/compile-state cs})
              ;; Put the agent in :running by OPENING a run manually (nothing
              ;; drives it — the :running wake branch only renews the lease).
              (let [opened (await (run/open-run! {:seon.agent/id agent-id
                                                  :seon.agent.run/trigger :message
                                                  :seon.agent.run/turn-limit 5}))
                    run-id (:seon.agent.run/id opened)]
                (is (= :running (derived agent-id)))
                (is (= 5 (:seon.agent.run/turn-limit
                           (run/snapshot {:seon.agent.run/id run-id}))))
                (await (send-inbound! [:seon.user/id "user"] agent-id "more please" 0 :human))
                (let [ok? (await (wait-until
                                   (fn [] (= 6 (:seon.agent.run/turn-limit
                                                 (run/snapshot {:seon.agent.run/id run-id}))))
                                   3000 25))]
                  (testing "the running agent RENEWED (work bound bumped), no new run"
                    (is ok? "renew! bumped the work bound (the sliding window)")
                    (is (= [run-id] (runs-for agent-id))
                        "still exactly ONE run — :running wakes renew, never open")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest wake-refuses-hop-exhausted-but-wakes-a-fresh-chain
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [cs (await (boot-agent!))]
              ;; a peer agent entity so the hop-exhausted sender's `from`
              ;; lookup-ref resolves (no trigger installed for it).
              (await (db/transact! {:seon.db/tx-data [{:seon.agent/id "AGTpeerrun0001"}]}))
              (loop/install-wake-trigger!
                {:seon.agent/id            agent-id
                 :seon.agent/llm-fn        (scripted-llm "(complete \"ok\")")
                 :seon.agent/compile-state cs})
              (testing "a hop-CAP message wakes NOTHING (loud refusal, no run)"
                (await (send-inbound! [:seon.agent/id "AGTpeerrun0001"] agent-id
                                      "ping-pong" warn/hop-cap :agent))
                ;; the exhausted branch schedules NO setTimeout; a short poll
                ;; confirms no run materialized on the macrotask queue either.
                (await (wait-until (constantly false) 200 25))
                (is (= [] (runs-for agent-id))
                    "hop-exhausted ⇒ refused at wake; no run opened")
                (is (= :idle (derived agent-id))))
              (testing "a fresh hops=0 human message DOES wake (opens + drives)"
                (await (send-inbound! [:seon.user/id "user"] agent-id "fresh start" 0 :human))
                (let [ok? (await (wait-until (fn [] (seq (runs-for agent-id))) 8000 25))]
                  (is ok? "the hops=0 message opened a run")
                  (is (= 1 (count (runs-for agent-id)))
                      "exactly the one fresh run (the exhausted message opened none)"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
