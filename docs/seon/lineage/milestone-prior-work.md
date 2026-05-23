---
type: research
status: active
tags: [research, prior-art, milestone]
---

# Milestone Prior Work — Evidence Audit

## Statement of Purpose

This document is the honest, evidence-backed status of each of Seon's eight
milestones (M1–M8). The current README Status table lists some milestones as
"not started" or "partial" — but Sean has stated explicitly that working code
exists at different points in time for every milestone. This audit hunts
across:

- The current `seon` repo (all branches, including unreachable commits)
- Published predecessor repos (`seon-biff`, `seon.biff`, `seon-look-into`,
  `ml-options-trading`)
- Local-only sibling repos (`primer`, `seon-old-base`, `seon.bak`, `seon.main`,
  `seon-gsap`, `seon-visualizations`, `cljs-chat-interface`)

For each milestone we document what the spec claims, where prior working code
lives, the current README status, a recommended new status, and a one-line
table-row replacement.

The deliverable at the end is a markdown Status table ready to paste into the
README, replacing today's table with one that no longer reads "not started"
where working code in fact exists.

## The 8 Milestones

### M1: Reliable Runtime

**What the milestone claims.** Agents run in isolated JVMs that cannot corrupt
each other; cross-boundary calls route through one flow topology; the on-disk
Datalog store survives crashes; the pool self-heals; startup is deterministic.

**Evidence found.**

- Current `seon` repo / `main`: `src/seon/flow/{topology,harness,pool,msg,trace,status,agent_runner}.clj` —
  full `topology/request!`, `flow.pool` self-healing JVM pool, harness TCP
  bridge, pool warmup with health gates. The `flow/harness.clj` defines
  `namespace-step` (line 77) — the orchestrator-side step-fn that already
  treats namespaces as flow processes.
- Current `seon` repo: `src/seon/db/datahike/*.clj` plus the connection
  manager in `seon.db` — Datahike embedded LMDB store, per-DB locking,
  two-phase startup. Crash-survival is the default behavior of the on-disk
  LMDB log.
- Branch `feature/agent-isolation` ships isolated agent JVMs over TCP nREPL
  (`bin/nrepl`, `dev/nrepl.clj`, `docs/prds/agent-isolation/research/nrepl-multi-server.md`).
  This is the prior name for the harness work that landed on main.
- Branch `feature/namespace-isolation` — the architectural predecessor:
  `docs/prds/namespace-isolation/prd.md` plus per-namespace process
  scaffolding.
- Branch `feature/mcp-resilience` carries the MCP-side resilience work
  (async dispatch, cancellation, non-blocking init) that the M1 capability
  notes list as complete.
- Predecessor `ml-options-trading` (= `seon-2025-11-trading-domain`):
  `src/ml_options/system.clj`, `src/ml_options/runner.clj` — Integrant
  lifecycle, `seon.db`-style single-API pattern, REPL dev loop. The
  ancestor of the current two-phase startup.

**Current README status.** `partial`.

**Recommended new status.**
`partial — flow backbone, pool, embedded Datahike crash-survival all live; atom-watches + state registries still bypass flow.`
This matches the milestone doc's own "What Remains Honest" section verbatim.

**One-line replacement.**
`| [M1: Reliable runtime](docs/seon/vision/m1-reliable-runtime.md)                | partial — flow + pool + embedded Datahike all live on main; atom watches still bypass flow |`

### M2: Trustworthy Data

**What the milestone claims.** Single Malli-driven schema registration;
validated writes; Nippy preserves types across JVM boundaries; no `:any` in
persisted schemas; absence (not nil) for optional fields; generative
roundtrip tests.

**Evidence found.**

- Current `seon` repo / `main`: `src/seon/schema.cljc` is the single
  registration point (`schema/register!`). `src/seon/db.clj` validates every
  transaction via Malli before handing to Datahike. `test/seon/db/pipeline_test.clj`
  + `test/seon/db/validation_test.clj` are the generative roundtrip tests
  (Phase 1–4 of the schema-unification project — see memory pointer
  `Schema Unification Progress`).
