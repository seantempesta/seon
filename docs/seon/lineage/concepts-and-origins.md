---
type: reference
status: active
tags: [reference]
---

<!-- STATUS: complete; cross-cutting + coverage notes filled -->

# Concepts and origins

> By-concept lineage. For the by-repo timeline, see [`predecessors.md`](predecessors.md).

## Statement of purpose

This document is the concept-indexed companion to [`predecessors.md`](predecessors.md). Where `predecessors.md` walks the seventeen-repo timeline chronologically, this file does the inverse: it takes each load-bearing architectural concept in Seon and traces it back through the predecessor repos to its earliest written-down form, with dated commits, SHAs, and links to where the concept lives in code and docs today.

The audience is anyone — a collaborator, a reviewer, a lawyer — who wants a clean dated record of where each idea came from rather than a repo-by-repo narrative. Each section names a single concept, identifies the first commit that records it, traces its evolution across the predecessor spine, and points to the current implementation and documentation.

Read it scrolling, not searching: the eight concepts are ordered from runtime-foundational outward.

A single commit deserves special attention up front: [`b302ef8`](https://github.com/seantempesta/seon-2025-02-architecture/commit/b302ef8) on 2025-03-04 in `seon-2025-02-architecture` ("maybe crazy to do this") expanded the README from 179 lines to 1,874 lines and added `seon/app/eelchat.clj`, `seon/app/tasks.clj`, `seon/router.clj`, `seon/websockets.clj`, and the agent-driven generation patterns in a single shot. Six of the eight concepts below have their first explicit written form in this one commit. The "Cross-cutting notes" section at the end records this convergence.

---

## 1. Namespace-as-process

**First written down**: 2025-03-04, [`b302ef8`](https://github.com/seantempesta/seon-2025-02-architecture/commit/b302ef8) in `seon-2025-02-architecture` — the 1,936-line design README opens with "SEON System Design: Namespaces are king" and defines per-app namespaces (`seon.app.tasks`, instantiated as `seon.app.tasks.a3f721e9`) and per-session namespaces (`seon.repl.{id}`) as the unit of isolation and ownership.

**Earlier seed**: 2025-01-25, [`f03c10d`](https://github.com/seantempesta/cljs-chat-interface/commit/f03c10d) in the local-only `cljs-chat-interface` (not published on GitHub) — the "tile-per-session" UI model, where each chat tile owned its own state. The lineage is conceptual: tile-per-session was the UI shape that later generalized into namespace-per-process. Local path only: `~/src/cljs-chat-interface/src/main/app/sessions/`.

**First implemented as a process-with-message-envelope**: 2026-02-16, [`f477651`](https://github.com/seantempesta/seon/commit/f477651) "feat: flow harness with namespace isolation and cross-ns function calls" — introduces `src/seon/flow/harness.clj` and the topology backbone where each namespace is a `core.async.flow` process with a typed inbox.

**Evolution**:

- 2025-03-04 (seon-2025-02-architecture): documented in README; partially realized — namespaces are app boundaries, but routed via HTMX/WinBox, not flow messages.
- 2025-11-28 → 12-05 (ml-options-trading): namespaces become Integrant components with explicit lifecycle, but cross-namespace calls are still direct `require`/invoke.
- 2026-02-16 → present (this repo): namespace-as-process becomes a literal flow process with a step-fn (`describe`/`init`/`transition`/`transform`) and a per-namespace message channel. `topology/request!` is the sole legal cross-boundary call.

**Current code**: `src/seon/flow/topology.clj`, `src/seon/flow/harness.clj`, `src/seon/flow/msg.clj`
**Current docs**: [`docs/seon/concepts/namespace-as-process.md`](../concepts/namespace-as-process.md), [`docs/seon/components/flow-topology.md`](../components/flow-topology.md), [`docs/seon/components/harness.md`](../components/harness.md)

**Why it matters**: It is the unit of composition for the whole runtime — every other concept (schemas, ctx, discovery, isolation) hangs off "the namespace is the process."

---

## 2. Schema-as-contract

**First written down**: 2025-03-04, [`b302ef8`](https://github.com/seantempesta/seon-2025-02-architecture/commit/b302ef8) in `seon-2025-02-architecture`. README §1: "describe the data model with fully namespaced Clojure Specs that are compatible with generative testing"; the source files added in the same commit (e.g. `seon/app/tasks.clj`, `seon/app/eelchat.clj`) carry namespaced `s/def` schemas for inputs, outputs, and persisted entities. Schemas are advertised on the public surface and used by generative testing.

**Substrate switch (spec → Malli)**: 2025-11-28, [`6cb81f4`](https://github.com/seantempesta/seon-2025-11-trading-domain/commit/6cb81f4) ("first commit") in `seon-2025-11-trading-domain` already lists `metosin/malli {:mvn/version "0.17.0"}` in `deps.edn` and uses Malli schemas for entity validation. The conceptual move — "every function and every datom is described by a schema written in the same namespace whose name it carries" — is the same as `seon-biff`; the implementation switches from `clojure.spec` to Malli because Malli supports schemas-as-data (queryable) without the macro/registry friction.

**Function-level `:malli/schema` metadata**: 2026-02-14, [`b69a310`](https://github.com/seantempesta/seon/commit/b69a310) "feat: knowledge graph foundation with Datalevin storage" — the graph scanner reads `:malli/schema` metadata off public functions and ingests it. From this commit forward, schemas are the discoverable contract, not just runtime validators.

**Evolution**:

- 2025-03-04 (seon-2025-02-architecture): `clojure.spec` with namespaced keywords; specs co-located with the code they describe.
- 2025-11-28 → 12-05 (ml-options-trading): Malli replaces spec; entity schemas live in `src/ml_options/db/schema.clj`.
- 2026-02-14 → present (this repo): `:malli/schema` on every public function; runtime instrumentation throws on mismatch; `seon.schema/register!` is the single source of truth.

**Current code**: `src/seon/schema.clj`, `src/seon/db/schema.clj`, `src/seon/dev/instrumentation.clj`
**Current docs**: [`docs/seon/components/schema-system.md`](../components/schema-system.md), [`docs/conventions.md`](../../conventions.md)

**Why it matters**: It is the typed boundary every other layer queries. Without schemas as data, function discovery, validated transacts, and per-namespace ctx are all impossible.

---

## 3. Function discovery via Datalog graph

**First written down**: 2025-03-04, [`b302ef8`](https://github.com/seantempesta/seon-2025-02-architecture/commit/b302ef8) in `seon-2025-02-architecture`. README §7.2 "Runtime Enhancement" describes recording namespaces, functions, and specs to the database with relationships so the system is self-describing. README §2.5 calls this "Database-Driven Development": "Queryable code and data relationships … Knowledge graph of all system components." `src/seon/app/registry.clj` in the same commit shows the pattern: an app registry persisted to XTDB queryable by Datalog (`{:find [(pull ?e [*])] :where [[?e :db/doc-type :app]]}`).

**First implemented as a graph indexed by schema shape**: 2026-02-14, [`b69a310`](https://github.com/seantempesta/seon/commit/b69a310) "feat: knowledge graph foundation with Datalevin storage" — adds `src/seon/graph/{ingest,query,scanner,extract,analyzer}.clj`. Functions are scanned, their `:malli/schema` metadata extracted, and the input/output keys ingested as datoms so "which functions accept `:seon.x/y`?" is a Datalog query.

**Refinement to namespaced graph attributes**: 2026-02-19, [`889c390`](https://github.com/seantempesta/seon/commit/889c390) "refactor: migrate graph entities from :graph/* to :seon.fn/*, :seon.ns/*, :seon.call/*" — the graph now uses the same namespaced-keyword convention as the rest of the system.

**Evolution**:

- 2025-03-04 (seon-2025-02-architecture): designed in README; app registry persisted to XTDB; no schema-shape indexing yet.
- 2026-02-14 (this repo): production knowledge graph with schema-shape indexing.
- 2026-02-19 (this repo): namespaced attributes; `:seon.fn/*`, `:seon.ns/*`, `:seon.call/*`.

**Current code**: `src/seon/graph/ingest.clj`, `src/seon/graph/query.clj`, `src/seon/graph/scanner.clj`, `src/seon/graph/extract.clj`, `src/seon/graph/analyzer.clj`
**Current docs**: [`docs/seon/components/code-graph.md`](../components/code-graph.md), [`docs/seon/concepts/renderer-discovery.md`](../concepts/renderer-discovery.md)

**Why it matters**: It turns "find a function that takes this shape" into a database query instead of a name search or file import, which is what makes agent-driven routing tractable.

---

## 4. REPL-as-interface (eval pipeline)

**First written down (as a `seon.repl` namespace)**: 2024-10-03, [`2a12d1b`](https://github.com/seantempesta/seon-2024-10-xtdb-biff/commit/2a12d1b) in `seon-2024-10-xtdb-biff` — "replicated chat functionality in the seon.repl namespace". The `seon.repl` namespace pattern (chat-driven, server-rendered, persisted) is first established here, eighteen months before the current repo.

**First written down (as the design pattern)**: 2025-03-04, [`b302ef8`](https://github.com/seantempesta/seon-2025-02-architecture/commit/b302ef8) in `seon-2025-02-architecture`. README §2.1: "REPL/Chat Sessions/Workspaces: `seon.repl.{id}` (e.g., `seon.repl.a3f721e9`) — The REPL is the heart and soul of SEON and you can interact via text or Clojure code." §7.2 describes the pipeline that runs on every form: record namespaces, functions, and specs to the DB, establish relationships, all without special syntax.

**First implemented as a multi-stage eval pipeline**: 2026-02-19, [`aa073c6`](https://github.com/seantempesta/seon/commit/aa073c6) "feat: Super REPL form router with Datalevin storage" — and [`42d1f35`](https://github.com/seantempesta/seon/commit/42d1f35) the same day "feat: namespace graduation from Super REPL to disk". Together these introduce the canonical pipeline: agent evals a form → form is routed → schema is validated → metadata is transacted into the graph → form is persisted to disk → schema-selected tests are run.

**Evolution**:

- 2024-10-03 (seon-2024-10-xtdb-biff): `seon.repl` as a chat namespace; concept of "REPL is the interface" first wired in.
- 2024-10-14 → 2025-01-20 (seon-2024-10-kit-migration / `seon-look-into`): REPL UI built out (`src/cljc/seon/repl/session_1.cljc`, `src/clj/seon/app/repl.clj`); session ids; UI-driven REPL evaluation.
- 2025-03-04 (seon-2025-02-architecture): explicit pipeline design — eval → spec → DB → relationships.
- 2026-02-19 → present (this repo): production "Super REPL" with form router, graph ingestion, disk graduation, MCP cockpit.

**Current code**: `src/seon/repl/`, `src/seon/runtime.clj`, `src/seon/dev/`
**Current docs**: [`docs/seon/components/dev-tools.md`](../components/dev-tools.md), [`docs/seon/components/runtime.md`](../components/runtime.md)

**Why it matters**: It is the only legal write path for agent-authored code. Agents do not edit files; they eval forms, and the pipeline enforces every invariant before anything reaches disk.

---

## 5. Multi-agent isolation / per-agent JVM

**First written down**: 2025-03-04, [`b302ef8`](https://github.com/seantempesta/seon-2025-02-architecture/commit/b302ef8) in `seon-2025-02-architecture`. README §1: agents inherit "code from a specified namespace (runs in a **randomized sub-namespace** to prevent namespace collisions)". README §4.2 (`seon.sessions.dynamic`) shows the realization: `(create-ns ns-sym)` plus `(intern ns-sym 'ctx ...)` builds a per-session namespace with its own ctx atom, app implementation, and routes. This is in-JVM isolation by namespace, not yet a separate process.

**First implemented as a separate JVM per agent**: 2026-02-14, [`dcb6f77`](https://github.com/seantempesta/seon/commit/dcb6f77) "feat: Super REPL PRD + isolated agent JVM prototype" and [`316dba9`](https://github.com/seantempesta/seon/commit/316dba9) the next day "feat: production-ready agent JVM pool with lifecycle management". Each agent gets its own JVM with its own nREPL port; the main process speaks to it over TCP via the flow harness.

**Earlier conceptual seed**: 2025-11-18, `ml-ct-scan` (local-only) — multi-channel volumetric INR for CT imaging. The semantic-channel-per-agent isolation pattern was prototyped in PyTorch before being ported back to Clojure. Local path only: `~/src/ml-ct-scan/`.

**Evolution**:

- 2025-03-04 (seon-2025-02-architecture): randomized sub-namespace isolation, in-process.
- 2026-02-14 (this repo): separate JVM per agent, nREPL + TCP harness, lifecycle-managed pool.
- 2026-02-16 (this repo): [`f477651`](https://github.com/seantempesta/seon/commit/f477651) ties the JVM pool to `core.async.flow` so messages route uniformly across in-process and remote-process namespaces.

**Current code**: `src/seon/flow/harness.clj`, `src/seon/orchestrator/session.clj`
**Current docs**: [`docs/seon/components/harness.md`](../components/harness.md), [`docs/seon/components/agent-system.md`](../components/agent-system.md)

**Why it matters**: An agent that can crash its own JVM cannot crash the orchestrator. Isolation is the precondition for trusting agents to evaluate code.

---

## 6. Context atoms with persistence

**First written down (as namespace-scoped state)**: 2024-06-28, [`e18370d`](https://github.com/seantempesta/ea/commit/e18370d) (local-only `ea` repo; this is the on-disk git SHA, not a GitHub link) — earliest use of Clojure atoms as per-component reactive state in `src/ea/frontend/web/live_tiles.cljs`. The shape is `(defonce state (atom {}))` per UI component. Local path only: `~/src/ea/`.

**First written down (as `ctx`)**: 2025-03-04, [`b302ef8`](https://github.com/seantempesta/seon-2025-02-architecture/commit/b302ef8) in `seon-2025-02-architecture`. README §2.2 "Context-Based State Management": each namespace maintains its state in a context map `ctx`, with clear ownership, predictable updates via pure functions, and isolation. `seon.sessions.dynamic` shows the concrete pattern: `(intern ns-sym 'ctx (atom {:title title :created-at ...}))`. This is the **named** `ctx` atom living inside the namespace.

**First implemented as schema-validated + persisted + SSE-pushed**: 2026-02-19, [`aa8f7ed`](https://github.com/seantempesta/seon/commit/aa8f7ed) and [`77a38ef`](https://github.com/seantempesta/seon/commit/77a38ef) "feat: unified ctx system with Datalevin persistence" — `src/seon/ctx.clj` creates atoms with Malli-validated swaps, disk persistence, and a live SSE channel. From this commit forward, the agent and the human observer see the same ctx state in real time.

**Evolution**:

- 2024-06-28 (`ea`, local): atom-per-component as reactive state.
- 2025-03-04 (seon-2025-02-architecture): `ctx` named and described as per-namespace state.
- 2026-02-19 → present (this repo): validated, persisted, SSE-pushed `::*ctx*` per namespace.

**Current code**: `src/seon/ctx.clj`, `src/seon/ctx/`, `src/seon/web/` (SSE wiring)
**Current docs**: [`docs/seon/components/context.md`](../components/context.md)

**Why it matters**: It is the only durable state a namespace owns. Everything else (functions, schemas, the call graph) is code; ctx is the agent's living workspace.

---

## 7. Ctx-as-OS (the Primer pattern)

**First written down**: 2025-03-04, [`b302ef8`](https://github.com/seantempesta/seon-2025-02-architecture/commit/b302ef8) in `seon-2025-02-architecture`. The README is, in effect, a single statement of this pattern: "SEON creates perfect alignment between code, database, and specifications through a fully namespaced architecture. This alignment enables both humans and AI agents to reason about the system, **discover functionality through data**, and create new applications that integrate seamlessly with existing components." The Primer pattern — one data structure for the whole system, UI derived, agent writes data, schemas constrain writes — appears in §2.3 (fully-namespaced alignment of code/db/specs), §2.5 (database-driven development), and §3.1 (the App protocol where `(render ctx size)` derives UI from ctx).

**Implementation trajectory**: the pattern has been built incrementally as the other concepts came online. Three-tier storage (DB datoms = projections; blobs = persistent full content; volatile per-session values) is the current consolidation, with the test runner (`seon.test.runner`, shipped 2026-05-22) cited as the canonical example.

**Evolution**:

- 2025-03-04 (seon-2025-02-architecture): articulated as design philosophy.
- 2026-02-19 (this repo): unified ctx + unified render pipeline (`798df03`) realize "UI is derived from ctx".
- 2026-02-21 (this repo): [`10f2235`](https://github.com/seantempesta/seon/commit/10f2235) "feat: unified runtime registry (Phase 1)" + [`67f5a8a`](https://github.com/seantempesta/seon/commit/67f5a8a) — agents and their runs become entities in the same graph as everything else.
- 2026-05-22 (this repo): three-tier storage formalized via `seon.test.runner` as exemplar.

**Current code**: `src/seon/runtime.clj`, `src/seon/ctx.clj`, `src/seon/render.clj` (renderer resolution), `src/seon/test/runner.clj`
**Current docs**: [`docs/seon/architecture/overview.md`](../architecture/overview.md), [`docs/seon/vision/index.md`](../vision/index.md), [`docs/seon/components/renderer.md`](../components/renderer.md)

**Why it matters**: It is the design north star — the assertion that an entire computing system can be one queryable data structure with derived views, not a pile of files and processes. Everything else is in service of making that real.

---

## 8. Capability-gated WASM containment

**First written down**: 2026-05-20, [`967328a`](https://github.com/seantempesta/seon/commit/967328a) "import wasm-tauri skeleton + design docs for WASM containment (Phase 3)" — imports the `pod-host/wasm-tauri/` workspace (Rust + WIT) and the spike report at [`docs/prds/agent-runtime/research/wasm-spike-2026-05-20.md`](../../prds/agent-runtime/research/wasm-spike-2026-05-20.md). The spike report is the authoritative design doc: it identifies wasm-rquickjs + wasmtime + Tauri as the chosen stack, documents the WIT-typed import surface (`fs`, `http`, `mcp`, `capability-prompt`, `eval`), and explicitly supersedes an earlier EdgeJS-via-Wasmer-CLI direction.

**Earlier seeds**:

- 2026-05-15, [`643ddb9`](https://github.com/seantempesta/seon/commit/643ddb9) "spike(libdatahike-wasm): track-B working spike — Roman+datahike→WASM via Web Image" — the upstream WASM compilation of datahike that proved the approach viable.
- 2026-05-16, [`815ad2a`](https://github.com/seantempesta/seon/commit/815ad2a) "spike(libdatahike-cljs): chunks CLJS-2.5 + REPL workflow — bench green on 3 backends" — the CLJS pod spike with REPL parity.

**Evolution**:

- 2026-05-15 → 05-16 (this repo): runtime spikes; libdatahike compiled to WASM; CLJS pod spiked.
- 2026-05-20 (this repo): containment plan committed; spike report authored; pod-host workspace imported.
- 2026-05-22 onward (this repo): spec rewrite [`f5d678c`](https://github.com/seantempesta/seon/commit/f5d678c) "v1 spec rewrite, Platform Phase 2 + Capability A".

**Current code**: `pod-host/wasm-tauri/`, `pod-host/libdatahike-cljs/`, `pod-host/datahike-harness/`
**Current docs**: [`docs/prds/agent-runtime/`](../../prds/agent-runtime/) (`platform.md`, `v1.md`, `v2.md`, `v3.md`, `STATUS.md`), [`docs/prds/agent-runtime/research/wasm-spike-2026-05-20.md`](../../prds/agent-runtime/research/wasm-spike-2026-05-20.md)

**Why it matters**: The CLJS sandbox in the V0 pod is "accident prevention, not security." WIT-typed capability gates under wasmtime are what turn agent autonomy from a research demo into something an outside user can run without trusting the agent's code.

---

## Cross-cutting notes

### The primary design realization: `b302ef8` (2025-03-04)

Six of the eight concepts above — namespace-as-process, schema-as-contract, function discovery via Datalog, REPL-as-interface, in-namespace agent isolation, and ctx-as-OS — have their first explicit written form in [`b302ef8`](https://github.com/seantempesta/seon-2025-02-architecture/commit/b302ef8). The commit message reads "maybe crazy to do this." It expanded the README from 179 lines to 1,874 lines in a single shot and added the first concrete app/session files (`seon/app/tasks.clj`, `seon/app/eelchat.clj`, `seon/sessions/dynamic` patterns in the README).

This is a single dated event documenting the design as a coherent whole, **ten months before the current `seon` repo's first commit**. For prior-art purposes it should be cited as the primary design realization.

### Predecessor `seon.repl` namespace: `2a12d1b` (2024-10-03)

The name `seon.repl`, the chat-driven interaction shape, and the persistence-on-eval pattern existed seventeen months before the current repo, in [`2a12d1b`](https://github.com/seantempesta/seon-2024-10-xtdb-biff/commit/2a12d1b). This is the earliest dated commit that uses the project name "seon" in a load-bearing way (`seon.bak` from 2024-08-24 used the name but was a Biff starter without architectural commitment).

### Library transition: `seon-2025-02-architecture` → `ml-options-trading`

The library switch from `clojure.spec` + XTDB v1 + Biff to Malli + XTDB v2 (later Datahike) + Integrant is roughly continuous from 2025-11-28 [`6cb81f4`](https://github.com/seantempesta/seon-2025-11-trading-domain/commit/6cb81f4) onward. The concepts are preserved; the libraries change for queryability and lifecycle reasons. Treat this as a refactor, not a redesign.

### Convergence in `seon` (2026-02-14 → 2026-02-21)

A second convergence event sits in the current repo. In one week:

- 2026-02-14 [`dcb6f77`](https://github.com/seantempesta/seon/commit/dcb6f77) — isolated agent JVM prototype.
- 2026-02-14 [`b69a310`](https://github.com/seantempesta/seon/commit/b69a310) — knowledge graph foundation.
- 2026-02-16 [`f477651`](https://github.com/seantempesta/seon/commit/f477651) — flow harness with namespace isolation.
- 2026-02-19 [`aa073c6`](https://github.com/seantempesta/seon/commit/aa073c6) — Super REPL form router.
- 2026-02-19 [`aa8f7ed`](https://github.com/seantempesta/seon/commit/aa8f7ed) — unified ctx system with persistence.
- 2026-02-21 [`06d7ae1`](https://github.com/seantempesta/seon/commit/06d7ae1) — DB write flow + `seon.db` API.

This is the "design → working runtime" moment. Concepts 1 through 6 all reached production form within seven days.

---

## Coverage notes

- **Concepts 1, 3, 5, 6, 7** — clear, dated, attributable. The 2025-03-04 design realization plus the 2026-02-14 → 02-21 implementation week gives clean priority for each.
- **Concept 2 (schema-as-contract)** — clear but split. The conceptual statement is in `seon-biff` 2025-03-04 (with `clojure.spec`); the Malli adoption is in `ml-options-trading` 2025-11-28; the function-level metadata + graph ingestion is `seon` 2026-02-14. None of the three commits is wrong to cite as "first"; they answer different questions.
- **Concept 4 (REPL-as-interface)** — earliest in `seon.biff` 2024-10-03 (the namespace name + chat shape), articulated in `seon-biff` 2025-03-04 (the eval pipeline), realized in `seon` 2026-02-19 (the Super REPL form router). The lineage is unusually long here; the predecessor record helps.
- **Concept 8 (WASM containment)** — youngest concept. First written form is 2026-05-20; this was the active work at the time of writing. There is no predecessor-repo lineage to record beyond the upstream spikes (wasm-rquickjs, wasm-component-model, datahike-cljs); the design is current-repo-original.
- **Earlier-seed claims (cljs-chat-interface tile-per-session, `ea` atom-per-component, ml-ct-scan multi-channel)** — local-only repos. GitHub links do not exist; on-disk SHAs are the only evidence. They are noted in this document as conceptual seeds rather than first-written-down commits, to preserve honesty about what is and isn't externally verifiable.

The eight concepts in this document are not exhaustive of what's distinctive about Seon. Concepts that would be candidates for a 9th/10th section if the document grew: **renderer discovery** (rendering is just function discovery, but on output-shape); **flow as request/reply backbone** (`topology/request!` + per-namespace `core.async.flow` is itself a contribution distinct from "namespace-as-process"); **per-agent dev hooks** (the auto-reload + auto-test + auto-review feedback loop that runs after every edit); **the documentation system itself** (`seon.dev.markdown` as a native Seon-validated docs layer). None of these are required to make the current document's narrative work, but they are real and dated.
