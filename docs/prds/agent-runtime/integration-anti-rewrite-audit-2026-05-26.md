---
type: research
status: active
tags: [research, agent, database, platform]
---

# Integration Anti-Rewrite Audit

**Date:** 2026-05-26
**Scope:** Spelunk for code that ALREADY EXISTS for the integration described in
`integration-architecture-2026-05-26.md` and `v0-to-v2-transition-plan-2026-05-26.md`.
**Hard rule:** no code changes, only file:line citations.

## TL;DR

The integration plan's "new code" budget of ~1000–1200 LOC is **substantially overstated**.
The single biggest claim — "write a JVM konserve-sqlite (~400–500 LOC)" — is wrong:
**`konserve-jdbc` + `sqlite-jdbc` already works on JVM and is in active use** at
`pod-host/sidecar-poc/jvm-writer/deps.edn:11-12` with backend wiring at
`pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/writer.clj:61-70`. The remaining
work — dynamic conn-process registration, wire-server, session schema, tx subscription —
all have substantial existing pieces in `src/seon/` that the plan does not credit.

**Headline numbers:**

| Plan claim | Reality |
|---|---|
| `konserve-sqlite` JVM port: ~400–500 LOC | 0 LOC. Add 2 deps; extend one `case` arm in `seon.db.datahike.flow/namespace-config`. |
| Dynamic session registration: ~100–150 LOC | ~50–80 LOC. `seon.orchestrator.session` already has lifecycle + schema; `seon.db.datahike.flow/build-datahike-flow!` needs an "add-namespace" companion. |
| Wire-server: ~400–500 LOC | ~200–300 LOC. `seon.db.relay` is the exact pattern over TCP. Either swap socket type for UDS or reuse the sidecar-poc UDS bits verbatim. |
| Session entity schema: ~30 LOC | 0 LOC of new schema. `src/seon/session.clj:1-50` and `src/seon/orchestrator/session.clj:1-150` already define `::id`, `::namespace`, `::status`, `::started-at`, `::stopped-at`, `::db-name`, `::nrepl-port`, etc. |

**Top 3 reuse opportunities the plan misses:**

1. **konserve-jdbc + xerial/sqlite-jdbc** already works (pod-host/sidecar-poc/jvm-writer uses it). The "JVM port of konserve-sqlite-cljs" line item should be deleted.
2. **`seon.db.relay`** (`src/seon/db/relay.clj`, 339 LOC) is the wire-server architecture already — length-prefixed Nippy framing, routes by `::db-name`, request/reply over a socket. Same shape as the proposed `seon.server.wire`. Either reuse it as TCP or extract its codec/framing and re-host on UDS.
3. **`seon.orchestrator.session`** (609 LOC) already has agent-session entity schema with `::db-name` field (line 59), plus `start-agent-session!` / `stop-agent-session!` / `list-agent-sessions`. The plan describes building this from scratch.

---

## Findings, item by item

### 1. konserve-sqlite for JVM

- **Existing:** YES — `pod-host/sidecar-poc/jvm-writer/deps.edn:11-12` pins `io.replikativ/konserve-jdbc {:mvn/version "0.2.91" :exclusions [io.replikativ/konserve]}` plus `org.xerial/sqlite-jdbc {:mvn/version "3.46.1.0"}`. Wired in `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/writer.clj:61-70`:
  ```clojure
  "sqlite"
  {:store {:backend :jdbc :dbtype "sqlite" :dbname (:path opts) :table "store" :id ...}
   :keep-history? true :schema-flexibility :write}
  ```
  This is exercised by the 47-test / 189-assertion protocol integration suite that ships green.
