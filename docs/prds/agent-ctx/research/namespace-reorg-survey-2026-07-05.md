---
type: research
status: draft
tags: [research]
---

# Namespace reorganization survey — full inventory (2026-07-05)

TL;DR: 210 namespaces (207 at scan start + `seon.repair.candidates` appearing
mid-survey, + 2 phantom-file notes) across **49 top-level `seon.*` segments** and
7 `my.*` segments. ~90 namespaces are pod-active, ~95 are JVM-paused-track,
~15 are cljc/shared — but "`.clj` = paused" is FALSE for `seon.server.*`,
`seon.embed` (clj), `seon.db.datahike.schema`, and all of `seon.dev.*` (wire-server
and dev-hook are live processes). 130 files carry `schema/register!` (1,996 calls
total) — renaming those namespaces renames PERSISTED attributes (cluster-reset or
migration cost); renaming the ~80 register-free namespaces is cheap. Analysis only;
the orchestrator designs the tree from this.

Method: require-graph parsed from `(ns …)` forms with docstrings stripped
(33 fake docstring-mention edges removed); sizes are estimated tokens
(chars/4); external refs grepped across `bin/`, `shadow-cljs.edn`,
`deps.edn`, `config/*.edn`, `src-diffusion/`, `src-inspect-ai/`,
`.claude/`. Read-only; no pod/test runs. Raw intermediates in the session
scratchpad (`edges3.txt`, `master.tsv`, `registers.txt`, `ext-*.txt`).

## Master inventory

Columns: Track (`pod` = .cljs pod-active; `JVM` = .clj — paused EXCEPT the
callouts above; `cljc` = shared; `clj+cljs` = both siblings exist), Tok =
estimated tokens, Deps = direct in-src dependent count, Reg! = `schema/register!`
call count (persisted-attr rename cost proxy), Last = last git touch.

