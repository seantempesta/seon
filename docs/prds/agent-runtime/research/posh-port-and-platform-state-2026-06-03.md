---
type: research
status: draft
tags: [research, agent, database, flow, architecture]
---

# Posh port + V2 platform-track state (2026-06-03)

> **Terminology note.** This is a point-in-time research artifact; it predates the
> canonical [glossary](../glossary.md). Where it says *scout* read *subagent/agent*,
> *orchestrator* read *agent*, *read-set* read *patterns*, *session* read *the
> database*. Verbatim external quotes keep their original words.

**Branch:** `feature/agent-runtime`
**Posh source:** vendored submodule at `reference-code/posh/`
**Author of deliverable:** research agent, one pass over the live code + docs.

This file does two jobs. **Job 1** establishes the real current state of the V2 /
platform track (the system we are migrating to). **Job 2** assesses porting Posh's
reactive engine into that architecture and what we should optimize. Read Job 1
first; every Job-2 feasibility answer cites it.

---

## TL;DR

- The V2 platform substrate is **real and green for the wire/session layer**, but
  **has no reactive engine of any kind**. `src/seon/server/` is a single-JVM,
  single-datahike-conn-per-session wire server with an imperative tx broadcast.
  69 deftests / 234 assertions across `test/seon/server/*` are green (close to the
  reported "61/237"; counts drift as tests are added — facts seed added 7
  deftests).
- **The broadcast is raw datoms, fired imperatively inside the `transact` handler**
  (`wire.clj:276`), NOT via datahike's `listen!`. Every subscriber gets every
  commit's full `tx-data` and re-derives locally. There is no server-side notion
  of "which standing query changed."
- **Multi-session exists and is green; multi-cluster shared-DB grouping does NOT
  exist.** `seon.server.session` is a flat `{db-name -> conn}` registry plus a
  flat `{agent-id -> db-name}` index. N agents can share one db-name (collaboration)
  or each get their own (isolation). There is no "cluster" entity, no DB-group, no
  population concept — that is design-only (multi-agent-design-2026-05-27.md).
- **No scout / summary / agent / fn entities are persisted on the server side.**
  The program-graph entity machinery (`:seon.fn`, `:seon.scout`, summaries) lives
  in the **V1 CLJS pod** (`src/seon/*.cljs`, `src/seon/code.cljc`), not in
  `src/seon/server/`. The server is a dumb datahike pipe; it knows datoms, not
  scouts.
- **Posh port verdict: feasible and a strong fit, host-side, as a single
  subscription manager run inside a real datahike `listen!` callback** — but it
  requires building the thing the platform conspicuously lacks (the reactive
  engine), and it requires switching the broadcast from "ship raw datoms" to "ship
  which scout-queries changed + their new results." That is a strict architectural
  win for the scout/orchestrator topology and a strict change to the current wire
  contract. Details + risks in Job 2.

---

## Job 1 — Current state of the V2 / platform track

### 1.1 Directory reality (verified with `find`/`ls`)

The recent renaming is in place:

- `client-runtime/` — host side. `client-runtime/host/` (Rust + WIT:
  `host/wit/db.wit`, `host/src/`), `client-runtime/bench/`, `client-runtime/docs/`.
  **`CUTOVER.md` lives at `client-runtime/docs/CUTOVER.md`** (NOT
  `pod-host/sidecar-poc/CUTOVER.md` — that path is stale; `pod-host/sidecar-poc/`
  no longer exists, `pod-host/` now holds only `datahike-harness`,
  `libdatahike-cljs`, `pod-build`, `wasm-tauri`).
- `guest-cljs/` — the CLJS guest. `guest-cljs/src/seon/client_runtime/db.cljs`
  (the wire-client `seon.client-runtime.*` ns), `guest-cljs/src/seon/client_runtime/wit.cljs`,
  plus `guest-cljs/src-overlay/seon/{db,eval,repl}.cljs` (the overlay shims that
  replace V1 in-process namespaces).
- `src/seon/server/` — the JVM wire server, `seon.server.*` namespaces (7 files).

So the anchor from memory `test/seon/server/* = 61 tests/237 assertions green`
maps to the live `src/seon/server/` + `test/seon/server/`. Current count: **69
deftests / 234 `is`/`are` assertions** in `test/seon/server/` (9 test files).
The delta from 61/237 is the `facts_test.clj` (7 deftests) and `store_test.clj`
additions landing since that anchor was recorded.

### 1.2 What is BUILT and green vs DESIGNED-only

