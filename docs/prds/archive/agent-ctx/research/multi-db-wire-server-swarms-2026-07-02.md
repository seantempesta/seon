---
type: research
status: active
tags: [research, database, agent]
---

# Multi-DB wire-server + agent swarms — design proposal (2026-07-02)

> Design research for the owner's ask: "solve the parallelism picture — clear
> separate DB environments, multiple concurrent databases on the wire-server,
> in-memory databases, swarms of agents tackling issues with shared updates
> eventually, and a measurable advantage over single-env agents." Written as a
> PROPOSAL for both lanes + the owner — it crosses the lane boundary
> (wire-server = tooling territory; the bench runner = eval territory). No
> code was changed for this doc; every claim is grounded file:line.

## TL;DR — the recommended architecture

**The wire-server is ALREADY multi-DB.** `seon.server.registry` holds
`{db-name → conn}` with agent-id routing, every wire op optionally carries
`db-name`/`agent-id`, the tx-feed is db-name-tagged and per-DB routed, and
`ensure-db` is a live wire op. Nobody should build "multi-DB support" — it
exists and is reachable today from the wire-node REPL. **What's missing is
(a) two small lifecycle verbs (`remove-db`, `list-dbs`) and (b) the POD's
db-name awareness** — the pod hardwires one store derived from the req-sock
basename.

**Recommended target: N lightweight pods sharing ONE wire-server, one
`:file`-backed db-name each, plus the shared cluster DB as the swarm's
coordination channel.**

1. **Parallel unit = the pod, not the thread.** The `/solve` ceiling-of-1 is
   pod-side (`*conn*` root swap + a schema-registry swap + one shared
   compile-state + one event loop), not wire-server-side. Even after the
   queued fiber-local-`*conn*` work, one-pod-one-world stays the clean bench
   isolation unit; fiber-local raises per-pod concurrency only for
   LLM-latency-bound rows and fixes the collision bug class.
2. **A pod CANNOT peer-attach a wire-server `:memory` DB.** The pod's read
   model is a DIS peer on a shared-filesystem konserve `:file` store (local
   lazy db values); a JVM-side `:memory` store has no pod-readable substrate
   — reads would degrade to per-read RPCs. So: **ephemeral bench worlds stay
   pod-local `:memory` conns** (what `/solve` already does — they never touch
   the wire-server at all), and **pod-attachable named worlds are `:file`
   disposables** (create under `data/clusters/bench-*/`, delete after).
   Wire-server `:memory` DBs remain useful for JVM-side/test consumers.
3. **Swarm = N agents in separate worlds + ONE shared cluster DB as the
   "shared updates eventually" channel.** No cross-DB merge/sync machinery:
   publishing is an explicit transact of specific entities (kb facts, issue
   claims, results) into the shared DB — db-name-routed writes the wire
   client already supports — with the existing provenance tx-meta; updates-in
   ride the existing per-DB tx-feed subscription. Claims use the existing
   `cas-assert` fence to prevent duplicated work. This stays inside every
   settled constraint (per-cluster DBs, sole writer, coordination through the
   DB, from/to refs + hop-cap).
4. **First slice for the eval lane** (immediate win): the already-planned
   `bench-cluster-N` full-cluster launcher — zero new infra. Second slice:
   pod db-name awareness so N pods share one wire-server JVM (bench-cluster
   v2: 1 JVM + N Node processes instead of N JVMs + N Nodes).

## Current-state map (file:line grounding)

### The wire-server side — multi-DB already built

