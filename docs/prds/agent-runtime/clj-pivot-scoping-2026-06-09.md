---
type: research
status: active
tags: [research, agent, database, flow]
---

# CLJ-pivot scoping (Track B) — 2026-06-09

> Assessment/design only. NOT a build. Grounds the Track B pivot of
> `cljs-finish-clj-pivot-plan-2026-06-09.md`: keep the CLJS pod as the agent
> loop + eval + context render; move the central store + heavy code
> analysis/indexing to a CLJ subsystem the pod talks to. Every claim below is
> backed by a code ref (`file:line`) and/or a live eval against the running
> orchestrator JVM + the running wire-server (`bin/seon status`: jvm pid 41468,
> wire-server pid 51224, both up 2026-06-03).

## TL;DR

- **The pivot's hardest piece already exists and WORKS LIVE.** A JVM datahike
  wire-server (`:writer` alias → `seon.server.boot` → `seon.server.wire`) owns
  an **on-disk file-backed** datahike conn and answers `ping/q/transact/pull/
  pull-many/schema/entity-pull/...` over a Unix-domain socket with Transit-JSON
  value payloads. Verified end-to-end over the socket from the orchestrator REPL
  this session: register schema → transact entity → query it back
  (`#{["scoping-agent-probe"]}`), against `data/clusters/default/store`,
  basis-t > 0. This is the central store + the integration seam.
- **A complete CLJS guest client mirrors `datahike.api`** (`transact!/q/pull/
  entity/listen!/pull-many/filter/transact-batch!`) and routes every op to that
  writer — `seon.client-runtime.db` + `seon.client-runtime.wit`. The guest's
  Transit codec mirrors the server's exactly.
- **The ONE missing transport piece for the POD:** the guest client routes
  through **WIT-bound host fns** (`wit.cljs:25-40`), i.e. the WASM host. The V0
  pod is plain Node (no WASM), and there is **no `node:net` UDS client** anywhere
  (grep: 0 hits for `net.connect`/`node:net`/`.sock` in `src/seon/*.cljs` or
  `guest-cljs/src`). The pod today embeds **datahike-cljs in-process**
  (`client.cljs:29 [datahike.api :as d]`, `db.cljs:208`). So the pivot's
  first slice = a thin Node UDS transport that satisfies the same op surface the
  guest client already calls, then point the pod's `seon.db` conn at it.
- **Heavy analysis/indexing is JVM-resident and working in-process** today:
  `seon.graph.analyzer` (verified: `analyze-form` returns
  `{::success ::raw-analysis}`), `seon.graph.scanner`, `seon.graph.ingest`,
  `seon.graph.query`, `seon.dev.compliance` (verified: `analyze-namespace` on a
  live ns returns `{::compliant? true ::public-fns 2 ::with-complete-specs 2}`).
- **The richer compliance checker is the right home for A2's SPECIFIC checks**
  (return-is-any / arg-is-any / uses-maybe). It already detects the banned node
  TYPE via a parsed-schema walk (`compliance.clj:180-191 banned-node-types`) but
  currently lumps everything into one `:incomplete-spec` violation
  (`compliance.clj:332`). Making it say WHERE (return vs which arg) is a precise
  enhancement of one fn, not a rebuild — and it belongs on the JVM (B3).
- **Two genuine gaps:** (1) the reactive engine is **built but unwired** into the
  wire-server (no `handle-op` defmethods, no `::reactive` on-ensure-db hook
  registered); (2) the main JVM's own `:seon.db/flow` is **`:memory` on every
  profile** (`system.edn:134-136`) and currently has **zero registered
  conn-processes** in the running JVM — it is NOT the central store. The
  wire-server is. Don't conflate them.

---

## 1. What's ALREADY built + working (the reuse inventory)

### 1.1 Central datahike store + the wire — BUILT, WORKING (on-disk, live)

- **Code:** `seon.server.wire` (`src/seon/server/wire.clj`, 657 lines),
  `seon.server.store` (config), `seon.server.registry` (multi-DB conn registry),
  `seon.server.broadcast` (pub socket), `seon.server.codec` (CBOR framing),
  `seon.server.transit` (Transit-JSON value payloads), `seon.server.boot`
  (`:writer` main).
- **Launch:** `bin/seon start wire-server` →
  `clojure -M:writer --backend file --path data/clusters/default/store
  --req-sock tmp/seon-cluster-default-req.sock
  --pub-sock tmp/seon-cluster-default-pub.sock --repl-port 7891`
  (`bin/seon:49`).
