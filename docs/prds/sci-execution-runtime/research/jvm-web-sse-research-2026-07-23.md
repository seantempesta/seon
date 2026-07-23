---
type: research
status: active
tags: [research, ui, architecture]
---

# JVM web/SSE tier research (2026-07-23)

Grounding for the all-JVM design pass (ruling 24 confirmed + ruling 25
commissioned, `program-synthesis-2026-07-21.md:1206-1222`): can the Seon web
UI (Datastar SSE morphs, hiccup rendering, database-derived routes,
`/agent/{id}`, `/data`, debug feeds) be served from the JVM cleanly; what the
old JVM server did; exactly why it was removed; and the maintained idiom the
JVM tier should follow. Serving side only — where sci-contained authored
renders slot in belongs to the guarded-eval-door research
(`program-synthesis-2026-07-21.md:1197-1204`) and the render-port unit
(ruling 20(d), `:1290-1293`); this report cross-references, it does not
duplicate.

## TL;DR

- **Yes, cleanly.** The current tier is ~10,300 LOC, of which only
  ~1,200–1,500 LOC is genuinely Bun-bound plumbing (Bun.serve/WHATWG
  Request/Response, `ReadableStream` direct SSE, `node:zlib` gzip,
  `js/setTimeout` timers, `^:async`/Promise shaping). The view layer
  (`seon.ui.*`, `seon.render.*`) is already almost pure data →
  hiccup — `render/value.cljc` (1,883 LOC) is portable today, and the rest
  has single-digit platform touches.
- **The old JVM server was NOT removed because JVM serving failed.** It was
  removed (commit `6c1079c8d`, 2026-07-13, "archive paused JVM application")
  because it implemented the *wrong, superseded contracts* — Integrant
  lifecycle, core.async flow topology, an obsolete route/action contract, a
  duplicate renderer — while the canonical implementation had moved to CLJS.
  The archive-boundary audit explicitly anticipated this moment: "Any future
  JVM rendering must consume the same pure surface data and Datastar
  contract as CLJS; it should not revive the old Chassis/http-kit stack"
  (`docs/prds/archive/runtime-reliability/research/jvm-archive-boundary-2026-07-13.md:41-44`).