| Concern | Where | State |
|---|---|---|
| DB registry `{db-name → conn}` | `src/seon/server/registry.clj:180-190` (`!registry`, `!agents`), `:294-326` (`ensure-db!` — idempotent, locked create, hooks fire once) | **Built** |
| Per-request conn routing | `src/seon/server/wire.clj:629-661` (`resolve-conn-for-req` + `handle-req`) — every op resolves by `agent-id` → `db-name` → ambient fallback | **Built** |
| Envelope carries db-id | optional `:seon.store.wire/agent-id` / `:seon.store.wire/db-name` on ANY request (`wire.clj:635-640`; pod client `src/seon/store/internal/wire_node.cljs:155-158` `routed`) | **Built** |
| `ensure-db` wire op | `wire.clj:246-264` — creates/looks-up a named DB, returns basis-t; backend defaults `:memory` there but `:file` in `registry.clj:304` (inconsistency, see artifacts) | **Built** |
| Per-conn listeners on open | `registry.clj:240-290` on-ensure-db hooks; `wire.clj:442-447` installs `::raw-broadcast` per conn; `boot.clj:233-264` installs `::reactive` per conn | **Built** |
| db-name-tagged broadcast + per-DB routing | `src/seon/server/broadcast.clj:38-89` (`subscribe!` keyed by db-name; socket subscribers get the tagged stream and demux) | **Built** |
| tx-feed subscription routed by db-name/agent-id | `src/seon/server/boot.clj:92-153` (`db-name-for-req`, `subscribe-tx` incl. since-t replay per subscriber) | **Built** |
| Agent-id → db-name binding | `registry.clj:371-414` (`register-agent!`/`resolve-agent`) — exists but has **no wire op** exposing it | Built, not wired |
| DB removal / deletion | `registry.clj:328-346` `remove-db!` (releases conn, does NOT delete data) — **no wire op**; no `d/delete-database` path at all | **Gap** |
| List DBs | `registry.clj:357-363` `list-sessions` — **no wire op** | **Gap** |
| Backends | `src/seon/server/store.clj:125-148` `config-for`: `:memory` (per-name deterministic id, `name->uuid` :110-115), `:file`, `:sqlite` (wired but throws — konserve-jdbc dispatch gap, `store.clj:32-42`) | Built (`:memory`,`:file`) |
| Datahike N-conns-per-JVM | `reference-code/datahike/src/datahike/connector.cljc:2-3` (`datahike.connections` registry keyed by conn-id), `:269-283` (`release` ref-counted). `:memory` skips index flush entirely (`reference-code/datahike/src/datahike/writing.cljc:65-66`); `delete-database` (`writing.cljc:519-525`) drops the store via konserve lifecycle | Library supports it; the registry already opens N |

Net: **one wire-server process hosting N concurrent databases is the shipped
design** — the acme-era "Path B" work built it. The wire protocol needs zero
envelope changes for multi-DB.

### The pod side — hardwired to ONE store

| Concern | Where | State |
|---|---|---|
| The single conn root | `src/seon/db.cljs:338-353` — `*conn*` defonce dynamic root, `set!` at boot; the async wake path reads the ROOT | The known ceiling |
| Fiber-local scopes exist | `db.cljs:355-407` — `with-agent`/`with-tx-context` are AsyncLocalStorage-backed (`internal/run-with-agent`); the queued fiber-local-`*conn*` unit extends this same mechanism | Precedent in place |
| DIS-peer read model | `src/seon/store/wire.cljs:1-35` docstring + `cluster-config` :109-132 — reads are LOCAL konserve `:file` reads (`:lock-blob? false`), writes route through the `:seon-wire` PWriter (:246-308) over UDS | **`:file`-store-coupled** |
| Store identity derivation | `wire.cljs:87-107` `store-id` — re-implements the JVM's `UUID/nameUUIDFromBytes` keyed off the **req-sock basename** (`:seon.server/<basename>`), mirroring `wire.clj:52-66` `opts->config-for-request`. One socket ⇒ one store, by construction | The pod-side hardwire |
| Writes carry NO db-name | `wire.cljs:246-308` `SeonWireWriter` builds the transact req without `:seon.store.wire/db-name` → always the ambient conn | Gap for multi-world pods |
| tx-feed adapter | `wire.cljs:404-470` — one adapter singleton (`!adapter` :324), subscribes without db-name → ambient feed | Gap likewise |
| `/solve` scratch worlds | `src/seon/web/serve.cljs:495-613` `solve-once!`: saves `db/*conn*` + the FULL `schema/*schemas` snapshot (:525-526), `set!`s `*conn*` to a fresh conn (:529), restores both in `finally` (:609-613). Documented SERIAL-ONLY (:505-508) | The measured ceiling=1 (calibration doc) |
| Scratch conn = pod-local `:memory` datahike-cljs | `src/seon/client.cljs:614-629` `open-agent-conn!` — `d/create-database`+`d/connect` `{:backend :memory :id (random-uuid)}` IN the pod. **The wire-server is never involved in a `/solve` world** | Load-bearing discovery |
| Shared compile-state per pod | `serve.cljs:536` — every sample evals against the ONE `repl/ensure-bootstrap!` compile-state (facts isolated per world; compiled defs leak across samples) | Accepted for QA rows; disqualifies per-pod concurrency for codegen rows |
| Explicit-db reads exist | `db.cljs:552-631, 896-958` — `query`/`pull`/`entity` accept explicit `::db/db` / `::db/conn`; only the OMITTED-db ambient path reads `*conn*` | Enables two-conn pods |
| Cluster env plumbing | `bin/seon:87-119` (`SEON_CLUSTER_DIR`/`SEON_REQ_SOCK`/`SEON_PUB_SOCK`/`SEON_PORT`/`SEON_PORT_FILE`), `bin/acme:39-46` (the full-isolation override set) | The launcher template |