- **Live verification (this session, over the socket from the orchestrator
  JVM):**
  - `{"op" "ping"}` → `{"pong" true "ok" true}`.
  - `{"op" "schema"}` → ok, Transit string (329 chars of installed schema).
  - register `:scope.probe/name` (identity) → transact `{:scope.probe/name
    "scoping-agent-probe"}` → `datoms-added 2` → `{"op" "q" ...}` →
    `#{["scoping-agent-probe"]}`. Full register→store→read **over the wire**,
    against the on-disk store. Backend = `:file`, basis-t = 536870912 at boot
    (`logs/wire-server.log`).
- **On disk:** `data/clusters/default/store/` (the konserve file tree). NOTE:
  this is the wire-server's store; `data/sessions/<short>/` is the multi-DB
  per-session layout `seon.server.store/default-path` derives (`store.clj:75-84`)
  — already populated with stale single-letter dirs from prior runs.
- **Ops surface (every `handle-op` defmethod in `wire.clj`):** `ping`,
  `ensure-db` (multi-DB lifecycle, `wire.clj:224`), `q` (`:243`),
  `transact` (`:353`), `transact-batch` (`:394`), `pull` (`:457`),
  `entity-pull` (`:476`, eager, component-ref expansion to depth),
  `pull-many` (`:493`), `schema` (`:501`), `reverse-schema` (`:506`),
  `db-filter`/`q-filtered`/`filter-release` (filtered-db handles, `:511-539`).
- **Wire format:** control envelope = CBOR map with string keys; every Clojure
  VALUE (query, args, tx-data, selectors, eids, results, tempids, tx-meta) =
  Transit-JSON string. Datom shape `[e a-transit v-transit t op]`
  (`wire.clj:5-12`, `134-144`). Schema-driven float/double coercion on
  ints (`wire.clj:166-196`) handles the JS-number ambiguity.
- **Conn routing:** every request optionally carries `agent-id` and/or `db-name`;
  `resolve-conn-for-req` (`wire.clj:541-559`) → `registry/resolve-conn`
  (`registry.clj:354-385`). Neither key present → the single ambient conn
  (single-DB back-compat). This is the seam for per-session/per-agent stores.

### 1.2 Multi-DB registry — BUILT, working (the per-session seam)

- `seon.server.registry` (`src/seon/server/registry.clj`, 418 lines): an atom
  `{db-name -> {::conn ::backend ::path}}` + an atom `{agent-id -> db-name}`.
  `ensure-db!` (idempotent, lock-serialized create, `:243`), `remove-db!`,
  `register-agent!`/`resolve-agent`/`resolve-conn`, `list-sessions`/`list-agents`.
- **The on-ensure-db extension point** (`registry.clj:209-228`): a vector of
  `(fn [conn db-name])` hooks fired once per newly-opened conn. The wire-server
  installs its `::raw-broadcast` `d/listen` here WITHOUT the registry requiring
  `wire.clj` (`wire.clj:318-325`). **This is the designed reactive seam** — the
  reactive engine is meant to register a `::reactive` hook the same way (see §3).
- Per-session DBs (`:memory | :file | :sqlite`) via `seon.server.store/config-for`
  (`store.clj:125`). `:sqlite` is wired but throws "not yet supported" pending a
  konserve-jdbc dispatch shim (`store.clj:32-42`) — `:file` is the working
  on-disk backend today.

### 1.3 CLJS guest DB client — BUILT (mirrors datahike.api, wire-only)

- `seon.client-runtime.db` (`guest-cljs/src/seon/client_runtime/db.cljs`, 307
  lines): `db`, `connect`, `transact!`, `transact-batch!`, `q`, `pull`,
  `pull-many`, `entity`, `schema`, `reverse-schema`, `filter`/`q-on`/
  `release-filter!`, `listen!`/`unlisten!`. A `db` value is `{:basis-t :conn}`;
  a `conn` is `{:basis-t (atom) :listeners (atom) :sub ...}`. `listen!` spins a
  guest-side poll loop over `next-tx-event` (`db.cljs:225-271`).
- It funnels every op through `seon.client-runtime.wit`
  (`guest-cljs/src/seon/client_runtime/wit.cljs`) which calls **WIT-bound host
  fns** resolved from `globalThis.__seon_client_runtime_db` or a `js/require`
  mock (`wit.cljs:25-40`). `seon.client-runtime.transit` (`transit.cljs:1-8`)
  is the exact mirror of `seon.server.transit`.
