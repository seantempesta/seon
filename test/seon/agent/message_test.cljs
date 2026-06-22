(ns seon.agent.message-test
  "Unit tests for messaging codified (unit 1.5): `seon.agent/message!` +
   `reply!`, the from/to refs schema, hops derivation, the blank-content
   guard, the derived conversation (`seon.agent/messages` — from = me OR
   to ∋ me), and transcript labels by ref kind.

   All tests open a FRESH `:memory` datahike conn seeded with the pod's
   boot schema + a user entity + two agents — nothing here touches the
   live agent conn.

   Run interactively via MCP eval:
     (require 'seon.agent.message-test :reload)
     (cljs.test/run-tests 'seon.agent.message-test)"
  (:require
    [cljs.test :refer [deftest is testing async]]
    [datahike.api :as d]
    [seon.agent :as agent]
    [seon.agent.message :as msg]
    [seon.client :as client]
    [seon.db :as db]
    [seon.eval :as seval]))

(def ^:private a-id "msgtest-agent-a")
(def ^:private b-id "msgtest-agent-b")

(defn- fresh-conn
  "Promise of a fresh :memory conn with the pod's boot schema + the user
   entity + agents A and B (the same rows seon.client seeds at boot)."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (d/transact!
                       conn
                       {:tx-data (into (db/malli->datahike-schema
                                         client/agent-bootstrap-attrs)
                                       ;; tx-meta attrs — with-agent's ALS
                                       ;; scope auto-stamps :seon.db/agent-id
                                       ;; into tx-meta, which needs the attr
                                       ;; installed (same as prod boot).
                                       (db/tx-meta-datahike-schema))})
                     (.then (fn [_]
                              (d/transact!
                                conn
                                {:tx-data [{:seon.user/id "user"}
                                           {:seon.agent/id a-id
                                            :seon.agent/state :idle}
                                           {:seon.agent/id b-id
                                            :seon.agent/state :idle}]})))
                     (.then (fn [_] conn))))))))

(defn- with-conn
  "Open a fresh seeded conn, `set!` it as the ROOT `db/*conn*` for the
   duration of `body` (conn → Promise), restore the prior root after.

   Root set! (not `binding`): CLJS `^:async` fns compile `or`/`if`
   forms into AWAITED IIFEs, so a dynamic binding established by the
   caller is popped at the first microtask boundary INSIDE the async
   body — `binding` around `message!`/`reply!` silently reads the prior
   root conn (verified live 2026-06-09). The root swap is visible
   across microtasks; tests run serially and restore in `.finally`."
  [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(defn- pulled-msgs
  "All :seon.agent.message rows in the conn, oldest-first, from/to pulled with
   their id attrs."
  [conn]
  (->> (d/q '[:find (pull ?m [* {:seon.agent.message/from
                                 [:db/id :seon.user/id :seon.agent/id]
                                 :seon.agent.message/to
                                 [:db/id :seon.user/id :seon.agent/id]}])
              :where [?m :seon.agent.message/id _]]
            @conn)
       (map first)
       (sort-by #(.getTime ^js (:seon.agent.message/at %)))
       vec))

;; ---------------------------------------------------------------------------
;; message! — fully-formed storage + boundary defaults.
;; ---------------------------------------------------------------------------

