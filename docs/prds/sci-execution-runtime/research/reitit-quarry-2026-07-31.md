---
type: research
status: active
tags: [research, ui, render, architecture]
---

# Reitit quarry — what the old routing was, and how to adopt it fresh

Owner (verbatim, 2026-07-31): *"wtf? we lost all the reitit work? … there were
some really nice attributes to using reitit. I don't want to write my own
routing system."*

**Nothing is lost.** The reitit work is intact in three places: the quarry
source (`src-old/seon/route.cljs`, `src-old/seon/web/router.cljs`), the
architecture target (`docs/seon/architecture/ui.md:477-535`), and the original
adoption study (`docs/prds/archive/agent-fsm/research/reitit-routing-2026-06-27.md`).
Reitit 0.10.1 is already vendored as a submodule at
`reference-code/reitit` (submodule declared in `.gitmodules`; HEAD
`106fc4c7a0` = "Release 0.10.1", the exact version the old system pinned).
The fresh tree simply has not adopted it yet, and the current dispatcher says
so in its own docstring.

## 1. Excavation — what actually existed

### 1.1 The dependency pin

`deps.edn` still carries the old pin, inside the DEAD `:cljs` alias:

```clojure
metosin/reitit-ring {:mvn/version "0.10.1"}
metosin/reitit-malli {:mvn/version "0.10.1"}
```

(`deps.edn`, `:aliases :cljs :extra-deps`). It is absent from `:deps` and from
every live alias, so the fresh JVM classpath has no reitit today.
`reitit-malli` was pinned but **never required by any namespace** — coercion
was never wired (`rg -n reitit src-old/` shows `reitit.ring` as the only
require, `src-old/seon/web/router.cljs:62`).

### 1.2 Routes as database facts — this is the real "nice attribute"

`src-old/seon/route.cljs` owns the schema and the seeded route set. It is a
plain `:seon.route/*` attribute population, no kinds:

```clojure
(schema/register! ::pattern :string)                          ; the path string "/agent/{id}"
(schema/register! ::method :keyword)                          ; :get / :post / … → the method endpoint
(schema/register! ::name [:keyword {:seon.db/identity true}]) ; reverse-routing key (identity ⇒ idempotent upsert)
(schema/register! ::owner :seon.db/ref)                       ; → owning agent; rides as route-data, meta-merges parent→child
(schema/register! ::handler :symbol)                          ; the handler/layout symbol → resolved via lookup-value
(schema/register! ::middleware :keyword)                      ; one middleware key → reitit's registry
```

(`src-old/seon/route.cljs:43-48`; entity map at `:53-60`.)

The seeded core set (`src-old/seon/route.cljs:98-112`) was seven rows:

```clojure
[{::pattern "/"                 ::method :get  ::name ::root
  ::handler 'seon.web.datastar/serve-root!}
 {::pattern "/agent/{id}"       ::method :get  ::name ::agent
  ::handler 'seon.web.datastar/serve-agent-page!}
 {::pattern "/agent/{id}/value" ::method :get  ::name ::agent-value
  ::handler 'seon.web.serve/value!}
 {::pattern "/agent/{id}/feed"  ::method :get  ::name ::agent-feed
  ::handler 'seon.web.datastar/open-agent-feed!}
 {::pattern "/agent/{id}/debug" ::method :get  ::name ::agent-debug
  ::handler 'seon.web.debug/debug-page!}
 {::pattern "/agent/{id}/debug/feed" ::method :get ::name ::agent-debug-feed
  ::handler 'seon.web.debug/debug-feed!}
 {::pattern "/agent/{id}/call"  ::method :post ::name ::agent-call
  ::handler    'seon.web.reactive.call/handle!
  ::middleware ::same-origin}]
```

The docstring states the model exactly: *"The router is a pure derived value of
these datoms, rebuilt on tx. This replaces the hand-rolled
`case`/`cond`/`re-matches` dispatch."* (`src-old/seon/route.cljs:9-10`).

**Verified: yes, route tables really were stored as facts and compiled to a
reitit router.** `src-old/seon/web/router.cljs:183-226` is the pure projection:

```clojure
(def ^:private route-query
  '[:find [(pull ?route [:seon.route/pattern
                         :seon.route/method
                         :seon.route/handler
                         :seon.route/middleware]) ...]
    :where [?route :seon.route/pattern]])
```