### The measured problem this must solve

`docs/prds/agent-ctx/research/calibration-run-2026-07-02.md`: per-pod `/solve`
concurrency ceiling = 1 (deterministic conn-swap collision at c=2: cas
`:entity-id/missing`, `halt superseded`, full 300s burn, `turns=0`). Parallel
scoring today = N whole clusters (JVM + Node + store each). The eval-lane plan
(A6) already names `bench-cluster-N` as the lever.

## Q1 — Multi-DB wire-server: one process × N DBs vs N processes

**Cost of one-process-N-DBs in the current protocol: ~zero.** The envelope
already carries the db-id (optional `db-name`/`agent-id` on every op —
`wire_node.cljs:21`), the tx-feed already tags and routes per DB
(`broadcast.clj:86-88`), and `handle-req` already resolves the conn per
request (`wire.clj:649-661`). Datahike serializes writes per-conn internally,
so N DBs write in parallel across conns while each DB stays
single-writer-serialized — exactly the per-cluster-DB model. The request
server spawns a thread per accepted UDS connection (`wire.clj:665-701`) and
the pod client opens a connection per rpc (`wire_node.cljs:105-151`), so
request concurrency across DBs already exists.

What one-process costs vs N processes: a shared failure domain (one JVM crash
= all worlds' writers down; pods fail loud and re-ping, stores are durable for
`:file`), a shared heap (per-store LRU caches — `reference-code/datahike/src/
datahike/store.cljc:24-34`), and a shared embed augmenter/rate limit
(`wire.clj:197-218`). What N processes cost: ~1 JVM each (hundreds of MB heap
+ 10-20s boot + a proc-supervisor namespace each — `bin/acme:39-46` shows the
six env vars a full cluster needs).

What actually changes if we adopt one-process-N-DBs as the default:

- **Server:** add `remove-db` (release + optional `d/delete-database`) and
  `list-dbs` wire ops over the existing `registry/remove-db!`/`list-sessions`;
  optionally expose `register-agent!` as a wire op (the registry fn exists,
  `registry.clj:371`). Unify the `ensure-db` backend default (`:memory` in
  `wire.clj:260` vs `:file` in `registry.clj:304` — pick ONE; recommendation:
  make the wire op default `:file` so a bare ensure-db yields a pod-attachable
  store, and require `backend "memory"` explicitly for JVM-side ephemerals).
- **tx-feed:** nothing — `subscribe-tx` already takes db-name (`boot.clj:111`).
- **REPL surface:** nothing structural — the 7891 socket REPL reaches the
  registry atom already; convenience fns at most.

**Recommendation:** ONE wire-server hosting N DBs is the default topology for
swarms and bench groups; N processes remains the escape hatch for hard
isolation (acme stays its own process; the planning bench that restarts pods
keeps its own cluster). Do not build anything beyond the two lifecycle verbs.

## Q2 — In-memory databases for ephemeral worlds

**What datahike `:memory` gives us:** a konserve memory store keyed by the
config `:id` (deterministic per db-name via `store.clj:110-115` `name->uuid`,
so re-ensure lands on the same store); no index flush ever happens
(`writing.cljc:65-66` — `flush!` is gated on `not= :memory`), so transacts
skip disk entirely; `keep-history?`/as-of work normally in memory;
`delete-database` (`writing.cljc:519-525`) drops the store; `d/release`
(`connector.cljc:269-283`) is ref-counted per conn-id. Lifecycle verbs map
1:1 onto wire ops: create = `ensure-db` (exists), wipe = `remove-db` +
`delete-database` + re-`ensure-db`, destroy = `remove-db` with delete.

**The constraint that shapes everything: pod attachment.** The pod is a DIS
peer — `@conn` re-reads the branch root from LOCAL konserve `:file` and
reconstitutes a lazy db value (`wire.cljs:4-16`), which is why agent reads are
free and constant-rate (every context render derefs). A wire-server `:memory`
store has no filesystem substrate the pod can read; "attaching" would mean
every read becomes a `q`/`pull` rpc — a different (and much worse) performance
model for the render loop. **Therefore:**

