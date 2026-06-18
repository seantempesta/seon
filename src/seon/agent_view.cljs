(ns seon.agent-view
  "Per-agent filtered DB view (tx-log-as-context-v1.md §2).

   Every transact carries `:seon.db/agent-id` in its tx-meta when it
   originated inside `(seon.db/with-agent id ...)` — already shipped
   in commit 5a82742. To give agent A a view of 'just my universe',
   we wrap the conn in a `d/filter` that keeps a datom only when its
   tx is either core (no `:seon.db/agent-id`) or matches the
   given agent-id.

   Probe 1b confirmed `d/filter` works in datahike-cljs. The
   filtered db is a regular db value; `d/q` and `d/pull` respect it.

   This namespace returns a db value (not a conn). Callers pass it as
   `:seon.db/db` to read API calls."
  (:require
    [datahike.api :as d]
    [seon.db :as db]))

(defn- core-or-mine?
  "True when the tx at `tx-eid` should be visible to `agent-id`:
   - no `:seon.db/agent-id` stamped (core tx), OR
   - stamped with this agent's id, OR
   - `:seon.db/origin :core-seed` — boot-seed tx (entity-kind
     `:seon.schema` rows, my.kb.instruction rows, core `:seon.ns`/
     `:seon.fn` index). These are CORE data by definition, but
     `seon.client/start-agent!` runs the seed inside the booting
     agent's `with-agent` scope, so they arrive agent-stamped. Without
     this clause every OTHER agent's filtered view loses the kind
     schemas and `seon.render/render-entity-html` resolves no kind
     (2026-06-09 'no renderable entities' inspector bug — the seed
     datoms were fragmented across txs owned by DIFFERENT booting
     agents, so even the stamping agents saw only partial slices).

   entity-attr lookup respects history-augmented dbs; bare attr read
   against the tx-as-entity (datahike materializes tx-meta on the tx
   eid)."
  [db tx-eid agent-id]
  (let [tx-ent (d/entity db tx-eid)
        tx-aid (:seon.db/agent-id tx-ent)]
    (or (nil? tx-aid)
        (= tx-aid agent-id)
        (= :core-seed (:seon.db/origin tx-ent)))))

(defn agent-view
  "Return a filtered db value that scopes reads to `agent-id` plus
   core-wide tx. Core tx are never filtered out (they carry
   shared schema, instruction rows, handler registrations).

   Map-in, map-out (returns the db value under `:seon.db/db`)."
  {:malli/schema [:=> [:cat [:map [:seon.agent/id :string]
                                  [:seon.db/conn {:optional true} :seon.db/conn]]]
                       [:map [:seon.db/db :seon.db/db]]]}
  [{:seon.agent/keys [id] :seon.db/keys [conn]}]
  (let [c    (or conn db/*conn*)
        base @c
        ;; The agent's OWN entity eid — datoms ON it are always visible
        ;; regardless of which tx asserted them. Without this, an agent
        ;; whose entity was CREATED by another agent (agent A `message!`s
        ;; a not-yet-existing agent B, stub boots, …) cannot see its own
        ;; `:seon.agent/id` datom and every `[:seon.agent/id <me>]`
        ;; lookup (ctx-preview, assemble-context) throws.
        own-eid (try (:db/id (d/pull base '[:db/id] [:seon.agent/id id]))
                     (catch :default _ nil))
        ;; Per-tx verdict memo. The pred runs on EVERY datom access
        ;; through the FilteredDB, and `core-or-mine?` does a
        ;; `d/entity` (tx-meta read) each time — on the file-backed pod
        ;; store (A1, 2026-06-09) that made one inspector render issue
        ;; MILLIONS of konserve index reads and wedge the pod event
        ;; loop at 100% CPU (observed live). A tx's meta (`agent-id`,
        ;; `origin`) is immutable once committed, so the verdict is
        ;; cached per tx-eid for the lifetime of THIS filtered db value
        ;; — each distinct tx is judged exactly once.
        !tx-ok (atom {})
        pred (fn [db datom]
               (let [^js datom datom]
                 (or ;; Identity attrs are PUBLIC core facts —
                     ;; "agents know other agents purely by id"
                     ;; (messaging 1.5). Without this an agent can't
                     ;; label `agent-<id>` on messages from/to peers
                     ;; whose identity datom landed in the peer's own
                     ;; tx scope.
                     (contains? #{:seon.agent/id :seon.user/id} (.-a datom))
                     (let [tx (.-tx datom)]
                       (if-some [v (get @!tx-ok tx)]
                         v
                         (let [v (core-or-mine? db tx id)]
                           (swap! !tx-ok assoc tx v)
                           v)))
                     (and (some? own-eid) (= own-eid (.-e datom))))))
        filtered (d/filter base pred)]
    {:seon.db/db filtered}))