| Capability | State | Evidence |
|---|---|---|
| JVM wire writer, one datahike conn, UDS req socket | **green** | `wire.clj:450` `start-req-server!`; `-main` opens one conn (`wire.clj:490-502`) |
| CBOR control envelope + Transit-JSON value payloads | **green** | `wire.clj:5-12`, `transit.clj`, `codec.clj` |
| Ops: `ping q transact transact-batch pull entity-pull pull-many schema reverse-schema db-filter q-filtered filter-release` | **green** | `handle-op` defmethods `wire.clj:194-438` |
| `as-of`/basis-t time travel on reads | **green** | `resolve-db-with-basis-t` `wire.clj:181`; every read op threads `basis-t` |
| Filtered-db handles (`d/filter` + registry) | **green** | `wire.clj:173-179, 410-438` |
| Pub-socket fanout (raw tx events) | **green** | `broadcast.clj`; fired at `wire.clj:276` |
| Per-session conn registry (`{db-name -> conn}`) | **green** | `session.clj:134` `!registry`, `ensure-db!`/`remove-db!`/`get-conn`/`list-sessions` |
| Agent→session index (`{agent-id -> db-name}`) | **green** | `session.clj:138` `!agents`, `register-agent!`/`resolve-agent` |
| Multi-session isolation smoke (2×2, 3×1) | **green** (PoC) | CUTOVER.md "Phase PF" |
| N=3 multi-agent stress, 300s, 0 errors | **green** (PoC) | CUTOVER.md "Phase D" |
| Facts knowledge base + seed | **green** | `facts_test.clj`, store seed |
| Rust host snapshot cache + tx broadcast relay + batcher | **green** (PoC) | CUTOVER.md |
| wasm32-wasip2 CLJS guest end-to-end | **green** (PoC) | CUTOVER.md "Phase C" |
| Guest-side `listen!`/fan-out shim | **green** | `guest-cljs/src/seon/client_runtime/db.cljs:219-258` |
| **Reactive engine / standing-query wake / which-query-changed** | **does not exist** | no `d/listen!` anywhere in `src/seon/server/`; broadcast is unfiltered |
| **Multi-cluster / shared-DB agent groups** | **design-only** | flat registries only; "cluster" appears in no server code |
| **Scout / summary / agent / fn entities persisted server-side** | **does not exist** | program-graph entities are V1-pod-side (`src/seon/*.cljs`), not server |
| Real LLM-driven agent turn inside a guest | **red** (blocking) | CUTOVER.md |
| LLM HTTP capability through WIT | **red** (blocking) | CUTOVER.md |

### 1.3 The wire protocol shape (cited)

**Control envelope** is a CBOR map with string keys; **value payloads** (query/pull
results, tx-data values, tx-meta, tempids, args, selectors, eids) are Transit-JSON
strings inside the envelope (`wire.clj:5-12`). **Datom wire shape** is
`[e a-transit v-transit t op]` — `a` and `v` are Transit-JSON strings, `e`/`t` are
ints, `op` is bool (`wire.clj:107-117` `datom->wire`).

A `transact` response carries both structured fields and a single `"payload"`
Transit blob (`wire.clj:239-263` `ok-response-from-report`):

```clojure
(cond-> {"basis-t" bt "basis-t-before" bt-before
         "tempids" (T tempids) "tx-data" wire-data "tx-meta" (T tx-meta)
         "datoms-added" added "datoms-retracted" retracted
         "payload" (T (cond-> {:basis-t bt :basis-t-before bt-before ...}))}
  request-id (assoc "request-id" request-id))

```

**The broadcast event** is built per-commit and fired *after* the commit returns,
synchronously, on the writer thread (`wire.clj:265-277`):

```clojure
(defmethod handle-op "transact" [conn req]
  (let [... report (d/transact conn tx*) ...
        event (ok-event-from-report r)]
    (try (bcast/broadcast! event) (catch Throwable _))   ; <-- after commit
    (ok (ok-response-from-report r))))

```

`ok-event-from-report` (`wire.clj:227-237`):

```clojure
{"event" "tx" "basis-t" bt "basis-t-before" bt-before
 "db-name" "default" "tx-data" wire-data
 "datoms-added" added "datoms-retracted" retracted
 "tx-meta" (T tx-meta)
 ;; + optional "request-id"}

```

So the verified earlier finding holds and is now precise: **`broadcast!` fires
after each commit (`wire.clj:276`), shipping the full raw `tx-data` plus `basis-t`,
`basis-t-before`, counts, `tx-meta`, and the originating `request-id`.** Every
subscriber receives every commit. There is no per-subscriber filtering and no
"which query is affected" routing — exactly the Datomic tx-report-queue
"fire-hose, you filter" model the reactive-databases survey flags as the
load-bearing fact (reactive-databases-survey-2026-06-03.md §1.1).