- **Implication:** the protocol + marshalling + a datahike-shaped client API are
  done. What's WASM-specific is only the transport (WIT imports). A Node-pod
  transport that speaks the same socket protocol drops straight under this same
  client shape.

### 1.4 Heavy code analysis / indexing — BUILT, working in-process (JVM)

- `seon.graph.analyzer` (`analyze-form`/`analyze-project!`) — verified live:
  `analyze-form {::source "(defn ema ...)"}` → `{::success true ::raw-analysis
  ...}`.
- `seon.graph.scanner` (spec/schema + var extraction from source).
- `seon.graph.ingest` (`ingest-namespace!`, `ingest-analysis!`,
  `ingest-incremental!`, `ingest-file!`) — bulk + incremental upsert of ns/fn/
  var-usage/ns-dependency/spec/var/shape entities into a datahike db-name
  (`ingest.clj:440,533,602,637`).
- `seon.graph.query` (`dependents-of`, `dependencies-of`, `call-graph`,
  `callers-of`, `functions-in-ns`, `transitive-dependents-of`,
  `search-functions`, `functions-with-output-key`, `functions-matching-data`,
  `function-output-keys`) — the code-graph query surface.
- These run in-process and are DB-name-parameterized; they do not depend on the
  pod. They are the B2 offload target.

### 1.5 Compliance checker (the richer Malli-walk) — BUILT, working

- `seon.dev.compliance` (`src/seon/dev/compliance.clj`, 766 lines) — verified
  live: `analyze-namespace {::namespace 'seon.server.store}` →
  `{::compliant? true ::public-fns 2 ::with-schema 2 ::with-complete-specs 2}`.
- It **parses** each fn's `:malli/schema` with `m/schema` and **walks** the
  parsed tree to find banned node TYPES (`:any`/`:some`/`:maybe`) at any depth /
  inside any combinator (`compliance.clj:180-214 banned-node-types` +
  `complete-spec?`). This is strictly richer than anything the pod can do over
  the corpus, because it resolves references against the real registry and sees
  the resolved node types.
- **Current limitation (the A2 enhancement, not a rebuild):** it emits ONE
  `:incomplete-spec` violation (`compliance.clj:332`) and a single generic
  message; it knows WHICH banned type is present but not WHERE (return vs which
  arg slot). A2 wants per-defect kinds with locations. See §3 op-routing.

### 1.6 Runtime instrumentation — BUILT (JVM)

- `:seon.dev/instrumentation` Integrant component instruments every public fn
  with `:malli/schema` (CLAUDE.md). Lives JVM-side; survives `(user/reset)`. It
  is a JVM capability the pod's eval cannot replicate (the pod validates via its
  own boundary in `db.cljs`).

---

## 2. The integration seam (how the pod talks to the central CLJ store)

**Transport:** Unix-domain socket (req/reply) + a second UDS for tx-event pub.
Frames are length-prefixed CBOR (`seon.server.codec`); value payloads are
Transit-JSON. Live sockets right now: `tmp/seon-cluster-default-req.sock`,
`tmp/seon-cluster-default-pub.sock`.

**Message shape:** `{"op" <string> ... value-fields-as-transit-strings}` in,
`{"ok" true ...}` / `{"ok" false "error" .. "error-kind" ..}` out. Full surface
in §1.1. Request optionally carries `agent-id`/`db-name` for multi-DB routing.

**What already works on the seam (verified live this session):** ping, schema,
transact (DDL + data), q — full register→store→read roundtrip against the
on-disk store. The wire-server is a stable, daemonized, REPL-attachable
(`127.0.0.1:7891`) process.

**What the pod would use to drive it:** the `seon.client-runtime.db` API surface
already exists and is datahike-shaped. The pod's existing `seon.db` (`db.cljs`)
is ALSO datahike-shaped (positional `(transact! conn tx)` + map-in). So the seam
is: replace the pod's in-process datahike-cljs conn with a **wire conn** whose
ops marshal to the socket. Two viable framings:

1. **New Node transport ns** `seon.client-runtime.node-wire` (or reuse the WIT
   surface name) that opens a `node:net` UDS socket, frames CBOR, and exposes the
   same fn names the guest `wit.cljs` expects (`q`, `transact`, `pull`, ...).
   Then `seon.client-runtime.db` works UNCHANGED in the pod. This is the
   minimal-divergence path and reuses the most code.
