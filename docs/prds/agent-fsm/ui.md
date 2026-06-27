---
type: prd
status: draft
tags: [prd, web, agent, architecture]
---

# Unified Context + UI — the single data model

The canonical spec for one coherent system where the agent's **context** and the
human's **UI** are the same data, dual-rendered. This is the artifact to take to
R (core-context lane) for agreement. Vocabulary settled with the owner
(2026-06-27): **block · canvas · world · root agent · slot · layout · route**
(the all-agents overview is the root agent's world at `/`, not a separate dashboard),
`seon.ui.*` for the web layer with `seon.render.*` (the render engine, not
UI-specific) kept separate. Supersedes the `section`/`panel` naming. Supporting research:
[[reitit-routing-2026-06-27]], [[ui-override-research-2026-06-27]],
[[ui-override-plan-2026-06-27]].

## TL;DR

One atom — the **block** (`:seon.agent.ctx/block`): a DB-derived map with up to two
**renders** — an **ai render** (`:seon.render/ai`, text in the prompt) and an
**html render** (`:seon.render/html`, a tile on the page). An agent's context is a
vector of blocks (`:seon.agent/ctx`), merged over a seeded default set
(`default-blocks`) and sorted by `:seon.agent.ctx/priority`. The **prompt** is the ai
renders concatenated; the **page** is a **layout** (a fn of the db) that places
block html renders into named **slots**. **Routing is data** consumed by reitit (the front door): a
route's handler IS a layout, so nested routes ARE nested layouts — `/` is the
**root agent's world** (the seeded system agent `:seon.agent/id "root"`, whose
system-scoped blocks render the agent previews — NOT a separate dashboard),
`/agent/{id}` is an agent's **world** (the **canvas** focal block + a priority
scroll), `/agent/{id}/app/{x}` is an agent app. The **live channel** stays ours
(datastar/SSE + the `!last-tree` slot-tree diff); reitit only routes the request
that opens it. Every failure becomes a friendly **error value** (one base
`:seon/error`) shown two ways from one source: an **error tile** (human) and the
**warnings block** (agent). Every layer is a symbol or a datom, so a third party
overrides any of it — blocks, canvas, layout, root world, routes, CSS, client —
reusing the same primitives, with zero `src/seon` edits.

## 1. The block — the one atom

```clojure
;; registered in seon.agent.ctx (the ns whose name the keyword carries; ns moved
;; seon.ctx → seon.agent.ctx, CLJS track only). Reference the registered render
;; shapes — do NOT re-inline [:or :symbol :string] (see data-model §6 finding 1).
(schema/register! :seon.agent.ctx/block
  [:map
   [:seon.agent.ctx/name     :seon.agent.ctx/name]   ; per-agent merge key = prompt header = DOM #tile-<name>
   [:seon.agent.ctx/priority :seon.agent.ctx/priority] ; sort: prompt order AND default-scroll order
   [:seon.render/ai          {:optional true} :seon.render/ai]    ; present → ai render (prompt text)
   [:seon.render/html        {:optional true} :seon.render/html]]) ; present → html render (a tile)

```

- **Presence of a render key decides where the block appears** (no `:kind`
  discriminator — datomic-idiomatic). ai-render-only = prompt only (no tile).
  html-render-only = a tile only (zero prompt tokens). Both = both places.
- **`:seon.agent.ctx/name` is the single identity** — the upsert key, the prompt-section
  header, AND the DOM slot id `#tile-<name>`. One keyword, three roles, always in
  sync. This is what makes "the agent edits the same thing the human sees" true.

## 2. Where blocks come from — defaults + the agent's own + the override seam

The blessed `set-tee-fn!` idiom (a `defonce ^:private` atom + installer + guarded
read), so a third party overrides the default SET with no `seon/` src edit and no
capture-dance:

```clojure
(defn core-blocks [] ...)            ; PUBLIC, stable — seon's defaults; third parties call to EXTEND
(defonce ^:private !blocks-provider (atom nil))
(defn set-blocks-provider! [f] (reset! !blocks-provider f) nil)   ; the ONE override hook
(defn default-blocks []              ; the guarded seam context-root reads (errors-as-values)
  (if-let [f @!blocks-provider]
    (try (f) (catch :default e <core-blocks + a loud :blocks-provider-error block>))
    (core-blocks)))

```

- Per-agent attr `:seon.agent/ctx` `[:vector {:seon.db/component true}
  :seon.db/ref]` — blocks the agent owns; verbs `add-block!` / `remove-block!`.
- The ONE merge seam (`context-root`): `(gather-blocks (default-blocks)
  (agent-blocks entity))` sorted by `:seon.agent.ctx/priority`; agent blocks override
  core by `:seon.agent.ctx/name`.
- A pure ADD needs NO provider override (name a block + its symbols; symbols
  resolve late). `set-blocks-provider!` is for changing the DEFAULT SET every
  agent sees.

## 3. The two renders (one engine)

- **Prompt (agent):** R's `render-context` renders each merged block's ai render
  (guarded) and concatenates by priority. The byte-stable prefix at low priority
  is preserved for provider prefix-caching.
- **Page (human):** a layout fn places each block's html render into a slot.
- Both resolve `:seon.render/ai` / `:seon.render/html` symbols through the ONE
  `seon.render` engine — agent-authored symbols SCI-bounded, core symbols direct.

## 4. Slots + layouts

- **slot** — `(slot :name)` → `[:div {:id "tile-<name>" :data-slot :name}]`, an
  EMPTY placeholder. Recursive: it does NOT resolve `:name`, it marks a hole.
  Resolution happens at expansion — render the block's `:html`; if THAT output
  contains more `(slot …)`, recurse. Generalizes the injected
  `:seon.render/render` handle (render.cljs) into a named, DB-keyed slot.
- **layout** — a block (or a route handler) whose `:html` is `(fn [request]
  hiccup-of-slots)` — queries the db (the request carries it) + path-params, owns
  placement + CSS only. Whether a render is a **layout** is NEVER stored — it is
  purely whether its output contains child slots (none = a leaf tile).

## 5. The pages + the canvas (all are agent worlds)

- **canvas** — the focal **block** `:canvas` (high priority), likely a small
  nested layout `[(slot :transcript) (slot :composer)]`: the agent↔human
  communication block. Because it is a block, a third party overrides the whole
  comms block by overriding one symbol.
- **world layout** (`/agent/{id}`) — places `(slot :canvas)` focal + the agent's
  other blocks' html renders (tiles) as a priority-sorted vertical scroll.
- **the root agent's world** (`/`) — the multi-agent overview is NOT a separate
  page kind: it is the **root agent's** world (`:seon.agent/id "root"`), rendered by
  the IDENTICAL world layout / block / slot machinery. Its system-scoped blocks
  query across all agents and place `(slot :agent-<id>-preview)` per agent in a
  grid; each preview is a compact agent render, reverse-routed-linked into that
  agent's world. Step back / dive in. There is no "dashboard" mechanism — root is
  just an agent with system-scoped ctx and an elevated capability grant (through the
  same `/call` gate). The render + route tree: root world (`/`) → per-agent worlds
  (`/agent/{id}`) → apps (`/agent/{id}/app/{x}`).
- **app layout** (`/agent/{id}/app/{x}`) — an agent-authored app; its handler is a
  layout placing its own slots.

## 6. Routing is data — reitit as the front door

```clojure
(schema/register! :seon.route/pattern :string)                            ; reitit syntax "/agent/{id}"
(schema/register! :seon.route/method  :keyword)                           ; :get :post …
(schema/register! :seon.route/name    [:keyword {:seon.db/identity true}]) ; reverse routing, unique
(schema/register! :seon.route/owner   :seon.db/ref)                       ; owning agent — rides as route-data for auth
(schema/register! :seon.route/middleware {:optional true} [:vector :keyword]) ; later auth; v1 absent
;; the handler reuses :seon.render/html — a route handler IS a layout symbol

```

