---
type: research
status: active
tags: [research, agent, database]
---

# Sidecar PoC — coverage audit of datahike API surface used by V0

Author: research agent
Date: 2026-05-25
Scope: `src/seon/**/*.cljs` (V0 CLJS pod) + `pod-host/libdatahike-cljs/src` (bench harness, mirrors guest-side usage). JVM `src/seon/db/datahike/conn_process.clj` is the writer process — its surface is the floor of what the sidecar must also cover, because the JVM side will move *to* the sidecar later.

Sidecar baseline: `pod-host/sidecar-poc/PROTOCOL.md` + `rust-host/wit/sidecar.wit`. Today the protocol supports `ping`, `q`, `transact`, `pull`, `schema`, pub-socket tx events; the WIT surface exposes `q / transact / pull / subscribe-tx`.

## TL;DR

- **Distinct datahike APIs used in CLJS:** 9 functions: `create-database`, `connect`, `transact!`, `q`, `pull`, `entity`, `listen`, `unlisten`, plus the `@conn` deref. Plus `database-exists?` / `transact` (sync) / `release` / `schema` / `pull-many` on the JVM writer side (5 more — not user-visible from the CLJS guest, but the sidecar already covers them internally).
- **Sidecar protocol covers 7/9 cleanly (q, pull, transact!, listen via subscribe-tx pub, schema-install via `:op transact`, create-database via writer-start cfg, connect → handle).** The two genuine gaps are `entity` (lazy entity API used 15 times — needs an `entity-pull` extension or guest-side rewrite to eager pull) and `unlisten!` (subscribe-tx exists; no unsubscribe yet).
- **Predicates/fns in queries — the load-bearing question:** every predicate used is a CLJC built-in (`>`, `>=`, `=`, `contains?`, `identity`, `get-else`, `ground`) **except one** at `src/seon/agent.cljs:458` where a JS fn `(fn [^js d] (.getTime d))` is passed as an `:in` arg and called as `[(?->ms ?at) ?ms]`. This is a guest-side fn binding; the JVM writer cannot execute the guest's JS closure. Trivially refactorable — see "Out-of-scope features used by V0".
- **Out-of-scope features V0 actually uses:** ONE — the `?->ms` Date→ms shim above. No `:db.fn/cas`, no `:db/fn` registered functions, no `:db.fn/retractEntity` (the bare `[:db/retractEntity eid]` form IS used but is a built-in, not a tx fn), no `d/filter`, no `d/datoms`, no `d/with`, no `d/history`/`as-of`/`since`, no `d/index-range`, no `d/seek-datoms`. Lazy `d/entity` IS used but the access patterns are shallow (top-level keys + one ref hop in `:seon.agent/sessions` traversal at agent.cljs:494) — eager `pull` is a behavior-preserving substitute.
- **Verdict — swap datahike-cljs for sidecar overlay with no behavior change: YES with caveats.** Three caveats: (1) add an `entity` shim that delegates to `pull` with `'[*]` (or a smarter selector inferred from the call site) — 15 sites need it; (2) add `unlisten` to the protocol+WIT; (3) refactor the one `?->ms` predicate site to pre-compute ms in the `:in` row or store ms alongside :inst. None require new datahike features. Estimated effort: ~6-10 person-hours total.

## Full coverage table

API call counts cover both `src/seon/*.cljs` (V0 pod) and `pod-host/libdatahike-cljs/src/**/*.cljs` (bench harness, mirrors what an in-guest CLJS would call). The `seon.db` wrapper is the seam — agent code never calls `datahike.api` directly except in three places that pre-date the wrapper.

