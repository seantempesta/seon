(ns seon.agent-loop-test
  "The RUN-MODEL loop — `seon.agent.loop/run-loop!` as a fold of
   `seon.agent.loop/transition` over events derived from the run's data. Drives the REAL
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
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing async use-fixtures]]
    [my.blob :as blob]
    [seon.agent :as agent]
    [seon.agent.home :as home]
    [seon.agent.loop :as loop]
    [seon.agent.message :as msg]
    [seon.agent.run :as run]
    [seon.agent.runtime :as runtime]
    [seon.agent.schedule :as schedule]
    [seon.ai.dispatch :as dispatch]
    [seon.client :as client]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.derive :as derive]
    [seon.eval :as seval]
    [seon.repl :as repl]
    [seon.repl.internal :as repl-internal]
    [seon.test-seed :as test-seed]
    [seon.warn :as warn]))

;; run-turn!'s ALWAYS-ON blob capture must not write into the live
;; cluster's blob dir from a hermetic test — repoint the storage view at a
;; pid-scoped tmp dir for this ns and restore after.
(def ^:private blob-fixture-dir (str "tmp/loop-test-blobs-" (.-pid js/process)))

(defonce ^:private !saved-blob-storage-view (atom nil))

(def ^:private agent-id "AGTlooprun0001")          ; 14 chars (:seon.db/id)

