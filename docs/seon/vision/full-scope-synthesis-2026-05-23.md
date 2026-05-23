---
type: research
status: active
tags: [research, vision]
---

# Full-Scope Synthesis for README Rewrite

## 1. TL;DR

- **Seon is a personal AI that can do anything for you, because it can write code.** The agent IS the product; the codebase architecture is the substrate that lets the agent ship reliable software for its human, on the human's hardware.
- **The product is a substrate, not a feature set.** Concretely it is: a Datalog graph database of every function and schema, a REPL that is the only valid way to add or change code, Malli contracts on every public function, and namespace-scoped agents that own their code long-term.
- **Personal domains (trading, health, finance) are test cases, not the product.** They prove the substrate works. Anything a person can describe — track this metric, watch this market, render this dashboard, react to this event — becomes a namespace the substrate can grow.
- **Eight milestones, M1 → M8.** M1–M5 (Reliable Runtime → Observable System) are partial/in-progress today. M6 (Eval Pipeline), M7 (Namespace as Living Process), M8 (Autonomous Namespace Agents) are the unbuilt half — that's where "agent owns and evolves the namespace" becomes real.
- **WebAssembly containment is the deployment story, not the project.** Phase 3 puts the CLJS pod inside a `wasm32-wasip2` component running in wasmtime, embedded in a Tauri Rust host with a WIT-typed capability surface (`fs`, `http`, `mcp`, `capability-prompt`, `eval`). It is what makes "the agent can do anything" survivable.

## 2. The Product Framing

There are three live framings on disk. They are compatible but emphasize different things, and the public README currently leads with the narrowest of the three.

### Framing A — current README (`README.md:3-7`, commit `c0c2888`)

> **Infrastructure for AI agents to write reliable software.**
>
> Not a framework. Not a library. A codebase architecture where agents can discover functions by their contracts, learn from history, own code long-term, and compose safely.
>
> The personal domains (trading, health, finance) are test cases. The infrastructure is the product.

Audience: other infra/agent researchers. Sells the substrate. Says nothing about what an end-user gets.

### Framing B — `docs/seon/vision/index.md:7-24` (current main vision)

> # Seon Vision
>
> ## The Thesis
>
> AI agents will write most software. The question isn't *if* but *how well*.
>
> Current approaches are broken: agents bolt onto codebases designed for humans, hallucinate interfaces, have no memory, step on each other, and ship broken code. The result is increasingly fragmented codebases with mounting technical debt.
>
> **Seon is infrastructure for AI agents to write reliable software.**

Same product claim as A, but with the "why now" wedge. This is the strongest version of the substrate framing.

### Framing C — the user's spoken pitch (2026-05-23)

> A personal AI that can do anything for you because it can write code.

This is the consumer-facing claim. It is absent from the current README and only implicit in the vision doc (the line "The personal domains (trading, health, finance) are test cases" gestures at it). The substrate framings (A and B) explain *how*; framing C explains *what for*.

### The 924820e framing (commit `924820e`, the oldest README)

> A Clojure runtime designed so AI agents can write, own, and evolve software reliably. Every namespace is wired in as a `core.async.flow` process with a typed message envelope and an injected, schema-validated state atom; functions are discovered via Malli schema contracts queried from a Datalog graph (Datalevin / Datahike on LMDB) rather than by name lookup or file imports.

This is the most *technically descriptive* one-paragraph framing the project has ever had publicly. It names the load-bearing primitives (`core.async.flow`, Datalog graph, Malli, schema-discovery) in one sentence. Commits `35b912e` / `584b08c` then deleted this in favor of the slogan-only "Infrastructure for AI agents to write reliable software." That deletion is the scrub the user remembers.

### Reconciliation

A new README should layer all three: lead with C (what the user gets), follow with A/B (what makes it reliable), and keep the 924820e technical sentence as the "how it's built" mid-section paragraph. The personal-AI claim and the substrate claim are not in tension — the substrate exists *so that* the personal AI is reliable enough to trust. Burying that connection (as the current README does) makes the project look like a niche Clojure architecture exercise instead of an alpha product.

## 3. Capability Scope — what a finished Seon lets a user DO

Pulled from M1–M8 + 28 capability docs under `docs/seon/vision/capabilities/`. Concrete scenarios, not abstractions.

