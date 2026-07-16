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
       `derive/resumable-agent-ids` selects every nonterminated process host.
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
    [seon.db.coordinate :as db.coordinate]
    [seon.db.id :as db.id]
    [seon.db.protocol :as db.protocol]
    [seon.derive :as derive]))

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
                     (derive/resumable-agent-ids @db/*conn*))
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
                     (derive/resumable-agent-ids @db/*conn*))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(def ^:private birth-coordinate
  {::db.coordinate/database-id #uuid "00000000-0000-0000-0000-000000000081"
   ::db.coordinate/branch :db
   ::db.coordinate/commit-id #uuid "00000000-0000-0000-0000-000000000082"
   ::db.coordinate/t 536870912})

(defn- grouped-result
  [value]
  (db.protocol/success {::db.protocol/result value}))

(deftest initial-agent-birth-acquires-once-and-commits-root-and-child-atomically
  (async done
    (let [original-execute db/execute-many
          original-allocate db.id/allocate!
          original-transact db/transact!
          calls (atom [])
          child-id "amber-fox-river"]
      (set! db/execute-many
            (fn [request]
              (swap! calls conj [:acquire request])
              (js/Promise.resolve
                {::db/coordinate birth-coordinate
                 ::db/results
                 [(grouped-result [{:seon.agent/id "root"} nil])
                  (grouped-result nil)
                  (grouped-result
                    #{[:seon.agent/id
                       :seon.db.id.generator/human-readable]})]})))
      (set! db.id/allocate!
            (fn [request]
              (swap! calls conj [:allocate request])
              (js/Promise.resolve
                {:seon.db/ok? true
                 ::db.id/ids {:seon.agent/id child-id}})))
      (set! db/transact!
            (fn [& [request]]
              (swap! calls conj [:forbidden-transact request])
              (js/Promise.resolve {:seon.db/ok? false})))
      (-> (agent/ensure-initial-agent! {})
          (.then
            (fn [result]
              (let [[[_ acquire] [_ allocation]] @calls
                    [root-member history-member policy-member]
                    (::db/members acquire)
                    built ((::db.id/transaction-builder allocation)
                           {:seon.agent/id child-id})
                    tx-data (::db/tx-data built)
                    root-row (first (filter #(= "root" (:seon.agent/id %))
                                            tx-data))
                    child-row (first (filter #(= child-id (:seon.agent/id %))
                                             tx-data))]
                (is (= {::agent/root-created? true
                        ::agent/initial-created? true
                        :seon.agent/id child-id}
                       result))
                (is (= 2 (count @calls))
                    "one grouped acquisition feeds one allocation commit")
                (is (= [db.protocol/pull-many-operation
                        db.protocol/query-operation
                        db.protocol/query-operation]
                       (mapv ::db.protocol/operation (::db/members acquire))))
                (is (= [[:seon.agent/id "root"]
                        [:seon.ns/name :my.agent.root]]
                       (::db.protocol/entity-ids root-member)))
                (is (true? (::db.protocol/history? history-member)))
                (is (= db.id/generator-policy-query
                       (::db.protocol/query-form policy-member)))
                (is (not (contains? allocation :seon.db/conn)))
                (is (= {:seon.agent/id
                        :seon.db.id.generator/human-readable}
                       (::db.id/generator-policies allocation)))
                (is (= birth-coordinate (::db/expected-coordinate built))
                    "the immutable acquisition coordinate fences the birth")
                (is (= 4 (count tx-data))
                    "root, root home, child, and child home share one commit")
                (is (seq (:seon.agent/ctx root-row)))
                (is (seq (:seon.agent/ctx child-row)))
                (is (= [:seon.agent/id "root"]
                       (:seon.agent/parent child-row)))
                (is (= #{:my.agent.root
                         (keyword "my.agent.amber-fox-river")}
                       (into #{} (keep :seon.ns/name) tx-data))))))
          (.catch (fn [error]
                    (is false (str "atomic startup birth threw — " error))))
          (.finally
            (fn []
              (set! db/execute-many original-execute)
              (set! db.id/allocate! original-allocate)
              (set! db/transact! original-transact)
              (done)))))))

(deftest initial-agent-history-prevents-a-replacement-without-a-write
  (async done
    (let [original-execute db/execute-many
          original-allocate db.id/allocate!
          original-transact db/transact!
          calls (atom [])]
      (set! db/execute-many
            (fn [request]
              (swap! calls conj [:acquire request])
              (js/Promise.resolve
                {::db/coordinate birth-coordinate
                 ::db/results
                 [(grouped-result
                    [{:seon.agent/id "root"}
                     {:seon.ns/name :my.agent.root
                      :seon.ns/source "(ns my.agent.root)"}])
                  (grouped-result 42)
                  (grouped-result
                    #{[:seon.agent/id
                       :seon.db.id.generator/human-readable]})]})))
      (set! db.id/allocate!
            (fn [request]
              (swap! calls conj [:forbidden-allocate request])
              (js/Promise.resolve {:seon.db/ok? false})))
      (set! db/transact!
            (fn [& [request]]
              (swap! calls conj [:forbidden-transact request])
              (js/Promise.resolve {:seon.db/ok? false})))
      (-> (agent/ensure-initial-agent! {})
          (.then
            (fn [first-result]
              (is (= {::agent/root-created? false
                      ::agent/initial-created? false}
                     first-result))
              ;; The history member remains true even if the current child was
              ;; retracted. A later startup must make the same no-write choice.
              (agent/ensure-initial-agent! {})))
          (.then
            (fn [second-result]
              (is (= {::agent/root-created? false
                      ::agent/initial-created? false}
                     second-result))
              (is (= 2 (count @calls)))
              (is (every? #(= :acquire (first %)) @calls))))
          (.catch (fn [error]
                    (is false (str "historical startup proof threw — " error))))
          (.finally
            (fn []
              (set! db/execute-many original-execute)
              (set! db.id/allocate! original-allocate)
              (set! db/transact! original-transact)
              (done)))))))

;; ============================================================
;; create! — default-turn-limit pass-through, honest error envelope,
;; purpose never defaulted.
;; ============================================================

(deftest create!-acquires-once-fences-the-birth-and-preserves-errors
  (async done
    (let [original-execute db/execute-many
          original-transact db/transact!
          acquired (atom [nil nil])
          transaction-result
          (atom {:seon.db/ok? true})
          calls (atom [])
          id "AGTbirth000001"]
      (set! db/execute-many
            (fn [request]
              (swap! calls conj [:acquire request])
              (js/Promise.resolve
                {::db/coordinate birth-coordinate
                 ::db/results [(grouped-result @acquired)]})))
      (set! db/transact!
            (fn [& [request]]
              (swap! calls conj [:transact request])
              (js/Promise.resolve @transaction-result)))
      (-> (agent/create! {:seon.agent/id id
                          :seon.agent/purpose "track Acme orders"
                          :seon.agent/default-turn-limit 7})
          (.then
            (fn [result]
              (is (= {:seon.agent/id id} result))
              (let [[[_ acquire] [_ transact]] @calls
                    member (first (::db/members acquire))
                    agent-row (first (::db/tx-data transact))]
                (is (= db.protocol/pull-many-operation
                       (::db.protocol/operation member)))
                (is (= [[:seon.agent/id id]
                        [:seon.ns/name :my.agent.AGTbirth000001]]
                       (::db.protocol/entity-ids member)))
                (is (= birth-coordinate (::db/expected-coordinate transact)))
                (is (= id (:seon.agent/id agent-row)))
                (is (= "track Acme orders" (:seon.agent/purpose agent-row)))
                (is (= 7 (:seon.agent/default-turn-limit agent-row)))
                (is (= 2 (count (::db/tx-data transact)))
                    "agent and home namespace are one transaction"))
              (reset! acquired
                      [{:seon.agent/id id}
                       {:seon.ns/name :my.agent.AGTbirth000001
                        :seon.ns/source "(ns my.agent.AGTbirth000001)"}])
              (agent/create! {:seon.agent/id id})))
          (.then
            (fn [idempotent]
              (is (= {:seon.agent/id id} idempotent))
              (is (= 1 (count (filter #(= :transact (first %)) @calls)))
                  "a complete entity performs no second write")
              (reset! acquired [nil nil])
              (reset! transaction-result
                      {:seon.db/ok? false
                       :seon.db/error
                       {:seon.error/message "rejected"
                        :seon.error/kind :user-input}})
              (agent/create! {:seon.agent/id "AGTbirth000002"})))
          (.then
            (fn [failed]
              (is (false? (:seon.db/ok? failed)))
              (is (= "rejected"
                     (get-in failed [:seon.db/error :seon.error/message])))
              (is (= 3 (count (filter #(= :acquire (first %)) @calls)))
                  "every create decision owns one immutable acquisition")))
          (.catch (fn [error]
                    (is false (str "session-native create threw — " error))))
          (.finally
            (fn []
              (set! db/execute-many original-execute)
              (set! db/transact! original-transact)
              (done)))))))

(deftest mint!-uses-the-session-allocator-without-a-local-connection
  (async done
    (let [original-allocate db.id/allocate!
          calls (atom [])
          child-id "silver-pine-brook"]
      (set! db.id/allocate!
            (fn [request]
              (swap! calls conj request)
              (js/Promise.resolve
                {:seon.db/ok? true
                 ::db.id/ids {:seon.agent/id child-id}})))
      (-> (agent/mint! {:seon.agent/purpose "observe the cluster"
                        :seon.agent/default-turn-limit 5
                        :seon.agent/parent [:seon.agent/id "root"]})
          (.then
            (fn [result]
              (let [request (first @calls)
                    built ((::db.id/transaction-builder request)
                           {:seon.agent/id child-id})
                    [agent-row home-row] (::db/tx-data built)]
                (is (= {:seon.agent/id child-id} result))
                (is (= 1 (count @calls)))
                (is (not (contains? request :seon.db/conn)))
                (is (= child-id (:seon.agent/id agent-row)))
                (is (= "observe the cluster" (:seon.agent/purpose agent-row)))
                (is (= 5 (:seon.agent/default-turn-limit agent-row)))
                (is (= [:seon.agent/id "root"]
                       (:seon.agent/parent agent-row)))
                (is (= (keyword "my.agent.silver-pine-brook")
                       (:seon.ns/name home-row))))))
          (.catch (fn [error]
                    (is false (str "session-native mint threw — " error))))
          (.finally
            (fn []
              (set! db.id/allocate! original-allocate)
              (done)))))))

(deftest resume-reconstructs-process-state-without-writing-database-state
  (async done
    (let [previous-state @client/!state
          original-listen db/listen!
          original-query db/query
          original-unlisten db/unlisten!
          !handler (atom nil)]
      (-> (with-conn
            (fn ^:async run []
              (reset! client/!state
                      (-> previous-state
                          (dissoc ::client/advertisement-owner
                                  ::client/advertisement-interest-key
                                  ::client/advertisement-desired-coordinate
                                  ::client/advertisement-accepted-coordinate
                                  ::client/resumable-agent-ids-coordinate)
                          (assoc ::client/resumable-agent-ids [])))
              (set! db/listen!
                    (fn [request]
                      (reset! !handler (::db/handler request))
                      (js/Promise.resolve
                       {::db/key ::advertisement-proof
                        ::db/coordinate
                        (db.coordinate/resolved @db/*conn*)})))
              (set! db/query
                    (fn [& args]
                      (let [request (first args)]
                        (if (and (= 1 (count args))
                                 (map? request)
                                 (::db/coordinate request))
                          (original-query
                           (-> request
                               (dissoc ::db/coordinate)
                               (assoc ::db/db @db/*conn*)))
                          (apply original-query args)))))
              (set! db/unlisten!
                    (fn [_] (js/Promise.resolve {::db/ok? true})))
              (await ((deref #'client/attach-runtime-advertisement!)))
              (let [id "AGTresume00001"]
                (await (agent/create! {:seon.agent/id id}))
                (await (@!handler
                        {::db.protocol/coordinate
                         (db.coordinate/resolved @db/*conn*)}))
                (let [birth-t (db/basis-t)
                      resumed (await (agent/resume! {:seon.agent/id id}))]
                  (is (true? (:seon.agent.runtime/resumed? resumed)))
                  (is (some #{id}
                            (:seon.dev.runtime-id/ids
                             (client/runtime-advertisement)))
                      "post-boot birth refreshes the addressed projection")
                  (is (= birth-t (db/basis-t))
                      "compiler/listener reconstruction adds no database facts")
                  (is (= :terminated
                         (await
                           (db/with-agent
                             "root"
                             (fn ^:async terminate-agent []
                               (await (lifecycle/terminate id)))))))
                  (await (@!handler
                          {::db.protocol/coordinate
                           (db.coordinate/resolved @db/*conn*)}))
                  (is (not (some #{id}
                                 (:seon.dev.runtime-id/ids
                                  (client/runtime-advertisement))))
                      "termination disappears from the addressed projection")))
              (await ((deref #'client/detach-runtime-advertisement!)))))
          (.catch (fn [e] (is false (str "threw — " e))))
          (.finally
           (fn []
             (set! db/listen! original-listen)
             (set! db/query original-query)
             (set! db/unlisten! original-unlisten)
             (reset! client/!state previous-state)
             (done)))))))
