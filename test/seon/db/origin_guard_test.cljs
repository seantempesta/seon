(ns seon.db.origin-guard-test
  "Origin-forge guard tests (unit #24 item 3, verifier rec 2026-06-09).

   Contract under test — `seon.db.internal/warn-on-seed-origin-forge!` via the
   public `transact!` path:

   1. An AGENT-scoped tx claiming `:seon.db/origin :substrate-seed`
      bumps `seon.db.internal/!seed-origin-forge-count` (and console-warns).
   2. The guard is WARN-ONLY today: the tx still commits and the
      origin lands UNCHANGED as `:substrate-seed` (the boot-seed path
      still runs inside `with-agent` — see the enforcement TODO in
      seon.db). This test PINS the warn-only behavior so the eventual
      flip to enforcement consciously updates it.
   3. No agent scope + `:substrate-seed` origin → no count (the
      legitimate substrate path).
   4. Agent scope + non-seed origin → no count.

   Run via `seon.test.runner/run-vars` / run-block over MCP."
  (:require
    [cljs.test :as t :refer [deftest is testing async]]
    [datahike.api :as d]
    [seon.db :as db]
    [seon.db.internal :as internal]
    [seon.schema :as schema]))

(schema/register! ::name :string)

(defn- fresh-conn
  "Promise of a fresh :memory datahike conn (same pattern as
   seon.db.envelope-test) with the 7 tx-meta attrs installed —
   these tests transact INSIDE tx-context/agent scopes, so the
   auto-merged tx-meta needs its datahike schema present.
   `:keep-history? true` is REQUIRED: tx-meta-as-history is a silent
   no-op without it (the origin assertions read the tx entity)."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (d/transact! conn {:tx-data (db/tx-meta-datahike-schema)})
                     (.then (fn [_] conn))))))))

(defn- seed-origin-transact!
  "Run one transact! on `conn` with `:seon.db/origin :substrate-seed`
   tx-context, optionally inside a `with-agent` scope. Returns the
   envelope promise."
  [conn agent-id]
  (let [run (fn []
              (db/with-tx-context
                {:seon.db/origin :substrate-seed}
                (fn []
                  (db/transact! {:seon.db/tx-data [{::name "x"}]
                                 :seon.db/conn    conn}))))]
    (if agent-id
      (db/with-agent agent-id run)
      (run))))

(deftest agent-scoped-seed-origin-counts-but-commits-unchanged
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (reset! internal/!seed-origin-forge-count 0)
                 (-> (seed-origin-transact! conn "forger-agent-1")
                     (.then (fn [env]
                              (testing "tx still commits (warn-only)"
                                (is (true? (:seon.db/ok? env))))
                              (testing "forge counter bumped exactly once"
                                (is (= 1 @internal/!seed-origin-forge-count)))
                              (testing "origin lands UNCHANGED — warn-only, no override yet"
                                (let [report (:seon.db/tx-report env)
                                      tx-eid (.-tx ^js (first (:tx-data report)))
                                      tx-ent (d/entity @conn tx-eid)]
                                  (is (= :substrate-seed
                                         (:seon.db/origin tx-ent)))
                                  (is (= "forger-agent-1"
                                         (:seon.db/agent-id tx-ent)))))
                              (done))))))
        (.catch (fn [err]
                  (is false (str "unexpected rejection: " err))
                  (done))))))

(deftest substrate-scoped-seed-origin-does-not-count
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (reset! internal/!seed-origin-forge-count 0)
                 (-> (seed-origin-transact! conn nil)
                     (.then (fn [env]
                              (is (true? (:seon.db/ok? env)))
                              (testing "legitimate substrate seed → no warn count"
                                (is (zero? @internal/!seed-origin-forge-count)))
                              (done))))))
        (.catch (fn [err]
                  (is false (str "unexpected rejection: " err))
                  (done))))))

(deftest agent-scoped-non-seed-origin-does-not-count
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (reset! internal/!seed-origin-forge-count 0)
                 (-> (db/with-agent "honest-agent-1"
                       (fn []
                         (db/with-tx-context
                           {:seon.db/origin :agent}
                           (fn []
                             (db/transact! {:seon.db/tx-data [{::name "y"}]
                                            :seon.db/conn    conn})))))
                     (.then (fn [env]
                              (is (true? (:seon.db/ok? env)))
                              (testing "agent-origin tx in agent scope → no warn count"
                                (is (zero? @internal/!seed-origin-forge-count)))
                              (done))))))
        (.catch (fn [err]
                  (is false (str "unexpected rejection: " err))
                  (done))))))