2. **Point the pod's own `seon.db` (`db.cljs`) `*conn*`** at a wire-backed conn
   value. More work (the pod's `db.cljs` has its own validation/ensure-attrs
   machinery, ~1100 lines) but keeps the agent-facing API byte-identical.

Recommended: option 1 (transport adapter under the existing guest client),
because the guest client + Transit codec + protocol are done and the only WASM
coupling is `wit.cljs`'s host-fn resolution.

**What's stubbed / not-yet-wired on the seam:**

- **Reactive subscriptions are NOT reachable over the wire.** `wire.clj` has no
  `register-subscription`/`unregister-subscription` `handle-op` defmethods, and
  no `::reactive` on-ensure-db hook is registered. `seon.server.reactive` only
  registers SCHEMAS at ns-load (`reactive.clj:32-50,273-302`); its pure engine
  fns (`register-subscription`/`unregister-subscription`, `reactive.clj:286,304`)
  exist but nothing calls them from a `handle-op`. `boot.clj:22-31` documents
  this explicitly ("the reactive op-wrappers ... WILL live HERE too" — they
  don't yet). So `listen!` on the guest client → `subscribe-tx`/`next-tx-event`
  WIT ops that the wire-server doesn't yet implement as `handle-op`s. The
  guest's tx-event poll loop expects a `subscribe-tx`/`next-tx-event` op surface
  that is not in `wire.clj`'s multimethod.
- **`seon.session` (Path A entity lifecycle) is built but unwired** to the
  registry (memory note + `registry.clj:9-21` "Sibling NS" comment): the session
  ENTITY (`seon.session`, persisted to `:seon.orchestrator`) and the runtime conn
  REGISTRY (`seon.server.registry`) are separate; nothing binds an MCP-eval
  agent-id to a registry db-name in the live system today.

---

## 3. Op-routing map (what moves, what stays, where A2's checks live)

| Operation | Today (V0 pod) | After pivot | Notes |
| --- | --- | --- | --- |
| Agent loop / turn lifecycle | pod (`agent.cljs`) | **stays in pod** | the proven loop |
| `cljs.js` eval + per-form emit | pod (`eval.cljs`) | **stays in pod** | REPL is local |
| Context assembly / render | pod (`agent.cljs`, `render.cljs`) | **stays in pod** | derived-from-DB; reads via the wire conn |
| `transact!` / `q` / `pull` / `pull-many` / `entity` | in-proc datahike-cljs (`db.cljs:208`) | **→ wire** (`handle-op` transact/q/pull/...) | surface done; needs Node transport |
| `listen!` (reactive tx feed) | in-proc datahike-cljs listener | **→ wire** (subscribe-tx/next-tx-event) | **GAP**: reactive not wired into `wire.clj` (§2) |
| Corpus indexing (`:seon.fn`/`:seon.ns`/`:seon.schema`/`:seon.test`) | pod analyzer-info + tee on eval | **split**: pod still TEES eval'd defs as data; **heavy/whole-project indexing → JVM** `graph/ingest` | code-as-data: pod emits source strings; JVM can re-ingest for cross-ns analysis |
| Code-graph queries (dependents/callers/output-keys) | not in pod | **→ JVM** `graph/query` | B2 |
| Compliance / warnings checks | pod-derived from corpus (A2) | **specific checks → JVM** `dev/compliance`, rendered in pod | see below |
| Instrumentation | n/a in pod | **JVM-only** | B-track capability |

**A2 ↔ B3 — the warnings split (the key design call).** A2 builds CLJS-doable,
clustered, SPECIFIC checks over the pod's corpus (no-malli-schema, missing-test,
bad-ref where detectable). The DEEPER, registry-resolving checks
(return-is-any / arg-is-any / uses-maybe, cross-ns ref integrity) are exactly
what `seon.dev.compliance` already computes via its parsed-schema walk. The
pivot lets those run JVM-side and ship their results to the pod **as data** for
the SAME clustered renderer A2 builds. Concretely:

- Enhance `compliance.clj`'s `banned-node-types`/`complete-spec?`
  (`:180-214`) to return per-defect entries with LOCATION — walk the parsed
  `:=>`/`:function` schema, tag a banned node as `:return` vs `[:arg <name>]` by
  its path. This turns one `:incomplete-spec` violation into the specific kinds
  A2's renderer wants. It is a precise extension of ONE namespace, not a v2.