| API | call sites | usage shape | predicates/fns? | sidecar status | difficulty if extension | notes |
|---|---|---|---|---|---|---|
| `d/transact!` | V0: 9 (db.cljs:853 + smokes + tests + spike repls); bench: 7 | `(d/transact! conn tx-data)` or `(d/transact! conn arg-map)` — arg-map shape is `{:tx-data ... :tx-meta ...}` per db.cljs:846 | none | ✅ in protocol (`:op transact`, supports `tx-meta` + `request-id`) | — | request-id round-trip already specced; tempids returned. db.cljs:853 |
| `d/q` | V0: 28 query call sites (most through `db/query` wrapper at db.cljs:1131; 3 direct: wasm_smoke.cljs:49, client.cljs:163, client.cljs:389, db_test.cljs:306) | `(apply d/q query db args)` — db.cljs:1143 | builtins only (1 exception, see below) | ✅ in protocol (`:op q`, args list, EDN-string query) | — | predicate audit below |
| `d/pull` | V0: 5 callsites via wrapper (agent.cljs:648, 821, 922, 1002, 1179) + db.cljs:1151 + bench:391 | `(d/pull db pull-pattern ref)` — db.cljs:1151. Patterns include `'[*]`, named-key selectors, and component ref traversal `'[:seon.agent/sessions]` etc. | none | ✅ in protocol (`:op pull`) | — | `eid` must accept lookup-refs (e.g. `[:seon.agent/id "alpha"]`) — the JVM writer already does, but the WIT signature `eid: s64` does NOT. Bump to EDN string or variant. |
| `d/entity` | V0: 15 sites (db.cljs:1158 + agent.cljs ×11 + db_test.cljs ×3) | `(d/entity db ref)` — lazy entity. Used for: state lookup (`:seon.agent/state`), shallow component-ref traversal (`:seon.agent/sessions` → last by `:seon.session/at`), nil-guard before pull. | none | 🟡 needs extension — no protocol op | low (1-2h) — add `:op entity-pull` returning eager realized map; selector defaults to `'[*]` with ref-attr recursion to depth 1 | every access site reads at most one ref-hop deep. See "Refactor list". |
| `d/listen` | V0: 1 site (db.cljs:1240 — the seon.db wrapper); used by `seon.agent` listener + trigger dispatcher | `(d/listen c k handler-fn)` where handler-fn closes over guest state. db.cljs:1207-1256. | none | ✅ pub-socket already delivers full tx-data + tx-meta + basis-t-before; CLJS overlay subscribes once, dispatches to per-key handlers locally | — | The CLJS overlay needs to manage a local `{key → handler}` registry and fan out from one pub stream — exactly the pattern the WIT `subscribe-tx` already anticipates. |
| `d/unlisten` | V0: 1 site (db.cljs:1278) | `(d/unlisten c key)` | none | 🟡 protocol exists for subscribe, no unsubscribe op | low — if the CLJS overlay does the fan-out locally, `unlisten` is a no-op on the wire (just drop from local map). No protocol change needed. | — |
| `d/create-database` | V0: 4 (wasm_smoke:45, client:159/182/322, repl:117, db_test:65) + bench: 4 + spike files | `(await (d/create-database cfg))` | none | ✅ implicit — writer subprocess creates DB on start (cfg passed via `--backend` + `--path`) | — | guest-side `create-database` becomes a no-op or maps to "verify writer's DB matches cfg". |
| `d/connect` | V0: 6 + bench: 8 | `(await (d/connect cfg {:sync? false}))` | none | ✅ implicit — single writer = single conn; guest gets a handle | — | guest-side `connect` becomes "register with sidecar"; returns a logical handle. |
| `@conn` (deref) | V0: 1 site in wrapper (db.cljs:1142, 1150, 1158) | `@(resolve-conn conn)` to obtain a db value before `d/q` / `d/pull` / `d/entity` | — | ✅ writer returns `basis-t` on every q/transact; reads always against latest | — | Overlay's `query` ignores db value (always-latest from writer) or threads `basis-t` for snapshot reads. PoC's Phase-2 snapshot cache already keys on this. |
| `d/database-exists?` | JVM writer only: conn_process.clj:93 | guard before `create-database` | — | ✅ writer-internal, not exposed | — | — |
| `d/transact` (sync) | JVM writer only: conn_process.clj:134, 143, 185 | the writer uses the sync API per its own "G1" guideline | — | ✅ writer-internal | — | — |
| `d/release` | JVM writer only: conn_process.clj:262 | conn cleanup on shutdown | — | ✅ writer-internal | — | — |
| `d/schema` | JVM writer only: conn_process.clj:107, 141, 219 | reads current schema map | — | 🟡 not in protocol but trivial — `(:schema @conn)` walk | low — add `:op schema-read` if guest ever needs it | guest currently doesn't call this; only the writer's schema-install path uses it. |
| `d/pull-many` | JVM writer only: conn_process.clj:208 | batched pull | — | 🟡 not in protocol | low — add `:op pull-many` taking `[selector eids]` | not used from CLJS today; nice-to-have for batched UI renders. |