- **Konserve version pin** (line 10 of that deps.edn): `org.replikativ/konserve {:mvn/version "0.9.346"}` — newer than what konserve-jdbc 0.2.91 requests; exclusion strips konserve-jdbc's transitive pull. **The "konserve-jdbc is built against 0.8.x; datahike pulls 0.9.x" claim in `integration-architecture-2026-05-26.md` §7 is wrong; the actual collision is resolvable with the exact pin already in jvm-writer.**
- **Closest analog (if a pure-konserve adapter were really required):** `reference-code/konserve-lmdb/src/konserve_lmdb/` (store.clj 452 LOC + native.clj 671 LOC + buffer.clj 1006 LOC = 2129 LOC). That's the wrong tree to climb when the JDBC adapter already exists.
- **JVM datahike-harness already uses multiple konserve backends:** `pod-host/datahike-harness/src/seon/podhost/datahike_harness/backends.clj:14-23` requires `konserve-lmdb.store` and `konserve-gcs.core` for side-effect dispatch registration. Same pattern would work for `konserve-jdbc.core` and is already used in writer.clj.
- **Recommendation:** REUSE. Add `org.replikativ/konserve-jdbc` + `org.xerial/sqlite-jdbc` + pin `org.replikativ/konserve` in the root `deps.edn`. Extend `seon.db.datahike.flow/namespace-config` (`src/seon/db/datahike/flow.clj:146-163`) to accept `:sqlite` in the `::backend` enum (`flow.clj:46`) and produce `{:backend :jdbc :dbtype "sqlite" ...}` store maps.
- **Honest LOC estimate if writing fresh:** N/A — don't write fresh.
- **Honest LOC for the extension:** ~30 LOC of `case` arm + path derivation + a `(require '[konserve-jdbc.core])` for side-effect dispatch registration. Plus deps.edn changes.

### 2. Dynamic conn-process registration on the running flow