- **Bench-sample worlds: pod-local `:memory` conns — the shipped mechanism.**
  `/solve` already opens a fully local datahike-cljs `:memory` DB per sample
  (`client.cljs:614-629`), core-seeds it, and throws it away. Zero
  wire-server cost, perfect isolation of FACTS. Keep this; the fix it needs
  is pod-side (Q4), not a storage change.
- **Pod-attachable named worlds (swarm member worlds, multi-turn disposable
  envs): `:file`-backed disposables** — `ensure-db` with `backend :file`,
  path under `data/clusters/<group>/<name>/store` (the registry already
  hardens paths, `store.clj:96-108`), destroyed with the new `remove-db`
  verb + directory delete. "Ephemeral" = lifecycle, not backend.
- **Wire-server `:memory` DBs: JVM-side consumers only** — server tests, the
  eval harness poking state over the wire, short-lived coordination scratch.
  Fine over `q` rpcs at low rates.

**How a pod (or N pods) attaches to a specific db-id** — this is the Q4/slice-2
change: `SEON_DB_NAME` in the launcher env; at boot the pod (a) calls
`ensure-db` (`:file`, per-name path), (b) builds `cluster-config` from the
db-name — path + store `:id` derived from the db-name via the SAME
`name->uuid` semantics on both sides (today `wire.cljs:87-107` derives from
the sock basename — that coupling must move to db-name), (c) tags every rpc
(`routed`, `wire_node.cljs:155`) and its `subscribe-tx` with the db-name.

## Q3 — The swarm topology: "shared updates eventually"

Settled constraints frame this tightly: a cluster = one DB + orchestrator + N
task agents; **all coordination flows through the DB**; messaging = from/to
refs + hop-cap (`docs/seon/architecture/agent-runtime.md:195`,
`data-model.md:356-359`); per-cluster DBs. Four candidate mechanisms, judged:

| Option | Verdict |
|---|---|
| (a) Shared cluster DB as the coordination/knowledge channel; per-agent scratch worlds for execution | **Recommended.** It IS the settled model — today's default cluster already runs N agents on one DB. The new part is only the per-agent isolated EXECUTION world. |
| (b) Periodic merge/sync of entity sets between DBs | Rejected: a new mechanism (conflict rules, id translation, sync scheduler) with no existing owner; violates one-mechanism. "Eventually" is achieved by (a)'s explicit publish. |
| (c) Cross-DB messaging via from/to refs | Degenerates into (a): a message row must LIVE in some DB; putting it in the shared DB where both agents have identity rows (`:seon.agent/id` is `:db.unique/identity`, `registry.clj:126`) is exactly (a). Hop-cap unchanged. |
| (d) history/as-of as a merge substrate | Rejected: history is per-DB forensics; cross-DB temporal merge is a research project, not a slice. |

**The concrete swarm shape (proposal):**

- **One shared cluster DB** (`:file`, the existing default store or a named
  `swarm-<id>` DB) holds: the issue/plan board (plan items with cas-fenced
  claims — `db.cljs:418-440` `cas-assert` is the existing work fence), the
  published kb facts (provenance-tagged via the existing tx-meta auto-stamp),
  and agent↔user messages.
- **N worker pods**, each on its OWN world DB (slice 2) for task execution —
  agent-authored code, task-local state, failed experiments stay out of the
  shared world.
- **Publish** = an explicit agent verb that transacts selected entities into
  the shared DB — a db-name-routed write (the wire client supports per-op
  db-name today, `wire_node.cljs:155-158`; the pod's `SeonWireWriter` needs a
  db-name field, see slice 4). Not automatic sync: the agent decides what is
  vetted knowledge vs scratch. Provenance rides tx-meta as it already does.
- **Updates-in** = a second peer conn on the shared `:file` DB (the pod CAN
  hold two datahike conns; explicit-db reads exist, `db.cljs:552-631`) + a
  `subscribe-tx` on the shared db-name; a "swarm board" context block renders
  from the shared conn's db value. This reuses the reactive-context principle
  — the board is a section fn over the shared DB, nothing stored.

**What gives swarms a MEASURABLE advantage over single-env agents:**

1. **Throughput** — wall-clock to resolve a frozen issue SET (N independent
   issues): near-linear in pod count while single-env serializes.
