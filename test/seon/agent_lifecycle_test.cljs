(ns seon.agent-lifecycle-test
  "Agent lifecycle for the FSM rebuild (feature/agent-fsm): the loop owns
   `:seon.agent/state` (the 5-value enum), the verbs only SET it, and
   resume is derived from state — there is no stored `:seon.agent/completed-at`
   anymore (it was deleted; resume reads the enum).

   Pins the invariants the boot + loop paths depend on:

     - the lifecycle VERBS — `agent/wait` / `agent/complete` / `agent/terminate`
       are ALS-scoped (default to `(db/current-agent-id)`) and each is a
       small state transact returning the new `:seon.agent/state` keyword
       (the value the transcript shows). `wait` with no agent in scope
       returns a loud error envelope (errors are values).
     - the RESUME query is `agent/armable-agent-ids` — every agent NOT in
       the terminal `:terminated` state (so :idle / :waiting / :completed
       all stay wakeable), sorted; empty store = [] (genuine first boot);
       a `:terminated` agent is excluded until its state changes.
     - `agent/create!` roundtrips `:seon.agent/max-turns-per-loop` (the base
       per-loop cap the FSM reads via `fsm/max-turns-per-loop`); purpose is
       never defaulted; a failed transact returns the db error envelope.
     - `agent/boot!` propagates create!'s error envelope (no ghost agent).

   The MOTIVATING regression — many-messages / no-deaf — drives the REAL
   FSM loop (`fsm/run-loop!`, not a mock): an agent that receives 3 distinct
   messages in one burst SEES and answers ALL of them. The bug the rebuild
   fixed was 'deaf after one message'; this asserts none are dropped.

   Tests open a FRESH `:memory` conn (via `seon.client/open-agent-conn!`,
   the same boot helper the pod uses) — nothing here touches the live agents.

   Run interactively via MCP eval:
     (require 'seon.agent-lifecycle-test :reload)
     (cljs.test/run-tests 'seon.agent-lifecycle-test)"
  (:require
    [cljs.test :refer [deftest is testing async]]
    [seon.agent :as agent]
    [seon.agent.lifecycle :as lifecycle]
    [seon.agent.loop :as fsm]
    [seon.client :as client]
    [seon.ctx :as ctx]
    [seon.db :as db]
    [seon.eval :as seval]
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

(defn- seed-agents!
  "Transact agent entities `aaa` (idle) + `bbb` (idle)."
  []
  (db/transact!
    {:seon.db/tx-data [{:seon.agent/id "aaa-2606101200" :seon.agent/state :idle}
                       {:seon.agent/id "bbb-2606101200" :seon.agent/state :idle}]}))

;; ============================================================
;; Lifecycle verbs — wait / complete / terminate SET the state enum; the
;; loop only READS it. Each returns the new :seon.agent/state keyword.
;; ============================================================

(deftest verbs-set-state-and-default-to-scoped-agent
  (async done
    (-> (with-conn
          (fn ^:async run []
            (await (seed-agents!))
            (testing "wait parks the SCOPED agent → :waiting"
              (let [r (await (db/with-agent "aaa-2606101200"
                               (fn ^:async w [] (await (lifecycle/wait "need an answer")))))]
                (is (= :waiting r) "wait returns the new state keyword")
                (is (= :waiting (:seon.agent/state
                                  (db/entity {:seon.db/ref [:seon.agent/id "aaa-2606101200"]})))
                    "the entity's state is :waiting")
                (is (= "need an answer"
                       (:seon.agent/wait-note
                         (db/entity {:seon.db/ref [:seon.agent/id "aaa-2606101200"]})))
                    "the note rides along for monitoring agents")))
            (testing "complete finishes the SCOPED agent → :completed"
              (let [r (await (db/with-agent "bbb-2606101200"
                               (fn ^:async c [] (await (lifecycle/complete "done")))))]
                (is (= :completed r))
                (is (= :completed (:seon.agent/state
                                    (db/entity {:seon.db/ref [:seon.agent/id "bbb-2606101200"]}))))))
            (testing "terminate kills an agent by id → :terminated (the one unwakeable state)"
              (let [r (await (lifecycle/terminate "aaa-2606101200"))]
                (is (= :terminated r))
                (is (= :terminated (:seon.agent/state
                                     (db/entity {:seon.db/ref [:seon.agent/id "aaa-2606101200"]}))))))
            (testing "wait with no agent in scope → loud error envelope (errors are values)"
              (let [env (await (lifecycle/wait "orphan"))]
                (is (false? (:seon.db/ok? env)))
                (is (string? (:seon.error/message (:seon.db/error env))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; armable-agent-ids — THE resume query. Every agent NOT :terminated is
;; resumable (idle/waiting/completed all stay wakeable); state-derived,
;; nothing stored. Empty store = genuine first boot.
;; ============================================================

(deftest armable-skips-terminated-and-tracks-state-live
  (async done
    (-> (with-conn
          (fn ^:async run []
            (testing "empty store = genuine first boot (mint path)"
              (is (= [] (agent/armable-agent-ids {:seon.db/db @db/*conn*}))))
            (await (seed-agents!))
            (testing "both idle agents are armable, sorted asc"
              (is (= ["aaa-2606101200" "bbb-2606101200"]
                     (agent/armable-agent-ids {:seon.db/db @db/*conn*}))))
            (testing ":waiting + :completed stay armable (a message resumes them)"
              (await (agent/set-state! {:seon.agent/id "aaa-2606101200" :seon.agent/state :waiting}))
              (await (agent/set-state! {:seon.agent/id "bbb-2606101200" :seon.agent/state :completed}))
              (is (= ["aaa-2606101200" "bbb-2606101200"]
                     (agent/armable-agent-ids {:seon.db/db @db/*conn*}))
                  "neither :waiting nor :completed drops from the roster"))
            (testing "only :terminated is excluded — the unwakeable state"
              (await (lifecycle/terminate "aaa-2606101200"))
              (is (= ["bbb-2606101200"]
                     (agent/armable-agent-ids {:seon.db/db @db/*conn*}))
                  "the terminated agent is NOT resumed (history, not roster)"))
            (testing "changing state off :terminated re-arms the agent"
              (await (agent/set-state! {:seon.agent/id "aaa-2606101200" :seon.agent/state :idle}))
              (is (= ["aaa-2606101200" "bbb-2606101200"]
                     (agent/armable-agent-ids {:seon.db/db @db/*conn*}))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; create! — max-turns-per-loop pass-through (the base per-loop cap the
;; FSM reads), honest error envelope, purpose never defaulted.
;; ============================================================

(deftest create!-roundtrips-max-turns-per-loop
  (async done
    (-> (with-conn
          (fn ^:async run []
            (testing "the cap in the request lands as entity data + the FSM reads it"
              (let [res (await (agent/create!
                                 {:seon.agent/id "AGTcapround001"
                                  :seon.agent/max-turns-per-loop 7}))]
                (is (= {:seon.agent/id "AGTcapround001"} res)
                    "success keeps the success shape")
                (is (= 7 (:seon.agent/max-turns-per-loop
                           (db/entity {:seon.db/ref [:seon.agent/id "AGTcapround001"]})))
                    "max-turns-per-loop is stored on the entity")
                (is (= 7 (fsm/max-turns-per-loop "AGTcapround001"))
                    "the FSM's base-cap read sees it (not the 20 default)")))
            (testing "absent cap leaves the stored cap unchanged (re-create)"
              (await (agent/create! {:seon.agent/id "AGTcapround001"}))
              (is (= 7 (:seon.agent/max-turns-per-loop
                         (db/entity {:seon.db/ref [:seon.agent/id "AGTcapround001"]})))
                  "idempotent re-create never retracts the cap"))))
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
            ;; :seon.agent/max-turns-per-loop is :int, a string is rejected
            ;; by the transact! validation gate.
            (let [res (await (agent/create!
                               {:seon.agent/id "AGTcapround002"
                                :seon.agent/max-turns-per-loop "not-an-int"}))]
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
;; boot! — create!'s error envelope must propagate through the boot path
;; (boot! used to destructure :seon.agent/id off the envelope, get nil,
;; and return a success shape for a ghost agent).
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

;; ============================================================
;; THE MOTIVATING REGRESSION — many-messages / no-deaf.
;;
;; The bug the FSM rebuild fixed: the agent went DEAF after answering one
;; message, dropping any others that were waiting. The fix: the loop's
;; sliding cap + wake-during-loop means an agent that receives N distinct
;; messages in one burst gets a turn to SEE and answer EVERY one.
;;
;; This drives the REAL loop (`fsm/run-loop!`, NOT a mock): seed an agent,
;; deliver 3 distinct user messages, run the loop with a scripted LLM that
;; — each turn — acks the OLDEST still-unacked inbound (reading its own
;; message log, the same data the transcript shows it) and emits NOTHING
;; once all are acked (so the loop halts clean on the empty-streak guard,
;; not the cap). Assert ALL THREE distinct contents got an ack — none
;; dropped.
;; ============================================================

(def ^:private nodeaf-id "AGTnodeaf00001")          ; 14 chars (:seon.db/id)

(def ^:private nodeaf-questions ["q-alpha" "q-beta" "q-gamma"])

(defn- next-unacked
  "The oldest inbound content `nodeaf-id` has NOT yet acked, or nil when all
   are acked. Reads the live db value — the SAME message log the agent sees
   in its transcript. (Computed in the LLM fn so the scripted model can emit
   the EMPTY completion once its inbox is drained — an empty text → 0 forms →
   the loop's empty-streak guard halts clean, never the cap.)"
  [db]
  (let [me       (:db/id (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id nodeaf-id]}))
        inbounds (->> (db/query
                        {:seon.db/db db
                         :seon.db/query
                         '[:find ?c ?at :in $ ?me :where
                           [?m :seon.agent.message/to ?me]
                           [?m :seon.agent.message/from ?f]
                           [(not= ?f ?me)]
                           [?m :seon.agent.message/content ?c]
                           [?m :seon.agent.message/at ?at]]
                         :seon.db/args [me]})
                      (sort-by second)
                      (map first))
        replied  (set (db/query
                        {:seon.db/db db
                         :seon.db/query
                         '[:find [?c ...] :in $ ?me :where
                           [?m :seon.agent.message/from ?me]
                           [?m :seon.agent.message/content ?c]]
                         :seon.db/args [me]}))]
    (first (remove (fn [c] (contains? replied (str "ack: " c))) inbounds))))

(defn- scripted-ack-llm
  "ctx -> Promise<{:text …}>. Each turn it reads the agent's OWN message log
   (the same data the transcript shows it), acks the oldest still-unacked
   inbound with a real (message/user \"ack: <q>\") form, and emits the EMPTY
   completion once every inbound is acked → 0 forms → the loop halts clean on
   the empty-streak guard (not the cap). Nothing is mocked — the loop, the
   eval, and the message write are all real."
  [_ctx]
  (let [q (next-unacked @db/*conn*)]
    (js/Promise.resolve
      {:text (if q
               (str "(message/user (str \"ack: \" " (pr-str q) "))")
               "")})))

(defn- deliver-burst!
  "Transact the three distinct user messages to `nodeaf-id` in one burst,
   each with a monotonic :at so the agent's oldest-first walk is stable."
  []
  (db/transact!
    {:seon.db/tx-data
     (vec (map-indexed
            (fn [i q]
              ;; :seon.agent.message/id is a 14-char :seon.db/id.
              {:seon.agent.message/id      (str "MSGnodeaf0000" (inc i))
               :seon.agent.message/from    {:seon.user/id "user"}
               :seon.agent.message/to      [{:seon.agent/id nodeaf-id}]
               :seon.agent.message/content q
               :seon.agent.message/at      (js/Date. (* (inc i) 1000))
               :seon.agent.message/hops    0
               :seon.agent.message/origin  :human})
            nodeaf-questions))}))

(defn- ack-contents
  "The set of distinct contents the agent sent (its acks)."
  [db]
  (let [me (:db/id (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id nodeaf-id]}))]
    (set
      (db/query
        {:seon.db/db db
         :seon.db/query
         '[:find [?c ...] :in $ ?me :where
           [?m :seon.agent.message/from ?me]
           [?m :seon.agent.message/content ?c]]
         :seon.db/args [me]}))))

(deftest many-messages-no-deaf-after-one
  (async done
    (-> (with-conn
          (fn ^:async run []
            (let [cs (await (repl/ensure-bootstrap!))]
              (await (db/transact! {:seon.db/tx-data [{:seon.user/id "user"}]}))
              ;; boot the agent: home ns + entity, in its own scope
              (await (db/with-agent nodeaf-id
                       (fn ^:async boot []
                         (await (seval/setup-agent-ns! cs (ctx/home-ns nodeaf-id) nodeaf-id))
                         (await (agent/create! {:seon.agent/id nodeaf-id})))))
              ;; deliver 3 distinct messages in one burst BEFORE the wake
              (let [env (await (deliver-burst!))]
                (is (not (false? (:seon.db/ok? env))) "the 3-message burst landed"))
              ;; drive the REAL loop: mint a wake, flip :active, run-loop!
              (let [halt (await (db/with-agent nodeaf-id
                                  (fn ^:async drive []
                                    (let [wake (await (agent/fresh-wake! {:seon.agent/id nodeaf-id}))]
                                      (await (agent/set-state!
                                               {:seon.agent/id nodeaf-id :seon.agent/state :active}))
                                      (await (fsm/run-loop!
                                               {:seon.agent/id            nodeaf-id
                                                :seon.agent/llm-fn        scripted-ack-llm
                                                :seon.agent/compile-state cs}
                                               wake))))))
                    db   @db/*conn*
                    acks (ack-contents db)
                    n-turns (count (:seon.agent.session/turns
                                     (ctx/current-session nodeaf-id db)))]
                (testing "EVERY one of the 3 messages was answered — none dropped"
                  (doseq [q nodeaf-questions]
                    (is (contains? acks (str "ack: " q))
                        (str "the agent answered " q " — not deaf after the first"))))
                (testing "the loop ran at least one turn per message"
                  (is (>= n-turns (count nodeaf-questions))
                      "≥ 3 turns — one per message it had to see and answer"))
                (testing "the loop did NOT run away to the cap"
                  (is (not= :halt-cap halt)
                      "it halted clean (quiet/verb), not by exhausting the cap —
                       the agent finished its inbox, it didn't churn"))
                (testing "the agent ends idle — ready for the next wake"
                  (is (= :idle (:seon.agent/state
                                 (db/entity {:seon.db/db db
                                             :seon.db/ref [:seon.agent/id nodeaf-id]})))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
