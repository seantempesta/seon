(ns seon.agent-lifecycle-test
  "Agent lifecycle for the RUN MODEL (feature/agent-fsm cutover): state is
   DERIVED from the agent's primitives (terminated-at / open run / paused-at)
   via `seon.derive/derive-state` — there is NO stored `:seon.agent/state`. The
   lifecycle functions MUTATE the run; the derived state follows.

   Pins the invariants the boot + loop paths depend on:

     - the lifecycle FUNCTIONS — `wait` / `complete` / `pause` / `resume` /
       `terminate`. `wait`/`complete`/`pause`/`resume` are ALS-scoped (default
       to `(db/current-agent-id)`) and act on the agent's OPEN run; `terminate`
       takes an explicit id and sets `:seon.agent/terminated-at`. Each returns
       the new DERIVED state keyword. `wait` with no agent in scope returns a
       loud error envelope (errors are values).
     - `agent/armable-agent-ids` selects idle wake targets, while
       `agent/resumable-agent-ids` selects every nonterminated process host.
     - `agent/create!` roundtrips `:seon.agent/default-turn-limit`; purpose is
       never defaulted; a failed transact returns the db error envelope; and
       all initial agent/context/home-namespace facts share one transaction.

   Tests open a FRESH `:memory` conn (via `seon.client/open-agent-conn!`,
   the same boot helper the pod uses) — nothing here touches the live agents."
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing async]]
    [seon.agent :as agent]
    [seon.agent.lifecycle :as lifecycle]
    [seon.agent.message :as msg]
    [seon.agent.run :as run]
    [seon.agent.testrun :as testrun]
    [seon.client :as client]
    [seon.db :as db]
    [seon.db.replica :as replica]
    [seon.launch :as launch]))

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
  "Transact two born idle agents plus the human delivery target."
  []
  (db/transact!
    {:seon.db/tx-data [{:seon.agent/id "aaa-2606101200"
                        :seon.eval/home-requires []}
                       {:seon.agent/id "bbb-2606101200"
                        :seon.eval/home-requires []}
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

(deftest runtime-advertisement-uses-immutable-launch-writer-owner
  (let [descriptor
        (-> replica/default-launch-descriptor
            (assoc-in [::launch/runtime ::launch/runtime-cluster]
                      "default-proof")
            (assoc-in [::launch/writer-owner ::launch/writer-cluster]
                      "default")
            (assoc-in [::launch/writer-owner ::launch/writer-repl-port-file]
                      "tmp/source-writer.port"))]
    (with-redefs [replica/process-launch-descriptor descriptor
                  db/attached? (constantly false)]
      (is (= #:seon.dev.runtime-id
             {:cluster "default-proof"
              :ids []
              :seon.launch/writer-cluster "default"
              :seon.launch/writer-repl-port-file
              "tmp/source-writer.port"}
             (client/runtime-advertisement))))))

;; ============================================================
;; Lifecycle functions — wait / complete / pause / resume / terminate MUTATE the
;; run; the derived state follows. Each returns the new derived state keyword.
;; ============================================================

(deftest lifecycle-functions-mutate-the-run-and-default-to-scoped-agent
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
              (let [r (await (db/with-agent
                               "root"
                               (fn ^:async terminate-agent []
                                 (await (lifecycle/terminate
                                          "aaa-2606101200")))))]
                (is (= :terminated r))
                (is (= :terminated (derived "aaa-2606101200")))))
            (testing "wait with no agent in scope → loud error envelope (errors are values)"
              (let [env (await (lifecycle/wait "orphan"))]
                (is (false? (:seon.db/ok? env)))
                (is (string? (:seon.error/message (:seon.db/error env))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest root-and-ancestors-manage-targeted-run-lifecycle
  (async done
    (-> (with-conn
          (fn ^:async run-targeted []
            (await (seed-agents!))
            (await (db/transact!
                     {:seon.db/tx-data
                      [{:seon.agent/id "root"}
                       {:seon.agent/id "bbb-2606101200"
                        :seon.agent/parent
                        [:seon.agent/id "aaa-2606101200"]}]}))
            (await (run/open-run! {:seon.agent/id "bbb-2606101200"
                                   :seon.agent.run/trigger :message}))
            (testing "root can correct a target's purpose"
              (let [result
                    (await
                      (db/with-agent
                        "root"
                        (fn ^:async set-child-purpose []
                          (await
                            (agent/set-purpose!
                              {:seon.agent/id "bbb-2606101200"
                               :seon.agent/purpose "Own parser recovery"})))))]
                (is (true? (:seon.agent/ok? result)))
                (is (= "Own parser recovery"
                       (:seon.agent/purpose
                         (db/entity {:seon.db/ref
                                     [:seon.agent/id "bbb-2606101200"]}))))))
            (testing "root can pause and resume any ordinary agent"
              (is (= :paused
                     (await
                       (db/with-agent
                         "root"
                         (fn ^:async root-pause []
                           (await (lifecycle/pause
                                    {:seon.agent/id "bbb-2606101200"})))))))
              (is (= :running
                     (await
                       (db/with-agent
                         "root"
                         (fn ^:async root-resume []
                           (await (lifecycle/resume
                                    {:seon.agent/id "bbb-2606101200"}))))))))
            (testing "an ordinary agent cannot manage an ancestor"
              (let [purpose-result
                    (await
                      (db/with-agent
                        "bbb-2606101200"
                        (fn ^:async child-purpose-parent []
                          (await
                            (agent/set-purpose!
                              {:seon.agent/id "aaa-2606101200"
                               :seon.agent/purpose "wrong"})))))
                    result
                    (await
                      (db/with-agent
                        "bbb-2606101200"
                        (fn ^:async child-pause-parent []
                          (await (lifecycle/pause
                                   {:seon.agent/id "aaa-2606101200"})))))]
                (is (false? (:seon.agent/ok? purpose-result)))
                (is (false? (:seon.db/ok? result)))
                (is (= :idle (derived "aaa-2606101200")))))
            (testing "an ancestor can manage its descendant"
              (is (= :paused
                     (await
                       (db/with-agent
                         "aaa-2606101200"
                         (fn ^:async parent-pause []
                           (await (lifecycle/pause
                                    {:seon.agent/id "bbb-2606101200"}))))))))
            (testing "root is a protected cluster identity"
              (let [result
                    (await
                      (db/with-agent
                        "root"
                        (fn ^:async terminate-root []
                          (await (lifecycle/terminate "root")))))]
                (is (false? (:seon.db/ok? result)))
                (is (= :idle (derived "root")))))
            (testing "clearing agent scope grants no host management authority"
              (let [purpose-result
                    (await
                      (db/with-agent
                        "bbb-2606101200"
                        (fn ^:async clear-purpose-scope []
                          (await
                            (db/without-agent
                              (fn ^:async no-agent-purpose []
                                (await
                                  (agent/set-purpose!
                                    {:seon.agent/id "aaa-2606101200"
                                     :seon.agent/purpose "bypassed"}))))))))
                    terminate-result
                    (await
                      (db/with-agent
                        "bbb-2606101200"
                        (fn ^:async clear-terminate-scope []
                          (await
                            (db/without-agent
                              (fn ^:async no-agent-terminate []
                                (await
                                  (lifecycle/terminate
                                    "aaa-2606101200"))))))))]
                (is (false? (:seon.agent/ok? purpose-result)))
                (is (false? (:seon.db/ok? terminate-result)))
                (is (not= "bypassed"
                          (:seon.agent/purpose
                            (db/entity {:seon.db/ref
                                        [:seon.agent/id "aaa-2606101200"]}))))
                (is (= :idle (derived "aaa-2606101200")))))
            (testing "root cannot mint a ghost by targeting an unknown id"
              (let [unknown "ghost-agent"
                    purpose-result
                    (await
                      (db/with-agent
                        "root"
                        (fn ^:async unknown-purpose []
                          (await
                            (agent/set-purpose!
                              {:seon.agent/id unknown
                               :seon.agent/purpose "should not exist"})))))
                    terminate-result
                    (await
                      (db/with-agent
                        "root"
                        (fn ^:async unknown-terminate []
                          (await (lifecycle/terminate unknown)))))]
                (is (false? (:seon.agent/ok? purpose-result)))
                (is (false? (:seon.db/ok? terminate-result)))
                (is (nil? (:seon.agent/id
                            (db/entity {:seon.db/ref
                                        [:seon.agent/id unknown]}))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "targeted lifecycle threw: " error))
                  (done))))))

;; ============================================================
;; complete-gate — a SUCCESS claim is refused while the agent's latest real
;; test run is RED. Derived from the agent's own :seon.agent.testrun datoms
;; (no stored gate flag); scoped so a non-test agent (no testrun) completes
;; normally. Fabrication fix: a fabricated "all pass" + complete in one reply
;; is caught because the real red testrun datom persisted BEFORE complete evals.
;; ============================================================

(defn- run-result
  "A recognized `::result` map with the given failed/errors counts."
  [failed errors]
  {:seon.agent.testrun/ok?       true
   :seon.agent.testrun/framework :pytest
   :seon.agent.testrun/passed    3
   :seon.agent.testrun/failed    failed
   :seon.agent.testrun/errors    errors
   :seon.agent.testrun/failures
   (if (pos? (+ failed errors))
     [{:seon.agent.testrun/test-name "test_x"
       :seon.agent.testrun/path      "tests/test_x.py"
       :seon.agent.testrun/message   "assert 1 == 2"}]
     [])})

(deftest complete-gate-refuses-a-success-claim-while-tests-are-red
  (async done
    (-> (with-conn
          (fn ^:async run []
            (await (seed-agents!))
            (let [aid       "aaa-2606101200"
                  reopen    (fn ^:async _ []
                              (await (run/open-run! {:seon.agent/id           aid
                                                     :seon.agent.run/trigger  :message})))
                  complete! (fn ^:async _ []
                              (await (db/with-agent aid
                                       (fn ^:async c [] (await (lifecycle/complete "done"))))))
                  record!   (fn ^:async _ [res]
                              (await (testrun/record!
                                       {:seon.agent.testrun/agent-id aid
                                        :seon.agent.testrun/result   res})))]
              (testing "NO testrun → complete allowed (non-test task scoping)"
                (await (reopen))
                (is (= :idle (await (complete!)))
                    "an agent that ran no tests completes normally"))
              (testing "GREEN latest testrun → complete allowed"
                (await (record! (run-result 0 0)))
                (await (reopen))
                (is (= :idle (await (complete!)))
                    "a green run is a real terminal-green → success is honest"))
              (testing "RED latest testrun → complete REFUSED (honest envelope), run stays open"
                (await (record! (run-result 2 0)))
                (await (reopen))
                (let [env (await (complete!))
                      msg (:seon.error/message (:seon.db/error env))]
                  (is (false? (:seon.db/ok? env)) "refusal is an errors-as-value envelope")
                  (is (string? msg))
                  (is (str/includes? msg "RED") "the message names the RED state")
                  (is (str/includes? msg "2 failed") "and the actual failing count")
                  (is (= :running (derived aid))
                      "the run is NOT closed — the agent keeps working, not falsely done")))
              (testing "RED then GREEN (latest green) → complete allowed"
                (await (record! (run-result 0 0)))
                (is (= :idle (await (complete!)))
                    "a later real green supersedes the earlier red (latest-wins)"))
              (testing "GREEN then RED (latest red) → complete refused"
                (await (reopen))
                (await (record! (run-result 0 1)))
                (let [env (await (complete!))]
                  (is (false? (:seon.db/ok? env))
                      "the newest run is red (1 error) → refused even after a prior green")
                  (is (str/includes? (:seon.error/message (:seon.db/error env)) "1 error")
                      "errors count, not just failures, gate completion"))))))
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
            (testing "the reserved identity-only root is not a born agent"
              (is (= [] (agent/armable-agent-ids {:seon.db/db @db/*conn*}))))
            (await (seed-agents!))
            (testing "both born agents with no run are armable, sorted asc"
              (is (= ["aaa-2606101200" "bbb-2606101200"]
                     (agent/armable-agent-ids {:seon.db/db @db/*conn*}))))
            (testing "an agent with an OPEN run is :running → NOT armable"
              (await (run/open-run! {:seon.agent/id "aaa-2606101200"
                                     :seon.agent.run/trigger :message}))
              (is (= ["bbb-2606101200"]
                     (agent/armable-agent-ids {:seon.db/db @db/*conn*}))
                  "a running agent is mid-run, not wakeable")
              (is (= ["aaa-2606101200" "bbb-2606101200"]
                     (agent/resumable-agent-ids {:seon.db/db @db/*conn*}))
                  "running state still needs process-local handles"))
            (testing "closing the run re-arms it (back to derived :idle)"
              (await (db/with-agent "aaa-2606101200"
                       (fn ^:async w [] (await (lifecycle/wait "park")))))
              (is (= ["aaa-2606101200" "bbb-2606101200"]
                     (agent/armable-agent-ids {:seon.db/db @db/*conn*}))))
            (testing "a terminated agent is excluded (history, not armable)"
              (await
                (db/with-agent
                  "root"
                  (fn ^:async terminate-agent []
                    (await (lifecycle/terminate "aaa-2606101200")))))
              (is (= ["bbb-2606101200"]
                     (agent/armable-agent-ids {:seon.db/db @db/*conn*})))
              (is (= ["bbb-2606101200"]
                     (agent/resumable-agent-ids {:seon.db/db @db/*conn*}))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest initial-agent-is-created-once-across-current-state-retraction
  (async done
    (-> (with-conn
          (fn ^:async run []
            (await (agent/create! {:seon.agent/id "root"}))
            (let [first-result (await (agent/ensure-initial-agent! {}))
                  initial-id (:seon.agent/id first-result)
                  second-result (await (agent/ensure-initial-agent! {}))]
              (is (true? (::agent/initial-created? first-result)))
              (is (and (string? initial-id) (not= "root" initial-id)))
              (is (= "root"
                     (get-in (db/entity
                               {:seon.db/ref [:seon.agent/id initial-id]})
                             [:seon.agent/parent :seon.agent/id])))
              (is (= {::agent/initial-created? false} second-result)
                  "a second startup does not create another ordinary agent")
              (await (db/transact!
                       {:seon.db/tx-data
                        [[:db.fn/retractEntity
                          [:seon.agent/id initial-id]]]}))
              (is (= {::agent/initial-created? false}
                     (await (agent/ensure-initial-agent! {})))
                  "historical birth prevents replacement after retraction"))))
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
;; Complete birth facts are one atomic database transition.
;; ============================================================

(deftest create!-commits-context-and-home-namespace-atomically
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [id "AGTbirth000001"
                  ns-name :my.agent.AGTbirth000001
                  res (await (agent/create! {:seon.agent/id id}))
                  entity (db/entity {:seon.db/ref [:seon.agent/id id]})
                  ns-entity (db/entity {:seon.db/ref [:seon.ns/name ns-name]})
                  txs (db/query
                        {:seon.db/query
                         '[:find [?tx ...]
                           :in $ ?id ?ns
                           :where
                           (or-join [?tx ?id ?ns]
                             [?a :seon.agent/id ?id ?tx]
                             [?a :seon.agent/ctx _ ?tx]
                             [?n :seon.ns/name ?ns ?tx]
                             [?n :seon.ns/source _ ?tx]
                             [?n :seon.ns/require-edges _ ?tx])]
                         :seon.db/args [id ns-name]})]
              (is (= {:seon.agent/id id} res))
              (is (seq (:seon.agent/ctx entity))
                  "the complete configured block tree exists immediately")
              (is (string? (:seon.ns/source ns-entity))
                  "the deterministic home declaration exists immediately")
              (is (seq (:seon.ns/require-edges ns-entity))
                  "structural home dependencies exist immediately")
              (is (= 1 (count (set txs)))
                  "agent identity, context link, and home namespace share one tx"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest resume-reconstructs-process-state-without-writing-database-state
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [id "AGTresume00001"]
              (await (agent/create! {:seon.agent/id id}))
              (let [birth-t (db/basis-t)
                    resumed (await (agent/resume! {:seon.agent/id id}))]
                (is (true? (:seon.agent.runtime/resumed? resumed)))
                (is (some #{id}
                          (:seon.dev.runtime-id/ids
                           (client/runtime-advertisement)))
                    "runtime addressing projects the durable agent query")
                (is (= birth-t (db/basis-t))
                    "compiler/listener reconstruction adds no database facts")
                (is (= :terminated
                       (await
                         (db/with-agent
                           "root"
                           (fn ^:async terminate-agent []
                             (await (lifecycle/terminate id)))))))
                (is (not (some #{id}
                               (:seon.dev.runtime-id/ids
                                (client/runtime-advertisement))))
                    "termination disappears from the database-derived advertisement")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest create!-completes-the-bare-provenance-root-once
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [root-before (db/entity {:seon.db/ref [:seon.agent/id "root"]})
                  t-before (db/basis-t)]
              (is (= "root" (:seon.agent/id root-before))
                  "provenance genesis installs the root lookup target")
              (is (nil? (:seon.agent/ctx root-before))
                  "genesis does not pretend the agent birth already happened")
              (await (agent/create! {:seon.agent/id "root"}))
              (let [root-after (db/entity
                                 {:seon.db/ref [:seon.agent/id "root"]})
                    t-born (db/basis-t)]
                (is (> t-born t-before))
                (is (seq (:seon.agent/ctx root-after)))
                (is (string?
                      (:seon.ns/source
                        (db/entity
                          {:seon.db/ref [:seon.ns/name :my.agent.root]}))))
                (await (agent/create! {:seon.agent/id "root"}))
                (is (= t-born (db/basis-t))
                    "a born root is an exact no-op and keeps its edits")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
