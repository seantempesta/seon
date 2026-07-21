---
type: research
status: active
tags: [research, agent, database, flow]
---

# MVP server data-path — wired vs stubbed, end-to-end (2026-06-08)

> Recon of the PLATFORM SERVER data path for the single-cluster live MVP: how a
> transaction by the agent flows through the host (datahike conn) → the reactive
> engine → the broadcast → out to consumers (the webview and other agents), and
> what the `:seon.db/subscriptions`-on-the-agent-entity idea maps to. Grounded
> against the live code at HEAD (`feature/agent-runtime`, commits through
> `45b0939`). No code edited.

## TL;DR

- **The raw write→broadcast path is fully wired and runnable today.** A guest
  transact crosses WIT → Rust host → UDS req socket → `wire.clj handle-op
  "transact"` → datahike commit → the per-conn `::raw-broadcast` `d/listen`
  callback → `bcast/broadcast!` → (a) socket subscribers (Rust host) and (b)
  in-process per-DB subscribers. `request-id` is stamped into tx-meta and rides
  the event. db-name is real (no hardcoded `"default"`). All of this is live in
  `wire.clj` + `broadcast.clj` + `registry.clj`.
- **The REACTIVE path is NOT live yet.** `reactive.clj`'s engine
  (`on-tx!`, `register-sub!`, `rebuild!`, the two wire handlers) is done and
  green (12 deftests in `reactive_test.clj`), but **nothing installs a
  `::reactive` `d/listen` on any conn at server start**, and **no `handle-op`
  exists for `register-subscription`/`unregister-subscription`**. The engine
  exists; it is unplugged.
- **The transport for the MVP agent is the WIT bridge, not nREPL/MCP.** A
  node/CLJS guest talks to the JVM host through `seon.client-runtime.wit` →
  wasm-rquickjs JS imports → Rust host `db_iface::Host` → UDS sockets. The guest
  never sees a socket. There IS a working guest-side wire client
  (`seon.client-runtime.db` + `.wit`), exercising q/pull/transact/listen.
- **`:seon.db/subscriptions`-on-the-agent-entity does NOT exist** — no schema,
  no guest API, no WIT op. The current model is op-based + a standalone
  `:seon.subscription/id` entity carrying a `:seon.subscription/query` source
  string. The user's desired "transact a subscriptions vector onto my eid" shape
  is a NEW design layered on top (see §3). It is compatible but unbuilt.
- **The cleanest MVP webview bridge is a JVM-side SSE handler calling
  `bcast/subscribe!`** for one db-name — no Rust host needed. The existing
  `src/seon/web/*` Datastar stack is V1/JVM-lane (uses `seon.db`/Integrant) and
  is NOT wired to the server-lane datahike registry; a thin new handler is
  required.

## End-to-end data path (text diagram)

