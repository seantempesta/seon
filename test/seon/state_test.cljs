(ns seon.state-test
  "Contract tests for `seon.state/reconcile!` (the holistic declarative-state
  sync primitive) and the #35 component-cascade fix in
   `seon.agent.ctx/upsert-ctx-tx`.

   reconcile! is exercised on a FRESH :memory conn seeded with mixed-process,
   MIXED-identity-attr rows (the manage-by-provenance contract). The #35 proof
   drives the REAL `install!` path on a fresh agent conn and counts orphaned
   block entities before/after a block-set replace.

   All on fresh :memory conns — never the live agent conn."
  (:require
    [cljs.test :refer [deftest is async]]
    [datahike.api :as d]
    [seon.agent :as agent]
    [seon.agent.ctx :as ctx]
    [seon.client :as client]
    [seon.db :as db]
    [seon.schema :as schema]
    [seon.state :as state]))

;; --- scratch attrs: MIXED identity namespaces + a component parent ----------
;; Two identity attrs of DIFFERENT namespaces (proves reconcile keys off each
;; row's OWN identity attr, no taxonomy), plus a component-vector parent + a
;; non-identity child attr (proves the retract cascade).
(schema/register! :seon.state.scratch.a/id     [:string {:seon.db/identity true}])
(schema/register! :seon.state.scratch.a/label  :string)
(schema/register! :seon.state.scratch.b/id     [:string {:seon.db/identity true}])
(schema/register! :seon.state.scratch.parent/id   [:string {:seon.db/identity true}])
(schema/register! :seon.state.scratch.child/name  :keyword)
(schema/register! :seon.state.scratch.parent/kids
                  [:vector {:seon.db/component true} :seon.db/ref])

(def ^:private scratch-attrs
  [:seon.state.scratch.a/id :seon.state.scratch.a/label
   :seon.state.scratch.b/id
   :seon.state.scratch.parent/id :seon.state.scratch.child/name
   :seon.state.scratch.parent/kids])

(defn- scratch-conn
  "Promise of a fresh :memory conn with provenance + the scratch attr schema
   installed. `:keep-history? true` is REQUIRED — provenance reads
   the tx entity."
  []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then
          (fn ^:async install [conn]
            (await (db/ensure-provenance! {:seon.db/conn conn}))
            (await
              (db/with-tx-context
                {:seon.db/user [:seon.agent/id "root"]
                 :seon.db/process
                 [:seon.db.process/id :seon.db.process/boot]}
                (fn []
                  (db/transact!
                    {:seon.db/conn conn
                     :seon.db/tx-data
                     (db/malli->datahike-schema scratch-attrs)}))))
            conn)))))

;; ============================================================
;; reconcile! — upsert / add / retract-stale / leave-authored-alone +
;; the component-parent retract cascade.
;; ============================================================

(deftest reconcile-syncs-managed-population
  (async done
    (-> (scratch-conn)
        (.then
          (fn [conn]
            (-> ;; seed managed rows (mixed identity attrs) through boot
                (db/with-tx-context
                  {:seon.db/user [:seon.agent/id "root"]
                   :seon.db/process
                   [:seon.db.process/id :seon.db.process/boot]}
                  (fn []
                    (db/transact!
                      {:seon.db/conn conn
                       :seon.db/tx-data
                       [{:seon.state.scratch.a/id "keep1"  :seon.state.scratch.a/label "old"}
                        {:seon.state.scratch.a/id "stale1" :seon.state.scratch.a/label "doomed"}
                        {:seon.state.scratch.b/id "keepB"}
                        {:seon.state.scratch.parent/id "pstale"
                         :seon.state.scratch.parent/kids
                         [{:seon.state.scratch.child/name :gk1}
                          {:seon.state.scratch.child/name :gk2}]}]})))
                ;; seed a human/repl row — OUTSIDE the managed process scope
                (.then (fn [_]
                         (db/with-tx-context
                           {:seon.db/user [:seon.user/id "user"]
                            :seon.db/process
                            [:seon.db.process/id :seon.db.process/repl]}
                           (fn []
                             (db/transact!
                               {:seon.db/conn conn
                                :seon.db/tx-data
                                [{:seon.state.scratch.a/id "agent1"
                                  :seon.state.scratch.a/label "mine"}]})))))
                ;; RECONCILE to a new desired set (under a managed process)
                (.then (fn [_]
                         (db/with-tx-context
                           {:seon.db/user [:seon.agent/id "root"]
                            :seon.db/process
                            [:seon.db.process/id :seon.db.process/boot]}
                           (fn []
                             (state/reconcile!
                               {:seon.state/desired
                                [{:seon.state.scratch.a/id "keep1"
                                  :seon.state.scratch.a/label "new"}      ; UPDATE
                                 {:seon.state.scratch.b/id "keepB"}        ; KEEP
                                 {:seon.state.scratch.a/id "fresh1"
                                  :seon.state.scratch.a/label "added"}]    ; ADD
                                :seon.db/managed-scope
                                #{:seon.db.process/boot
                                  :seon.db.process/config}
                                :seon.db/managed-identity-attrs
                                #{:seon.state.scratch.a/id
                                  :seon.state.scratch.b/id
                                  :seon.state.scratch.parent/id}
                                :seon.db/conn conn})))))
                (.then
                  (fn [res]
                    (let [db    @conn
                          a-ids (set (d/q '[:find [?v ...]
                                            :where [?e :seon.state.scratch.a/id ?v]] db))
                          b-ids (set (d/q '[:find [?v ...]
                                            :where [?e :seon.state.scratch.b/id ?v]] db))
                          label (fn [v] (d/q '[:find ?l .
                                               :in $ ?v
                                               :where [?e :seon.state.scratch.a/id ?v]
                                                      [?e :seon.state.scratch.a/label ?l]] db v))]
                      ;; envelope
                      (is (true? (:seon.state/ok? res)) "reconcile! success envelope")
                      (is (= 3 (:seon.state/upserted res)) "3 desired maps upserted")
                      (is (= 2 (:seon.state/retracted res))
                          "2 stale managed entities retracted (stale1 + pstale)")
                      ;; UPDATE — existing managed row's scalar attr changes
                      (is (= "new" (label "keep1"))
                          "an existing managed row is UPDATED in place (upsert by identity)")
                      ;; ADD — new desired row appears
                      (is (contains? a-ids "fresh1") "a NEW desired row is added")
                      (is (= "added" (label "fresh1")))
                      ;; KEEP — a managed row under a DIFFERENT identity ns survives
                      (is (= #{"keepB"} b-ids)
                          "a kept managed row (different identity namespace) survives")
                      ;; RETRACT-STALE — managed row absent from desired is gone
                      (is (not (contains? a-ids "stale1"))
                          "a stale managed row is RETRACTED")
                      (is (empty? (d/q '[:find [?v ...]
                                         :where [?e :seon.state.scratch.parent/id ?v]] db))
                          "the stale component-parent is retracted")
                      ;; CASCADE — the stale parent's children are gone, no orphans
                      (is (empty? (d/q '[:find [?n ...]
                                         :where [?c :seon.state.scratch.child/name ?n]] db))
                          "the stale parent's component children cascade-retract — NO orphans")
                      ;; LEAVE-AUTHORED-ALONE — REPL row untouched
                      (is (contains? a-ids "agent1")
                          "a REPL-authored row is PRESERVED outside the managed scope")
                      (is (= "mine" (label "agent1"))
                          "…and is left completely unchanged")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "unexpected rejection: " e)) (done))))))

