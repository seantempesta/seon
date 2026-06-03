---
type: prd
status: draft
tags: [prd, agent, database]
---

# CUTOVER.md — V1 → V2 retirement plan

> **SUPERSEDED / current shape (2026-06-03).** Authoritative plan:
> [platform-v2-node-first-plan-2026-06-03](../../docs/prds/agent-runtime/platform-v2-node-first-plan-2026-06-03.md).
> The V1→V2 retirement still happens, but the cutover is now reframed in two
> stages:
>
> - **V2.0 (prove the architecture) — Node-first.** Repoint the real V0 agent
>   loop at a **Node** wire client against the **one-JVM-many-DBs** wire-server.
>   No wasm sandbox; native `fetch` for LLM HTTP; trusted/single-user posture.
>   This is the convergence of the MVP track (real agent loop) and the Platform
>   track (multi-DB server), and it is the prove-the-architecture milestone.
> - **V2.1 (harden the boundary) — wasm.** Swap Node→wasm32-wasip2 + WIT-typed
>   capabilities. Same wire protocol, same agent CLJS, same multi-DB server.
>
> Effect on the checklist below: the blocking items framed as "real agent turn
> in a **wasm guest**" and "**LLM HTTP through WIT**" are **V2.1** items, NOT
> the immediate POC. For V2.0 the equivalent blocking item is "real V0 agent
> turn in a **Node process** with native-`fetch` LLM." The wasm/WIT/ALS/blob
> items move to V2.1 hardening. Read the per-item annotations added below.

## Why

- **Multi-session isolation** — per-user secure namespaces. V1 is single-tenant
  by construction; V2 isolates by separate process + sockets + store.
- **JVM-AOT datahike** — real Clojure, full feature set (history, as-of,
  filters), hot-reloadable from REPL. datahike-cljs at V1 is a partial
  port and inherits cljs.core's limits.
- **WASI-sandboxed agents** — guests run in wasm32-wasip2 with WIT-typed
  imports. Capability surface is enforced by wasmtime, not trust.
- **Performance ceiling** — Rust host snapshot cache delivers sub-microsecond
  warm reads. The V0 in-process path is fast but does not isolate.
- **Training-data capture** — every session's tx-log IS the training data;
  per-session bitemporal history is automatic.

## What V2 currently does

| Capability | Status | Notes |
|---|---|---|
| JVM writer + UDS + CBOR control + Transit-JSON values | green | Phase 1 + 2026-05-26 Transit migration. |
| Rust host with snapshot cache + tx broadcast + tx batcher | green | Phase 2 + PE. 99.1% hit rate on cache-friendly workload. |
| wasm32-wasip2 CLJS guest end-to-end | green | Phase C, ~6.8 MB release component. |
| Full V0 datahike API on the wire (overlay) | green | Phase B. 9 audit APIs + bonus surface. |
| `next-tx-event` host → guest, listener fan-out | green | Phase C, non-blocking try_recv + setTimeout yield. |
| N=3 multi-agent stress | green | Phase D, 300s, 0 errors, 0 out-of-order. |
| Multi-session isolation | green | Phase PF, 2×2 + 3×1 smokes, cross-contamination disjoint. |
| WASI preopen filesystem (`seon.client-runtime.fs`) | green | RO `/seon-src` + RW `/scratch`, EACCES enforced. |
| Facts knowledge base + `learn` role | green | 34 seed facts, 6 tests / 16 assertions. |
| Real V0 agent turn (V2.0 = **Node process**) | **red** | Workload guests + smokes only. No LLM-driven turn yet. V2.0 target is a Node process; the wasm-guest turn is V2.1. |
| LLM HTTP (V2.0 = native `fetch`) | **red** | deepseek client is V1-only; runs natively in Node (V2.0). The WIT-typed HTTP capability is V2.1 hardening. |
| ALS / async-context parallel-agent smoke | **red** | Multi-agent N=3 ran, but cross-await context isolation not separately verified. V2.1 concern (separate Node processes sidestep it in V2.0). |
| EDN fallback removed | yellow | Transit is canonical; EDN string input still accepted for the smoke/REPL diagnostic path. |
| Blob capability (artifacts / large content) | **red** | Three-tier storage rule says blobs ≠ datoms ≠ globalThis. WIT shape TBD. |
| `sessions.edn` declarative config | yellow | CLI flags + lazy spawn cover v1; declarative config deferred. |
| Live-V1-session migration | **red** | No export/replay path from V1 store to V2 session store. |
| Tauri integration | not started | Out of scope for cutover — separate milestone. |

