(ns seon.embed
  "Embedding SEARCH API for the pod (P2-C, the agent-facing query side).

   The pod is READ-ONLY and carries NO Proximum/Gemini — query embedding + KNN
   live on the JVM wire-server (it owns the embeddings key + the HNSW index).
   This sibling of `seon.embed` (clj — the wire-server FOUNDATION + WRITE +
   query-side verb) is the pod's thin client over the `knn-search` wire verb:

     pod                                wire-server (JVM)
     ───                                ─────────────────
     search {query, k, where?, eids?}
       │  resolve :where → eids on the LOCAL db value
       │  send {query, k, eids} over UDS ─────────────►  embed query (Gemini,
       │                                                  retrieval prefix) +
       │                                                  KNN (eid entity-filter)
       │  ◄──────────────────────────  [{eid distance} …]  (distance-ascending)
       │  search-pull: d/pull each hit's eid LOCALLY (reads are local lazy
       │  db values) and rank → enriched hits
       ▼
     [{:seon.embed/eid :seon.embed/distance (:seon.embed/entity …)} …]

   `search` returns the raw `{eid distance}` hits (the pod NEVER embeds — the
   query text goes over the wire as plain text). `search-pull` is the agent-
   facing convenience: it pulls full source for each hit from the LOCAL db
   (default pattern covers `:seon.fn/*` source + the kb shape; pass your own).

   TYPE-SCOPING (`:where`): the pod resolves a datalog `:where` to an eid set on
   its LOCAL db and sends those eids; the wire-server restricts KNN to them via a
   Proximum entity-filter. A new scope is just a different `:where` — no schema
   change. `:eids` may also be passed directly (already-resolved).

   Native `^:async`/`await` throughout (the pod is core.async-free). The wire
   transport is `seon.store.internal.wire-node/knn-search`."
  (:require
    [datahike.api :as d]
    [seon.db :as db]
    [seon.schema :as schema]
    [seon.store.internal.wire-node :as wire-node]))

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
(schema/register! ::sock-path [:string {:min 1}])
;; A datahike db value — third-party runtime handle (see seon.db/::db-val).
(schema/register! ::db 'map?)

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
   [::db    {:optional true} ::db]
   [::sock-path {:optional true} ::sock-path]])

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
   [::db    {:optional true} ::db]
   [::sock-path {:optional true} ::sock-path]])

;; ---------------------------------------------------------------------------
;; Defaults
;; ---------------------------------------------------------------------------

(def ^:const default-k
  "Default neighbour count when a caller omits `:seon.embed/k`."
  10)

(def default-pull-pattern
  "Sensible default `search-pull` pattern: `:db/id` + the shipped embeddable
   kinds' identity/payload attrs (fn source/doc/sym + the kb title/body/id).
   `seon.db/pull` silently drops any of these the local conn hasn't installed
   yet, so this pattern is safe across kinds — only the attrs an entity actually
   carries come back. Pass your own `:seon.embed/pull-pattern` for other kinds."
  '[:db/id
    :seon.fn/sym :seon.fn/doc :seon.fn/source
    :my.kb/id :my.kb/title :my.kb/body])

;; ---------------------------------------------------------------------------
;; :where → eids (LOCAL db resolution — the type-scope)
;; ---------------------------------------------------------------------------

(defn- local-db
  "The db value to resolve a `:where` against / pull from: an explicit `db` or
   the pod's `*conn*` current value. Throws if neither is available."
  [db]
  (cond
    (some? db)        db
    (some? db/*conn*) @db/*conn*
    :else (throw (ex-info "seon.embed: *conn* is unbound and no :seon.embed/db was provided" {}))))

(defn where->eids
  "Resolve datalog `:where` clauses to a SET of entity-ids on `db` (the LOCAL
   pod db value). The clauses bind `?e`; we wrap them in a `[:find ?e :where …]`
   query. Returns the matched eids (possibly empty). This is the type-scope the
   wire-server restricts KNN to."
  {:malli/schema [:=> [:catn [:db ::db] [:where ::where]] ::eids]}
  [db where]
  (into #{} (map first) (db/query (into '[:find ?e :where] where) db)))

;; ---------------------------------------------------------------------------
;; search / search-pull
;; ---------------------------------------------------------------------------

(defn ^:async search
  "Embedding KNN search (P2-C). Resolves `:seon.embed/where` (datalog clauses
   binding `?e`) to an eid type-scope on the LOCAL db, then sends the query TEXT
   + `k` + the eid scope over the wire — the wire-server embeds the query (with
   the retrieval instruction) and runs KNN, returning the nearest eids. The pod
   does NOT embed.

   `:seon.embed/eids` may be passed directly (already-resolved eids); it UNIONS
   with any `:where` result. With neither, the search is unscoped (whole index).

   Resolves to `{:seon.embed/hits [{:seon.embed/eid e :seon.embed/distance d} …]}`
   distance-ascending. `:seon.embed/k` defaults to `default-k`."
  {:malli/schema [:=> [:cat ::search-request] :any]}
  [{::keys [query k where eids db sock-path]}]
  (let [k     (or k default-k)
        ldb   (local-db db)
        scope (cond-> (or eids #{})
                (seq where) (into (where->eids ldb where)))
        hits  (await (if sock-path
                       (wire-node/knn-search sock-path query k scope {})
                       (wire-node/knn-search query k)))]
    ;; `knn-search` resolves to the decoded value: the hits vector on ok, or the
    ;; raw not-ok envelope (a map with "ok" false) on error — surface that.
    (if (and (map? hits) (false? (get hits "ok")))
      (throw (ex-info "seon.embed/search: wire knn-search failed"
                      {::query query ::response hits}))
      {::hits (vec hits)})))

(defn- enrich-hit
  "Attach the locally-pulled entity to a `{eid distance}` hit. A hit whose eid no
   longer resolves on the local db (raced retraction) keeps just eid+distance."
  [db pull-pattern {::keys [eid distance]}]
  (let [ent (db/pull db pull-pattern eid)]
    (cond-> {::eid eid ::distance distance}
      (seq ent) (assoc ::entity ent))))

(defn ^:async search-pull
  "Like [[search]] but ENRICHES each hit with the full entity pulled from the
   LOCAL db (reads are local lazy db values). This is the agent-facing
   convenience — one call from NL query to ranked, source-bearing hits.

   `:seon.embed/pull-pattern` overrides `default-pull-pattern` (which covers the
   shipped fn + kb kinds). Resolves to
   `{:seon.embed/hits [{:seon.embed/eid e :seon.embed/distance d
                        :seon.embed/entity {…}} …]}`, distance-ascending."
  {:malli/schema [:=> [:cat ::search-pull-request] :any]}
  [{::keys [query k where eids pull-pattern db sock-path]}]
  ;; Resolve the LOCAL db value ONCE and forward it to `search` so the
  ;; `:where`→eids scope and the pull-enrichment read the SAME db value — never
  ;; two independent `*conn*` resolutions that could drift (the scope would be
  ;; resolved against one db, the source pulled from another).
  (let [ldb     (local-db db)
        pattern (or pull-pattern default-pull-pattern)
        {::keys [hits]}
        (await (search (cond-> {::query query ::db ldb}
                         k         (assoc ::k k)
                         where     (assoc ::where where)
                         eids      (assoc ::eids eids)
                         sock-path (assoc ::sock-path sock-path))))]
    {::hits (mapv #(enrich-hit ldb pattern %) hits)}))
