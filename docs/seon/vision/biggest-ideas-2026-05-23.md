---
type: research
status: active
tags: [research, vision]
---

# Biggest Ideas in the Seon Codebase — Exhaustive Hunt (2026-05-23)

Companion to `full-scope-synthesis-2026-05-23.md`. That file mapped the framing
and milestone scope; this one hunts for the **boldest, weirdest, most-original
claims** scattered across capability docs, archived branches, PRD research dirs,
and the v1 spec long-tail. Quotes are verbatim with cites so the README rewrite
can preserve the original ambition.

## Coverage log

- [x] Surface A: 29 capability docs on main (`docs/seon/vision/capabilities/*.md`) — all read in full
- [x] Surface B: 8 milestone docs (`m1-reliable-runtime`..`m8-autonomous-agents`) — all read in full
- [x] Surface C: 28 PRD top-level specs — `agent-repl-interface/prd.md`, `super-repl/prd.md`,
  `shape-graph/design.md`, `namespace-bootstrap/design.md`, `refinement/prd.md`,
  `spec-driven-rendering/prd.md`, `unified-namespace-flow/design.md`, `render-pipeline/prd.md`,
  `datahike-migration/prd.md`, `schema-unification/design.md`, `unified-flow/design.md`,
  `datalevin-reactive/design.md`, `logging-system/prd.md`, `test-infrastructure/design.md`,
  `graph-cleanup/prd.md`, `namespace-ui/prd.md`, `data-viewer/prd.md`,
  `agent-runtime/{README,v1,v2,v3,platform}.md` (v1 read across multiple long ranges).
  PRDs NOT individually read past summary: `dashboard-polish`, `datalevin-migration`,
  `datalevin-reactive` (only intro), `flow-datalevin-writer`, `mcp-resilience`,
  `schema-viewer`, `stability-improvements`, `startup-reliability`, `test-coverage-audit`,
  `_example-feature`.
- [x] Surface D: archive triage across `feature/refinement` (173 files) and `feature/super-repl`
  (133 files). Files actually read in full or near-full:
  1. `feature/refinement:docs/archive/primer/docs/primer/prd.md`
  2. `feature/refinement:docs/archive/primer/docs/primer/research/architecture-vision.md`
  3. `feature/refinement:docs/archive/primer/docs/primer/research/ctx-as-os.md`
  4. `feature/refinement:docs/archive/primer/docs/primer/research/state-machine.md`
  5. `feature/refinement:docs/archive/primer/docs/primer/research/seon-architecture-research.md`
  6. `feature/refinement:docs/archive/seon-transform/prd.md`
  7. `feature/refinement:docs/archive/seon-transform/notes.md`
  8. `feature/refinement:docs/archive/algorithmic-trading-agent/prd.md`
  9. `feature/refinement:docs/archive/algorithmic-trading-agent/research/agent-experience-design.md`
  10. `feature/refinement:docs/archive/algorithmic-trading-agent/research/repl-recording.md`
  11. `feature/refinement:docs/archive/auto-test-hook/prd.md`
  12. `feature/refinement:docs/archive/unified-dev-hook/research/data-model-redesign.md`
  13. `feature/super-repl:docs/archive/PLAN-original-transformation.md`
  14. `feature/super-repl:docs/archive/clojure-claude-sdk/prd.md`
  15. `feature/super-repl:docs/archive/clojure-claude-sdk/bidirectional-control.md`
  16. `feature/super-repl:docs/archive/dynamic-context/prd.md`
  17. `feature/super-repl:docs/archive/namespace-isolation/prd.md`
  18. `feature/super-repl:docs/archive/provider-agnostic-agents/prd.md`
  19. `feature/super-repl:docs/archive/observatory-xtdb/prd.md`
  20. `feature/super-repl:docs/archive/sql-migration/prd.md`
  21. `feature/super-repl:docs/archive/xtdb-browser/prd.md`
  22. `feature/super-repl:docs/archive/agent-isolation/research/sql-interface-design.md`
  23. `feature/super-repl:docs/archive/sse-live-reload/prd.md`
  24. `feature/super-repl:docs/archive/namespace-render-toggle/README.md` (small, almost empty)
  Triaged 60+ filenames; the above are the ones with unique design content.
  Smaller archive dirs on `feature/agent-isolation` triaged — its `archive/` files are
  duplicates of refinement/super-repl content (auto-test-hook, test-suite-fixes,
  code-cleanup). Not separately re-read.
- [x] Surface E: PRD research dirs spot-checked. The richest are the
  agent-runtime `research/*-2026-05-22.md` consultations (already cited in prior
  synthesis); schema-unification's `serialization-findings.md` and `nil-semantics-findings.md`
  (cited via ADRs 001/002); shape-graph's design is one document; bootstrap research is in the
  PRD itself; the rest are operational findings, not vision.