`db->routes` (~10 lines) projects route datoms → reitit's vector; the handler
symbol + `:seon.route/owner` ride as opaque route-data. Verified against
`reference-code/reitit` 0.10.1 (vendored), all `.cljc` (runs in the Node pod):

- match + path-params parsed once; auto precedence; **build-time path + name
  conflict detection** (`core.cljc` throws on overlap/dup-name — our hand-rolled
  `cond` silently shadows).
- **reverse routing** (`match-by-name` → URL from name+params): a layout links to
  child routes by name, no string-building.
- **nested route data meta-merges parent→child** (`ring/compile-result:80`):
  `:owner`/middleware set on `/agent/{id}` flow to every child — **nested routes
  ARE nested layouts.**
- **middleware seam** (`middleware.cljc` `IntoMiddleware`): middleware can be a
  *keyword* resolved through a `::registry`, and a `:compile` middleware reads
  route-data and vanishes when N/A. This is the auth answer AND the error-catch
  answer (§8). v1: empty registry. Later: add `:authz` to a subtree's route-data +
  one registry entry — ZERO handler edits.
- **3-arity async handler `[request respond raise]`** (`ring.cljc:391`) maps onto
  our Promise handlers; `reloading-ring-handler` rebuilds the router from a thunk
  per request → "router is a pure derived value of the route datoms" is a
  one-liner.
- **The capability gate STAYS** (`seon.call`): reitit dispatches the URL;
  `call.cljs` authorizes the fn (namespace-as-route → owning agent →
  granted-`:seon.fn` → SCI-bounded). reitit replaces the FRAGILE part (dispatch),
  NOT the SECURE part.