…sorted into one canonical fingerprint (`route-projection`, `:190-198`), then
grouped by pattern and nested per method into reitit's route data
(`projection->routes`, `:200-213`), and finally compiled:

```clojure
(defn- build-ring-handler
  [projection config]
  (rr/ring-handler
    (rr/router (into (projection->routes projection)
                     (static-supplement config))
               {:reitit.middleware/registry mw-registry})
    not-found))
```

(`src-old/seon/web/router.cljs:300-307`.)

### 1.3 Custom extensions on top of reitit

Four, all worth knowing:

1. **Late-bound handler symbols.** `:seon.route/handler` is a
   `:db.type/symbol` resolved *at request time* through
   `seon.render.core/resolve-compiled`, so redefining a handler took effect
   without a router rebuild, and an unresolved symbol degraded to a 500 rather
   than throwing (`src-old/seon/web/router.cljs:169-181`). Same late-binding as
   the render engine's `:seon.render/html` symbols.
2. **A middleware registry keyed by keyword** — the one place a
   `:seon.route/middleware` keyword becomes middleware, threaded in as
   `{:reitit.middleware/registry mw-registry}`; an unknown keyword throws
   legibly at *build* time (`src-old/seon/web/router.cljs:147-158`). Two
   entries: `:seon.route/same-origin` (CSRF gate on state-changing POSTs,
   `:123-132`) and `:seon.route/loopback-peer` (operator doors, `:134-145`).
3. **A database-interest cache** — one `db/listen!` interest on `route-query`,
   an owner token, desired/accepted database values, and rebuild only when the
   canonical fingerprint changes (`src-old/seon/web/router.cljs:70-80,
   309-402`). About 150 lines of machinery whose only job is keeping the
   compiled router current with the facts.
4. **A graceful default handler** — no dead-end 404; a miss 302s to `/`
   (`src-old/seon/web/router.cljs:284-294`, architecture `ui.md:468-475`).

### 1.4 The honest gap: reverse routing was NEVER used

`:seon.route/name` was declared `{:seon.db/identity true}` and commented
"reverse-routing key", but its actual job was **idempotent upsert on re-seed**
(`src-old/seon/route.cljs:45`, `:89-95`). Grepping the whole quarry for
`match-by-name` / `match->path` returns **zero hits**, and the old renderers
still hand-built URL strings:

- `src-old/seon/render/system.cljs:78` — `(str "/agent/" id)`
- `src-old/seon/render/system.cljs:105` — `"/data"`
- `src-old/seon/web/debug.cljs:192`, `src-old/seon/web/data.clj:79` — `"/"`

Neither did coercion (`reitit-malli` pinned, never required). So of the
"nice attributes", the ones that were *realized* are: routes-as-data,
late-bound handler symbols, the keyword middleware registry, build-time
conflict detection, and the graceful default. Reverse routing and coercion
were designed-for and paid-for but never collected. State that plainly before
adopting: we are adopting a working mechanism *plus* two affordances the old
system never cashed in.

### 1.5 Where the reitit work landed and left

`git log -S reitit` on `src/ src-old/ deps.edn`, oldest relevant first:

