(ns seon.embed
  "Embedding search API for the pod.

   The pod carries no Proximum or embedding model — query embedding and KNN
   live on the JVM database writer (it owns the embeddings key + HNSW index).
   This namespace is the pod's thin client for that operation:

     pod                                database server (JVM)
     ───                                ─────────────────
     search {query, k, where?, eids?}
       │  resolve :where → eids at one immutable database value
       │  send {query, k, eids} over UDS ─────────────►  embed query (Gemini,
       │                                                  retrieval prefix) +
       │                                                  KNN (eid entity-filter)
       │  ◄──────────────────────────  [{eid distance} …]  (distance-ascending)
       │  search-pull: one ordered pull-many at that same database value
       ▼
     [{:seon.embed/eid :seon.embed/distance (:seon.embed/entity …)} …]

   `search` returns the raw `{eid distance}` hits (the pod NEVER embeds — the
   query text goes over the wire as plain text). `search-pull` is the agent-
   facing convenience: it pulls full source for each hit at the same database
   value (the default pattern is `[*]`; pass your own to narrow it).

   TYPE-SCOPING (`:where`): the pod resolves a datalog `:where` to an eid set at
   the selected database value; the database server restricts KNN to them via a
   Proximum entity-filter. A new scope is just a different `:where` — no schema
   change. `:eids` may also be passed directly (already-resolved).

   Native `^:async`/`await` throughout (the pod is core.async-free)."
  (:require
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Schemas — request/response shapes for the pod-facing surface.
;; ---------------------------------------------------------------------------

(schema/register! ::query [:string {:min 1}])
(schema/register! ::k [:int {:min 1}])
(schema/register! ::eid :int)
(schema/register! ::eids [:set ::eid])
(schema/register! ::distance :double)
(schema/register! ::where [:vector :any])         ; datalog :where clauses
(schema/register! ::pull-pattern [:vector :any])

(schema/register! ::hit
                  [:map
                   [::eid ::eid]
                   [::distance ::distance]])
(schema/register! ::hits [:vector ::hit])

(schema/register!
  ::search-request
  [:map
   [::query ::query]
   [::k     {:optional true} ::k]
   [::where {:optional true} ::where]
   [::eids  {:optional true} ::eids]
   [:seon.db/db {:optional true} :seon.db/db]])

;; A pulled entity map — its keys vary by kind (fn / kb / …), so the value
;; shape is a plain map of namespaced keys; the third-party-boundary pull
;; result is not seon-authored, hence the open :map.
(schema/register! ::entity :map)
(schema/register!
  ::pull-hit
  [:map
   [::eid ::eid]
   [::distance ::distance]
   [::entity {:optional true} ::entity]])
(schema/register! ::pull-hits [:vector ::pull-hit])

(schema/register!
  ::search-pull-request
  [:map
   [::query ::query]
   [::k     {:optional true} ::k]
   [::where {:optional true} ::where]
   [::eids  {:optional true} ::eids]
   [::pull-pattern {:optional true} ::pull-pattern]
   [:seon.db/db {:optional true} :seon.db/db]])

;; ---------------------------------------------------------------------------
;; Defaults
;; ---------------------------------------------------------------------------

(def ^:const default-k
  "Default neighbour count when a caller omits `:seon.embed/k`."
  10)

(def default-pull-pattern
  "Default `search-pull` selector: every attribute the entity carries.

   The wildcard avoids hard-coding domain attributes or requiring every
   database to install them. Pass `:seon.embed/pull-pattern` to narrow it."
  '[*])

(defn enabled?
  "True when embedding features are enabled (`SEON_EMBED` is present).

   Present = any value. The same single switch the database server reads, so one
   env var gates the feature across both processes. UNSET ⇒ every semantic
   surface (`my.kb` recall, diffusion `+semantic`, the header's embed
   indicator) stays off."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (some? (.. js/process -env -SEON_EMBED)))

;; ---------------------------------------------------------------------------
;; :where → eids at one immutable database value (the type-scope)
;; ---------------------------------------------------------------------------

(defn ^:async where->eids
  "Resolve datalog `:where` clauses to a set of entity IDs.

   The clauses bind `?e`; the authority query and subsequent KNN use the same
   database value."
  {:malli/schema [:=> [:cat ::where :seon.db/db] :any]}
  [where database]
  (let [result (await (db/query {:seon.db/query
                                 (into '[:find ?e :where] where)
                                 :seon.db/db database}))]
    (if (:seon.error/message result)
      result
      (into #{} (map first) result))))

;; ---------------------------------------------------------------------------
;; search / search-pull
;; ---------------------------------------------------------------------------

(defn ^:async search
  "Find stored entities semantically similar to a text query.

   Embedding KNN search. Resolves `:seon.embed/where` (datalog clauses
   binding `?e`) to an eid type-scope at one database value, then sends the
   query text + `k` + the eid scope over the protocol. The database server
   embeds the query with the retrieval instruction and runs KNN, returning the
   nearest eids. The pod does NOT embed.

   `:seon.embed/eids` may be passed directly (already-resolved eids); it UNIONS
   with any `:where` result. With neither, the search is unscoped (whole index).

   Resolves to `{:seon.embed/hits [{:seon.embed/eid e :seon.embed/distance d} …]}`
   distance-ascending. `:seon.embed/k` defaults to `default-k`. A wire failure
   RESOLVES to `{:seon.embed/hits [] :seon/error {…}}` — errors are values,
   never a rejection."
  {:malli/schema [:=> [:cat ::search-request] :any]}
  [{::keys [query k where eids] database :seon.db/db}]
  (let [database (or database (await (db/db)))]
    (if (:seon.error/message database)
      {::hits [] :seon/error database}
      (let [where-eids (if (seq where)
                         (await (where->eids where database))
                         #{})]
        (if (:seon.error/message where-eids)
          {::hits [] :seon/error where-eids}
          (let [scope (into (or eids #{}) where-eids)
                hits (await
                      (db/knn-search!
                       (cond-> {::protocol/query query
                                ::protocol/limit (or k default-k)
                                :seon.db/db database}
                         (seq scope)
                         (assoc ::protocol/entity-ids (vec scope)))))]
            (if (:seon.error/message hits)
              {::hits [] :seon/error hits}
              {::hits (vec hits)})))))))

(defn ^:async search-pull
  "Like [[search]] but ENRICHES each hit with the full pulled entity.

   Pulled in one ordered authority request at the search database value. This
   is the agent-facing convenience — one call from NL query to ranked,
   source-bearing hits.

   `:seon.embed/pull-pattern` overrides `default-pull-pattern` (the `[*]`
   wildcard — kind-agnostic, covers whatever attrs a hit carries). Resolves to
   `{:seon.embed/hits [{:seon.embed/eid e :seon.embed/distance d
                        :seon.embed/entity {…}} …]}`, distance-ascending. A
   [[search]] error envelope (`:seon/error`) passes through UNCHANGED."
  {:malli/schema [:=> [:cat ::search-pull-request] :any]}
  [{::keys [query k where eids pull-pattern] database :seon.db/db}]
  (let [database (or database (await (db/db)))]
    (if (:seon.error/message database)
      {::hits [] :seon/error database}
      (let [pattern (or pull-pattern default-pull-pattern)
            {::keys [hits] :as res}
            (await (search (cond-> {::query query :seon.db/db database}
                             k     (assoc ::k k)
                             where (assoc ::where where)
                             eids  (assoc ::eids eids))))]
        (if (:seon/error res)
          res
          (let [entities
                (await
                 (db/pull-many
                  {:seon.db/pull-pattern pattern
                   :seon.db/refs (mapv ::eid hits)
                   :seon.db/db database}))]
            (if (:seon.error/message entities)
              {::hits [] :seon/error entities}
              {::hits
               (mapv (fn [{::keys [eid distance]} entity]
                       (cond-> {::eid eid ::distance distance}
                         (seq entity) (assoc ::entity entity)))
                     hits entities)})))))))