### From the milestone scenarios (verbatim setups)

**Track a new personal metric** (M7 scenario, `m7-namespace-as-process.md:14-28`)

> `seon.health.workout` starts with the default step function. It handles requests via `topology/request!`, persists ctx changes to the database, and pushes SSE updates to connected browsers. Standard behavior, no custom code.
> A user starts logging workouts. The health agent notices a pattern: after every workout log, three other namespaces poll for the updated workout count. The agent decides to add a feed signal.

User says "track my workouts." The substrate spins up a namespace with default behavior. The agent on the namespace observes how it's being used and adds reactivity. No human writes a schema, a route, or a SSE handler.

**Display a new data type** (M4 scenario, `m4-discoverable-codebase.md:14-37`)

> A new domain namespace `seon.health.metrics` stores body weight measurements. An agent needs to display them in the UI. It has never seen the health namespace before.
> `(gq/discover {:seon.discover/input-keys #{:seon.health/metric-id :seon.health/value :seon.health/unit} :seon.discover/output-key :seon.render/html})`
> The agent did not need to know that `seon.health.render/metric-card` exists. The graph found it by matching the data's keys against function input schemas.

Renderers, transformers, validators, event handlers — all discovered by shape, not by import. New data lands; the system finds the most specific handler. If none exists, the agent writes one and it's discoverable on the next request.

**Survive a crash without losing work** (M1 scenario, `m1-reliable-runtime.md:14-33`)

> An agent working on `seon.trading.signals` hits an infinite loop. Its JVM pegs a CPU core and stops responding to health checks.
> 1. The pool's health monitor detects the unresponsive JVM after the grace period expires.
> 2. ... A pre-warmed replacement JVM is already available.
> 3. The operator (or orchestrator) relaunches the agent. It acquires a fresh JVM, opens its embedded Datahike connection, and picks up where it left off -- its session history is in the database, not in the dead process.

**Agents react to schema changes upstream** (M8 scenario, `m8-autonomous-agents.md:14-58`)

> A developer adds a new attribute to `seon.trading.positions`: `:seon.trading.positions/risk-score`. This triggers a schema change notification ... The message routes to `seon.trading.signals` because the graph shows a dependency edge ...
> The agent wakes. It sees the notification in its context. It decides this new attribute is relevant -- risk scores should affect signal confidence. It writes a function ...
> Next time a risk score is available alongside a signal, the discovery mechanism finds `adjust-for-risk` as the most specific handler. No wiring. No registration. The system composed itself.

This is the load-bearing scenario for framing C. The agent isn't asked. The agent isn't human-orchestrated. The agent's namespace receives a typed notification, the agent reacts by writing code, and the next time the situation arises the system handles it autonomously. *That* is "personal AI that can do anything because it can write code."

### The capability inventory (under `docs/seon/vision/capabilities/`, 28 docs)

Grouped by what the user gets:

| User-facing capability | Source | What it delivers |
|---|---|---|
| Track any new domain | `m7-namespace-as-process.md`, `capabilities/namespace-persistence.md` | A namespace ships with default behavior; agent grows specificity over time |
| See live state of everything | `capabilities/agent-observatory.md`, `system-dashboard.md`, `reactive-ui.md`, `data-explorer.md`, `schema-browser.md` | Phosphor Terminal dashboard with SSE-pushed updates, agent logs, schema browser, data explorer |
| Ask "what can this data do?" | `capabilities/function-discovery.md`, `renderer-discovery.md`, `code-graph.md` | Datalog query over function schemas; specificity-ranked results; no grep |
| Get reliable code | `m2-trustworthy-data.md`, `capabilities/validated-writes.md`, `data-contracts.md`, `code-quality-pipeline.md` | Malli validation on every write; generative roundtrip tests; eval pipeline rejects schema-less code |
| Agents that own their code | `m8-autonomous-agents.md`, `capabilities/agent-isolation.md`, `repl-first-development.md`, `inter-agent-messaging.md` | Namespace-scoped agents, typed mailboxes, schema-change notifications, persistent stewardship |
| Survive failure | `m1-reliable-runtime.md`, `capabilities/pool-self-healing.md`, `resilient-writes.md`, `mcp-resilience.md`, `self-monitoring.md` | Isolated JVMs (substrate) or WASM components (Phase 3), pre-warmed pool, separate DB process, auto-replenishment |
| Learn from history | `vision/index.md` Layer 6, `capabilities/agent-log-access.md` | All messages persisted; session replay; pattern extraction; mistake tracking |