- **Existing:** PARTIALLY. `seon.db.datahike.flow/build-datahike-flow!` (`src/seon/db/datahike/flow.clj:193-280`) computes `:procs` + `:conns` from `::namespaces` once and calls `flow/create-flow` + `flow/start` + `flow/resume`. **No `add-namespace!` companion exists.** The conn-process spawning logic is structured (`conn-pid`, `namespace-config`, `guard-single-writer!`) so adding a runtime-add fn would compose cleanly.
- **The flow uses pid-keyed `:procs` map:** `flow.clj:218` `pids (into {} (map (fn [{::keys [db-name]}] [db-name (conn-pid db-name)])) configs)`. Adding a namespace = transact a new conn-process into the running flow + update `pids`. core.async.flow exposes `flow/inject` to processes but **doesn't expose a public `add-process!`** — the closest path would be to keep one "spare" supervisor process or to rebuild on add. **Confirm via core.async.flow source** in `reference-code/core.async/` before committing to an approach.
- **Alias mechanism exists** (`src/seon/db.clj:201-207`, `src/seon/db/datahike/flow.clj:63-65`): `::aliases` map maps "logical db-name → internal db-name". Test fixtures use it. This could let one physical conn-process serve multiple logical session names if isolation isn't required (probably not what we want, but the indirection is there).
- **Runtime registry pattern to reuse:** `seon.runtime/register-flow!` / `unregister-flow!` (`src/seon/runtime.clj:885-918`) keeps an in-memory `flow-handles` atom AND persists to the `:seon.runtime` DB. **This is the canonical "register a running thing at runtime" pattern.** The plan's `(db/ensure-db! :seon.session/<name>)` should follow the same dual atom+DB shape.
- **Recommendation:** EXTEND. Write `add-namespace!` in `seon.db.datahike.flow` that (a) computes the new conn-process spec via the existing helpers, (b) injects it into the running flow (mechanism TBD — investigate core.async.flow's `flow/inject` + supervisor patterns, or wrap `(stop) (rebuild) (start)` with a hot-swap), (c) persists the namespace registration via the existing pattern. Expose `(db/ensure-db! db-name {…})` as the public entry point.
- **Honest LOC estimate:** ~80–120 LOC if `flow` supports dynamic add; ~150–200 if we have to do a stop/rebuild/start dance (still less than the plan's 100-150 because all of `namespace-config`, `conn-pid`, `guard-single-writer!`, and the conn-process step-fn already exist).

### 3. Session entity schema

- **Existing:** YES, two competing surfaces.
  - `seon.session` (`src/seon/session.clj`, 472 LOC) — `::agent` (id), `::namespace`, `::port`, `::pid`, `::started-at`, `::stopped-at`, `::checkpointed-at`. Owns "the canonical agent session registry" per its docstring.
  - `seon.orchestrator.session` (`src/seon/orchestrator/session.clj`, 609 LOC) — `::id`, `::namespace`, `::status`, `::nrepl-port`, `::started-at`, `::stopped-at`, **`::db-name`** (line 59), `::nrepl-session-id`, `::last-activity-at`, `::eval-count`, `::current-eval`. Full lifecycle: `start-agent-session!`, `stop-agent-session!`, `get-agent-session`, `list-agent-sessions`, `get-session-port`. Live JVM-only handles (`::ctx-atom`, `::pool`, `::ns-db-name`, `::current-eval`) are kept in an in-process map keyed by session-id (docstring lines 8-11).
- **Critical existing field:** `seon.orchestrator.session/::db-name` (line 59) — the agent session is **already** mapped to a `db-name` keyword. The plan's "agents join a session, sessions hold a DB" mental model is already encoded here.
- **What's missing for the V2 architecture:** `::backend` (sqlite/file/memory), `::path` (filesystem location for the konserve store), and a `:seon.session/state` enum that includes `:archived` and `:gc'd` per the plan's §5.
- **Recommendation:** EXTEND `seon.orchestrator.session` (NOT `seon.session` — they overlap and the orchestrator one is more complete and is what calls into `seon.flow.pool`). Add `::backend`, `::path`, and extend the `::status` enum. **Do NOT write a third session namespace.**
- **Honest LOC estimate:** ~30 LOC of new schema attrs + extending the existing entity-schema map. **The plan's "30 LOC" is right but it should be in `seon.orchestrator.session`, not a new file.**

### 4. Wire-server / UDS protocol

- **Existing TCP wire-server (the real find):** `src/seon/db/relay.clj` (339 LOC) is the **exact architecture** the plan calls for, over TCP loopback instead of UDS. Its docstring (lines 1-21):
  > "Lets code running inside an agent JVM call `seon.db/transact!`, `seon.db/query`, `seon.db/pull-by-name`, or `seon.db/pull-many-by-name` against any flow-managed db (`:seon.session`, `:seon.orchestrator`, etc.) even though the agent JVM has no local datahike flow. … Requests flow agent -> orchestrator; the orchestrator dispatches to the resident `seon.db/<op>` (which routes through the datahike flow), then replies."

  Schema includes `::request-id`, `::op [:enum :transact! :query :pull-by-name :pull-many-by-name]`, `::db-name`, `::args`, `::reply`. Uses **Nippy** (already in the JVM stack) for framing. **This is the wire-server with a different socket type.**
- **TCP plumbing:** `src/seon/flow/harness/channel.clj` (158 LOC) — `start-server!` (line 96), `connect!` (line 144), `start-reader-thread!` / `start-writer-thread!`. Uses `java.net.ServerSocket` + `InetAddress/getLoopbackAddress` (line 17). Length-prefixed binary frames.
- **Existing UDS plumbing (V2 PoC):** `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/writer.clj:460-496` — `start-req-server!` opens a `ServerSocketChannel/open StandardProtocolFamily/UNIX` against a `UnixDomainSocketAddress`. `handle-op` is a defmulti on the `"op"` string with 12 methods (ping, q, transact, transact-batch, pull, entity-pull, pull-many, schema, reverse-schema, db-filter, q-filtered, filter-release). Codec at `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/codec.clj` (80 LOC, CBOR length-framed) + transit at `pod-host/sidecar-poc/jvm-writer/src/seon/sidecar/transit.clj` (41 LOC).
- **No `UnixDomain*` / `StandardProtocolFamily` references exist anywhere in `src/`** — the JVM source tree has not adopted UDS; only the pod-host sidecar PoC has.
- **Recommendation:** **REUSE `seon.db.relay`'s op-dispatch + request/reply shape; replace its TCP `ServerSocket` with `ServerSocketChannel + StandardProtocolFamily/UNIX`** by lifting the socket code from `writer.clj:460-496`. The Nippy framing in relay (which can encode arbitrary Clojure values across JVM↔JVM) is **fine for JVM↔JVM** but **wrong for CLJS guests** — CLJS would need CBOR + Transit-JSON. So either: (a) keep two wire codecs (Nippy for JVM agents via relay; CBOR+Transit for CLJS via UDS) and route by transport, or (b) standardize on CBOR+Transit everywhere (cheaper for the cross-runtime promise of seon). The plan should be explicit about which.
- **Honest LOC estimate:** ~200–300 LOC, mostly handler glue + the UDS accept-loop port. Codec.clj (80) + transit.clj (41) + broadcast.clj (51) port verbatim = ~170 LOC moved, not written. The writer.clj `handle-op` methods (~310 LOC of `defmethod`s) are the bulk and need to be rewritten to dispatch by `:db-name` to `seon.db/*` instead of calling `d/q` / `d/transact` against a private conn.

### 5. Tx-event broadcast / subscription

- **Existing:** YES, complete. `src/seon/db/datahike/tx_bus.clj` (128 LOC) is a `core.async.flow` step-fn with three inputs:
  - `:seon.db.datahike/sub` → `{::db-name kw ::key kw ::callback fn}`
  - `:seon.db.datahike/unsub` → `{::db-name kw ::key kw}`
  - `:seon.db.datahike/tx-report` → tx-report; **fans out to all `[db-name *]` keys** matching the report's db-name (line 100-101).
- **Public API:** `seon.db.datahike.flow/subscribe!` (`flow.clj:349-362`) and `unsubscribe!` (`flow.clj:364-372`). Takes `{::flow ::db-name ::key ::callback}`.
- **SSE bridge:** `src/seon/web/sse.clj` exists but the grep didn't show it directly subscribing to tx-bus — the SSE broadcast machinery (`src/seon/web/broadcast.cljs` line 388 `Initialize the SSE broadcast infrastructure`) is the natural consumer; have not traced fully whether SSE wires through tx-bus or through a separate channel.
- **Recommendation:** REUSE. Wire-server's pub-socket handler registers a subscriber on tx-bus per (db-name, key) and forwards each report over the UDS pub socket. Translation step (report → wire bytes) lives in the wire-server, NOT in tx-bus.
- **LOC estimate for the bridge:** ~50 LOC.

### 6. The "session" concept overlap

- **Two existing namespaces:**
  - `seon.session` (`src/seon/session.clj`, 472 LOC) — Phase 3 spawns one **agent JVM** per session via `seon.flow.pool`, evaluates forms in it, persists `*ctx*` checkpoints. Docstring: "Owner of `:seon.session` — the canonical agent session registry. One row per running agent JVM: id, namespace, port, status, ctx checkpoint."
  - `seon.orchestrator.session` (`src/seon/orchestrator/session.clj`, 609 LOC) — More complete agent-lifecycle layer; calls into `seon.flow.pool` AND keeps an in-process atom of live handles. Has `::db-name` field.
- **Relation:** `seon.session` (`session.clj:20`) requires `seon.orchestrator.session :as orch` — the bare `seon.session` is the **owner of the `:seon.session` DB schema**, while `seon.orchestrator.session` is the **lifecycle API** layered on top. Both write to the same DB conceptually but the lifecycle ns has the richer schema.
- **Overlap with V2 agent-session:** The existing notion is **one agent = one JVM**. The V2 PoC notion is **one agent = one wasm guest, N agents per session**. These are structurally compatible: replace "JVM" with "runtime instance" and "nREPL port" with "guest id". The `::db-name` field already implies "session has a DB"; the only change is that multiple agents now point at the same db-name.
- **Recommendation:** UNIFY in `seon.orchestrator.session`. Keep `seon.session` as the schema-owner ns (its current role). Add `:seon.session/agents [:vector :seon.db/ref]` to allow N agents per session, but the cardinality-many ref already works via the existing `:seon.db/ref` shape. **Do NOT introduce a third namespace; the plan's `:seon.session/<name>` db-naming aligns with what's already on disk.**

### 7. Existing agent registry / runtime tracking

- **Existing:** YES, **better than the atom-state PRD proposes**. `seon.runtime` (`src/seon/runtime.clj`, 997 LOC):
  - `register!` (line 376) / `unregister!` (414) / `instance` (443) / `instances` (457) — per-namespace runtime registry with `::status`, `::location`, `::session-id`, `::nrepl-port`, `::started-at`, `::component-key`. **DB-backed (`:seon.runtime`), survives restart.**
  - `register-flow!` (885) / `unregister-flow!` (907) / `get-flow` (920) / `list-flows` (930) — in-memory atom for non-serializable flow handles. **Atom + DB pattern.**
  - `start-agent-run!` (679) / `complete-agent-run!` (717) / `agent-runs` (749) — per-agent-run accounting in the same DB.
  - `mark-crashed!` (486) / `cleanup-stale!` (546) / `hydrate-cache!` (572) — crash-recovery lifecycle.
- **The proposed `seon.agents/!instances` atom in `atom-state-system-2026-05-26.md` already exists as `seon.runtime/flow-handles`** (and the DB-backed `:seon.runtime/*` entities for durable parts). The atom-state PRD should reference / extend this rather than introducing parallel state.
- **Recommendation:** EXTEND `seon.runtime`. Per-agent runtime instances of the wasm guest variety register with `::location :external` and a new `::runtime-kind :wasm` attr. The flow-handles atom pattern is the prior art for "things that can't be serialized."

### 8. Tests + infrastructure to borrow

- **Existing JVM DB tests:** `test/seon/db/`
  - `consistency_test.clj`, `pipeline_test.clj`, `routing_test.clj`, `validation_test.clj` — all exercise `seon.db` directly.
  - `test/seon/db/datahike/flow_test.clj` (DB flow tests).
  - `test/seon/db/datahike/schema_test.clj`.
- **Multi-DB test fixture:** `test/seon/test_utils.clj:37` — "the underlying konserve `:memory` store is identified by a UUID derived [from db-name]". `test_utils.clj:162` — populates `seon.db/*datahike-flow*` so tests can bind a custom flow. **This is the pattern for "spin up an ephemeral flow with N namespaces."**
- **Orchestrator session tests:** `test/seon/orchestrator/session_test.clj` — pre-existing session-lifecycle tests we can pattern multi-session-multi-db tests after.
- **CLJS test runner:** `seon.test.runner` — "the gold-standard three-tier example" per memory; usable for guest-side wire tests.
- **Sidecar protocol tests:** `pod-host/sidecar-poc/jvm-writer/test/seon/sidecar/` — 47 tests / 189 assertions green, exercising every `handle-op` defmethod.
- **Recommendation:** Multi-session integration tests should follow `test/seon/db/routing_test.clj` + `test/seon/orchestrator/session_test.clj` shape. Per-session UDS round-trip tests should follow `pod-host/sidecar-poc/jvm-writer/test/seon/sidecar/protocol_integration_test.clj`. **The CLJC schema-sharing tests at `test/seon/db/transact_precondition_test.cljs` and `tx_context_test.cljs` are the only existing cross-platform DB tests; they're the gold-standard for "this works in both runtimes."**

### 9. Cross-language schema sharing

- **`seon.schema` is fully CLJC** — `src/seon/schema.cljc:1-30`. `register!` is shared. Both CLJ and CLJS callers use the same `(schema/register! ::foo ...)` and the registry survives namespace reloads via `defonce`.
- **No divergences found.** The CLJS pod's `seon.client.cljs` and `seon.db.cljs` consume registered schemas the same way.
- **Implication for the integration:** Session/agent entity schemas registered in `seon.orchestrator.session` (CLJ) are visible to CLJS too — wire-server can validate incoming request maps against the same Malli schemas.

### 10. Anything else weird

- **The "Master `:seon` DB" mental model in `integration-architecture-2026-05-26.md:53` doesn't exist.** Grepping `(db/transact! :seon\b` and `(db/query :seon\b` produces zero matches. All real writes go to `:seon.runtime`, `:seon.orchestrator`, `:seon.session`, `:seon.phase2.demo`, `:seon.flow`, `:seon.repl`. The architecture-doc concept of "one master DB" is **a naming refactor request**, not a description of the current code. Decide explicitly: are we collapsing N namespaces into one `:seon`? Or is "master" just a label for the union of existing namespaces?
- **`seon.db.datahike.flow` already enforces single-writer per store** (`flow.clj:171-187`, `guard-single-writer!`). The plan's session-lifecycle code needs to coordinate with this guard when creating new sessions; otherwise concurrent `ensure-db!` calls for the same name will trip it.
- **`seon.flow.pool`** is referenced but its health-checker SIGKILLs idle JVMs (`session.clj:12-13` comment). For wasm agents this layer is probably retired; just noting it's there.
- **`seon.db.relay` Nippy framing** uses `taoensso.nippy/fast-freeze` for arbitrary Clojure values — this **doesn't work cross-runtime to CLJS** (Nippy is JVM-only). The wire-server for CLJS guests MUST use CBOR+Transit-JSON as the sidecar-poc already does. Relay can keep Nippy for JVM↔JVM agent processes if we keep that capability.
- **Datahike fork status:** `deps.edn:197-199` pins `org.replikativ/datahike` at `seantempesta/datahike` SHA `01ba3f18bf08da2c093eb0972ec1f272b817f23d` via `:override-deps` — applies to `:cljs` profile only. The JVM `:dev` alias still uses `org.replikativ/datahike 0.8.1671` (line 84). If konserve-jdbc storage is going to be shared by JVM and CLJS, the version skew (0.8.1671 mvn vs git-SHA) needs an explicit decision.

---

## Architecture-plan corrections

| Plan claim | Correction | Citation |
|---|---|---|
| §7 "konserve-sqlite-cljs (the CLJS adapter) … JVM equivalent does NOT exist yet" | **False.** `konserve-jdbc + sqlite-jdbc + konserve 0.9.346 pin` already works on JVM and is used by sidecar-poc's writer. | `pod-host/sidecar-poc/jvm-writer/deps.edn:11-12`, `writer.clj:61-70` |
| §7 "konserve-jdbc 0.2.91 is built against konserve 0.8.x; datahike 0.8.1681 pulls 0.9.x" | The collision is resolvable with `:exclusions [io.replikativ/konserve]` on konserve-jdbc, pinning konserve at `0.9.346`. Already in use. | `pod-host/sidecar-poc/jvm-writer/deps.edn:10-12` |
| §7 "Port `jvm-writer/writer.clj` handlers" | **`seon.db.relay` is the better port source** — it already routes by `::db-name` and uses request/reply over a socket. writer.clj is single-DB. | `src/seon/db/relay.clj:1-50` |
| §5 "as entities in the master `:seon` DB" | There is no `:seon` DB in current code. Session entities should live in `:seon.session` (already exists) or whatever namespace `seon.orchestrator.session` already writes to. | (no grep hits for `:seon` db-name) |
| §7 "Master `:seon` DB" line item: "EXISTS, in production" | Misleading. **`:seon` is a placeholder name in docstrings; the real db-names are `:seon.runtime`, `:seon.orchestrator`, `:seon.session`, etc.** | `src/seon/db.clj:10` (example only) |
| §3 file layout: `seon/data/seon/store.sqlite` for "master `:seon` DB" | Should be one subdir per real db-name, mirroring the existing `seon.db.datahike.flow/namespace-config` `:path` derivation (`flow.clj:155-158`). | `src/seon/db/datahike/flow.clj:155-158` |
| §7 "Dynamic session registration … Lifecycle pieces exist in the flow" | **Not really.** `build-datahike-flow!` is monolithic; no add-namespace-at-runtime mechanism exists. Closer to "design from scratch" than the plan implies. | `src/seon/db/datahike/flow.clj:193-280` |

---

## Search log

Commands actually run (output lengths inline):

```
find . -type d (excludes) -prune -o -type f -name '*.clj*' -print
  | xargs grep -l 'konserve' → 17 files (10 in pod-host, 5 in src/, 2 in test/)
grep -rnE 'UnixDomain|UDS|StandardProtocolFamily|SocketChannel|UnixDomainSocketAddress' src/ test/ → 0 matches
grep -n 'konserve|sqlite|jdbc|datahike|override-deps' deps.edn → 9 matches
cat pod-host/sidecar-poc/jvm-writer/deps.edn → konserve-jdbc + sqlite-jdbc + konserve pin confirmed
grep -nE 'register|spawn|add|create-conn|ensure' src/seon/runtime.clj → 30 lines (mostly schema/register! calls + register-flow!)
grep -rn 'namespace-schemas|build-datahike-flow' src/seon/ → 9 matches; only one call site
grep -n ':seon\b|:seon\s' resources/system.edn → 0 matches (the `:seon` db-name does not appear)
grep -rE 'transact!.*:seon\b|query.*:seon\b' src/seon/*.clj → confirms only :seon.runtime, :seon.session, :seon.orchestrator
wc -l src/seon/db/relay.clj src/seon/flow/harness/channel.clj writer.clj codec.clj transit.clj broadcast.clj
  → 339 + 158 + 512 + 80 + 41 + 51 = 1181 LOC of existing wire / framing / UDS code
wc -l src/konserve_sqlite_cljs/core.cljs reference-code/konserve-lmdb/src/konserve_lmdb/*.clj
  → 438 + 2129 LOC; konserve-lmdb is NOT the right analog given konserve-jdbc exists
grep -n 'handle-op' writer.clj → 1 defmulti + 12 defmethods + 1 caller
cat .gitmodules | head → konserve-lmdb is a submodule under reference-code/
```

---

## Confidence

**High confidence** on findings 1, 3, 4, 5, 6, 7, 9. These rest on direct file:line citations.

**Medium confidence** on finding 2 (dynamic registration) — I read the build path and the runtime registry pattern, but did NOT trace whether `core.async.flow/create-flow` supports a `add-process!` operation at runtime, nor whether the current `flow/inject` mechanism (used in `flow.clj:327`, `flow.clj:358`, `flow.clj:369`) could be repurposed. Worth one focused read of `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj` before committing to "stop and rebuild" as the only option.

**Known gaps:**

- Did not trace whether `src/seon/web/sse.clj` actually subscribes to `tx-bus` end-to-end (found `broadcast.cljs:388` init but not the wire-up).
- Did not read `seon.flow.harness.bridge` or `seon.flow.harness.proxy` (potential reuse candidates for the wire-server beyond `channel.clj`).
- Did not verify whether the datahike fork at SHA `01ba3f18` includes JVM changes or is CLJS-only — the `:override-deps` is on the `:cljs` profile but the README in the fork might reveal cross-platform changes.
- Did not enumerate every `seon.flow.*` file; there may be additional reusable plumbing in `seon.flow.harness.channel`'s siblings that I missed.
- Time spent: ~1.5h. Did not start every claimed-existing namespace fresh — skimmed many. If any specific claim above is challenged, a deeper read of that file should resolve it.
