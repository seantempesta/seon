(ns seon.embed
  "Embedding SEARCH API for the pod (P2-C, the agent-facing query side).

   The pod carries no Proximum or embedding model — query embedding and KNN
   live on the JVM database writer (it owns the embeddings key + HNSW index).
   This sibling of `seon.embed` is the pod's thin client for that operation:

     pod                                database server (JVM)
     ───                                ─────────────────
     search {query, k, where?, eids?}
       │  resolve :where → eids at one authority coordinate
       │  send {query, k, eids} over UDS ─────────────►  embed query (Gemini,
       │                                                  retrieval prefix) +
       │                                                  KNN (eid entity-filter)
       │  ◄──────────────────────────  [{eid distance} …]  (distance-ascending)
       │  search-pull: one ordered pull-many at that same coordinate
       ▼
     [{:seon.embed/eid :seon.embed/distance (:seon.embed/entity …)} …]

   `search` returns the raw `{eid distance}` hits (the pod NEVER embeds — the
   query text goes over the wire as plain text). `search-pull` is the agent-
   facing convenience: it pulls full source for each hit at the same coordinate
   (default pattern covers `:seon.fn/*` source + the kb shape; pass your own).

   TYPE-SCOPING (`:where`): the pod resolves a datalog `:where` to an eid set at
   the selected coordinate; the database server restricts KNN to them via a
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
(schema/register! ::coordinate :seon.db.coordinate/coordinate)

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
   [::coordinate {:optional true} ::coordinate]])

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
   [::coordinate {:optional true} ::coordinate]])

;; ---------------------------------------------------------------------------
;; Defaults
;; ---------------------------------------------------------------------------

(def ^:const default-k
  "Default neighbour count when a caller omits `:seon.embed/k`."
  10)

(def default-pull-pattern
  "Default `search-pull` pull pattern: the `[*]` WILDCARD — every attribute the
   hit entity actually carries, of ANY kind. NO hard-coded attr names: a named
   pattern THROWS when the local conn has never installed one of the attrs (a
   fn-only store has no `:my.kb/*`), and bakes in which kinds exist. `[*]` is
   kind-agnostic; `render-hit` dispatches on whatever attrs come back. Pass your
   own `:seon.embed/pull-pattern` to narrow it."
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
;; :where → eids at one immutable coordinate (the type-scope)
;; ---------------------------------------------------------------------------

(defn ^:async where->eids
  "Resolve datalog `:where` clauses to a set of entity IDs.

   The clauses bind `?e`; the authority query and subsequent KNN use the same
   complete coordinate."
  {:malli/schema [:=> [:cat ::where ::coordinate] :any]}
  [where point]
  (let [result (await (db/query {:seon.db/query
                                 (into '[:find ?e :where] where)
                                 :seon.db/coordinate point}))]
    (if (:seon.error/message result)
      result
      (into #{} (map first) result))))

;; ---------------------------------------------------------------------------
;; search / search-pull
;; ---------------------------------------------------------------------------

(defn ^:async search
  "Find stored entities semantically similar to a text query.

   Embedding KNN search. Resolves `:seon.embed/where` (datalog clauses
   binding `?e`) to an eid type-scope at one coordinate, then sends the query text
   + `k` + the eid scope over the protocol — the database server embeds the query (with
   the retrieval instruction) and runs KNN, returning the nearest eids. The pod
   does NOT embed.

   `:seon.embed/eids` may be passed directly (already-resolved eids); it UNIONS
   with any `:where` result. With neither, the search is unscoped (whole index).

   Resolves to `{:seon.embed/hits [{:seon.embed/eid e :seon.embed/distance d} …]}`
   distance-ascending. `:seon.embed/k` defaults to `default-k`. A wire failure
   RESOLVES to `{:seon.embed/hits [] :seon/error {…}}` — errors are values,
   never a rejection."
  {:malli/schema [:=> [:cat ::search-request] :any]}
  [{::keys [query k where eids coordinate]}]
  (let [point (or coordinate (await (db/head-coordinate)))]
    (if (:seon.error/message point)
      {::hits [] :seon/error point}
      (let [where-eids (if (seq where)
                         (await (where->eids where point))
                         #{})]
        (if (:seon.error/message where-eids)
          {::hits [] :seon/error where-eids}
          (let [scope (into (or eids #{}) where-eids)
                hits (await
                      (db/knn-search!
                       (cond-> {::protocol/query query
                                ::protocol/limit (or k default-k)
                                :seon.db/coordinate point}
                         (seq scope)
                         (assoc ::protocol/entity-ids (vec scope)))))]
            (if (:seon.error/message hits)
              {::hits [] :seon/error hits}
              {::coordinate point ::hits (vec hits)})))))))

(defn ^:async search-pull
  "Like [[search]] but ENRICHES each hit with the full pulled entity.

   Pulled in one ordered authority request at the search coordinate. This is the
   agent-facing convenience — one call from NL query to ranked,
   source-bearing hits.

   `:seon.embed/pull-pattern` overrides `default-pull-pattern` (the `[*]`
   wildcard — kind-agnostic, covers whatever attrs a hit carries). Resolves to
   `{:seon.embed/hits [{:seon.embed/eid e :seon.embed/distance d
                        :seon.embed/entity {…}} …]}`, distance-ascending. A
   [[search]] error envelope (`:seon/error`) passes through UNCHANGED."
  {:malli/schema [:=> [:cat ::search-pull-request] :any]}
  [{::keys [query k where eids pull-pattern coordinate]}]
  (let [pattern (or pull-pattern default-pull-pattern)
        {::keys [hits] :as res}
        (await (search (cond-> {::query query}
                         k         (assoc ::k k)
                         where     (assoc ::where where)
                         eids      (assoc ::eids eids)
                         coordinate (assoc ::coordinate coordinate))))]
    (if (:seon/error res)
      res
      (let [entities
            (await
             (db/pull-many
              {:seon.db/pull-pattern pattern
               :seon.db/refs (mapv ::eid hits)
               :seon.db/coordinate (::coordinate res)}))]
        (if (:seon.error/message entities)
          {::hits [] :seon/error entities}
          {::hits
           (mapv (fn [{::keys [eid distance]} entity]
                   (cond-> {::eid eid ::distance distance}
                     (seq entity) (assoc ::entity entity)))
                 hits entities)})))))