- The wire-server gains a `compliance` (or `warnings`) `handle-op` that runs the
  scoped checks for a given ns and returns
  `{:seon.warn/kind ... :seon.warn/affected [{:sym :where} ...] ...}` — the SAME
  shape A2's CLJS checks emit — so the pod's `warnings-section` composes both
  sources through one registry. (Matches the A2 spec's compositional, clustered,
  per-kind contract.)

---

## 4. What's MISSING + risks

| # | Missing / risk | Severity | Evidence |
| --- | --- | --- | --- |
| M1 | **No Node UDS transport.** Pod is plain Node; the only wire client routes through WIT (WASM). 0 hits for `node:net`/`.sock` in pod/guest src. | blocking (first slice) | `wit.cljs:25-40`; grep |
| M2 | **Reactive not wired into the wire-server.** No `register/unregister-subscription` `handle-op`, no `::reactive` on-ensure-db hook, no `subscribe-tx`/`next-tx-event` ops. The guest `listen!` loop targets ops the server lacks. | high (B4 / live two-pane) | `boot.clj:22-31`; `reactive.clj` registers schemas only; `wire.clj` multimethod has no sub ops |
| M3 | **Pod↔wire `listen!` semantics mismatch.** JVM `d/listen` gives `{:db-before :db-after}`; the guest expects `{:basis-t :tx-data :tx-meta}`. The broadcast path (`wire.clj:299-311`) emits the latter on the PUB socket, but the pod must consume the pub socket (a second Node UDS) and fan out — not yet built for Node. | medium | `db.cljs:273-296`; `broadcast.clj` |
| M4 | **`seon.session` entity ↔ registry binding is unwired.** No live `register-agent!` call path; agent-id→db-name routing exists in code but nothing populates it. | medium (per-session partitioning) | `registry.clj:9-21,309`; memory note |
| M5 | **`:sqlite` backend throws** (konserve-jdbc dispatch shim missing). `:file` is the only working on-disk backend. Fine for now; revisit for scale. | low | `store.clj:32-42` |
| M6 | **Serialization edge: JS number 1 vs 1.0.** Handled server-side by schema-driven coercion (`wire.clj:166-196`) but ONLY for attrs whose schema declares float/double. Untyped numeric attrs from the pod could round-trip surprisingly. | low/watch | `wire.clj:149-196` |
| M7 | **Two "central stores" could be confused.** `:seon.db/flow` (main JVM) is `:memory` on all profiles and has 0 registered conn-processes live — it is NOT the pivot's store. The wire-server is. Pivot must not accidentally target `seon.db/flow`. | design clarity | `system.edn:134-136`; live: flow registered db-names = `()` |
| R1 | **Pod loses the convenience of in-process datahike-cljs** (sync-ish, no marshalling). Every DB op becomes a socket round-trip. Mitigated: the guest client already batches (`transact-batch!`) and uses basis-t snapshots; the wire is local UDS (cheap). | acceptable | `db.cljs:112-154` |
| R2 | **Corpus double-source.** If the pod TEEs eval'd defs as `:seon.fn` data AND the JVM re-ingests source, two writers touch the same entities. Code-as-data principle says one mechanism — decide: pod writes the live-eval corpus, JVM owns whole-project static indexing into a SEPARATE db-name (or the JVM ingests only what the pod doesn't). | design | code-as-data concept; `ingest.clj` upsert+retract-stale |

---

## 5. Recommended first slice + sequence

**First slice (smallest end-to-end, proves the seam from the POD specifically):**

> Route ONE pod DB op (`transact!` then `q`) to the running wire-server over a
> Node UDS socket, and read the result back in the pod's REPL.

1. Write `seon.client-runtime.node-wire` (pod-side, plain Node): open a
   `node:net` connection to `tmp/seon-cluster-default-req.sock`, implement the
   CBOR length-prefixed framing (mirror `seon.server.codec`), and expose
   `q`/`transact`/`pull`/`schema` fns that Transit-encode values (reuse
   `seon.client-runtime.transit`, already CLJS). This is the M1 fix and the only
   genuinely new code.
2. Make `seon.client-runtime.wit`'s host-fn resolution fall back to this Node
   transport when the WIT module is absent (it already has a `js/require`
   fallback branch at `wit.cljs:38`). Then `seon.client-runtime.db` works in the
   pod unchanged.