| commit | what |
|---|---|
| `126d6f8e5` | reitit front door for the pod HTTP surface (Lane-U) |
| `39375852e` | `:seon.route` schema + seeded core routes (Phase 5) |
| `3c7cfb723` | pod router is a pure derived value of `:seon.route/*` datoms (#16) |
| `df6249f57` | invalidate derived routes from database facts |
| `31525784f` | Replace Node web transport with Bun serve |
| `f25e34594` | R0: the tree split — the fresh tree IS the project |

The mechanism did not fail and was not rejected. It stayed in `src-old/` at the
tree split (`f25e34594`) and the fresh web slice was written from zero.

## 2. What the fresh dispatcher lacks

Current owner: `src/seon/render/web.clj:901-1034`, a single `cond` over
`(:uri request)` / `(:request-method request)` with four `re-matches`
extractions (`exact-agent-id`, `:886-889`). Its own docstring:

> "Reitit remains deferred until nested route data and capability middleware
> make a tree pay for itself." (`src/seon/render/web.clj:904-906`)

That was an honest deferral, not an oversight. What it costs today:

| reitit attribute | present in the `cond`? | matters under ruling #17? |
|---|---|---|
| **Reverse routing** (`r/match-by-name` + `r/match->path`, `reference-code/reitit/modules/reitit-core/src/reitit/core.cljc:49,70-76`) | no — 11 hand-built path strings (below) | **YES.** A namespace page's URL is generated from a name + params in dozens of render sites; hand-built strings are the exact thing that rots when a route line changes. |
| **Route data as plain edn** (arbitrary keys ride through; only HTTP methods are special-cased — `reference-code/reitit/modules/reitit-ring/src/reitit/ring.cljc:19` `group-keys`) | no — behavior is Clojure control flow, not a value | **YES.** Ruling #17 says "adding a namespace page is adding a route line." A line is only addable if the table is a value; `cond` branches are not. An agent can *query* an edn table; it cannot query a `cond`. |
| **Build-time path + name conflict detection** (`core.cljc:292,371-375`; legible messages `exception.cljc:32-51`) | no — a `cond` silently shadows | **YES**, and increasingly: every added page raises overlap risk. |
| **Per-route / per-subtree middleware with keyword registry** | no — `same-origin?` is an inline `if` at `web.clj:935` | Medium. One gate today; the guarded-door and capability seams will want more. |
| **Path/query coercion** (`reitit.coercion` + `reitit-malli`) | no | **Low/no.** We have exactly one path param shape (an agent id) and Malli already owns contracts at the function boundary. Do **not** adopt `reitit-malli` now. |
| **Path-param decoding** (percent-decode in the trie, `trie.cljc:206-259`; `impl.cljc:239-250`) | yes, hand-done via `URLDecoder` at `web.clj:888` | Parity, not a gain. |

### Hand-built URL strings in the landed renderers — 11 sites

```
src/seon/render/web.clj:203   (str "/agent/" (path-segment id))
src/seon/render/web.clj:204   (str "/agent/" (path-segment id) "/debug")
src/seon/render/web.clj:392   (str "/agent/" (path-segment agent-id) "/debug")
src/seon/render/web.clj:411   (str "/feed/" (path-segment agent-id) "?" query)
src/seon/render/web.clj:952   (str "/feed/" id)
src/seon/render/web.clj:973   (str "/feed/" agent-id)
src/seon/render/web.clj:1004  (str "/data?entity=" …)
src/seon/render/web.clj:1024  (str "/feed/" id)
src/seon/render/root.clj:115  (str "/agent/" id)
src/seon/render/root.clj:117  (str "/data?entity=" …)
src/seon/render/agent.clj:116 "/"
```

(`src/seon/render/value.cljc:64` renders an `:href` but receives its URL from
`:seon.render.value/route-base` — it is a consumer, not a construction site.)

Four of those eleven build `/feed/{id}` and three build `/agent/{id}/debug`.
That is the duplication reverse routing removes.

## 3. Current state

- **Fresh `deps.edn` `:deps`: no reitit.** Present only in the dead `:cljs`
  alias (`metosin/reitit-ring` + `metosin/reitit-malli`, both `0.10.1`).
- **Vendored: YES**, `reference-code/reitit`, submodule declared in
  `.gitmodules`, checked out at `106fc4c7a0` = "Release 0.10.1" — the exact
  version of the old pin. The vendor-crucial-deps law is already satisfied;
  this report is written from that source, not from memory.
- **Old pin: `0.10.1`** (both `reitit-ring` and the unused `reitit-malli`).

Dependency footprint, resolved (`clojure -Sdeps … -Stree`):

```
metosin/reitit-ring 0.10.1
  . metosin/reitit-core 0.10.1
    . meta-merge/meta-merge 1.0.0
  . ring/ring-core 1.15.3
    . org.ring-clojure/ring-core-protocols 1.15.3
    . org.ring-clojure/ring-websocket-protocols 1.15.3
    . ring/ring-codec 1.3.0
    . commons-io/commons-io 2.20.0
    . org.apache.commons/commons-fileupload2-core 2.0.0-M4
    . crypto-random/crypto-random 1.2.1  → commons-codec 1.15
    . crypto-equality/crypto-equality 1.0.1
```

`reitit-core` alone costs **one** jar (`meta-merge`). `reitit-ring` costs
**eight**, all from `ring/ring-core` — which `reitit.ring` requires only under
`#?(:clj …)` for `ring.util.mime-type` / `ring.util.response`
(`reference-code/reitit/modules/reitit-ring/src/reitit/ring.cljc:1-9`), used by
its resource/file handlers we do not need. That is the one honest cost of the
recommendation.

Note: `reitit-core` ships a Java source (`modules/reitit-core/java-src/reitit/Trie.java`),
so `:local/root "reference-code/reitit"` will **not** work — the submodule is
for reading; the classpath coordinate must be `:mvn/version "0.10.1"`, which is
exactly what the submodule's HEAD is.

## 4. Adoption recommendation

**Adopt reitit on the JVM side. Yes, it is the right call.** It is CLJ-native
and Ring-compatible by construction (`reitit.ring/ring-handler` returns an
ordinary Ring handler, `ring.cljc:360-380`), we already speak Ring maps to
http-kit, and the vendored source confirms every feature claimed above. There is
no alternative worth writing: the "alternative" is the `cond` we already have,
and the owner's instruction is explicit — no hand-rolled routing system.

**But do NOT restore the route-facts machinery.** Adopt the *table as a value*,
not the table as a database population. Reasons, in order:

1. Ruling #17 says adding a namespace page is **adding a route line**. A `def`
   in source is a route line. A route fact requires a transaction and — with
   the old system's sovereignty rules — a `bin/seon init … --force` refork
   before a new route lands (`src-old/seon/web/CLAUDE.md`, "Route truth").
   Facts made adding a route *harder*, not easier.
2. Reverse routing from a `def` router is a **pure function call** with no
   threading. From a database-derived router it is a runtime value that must be
   threaded into every renderer — which is precisely why the old system never
   used reverse routing and hand-built strings instead (§1.4). The `def` form is
   the one that actually collects the prize.
3. It deletes ~150 lines of interest/desired/accepted cache machinery
   (`src-old/seon/web/router.cljs:70-80,309-402`) that exists only because the
   table was facts.
4. The table is still **data**: walkable, printable, renderable, and queryable
   by an agent through the ordinary program-graph facts (`:seon.fn`) that index
   `src/`. "Routes as data" does not require "routes as datoms".

Agent-authored pages (`/agent/{id}/app/{x}`) are the one genuine future case
for route facts. When that lands, the shape is a *second projection concatenated
into the same route vector* — not a different mechanism. Flag it for the owner
as option B with §1.2/§1.3 as precedent, and record its real cost (refork per
route change, late-bound handler symbols, cache invalidation, no pure reverse
routing).

### 4.1 The route table, as edn

Living in `seon.render.web` beside the handlers it names. Under ruling #17
every screen is one namespace's surface, so the namespace page is a first-class
route, and `/` is root's namespace page:

```clojure
(def routes
  "The one route table. Adding a namespace page is adding a line here."
  [["/"                       {:name ::root
                               :get  {:handler #'root-page}}]
   ["/ns/{namespace}"         {:name ::namespace-page
                               :get  {:handler #'namespace-page}}]
   ["/ns/{namespace}/debug"   {:name ::namespace-debug
                               :get  {:handler #'namespace-debug-page}}]
   ["/agent/{id}"             {:name ::agent
                               :get  {:handler #'agent-page}}]
   ["/agent/{id}/debug"       {:name ::agent-debug
                               :get  {:handler #'agent-debug-page}}]
   ["/agent/{id}/message"     {:name       ::agent-message
                               :post {:middleware [::same-origin]
                                      :handler    #'inbound-message}}]
   ["/feed/{id}"              {:name ::feed
                               :get  {:handler #'agent-feed}}]
   ["/data"                   {:name ::data
                               :get  {:handler #'data-page}}]
   ["/css/{*path}"            {:name ::css  :get {:handler #'static-resource}}]
   ["/js/{*path}"             {:name ::js   :get {:handler #'static-resource}}]])
```

`/ns/{namespace}` resolves namespace → owner agent (`:seon.agent/namespace` is
unique) → the walk in the `:seon.render/html` projection; the `/debug` sibling
is the same walk rendered as ruling #16's two panes. Both are one handler pair
over one resolution, so the "route line" claim is literally true.

Handlers as **vars** (`#'agent-page`), not closures: that is the fresh-tier
equivalent of the old late-bound `:db.type/symbol` — re-evaluating a handler
`defn` changes behavior with no router rebuild, exactly like flow procs
referencing transforms as vars. It needs no symbol resolver, no 500-degrade
path, and no runtime `resolve`.

Handlers take the Ring request; the service map (connection, caps, process,
mult) rides in as one closure at construction, or as route-data on the router's
`:data` — pick the closure, it is one `let` and keeps handlers ordinary.

### 4.2 The router and reverse routing

```clojure
(def router (reitit.ring/router routes {::rr/default-options …
                                        :reitit.middleware/registry mw-registry}))

(defn path
  "The URL for route `name` with `params` — the only URL construction site."
  {:malli/schema [:=> [:catn [::name :qualified-keyword] [::params [:map-of :keyword :string]]] :string]}
  ([name] (path name {}))
  ([name params] (r/match->path (r/match-by-name! router name params))))
```

`match-by-name!` throws on an unknown name or missing params
(`core.cljc:60-68`), so a typo'd link fails loudly at first render rather than
404ing a user. Every one of the eleven sites in §2 becomes
`(path ::agent {:id id})`, `(path ::feed {:id id})`, `(path ::agent-debug {:id id})`,
`(path ::data {} )` + query params via `match->path`'s second arity
(`core.cljc:70-76`). `router` being a `def` makes `path` pure — no threading,
usable from `root.clj`, `agent.clj`, and `value.cljc` alike.

Conflict detection is free and on by default: `reitit.core/router` throws
`:path-conflicts` / `:name-conflicts` at construction (`core.cljc:355-380`,
messages `exception.cljc:32-51`). Because `router` is a `def`, a conflicting
route line fails at **namespace load** — i.e. at `bin/test` and at the edit
hook — which is the strongest available form of the guarantee.

### 4.3 Middleware

One registry entry to start, replacing the inline `if` at `web.clj:935`:

```clojure
(def mw-registry
  {::same-origin {:name ::same-origin
                  :wrap (fn [handler]
                          (fn [request]
                            (if (same-origin? request)
                              (handler request)
                              {:status 403
                               :headers {"content-type" "text/plain; charset=utf-8"}
                               :body "cross-origin POST refused"})))}})
```

Unknown keyword ⇒ build-time throw, same as the old system
(`src-old/seon/web/router.cljs:147-158`). This is the seam the capability gate
will use later without touching a handler.

### 4.4 Default handler

Current behavior is a plain 404 (`web.clj:1032-1034`); the old system 302'd home
(`src-old/seon/web/router.cljs:291-294`, `ui.md:468-475`). Keep the 404 in the
adoption commit — changing miss behavior is a separate, owner-visible decision,
not something to smuggle into a dispatcher swap. Flag it.

### 4.5 Migration cost

| step | cost |
|---|---|
| Add `metosin/reitit-ring {:mvn/version "0.10.1"}` to `:deps` (8 jars, §3) | minutes |
| Split the `cond` body into named handler `defn`s (each branch already is one) | ~1 h; mechanical, mostly cut/paste of existing branch bodies |
| `routes` + `router` + `path` + `mw-registry` | ~40 lines new |
| Delete `exact-agent-id`, the four `re-matches`, and the inline same-origin `if` | ~25 lines deleted |
| Replace 11 hand-built URL strings with `path` calls | ~30 min |
| Tests: one route-table test (every `:name` resolves, `path` round-trips, no conflicts) + keep the existing web tests green | ~30 min |

**Net: half a day, one owner (`src/seon/render/web.clj` plus three renderer
files for hrefs), one commit.** No behavior change is intended beyond the
default-handler question in §4.4.

Two parity checks to run live, not assume:

1. Percent-encoded agent ids still decode — reitit decodes in the trie
   (`trie.cljc:206-259`), current code uses `URLDecoder` at `web.clj:888`.
   Falsify with an id containing `%2F`.
2. The SSE feed route still holds a long-lived connection through
   `reitit.ring/ring-handler` — it is a plain Ring handler wrapper, so it
   should, but the http-kit drain path (`web.clj:502-528`) is the thing that
   actually matters and must be re-proven on the wire, not by a passing test.

## 5. Open owner questions

1. **Route table as `def` (recommended) or as `:seon.route/*` facts (old
   precedent)?** §4 argues the `def`; facts return when agent-authored pages
   land, as a second projection into the same vector.
2. **Miss behavior: 404 (current) or 302 home (old, `ui.md:468-475`)?**
3. **`/ns/{namespace}` naming** — is `/ns/` the path prefix the owner wants for
   ruling #17's namespace pages, or should the namespace page live at the root
   of a shorter prefix?
