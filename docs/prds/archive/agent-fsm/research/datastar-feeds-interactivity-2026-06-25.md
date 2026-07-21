---
type: research
status: active
tags: [research, web, agent, flow]
---

# Datastar feeds + agent-authored interactivity — design & findings

## TL;DR

- **Datastar has no built-in stream multiplexer.** Each `@get/@post/...`
  action opens its **own** `fetch`-based SSE connection
  (`library/src/plugins/actions/fetch.ts`), and every SSE event carries a
  `selector` + `mode` so it patches a targeted DOM region. A "video-wall"
  is therefore built two ways, both native: **(1) one SSE per feed** (N
  independent `data-init="@get('/feed-x/sse')"` elements, each its own
  pipeline — a crashed feed = one dead tile) or **(2) one SSE that
  targets many regions** by selector. **Seon already uses pattern (1)** —
  `inspector.cljs` opens separate `/agent/<id>/sse`,
  `/agent/<id>/debug/sse`, `/agents/sse`, `/data/sse` streams. The
  video-wall is mostly a layout + lifecycle story on top of what exists.
- **Agent-authored interactivity already has a working blueprint on the
  JVM track**: `seon.web.reactive.transform/transform-hiccup` rewrites
  `[:button {:on:click :my-fn} "x"]` → `@post('/ns/<ns>/my-fn')`, and
  `seon.ns.routes/function-call-handler` resolves + Malli-coerces +
  validates + invokes the fn. **What's missing on the ACTIVE pod**: that
  whole path is `.clj`-only; the pod (`serve.cljs`) has no `/call` route,
  no transform, and the JVM capability check is a `seon.*`-prefix
  whitelist — **not** the eval/render sandbox capability surface. The new
  work is porting the rewrite+route to the pod and routing the call
  through the **same sandbox** eval/render already use.

---

## Part A — Datastar feed model + video-wall design

### A.1 The SSE event model (RC7 — renamed in RC)

Datastar 1.0-RC collapsed the old `merge-fragments`/`merge-signals` verbs
into **two** event types (`reference-code/datastar/library/src/engine/consts.ts`,
`.../datastar-clojure/.../consts.clj`):

- `datastar-patch-elements` — patch HTML into the DOM.
- `datastar-patch-signals` — RFC 7386 JSON-merge-patch the signal store.

Plus `execute-script!` (delivered as a `patch-elements` append of a
`<script>`). The Clojure SDK surface is
`.../clojure/api.clj`: `patch-elements!`, `patch-elements-seq!`,
`remove-element!`, `patch-signals!`, `execute-script!`.

**Targeting.** Each `patch-elements` event carries dataline opts
(`consts.clj`): `selector <css>`, `mode <patch-mode>`,
`useViewTransition`, `namespace`. Patch modes
(`element-patch-mode-*`): `outer` (default — **morph**, datastar's
idiomorph-style merge), `inner`, `replace`, `remove`, `prepend`,
`append`, `before`, `after`. With no selector, datastar targets by the
fragment's own `id`. So **one stream can update many regions** by
emitting events with different selectors; morph keeps DOM identity/focus
stable across pushes.

### A.2 Is there native multiplexing? No — one SSE per action.

`fetch.ts` `createHttpMethod` builds `@get/@post/@put/@patch/@delete` as
**independent** `fetchEventSource` calls. Each is a separate HTTP/SSE
connection with its own retry/backoff (`retryInterval`, `retryScaler`,
`retryMaxCount`, exponential), its own `AbortController`, and
`openWhenHidden`. There is **no** server-push fan-out to "all regions"
over one socket — the client opens as many SSE connections as it has
live actions. (Browsers cap ~6 concurrent SSE per origin over HTTP/1.1;
HTTP/2 multiplexes many streams over one TCP connection, so Caddy/h2 in
front lifts that cap — relevant if a wall has dozens of tiles.)

Content negotiation: the response `Content-Type` decides handling
(`fetch.ts` `dispatchNonSSE`) — `text/event-stream` = streaming SSE,
`text/html` = a single `patch-elements` (one-shot), `application/json` =
`patch-signals`, `text/javascript` = script. So a feed can be a
long-lived `text/event-stream` while an interaction POST replies
`204 No Content` (mutate-only) or a one-shot HTML patch.

