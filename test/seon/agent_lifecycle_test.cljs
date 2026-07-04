(ns seon.agent-lifecycle-test
  "Agent lifecycle for the RUN MODEL (feature/agent-fsm cutover): state is
   DERIVED from the agent's primitives (terminated-at / open run / paused-at)
   via `seon.derive/derive-state` — there is NO stored `:seon.agent/state`. The
   lifecycle verbs MUTATE the run; the derived state follows.

   Pins the invariants the boot + loop paths depend on:

     - the lifecycle VERBS — `wait` / `complete` / `pause` / `resume` /
       `terminate`. `wait`/`complete`/`pause`/`resume` are ALS-scoped (default
       to `(db/current-agent-id)`) and act on the agent's OPEN run; `terminate`
       takes an explicit id and sets `:seon.agent/terminated-at`. Each returns
       the new DERIVED state keyword. `wait` with no agent in scope returns a
       loud error envelope (errors are values).
     - the RESUME query is `agent/armable-agent-ids` — every agent whose
       DERIVED state is `:idle` (not terminated AND no open run), sorted; empty
       store = [] (genuine first boot); a `:terminated` or `:running` agent is
       excluded.
     - `agent/create!` roundtrips `:seon.agent/default-turn-limit`; purpose is
       never defaulted; a failed transact returns the db error envelope.
     - `agent/boot!` propagates create!'s error envelope (no ghost agent).

   Tests open a FRESH `:memory` conn (via `seon.client/open-agent-conn!`,
   the same boot helper the pod uses) — nothing here touches the live agents."
  (:require
    [cljs.test :refer [deftest is testing async]]
    [seon.agent :as agent]
    [seon.agent.lifecycle :as lifecycle]
    [seon.agent.message :as msg]
    [seon.agent.run :as run]
    [seon.client :as client]
    [seon.db :as db]))

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