Legend: **green** = shipped + smoked. **yellow** = partial, works but not
cutover-blocking. **red** = blocking. **not started** = explicitly deferred.

## Cutover criteria (binary checklist)

Each item is done/not-done. **All "blocking" items must be done before
cutover.** Yellow items are nice-to-have; non-blocking.

### Blocking — V2.0 (Node, prove-the-architecture)

- [ ] Phase D + PF smokes re-verified on the post-2026-05-26 Transit wire
      (re-run after any wire change; both are currently green).
- [ ] **One JVM, many DBs wired** — wire-server resolves conn-per-request via
      `session.clj` `!registry`, real db-name on broadcast, per-DB routing,
      per-conn `listen!` hook; two DBs in one process with zero cross-bleed.
      (This is clusters-and-multi-db-wiring P1 — the shared seam.)
- [ ] **Real V0 agent turn runs in a Node process** against the wire-server:
      the MVP agent loop (`agent.cljs`/`eval.cljs`/deepseek) with its `seon.db`
      repointed at a Node wire client over a socket; native `fetch` for LLM
      HTTP (no capability work in V2.0) → completes one full turn, persists
      turn-log datoms, emits tx events.
- [ ] Drop EDN fallback in JVM writer's `read-T` (verify no production
      caller relies on it; smoke + REPL harness migrate to Transit).
- [ ] V1 / V2 parity matrix: every user-reachable entry point in V1 has
      an equivalent in V2. Concretely: every `src/seon/*.cljs` namespace
      with a public API has either (a) an overlay shadow at
      `guest-cljs/src-overlay/seon/`, or (b) a documented "not needed in
      V2" reason in this file.
- [ ] Migration plan for live V1 sessions: either export-and-replay
      (V1 datahike-cljs store → V2 konserve store) OR a "drop them"
      declaration with the user's sign-off.

### Blocking — V2.1 (wasm, harden-the-boundary)

These were previously framed as cutover-blocking but are now the **V2.1**
hardening step, sequenced AFTER the Node POC proves the architecture.

- [ ] Real V0 agent turn runs inside a **wasm guest** (WASI preopen for
      bootstrap caches + V0 turn driver under wasm32-wasip2). The Node turn
      (V2.0) proves the loop; this re-proves it under the sandbox.
- [ ] **LLM HTTP capability through WIT** (`seon:capabilities/http` style) or
      Rust host-proxied `deepseek-chat` op — without leaking the host's network
      capability beyond declared endpoints. (V2.0 uses native `fetch`; the WIT
      capability is the sandbox-hardening replacement.)
- [ ] ALS parallel-agent smoke proves cross-await context isolation in the
      wasm runtime (two concurrent host calls in flight don't bleed
      AsyncLocalStorage state). Less pressing under Node-first (separate OS
      processes); relevant when agents share a host runtime.
- [ ] Blob WIT capability for artifacts/results/large content
      (three-tier storage rule: datoms = projections, blobs = persistent
      full content, globalThis = volatile session). Shape + smoke.
- [ ] In-guest REPL + host watchdog (epoch interruption + import timeouts +
      Store teardown) per `research/repl-access-design-2026-06-03.md` — the
      wasm-specific diagnostic story (trivial/free in the Node runtime).

### Non-blocking (do during, or right after, cutover)

- [ ] `sessions.edn` declarative config — pre-spawn sessions at host
      startup, pin per-session metadata.
- [ ] `drop_session(name)` op — currently sessions live for host lifetime.
- [ ] Smarter cache invalidation — drop entries whose query depends on
      changed attrs, not blanket "drop all older than current basis-t".