Sort key was call-site count. Direct `datahike.api` usage from CLJS (bypassing `seon.db`) is concentrated in three lanes: (a) smoke/demo files (`wasm_smoke.cljs`, `client.cljs` smoke fns, `repl.cljs`), (b) tests (`db_test.cljs`), (c) the wrapper itself (`db.cljs`). All "real" agent code goes through `db/transact!` / `db/query` / `db/pull` / `db/entity` / `db/listen!` — the surface to overlay is exactly those five functions.

## Predicate / fn audit (the load-bearing question)

Every Datalog query that uses `[(...)]` predicate or expression clauses in V0:

| File:line | Clause | Type |
|---|---|---|
| `src/seon/agent.cljs:458` | `[(?->ms ?at) ?ms]` where `?->ms` is bound from `:in` to `(fn [^js d] (.getTime d))` (agent.cljs:461) | **GUEST-SIDE USER FN — out of scope** |
| `src/seon/agent.cljs:459` | `[(> ?ms ?since-ms)]` | CLJC built-in |
| `src/seon/agent.cljs:1057` | `[(> ?e-at ?cutoff)]` | CLJC built-in |
| `src/seon/agent.cljs:1080` | `[(>= ?dur ?threshold)]` | CLJC built-in |
| `src/seon/agent.cljs:1082` | `[(> ?at ?cutoff)]` | CLJC built-in |
| `src/seon/agent.cljs:1097` | `[(identity ?f-at) _]` | CLJC built-in (identity binding for or-join shape parity) |
| `src/seon/agent.cljs:1099` | `[(> ?f-at ?p-at)]` | CLJC built-in |
| `src/seon/client.cljs:394` | `[(ground :ns) ?kind]` | Datalog built-in (`ground` is a query keyword, evaluated by the engine) |
| `src/seon/client.cljs:397` | `[(ground :fn) ?kind]` | Datalog built-in |
| `src/seon/client.cljs:400` | `[(ground :schema) ?kind]` | Datalog built-in |
| `src/seon/render/default.cljs:107-110` | 4× `[(get-else $ ?e <attr> <default>) ?v]` | Datalog built-in |
| `src/seon/render/default.cljs:134` | `[(identity ?e) ?eid]` | CLJC built-in |
| `src/seon/render/default.cljs:176` | `[(contains? #{:idle :running} ?state)]` | CLJC built-in (set literal — pure data) |

**Verdict on predicates:** every clause runs server-side against the JVM writer's Clojure runtime EXCEPT the one `?->ms` site. That site is trivially fixable: store ms alongside `:inst` (or just compute `?since-ms` in the caller and rewrite the predicate to `[(.getTime ?at) ?ms]` — but that still calls a host method via Datalog, which the engine wouldn't resolve on a Date arg from the wire). The cleanest fix is to **pre-compute ms on the wire's `:in` row, drop the fn binding** — replies-after at agent.cljs:445 already has both inputs available before the query runs.

`get-else`, `ground`, `contains?` are all server-side resolvable — `contains?` against a literal set is a value-level check that the JVM Clojure runtime handles natively. No guest-side closure crosses the wire in any other site.

## Out-of-scope features used by V0

