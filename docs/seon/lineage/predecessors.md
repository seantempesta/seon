---
type: reference
status: active
tags: [reference, prior-art, lineage]
---

# Predecessors

> The dated history behind the project.

The current `seon` git history starts 2025-12-13. That's the consolidation point, not the starting point. The architectural ideas — namespace-as-process, code-graph discovery, schema-as-contract, REPL-as-interface, multi-agent isolation — were iterated across seventeen predecessor repositories over the eighteen months before that.

This file is here so the history is followable. Every idea has predecessors, mine and other people's, and a public timeline makes it possible for anyone (including me, six months from now) to actually trace one.

The five most-significant predecessors are on GitHub, all tagged `v0.1-prior-art-2026-04-21` with RFC 3161 FreeTSA timestamps anchoring them externally. The other twelve are local-only; their on-disk git timestamps plus the table below are the record.

---

## Summary

**Seon** is a Clojure runtime and codebase architecture, in active development by Sean Tempesta since 2025-12, designed so that AI agents can write, own, and evolve software reliably. It treats every namespace as a `core.async.flow` process with a typed message envelope and an injected, schema-validated state atom; functions are discovered via Malli schema contracts queried from a Datalog graph (Datahike on LMDB) rather than by name lookup or file imports. The personal domains in the repo (`seon.health`, etc.) are exercise harnesses for the runtime, not the product.

## Origin and timeline

- **First commit on this repo**: 2025-12-13 (`Initial commit: ml-options codebase copy`, renamed to `seon` the same day).
- **Total commits**: 460+ across 45+ distinct active days spanning Dec 2025 – present.
- **Immediate predecessor**: the initial commit imports `/Users/sean/src/ml-options-trading` — a Clojure project worked on 2025-11-28 through 2025-12-05 that already contained the Integrant lifecycle, Malli schemas, REPL-driven dev loop, and "database as single API" patterns. Renamed to `seon` via `git mv` on 2025-12-13.
- **Conceptual lineage begins 2024-06.** See the table below.

## Predecessor lineage

All repos below are Sean's own work unless explicitly flagged as fork.

