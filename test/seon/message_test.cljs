(ns seon.message-test
  "Unit tests for messaging codified (unit 1.5): `seon.agent/message!` +
   `reply!`, the from/to refs schema, hops derivation, the blank-content
   guard, the derived conversation (`seon.agent/messages` — from = me OR
   to ∋ me), and transcript labels by ref kind.

   All tests open a FRESH `:memory` datahike conn seeded with the pod's
   boot schema + a user entity + two agents — nothing here touches the
   live agent conn.

   Run interactively via MCP eval:
     (require 'seon.message-test :reload)
     (cljs.test/run-tests 'seon.message-test)"
  (:require
    [cljs.test :refer [deftest is testing async]]
    [datahike.api :as d]
    [seon.agent :as agent]
    [seon.client :as client]
    [seon.db :as db]))

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
  "All :seon.message rows in the conn, oldest-first, from/to pulled with
   their id attrs."
  [conn]
  (->> (d/q '[:find (pull ?m [* {:seon.message/from
                                 [:db/id :seon.user/id :seon.agent/id]
                                 :seon.message/to
                                 [:db/id :seon.user/id :seon.agent/id]}])
              :where [?m :seon.message/id _]]
            @conn)
       (map first)
       (sort-by #(.getTime ^js (:seon.message/at %)))
       vec))

;; ---------------------------------------------------------------------------
;; message! — fully-formed storage + boundary defaults.
;; ---------------------------------------------------------------------------

(deftest message-from-user-is-fully-formed-hops-zero
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (agent/message!
                  {:seon.message/from    agent/user-ref
                   :seon.message/to      [:seon.agent/id a-id]
                   :seon.message/content "hello agent A"})
                (.then
                  (fn [{ok?  :seon.message/ok?
                        mid  :seon.message/id
                        hops :seon.message/hops
                        :as  env}]
                    (is (true? ok?) "concise success envelope")
                    (is (string? mid) "response carries the message id")
                    (is (= 0 hops) "response carries the hops")
                    (is (not (contains? env :seon.db/tx-report))
                        "raw tx-report is OFF the agent surface")
                    (let [[m] (pulled-msgs conn)]
                      (testing "stored message is FULLY formed"
                        (is (= mid (:seon.message/id m))
                            "response id = the stored row's id")
                        (is (= "user" (:seon.user/id (:seon.message/from m))))
                        (is (= [a-id]
                               (mapv :seon.agent/id (:seon.message/to m)))
                            "single ref normalized to a vector")
                        (is (= "hello agent A" (:seon.message/content m)))
                        (is (some? (:seon.message/at m)))
                        (is (= 0 (:seon.message/hops m))
                            "from = the user ⇒ hops 0"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest message-defaults-from-als-scope-and-to-user
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (db/with-agent a-id
                  (fn []
                    (agent/message!
                      {:seon.message/content "report for my human"})))
                (.then
                  (fn [{ok? :seon.message/ok? hops :seon.message/hops}]
                    (is (true? ok?))
                    (is (= 1 hops) "response hops = stored hops")
                    (let [[m] (pulled-msgs conn)]
                      (is (= a-id (:seon.agent/id (:seon.message/from m)))
                          "from defaulted to the ALS agent")
                      (is (= ["user"]
                             (mapv :seon.user/id (:seon.message/to m)))
                          "to defaulted to THE user")
                      (is (= 1 (:seon.message/hops m))
                          "agent-originated, no waking msg ⇒ hops 0+1")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest blank-content-is-refused-with-an-envelope
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (agent/message! {:seon.message/from agent/user-ref
                                 :seon.message/content "   "})
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
            (-> (agent/message! {:seon.message/content "who am I?"})
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
   with one turn whose :seon.turn/woken-by points at it. Returns a
   Promise."
  [conn from-map hops]
  (d/transact!
    conn
    {:tx-data
     [{:seon.message/id      "MSGmsgtestWAKE"
       :seon.message/from    from-map
       :seon.message/to      [{:seon.agent/id a-id}]
       :seon.message/content "are you there?"
       :seon.message/at      (js/Date.)
       :seon.message/hops    hops}
      {:seon.agent/id a-id
       :seon.agent/sessions
       [{:seon.session/id "SESmsgtest0001"
         :seon.session/at (js/Date.)
         :seon.session/turns
         [{:seon.turn/id       "TRNmsgtest0001"
           :seon.turn/at       (js/Date. (+ (js/Date.now) 5))
           :seon.turn/status   :running
           :seon.turn/woken-by [:seon.message/id "MSGmsgtestWAKE"]}]}]}]}))

(deftest reply-targets-the-waking-sender-and-increments-hops
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (seed-woken-turn! conn {:seon.user/id "user"} 0)
                (.then (fn [_]
                         (db/with-agent a-id
                           (fn []
                             (agent/reply!
                               {:seon.message/content "yes — here"})))))
                (.then
                  (fn [{ok? :seon.message/ok? hops :seon.message/hops}]
                    (is (true? ok?))
                    (is (= 1 hops) "concise response carries hops 0 + 1")
                    (let [m (->> (pulled-msgs conn)
                                 (remove #(= "MSGmsgtestWAKE"
                                             (:seon.message/id %)))
                                 first)]
                      (is (= ["user"]
                             (mapv :seon.user/id (:seon.message/to m)))
                          "reply target = the waking message's from")
                      (is (= a-id (:seon.agent/id (:seon.message/from m))))
                      (is (= 1 (:seon.message/hops m))
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
                               {:seon.message/content "checked — totals ok"})))))
                (.then
                  (fn [{ok? :seon.message/ok? hops :seon.message/hops}]
                    (is (true? ok?))
                    (is (= 3 hops) "concise response carries the climbing hops")
                    (let [m (->> (pulled-msgs conn)
                                 (remove #(= "MSGmsgtestWAKE"
                                             (:seon.message/id %)))
                                 first)]
                      (is (= [b-id]
                             (mapv :seon.agent/id (:seon.message/to m)))
                          "reply goes back to agent B")
                      (is (= 3 (:seon.message/hops m))
                          "waking hops 2 + 1 — the chain marches to the cap")))))))
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
                   [{:seon.message/id "MSGmsgtestIN01"
                     :seon.message/from {:seon.user/id "user"}
                     :seon.message/to [{:seon.agent/id a-id}]
                     :seon.message/content "for A"
                     :seon.message/at (js/Date. (+ (js/Date.now) 1))
                     :seon.message/hops 0}
                    {:seon.message/id "MSGmsgtestOUT1"
                     :seon.message/from {:seon.agent/id a-id}
                     :seon.message/to [{:seon.user/id "user"}]
                     :seon.message/content "from A"
                     :seon.message/at (js/Date. (+ (js/Date.now) 2))
                     :seon.message/hops 1}
                    {:seon.message/id "MSGmsgtestB2B1"
                     :seon.message/from {:seon.agent/id b-id}
                     :seon.message/to [{:seon.user/id "user"}]
                     :seon.message/content "B's own thread"
                     :seon.message/at (js/Date. (+ (js/Date.now) 3))
                     :seon.message/hops 1}]})
                (.then
                  (fn [_]
                    (let [ids (mapv :seon.message/id
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
