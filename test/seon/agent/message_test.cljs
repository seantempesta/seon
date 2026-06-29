(ns seon.agent.message-test
  "Unit tests for messaging codified (unit 1.5): `seon.agent/message!`,
   the from/to refs schema, hops derivation, the blank-content
   guard, the derived conversation (`seon.agent/messages` — from = me OR
   to ∋ me), and transcript labels by ref kind. (The `reply!` tests were
   deleted with `reply!` in the agent-fsm redesign U2 — the new
   `message/user` / `message/agent` verbs are verified live, not here:
   tests are deferred until the format is proven.)

   All tests open a FRESH `:memory` datahike conn seeded with the pod's
   boot schema + a user entity + two agents — nothing here touches the
   live agent conn.

   Run interactively via MCP eval:
     (require 'seon.agent.message-test :reload)
     (cljs.test/run-tests 'seon.agent.message-test)"
  (:require
    [cljs.test :refer [deftest is testing async]]
    [datahike.api :as d]
    [malli.core :as m]
    [seon.agent :as agent]
    [seon.agent.todo :as todo]
    [seon.client :as client]
    [seon.db :as db]
    [seon.warn :as warn]))

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
                                           {:seon.agent/id a-id}
                                           {:seon.agent/id b-id}]})))
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

;; ---------------------------------------------------------------------------
;; Hop derivation is PER-PAIR (`internal/outbound-hops`): the ping-pong guard
;; measures back-and-forth depth within ONE {me,peer} pair, reset at each human
;; message — NOT the length of a delegation tree's wake chain. So a genuine
;; A↔B↔A↔B runaway still climbs to the cap (bug #79's loop-guard preserved),
;; while a parent delegating to childA then childB (distinct pairs, distinct
;; rounds) does NOT accumulate (bug #79's deadlock fixed).
;; ---------------------------------------------------------------------------

(defn- send-hops
  "Send agent→agent (from = `from-id` via ALS scope, to = `to-id`); resolve
   to the stored `:seon.agent.message/hops`."
  [from-id to-id content]
  (-> (db/with-agent from-id
        (fn []
          (agent/message! {:seon.agent.message/to      [:seon.agent/id to-id]
                           :seon.agent.message/content content})))
      (.then (fn [env] (:seon.agent.message/hops env)))))

(deftest runaway-same-pair-loop-still-trips-the-hop-cap
  (async done
    (-> (with-conn
          (fn [_]
            ;; A↔B bouncing the SAME pair — each reply derives from the pair's
            ;; prior depth, so hops climb 1→2→3→4 and the 4th HITS the cap
            ;; (the wake trigger refuses ≥ cap; the loop-guard is intact).
            (-> (send-hops a-id b-id "ping 1")
                (.then (fn [h1]
                         (is (= 1 h1) "fresh pair ⇒ first contact is hops 1")
                         (send-hops b-id a-id "pong 1")))
                (.then (fn [h2]
                         (is (= 2 h2) "reply within the pair ⇒ +1")
                         (send-hops a-id b-id "ping 2")))
                (.then (fn [h3]
                         (is (= 3 h3) "still the same pair ⇒ +1")
                         (send-hops b-id a-id "pong 2")))
                (.then (fn [h4]
                         (is (= 4 h4) "the 4th bounce in one pair reaches the cap")
                         (is (>= h4 warn/hop-cap)
                             "a genuine runaway STILL trips the hop-cap (>= cap ⇒ wake refused)"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest multi-round-delegation-does-not-accumulate-hops
  (async done
    (-> (with-conn
          (fn [conn]
            ;; parent = a-id; children = b-id (round 1) and c-id (round 2).
            (-> (d/transact! conn {:tx-data [{:seon.agent/id "msgtest-agent-c"}]})
                (.then (fn [_]
                         ;; round 1: parent → childB (task), childB → parent (report)
                         (send-hops a-id b-id "research option 1")))
                (.then (fn [hpx]
                         (is (= 1 hpx) "parent→childB is a fresh pair ⇒ hops 1")
                         (send-hops b-id a-id "option 1 done — stored 6 rows")))
                (.then (fn [hxp]
                         (is (= 2 hxp) "childB→parent report ⇒ hops 2")
                         ;; round 2: parent → childC (a DISTINCT pair)
                         (send-hops a-id "msgtest-agent-c" "research option 2")))
                (.then (fn [hpy]
                         (is (= 1 hpy)
                             "parent→childC is a DISTINCT pair ⇒ resets to hops 1 (NOT 3)")
                         (send-hops "msgtest-agent-c" a-id "option 2 done — stored 6 rows")))
                (.then (fn [hyp]
                         (is (= 2 hyp)
                             "childC→parent round-2 report ⇒ hops 2, NOT 4 — wakes the parent (bug #79 fixed)")
                         (is (< hyp warn/hop-cap)
                             "the 2nd-round report is UNDER the cap ⇒ no silent deadlock"))))))
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
;; `from` accepts EVERY datahike ref form — a lookup-ref `[:seon.agent/id id]`,
;; a resolved eid (int), and the ALS default (which derives a lookup-ref). The
;; slot schema is `:seon.db/ref`, which admits all three by design
;; (`[:or :int :string [:tuple :keyword …]]`); this locks that invariant at the
;; boundary (the request schema) AND end-to-end (each form transacts + resolves
;; back to the same sender). Regression for delegation-drive finding #5, whose
;; `:malli.core/invalid-schema` was the registry-stomp (#41), NOT a from-slot
;; defect — the slot already admits the lookup-ref, as these asserts prove.
;; ---------------------------------------------------------------------------

(deftest from-slot-admits-lookup-ref-resolved-ref-and-default
  (testing "the request schema admits all three from-ref forms at the boundary"
    (let [req (fn [from] {:seon.agent.message/from    from
                          :seon.agent.message/to      [:seon.agent/id a-id]
                          :seon.agent.message/content "hi"})]
      (is (m/validate :seon.agent.message/message-request
                      (req [:seon.agent/id a-id]))
          "lookup-ref from validates")
      (is (m/validate :seon.agent.message/message-request (req 42))
          "resolved eid (int) from validates")
      (is (m/validate :seon.agent.message/message-request
                      (dissoc (req nil) :seon.agent.message/from))
          "absent from validates (defaults to the ALS agent)")))
  (async done
    (-> (with-conn
          (fn [conn]
            (let [a-eid (d/q '[:find ?e . :where [?e :seon.agent/id "msgtest-agent-a"]]
                             @conn)
                  from-resolves-to-a?
                  (fn [{mid :seon.agent.message/id ok? :seon.agent.message/ok?} label]
                    (is (true? ok?) (str label " → ok? envelope"))
                    (let [m (first (filter #(= mid (:seon.agent.message/id %))
                                           (pulled-msgs conn)))]
                      (is (= a-id (:seon.agent/id (:seon.agent.message/from m)))
                          (str label " → stored from resolves to agent A"))))]
              (is (int? a-eid) "agent A has a resolved eid to send as")
              ;; (1) explicit lookup-ref from
              (-> (agent/message! {:seon.agent.message/from    [:seon.agent/id a-id]
                                   :seon.agent.message/to      [:seon.agent/id b-id]
                                   :seon.agent.message/content "from lookup-ref"})
                  (.then #(from-resolves-to-a? % "lookup-ref"))
                  ;; (2) resolved eid (int) from
                  (.then (fn [_]
                           (agent/message! {:seon.agent.message/from    a-eid
                                            :seon.agent.message/to      [:seon.agent/id b-id]
                                            :seon.agent.message/content "from resolved eid"})))
                  (.then #(from-resolves-to-a? % "resolved-eid"))
                  ;; (3) default from = the ALS agent (a derived lookup-ref)
                  (.then (fn [_]
                           (db/with-agent a-id
                             (fn []
                               (agent/message!
                                 {:seon.agent.message/to      [:seon.agent/id b-id]
                                  :seon.agent.message/content "from als default"})))))
                  (.then #(from-resolves-to-a? % "als-default"))))))
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

;; ---------------------------------------------------------------------------
;; The message ↔ todo safety net (P4): a :human inbound auto-mints ONE
;; address-todo per agent recipient, ATOMIC in the message's tx, linked via
;; :seon.agent.todo/message. "Addressed" DERIVES from the linked todo's
;; completion — no stored handled? flag.
;; ---------------------------------------------------------------------------

(defn- todos-for
  "Address-todos owned by agent `aid`, message back-ref pulled."
  [conn aid]
  (->> (d/q '[:find (pull ?t [* {:seon.agent.todo/owner   [:seon.agent/id]
                                 :seon.agent.todo/from    [:seon.user/id :seon.agent/id]
                                 :seon.agent.todo/message [:seon.agent.message/id]}])
              :in $ ?aid
              :where
              [?o :seon.agent/id ?aid]
              [?t :seon.agent.todo/owner ?o]]
            @conn aid)
       (map first)
       vec))

(deftest human-message-auto-mints-a-linked-address-todo
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (agent/message!
                  {:seon.agent.message/from    agent/user-ref
                   :seon.agent.message/to      [:seon.agent/id a-id]
                   :seon.agent.message/content "please audit the schemas\nthen tell me what you find"})
                (.then
                  (fn [{mid :seon.agent.message/id}]
                    (let [ts (todos-for conn a-id)
                          t  (first ts)]
                      (is (= 1 (count ts)) "exactly ONE address-todo minted")
                      (is (= :open (:seon.agent.todo/status t)) "minted open")
                      (is (= a-id (get-in t [:seon.agent.todo/owner :seon.agent/id]))
                          "owned by the agent recipient")
                      (is (= "user" (get-in t [:seon.agent.todo/from :seon.user/id]))
                          "from = the human sender")
                      (is (= mid (get-in t [:seon.agent.todo/message :seon.agent.message/id]))
                          "linked to the SAME-TX message via :seon.agent.todo/message")
                      (is (= "please audit the schemas then tell me what you find"
                             (:seon.agent.todo/title t))
                          "title = clipped single-line preview (newline collapsed)")
                      (is (empty? (todos-for conn b-id))
                          "no todo for an un-addressed agent")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest clipped-title-is-bounded-with-ellipsis
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (agent/message!
                  {:seon.agent.message/from    agent/user-ref
                   :seon.agent.message/to      [:seon.agent/id a-id]
                   :seon.agent.message/content (apply str (repeat 200 "x"))})
                (.then
                  (fn [_]
                    (let [title (:seon.agent.todo/title (first (todos-for conn a-id)))]
                      (is (= 81 (count title)) "clipped to ~80 chars + the … glyph")
                      (is (re-find #"…$" title) "trailing ellipsis marks the cut")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest agent-message-mints-no-todo
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (db/with-agent b-id
                  (fn []
                    (agent/message!
                      {:seon.agent.message/to      [:seon.agent/id a-id]
                       :seon.agent.message/content "heads up: foo depends on qux"})))
                (.then
                  (fn [{mid :seon.agent.message/id}]
                    (let [[m] (filter #(= mid (:seon.agent.message/id %)) (pulled-msgs conn))]
                      (is (= :agent (:seon.agent.message/origin m))
                          "agent-originated ⇒ origin :agent"))
                    (is (empty? (todos-for conn a-id))
                        "agent→agent message mints NO address-todo"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest addressed-derives-from-the-linked-todos-completion
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (agent/message!
                  {:seon.agent.message/from    agent/user-ref
                   :seon.agent.message/to      [:seon.agent/id a-id]
                   :seon.agent.message/content "fix the failing test"})
                (.then
                  (fn [{mid :seon.agent.message/id}]
                    (let [t (first (todos-for conn a-id))]
                      (is (= :open (:seon.agent.todo/status t)) "unaddressed ⇒ linked todo open")
                      (todo/done! {:seon.agent.todo/id (:seon.agent.todo/id t)}))))
                (.then
                  (fn [_]
                    (let [t2 (first (todos-for conn a-id))]
                      (is (= :done (:seon.agent.todo/status t2))
                          "completing the todo ⇒ the message is addressed (derived)")
                      (is (string? (get-in t2 [:seon.agent.todo/message :seon.agent.message/id]))
                          "still linked to its message after completion")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest labels-resolve-by-ref-kind
  (testing "user / self / other-agent labels"
    (is (= "user" (agent/message-label {:seon.user/id "user"} a-id)))
    (is (= "assistant" (agent/message-label {:seon.agent/id a-id} a-id)))
    (is (= (str "agent-" b-id)
           (agent/message-label {:seon.agent/id b-id} a-id)))
    (is (= "unknown" (agent/message-label nil a-id)))))