3. From the pod REPL: `(d/transact! conn [{:scope.probe/name "from-pod"}])` then
   `(d/q '[...] (d/db conn))` → confirm the row, and confirm it's visible from
   the JVM side (`data/clusters/default/store`). **Oracle:** the same entity
   readable from BOTH the pod and the wire-server's REPL (`127.0.0.1:7891`).

This slice deliberately uses the SINGLE ambient conn (no agent-id/db-name) —
the back-compat path that already works — so it isolates the transport from the
multi-DB + reactive gaps.

**Sequence after the first slice:**

- **S2 — read path + corpus reads over the wire.** Point the pod's context/
  render reads (`q`/`pull`/`entity`) at the wire conn. Verify context assembly
  renders identically against the central store.
- **S3 — reactive (M2/M3).** Add `subscribe-tx`/`next-tx-event` + `register/
  unregister-subscription` `handle-op`s to `wire.clj` (or `boot.clj`), register
  the `::reactive` on-ensure-db hook, and a Node pub-socket consumer so the pod's
  `listen!` two-pane webview updates live. This unblocks B4.
- **S4 — heavy analysis offload (B2/B3).** Expose `graph/ingest` +
  `graph/query` + the LOCATION-aware `dev/compliance` checks as `handle-op`s
  (or a sibling op-namespace), and route A2's deeper warnings there; the pod's
  clustered `warnings-section` composes JVM + CLJS check results.
- **S5 — per-session DBs (M4).** Wire `register-agent!` on agent boot so each
  agent/session routes to its own `db-name` (`:file` backend under
  `data/sessions/<id>/`); the ambient conn becomes the substrate-shared default.

**What stays in CLJS (the pod), permanently:** the agent loop, `cljs.js` eval +
per-form REPL-style emissions, context assembly/render (a pure function of the
DB read over the wire), the agent-facing `seon.db`/`d`-shaped API, `seon.fs`,
the loopback HTTP+SSE webview.

---

## Appendix — live evidence captured this session (orchestrator JVM)

- `bin/seon status`: pod 19783, cljs-watch 8942, jvm 41468, wire-server 51224
  (all up).
- Wire-server roundtrip over `tmp/seon-cluster-default-req.sock`:
  `ping → {"pong" true "ok" true}`; `schema → ok` (329-char Transit);
  register `:scope.probe/name` → transact → `datoms-added 2` →
  `q → #{["scoping-agent-probe"]}`.
- `seon.dev.compliance/analyze-namespace 'seon.server.store` →
  `{::compliant? true ::public-fns 2 ::with-schema 2 ::with-complete-specs 2}`.
- `seon.graph.analyzer/analyze-form {::source "(defn ema ...)"}` →
  `{::success true ::raw-analysis ...}`.
- `seon.db/query :seon.runtime ...` → error: "No conn-process for db-name
  :seon.runtime — Registered: ()" (main JVM `:seon.db/flow` empty/`:memory`,
  NOT the central store — see M7).
- `wire-server.log`: `[writer] datahike ready; basis-t= 536870912`, backend
  `:file`, path `data/clusters/default/store`.

## Track 2 wiring progress 2026-06-09

> Implemented + verified the three Track 2 pieces: (1) re-verified the
> wire-server op surface + multi-DB routing, (2) built the missing plain-Node
> UDS transport as a REPL-addressable shadow-cljs runtime (NOT a hand-rolled
> standalone script — see decision below), (3) wired BOTH database-sync
> mechanisms (raw tx feed + reactive query subscriptions) into the wire-server.
> All proven end-to-end against the live wire-server + via MCP into the CLJS
> Node runtime. Wire-server restarted from current source (file-backed; data
> persisted). The pod's `:client` build was NOT touched.

### Key finding: the running wire-server was STALE

The wire-server pid 51224 (booted Jun 3) predated `ensure-db` / multi-DB
routing / the registry `resolve-conn` path — its `handle-op` multimethod had
NO `ensure-db` method and silently fell db-name-routed requests through to the
ambient conn (no isolation). The on-disk SOURCE had all of it; the PROCESS did
not. Fixed by restarting via `bin/seon restart wire-server` (now pid 40527,
basis-t persisted across the restart — file-backed konserve at
`data/clusters/default/store`). Lesson: verify the live PROCESS, not just the
source, before trusting "BUILT, working".