**Important nuance:** the server does NOT use datahike's own `listen!`. It calls
`d/transact` and then imperatively calls `bcast/broadcast!`. The guest side
(`guest-cljs/.../db.cljs`) *reimplements* a `listen!`/`unlisten!` + fan-out loop
that polls `next-tx-event` from the host and dispatches into a local listener map
(`db.cljs:17-32, 219-258`) — "exactly the shape `datahike.core/listen!` uses on
the JVM, modulo [the wire]" (its own docstring). The `"db-name" "default"`
hardcode in `ok-event-from-report` is a smell: the standalone `wire.clj -main`
only ever opens ONE conn, so the broadcast can't distinguish sessions. The
multi-session registry (`session.clj`) and the broadcast (`broadcast.clj`) are
**not wired together** — `session.clj`'s `::pub-chan` slot is explicitly "always
nil in Wave 2; Wave 4 will populate" (`session.clj:53-56`). So multi-session
*storage* is green but multi-session *broadcast* is not.

### 1.4 Session / multi-session model; clusters

`seon.server.session` (`session.clj`) is the runtime registry:

- `!registry` — `{db-name -> {::conn ::backend ::path ::pub-chan}}` (`:134`).
- `!agents` — `{agent-id -> db-name}` (`:138`). "A given agent joins exactly one
  session; a session has N agents" (`:140-141`).
- `ensure-db!` is idempotent + concurrency-safe via `locking !registry`
  (`:178-204`). `resolve-agent` is the 14-LOC routing primitive
  (`:273-283`): agent-id → db-name → conn.