- Nippy wire protocol on inter-JVM TCP: `src/seon/flow/harness/channel.clj`
  uses Nippy `fast-freeze`/`fast-thaw` (Phase 4, shipped).
- The `:seon.db/ref` type and the `seon-db-props->db-props` bridge live in
  `seon.schema` + `src/seon/db/datahike/schema.clj`.
- Predecessor `ml-options-trading`: `src/ml_options/db/{schema,transactions,queries}.clj` —
  the original "validated transact" pattern (Malli + a single db API). This
  is where the convention was first established.
- Predecessor `seon-biff` (`seon-2025-02-architecture`): the schema-as-EAV
  alignment is documented at length in the 1936-line README (sections
  2.3 "Fully Namespaced Alignment", 6 "Database Schema") and codified in
  `src/seon/schema.clj` + the `seon.app.tasks` example. Clojure Spec was the
  vehicle then; Malli is the current vehicle; the convention is the same.
- Memory pointer `Schema Unification Progress`: Phases 1–5b completed
  (validation gate, pipeline roundtrip, ctx/repl/runtime Malli-derived,
  Nippy channel, identity attrs migrated to `:seon.db/identity`).

**Current README status.** `partial`.

**Recommended new status.**
`partial — validation gate + Nippy + generative roundtrip live on main; :any holdouts in wire protocol/render + duplicate ::db-name/::namespace registrations remain.`

**One-line replacement.**
`| [M2: Trustworthy data](docs/seon/vision/m2-trustworthy-data.md)                | partial — validation gate, Nippy, generative roundtrip live; :any holdouts in flow/msg.clj + render.clj remain |`

### M3: Convention Uniformity

**What the milestone claims.** Every public function uses map-in/map-out with
`:malli/schema`, all keys namespaced, no `:any`/`[:maybe X]`, no duplicate
registrations, no dead code, graph indexes function schemas.

**Evidence found.**

- Current `seon` repo / `main`: `src/seon/dev/{hook,compliance,instrumentation,lint,review,test_select,clojure_replace}.clj` —
  the dev-hook pipeline that enforces conventions on every edit. This
  *enforces* M3 on new code as it lands.
- Memory note "All public functions with `:malli/schema` metadata are
  instrumented at runtime. There is no off mode." — instrumentation managed
  by Integrant (`:seon.dev/instrumentation`) survives `(user/reset)`. This is
  M3's runtime teeth.
- `src/seon/graph/{ingest,query,scanner,extract,analyzer}.clj` — the graph
  already stores function input/output specs as Datahike refs to spec
  entities with `contains-keys` / `optional-keys`. Output-key discovery is
  in `gq/functions-with-output-key`.
- The milestone's "What Remains Honest" section enumerates the gap honestly:
  many functions still lack schemas, three rendering systems coexist, ~14
  copies of `::db-name`, etc. Memory note "Codebase-wide convention audit"
  confirms this is in-flight.
- Predecessor `seon-biff` README §2.3 + §3.4 (App Registry System) — the
  convention rule "Code, database, and specifications share identical
  namespacing" is *the* North Star idea, formalized ten months before this
  repo existed.

**Current README status.** `in progress`.

**Recommended new status.** No change — "in progress" is already honest.
Possibly tighten to: `in progress — hook enforces on new code; legacy schema duplication + dead-code sweep ongoing.`

**One-line replacement.**
`| [M3: Convention uniformity](docs/seon/vision/m3-convention-uniformity.md)      | in progress — dev hook enforces on new code; legacy schema duplication + dead-code sweep ongoing |`

### M4: Discoverable Codebase

**What the milestone claims.** "Given this data shape, what functions work
with it?" — one mechanism (the specificity-ranking algorithm renderer
discovery already uses) generalized to all function discovery.

