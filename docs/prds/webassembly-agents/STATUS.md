---
type: reference
status: active
tags: [reference, prd]
---

# webassembly-agents — recent ships + cross-track coordination

This file is for time-sensitive coordination: what shipped this week,
what's in flight, what's needed across the MVP↔Platform boundary,
and how to iterate. **Design lives in [README.md](README.md) and the
versioned specs (v1.md, v2.md, v3.md). This file does not duplicate
spec content.**

## Tracks

- **MVP track**: agent eval surface — design in [v1.md](v1.md).
  Currently in implementation against the V0 CLJS pod (Node, not
  WASM yet).
- **Platform track**: WASM-Tauri containment — design in
  [platform.md](platform.md). Capability hardening + Phase 2 test
  infra shipped 2026-05-22 (below).

## In flight on the MVP track (2026-05-22)

### v1 spec draft landed — implementation has NOT started

The 2849-line `agent-repl-mvp.md` was rewritten from scratch as
[v1.md](v1.md) (~1483 lines, defines each thing once). Old doc
preserved at `archive/agent-repl-mvp-pre-2026-05-22.md`. **This is a
spec, not code.** Nothing in v1 has been built yet — `:seon.session`,
`:seon.turn`, `:seon.blob`, `*tx-context*`, `run-turn!` /
`run-agentic-loop!`, the persistent-program detect-and-tee, the
5-section composer in `seon.agent`, the boot preconditions — none of
it exists in `src/seon/`. The current V0 pod (which runs deepseek
end-to-end today via `start-agent-with-deepseek!`) implements an
earlier substrate-teaching ctx and lacks every v1 entity past
`:seon.agent` / `:seon.message` / `:seon.eval`.

What [v1.md](v1.md) IS: the agreed-upon design for what to build
next on the MVP track.

The rewrite was driven by three research artifacts under `research/`:

- `v0-implementation-state-2026-05-22.md` — what's wired vs specced
- `datahike-capabilities-2026-05-22.md` — datahike primitives we
  should leverage (tx-meta + history, `:db/isComponent`, reverse-ref
  pulls, `d/listen!`)
- `gemini-graph-modeling-2026-05-22.md` — full-context Gemini
  critique with raw response preserved

Sean's locked-in design decisions and version dependency graph live
in [README.md](README.md); don't re-litigate them.

## Recent ships (2026-05-22) — Platform track only

### Platform track — Capability surface Phase A shipped

HTTPS allowlist override via `SeonHttpHooks::send_request` in
`pod-host/wasm-tauri/src-tauri/src/pod.rs`; 3 unit tests in
`tests/http_allowlist.rs` pass. Note: wasmtime 44 moved the override
hook from `WasiHttpView::send_request` (per the research file) to a
separate `WasiHttpHooks` trait — implementation differs from
research pseudocode but behaviorally equivalent.

Side-fix: the subagent fixed a pre-existing `[workspace.package]`
missing `authors` field that was blocking ALL `cargo` invocations
on the workspace.

Remaining capability phases (B–E) pending. Research:
`research/capability-surface-2026-05-22.md`.

### Platform track — Phase 2 test capture shipped

`src/seon/test/runner.cljs` ships `run-vars` + `stash-run!` +
`record-run!` + `run-and-record!`. Storage design per Sean's
three-tier rule: FULL run-result lives on the agent's ns (globalThis
stash, reached via `(result <run-id>)`); DB carries the surfaced
projection only (`:seon.test/sym`, `:last-passed-at`,
`:last-failed-at`, `:last-failure-summary` ≤200 chars,
`:last-run-id`).

This unblocks the MVP track's D4 auto-run hook — eval-batch's
`:seon.fn`-touch listener calls `seon.test.runner/run-and-record!`.
Agent-facing surface is `seon.agent/test` / `seon.agent/tests`
(wraps the runner; agent never types the runner namespace).

See [platform.md](platform.md) §"Phase 2 — Test infra promoted to
data" for the platform-side design rationale.

### Known issues — recent fixes

- **KI-2 + KI-5 FIXED** (platform track). Two independent `defonce
  !compile-state` atoms (`seon.client/!compile-state`,
  `seon.repl/!compile-state`) were silently diverging. Collapsed to
  one canonical atom in `seon.repl`, shared via
  `seon.repl/ensure-bootstrap!`. Version-stamped via
  `seon.eval/init-version` so hot-reloads rotate the cache.
  Findings: `research/compile-state-lifecycle-2026-05-22.md`.
- **KI-3 FIXED** (platform track). `seon.error/->map` now emits a
  top-level `:seon.error/data` key holding the deep-merged ex-data
  across the entire cause chain (deepest-wins). Renderers read one
  key. Findings: `research/eval-error-envelope-2026-05-22.md`.