- [x] Surface F: `docs/seon/architecture/overview.md` read in full; ADRs 001-007 read
  (headers + rationale). `CLAUDE.md` consumed as system context.
- [x] Surface G: `docs/prds/agent-runtime/v1.md` read across ranges 1–400, 400–900,
  1100–1635 (the deferred-to-v2/v3 sections and the implementation protocol §11). v2.md
  read 1–200. Platform.md read 1–200 + 400–571.

Honest gaps: 8 of 28 PRDs (the smaller status/polish ones listed above) only previewed,
not read line-by-line. v2.md sections after line 200 (blob / hard-rules / curation functions)
not exhaustively read — the v1 deferral table tells us what's in them. Archive triage
across `feature/mcp-resilience`, `feature/namespace-ui`, `feature/sse-live-reload`,
`feature/stability-improvements`, `feature/unified-dev-hook` not done — those branches
have small archive/ dirs and the file names overlap with what is already in
refinement+super-repl.

## 1. The 10 biggest ideas (ranked)

Each is a load-bearing claim Sean has made, in his own (or his prior agent's)
words. Ranked by ambition + how much they shape the rest of the system.

### #1 The system composes itself. Agents wake on notifications, write code, and the next request is handled automatically without wiring.

`docs/seon/vision/m8-autonomous-agents.md:55-58`:

> "Next time a risk score is available alongside a signal, the discovery
> mechanism finds `adjust-for-risk` as the most specific handler. **No wiring.
> No registration. The system composed itself.**"

`docs/seon/vision/m8-autonomous-agents.md:8-10`:

> "The agent does not need Claude Code. It does not edit files. It receives
> Malli-specced messages through flow, evaluates forms in the REPL pipeline,
> and its work is validated, persisted, and tested automatically."

Why it's big: this is the difference between "AI tool" and "AI core." The
README's framing-A "agents own code long-term" hand-waves at this; M8 makes it
concrete (notification → wake → write handler → next message handled by
discovery → return to idle). It's the load-bearing claim under "personal AI
that can do anything."

### #2 The filesystem is a persistence format. The graph database IS the system. The REPL is the only interface.

`docs/seon/vision/index.md:110-112`:

> "The file system is a persistence format, not the source of truth. The graph
> database is the system. The REPL is the only interface agents need."

`docs/prds/agent-repl-interface/prd.md:9-11`:

> "Agents develop Clojure code exclusively through REPL eval — no file editing,
> no line numbers, no `clojure_replace`. The `*ctx*` atom is the agent's entire
> world."

`docs/prds/agent-runtime/v1.md:90-99`:

> "**Context is `fn(DB)`, not an accumulated log.** The rendered prompt the LLM
> sees each turn is derived freshly from current DB state by running the section
> fns. It is not a transcript that grows; it is a projection."

Why it's big: every other "AI codes for you" product treats files as canonical
and the database as a cache. Seon inverts this. The agent's eval IS the
transaction; tx-meta carries the agent/session/turn/eval bundle; resume
re-evals the program-graph entities in tx order to rebuild the program. The
file is a side effect, written via `(seon/persist!)` like `git commit`.

### #3 One discovery mechanism, used for everything. Rendering, transformation, event handling, validation, testing, AI context — all are "find the most specific function whose schema matches."

`docs/seon/vision/index.md:79-95` (table) summarised as:

> "No separate rendering system, subscription system, test runner, or event
> system. **One discovery mechanism. Functions that match are functions that
> work.**"

`docs/seon/vision/m4-discoverable-codebase.md:5-7`:

> "One mechanism serves all use cases — rendering, transformation, event
> handling, validation, testing. An agent that needs to do something with data
> does not grep source files or hallucinate function names."

`docs/seon/vision/m7-namespace-as-process.md:8-10` and the
`capabilities/inter-agent-messaging.md:7-15` "smart defaults" pattern reinforce
that this is *the* primitive, not a feature.

Why it's big: collapses ~five subsystems (router, dispatcher, render registry,
subscription system, test selector) into one query. M4 is where the unbuilt
half of Seon (M6/M7/M8) becomes possible.

### #4 Every namespace is a long-running flow process with a typed mailbox. The default step function ships everything; agents add specificity over time.

`docs/seon/vision/m7-namespace-as-process.md:5-8`:

> "Every namespace is a flow process with typed inputs, a custom step function,
> and state that participates in the reactive surface. **The default step
> function handles everything generically. Agents add specificity over time** —
> a custom step function, subscription handlers, feed signal reactions — and
> each addition makes the namespace more capable without changing anything else
> in the system."

`docs/seon/vision/capabilities/inter-agent-messaging.md:14-23`:

> "The router uses the same specificity algorithm as renderer-discovery: ...
> If no handler exists, apply the smart default for that message type. If the
> message requires acknowledgment and no handler exists, wake the namespace's
> agent."

