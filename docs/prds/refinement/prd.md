# PRD: Refinement — One System, End to End

**Status:** In Progress
**Priority:** High
**Branch:** `feature/refinement`

---

## Vision

An agent uses the Super REPL (via MCP) to execute code in a remote Clojure process. The agent calls functions like normal Clojure — cross-namespace calls that need `*ctx*` or datalevin conn are transparently routed through flow channels. The session is visible in Observatory. A browser request to `/ns/seon.example` renders via the same pipeline.

---

## Current Problems

1. **XTDB still starts** — dead weight, trading is last holdout
2. **Three ctx systems** — `seon.ctx` (new), `seon.agent.ctx` (old/XTDB), `seon.orchestrator.nrepl` (middleware) — should be one
3. **Proxy injection is manual** — `proxy-ns!` works but requires explicit calls; agents should get transparent cross-ns routing automatically
4. **No flow-level logging** — `bridge.clj` and `harness.clj` have zero logging; can't trace requests through flow
5. **Code scanner doesn't detect ctx/conn needs** — can't auto-determine which fns need flow sessions
6. **Render pipeline untested e2e** — scanner → find-renderer → (flow if stateful) → SSE not verified

---

## Track 0: Fix MCP REPL (Prerequisite) -- DONE

**Goal:** Orchestrator MCP REPL (`mcp__seon__eval`) works reliably. Agents can use it for live REPL-driven development and testing.

**Status:** Complete (commit `e07a866`). Self-healing session logic added to `bin/mcp-server`.

1. ~~Diagnose why MCP eval returns nil~~ -- done
2. ~~Fix the issue~~ -- done (self-healing nREPL session re-clone)
3. ~~Verify: `(+ 1 2)` → `3`, `(user/status)` returns system info~~ -- done
4. ~~Verify agent sessions can be created via `mcp__seon__create_session`~~ -- done

---

## Track 1: Remove XTDB + `reference-code/` -- ~70% DONE (uncommitted)

**Goal:** Zero XTDB in the codebase. Trading archived. reference-code removed.
**Status:** AI/agent/orchestrator layer cleaned (commits `7572f29`, `9e69b14`). `deps.edn` and `system.edn` cleanup uncommitted. 12 files still reference XTDB (mostly `ai/datalevin.clj` legacy naming and `web/` files using `:xt/id`). Trading not archived. `reference-code/` not removed.

1. Move `src/seon/trading/` → `docs/archive/trading/`
2. Remove from `system.clj`: `:seon/xtdb-node`, `:seon/namespace-dbs`, XTDB dep from http-server
3. Remove `::ai/node` from entire AI/agent API (`ai.clj`, `claude.clj`, `agent.clj`, `session.clj`, `user.clj`, `agents.clj`)
4. Archive/delete XTDB-only files: `db/node.clj`, `db/multi.clj`, `db/queries.clj`, `db/transactions.clj`
5. Clean remaining refs: `health.clj`, `core.clj`, `ctx.clj`, `agent/ctx.clj`, `agent/helpers.clj`, `db/factory.clj`, `ai/datalevin.clj`, `ai/gemini.clj`
6. Remove `reference-code/` dir + `.gitmodules`
7. Remove XTDB deps from `deps.edn`
8. Delete `src/seon/experimental/context_injection.clj` (dead research code)
9. Fix tests, run full suite

**Key files:** `src/seon/system.clj`, `deps.edn`, `.gitmodules`

---

## Track 2: Unified Agent Runtime — Session-ID as Universal Key -- ~50% DONE (uncommitted)

**Goal:** ONE model for all agents (AI, web, human REPL). Session = session-id + pool JVM + flow channels + ctx. Pool JVMs are the single runtime.
**Status:** Pool layer has `claim!`/`release-session!`/`get-jvm-by-session` (uncommitted). Session layer partially migrated. `orchestrator/nrepl.clj` NOT deleted yet. `seon.agent.ctx` still imported. No e2e verification.

**The model:** `start-session!(namespace, opts)` →
1. Generate session-id (4-char hex)
2. Claim runtime from pool (anonymous JVM gets session-id)
3. Setup: create namespace, inject `*ctx*`, configure Datalevin
4. Wire flow: start TCP bridge, register in topology
5. Auto-proxy: analyze namespace deps, create proxies for cross-ns calls
6. Register: session-id → {port, namespace, flow-channels, status} in master DB
7. Return {session-id, nrepl-port}