### A.3 Compressed full-update push

Seon's model is **render → hash → push only on change**
(`src/seon/web/sse.clj:248` uses `:event-id new-view-hash`; the pod
inlines the same in `inspector.cljs:1594`). Each tile re-renders its
whole region server-side; if the content hash is unchanged, nothing is
sent; if changed, the full fragment is pushed and **morphed** in. This is
the "push compressed full updates" model — full HTML, cheap because (a)
morph means the browser only mutates the diff, and (b) the wire bytes
compress hard.

Compression is a **write profile** on the SSE connection
(`.../adapter/common.clj`): `gzip-profile`, `gzip-buffered-writer-profile`,
and brotli via the `sdk-brotli` lib (`->brotli-profile`,
`->brotli-buffered-writer-profile`, `content-encoding "br"`, tunable
`:quality`/`:window-size`, defaults q5/w24 — the hyperlith approach).
Repeated full-DOM pushes over a long-lived brotli stream benefit from the
shared compression window, so successive near-identical renders cost a
few bytes. **Pod gap:** `serve.cljs` writes raw SSE strings via
`write-status!`/the SSE pipe — no gzip/brotli yet; a Node feed would add
`zlib` (brotli) on the response stream to match.

### A.4 Recommended video-wall design

**One SSE per feed (pattern 1), reactive-render per feed.** Each tile is
an isolated server-side pipeline:

```
[:div.tile {:id "feed-trading"
            :data-init "@get('/feed/trading/sse')"
            :data-on:online__window "@get('/feed/trading/sse')"}]
```

- Each feed = a `render-fn` (fn of the DB, per the reactive-context
  principle) wrapped by an `sse/render-handler` that polls/derives,
  hashes, and pushes a `datastar-patch-elements` morph into
  `#feed-<name>`. This is exactly `seon.ns.routes/get-namespace-handler`
  + `instance-sse-handler` generalised to arbitrary feeds.
- **Isolation = the whole point.** One feed's render throwing, hanging,
  or its SSE dropping is one dead/stale tile; datastar auto-retries that
  one connection (`fetch.ts` backoff) and `data-on:online__window`
  re-arms it on network resume. Other tiles are untouched — separate
  connections, separate handlers. The shell page is a static skeleton
  (`namespace-skeleton`) of `id`'d tile divs; nothing in the shell can
  black-screen.
- Debug view is **its own feed** (`/agent/<id>/debug/sse`, already
  exists) — same mechanism, different render-fn.
- Compression: give each feed's `->sse-response` a brotli write profile
  (JVM) / wrap the Node response in brotli (pod).

**Where datastar falls short / what we build:**

- No multiplexer → **we** own the feed registry (which feeds exist, their
  render-fns, lifecycle). Trivially a section-function-of-the-DB.
- No back-pressure/coalescing across feeds → the hash-gate
  (`sse.clj`) is **our** coalescing; keep it per-feed.
- HTTP/1.1 connection cap → front with HTTP/2 (Caddy) when tile count is
  high.
- Cross-tile atomicity (one tx updates several feeds) is **not** a
  datastar concern — each feed independently re-derives from the DB on
  the next tick, so a single tx naturally surfaces in every affected
  feed (reactive-context: "Agent A's write shows up in B's render").

---

## Part B — Agent-authored interactivity (authoring → rewrite → route → sandbox-call)

### B.1 What already exists (JVM track, `.clj`)

The full round-trip is **already implemented** for the paused JVM track —
this is the blueprint:

1. **Authoring surface** — `src/seon/web/reactive/transform.clj`. An agent
   writes clean hiccup:
   `[:button {:on:click :increment} "Add"]`,
   `[:input {:field :seon.trading/symbol}]`.
