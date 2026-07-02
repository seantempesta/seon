(ns seon.db.origin-guard-test
  "Origin-stamp tests — provenance is derived at the transact boundary.

   Contract under test — `seon.db.internal/derive-origin` via the public
   `transact!` path: `:seon.db/origin` is STAMPED from the ambient scope
   (`with-agent` / `with-tx-context`); callers never pass it.

   1. Agent scope + `:core-seed` tx-context claim → the committed tx
      carries `:agent` (managed origins are unforgeable from inside an
      agent scope).
   2. No agent scope + `:core-seed` tx-context → `:core-seed` commits
      (the legitimate core-writer path).
   3. Agent scope alone (no tx-context) → `:agent` is stamped.
   4. Agent scope + a non-managed tx-context origin (`:system`) →
      trusted as claimed (core code narrows its own agent-scoped
      writes).
   5. A caller-passed `:tx-meta` `:seon.db/origin` is never consulted —
      dropped when unscoped, overridden by the scope otherwise.

   Run via `seon.test.runner/run-vars` / run-block over MCP."
  (:require
    [cljs.test :as t :refer [deftest is testing async]]
    [datahike.api :as d]
    [seon.db :as db]
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

(defn- tx-entity
  "The tx ENTITY of envelope `env` (transacted with `:return-report?`)."
  [conn env]
  (let [report (:seon.db/tx-report env)
        tx-eid (.-tx ^js (first (:tx-data report)))]
    (d/entity @conn tx-eid)))

(defn- scoped-transact!
  "One `transact!` on `conn`, optionally inside `with-agent agent-id`
   and/or a `with-tx-context ctx`. Returns the envelope promise
   (with `:return-report?` so the tx entity is readable)."
  [conn {:keys [agent-id ctx tx-meta]}]
  (let [tx  (fn []
              (db/transact! (cond-> {:seon.db/tx-data        [{::name "x"}]
                                     :seon.db/conn           conn
                                     :seon.db/return-report? true}
                              tx-meta (assoc :seon.db/opts {:tx-meta tx-meta}))))
        run (if ctx
              (fn [] (db/with-tx-context ctx tx))
              tx)]
    (if agent-id
      (db/with-agent agent-id run)
      (run))))

(deftest agent-scoped-managed-claim-stamps-agent
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (-> (scoped-transact! conn {:agent-id "forger-agent-1"
                                             :ctx {:seon.db/origin :core-seed}})
                     (.then (fn [env]
                              (is (true? (:seon.db/ok? env)))
                              (testing "managed origin claim from agent scope → stamped :agent"
                                (let [tx-ent (tx-entity conn env)]
                                  (is (= :agent (:seon.db/origin tx-ent)))
                                  (is (= "forger-agent-1"
                                         (:seon.db/agent-id tx-ent)))))
                              (done))))))
        (.catch (fn [err]
                  (is false (str "unexpected rejection: " err))
                  (done))))))

(deftest unscoped-managed-claim-commits-as-claimed
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (-> (scoped-transact! conn {:ctx {:seon.db/origin :core-seed}})
                     (.then (fn [env]
                              (is (true? (:seon.db/ok? env)))
                              (testing "legitimate unscoped core writer → :core-seed commits"
                                (let [tx-ent (tx-entity conn env)]
                                  (is (= :core-seed (:seon.db/origin tx-ent)))
                                  (is (nil? (:seon.db/agent-id tx-ent)))))
                              (done))))))
        (.catch (fn [err]
                  (is false (str "unexpected rejection: " err))
                  (done))))))

(deftest bare-agent-scope-stamps-agent
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (-> (scoped-transact! conn {:agent-id "honest-agent-1"})
                     (.then (fn [env]
                              (is (true? (:seon.db/ok? env)))
                              (testing "agent scope with no tx-context → :agent stamped"
                                (let [tx-ent (tx-entity conn env)]
                                  (is (= :agent (:seon.db/origin tx-ent)))
                                  (is (= "honest-agent-1"
                                         (:seon.db/agent-id tx-ent)))))
                              (done))))))
        (.catch (fn [err]
                  (is false (str "unexpected rejection: " err))
                  (done))))))

(deftest agent-scoped-non-managed-claim-is-trusted
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 (-> (scoped-transact! conn {:agent-id "system-agent-1"
                                             :ctx {:seon.db/origin :system}})
                     (.then (fn [env]
                              (is (true? (:seon.db/ok? env)))
                              (testing "non-managed origin (:system) survives agent scope"
                                (is (= :system
                                       (:seon.db/origin (tx-entity conn env)))))
                              (done))))))
        (.catch (fn [err]
                  (is false (str "unexpected rejection: " err))
                  (done))))))

(deftest caller-tx-meta-origin-is-never-consulted
  (async done
    (-> (fresh-conn)
        (.then (fn [conn]
                 ;; Unscoped + caller-passed origin → dropped (no scope,
                 ;; no stamp; the caller's claim never reaches the store).
                 (-> (scoped-transact! conn {:tx-meta {:seon.db/origin :core-seed}})
                     (.then (fn [env]
                              (is (true? (:seon.db/ok? env)))
                              (testing "unscoped caller claim → NO origin on the tx"
                                (is (nil? (:seon.db/origin (tx-entity conn env)))))
                              ;; Agent scope + caller-passed origin → the
                              ;; scope's derived value wins.
                              (scoped-transact!
                                conn {:agent-id "forger-agent-2"
                                      :tx-meta  {:seon.db/origin :core-seed}})))
                     (.then (fn [env]
                              (is (true? (:seon.db/ok? env)))
                              (testing "agent-scoped caller claim → stamped :agent"
                                (is (= :agent
                                       (:seon.db/origin (tx-entity conn env)))))
                              (done))))))
        (.catch (fn [err]
                  (is false (str "unexpected rejection: " err))
                  (done))))))
