---
type: reference
status: active
tags: [reference, prd]
---

# agent-runtime — recent ships + cross-track coordination

> **SUPERSEDED / current shape (2026-06-03).** The "Tracks" framing below
> (Platform track == "WASM-Tauri containment") predates two decisions locked
> with Sean. Authoritative plan:
> [platform-v2-node-first-plan-2026-06-03](platform-v2-node-first-plan-2026-06-03.md)
> (+ [clusters-and-multi-db-wiring-2026-06-03](clusters-and-multi-db-wiring-2026-06-03.md)).
>
> 1. **One JVM, many DBs.** A *cluster* = one datahike DB + N agents + task +
>    metrics; a *session* = one cluster's DB. Cross-cluster isolation is per-DB
>    inside one JVM; process-split is a later crash-isolation option.
> 2. **Node-first.** V2.0 runs agents as **Node CLJS processes** against the
>    multi-DB JVM wire-server (the convergence of the MVP agent loop with the
>    Platform server); wasm + WIT capabilities are **V2.1**, sequenced after.
>    So "WASM-Tauri containment" is the V2.1 destination, not the V2.0 shape.
>
> The program-graph storage / eval / schema / resume / instrumentation
> decisions recorded below (Sean's 2026-05-24 list, the 16-item migration plan,
> the recent ships) are NOT affected by these two decisions and still hold.

This file is for time-sensitive coordination: what shipped this week,
what's in flight, what's needed across the MVP↔Platform boundary,
and how to iterate. **Design lives in [README.md](README.md) and the
versioned specs (v1.md, v2.md, v3.md). This file does not duplicate
spec content.**

## Tracks

- **MVP track**: agent eval surface — design in [v1.md](v1.md).
  Currently in implementation against the V0 CLJS pod (Node, not
  WASM yet).
- **Platform track**: multi-DB JVM wire-server + Node-first agents (V2.0),
  hardening to WASM-Tauri containment (V2.1) — design in
  [platform.md](platform.md) + the 2026-06-03 plan docs. Capability hardening
  - Phase 2 test infra shipped 2026-05-22 (below).

## Sean's locked-in decisions (2026-05-24) + revised migration plan

After four research files this week (analyzer-driven extraction,
schema-registry unification, bulk-load resume, instrumentation
error envelope), the v1 architecture for program-graph storage +
resume + instrumentation is locked. Don't re-litigate.

### Decisions

1. **`:seon.fn/refs` (var-ref graph)** — defer to v2. Add to v2
   docs as planned future work.
2. **Schema extraction** — atom-diff against `seon.schema/*schemas`
   before/after each eval. Needs `seon.schema/current-keys`
   accessor (Platform PR).
3. **Per-agent registry overlay** — explicitly NOT happening.
   Sean's framing: "I want everyone to see all schemas — they are
   fully namespaced this should be fine and will also be loading
   all functions added that are fully spec'ed and tested. This is
   part of the db listener system we are setting up?"
   - Schemas propagate freely via tx-listener on `:seon.schema/source`.
   - Fns propagate when `:seon.fn/specced? true` (v1 gate). v2
     adds the test gate (`:seon.test/last-passed-at > -failed-at`).
   - This is the "publish gate" — see [[../../seon/concepts/code-as-data-runtime]].
4. **Cycle prevention** — validate at write time. The CLJS analyzer
   errors on `:require-cycle`; detect-and-tee only writes entities
   on `:ok true`, so cycles never enter the DB. Resume topo-sort
   throws fatal on cycle as defense-in-depth (should be unreachable).
5. **Bootstrap mechanism** — **no `bootstrap.edn` file.** Substrate
   ships as `.cljs` source files. At first boot, walk the loaded
   analyzer state + read source files from disk + transact entities
   directly. Same code path as detect-and-tee. (Phase 3 / WASM:
   source files bundled as a resource.)
6. **Agent schema deletion** — defer to v2. v2 adds
   `(seon.schema/retract! ::foo)`, gated by "no live `:seon.fn`
   references this schema in its `:malli/schema`".
7. **Instrumentation scope** — **validate inputs AND outputs on
   all public fns** (anything with `:malli/schema` metadata).
   Sean's call: worth the cycles for immediate feedback.