**Evidence found.**

- Current `seon` repo / `main`: `src/seon/render.clj` `resolve-renderer` is
  production. It delegates to `gq/functions-with-output-key`, ranks by
  matched required keys, namespace-proximity tiebreak, cache invalidated on
  code changes. **This is M4's algorithm working in production for one
  output type.**
- `src/seon/graph/query.clj` — query API over the code graph; functions,
  call edges, specs all queryable.
- Memory note "Shape Graph — IMPLEMENTED": 138 shapes, 333 entries indexed.
  `route-data!` works end-to-end: data → graph discovery → cascade execution
  → pruning → results. Reactive: consumers register interest in data shapes.
  No consumers = no execution. (See `project_shape_graph.md` in user memory.)
- Memory note "Malli Decode as Dispatch": `m/decode` + the
  `dependent-default-transformer` is the injection layer. `:default/fn`
  provides values when keys missing. This is the dispatch wiring around
  discovery.
- Predecessor `seon-biff` README §3.4 (App Registry System, lines 374–522)
  and the original spec-discovery design — the architectural source.

**Current README status.** `partial`.

**Recommended new status.**
`partial — renderer discovery + shape-graph indexing live; generalized gq/discover and unification of three SSE/render/AI-context paths remain.`

**One-line replacement.**
`| [M4: Discoverable codebase](docs/seon/vision/m4-discoverable-codebase.md)      | partial — renderer discovery + shape graph (138 shapes / 333 entries) live; generalized discover API + path unification remain |`

### M5: Observable System

**What the milestone claims.** Live dashboard; agent observatory; schema
browser; data explorer; single SSE push path; single rendering system;
status badge unification.

**Evidence found.**

- Current `seon` repo / `main`: `src/seon/web/*` — Datastar/SSE web layer,
  components, handlers. `src/seon/ns/routes.clj` renders namespaces.
- Agent observatory: working — live conversation logs exposed in the UI
  (see capability note `agent-observatory`).
- Reactive UI: SSE push on ctx changes, cache invalidation on code changes.
  Renderer discovery (above) drives namespace views.
- Dashboard polish PRD: `docs/prds/dashboard-polish/prd.md` — explicit work
  item to bring dashboard to design-system standard.
- Predecessor `seon-visualizations` (local, 2026-03-04, 11 commits): React +
  Vite presentation layer for seon concepts. Not part of the current
  runtime but documents the visualization explorations.
- Predecessor `seon-biff` README §2.4 "Responsive Multi-Level Visualization"
  (Micro/Tile/Small/Medium/Full sizes) — the design intent that the current
  observability layer is converging back toward.

**Current README status.** `partial`.

**Recommended new status.** No major change — but the schema browser and
data explorer are genuinely not built. Suggest: `partial — agent observatory + reactive SSE live; schema browser, data explorer, three-way SSE/render unification still ahead.`

**One-line replacement.**
`| [M5: Observable system](docs/seon/vision/m5-observable-system.md)              | partial — agent observatory + reactive SSE live; schema browser, data explorer, unification of three SSE paths still ahead |`

### M6: The Eval Pipeline

**What the milestone claims.** Agents develop through the REPL exclusively.
Eval pipeline classifies forms, runs discoverable constraint functions,
updates `*ctx*`, rejects invalid forms. `*ctx*` carries schemas, functions,
tests, history, issues. `(seon/persist!)` graduates from `:live` to
`:persisted` on disk.

**Evidence found.**

- **Current `seon` repo / branch `feature/super-repl`:**
  `src/seon/repl/super.clj` (282 lines). This *is* the eval pipeline: form
  router that receives forms, classifies via edamame, stores in Datalevin
  (now Datahike), routes to agent JVMs, updates the code index after each
  eval. Schemas: `::source`, `::namespace`, `::agent-id`, `::form-type`.
  The Datalevin schema for form storage (`:form/id`, `:form/namespace`,
  `:form/type`, `:form/name`, `:form/source`, `:form/agent-id`,
  `:form/version`, `:form/created-at`) is the persistence layer M6 needs.