| Repo | Published | Period | Commits | Concepts contributed |
|------|-----------|--------|---------|---------------------|
| `ai-subtitle-translation-clj` | local | 2024-06-19 → 06-20 | 6 | Clojure wrapper for OpenAI Completions + SRT parsing — earliest Clojure-meets-LLM point |
| `ea` | local | 2024-06-28 → 08-02 | 33 | Clojure + Shadow-CLJS reactive UI; atoms-for-mutable-state (later evolved into seon's context atoms) |
| `seon.bak` | local | 2024-08-24 | 1 | **First use of the "seon" name.** Biff starter baseline. |
| `seon.biff` | [seon-2024-10-xtdb-biff](https://github.com/seantempesta/seon-2024-10-xtdb-biff) | 2024-10-03 → 10-04 | 5 | XTDB 2.0 + Datomic exploration with Biff; `seon.repl` namespace pattern introduced |
| `seon-look-into` | [seon-2024-10-kit-migration](https://github.com/seantempesta/seon-2024-10-kit-migration) | 2024-10-04 → 2025-01-20 | 45 | Kit framework migration; datomic-storage; early agentic-runtime hints |
| `gary` | local | 2024-10-24 | 56 | Clojure + Shadow-CLJS reactive live data (single-day burst) |
| `ultimate-chatui` | local | 2025-01-30 → 02-07 | 96 | React session-tile home screen; adaptive UI paradigm; **tile-per-session** model (predecessor to namespace-per-agent) |
| `cljs-chat-interface` | local | 2025-01-25 → 08-08 | 137 | ClojureScript + Reagent REPL-first chat UI evolving into "mini-app" sessions; "Magic Wand" interface triggers session evolution — REPL-pipeline + schema-discovery + namespace-as-process origins |
| `pixijs-test` | local | 2025-01-29 → 01-30 | 58 | GPU-accelerated tile rendering (perf variant of ultimate-chatui) |
| `seon-gsap` | local | 2025-01-30 | 9 | Next.js / React spatial UI prototype for multi-session management |
| `seon-biff` | [seon-2025-02-architecture](https://github.com/seantempesta/seon-2025-02-architecture) | 2025-02-26 → 03-07 | 83 | **Primary design realization.** A ~72 KB README in this repo documents namespace-as-process (`seon.app.tasks.{id}`, `seon.repl.{id}`), context-driven state (`ctx`), EAV triples, namespaced Clojure Specs, code-graph (function discovery via specs), schema-discovery (generative testing), REPL-pipeline, namespace isolation (randomized sub-namespaces), and multi-agent design — **all of seon's core concepts appearing together, ten months before the current repo existed.** |
| `lynx-test` | local | 2025-03-10 | 1 | ReactLynx mobile probe; no direct lineage |
| `ooda-subagents` | local (fork) | 2025-07-25 → 07-26 | 6 | **FORK** of `al3rez/ooda-subagents`. OODA-loop multi-agent supervision patterns Sean explored — upstream framework is not claimed as Sean's IP; Sean's adaptations are. |
| `options-trading` | local | 2025-10-30 | 4 | Python/QuantConnect options research (early) |
| `ml-ct-scan` | local | 2025-11-18 | 32 | PyTorch volumetric INR for CT imaging — multi-channel semantic analysis (conceptual seed for multi-agent namespace isolation) |
| `michael-medical-claude` | local | 2025-11-24 → 11-26 | 2 | HTML/JS medical dashboard (Claude-integration test) |
| `ml-options-trading` | [seon-2025-11-trading-domain](https://github.com/seantempesta/seon-2025-11-trading-domain) | 2025-11-28 → 12-05 | 59 | **Immediate git ancestor of `seon`.** XTDB v2 + ThetaData; Integrant component lifecycle; REPL-driven dev; database-as-single-API (`seon.db`); Malli schema validation. Renamed to `seon` 2025-12-13. |
| **`seon`** (this repo) | [seon](https://github.com/seantempesta/seon) | 2025-12-13 → present | 460+ | Consolidation + dedicated development. |
| `seon-visualizations` | local | 2026-03-04 | 11 | React + Vite presentation layer for seon concepts (docs/exploration, not runtime) |
| `scan-to-plan` | local | 2026-04-12 → 04-14 | 24 | Self-fork of `seantempesta/image-to-architecture`. Agent-editable model schemas + render-edit autonomous loops — schema-driven agent autonomy at scale. |

### The published spine

The five linked repos are the load-bearing publicly-dated record. All have the immutable tag `v0.1-prior-art-2026-04-21` and date-anchored predecessor/successor cross-links in their READMEs:

- [seon-2024-10-xtdb-biff](https://github.com/seantempesta/seon-2024-10-xtdb-biff) (Oct 2024)
- [seon-2024-10-kit-migration](https://github.com/seantempesta/seon-2024-10-kit-migration) (Oct 2024 – Jan 2025)
- [seon-2025-02-architecture](https://github.com/seantempesta/seon-2025-02-architecture) (Feb – Mar 2025)
- [seon-2025-11-trading-domain](https://github.com/seantempesta/seon-2025-11-trading-domain) (Nov – Dec 2025)
- [seon](https://github.com/seantempesta/seon) (Dec 2025 – present)

The other twelve repos remain local; the prior-art table above plus their on-disk git timestamps document them. Any can be published later if a specific question warrants.

## Key inflection points

Which repo introduced which concept:

- **Clojure + LLM** — `ai-subtitle-translation-clj` (2024-06)
- **Reactive state atoms as component model** — `ea` (2024-06)
- **"seon" name first used** — `seon.bak` (2024-08)
- **Tile-per-session UI as namespace-per-agent precursor** — `ultimate-chatui`, `cljs-chat-interface` (2025-01)
- **Namespace-as-process + code-graph + schema-discovery + REPL-pipeline + multi-agent design appearing together** — **`seon-biff`** (2025-02-26 → 03-07). The single most important predecessor for priority-date purposes.
- **Integrant + Malli + REPL dev loop + database-as-single-API** — `ml-options-trading` (2025-11)
- **Named project and direct git ancestor** — rename on 2025-12-13

## Scope note

Fifteen of the seventeen are originals. Two are forks:

- `ooda-subagents` — forked from `al3rez/ooda-subagents` to study OODA-loop supervision patterns. The framework is `al3rez`'s work; my changes on top are minor.
- `scan-to-plan` — a self-fork of an earlier repo of mine, `seantempesta/image-to-architecture`. Same author across both.

The vendored open-source dependencies under `~/src/` and `reference-code/` (Datahike, Datascript, Biff, Electric, Portal, XTDB, and others) are third-party projects checked out so I can read their source. They're somebody else's work, not mine.

## What this codebase actually does differently

Five architectural choices, all visible in the code:

1. **Namespace-as-process runtime.** Every Clojure namespace is wired in as a `core.async.flow` process with a 4-arity step function (`describe / init / transition / transform`) and a per-namespace message inbox. Cross-namespace calls go through `topology/request!` rather than direct `require`/invoke, giving uniform backpressure, overload replies, and observability. Evidence: `src/seon/flow/topology.clj`, `src/seon/flow/harness.clj`, `src/seon/flow/msg.clj`, concept doc [`docs/seon/concepts/namespace-as-process.md`](../concepts/namespace-as-process.md).

2. **Schema-as-registration / function discovery via Datalog.** Public functions advertise Malli input/output schemas with namespaced keywords; the code graph ingests them as datoms so the question "which functions accept `:seon.x/y`?" is a database query, not a name search. Evidence: `src/seon/graph/{ingest,query,scanner,extract,analyzer}.clj`, vision doc [`docs/seon/vision/index.md`](../vision/index.md).

3. **REPL pipeline as the source-of-truth interface.** Agents do not edit files directly; they eval forms in an nREPL, the eval pipeline validates the schema, transacts metadata into the graph, persists the form to disk, and runs schema-selected tests. Evidence: `src/seon/repl/`, `src/seon/runtime.clj`, the dev hooks under `src/seon/dev/`.

4. **Per-agent JVM isolation with a TCP harness.** Each subagent gets its own JVM and its own nREPL port; the main process communicates over TCP so agents cannot stomp on each other's namespaces. Evidence: `src/seon/flow/harness.clj`, `src/seon/orchestrator/session.clj`, [`docs/seon/components/harness.md`](../components/harness.md), [`docs/seon/components/agent-system.md`](../components/agent-system.md).

5. **Validated, persisted, SSE-pushed context atoms.** Each namespace declares a `::*ctx*` Malli schema; `seon.ctx` creates an atom with schema enforcement on every swap, persistence to disk, and live push to the browser via Datastar / SSE so agent and human can observe state in real time. Evidence: `src/seon/ctx.clj`, `src/seon/ctx/`, `src/seon/web/`, [`docs/seon/components/context.md`](../components/context.md).

The unit of work, throughout, is a typed message into a process-owned namespace whose API is discoverable via a graph. That's the shape the code is built around.

## Repo state and public history

- **Published to GitHub (private)** 2026-04-21: <https://github.com/seantempesta/seon>
- **Flipped to public + AGPL-3.0** 2026-05-09: same URL, with `LICENSE`, `README`, `CONTRIBUTING` (containing the inbound-license clause for relicensing optionality), and a pre-public scrub of the working tree (removed `docs/archive/`, untracked test databases and Obsidian plugins, removed consumer-domain integrations).
- **Tag `v0.1-prior-art-2026-04-21`** is the FreeTSA-anchored RFC 3161 timestamp; SHA preserved unchanged across the public flip.
- **Default branch** `main` as of 2026-05-09.
- **Submodules** under `reference-code/` are vendored upstream dependencies (Datahike, Malli, core.async, nREPL, Datastar, Integrant, etc.) — referenced for context, not claimed.
- **Orientation files** preserved at the repo root: `AGENT.md`, `CLAUDE.md`, `ORCHESTRATOR.md`, `docs/seon/_dashboard.md`. The current `README.md` points to all of them.

## Dependencies and related projects

- **Language / runtime**: Clojure on the JVM; some Node tooling for Datastar / web assets; ClojureScript on Node and (in flight) on `wasm32-wasip2` via wasm-rquickjs.
- **Core libraries**: Datahike (Datalog on LMDB), Malli (schemas), Integrant (lifecycle), core.async + flow (concurrency), nREPL, Datastar / SSE (UI), Tauri + wasmtime (containment for the WASM agent surface).
- **External LLM integration**: Anthropic Claude via the Claude Code CLI and `claude-agent-sdk`; Google Gemini via HTTP for code review and search; DeepSeek via HTTP for the v1 agent REPL.

## Sources

- Repo root: <https://github.com/seantempesta/seon>
- Orientation: [`CLAUDE.md`](../../../CLAUDE.md), [`AGENT.md`](../../../AGENT.md), [`ORCHESTRATOR.md`](../../../ORCHESTRATOR.md)
- Vision: [`docs/seon/vision/index.md`](../vision/index.md)
- System map: [`docs/seon/_dashboard.md`](../_dashboard.md)
- Concept: [`docs/seon/concepts/namespace-as-process.md`](../concepts/namespace-as-process.md)
- Components: [`docs/seon/components/`](../components/)
- Code evidence: `src/seon/flow/`, `src/seon/graph/`, `src/seon/repl/`, `src/seon/ctx.clj`, `src/seon/orchestrator/session.clj`, `src/seon/ai/`
- First commit on this repo: 2025-12-13. Latest: see `git log`.