Drivers (Claude, human, automation) just need session-id to interact.

**Phase 1 (this PR):**
1. Pool: add `claim!` (assigns session-id to idle JVM, injects `*ctx*`, returns handle)
2. Pool: add `get-jvm-by-session`, `release!` (clears session-id, returns to idle)
3. Session: `start-agent-session!` delegates to `pool/claim!` instead of `nrepl/start-namespace-nrepl!`
4. Session: `stop-agent-session!` delegates to `pool/release!`
5. System wiring: `:seon/orchestrator-sessions` depends on `:seon/agent-pool`
6. DELETE `orchestrator/nrepl.clj` — pool replaces everything it does
7. Verify: MCP eval, `user/launch-agent!!`, `@*ctx*` in agent REPL, Observatory

**Future phases:** auto-proxy at claim time, DB naming `agent-{session-id}`, runtime abstraction (cljs pool same interface).

**Key files:** `src/seon/flow/pool.clj`, `src/seon/orchestrator/session.clj`, `src/seon/orchestrator/nrepl.clj` (delete), `src/seon/ctx.clj`, `src/seon/system.clj`

**Detailed plan:** `docs/prds/refinement/plan-unified-runtime.md`

---

## Track 3: Flow Logging + Tracing -- ~90% DONE (uncommitted)

**Goal:** Full trace visibility from browser/MCP request → flow → agent JVM → response.
**Status:** `trace.clj` created with Datalevin persistence. Logging added to harness, bridge, and proxy (all uncommitted). No Observatory UI integration. No e2e test.

1. Add structured logging to `bridge.clj` (fn resolution, execution start/end, errors, timeouts)
2. Add structured logging to `harness.clj` (request forwarding, reply reception, overload events)
3. Add logging to `proxy.clj` (proxy call initiation, response received)
4. Persist flow events to Datalevin -- DONE (trace.clj)
5. Surface flow events in Observatory agent detail view -- DONE (agents.clj render-flow-events)
6. Tests for trace.clj -- DONE (trace_test.clj, requires running system)
7. Test: launch agent, verify full trace visible in logs

**Key files:** `src/seon/flow/harness/bridge.clj`, `src/seon/flow/harness.clj`, `src/seon/flow/harness/proxy.clj`, `src/seon/web/agents.clj`

---

## Track 4: Render Pipeline E2E + Code Scanner (after Tracks 1-3)

**Goal:** `/ns/seon.example` works — scanner finds renderer, flow session spins up if needed, SSE delivers.

1. Verify code scanner runs at startup and populates graph DB
2. Extend scanner to detect ctx/conn needs (check fn arglists for `ctx`, `conn`, `db` params)
3. Verify `find-renderer` resolves correctly from Datalevin
4. Wire stateful render path in `seon.ns.routes`
5. Test with `seon.health.workout.render` (existing proof of life)
6. Test with browser navigation
7. Full test suite, report count

**Key files:** `src/seon/graph/scanner.clj`, `src/seon/render.clj`, `src/seon/ns/routes.clj`, `src/seon/system.clj`

---

## Agent Instructions

**All agents MUST:**
1. Use `user/search` with `:files` when hitting resistance or unsure how something works
2. Test changes via the MCP REPL (live, not just unit tests)
3. Run the full test suite before finishing — fix failures, report final counts
4. **Document findings** in `docs/prds/refinement/notes.md` — architecture decisions, gotchas, things that surprised you. You have domain expertise after doing the work; don't let it evaporate.
5. Commit working code with descriptive messages to `feature/refinement`

---

## Coordination

- **Track 0 runs first** — all other tracks depend on a working REPL
- **Tracks 1, 2, 3 run in parallel** — independent concerns
- **Track 4 runs after** — depends on XTDB removal and logging being in place
- Orchestrator reviews commits, resolves conflicts, runs integration test

---

## Success Criteria

1. `grep -ri xtdb src/` → nothing (only `docs/archive/`)
2. `ls reference-code/` → doesn't exist
3. Only ONE ctx system: `seon.ctx` — no `seon.agent.ctx` in active code
4. Launch agent via MCP Super REPL → cross-ns call routes through flow → visible in logs
5. Observatory shows session with flow event timeline
6. `/ns/seon.health.workout` renders in browser
7. Full test suite: 0 failures