This list deliberately leaves out the partner WebAssembly track — covered in §4 as a pillar, not a capability.


## 2. The Product Framing

_pending_

## 3. Capability Scope

_pending_

## 4. Architectural Pillars

Each pillar is a load-bearing idea. Without any one of them, the personal-AI claim collapses into a Cursor competitor.

### 4.1 Namespace-as-process

`docs/seon/concepts/namespace-as-process.md`, milestone `m7-namespace-as-process.md`. Every namespace is a `core.async.flow` process with a typed message envelope, an injected `*ctx*` atom, and a default step function that handles request/reply, persistence, and SSE push out of the box. Custom behavior is opt-in via a `{:seon.flow/step true}` function. Quoting `m7-namespace-as-process.md:8-10`: "A namespace is still just functions, specs, and tests. That does not change. What changes is the runtime envelope: the flow process that routes messages to the namespace, manages its state, and connects it to the rest of the system."

### 4.2 Schema-as-contract (Malli, namespaced everywhere)

`vision/index.md:56-70`, `m2-trustworthy-data.md`, `m3-convention-uniformity.md`. Every public function is map-in, map-out, `:malli/schema` on the var. Every persisted attribute is registered via `seon.schema/register!`. No `:any`, no `[:maybe X]`, no bare keywords. The schema *is* the documentation, the discovery key, the validation gate, the test generator. `vision/index.md:62`: "Every key is globally unique and queryable" — `:seon.trading/position`, never `:position`.

### 4.3 REPL-as-interface (not the filesystem)

`vision/index.md:102-112`, milestone `m6-eval-pipeline.md`, `capabilities/repl-first-development.md`, `docs/prds/agent-runtime/v1.md`. From `vision/index.md:111-112`: "The file system is a persistence format, not the source of truth. The graph database is the system. The REPL is the only interface agents need." The agent never edits files. It evals forms. The pipeline validates the form (schema present, concrete types, map-in/map-out), then compiles, then transacts metadata to the graph, then persists source to disk, then runs affected tests. `(seon/persist!)` is the explicit graduation step — like `git commit`.

### 4.4 Graph-as-source-of-truth (Datalog over Datahike on LMDB)

`vision/index.md:45-54`, `capabilities/code-graph.md`, `capabilities/function-discovery.md`. The graph stores every function, schema, namespace, call edge, test, agent message, eval, and turn — and it stores them in a queryable form. From the 924820e README (the wording predates the Datalevin→Datahike migration; the underlying claim is unchanged): "functions are discovered via Malli schema contracts queried from a Datalog graph (Datalevin / Datahike on LMDB) rather than by name lookup or file imports." Reverse-ref pulls do namespace-scoped discovery in one query. The agent's first move in a new namespace is a graph query, not a file read.

### 4.5 Specificity-based discovery (one mechanism for everything)

`vision/index.md:79-95`, `capabilities/renderer-discovery.md`, `concepts/renderer-discovery.md`. The same algorithm (count of matched required keys, namespace-proximity tiebreak) discovers renderers, transformers, validators, event handlers, AI context builders. `vision/index.md:94-95`: "No separate rendering system, subscription system, test runner, or event system. One discovery mechanism. Functions that match are functions that work." Renderer discovery is the production proof. M4 generalizes it.

### 4.6 Progressive enhancement (smart defaults, agents add specificity)

`concepts/progressive-enhancement.md`, `m7-namespace-as-process.md:10`, `capabilities/inter-agent-messaging.md`. A new namespace ships with the default step function. The default handles requests, persists state, pushes SSE. If a message arrives that needs a specific handler, the smart default fires and the agent is woken. The agent writes the handler. Next time, the handler is discovered automatically. From `vision/index.md:128`: "ship the default, then build specificity. Agents progressively replace defaults with specific handlers as functionality is needed."

### 4.7 Namespace-scoped agents (long-term ownership, not task completion)