(use-fixtures :once
  {:before (fn []
             (reset! !saved-blob-storage-view @blob/!storage-view)
             (reset! blob/!storage-view
                     {:my.blob/writable-dir blob-fixture-dir
                      :my.blob/read-only-dirs []}))
   :after  (fn []
             (reset! blob/!storage-view @!saved-blob-storage-view)
             (-> (js/require "node:fs")
                 (.rmSync blob-fixture-dir #js {:recursive true :force true})))})

(defn- with-conn
  [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (let [prev db/*conn*]
                 (set! db/*conn* conn)
                 ;; the my.* slice of the boot index — SCI bounding is
                 ;; fail-loud, so the default ctx blocks' my.* render fns
                 ;; need their stored source rows to render BOUNDED here.
                 (-> (db/transact! {:seon.db/tx-data (test-seed/my-core-rows)})
                     (.then (fn [_] (body)))
                     (.finally
                       (fn []
                         (loop/uninstall-wake-trigger!
                           {:seon.agent/id agent-id})
                         (set! db/*conn* prev)))))))))

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
  "Fresh isolated database: user fact plus an atomically born and resumed
   agent. Returns the compile-state."
  []
  (let [cs (await (repl/ensure-bootstrap!))]
    (await (db/transact! {:seon.db/tx-data [{:seon.user/id "user"}]}))
    (await (agent/create! {:seon.agent/id agent-id}))
    (await (db/with-agent agent-id
             (fn ^:async boot []
               (await (seval/setup-agent-ns! cs (home/home-ns agent-id) agent-id)))))
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

(defn- wake-work-settled?
  "True after an agent's wake runs and all of its turns finish closing."
  [id]
  (let [run-ids  (runs-for id)
        turn-eids (turns-for id)]
    (and (seq run-ids)
         (seq turn-eids)
         (every? (fn [run-id]
                   (= :closed
                      (:seon.agent.run/status
                        (run/snapshot {:seon.agent.run/id run-id}))))
                 run-ids)
         (every? (fn [turn-eid]
                   (contains? #{:done :error}
                              (:seon.agent.turn/status
                                (db/entity {:seon.db/ref turn-eid}))))
                 turn-eids))))

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
  "Allocate and transact a fully formed inbound message while preserving the
   test-controlled sender, hops, and origin."
  [from to-agent-id content hops origin]
  (await
    (db.id/allocate!
      {::db.id/allocations
       [{::db.id/key ::inbound-message
         ::db.id/identity-attr :seon.agent.message/id}]
       ::db.id/transaction-builder
       (fn [ids]
         {:seon.db/tx-data
          [{:seon.agent.message/id      (get ids ::inbound-message)
            :seon.agent.message/from    from
            :seon.agent.message/to      [[:seon.agent/id to-agent-id]]
            :seon.agent.message/content content
            :seon.agent.message/at      (js/Date.)
            :seon.agent.message/hops    hops
            :seon.agent.message/origin  origin}]})
       :seon.db/conn db/*conn*})))

(defn ^:async allocate-turn!
  "Allocate one realistic running turn attached to `run-id`."
  [run-id]
  (let [env
        (await
          (db.id/allocate!
            {::db.id/allocations
             [{::db.id/key ::turn
               ::db.id/identity-attr :seon.agent.turn/id}]
             ::db.id/transaction-builder
             (fn [ids]
               {:seon.db/tx-data
                [{:seon.agent.turn/id (get ids ::turn)
                  :seon.agent.turn/at (js/Date.)
                  :seon.agent.turn/status :running
                  :seon.agent.turn/run [:seon.agent.run/id run-id]}]})
             :seon.db/conn db/*conn*}))]
    (get-in env [::db.id/ids ::turn])))

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
                (testing "run A is no longer the agent's current run (B is)"
                  (is (not= a-id (:seon.agent.run/id
                                   (run/current-run {:seon.agent/id agent-id})))
                      "the agent's pointer moved to the newer run B"))
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
;; WORK FENCE (§8b) — eval-batch! LEADS with an in-tx CAS; a superseded run's
;; batch is rejected at the START (skipped, nothing recorded); the CURRENT
;; run's batch commits. This is the F14 closure: the unit of WORK is fenced,
;; not just lifecycle bookkeeping.
;; ============================================================

(deftest superseded-runs-eval-batch-is-fenced-current-runs-commits
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [cs (await (boot-agent!))
                  r1 (:seon.agent.run/id
                       (await (run/open-run! {:seon.agent/id agent-id
                                              :seon.agent.run/trigger :message})))
                  ;; supersede: r2 owns the agent now; r1 is orphaned (open, but
                  ;; the pointer moved).
                  r2 (:seon.agent.run/id (await (supersede! agent-id)))
                  turn1 (await (allocate-turn! r1))
                  turn2 (await (allocate-turn! r2))]
              (testing "no eval rows exist before either batch"
                (is (empty? (db/query {:seon.db/query
                                       '[:find [?e ...] :where [?e :seon.eval/id _]]}))))
              ;; eval-batch! for the OLD run r1 — the leading CAS fails (pointer
              ;; → r2), so the whole batch is SKIPPED.
              (let [fenced (await (db/with-agent agent-id
                                    (fn ^:async eb []
                                      (await (seval/eval-batch!
                                               cs (repl-internal/parse-forms "(def fenced-marker 42)")
                                               (home/home-ns agent-id)
                                               agent-id turn1 r1)))))]
                (testing "the superseded run's batch is fenced — skipped, nothing recorded"
                  (is (true? (:seon.eval/fenced? fenced)))
                  (is (= 0 (:seon.eval/n-ok fenced)))
                  (is (empty? (:seon.eval/ids fenced)))
                  (is (empty? (db/query {:seon.db/query
                                         '[:find [?e ...] :where [?e :seon.eval/id _]]}))
                      "the fenced batch landed ZERO :seon.eval datoms")))
              ;; eval-batch! for the CURRENT run r2 commits normally.
              (let [ok-batch (await (db/with-agent agent-id
                                      (fn ^:async eb2 []
                                        (await (seval/eval-batch!
                                                 cs (repl-internal/parse-forms "(+ 1 1)")
                                                 (home/home-ns agent-id)
                                                 agent-id turn2 r2)))))]
                (testing "the CURRENT run's batch is NOT fenced — it commits"
                  (is (nil? (:seon.eval/fenced? ok-batch)))
                  (is (= 1 (:seon.eval/n-ok ok-batch)))
                  (is (= 1 (count (db/query {:seon.db/query
                                             '[:find [?e ...] :where [?e :seon.eval/id _]]})))
                      "exactly the current run's one eval row landed"))))))
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
              (await (send-inbound! [:seon.user/id "user"]
                                    agent-id "wake up" 0 :human))
              ;; the handler schedules setTimeout(0) → open-run! + run-loop!;
              ;; the scripted (complete …) closes the run on turn 1.
              (let [ok? (await (wait-until
                                 (fn [] (wake-work-settled? agent-id))
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

;; ============================================================
;; SPAWN + RESUME — `agent/start!` returns the committed child id and resumes
;; its runtime before returning, so a message sent immediately afterward wakes
;; it. There is no callback seam or out-of-band arm sweep.
;; ============================================================

(deftest start!-returns-child-id-and-arms-it-so-a-message-wakes-it
  (async done
    (-> (with-conn
          (fn ^:async run []
            (await (db/transact! {:seon.db/tx-data [{:seon.user/id "user"}]}))
            (with-redefs [dispatch/llm-fn
                          (fn [] (scripted-llm "(complete \"ok\")"))]
              ;; Spawn with NO id — start! MINTS one and must return it. (No
              ;; agent scope ⇒ parentless child.)
              (await
                (db/without-agent
                  (fn ^:async host-start! []
                    (await
                      (db/with-tx-context
                        {:seon.db/user nil
                         :seon.db/process nil}
                        (fn ^:async mint-message-and-observe! []
                          (is (nil? (db/current-agent-id))
                              "host start has no calling agent")
                          (let [res      (await
                                           (agent/start!
                                             {:seon.agent/purpose
                                              "spawn-arm test"}))
                                child-id (:seon.agent/id res)]
                            (try
                              (testing "start! returns the minted child id (addressable same-turn)"
                                (is (string? child-id) "response carries :seon.agent/id")
                                (is (some? (db/entity
                                             {:seon.db/ref [:seon.agent/id child-id]}))
                                    "the returned id names a real entity — not a ghost"))
                              (testing "the minted child is ARMED in-process: a message wakes it"
                                (is (= [] (runs-for child-id))
                                    "idle, no run before any message")
                                (await (send-inbound! [:seon.user/id "user"]
                                                      child-id "wake up" 0 :human))
                                (let [woke? (await (wait-until
                                                     (fn []
                                                       (wake-work-settled? child-id))
                                                     8000 25))]
                                  (is woke?
                                      (str "a message sent immediately after start! opened a run "
                                           "on the freshly-spawned child — no out-of-band "
                                           "resume"))
                                  (when woke?
                                    (let [run-id (first (runs-for child-id))]
                                      (is (= #{[child-id]}
                                             (db/query
                                               {:seon.db/query
                                                '[:find ?user-id
                                                  :in $ ?run-id
                                                  :where
                                                  [?run :seon.agent.run/id ?run-id ?tx]
                                                  [?tx :seon.db/user ?user]
                                                  [?user :seon.agent/id ?user-id]
                                                  [?tx :seon.db/process ?process]
                                                  [?process :seon.db.process/id
                                                   :seon.db.process/repl]]
                                                :seon.db/args [run-id]}))
                                          "the wake transaction belongs to the child through REPL")))))
                              (finally
                                (runtime/unhost!
                                  {:seon.agent/id child-id})))))))))))))
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
                (await (send-inbound! [:seon.user/id "user"]
                                      agent-id "more please" 0 :human))
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
                (await (send-inbound! [:seon.user/id "user"]
                                      agent-id "fresh start" 0 :human))
                (let [ok? (await (wait-until
                                   (fn [] (wake-work-settled? agent-id))
                                   8000 25))]
                  (is ok? "the hops=0 message opened a run")
                  (is (= 1 (count (runs-for agent-id)))
                      "exactly the one fresh run (the exhausted message opened none)"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ── The FSM transition table — pure, no db. It lives WITH the loop that
;;    folds it ([[seon.agent.loop/transition]]). ──

(deftest transition-follows-the-table
  (is (= :running    (loop/transition :idle :trigger)))
  (is (= :running    (loop/transition :running :turn-ok)))
  (is (= :idle       (loop/transition :running :wait)))
  (is (= :idle       (loop/transition :running :complete)))
  (is (= :idle       (loop/transition :running :turn-limit)))
  (is (= :idle       (loop/transition :running :deadline)))
  (is (= :idle       (loop/transition :running :superseded)))
  (is (= :idle       (loop/transition :running :error)))
  (is (= :idle       (loop/transition :running :no-forms)) "empty-streak halt closes clean")
  (is (= :paused     (loop/transition :running :pause)))
  (is (= :terminated (loop/transition :running :terminate)))
  (is (= :running    (loop/transition :paused :resume)))
  (is (= :terminated (loop/transition :paused :terminate))))

(deftest unknown-event-leaves-the-state-unchanged
  (is (= :running    (loop/transition :running :resume)) "resume isn't valid in running")
  (is (= :idle       (loop/transition :idle :complete)) "complete isn't valid in idle")
  (is (= :paused     (loop/transition :paused :turn-ok)) "turn-ok isn't valid in paused")
  (is (= :terminated (loop/transition :terminated :trigger)) "terminal absorbs everything")
  (is (= :terminated (loop/transition :terminated :resume))))

;; ── Cron ACTION — a due schedule's fn actually RUNS when it fires, and the
;;    fire does NOT burn a turn (#66) ────────────────────────────────────────
;;    fire-due-schedules! hands the due fns to exec-scheduled-fns!, which
;;    eval-batches them as a SCHEDULE-FIRE turn on the opened run. Two things
;;    are pinned:
;;      1. EXECUTION — the fn's invocation lands as an OK :seon.eval the agent
;;         re-reads (real path, not a stub), AND a turn was created on the run
;;         (so the eval RENDERS in the transcript via the run→turn→evals walk).
;;      2. NO TURN BURNED (#66) — the schedule-fire turn is stamped
;;         `:seon.agent.turn/scheduled? true`, so `derive/run-turn-count` (the
;;         WORK budget the loop checks vs turn-limit) is 0 even though a raw
;;         turn DID land. Before the fix run-turn-count counted it → every cron
;;         tick stole an LLM turn and the run hit turn-limit having done no work.
;;    DETERMINISTIC: an explicit fixed `now` (the cron is `* * * * *`, due at any
;;    instant), so the test never races a wall-clock minute boundary.

(deftest schedule-fire-executes-the-scheduled-fn
  (async done
    (let [now (js/Date. "2026-06-28T12:00:00.000Z")]
      (-> (with-conn
            (fn []
              (-> (boot-agent!)
                  ;; A due-every-minute schedule whose fn is a resolvable 0-arg
                  ;; fn (host-timezone) — eval'ing `(…)` returns a value iff RAN.
                  (.then (fn [_]
                           (db/transact!
                             {:seon.db/tx-data
                              [{:seon.agent/id agent-id
                                :seon.agent/schedules
                                [{:seon.agent.schedule/id  "sched-fire0001"   ; exactly 14 chars
                                  :seon.agent.schedule/cron "* * * * *"
                                  :seon.agent.schedule/fn   'seon.agent.schedule/host-timezone}]}]})))
                  (.then (fn [res]
                           (is (not (false? (:seon.db/ok? res))) "schedule attached")))
                  (.then (fn [_]
                           ;; Real executor injected (no LLM drive): exec-only.
                           (schedule/fire-due-schedules!
                             {:seon.agent/now               now
                              :seon.agent.schedule/exec-fn! loop/exec-scheduled-fns!})))
                  (.then (fn [res]
                           (is (= 1 (count (:seon.agent.schedule/fired res)))
                               "fired one :schedule run for the idle agent")
                           (let [cur    (run/current-run {:seon.agent/id agent-id})
                                 run-id (:seon.agent.run/id cur)]
                             (is (= :schedule (:seon.agent.run/trigger cur))
                                 "the open run is :schedule-triggered")
                             (is (pos? (count (turns-for agent-id)))
                                 "exec opened a turn on the run (so its eval renders)")
                             ;; #66 — the schedule-fire turn must NOT count toward
                             ;; turn-limit. A raw count sees 1 turn; run-turn-count
                             ;; (the WORK budget) EXCLUDES the scheduled? turn → 0.
                             (is (= 1 (turn-count run-id))
                                 "a turn DID land on the run (raw count)")
                             (is (= 0 (derive/run-turn-count @db/*conn* run-id))
                                 "but run-turn-count EXCLUDES it — the fire burns no work turn (#66)")
                             (let [rows (or (db/query {:seon.db/query
                                                       '[:find ?src ?ok
                                                         :where
                                                         [?e :seon.eval/source ?src]
                                                         [?e :seon.eval/ok? ?ok]]})
                                            [])
                                   hit  (some (fn [[src ok]]
                                                (when (str/includes?
                                                        src "seon.agent.schedule/host-timezone")
                                                  ok))
                                              rows)]
                               (is (true? hit)
                                   "the scheduled fn RAN — its invocation recorded as an OK :seon.eval"))))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "schedule-fire test threw — " e)) (done)))))))

;; ============================================================
;; ASYNC-PARK bounds — the wedge class closed by racing the loop's awaits
;; through seon.eval/race-timeout (the ONE racer):
;;   1. per-ATTEMPT (SEON_LLM_ATTEMPT_TIMEOUT_MS): a never-settling adapter
;;      attempt yields a timed-out :seon.ai/error VALUE → turn :error → the
;;      loop closes the run :error. The loop RETURNS — never parks.
;;   2. per-TURN (SEON_TURN_TIMEOUT_MS): a hung run-turn! frees the awaiter
;;      with a :seon/error value → the loop closes the run :error instead of
;;      parking until the deadline reaper fences it.
;; Neither bound cancels the underlying work (no preemption) — a late
;; settler's run-scoped writes are aborted by the in-tx CAS work-fence.
;; ============================================================

(defn- never-settling-llm
  "ctx-string -> a Promise that NEVER settles (the park the bounds must free)."
  [_ctx]
  (js/Promise. (fn [_ _])))

(defn- set-env!
  "Set/unset `process.env[k]` — `v` string sets, nil deletes. Returns prior."
  [k v]
  (let [prior (aget (.-env js/process) k)]
    (if (some? v)
      (aset (.-env js/process) k v)
      (js-delete (.-env js/process) k))
    prior))

(deftest never-settling-llm-attempt-times-out-run-closes-error
  (async done
    (let [prior (set-env! "SEON_LLM_ATTEMPT_TIMEOUT_MS" "60")]
      (-> (with-conn
            (fn ^:async run []
              (let [cs     (await (boot-agent!))
                    opened (await (run/open-run! {:seon.agent/id agent-id
                                                  :seon.agent.run/trigger :message}))
                    run-id (:seon.agent.run/id opened)
                    final  (await (db/with-agent agent-id
                                    (fn ^:async drive []
                                      (await (loop/run-loop!
                                               {:seon.agent/id            agent-id
                                                :seon.agent/llm-fn        never-settling-llm
                                                :seon.agent/compile-state cs}
                                               run-id)))))]
                (testing "the loop RETURNS (never parks) and lands :idle"
                  (is (= :idle final)))
                (testing "the run closed :error — the timed-out attempt became a value"
                  (is (= :error (:seon.agent.run/closed-reason
                                  (run/snapshot {:seon.agent.run/id run-id})))))
                (testing "the one turn recorded :error (the :seon.ai/error surfaced)"
                  (is (= [:error]
                         (db/query {:seon.db/query
                                    '[:find [?s ...] :in $ ?r
                                      :where
                                      [?run :seon.agent.run/id ?r]
                                      [?t :seon.agent.turn/run ?run]
                                      [?t :seon.agent.turn/status ?s]]
                                    :seon.db/args [run-id]})))))))
          (.finally (fn [] (set-env! "SEON_LLM_ATTEMPT_TIMEOUT_MS" prior)))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest hung-run-turn-hits-per-turn-bound-run-closes-error
  (async done
    ;; Loop bound 80ms fires FIRST; the attempt cap (250ms) exists only so the
    ;; background attempt settles (as a timed-out error) before the test conn
    ;; is torn down — the drain wait below absorbs its late, fenced writes.
    (let [prior-turn (set-env! "SEON_TURN_TIMEOUT_MS" "80")
          prior-llm  (set-env! "SEON_LLM_ATTEMPT_TIMEOUT_MS" "250")]
      (-> (with-conn
            (fn ^:async run []
              (let [cs     (await (boot-agent!))
                    opened (await (run/open-run! {:seon.agent/id agent-id
                                                  :seon.agent.run/trigger :message}))
                    run-id (:seon.agent.run/id opened)
                    final  (await (db/with-agent agent-id
                                    (fn ^:async drive []
                                      (await (loop/run-loop!
                                               {:seon.agent/id            agent-id
                                                :seon.agent/llm-fn        never-settling-llm
                                                :seon.agent/compile-state cs}
                                               run-id)))))]
                (testing "the loop RETURNS at the per-turn bound and lands :idle"
                  (is (= :idle final)))
                ;; Under suite load close-run!'s OWN awaiter can be freed by
                ;; the same 80ms bound (the loop bounds EVERY await, close
                ;; included) — the close tx still lands: nothing moved the
                ;; pointer, so its CAS work-fence passes. Await the settle,
                ;; then assert the OUTCOME, never the timing.
                (await (wait-until
                         #(some? (:seon.agent.run/closed-reason
                                   (run/snapshot {:seon.agent.run/id run-id})))
                         2000 25))
                (testing "the run closed :error (not parked until the deadline reaper)"
                  (is (= :error (:seon.agent.run/closed-reason
                                  (run/snapshot {:seon.agent.run/id run-id})))))
                ;; Drain: let the still-running turn's attempt time out (250ms)
                ;; and its late writes hit the CAS fence while THIS conn is
                ;; still installed (never the next test's database).
                (await (js/Promise. (fn [res] (js/setTimeout res 400)))))))
          (.finally (fn []
                      (set-env! "SEON_TURN_TIMEOUT_MS" prior-turn)
                      (set-env! "SEON_LLM_ATTEMPT_TIMEOUT_MS" prior-llm)))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; ============================================================
;; OUTCOME NOTICE → REAL WAKE (multiagent-context Piece 2b, Gap B) — the
;; end-to-end proof: an abnormal close of a CHILD run sends a notice that
;; drives the PARENT'S real wake trigger — the parent's run count INCREASES
;; (a new run actually opens), not just a message-datom existence check.
;; ============================================================

(deftest child-outcome-notice-wakes-the-parent-end-to-end
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [cs       (await (boot-agent!))
                  child-id "AGTloopchild01"]
              ;; parent = agent-id (booted, idle); arm its REAL wake trigger.
              (loop/install-wake-trigger!
                {:seon.agent/id            agent-id
                 :seon.agent/llm-fn        (scripted-llm "(complete \"noted child outcome\")")
                 :seon.agent/compile-state cs})
              ;; child, parent ref set (raw transact — spawn path not under test)
              (await (db/transact!
                       {:seon.db/tx-data [{:seon.agent/id     child-id
                                           :seon.agent/parent [:seon.agent/id agent-id]}]}))
              (is (= [] (runs-for agent-id)) "parent has NO run before the outcome")
              ;; open a run on the child and close it ABNORMALLY via the one
              ;; choke point — this fires the Piece 2b parent notice.
              (let [snap (await (run/open-run! {:seon.agent/id child-id
                                                :seon.agent.run/trigger :message}))]
                (await (run/close-run! {:seon.agent.run/id (:seon.agent.run/id snap)
                                        :seon.agent.run/closed-reason :turn-limit})))
              ;; the notice must DRIVE the parent's wake: a run actually opens.
              (let [woke? (await (wait-until
                                   (fn [] (wake-work-settled? agent-id))
                                   8000 25))
                    rids  (runs-for agent-id)]
                (is woke? "the outcome notice OPENED a run on the parent (real wake path)")
                (is (= 1 (count rids)) "parent run count 0 → 1")
                (testing "the parent run's CAUSE is the outcome notice itself"
                  (let [run-ent (db/entity {:seon.db/ref [:seon.agent.run/id (first rids)]})
                        cause   (some-> (:db/id (:seon.agent.run/cause run-ent)) db/entity)]
                    (is (re-find #"turn-limit" (str (:seon.agent.message/content cause)))
                        "cause message carries the closed-reason")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; OUTCOME NOTICE AT THE HOP CAP (Piece 2b "delivery must be reliable") —
;; hops NEVER gate the TRANSACT (message! always stores; only WAKING is
;; hop-gated at the trigger). Pinned: with the {child,parent} pair seeded AT
;; the cap, an abnormal close still STORES its notice datom, but that notice
;; is NOT wake-eligible (hop-live? false) and the parent does not wake — the
;; loud hop-exhausted refusal. The datom is never lost; a parked parent at
;; the cap resumes on the next HUMAN contact (hops reset at the barrier).
;; ============================================================

(deftest outcome-notice-at-hop-cap-transacts-but-does-not-wake
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [cs       (await (boot-agent!))
                  child-id "AGTloopchild02"]
              (loop/install-wake-trigger!
                {:seon.agent/id            agent-id
                 :seon.agent/llm-fn        (scripted-llm "(complete \"never runs\")")
                 :seon.agent/compile-state cs})
              (await (db/transact!
                       {:seon.db/tx-data [{:seon.agent/id     child-id
                                           :seon.agent/parent [:seon.agent/id agent-id]}]}))
              ;; seed the {child,parent} pair AT the cap: a prior parent→child
              ;; message carrying hops = hop-cap ⇒ the child's next send to the
              ;; parent derives hops = cap + 1 (≥ cap ⇒ not wake-eligible).
              (await (send-inbound! [:seon.agent/id agent-id] child-id
                                    "pair at the cap" warn/hop-cap :agent))
              (let [snap (await (run/open-run! {:seon.agent/id child-id
                                                :seon.agent.run/trigger :message}))]
                (await (run/close-run! {:seon.agent.run/id (:seon.agent.run/id snap)
                                        :seon.agent.run/closed-reason :turn-limit})))
              ;; the notice datom EXISTS — transaction is never hop-gated.
              (let [notice-eid (db/query {:seon.db/query
                                          '[:find ?m . :in $ ?c
                                            :where
                                            [?ce :seon.agent/id ?c]
                                            [?m :seon.agent.message/from ?ce]]
                                          :seon.db/args [child-id]})
                    notice     (db/entity notice-eid)]
                (is (some? notice-eid) "the outcome notice TRANSACTED at the hop cap")
                (is (re-find #"turn-limit" (str (:seon.agent.message/content notice)))
                    "it is the outcome notice")
                (is (> (:seon.agent.message/hops notice) warn/hop-cap)
                    "its derived hops exceed the cap")
                (is (false? (msg/hop-live? notice))
                    "explicitly NOT wake-eligible — hops gate waking, not storage"))
              ;; and the parent does NOT wake (loud refusal at the trigger).
              (let [woke? (await (wait-until (fn [] (seq (runs-for agent-id))) 500 25))]
                (is (false? woke?) "no parent run opened — the wake was hop-refused")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
