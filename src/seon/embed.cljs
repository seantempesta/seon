(ns seon.embed
  "Embedding SEARCH API for the pod (P2-C, the agent-facing query side).

   The pod is READ-ONLY and carries NO Proximum/Gemini — query embedding + KNN
   live on the JVM database writer (it owns the embeddings key + HNSW index).
   This sibling of `seon.embed` is the pod's thin client for that operation:

     pod                                database server (JVM)
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
   its local db and sends those eids; the database server restricts KNN to them via a
   Proximum entity-filter. A new scope is just a different `:where` — no schema
   change. `:eids` may also be passed directly (already-resolved).

   Native `^:async`/`await` throughout (the pod is core.async-free)."
  (:require
    [datahike.api :as d]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.db.replica :as replica]
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
  "Resolve datalog `:where` clauses to a SET of entity-ids on `db`.

   The LOCAL pod db value. The clauses bind `?e`; we wrap them in a
   `[:find ?e :where …]` query. Returns the matched eids (possibly empty).
   This is the type-scope the database server restricts KNN to."
  {:malli/schema [:=> [:catn [:db ::db] [:where ::where]] ::eids]}
  [db where]
  (into #{} (map first) (db/query (into '[:find ?e :where] where) db)))

;; ---------------------------------------------------------------------------
;; search / search-pull
;; ---------------------------------------------------------------------------

(defn ^:async search
  "Find stored entities semantically similar to a text query.

   Embedding KNN search. Resolves `:seon.embed/where` (datalog clauses
   binding `?e`) to an eid type-scope on the LOCAL db, then sends the query TEXT
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
  [{::keys [query k where eids db sock-path]}]
  (let [k     (or k default-k)
        ldb   (local-db db)
        scope (cond-> (or eids #{})
                (seq where) (into (where->eids ldb where)))
        hits
        (await
         (replica/knn-search!
          (cond-> {::protocol/query query
                   ::protocol/limit k}
            (seq scope)
            (assoc ::protocol/entity-ids (vec scope))
            sock-path
            (assoc ::replica/request-socket-path sock-path))))]
    ;; `knn-search!` resolves to the hits vector or the canonical failed
    ;; protocol map. A writer failure
    ;; (server down, embeddings disabled, index error) is an EXPECTED error and
    ;; rides the VALUE channel — a specced ^:async fn must never reject with an
    ;; expected error (docs/conventions.md "Errors Are Values", consequence 3).
    (if (and (map? hits) (false? (::protocol/success? hits)))
      {::hits []
       :seon/error {:seon.error/kind    :core-bug
                    :seon.error/message (str "seon.embed/search: knn-search failed — "
                                             (or (::protocol/error hits)
                                                 (pr-str hits)))}}
      {::hits (vec hits)})))

(defn- enrich-hit
  "Attach the locally-pulled entity to a `{eid distance}` hit. A hit whose eid no
   longer resolves on the local db (raced retraction) keeps just eid+distance."
  [db pull-pattern {::keys [eid distance]}]
  (let [ent (db/pull db pull-pattern eid)]
    (cond-> {::eid eid ::distance distance}
      (seq ent) (assoc ::entity ent))))

(defn ^:async search-pull
  "Like [[search]] but ENRICHES each hit with the full pulled entity.

   Pulled from the LOCAL db (reads are local lazy db values). This is the
   agent-facing convenience — one call from NL query to ranked,
   source-bearing hits.

   `:seon.embed/pull-pattern` overrides `default-pull-pattern` (the `[*]`
   wildcard — kind-agnostic, covers whatever attrs a hit carries). Resolves to
   `{:seon.embed/hits [{:seon.embed/eid e :seon.embed/distance d
                        :seon.embed/entity {…}} …]}`, distance-ascending. A
   [[search]] error envelope (`:seon/error`) passes through UNCHANGED."
  {:malli/schema [:=> [:cat ::search-pull-request] :any]}
  [{::keys [query k where eids pull-pattern db sock-path]}]
  ;; Resolve the LOCAL db value ONCE and forward it to `search` so the
  ;; `:where`→eids scope and the pull-enrichment read the SAME db value — never
  ;; two independent `*conn*` resolutions that could drift (the scope would be
  ;; resolved against one db, the source pulled from another).
  (let [ldb     (local-db db)
        pattern (or pull-pattern default-pull-pattern)
        {::keys [hits] :as res}
        (await (search (cond-> {::query query ::db ldb}
                         k         (assoc ::k k)
                         where     (assoc ::where where)
                         eids      (assoc ::eids eids)
                         sock-path (assoc ::sock-path sock-path))))]
    (if (:seon/error res)
      ;; Wire-failure envelope from `search` — pass it through unchanged
      ;; (hits already empty); never enrich, never reject.
      res
      {::hits (mapv #(enrich-hit ldb pattern %) hits)})))