- **Current `seon` repo / `main`:** `src/seon/repl/graduate.clj` —
  *literally* the M6 graduation step. This is the persisted name of what
  the spec calls `(seon/persist!)`.
- **Current `seon` repo / `main`:** `src/seon/repl/context.clj` — the
  `*ctx*` container for REPL session state.
- **Current `seon` repo / `main` (CLJS pod):** `src/seon/eval.cljs`
  (617 lines) — full eval pipeline implementation: per-form eval timeout,
  resolves-on-globalthis check, raw-eval, public `eval`, budget control,
  bootstrap caches. This is the v1 pod's working eval pipeline.
- **Current `seon` repo / `main` (CLJS pod):** `src/seon/repl.cljs`
  (213 lines) + `src/seon/agent.cljs` (585 lines, see M8) — the CLJS-pod
  REPL surface the agent uses.
- **PRD:** `docs/prds/agent-repl-interface/prd.md` (577 lines) — the M6
  spec, 6 phases, fully specified.
- **PRD:** `docs/prds/super-repl/prd.md` (620 lines) — the original Super
  REPL spec. Branch `feature/super-repl` is the working implementation
  branch.
- **PRD:** `docs/prds/agent-runtime/v1.md` §4 "Eval pipeline"
  (lines 566–697) — the WASM-track rewrite of the eval pipeline,
  REPL-verified per the implementation protocol.
- **Predecessor `seon-biff/src/seon/agent.clj`** (446 lines) — the
  interceptor-chain FSM eval pipeline from Feb–Mar 2025: state machine with
  `:generate-next-spec`, `:execute-code`, `:retry-spec-generation`,
  `:finalize`. Specs registered, functions registered, history tracked, all
  in a `system-ctx` atom. Constraint enforcement was syntax + spec
  validation via execute-code. **This is M6's earliest working prototype,
  ten months before the current repo existed.**
- **Predecessor `seon-biff/src/seon/router.clj`** (125 lines) — the
  http-handler-as-namespace-eval pattern: every namespace gets a `ctx`
  atom, requests are merged into the ctx, the function transforms ctx and
  returns the new state, which is reset! into the atom.

**Current README status.** `not started`.

**Recommended new status.**
`prototyped — super-repl branch + repl/graduate.clj on main + CLJS pod eval.cljs/repl.cljs/agent.cljs all working; earliest FSM at seon-biff/src/seon/agent.clj (Feb–Mar 2025). Constraint-fn discovery not yet wired.`

**One-line replacement.**
`| [M6: Eval pipeline](docs/seon/vision/m6-eval-pipeline.md)                      | prototyped — feature/super-repl + repl/graduate.clj + CLJS pod eval.cljs/repl.cljs all working; constraint-fn discovery not yet wired |`

### M7: Namespace as Living Process

**What the milestone claims.** Every namespace is a flow process with custom
step function, typed inputs, ctx atom, feed signals (`:seon.ns/feeds`),
subscription inputs, and smart defaults. Default step function handles
request/reply, state persistence, SSE push out of the box.

**Evidence found.**

- **Current `seon` repo / `main`:** `src/seon/flow/harness.clj` defines
  `namespace-step` (line 77) — "Orchestrator-side namespace process step-fn.
  Returns a map compatible with namespace-step init (in-ports/out-ports)".
  The default step function exists and is in production for every
  orchestrated namespace.
- **Current `seon` repo / `main`:** `src/seon/ctx.clj` + `src/seon/ctx/*`
  — per-namespace ctx atoms with schema validation on swap, persistence to
  disk, SSE push. The capability note `unified-context` lists this as
  complete.