**There is NO cluster / DB-group / population abstraction in code.** Sharing a DB
between N agents is achieved by pointing N `!agents` entries at the same db-name.
That gives you "N agents, one shared DB" but it is flat: you cannot express "two
*independent* clusters {A,B,C} on DB-X and {D,E,F} on DB-Y, both running the same
experiment, selected for competence." You can *encode* that today only as two
db-names with three agent entries each — there is no first-class grouping, no
per-cluster lifecycle, no cross-cluster comparison surface. The "parallel agent
populations sharing a substrate, selected for competence / evolutionary growing"
idea is **design-only** (it does not appear in `src/seon/server/`; it lives as
intent in multi-agent-design-2026-05-27.md and the orchestrator's framing).

### 1.5 How datahike runs

- **Embedded in the JVM wire-server process**, one conn per session, via direct
  `datahike.api/connect` (`session.clj:170`, `wire.clj:67`). No flow, no
  `seon.db` layer on the server side — `session.clj` docstring is explicit:
  "Direct `datahike.api/connect`. No flow."
- **Backends:** `:memory` (tests), `:file` (konserve file tree, "transitional /
  acceptable"), `:sqlite` (wired but **throws "not yet supported"** — konserve-jdbc
  isn't registered on datahike's store multimethods, see `store.clj:32-42`). So in
  practice **`:memory` + `:file` only**. `:keep-history? true`,
  `:schema-flexibility :write` (`store.clj:117-121`).
- **How many DBs/conns:** one per session/db-name. The standalone `wire.clj -main`
  opens exactly one. The session registry can hold N. konserve `:id` is a
  deterministic UUID from the db-name (`store.clj:110-115`), so reopening a name
  lands on the same store.
- **`max-tx` is basis-t** (`wire.clj:75-76` `basis-t-of` = `(:max-tx db)`).

### 1.6 Are agents/functions/summaries persisted as DB entities yet?

**Not on the platform/server side.** `src/seon/server/` knows only generic datoms.
The `:seon.fn` / `:seon.ns` / `:seon.schema` "code-as-data" entity machinery, the
analyzer-driven extraction, and any scout/summary entity are all in the **V1 CLJS
pod** (`src/seon/agent.cljs`, `src/seon/eval.cljs`, `src/seon/code.cljc`,
`src/seon/render.cljs`, `src/seon/test/runner.cljs`). The platform track has the
*storage substrate* for these (it's just datahike) but none of the *entity
conventions* have been ported into a server-side schema yet. The one persisted
domain entity proven on the server is the **facts knowledge base**
(`facts_test.clj`, `:fact/subject` etc.) — and that's a seed, not agent-authored.

**Code-smell flag:** the program-graph entity conventions exist in two places —
the V1 pod owns them, the V2 server has none — and the CUTOVER parity matrix
(client-runtime/docs/CUTOVER.md, "V1/V2 parity matrix" blocking item) explicitly
requires every V1 public ns to get an overlay shadow or a "not needed" note. Scout
/ summary entities are not yet on that matrix at all. This is the gap any posh port
lands in the middle of.

---

## Job 2 — Porting Posh into this architecture

### 2.0 What posh actually is (verified from the vendored source)

The pure engine is **`posh.core` + `posh.lib.*`**; the reagent ratom layer is
optional (`plugin_base.cljc` `make-query-reaction` / `:ratom` / `:make-reaction`
are only invoked through the ratom path — `posh.core` never touches them).

**`dcfg`** is the datastore-agnostic seam (`clj/datascript.clj`,
`clj/datomic.clj`): a map of `{:db :pull* :pull-many :q :filter :with :entid
:transact! :listen! :conn? :ratom :make-reaction}`. The datascript impl is a
trivial 1:1 map to `datascript.core`; the datomic impl stubs `listen!` with a
`TODO` (Datomic's tx-report-queue doesn't match datascript's `listen!` shape).
**A datahike dcfg is the template-shaped first deliverable** (§2.5).

**The engine** (`core.cljc`):

- `posh-tree` = one map: `{:cache :graph :dcfg :conns :schemas :dbs :filters ...}`.
  `:cache` is `{storage-key -> {:reload-patterns ... :results ... :pass-patterns
  ... :reload-fn ...}}`. `storage-key` is e.g. `[:q query args]` or
  `[:pull poshdb pull-pattern eid]` (`core.cljc:107, 137`).
- `add-q`/`add-pull` register a standing query: run analysis once, store
  `:reload-patterns` (the wake-set) + `:results` (`core.cljc:106-146`).
- `after-transact` (`core.cljc:213-238`) is the **two-gate dispatch**, run once
  per commit per db-id:
  1. rebuild `:dbs` from `:db-after`;
  2. `cache-changes` walks each storage-key: **cheap gate** =
     `(dm/any-datoms-match? reload-patterns tx)` (`core.cljc:162`); only on a
     match does it call `:reload-fn` to **re-run the full query** and produce new
     `:results`;
  3. `really-changed` = `(not= new-result old-cache)` (`core.cljc:231-235`) — the
     final equality gate that suppresses no-op recomputes;
  4. returns `{:changed really-changed}`.
- The `:graph` / `:pass-patterns` cascade (`core.cljc:158-184`) propagates a
  derived view's change downstream to chained views (`add-filter-*` builds these);
  this is posh's built-in "agent A's summary feeds agent B" mechanism.

**The cheap matcher** (`datom_matcher.cljc`): `any-datoms-match?` is `O(patterns ×
datoms)` of `datom-match-pattern?`, where a pattern element is a literal, a set
(membership), or `'_` (wildcard) (`datom_matcher.cljc:4-22`). `[[]]` means "match
everything," `nil`/`[]` mean "match nothing."

**The wake-pattern derivation** (`q_analyze.cljc` `q-analyze`, `:495`): normalizes
where-clauses to `[$ e a v]` eavs (`normalize-all-eavs`), **runs the query** to
bind qvars (`r` at `:509`), then `pattern-from-eav` (`:189`, a big `core.match`
table) emits e/a/v patterns. Three flavors:
- `:simple-patterns` — all qvars wildcarded (coarsest; `:566-568`).
- `:patterns` — qvars bound from results where they're "linked" (shared across ≥2
  eavs), others wildcarded (`:571-573`). This is the entity-precise one.
- `:filter-patterns` — for the cascade (`:574`).
Plus `lookup-ref-patterns` (`:510-514`) handles `[:attr val]` identity inputs.

**`set-conn-listener!`** (`plugin_base.cljc:25-45`) is the single driver: one
`(:listen! dcfg) conn :posh-listener (fn [tx-report] (swap! posh-atom
p/after-transact {conn tx-report}) ...)`. **One tx-listener drives the whole
engine.** This is exactly the Convex "single subscription manager walks the log
once per commit" shape (reactive-databases-survey-2026-06-03.md §1.2).

### 2.1 Where does the posh engine live in V2? — HOST-SIDE, single manager

**Run the posh engine on the JVM host, inside a real datahike `listen!` callback,
as one subscription manager per DB (Convex-style).** Argument:

- **Only the host has what posh needs synchronously.** posh's `after-transact`
  calls `:q`/`:pull` synchronously *inside* the listener, against `:db-after`. The
  verified facts hold on the host: datahike `listen!` fires **synchronously in the
  writer after commit** (`reference-code/datahike/src/datahike/writer.cljc:247`:
  `(doseq [[_ callback] (listeners)] (callback tx-report))` right before
  delivering the promise) and `datahike.query/q` runs synchronously over the
  realized `:db-after`. Guests are async/wire-only — they cannot run a synchronous
  query inside a synchronous listener; every `q` is a round-trip.
- **The host already has the conn + the commit.** The server's `transact` handler
  (`wire.clj:265-277`) is the natural injection point. Today it does
  `(d/transact conn tx*)` then `bcast/broadcast! raw-event`. The posh-on-host
  design replaces that imperative broadcast: register `(d/listen! conn :posh
  posh-callback)` once at conn creation, let posh compute `:changed`, and
  broadcast **the changed scout-queries + their new results/summaries** instead of
  raw datoms.

**The strict-win comparison vs current broadcast-raw-datoms:**

| | Current (`broadcast.clj`) | Posh-on-host |
|---|---|---|
| What ships per commit | full `tx-data` to *every* subscriber | only scout-keys whose `really-changed` is true, + new results |
| Who decides relevance | every guest, re-deriving locally | the host, once, for all subscribers |
| Re-run cost | N guests each re-run their queries over wire round-trips | host re-runs each dirty query once, synchronously, in-process |
| Wire volume | O(commits × subscribers × datoms) | O(commits × *changed* scouts × result-size) |

For the scout/orchestrator topology this is **a strict win**: scouts and the UI
subscribe to *summaries that changed*, not to a datom fire-hose they must re-filter
and re-query over the wire. It also collapses the cascade (scout→orchestrator→UI)
onto one in-process posh `:graph` pass instead of N independent wire-driven
re-derivations.

**What breaks / the honest costs:**

1. **It changes the wire contract.** The current contract is "here are the raw
   datoms, do what you want." Some existing consumers (the guest's local `listen!`
   fan-out at `db.cljs:219-258`, the Rust host's snapshot cache invalidation) read
   raw `tx-data`. You cannot just swap the broadcast payload; you must either run
   BOTH (raw `tx` events for cache/own-tx-dedup, plus a new `scout-changed` event
   for reactive subscribers) or migrate those consumers. Recommend: **add a second
   event type `"event" "scout-changed"`, keep `"event" "tx"` for the cache/dedup
   path.** Two event kinds on one pub socket.
2. **Effectful recompute.** posh's `reload-fn` is a *pure* re-run of the query.
   That's correct for the cheap "observation" half of a scout. But the scout's
   *summary* is LLM-authored and expensive/non-idempotent — it must NOT run inside
   the synchronous listener (it would block the writer thread for seconds and
   re-run on every relevant tx). Split: posh's pure re-run produces the new raw
   observation (`:results`) synchronously; the LLM summary is a *downstream,
   debounced, relevance-gated* effect (Convex "action" vs "query" — survey §5.4).
   **Do not let an LLM call run inside `d/listen!`.**
3. **Blocking the writer.** datahike fires listeners on the writer thread before
   delivering the tx promise (`writer.cljc:247`). A slow posh pass delays *every*
   client's transact ack. Keep the in-listener work to: cheap pattern match + the
   (usually few) dirty-query re-runs. Push everything else (summary, broadcast
   fan-out I/O) onto a separate thread/queue. The current `broadcast!` already
   risks this (it does socket writes on the writer thread, `wire.clj:276` →
   `broadcast.clj:17-26`); posh makes the discipline mandatory.

### 2.2 Does posh's cache/graph map onto multiple clusters sharing DBs?

posh's `posh-tree` is **one atom keyed by storage-key**, but it is **already
db-id-partitioned**: `add-db` keys conns/schemas/dbs/filters by `db-id`
(`core.cljc:38-51`), `cache-changes` takes a `db-id` and matches against
`(get (:reload-patterns analysis) db-id)` (`core.cljc:158-162`), and
`after-transact` folds over `(for [[db-id conn] conns] ...)` (`core.cljc:213-230`).
So a *single* posh engine can host many DBs.

**Recommendation: one posh engine per DB (per shared-DB group), not one global
engine, and not one-per-cluster-if-clusters-share-a-DB.** Reasoning:

- The wake unit is the datahike conn + its `listen!`. posh's listener is
  per-conn (`set-conn-listener!`). A shared DB = one conn = one listener = one posh
  engine instance that all agents on that DB attach their standing queries to.
  This is the natural fit for "parallel agent populations sharing a substrate":
  every agent in the population registers its scout query into the *same* posh
  engine for that DB, and a write by any agent wakes exactly the scouts whose
  patterns overlap — including scouts owned by *other* agents in the population.
  That is the "cross-agent coordination falls out" property from CLAUDE.md's
  reactive-context principle, for free.
- **N independent clusters that do NOT share a DB ⇒ N posh engines** (one per
  conn), because they're N conns with N listeners. The session registry
  (`session.clj`) already gives you N conns; you'd attach one posh engine per
  registered conn. The "select for competence" comparison across clusters then
  reads each cluster's summary entities — a *query across DBs*, which is an
  orchestrator concern above posh, not a posh feature.
- **Don't** run one global posh engine spanning unrelated clusters' DBs: it would
  put unrelated populations' standing queries in one atom and one listener thread,
  coupling their wake latency and their failure modes. db-id partitioning exists,
  but process/atom isolation per shared-DB group is the cleaner blast radius.

Net: **posh engine instance == datahike conn == shared-DB group.** Clusters that
share a DB share an engine (and thereby see each other's writes — desired);
clusters on separate DBs get separate engines (isolation — desired). This maps
1:1 onto `session.clj`'s `{db-name -> conn}` registry: **one posh engine per
registry entry.** It does NOT require the (nonexistent) cluster abstraction — the
db-name *is* the grouping key.

### 2.3 Can the scout summary be a posh pull/q reaction persisted as a DB entity?

Yes, and the mapping is clean:

| posh concept | Seon entity |
|---|---|
| `storage-key` (e.g. `[:q query args]`) | `:seon.scout/id` (a stable hash of the query) + `:seon.scout/query` |
| `:reload-patterns` (the wake-set, plain data) | `:seon.scout/wake-patterns` — stored, **glanceable** ("what does this scout watch?") |
| `:results` (raw observation) | `:seon.scout/observation` (or a blob ref if large) |
| LLM-authored summary fn | the cheap render → `:seon.scout/summary-html` + `:seon.scout/summary-text` |
| `:graph` edges (cascade) | `:seon.scout/feeds` refs to downstream orchestrator subscriptions |

This fits the existing **code-as-data / functions-as-data** machinery in principle:
the summary render fn is a `:seon.fn`-style entity (a defining form persisted +
instrumented), and the scout's wake-patterns are *derived data* you can query
("show me every scout that wakes on `:seon.position/qty`"). The honest caveat:
that machinery currently lives in the **V1 pod** (`code.cljc`, `analyzer_info.cljs`,
`render.cljs`), **not** in `src/seon/server/`. So "persist the scout as a DB entity
that the graph/ingest `:seon.fn` machinery understands" requires first **porting
the `:seon.fn`/`:seon.schema` entity conventions to the server side** (the
CUTOVER parity-matrix work, §1.6). The scout entity is a *new* `:seon.scout/*`
schema you'd register via `seon.schema/register!` and transact through the wire —
straightforward — but the "summary fn is itself a queryable program-graph entity"
half depends on the not-yet-ported analyzer pipeline.

Key alignment with CLAUDE.md's "derive, don't store": **store the wake-patterns
and the latest observation/summary (those ARE the renderer projection), but do not
store a notification queue or an "acknowledged" flag.** A scout with no subscribers
still persists (code + last summary), is resumable (re-register its standing query
on load — posh's `add-q` is idempotent via the `cached` check, `core.cljc:138-140`),
and its lifecycle ties to subscription (posh's `make-query-reaction` `:on-dispose`
removes the item unless `:cache :forever`, `plugin_base.cljc:106-114` — but that's
the *ratom* layer; on the host you'd gate disposal on "no subscribers AND scout
entity marked ephemeral").

### 2.4 What do we OPTIMIZE in our version of posh?

posh re-runs the full query on every pattern match (no IVM) and its `q` patterns
are coarse (qvars wildcarded in `:simple-patterns`; only `:patterns` and `pull` are
entity-precise). Evaluating the four candidates:

**(a) Entity-precise patterns for `q` too — YES, use `:patterns` not
`:simple-patterns`.** posh's `q-analyze` already computes `:patterns` (qvars bound
from results, `q_analyze.cljc:571-573`), but `update-q-with-dbvarmap`
(`update.cljc:53-75`) requests `:simple-patterns` for the reload gate — the coarse
one. Our port should prefer `:patterns` as the reload-pattern so a scout watching
entity 42 wakes on writes to *42*, not on any write to that attribute across all
entities. **Cost:** `:patterns` requires re-deriving patterns whenever results
change (because the bound qvar values change), which posh handles via `reload-fn`
re-running anyway. For high-selectivity scouts (one entity) this is a large
precision win; for low-selectivity scouts (all positions) `:simple-patterns` is
fine. **Make it per-scout configurable**, defaulting to `:patterns`.

**(b) datahike `as-of`/basis-t for catch-up instead of posh's in-memory `:dbs` —
YES.** posh keeps `:dbs` (materialized db values per db-id, rebuilt every
`after-transact` from `:db-after`, `core.cljc:213-220`) so it can re-run queries.
On datahike the host *has* the live conn and `as-of`/basis-t (already wired:
`wire.clj:181` `resolve-db-with-basis-t`). A scout that was asleep/disconnected
catches up by re-running its query at the current `(d/db conn)` and diffing against
its last persisted `:seon.scout/observation` — no need to retain a chain of
in-memory db values. This also kills posh's `:dbs` memory growth. **Drop posh's
`:dbs`; use `(d/db conn)` / `(d/as-of db t)` directly in the datahike dcfg.**
(This is exactly the survey's "build wakeups on the replayable log, not an
ephemeral bus" — reactive-databases-survey §1.8, §5.1.)

**(c) Reuse datahike's internal `propagate-query-cache` attribute-eviction —
INVESTIGATE, do not assume.** datahike's `query.cljc` has its own query cache that
a tx invalidates by attribute. If datahike already computes "which cached queries
does this tx touch" by attribute, we could in principle subscribe to *that* instead
of maintaining posh's pattern cache. **But:** datahike's cache is keyed by the
*exact query+db form* (memoization), evicted coarsely by attribute presence — it is
NOT entity-precise and NOT a public subscription API. It tells you "this cached
result is stale," not "this standing subscription's result changed." It also
doesn't give you the `really-changed` `not=` gate. **Verdict: posh's pattern cache
is the right layer; datahike's query cache is an orthogonal memoization we should
leave alone** (and possibly *disable* for scout re-runs to avoid double-caching).
Worth one REPL probe to confirm datahike's cache doesn't serve stale `:db-after`
results inside a listener — but don't architect on reusing it.

**(d) The effectful-recompute split — YES, this is the most important
optimization.** Two tiers, both gated:
- **Tier 1 (synchronous, in-listener, pure):** posh cheap pattern match →
  `really-changed` `not=` on the raw observation. Cheap, always runs, never calls
  an LLM. Produces `:seon.scout/observation`. This is posh almost unmodified.
- **Tier 2 (async, debounced, relevance-gated):** only when Tier 1's observation
  *really changed* AND a relevance predicate passes, enqueue the LLM
  summary-recompile off-thread. The relevance gate prevents an LLM call per datom;
  the debounce coalesces a burst of writes into one summary. This is the
  Convex-action discipline (survey §5.4) and it's what makes scouts affordable at
  agent-population scale.

Two more we should add beyond the four asked:
- **(e) Own-tx dedup.** A scout shouldn't wake on its own agent's writes (infinite
  self-trigger risk). The broadcast already carries `request-id` (`wire.clj:237`);
  the CUTOVER non-blocking list names "own-tx dedup (gap #2)". Filter own
  request-ids before the posh pass for a given agent's scouts.
- **(f) Cycle/epoch cap.** scout A wakes B wakes A. posh's `:graph` cascade
  assumes a DAG (`core.cljc:166-180` recurses through `:outputs`); a genuine cycle
  would recurse without bound. Add an epoch cap per reactive round (survey §4.4,
  §5.4 — the one thing none of the DAG frameworks solve and we must).

### 2.5 Concrete port plan + risks

**Smallest first milestone (prove the core):**

1. Write `posh.clj.datahike` dcfg (template = `posh/clj/datascript.clj`): map
   `:db :pull* :pull-many :q :filter :with :entid :transact!` to `datahike.api`;
   `:listen!` to `datahike.core/listen!`; `:conn?` to `datahike.core/conn?`. Leave
   `:ratom`/`:make-reaction` **out** (host engine is headless — `posh.core` never
   calls them; only `plugin_base`'s ratom path does, which we skip).
2. In a JVM REPL against a `:memory` datahike conn: `posh!`-style wire one
   `(d/listen! conn :posh (fn [r] (swap! posh-atom p/after-transact {conn r})))`,
   `add-q` a scout query for one entity, transact an *unrelated* datom, assert the
   scout did **not** appear in `:changed`; transact a *relevant* datom, assert it
   **did**, with correct new `:results`.
3. Headless (no reagent): read `:changed` straight off the swapped posh-atom (it's
   in `after-transact`'s return, `core.cljc:236-238`). This proves "a scout wakes
   only on relevant txns" with zero ratom dependency.

That milestone is ~a day and de-risks the whole approach. Only after it's green do
you touch the wire (add the `scout-changed` broadcast event, §2.1) or the entity
schema (§2.3).

**Real risks (honest):**

- **datahike TxReport vs datascript datom shape.** posh's matcher expects datoms as
  `[e a v t added]` sequences (`datom_matcher.cljc`). datahike's `:tx-data` are
  `datahike.datom.Datom` records; they're seqable to `[e a v tx added]` (the
  server already does this, `wire.clj:107-117`) but `any-datoms-match?` calls
  `(first datom)`/`(rest datom)` — confirm datahike Datoms support `first`/`rest`
  (they implement `Seqable`/`Indexed`; the wire code uses `.-e`/`.-a` field access,
  so posh's seq access needs a verify or a `(seq d)` adapter in the dcfg). **One
  REPL probe.** Likely a 1-line coercion.
- **`entid` / lookup-ref handling.** posh's `:patterns` path resolves lookup-refs
  via `(:entid dcfg)` (`q_analyze.cljc:425-429, 510-514`). datahike's `entid`
  semantics (and `:db/ident` resolution) differ subtly from datascript's. Scouts
  keyed by `:seon.db/identity` lookup-refs need this to be exact or they'll wake on
  the wrong entity. Test with a real identity attr.
- **History / temporal dbs vs posh's `:dbs`.** If we drop posh's `:dbs` (§2.4b) and
  re-run against `(d/db conn)`, fine — but datahike with `:keep-history? true`
  (which `store.clj:120` sets) means `as-of`/`since` are available; a scout that
  wants "what changed since I last woke" should use `since`, not posh's db-chain.
  Make sure scouts re-run against the *current* db, not a stale history db, inside
  the listener (the listener gets `:db-after` directly — use it).
- **Multi-conn.** posh supports multiple dbs in one engine (`add-db` per db-id),
  but our recommendation is one engine per conn (§2.2). The only multi-db scout
  case is a query that joins two session DBs — datahike `d/q` can take multiple db
  args, and posh's `dbvarmap` handles multi-`$` queries (`q_analyze.cljc:361-367`).
  Cross-DB scouts are possible but rare; defer.
- **Blocking the writer thread** (§2.1 cost 3) — the single biggest operational
  risk. Mitigate by keeping the in-listener work minimal and pushing summary +
  fan-out off-thread from day one.
- **It's net-new platform code.** The platform track has *no* reactive engine
  today; this is additive, not a refactor of something working. Don't let it block
  the CUTOVER blocking items (real LLM turn in a guest, LLM HTTP capability) — those
  gate the platform; the posh engine is a layer *on top* of a platform that must
  first be able to run an agent at all.

---

## Summary (read this if nothing else)

1. V2 platform = single-JVM wire server, one datahike conn per session, ops green,
   **69 deftests/234 assertions** in `test/seon/server/`. Real and solid.
2. Broadcast is **raw datoms, imperative, post-commit** (`wire.clj:276`), unfiltered
   — NOT datahike `listen!`-driven. There is **no reactive engine** at all.
3. Multi-session storage is green (`session.clj` registries); **multi-cluster /
   shared-DB grouping is design-only** — the db-name is the only grouping key.
4. datahike runs embedded, `:memory`+`:file` only (`:sqlite` wired-but-throws);
   `:keep-history? true`. basis-t = `:max-tx`. `as-of` already wired.
5. **No scout/summary/agent/fn entities persisted server-side** — that machinery is
   V1-pod-only and not yet on the CUTOVER parity matrix.
6. Posh port is feasible and a good fit: **one headless posh engine per datahike
   conn, driven by a real `d/listen!` callback on the JVM host** (the verified
   synchronous listener + synchronous `q` make this work, `writer.cljc:247`).
7. Change the broadcast from "raw datoms to everyone" to "scout-keys that
   really-changed + new results" — **strict win** for the scout/orchestrator
   topology; add it as a *second* event type so the cache/own-tx-dedup path keeps
   raw `tx` events.
8. Engine-per-conn maps 1:1 onto `session.clj`'s `{db-name -> conn}`; shared-DB
   populations share an engine (see each other's writes for free), separate DBs
   get separate engines. No cluster abstraction needed.
9. Optimize: prefer entity-precise `:patterns` over `:simple-patterns`; drop
   posh's in-memory `:dbs` and use `(d/db conn)`/`as-of`; do NOT reuse datahike's
   query cache (orthogonal memoization, not a subscription API); split pure
   in-listener observation from debounced, relevance-gated LLM summary.
10. Add own-tx dedup (`request-id` already on the wire) and an epoch cap for
    scout-A↔scout-B cycles (no DAG framework solves cycles; we must).
11. First milestone (~1 day): `posh.clj.datahike` dcfg + headless `d/listen!`-driven
    `after-transact`, proving a scout wakes only on relevant txns. Then wire +
    schema.
12. Biggest risks: datahike Datom seq-access in the matcher (1-line probe), exact
    `entid`/lookup-ref semantics for identity-keyed scouts, and **never block the
    writer thread** — keep the listener cheap, push LLM + I/O off-thread.

**File:** `docs/prds/agent-runtime/research/posh-port-and-platform-state-2026-06-03.md`
