(ns seon.route
  "Routing-as-data: the `:seon.route/*` schema + the seeded CORE route set.

   Each `:seon.route` entity is a datom a `db->routes` fn (the UI lane,
   `seon.web.router`) projects into a reitit route vector — GROUP rows by
   `:seon.route/pattern`, nest per `:seon.route/method`, resolve
   `:seon.route/handler` via `seon.eval/lookup-value`, map
   `:seon.route/middleware` keywords through reitit's registry. The router is a
   pure derived value of these datoms, rebuilt on tx. This replaces the
   hand-rolled `case`/`cond`/`re-matches` dispatch.

   This ns owns ONLY the schema + the boot seed (Core lane). reitit, the
   Node↔Ring adapter, `db->routes`, and the capability gate live in the UI lane
   (`seon.web.*`) — see [[seon.web.router]].

   ## The handler value — a symbol resolved late

   `:seon.route/handler` stores a native `:db.type/symbol` (datahike has a
   symbol type), resolved late at request time via `eval/lookup-value` — the
   same late-binding the render engine uses for `:seon.render/html` symbols, so
   a route can name a handler before (or after) it exists, and a redefine takes
   effect with no re-transact. The seeded core handlers below name the EXISTING
   pod handler fns (the `seon.web.*` `(req res …)` handlers the static route
   vector wires today); `db->routes` resolves the symbol and wraps it with the
   node-req/node-res/path-param extraction + the hijack sentinel.

   ## Path syntax

   `:seon.route/pattern` uses reitit's brace syntax (`/agent/{id}`); reitit's
   `split-path` accepts both `{id}` and `:id` (it defaults to
   `#{:bracket :colon}`)."
  (:require
    [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Attribute schemas (data-model §4.8) — one register! per attr. The bridge
;; (seon.db.internal) derives each datahike facet: `:string`→string,
;; `:keyword`→keyword, `{:seon.db/identity true}`→`:db.unique/identity`,
;; `:seon.db/ref` (the ONE canonical ref shape, referenced — never re-inlined),
;; `:symbol`→`:db.type/symbol`, `[:vector :keyword]`→cardinality-many.
;; ---------------------------------------------------------------------------

(schema/register! ::pattern :string)                          ; the path string "/agent/{id}"
(schema/register! ::method :keyword)                          ; :get / :post / … → the method endpoint
(schema/register! ::name [:keyword {:seon.db/identity true}]) ; reverse-routing key (identity ⇒ idempotent upsert)
(schema/register! ::owner :seon.db/ref)                       ; → owning agent; rides as route-data, meta-merges parent→child
(schema/register! ::handler :symbol)                          ; the handler/layout symbol → resolved via lookup-value
(schema/register! ::middleware [:vector :keyword])            ; middleware keys → reitit's registry

;; The entity map: an entity's KIND is the attrs it carries (no stored
;; `:kind`). A `:seon.route` row is identified by `:seon.route/name`
;; (identity) + the required pattern/method/handler.
(schema/register! ::route
  [:map {:seon.db/entity true}
   [::pattern    ::pattern]
   [::method     ::method]
   [::name       ::name]
   [::handler    ::handler]
   [::owner      {:optional true} ::owner]
   [::middleware {:optional true} ::middleware]])

;; ---------------------------------------------------------------------------
;; The seeded CORE route set (coordination Interface #2 — authoritative).
;;
;; `root = /` (root-os-vision, owner 2026-06-28): the all-agents overview IS the
;; root agent's world, so `/` IS the dashboard and `/agent/{id}` is uniform for
;; EVERY agent incl `/agent/root`. `/world` + `/world/feed` are RETIRED. `/`'s
;; handler is `seon.web.datastar/serve-root!` — it serves root's world shim page
;; (the per-agent page bound to the literal id "root"); the page's feed opens
;; `/agent/root/feed`, whose world-layout renders root's canvas =
;; `seon.render.system/system-view`. (`/` stays a CORE route datom here; if root's
;; bootstrap/config later OWNS `/`, only its `:seon.route/owner` ref + which
;; seed-step writes it changes — still a datom, no shape change.)
;;
;; Each agent world is TWO GET routes — the shim page and its long-lived SSE feed
;; at a SEPARATE `…/feed` sibling path (the datastar-clojure `tiny_gzip.clj`
;; separate-GET-stream idiom; the shim's `data-init="@get('…/feed')"` opens the
;; stream). If `db->routes` dropped the `/feed` routes the live stream would 404
;; after the static-vector cutover, so they MUST be seeded. The ONE action door
;; is `/agent/{id}/call` (POST) — the fn rides as a `?fn=` route-data descriptor;
;; namespaces are NOT a routing level, so there are NO per-ns / per-fn routes.
;;
;; The handler symbols name the pod handler fns the static route vector
;; (`seon.web.router`) wires today, so the db projection is behaviour-preserving
;; for the cutover.
;;
;; NOTE: dropping `/world` from the seed stops NEW boots from getting those
;; rows; an EXISTING store keeps them until a `bin/seon cluster reset` (the seed
;; upserts, it does not retract — fresh-via-reset, no migration shim). UI's
;; graceful no-match → 302 `/` covers any lingering `/world` hit meanwhile.
;; ---------------------------------------------------------------------------

(defn core-routes-tx
  "Tx-data for the seeded core route set. Identity upsert on `:seon.route/name`
   — idempotent (re-seeding re-asserts the same datoms, no duplicate entities).
   Pure builder; the caller transacts it with `:seon.db/origin :core-seed`."
  {:malli/schema [:=> [:cat] [:vector ::route]]}
  []
  [{::pattern "/"                ::method :get  ::name ::root
    ::handler 'seon.web.datastar/serve-root!}
   {::pattern "/agent/{id}"      ::method :get  ::name ::agent
    ::handler 'seon.web.datastar/serve-agent-page!}
   {::pattern "/agent/{id}/feed" ::method :get  ::name ::agent-feed
    ::handler 'seon.web.datastar/open-agent-feed!}
   {::pattern "/agent/{id}/call" ::method :post ::name ::agent-call
    ::handler    'seon.web.reactive.call/handle!
    ::middleware [::same-origin]}])