- **Recommended idiom:** http-kit `as-channel` through the vendored
  `datastar-clojure` SDK adapter, one virtual thread per SSE connection
  (hyperlith's shape), Seon's own `seon.reactive` shared-subscription
  registry ported to `.cljc` as the invalidation authority (it is more
  precise than hyperlith's broadcast-everything refresh mult), latest-wins
  backpressure preserved, gzip via the SDK's `GZIPOutputStream`-per-event
  write profile (a direct semantic replacement for Bun's `Z_SYNC_FLUSH`
  stream).
- **Browser story is unchanged:** no ClojureScript ships to the browser
  today and none needs to at the end state. The browser is pure
  Datastar-morphed HTML plus the vendored static `datastar.js`.
- **First slice:** serve `/data` + `/data/feed` from the JVM writer process
  against its own authoritative Datahike connection; prove one transact →
  one morph with a server-side SSE client.

## 1. The current tier — honest inventory

### 1.1 LOC census

| Area | Files | LOC |
|---|---|---|
| `src/seon/web/` | serve.cljs 2124 · datastar.cljs 1170 · router.cljs 486 · reactive/call.cljs 308 · reactive/transform.cljs 267 · debug.cljs 279 · brand.cljs 238 · value.cljs 49 | 4,921 |
| `src/seon/render/` | value.cljc 1883 · canvas.cljs 615 · handlers/* 850 · chat.cljs 136 · system.cljs 130 · surface.cljs 87 · view_unit.cljs 42 · schema.cljs 30 | 3,773 |
| `src/seon/ui/` | html.cljc 353 · markdown.cljs 226 · clojure.cljs 192 · agent_view.cljs 93 · header.cljs 47 | 911 |
| `src/seon/route.cljs` | seeded route datoms + schema | 115 |
| `src/seon/reactive.cljs` | shared render invalidation engine | 580 |
| **Total** | | **~10,300** |

### 1.2 What is pure hiccup / data transforms (portable as-is)

- `src/seon/route.cljs:1-115` — route schema + the seeded core route set
  (`/` → `serve-root!`, `/agent/{id}` + `/agent/{id}/feed`,
  `/agent/{id}/debug(+/feed)`, `/agent/{id}/call`, `POST /agents`;
  `route.cljs:98-115`). Zero platform interop; renames to `.cljc` verbatim.
- `src/seon/render/value.cljc` — already `.cljc` with reader conditionals at
  its numeric edges (`value.cljc:220-247`). The single largest render
  namespace is portable **today**.
- `src/seon/ui/agent_view.cljs:1-30` — "This pure UI leaf composes eager
  header and surface data into stable hiccup." No js interop at all.
- `src/seon/ui/html.cljc` — the canonical hiccup serializer, already
  portable; the 07-13 audit named it exactly for this reuse: "a future JVM
  adapter may consume the same input"
  (`jvm-archive-boundary-2026-07-13.md`, Keep-shared table).
- `src/seon/ui/clojure.cljs` — deliberately a *pure server tokenizer* so the
  agent view ships only datastar.js (`clojure.cljs:2-9`). Portable verbatim.
- `src/seon/ui/markdown.cljs:1-25` — hand-rolled markdown→hiccup written
  *because* markdown-clj was JVM-only. Portable verbatim; do NOT swap it for
  markdown-clj at port time (one mechanism; both runtimes must render
  identically while any CLJS consumer remains).
- `src/seon/render/{canvas,chat,system,surface,view_unit,schema}.cljs` —
  pure hiccup/projection code; total platform residue is two `js/Date`
  instance checks (`canvas.cljs:451`, `chat.cljs:64`) → `inst?`/reader
  conditional.
- `src/seon/render/handlers/*.cljs` — pure except URL/query helpers
  (`handlers/eval.cljs:140-158` `js/URLSearchParams`,
  `js/encodeURIComponent`, `js/JSON.stringify`;
  `handlers/message.cljs:34-36` `js/Date`) → one portable URL-encoding
  helper.

### 1.3 What is Bun-bound (rewrite at the platform seam)

- **`seon.web.serve` (2,124 LOC)** — `Bun.serve` dispatch + lifecycle
  (`serve.cljs:1940-2010`), static files via `(.file js/Bun full)` +
  `node:path` normalize traversal guard (`serve.cljs:24-25,166-176`), WHATWG
  `js/Response` everywhere (`serve.cljs:97,367`), port/bind env + port-file
  (`serve.cljs:1975-2005`), and `js/Promise`-shaped handlers throughout
  (`handle-chat!`, `handle-agent-run!` etc.). The *domain logic* inside the
  handlers (wake path, lifecycle calls, JSON envelopes) is portable; the
  async shaping is not — and on JVM virtual threads it collapses to plain
  synchronous code, which is a simplification, not a port cost.
- **`seon.web.router` (486 LOC)** — reitit itself is `.cljc` (vendored,
  `reference-code/reitit/`); the `db->routes` projection and the static
  supplement table (`router.cljs:255-305`) are portable. The Bun
  Request→Ring translation (`router.cljs:30-32,103`) and WHATWG `Response`
  constructors (`router.cljs:87-92,315`) mostly *delete* on JVM — a Ring
  server is already Ring-shaped.
- **`seon.web.datastar` (1,170 LOC)** — the SSE plumbing core:
  - encoding negotiation, identity default on loopback,
    `SEON_FEED_COMPRESSION=gzip` opt-in (`datastar.cljs:212-241`);
  - gzip writer over `node:zlib` `createGzip` + `Z_SYNC_FLUSH` per event
    (`datastar.cljs:247-277`);
  - latest-wins backpressure — negative write / async flush → retain only
    newest pending event (`datastar.cljs:279-336`);
  - 15 s heartbeat via `js/setInterval` (`datastar.cljs:338-391`);
  - per-connection `js/ReadableStream` direct response
    (`datastar.cljs:792-859`).
  The feed registry, subscription normalization, render-read with
  `db/with-read-evidence` (`datastar.cljs:399-416,430-444`), and the shim
  HTML are portable logic; roughly the lower ~400 LOC of socket plumbing is
  the rewrite.
- **`seon.reactive` (580 LOC)** — the engine is portable data-flow; residue
  is `performance.now`/`setTimeout` (`reactive.cljs:80-82`) and Promise
  composition (`reactive.cljs:461-561`). Porting needs a real concurrency
  review: its one-active-computation / newest-pending invariants are free
  under single-threaded JS and must become explicit (CAS on the registry
  atom is fine; the "only one compute per key at a time" guarantee must be
  enforced, not assumed) under virtual threads.
- **`seon.web.reactive.transform` (267 LOC)** — portable postwalk rewriting
  fn-calls into `@post` actions; `node:crypto` hash (`transform.cljs:48`) →
  `MessageDigest` seam.
- **`seon.web.reactive.call` (308 LOC)** — the `/agent/{id}/call` door
  invokes a granted fn *in the supervised Bun child*
  (`call.cljs:42,117`). Under the all-JVM end state this becomes an
  in-process sci invocation through the one guarded-eval door (cross-ref:
  the guarded-eval-door research owns that contract), or a JS-leaf wire call
  for package fns. This namespace is a **contract rewrite**, not a port.
- **`seon.web.brand` (238)** — CSS read via `fs.readFileSync`
  (`brand.cljs:137`) → `slurp`/`io/resource`. Trivial.
- **`seon.web.debug` (279), `seon.web.value` (49)** — hiccup + acquisition;
  Promise.all shaping only.

### 1.4 Browser-asset story (verified)

`shadow-cljs.edn` has **no `:target :browser` build** — every build is
`:node-script`, `:node-test`, or `:bootstrap`
(`shadow-cljs.edn:58,95,126,143,299,325`). The agent-view shim ships *only*
`<script type="module" src="/js/datastar.js">` (`datastar.cljs:598`;
affirmed by design in `ui/clojure.cljs:4-9`). `resources/public/js/`
contains vendored static assets only (`datastar.js`, highlight bundles,
`seon-debug.js`, `scittle.js`, `reactive-demo.js` — the latter three are
legacy debug-shell extras, not shadow outputs). **No ClojureScript is
compiled for or shipped to the browser today.** At the all-JVM end state,
shadow-cljs builds only the disposable Bun JS-leaf/diffusion-worker
artifacts; the browser story does not change at all.

## 2. The old JVM server — autopsy

### 2.1 What it was (at `runtime-reliability-pre-refactor-2026-07-13`)

| Namespace (at tag) | LOC | Role |
|---|---|---|
| `src/seon/web/server.clj` | 123 | http-kit `run-server` as an **Integrant** component; `requiring-resolve` late-bound handler; wrap-json-body/no-cache middleware; suspend/resume keys (`server.clj:1-123`) |
| `src/seon/web/sse.clj` | 414 | Datastar SDK-pattern SSE over `hk/as-channel`: `patch-elements`/`execute-script` builders, headers-once, content negotiation, **Brotli write profiles**, hash-based change detection, core.async refresh mult (`sse.clj:1-140`) |
| `src/seon/web/sse/flow.clj` | 408 | **core.async.flow** topology: change aggregation/debounce → client registry → broadcast (`sse/flow.clj:1-27`) |
| `src/seon/web/brotli.clj` | 188 | Brotli compression profile |
| `src/seon/web/routes.clj` + `handlers.clj` | 227 | hand-wired route table + handlers (old contract) |
| `src/seon/web/html.clj`, `components.clj`, `tailwind.clj` | 748 | JVM Chassis-side HTML/components/tailwind pipeline |
| `src/seon/web/browser.clj`, `caddy.clj`, `logs.clj`, `namespace.clj`, `flows.clj` | 1,189 | browser automation, proxy, logs, ns pages, flow UI |
| `src/seon/web/reactive/{actions,demo,transform}.clj` | 413 | the OLD action/route contract emitter |
| `src/seon/render.clj`, `render/code.clj`, `render/default_page.clj`, `ui/viewer.clj`, `ui/components.cljc` | 1,933 | the duplicate JVM renderer |
| **Total JVM web/render/ui tier** | | **~5,600** |

### 2.2 Why it was removed — the recorded reasons

Deletion commit: `6c1079c8d` "refactor(runtime): archive paused JVM
application" (2026-07-13). The message is thin; the reasons live in the
runtime-reliability PRD and its archive-boundary audit:

1. **Duplicate implementation, not the foundation.** "The paused JVM
   application is not the foundation of the future server… The old JVM
   agent loop, Integrant application, core.async flow topology,
   session/context system, HTML renderer, HTTP/SSE server, nREPL MCP
   server, and test harness do not implement that target"
   (`jvm-archive-boundary-2026-07-13.md:11-41`).
2. **Wrong contracts, specifically in the web tier.** "The old JVM renderer
   is specifically not a portable renderer to retain.
   `src/seon/web/reactive/transform.clj` emits the old route and action
   contract, while its `.cljs` sibling emits current `/agent/{id}/call`
   actions and Datastar attributes. The two files are not equivalent
   implementations. The reusable boundary is hiccup/surface data plus the
   Datastar protocol, not the old JVM handlers"
   (`jvm-archive-boundary-2026-07-13.md`, archive table tail).
3. **Delete-overlap program goal.** The refactor's outcome was "one
   canonical CLJS agent and web UI implementation… no paused application,
   compatibility path, duplicate reactive channel… The refactor succeeds by
   deleting overlap"
   (`docs/prds/archive/runtime-reliability/roadmap.md:12-29`).
4. **Keeping it would invite reuse of the wrong thing.** "Moving it under
   an `archive/` source tree would keep it in searches, invite accidental
   reuse, and leave two apparent implementations. Git is the archive"
   (`jvm-archive-boundary-2026-07-13.md:19-23`).

**Nothing in the record says JVM HTTP/SSE serving itself was a problem.**
http-kit SSE with Brotli and hash-based change suppression demonstrably
worked (`sse.clj:1-11` reads as a competent Datastar SDK port). What was
intrinsically bad: Integrant lifecycle choreography for hot reload
(`server.clj` needed `requiring-resolve`-per-request plus
suspend/resume keys to survive clj-reload), the core.async.flow
debounce/broadcast topology (a second reactive system — the modern
`seon.reactive` settle-ms + read-evidence invalidation is strictly better),
and a whole second renderer.

### 2.3 Resurrect vs redesign vs never

| From the old tier | Verdict |
|---|---|
| http-kit as the server | **Resurrect the choice** — now reinforced by both maintained idioms (hyperlith pins `http-kit 2.9.0-beta1`, `reference-code/hyperlith/deps.edn:8`; datastar-clojure ships a first-class http-kit adapter) |
| `sse.clj` patch-elements/headers/negotiation mechanics | **Redesign via the vendored `datastar-clojure` SDK** — the SDK now owns event framing and write profiles; do not hand-roll again |
| Brotli write profile (`brotli.clj`) | **Optional later** — the SDK has an `sdk-brotli` library; current Seon policy is identity-on-loopback / gzip-opt-in (`datastar.cljs:212-241`), keep that policy |
| Hash-based change suppression | **Already superseded** — `seon.reactive` equality suppression owns this (`src/seon/CLAUDE.md` one-mechanism table, Reactive reads row) |
| Integrant component lifecycle | **Never** — the pod's serialized-phase `start-runtime!`/`stop-runtime!` pattern is the lifecycle authority |
| core.async.flow SSE topology (`sse/flow.clj`) | **Never** — a second reactive channel by construction |
| The old route/action contract (`routes.clj`, `reactive/*.clj`) | **Never** — routes are `:seon.route/*` datoms now (`route.cljs:98-115`) |
| The JVM renderer (`render.clj`, `html.clj`, `components.clj`, `viewer.clj`) | **Never** — `seon.render`/`seon.ui` is the one renderer; it ports, it is not replaced |

## 3. The maintained idioms (vendored source, read 2026-07-23)

### 3.1 datastar-clojure (`reference-code/datastar-clojure/`)

- **Adapters:** http-kit (`libraries/sdk-http-kit/...adapter/http_kit.clj`),
  generic Ring streamable-body (`libraries/sdk-ring/...adapter/ring.clj`),
  plus rj9a and ring-jetty adapter tests. The http-kit adapter is the
  smallest fit for Seon.
- **SSE generator contract** (`adapter/http_kit.clj:23-77`):
  `->sse-response ring-request {on-open on-close on-exception
  write-profile}` over `hk-server/as-channel`; status + SSE headers sent
  automatically before `on-open` receives the live `sse-gen`; `on-close`
  receives the http-kit close status; `d*/patch-elements!` writes one morph
  event. Close semantics are owned by the adapter (`ac/close-sse!` flushes
  then runs the callback, `http_kit.clj:66-77`).
- **Write profiles** (`adapter/common.clj:18-53`): composable
  `{wrap-output-stream, write!, content-encoding}`; `gzip-profile` wraps a
  `GZIPOutputStream` + temp-buffer writer and flushes per event — the exact
  JVM replacement for the Bun `createGzip`/`Z_SYNC_FLUSH` writer
  (`datastar.cljs:247-277`), including the compress-tiny-events behavior
  the `tiny_gzip.clj` example exists to prove ("compressing tiny events
  seems to work fine", `src/dev/examples/tiny_gzip.clj:15-17`).
- **The separate-GET-stream idiom** Seon already follows
  (`route.cljs:74-79` cites `tiny_gzip.clj` directly): page shim GET +
  sibling long-lived feed GET; broadcast = iterate the open `sse-gen` set
  (`tiny_gzip.clj:20-58`).

### 3.2 hyperlith (`reference-code/hyperlith/`)

- **Server:** http-kit (`impl/datastar.clj:15`, `deps.edn:8`); virtual
  threads as the default execution substrate — agents/futures on
  `newVirtualThreadPerTaskExecutor` (`core.clj:24-30`), `util/thread` =
  `Thread/startVirtualThread` (`impl/util.clj:28-30`).
- **Per-connection model** (`impl/datastar.clj:122-181`): each SSE
  connection taps a global refresh **mult with a dropping-buffer 1** —
  hyperlith's latest-wins — and runs its own thread looping
  take → render → compress → `hk/send!`. Render work is pushed to a fixed
  CPU pool sized to cores (`impl/cpu_pool.clj:5`) so render cost never
  blocks the connection threads.
- **Per-connection compression context:** a streaming Brotli
  `OutputStream` + `SSENewlineFilterWriter` lives as long as the
  connection (`impl/datastar.clj:148-176`), giving cross-event compression
  windows — stronger than per-event gzip; noted as a later option, not the
  first cut.
- **Reconnect semantics:** the shim opens the feed with
  `retryMaxCount: Infinity` and reopens on `online` events
  (`impl/datastar.clj:53-59,84-88`); reconnect = full repaint, exactly
  Seon's "Reconnect = repaint view = f(db); no since-t replay"
  (`src/seon/web/CLAUDE.md`, Rules that bite).
- **What NOT to copy:** the global broadcast-everything refresh mult and
  render-per-connection-per-batch. Seon's `seon.reactive` normalized
  subscriptions share one render + serialized bytes across equivalent
  sockets and invalidate by observed reads
  (`datastar.cljs:70-74,430-444`) — strictly more precise at
  hundreds-of-agents scale. Keep Seon's registry; adopt hyperlith's
  thread/connection shape around it.

### 3.3 The concrete Seon-on-JVM idiom

- **Server:** http-kit (`run-server`, `as-channel`) — no Jetty, no second
  server; matches both vendored idioms and the old server's own proven
  choice.
- **SSE:** `datastar-clojure` http-kit adapter; one `sse-gen` per
  connection held in the ported `seon.web.datastar` feed registry keyed
  exactly as today (`::feed-key`, `datastar.cljs:50-56`).
- **Threading:** one virtual thread per SSE connection for writes
  (hyperlith shape; composes with the JVM-concurrency research lane's
  vthread direction, `program-synthesis-2026-07-21.md:1191-1196`); renders
  run wherever `seon.reactive`'s ported compute path puts them — bounded
  CPU pool if the concurrency research confirms the hybrid-pool answer.
- **Backpressure:** keep latest-wins semantics (newest pending event
  replaces older, `datastar.cljs:294-336`) implemented as a per-connection
  1-slot mailbox drained by the connection's thread — the vthread analog of
  the current Bun drain loop; hyperlith's dropping-buffer-1 proves the
  shape.
- **Compression:** identity on loopback, gzip via SDK write profile when
  `SEON_FEED_COMPRESSION=gzip` — policy unchanged, mechanism swapped.
- **Heartbeat:** one `ScheduledExecutorService` writing `: keep-alive`
  comments, porting `datastar.cljs:338-391` semantics (skip when draining,
  never displace pending state).

## 4. Migration cost table

Legend: **move** = rename/port with trivial reader-conditional or helper
seams; **rewrite** = re-implement against JVM mechanisms; **delete** =
platform shim that has no JVM counterpart; **contract** = depends on an
owner decision outside this report.

| Current namespace | LOC | Verdict | Notes |
|---|---|---|---|
| `route.cljs` | 115 | move (100%) | zero interop; `.cljc` rename |
| `ui/html.cljc`, `render/value.cljc` | 2,236 | move (already portable) | none |
| `ui/{agent_view,header,clojure,markdown}.cljs` | 558 | move (~100%) | no js interop found |
| `render/{canvas,chat,system,surface,view_unit,schema}.cljs` | 1,040 | move (~99%) | 2 `js/Date` sites (`canvas.cljs:451`, `chat.cljs:64`) |
| `render/handlers/*` | 850 | move (~97%) | URL/JSON helpers (`handlers/eval.cljs:140-158`) → portable helper |
| `reactive.cljs` | 580 | move (~85%) + concurrency review | timer/promise seams (`:80-82,461-561`); single-thread invariants must become explicit |
| `web/datastar.cljs` | 1,170 | move (~65%) / rewrite (~35%) | registry + negotiation + shim portable; gzip writer, ReadableStream, heartbeat, drain loop → SDK + vthread mailbox (`:247-391,792-859`) |
| `web/router.cljs` | 486 | move (~60%) / delete (~30%) | `db->routes` + supplement portable; Bun↔Ring translation deletes on a Ring server (`:30-32,83-103,471-481`) |
| `web/serve.cljs` | 2,124 | move (~55%) / rewrite (~40%) | handler domain logic ports and *simplifies* (await → sync on vthreads); Bun.serve/static/lifecycle plumbing rewritten (`:24-25,166-176,1940-2010`) |
| `web/debug.cljs` | 279 | move (~90%) | Promise.all shaping only |
| `web/brand.cljs` | 238 | move (~95%) | `fs.readFileSync` → `slurp` (`:137`) |
| `web/value.cljs` | 49 | move | trivial |
| `web/reactive/transform.cljs` | 267 | move (~90%) | `node:crypto` → `MessageDigest` (`:48`) |
| `web/reactive/call.cljs` | 308 | **contract** | today invokes the supervised Bun child (`:42,117`); end state = sci in-process via the guarded-eval door, or JS-leaf wire for package fns — owned by ruling 25's leaf-host contract |

Net: **~7,800 LOC moves near-verbatim, ~1,500 LOC rewrites at the platform
seam, ~300 LOC deletes, ~300 LOC awaits the leaf-host contract.** Nothing in
the view/render family needs redesign.

## 5. Live-update parity risks

| Today on Bun | JVM idiom | Risk |
|---|---|---|
| Direct-stream negative-write signal + `flush(true)` drain (`datastar.cljs:294-336`) | vthread blocks on `hk/send!`; latest-wins via 1-slot mailbox | LOW — semantics preserved; hyperlith proves the shape |
| `node:zlib` `Z_SYNC_FLUSH` per SSE event (`datastar.cljs:247-277`) | SDK `gzip-profile` flushes `GZIPOutputStream` per event (`adapter/common.clj:18-53`) | LOW — `tiny_gzip.clj` exists precisely to prove tiny-event gzip flushing works on JVM |
| **Browser-bridge 503 on long gzip SSE** | unchanged | **Not Bun-specific and not protocol-inherent** — it is the in-tool Chrome agent's network layer ("the in-tool Chrome agent's network layer 503s long-lived event-streams", `.agents/skills/datastar-web-ui/SKILL.md:66`; `.agents/skills/browser-automation/SKILL.md:3,22,77`). Loopback feeds are identity-encoded anyway (`datastar.cljs:216`, `web/CLAUDE.md`). The scar follows the verification *tool*, not the server; server-side SSE clients remain the verification path on JVM too |
| Single-threaded registry atoms (`!feeds`, `datastar.cljs:76-85`) | shared mutable registry under real concurrency | MEDIUM — `swap!` stays correct, but "one active computation per subscription" and socket-identity checks (`current-socket?`, `:425-428`) rely on JS's serial event loop; the port must make those invariants explicit (CAS/loop or per-subscription lock). This is the same class of work the JVM-concurrency research already owns |
| `^:async`/`await` handler shaping | plain sync on vthreads | NONE — a simplification |
| Hot reload of handlers | late-bound `:seon.route/handler` symbols via `lookup-value` (`route.cljs:16-25`) | LOW — the late-binding design is runtime-agnostic; do NOT reintroduce the old `requiring-resolve`-per-request + Integrant suspend/resume machinery (`old server.clj:75-99`) |

## 6. First-slice proposal

**Serve `/data` + `/data/feed` from the JVM writer process.**

Why `/data`: it is the smallest complete page that exercises the whole
serving contract — shim GET, long-lived SSE feed, whole-view morph, the
canonical feed registry, and live invalidation
(`router.cljs:265-268`, `debug.cljs:269`) — and the JVM writer already
holds the authoritative Datahike connection, so the slice needs **no
replica, no wire protocol, no agent runtime**. The archived roadmap's own
live proof for `/data` ("a database transaction produce a second
data-browser morph", `runtime-reliability/roadmap.md`, canonical live-feed
cut section) is the reusable acceptance shape.

Slice contents:

1. Port the pure prerequisites: `ui/html.cljc` (done), `render/value.cljc`
   (done), the `/data` render path in `web/debug.cljs` + `web/value.cljs`,
   and a minimal `seon.reactive` compute path (or, for the very first cut,
   re-render on every committed transaction — the writer sees every
   `tx-report` natively; equality suppression can land with the full
   reactive port).
2. New JVM-side serving leaf: http-kit `run-server` on a side port +
   `datastar-clojure` http-kit adapter; one vthread per feed connection;
   identity encoding.
3. Proof: `curl` the shim; open the feed with a server-side SSE client;
   `transact!` one fact through the writer; observe the second morph;
   close; observe `on-close` cleanup. Then the same against
   `SEON_FEED_COMPRESSION=gzip` to retire the gzip-parity question early.

This slice deliberately does not touch `/agent/{id}` (needs the agent
runtime's projections), `/call` (leaf-host contract), or route datom
projection (`db->routes` can arrive with the second slice; the first slice
may hand-wire its two routes exactly as `router.cljs:265-268` does today).

## 7. Cross-references

- All-JVM design pass + owner scope: `program-synthesis-2026-07-21.md:1186-1222`.
- Guarded-eval door (where authored renders execute): `:1197-1204`; render
  port unit ruling 20(d): `:1290-1293`.
- Removal record: commit `6c1079c8d`;
  `docs/prds/archive/runtime-reliability/research/jvm-archive-boundary-2026-07-13.md`;
  `docs/prds/archive/runtime-reliability/roadmap.md:12-29,2163`.
- Old source: git tag `runtime-reliability-pre-refactor-2026-07-13`,
  `src/seon/web/*.clj`, `src/seon/render*.clj`.
- Vendored idioms: `reference-code/datastar-clojure/` (SDK + http-kit
  adapter + `src/dev/examples/tiny_gzip.clj`),
  `reference-code/hyperlith/src/hyperlith/impl/datastar.clj`,
  `impl/util.clj`, `impl/cpu_pool.clj`, `core.clj`.