- Remaining unfixed KIs (KI-1, KI-4, KI-6) listed in
  [v1.md Appendix B](v1.md#appendix-b--known-implementation-issues-unfixed-as-of-2026-05-22).

## Cross-track touchpoints

The MVP and Platform tracks share infrastructure. Coordination
points outstanding:

### MVP needs from Platform (added 2026-05-22, v1 design)

**Phase 2.5 substrate primitives — in flight 2026-05-22.** Six
items negotiated with the MVP agent so v1 substrate doesn't land
inline in feature code. Full plan + execution order +
responsibility split lives in [platform.md](platform.md) §"Phase
2.5 — v1 substrate primitives". Summary:

1. D13 Node-side dynvar probe (Platform, blocks everything).
2. `seon.id` namespace extraction (Platform, no deps).
3. `:keep-history? true` flip + boot `assert-preconditions!`
   (Platform).
4. Tx-meta auto-merge in `seon.db/transact!` + 7 tx-meta attr
   registrations + KI-1 invocation-shape precondition (Platform,
   one coherent patch). Conflict rule: explicit `opts.tx-meta`
   wins per-key; dynvar fills unset keys — keeps MVP's explicit
   plumbing forward-compatible.
5. `parse-forms` rewrite-clj refactor (Platform, waits on MVP
   confirming v1.md §4.1 return shape is final).
6. `seon.code/extract-defn-name` / `extract-schema-key` /
   `extract-ns-name` (Platform, waits on MVP landing
   `test/seon/eval/detect_tee_test.cljs` corpus per v1.md §11
   Risk 2).

MVP track does NOT touch the 6 items above in their feature
branch; works in parallel on `:seon.session`/`:seon.turn` schemas,
`run-turn!` scaffolding (with explicit `:tx-meta` passthrough
until item 4 lands), and the Risk 2 corpus.

### Other Platform-blocking items (not Phase 2.5)

1. **Blob dir read+write in the `seon:fs/sandbox` WIT interface.**
   V1 adds `:seon.blob` content-addressed archival storage; bytes
   at `<pod-data>/blobs/<hash[:2]>/<hash>.zst`. The drafted
   `seon:fs/sandbox` WIT (`pod-host/wasm-tauri/src-wit/seon-pod.wit`)
   needs to expose read+write to the blob subdir as a preopened
   directory. Phase 7 capability hardening plans this; v1 needs it
   concrete. No new WIT interface — just `seon.fs` default-deny
   allowlist + WIT host config. *(Note: v1 stores
   `:seon.turn/prompt-text` inline as a string — blob subsystem
   itself defers to v2. This entry survives because the WIT
   surface needs the directory grant ahead of v2 implementation.)*

2. **D13 WASM-boundary dynvar probe (separate from Phase 2.5 item
   1).** Phase 2.5 verifies survival on Node. WASM-boundary
   survival across wstd's message-passing fiber model is a
   distinct probe required before Phase 3 cutover. If Node passes
   but WASM fails, the remediation options from v1.md §11 Risk 1
   apply at the Component boundary specifically (explicit-arg
   threading through host imports, or host-side scope-token store).
   ~30-min probe under wasmtime CLI.

### Other cross-track touchpoints

- **Eval surface contract.** [v1.md](v1.md) §4 describes what
  `eval` returns; [platform.md](platform.md) §"Eval surface" wires
  it into the WIT `eval-form` export.
- **Analyzer-cache load.** V0 pod loads from `out/bootstrap/ana/`.
  WASM build needs the same caches packaged into the Component
  bundle (see `research/m2-findings-2026-05-21.md`).

## Multi-pod concurrency rules

Locking in so future agents don't re-derive:

- **Each pod gets its own datahike DB.** Single-writer per LMDB
  store; two processes on one DB will deadlock or corrupt.
- **Blob dirs can be shared across pods.** Content-addressed by
  SHA-256; duplicate writes are no-ops. Safe by construction. Useful
  when multiple agents want to read each other's archived artifacts.
- **Different pod versions on the same DB sequentially:** OK via D1
  rules (substrate schemas are additive; datahike `:db/valueType`
  is immutable; newer bootstrap is "transact only entries not
  present" by identity-attr lookup).
- **Different pod versions on the same DB concurrently:** NOT
  supported — single-writer constraint.
- **Multiple agents in one pod process:** supported by architecture;
  v1 assumes single-agent. `seon.agent/*id*` dynvar provides
  per-agent scope when v3 cross-agent collab lands.

## Iteration surface

- Bring up the V0 pod: `clj -M:cljs watch client` (terminal 1) +
  `node out/client/main.js` (terminal 2). See
  `docs/seon/pod/REPL-WORKFLOW.md`.
- MCP tools: `mcp__seon_cljs__eval` for host-side eval (the
  substrate's `:client` runtime). `(seon.repl/dev-init!)` once per
  pod boot brings up `@!compile-state` and `@!conn`.
- WASM iteration: reserve for confidence runs. See
  `research/m2-findings-2026-05-21.md` §"Iteration cadence".

## Layout

See [README.md](README.md) §"Layout" for the canonical file map.