### 1. Wire-server op/message contract (re-verified, file:line)

- Transport: UDS req/reply (`tmp/seon-cluster-default-req.sock`) + pub
  (`...-pub.sock`). Framing: 4-byte BE length + CBOR (`seon.server.codec`).
  Control envelope = CBOR map, STRING keys. VALUES (query/args/tx-data/
  selectors/eids/results/tempids/tx-meta/datom a,v) = Transit-JSON STRINGS
  (`seon.server.transit`). Datom wire shape `[e a-transit v-transit t op]`
  (`wire.clj:134-144`).
- Op surface (all `handle-op` defmethods): `ping`, `ensure-db` (`wire.clj:224`),
  `q` (`:243`), `transact` (`:353`), `transact-batch` (`:394`), `pull` (`:457`),
  `entity-pull` (`:476`), `pull-many` (`:493`), `schema` (`:501`),
  `reverse-schema` (`:506`), `db-filter`/`q-filtered`/`filter-release`
  (`:511-539`) — PLUS the four NEW ops added this task in `seon.server.boot`:
  `subscribe-tx`, `next-tx-event`, `unsubscribe-tx`, `register-subscription`,
  `unregister-subscription`.
- Multi-DB routing (`resolve-conn-for-req`, `wire.clj:541-559`): a request's
  optional `agent-id` / `db-name` → `registry/resolve-conn`. Verified live:
  `ensure-db {"db-name" "seon.track2/scratch3" "backend" "memory"}` →
  scoped transact+query returns the row, ambient query returns `#{}`
  (isolation), unknown db-name → typed `{"error-kind" "not-found"}`.

### 2. Node transport — DECISION: a shadow-cljs `:node-script` runtime, NOT a standalone JS script

First built a dependency-free standalone Node script (hand-rolled CBOR +
transit-js) and proved the roundtrip. Per user direction (2026-06-09), REPLACED
it with the project's shadow-cljs node setup so the transport is a live runtime
you REPL into over MCP. The standalone JS proto was deleted.

- New shadow build `:wire-node` (`shadow-cljs.edn`): `:target :node-script`,
  `:main seon.dev.wire-node/-main`, `:devtools {:enabled true}` so the Node
  process registers as a shadow runtime. OUTSIDE `:client` (own build, own
  main).
- `src/seon/dev/wire_node.cljs` — the transport. `node:net` UDS socket; values
  via `cognitect.transit` (transit-cljs, already on the `:cljs` classpath;
  byte-identical to `seon.server.transit` / `seon.client-runtime.transit`);
  CBOR framing via `src/seon/dev/cbor.cljs` (a CLJS port of the JS codec,
  byte-verified against `seon.server.codec/encode`). Exposes async fns: `ping`,
  `ensure-db`, `transact`, `q`, `pull`, `schema`, `subscribe-tx`,
  `next-tx-event`, `unsubscribe-tx`, plus generic `rpc`.
- `src/seon/dev/cbor.cljs` — minimal CBOR encode/decode for the envelope
  subset (indefinite map like Jackson; def + indef strings/arrays/maps on
  decode). 0 warnings.
- Build + run:
  - add to the running watcher: `(shadow.cljs.devtools.api/watch :wire-node)`
    via the shadow nREPL (7889), or `clj -M:cljs watch wire-node`.
  - run: `node out/wire-node/main.js --agent-id wire`
  - REPL in: `mcp__seon_cljs__create_session build=":wire-node"` → eval in that
    sid (the `agent_id "wire"` probe path also works once `node_agent` is in
    the build; the create_session path is the reliable one).
- VERIFIED via MCP into the CLJS runtime: ping → `{"pong" true}`; register
  `:track2.cljs/marker` (identity) → transact → q → pull
  (`{:db/id 12 :track2.cljs/marker "from-cljs-node-..."}`); the transact
  `payload` decodes to native Clojure (`:datoms-added 2`, `:tx-meta` with
  `#inst`/`#uuid`). Multi-DB routing works from the Node client too.
- ORACLE (cross-process): the marker written by the CLJS Node runtime is
  readable from the wire-server's own JVM REPL (127.0.0.1:7891) against the
  on-disk store: `#{["from-cljs-node-1781020468025"]}`.

### 3. BOTH db-sync mechanisms wired into the wire-server (`seon.server.boot`)