`m8-autonomous-agents.md`, `capabilities/agent-isolation.md`, `capabilities/repl-first-development.md`. One agent stewards one namespace. The agent has its own JVM (substrate) or its own WASM component (Phase 3), its own context, its own typed mailbox. It is not Claude Code spawned for a task — it is a persistent process that wakes on notifications, evolves the namespace over months, hands off ownership on context exhaustion. `m8-autonomous-agents.md:10-12`: "The agent does not need Claude Code. It does not edit files. It receives Malli-specced messages through flow, evaluates forms in the REPL pipeline, and its work is validated, persisted, and tested automatically."

### 4.8 WASM containment (the deployment story)

`docs/seon/pod/wasm-spike-2026-05-20.md`, `docs/prds/agent-runtime/platform.md`, `pod-host/wasm-tauri/`. CLAUDE.md current-focus block: "The seon CLJS pod runs as a `wasm32-wasip2` Component (via wasm-rquickjs) inside wasmtime, embedded in a Tauri Rust process. The capability surface is WIT-typed: `fs`, `http`, `mcp`, `capability-prompt`, `eval`. The Rust host decides what to grant. The agent's CLJS code cannot reach beyond the WIT imports — wasmtime enforces." The CLJS sandbox alone is *not* a security boundary — it catches LLM hallucinations, not adversarial code. WASM is what makes "personal AI that can do anything" survivable on the user's hardware.

## 5. Older README Candidates

Six README revisions tracked on main. Two pairs are duplicates from squashed branches, leaving four distinct framings.

| Commit | Date | Framing | Lead paragraph (verbatim) |
|---|---|---|---|
| `924820e` | earliest | **Technical-architectural** | "A Clojure runtime designed so AI agents can write, own, and evolve software reliably. Every namespace is wired in as a `core.async.flow` process with a typed message envelope and an injected, schema-validated state atom; functions are discovered via Malli schema contracts queried from a Datalog graph (Datalevin / Datahike on LMDB) rather than by name lookup or file imports." |
| `35b912e` / `584b08c` | mid | **Slogan + status + license** | "Infrastructure for AI agents to write reliable software. Not a framework. Not a library. A codebase architecture where agents can discover functions by their contracts, learn from history, own code long-term, and compose safely." (The trading/health/finance line as test-cases is here.) |
| `33cdce3` / `09e3b9c` | later | Same slogan + **Lineage** section | Adds the four-predecessor table and the RFC 3161 timestamping note. Same lead. |
| `c0c2888` | newest | Same slogan + Lineage + **Active tracks** | Adds the "Substrate / WebAssembly containment" two-track section. Same lead. |

Ranked by usefulness for the new README:

1. **`924820e` — highest value as a single recoverable paragraph.** It is the only revision that names the primitives in one sentence. Drop this sentence into the new README at the "how it's built" position.
2. **`c0c2888` (current) — useful for the Active tracks pattern and the Lineage block.** Both should survive into the new README.
3. **`35b912e` / `584b08c` — useful for the test-cases-not-the-product line.** "The personal domains (trading, health, finance) are test cases. The infrastructure is the product." That line should stay.

### Reconstructed strongest opener (composite, citing sources)

Lead: framing C (user pitch, 2026-05-23): "A personal AI that can do anything for you because it can write code."

Then framing B (`vision/index.md:7-15`):

> AI agents will write most software. The question isn't *if* but *how well*. Current approaches are broken: agents bolt onto codebases designed for humans, hallucinate interfaces, have no memory, step on each other, and ship broken code.

Then framing 924820e:

> Seon is a Clojure runtime designed so AI agents can write, own, and evolve software reliably. Every namespace is wired in as a `core.async.flow` process with a typed message envelope and an injected, schema-validated state atom; functions are discovered via Malli schema contracts queried from a Datalog graph rather than by name lookup or file imports.

Then framing A's test-cases line:

> The personal domains (trading, health, finance) are test cases. The infrastructure is the product.

This composite covers what the project is *for*, why current tooling fails, how it's built, and how it's measured.

## 6. Archive Material Worth Resurrecting

Cross-branch archives surveyed: `feature/refinement` (173 archive files) and `feature/super-repl` (133 archive files).