2. **Cross-pollination** — agent B succeeds/short-circuits using a fact ONLY
   agent A published (a kb-recall subscore where the scored fact's provenance
   is a teammate's agent-id). This is the capability single-env agents cannot
   have at all — the headline differentiator.
3. **Dedup under contention** — cas-fenced claims mean two agents never burn
   tokens on the same issue; measurable as wasted-turn rate vs an unfenced
   ablation.

**How the eval lane benches it (the future eval row):** `swarm_vs_single` —
same frozen issue set + same TOTAL LLM budget; arms = (1 pod, serial) vs (N
pods + shared DB); score = issues-resolved-per-wall-clock + teammate-fact
recall subscore; `pass^k` across seeds; flakes classified per the standing
taxonomy. Runs on the slice-2 topology (1 wire-server JVM + N+1 DBs + N pods).

## Q4 — The pod side: what actually forces one-pod-per-env

The `*conn*` root is the famous constraint, but it is **not alone**. The
serial-only globals in a `/solve` sample:

1. `db/*conn*` root swap (`serve.cljs:529/612`) — the collision mechanism the
   calibration run proved.
2. `schema/*schemas` registry snapshot/restore (`serve.cljs:526/613`) — a
   SECOND root swap with the same serial-only property; fiber-local `*conn*`
   alone does not fix it.
3. ONE bootstrap compile-state (`serve.cljs:536`) — compiled defs leak across
   samples (facts don't); disqualifies concurrent codegen samples per pod
   regardless of conn locality.
4. ONE event loop — self-host compiles are synchronous stalls; concurrency
   only pays where samples are LLM-latency-bound (they are: median 42s,
   mostly waiting on DeepSeek).

**So:** the queued fiber-local-`*conn*` unit (tooling lane; the ALS precedent
is already in `db.cljs:355-407`) is worth doing for **correctness** — it
deletes the conn-swap collision class, is the candidate root for the turn-6
recall gap, and lets live agents + `/solve` coexist safely — and it raises
the per-pod ceiling to small-N (2-4) for QA-shaped rows IF the schema
registry gets the same treatment (or scratch worlds stop needing registry
mutation). It does NOT make one pod a general N-world host: compile-state
sharing and CPU stalls remain. **N lightweight pods on one wire-server is the
honest parallelism architecture; fiber-local conn is a correctness fix with a
modest concurrency bonus.**

Interaction with the `/solve` door: with a fiber-local conn, `solve-once!`
binds the scratch conn for the sample's dynamic extent instead of `set!`-ing
the root; the wake trigger and turn loop for the scratch agent read the
fiber value (ALS propagates through Node timers/awaits — same guarantee
`with-agent` already relies on). The `finally` restore disappears — nothing
to restore.

## Q5 — Migration path (ordered, independently landable)

Each slice is live-verifiable on its own; efforts: S ≤ half-day, M = 1-2
days, L = 3+.

1. **Slice 0 — `bench-cluster-N`, full clusters (eval lane; S-M).** Already
   planned (eval-lane-plan A6). Generalize `bin/acme` into a
   `bench-cluster <group> <n>` launcher: per-group `SEON_CLUSTER_DIR`/
   sockets/ports/logs (the 6-var recipe at `bin/acme:39-46`), N solve URLs,
   `POD_MAX_SAMPLES=1` each. **Unblocks parallel scoring with ZERO src/seon
   changes.** Cost: ~1 JVM per pod (the thing slice 2 removes).
   Live proof: an 8-sample gsm8k run across 4 clusters, zero collisions,
   ~4× wall-clock reduction vs the calibration serial run.
2. **Slice 1 — DB lifecycle wire ops (tooling lane, server-only; S).** Add
   `remove-db` (registry release + optional `d/delete-database` + data-dir
   delete for `:file`) and `list-dbs` handle-ops over the existing registry
   fns; unify the `ensure-db` backend default. Verify from the wire-node REPL
   (`proc:wire`): ensure → transact → list → remove → ensure-again-is-fresh.
3. **Slice 2 — pod db-name awareness → N pods, ONE wire-server (tooling
   lane; M).** `SEON_DB_NAME` env: boot `ensure-db` (`:file`, per-name path);
   re-key `store-id`/`cluster-config` (`wire.cljs:87-132`) from db-name
   instead of sock-basename (ONE derivation, both sides, db-name-based);
   thread db-name through `routed` defaults + `SeonWireWriter` +
   `subscribe-tx`. Default deployment (no env) stays byte-identical (the
   ambient-conn back-compat path, `wire.clj:647`). Then bench-cluster v2 = 1
   JVM + N Nodes. Live proof: two pods, two db-names, one JVM; a write in
   pod A never appears in pod B's feed; both solve doors green concurrently.
4. **Slice 3 — fiber-local `*conn*` + `solve-once!` binds-not-swaps (tooling
   lane, already queued; M-L).** Include the `schema/*schemas` story (fiber
   or per-world registry) or explicitly keep `/solve` at 1-per-pod and take
   the correctness win only. Live proof: deliberate c=2 `/solve` run with
   zero cas collisions (the calibration run's failing case, re-run green).
5. **Slice 4 — swarm publish/subscribe (tooling lane; M) + the
   `swarm_vs_single` row (eval lane; M).** Second peer conn on the shared DB
   + the publish verb (db-name-routed transact) + the swarm-board block; the
   cas-fenced claim convention on plan items; then the eval row as specced in
   Q3. Live proof: two live DeepSeek agents in separate worlds; agent B's
   scored answer uses a fact with agent A's provenance.

Dependency shape: 0 ∥ 1 → 2 → 4; 3 is independent of 2/4 (pure pod-side) and
can land any time after 0.

## Complexity artifacts found (standing directive)

- `seon.store.wire/store-id` (`wire.cljs:87-107`) — Node-crypto
  re-implementation of `UUID/nameUUIDFromBytes` keyed off the req-sock
  BASENAME, silently coupling one-socket⇒one-store. Slice 2 must re-key by
  db-name; keep ONE derivation semantics (`seon.server.store/name->uuid`) on
  both sides.
- `ensure-db` backend default divergence: wire op `:memory`
  (`wire.clj:260`) vs registry `:file` (`registry.clj:304`). Unify (slice 1);
  recommendation `:file` at the wire op.
- `registry.clj:9-30` docstring still speaks "Path B session registry" /
  `seon.session` vocabulary — stale vs the cluster model; update when touched.
- `wire.clj:222-234` `filtered-dbs` handle registry is global and unbounded
  (handles never expire); harmless today, worth a TTL when multi-DB traffic
  grows.
- `solve-once!`'s restore-order-dependent `finally` (`serve.cljs:609-613`,
  flagged in the calibration doc) — subsumed entirely by slice 3
  (bind, don't swap).
- The per-sample core-seed inside an agent scope trips
  `warn-on-seed-origin-forge!` on every `/solve` (calibration doc, anomalies
  table) — pre-existing warn noise; slice 3's rework of `solve-once!` is the
  natural place to fix the seed's origin scoping.

## Open questions for the owner

1. **Swarm channel ruling:** confirm the shared cluster DB is the ONLY
   "shared updates" mechanism (option a) and that cross-DB merge/sync
   machinery (option b) is OUT. This doc recommends (a) as settled-model
   consistent; it forecloses offline/async DB merging.
2. **Slice 2 timing:** is N-full-clusters (slice 0) good enough for the eval
   lane until swarm work starts, or is the 1-JVM topology wanted now? RAM
   math: N JVMs (~0.5-1GB each) vs 1; boot time per group ~10-20s × N vs
   once.
3. **`ensure-db` default backend** at the wire op: `:file` (pod-attachable,
   this doc's recommendation) or keep `:memory` (current, test-friendly)?
4. **Per-pod concurrency ambition:** after slice 3, do we WANT >1 sample per
   pod for QA rows (requires the schema-registry fiber story), or lock
   `POD_MAX_SAMPLES=1` permanently and spend the effort on pod count?
5. **Compile-state sharing:** accept compiled-def leakage across samples in
   one pod for QA rows (today's behavior), with codegen rows pinned to
   fresh-pod-per-sample? (This doc assumes yes.)
6. **Seed idempotency for shared worlds:** if two pods ever `ensure-db` +
   core-seed the same db-name concurrently, is `seon.state/reconcile!`'s
   provenance-scoped diff sufficient, or do we need a seed lease? (Untested;
   slice 2's live proof should include a double-boot race.)

## Sources

All grounding is first-party source read for this doc (files/lines cited
inline above): `src/seon/server/{wire,boot,registry,store,broadcast}.clj`,
`src/seon/store/wire.cljs`, `src/seon/store/internal/wire_node.cljs`,
`src/seon/db.cljs`, `src/seon/client.cljs`, `src/seon/web/serve.cljs`,
`reference-code/datahike/src/datahike/{connector,writing,store}.cljc`,
`bin/seon`, `bin/acme`, plus
[[research/calibration-run-2026-07-02]] and [[eval-lane-plan]]. No external
LLM was consulted — the design space was fully resolvable from source.