2. **Rewrite** — `transform-hiccup` (postwalk) rewrites event attrs:
   `:on:click :increment` → `:data-on:click "@post('/ns/<ns>/increment')"`;
   `:on:click:form` → adds `{contentType:'form'}`;
   `:field :kw` → `{:name (pr-str kw)}` (qualified-keyword form field).
   The `@post(url)` form sends **filtered signals as JSON** by default
   (`fetch.ts`: `payload ?? filtered(...)` → `JSON.stringify`); the
   `:form` variant collects the nearest `<form>` as
   `x-www-form-urlencoded`.
3. **Route** — `src/seon/ns/routes.clj` `function-call-handler` (POST
   `/ns/:namespace/:function`, registered in `routes.clj` dynamic-routes):
   `parse-form-body` → `resolve-and-call` →
   `seon.web.reactive.actions/resolve-action` (capability gate) →
   `extract-fn-input-schema` + `coerce-with-schema` (Malli
   string-transformer) + `m/validate` → invoke the fn (which transacts) →
   `{"success":true}`. The reactive render→hash→push then updates the
   feed on its next SSE tick.
4. **Capability/security** — `actions/resolve-action` only resolves
   `valid-action-namespace?` = symbol whose name starts with `"seon."`,
   then `ns-resolve` + `var?`/`fn?`. Args are Malli-coerced and validated
   against the fn's `:malli/schema` input map before the call.

### B.2 The gap (ACTIVE pod, `.cljs`)