Why it's big: progressive enhancement as architecture. Ship the minimum;
agents grow the specific behaviors as they're needed. Nothing else in
AI-for-code-land works this way today.

### #5 Bitemporal history as agent memory. The agent can ask "what did this function return last week?"

`feature/super-repl:VISION.md` (in prior synthesis, branch-citation):

> "XTDB provides bitemporal history: Valid time: When was this fact true in the
> world? Transaction time: When did we record this fact? Agents can query:
> 'What did this function return last week?' 'How has this data evolved?'
> 'What changed between working and broken?' Most databases can't answer these
> questions. **For agents learning from experience, they're essential.**"

Current core: `docs/prds/agent-runtime/v1.md:1107-1115`:

> "`:keep-history? true` on the agent conn. Tx-meta datoms only persist when
> history is on. ... Pulling `[:seon.eval/id "K9p…"]` returns the eval entity
> AND every tx-meta datom on it. `(d/q '[:find ?e ?a ?v ?op :in $ ?tx :where
> [?e ?a ?v ?tx ?op]] (d/history db) [:seon.eval/id "K9p…"])` returns the
> datoms that eval wrote."

`docs/prds/agent-runtime/v1.md:442-446`:

> "**The eval-IS-tx mechanic.** ... `(d/history db) [:seon.eval/id "K9p…"]`
> returns the datoms that eval wrote."

Why it's big: the agent's mistakes are first-class data. Replay is a query,
not an instrumented capture. Datahike's keep-history? makes the XTDB bitemporal
pitch survive into the current core — this is the most under-sold property
of the database choice.

### #6 The agent never knows whether code runs in-process, in a separate JVM, or in a WASM sandbox. The dispatch layer routes transparently.

`docs/prds/unified-namespace-flow/design.md:747-755`:

> "Every namespace is an actor with isolated state and functions. The system
> routes data to functions based on Malli schema matching. ... **Two execution
> modes share one interface:** In-process — function runs in the core Seon JVM
> (fast, no serialization). Separate process — function runs in an agent JVM
> (sandboxed, TCP/Nippy). **The agent never knows or cares which mode it's
> in.** It requires namespaces, calls functions, gets results."

`docs/prds/agent-runtime/platform.md:18-24`:

> "**Self-hosted CLJS eval inside WASM.** Agent emits any valid CLJS; it runs
> under wasmtime + wasm-rquickjs with the analyzer fully populated, error
> shapes meaningful, and async/await native."

Why it's big: the core is mode-agnostic. A function call is "send map,
receive map" whether the target is in your JVM or a wasmtime instance in a
Tauri host on a stranger's laptop. This is where the WASM track becomes part of
the user-facing pitch, not just deployment plumbing.

### #7 Capability surface as a WIT-typed boundary. Every external action — HTTP, fs, npm install, MCP — flows through a host-decided interface. No ambient authority.

`docs/prds/agent-runtime/platform.md:42-50`:

> "**Capabilities are explicit.** Every external action (HTTP, fs, npm install,
> package fetch) flows through a WIT import the agent can see and the host can
> deny. No ambient authority."

`docs/prds/agent-runtime/platform.md:464-472` (Phase 7):

> "The agent has explicitly bounded access. Every external action — HTTP, fs
> read, fs write, npm install, package fetch — flows through a WIT import. The
> Tauri host decides which to grant. ... Production deployments lock down
> everything except `eval`; development unlocks `fs`/`http`/etc."

`CLAUDE.md` (current-focus block):

> "The CLJS sandbox layer is NOT a security boundary — the agent's `cljs.js`
> eval can mutate the `!config` atoms or `(js/require "node:fs")` directly. It
> catches LLM hallucinations, not adversarial code."

Why it's big: the alpha-blocking work isn't the AI; it's the containment that
makes "personal AI that can do anything" safe to deploy on the user's machine.

### #8 The agent can install dependencies live, from inside the sandbox, with capability-prompted approval.

`docs/prds/agent-runtime/platform.md:30-34`:

> "**Dynamic deps.** Agent runs `(seon.deps/install ...)` from the REPL and
> acquires new CLJS or npm packages without a core rebuild.
> Capability-bounded: fs cache + HTTP + (eventually) a curated registry, all
> via WIT imports."

`docs/prds/agent-runtime/platform.md:480-502` (end-state vignette):

