(ns seon.state
  "Holistic system-state reconcile — make the DB's MANAGED datoms match a
   desired set of entity-maps. ONE primitive ([[reconcile!]]) over the whole
   declarative-state surface (context blocks, routes, core entities): seed,
   config override, reset, and restore are all expressions of it — we write
   the reconcile once, never a loader per state area.

   The managed slice is defined by PROVENANCE, not a taxonomy: a row is
   managed iff its first-assertion tx refs a stable database process in the
   managed scope (for example boot or config); agent-authored REPL rows sit
   outside it and are NEVER touched. There are no
   entity 'kinds' — datahike has none. An entity is its attribute set,
   identity is a per-attribute `:db.unique/identity` property, and the
   managed/authored split is pure provenance. So reconcile takes NO kind
   argument and runs NO id-attr-registry loop: it operates in attribute
   space — presence, provenance, connection."
  (:require
    [clojure.set :as set]
    [seon.db :as db]
    [seon.schema :as schema]))

;; A desired entity-map MUST carry exactly one `:db.unique/identity` attr (the
;; upsert handle); `:map` is the honest schema (desired sets are heterogeneous
;; — routes, core entities, blocks), with the one-identity-attr invariant
;; enforced at runtime by [[reconcile!]] (errors-as-values), since Malli can't
;; express "has SOME identity attr" generically.
(schema/register! ::desired-entity :map)
(schema/register! ::desired [:vector ::desired-entity])
(schema/register! ::ok?       :boolean)
(schema/register! ::error     :string)
(schema/register! ::upserted  :int)
(schema/register! ::retracted :int)

(schema/register!
  ::reconcile-request
  [:map
   [::desired ::desired]
   [:seon.db/managed-scope :seon.db/managed-scope]
   [:seon.db/managed-identity-attrs :seon.db/managed-identity-attrs]
   [:seon.db/conn {:optional true} :seon.db/conn]])

(schema/register!
  ::reconcile-response
  [:or
   [:map [::ok? [:= true]]  [::upserted ::upserted] [::retracted ::retracted]]
   [:map [::ok? [:= false]] [::error ::error]]])

(defn- desired-identity
  "The single registered `:db.unique/identity` `[attr value]` a desired
   entity-map carries — datahike's find-or-create handle for the upsert. nil
   when the map carries ZERO or MORE-THAN-ONE identity attr: neither upserts
   safely (none allocates a fresh eid every run; two risk a Conflicting-upserts
   throw), so [[reconcile!]] rejects such a set rather than duplicate rows."
  [m]
  (let [ids (filterv schema/identity-attr? (keys m))]
    (when (= 1 (count ids))
      (let [a (first ids)] [a (get m a)]))))

(defn ^:async reconcile!
  "Make the MANAGED datoms match `:seon.state/desired`.

   `:seon.state/desired` is a vector of
   entity-maps. THREE attribute / connection moves — no entity 'kind' loop:

     1. UPSERT each desired map by its OWN `:db.unique/identity` attr —
        datahike's `upsert-eid` finds-or-creates by `[attr value]` (the same
        code path for every map, with no id-attr registry consulted).
     2. ENUMERATE the current managed population PURELY BY PROVENANCE
        ([[seon.db/managed-identities]] over the explicit process and identity
        attribute scopes).
     3. RETRACT (via `:db.fn/retractEntity`, which cascade-retracts component
        children) every managed entity whose identity is ABSENT from the
        desired set. Rows outside the managed process scope
        are NEVER touched.

   Upsert + retract land in ONE atomic transact; `stale` is diffed against the
   db value BEFORE the write. Writes inherit the ambient
   `seon.db/with-tx-context` user/process refs, so the caller establishes the
   appropriate boot or config process — the
   re-added rows then stay managed for the next reconcile. Errors are values:
   a desired map lacking exactly one identity attr, or a failed transact, comes
   back as `{:seon.state/ok? false :seon.state/error …}`.

     (db/with-tx-context {:seon.db/user [:seon.agent/id \"root\"]
                          :seon.db/process
                          [:seon.db.process/id :seon.db.process/boot]}
       (fn [] (seon.state/reconcile!
                {:seon.state/desired    [{:seon.route/name :main …} …]
                 :seon.db/managed-scope
                 #{:seon.db.process/boot :seon.db.process/config}
                 :seon.db/managed-identity-attrs
                 #{:seon.route/name :my.skills/name :seon.config/id}})))"
  {:malli/schema [:=> [:cat ::reconcile-request] ::reconcile-response]}
  [{::keys [desired]
    scope :seon.db/managed-scope
    identity-attrs :seon.db/managed-identity-attrs
    conn :seon.db/conn}]
  (let [identities   (mapv desired-identity desired)
        outside-scope (into #{}
                            (comp (keep first) (remove identity-attrs))
                            identities)]
    (cond
      (some nil? identities)
      {::ok? false
       ::error (str "reconcile!: every desired entity-map must carry exactly "
                    "ONE :seon.db/identity attribute (the upsert handle). "
                    "Offending maps' keys: "
                    (pr-str (mapv #(vec (keys %))
                                  (remove desired-identity desired))))}

      (seq outside-scope)
      {::ok? false
       ::error (str "reconcile!: desired identities fall outside "
                    ":seon.db/managed-identity-attrs: "
                    (pr-str (sort outside-scope)))}

      :else
      (let [desired-set (set identities)
            managed     (db/managed-identities
                          (cond-> {:seon.db/managed-scope scope
                                   :seon.db/managed-identity-attrs
                                   identity-attrs}
                            conn (assoc :seon.db/conn conn)))
            stale       (for [[e ids] managed
                              :when (empty? (set/intersection ids desired-set))]
                          e)
            tx-data     (into (vec desired)
                              (map (fn [e] [:db.fn/retractEntity e]))
                              stale)
            env         (await (db/transact!
                                 (cond-> {:seon.db/tx-data tx-data}
                                   conn (assoc :seon.db/conn conn))))]
        (if (false? (:seon.db/ok? env))
          {::ok? false
           ::error (str "reconcile! transact failed: "
                        (:seon.error/message (:seon.db/error env)))}
          {::ok?       true
           ::upserted  (count desired)
           ::retracted (count stale)})))))