| Feature | Used? | Where | Migration |
|---|---|---|---|
| `:db.fn/cas` (CAS tx fn) | NO | — | — |
| `:db/fn` (registered tx fns) | NO | — | — |
| Lazy entity deep traversal | **shallow only** | agent.cljs:494 `(:seon.agent/sessions a)` then `(last (sort-by :seon.session/at ...))` — one ref hop, sort host-side | If `entity-pull` returns `:seon.agent/sessions` as eagerly-realized maps (component refs naturally pull as nested maps), the guest sort runs on plain data. No change to call sites if the overlay implements `entity` as a thin pull. |
| Tx fns inside tx-data | NO — only literal `[:db/add]`, `[:db/retract e a]`, `[:db/retract e a v]`, `[:db/retractEntity eid]` (built-ins, parsed by writer) | db.cljs:41, agent.cljs:1230, web/serve.cljs:254 | — |
| `d/filter` | NO | — | — |
| `d/with` (speculative tx) | NO | — | — |
| `d/history` / `d/as-of` / `d/since` | NO — explicitly avoided per client.cljs:345 comment ("Query against `@conn`, NOT `(d/history db)`") | — | — |
| `d/datoms` / `d/seek-datoms` / `d/index-range` | NO | — | — |
| `d/touch` (force-realize entity) | NO | — | — |
| Guest-side query fn binding | **YES — 1 site** | agent.cljs:458 — `?->ms = (fn [^js d] (.getTime d))` | Rewrite `replies-after` to compute ms before the query, or store ms as a denormalized attr. ~5 LOC change. |

## Proposed protocol extensions

For each 🟡 row, the minimal protocol delta. Following the existing PROTOCOL.md / WIT conventions (EDN-string in, CBOR or EDN-string out).

```wit
// Add to interface db {}:

/// Eagerly pull an entity by ref. `ref` is an EDN string (eid or lookup-ref).
/// Default selector is `'[*]` with component refs followed to depth 1.
/// Returns EDN string of the realized map, or empty string if not found.
entity: func(ref: string) -> result<string, db-error>;

/// Batched pull. `selector` EDN; `eids` EDN list of eids/lookup-refs.
pull-many: func(selector: string, eids: string) -> result<string, db-error>;

/// Read current schema. Returns EDN of {attr → schema-map}.
schema-read: func() -> result<string, db-error>;

/// Drop a tx subscription previously created via subscribe-tx.
unsubscribe-tx: func(handle: u32) -> result<bool, db-error>;