- Seeded core routes: `/` root agent's world, `/agent/{id}` world,
  `/agent/{id}/feed` SSE, `/call` action door. Agents add `/agent/{id}/app/{x}`
  rows (capability-gated, handler in the agent's own `my.agent.<id>` ns).
- Integration: a ~20-line Node↔Ring adapter (the pod is raw `node:http`); static
  files stay `node:fs` (reitit's file handlers are the ONLY `#?(:clj)`-gated part;
  everything else is host-neutral).

## 7. The live channel + process topology (SSE)

**SSE is a pure derivation of the tx-log — not the agent pushing.** Today the
pod's ONE tx-listener (`db/listen! ::inspector`, `inspector.cljs:1752`) fires on
every datahike commit, re-renders the affected fragments from its db read-replica,
and writes `event: datastar-patch-elements` frames to each open connection
(`!sse-by-agent`). The agent only `transact!`s datoms (forwarded to the JVM
writer); it never touches a browser stream. It LOOKS agent-driven only because the
agent loop + the streamer share the one pod process today.

- reitit has ZERO SSE/streaming primitives (grepped all 16 modules) — by design;
  routing ⊥ live-updates. Adopting reitit touches the SSE engine NOT AT ALL.
- Our engine unchanged: datastar + packetstar.js + the per-connection `!last-tree`
  slot-tree diff (BFS to fixpoint; leaf patch on content change; ONE
  fully-expanded subtree patch on shape change → packetstar.js byte-unchanged).
- reitit routes the full-page GET (→ layout → HTML) + the action POSTs + the GET
  that OPENS the feed; thereafter patches stream over the raw socket. The one seam:
  the SSE handler needs the raw `node:http` `res` — keep SSE routes in the thin
  adapter before reitit, or inject `res` + return `{:seon.http/hijacked true}`.
  ~10 lines.

**The relocation property — why isolation + a UI-host are free.** Because the
stream is derived from commits, the streamer can be ANY process holding a
read-replica + a tx-listener; it need not be the agent's process. The convergence
topology:

- **Isolated per-agent Node runtimes** — each agent its own Node process (own SCI
  sandbox + event loop = isolation), writing datoms to the shared JVM DB.
- **One dedicated UI-host Node** (the center point the browser connects to) — holds
  a shared-DB read-replica, runs the tx-listener, derives every agent's world (the
  root agent's world at `/` included) from `:seon.agent/ctx`, streams SSE. Reads
  every agent's data; runs no agent.
- **JVM `wire-server`** — authoritative writer + heavy processing; may host its own
  admin UI (needs the render layer portable), but the user-facing UI is the Node
  host.

**The hard invariant (already honored): no agent code ever touches an SSE
connection.** agent → datom → tx-listener → derived render → SSE, one way. Actions
too: a browser POST hits the UI host → routed to the owning agent's runtime to
execute (SCI-bounded, isolation preserved) → result datoms → tx-listener → stream.
The DB is the bus both ways. **v1 = the single pod plays all roles**; the split is
the target and changes WHICH process runs the tx-listener, NOT the data model. R's
tx-feed replay + reconnect-since-t (`tx_feed_replay_test` on this branch) is the
enabler.

## 8. Friendly errors → the warnings block

One source, two renders — the agent's ask, fitted to the existing machinery.

- **One source:** the guarded renderer (`seon.render`, exists) turns ANY render
  failure — block ai/html throw, missing symbol, SCI deadline, capability
  denial, route-handler throw (caught by a reitit middleware) — into a first-class
  **error value** instead of crashing siblings:

  ```clojure
  (schema/register! :seon.render/error
    [:map
     [:seon.error/message :string]                      ; friendly, not a raw stack
     [:seon.error/where   :keyword]                      ; the block name / route name
     [:seon.error/symbol  {:optional true} :symbol]      ; the offending fn
     [:seon.error/hint    {:optional true} :string]])    ; the actionable fix

  ```

- **Human (page):** the html render shows an error value as an **error tile** —
  siblings/ancestors untouched, self-heals next render.
- **Agent (prompt):** the **`:warnings` block** aggregates all current error values
  into friendly, fix-oriented prose in its ai render (so they enter the prompt) +
  an error list in its html render.
- **Wired into the EXISTING registry:** add a `:render-health` check to
  `seon.warn/checks` (today: failed-evals, bad-ref, slow-evals, failing-tests,
  spec-hygiene). Pure fn of state, never stored, self-healing — generalizes the
  existing `missing-slot-render` self-heal line.
- **Friendly + actionable:** "Block `:status` html `my.agent.abc/status-tile`
  threw `TypeError: x is undefined` — fix the fn; it reappears next render."
  Missing-symbol / arity / schema-rejection / capability-denied all flow the same
  way.
- **reitit dividend:** the SAME middleware mechanism that does auth-later also
  wraps handlers to catch throws → error value, so route/layout failures appear
  identically (human error page + agent warnings entry if the agent owns it).

## 9. Total third-party override (the acme proof)

Every layer is a symbol or a datom; acme overrides each reusing the same
primitives, with zero `src/seon` edits:

| Layer | Override mechanism | Default |
|---|---|---|
| **block set** | `set-blocks-provider!` | `core-blocks` |
| **a tile's look** | the block's `:seon.render/html` symbol | seon's html render fn |
| **canvas / any layout** | the layout block's `:html` symbol | seon's layout fn |
| **root agent's world (`/`)** | the `/` route handler symbol (root's world-layout) | `seon.ui/root-world-layout` |
| **routes / apps** | `:seon.route/*` rows | seeded core routes |
| **CSS / theme** | `SEON_BRAND_CSS` (injected on ALL heads) | Phosphor |
| **client JS** | `SEON_EXTRA_PUBLIC` + scripts | packetstar.js |

acme installs at preload in `acme/src/acme/overrides.cljs` (already `:require`d by
`acme.pod`), exactly where it already does `(reset! client/!extra-core-vars …)`.
Acceptance: a COMPLETELY different acme UI end-to-end, `bin/acme build` 0 warnings,
default seon UI unchanged.

## 10. Malli throughout

- Every map is a registered `:malli/schema`: `:seon.agent.ctx/block`, `:seon.route/*`,
  `:seon.render/error`, layout I/O — instrumented like everything else.
- reitit route-data is OPEN maps → our malli-validated maps ride as route-data
  with no friction.
