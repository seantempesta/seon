(ns seon.web.reactive.call-test
  "The /call security boundary + the namespace-routed invoke.

   (b) The capability gate (`capability-check` / `resolve-owning-agent` /
       `granted-fn?`) — pure fns of a db value. A granted home-ns fn
       resolves + is allowed; a fn NOT granted to the owning agent, a
       cross-agent/dead-agent namespace, and `fs`/core symbols are REFUSED
       (no owning agent or no `:seon.fn` row) — never invoked.

   (c) A /call that invokes a granted fn which transacts → the datom is
       written (the reactive push is the inspector feed's job). Proven
       in-process (ensure-bootstrap! + open-agent-conn! + transact :seon.ns
       / :seon.fn rows + replay-program-graph! + invoke!), the same harness
       the SCI tile tests use — no live pod."
  (:require
    [cljs.test :refer [deftest is testing async]]
    [seon.client :as client]
    [seon.ctx :as ctx]
    [seon.db :as db]
    [seon.repl :as repl]
    [seon.web.reactive.call :as call]))

;; A valid 14-char id (`:seon.db/id` is [:string {:min 14 :max 14}]).
(def ^:private agent-id "tst-2606260000")
(def ^:private home-ns (ctx/home-ns agent-id))            ; my.agent.tst-2606260000
(def ^:private home-kw (keyword (str home-ns)))           ; :my.agent.tst-2606260000

(def ^:private granted-sym (symbol (str home-ns) "set-purpose!"))

(def ^:private set-purpose-source
  ;; Transacts onto the agent's OWN entity. Uses (current-agent-id) — proving
  ;; invoke! ran the form inside the agent's with-agent scope.
  (str "(defn set-purpose! [p]\n"
       "  (seon.db/transact!\n"
       "    {:seon.db/tx-data [{:seon.agent/id (seon.db/current-agent-id)\n"
       "                        :seon.agent/purpose p}]}))"))

(defn- seed!
  "Transact the agent + its home ns + the one granted fn row."
  []
  (db/transact!
    {:seon.db/tx-data
     [{:seon.agent/id agent-id}
      {:seon.ns/name   home-kw
       :seon.ns/source (str "(ns " home-ns ")")}
      {:seon.fn/sym        (str granted-sym)
       :seon.fn/ns         {:seon.ns/name home-kw}
       :seon.fn/source     set-purpose-source
       :seon.fn/created-at (js/Date.)}]}))

;; ---------------------------------------------------------------------------
;; (b) Capability gate — pure, no bootstrap.
;; ---------------------------------------------------------------------------

(deftest capability-gate-allows-granted-refuses-everything-else
  (async done
    (-> (client/open-agent-conn!)
        (.then
          (fn [conn]
            (binding [db/*conn* conn]
              (.then
                (seed!)
                (fn [_]
                  (let [db @conn]
                    (testing "namespace IS the route — owning agent resolves from my.agent.<id>"
                      (is (= agent-id (call/resolve-owning-agent db granted-sym))))
                    (testing "fs / core / cross-agent namespaces resolve to NO owning agent"
                      (is (nil? (call/resolve-owning-agent db 'fs/readFileSync)))
                      (is (nil? (call/resolve-owning-agent db 'seon.client/start-agent!)))
                      (is (nil? (call/resolve-owning-agent db 'my.agent.nobody-here1/x))))
                    (testing "GRANTED — a home-ns fn the agent defined passes the gate"
                      (let [r (call/capability-check db granted-sym)]
                        (is (= agent-id (::call/agent-id r)))
                        (is (nil? (::call/refused r)))))
                    (testing "REFUSED — fs symbol (no owning agent), never reaches invoke"
                      (let [r (call/capability-check db 'fs/readFileSync)]
                        (is (some? (::call/refused r)))
                        (is (nil? (::call/agent-id r)))))
                    (testing "REFUSED — a symbol in the home ns with NO :seon.fn row"
                      (let [ghost (symbol (str home-ns) "not-a-real-fn")
                            r     (call/capability-check db ghost)]
                        (is (some? (::call/refused r)))
                        (is (nil? (::call/agent-id r)))
                        (is (false? (call/granted-fn? db agent-id ghost)))))
                    (testing "REFUSED — a fn in another agent's home ns (dead/absent)"
                      (let [r (call/capability-check db 'my.agent.someone-else9/do-it)]
                        (is (some? (::call/refused r)))
                        (is (nil? (::call/agent-id r)))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (c) Granted invoke transacts — the datom is written.
;; ---------------------------------------------------------------------------

(deftest call-invokes-granted-fn-and-it-transacts
  ;; The invoked fn transacts inside invoke!'s internal awaits, so the conn
  ;; must be ROOT-set! — CLJS `binding` does NOT survive await boundaries
  ;; (only the root binding / AsyncLocalStorage does; this is exactly how the
  ;; live pod root-set!s db/*conn* at boot). Mirrors seon.teachings-test.
  (async done
    (let [prev-conn db/*conn*
          finish    (fn [] (set! db/*conn* prev-conn) (done))]
      (-> (js/Promise.all #js [(repl/ensure-bootstrap!) (client/open-agent-conn!)])
          (.then
            (fn [res]
              (let [cs   (aget res 0)
                    conn (aget res 1)]
                (set! db/*conn* conn)
                (-> (seed!)
                    (.then
                      (fn [_]
                        (client/replay-program-graph!
                          {:conn conn :compile-state cs :agent-id agent-id})))
                    (.then
                      (fn [stats]
                        (testing "the agent ns + fn replay cleanly"
                          (is (= 0 (:seon.client/replay-n-fail stats))
                              (str "replay had failures — " (pr-str stats))))
                        (call/invoke! agent-id granted-sym ["hello from call"])))
                    (.then
                      (fn [env]
                        (testing "invoke returns an ok envelope"
                          (is (true? (::call/ok? env))
                              (str "invoke not ok — " (pr-str env))))
                        (testing "the granted fn transacted onto the agent — datom written"
                          (is (= "hello from call"
                                 (ffirst
                                   (db/query
                                     '[:find ?p :in $ ?id :where
                                       [?a :seon.agent/id ?id]
                                       [?a :seon.agent/purpose ?p]]
                                     @conn agent-id)))))))))))
          (.then (fn [_] (finish)))
          (.catch (fn [e] (is false (str "threw — " e)) (finish)))))))