(defn- seed-agents!
  "Transact two bare agent entities (id only — derived :idle) + THE user
   entity (complete's no-parent delivery target)."
  []
  (db/transact!
    {:seon.db/tx-data [{:seon.agent/id "aaa-2606101200"}
                       {:seon.agent/id "bbb-2606101200"}
                       {:seon.user/id "user"}]}))

(defn- user-messages-from
  "Contents of messages FROM agent `id` TO the user, in db order."
  [id]
  (let [db      @db/*conn*
        agent-e (:db/id (db/entity {:seon.db/db db
                                    :seon.db/ref [:seon.agent/id id]}))
        user-e  (:db/id (db/entity {:seon.db/db db
                                    :seon.db/ref [:seon.user/id "user"]}))]
    (->> (db/query {:seon.db/db db
                    :seon.db/query '[:find ?m ?c :in $ ?from ?to :where
                                     [?m :seon.agent.message/from ?from]
                                     [?m :seon.agent.message/to ?to]
                                     [?m :seon.agent.message/content ?c]]
                    :seon.db/args [agent-e user-e]})
         (sort-by first)
         (mapv second))))

(defn- derived [id]
  (:seon.agent/state (agent/derive-status {:seon.agent/id id})))

;; ============================================================
;; Lifecycle verbs — wait / complete / pause / resume / terminate MUTATE the
;; run; the derived state follows. Each returns the new derived state keyword.
;; ============================================================

(deftest verbs-mutate-the-run-and-default-to-scoped-agent
  (async done
    (-> (with-conn
          (fn ^:async run []
            (await (seed-agents!))
            (testing "a bare agent with no run is DERIVED :idle"
              (is (= :idle (derived "aaa-2606101200"))))
            (testing "wait closes the SCOPED agent's open run :waited → :idle"
              (await (run/open-run! {:seon.agent/id "aaa-2606101200"
                                     :seon.agent.run/trigger :message}))
              (is (= :running (derived "aaa-2606101200")) "an open run ⇒ :running")
              (let [r (await (db/with-agent "aaa-2606101200"
                               (fn ^:async w [] (await (lifecycle/wait "need an answer")))))]
                (is (= :idle r) "wait returns the new derived state")
                (is (= :idle (derived "aaa-2606101200")) "the run closed → :idle")))
            (testing "complete closes the SCOPED agent's run :completed → :idle
                      AND (no parent) DELIVERS the result to the human"
              (await (run/open-run! {:seon.agent/id "bbb-2606101200"
                                     :seon.agent.run/trigger :message}))
              (let [r (await (db/with-agent "bbb-2606101200"
                               (fn ^:async c [] (await (lifecycle/complete "done")))))]
                (is (= :idle r))
                (is (= :idle (derived "bbb-2606101200")))
                (is (= ["done"] (user-messages-from "bbb-2606101200"))
                    "a parentless complete messages its result string to the
                     user — the string is delivered, never discarded")))
            (testing "a BLANK complete result delivers nothing and just closes"
              (await (run/open-run! {:seon.agent/id "bbb-2606101200"
                                     :seon.agent.run/trigger :message}))
              (let [r (await (db/with-agent "bbb-2606101200"
                               (fn ^:async c [] (await (lifecycle/complete "  ")))))]
                (is (= :idle r) "blank result still closes the run cleanly")
                (is (= ["done"] (user-messages-from "bbb-2606101200"))
                    "no second (blank) message was stored")))
            (testing "a message already sent THIS RUN suppresses complete's
                      delivery — the prior message IS the answer (no clobber)"
              (await (run/open-run! {:seon.agent/id "bbb-2606101200"
                                     :seon.agent.run/trigger :message}))
              (let [r (await (db/with-agent "bbb-2606101200"
                               (fn ^:async c []
                                 (await (msg/user "the answer"))
                                 (await (lifecycle/complete "filler")))))]
                (is (= :idle r) "complete still closes the run cleanly")
                (is (= :idle (derived "bbb-2606101200")))
                (is (= ["done" "the answer"] (user-messages-from "bbb-2606101200"))
                    "exactly ONE message landed this run — the filler complete
                     string was NOT sent (the earlier message is the delivered
                     answer; last-message-wins can no longer clobber it)")))
            (testing "pause/resume HOLD the run without killing it"
              (await (run/open-run! {:seon.agent/id "bbb-2606101200"
                                     :seon.agent.run/trigger :message}))
              (let [p (await (db/with-agent "bbb-2606101200"
                               (fn ^:async pz [] (await (lifecycle/pause)))))]
                (is (= :paused p) "pause returns :paused")
                (is (= :paused (derived "bbb-2606101200")) "open run + paused-at ⇒ :paused"))
              (let [rs (await (db/with-agent "bbb-2606101200"
                                (fn ^:async rz [] (await (lifecycle/resume)))))]
                (is (= :running rs) "resume returns :running")
                (is (= :running (derived "bbb-2606101200")) "paused-at cleared ⇒ :running")))
            (testing "terminate sets terminated-at → :terminated (the unwakeable state)"
              (let [r (await (lifecycle/terminate "aaa-2606101200"))]
                (is (= :terminated r))
                (is (= :terminated (derived "aaa-2606101200")))))
            (testing "wait with no agent in scope → loud error envelope (errors are values)"
              (let [env (await (lifecycle/wait "orphan"))]
                (is (false? (:seon.db/ok? env)))
                (is (string? (:seon.error/message (:seon.db/error env))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; pause/resume budget — pause BANKS deadline−now on :remaining-ms; resume
;; re-extends the deadline by the BANKED budget (not the default window), and
;; the paused derive-status surfaces the frozen budget (not deadline−now).
;; ============================================================

(deftest pause-banks-remaining-ms-and-resume-re-extends-by-it
  (async done
    (-> (with-conn
          (fn ^:async run []
            (await (db/transact! {:seon.db/tx-data [{:seon.agent/id "ccc-2606101200"}]}))
            (testing "pause banks deadline−now; resume re-extends by the BANKED budget"
              (let [deadline (js/Date. (+ (.getTime (js/Date.)) 60000)) ; ~1 min out
                    opened   (await (run/open-run!
                                      {:seon.agent/id           "ccc-2606101200"
                                       :seon.agent.run/trigger  :message
                                       :seon.agent.run/deadline deadline}))
                    run-id   (:seon.agent.run/id opened)]
                (await (db/with-agent "ccc-2606101200"
                         (fn ^:async pz [] (await (lifecycle/pause)))))
                (let [r      (db/entity {:seon.db/ref [:seon.agent.run/id run-id]})
                      banked (:seon.agent.run/remaining-ms r)]
                  (testing "pause banked remaining-ms ≈ deadline − now"
                    (is (number? banked) "remaining-ms was banked at pause")
                    (is (<= 55000 banked 60000)
                        "banked ≈ 60s budget (allowing test execution slack)"))
                  (testing "the paused snapshot surfaces the FROZEN budget (FIX D)"
                    (let [snap (agent/derive-status {:seon.agent/id "ccc-2606101200"})]
                      (is (= banked (:seon.agent.run/ms-remaining snap))
                          "ms-remaining = banked budget while paused, not deadline−now")))
                  (testing "resume re-extends by the banked budget, NOT the 10-min default"
                    (let [before (.getTime (js/Date.))]
                      (await (db/with-agent "ccc-2606101200"
                               (fn ^:async rz [] (await (lifecycle/resume)))))
                      (let [r2     (db/entity {:seon.db/ref [:seon.agent.run/id run-id]})
                            new-dl (.getTime ^js (:seon.agent.run/deadline r2))
                            ext    (- new-dl before)]
                        (is (nil? (:seon.agent.run/paused-at r2)) "paused-at cleared")
                        (is (<= (- banked 5000) ext (+ banked 5000))
                            "deadline re-extended by the BANKED remaining-ms (~60s)")
                        (is (< ext 120000)
                            "NOT default-deadline-ms (600000) — a resume! that
                             re-extended by the default window FAILS this")))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; armable-agent-ids — derived :idle (not terminated AND no open run).
;; ============================================================

(deftest armable-is-derived-idle-only
  (async done
    (-> (with-conn
          (fn ^:async run []
            (testing "empty store = genuine first boot (mint path)"
              (is (= [] (agent/armable-agent-ids {:seon.db/db @db/*conn*}))))
            (await (seed-agents!))
            (testing "both bare agents (no run) are armable, sorted asc"
              (is (= ["aaa-2606101200" "bbb-2606101200"]
                     (agent/armable-agent-ids {:seon.db/db @db/*conn*}))))
            (testing "an agent with an OPEN run is :running → NOT armable"
              (await (run/open-run! {:seon.agent/id "aaa-2606101200"
                                     :seon.agent.run/trigger :message}))
              (is (= ["bbb-2606101200"]
                     (agent/armable-agent-ids {:seon.db/db @db/*conn*}))
                  "a running agent is mid-run, not wakeable"))
            (testing "closing the run re-arms it (back to derived :idle)"
              (await (db/with-agent "aaa-2606101200"
                       (fn ^:async w [] (await (lifecycle/wait "park")))))
              (is (= ["aaa-2606101200" "bbb-2606101200"]
                     (agent/armable-agent-ids {:seon.db/db @db/*conn*}))))
            (testing "a terminated agent is excluded (history, not roster)"
              (await (lifecycle/terminate "aaa-2606101200"))
              (is (= ["bbb-2606101200"]
                     (agent/armable-agent-ids {:seon.db/db @db/*conn*}))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; create! — default-turn-limit pass-through, honest error envelope,
;; purpose never defaulted.
;; ============================================================

(deftest create!-roundtrips-default-turn-limit
  (async done
    (-> (with-conn
          (fn ^:async run []
            (testing "the work bound in the request lands as entity data"
              (let [res (await (agent/create!
                                 {:seon.agent/id "AGTcapround001"
                                  :seon.agent/default-turn-limit 7}))]
                (is (= {:seon.agent/id "AGTcapround001"} res)
                    "success keeps the success shape")
                (is (= 7 (:seon.agent/default-turn-limit
                           (db/entity {:seon.db/ref [:seon.agent/id "AGTcapround001"]})))
                    "default-turn-limit is stored on the entity")))
            (testing "a new run seeds its turn-limit from the agent's default"
              (let [snap (await (run/open-run! {:seon.agent/id "AGTcapround001"
                                                :seon.agent.run/trigger :message}))]
                (is (= 7 (:seon.agent.run/turn-limit snap))
                    "the run's WORK bound = the agent's default-turn-limit")))
            (testing "absent default leaves the stored value unchanged (re-create)"
              (await (agent/create! {:seon.agent/id "AGTcapround001"}))
              (is (= 7 (:seon.agent/default-turn-limit
                         (db/entity {:seon.db/ref [:seon.agent/id "AGTcapround001"]})))
                  "idempotent re-create never retracts the default"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest create!-leaves-purpose-absent-unless-stated
  (async done
    (-> (with-conn
          (fn ^:async run []
            (testing "no stated purpose → attr ABSENT (optional = absent)"
              (await (agent/create! {:seon.agent/id "AGTpurpose0001"}))
              (is (nil? (:seon.agent/purpose
                          (db/entity {:seon.db/ref [:seon.agent/id "AGTpurpose0001"]})))
                  "no instruction-text default leaks to the customer tile"))
            (testing "blank purpose is treated as unstated"
              (await (agent/create! {:seon.agent/id "AGTpurpose0002"
                                     :seon.agent/purpose "   "}))
              (is (nil? (:seon.agent/purpose
                          (db/entity {:seon.db/ref [:seon.agent/id "AGTpurpose0002"]})))))
            (testing "an explicitly stated purpose still lands unchanged"
              (await (agent/create! {:seon.agent/id "AGTpurpose0003"
                                     :seon.agent/purpose "track Acme orders"}))
              (is (= "track Acme orders"
                     (:seon.agent/purpose
                       (db/entity {:seon.db/ref [:seon.agent/id "AGTpurpose0003"]})))
                  "the explicit-purpose param path is intact"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest create!-returns-the-error-envelope-on-failed-transact
  (async done
    (-> (with-conn
          (fn ^:async run []
            ;; Force the transact to FAIL via an invalid attr value —
            ;; :seon.agent/default-turn-limit is :int, a string is rejected
            ;; by the transact! validation gate.
            (let [res (await (agent/create!
                               {:seon.agent/id "AGTcapround002"
                                :seon.agent/default-turn-limit "not-an-int"}))]
              (is (false? (:seon.db/ok? res))
                  "failure returns the db error envelope, NOT the success
                   shape (errors are values, like message!)")
              (is (map? (:seon.db/error res)) "the error map rides along")
              (is (nil? (:seon.agent/id res))
                  "no success key on the failure path")
              (is (nil? (db/entity {:seon.db/ref [:seon.agent/id "AGTcapround002"]}))
                  "and indeed NO entity exists — anything downstream trusting
                   the old success shape chased a ghost"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; boot! — create!'s error envelope must propagate through the boot path.
;; ============================================================

(deftest boot!-propagates-create!-error-envelope
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [stub-llm (fn [_] (js/Promise.resolve {:text ""}))]
              (testing "forced create! failure (id violates :seon.db/id — 14 chars)
                        surfaces the db error envelope"
                (let [res (await (agent/boot!
                                   {:seon.agent/id            "short"
                                    :seon.agent/llm-fn        stub-llm
                                    :seon.agent/compile-state nil}))]
                  (is (false? (:seon.db/ok? res))
                      "boot! hands the create! envelope up — errors are values;
                       no nil id leaks downstream")
                  (is (map? (:seon.db/error res)) "the error map rides along")
                  (is (nil? (:seon.agent/ns res))
                      "no success keys on the failure path")))
              (testing "normal path unchanged"
                (let [res (await (agent/boot!
                                   {:seon.agent/id            "AGTbootok00001"
                                    :seon.agent/llm-fn        stub-llm
                                    :seon.agent/compile-state nil}))]
                  (is (= "AGTbootok00001" (:seon.agent/id res)))
                  (is (= 'my.agent.AGTbootok00001 (:seon.agent/ns res)))
                  (is (some? (db/entity {:seon.db/ref [:seon.agent/id "AGTbootok00001"]}))
                      "the entity exists on the success path"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