- reitit-malli coercion (vendored, optional): validate/coerce path-params / query
  / body against a route's `:parameters` malli schema — free alignment since we
  malli-everything, not new work.
- `set-blocks-provider!` is `[:=> [:cat fn?] :nil]` — instrumented; a throwing
  provider falls back to `core-blocks` + a loud error block.

## 11. Lane split (who owns what — both must agree)

- **R — core context.** `:seon.agent.ctx/block` schema + `:seon.agent/ctx`,
  `default-blocks`/`core-blocks`/`set-blocks-provider!`, the `seon.render`
  dual-render engine, prompt assembly (`render-context`), fixed system-text, the
  `:seon.route/*` + `:seon.render/error` schema registration + seed, and
  `seon.warn` (incl. the new `:render-health` check). R's derived tiles become
  html-bearing blocks.
- **U — UI/UX** (`seon.ui.*` + web + css/js). The layout fns
  (root-world/world/app), the slot primitive + slot-tree BFS + the `!last-tree` SSE
  diff, the shell, ALL of reitit (`db->routes`, the Node↔Ring adapter, the router
  rebuild, the middleware registry), the error-tile render, CSS/theme,
  packetstar.js.
- **Shared contract:** the `:seon.agent.ctx/block` map shape, resolved through
  `seon.render`. reitit lives entirely in U, consuming `:seon.route/*` datoms whose
  schema R registers — the ONLY coupling.

## 12. Migration / convergence (atomic)

- **Supersedes R's in-flight `section → panel`.** The unit is **`block`**
  (`:seon.agent.ctx/block`), vector **`:seon.agent/ctx`**, producer **`default-blocks`**,
  override **`set-blocks-provider!`** — NOT panel / `:seon.agent/panels`. Both
  lanes rename to `block` in ONE atomic patch + `bin/seon cluster reset default`.
- **The 5 asks to R:** (1) `:seon.render/ai` optional in the block schema; (2)
  rename `section`→`block` + `:seon.agent/sections`→`:seon.agent/ctx` + the ns move
  `seon.ctx`→`seon.agent.ctx` with every `:seon.ctx/*`→`:seon.agent.ctx/*` (CLJS
  track only; FROM-side grep keeps the literal `:seon.ctx/section`); (3)
  `add-block!`/install accept html-only + batch upsert-by-name; (4) expose ONE pure
  `(fn [db agent-id] → merged priority-sorted blocks)`; (5) R's derived tiles
  become html-only blocks (the crux — the prompt-set and the page-set become ONE
  set).
- U-lane web Datalog sites that read the renamed attr fail SILENTLY (empty result,
  not an error) if missed — grep-verify zero `:seon.agent/sections` /
  `:seon.ctx/section` before the reset.
- system-text stays a fixed code const (non-overridable) — keep R's lockdown.

## 13. Open decisions / risks

- **Dynamic-slot streaming** is the genuinely hard part — the `!last-tree`
  shape-change rule needs explicit tests (move + content-change in one tx; churny
  subtrees; coalesced flip-flop). Measure before memoizing `(slot-id, basis-t)`.
- **Capability bound:** agent layouts/handlers/blocks MUST go through SCI-bounded
  invoke (deadline), never `lookup-value`-direct, or a runaway freezes the
  single-threaded pod. v1 routes read-only + own-fns.
- **Web bundle:** keep the bootstrap CLJS compiler OUT of the web bundle (the lean
  core table tile.cljs uses today).
- **Atomic wide rename** across the shared tree (section→block, the `seon.ctx →
  seon.agent.ctx` ns move + `:seon.ctx/*` → `:seon.agent.ctx/*`, `:seon.agent/ctx`,
  the two renders) — one unit, fresh `bin/test-cljs`, atomic commit.
- **Agent preview render** (the root agent's world) — does a preview reuse the
  agent's html renders in miniature, or a dedicated compact `:agent-preview` tile?
  (Leaning: a dedicated tile that reverse-routes to the world.)
- **Per-cluster CSS keying** if one wire-server themes N clusters (today brand is a
  global singleton).
