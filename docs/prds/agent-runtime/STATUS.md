---
type: reference
status: active
tags: [reference, prd]
---

# agent-runtime — recent ships + cross-track coordination

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

## Queued — next Platform agent picks this up

### Resume phase (v1.md §7.4) — DEEP RESEARCH FIRST

Status: **paused at design**. Implementation NOT started despite the
prior Platform session being ready to ship. Sean's directive:
"resume from just stored code means we need to intelligently unravel
the order to execute everything in efficiently and I don't want this
to be hacked together, but carefully planned."

The v1.md sketch ("tx-id is monotonic → topological by construction
→ `(doseq [e entities] (raw-eval (:source e)))`") is plausible but
underverified. The CLJS bootstrap analyzer state, namespace
dependency ordering, schema-registry timing, and datahike replay
semantics under our `:keep-history? true` config all have edge cases
that need source-grounded answers before code lands.

**Next Platform agent action:** read
[`research/resume-design-questions-2026-05-23.md`](research/resume-design-questions-2026-05-23.md)
end-to-end. Dispatch the research prompt at the bottom of that file
as a background agent (`run_in_background: true`, single agent with
full context). Pick up parallel-safe work while it runs. When the
research file lands at `research/resume-findings-<date>.md`, design
the implementation from those findings — NOT from the v1.md sketch
alone.

### Parallel-safe Platform work (can ship while resume research runs)

- `bin/seon log-stream` SSE endpoint per
  [`research/error-envelope-and-log-stream-2026-05-23.md`](research/error-envelope-and-log-stream-2026-05-23.md)
  recommendations §3. New endpoint on the pod's HTTP loopback,
  filterable + replayable. ~2-3 hours. Doesn't touch any code MVP is
  actively in.
- "Cheap sequence-first" envelope items from the same research file
  (§Key concrete changes): `seon.web.serve` request-level
  `log/error-console!` → `log/error!` (3 callsites), same for
  `broadcast/render-for-new-conn!`, add DB write inside
  `seon.db/listen!`'s currently-swallowed error catch. ~30 min total.
  Same constraint: doesn't touch `eval.cljs` / `agent.cljs` /
  `parse.cljc`.

### Held — waits on MVP signal before Platform touches

- Full error-envelope unification (`db/transact!` → return-data
  refactor, `:ok` → `:seon.eval/ok` keyword rename, ~30 callsites).
  Per MVP's flag: "If error-envelope work starts now while I'm in
  eval.cljs, we'll conflict. Let me drive the loop end-to-end first."
- Persistent backend (`:memory` → on-disk). Separate conversation
  Sean owns. Resume's end-to-end value is gated on this.

## Recent ships (2026-05-23) — Platform track only

### Platform track — eval-batch! refactor (Items 1+2 + with-tx-context + duration-ms)

Commit `5786247`. MVP-verified all-clear. Bundles:

- `seon.db/validate-entity-values!` dispatches on schema-declared
  ref ARITY (`:one` / `:many` / `nil`), not on value-shape. Single-
  card lookup tuples like `[:seon.ns/name :foo]` no longer get
  iterated as many-card containers (MVP Item 1, committed separately
  as `615a120`).
- Evals attach as `:seon.turn/evals` component children of the
  owning turn (v1.md §2.1, acceptance criterion 11). One pull on
  `:seon.turn/id` returns the turn with evals inline. Verified
  end-to-end via MCP eval.
- `eval-batch!` return shape: `{:seon.eval/ids […]
  :seon.eval/n-ok int :seon.eval/n-fail int}`. `run-agentic-loop!`
  stop policy can distinguish "10 fails" from "10 successes."
- `eval-batch!` signature: `turn-n` int → `turn-id` string (5
  args, same arity).
- `:seon.eval/duration-ms :int` populated per form (wall clock
  around the await). Slow-eval warning predicate has live data.
- Read failures from `seon.parse/parse-forms` (`:kind :read
  :ok? false`) land as failed `:seon.eval` entities — agent sees
  its own broken text in next turn's ctx.
- `with-tx-context {:seon.db/agent-id … :seon.db/eval-id … :seon.db/
  origin :agent}` wraps per-form work in `eval-batch!`. Closes
  Phase 2.5 item 4 consumer side. Caller (run-turn!) can layer
  turn-id / session-id at a wider scope.
- `:seon.eval/agent` and `:seon.eval/turn` schemas deleted — agent
  reachable via component chain. Dropped from `agent-bootstrap-
  attrs` in `seon.client`.

### Platform track — parse-forms rewrite-clj + .cljc + JVM corpus

Commit `676baf0`. Phase 2.5 item 5 closed. `seon.repl/parse-forms`
extracted to `seon.parse.cljc` (JVM-testable). New entry shapes:
`{:kind :form :narration :source :form}` (success) and
`{:kind :read :ok? false :narration :source :error}` (per-form
isolated read failure). Byte-faithful `:source` from rewrite-clj
(load-bearing for resume re-eval). 5 tests / 47 assertions pass
on `bin/test seon.parse-test`. `seon.repl/parse-forms` preserved
as a re-export for MVP's existing call site.

### Platform track — MCP eval retry on status-only errors

Commit `190de3b`. Discovered while testing post-pod-restart eval:
shadow's nREPL returns TWO distinct failure shapes when a session
is bound to a dead JS runtime. The retry from `63f5a7b` only
caught the `:err`-text shape; the status-only shape (where
`:status [...error]` is set but `:err` is empty) leaked through.
Widened the detection. Post-restart MCP eval now self-heals
without manual `create_session`.

### Platform track — Phase 2.6 schema bridge cleanup (resolves MVP PLATFORM-FLAG)

`seon.client/agent-bootstrap-schema` (~200 lines of hand-written `:db.type/*` entries) is now `seon.client/agent-bootstrap-attrs` — a 55-keyword vector that flows through `seon.db/malli->datahike-schema` at boot. Adding a new datahike attr is now: `(schema/register! :foo/bar <malli-shape>)` in the owning ns + add `:foo/bar` to `agent-bootstrap-attrs`. No more spread-the-smell hand-written entries.

Touch surface:

- **`seon.db/resolve-malli-form`** — only recurse on registry indirections when the resolved definition is a Malli form (keyword/vector). Malli built-ins like `:inst` resolve to an `IntoSchema` object via the seon.schema registry; recursing into that lost the head and broke the mapping. Now returns the form unchanged when the resolved def is anything other than a follow-able shape.
- **`seon.db/form-properties`** — finds the first map-typed child anywhere in the form (not just index 1). Supports both Malli's canonical `[:vector {props} child]` placement and the readability variant `[:vector child {props}]` MVP's regs use.
- **`seon.log/at` and `:seon.log/dismissed-at`** — `:any` → `:inst`. The pre-Phase-2.6 comment ("not really :inst in CLJS") was stale; `inst?` returns true for `js/Date` and datahike's `:db.type/instant` accepts it. Confirmed pattern: `:seon.message/at`, `:seon.eval/at`, `:seon.turn/at` all use `:inst`.
- **`seon.render/ai` and `:seon.render/html`** — `[:fn symbol?]` → `:symbol`. Same validation (both reject non-symbols), but `:symbol` maps through the bridge to `:db.type/symbol`; `[:fn ...]` had no mapping.
- **`seon.test/sym`** — added `{:seon.db/identity true}` marker (was identity in old hand-written schema; Malli reg was missing the property).
- **`seon.agent/id`, `:seon.message/id`, `:seon.eval/id`** — added `{:seon.db/identity true}` marker. Same reason. Surgical 4-character additions in MVP's lane; no overlap with their active loop/turn work area.

Verified: derived schema has 55 attrs + 7 tx-meta attrs = 62. 5 component-refs (sessions, ctx, turns, messages, evals). 9 identity attrs (agent.id, session.id, turn.id, message.id, eval.id, ns.name, fn.sym, schema.key, test.sym). 8 instant attrs, 9 ref attrs, 9 keyword attrs, 3 symbol attrs. Fresh in-memory conn transacts the derived schema cleanly (201 tx-data datoms, no errors).

**Live pod still uses the OLD schema** — the in-memory conn was created at boot before the new code loaded. To switch to the new schema, restart: `bin/seon restart pod`. MVP, your call when — heads up I won't restart unilaterally because you'll lose any in-flight conn state. The new code IS hot-loaded into the bundle; `(open-agent-conn!)` calls create-with-new-schema.

### Platform track — multi-agent process supervisor `bin/seon`

Idempotent, lock-safe (mkdir-mutex per process), multi-agent-friendly process supervisor. Replaces ad-hoc `pkill` / `nohup` / `lsof` patterns and resolves the ownership problem where two agents might race to start/stop the same process.

```bash
bin/seon start pod         # idempotent
bin/seon status            # which procs alive, PIDs, pod port + URL
bin/seon tail pod          # tail -f logs/pod.log (any number of agents OK)
bin/seon restart cljs-watch
```

Registered: `pod`, `cljs-watch`, `jvm`. State at `tmp/proc/<name>/`, logs at `logs/<name>.log`. Full protocol: [[../../seon/process-management]]. CLAUDE.md "Surgical Process Management" section also rewritten to point at the supervisor.

**Use this for any process lifecycle work** instead of running `node out/client/main.js` directly or pkilling. It's the only way concurrent agents don't step on each other.

### Platform track — render-surface rename + symbol lookup moved

A-8 closed. Symbol resolution (`seon.render/resolve-symbol`) moved
to `seon.eval/lookup-value` — same semantics (walks `js/globalThis`
segment-by-segment with `cljs.core/munge`, handles reserved-word
munge, never throws), now lives next to the analyzer-cache concerns
in `seon.eval` it's conceptually paired with. Single implementation,
shared by render + any future per-entity dispatch.

Renamed `seon.render/ai-dispatch` → `seon.render/ai-render` and
`html-dispatch` → `html-render` — they're 4-line resolve+call shims,
NOT multimethod dispatch; the old name overpromised. Real per-entity
Malli-specificity dispatch arrives in v2 with `:seon.fn/output-keys`
indexed; that one earns the "dispatch" word and will live in
`seon.eval` alongside the program-graph queries it needs.

Callers updated: `seon.web.broadcast/render-agent!`,
`seon.agent/run-turn!`, `seon.ai.deepseek` docstring. `seon.client`
no longer wires `use-compile-state!` (deleted — lookup-value needs
no boot-time atom). `out/client/main.js` compiles 0 warnings.

MVP-track impact: any v1 work that calls into render slot
resolution uses `seon.render/ai-render` / `html-render` and (for
direct symbol lookup) `seon.eval/lookup-value`. No behavior
change; just names.

### Platform track — PRD folder rename

`docs/prds/webassembly-agents/` → `docs/prds/agent-runtime/`.
Scope expanded past the WASM proof of concept to cover the full
runtime — WASM containment is one phase (Platform Phase 3),
not the whole story. All internal cross-refs, the dynamic-context-
and-canvas research doc, `CLAUDE.md`, `README.md`,
`docs/seon/_dashboard.md`, vision docs, and pod-host Rust source
docstrings updated. Branch is now `feature/agent-runtime`.

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