### `feature/super-repl:VISION.md` — the previous full-scope vision doc

This file existed at the repo root before the docs reorganization. It is the direct predecessor of `docs/seon/vision/index.md`. Two things in it are NOT in the current vision doc and should come back:

**The XTDB / bitemporal framing** — `feature/super-repl:VISION.md`:

> The Right Database
>
> XTDB provides bitemporal history:
> - Valid time: When was this fact true in the world?
> - Transaction time: When did we record this fact?
>
> Agents can query: "What did this function return last week?" "How has this data evolved?" "What changed between working and broken?"
>
> Most databases can't answer these questions. For agents learning from experience, they're essential.

An earlier draft of the vision doc replaced this with an LMDB-storage pitch and dropped the bitemporal-history framing. The migration to Datahike (which preserves history when `:keep-history? true`; see `docs/prds/agent-runtime/v1.md:1112`) makes the bitemporal-history pitch load-bearing again and it should be restored. Bitemporality is part of what makes "learn from history" real.

**The Validation Criteria block** — `feature/super-repl:VISION.md`:

> ### Near-term (3 months)
> - [ ] Agent completes multi-phase PRD without human intervention
> - [ ] Function discovery: agent finds composable functions via schema query
> - [ ] Zero resource leaks over 24-hour agent marathon
>
> ### Medium-term (6 months)
> - [ ] Agent maintains a namespace for 30+ days, evolving based on usage
> - [ ] Non-developer gives problem → agents build working solution
> - [ ] System suggests improvements based on usage patterns
>
> ### Long-term (12 months)
> - [ ] Multiple agents collaborate on cross-cutting feature
> - [ ] Agent notices regression and fixes it proactively
> - [ ] New domain added with minimal human guidance

These are the missing falsifiers. The current vision doc has milestones but no time-bound success criteria. The "non-developer gives problem → agents build working solution" line is the consumer-AI promise in falsifiable form. It should anchor the README's "What success looks like" section.

### `feature/refinement:docs/archive/primer/docs/primer/research/architecture-vision.md` — the Primer state-machine framing

A different application built on the same substrate. The key idea:

> The Core Insight: A Primer session is a server-controlled state machine where:
> - Scene = current state (data structure)
> - Templates = render functions (scene → hiccup)
> - Transitions = valid next states (AI-driven or user-triggered)
> - Checkpoint = serialize scene to XTDB for replay/debugging
>
> The AI doesn't "generate HTML" - it generates state transitions. The templates are pre-built. This gives us:
> 1. Instant rendering (no waiting for AI to generate markup)
> 2. Deterministic replay (same state = same view)
> 3. Composable complexity (templates call templates)
> 4. Debuggable (inspect state at any point)

This vision (an AI tutor / Diamond Age "Primer" built on the substrate) demonstrates that the substrate is general-purpose. The README does not currently illustrate any application beyond trading/health/finance. One sentence — "the same substrate powers a Diamond Age-style Primer prototype that lives on `feature/refinement`" — would do a lot to communicate scope.

### `feature/refinement:docs/archive/seon-transform/prd.md` — the Sanderson Elantris name origin

> **Seon** - from the archaic "to see", and inspired by the Seons of Brandon Sanderson's *Elantris*: sentient, luminous beings that serve and assist their bonded humans.

This naming/lore line is in CLAUDE.md but absent from the public README. It is exactly the kind of one-line color that a consumer pitch should have. It frames the product as a *bonded servant*, not a CLI tool.

### `feature/refinement:docs/archive/primer/docs/primer/research/seon-architecture-research.md`

Contains the original "schema-first / generators built-in / capabilities pattern" explanation. The capabilities pattern is mostly obsolete in current code, but the line *"Schema-first design - entities defined before code"* is a clean one-liner that the README could borrow as a tagline for the contracts pillar.


## 5. Older README Candidates

_pending_

## 6. Archive Material Worth Resurrecting

_pending_

## 7. What's In the Repo Today (main)

Concrete inventory of `/Users/sean/src/seon` on `main` at `c0c2888`. The README can credibly point to all of these as real artifacts.

### Top-level Clojure namespaces under `src/seon/`

Two lanes, per CLAUDE.md "Lane discipline": `.clj` (JVM substrate) and `.cljs` (CLJS pod). Some `.cljc` for portable code.

