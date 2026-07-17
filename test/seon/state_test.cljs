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
    [datahike.db :as datahike-db]
    [seon.agent :as agent]
    [seon.agent.ctx :as ctx]
    [seon.db :as db]
    [seon.db.coordinate :as coordinate]
    [seon.db.protocol :as protocol]
    [seon.schema :as schema]
    [seon.state :as state]))

;; --- scratch attrs: MIXED identity namespaces + a component parent ----------
;; Two identity attrs of DIFFERENT namespaces (proves reconcile keys off each
;; row's OWN identity attr, no taxonomy), plus a component-vector parent + a
;; non-identity child attr (proves the retract cascade).
(schema/register! :seon.state.scratch.a/id     [:string {:seon.db/identity true}])
(schema/register! :seon.state.scratch.a/label  :string)
(schema/register! :seon.state.scratch.a/tags   [:set :keyword])
(schema/register! :seon.state.scratch.b/id     [:string {:seon.db/identity true}])
(schema/register! :seon.state.scratch.b/note   :string)
(schema/register! :seon.state.scratch.parent/id   [:string {:seon.db/identity true}])
(schema/register! :seon.state.scratch.child/name  :keyword)
(schema/register! :seon.state.scratch.parent/kids
                  [:vector {:seon.db/component true} :seon.db/ref])
;; Registered but deliberately NOT installed by scratch-conn. The first
;; reconcile attempt installs these attrs, invalidating its frozen basis; the
;; bounded retry must recompile and commit the entity on attempt two.
(schema/register! :seon.state.scratch.late/id
                  [:string {:seon.db/identity true}])
(schema/register! :seon.state.scratch.late/value :string)

(def ^:private scratch-attrs
  [:seon.state.scratch.a/id :seon.state.scratch.a/label
   :seon.state.scratch.a/tags
   :seon.state.scratch.b/id :seon.state.scratch.b/note
   :seon.state.scratch.parent/id :seon.state.scratch.child/name
   :seon.state.scratch.parent/kids])

(def ^:private desired-state
  [{:seon.state.scratch.a/id "keep1"
    :seon.state.scratch.a/label "new"
    :seon.state.scratch.a/tags #{:new :kept}}
   ;; :seon.state.scratch.b/note is deliberately omitted: exact desired
   ;; state retracts the stale scalar from this retained entity.
   {:seon.state.scratch.b/id "keepB"}
   {:seon.state.scratch.a/id "fresh1"
    :seon.state.scratch.a/label "added"}
   ;; A retained component collection is replaced exactly and its old owned
   ;; children must cascade away.
   {:seon.state.scratch.parent/id "pkeep"
    :seon.state.scratch.parent/kids
    [{:seon.state.scratch.child/name :gk3}]}])

(deftest acquisition-joins-one-pull-to-many-provenance-rows
  (let [entity {:db/id 41
                :seon.state.scratch.parent/id "one-pull"
                :seon.state.scratch.parent/kids
                [{:db/id 42 :seon.state.scratch.child/name :child}]}
        rows ((deref #'state/acquisition-rows)
              [[41 entity]]
              [[41 103] [41 101] [41 102]]
              [[103 :seon.db.process/config]
               [101 :seon.db.process/boot]
               [102 :seon.db.process/config]])]
    (is (= 1 (count rows))
        "one pulled component tree survives many scalar provenance rows")
    (is (= entity (:seon.state/entity (first rows)))
        "the component payload is neither duplicated nor rebuilt")
    (is (= 101 (:seon.state/first-tx (first rows))))
    (is (= :seon.db.process/boot
           (:seon.state/first-process (first rows)))
        "the process follows the minimum transaction")))

(deftest acquisition-does-not-manage-an-unattributed-first-transaction
  (let [entity {:db/id 51 :seon.state.scratch.a/id "unattributed-first"}
        rows ((deref #'state/acquisition-rows)
              [[51 entity]]
              [[51 200] [51 201]]
              [[201 :seon.db.process/config]])]
    (is (= 200 (:seon.state/first-tx (first rows))))
    (is (nil? (:seon.state/first-process (first rows)))
        "a later config touch cannot claim an entity born unattributed")))

(deftest lookup-ref-acquisition-pulls-only-the-addressed-identity
  (let [database
        (d/db-with
          (datahike-db/empty-db
            {:seon.agent/id {:db/unique :db.unique/identity}})
          (mapv (fn [n]
                  {:seon.agent/id (if (= n 37) "target" (str "other-" n))})
                (range 100)))
        rows (d/q (deref #'state/reconcile-lookup-ref-query)
                  database
                  [[:seon.agent/id "target"]])]
    (is (= 1 (count rows))
        "one exact lookup pair does not pull every entity sharing its attr")
    (is (= "target" (get-in (first rows) [1 :seon.agent/id])))))

(deftest reconcile-acquires-once-and-fences-its-transaction
  (async done
    (let [point {::coordinate/database-id
                 #uuid "00000000-0000-0000-0000-000000000031"
                 ::coordinate/branch :db
                 ::coordinate/commit-id
                 #uuid "00000000-0000-0000-0000-000000000032"
                 ::coordinate/t 536870912}
          next-point (assoc point
                            ::coordinate/commit-id
                            #uuid "00000000-0000-0000-0000-000000000033"
                            ::coordinate/t 536870913)
          original-execute db/execute-many
          original-transact db/transact!
          calls (atom [])
          restore! (fn []
                     (set! db/execute-many original-execute)
                     (set! db/transact! original-transact))]
      (set! db/execute-many
            (fn [request]
              (swap! calls conj [:acquire request])
              (js/Promise.resolve
                {::db/coordinate point
                 ::db/results
                 [(protocol/success {::protocol/schema {}})
                  (protocol/success
                    {:datahike.query/result
                     [[41 {:db/id 41
                           :seon.state.scratch.a/id "stale"}]]})
                  (protocol/success
                    {:datahike.query/result [[41 100]]})
                  (protocol/success
                    {:datahike.query/result
                     [[100 :seon.db.process/boot]]})]})))
      (set! db/transact!
            (fn [request]
              (swap! calls conj [:transact request])
              (js/Promise.resolve
                {::db/ok? true ::db/coordinate next-point})))
      (-> (state/reconcile!
            {:seon.state/desired []
             :seon.db/managed-scope #{:seon.db.process/boot}
             :seon.db/managed-identity-attrs
             #{:seon.state.scratch.a/id}})
          (.then
            (fn [result]
              (let [[[_ acquire] [_ transact]] @calls]
                (is (= 4 (count (::db/members acquire)))
                    "one grouped read carries schema, entity, tx, and process members")
                (is (= point (::db/expected-coordinate transact))
                    "the exact acquisition coordinate fences the write")
                (is (= [[:db.fn/retractEntity 41]] (::db/tx-data transact)))
                (is (= next-point (:seon.state/coordinate result))))))
          (.catch (fn [error]
                    (is false (str "coordinate-fenced reconcile rejected: " error))))
          (.finally (fn [] (restore!) (done)))))))

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
                       [{:seon.state.scratch.a/id "keep1"
                         :seon.state.scratch.a/label "old"
                         :seon.state.scratch.a/tags #{:old :drop}}
                        {:seon.state.scratch.a/id "stale1" :seon.state.scratch.a/label "doomed"}
                        {:seon.state.scratch.b/id "keepB"
                         :seon.state.scratch.b/note "remove-me"}
                        {:seon.state.scratch.parent/id "pkeep"
                         :seon.state.scratch.parent/kids
                         [{:seon.state.scratch.child/name :gk-old-1}
                          {:seon.state.scratch.child/name :gk-old-2}]}
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
                                desired-state
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
                      (is (true? (:seon.state/changed? res)))
                      (is (pos? (:seon.state/operations res)))
                      (is (= (db/head-coordinate db)
                             (:seon.state/coordinate res))
                          "a changed reconcile returns its complete point")
                      ;; UPDATE — existing managed row's scalar attr changes
                      (is (= "new" (label "keep1"))
                          "an existing managed row is UPDATED in place (upsert by identity)")
                      ;; ADD — new desired row appears
                      (is (contains? a-ids "fresh1") "a NEW desired row is added")
                      (is (= "added" (label "fresh1")))
                      (is (= #{:new :kept}
                             (:seon.state.scratch.a/tags
                               (db/entity db [:seon.state.scratch.a/id
                                              "keep1"])))
                          "cardinality-many values match desired exactly")
                      ;; KEEP — a managed row under a DIFFERENT identity ns survives
                      (is (= #{"keepB"} b-ids)
                          "a kept managed row (different identity namespace) survives")
                      (is (not (contains?
                                 (db/entity db
                                            [:seon.state.scratch.b/id "keepB"])
                                 :seon.state.scratch.b/note))
                          "an omitted scalar is retracted from a retained entity")
                      ;; RETRACT-STALE — managed row absent from desired is gone
                      (is (not (contains? a-ids "stale1"))
                          "a stale managed row is RETRACTED")
                      (is (= #{"pkeep"}
                             (set (d/q '[:find [?v ...]
                                         :where
                                         [?e :seon.state.scratch.parent/id ?v]]
                                       db)))
                          "the stale component-parent is retracted")
                      ;; CASCADE — the stale parent's children are gone, no orphans
                      (is (= #{:gk3}
                             (set (d/q '[:find [?n ...]
                                         :where
                                         [?c :seon.state.scratch.child/name ?n]]
                                       db)))
                          "stale and replaced component children cascade; only desired remains")
                      ;; LEAVE-AUTHORED-ALONE — REPL row untouched
                      (is (contains? a-ids "agent1")
                          "a REPL-authored row is PRESERVED outside the managed scope")
                      (is (= "mine" (label "agent1"))
                          "…and is left completely unchanged")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "unexpected rejection: " e)) (done))))))

(deftest reconcile-converged-state-is-a-no-op
  (async done
    (let [!conn   (atom nil)
          !before (atom nil)
          request (fn [conn]
                    {:seon.state/desired desired-state
                     :seon.db/managed-scope
                     #{:seon.db.process/boot :seon.db.process/config}
                     :seon.db/managed-identity-attrs
                     #{:seon.state.scratch.a/id
                       :seon.state.scratch.b/id
                       :seon.state.scratch.parent/id}
                     :seon.db/conn conn})]
      (-> (scratch-conn)
          (.then
            (fn [conn]
              (reset! !conn conn)
              (db/with-tx-context
                {:seon.db/user [:seon.agent/id "root"]
                 :seon.db/process
                 [:seon.db.process/id :seon.db.process/boot]}
                (fn [] (state/reconcile! (request conn))))))
          (.then
            (fn [first-result]
              (is (true? (:seon.state/changed? first-result)))
              (reset! !before (db/basis-t @@!conn))
              (state/reconcile! (request @!conn))))
          (.then
            (fn [again]
              (is (true? (:seon.state/ok? again)))
              (is (false? (:seon.state/changed? again)))
              (is (zero? (:seon.state/operations again)))
              (is (= (db/head-coordinate @@!conn)
                     (:seon.state/coordinate again)))
              (is (= @!before (db/basis-t @@!conn))
                  "converged reconcile submits no transaction")
              (done)))
          (.catch (fn [e]
                    (is false (str "unexpected rejection: " e))
                    (done)))))))

