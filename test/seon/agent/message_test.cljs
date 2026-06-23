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
