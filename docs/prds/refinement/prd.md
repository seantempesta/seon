# PRD: Refinement — One System, End to End

**Status:** In Progress (Tracks 0-3 mostly complete, Track 4 + ctx unification remaining)
**Priority:** High
**Branch:** `feature/refinement` — 15 commits ahead of main
**Last updated:** 2026-02-20

---

## Vision

An agent uses the Super REPL (via MCP) to execute code in a remote Clojure process. The agent calls functions like normal Clojure — cross-namespace calls that need `*ctx*` or datalevin conn are transparently routed through flow channels. The session is visible in Observatory. A browser request to `/ns/seon.example` renders via the same pipeline.

**Success = we can do this demo and watch the full trace in logs + Observatory.**

---

## What's Been Done

### Track 0: Fix MCP REPL — DONE
- Self-healing nREPL session re-clone in `bin/mcp-server`
- MCP eval works: `(+ 1 2)` → `3`, `(user/status)` returns system info

### Track 1: Remove XTDB — DONE
- Trading archived to `docs/archive/trading/`
- XTDB deps removed from `deps.edn`
- XTDB components removed from `system.clj` and `system.edn`
- `::ai/node` removed from entire AI/agent API
- XTDB db files deleted (`db/node.clj`, `db/multi.clj`, `db/queries.clj`, `db/transactions.clj`, `db/factory.clj`)
- `reference-code/xtdb*` removed (other submodules kept)
- `experimental/context_injection.clj` deleted
- `:xt/id` renamed to `:seon/id`, `::xtdb-id` to `::entity-id`
- Orphaned test files deleted
- Datalevin skill created (`.claude/skills/datalevin/SKILL.md`), xtdb-queries skill deleted
- CLAUDE.md and AGENT.md updated: all XTDB refs → Datalevin
- **5 files have intentional XTDB mentions** (accurate comments like "XTDB has been removed")
- **483 tests, 2417 assertions, 0 failures** (down from 712 — deleted tests for removed code)

### Track 2: Unified Agent Runtime — ~75% DONE
**Done:**
- Pool layer: `claim!`, `release-session!`, `get-jvm-by-session`, `::session->port` tracking (`src/seon/flow/pool.clj`)
- `orchestrator/nrepl.clj` DELETED (536 lines) + tests
- System wiring: `:seon/orchestrator-sessions` depends on `:seon/agent-pool`
- Session tests simplified for pool-based model

**Not done (see Track 5 below):**
- `session.clj` still imports `seon.agent.ctx` — needs ctx unification first
- `health.clj` has dead dynamic requires of deleted nrepl (gracefully degraded)
- No e2e verification (MCP eval → pool JVM → `@*ctx*` → Observatory)
- Auto-proxy injection not started

### Track 3: Flow Logging + Tracing — ~90% DONE
**Done:**
- `src/seon/flow/trace.clj` — event persistence to Datalevin, `events-for-session` query
- Structured logging in `bridge.clj`, `harness.clj`, `proxy.clj`
- Observatory flow event timeline in agent detail view (`web/agents.clj`)
- `test/seon/flow/trace_test.clj` — 5 tests (require running system)

**Not done:**
- E2e verification (launch agent, verify full trace visible)

---

## What's Next

### Track 5: Unify Context Systems (NEW — prerequisite for Track 2 completion)

**Problem:** Two ctx systems exist and they're architecturally different:

| System | File | What it does |
|--------|------|-------------|
| `seon.agent.ctx` | `src/seon/agent/ctx.clj` | Validated persisted atoms with Malli schema enforcement, reserved key protection (`::namespace`, `::session-id` can't be overwritten), snapshot history, Datalevin persistence |
| `seon.ctx` | `src/seon/ctx.clj` | Simpler instance registry — `create!`/`get`/`destroy!` lifecycle, client tracking, less validation |

They are NOT drop-in replacements. The agent that attempted the swap correctly identified this and stopped.

**Goal:** ONE ctx system that has:
- Validated persisted atoms (from `agent.ctx`)
- Instance registry lifecycle (from `seon.ctx`)
- Client tracking (from `seon.ctx`)
- Reserved key protection (from `agent.ctx`)

**Approach:**
1. Research both files — understand every public fn and who calls it
2. Design merged API
3. Implement in `seon.ctx` (the keeper)
4. Migrate all callers from `agent.ctx` → `seon.ctx`
5. Delete `seon.agent.ctx`
6. Run tests

**Key files:**
- `src/seon/ctx.clj` (keep, extend)
- `src/seon/agent/ctx.clj` (read, then delete)
- `src/seon/orchestrator/session.clj` (primary caller of agent.ctx)
- `src/seon/flow/agent_runner.clj` (may import agent.ctx)

### Track 4: Render Pipeline E2E + Code Scanner

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

**Key files:** `src/seon/graph/scanner.clj`, `src/seon/render.clj`, `src/seon/ns/routes.clj`, `src/seon/system.clj`

### Track 6: E2E Verification + Health Cleanup

After Tracks 4 and 5:
1. Restart server, verify all components start
2. `user/launch-agent!!` works — agent gets pool JVM, MCP eval works, `@*ctx*` returns session context
3. Observatory shows agent with flow event timeline
4. `/ns/seon.health.workout` renders in browser
5. Clean dead requires in `health.clj`
6. Full test suite: 0 failures, 0 errors

---

## Key Lessons Learned

1. **Agents run out of context on large tasks.** Max ~7 files per agent. Prefer small complete tasks over large half-done ones.
2. **Agents can't always run bash.** File deletion/git operations sometimes need orchestrator help.
3. **Honesty > completion.** Agents should terminate early and explain how to split work rather than leaving hidden breakage. (Added to CLAUDE.md and AGENT.md.)
4. **The MCP REPL session can go stale** after server restart. `bin/mcp-server` now self-heals by re-cloning the nREPL session.
5. **Kill JVMs with `pkill -9 -f "java.*seon"`** not `pkill -f "clojure.*seon"`.
6. **`seon.agent.ctx` and `seon.ctx` are not drop-in replacements.** agent.ctx has validation + persistence that ctx doesn't. Need proper merge.
7. **Use `user/search` with `:files`** when debugging — Gemini can't help without seeing the actual code.

---

## Key Files Reference

| File | Purpose |
|------|---------|
| `src/seon/flow/pool.clj` | Pool JVM lifecycle, `claim!`/`release-session!` |
| `src/seon/flow/trace.clj` | Flow event persistence + querying |
| `src/seon/flow/harness.clj` | Flow channel management, request forwarding |
| `src/seon/flow/harness/bridge.clj` | TCP bridge for cross-JVM fn calls |
| `src/seon/flow/harness/proxy.clj` | Transparent proxy for cross-ns routing |
| `src/seon/orchestrator/session.clj` | Agent session lifecycle (uses pool) |
| `src/seon/ctx.clj` | Context system (to be extended) |
| `src/seon/agent/ctx.clj` | Old ctx system (to be merged into ctx.clj then deleted) |
| `src/seon/render.clj` | `find-renderer` + Datalevin resolution cache |
| `src/seon/graph/scanner.clj` | Code scanner for graph DB |
| `src/seon/ns/routes.clj` | Namespace browser routes |
| `src/seon/web/agents.clj` | Observatory UI (agent detail + flow events) |
| `src/seon/ai/datalevin.clj` | AI session/message persistence |
| `bin/mcp-server` | MCP server (babashka) — Super REPL entry point |
| `docs/prds/refinement/plan.md` | Original master plan |
| `docs/prds/refinement/plan-unified-runtime.md` | Detailed pool/session unification plan |
| `docs/prds/refinement/notes.md` | Agent findings and gotchas |
| `.claude/skills/datalevin/SKILL.md` | Datalevin skill for agents |

---

## Agent Instructions

**All agents MUST:**
1. Use `user/search` with `:files` when hitting resistance or unsure how something works
2. Test changes via the MCP REPL (`mcp__seon__eval` session_id="orchestrator")
3. Run focused tests first, full suite before finishing — report exact counts
4. **Document findings** in `docs/prds/refinement/notes.md`
5. Commit working code with descriptive messages
6. **Be honest** — it's far worse to hide remaining work than to report incomplete results
7. **Terminate early** if the task is too large — explain how to split it

---

## Coordination

- **Track 5 (ctx unification) should run first** — Track 2 completion depends on it
- **Track 4 (render E2E) can run in parallel** with Track 5 — independent concern
- **Track 6 (e2e verification) runs last** — depends on everything else
- Each agent edits max ~7 files
- Orchestrator reviews commits, resolves conflicts, runs integration

---

## Success Criteria

1. `grep -ri "xtdb" src/` → only intentional comments
2. Only ONE ctx system: `seon.ctx` — no `seon.agent.ctx` in active code
3. Launch agent via MCP → pool JVM claimed → `@*ctx*` returns session context → visible in Observatory
4. Cross-ns call routes through flow → visible in trace logs
5. `/ns/seon.health.workout` renders in browser via render pipeline
6. Full test suite: 0 failures, 0 errors