- **Current `seon` repo / `main`:** `src/seon/flow/topology.clj`,
  `flow/msg.clj`, `flow/pool.clj` — the routing backbone +
  request/reply + observability. Capability `flow-topology` listed
  complete.
- **Concept doc:** `docs/seon/concepts/namespace-as-process.md` — the
  current architectural framing of M7.
- **PRD:** `docs/prds/namespace-isolation/prd.md` (on `feature/namespace-isolation`
  branch) — the working specification for per-namespace processes.
- **PRD:** `docs/prds/namespace-ui/decisions.md` — the UI-side decisions for
  what a "namespace as living process" looks like to the browser/agent.
- **Predecessor `seon-biff/src/seon/router.clj`** (125 lines) — the
  pre-flow incarnation of M7: every namespace has a `ctx` atom, HTTP
  requests are routed by namespace+function-name, the request map is
  namespace-qualified, merged into ctx, fn invoked with the merged map, and
  ctx is `reset!`-ed to the result. **This is M7's default step function,
  expressed as Ring handlers in Feb 2025.** Cross-namespace coordination
  was via WebSocket update handlers (`ws/register-handlers`, e.g.
  `task-created` → `seon.app.tasks/handle-task-created-update`).
- **Predecessor `seon-biff/src/seon/app/tasks.clj`** (499 lines) — the
  concrete example: `(defonce ctx (atom {::tasks {}}))`, WS update handlers
  registered to the ctx, namespaced specs, render functions, action
  handlers. A working "namespace as living process" in 2025-02.
- **Predecessor `seon-biff` README §4.2 "Dynamic Session App"** (lines
  1144–1300) — the runtime-generated session namespace: `create-ns ns-sym`,
  `intern ns-sym 'ctx (atom {...})`, intern specs, intern a SessionApp
  deftype implementing `(init/render/handle-action)`. **This is namespace
  spawning as a first-class operation — ten months before the current
  repo.**
- **Predecessor `seon-biff` README §3.4 "App Registry System"** (lines
  374–522) — the registration/discovery layer for namespaces-as-apps.

**Current README status.** `not started`.

**Recommended new status.**
`prototyped — default namespace-step + ctx atoms + topology already in production on main; custom step-fn discovery, :seon.ns/feeds, subscription inputs not yet built. seon-biff/router.clj is the design ancestor (Feb 2025).`

**One-line replacement.**
`| [M7: Namespace as living process](docs/seon/vision/m7-namespace-as-process.md) | prototyped — default namespace-step + ctx atoms + flow topology in production; custom step-fn discovery and feeds/subscriptions remain |`

### M8: Autonomous Namespace Agents

**What the milestone claims.** Agents steward namespaces through the REPL
without human orchestration. They receive typed notifications about failing
tests, schema changes, consumer requests, and respond by writing functions
through the eval pipeline. Idle agents wake on notifications; system
composes itself.

**Evidence found.**

- **Current `seon` repo / `main` (CLJS pod):** `src/seon/agent.cljs`
  (585 lines) — the working v1 agent loop. Key fns:
  - `create!` (line 306) — spawn an agent record
  - `boot!` (line 334) — bring up
  - `chat` (line 360) — send a turn
  - `run-turn!` (line 447) — execute one turn end-to-end
  - `run-agentic-loop!` (line 541) — **the autonomous agent loop**
  - `install-user-trigger!` (line 285) — install a DB trigger that wakes
    the agent on user-message inserts
  - `user-message-handler` (line 261) — the wake-up handler
  - `per-agent-shape?` (line 225) — agent-scoped shape predicate
  This is M8 in its smallest working form: an agent that lives in a single
  JVM/pod, receives typed messages from a DB trigger, runs its loop, and
  evals through the M6 pipeline.
- **Current `seon` repo / `main` (CLJS pod):** `src/seon/client.cljs`
  (552 lines) — the agent runtime: HTTP+SSE loopback server, DeepSeek
  integration, bootstrap CLJS compiler. The runtime the agent runs on.