The pod (`src/seon/web/serve.cljs`) has **no** generic call route — only
`/chat`, `/clear`, `/agents/new`, `/agent/<id>/complete`, `/log`
(`serve.cljs` `handler` dispatch ~L479-500). There is **no** `transform`
ns and **no** `read-signals`/`/call` on the pod. Interactions today are
hand-wired endpoints (`inspector.cljs:616` notes the complete-agent
button posts "the same endpoint Datastar `data-on-click__post` would
call" — bespoke, not generic). The sandbox is `seon.eval/eval`
(persistent compile-state, timeout-raced, never-throws). So porting
B.1 to the pod is the real work.

### B.3 Three locked principles

The pod design is locked to three principles (owner refinement,
2026-06-25):

1. **Keep the web predictable.** The browser only ever sees **standard
   datastar** — `@post(url, {...})`, `data-on-*`, signals. No custom
   client runtime, no bespoke browser protocol, no magic JS. A tile is
   debuggable like any hypermedia app (DevTools network tab shows a plain
   POST). **All cleverness is server-side.**
2. **The sugar is a render-time rewrite** — macro-like, but it runs
   **server-side when the tile renders**, not in the browser. It
   recognises an agent's fn-call or fn-ref in a handler slot and emits the
   same standard `@post('/call', {...})`.
3. **Routing is the namespace.** The handler symbol is
   **agent-namespaced** (`my.agent.HtK-xxx/my-fn`). `/call` resolves the
   **owning agent** from the symbol's namespace and invokes the fn in
   **that agent's sandboxed VM**. No routing table — the name *is* the
   route. Same call-routing path as eval + render-fns.

### B.4 Authoring surface + render-time rewrite (two converging cases)

An agent authoring a tile writes a handler in a `:on:<event>` slot in one
of two shapes; **both rewrite to the same predictable `@post`**:

**Case 1 — fn-CALL: args bound at RENDER time.** The agent writes a call
form whose args are known while the tile renders (e.g. a row id from the
data being rendered):

```clojure
;; agent hiccup, inside a render over rows:
[:button {:on:click (cancel-order! order-id)} "Cancel"]
;;                                  ^ order-id is in scope at render time
```

The render-time transform serialises the **render-bound args** into the
`@post` payload:

```clojure
[:button {:data-on:click
          "@post('/call', {payload: {fn:'my.agent.HtK-7a2/cancel-order!',
                                     args:'[\"ord-918\"]'}})"} "Cancel"]
```

**Case 2 — fn-REF: args pulled from CLICK-time signals.** The agent
writes a bare symbol; the args come from the datastar signal store (form
field values) at click time:

```clojure
;; agent hiccup, a form whose inputs bind signals:
[:form
 [:input {:field :qty}]            ; binds signal `qty`
 [:button {:on:click submit-order!} "Buy"]]   ; bare fn-ref
```

rewrites to a `@post` that carries the **filtered signals** (datastar's
default body when no `payload` is given — `fetch.ts`:
`payload ?? filtered({include,exclude})`):

```clojure
[:button {:data-on:click
          "@post('/call', {payload: {fn:'my.agent.HtK-7a2/submit-order!'}})"}
 "Buy"]
;; client merges signals → POST body {fn, signals:{qty: 10, ...}}
```

So the two cases **converge** on one endpoint and one wire shape: a `fn`
symbol always, plus **either** render-bound `args` **or** click-time
`signals`. The handler treats them uniformly (signals become the arg map
when `args` is absent — exactly the JVM `:field`/`contentType:'form'`
path generalised).

**The transform.** A server-side postwalk (port + extend
`reactive/transform.clj` to `.cljc`) that, for each `:on:<event>` attr:

- distinguishes a **list form** `(sym & args)` (Case 1) from a **bare
  symbol** `sym` (Case 2) in the handler slot;
- for Case 1, evaluates the arg expressions in the render scope and
  serialises them;
- emits `:data-on:<event> "@post('/call', {payload:{fn:'<qualified>', …}})"`.
- The fn symbol is left **fully agent-qualified** (the render knows the
  owning agent's ns — it's rendering *that agent's* tile), which is what
  makes B.5 routing tableless.

**Arg serialisation.** Render-bound `args` (Case 1) are serialised as a
**transit-JSON string** (preferred over raw EDN: lossless keywords/sets/
instants, and it rides safely as a JSON string value inside the datastar
payload). Click-time values (Case 2) ride as datastar **signals** — JSON
primitives from form inputs, parsed by `read-signals`. The route reads
`args` back with transit; signals with JSON. (EDN-string is an acceptable
fallback if transit-cljs is not wired on the pod; transit is the
recommendation because the JS↔CLJS boundary already favours it.)

### B.5 The `/call` route — namespace-as-route into the owning VM

New `POST /call` in `serve.cljs`:

1. **read-signals / read-body.** `read-body` → JSON parse (pod has
   `read-body`/`parse-urlencoded`; add a JSON branch). The datastar
   `read-signals` equivalent is "GET → `?datastar=`, POST → body"
   (`api/signals.clj get-signals`). Extract `{fn, args?, signals?}`.
2. **Resolve the owning agent from the symbol's namespace.** The `fn`
   symbol is agent-namespaced (`my.agent.HtK-7a2/cancel-order!`); its
   **namespace identifies the agent** (the agent's home ns / VM). No
   routing table is consulted — the router maps ns → agent VM directly.
   Reject if the ns is not a live agent the caller may reach.
3. **Sandbox-invoke in THAT agent's VM.** Reconstruct the call against
   the owning agent's compile-state and invoke via `seon.eval` (a thin
   `invoke` over `eval`), passing render-bound `args` **or** the signal
   map as the arg. Same capability surface, timeout, and never-throws
   contract as agent eval and render-fns. Capability check = the
   eval/render gate (only agent-granted/registered `:seon.fn` entities
   callable) — **not** a `seon.*` string prefix (the JVM stopgap). The
   HTTP layer cannot widen what's callable; the sandbox decides.
4. **Malli-validate args** against the target fn's `:malli/schema` before
   invoke (instrumentation also enforces it inside the VM; explicit
   pre-validation yields a clean 400).
5. The fn **transacts** (`seon.db/transact!` → wire-server). The reactive
   feed (Part A) re-derives and pushes the morph on its next tick.
   Optionally `/call` replies a one-shot `text/html` `patch-elements` for
   instant local feedback (`fetch.ts` `dispatchNonSSE`).

Because the browser sees only `@post('/call', …)` with signals, the
front end stays a plain hypermedia app (principle 1); the rewrite is the
only "compiler", and it runs at render time server-side (principle 2);
the agent-qualified symbol carries its own route into the owning sandbox
(principle 3).

### B.6 The unified call-routing path (the key claim)

```
                       ┌───────────────────────────────────┐
  agent eval  ───────► │  seon.eval (owning agent's VM):    │ ──► seon.db/transact!
  render fn   ───────► │  ns→agent resolve, capability-     │      (→ wire-server)
  interaction ───────► │  checked invoke, Malli-validated,  │
   (/call)             │  timeout, never-throws             │ ──► reactive render
                       └───────────────────────────────────┘      → hash → SSE push
```

Eval, render-fn invocation, and UI interactions are **three entry doors
to one sandboxed-call mechanism**. They differ only in how the form/args
arrive (REPL string / render call / rewritten `@post` payload), never in
the trust boundary, the ns→agent resolution, or the execution path. Same
"turtles all the way down" unification the project applies to context and
rendering: an interaction is just an eval whose source was authored as
hiccup, rewritten at render time, and routed by its namespace into the
owning agent's VM.

---

## What exists vs what's new

| Piece | Status |
|---|---|
| Datastar SSE event model (patch-elements/signals, morph, selectors) | exists (lib + SDK) |
| Per-feed independent SSE streams (video-wall pattern 1) | **exists** — `inspector.cljs` agent/debug/agents/data feeds |
| render→hash→push coalescing | exists — `sse.clj:248`, `inspector.cljs:1594` |
| Compression write profiles (gzip/brotli) | exists JVM (`adapter/common.clj`, `sdk-brotli`); **new on pod** (Node zlib/brotli) |
| Standard datastar browser surface (`@post`/signals/`data-on-*`) | exists — keep it; no custom client (locked principle 1) |
| Render-time rewrite of `:on:click` handler → `@post` | partial JVM (`reactive/transform.clj` does keyword-fn → `@post('/ns/...')`); **new**: two-case fn-CALL/fn-REF rewrite to `/call`, port to `.cljc`, run at render time |
| fn-CALL (render-bound args, transit) vs fn-REF (click-time signals) | **new** — converge on one `@post('/call', {payload:{fn,args?}})` |
| `/call` handler: read-signals → Malli coerce/validate → invoke | adapt JVM `function-call-handler`/`resolve-and-call` (`ns/routes.clj`); **new on pod** (`/call` in `serve.cljs`) |
| `read-signals`/JSON body parse | exists (`api/signals.clj`; JVM `wrap-json-body`; pod `read-body`/`parse-urlencoded` — add JSON branch) |
| **Namespace-as-route** → resolve owning agent from fn's ns → its VM | **new** — replaces both the JVM `seon.*` prefix whitelist AND any routing table; the agent-qualified symbol IS the route |
| Capability gate = **eval/render sandbox** surface, in the owning VM | **new** — route through `seon.eval`, not `ns-resolve` + prefix check |

## Cited files

- Datastar lib: `reference-code/datastar/library/src/plugins/actions/fetch.ts`
  (per-action SSE, payload, content-negotiation, retry),
  `.../engine/consts.ts`, `.../plugins/attributes/on.ts`.
- Datastar Clojure SDK:
  `reference-code/datastar-clojure/libraries/sdk/.../api.clj`,
  `.../api/signals.clj` (`get-signals`/read-signals),
  `.../consts.clj` (event types, patch modes),
  `.../adapter/common.clj` (write profiles),
  `.../sdk-ring/.../adapter/ring.clj` (`->sse-response`, gzip profiles),
  `.../sdk-brotli/.../brotli.clj`.
- Seon JVM track: `src/seon/web/reactive/transform.clj`,
  `.../reactive/actions.clj`, `src/seon/ns/routes.clj`
  (`function-call-handler`, `resolve-and-call`), `src/seon/web/routes.clj`,
  `src/seon/web/sse.clj`, `src/seon/web/server.clj` (`wrap-json-body`),
  `src/seon/web/components.clj` (`action-button`).
- Seon ACTIVE pod: `src/seon/web/serve.cljs` (route dispatch,
  `read-body`, `parse-urlencoded`), `src/seon/web/inspector.cljs`
  (per-feed SSE, inline patch-elements), `src/seon/eval.cljs` (sandbox
  `eval`).
</content>