| Namespace | Track | Tok | Deps | Top dependents | Reg! | Last | Purpose |
|---|---|---|---|---|---|---|---|
| `my.blob` | pod | 3071 | 5 | seon.agent.inspect, seon.agent.turn, seon.agent.web | 22 | 2026-07-02 | Content-addressed blob store — the disk tier for LARGE content. |
| `my.data` | pod | 1628 | 2 | my.kb, seon.client | 9 | 2026-07-01 | Turn stored rows into the number your human asked for — SUM, argMAX, |
| `my.kb` | pod | 3742 | 2 | my.kb.shared, seon.client | 22 | 2026-07-01 | Your knowledge base IS the database — schema'd data, never a text blob. |
| `my.kb.shared` | pod | 1282 | 1 | seon.client | 5 | 2026-07-01 | The SYSTEM-WIDE instruction surface — standing guidance shown to ALL |
| `my.plan` | pod | 4653 | 1 | seon.client | 43 | 2026-07-02 | Your PLANNING system — a per-agent dependency graph, not a todo list. |
| `my.plan.internal` | pod | 5895 | 1 | my.plan | 0 | 2026-07-02 | Private plumbing for `my.plan` — fail/agent-scoping helpers, the |
| `my.skills` | pod | 4353 | 1 | seon.client | 10 | 2026-07-01 | Loadable skills — knowledge an agent dials INTO its own context only while |
| `my.canvas` | pod | 2868 | 1 | seon.client | 12 | 2026-07-01 | INTERACTIVE canvas pieces — the sibling of `my.ui` (static). Where |
| `my.ui` | pod | 3312 | 1 | seon.client | 21 | 2026-07-01 | COMPOSE your canvas from small dual-render pieces — don't hand-roll a |
| `seon.agent` | pod | 10294 | 5 | my.plan, seon.agent.loop, seon.client | 51 | 2026-07-05 | The agent RECORD + the agent-facing verbs — 'what an agent IS' (the loop |
| `seon.agent.ctx` | pod | 32329 | 14 | my.skills, seon.agent, seon.agent.ctx.canvas | 32 | 2026-07-05 | Context generation — the ONE block renderer. The prompt IS a REPL |
| `seon.agent.ctx.findings` | pod | 2050 | 1 | seon.client | 0 | 2026-07-01 | The stored-findings context section — the CONTENT counterpart to |
| `seon.agent.ctx.inventory` | pod | 2649 | 1 | seon.client | 0 | 2026-07-01 | The stored-data inventory context section — a cheap, reactive map of |
| `seon.agent.ctx.canvas` | pod | 3397 | 1 | seon.client | 0 | 2026-07-02 | The `:canvas` context section — "what your human currently sees", |
| `seon.agent.ctx.namespaces` | pod | 8598 | 2 | seon.agent, seon.client | 5 | 2026-07-05 | The `:namespaces` context section — THE BODY of the prompt. COMPACT |
| `seon.agent.ctx.relevant` | pod | 1416 | 2 | seon.agent.turn, seon.client | 0 | 2026-07-01 | The `:relevant-source` context section — the top-k embedding-retrieval |
| `seon.agent.ctx.render-fns` | pod | 4690 | 3 | seon.agent.ctx, seon.agent.ctx.canvas, seon.render | 8 | 2026-07-04 | Auto-run — the current ns's render fns become context (context.md |
| `seon.agent.ctx.transcript` | pod | 11382 | 2 | seon.agent, seon.client | 9 | 2026-07-02 | The `:transcript` context section + its `:seon.render/html` twin — the |
| `seon.agent.ctx.usage` | pod | 828 | 2 | seon.ui.header, seon.web.debug | 6 | 2026-07-01 | Read-side extractor for the per-turn LLM token usage — the FIRST (and |
| `seon.agent.ctx.warnings` | pod | 1610 | 2 | seon.agent, seon.client | 0 | 2026-07-05 | The `:warnings` context section — current problems rendered as a |
| `seon.agent.env` | JVM | 2833 | 0 | — | 8 | 2026-05-21 | Agent environment toolkit for graph search, schema discovery, and context persistence. |
| `seon.agent.fs` | pod | 5632 | 3 | seon.agent.search.internal, seon.agent.shell.internal, seon.client | 43 | 2026-07-02 | Local-filesystem capability — your eyes and hands on the user's |
| `seon.agent.fs.internal` | pod | 3237 | 1 | seon.agent.fs | 0 | 2026-07-02 | Filesystem-capability internals — the private data-manipulation + |
| `seon.agent.helpers` | JVM | 1320 | 0 | — | 7 | 2026-05-21 | SQL helpers for agents. These use *ctx* implicitly for cleaner syntax. |
| `seon.agent.inspect` | pod | 7123 | 1 | seon.web.debug | 35 | 2026-07-05 | Agent self-introspection: 'what am I seeing right now?' |
| `seon.agent.internal` | pod | 243 | 1 | seon.agent.lifecycle | 0 | 2026-06-24 | Framework data-manipulation internals for the agent lifecycle verbs. |
| `seon.agent.lifecycle` | pod | 2445 | 2 | seon.client, seon.web.serve | 2 | 2026-07-04 | The agent's run-lifecycle verbs — `wait` / `complete` / `pause` / `resume` |
| `seon.agent.loop` | pod | 9637 | 2 | seon.agent.lifecycle, seon.client | 5 | 2026-07-02 | The agent LOOP — the wake trigger + the run-driven fold. |
| `seon.agent.message` | pod | 3873 | 3 | seon.agent, seon.agent.ctx.transcript, seon.agent.lifecycle | 12 | 2026-07-02 | Message model — THE single write path for `:seon.agent.message` rows. |
| `seon.agent.message.internal` | pod | 1038 | 1 | seon.agent.message | 0 | 2026-07-02 | Private plumbing for `seon.agent.message` — the user-entity predicate |
| `seon.agent.run` | pod | 7420 | 6 | seon.agent.ctx.transcript, seon.agent.lifecycle, seon.agent.loop | 28 | 2026-07-02 | The RUN entity + its lifecycle — a run is the bounded unit of work a |
| `seon.agent.schedule` | pod | 4759 | 2 | seon.agent.loop, seon.client | 17 | 2026-07-01 | Cron-as-data — the SCHEDULE entity + the PURE cron logic. An agent owns a |
| `seon.agent.search` | pod | 4701 | 2 | seon.agent.search.internal, seon.client | 35 | 2026-07-02 | Content search over allowed files — ripgrep (`@vscode/ripgrep`) wrapped |
| `seon.agent.search.internal` | pod | 4747 | 1 | seon.agent.search | 0 | 2026-06-29 | Plumbing behind `seon.agent.search/grep` — the hard caps, the envelope |
| `seon.agent.shell` | pod | 3020 | 2 | seon.agent.shell.internal, seon.client | 21 | 2026-07-02 | Run real commands — argv in, `{exit out err}` out, as data. |
| `seon.agent.shell.internal` | pod | 2287 | 1 | seon.agent.shell | 0 | 2026-07-02 | Plumbing behind `seon.agent.shell` — the hard caps, the envelope |
| `seon.agent.turn` | pod | 8173 | 4 | seon.agent.inspect, seon.agent.loop, seon.diffusion.retrieval | 15 | 2026-07-05 | One agentic TURN, end-to-end — the unit the loop ([[seon.agent.loop]]) |
| `seon.agent.web` | pod | 3877 | 2 | seon.agent.web.internal, seon.client | 29 | 2026-07-04 | Fetch a web page — URL in, extracted markdown + a capped preview out. |
| `seon.agent.web.internal` | pod | 6401 | 1 | seon.agent.web | 0 | 2026-07-04 | Plumbing behind `seon.agent.web` — the SSRF/private-range guard, the |
| `seon.ai` | clj+cljs | 13270 | 10 | seon.agent.inspect, seon.agent.turn, seon.ai.agent | 90 | 2026-07-04 | Base AI namespace defining common schemas and entity persistence. |
| `seon.ai.agent` | JVM | 6201 | 4 | seon.ai.claude, seon.health, seon.web.handlers | 12 | 2026-05-20 | Provider-agnostic agent extension points and registry. |
| `seon.ai.agent.log` | JVM | 3133 | 1 | seon.ai.claude | 0 | 2026-01-23 | Structured per-agent logging to logs/agents/{session-id}.log |
| `seon.ai.agent.views` | JVM | 16220 | 0 | — | 0 | 2026-06-28 | View renderers for agent data types. |
| `seon.ai.anthropic` | pod | 4775 | 1 | seon.client | 4 | 2026-07-04 | Anthropic Messages API client (C-20) on the official |
| `seon.ai.claude` | JVM | 16958 | 0 | — | 32 | 2026-05-27 | Claude Code provider namespace extending seon.ai base. |
| `seon.ai.claude.sdk` | JVM | 2538 | 1 | seon.ai.claude | 10 | 2026-02-21 | Claude Code CLI process management. |
| `seon.ai.diffusiongemma` | pod | 5325 | 1 | seon.client | 27 | 2026-07-05 | DiffusionGemma CONTROL backend — the transformers RunPod worker that |
| `seon.ai.gemini` | JVM | 6882 | 1 | seon.dev.review | 27 | 2026-05-20 | Native Clojure client for the Gemini API. |
| `seon.ai.openai-compat` | pod | 5232 | 1 | seon.client | 3 | 2026-07-04 | OpenAI-compatible chat-completions client on the official `openai` |
| `seon.ai.tokens` | cljc | 943 | 26 | my.blob, my.skills, seon.agent.ctx | 5 | 2026-07-02 | Token <-> char estimation — the ONE place the `chars/4` heuristic lives. |
| `seon.analyzer-info` | pod | 3848 | 2 | seon.client, seon.eval | 10 | 2026-07-05 | Read-side wrapper over the bootstrap-CLJS analyzer state in |
| `seon.claude.exploration` | JVM | 7138 | 0 | — | 8 | 2026-01-19 | EXPERIMENTAL: Protocol exploration and research for Claude Code CLI. |
| `seon.client` | pod | 37096 | 1 | seon.dev.test-preload | 9 | 2026-07-05 | V0 CLJS pod entry point. Long-running Node process; the V0 client. |
| `seon.config` | clj+cljs | 11966 | 21 | my.skills, seon.agent.ctx, seon.agent.ctx.namespaces | 13 | 2026-07-05 | Centralized system configuration loading. |
| `seon.core` | JVM | 2701 | 1 | seon.runner | 0 | 2026-05-21 | Entry point for the Seon system. |
| `seon.ctx` | JVM | 8264 | 5 | seon.agent.env, seon.ns.lifecycle, seon.ns.routes | 31 | 2026-05-27 | Purpose: Unified stateful context for namespace instances — atom + Datahike |
| `seon.ctx.history` | JVM | 1636 | 0 | — | 10 | 2026-02-26 | Pure diff utilities for ctx history. |
| `seon.db` | clj+cljs | 21524 | 66 | my.blob, my.data, my.kb | 54 | 2026-07-05 | Seon's database API. All database access goes through here. |
| `seon.db.datahike.conn-process` | JVM | 2976 | 1 | seon.db.datahike.flow | 9 | 2026-04-19 | A core.async.flow step-fn that owns a single Datahike connection. |
| `seon.db.datahike.flow` | JVM | 3790 | 1 | seon.db.datahike.system | 29 | 2026-05-14 | Build + start a core.async.flow topology that owns datahike connections. |
| `seon.db.datahike.schema` | JVM | 3586 | 2 | seon.db.datahike.conn-process, seon.embed | 1 | 2026-07-03 | Malli -> Datahike schema bridge. |
| `seon.db.datahike.system` | JVM | 898 | 1 | seon.system | 1 | 2026-06-03 | Integrant key for the datahike flow. |
| `seon.db.datahike.tx-bus` | JVM | 1083 | 1 | seon.db.datahike.flow | 6 | 2026-04-19 | core.async.flow step-fn that fans tx-reports out to registered subscribers. |
| `seon.db.internal` | pod | 18105 | 1 | seon.db | 0 | 2026-07-03 | Plumbing behind `seon.db` — validation gate, invocation normalization, |
| `seon.db.relay` | JVM | 3433 | 0 | — | 24 | 2026-04-25 | Cross-JVM `seon.db` relay (Phase 3 step 9 of the datahike migration). |
| `seon.db.schema` | JVM | 1627 | 7 | seon.ctx, seon.db.tx, seon.flow.trace | 0 | 2026-05-20 | Persisted entity schema registry + Malli-level structural validation. |
| `seon.db.tx` | JVM | 904 | 0 | — | 8 | 2026-05-20 | Transaction metadata for Datahike writes. |
| `seon.demo` | pod | 126 | 0 | — | 0 | 2026-06-17 | Demo core ns — the always-on fixture for third-party build-time override. |
| `seon.derive` | pod | 5207 | 12 | seon.agent, seon.agent.ctx.transcript, seon.agent.loop | 5 | 2026-07-02 | The ONE leaf of DB-derived projections — every pure read that turns a db |
| `seon.dev.analysis` | JVM | 4194 | 0 | — | 24 | 2026-03-04 | Unified code analysis using clj-kondo. |
| `seon.dev.clojure-replace` | JVM | 7448 | 0 | — | 22 | 2026-02-04 | Comment-aware s-expression match/replace editing using rewrite-clj. |
| `seon.dev.codebase` | JVM | 3282 | 1 | seon.dev.hook | 12 | 2026-03-11 | Codebase introspection utilities for the development hook. |
| `seon.dev.compliance` | JVM | 7947 | 1 | seon.dev.hook | 19 | 2026-06-08 | Convention compliance checking for Clojure namespaces. |
| `seon.dev.context` | JVM | 7820 | 1 | seon.dev.hook | 50 | 2026-02-19 | Agent context tracking for development feedback. |
| `seon.dev.docstring` | JVM | 3843 | 1 | seon.dev.hook | 26 | 2026-07-01 | WARN-ONLY doc-lint for public-fn docstring FIRST LINES. |
| `seon.dev.hook` | JVM | 11734 | 0 | — | 19 | 2026-07-02 | Main orchestrator for the development feedback hook. |
| `seon.dev.hook-test-ns` | JVM | 831 | 0 | — | 2 | 2026-06-10 | Test namespace for safely experimenting with the unified dev hook. |
| `seon.dev.instrumentation` | JVM | 4336 | 1 | seon.system | 5 | 2026-05-29 | Malli function instrumentation with agent-friendly error messages. |
| `seon.dev.lint` | JVM | 5640 | 3 | seon.dev.clojure-replace, seon.dev.hook, seon.dev.repair | 27 | 2026-03-04 | Shared Clojure validation module for syntax and static analysis. |
| `seon.dev.markdown` | JVM | 9649 | 1 | seon.dev.hook | 39 | 2026-07-01 | Pure markdown analysis namespace for development hook integration. |
| `seon.dev.node-agent` | pod | 733 | 0 | — | 0 | 2026-07-01 | Minimal Node 'agent' process for the multi-runtime MCP-eval go/no-go probe |
| `seon.dev.repair` | JVM | 2209 | 1 | seon.dev.hook | 9 | 2026-03-04 | Delimiter repair for Clojure source code. |
| `seon.dev.review` | JVM | 4751 | 1 | seon.dev.hook | 16 | 2026-06-08 | AI code review for the development hook. |
| `seon.dev.runtime-id` | cljc | 1891 | 3 | seon.client, seon.dev.node-agent, seon.store.internal.wire-node | 0 | 2026-07-03 | The MCP runtime-addressing probe surface (mcp-agent-id-unification |
| `seon.dev.suggestions` | JVM | 2819 | 1 | seon.dev.lint | 13 | 2026-03-04 | Symbol suggestion module using Levenshtein distance. |
| `seon.dev.test` | JVM | 3748 | 0 | — | 0 | 2026-05-20 | REPL-first test system that returns structured data. |
| `seon.dev.test-preload` | pod | 1008 | 0 | — | 0 | 2026-06-27 | Dev-only preload that pulls platform CLJS test namespaces into the |
| `seon.dev.test-select` | JVM | 1766 | 1 | seon.dev.test | 6 | 2026-03-04 | Dependency-aware test selection. |
| `seon.dev.verify` | JVM | 4505 | 3 | seon.dev.hook, seon.dev.test, seon.dev.test-select | 15 | 2026-06-10 | Test orchestration for the development hook. |
| `seon.diffusion.grammar` | cljc | 1157 | 3 | seon.diffusion.oracle, seon.diffusion.retrieval, seon.repair.candidates | 0 | 2026-07-05 | Pure form-SHAPE predicates shared by the CLJS-pod oracle |
| `seon.diffusion.oracle` | pod | 2973 | 0 | — | 9 | 2026-07-02 | UNIFIED control-signal oracle — the buzzsaw the diffusion worker calls ONCE |
| `seon.diffusion.retrieval` | pod | 7321 | 2 | seon.diffusion.oracle, seon.diffusion.scaffold | 13 | 2026-07-05 | RETRIEVAL leg of the diffusion buzzsaw — the third control signal beside |
| `seon.diffusion.scaffold` | pod | 2050 | 0 | — | 13 | 2026-07-01 | SCAFFOLD leg of the diffusion buzzsaw — the Seon-side template generator that |
| `seon.embed` | clj+cljs | 19045 | 6 | seon.agent.turn, seon.diffusion.oracle, seon.diffusion.retrieval | 44 | 2026-07-01 | Embedding-index FOUNDATION for the wire-server (JVM, sole datahike writer). |
| `seon.embed.preflight` | JVM | 2471 | 0 | — | 0 | 2026-06-22 | Loud, third-party-facing self-check for the embedding-backed wire-server. |
| `seon.embed.stash` | pod | 701 | 2 | seon.agent.ctx.relevant, seon.agent.turn | 0 | 2026-07-01 | Per-turn embedding-retrieval stash — an AsyncLocalStorage bridge from the |
| `seon.error` | pod | 6344 | 14 | seon.agent.ctx.render-fns, seon.agent.inspect, seon.agent.turn | 14 | 2026-07-05 | Uniform error→map conversion for the safe-by-default boundary |
| `seon.error.instrument` | cljc | 4070 | 6 | seon.agent.ctx, seon.error, seon.eval | 17 | 2026-07-05 | Phase A item 8 — Malli instrumentation error envelope + renderer. |
| `seon.eval` | pod | 53868 | 12 | seon.agent.ctx, seon.agent.ctx.namespaces, seon.agent.ctx.render-fns | 14 | 2026-07-05 | Agent eval surface. SAFE BY DEFAULT — `eval` returns |
| `seon.eval.bootstrap-cache` | pod | 710 | 2 | seon.eval, seon.worker-eval | 0 | 2026-07-02 | Bootstrap analysis-cache loading — the shared LEAF under `seon.eval` |
| `seon.experimental.sci-exploration` | JVM | 5554 | 0 | — | 0 | 2026-02-01 | Research: Sci (Small Clojure Interpreter) for sandboxed evaluation. |
| `seon.flow.agent-runner` | JVM | 784 | 0 | — | 0 | 2026-05-27 | Minimal agent JVM entry point for isolated process execution. |
| `seon.flow.harness` | JVM | 3011 | 1 | seon.flow.topology | 4 | 2026-02-22 | Orchestrator-side flow process for a single namespace. |
| `seon.flow.harness.bridge` | JVM | 2931 | 1 | seon.flow.harness.proxy | 3 | 2026-03-05 | Agent JVM bridge step-fn for flow-routed namespace isolation. |
| `seon.flow.harness.channel` | JVM | 1367 | 2 | seon.db.relay, seon.flow.harness | 2 | 2026-03-05 | Bidirectional TCP <-> core.async channel adapter. |
| `seon.flow.harness.proxy` | JVM | 1206 | 0 | — | 6 | 2026-05-29 | Proxy namespace generation for transparent cross-namespace calls. |
| `seon.flow.msg` | JVM | 1094 | 9 | seon.db.datahike.conn-process, seon.db.datahike.flow, seon.flow.harness | 23 | 2026-03-06 | Message envelope schemas for flow wire protocol. |
| `seon.flow.pool` | JVM | 9350 | 5 | seon.flow.harness, seon.flow.topology, seon.repl | 13 | 2026-06-03 | Pre-warmed JVM pool for instant agent startup. |
| `seon.flow.status` | JVM | 4165 | 3 | seon.flow.topology, seon.system, seon.web.flows | 9 | 2026-06-10 | Collects runtime status from all registered flows. |
| `seon.flow.topology` | JVM | 6790 | 2 | seon.db.datahike.flow, seon.system | 3 | 2026-05-20 | Reply router and topology wiring for flow-routed namespace isolation. |
| `seon.flow.trace` | JVM | 1494 | 2 | seon.flow.harness, seon.system | 10 | 2026-05-20 | Flow event tracing and persistence. |
| `seon.graph.analyzer` | JVM | 3116 | 2 | seon.graph.ingest, seon.repl | 21 | 2026-05-20 | Code analysis for the knowledge graph. |
| `seon.graph.context` | JVM | 4052 | 2 | seon.render.code, seon.repl.context | 7 | 2026-05-20 | Topological context builder for AI agents. |
| `seon.graph.extract` | JVM | 7690 | 0 | — | 3 | 2026-03-18 | Unified code graph extraction pipeline. |
| `seon.graph.ingest` | JVM | 7663 | 1 | seon.repl | 60 | 2026-05-20 | Ingest analysis data into the Datahike knowledge graph. |
| `seon.graph.query` | JVM | 5134 | 6 | seon.agent.env, seon.dev.test-select, seon.ns.lifecycle | 7 | 2026-05-20 | Datalog query API for the knowledge graph. |
| `seon.graph.scanner` | JVM | 4043 | 1 | seon.graph.extract | 4 | 2026-05-20 | Static source scanner for spec/schema and var extraction. |
| `seon.handlers.eval` | pod | 2092 | 1 | seon.client | 0 | 2026-07-02 | Renderers for `:seon.eval` entities — what the LLM sees of its own |
| `seon.handlers.fn` | pod | 2437 | 2 | seon.agent.ctx, seon.client | 0 | 2026-06-24 | Renderers for `:seon.fn` entities — fns the agent has defined via |
| `seon.handlers.message` | pod | 1449 | 1 | seon.client | 0 | 2026-06-28 | Renderers for `:seon.agent.message` entities — AI-text + HTML forms |
| `seon.handlers.ns` | pod | 1460 | 2 | seon.agent.ctx, seon.client | 0 | 2026-07-01 | Renderers for `:seon.ns` entities — namespaces the agent has created |
| `seon.handlers.schema` | pod | 743 | 2 | seon.agent.ctx, seon.client | 0 | 2026-07-01 | Renderers for `:seon.schema` entities — Malli schemas the agent has |
| `seon.handlers.test` | pod | 1685 | 1 | seon.agent.ctx | 0 | 2026-07-01 | Renderers for `:seon.test` entities — `deftest`s the agent has |
| `seon.health` | JVM | 4392 | 3 | seon.ai.claude, seon.core, seon.web.handlers | 11 | 2026-06-11 | System health checks and monitoring. |
| `seon.health.metrics` | JVM | 604 | 0 | — | 6 | 2026-02-22 | Body composition metrics: BMI computation and categorization. |
| `seon.indexing` | JVM | 1817 | 2 | seon.client, seon.dev.test-preload | 0 | 2026-07-02 | Compile-time enumeration of the CLJS build's program-graph surface. |
| `seon.instrument` | cljc | 9269 | 3 | seon.agent.ctx.warnings, seon.client, seon.eval | 0 | 2026-07-05 | Malli instrumentation for every specced first-party fn — registration |
| `seon.items` | pod | 300 | 2 | my.data, seon.client | 3 | 2026-06-28 | The shared self-describing-collection envelope — a `:seon.items/*` |
| `seon.log` | pod | 4402 | 13 | seon.agent.loop, seon.agent.turn, seon.ai | 11 | 2026-07-01 | Structured event logging for the pod — error / warn / info / debug |
| `seon.logging` | JVM | 2525 | 1 | seon.core | 7 | 2026-05-20 | Centralized Timbre logging configuration for Seon. |
| `seon.ns.example` | JVM | 1546 | 0 | — | 2 | 2026-01-23 | Example namespace demonstrating the view system. |
| `seon.ns.introspect` | JVM | 1078 | 2 | seon.ns.routes, seon.web.namespace | 0 | 2026-01-23 | Generic namespace introspection at runtime. |
| `seon.ns.lifecycle` | JVM | 4826 | 2 | seon.core, seon.ns.routes | 29 | 2026-05-21 | Lifecycle management for dynamic namespaces. |
| `seon.ns.routes` | JVM | 11522 | 1 | seon.web.routes | 0 | 2026-05-15 | Namespace HTTP routes: page rendering, introspection, and function calls. |
| `seon.ns.view` | JVM | 4607 | 4 | seon.ai.agent, seon.ai.agent.views, seon.ns.example | 10 | 2026-06-28 | Namespace-based view system for rendering Clojure values in multiple formats. |
| `seon.phase2.demo` | JVM | 171 | 1 | seon.system | 2 | 2026-04-19 | Phase 2 demo namespace -- purely a test fixture for the datahike routing work. |
| `seon.platform` | pod | 953 | 17 | my.blob, seon.agent.fs, seon.agent.fs.internal | 0 | 2026-07-01 | Runtime host detection. Returns `:node` when running under Node.js |
| `seon.render` | clj+cljs | 22443 | 14 | seon.agent.ctx, seon.agent.ctx.canvas, seon.agent.ctx.transcript | 25 | 2026-07-04 | Multi-format rendering with code-graph-based renderer resolution. |
| `seon.render.chat` | pod | 3044 | 1 | seon.render.canvas | 12 | 2026-07-01 | The conversation surface — chat bubbles for the consumer agent view |
| `seon.render.code` | JVM | 3805 | 1 | seon.ai.claude | 6 | 2026-05-29 | Code and documentation rendering from the knowledge graph. |
| `seon.render.default` | pod | 3047 | 3 | seon.client, seon.render, seon.render.chat | 0 | 2026-07-01 | Default renderers + shared DB-read helpers for the render surface. |
| `seon.render.default-page` | JVM | 2607 | 1 | seon.ns.routes | 4 | 2026-02-26 | Default page template for namespaces with *ctx* but no custom renderer. |
| `seon.render.canvas` | pod | 7961 | 4 | seon.agent.ctx.canvas, seon.agent.ctx.render-fns, seon.client | 14 | 2026-07-02 | The canvas — the ONE thing an agent is currently conveying to |
| `seon.render.sci` | pod | 8639 | 2 | seon.agent.ctx.render-fns, seon.render | 1 | 2026-07-05 | SCI-bounded invocation for AGENT-authored canvas fns (tile-isolation |
| `seon.render.system` | pod | 5275 | 2 | seon.client, seon.ui.header | 4 | 2026-07-02 | Root's SYSTEM VIEW — the `/` dashboard that IS root's world canvas. |
| `seon.render.value` | pod | 5383 | 2 | seon.eval, seon.render | 0 | 2026-07-02 | Structural value renderer — the render-twin applied to EVERY eval value. |
| `seon.repair` | cljc | 3337 | 1 | seon.eval | 17 | 2026-07-01* | Best-effort delimiter repair for one Clojure form, via parinferish |
| `seon.repair.candidates` | pod | 1575 | 1 | seon.worker-eval | 0 | (uncommitted) | Shared candidate/distance/tier intelligence for SYMBOL auto-fix. |
| `seon.repl` | clj+cljs | 5128 | 5 | seon.agent.loop, seon.client, seon.repl.graduate | 22 | 2026-07-02 | REPL form router. |
| `seon.repl.context` | JVM | 1183 | 0 | — | 4 | 2026-03-04 | Context cockpit for AI agents. |
| `seon.repl.graduate` | JVM | 1594 | 0 | — | 8 | 2026-05-20 | Namespace graduation: assembles Datahike-stored forms into a .clj file, |
| `seon.repl.internal` | cljc | 10561 | 7 | seon.agent.loop, seon.agent.turn, seon.client | 0 | 2026-07-03 | REPL text parser — turns an LLM reply (text containing `;` comments |
| `seon.result` | pod | 155 | 1 | seon.items | 1 | 2026-06-28 | The shared result discriminator `:seon.result/ok?` — a boolean that |
| `seon.retry` | pod | 2273 | 1 | seon.agent.turn | 9 | 2026-07-01 | Composable async retry — a general resilience primitive (NOT |
| `seon.route` | pod | 1553 | 1 | seon.client | 7 | 2026-07-01 | Routing-as-data: the `:seon.route/*` schema + the seeded CORE route set. |
| `seon.runner` | JVM | 195 | 0 | — | 0 | 2025-12-13 | Long-running server entry point. |
| `seon.runtime` | JVM | 9823 | 9 | seon.ai.agent, seon.ai.claude, seon.flow.status | 65 | 2026-06-17 | Unified runtime registry for all namespace instances. |
| `seon.schema` | cljc | 5199 | 129 | my.blob, my.data, my.kb | 8 | 2026-07-01 | Global Malli schema registry for Seon — the SINGLE SOURCE OF TRUTH for |
| `seon.schema.internal` | cljc | 1781 | 1 | seon.schema | 0 | 2026-06-27 | Malli-form mechanics and register!-time gates for `seon.schema`. |
| `seon.server.boot` | JVM | 3036 | 0 | — | 0 | 2026-07-02 | Wire-server boot entry — the platform-lane glue that composes the listener |
| `seon.server.broadcast` | JVM | 1463 | 2 | seon.server.boot, seon.server.wire | 0 | 2026-07-04 | Pub fanout, per-DB routed. The writer's `d/listen!` `::raw-broadcast` |
| `seon.server.client` | JVM | 1067 | 0 | — | 0 | 2026-06-24 | Tiny smoke client. Connects to the writer's req socket and runs a sequence |
| `seon.server.codec` | JVM | 450 | 3 | seon.server.broadcast, seon.server.client, seon.server.wire | 0 | 2026-06-24 | Transit-JSON codec + length-framed I/O. |
| `seon.server.reactive` | JVM | 4039 | 1 | seon.server.boot | 14 | 2026-06-24 | Per-conn reactive engine. Routes each datahike commit to the subscriptions it |
| `seon.server.registry` | JVM | 7243 | 4 | seon.embed, seon.server.boot, seon.server.wire | 38 | 2026-07-05 | The wire-server's cluster RUNTIME registry — atom of `{db-name -> entry}` |
| `seon.server.store` | JVM | 1800 | 2 | seon.server.registry, seon.server.wire | 7 | 2026-06-03 | Build datahike config maps for the wire-server's per-session DBs. |
| `seon.server.transit` | JVM | 420 | 0 | — | 0 | 2026-06-24 | Transit-JSON string codec helpers. |
| `seon.server.wire` | JVM | 9358 | 2 | seon.embed, seon.server.boot | 0 | 2026-07-02 | Sidecar JVM writer: owns the single Datahike connection and answers requests |
| `seon.session` | JVM | 7380 | 2 | seon.ai.claude, seon.system | 41 | 2026-06-03 | Agent session ENTITY layer — the canonical session-as-datom record. |
| `seon.state` | pod | 1435 | 1 | seon.client | 8 | 2026-07-01 | Holistic system-state reconcile — make the DB's MANAGED datoms match a |
| `seon.store.internal.wire-node` | pod | 4712 | 2 | seon.embed, seon.store.wire | 0 | 2026-07-02 | Plain-Node (NO WASM) UDS transport to the JVM wire-server, built as a |
| `seon.store.wire` | pod | 8402 | 2 | seon.agent.inspect, seon.client | 6 | 2026-07-02 | THE pod↔cluster-store seam (unit 2.2e — the flip). |
| `seon.system` | JVM | 2938 | 2 | seon.ai.claude, seon.core | 0 | 2026-06-03 | Integrant system configuration and component definitions. |
| `seon.system.config` | JVM | 588 | 0 | — | 0 | 2026-05-20 | Malli schemas for Integrant component configurations. |
| `seon.test.runner` | pod | 10474 | 2 | seon.client, seon.eval | 22 | 2026-07-01 | Phase 2 — test capture as data. |
| `seon.ui.clojure` | pod | 2105 | 1 | seon.render | 1 | 2026-06-28 | Server-side Clojure syntax highlighter — `clj->hiccup`. A pure CLJS leaf, |
| `seon.ui.components` | cljc | 2737 | 2 | seon.render.default, seon.web.debug | 0 | 2026-07-01 | Shared UI component library for Phosphor Terminal styling. CLJC port |
| `seon.ui.header` | pod | 3425 | 3 | seon.ui.world, seon.web.datastar, seon.web.debug | 3 | 2026-07-01 | The persistent global status bar — `system-header = f(db)` — rendered as a |
| `seon.ui.html` | cljc | 3638 | 4 | seon.render, seon.render.canvas, seon.web.datastar | 0 | 2026-07-01 | Hiccup → HTML-string. Pure data transform; portable across CLJ + CLJS. |
| `seon.ui.markdown` | pod | 2257 | 4 | seon.agent.ctx, seon.render, seon.render.chat | 0 | 2026-07-01 | Minimal markdown → hiccup renderer for chat messages. |
| `seon.ui.viewer` | JVM | 2457 | 1 | seon.web.namespace | 0 | 2026-01-23 | Value viewer with multimethod dispatch for rendering Clojure values as Hiccup. |
| `seon.ui.world` | pod | 2882 | 1 | seon.web.datastar | 0 | 2026-06-28 | The per-agent world layout — `world-layout = f(db, agent-id)` → the |
| `seon.warn` | pod | 12525 | 4 | seon.agent.ctx, seon.agent.ctx.warnings, seon.agent.loop | 13 | 2026-07-05 | Compositional, clustered warning checks over the program-graph corpus |
| `seon.web.brand` | pod | 2330 | 3 | seon.ui.header, seon.web.datastar, seon.web.debug | 12 | 2026-07-01 | Downstream brand surface (fix-everything PRD C-17) — the product |
| `seon.web.brotli` | JVM | 1657 | 0 | — | 0 | 2026-02-22 | Brotli compression utilities for streaming SSE connections. |
| `seon.web.browser` | JVM | 6472 | 1 | seon.web.routes | 9 | 2026-03-05 | REPL-to-browser execution bridge. |
| `seon.web.caddy` | JVM | 1002 | 1 | seon.system | 0 | 2026-02-27 | Caddy reverse proxy Integrant component. |
| `seon.web.components` | JVM | 2488 | 3 | seon.ai.agent.views, seon.ns.routes, seon.web.flows | 0 | 2026-01-23 | Shared UI component library for consistent Phosphor Terminal styling. |
| `seon.web.datastar` | pod | 7359 | 1 | seon.web.router | 0 | 2026-07-01 | Datastar gzip-morph SSE streamer — the hyperlith `view = f(db)` model |
| `seon.web.debug` | pod | 13885 | 2 | seon.client, seon.web.router | 0 | 2026-07-02 | Operator dev tools — the two surfaces that have NO world-page equivalent: |
| `seon.web.flows` | JVM | 2330 | 1 | seon.web.routes | 0 | 2026-02-22 | Flow monitor page — shows all registered flows with status, |
| `seon.web.handlers` | JVM | 1137 | 1 | seon.web.routes | 0 | 2026-02-27 | HTTP request handlers. |
| `seon.web.html` | JVM | 4054 | 4 | seon.ns.routes, seon.web.flows, seon.web.handlers | 0 | 2026-02-26 | HTML templating using Chassis (compile-time Hiccup). |
| `seon.web.logs` | JVM | 639 | 1 | seon.web.handlers | 0 | 2026-01-23 | Log viewer state management and log fetching. |
| `seon.web.namespace` | JVM | 810 | 0 | — | 0 | 2026-01-29 | Namespace introspection web handlers. |
| `seon.web.reactive.actions` | JVM | 498 | 1 | seon.ns.routes | 0 | 2026-02-26 | Action resolution for reactive UI. |
| `seon.web.reactive.call` | pod | 3562 | 1 | seon.web.router | 0 | 2026-07-01 | The `/call` route — agent fn-calls authored as hiccup, routed by NAMESPACE |
| `seon.web.reactive.demo` | JVM | 1470 | 0 | — | 0 | 2026-01-31 | Demo page for reactive UI - now instance-based. |
| `seon.web.reactive.transform` | clj+cljs | 4268 | 3 | seon.ns.routes, seon.render, seon.web.reactive.call | 0 | 2026-07-01 | Hiccup transformation for reactive UI. |
| `seon.web.router` | pod | 4799 | 1 | seon.web.serve | 0 | 2026-07-02 | The pod's HTTP front door — reitit over a route vector DERIVED from the |
| `seon.web.routes` | JVM | 1136 | 1 | seon.web.server | 0 | 2026-05-21 | Simple map-based router for HTTP endpoints. |
| `seon.web.serve` | pod | 12887 | 1 | seon.client | 0 | 2026-07-05 | Pod-side HTTP+SSE server on a loopback ephemeral port. |
| `seon.web.server` | JVM | 1326 | 0 | — | 0 | 2026-05-21 | HTTP server Integrant component using http-kit. |
| `seon.web.sse` | JVM | 4473 | 5 | seon.ns.routes, seon.web.flows, seon.web.handlers | 0 | 2026-02-26 | SSE (Server-Sent Events) implementation following Datastar SDK patterns. |
| `seon.web.sse.flow` | JVM | 3392 | 0 | — | 0 | 2026-03-05 | Flow-based SSE infrastructure for code change propagation. |
| `seon.web.tailwind` | JVM | 1383 | 1 | seon.system | 0 | 2026-02-27 | Tailwind CSS watcher Integrant component. |
| `seon.worker-eval` | pod | 9442 | 0 | — | 0 | 2026-07-05 | LEAN, STANDALONE EVAL/CORRECTNESS oracle for CO-LOCATION on the diffusion |
| `seon.worker-validator` | pod | 2292 | 0 | — | 0 | 2026-07-03 | LEAN, STANDALONE parse/syntactic oracle for CO-LOCATION on the diffusion |

\* `seon.repair.cljc` and `seon.worker_eval.cljs` have UNCOMMITTED working-tree
edits (the in-flight symbol-autofix unit); `src/seon/repair/candidates.cljs` is
untracked and appeared mid-survey. Its docstring says it is "The ONE mechanism
behind both repair consumers … the pod agent-eval pre-flight gate (`seon.eval`)
and the worker-eval bundle's `op:"repair"`" — so `seon.eval` will require it
once that unit lands (only `seon.worker-eval` requires it today).

## Top-level segment census

49 distinct `seon.*` second segments: agent, ai, analyzer-info, claude, client,
config, core, ctx, db, demo, derive, dev, diffusion, embed, error, eval,
experimental, flow, graph, handlers, health, indexing, instrument, items, log,
logging, ns, phase2, platform, render, repair, repl, result, retry, route,
runner, runtime, schema, server, session, state, store, system, test, ui, warn,
web, worker-eval, worker-validator. Plus 7 `my.*`: blob, data, kb, plan, skills,
tile, ui.

Of the 49, **29 are single-namespace or 2-file segments** — the merge surface
the owner is after.

## Owner questions — evidence

### `seon.repair` (.cljc)

- **Who requires it:** exactly ONE ns — `seon.eval` (the parinfer repair
  sub-loop in `eval-batch!`). `seon.repl.internal` mentions it only in
  docstrings/comments ("SAFE: parinferish closes it (`seon.repair`)") — no
  require. `seon.worker-eval` uses `:seon.repair/*` KEYWORDS as its result
  envelope but requires only `seon.repair.candidates`.
- **Coupling verdict:** the require-graph coupling is to the **EVAL side**.
  The parse side (`seon.repl.internal`) produces the `:read` entry that
  TRIGGERS repair, but never calls it. `seon.repl.repair` would be
  half-truthful: `seon.repl` (cljs) itself doesn't touch it; the sole call
  site is `seon.eval`. If a family must own it, `seon.eval.*` matches actual
  call sites better than `seon.repl.*`. Counterpoint: the new
  `seon.repair.candidates` is deliberately dependency-light (grammar only, no
  seon.schema/db) so the worker bundle can include it — pushing it UNDER
  `seon.eval` would drag worker-eval's bundle toward the eval family name
  while its only heavy consumer is the standalone worker.
- **::keywords owned (17 register! calls):** `:seon.repair/` source,
  repaired?, reads?, change, changes, note, level, class, classes,
  class-enabled-request, applied-class, from, to, fix, fixes, source-request,
  result. These are RESULT-ENVELOPE keys (validated fn args/returns), not
  observed in any `transact!` — grep shows no `:seon.repair/*` datom writes,
  so the rename cost here is code + instrumentation schemas, **not** stored
  data. Cheap-ish despite the register! count.
- **Mid-survey drift:** `repair.cljc` gained ~9 register! calls (class/fix
  machinery) in the uncommitted working tree; re-check after the autofix unit
  lands.

### `seon.dev.repair` (.clj) vs `seon.repair` (.cljc)

- Both call parinferish indent-mode repair. `seon.repair`'s own docstring
  admits the lineage: "The JVM-only `seon.dev.repair` is the pattern this
  mirrors; we drop its cljfmt step … and its edamame `delimiter-error?`
  probe."
- Actual overlap: the parinferish invocation + changed-and-re-reads accept
  logic (~1/3 of dev.repair's 2,209 tok). dev.repair's extras: edamame
  `delimiter-error?` probe, cljfmt `repair-and-format`, `seon.dev.lint`
  integration.
- **Could the dev hook require the .cljc directly?** Mechanically yes —
  `seon.repair` is cljc, JVM-loadable, and its only deps are parinferish +
  seon.schema (both on the hook JVM's classpath; the hook already loads
  seon.schema-requiring nses). The hook would keep `delimiter-error?` and the
  cljfmt step somewhere (either folded into `seon.dev.lint` or kept as a thin
  dev-side wrapper that delegates the repair core to `seon.repair`). Verdict:
  genuine duplicate mechanism, consolidation feasible; dev.repair untouched
  since 2026-03-04.

### Loose top-level singles — family coupling by require-graph

| Namespace | Verdict | Evidence |
|---|---|---|
| `seon.analyzer-info` | eval family | in: eval, client; wraps `@compile-state` which seon.eval owns |
| `seon.client` | genuine root | THE pod entry (shadow `:entries`); requires 59 nses |
| `seon.core` / `seon.runner` / `seon.system` / `seon.system.config` / `seon.logging` | one JVM-boot family | runner→core→system chain; logging required only by core |
| `seon.ctx` + `seon.ctx.history` | JVM ns-instance family (with ns.*, session, runtime) | in: agent.env, ns.lifecycle, ns.routes, session, web.browser — all JVM-paused; history has 0 dependents |
| `seon.demo` | build fixture | 0 src dependents; lives in shadow `:preloads` (3 builds) |
| `seon.derive` | genuine root leaf (db-read) | 12 dependents spanning agent + render + ui + web; requires only db+schema |
| `seon.embed` | server family (wire-server) + pod query sibling | clj side requires server.registry/wire/store.internal; consumers agent.turn + diffusion.* |
| `seon.error` + `seon.error.instrument` + `seon.instrument` | ONE error/instrumentation family | mutually entangled: error→error.instrument; instrument→{error, error.instrument}; eval→all three |
| `seon.eval` | genuine root (largest ns, 53.9k tok) | 12 dependents |
| `seon.health` (+ `.metrics`) | JVM boot/web family | in: ai.claude, core, web.handlers; metrics = BMI leftovers (stale) |
| `seon.indexing` | eval/client build-time | macros emitting client's core-vars; in: client, test-preload |
| `seon.items` + `seon.result` | one shape ns | result's SOLE consumer is items (155 tok + 300 tok) — merge candidate into one envelope ns |
| `seon.log` | genuine pod root leaf | 13 dependents across agent/render/web |
| `seon.ns.*` | JVM ns-instance family with ctx/runtime/session | ns.routes sole consumer = web.routes; lifecycle ← core |
| `seon.phase2.demo` | JVM test fixture | in: system (referenced from resources/system.edn); 171 tok |
| `seon.platform` | genuine pod root leaf | 17 dependents, requires nothing |
| `seon.repair` | eval family (see above) | sole requirer seon.eval |
| `seon.repl` | pod: bridge eval↔graph; JVM: form router | pod in: agent.loop, client, web.serve; both lanes exist |
| `seon.retry` | generic leaf, sole consumer agent.turn | utility; family-neutral |
| `seon.route` | web family | owns `:seon.route/*` schema that `seon.web.router` projects; sole requirer client (seeding) |
| `seon.runtime` | JVM system family | 9 dependents, all JVM-paused |
| `seon.session` | JVM system family | in: ai.claude, system |
| `seon.state` | db family | reconcile! over managed datoms; requires db+schema; sole requirer client |
| `seon.store.wire` (+ internal.wire-node) | db family (the pod's write transport) | "THE pod↔cluster-store seam" |
| `seon.test.runner` | eval/test family | in: client, eval; `seon.dev.test-preload` refs it in docstring only |
| `seon.warn` | agent.ctx family | all 4 dependents are agent.ctx/loop/message |
| `seon.worker-eval` / `seon.worker-validator` | diffusion-oracle family | 0 src dependents; standalone shadow builds consumed by bin/oracle-server + the GPU worker |

### `seon.ctx` vs `seon.agent.ctx`

**Genuinely different layers, not one split domain.** `seon.ctx` (.clj,
JVM-paused, last real touch 2026-05-27) is "unified stateful context for
namespace instances — atom + Datahike persistence + SSE push"; its 5 dependents
are all JVM-paused (agent.env, ns.lifecycle, ns.routes, session, web.browser).
`seon.agent.ctx` (.cljs, pod, touched daily) is prompt/context GENERATION —
"the ONE block renderer". Zero shared dependents, zero shared code, different
tracks. The collision is purely lexical; the cost is that `:seon.ctx/*` (31
register! calls, JVM) and `:seon.agent.ctx/*` (32, pod) are unrelated attr
families that look related.

### `seon.handlers.*` — misnamed family

They are **not HTTP handlers**. All six are entity RENDERERS for the pod:
"Renderers for `:seon.eval` / `:seon.fn` / `:seon.ns` / `:seon.agent.message` /
`:seon.schema` / `:seon.test` entities" — the render-ai/render-html fn pairs
stamped on entities and resolved by `seon.render`. Consumers: `seon.client`
(registration) and `seon.agent.ctx`. Family by function: **render**. They own
ZERO registered schemas, so renaming all six is cheap (code-only + the
`seon.render/*` symbol references + seeded `:seon.render/ai` handler symbols in
the store — see rename costs).

### Duplicate-mechanism candidates — evidence per pair

| Pair | Same domain? | Evidence |
|---|---|---|
| `seon.dev.repair` vs `seon.repair` | YES — real logic duplicate | see above; consolidation feasible |
| `seon.log` (pod) vs `seon.logging` (JVM) | Same word, different mechanisms | log = structured events → console + DB datoms (13 pod dependents); logging = Timbre appender CONFIG, sole consumer seon.core. No shared code. Merge is naming-only, not dedup |
| `seon.instrument` vs `seon.error.instrument` vs `seon.dev.instrumentation` | 2 of 3 are one family; the 3rd is the JVM twin | instrument (cljc) = the wrapper/census; error.instrument (cljc) = the error ENVELOPE the wrapper emits — same domain, already require each other; dev.instrumentation (clj, sole consumer seon.system) = the PAUSED JVM equivalent of `seon.instrument` (malli.instrument wrapper). Three homes for one concept across two tracks |
| `seon.test.runner` vs `seon.dev.test` vs `seon.dev.test-select`/`verify` | Two tracks of one concept | test.runner (pod) = run cljs.test vars → `:seon.test/*` datoms; dev.test (JVM, 0 dependents since the hook calls dev.verify directly) = REPL-first JVM test runner; dev.test-select/verify = the hook's affected-test machinery. dev.test looks orphaned (0 in-src dependents; `(user/run-tests)` on the paused track is its consumer via dev/ classpath) |
| `seon.route` vs `seon.web.router` vs `seon.web.routes` | route+router = one pod mechanism in two nses; web.routes = the paused JVM router | route owns the `:seon.route/*` schema + seed; web.router projects those datoms into reitit. Deliberate split (schema/seed vs projection) but same domain — merge candidate under web. web.routes (JVM, "simple map-based router") is unrelated code on the paused track |
| `seon.state` vs `seon.runtime` vs `seon.session` | NOT one domain | state (pod) = declarative datom reconcile; runtime (JVM) = live registry of namespace instances (65 register!); session (JVM) = session-as-datom entity layer. Different tracks and different mechanisms; only `runtime` vs `session` overlap conceptually (both JVM "what's running"), and session already requires runtime |
| `seon.ui.components` (cljc) vs `seon.web.components` (clj) | YES — near-duplicate | ui.components docstring: "CLJC port" of the Phosphor component library; web.components is the JVM original (consumers: ai.agent.views, ns.routes, web.flows — all paused). Fold JVM callers onto the cljc when the JVM track resumes |
| `seon.ui.viewer` / `seon.ns.view` / `seon.render.value` | partial | viewer (JVM 2026-01) + ns.view (JVM) = paused-track value/view rendering; render.value (pod) = the current mechanism. Cross-track triplication of "render a Clojure value" |

### Cross-boundary rename costs — where ns names are STRINGS outside src/

- **shadow-cljs.edn** (build entries/preloads): `seon.runner`(comment),
  `seon.client`, `seon.demo`, `seon.dev.test-preload`, `seon.test.runner`,
  `seon.dev.runtime-id`, `seon.dev.node-agent`, `seon.server.codec`,
  `seon.store.internal.wire-node`, `seon.worker-validator`,
  `seon.worker-eval`, `seon.repl.internal`, `seon.db`, `seon.eval`,
  `seon.schema`.
- **deps.edn** (aliases/exec-fns): `seon.server.boot`, `seon.server.wire`,
  `seon.server.reactive`, `seon.server.transit`, `seon.db.relay`,
  `seon.flow.agent-runner`, `seon.flow.harness`, `seon.runner`,
  `seon.web.router`, `seon.dev.test-preload`.
- **bin/ scripts** (heaviest): bin/seon → agent.ctx, agent.fs/search/shell/
  web/inspect/turn, config, error, eval, platform, server.registry,
  store.wire, web.serve, dev.test-preload; bin/seon-hook → dev.hook, config,
  db, error; bin/oracle-server → worker-validator, diffusion.grammar/oracle,
  repl, repl.internal; bin/acme → agent.shell/web, config, my.plan;
  bin/test-cljs → config, error, eval, schema; bin/mcp-server →
  dev.clojure-replace, repl.context, session, graph.query, dev.runtime-id;
  bin/mcp-server-cljs → agent, dev.runtime-id, store.wire.
- **config/*.edn** (manifest sections + block/handler SYMBOLS): seon.config,
  seon.agent.ctx(+.warnings/.render-fns/.canvas), seon.agent(.message/
  .lifecycle/.search/.shell/.web), seon.ai, seon.eval, seon.render(.system/
  .canvas), seon.route, seon.db, seon.schema, seon.error, my.kb/ui/canvas/
  plan/data/skills/blob. Renaming anything here breaks manifests until
  edited.
- **Python** (src-diffusion + src-inspect-ai): `my.plan` (40 refs — the
  drive/scorer prompts), `seon.ai` (21), `seon.server.registry` (7),
  `seon.db`, `seon.agent`, `seon.diffusion.oracle/retrieval`, `seon.web.serve`,
  `seon.repl.internal`, `seon.config`, `seon.ai.diffusiongemma`.
- **.claude/** config (hook edn + agent defs; the huge grep count is
  seon-hook.log noise): `seon.dev.repair`, `seon.dev.docstring`,
  `seon.dev.context` in `.claude/seon-hook.edn`.
- **Seeded store data** (`data/clusters/*/store`): every core fn/ns/schema/
  route entity persists its ns name (`:seon.fn/ns`, `:seon.route/handler`
  symbols, `:seon.render/ai` handler symbols, all `:seon.<ns>/*` attribute
  idents). ANY rename of a register!-owning or seeded ns ⇒ `bin/seon cluster
  reset` (core seed regenerates; agent-authored work in that store is wiped).
  This is the blanket cost on the pod side; per-ns it scales with the Reg!
  column.
- **Highest-cost renames** (many boundaries at once): `seon.db`,
  `seon.schema`, `seon.eval`, `seon.config`, `seon.agent(.ctx)`, `seon.ai`,
  `my.plan`, `seon.server.registry`, `seon.render`. **Cheap renames** (Reg!=0,
  no external strings): the six `seon.handlers.*`, `seon.instrument`,
  `seon.repl.internal` (shadow ref only), all `*.internal` nses,
  `seon.derive` (Reg!=5 but query-only helper keys — verify), most `seon.ui.*`.

### Stale / experimental flags

0 in-src dependents AND no live external consumer AND old last-touch:

| Namespace | Last | Note |
|---|---|---|
| `seon.claude.exploration` | 2026-01-19 | says EXPERIMENTAL in its own docstring |
| `seon.experimental.sci-exploration` | 2026-02-01 | research spike; SCI is now vendored + used elsewhere |
| `seon.ns.example` | 2026-01-23 | view-system demo |
| `seon.web.namespace` / `seon.web.reactive.demo` / `seon.web.brotli` / `seon.web.sse.flow` | 2026-01/02/03 | JVM web leftovers, 0 dependents |
| `seon.health.metrics` | 2026-02-22 | BMI/body-composition — violates the "no consumer-domain code in src/" rule outright |
| `seon.ctx.history` | 2026-02-26 | 0 dependents |
| `seon.repl.context` | 2026-03-04 | 0 in-src dependents; still loaded by bin/mcp-server |
| `seon.graph.extract` (+ scanner via it) | 2026-03-18 | 0 dependents |
| `seon.dev.analysis` | 2026-03-04 | 0 dependents (dev.lint has its own kondo path) |
| `seon.phase2.demo` | 2026-04-19 | fixture; name is a dead phase marker |
| `seon.agent.env` / `seon.agent.helpers` | 2026-05-21 | helpers docstring says "SQL helpers" — pre-datahike-era text; 0 dependents |
| `seon.db.tx` / `seon.db.relay` | 2026-05 | relay 0 in-src deps but referenced from deps.edn alias |
| `seon.repl.graduate` / `seon.dev.test` / `seon.flow.harness.proxy` / `seon.system.config` | 2026-05 | 0 dependents |
| `seon.embed.preflight` / `seon.server.client` / `seon.server.transit` | 2026-06 | operational smoke/CLI tools, 0 dependents — likely intentional keepers |
| `seon.ai.agent.views` (16.2k tok!) / `seon.ai.claude` (17k tok) | 2026-06-28 / 05-27 | views has 0 dependents; claude has 0 in-src dependents (loaded dynamically?) — biggest stale-token mass in the tree |

Zero-dependent but ALIVE via external entry points (do not flag): dev.hook
(bin/seon-hook), dev.test-preload/demo/node-agent/runner/worker-eval/
worker-validator (shadow), server.boot/flow.agent-runner (deps.edn),
diffusion.oracle (bin/oracle-server), dev.clojure-replace (bin/mcp-server),
dev.hook-test-ns (hook fixture).

## Smells found along the way (reported, not fixed)

1. **`seon.config.render/*` keywords have no code namespace** — registered
   inside `src/seon/config.cljs:105-115` and used throughout `config/system.edn`.
   Violates "keyword namespaces = real code namespaces" today; ALSO a precedent
   the reorg could either fix or lean on (if keyword-ns may decouple from
   code-ns, persisted-attr rename costs vanish — owner call).
2. **`seon.env` exists outside src/** — `env/dev/clj/seon/env.clj` +
   `env/prod/clj/seon/env.clj` (required by `seon.core`); invisible to any
   src/-scoped reorg unless included.
3. **`seon.extra`** — referenced by bin/seon, bin/acme, bin/test-cljs
   (SEON_EXTRA_SRC downstream override ns); not in src/ by design, but its
   NAME is load-bearing in three scripts.
4. **`seon.health.metrics`** is consumer-domain (BMI) code inside src/ —
   direct violation of the "no consumer-product code in src/" hard rule.
5. Stale docstrings as evidence rot: `seon.agent.helpers` claims "SQL
   helpers"; `seon.test.runner` and `seon.error.instrument` still carry
   "Phase 2 / Phase A item 8" work-log framing that renders into agent
   context.