**JVM lane (existing substrate):** `ai.clj`, `config.clj`, `core.clj`, `db.clj`, `health.clj`, `logging.clj`, `render.clj`, `repl.clj`, `runner.clj`, `runtime.clj`, `session.clj`, `system.clj` — plus subdirs `agent/`, `ai/` (provider clients), `claude/`, `ctx/`, `db/` (`datahike/`, `relay.clj`, `schema.clj`, `tx.clj`), `dev/`, `flow/`, `graph/`, `health/`, `ns/`, `orchestrator/`, `phase2/`, `render/`, `repl/`, `system/`, `test/`, `ui/`, `web/` (`broadcast.cljs`, `caddy.clj`, `components.clj`, `flows.clj`, `handlers.clj`, `html.clj`, `logs.clj`, `namespace.clj`, `reactive/`, `routes.clj`, `server.clj`, `sse.clj`, `sse/`, `tailwind.clj`).

**CLJS pod lane (V0):** `agent.cljs`, `client.cljs`, `db.cljs`, `db_test.cljs`, `error.cljs`, `eval.cljs`, `fs.cljs`, `log.cljs`, `platform.cljs`, `render.cljs`, `render_test.cljs`, `repl.cljs`, `wasm_eval_smoke.cljs`, `wasm_smoke.cljs`. Per CLAUDE.md current-focus: "the long-running Node process that hosts the agent loop, datahike-cljs, the bootstrap CLJS compiler, and a loopback HTTP+SSE server. Run it via `node out/client/main.js`."

**Portable / shared:** `code.cljc`, `schema.cljc`.

### Pod-host Rust workspace under `pod-host/wasm-tauri/`

Imported 2026-05-20. Contains:

- `Cargo.toml`, `Cargo.lock` — Rust workspace
- `src-tauri/` — Tauri app shell
- `src-wit/`, `src-wit-eval-smoke/`, `src-wit-smoke/` — WIT interface definitions (`fs`, `http`, `mcp`, `capability-prompt`, `eval` capability surface)
- `build-pod`, `build-eval-smoke`, `pod-build/`, `smoke-build/`, `eval-smoke-build/` — build pipelines
- `mcp-server-seon/` — MCP server crate
- `eval-smoke.mjs`, `smoke.mjs`, `seon-prelude.mjs`, `placeholder.mjs` — JS smoke harnesses

### Active PRDs under `docs/prds/` (28 directories)

`agent-repl-interface`, `dashboard-polish`, `data-viewer`, `datahike-migration`, `datalevin-migration` (predecessor, archive), `datalevin-reactive`, `flow-datalevin-writer`, `graph-cleanup`, `logging-system`, `mcp-resilience`, `namespace-bootstrap`, `namespace-ui`, `refinement`, `render-pipeline`, `schema-unification`, `schema-viewer`, `shape-graph`, `spec-driven-rendering`, `stability-improvements`, `startup-reliability`, `super-repl`, `test-coverage-audit`, `test-infrastructure`, `unified-flow`, `unified-namespace-flow`, `agent-runtime` — plus `_example-feature` and `readme.md`.

### Documentation tree under `docs/seon/`

`_dashboard.md`, `architecture/` (overview + ADRs 001-007), `components/` (one note per component, ~14), `concepts/` (patterns: `step-functions`, `renderer-discovery`, `request-reply`, `namespace-as-process`, `subscriptions`, `feeds`, `progressive-enhancement`, `socratic-agents`, `namespace-stewardship`), `orchestrator/` (active.md, prds.md, issues/ with ~49 open), `pod/` (WASM spike docs), `reference/`, `vision/` (index + 8 milestones + 28 capabilities).

### Per the dashboard

`testing` component reports "70 test files, ~819 tests, REPL-first runner." `namespaces` reference reports "102 namespaces with file paths and layer groupings." `components` table lists 14 components at status `production`/`stable`/`design`/`experimental`.

## 8. What's Missing From the Current README

The current README (~67 lines, 5 sections: title/slogan, Status, Active tracks, Lineage, License, Contributing, Contact) leaves the following on the floor:

1. **The user-facing promise.** Framing C — "personal AI that can do anything for you because it can write code" — is nowhere. A first-time visitor reads "infrastructure for AI agents" and parses it as developer-tooling.
2. **The Sanderson Seon naming.** The bonded-servant frame from `seon-transform/prd.md` is in CLAUDE.md (line ~115 region) but not the README. It is exactly the kind of evocative single sentence a consumer pitch needs.
3. **The architectural pillars.** Eight load-bearing ideas (§4 above). The README has zero technical detail beyond the slogan. The 924820e paragraph that named the primitives in one sentence was deleted.
4. **What "do anything" actually looks like.** No scenario, no example, no "ask Seon to track X." The M7/M8 scenarios in the milestone docs are the strongest illustrations the project has — none surface in the README.
5. **Eight milestones with status.** M1–M5 are partial, M6–M8 are unbuilt. The README implies the project is undifferentiated work-in-progress; the milestone table in `vision/index.md:282-290` is much more honest and more compelling.
6. **WebAssembly containment as the deployment story.** The Active tracks block names it but does not explain *why* it matters: the agent's eval surface needs to be sandboxed beyond what the CLJS sandbox provides (CLAUDE.md current-focus: "The CLJS sandbox layer is NOT a security boundary"). WASM is what makes the personal-AI claim survivable on the user's machine.
7. **The graph as source of truth.** The single most distinctive architectural claim is missing. From `vision/index.md:111-112`: "The file system is a persistence format, not the source of truth. The graph database is the system."
8. **Schema-driven function discovery.** The vision's core primitive (`vision/index.md:75-95`). Renderer discovery works in production; M4 generalizes it. None of this is mentioned.
9. **REPL-as-interface for agents.** Agents don't edit files; they eval forms validated by a constraint pipeline. This is M6 — and the v1 spec under `docs/prds/agent-runtime/v1.md` is the most detailed surviving artifact of the user's "REPL is the substrate" thesis.
10. **Validation criteria.** The `feature/super-repl:VISION.md` near/medium/long-term checklist is missing. Without falsifiers, "research project" reads as "indefinitely incomplete."
11. **Lineage prose vs lineage table.** The current lineage section is a four-row table of predecessor repos. That's fine for prior-art but undersells the *story*: 18 months of experiments converged on this specific shape. The 924820e version had this in prose form.
12. **Why Clojure, why Datalog, why immutable.** `vision/index.md:30-69` has the answer in a table and three paragraphs. The README points at this doc but doesn't summarize it.

## 9. Suggested README Outline

Headings + one-line intent each (no prose):

1. **Title + one-line tagline** — Framing C: "A personal AI that can do anything for you because it can write code."
2. **What it is** — Two paragraphs: the M-B substrate framing + the test-cases-not-the-product line.
3. **Why now** — The "agents will write most software" wedge from `vision/index.md`.
4. **A scenario** — One of the M7/M8 user-facing scenarios verbatim. "You ask Seon to track your workouts. The substrate spins up a namespace. The agent observes usage and writes the dashboard."
5. **What's distinctive** — The 924820e technical paragraph + a bulleted list of the eight pillars (§4 of this doc).
6. **Naming** — The Sanderson Seon bonded-servant line.
7. **Where it is** — The eight milestones with status, copied from `vision/index.md:282-290`. Bold the unbuilt ones (M6–M8) so the alpha-blocking work is visible.
8. **Active tracks** — Substrate (datahike-migration) + WebAssembly containment, current README's Active tracks block expanded with one paragraph each on why both exist.
9. **The personal domains** — Trading, health, finance as test cases. Link to the relevant component / domain notes for each. Frame them as proofs of the substrate, not as products.
10. **How to verify it works** — Time-bound validation criteria from `feature/super-repl:VISION.md` (near/medium/long-term).
11. **Lineage** — Current table + the one-line story "this is the shape that survived 18 months of experiments." Keep the RFC 3161 timestamping note.
12. **Where to read next** — Pointers: `docs/seon/vision/index.md`, `docs/seon/_dashboard.md`, `docs/prds/agent-runtime/v1.md`, `CLAUDE.md`.
13. **Status, License, Contributing, Contact** — Keep current sections verbatim.


## 8. What's Missing From the Current README

_pending_

## 9. Suggested README Outline

_pending_