```text
                          ┌─────────────────────────── GUEST (node/CLJS agent) ───────────────────────────┐
   agent LLM loop  ──────▶│ seon.client-runtime.db/transact!                                               │
                          │   → wit/transact-call (Transit-JSON encode tx-data/tx-meta, gen request-id)    │
                          │   → seon.client-runtime.wit/invoke "transact"  (JS WIT import)                 │
                          └──────────────────────────────────────┬─────────────────────────────────────────┘
                                                                 │ WIT seon:client-runtime/db@0.1.0
                          ┌──────────────────────────────────────▼─────────────────────────────────────────┐
                          │ RUST HOST (client-runtime/host): db_iface::Host (guest.rs)                     │
                          │   → CBOR-framed {"op":"transact", "tx-data":<transit>, ... } over UDS req sock  │
                          └──────────────────────────────────────┬─────────────────────────────────────────┘
                                                                 │ tmp/...-<session>-req.sock  (CBOR + len prefix)
  ┌──────────────────────────────────────────────────────────────▼──────────────────────────────────────────────────────┐
  │ JVM HOST (src/seon/server)                                                                                            │
  │                                                                                                                       │
  │  start-req-server! accept loop ─▶ handle-req ─▶ resolve-conn-for-req (registry, by agent-id/db-name; ambient fallback)│
  │       └▶ handle-op "transact" (wire.clj:353)                                                                          │
  │            ├ read-T tx-data / tx-meta ; coerce float/double per schema                                                │
  │            ├ stamp :seon.db/request-id into tx-meta  (wire.clj:363-367)   ◀── R1 DONE                                 │
  │            └ d/transact conn {:tx-data tx* :tx-meta tx-meta*}   ── COMMIT ──┐                                          │
  │                                                                            │ datahike fires ALL distinct-keyed         │
  │                                                                            │ d/listen callbacks SYNCHRONOUSLY          │
  │            ┌───────────────────────────────────────────────────────────────┤ on the writer thread                    │
  │            │                                                                │                                          │
  │   ::raw-broadcast listener (wire.clj:299) ── WIRED ──┐          ::reactive listener  ── NOT INSTALLED ──┐             │
  │     reads request-id off tx-meta                     │            reactive/on-tx! (reactive.clj:221)     │             │
  │     builds ok-event-from-report (db-name-tagged)     │              index → cheap gate → re-run →        │             │
  │     → bcast/broadcast! event{"event":"tx",...}       │              not= change gate →                   │             │
  │                                                      │              emit! changed-summaries-event         │             │
  │            ┌─────────────────────────────────────────▼────────────────────────────────────────────────▼─┐           │
  │            │ broadcast.clj/broadcast! (per-event "db-name" routing)                                       │           │
  │            │   (a) socket-subscribers: EVERY OutputStream gets every event  ─▶ UDS pub sock ─▶ Rust host  │           │
  │            │   (b) db-subscribers[db-name]: in-process fns, routed by "db-name"  ─▶ JVM consumers          │           │
  │            └──────────────────────────────────┬──────────────────────────────────┬──────────────────────┘           │
  └───────────────────────────────────────────────┼──────────────────────────────────┼───────────────────────────────────┘
                                                  │ UDS pub sock                     │ in-process (same JVM)
                          ┌────────────────────────▼──────┐          ┌───────────────▼───────────────────────────────────┐
                          │ Rust host demuxes by db-name   │          │ MVP WEBVIEW SSE handler  (NOT BUILT)               │
                          │ → guest next-tx-event (raw tx) │          │   bcast/subscribe! db-name → forward to browser    │
                          │ → another agent's listen! loop │          │   (Datastar merge-fragment / SSE)                  │
                          └────────────────────────────────┘          └────────────────────────────────────────────────────┘
```

Two consumer legs exist off one `broadcast!`: the **socket leg** (Rust host →
guests, the raw `tx` event used by other agents' `listen!` loops and by
`next-tx-event`), and the **in-process leg** (`subscribe!`, the natural webview
bridge). The reactive `changed-summaries` event would ride the SAME `broadcast!`
fanout once the `::reactive` listener is installed — its `emit!` is just
`bcast/broadcast!`.

## Wired vs stubbed