The guest's `seon.client-runtime.db/listen!` loop polls `subscribe-tx` /
`next-tx-event` (the RAW tx feed). `seon.server.reactive` is the richer QUERY
subscription engine (changed query rows). Both are now reachable over the wire:

- RAW TX FEED — `subscribe-tx` opens a bounded per-handle queue + a
  `broadcast/subscribe!` callback on the target db-name; `next-tx-event` drains
  one event with a ~50ms bounded wait (typed `no-event` on timeout — the guest
  swallows it); `unsubscribe-tx` tears down. Queue bounded at 1024, O(1) size
  via an `AtomicInteger` (ConcurrentLinkedQueue.size() is O(n)).
- REACTIVE QUERY SUBS — `register-subscription` / `unregister-subscription`
  delegate to `seon.server.reactive`; a `::reactive` on-ensure-db hook installs
  the engine `d/listen` on every conn, seeds the `:seon.subscription/*` schema,
  and rebuilds the engine cache from persisted sub datoms. A changed query
  result emits a `changed-summaries` event on the SAME pub fanout (db-name
  tagged, body as Transit payload).
- The AMBIENT conn (created by `wire/ensure-db!`, OUTSIDE the registry) now
  also runs the on-ensure-db hooks: `wire/-main` calls
  `registry/run-on-ensure-db-hooks!` on it (and stores `:ambient-db-name` in
  wire state so `subscribe-tx` with no routing targets the right pub bucket).
- VERIFIED end-to-end (cold wire-server boot, zero manual steps, via the CLJS
  runtime): `subscribe-tx` → handle + correct db-name; a commit delivers a raw
  `"tx"` event (`basis-t`, `datoms-added 2`, full `tx-data`); `register-
  subscription` returns initial rows; a matching commit then delivers BOTH a
  `"tx"` and a `"changed-summaries"` event carrying the canonical
  `:seon.server.reactive/changed` shape with updated rows.
- Tests: `test/seon/server/boot_test.clj` (6 tests / 17 assertions, green in a
  fresh kaocha JVM) covers both feeds + typed errors + unsubscribe.

### Files changed (NOT committed — orchestrator commits after review)

- `src/seon/server/boot.clj` — the 5 new `handle-op`s + the `::reactive`
  on-ensure-db hook + `seed-subscription-schema!` + per-db engine registry.
- `src/seon/server/wire.clj` — `ambient-db-name` getter; `-main` stores it +
  runs the registry hooks on the ambient conn.
- `src/seon/server/registry.clj` — `run-on-ensure-db-hooks!` made public.
- `src/seon/dev/wire_node.cljs`, `src/seon/dev/cbor.cljs` — the Node transport.
- `src/seon/dev/node_agent.cljs` — added public `set-agent-id!` (MCP probe).
- `shadow-cljs.edn` — `:wire-node` build.
- `test/seon/server/boot_test.clj` — new.

### Remaining for the `:client` (pod) integration handoff

1. The pod's db path (`src/seon/db.cljs` / `seon.client` use of datahike-cljs
   in-process) is UNCHANGED — that integration is the later coordinated `:client`
   change. The route: make the pod's `seon.db` conn drive the wire over the same
   transport `seon.dev.wire-node` proves (or hook `seon.client-runtime.wit`'s
   `js/require` fallback to it). The pod's `listen!` loop already targets
   `subscribe-tx`/`next-tx-event`, which now EXIST server-side.
2. The live shadow watcher hosting the pod did NOT have `guest-cljs/src` on its
   classpath, so `wire-node` uses `cognitect.transit` directly rather than the
   guest's `seon.client-runtime.transit` wrapper (same two calls). The handoff
   consolidates onto the guest wrapper once the pod build wires the transport.
3. `:sqlite` backend still throws (`store.clj:32-42`); `:file` is the working
   on-disk backend.

### Smell flagged (out of scope)

- The wire `handle-op` boundary uses STRING-keyed CBOR envelopes (the wire
  contract — consistent across all 18 ops; values are registered Transit
  schemas). Gemini repeatedly flags this as a namespaced-keyword-convention
  violation. It is the deliberate wire-protocol boundary, not seon-internal
  data — a defmethod dispatching on a string `"op"` can't carry `:malli/schema`
  instrumentation anyway. If we want machine-discoverable wire ops, that's a
  larger design (register per-op request/response schemas + a coercion layer at
  `handle-req`), worth a focused decision rather than a drive-by change.