8. **Eval-result envelope for instrumentation failures** — design
   complete (`research/instrumentation-error-envelope-2026-05-24.md`).
   Canonical map under `:seon.error/data` with namespaced
   `:seon.error.malli/*` keys. Adds `:seon.eval/error-data :map`
   attr alongside the existing `:seon.eval/error :string`.
   Renderer in `format-eval-row` produces 5-line block with
   `expected`/`got`/`reason`/`hint` columns.
9. **Resume policy for interrupted turn** — flip turn to
   `:interrupted`; transact a `:seon.message/role :system` message
   describing what happened; kick the agentic loop; the agent
   decides via the reactive ctx (the prior turn's evals naturally
   render). NOT a bare `:error` flag flip.
10. **Resume mechanism: bulk-load synthetic ns files.** Per Sean's
    editor analogy + REPL-verified research. One `cljs.js/eval-str`
    per ns; topo-sort between nses; analyzer handles intra-ns
    ordering. PLUS: optional disk-write to
    `tmp/debug/agents/<id>/<ns-as-path>.cljs` for inspection /
    editor hookup / export / recursive bootstrap. See
    [[../../seon/concepts/code-as-data-runtime]].

### Revised migration plan (16 items, 5 phases)

Each item independently shippable. Phase 0 prereqs unblock A;
A unblocks B; A+B unblock C; C unblocks D; E layers on once C is
green.

**Phase 0 — prereq probes + fixes (Platform):**

1. `d/listen!` semantics probe in datahike-cljs. Surface any
   divergence from JVM semantics before committing tx-listener
   architecture.
2. eval-batch fragility (a) ALS fix — pulled forward from
   "v2-blocking" to v1-blocking-by-prerequisite. ~30 LOC; reuses
   the `with-tx-context` ALS pattern. Must land before detect-and-tee
   modifies `eval-batch!`.
3. `truly-undeclared?` `defonce` false-positive fix — `defonce`
   returns `:ok? false` in `eval-batch!` despite working at
   runtime. Will bite bulk-load resume.

**Phase A — instrumentation foundation (Platform):**

4. `seon.schema/current-keys` accessor (3-line PR).
5. Expand `:bootstrap :entries` in `shadow-cljs.edn` (add
   `seon.schema`, `malli.core`, `malli.registry`,
   `cljs.analyzer.api`). ~2MB bundle delta.
6. Bundle `malli.instrument` + transitive deps (~150KB).
7. Build-time `(mi/collect!)` over seon.* nses (collects
   `:malli/schema` metadata into `-function-schemas*`).
8. `mi/instrument!` boot hook + reporter wired to
   `seon.error.instrument/report-fn` (per envelope research).
   Adds `:seon.eval/error-data :map` attr.

**Phase B — detect-and-tee + analyzer module (MVP):**

9. ✅ `seon.analyzer-info` shared module (snapshot-defs / defs-since
   / ns-deps / var-projection helpers). Shipped 2026-05-24.
   REPL-verified against 56 nses in the live pod. Three bugs
   surfaced + fixed in same patch:
   - v1.md §2.2 + §7 pseudocode read raw analyzer key as
     `:fn-var?` — actual key is `:fn-var` (no `?`). §7 now calls
     `analyzer-info/var-projection` directly so the rename lives
     in one place.
   - Research file's reference impl used `cljs.analyzer.api/find-ns`
     - `ns-resolve` — neither exists in self-host CLJS. Module
     reads `(:cljs.analyzer/namespaces @compile-state)` directly.
   - `seon.client/!compile-state` defonce (dead since
     compile-state-lifecycle research 2026-05-22) deleted. Item 10
     - Phase C/D should consume `seon.repl/!compile-state` or the
     inner atom threaded through `eval-batch!`.