// Also: relax `pull`'s eid type:
pull: func(selector: string, eid: string) -> result<string, db-error>;
// (was eid: s64; must accept lookup-refs as EDN strings)
```

JVM writer additions (PROTOCOL.md):

- `{"op": "entity", "ref": <edn-string>}` → `{"ok": true, "basis-t": N, "result": <cbor-map>}`. Implementation: `(d/pull @conn '[*] ref)` plus a recursive walk that pulls component refs one level deep. Server-side because component refs are knowable from the schema.
- `{"op": "pull-many", "selector": <edn-string>, "eids": [<edn-string> ...]}` → `{"ok": true, "basis-t": N, "result": [<cbor-map> ...]}`. Direct `d/pull-many` wrap.
- `{"op": "schema-read"}` → `{"ok": true, "basis-t": N, "result": <cbor-map>}`. Direct `(d/schema @conn)`.
- `{"op": "unsubscribe", "handle": N}` → `{"ok": true}`. (Or handle entirely guest-side if the overlay does fan-out from one upstream subscription.)

**Estimated effort per extension:**

- `entity` op: 2-3h (writer handler + walk component refs + CLJS overlay + tests)
- `pull-many` op: 1h (writer handler is one-liner; overlay is straightforward)
- `schema-read` op: 30min (writer handler one-liner; not blocking — guest doesn't call it today)
- `unsubscribe-tx` (wire): not needed if overlay does local fan-out; otherwise 1h
- `pull` eid-as-string: 30min (WIT signature change + EDN-parse on writer side)

Total: **~5-7h of protocol work**.

## Refactor list

V0 sites that need to be updated when datahike-cljs is swapped for the sidecar overlay. Group by reason.

### Reason A — guest-side fn binding in query (out-of-scope)

| Site | Current | Rewrite |
|---|---|---|
| `src/seon/agent.cljs:445-464` (`replies-after`) | Passes `(fn [d] (.getTime d))` as `:in` to convert Date→ms inside Datalog | Compute ms before query; drop `?->ms`. Rewrite predicate to `[(> ?at ?since-inst)]` directly (the JVM-side Clojure compare on java.util.Date works against wire-encoded #inst values). Or store `:seon.message/at-ms` alongside `:seon.message/at` and query on that. |

### Reason B — lazy entity deep access (eager-pull is enough)

| Site | Current | Rewrite |
|---|---|---|
| `src/seon/agent.cljs:493-494` | `(let [a (db/entity {:seon.db/ref [:seon.agent/id agent-id]})] (last (sort-by :seon.session/at (:seon.agent/sessions a))))` | `db/entity` returns eagerly-realized map with `:seon.agent/sessions` already pulled as a vector of component maps. Same code reads same shape — only the wrapper changes. No call-site change. |
| `src/seon/agent.cljs:332-334` (`user-msg-eid?`) | `(= :user (:seon.message/role (db/entity ...)))` | Same — `:seon.message/role` is a top-level scalar attr; eager pull preserves it. No call-site change. |
| All 13 other `db/entity` sites | Same pattern — read 1-2 scalar attrs, or one component-ref vector | No call-site change if overlay returns `(d/pull ... '[*])` with one-level component recursion. |

### Reason C — db-value pinning across multiple queries (basis-t threading)

| Site | Current | Rewrite |
|---|---|---|
| `src/seon/agent.cljs:1029-1099` (the warnings composer) | `(let [db @conn] (db/query {... :db db}) (db/query {... :db db}) ...)` — multiple queries against the same snapshot | The overlay's `query` takes an optional `basis-t`. Compose: get current basis-t once (from any preceding response), thread to all subsequent reads. Sidecar cache already returns basis-t on every reply. ~10 LOC change in the composer. |
| Listener handlers (`build-handler-input` at db.cljs:1199) | Handler input includes `:db` (db-after) and `:db-before` — derefs used to query the snapshot | The pub event already carries `basis-t` and `basis-t-before`. Overlay's handler-input substitutes these for opaque db values; queries take `basis-t` instead. **This is the biggest behavioral change in the overlay** — db handles become basis-t integers. |

### Reason D — unlisten

`src/seon/agent.cljs:372` calls `(db/unlisten! ...)` — works unchanged if the overlay handles fan-out locally.

### Sites that need NO change

- All 28 `db/query` call sites (predicates are server-resolvable except the one `?->ms` site)
- All `db/transact!` call sites (tx-data is plain data; tx-meta auto-merged)
- All `db/pull` call sites (overlay forwards `:op pull`)
- All `db/listen!` call sites (overlay manages local key→handler map; one upstream subscription)
- All `[:db/retractEntity eid]` tx-data forms (built-in; writer parses)

## Verdict

**Achievable: YES — with caveats X/Y/Z.**

- **X.** Add an `entity` op to the protocol (eager pull with one-level component-ref realization). 15 call sites depend on lazy-entity-with-shallow-access; without this op the refactor cost balloons because `db/entity`'s contract leaks into agent code (`(:seon.agent/sessions a)` traversal).
- **Y.** Switch listener handler input from opaque `db` values to `basis-t` integers; queries take optional `basis-t`. The pub event already carries it. ~30 LOC in db.cljs's `build-handler-input` + the agent.cljs warning composer. This is the biggest behavioral seam and the one place "no behavior change" leaks — but **it's the same behavior** modulo "db is identified by an int instead of an object". MVCC semantics are preserved by the sidecar cache.
- **Z.** Refactor the one `?->ms` site (~5 LOC) — the only out-of-scope feature actually used.

**Estimated total effort:**

- (a) Protocol extensions (entity, pull-many, schema-read, eid-as-string, optional unsubscribe): **5-7h**
- (b) CLJS overlay namespace `seon.db.sidecar` exposing `create-database / connect / transact! / q / pull / entity / listen / unlisten` with the same shapes datahike-cljs uses today: **6-10h** (most of it is the listener fan-out + basis-t-threaded reads)
- (c) Refactor V0 call sites (`?->ms` + basis-t threading in warnings composer + handler-input shape in db.cljs): **3-5h**

**Total: ~14-22 person-hours.** Single-day to multi-day depending on how aggressive the test coverage is on the overlay.

**Risks / unknowns:**

1. **The wire round-trip cost of `db/entity`.** Today entity is a sync local call; under the sidecar it's a UDS round-trip (~1.4-19ms cold, sub-µs warm per Phase-2 numbers). The 15 entity sites are mostly on hot paths (per-tx listener, per-turn composer). Mitigation: snapshot cache + batching (pull-many for the warnings composer's fan-in pattern).
2. **`@conn` deref semantics.** Today `@conn` returns a db value; downstream code calls `(d/q query db ...)`. The overlay must either (a) make `@conn` return a basis-t-tagged thunk that the overlay's `q` recognizes, or (b) accept that "db value" becomes "current snapshot at call time". Option (b) is simpler and matches what the warnings composer already needs (basis-t threading).
3. **Lookup-ref support in `pull` WIT signature.** Trivial fix (string instead of s64), but worth verifying the Phase-3 guest's existing `pull` calls don't hard-code `s64` upstream.
4. **`get-else`, `ground`, `contains?` server-side parity.** These are Datalog/CLJC primitives; the JVM writer's `d/q` resolves them natively. No risk — listed for completeness.
5. **Component-ref pull depth.** The overlay's `entity` shim follows component refs to depth 1. If any agent code expects depth 2+ (none observed in this audit), it breaks silently. Add a depth-2 spot-check in the overlay's tests.

## References

Cited call sites (absolute paths):

- `/Users/sean/src/seon/src/seon/db.cljs:853` — `d/transact!` (arg-map shape)
- `/Users/sean/src/seon/src/seon/db.cljs:1143` — `d/q` via wrapper
- `/Users/sean/src/seon/src/seon/db.cljs:1151` — `d/pull` via wrapper
- `/Users/sean/src/seon/src/seon/db.cljs:1159` — `d/entity` via wrapper
- `/Users/sean/src/seon/src/seon/db.cljs:1199-1205` — `build-handler-input` (db-after/db-before in handler shape)
- `/Users/sean/src/seon/src/seon/db.cljs:1240` — `d/listen` (sole call site)
- `/Users/sean/src/seon/src/seon/db.cljs:1278` — `d/unlisten` (sole call site)
- `/Users/sean/src/seon/src/seon/agent.cljs:451-462` — `replies-after` (the `?->ms` guest-side fn site)
- `/Users/sean/src/seon/src/seon/agent.cljs:493-494` — `:seon.agent/sessions` shallow ref-hop traversal
- `/Users/sean/src/seon/src/seon/agent.cljs:1029-1099` — warnings composer (basis-t threading site)
- `/Users/sean/src/seon/src/seon/agent.cljs:1052-1058`, `:1076-1083`, `:1091-1099` — three queries with `>`, `>=`, `identity` predicates
- `/Users/sean/src/seon/src/seon/render/default.cljs:107-110` — `get-else` (4 clauses)
- `/Users/sean/src/seon/src/seon/render/default.cljs:134`, `:176` — `identity`, `contains?` predicates
- `/Users/sean/src/seon/src/seon/client.cljs:389-400` — `ground` (3 clauses)
- `/Users/sean/src/seon/src/seon/web/serve.cljs:254-255` — `[:db/retractEntity eid]` tx-data form
- `/Users/sean/src/seon/src/seon/db/datahike/conn_process.clj:93-264` — JVM writer datahike surface (database-exists?, transact, schema, pull-many, release)
- `/Users/sean/src/seon/pod-host/sidecar-poc/PROTOCOL.md` — baseline protocol
- `/Users/sean/src/seon/pod-host/sidecar-poc/rust-host/wit/sidecar.wit` — baseline WIT