- **PRD:** `docs/prds/agent-runtime/v1.md` §6 "The agent loop"
  (lines 976–1088) and §10 "Worked example — one turn end-to-end"
  (lines 1325–1425) — fully specified, REPL-verified working code (per
  the implementation protocol in §11).
- **PRD:** `docs/prds/agent-runtime/STATUS.md` — implementation status
  tracker.
- **Current `seon` repo / branch `feature/agent-isolation`:** per-agent
  isolated JVMs over TCP nREPL — the multi-agent extension of the same
  loop.
- **Current `seon` repo / branch `feature/super-repl`:** the M6 pipeline
  the agent loop calls into.
- **`src/seon/orchestrator/session.clj`** — orchestrator-side agent session
  management.
- **Predecessor `seon-biff/src/seon/agent.clj` `development-loop`**
  (lines 372–392) — the autonomous loop in its earliest form:
  ```clojure
  (defn development-loop
    ([] (development-loop @system-ctx))
    ([ctx]
     (loop [current-ctx ctx iterations 0]
       ...
       (let [next-ctx (execute-step current-ctx)]
         (reset! system-ctx next-ctx)
         (if (= (:state next-ctx) :terminated) ...)
         (recur next-ctx (inc iterations))))))
  ```
  Drives the FSM (gather requirements → generate spec → execute → retry →
  generate function → execute → finalize) entirely from an `initialize`
  call. This *is* an autonomous agent eval loop, driven by a (simulated)
  LLM. Feb–Mar 2025.
- **Predecessor `seon-biff/src/seon/llm_simulator.clj`** (206 lines) —
  the loop's eval-side counterpart (an LLM-stub that produced the next
  form to eval).
- **Predecessor `cljs-chat-interface`** (local, 2025-01-25 → 08-08,
  137 commits): per the lineage doc, this is the "Magic Wand interface
  triggers session evolution" repo — the UX-level ancestor of "agent
  evaluates forms that grow the system."
- **Predecessor `ml-options-trading/src/ml_options/agent/analysis.clj`**
  — an `agent` namespace in the immediate git ancestor of `seon` (Nov–Dec
  2025).

**Current README status.** `not started`.

**Recommended new status.**
`prototyped — single-agent autonomous loop working in v1 CLJS pod (src/seon/agent.cljs run-agentic-loop!); multi-agent isolation on feature/agent-isolation; earliest FSM at seon-biff/agent.clj (Feb 2025). Typed notification routing, inter-agent messaging not yet built.`

**One-line replacement.**
`| [M8: Autonomous agents](docs/seon/vision/m8-autonomous-agents.md)              | prototyped — single-agent run-agentic-loop! working in CLJS pod (src/seon/agent.cljs); typed notifications + inter-agent messaging remain |`

## Recommended Status Table Replacement

Paste this into the README in place of the current Status table:

```markdown
| Milestone                                                                      | Status |
| ------------------------------------------------------------------------------ | ------ |
| [M1: Reliable runtime](docs/seon/vision/m1-reliable-runtime.md)                | partial — flow + pool + embedded Datahike all live on main; atom watches still bypass flow |
| [M2: Trustworthy data](docs/seon/vision/m2-trustworthy-data.md)                | partial — validation gate, Nippy, generative roundtrip live; `:any` holdouts in flow/msg.clj + render.clj remain |
| [M3: Convention uniformity](docs/seon/vision/m3-convention-uniformity.md)      | in progress — dev hook enforces on new code; legacy schema duplication + dead-code sweep ongoing |
| [M4: Discoverable codebase](docs/seon/vision/m4-discoverable-codebase.md)      | partial — renderer discovery + shape graph (138 shapes / 333 entries) live; generalized `gq/discover` API + path unification remain |
| [M5: Observable system](docs/seon/vision/m5-observable-system.md)              | partial — agent observatory + reactive SSE live; schema browser, data explorer, unification of three SSE paths still ahead |
| [M6: Eval pipeline](docs/seon/vision/m6-eval-pipeline.md)                      | prototyped — `feature/super-repl` + `repl/graduate.clj` + CLJS pod `eval.cljs`/`repl.cljs` all working; constraint-fn discovery not yet wired |
| [M7: Namespace as living process](docs/seon/vision/m7-namespace-as-process.md) | prototyped — default `namespace-step` + ctx atoms + flow topology in production; custom step-fn discovery and feeds/subscriptions remain |
| [M8: Autonomous agents](docs/seon/vision/m8-autonomous-agents.md)              | prototyped — single-agent `run-agentic-loop!` working in CLJS pod (`src/seon/agent.cljs`); typed notifications + inter-agent messaging remain |
```

