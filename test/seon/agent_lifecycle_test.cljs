(ns seon.agent-lifecycle-test
  "Agent lifecycle (P3.5/#31): `:seon.agent/completed-at` + RESUME, DON'T
   MINT. Pins the invariants the boot path depends on:

     - `seon.agent/complete!` mirrors `seon.agent.todo/complete!` exactly:
       stamp `completed-at`, unknown id → fail envelope, already-completed
       → idempotent success; id defaults to the ALS-scoped agent.
     - un-complete is an EXPLICIT `[:db/retract …]` (absent = active).
     - `seon.client/resumable-agent-ids` — the boot resume query — returns
       every agent WITHOUT `completed-at`, sorted, and skips completed
       ones; empty store = genuine first boot (mint path).

   Tests open a FRESH `:memory` conn (via `seon.client/open-agent-conn!`,
   the same boot helper the pod uses) — nothing here touches the live
   agents.

   Run interactively via MCP eval:
     (require 'seon.agent-lifecycle-test :reload)
     (cljs.test/run-tests 'seon.agent-lifecycle-test)"
  (:require
    [cljs.test :refer [deftest is testing async]]
    [seon.agent :as agent]
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
  "Transact agent entities `a1` (active) + `a2` (active)."
  []
  (db/transact!
    {:seon.db/tx-data [{:seon.agent/id "aaa-2606101200" :seon.agent/state :idle}
                       {:seon.agent/id "bbb-2606101200" :seon.agent/state :idle}]}))

(deftest complete!-stamps-and-is-idempotent
  (async done
    (-> (with-conn
          (fn ^:async run []
            (await (seed-agents!))
            (testing "complete! stamps completed-at and answers ok"
              (let [env (await (agent/complete! {:seon.agent/id "aaa-2606101200"}))]
                (is (true? (:seon.agent/ok? env)))
                (is (= "aaa-2606101200" (:seon.agent/id env))))
              (is (inst? (:seon.agent/completed-at
                           (db/entity {:seon.db/ref [:seon.agent/id "aaa-2606101200"]})))
                  "completed-at is stamped as an inst"))
            (testing "already-completed is idempotent success"
              (let [env (await (agent/complete! {:seon.agent/id "aaa-2606101200"}))]
                (is (true? (:seon.agent/ok? env)))
                (is (= "aaa-2606101200" (:seon.agent/id env)))))
            (testing "unknown id → fail envelope (errors are values)"
              (let [env (await (agent/complete! {:seon.agent/id "zzz-2606101299"}))]
                (is (false? (:seon.agent/ok? env)))
                (is (string? (:seon.agent/error env)))))
            (testing "no id + no agent in scope → fail envelope"
              (let [env (await (agent/complete! {}))]
                (is (false? (:seon.agent/ok? env)))
                (is (string? (:seon.agent/error env)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest complete!-defaults-to-scoped-agent
  (async done
    (-> (with-conn
          (fn ^:async run []
            (await (seed-agents!))
            (await
              (db/with-agent "bbb-2606101200"
                (fn ^:async in-scope []
                  (let [env (await (agent/complete! {}))]
                    (is (true? (:seon.agent/ok? env)))
                    (is (= "bbb-2606101200" (:seon.agent/id env))
                        "id defaulted from the ALS scope, like reply!")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest resume-query-skips-completed-and-retract-restores
  (async done
    (-> (with-conn
          (fn ^:async run []
            (testing "empty store = genuine first boot (mint path)"
              (is (= [] (client/resumable-agent-ids @db/*conn*))))
            (await (seed-agents!))
            (testing "both active agents are resumable, sorted"
              (is (= ["aaa-2606101200" "bbb-2606101200"]
                     (client/resumable-agent-ids @db/*conn*))))
            (await (agent/complete! {:seon.agent/id "aaa-2606101200"}))
            (testing "completed agent is NOT resumed (history, not roster)"
              (is (= ["bbb-2606101200"]
                     (client/resumable-agent-ids @db/*conn*))))
            ;; Un-complete = explicit retract (absent = active; never nil).
            (let [env (await (db/transact!
                               {:seon.db/tx-data
                                [[:db/retract [:seon.agent/id "aaa-2606101200"]
                                  :seon.agent/completed-at]]}))]
              (is (true? (:seon.db/ok? env)) "retract transacts clean"))
            (testing "retracting completed-at makes the agent resumable again"
              (is (= ["aaa-2606101200" "bbb-2606101200"]
                     (client/resumable-agent-ids @db/*conn*))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