(deftest message-from-user-is-fully-formed-hops-zero
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (agent/message!
                  {:seon.agent.message/from    agent/user-ref
                   :seon.agent.message/to      [:seon.agent/id a-id]
                   :seon.agent.message/content "hello agent A"})
                (.then
                  (fn [{ok?  :seon.agent.message/ok?
                        mid  :seon.agent.message/id
                        hops :seon.agent.message/hops
                        :as  env}]
                    (is (true? ok?) "concise success envelope")
                    (is (string? mid) "response carries the message id")
                    (is (= 0 hops) "response carries the hops")
                    (is (not (contains? env :seon.db/tx-report))
                        "raw tx-report is OFF the agent surface")
                    (let [[m] (pulled-msgs conn)]
                      (testing "stored message is FULLY formed"
                        (is (= mid (:seon.agent.message/id m))
                            "response id = the stored row's id")
                        (is (= "user" (:seon.user/id (:seon.agent.message/from m))))
                        (is (= [a-id]
                               (mapv :seon.agent/id (:seon.agent.message/to m)))
                            "single ref normalized to a vector")
                        (is (= "hello agent A" (:seon.agent.message/content m)))
                        (is (some? (:seon.agent.message/at m)))
                        (is (= 0 (:seon.agent.message/hops m))
                            "from = the user ⇒ hops 0")
                        (is (= :human (:seon.agent.message/origin m))
                            "from = the user ⇒ origin :human (#43)"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest message-defaults-from-als-scope-and-to-user
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (db/with-agent a-id
                  (fn []
                    (agent/message!
                      {:seon.agent.message/content "report for my human"})))
                (.then
                  (fn [{ok? :seon.agent.message/ok? hops :seon.agent.message/hops}]
                    (is (true? ok?))
                    (is (= 1 hops) "response hops = stored hops")
                    (let [[m] (pulled-msgs conn)]
                      (is (= a-id (:seon.agent/id (:seon.agent.message/from m)))
                          "from defaulted to the ALS agent")
                      (is (= ["user"]
                             (mapv :seon.user/id (:seon.agent.message/to m)))
                          "to defaulted to THE user")
                      (is (= 1 (:seon.agent.message/hops m))
                          "agent-originated, no waking msg ⇒ hops 0+1")
                      (is (= :agent (:seon.agent.message/origin m))
                          "agent-originated ⇒ origin :agent (#43)")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest blank-content-is-refused-with-an-envelope
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (agent/message! {:seon.agent.message/from agent/user-ref
                                 :seon.agent.message/content "   "})
                (.then
                  (fn [{ok? :seon.db/ok? error :seon.db/error}]
                    (is (false? ok?) "blank content → error envelope")
                    (is (re-find #"blank" (:seon.error/message error)))
                    (is (empty? (pulled-msgs conn))
                        "nothing stored — the empty-message class is dead"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest no-from-and-no-scope-is-refused
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (agent/message! {:seon.agent.message/content "who am I?"})
                (.then
                  (fn [{ok? :seon.db/ok? error :seon.db/error}]
                    (is (false? ok?))
                    (is (re-find #"with-agent" (:seon.error/message error)))
                    (is (empty? (pulled-msgs conn))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; reply! — target + hops derived from the turn's woken-by message.
;; ---------------------------------------------------------------------------

(defn- seed-woken-turn!
  "Transact a waking message (from `from-map`, hops `hops`) + a session
   with one turn whose :seon.agent.turn/woken-by points at it. Returns a
   Promise."
  [conn from-map hops]
  (d/transact!
    conn
    {:tx-data
     [{:seon.agent.message/id      "MSGmsgtestWAKE"
       :seon.agent.message/from    from-map
       :seon.agent.message/to      [{:seon.agent/id a-id}]
       :seon.agent.message/content "are you there?"
       :seon.agent.message/at      (js/Date.)
       :seon.agent.message/hops    hops}
      {:seon.agent/id a-id
       :seon.agent/sessions
       [{:seon.agent.session/id "SESmsgtest0001"
         :seon.agent.session/at (js/Date.)
         :seon.agent.session/turns
         [{:seon.agent.turn/id       "TRNmsgtest0001"
           :seon.agent.turn/at       (js/Date. (+ (js/Date.now) 5))
           :seon.agent.turn/status   :running
           :seon.agent.turn/woken-by [:seon.agent.message/id "MSGmsgtestWAKE"]}]}]}]}))

(deftest reply-targets-the-waking-sender-and-increments-hops
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (seed-woken-turn! conn {:seon.user/id "user"} 0)
                (.then (fn [_]
                         (db/with-agent a-id
                           (fn []
                             (agent/reply!
                               {:seon.agent.message/content "yes — here"})))))
                (.then
                  (fn [{ok? :seon.agent.message/ok? hops :seon.agent.message/hops}]
                    (is (true? ok?))
                    (is (= 1 hops) "concise response carries hops 0 + 1")
                    (let [m (->> (pulled-msgs conn)
                                 (remove #(= "MSGmsgtestWAKE"
                                             (:seon.agent.message/id %)))
                                 first)]
                      (is (= ["user"]
                             (mapv :seon.user/id (:seon.agent.message/to m)))
                          "reply target = the waking message's from")
                      (is (= a-id (:seon.agent/id (:seon.agent.message/from m))))
                      (is (= 1 (:seon.agent.message/hops m))
                          "waking hops 0 + 1")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest reply-to-an-agent-waking-message-carries-hops-plus-one
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (seed-woken-turn! conn {:seon.agent/id b-id} 2)
                (.then (fn [_]
                         (db/with-agent a-id
                           (fn []
                             (agent/reply!
                               {:seon.agent.message/content "checked — totals ok"})))))
                (.then
                  (fn [{ok? :seon.agent.message/ok? hops :seon.agent.message/hops}]
                    (is (true? ok?))
                    (is (= 3 hops) "concise response carries the climbing hops")
                    (let [m (->> (pulled-msgs conn)
                                 (remove #(= "MSGmsgtestWAKE"
                                             (:seon.agent.message/id %)))
                                 first)]
                      (is (= [b-id]
                             (mapv :seon.agent/id (:seon.agent.message/to m)))
                          "reply goes back to agent B")
                      (is (= 3 (:seon.agent.message/hops m))
                          "waking hops 2 + 1 — the chain marches to the cap")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; #51 NARROWED — reply ALWAYS transacts + delivers; the protection is a
;; LOOP-TERMINATION VETO, not a write block (reliability-49-53-deepdive
;; 2026-06-22). The gate no longer REFUSES a same-turn send. What
;; survives is `msg/same-turn-overclaim?` (the loop forces ONE make-good
;; turn) + `msg/overclaim-advisory-section` (the render the agent sees),
;; firing ONLY for the envelope-VALUE over-claim: a user-facing reply
;; landed in the same turn as a sibling form whose live value was a
;; `{*/ok? false}` failure envelope (eval-ok? TRUE). A genuine eval ERROR
;; is advisory after #50 and does NOT veto. All derived from the turn's
;; :seon.eval rows + the message log — nothing stored, nothing to clear.
;; ---------------------------------------------------------------------------

(def ^:private turn-id "TRNmsgtest0001")

(defn- seed-turn-eval!
  "Attach one :seon.eval row to the seeded turn (raw datahike — same
   bypass as seed-woken-turn!). `stash` (optional) is the eval's live
   value, stashed under its id exactly as eval-batch! does."
  [conn {:keys [eval-id source ok? error stash]}]
  (when (some? stash)
    (seval/stash-result-raw! eval-id stash))
  (d/transact!
    conn
    {:tx-data
     [{:seon.agent.turn/id turn-id
       :seon.agent.turn/evals
       [(cond-> {:seon.eval/id     eval-id
                 :seon.eval/at     (js/Date.)
                 :seon.eval/source source
                 :seon.eval/ns     :my.agent.msgtest
                 :seon.eval/ok?    ok?}
          error (assoc :seon.eval/error error))]}]}))

(defn- reply-in-batch!
  "Call reply! as the batch would: agent ALS scope + the turn's
   tx-context (run-turn! layers :seon.db/turn-id; eval-batch!'s
   per-form scope merges into it)."
  [req]
  (db/with-agent a-id
    (fn []
      (db/with-tx-context
        {:seon.db/agent-id a-id :seon.db/turn-id turn-id}
        (fn [] (agent/reply! req))))))

(defn- non-wake-msgs [conn]
  (->> (pulled-msgs conn)
       (remove #(= "MSGmsgtestWAKE" (:seon.agent.message/id %)))))

(defn- the-turn
  "The seeded turn entity (TRNmsgtest0001), evals inlined — what the loop
   veto reads (run-turn!'s result)."
  [conn]
  (db/entity {:seon.db/db @conn :seon.db/ref [:seon.agent.turn/id turn-id]}))

(defn- veto?
  "Would the loop force a make-good turn for the seeded turn? Reads the
   turn entity + message log, exactly as run-agentic-loop! does. The
   seeded turn is the most-recent turn, so the reply window is open."
  [conn]
  (msg/same-turn-overclaim? (the-turn conn) a-id))

;; An EVAL ERROR (ok? false) sibling no longer blocks the reply — after
;; #50 a genuine error is advisory, the loop grants a next turn, and the
;; error renders crystal-clear. The reply DELIVERS and the veto does NOT
;; fire (dropping the eval-error half was the whole point).
(deftest eval-error-sibling-reply-delivers-no-veto
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (seed-woken-turn! conn {:seon.user/id "user"} 0)
                (.then (fn [_]
                         (seed-turn-eval!
                           conn {:eval-id "EVLmsgtstERR1"
                                 :source  "(schema/register! ::run :string)"
                                 :ok?     false
                                 :error   "undeclared var schema/register!"})))
                (.then (fn [_]
                         (reply-in-batch!
                           {:seon.agent.message/content "logged it — all stored"})))
                (.then
                  (fn [{ok? :seon.agent.message/ok?}]
                    (is (true? ok?) "reply ALWAYS transacts — no write block")
                    (is (= 1 (count (non-wake-msgs conn)))
                        "the reply IS delivered to the user")
                    (is (false? (veto? conn))
                        "eval-error sibling does NOT veto the halt — advisory only"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; The decisive #26/B3 case: the transact REJECTION is an eval-ok? TRUE
;; VALUE (errors-as-values). The reply DELIVERS (the human gets the
;; answer) AND the loop forces one make-good turn so the advisory lands
;; where the agent sees it.
(deftest envelope-failure-sibling-reply-delivers-and-vetoes
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (seed-woken-turn! conn {:seon.user/id "user"} 0)
                (.then (fn [_]
                         (seed-turn-eval!
                           conn {:eval-id "EVLmsgtstENV1"
                                 :source  "(db/transact! {:seon.db/tx-data [{:my.run/id \"run-1\"}]})"
                                 :ok?     true
                                 :stash   {:seon.db/ok? false
                                           :seon.db/error
                                           {:seon.error/message
                                            "unregistered attr :my.run/id"}}})))
                (.then (fn [_]
                         (reply-in-batch!
                           {:seon.agent.message/content "stored as run-1"})))
                (.then
                  (fn [{ok? :seon.agent.message/ok?}]
                    (is (true? ok?) "the reply transacts + delivers, never blocked")
                    (is (= 1 (count (non-wake-msgs conn)))
                        "the human gets the answer")
                    (is (true? (veto? conn))
                        "envelope-value over-claim → loop forces one make-good turn")
                    (let [lines (msg/turn-overclaim-lines (the-turn conn) a-id nil)]
                      (is (= 1 (count lines)))
                      (is (re-find #"EVLmsgtstENV1" (first lines)))
                      (is (re-find #"error envelope" (first lines)))
                      (is (re-find #"unregistered attr" (first lines))
                          "the advisory line surfaces the envelope's message")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; A same-turn envelope failure with NO user-facing reply (e.g. an
;; agent-to-agent consult that failed) is NOT an over-claim — the veto is
;; scoped to the user-facing reply (TIGHTEST scope, DECISION §5.2).
(deftest envelope-failure-without-reply-does-not-veto
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (seed-woken-turn! conn {:seon.user/id "user"} 0)
                (.then (fn [_]
                         (seed-turn-eval!
                           conn {:eval-id "EVLmsgtstNR1"
                                 :source  "(db/transact! …)"
                                 :ok?     true
                                 :stash   {:seon.db/ok? false
                                           :seon.db/error
                                           {:seon.error/message "rejected"}}})))
                ;; no reply sent this turn
                (.then (fn [_]
                         (is (seq (msg/turn-envelope-failure-lines (the-turn conn)))
                             "the envelope failure is detected")
                         (is (false? (veto? conn))
                             "but with no user-facing reply, no over-claim → no veto"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; :force is the deliberate \"I am replying ABOUT the failure\" escape —
;; the reply still transacts + delivers (as every reply now does) AND it
;; opts the turn OUT of the make-good-turn veto: a forced reply over an
;; envelope-failure sibling is the agent declaring the over-claim is
;; intentional, so same-turn-overclaim? does NOT fire (the loop would
;; halt :replied, not recur). The unforced sibling test
;; (envelope-failure-sibling-reply-delivers-and-vetoes) proves the SAME
;; setup DOES veto without force — so the only difference here is the
;; force flag, isolating the opt-out.
(deftest force-reply-delivers-and-skips-the-veto
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (seed-woken-turn! conn {:seon.user/id "user"} 0)
                (.then (fn [_]
                         (seed-turn-eval!
                           conn {:eval-id "EVLmsgtstENV2"
                                 :source  "(db/transact! …)"
                                 :ok?     true
                                 :stash   {:seon.db/ok? false
                                           :seon.db/error
                                           {:seon.error/message "rejected"}}})))
                (.then (fn [_]
                         (reply-in-batch!
                           {:seon.agent.message/content
                            "the transact FAILED — nothing was stored; see eval EVLmsgtstENV2"
                            :seon.agent.message/force true})))
                (.then
                  (fn [{ok? :seon.agent.message/ok?}]
                    (is (true? ok?) "force = a deliberate reply ABOUT the failure")
                    (is (= 1 (count (non-wake-msgs conn)))
                        "the forced message IS stored")
                    (is (true? (:seon.agent.message/force
                                 (first (non-wake-msgs conn))))
                        "force IS persisted on the stored row (true)")
                    (is (false? (veto? conn))
                        "force opts OUT of the over-claim veto — the loop halts :replied, not recurs"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; All-green batch — a SUCCESS envelope ({*/ok? true}) is not a failure.
;; The reply delivers and the veto stays silent.
(deftest all-green-batch-reply-delivers-no-veto
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (seed-woken-turn! conn {:seon.user/id "user"} 0)
                (.then (fn [_]
                         (seed-turn-eval!
                           conn {:eval-id "EVLmsgtstOK01"
                                 :source  "(+ 1 2)"
                                 :ok?     true
                                 :stash   3})))
                (.then (fn [_]
                         (seed-turn-eval!
                           conn {:eval-id "EVLmsgtstOK02"
                                 :source  "(db/transact! {:seon.db/tx-data […]})"
                                 :ok?     true
                                 ;; success envelopes have ok? TRUE — not a failure
                                 :stash   {:seon.db/ok? true}})))
                (.then (fn [_]
                         (reply-in-batch!
                           {:seon.agent.message/content "done — stored 1 row"})))
                (.then
                  (fn [{ok? :seon.agent.message/ok?}]
                    (is (true? ok?) "all-green batch → reply delivers unchanged")
                    (is (= 1 (count (non-wake-msgs conn))))
                    (is (false? (veto? conn))
                        "no failure → no over-claim → no veto"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; The derived conversation + labels.
;; ---------------------------------------------------------------------------

(deftest messages-is-derived-from-me-or-to-me
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (d/transact!
                  conn
                  {:tx-data
                   [{:seon.agent.message/id "MSGmsgtestIN01"
                     :seon.agent.message/from {:seon.user/id "user"}
                     :seon.agent.message/to [{:seon.agent/id a-id}]
                     :seon.agent.message/content "for A"
                     :seon.agent.message/at (js/Date. (+ (js/Date.now) 1))
                     :seon.agent.message/hops 0}
                    {:seon.agent.message/id "MSGmsgtestOUT1"
                     :seon.agent.message/from {:seon.agent/id a-id}
                     :seon.agent.message/to [{:seon.user/id "user"}]
                     :seon.agent.message/content "from A"
                     :seon.agent.message/at (js/Date. (+ (js/Date.now) 2))
                     :seon.agent.message/hops 1}
                    {:seon.agent.message/id "MSGmsgtestB2B1"
                     :seon.agent.message/from {:seon.agent/id b-id}
                     :seon.agent.message/to [{:seon.user/id "user"}]
                     :seon.agent.message/content "B's own thread"
                     :seon.agent.message/at (js/Date. (+ (js/Date.now) 3))
                     :seon.agent.message/hops 1}]})
                (.then
                  (fn [_]
                    (let [ids (mapv :seon.agent.message/id
                                    (agent/messages {:seon.agent/id a-id}))]
                      (is (= ["MSGmsgtestIN01" "MSGmsgtestOUT1"] ids)
                          "from = me OR to ∋ me — B's unrelated message excluded")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest labels-resolve-by-ref-kind
  (testing "user / self / other-agent labels"
    (is (= "user" (agent/message-label {:seon.user/id "user"} a-id)))
    (is (= "assistant" (agent/message-label {:seon.agent/id a-id} a-id)))
    (is (= (str "agent-" b-id)
           (agent/message-label {:seon.agent/id b-id} a-id)))
    (is (= "unknown" (agent/message-label nil a-id)))))