> "Agent decides it needs a new dep. `(seon.deps/install '[reagent/reagent
> "1.2.0"])`. Pod fetches, caches, makes analyzer-visible. Returns `:installed`.
> Agent requires + uses it. ... Today's agent can write the deftest and run it.
> They can't install the dep — that's Phase 5. **Path is clear; core is
> ready to grow into it.**"

Why it's big: the agent isn't just writing code in a pre-bundled environment —
it's growing its own toolbox at runtime. This is the explicit end-state in the
platform spec, not a stretch goal.

### #9 The system is self-referential. The functions that discover other functions, route messages, and validate code are themselves discoverable through the same mechanism.

`docs/seon/vision/index.md:103-105`:

> "The system uses itself. The functions that discover other functions, route
> messages, and manage the graph — they are themselves registered in the graph
> with spec'd inputs and outputs. An agent looking for 'how do I query the
> graph?' discovers `seon.graph.query/functions-in-ns` through the same
> mechanism it would use to find a trading signal calculator."

`docs/seon/vision/m6-eval-pipeline.md:80-87`:

> "**Constraint Enforcement Is Function Discovery.** The constraints above are
> not hard-coded in the pipeline. Each constraint is a function with a spec'd
> input and output. ... The eval pipeline discovers all functions matching this
> signature and runs them. To add a new constraint — say, 'function names must
> not exceed 40 characters' — write a function that accepts `::eval/form` and
> returns `::constraint/result`. It's picked up automatically on next eval. ...
> **Turtles all the way down.**"

Why it's big: the system's quality gates are extensible without changing the
pipeline. Seon evolves itself through the same primitive it evolves user code.

### #10 The core is general-purpose. The same architecture powers a Diamond Age-style "Primer" — an AI tutor for children.

`feature/refinement:docs/archive/primer/docs/primer/prd.md` Vision section:

> "An interactive educational experience for children, inspired by Diamond
> Age's 'Young Lady's Illustrated Primer.' **The Core Insight:** The AI doesn't
> 'generate HTML' — it generates state transitions. Templates are pre-built.
> This gives: 1. Instant rendering (no waiting for AI to generate markup).
> 2. Deterministic replay (same state = same view). 3. Composable complexity
> (templates call templates). 4. Debuggable (inspect state at any point)."

`feature/refinement:docs/archive/primer/docs/primer/research/architecture-vision.md`:

> "A Primer session is a server-controlled state machine where: Scene =
> current state (data structure). Templates = render functions (scene →
> hiccup). Transitions = valid next states (AI-driven or user-triggered).
> Checkpoint = serialize scene to XTDB for replay/debugging. **The AI doesn't
> 'generate HTML' — it generates state transitions.**"

Why it's big: Trading/health/finance are routine. A literary-grade tutor for a
child, built on the same core, is the most evocative existence proof of
"personal AI that can do anything because it can write code." Worth one line in
the README.

## 2. Multi-language ambitions

Hunting for any mention of Python, multi-language, polyglot, language-agnostic,
WASM-as-packaging, runtime portability.

**WASM as the multi-language envelope (current direction).**
`docs/prds/agent-runtime/platform.md:18-23`:

> "**Self-hosted CLJS eval inside WASM.** Agent emits any valid CLJS; it runs
> under wasmtime + wasm-rquickjs with the analyzer fully populated."

`docs/prds/agent-runtime/platform.md:454-463` (Phase 6 — WASM pod, dynamic
npm deps):

> "**Pre-bundle a 'universal' npm set** — pick 50 common packages, bundle once.
> Limited but works without host bridge. **CDN-based** — fetch UMD/ESM builds
> from unpkg.com over `wasi:http`, eval into globalThis as a synthetic module."

`pod-host/wasm-tauri/` is the Rust+WIT workspace; the WIT world drafts `fs`,
`mcp`, `capability-prompt` interfaces for any guest, not just CLJS.

**The CLJS-vs-CLJ lane discipline (today) reads as a stepping stone.** CLAUDE.md:

> "for spec-01 `seon.*` API surfaces the V0 core uses **`.cljs` files
> alongside the existing `.clj` files**, not `.cljc` (yet). ... `seon.schema`
> is the one exception — promoted to `.cljc` 2026-05-16 because the file was
> 100% platform-portable. Other promotions wait until both sides converge on
> the spec §3 map-in/map-out + `*conn*` shape."

**Why-Not-Python lives in the vision doc.** `docs/seon/vision/index.md:264-267`:

> "**Python**: Dynamic, but mutable-by-default. No built-in spec system.
> Ecosystem churn. **TypeScript**: Types help, but object-oriented heritage.
> Build complexity. Node ecosystem churn. **Clojure**: Immutable,
> data-oriented, stable, REPL-native. The language is designed for what we're
> doing."

**Explicit "Python next" framing in Sean's brief but NOT in the on-disk docs.**
The current docs lock in Clojure; Sean's user-supplied direction
("CLJS via WASM today, Python next, anything WASM-compatible eventually")
adds Python as the next pod-language. The architecture supports it (WIT is
language-agnostic; only `seon.eval.cljs` is CLJS-specific), but no doc on disk
says "Python pod" today.

**Inter-process language neutrality at the wire.** ADR 001
(`docs/seon/architecture/decisions/001-nippy-serialization.md`): Nippy is
Clojure-specific. If Python pods land, this is the first explicit
contradiction — wire would need to be Transit/Avro/Protobuf, or each pod
serialises its own data via host-readable JSON over WIT. **Worth flagging in
§7.**

## 3. Specific ambitious claims

Quotes bolder than typical "infrastructure" framing.

**On timelines.** `docs/seon/vision/index.md:298-304`:

> "This is a bet that: 1. **AI agents will write most code within 5 years**.
> 2. Current approaches (bolt-on assistants) won't scale. 3. Purpose-built
> infrastructure dramatically improves agent reliability."

**On non-developers as users.** `feature/super-repl:VISION.md` validation
criteria (via prior synthesis, retained because it's still load-bearing):

> "**Medium-term (6 months):** Non-developer gives problem → agents build
> working solution. System suggests improvements based on usage patterns."

**On bonded service.** `feature/refinement:docs/archive/seon-transform/prd.md`:

> "**Seon** — from the archaic 'to see', and inspired by the Seons of Brandon
> Sanderson's *Elantris*: **sentient, luminous beings that serve and assist
> their bonded humans**."

**On the agent's ownership of code.** `docs/seon/vision/index.md:14-20`:

> "Not a framework. Not a library. A complete codebase architecture where
> agents can: Discover functions by their contracts (not hallucinate them).
> Learn from history (not repeat mistakes). **Own code long-term (not just
> complete tasks).** Compose safely (not break each other's work)."

**On McCarthy.** `docs/seon/vision/index.md:40-41`:

> "McCarthy designed Lisp for AI. **Maybe the killer app was always agents
> writing Lisp.**"

**On the personal-OS framing.**
`feature/super-repl:docs/archive/PLAN-original-transformation.md`:

> "Transform ml-options-trading into **Seon** - a personal 'OS for life' with
> modular domains (trading, health, finance, tasks, knowledge)."

**On agent learning from its own past.**
`feature/refinement:docs/archive/algorithmic-trading-agent/research/repl-recording.md`:

> "How do we capture the full REPL session for training data, separating what
> the LLM saw (limited context) from the full values (for replay/debugging)?
> ... **Training Data** — Capture sessions to train future agents."

That last one is striking — sessions aren't just memory, they're a training
corpus. The current v1 spec keeps the door open: every prompt-text and every
eval source survives on disk, indefinitely, replayable.

**On the agent being asked nothing.**
`docs/seon/vision/m8-autonomous-agents.md:14-58`:

> "`seon.trading.signals` has an agent. The agent is idle ... A developer adds
> a new attribute ... This triggers a schema change notification. ... The agent
> wakes. It sees the notification in its context. **It decides this new
> attribute is relevant** — risk scores should affect signal confidence. It
> writes a function ..."

**On WASM containment as the alpha-blocking work.** CLAUDE.md current-focus
block:

> "**Where we're going (Phase 3 — alpha-blocking):** WASM-Tauri containment.
> ... The Rust host decides what to grant. The agent's CLJS code cannot reach
> beyond the WIT imports — wasmtime enforces."

## 4. Recurring themes (3+ documents)

These themes show up in vision + capabilities + PRDs + archives. They are the
load-bearing ideas Sean has returned to across 18+ months.

1. **Map-in / map-out / namespaced everywhere.** CLAUDE.md, conventions.md,
   every capability doc, every PRD. Non-negotiable.
2. **Schemas are the discovery key, the validation gate, the documentation, the
   test generator — one Malli registration does all four.**
   `vision/index.md:130-135`, `m3-convention-uniformity.md`,
   `schema-unification/design.md`, ADR 004,
   `archive/primer/research/seon-architecture-research.md` ("Schema-first
   design — entities defined before code").
3. **Datalog as the agent's first move.** `vision/index.md:75-95`,
   `m4-discoverable-codebase.md`, `shape-graph/design.md`,
   `agent-runtime/v1.md:262-275` (root pull),
   `archive/xtdb-browser/prd.md`. The agent queries the graph before it greps.
4. **Two-tier rendering — HTML for humans, AI text for agents — from the same
   function.** `vision/index.md:75-95` table, `m5-observable-system.md`,
   `render-pipeline/prd.md`, `spec-driven-rendering/prd.md`,
   `m6-eval-pipeline.md` "composable AI renderers." A renderer can produce
   `{:seon.render/html ... :seon.render/ai ...}` in one map.
5. **Three-tier storage (DB datoms = projections; blobs = full content; stash
   = volatile per-session).** Memory-pin in user MEMORY.md, full in
   `agent-runtime/v1.md:103-115` and `platform.md` "where state goes" table.
6. **Bitemporal history / time-travel debugging.** XTDB era
   (`archive/seon-transform/prd.md`, `archive/observatory-xtdb/prd.md`), survives
   into Datahike era (`agent-runtime/v1.md:1107-1115`,
   `datahike-migration/prd.md` "gain time-travel debugging for free").
7. **Progressive enhancement = ship the default, replace with specificity.**
   `vision/index.md:120-128`, `concepts/progressive-enhancement.md`,
   `m7-namespace-as-process.md`, `inter-agent-messaging.md`,
   `bootstrap_v2.clj` impl.
8. **REPL is the core, not the file.** `vision/index.md:99-112`,
   `m6-eval-pipeline.md`, `super-repl/prd.md`, `agent-repl-interface/prd.md`,
   `agent-runtime/v1.md`. This is the most consistently restated claim
   in the codebase.
9. **Personal hardware, local-first, the user's data stays the user's.**
   `vision/index.md:271-275` ("Local-first means: your data stays yours, no
   API rate limits, works offline, full control over the runtime"),
   `agent-runtime/platform.md` (Tauri desktop host), `archive/PLAN-original-transformation.md`
   ("OS for life").

These nine are the README's load-bearing skeleton.

## 5. Surprises (archived, dropped from current vision)

Ideas alive in the archives but missing from `vision/index.md`. Each deserves
a one-line shout in the README rewrite.

### 5.1 The Primer — Diamond Age tutor on the same core

(Already #10 in §1.) `feature/refinement:docs/archive/primer/` is a ~650 LOC
working prototype with full design docs for a child's interactive storytelling
system built on the core. Demonstrates: scene = state, template = render
fn, transition = valid next state, checkpoint = bitemporal store. Nowhere in
current vision/index.md.

### 5.2 SQL as the agent's query language

`feature/super-repl:docs/archive/agent-isolation/research/sql-interface-design.md`:

> "**Agents likely know SQL better than XTQL.** ... **Design Decision: SQL
> Only.** Rationale: 1. Agents know SQL — it's universal. 2. SQL is XTDB v2's
> primary interface. 3. One syntax to learn, not two."

Current core is Datahike + Datalog. The "agents know SQL" insight got
buried in the XTDB→Datahike pivot. **Worth reviving as a wrapper layer** —
agents could be given a SQL surface over Datahike for joins they'd otherwise
struggle to express in Datalog.

### 5.3 Training data as a first-class output

`feature/refinement:docs/archive/algorithmic-trading-agent/research/repl-recording.md`:

> "1. **Training Data** — Capture sessions to train future agents. 2. **Replay**
> — Be able to replay exactly what agent saw. 3. **Full Values** — Access
> complete data for any step."

Two-level storage (what the agent saw vs. the full values) is exactly the
three-tier rule in v1. The training-data framing is gone from the current
vision but the data captured by v1 is sufficient to train models on. **README
should mention** that every Seon session is a fine-tunable corpus by
construction.

### 5.4 Provider-agnostic agent loop

`feature/super-repl:docs/archive/provider-agnostic-agents/prd.md` (status:
complete, but archived as "single provider suffices"):

> "1. **Provider Abstraction** — Add a new model provider (e.g., Gemini agents)
> without rewriting agent lifecycle code. 2. **Shared Message Format** — All
> providers produce `::ai/message` entities that can be stored, queried, and
> analyzed uniformly."

The archive's "single provider suffices" is current-core-correct (V0 pod
runs against deepseek alone), but the design preserves multi-model. Worth a
sentence in §7 contradictions.

### 5.5 Game-engine planning vs execution model

`feature/refinement:docs/archive/primer/docs/primer/research/ctx-as-os.md`:

> "**Planning Phase (slow, AI/designer):** Pre-compute possible futures.
> Register behaviors: 'if X, do Y'. Queue assets to load. **Execution Phase
> (fast, 60fps):** Check registered behaviors. Execute matching ones.
> Interpolate/render state. ... **The agent is in planning phase.** It doesn't
> generate responses in real-time — it sets up conditional logic ahead of
> time."

This frames agents not as "generate text" loops but as game-AI planners — they
set up behaviour tables in advance and the system runs them at low latency.
Compatible with the current message-routing design but not stated anywhere on
the main branch.

### 5.6 The ctx-as-OS pattern

Same file as 5.5:

> "**The entire system is one data structure. UI is derived. Agent writes data.
> Specs constrain writes.**"

This is the strongest one-sentence summary of the architecture anywhere in the
archives. Current vision/index.md never quite says it this cleanly.

### 5.7 Frozen-time agent sessions

`feature/refinement:docs/archive/algorithmic-trading-agent/research/agent-experience-design.md`:

> "**The agent thinks it's 'today'.** They query data, get current results.
> The frozen time is an implementation detail they never see."

A trading agent that operates against a historical snapshot is a backtesting
core — but the design generalises to "agent runs against any point in
DB history." Datahike + `:keep-history?` makes this trivial.

### 5.8 "Death from boredom" — agents going idle is normal, not a bug

`docs/seon/vision/m8-autonomous-agents.md:21-23` mentions agents idling, but
the strongest statement is implicit:

> "An idle agent consumes no resources. A notification wakes it. It processes
> the notification, does its work, and returns to idle."

The contrast with the polling-loop assumption baked into every other
agent-platform is the surprise.

### 5.9 Agents fork their own children

This isn't in current main, but
`feature/super-repl:docs/archive/clojure-claude-sdk/prd.md` describes an SDK
where Clojure code spawns Claude Code subprocesses. Combined with M8 + namespace
agents, this implies a tree of agents: the user works with one agent which
spawns subagents per namespace. The orchestrator model in the current code
(`src/seon/orchestrator/`) implements a slice of this.

## 6. One-line README candidates

Drawn directly from the material with cites. Pick one, or layer two of them.

1. **"A personal AI that can do anything for you because it can write code."**
   (Sean's spoken pitch, 2026-05-23; preserved in
   `docs/seon/vision/full-scope-synthesis-2026-05-23.md:46-48`.)

2. **"Infrastructure for AI agents to write reliable software."**
   (Current `README.md:3`, `docs/seon/vision/index.md:13`.)

3. **"AI agents will write most software. The question isn't *if* but *how
   well*."** (`docs/seon/vision/index.md:7-9`.)

4. **"A Clojure runtime designed so AI agents can write, own, and evolve
   software reliably."** (Commit `924820e` README — recoverable via `git show
   924820e:README.md`.)

5. **"The entire system is one data structure. UI is derived. Agents write
   data. Specs constrain writes."**
   (`feature/refinement:docs/archive/primer/docs/primer/research/ctx-as-os.md`.)

6. **"Seon — sentient, luminous beings that serve and assist their bonded
   humans."** (`feature/refinement:docs/archive/seon-transform/prd.md`. Pairs
   well with #1 above.)

7. **"McCarthy designed Lisp for AI. Maybe the killer app was always agents
   writing Lisp."** (`docs/seon/vision/index.md:40-41`. Best closer, not
   opener.)

8. **"The filesystem is a persistence format. The graph database is the
   system. The REPL is the only interface agents need."**
   (`docs/seon/vision/index.md:110-112`.)

9. **"Local-first means your data stays yours. No API rate limits. Works
   offline. Full control over the runtime."** (Paraphrased from
   `docs/seon/vision/index.md:271-275`.)

10. **"The same core that tracks your trades also tutors your kids — and
    the agent decides which to build first."** (Composite, drawing from the
    Primer archive + the M7 health-namespace scenario. Not a verbatim quote;
    closest to it: `m7-namespace-as-process.md:14-28` + `primer/prd.md`
    Vision section. Use only if the README wants a single-sentence existence
    proof.)

**Recommended composition for the rewrite:** lead with #1 (consumer-facing
promise), follow with #2 or #3 (the "why now" wedge), name the primitives with
a derivative of #4 (the technical paragraph from 924820e), and close with #7
(the McCarthy line). The Sanderson Seon name (#6) goes near the bottom as
naming/lore — it's evocative but only lands once the reader knows what the
product is.

## 7. Contradictions to resolve

Each of these is a tension between two places in the codebase. The README
rewrite should pick a side, or settle the contradiction in a vision-update
sequence.

### 7.1 Datahike is the database (resolved)

Resolved 2026-05-23: the database is **Datahike, embedded in-process** `[JVM track — paused]` —
no TCP, no separate JVM. (This holds on the paused JVM track; the active CLJS pod
forwards writes over a Unix socket to the `wire-server` central writer.) The current-state docs (vision/index.md,
capability docs, milestones) were swept to remove stale Datalevin
references. The `datahike-migration` PRD remains as the historical record.

When current-state prose needs to refer to the database generically
("a Datalog store on LMDB", "Datalog queries"), prefer "Datalog"; when
product specificity matters (deps, config, namespace names), use
"Datahike". Historical PRDs and migration docs are not rewritten — they
describe the journey, not the destination.

### 7.2 Single-language vs multi-language

`docs/seon/vision/index.md:259-269` argues Clojure as a *requirement*, not a
choice. Sean's direction (2026-05-23) is "CLJS via WASM today, Python next,
anything WASM-compatible eventually."

The architecture is already mode-agnostic
(`docs/prds/unified-namespace-flow/design.md:747-755`) but the vision doc
locks Clojure in. If the README leads with "language-agnostic," the vision
doc and the "Why Clojure" table need to be re-framed as "we picked Clojure
for the core; the pods can be any WASM-compatible language."

**Concrete inconsistency to fix:** ADR 001 picks Nippy (Clojure-specific) for
inter-JVM wire. If non-Clojure pods land, Nippy is wrong. **Either** keep
Nippy and confine non-Clojure pods to WIT-typed function calls (no shared
in-memory data), **or** swap Nippy for a polyglot wire format.

### 7.3 JVM core vs WASM pod

Two cores ship today:

- JVM lane in `src/seon/*.clj` — datahike, flow, web, all the milestone work
  (M1–M5) is on this. The capability docs describe this as "the system."
- CLJS pod in `src/seon/*.cljs` — V0 core running `node out/client/main.js`,
  hosting the agent loop, with datahike-cljs and a loopback HTTP+SSE server.
  This is what the v1 spec is being written against. Phase 3 will move this
  into a wasm32-wasip2 component inside Tauri.

Question for the README: which one is "Seon" today? Both? Most readers will
assume "the WASM thing" is roadmap. The current state is "two functioning
cores that need to converge." Sean's note in CLAUDE.md: "the convergence
is a deliberate Stage 2/3 step in the convergence plan."

**Recommendation:** the README's "Active tracks" section (already exists at
`README.md:c0c2888`) is the right shape — keep it, but make it crystal clear
that the JVM core is "what works today end-to-end" and the WASM pod is
"the deployable form factor we are building toward and have shipping smokes
for."

### 7.4 The core is the product vs the core is a means to an end

`README.md:c0c2888` framing A:

> "The personal domains (trading, health, finance) are test cases. The
> infrastructure is the product."

Sean's pitch (2026-05-23 + the archive's "OS for life" framing):

> "A personal AI that can do anything for you because it can write code."

These are not contradictory — the core exists *so that* the personal AI
is reliable — but the current README emphasises the wrong half. To a
first-time visitor, "infrastructure for AI agents" reads as developer-tooling
for AI researchers; "personal AI that can do anything" reads as consumer
product.

**Recommendation:** lead with the consumer claim; the infrastructure claim is
the *how*, not the *what*. The prior synthesis already proposes this layered
opening; this hunt confirms the support material is in the codebase to back
it up.

### 7.5 Agent owns code long-term vs agents are ephemeral REPL sessions

`docs/seon/vision/m8-autonomous-agents.md:5-7` (one agent stewards one
namespace; idle until woken; wakes on notification; "long-term ownership")
versus `docs/seon/vision/capabilities/agent-isolation.md` (agents are
JVM-pool-acquired, instrumentation deferred to claim-time, dispose on death)
versus `docs/prds/agent-runtime/v1.md` (sessions span pod runs; agents
have IDs and stable home-ns; resume re-evals the program-graph on boot).

The reconciliation: the *agent identity* is persistent (Datahike entity with
ID, home-ns, sessions, ctx). The *agent process* is ephemeral (claims a JVM
or boots a WASM pod, runs until idle/done/crashed, releases). The README
needs to say this clearly — without it the M8 framing reads as
"continuously-running daemons," which is the wrong mental model.

### 7.6 :keep-history? true (forever) vs blob GC / forget!

`docs/prds/agent-runtime/v1.md` ships with `:keep-history? true` and no
GC. `v2.md` adds `:db/noHistory` opt-outs on high-churn scalars and `forget!`
/ `forget-ns!` curation functions. `v3.md` adds blob GC and per-blob TTL.

For the README: "all history retained forever" is a wonderful claim until the
disk fills. The story is "v1 keeps everything; v2 lets you curate; v3 has
proper retention." Skirt it in the README; let the v1/v2/v3 doc speak for
itself.

### 7.7 Core authors files vs agent authors files

`docs/prds/agent-repl-interface/prd.md`: "Agents develop Clojure code
exclusively through REPL eval — no file editing, no line numbers, no
`clojure_replace`."

CLAUDE.md (multi-agent git safety, lane discipline): assumes Claude Code
subagents that DO edit files. The current orchestrator launches Claude Code
agents that operate via Edit/Write/clojure_replace.

The reconciliation: "REPL-first" is M6 — the unbuilt future. The current
agents (which the orchestrator runs) edit files because M6 hasn't shipped.
The README should not promise "agents never edit files" today. It should
promise "the REPL is the agent's interface; files are a persistence format."

### 7.8 Multi-language wire serialisation

(Covered in §7.2 — repeated here so it's not lost in the contradiction
list.) Nippy commits the wire format to Clojure-readable serialisation. Python
pods would need a different format. The choice deferred so far is implicit;
the README rewrite shouldn't surface it but a vision update should.

### 7.9 "Test cases, not the product" vs the Primer existence proof

`README.md:c0c2888`: "The personal domains (trading, health, finance) are
test cases. The infrastructure is the product."
`feature/refinement:docs/archive/primer/`: a fourth domain (children's AI
tutor) that demonstrates the core's generality much more vividly than
the canonical three.

The README should either (a) update the list to include the Primer (with the
caveat that it lives on a different branch), or (b) reframe "domains" as
"applications that prove the core." The current "trading / health /
finance" enumeration reads as the *complete* set, which is wrong.
