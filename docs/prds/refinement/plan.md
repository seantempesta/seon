---
type: prd
status: draft
tags: [prd, database]
---
# Plan: Refinement — One System, End to End

## Goal

**An agent uses the Super REPL (via MCP) to execute code in a remote Clojure process. The agent calls functions like normal Clojure — cross-namespace calls that need `*ctx*` or datalevin conn are transparently routed through flow channels. The session is visible in Observatory. A browser request to `/ns/seon.example` renders via the same pipeline.**

Success = we can do this demo and watch the full trace in logs + Observatory.

---

## Current Problems

1. **XTDB still starts** — dead weight, trading is last holdout
2. **Three ctx systems** — `seon.ctx` (new), `seon.agent.ctx` (old/XTDB), `seon.orchestrator.nrepl` (middleware) — should be one
3. **Proxy injection is manual** — `proxy-ns!` works but requires explicit calls; agents should get transparent cross-ns routing automatically
4. **No flow-level logging** — `bridge.clj` and `harness.clj` have zero logging; can't trace requests through flow
5. **Code scanner doesn't detect ctx/conn needs** — can't auto-determine which fns need flow sessions
6. **Render pipeline untested e2e** — scanner → find-renderer → (flow if stateful) → SSE not verified

---

## Tracks (Max Parallel — 3-4 Agents)

### Track 1: Remove XTDB + `reference-code/` (Agent 1)

**Goal:** Zero XTDB in the codebase. Trading archived. reference-code removed.

1. Move `src/seon/trading/` → `docs/archive/trading/`
2. Remove from `system.clj`: `:seon/xtdb-node`, `:seon/namespace-dbs`, XTDB dep from http-server
3. Remove `::ai/node` from entire AI/agent API (per PRD checklist — `ai.clj`, `claude.clj`, `agent.clj`, `session.clj`, `user.clj`, `agents.clj`)
4. Archive/delete XTDB-only files: `db/node.clj`, `db/multi.clj`, `db/queries.clj`, `db/transactions.clj`
5. Clean remaining refs: `health.clj`, `core.clj`, `ctx.clj`, `agent/ctx.clj`, `agent/helpers.clj`, `db/factory.clj`, `ai/datalevin.clj`, `ai/gemini.clj`
6. Remove `reference-code/` dir + `.gitmodules`
7. Remove XTDB deps from `deps.edn`
8. Delete `src/seon/experimental/context_injection.clj` (dead research code)
9. Fix tests, run full suite

**Key files:** `src/seon/system.clj`, `deps.edn`, `.gitmodules`, all files in PRD Phase 1

### Track 2: Unify Context + Auto-Proxy via Super REPL (Agent 2)

**Goal:** One ctx system. Super REPL automatically rewrites cross-ns calls to route through flow.

**Unify ctx (merge 3 → 1):**

1. Port validation logic (namespaced keys, Malli schemas, reserved key protection) from `seon.agent.ctx` into `seon.ctx`
2. Update `seon.orchestrator.session` to use `seon.ctx/create!` instead of `agent.ctx/make-persisted-ctx`
3. Keep nREPL middleware (`seon.orchestrator.nrepl`) — it just injects the unified atom as `*ctx*`
4. Archive `seon.agent.ctx` (all logic moved to `seon.ctx`)
5. Update `seon.flow.agent_runner` to use `seon.ctx`

**Auto-proxy injection (build `seon.flow.harness/inject.clj`):**

- The Super REPL is the rewrite point — when code is sent to a remote JVM via MCP eval, the Super REPL should:
  1. Analyze the namespace's `require` forms to find cross-ns deps
  2. For each seon.* dependency, check if it needs a flow session (via graph DB metadata or arglists)
  3. Call existing `proxy-ns!` to create transparent proxy
  4. Agent code calls functions normally — proxy routes through flow channels
- For fns needing `*ctx*` or datalevin conn: the flow relay on orchestrator side lazy-starts a flow session, injects those args before executing

**Key files:**

- `src/seon/ctx.clj` (extend with validation from agent.ctx)
- `src/seon/agent/ctx.clj` (archive after merge)
- `src/seon/orchestrator/session.clj` (switch to unified ctx)
- `src/seon/flow/harness/proxy.clj` (existing — read)
- `src/seon/flow/harness/bridge.clj` (existing — read)
- `src/seon/flow/harness.clj` (add auto-inject step)
- `bin/mcp-server` or equivalent (Super REPL entry point)

### Track 3: Flow Logging + Tracing (Agent 3)

**Goal:** Full trace visibility from browser/MCP request → flow → agent JVM → response.

1. Add structured logging to `bridge.clj`:
   - Function resolution, execution start/end, errors, timeouts
   - Use `::msg/trace-id` for correlation
2. Add structured logging to `harness.clj`:
   - Request forwarding, reply reception, overload events
3. Add logging to `proxy.clj`:
   - Proxy call initiation, response received
4. Persist flow events to Datalevin (events already emitted as `:overload`/`:error`/`:ok`)
5. Surface flow events in Observatory agent detail view
6. Test: launch agent, verify full trace visible in logs

**Key files:**

- `src/seon/flow/harness/bridge.clj` (add logging)
- `src/seon/flow/harness.clj` (add logging)
- `src/seon/flow/harness/proxy.clj` (add logging)
- `src/seon/web/agents.clj` (show flow events)

### Track 4: Render Pipeline E2E + Code Scanner (Agent 4, after Tracks 1-3)

**Goal:** `/ns/seon.example` works — scanner finds renderer, flow session spins up if needed, SSE delivers.

1. Verify code scanner runs at startup and populates graph DB
2. Extend scanner to detect ctx/conn needs (check fn arglists for `ctx`, `conn`, `db` params)
3. Verify `find-renderer` resolves correctly from Datalevin
4. Wire stateful render path in `seon.ns.routes`:
   - If render fn needs state → lazy-start flow session → send request via flow → SSE response
   - If static → call directly
5. Test with `seon.health.workout.render` (existing proof of life)
6. Test with browser navigation
7. Full test suite, report count

**Key files:**

- `src/seon/graph/scanner.clj` (extend — ctx/conn detection)
- `src/seon/render.clj` (verify find-renderer)
- `src/seon/ns/routes.clj` (wire flow for stateful renders)
- `src/seon/system.clj` (verify scanner init)

---

## Agent Coordination

- **Tracks 1, 2, 3 run in parallel** — independent concerns
- **Track 4 runs after** — depends on XTDB removal and logging being in place
- Each agent commits to the `feature/refinement` branch with descriptive messages
- Agents update the PRD (`docs/prds/refinement/prd.md`) with findings and decisions
- Orchestrator (me) reviews commits, resolves conflicts, runs integration test
- Final integration test after all tracks complete

## PRD Updates

Before launching agents, update `docs/prds/refinement/prd.md` to include:

- The goal statement from this plan
- Tracks 2-4 (currently only has Phase 1/XTDB removal)
- Success criteria for each track
- The three ctx systems problem + resolution

## Verification

1. `grep -ri xtdb src/` → nothing (only `docs/archive/`)
2. `ls reference-code/` → doesn't exist
3. Only ONE ctx system: `seon.ctx` — no `seon.agent.ctx` in active code
4. Launch agent via MCP Super REPL → cross-ns call routes through flow → visible in logs
5. Observatory shows session with flow event timeline
6. `/ns/seon.health.workout` renders in browser
7. Full test suite: 0 failures
