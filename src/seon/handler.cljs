(ns seon.handler
  "Handler registry — `register!` is the one verb (unified-loop-v1.md §1 D3).

   A handler is a DB entity that says 'when datoms with attribute X
   land, call symbol F'. Core-wide handlers carry no agent;
   per-agent handlers carry `:seon.handler/agent`. The composite-tuple
   identity on `[name agent]` upserts on re-registration so editing a
   handler is one transact, not retract-then-add.

   This namespace is intentionally tiny:
     - schema registrations (the entity shape + the register! request/response)
     - `register!` — map-in, map-out, one transact
     - `query-handlers` — read all live handlers for a given scope

   It does NOT install a dispatcher. The dispatcher (a `d/listen!`
   that fans out tx → handler → effect) lives in `seon.runtime` and
   reads from this registry. We can register handlers and never
   install the dispatcher (the v0 scenario — manual fire from the
   REPL); that's a feature, not a bug."
  (:require
    [datahike.api :as d]
    [seon.db :as db]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas — registration order matters. seon.db's malli→datahike
;; bridge resolves keyword refs at lookup time, so referenced shapes
;; (`:seon.handler.match/attr`, `:seon.handler/name`) must be
;; registered BEFORE the entity-shape schemas that point at them.
;; The seon.db schema-load guard catches mis-ordering with a clear
;; message if we get it wrong.
;; ============================================================

;; Identity name — keyword, e.g. `:wake/on-message`. NOT unique on its
;; own because two agents may want the same handler-name with different
;; per-agent fns. Composite identity `[name agent]` does the dedup.
(schema/register! :seon.handler/name      :keyword)

;; Owner. Nil ⇒ core-wide (the dispatcher scopes via the tx's
;; matched-datom). Otherwise a lookup-ref to a `:seon.agent` entity.
;; Stored as a ref so retracting the agent cascades the handler.
(schema/register! :seon.handler/agent     :seon.db/ref)

;; Match: today, attr (always) + value? (optional). The dispatcher
;; will look up handlers by attr first, then narrow by value if set.
;; Richer match-shapes (predicates, joins) are deferred per the spec.
(schema/register! :seon.handler.match/attr   :keyword)
(schema/register! :seon.handler.match/value? [:or :string :keyword :int :inst
                                                  :uuid :boolean :seon.db/ref])

;; The fn to call. Fully-qualified symbol resolved at dispatch time
;; via `seon.eval/lookup-value` (same path render uses). Agent-defined
;; fns work uniformly because the lookup walks globalThis.
(schema/register! :seon.handler/fn        :symbol)

;; Origins this handler accepts. Set so dispatcher can do (contains?).
;; Default (omitted on register!): #{:user :agent :system} — skips
;; :handler origin so a handler that emits {:tx ...} doesn't re-fire
;; itself.
(schema/register! :seon.handler/on-origin [:set [:enum :user :agent :system :handler :replay]])

;; Higher priority handlers run first. Default 0.
(schema/register! :seon.handler/priority  :int)

;; When the handler was last upserted. Renderers can show "freshest
;; first" lists; agents can see what they just changed.
(schema/register! :seon.handler/updated-at :inst)

;; `:seon.handler/match` is conceptually a sub-map but datahike has
;; no `:db.type/map`. We INLINE the match attrs (`:seon.handler.match/attr`
;; + `:seon.handler.match/value?`) directly on the handler entity at
;; persist time, while the request-shape schema still carries the
;; nested `:seon.handler/match` map for caller ergonomics. `register!`
;; flattens before transact.
(schema/register! :seon.handler/match
  [:map
   [:seon.handler.match/attr   :seon.handler.match/attr]
   [:seon.handler.match/value? {:optional true} :seon.handler.match/value?]])

;; Composite-tuple identity. Probe 1a confirmed this works in
;; datahike-cljs including with nil components — so core handlers
;; (agent nil) and agent handlers ([:seon.agent/id _]) live in the
;; same attr without colliding on name alone.
;;
;; NOTE: we register `:seon.handler/key` as the tuple. The bridge
;; doesn't currently know about `:db/tupleAttrs`, so we declare this
;; attr's datahike schema by hand at bootstrap time via
;; `bootstrap-schema!` below. The Malli registration is here only so
;; the transact-gate doesn't reject the attr.
(schema/register! :seon.handler/key [:tuple :seon.handler/name :seon.db/ref])

;; Request / response shapes for register!.
(schema/register! :seon.handler/register!-request
  [:map
   [:seon.handler/name      :seon.handler/name]
   [:seon.handler/agent     {:optional true} :seon.handler/agent]
   [:seon.handler/match     :seon.handler/match]
   [:seon.handler/fn        :seon.handler/fn]
   [:seon.handler/on-origin {:optional true} :seon.handler/on-origin]
   [:seon.handler/priority  {:optional true} :seon.handler/priority]])

(schema/register! :seon.handler/register!-response
  [:map
   [:seon.handler/registered? :boolean]
   [:seon.handler/key         {:optional true} [:tuple :keyword [:maybe :seon.db/ref]]]])

;; ============================================================
;; Bootstrap — declare the composite-tuple identity directly to
;; datahike. The Malli→datahike bridge doesn't yet emit
;; `:db/tupleAttrs`, so this is done by hand (one-time, idempotent).
;; ============================================================

(defn ^:async bootstrap-schema!
  "Declare the `:seon.handler/key` composite-tuple identity AND the
   per-attr datahike schema entries for the handler attrs. Idempotent:
   datahike upserts on `:db/ident`, so re-running is cheap. Called
   once at pod boot (or at first `register!` if the bootstrap step
   hasn't run yet).

   Returns a Promise resolving to `{:seon.handler/bootstrapped? true}`."
  []
  (let [conn db/*conn*
        ;; Attrs we need datahike to know about. Most are emitted by
        ;; the bridge; we re-emit here so this fn is a single source of
        ;; truth (and so a forgotten boot-time schema-derive doesn't
        ;; leave the registry half-built).
        attrs [{:db/ident :seon.handler/name
                :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
               {:db/ident :seon.handler/agent
                :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
               {:db/ident :seon.handler.match/attr
                :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
               {:db/ident :seon.handler.match/value?
                :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
               {:db/ident :seon.handler/fn
                :db/valueType :db.type/symbol :db/cardinality :db.cardinality/one}
               {:db/ident :seon.handler/on-origin
                :db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
               {:db/ident :seon.handler/priority
                :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
               {:db/ident :seon.handler/updated-at
                :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}
               ;; The tuple identity. tupleAttrs + unique identity →
               ;; same [name, agent] pair upserts.
               {:db/ident       :seon.handler/key
                :db/valueType   :db.type/tuple
                :db/tupleAttrs  [:seon.handler/name :seon.handler/agent]
                :db/cardinality :db.cardinality/one
                :db/unique      :db.unique/identity}]]
    (await (d/transact! conn {:tx-data attrs}))
    {:seon.handler/bootstrapped? true}))

;; ============================================================
;; register! — one verb. Map-in, map-out.
;; ============================================================

(defn ^:async register!
  "Register (or replace) a handler entity. Map-in, map-out.

   Composite-tuple identity `[:seon.handler/name :seon.handler/agent]`
   guarantees idempotent upsert: re-registering the same name+agent
   replaces the prior `:fn` / `:priority` / `:on-origin` rather than
   creating a duplicate row.

   Agent omitted ⇒ core-wide handler. Pass `:seon.handler/agent
   [:seon.agent/id \"<id>\"]` for a per-agent handler."
  {:malli/schema [:=> [:cat :seon.handler/register!-request]
                       :seon.handler/register!-response]}
  [request]
  (let [{:seon.handler/keys [name agent match fn on-origin priority]} request
        match-attr  (:seon.handler.match/attr   match)
        match-value (:seon.handler.match/value? match)
        ;; Look up the existing handler entity (if any) via the composite
        ;; tuple. datahike-cljs requires the lookup-ref for upsert; a
        ;; plain entity-map with overlapping tuple identity raises
        ;; `:transact/unique` instead of upserting (verified in REPL).
        tuple [name agent]
        existing (try
                   (:db/id (db/entity {:seon.db/ref [:seon.handler/key tuple]}))
                   (catch :default _ nil))
        base    {:seon.handler/name              name
                 :seon.handler.match/attr        match-attr
                 :seon.handler/fn                fn
                 :seon.handler/updated-at        (js/Date.)
                 :seon.handler/on-origin         (or on-origin
                                                     #{:user :agent :system})
                 :seon.handler/priority          (or priority 0)}
        entity (cond-> base
                 existing             (assoc :db/id existing)
                 (some? match-value)  (assoc :seon.handler.match/value? match-value)
                 (and (nil? existing) agent) (assoc :seon.handler/agent agent))
        r (await (db/transact! {:seon.db/tx-data [entity]}))]
    (if (:seon.db/ok? r)
      {:seon.handler/registered? true
       :seon.handler/key          tuple}
      {:seon.handler/registered? false})))

;; ============================================================
;; query-handlers — read the live registry for a given agent scope.
;; ============================================================

(defn query-handlers
  "Return the seq of registered handlers visible to `agent-id` (a
   string) — core-wide handlers PLUS the agent's own. Each map
   is the pulled entity.

   With no agent-id, returns core-wide handlers only.

   Sorted by `:seon.handler/priority` descending so the caller can
   walk in dispatch order without re-sorting."
  {:malli/schema [:=> [:cat [:map [:seon.agent/id {:optional true} :string]
                                  [:seon.db/db   {:optional true} :seon.db/db]]]
                      [:vector :map]]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [conn-or-db (or db @db/*conn*)
        all (db/query
              {:seon.db/db conn-or-db
               :seon.db/query
               '[:find (pull ?h [* {:seon.handler/agent [:seon.agent/id]
                                    :seon.handler/match [*]}])
                 :where
                 [?h :seon.handler/name _]]})
        rows (map first all)
        matches-scope?
        (fn [h]
          (let [hag (:seon.agent/id (:seon.handler/agent h))]
            (or (nil? hag) (= hag id))))]
    (->> rows
         (filter matches-scope?)
         (sort-by (comp (fnil - 0) :seon.handler/priority))
         vec)))

(comment
  ;; REPL exploration

  (require '[seon.handler :as h])

  ;; Boot the schema once at pod start.
  (h/bootstrap-schema!)

  ;; Register a core handler.
  (h/register!
    {:seon.handler/name :wake/on-message
     :seon.handler/match {:seon.handler.match/attr :seon.agent.message/to}
     :seon.handler/fn 'seon.handlers.wake/wake-on-message})

  ;; Re-register with a new fn — should upsert, not duplicate.
  (h/register!
    {:seon.handler/name :wake/on-message
     :seon.handler/match {:seon.handler.match/attr :seon.agent.message/to}
     :seon.handler/fn 'seon.handlers.wake/wake-on-message-v2})

  (h/query-handlers {})

  nil)