| Stage | Status | Where |
| --- | --- | --- |
| Guest CLJS wire client (q/pull/transact/listen) | **WIRED** | `guest-cljs/src/seon/client_runtime/db.cljs` + `.../wit.cljs` |
| WIT contract (db ops, tx-event, subscribe-tx/next-tx-event) | **WIRED** | `client-runtime/host/wit/db.wit:21-120` |
| WIT `register-subscription` / `changed-summaries` event | **MISSING** | `db.wit` has NO such func/record — reactive ops are JVM-internal only |
| Rust host: spawn JVM writer, UDS req/pub, CBOR framing, opportunistic batcher | **WIRED** | `client-runtime/host/src/main.rs`, `guest.rs` |
| Wire server runnable (`:writer` → `-m seon.server.boot`) | **WIRED** | `deps.edn:140-144`; boot loads BOTH wire + reactive |
| `handle-op "transact"` commit + request-id stamp | **WIRED** | `wire.clj:353-373` (request-id `:363-367`) |
| Per-conn `::raw-broadcast` `d/listen` (on-ensure-db hook) | **WIRED** | `wire.clj:299-325`; fires via `registry/run-on-ensure-db-hooks!` (`registry.clj:226,263`) |
| Real db-name on events (no `"default"`) | **WIRED** | `wire.clj:279-288`, `raw-broadcast-listener-fn:305` |
| Per-DB broadcast fanout (socket + in-process subscribe!) | **WIRED** | `broadcast.clj:38-87` |
| Conn-resolution by agent-id/db-name into `handle-op` | **WIRED** | `wire.clj:541-573`, `registry/resolve-conn` (`registry.clj:354`) |
| `{db-name→conn}` + `{agent-id→db-name}` registry, register-agent! | **WIRED** | `registry.clj:157-352` |
| on-ensure-db extension point | **WIRED** | `registry.clj:209-228` |
| `:seon.agent/id` registered server-side | **WIRED** | `registry.clj:103` |
| `seed-base-schema!` installs `:seon.db/request-id` for `:write` conns | **WIRED** | `wire.clj:70-79` (called in `ensure-db!` AND the raw-broadcast hook) |
| Reactive engine: `on-tx!`, two-gate dispatch, inverted index | **WIRED (built, green) but UNPLUGGED** | `reactive.clj:221-259`; 12 deftests `reactive_test.clj` |
| Reactive: `register-sub!`/`unregister-sub!`/`register-subscription!`/`rebuild!` | **WIRED (built, green)** | `reactive.clj:163-212` |
| Reactive wire handlers `register-subscription`/`unregister-subscription` (pure fns) | **WIRED (built, green)** | `reactive.clj:286-314`, registered request/response schemas `:276-302` |
| `changed-summaries-event` + `changed-entry` + rows schemas | **WIRED (registered)** | `reactive.clj:32-50` |
| Reactive `::reactive` `d/listen` installed on conns at boot | **MISSING** | reactive.clj registers NO `register-on-ensure-db-hook!`; grep: no `d/listen`/`::reactive` install in reactive.clj |
| `(reactive/engine-state db-name)` per-db state registry | **MISSING** | only `new-engine-state` exists (`reactive.clj:146`); no `{db-name→state}` atom + lookup |
| `install-reactive-schema! conn` (the `:write` `:db/ident` install for sub/fn/render attrs) | **MISSING** | grep: no malli→datahike install in `src/seon/server`; only `:seon.db/request-id` is seeded |
| `handle-op "register-subscription"` / `"unregister-subscription"` wrappers | **MISSING (Phase B, staged in boot.clj)** | `boot.clj:24` comment only; no defmethod |
| `:seon.fn/*` + `:seon.render/ai` + `:seon.agent/entity` + `:seon.subscription/agent`/`render-fn` schemas | **MISSING** | reactive.clj registers `:seon.subscription/id|query|active?`, `:seon.server.reactive/*` only; no `:seon.fn`, no render attrs, no agent-entity, no sub→agent ref |
| `:seon.db/subscriptions`-on-agent-entity (the user's shape) | **MISSING (no design landed)** | grep: nowhere in src/guest/test; only the op-based model exists |
| MVP webview SSE consumer (JVM-side) | **MISSING** | `src/seon/web/*` is V1/JVM-lane (`seon.db`/Integrant), not wired to server registry |
| register-agent! bind caller (guest→cluster bind op) | **MISSING (P2)** | `register-agent!` exists; nothing calls it from a wire op |

## §1 — Live data path: what's wired end-to-end today

A guest single `transact` is **fully live** from WIT down to the raw `tx`
broadcast: `handle-op "transact"` (`wire.clj:353`) reads/coerces tx-data, stamps
`:seon.db/request-id` into tx-meta (`:363-367`), commits, and datahike fires the
per-conn `::raw-broadcast` listener synchronously on the writer thread
(`wire.clj:299-311`), which builds the db-name-tagged event and calls
`bcast/broadcast!`. `broadcast!` fans to (a) socket subscribers (the Rust host's
single tagged stream) and (b) in-process per-db subscribers (`broadcast.clj:66-87`).

The **second** listener — the reactive engine's `::reactive` — is the only gap
in the listen chain. The platform deliberately built the extension point
(`registry/register-on-ensure-db-hook!`) so that BOTH `::raw-broadcast` (wire)
and `::reactive` (reactive) install per-conn without either ns requiring the
other. wire.clj registers its hook at ns-load (`wire.clj:318-325`). **reactive.clj
registers NO hook** — so at server start no `::reactive` listener is attached to
any conn, `on-tx!` never runs live, and no `changed-summaries` event is ever
emitted. The engine is proven in tests by manually wiring `dc/listen!` in the
`with-engine` harness (`reactive_test.clj:23`), but production has no equivalent.

**Runnable?** Yes. `:writer` → `-m seon.server.boot` (`deps.edn:140`).
`boot.clj` requires both `wire` and `reactive`, so reactive's schema
registrations fire at boot (its `::reactive` hook would too — but it doesn't
register one yet). The Rust host spawns this with `--req-sock/--pub-sock`.

## §2 — Guest↔host transport (how the MVP agent reads/writes the DB)

The MVP node agent talks to the JVM host **through the WIT bridge, not via MCP
eval or nREPL**:

```text
agent CLJS  →  seon.client-runtime.db  →  seon.client-runtime.wit (Transit encode)
            →  JS WIT import `seon:client-runtime/db@0.1.0`  (wasm-rquickjs)
            →  Rust host db_iface::Host (guest.rs)  →  CBOR over UDS req/pub sockets
            →  JVM wire-server (handle-op / broadcast)
```

- **Working guest-side wire client: YES.** `seon.client-runtime.db` mirrors
  `datahike.api` (create-database, connect, transact!, q, pull, pull-many,
  entity, schema, filter, listen!/unlisten!). All calls funnel through
  `seon.client-runtime.wit/invoke` → the JS WIT import. Transit-JSON for every
  value payload; CBOR for the control envelope.
- **Reads/writes:** `transact!` generates a `request-id`, Transit-encodes
  tx-data/tx-meta, calls the `transact` WIT func, bumps the conn's `:basis-t`
  atom (`db.cljs:99-110`). `q`/`pull` carry the conn's basis-t for consistent
  reads (server-side `d/as-of`).
- **Tx-event subscription (raw):** the guest's `listen!` spins ONE upstream
  subscription (`subscribe-tx` WIT op) and a polling loop on `next-tx-event`
  (`db.cljs:225-271`), fanning out to local callbacks. This is the RAW `tx`
  event stream — NOT the reactive `changed-summaries` stream.
- **CRITICAL GAP for the reactive MVP:** the WIT contract (`db.wit:21-120`) has
  **no** `register-subscription` / `unregister-subscription` func and **no**
  `changed-summaries` event record. The reactive wire handlers
  (`reactive/register-subscription`) are reachable only as JVM-internal
  `handle-op` ops over the req socket — and even those aren't registered yet
  (Phase B). So today a guest has **no way to register a reactive subscription
  or receive `changed-summaries`** through the production transport. The guest
  can only ride the raw `tx` stream and do its own client-side filtering.

For dev/REPL, `wire.clj` can also open a loopback socket REPL (`--repl-port`,
`wire.clj:625`), and `read-T` accepts EDN as a transitional convenience — so a
non-WIT diagnostic driver (Rust smoke, REPL) can drive ops directly. But the
MVP agent's path is WIT.

## §3 — `:seon.db/subscriptions` on the agent entity

**The user's desired shape — `(d/transact! <my-eid> :seon.db/subscriptions
[{...}])` to register always-live queries that render into the agent's context
— does not exist.** No `:seon.db/subscriptions` schema, no guest API, no WIT op.
What exists is a different (op-centric) model:

- A subscription is a **standalone durable entity**: `:seon.subscription/id`
  (identity string), `:seon.subscription/query` (the datalog query as a SOURCE
  STRING — code-as-data), `:seon.subscription/active?` (boolean). Registered via
  `reactive/register-subscription!` (`reactive.clj:186-195`), which transacts the
  datom then seeds the engine cache.
- Registration is driven by the **`register-subscription` wire op**
  (`reactive.clj:286`), keyed by `:seon.server.reactive/sub-id` +
  `:seon.server.reactive/query`. There is no link from the subscription to its
  owning agent yet — `:seon.subscription/agent` (a `:seon.db/ref`) is specced in
  m3-prep §2a/the PRD but **not registered** in live code.

**Compatibility of the on-entity shape.** The two are reconcilable but the
on-entity shape needs new work:

1. `:seon.db/subscriptions` would be a **component cardinality-many ref** on the
   agent entity (`[:vector {:seon.db/component true} :seon.db/ref]`), each ref a
   `:seon.subscription/*` entity. That is the standard Seon ref-vector pattern
   (CLAUDE.md "Shared schema shapes"). Today subscriptions are flat top-level
   entities found by `:seon.subscription/id` (`rebuild!` queries `[?s
   :seon.subscription/id ?id]`, `reactive.clj:205-209`); switching to
   agent-owned components is a query change in `rebuild!` plus the new ref attr.
2. The engine's `register-sub!`/`on-tx!`/`rebuild!` are **agnostic to where the
   sub datom lives** — they key off `:seon.subscription/id` + `query`. So the
   on-entity shape can reuse the entire engine; only the persistence shape and
   `rebuild!`'s discovery query change.
3. The "render results into the agent's context" half needs the **summary
   attrs** that don't exist yet: `:seon.render/ai`, `:seon.render/html` on the
   agent's own entity (the glossary's "summary"), plus the `render-fn` ref to a
   `:seon.fn` entity. None registered server-side.
4. To make `(d/transact! my-eid :seon.db/subscriptions [...])` actually
   *activate* a subscription (derive patterns, seed the engine cache, install in
   the index), the engine must learn of subscriptions **from a transaction**,
   not just from the op. That means `on-tx!` (or a sibling listener) must detect
   newly-transacted `:seon.subscription/*` datoms and self-register them — the
   "detect-and-tee" mechanism from the code-as-data-runtime principle. Today
   registration is explicit (op or `register-sub!`), NOT tx-driven. **This is
   the core new mechanism the on-entity shape requires.**

Recommendation: the on-entity shape is the right end-state (it matches the
reactive-context + code-as-data principles and makes subscriptions
agent-owned/glanceable), but it is a NEW design on top of the op model. The MVP
can ship the op model first (it's built) and layer the on-entity sugar +
tx-driven self-registration after.

## §4 — Webview consumer

**Yes — a JVM-side SSE handler that calls `bcast/subscribe!` for one db-name and
forwards changes to the browser is the cleanest MVP webview bridge, and it needs
no Rust host.** `broadcast.clj`'s in-process leg (`subscribe!`/`broadcast!`,
routed by `"db-name"`, `broadcast.clj:38-87`) is exactly this seam — it exists
specifically as "the reactive engine's / in-JVM consumer's routing path"
(`broadcast.clj:13-16`). A subscriber registered for the cluster's db-name
receives every `tx` event (and every `changed-summaries` event, once the
`::reactive` listener is installed) for that db, with zero cross-bleed.

Caveats:

- The existing `src/seon/web/*` Datastar stack is **V1/JVM-lane**: it requires
  Integrant + `seon.db` (the embedded JVM datahike, a DIFFERENT database from
  the server-lane registry conns). It is NOT wired to `seon.server.*`. So the
  webview is a **new, thin handler**, not a modification of the existing web
  server — it (a) `bcast/subscribe!`s to the cluster db-name, (b) on each event
  re-reads the agent's `:seon.render/ai`/`:seon.render/html` summary via a
  server-lane conn (`registry/get-conn` → `d/pull`), and (c) streams a Datastar
  `merge-fragment` over SSE. The `/datastar-web-ui` patterns apply to the
  rendering half.
- For the MVP "AI + HTML view that updates reactively", the consumer ideally
  subscribes to the `changed-summaries` event (so it re-renders only when an
  agent's summary actually moved). Until the `::reactive` listener is installed
  (§5), the webview can fall back to the raw `tx` event and re-pull the summary
  on any commit — correct but coarser.

## §5 — Platform punch-list vs reactive punch-list (single-cluster live MVP)

### Already landed (do not redo)

- Raw write→broadcast path end-to-end (WIT → host → commit → `::raw-broadcast`
  → `broadcast!`), request-id on single transact, real db-name, per-DB fanout,
  conn-resolution into `handle-op`, the registry + agent index + on-ensure-db
  extension point, `:seon.agent/id`, `seed-base-schema!` for `:write` conns, the
  `:writer`→`boot` load-path (both wire + reactive load at start). Commits
  `019d594` (conn-routing + listen! hook + per-DB broadcast) and `bb06be6`
  (R1 test + boot load-path).
- The reactive engine itself: `on-tx!`, two-gate dispatch, inverted index,
  register/unregister/rebuild, the two pure wire handlers, and the
  changed-summaries/changed-entry/rows schemas — all built and green
  (`reactive.clj`; 12 deftests).

### REACTIVE-lane punch-list (owned by the reactive seat)

1. **`(reactive/engine-state db-name)` + `{db-name→state}` atom.** A
   defonce'd registry of per-db engine states + a lookup fn. Phase B's wrappers
   and the `::reactive` listener must reach the SAME atom. (m3-prep "NEXT item
   1"; Platform is explicitly blocked on this — "Ping when `engine-state`
   lands.") **Not built.**
2. **`install-reactive-schema! conn`** — derive + transact the `:db/ident`
   install vector for `:seon.subscription/*`, `:seon.fn/*`, `:seon.render/ai`
   (the `:write`-flexibility install). Idempotent. Needed because cluster conns
   are `:schema-flexibility :write` (`store.clj:121`); `register-subscription!`
   would otherwise fail to transact `:seon.subscription/id`. **Not built.**
3. **The integration plug: reactive's own `register-on-ensure-db-hook!`.** At
   reactive ns-load, register a hook that per conn: `install-reactive-schema!`,
   `(new-engine-state db-name)`, stash it in the `{db-name→state}` atom,
   `rebuild!`, and `(d/listen conn ::reactive (fn [report] (on-tx! {...
   :emit! bcast/broadcast!} report)))`. Mirror `wire.clj:318-325`. **This is the
   single change that makes the reactive path go live.** **Not built.**
4. **Register the missing schemas:** `:seon.fn/sym`+`:seon.fn/source`+entity;
   `:seon.render/ai` (and reuse/flag `:seon.render/html`); `:seon.agent/entity`
   (with `:seon.render/*` + `launched-by`); `:seon.subscription/agent` +
   `:seon.subscription/render-fn` refs (after requiring `registry` so
   `:seon.agent/id` exists). m3-prep §2b/2c. **Not built.**
5. **changed-summaries entry enrichment (M3/M4):** today the entry carries only
   `{:seon.subscription/id, :seon.server.reactive/rows}` (`reactive.clj:40-43,
   254-257`). The MVP webview wants the agent-id + the rendered summary; add
   `:seon.agent/id` (resolved from the sub's `:seon.subscription/agent`) and the
   `:seon.render/*` values to each entry. **Not built.**

### PLATFORM-lane punch-list (owned by the platform/orchestrator seat)

1. **Phase B `handle-op` wrappers in `boot.clj`** — `register-subscription` /
   `unregister-subscription` defmethods that resolve the conn (registry),
   Transit-decode the request, look up `(reactive/engine-state db-name)`, and
   delegate to `reactive/register-subscription`/`unregister-subscription`.
   Staged in `boot.clj:24`; **gated on reactive item 1 (`engine-state`)**. **Not
   built.**
2. **The bind caller (P2): a `register-agent` wire op / cluster-config** that
   calls `registry/register-agent!` on guest→cluster bind, so
   `{agent-id→db-name}` is populated before `register-subscription` resolves a
   conn by agent-id. `register-agent!` exists; nothing calls it from a wire op.
   **Not built.** (Socket-REPL tests call it directly.)
3. **WIT surface for the reactive ops (if the guest is to register subscriptions
   itself).** `db.wit` has no `register-subscription`/`unregister-subscription`
   func and no `changed-summaries` event record; the Rust host has no routing
   for them. Required only if the MVP agent registers its OWN reactive
   subscriptions (vs. them being seeded host-side / via the diagnostic REPL).
   For a first single-cluster MVP this can be deferred if subscriptions are
   seeded over the dev socket REPL or the on-entity transact path (§3) is used
   instead. **Not built.**
4. **R1 regression test** pinning request-id → tx-meta → `changed-summaries`
   end-to-end. The wire-side half landed (`wire_request_id_test.clj`); the
   cross-listener end-to-end assertion is pending the `::reactive` listener
   being live. **Partial.**

### MVP webview punch-list (new, small)

5. **A server-lane SSE handler** that `bcast/subscribe!`s a cluster db-name,
   re-pulls the agent summary from a `registry/get-conn` conn on each event, and
   streams a Datastar fragment. New ns; not the V1 `src/seon/web/*` stack. **Not
   built.**

## Code smells / flags found

- **`reactive.clj:230` reads `:seon.db/request-id` off `:tx-meta`** (correct
  key, matches `wire.clj:363-367`). But because the `::reactive` listener is
  never installed, this path is untested in production — flag for the R1
  end-to-end test once the listener lands.
- **`registry.clj` docstring still references a sibling `seon.session` ns**
  (`registry.clj:9-22`) and `seon.session/with-agent` (`:307`). Per the glossary
  "session" is retired as an isolation-boundary term; verify whether
  `seon.session` still exists or these are stale doc references. Low priority,
  flagged not fixed.
- **`store.clj` + `registry.clj` use both `::db-name` keyword (store/registry
  routing) and the wire event's string `"db-name"`.** Platform confirmed these
  are intentionally distinct (`reactive-interface-platform-review` "db-name keys
  — confirmed distinct"). Not a bug; noting because the three db-name
  representations (keyword store config, keyword registry key, string wire
  event) are a known sharp edge for anyone wiring the webview.
- **`broadcast!` swallows in-process subscriber exceptions** (`broadcast.clj:86`
  `(catch Throwable _)`). For the webview that means a dropped SSE connection
  fails silently — acceptable (lossy-safe per the topology), but the handler
  must self-heal (re-subscribe / drop dead streams) since `subscribe!` has no
  dead-detection (unlike the socket leg, `broadcast.clj:73-81`).

## References

- `src/seon/server/wire.clj` — transact op, `::raw-broadcast` listener, conn
  routing, req server.
- `src/seon/server/reactive.clj` — the engine + pure wire handlers (unplugged).
- `src/seon/server/registry.clj` — `{db-name→conn}` + `{agent-id→db-name}` +
  on-ensure-db extension point + `resolve-conn`.
- `src/seon/server/broadcast.clj` — per-DB fanout (socket + in-process).
- `src/seon/server/boot.clj` — `:writer` entry; loads wire + reactive.
- `src/seon/server/store.clj` — `:schema-flexibility :write`, `:memory`/`:file`.
- `guest-cljs/src/seon/client_runtime/{db,wit}.cljs` — guest-side wire client.
- `client-runtime/host/wit/db.wit` — WIT contract (no reactive op).
- `client-runtime/docs/PROTOCOL.md` — wire protocol (CBOR envelope + Transit
  payloads, raw `tx` pub event).
- `docs/prds/agent-runtime/m3-prep-2026-06-03.md`,
  `reactive-interface-platform-review-2026-06-03.md`, `glossary.md`,
  `reactive-agent-topology.md`.