- [ ] Guest binary size reduction (currently ~6.8 MB; QuickJS + wasi:p2
      stubs dominate).
- [ ] Per-attribute cache shape (datahike-tools-datalog-style index in
      Rust, updated incrementally from tx-data).
- [ ] Own-tx dedup (gap #2) — filter own request-ids out of the broadcast
      stream so a guest doesn't see its own commits in its listener.

### Explicitly out of scope for cutover

- Tauri integration — "ship V2 in Tauri" is a separate milestone after
  cutover. Cutover proves V2 can replace V1 as a standalone platform;
  Tauri is the desktop-packaging step that comes next.
- libdatahike-native — abandoned for the JVM writer per the original PRD.

## Open risks

- **No LLM-driven turn yet.** This is the single largest unknown. V0 has the
  agent loop; the **V2.0** path repoints it at a Node wire client (native
  `fetch` LLM, no WIT capability work) and lands the overlay-shadow ports for
  every `seon.*` namespace the loop touches. The wasm-specific work (LLM HTTP
  through WIT, WASI bootstrap-cache preopens) defers to **V2.1**, so the Node
  POC de-risks the architecture before the sandbox quirks. Allocate one full
  session for the Node spike.
- **Cache hit rate on real workloads is unproven.** The cache-friendly
  synthetic workload hit 99.1% (post tx-batcher fix), but Phase D''s
  attempt at a realistic agent pattern landed at <1% — likely because
  basis-t silently coerces to 0 on the boundary. Diagnose before declaring
  the cache an architectural win.
- **Wire churn affects V0 audit refactors.** The audit named two V0 sites
  (`agent.cljs:445-464`, `agent.cljs:1029-1099`) that need overlay-shaped
  refactors. If V1 evolves before cutover, the refactor target moves.
  Coordinate with MVP track before they touch those sites.
- **Konserve-file is the only working backend.** SQLite/JDBC failed in the
  PoC. If we hit a multi-process-read need at scale, we're forced to
  konserve-sqlite (would need writing).
- **JVM cold-start ~11s per session.** Fine for long-running pods; would
  block per-request spawn use cases.
- **wasm guest size 6.8 MB.** Acceptable but not great for distribution;
  tighter builds + feature flags could shrink considerably.

## MVP-track asks

For the V1 / MVP track agents, here is what V2 needs from you to land
the cutover cleanly:

- **Hold schema changes during the agent-port spike.** When V2 starts
  porting a real LLM turn, the overlay needs the V0 `seon.agent`,
  `seon.eval`, `seon.client`, `seon.db` schemas to be stable for a
  ~48-hour window. Communicate before merging schema changes.
- **Freeze the deepseek client shape** until the LLM HTTP capability is
  wired. Either V2 proxies through the Rust host (and copies V1's request
  shape), or V2 adopts a WIT-typed HTTP capability — but the request/response
  shape `seon.ai.deepseek` exposes today must not move underfoot during the
  port.
- **Flag any V1-only conventions** that won't carry to V2. If V1 starts
  relying on Node-specific globals, `node:fs` paths, or in-process
  datahike-cljs idiosyncrasies that don't translate to a sandboxed
  wasm guest, leave a note in the relevant V1 file so the porter doesn't
  re-derive the constraint.
- **No new ad-hoc connections to datahike-cljs.** V2 talks to one writer
  per session. If V1 grows a second connection (a side-database, a temp
  in-memory db), V2 has to grow a matching session-or-namespace
  mechanism — call it out before adding.

## Decision point

Once all blocking items above are green, the orchestrator declares
cutover. At that point:

1. V2 becomes the default `bin/run` / `node out/...` entry point.
2. V1 sources at `src/seon/*.cljs` are deleted (one commit, atomic).
3. CLAUDE.md's "Current focus" section updates to "V2 / client-runtime".
4. `client-runtime/` is moved out of the PoC namespace and into
   `pod-host/platform/` (or whatever final name), with frontmatter
   `status: active`, not `draft`.
5. This CUTOVER.md is archived.

Until then: **stay in lane**. AGENT.md spells out the hard rules.
