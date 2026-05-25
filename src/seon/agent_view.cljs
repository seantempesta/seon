(ns seon.agent-view
  "Per-agent filtered DB view (tx-log-as-context-v1.md §2).

   Every transact carries `:seon.db/agent-id` in its tx-meta when it
   originated inside `(seon.db/with-agent id ...)` — already shipped
   in commit 5a82742. To give agent A a view of 'just my universe',
   we wrap the conn in a `d/filter` that keeps a datom only when its
   tx is either substrate (no `:seon.db/agent-id`) or matches the
   given agent-id.

   Probe 1b confirmed `d/filter` works in datahike-cljs. The
   filtered db is a regular db value; `d/q` and `d/pull` respect it.

   This namespace returns a db value (not a conn). Callers pass it as
   `:seon.db/db` to read API calls."
  (:require
    [datahike.api :as d]
    [seon.db :as db]))

(defn- tx-agent-id
  "Resolve the `:seon.db/agent-id` of a tx eid against the given db.
   Returns the agent-id string, or nil for substrate tx (no agent-id
   stamped). `:seon.db/agent-id` is a string scalar, not a ref, so a
   simple datom lookup suffices."
  [db tx-eid]
  ;; entity-attr lookup respects history-augmented dbs; bare attr read
  ;; against the tx-as-entity (datahike materializes tx-meta on the tx
  ;; eid).
  (:seon.db/agent-id (d/entity db tx-eid)))

(defn agent-view
  "Return a filtered db value that scopes reads to `agent-id` plus
   substrate-wide tx. Substrate tx are never filtered out (they carry
   shared schema, sticky entities, handler registrations).

   Map-in, map-out (returns the db value under `:seon.db/db`)."
  {:malli/schema [:=> [:cat [:map [:seon.agent/id :string]
                                  [:seon.db/conn {:optional true} :any]]]
                       [:map [:seon.db/db :any]]]}
  [{:seon.agent/keys [id] :seon.db/keys [conn]}]
  (let [c    (or conn db/*conn*)
        base @c
        pred (fn [db datom]
               (let [^js datom datom
                     tx     (.-tx datom)
                     tx-aid (tx-agent-id db tx)]
                 (or (nil? tx-aid) (= tx-aid id))))
        filtered (d/filter base pred)]
    {:seon.db/db filtered}))