(deftest reconcile-empty-many-is-equivalent-to-absent
  (async done
    (let [!conn (atom nil)
          request
          (fn [conn]
            {:seon.state/desired
             [{:seon.state.scratch.a/id "empty-many"
               :seon.state.scratch.a/tags #{}}]
             :seon.db/managed-scope #{:seon.db.process/boot}
             :seon.db/managed-identity-attrs
             #{:seon.state.scratch.a/id}
             :seon.db/conn conn})]
      (-> (scratch-conn)
          (.then
            (fn [conn]
              (reset! !conn conn)
              (db/with-tx-context
                {:seon.db/user [:seon.agent/id "root"]
                 :seon.db/process
                 [:seon.db.process/id :seon.db.process/boot]}
                (fn [] (state/reconcile! (request conn))))))
          (.then
            (fn [first-result]
              (is (true? (:seon.state/changed? first-result)))
              ;; The identity lands, while Datahike correctly omits the empty
              ;; cardinality-many attribute.
              (is (not (contains?
                         (db/entity @@!conn
                                    [:seon.state.scratch.a/id "empty-many"])
                         :seon.state.scratch.a/tags)))
              (state/reconcile! (request @!conn))))
          (.then
            (fn [again]
              (is (true? (:seon.state/ok? again)))
              (is (false? (:seon.state/changed? again)))
              (is (zero? (:seon.state/operations again)))
              (done)))
          (.catch (fn [error]
                    (is false (str "unexpected rejection: " error))
                    (done)))))))

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

(deftest reconcile-recompiles-after-first-use-schema-install
  (async done
    (-> (scratch-conn)
        (.then
          (fn [conn]
            (db/with-tx-context
              {:seon.db/user [:seon.agent/id "root"]
               :seon.db/process
               [:seon.db.process/id :seon.db.process/boot]}
              (fn []
                (state/reconcile!
                  {:seon.state/desired
                   [{:seon.state.scratch.late/id "late"
                     :seon.state.scratch.late/value "landed"}]
                   :seon.db/managed-scope #{:seon.db.process/boot}
                   :seon.db/managed-identity-attrs
                   #{:seon.state.scratch.late/id}
                   :seon.db/conn conn})))))
        (.then
          (fn [result]
            (is (true? (:seon.state/ok? result)))
            (is (= 2 (:seon.state/attempts result))
                "schema install changes the head; retry recompiles once")
            (done)))
        (.catch (fn [e]
                  (is false (str "unexpected rejection: " e))
                  (done))))))

(deftest reconcile-does-not-take-over-an-unmanaged-identity
  (async done
    (let [!conn (atom nil)
          !basis (atom nil)]
      (-> (scratch-conn)
          (.then
            (fn [conn]
              (reset! !conn conn)
              (db/with-tx-context
                {:seon.db/user [:seon.user/id "user"]
                 :seon.db/process
                 [:seon.db.process/id :seon.db.process/repl]}
                (fn []
                  (db/transact!
                    {:seon.db/conn conn
                     :seon.db/tx-data
                     [{:seon.state.scratch.a/id "owned-elsewhere"
                       :seon.state.scratch.a/label "preserve"}]})))))
          (.then
            (fn [_]
              (reset! !basis (db/basis-t @@!conn))
              (state/reconcile!
                {:seon.state/desired
                 [{:seon.state.scratch.a/id "owned-elsewhere"
                   :seon.state.scratch.a/label "must-not-land"}]
                 :seon.db/managed-scope #{:seon.db.process/boot}
                 :seon.db/managed-identity-attrs
                 #{:seon.state.scratch.a/id}
                 :seon.db/conn @!conn})))
          (.then
            (fn [result]
              (is (false? (:seon.state/ok? result)))
              (is (= @!basis (db/basis-t @@!conn))
                  "authority collision creates no transaction")
              (is (= "preserve"
                     (:seon.state.scratch.a/label
                       (db/entity @@!conn
                                  [:seon.state.scratch.a/id
                                   "owned-elsewhere"])))
                  "the unmanaged entity is unchanged")
              (done)))
          (.catch (fn [e]
                    (is false (str "unexpected rejection: " e))
                    (done)))))))

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