Vocabulary key:

- **partial** — implemented in main; documented gaps to close
- **in progress** — main has tooling that enforces it; legacy cleanup ongoing
- **prototyped** — working code exists (in this repo's branches, in the
  CLJS pod, or in a published predecessor) but the spec-level milestone
  isn't crossed on main yet

No row reads "not started" because, on the evidence, none of these is.

## Caveats

- **Unreachable commits not exhaustively scanned.** The brief mentions
  ~393 unreachable commits under `git fsck --no-reflogs --unreachable`. I
  scanned reachable branches comprehensively and the published
  predecessors. Targeted hunts in unreachable commits could surface
  additional prior art — particularly for M7's `:seon.ns/feeds` and
  subscription inputs, which I did not find concrete working code for.
- **`primer/`** has only `ROUGH_PLAN-NEEDS-REFINEMENT.md` and no `.clj`
  files on disk. The "Primer" lineage referenced in the README points to
  `feature/primer-foundation` in the current repo and to the
  `cljs-chat-interface` evolution model. The local `primer/` directory does
  not currently hold significant prior code.
- **`cljs-chat-interface`** was identified in the predecessors document as
  the UX-level M8 ancestor (137 commits, "Magic Wand" pattern). I did not
  open its source within the budget; the predecessor doc's characterization
  is the basis for the M8 reference.
- **`seon-look-into` (= `seon-2024-10-kit-migration`)** was not opened
  within budget; per the predecessors doc it contributed "datomic-storage;
  early agentic-runtime hints" but its concrete code was not inventoried.
- **`seon.bak`, `seon.main`, `seon-gsap`, `seon-visualizations`,
  `seon-old-base`** were not deep-scanned. Per the predecessors doc they
  are either Biff baselines (`seon.bak`), React presentation layers
  (`seon-visualizations`, `seon-gsap`), or short-lived. None likely change
  the milestone story but a thorough scan would harden it.
- **`feature/refinement`, `feature/sse-live-reload`, `feature/unified-dev-hook`,
  `feature/polymarket-analysis`, `feature/stability-improvements`,
  `feature/primer-foundation`, `feature/namespace-ui`** were not each
  opened. Most appear to be in-flight feature branches whose work has
  either landed on main or remains in-progress. Targeted scans could
  surface additional capability evidence (especially `feature/namespace-ui`
  for M5's dashboard polish).
- **The M7 spec describes `:seon.ns/feeds` and subscription inputs as
  not-yet-built.** I found the default-step + ctx atom prior art clearly
  but did not find evidence of feeds/subscriptions as working code in any
  scanned source — the M7 status genuinely remains "prototyped without the
  reactive surface."
- **The README's M6 entry I am recommending against ("not started") is
  the single biggest call.** I am confident there is *working* eval-pipeline
  code in three independent places (super-repl branch, CLJS pod, seon-biff
  predecessor). The constraint-fn-discovery layer the spec requires is the
  thing not yet built, but the pipeline-as-form-router is real.