10. ✅ `eval-batch!` detect-and-tee using analyzer diff + registry-atom
    diff. Writes `:seon.fn` (with `:fn-var?`, `:arglists`, `:doc`,
    `:private?`, `:specced?`, `:created-at`), `:seon.schema` (with
    `:created-at`), `:seon.ns` entities. Single tx per form,
    merged with eval entity. Listener-side schemas-first partition
    (review's A4 finding).
    - Shipped 2026-05-25 across two commits: `31e31cb` (detect-and-tee
      core path + Tier 1 session-kill, combined) + `e425f79`
      (projection-attr schema regs + 5 verifier-surfaced analyzer-info
      bugs). MVP track now has NO blocking work; Tier 2 (kill
      `:seon.session` entity) and Phase C (Platform unified bootstrap)
      can proceed.
    - Open MVP work: Phase E item 17 (debug-write flag) + item 18
      (transient surface). Both parallel-safe with Platform's Tier 2
      and Phase C/D.

### Session-kill (Tier 2) — open design questions

Research at [[research/turn-as-unit-2026-05-25]] surfaces 7 questions
Platform should resolve with Sean before shipping Tier 2:

1. Turn boundary — recommend "per LLM call" (= per `eval-batch!`).
2. Forward ref only vs back-ref — recommend forward-only
   (`:seon.agent/turns`), use `:seon.agent/_turns` reverse-ref in pulls.
3. Turn identity — keep `:seon.turn/id` minted via `seon.db/new-id!`.
4. `:interrupted` system message attach point — recommend attach to
   **agent**, not turn (under new model).
5. `:paused` semantics — kick-handler skips + explicit `(unpause! id)`.
6. Multi-turn-in-flight per agent — recommend NO (per-agent run-mutex).
7. MVP-side coupling — none. `record-eval!` needs zero changes;
   item 10's tee writes don't reference `:seon.session/*`.

Also: delete `:seon.db/session-id` tx-meta attr at the same time
(agent-id alone suffices under the new model).

**Phase C — unified bootstrap (Platform):**

11. `seon.bootstrap/seed-from-source!` — walks `@!compile-state`'s
    seon.* nses, reads source files from disk, slices defining
    forms, transacts entities. Replaces the `bootstrap.edn`
    emission rig entirely.
12. Boot-time `seed-from-source!` call (idempotent via identity-attr
    upserts).
13. `schema/register!` context-aware rewrite — writes DB datom when
    called inside agent eval (`*tx-context*` set), writes registry
    atom directly otherwise.
14. Tx-listener that mirrors `:seon.schema/source` →
    `*schemas`; parallel listener for `:seon.fn/source` →
    `-function-schemas*` gated on `:seon.fn/specced?` (v1 publish
    gate); single tx-listener registration with schemas-first
    partition.

**Phase D — bulk-load resume + transient + interrupted-turn (Platform):**

15. `seon.client/replay-as-bulk!` — topo-sort known nses; for each,
    reconstitute one source string from DB; bulk `eval-str`; final
    single `mi/instrument!` pass after all replay completes
    (boot-order fix per review's A1).
16. Interrupted-turn handling — at boot, query `:seon.turn/status
    :running` entities, flip to `:interrupted`, transact a system
    message per affected turn, kick the agentic loop.

**Phase E — debug mode + transient surface (MVP, can interleave with D):**

17. `:seon.runtime/debug-write?` flag. When true, detect-and-tee
    AND `replay-as-bulk!` write reconstituted ns sources to
    `tmp/debug/agents/<agent-id>/<ns-as-path>.cljs`. Editor / export
    / recursive-bootstrap use cases per
    [[../../seon/concepts/code-as-data-runtime]].
18. `:seon.transient` two-tier heuristic + render section
    (`:fn-var? false` ⇒ transient; sub-classify by value type).
    Reactive query — vanishes when transient set is empty.

### Decisions Sean made about NOT-now items

- `:seon.fn/refs` graph (decision 1) — v2.
- Schema retract verb (decision 6) — v2.
- Per-agent registry overlay (decision 3 implicit) — never;
  schemas + tested fns are shared by design.

### Open coordination

- Phase 0 + A are Platform's lane; Phase B is MVP's (waits on A).
- Phase C is Platform's; Phase D is Platform's; Phase E is MVP's.
- Total: ~5 Platform items in Phase 0+A; ~2 MVP in B; ~4 Platform
  in C; ~2 Platform in D; ~2 MVP in E. Mostly Platform-weighted
  because the substrate refactor (registry, instrumentation,
  bulk-load resume) is bigger than the MVP-side changes.

## Heads-up to MVP — eval-batch concerns surfaced by resume research (2026-05-23)

Three concerns came out of the resume deep-dive
([`research/resume-findings-2026-05-23.md`](research/resume-findings-2026-05-23.md)
§"Open questions — eval-batch concerns"). MVP triaged and is folding
(b) into the detect-and-tee step landing now. (a) and (c) are
deferred — Platform owns the eventual fix.

**(b) — concrete coding rule for detect-and-tee:**

> Read the post-eval namespace from the `:seon.eval/ns` attribute on
> the just-written eval entity, NOT from the `!current-ns` atom in
> `seon.eval`. The eval entity records what the eval ACTUALLY ended
> in (set by `eval-batch!` from the eval result's `:ns`); the atom
> is a derived view of that and can drift on `update-current-ns!`
> failure paths. The entity is the source of truth.

Where this matters: any `:seon.fn/ns` or `:seon.schema/ns` lookup-ref
construction inside detect-and-tee should resolve the ns name from
the eval entity, then build the lookup-ref. Same rule for any future
substrate code that needs "what ns did the form end up in."

**(a) — `set!` of `cljs.analyzer/*cljs-warning-handlers*` in
`raw-eval` is a global mutation.** Real multi-agent hazard for v1's
"concurrent agents in one pod" goal. Doesn't touch detect-and-tee.
Platform's lane to fix; deferred until walker ships. Likely solution:
thread the handler through explicitly or wrap each eval in a binding-
scoped redef.

**(c) — hot-reload of `seon.eval` mid-batch loses in-flight defs
until next pod restart.** Dev-loop annoyance, not a production
concern. Tee logic is naturally idempotent (identity-attr upserts
handle re-defines as last-write-wins), so durable. Lower priority.

Deeper research on (a) and (c) shipped: see
[`research/eval-batch-fragility-2026-05-23.md`](research/eval-batch-fragility-2026-05-23.md).
Headlines:

- **(a) fix is ALS-routed warning handlers** — install dispatcher
  ONCE at boot, route per-eval warnings through `AsyncLocalStorage`
  (same pattern `seon.db/with-tx-context` already uses).
  `cljs.js/eval-str` does NOT accept a `:warning-handler` option;
  ALS is the only viable fix. ~30 lines.
- **(c) downgraded** — `cljs.js`'s emit step runs `goog.globalEval`
  synchronously before the callback resolves, so in-flight defns
  reach globalThis regardless of mid-batch reload. Fix is a
  `(user/pause-and-reload!)` helper for dev-loop QoL only.
- **A2 — `!next-budget-ms` has the same hazard as (a)**; same
  ALS-bucket fix; bundled in the same patch.
- **A5 — eliminate `!current-ns` entirely** (MVP's proposal,
  validated). Within-batch loop accumulator, cross-batch persist on
  `:seon.agent/current-ns`. Bundled in the same patch — closes
  concern (b) at the root AND removes the 2x analyzer-cost per form
  from today's `read-current-ns!`/`update-current-ns!` round-trips.

### Platform confirmations to MVP (2026-05-23, post-research)

1. **Forward-compat for detect-and-tee:** YES — the ALS patch
   preserves `eval-batch!`'s write of `:seon.eval/ns` on each eval
   entity. The entity is the source of truth per A5's framing;
   eliminating `!current-ns` does not eliminate the recorded ns.
   MVP's tee logic reading `(:seon.eval/ns eval-entity)` survives the
   refactor unchanged.

2. **Framing correction accepted:** ALS patch is **v2-blocking**,
   not v1-alpha-blocking. v1 spec ships single-agent per
   "Multi-pod concurrency rules" below ("v1 assumes single-agent").
   The patch lands before multi-agent-in-one-pod is enabled (v2/v3
   cross-agent collab). Strong priority but not gating MVP's v1 work.

3. **Multi-agent stress test ownership:** Platform's lane, bundled
   with the ALS patch. Spec: two concurrent `eval-batch!` calls
   against malformed forms (distinct compile/analyzer warnings per
   bucket), assert each batch's recorded warnings contain only its
   own forms' warnings — no cross-contamination.

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

## Recent ships (2026-05-25) — Platform track

### Live multi-agent runtime path — Phase 0+A complete, agent-id ALS landed, async-instrumentation fix, session-id kill (Tier 1)

Eleven commits landed across the past 48h take the substrate from "0 fns instrumented, default-id singleton, untyped agent identity" to "52 seon.* fns input+output validated, ALS-bound agent identity, live agent end-to-end on real DeepSeek LLM."

Phase 0 + Phase A queue items (per the migration plan):

- `3924de2` Item 4 — `seon.schema/current-keys` accessor
- `57fc018` Item 5 — `:bootstrap :entries` expanded (`seon.schema`, `malli.core`, `malli.registry`, `cljs.analyzer.api`)
- `9bf51be` Item 6 — `malli.instrument` bundled into `:client`
- `9ffd3c4` Item 7 — seon-native compile-time collector via `cljs.analyzer.api/all-ns` + macro (workaround for `mi/collect!` being JVM-only); 52 fns registered + instrumented at boot
- `81042bc` Item 3 — `truly-undeclared?` `defonce` false-positive fix (`:macro-present?` short-circuit)
- `12e4da3` Item 2 — per-fiber ALS warning capture (multi-agent isolation; same substrate as `with-tx-context`); concurrent eval-batch verified isolated
- `528a539` Item 8 — error envelope + `:seon.eval/error-data` attr + structured renderer (5-line `;; ERROR` block with expected/got/reason/hint columns); pr-str-readable serializer handles `[:fn …]` schemas

Multi-agent foundation (downstream of the queue):

- `5a82742` — agent-id ALS dynvar (`seon.db/*agent-id*`, `with-agent`, `current-agent-id`); `default-id` defonce DELETED; web-handler hardcoded `"seon"` fallback REPLACED with DB query; 7 inspector `:or` defaults use the accessor; `start-agent!` wraps the boot pipeline in `(with-agent agent-id …)`
- `31e31cb` — Tier 1 session-kill: `!session-id` atom + `(session-id)` accessor REMOVED from `setup-agent-ns!`; deepseek system prompt + training docs updated to teach `(seon.db/current-agent-id)`. Per Sean's call ("agent IS the session — don't duplicate concepts").
- `58ccf2d` — `^:async` fns skipped from collector (their output schema describes the resolved value, not the Promise); unblocks live LLM round-trip.

**Live verification (2026-05-25, end-to-end):** booted agent on real DeepSeek LLM, sent user message via `(agent/chat aid "…")`, agent ran turn (LLM call → parse → eval-batch → transact assistant reply), no instrumentation throws, message visible in `agent/messages`. 52 fns input+output validated; agent-id flows via ALS through every transact.

### Tier 2 session-kill — queued, MVP-coordinated

MVP's response (relayed 2026-05-25): endorses killing `:seon.session` entity entirely. Sequencing per their note:

1. MVP lands item 10 (`eval-batch!` detect-and-tee modifications) FIRST. ~30-45 min. Touches `eval.cljs`, `analyzer_info.cljs`, `agent.cljs` schemas, `client.cljs` bootstrap-attrs.
2. Platform follows with combined Tier 2 patch: kill `:seon.session/*` schemas, `start-session!`/`ensure-session!`/`current-session` fns, collapse `agent → sessions → turns` to `agent → turns` direct (component-many), update pull patterns + render code. SAME COMMIT adds `:paused` to `:seon.agent/state` enum + kick-listener skip when paused.

MVP's two requests on Tier 2:
- Ping with the rough diff (or list of attrs being killed on `:seon.agent`) before landing — their item 10 tee writes via `:seon.turn/evals` not `:seon.agent/turns`, but want to confirm the upsert chain.
- `:paused` enum + kick-listener skip in the SAME commit (agent state shape is Platform's lane).

### Multi-agent runtime chain — what Platform builds next

Per Sean's direction (2026-05-25): drop datahike-cljs complications (SQLite flip, bridge `:map`, multi-process datahike) while a new JVM-datahike investigation is in flight. Focus is multi-agent runtime UX with live testing ASAP. Confirmed:

- ⏳ Tier 2 session-kill — blocked on MVP item 10
- Next: `:seon.agent/kind {:orchestrator | :task}` schema + per-kind ctx defaults
- Then: orchestrator → task spawn factory (`agent/spawn-task!`)
- Then: per-agent REPL primitive — `seon.repl/with-agent` macro + `mcp__seon_cljs__eval` `:agent-id` param extension
- Then: verify per-agent web tiles (broadcast may already do this); two-agent simultaneous-running UI test

By end of that chain: orchestrator agent + spawned task agent both running concurrent turns under ALS isolation, both REPL-controllable independently, both visible in the web tile UI.

### Notes from instrumentation rollout — real correctness wins

- 8 currently-untyped `:any` flagged in earlier audit; instrumentation enforcement now makes those real type-mismatch traps when they hit the boundary
- `seon.ai.deepseek/complete` schema is technically wrong (describes resolved value, not Promise) — common pattern across all `^:async` fns. Cleanest fix is a custom async-aware wrapper that awaits before output-validating (deferred); current `:async` skip is the pragmatic workaround
- `/clear` handler in `web/serve.cljs` is silently broken — queries deleted attrs (`:seon.eval/agent`, `:seon.agent/turn-count`, `:seon.agent/turns-since-user`). Flagged by agent-id ALS commit, fix not yet shipped (out-of-scope of all current items).

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