(deftest reconcile-rejects-identity-less-desired-map
  ;; A desired map carrying NO :db.unique/identity attr would allocate a fresh
  ;; eid every run (duplicate forever). reconcile! refuses such a set as a
  ;; value, never partially applying it.
  (async done
    (-> (scratch-conn)
        (.then
          (fn [conn]
            (state/reconcile!
              {:seon.state/desired    [{:seon.state.scratch.a/label "no-identity-attr"}]
               :seon.db/managed-scope #{:seon.db.process/boot
                                        :seon.db.process/config}
               :seon.db/managed-identity-attrs
               #{:seon.state.scratch.a/id}
               :seon.db/conn          conn})))
        (.then (fn [res]
                 (is (false? (:seon.state/ok? res))
                     "a desired map with no identity attr → error value, not a duplicate row")
                 (is (string? (:seon.state/error res)) "carries an explanatory error string")
                 (done)))
        (.catch (fn [e] (is false (str "unexpected rejection: " e)) (done))))))

;; ============================================================
;; #35 — upsert-ctx-tx component cascade (the orphan fix).
;; ============================================================

(defn- agent-conn
  "Promise of a fresh isolated conn with provenance and full pod schema."
  []
  (client/open-agent-conn!))

(defn- with-agent-conn
  "Fresh agent conn `set!` as the root `db/*conn*` for `body` (conn → Promise),
   prior root restored after (root set!, not `binding` — CLJS dynamic bindings
   pop at the first microtask boundary)."
  [body]
  (-> (agent-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(defn- install-doctrine!
  [content]
  (db/with-agent "AGTstatecasc01"
    (fn ^:async []
      (ctx/install! {:seon.agent.ctx/name     :doctrine
                     :seon.agent.ctx/priority 15
                     :seon.render/ai          content}))))

(deftest upsert-ctx-replace-cascades-no-orphans
  ;; Each install! REPLACES the agent's whole :seon.agent/ctx (retract the
  ;; component vector, re-add the kept+new blocks). With the #35 fix
  ;; (:db.fn/retractAttribute) the prior child block entities cascade-retract,
  ;; so the store holds EXACTLY the live set. Plain :db/retract only severed
  ;; the agent→block edges and ORPHANED the children (total block datoms grew
  ;; on every install!). We replace :doctrine three times and assert the store
  ;; never accumulates dead block rows.
  (async done
    (-> (with-agent-conn
          (fn [_conn]
            (-> (agent/create! {:seon.agent/id "AGTstatecasc01"})
                (.then (fn [_] (install-doctrine! "v1")))
                (.then (fn [_] (install-doctrine! "v2")))
                (.then (fn [_] (install-doctrine! "v3")))
                (.then
                  (fn [_]
                    (let [live      (ctx/ctx-entities {:seon.agent/id "AGTstatecasc01"})
                          live-n    (count live)
                          total     (or (db/query '[:find (count ?e) .
                                                    :where [?e :seon.agent.ctx/name]])
                                        0)
                          doctrines (filter #(= :doctrine (:seon.agent.ctx/name %)) live)]
                      (is (pos? live-n) "the agent has live context blocks")
                      (is (= live-n total)
                          "NO orphaned block entities — the component cascade fires (#35); plain :db/retract left total > live")
                      (is (= 1 (count doctrines))
                          "re-installing a name replaces — exactly one :doctrine block")
                      (is (= "v3" (:seon.render/ai (first doctrines)))
                          "the surviving block carries the latest content")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "unexpected rejection: " e)) (done))))))
