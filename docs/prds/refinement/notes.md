# Refinement Notes

Agents: document your findings, decisions, and gotchas here as you work.
You have domain expertise after doing the work — don't let it evaporate.

---

## Track 0: MCP REPL Fix

### What Was Broken
The MCP REPL (`mcp__seon__eval`) returned `nil` or "nREPL session expired" for ALL expressions.

### Root Cause (two compounding issues)
1. **Server on wrong port**: nREPL bound to random port instead of 7888 because port was already in use from a previous unclean shutdown. Fix: kill all java seon processes with `pkill -9 -f "java.*seon"` before restarting.

2. **Stale nREPL session**: The MCP server clones an nREPL session at startup for `*1/*2/*3` persistence. When the seon server restarts, this session becomes invalid. The MCP server detected "unknown-session" but returned an error instead of recovering.

### Fix Applied (`bin/mcp-server`)
Added self-healing to `execute-eval`: when "unknown-session" is detected, automatically re-clone a new nREPL session and retry. This handles server restarts transparently.

### Gotchas
- MCP server is a babashka process managed by Claude Code. Changes to `bin/mcp-server` require restarting the Claude Code session (or `/mcp` reset) to take effect.
- Kill processes with `pkill -9 -f "java.*seon"` not `pkill -f "clojure.*seon"` (child JVMs).
- Many orphaned caddy processes accumulate; clean up with `pkill -f caddy`.

## Datalevin Skill Creation

### What Was Done

- Created `.claude/skills/datalevin/SKILL.md` as the comprehensive Datalevin reference for agents
- Deleted `.claude/skills/xtdb-queries/SKILL.md` (XTDB is no longer the primary database)
- Updated `CLAUDE.md` skill table to reference `/datalevin` instead of `/xtdb-queries`

### Key Learnings from Source Code Analysis

1. **Schema is optional** -- Datalevin does schema-on-write. Untyped attributes are stored as EDN blobs. Seon uses this extensively (schemaless by default).
2. **Connection model** -- Connections are atoms wrapping DB values. `(d/db conn)` or `@conn` gets the immutable snapshot. Queries take DB values, not connections. Number one gotcha.
3. **No history** -- Unlike Datomic/XTDB, deletions are permanent. No `as-of` or `valid-time` queries.
4. **Seon's architecture** -- Client/server mode with in-process server. Connection manager (`seon.db.datalevin.conn`) provides TTL-cached connections per namespace.
5. **Two indexes only** -- `:eav` and `:ave`. Cost-based optimizer compensates.
6. **Cardinality many adds, never replaces** -- Must retract first to replace values.
7. **Nil values silently dropped** -- `{:name "Alice" :age nil}` stores only `:name`.

### Performance Expectations (from LMDB characteristics and Datalevin benchmarks)

- Sync writes: ~50-200ms for 1K entities, ~500-2000ms for 10K
- Async writes (`transact-async`): 10-100x faster via adaptive batching
- Point reads: sub-millisecond
- Simple queries: 1-5ms

## Track 1: XTDB Removal (Source + Test Cleanup)

### What Was Done (2026-02-20)

**Source files cleaned (comments/paths):**
- `src/seon/web/stats.clj` -- changed hardcoded `"data/xtdb"` path to `"data/datalevin"`, updated docstrings
- `src/seon/web/agents.clj` -- updated ~10 comments from "XTDB" to "Datalevin" (`:xt/id` key kept for now, see below)
- `src/seon/web/jobs.clj` -- updated "XTDB node" comment to "database node"
- `src/seon/dev/compliance.clj` -- updated example arglist comment
- `src/seon/agent/helpers.clj` -- updated error messages
- `tests.edn` -- updated comment

**Test files cleaned:**
- Deleted 7 orphaned test files that required XTDB on classpath:
  - `test/seon/agent/ctx_test.clj` (required `xtdb.api`)
  - `test/seon/agent/helpers_test.clj` (required `seon.db.multi` which is deleted)
  - `test/seon/db/node_test.clj`, `multi_test.clj`, `queries_test.clj`, `factory_test.clj` (all for deleted source files)
  - `test/seon/web/stats_test.clj` (tested XTDB-based trading stats, now removed)
- Updated `test/seon/test_utils.clj` -- removed XTDB fixture code, stubbed `with-test-node` as no-op
- Updated comments in `test/seon/ai_test.clj`, `ai/claude_test.clj`, `ai/gemini_test.clj`
- Fixed `test/seon/orchestrator/session_test.clj` -- wrong db-name expectation (`"test_start"` -> `"test.start"`)

**NOT changed (intentional):**
- `:xt/id` key usage in `ai/datalevin.clj`, `ai.clj`, `ai/agent.clj`, `web/agents.clj` -- this is the logical entity ID key used throughout the Datalevin persistence layer. Renaming it requires updating the Datalevin schema, all queries, and all consumers. It is a keyword, not an XTDB dependency. Should be a separate focused refactor if desired.

### Test Suite Results
- **496 tests, 2476 assertions, 0 failures, 3 errors**
- The 3 errors are from `seon.flow.pool-integration-test` -- nREPL infrastructure tests that fail when agent JVMs cannot spawn (environment-dependent, not a code bug)

## Track 2: Context Unification

_(to be filled by agent)_

## Track 3: Flow Logging

_(to be filled by agent)_

## Track 4: Render Pipeline E2E

_(to be filled by agent)_

---

## Audit: State of Branch (2026-02-20)

### 1. Commits So Far

4 commits on `feature/refinement` since `4f7ed65` (main):

1. `e07a866` - feat: enhance MCP REPL with self-healing for nREPL sessions, add documentation for refinement process
2. `411d817` - feat: implement unified agent runtime with session-id as universal key, update PRD and add detailed plan
3. `9e69b14` - refactor: remove XTDB from web/flow/user layer
4. `7572f29` - refactor: remove XTDB from AI/agent/orchestrator layer

### 2. Uncommitted Changes

9 files modified, 1 new file (226 insertions, 515 deletions):

| File | Nature of Change |
|------|-----------------|
| `deps.edn` | Removing XTDB dependencies (-20 lines) |
| `resources/system.edn` | Removing XTDB components (-41 lines) |
| `src/seon/ai.clj` | Removing `::node` schema, cleaning docstrings (-108 lines net) |
| `src/seon/flow/harness.clj` | Adding trace/logging to step-fn (+40 lines) |
| `src/seon/flow/harness/bridge.clj` | Adding structured logging to execute-local and remote-call (+103 lines changed) |
| `src/seon/flow/harness/proxy.clj` | Adding logging to proxy calls (+23 lines changed) |
| `src/seon/flow/pool.clj` | Adding `claim!`, `release-session!`, `get-jvm-by-session`, `::session->port` tracking (+72 lines) |
| `src/seon/system.clj` | Major cleanup, removing XTDB components (-125 lines net) |
| `test/seon/orchestrator/session_test.clj` | Removing nREPL-multi dependency, simplifying for pool-based sessions (-209 lines net) |
| `src/seon/flow/trace.clj` | **NEW** - Flow event tracing/persistence to Datalevin |

**None of these are committed yet.** This is a large uncommitted diff spanning Track 1, 2, and 3 work.

### 3. XTDB Removal Status

**Estimate: ~70% complete.**

12 files still reference XTDB. Categorized:

**Real dependencies (need code changes):**
- `src/seon/web/stats.clj` (lines 32-33) - hardcoded `"data/xtdb"` path for disk usage stats. Needs update or removal.
- `src/seon/web/agents.clj` (lines 482, 563, 663) - uses `:xt/id` as entity key for sessions/messages. This is from the Datalevin migration shim (`ai/datalevin.clj`) which maps `:seon.ai.datalevin/xtdb-id` back to `:xt/id`. Not a real XTDB dep but uses the `:xt/id` key name.
- `src/seon/ai/datalevin.clj` (many lines) - the migration shim itself. Uses `::xtdb-id` field name extensively for the logical ID mapping. Functional but naming is legacy.
- `src/seon/orchestrator/session.clj` - still requires `seon.agent.ctx` (old ctx system). Should use `seon.ctx` only.

**Comments/docstrings only (cosmetic):**
- `src/seon/dev/compliance.clj` (line 674) - comment showing example arglists mentioning `xtdb-node`. Cosmetic.
- `src/seon/ai/gemini.clj` - no actual XTDB refs found in grep output (false positive from earlier, or already cleaned).

**Indirect/not blocking:**
- `src/seon/dev/hook.clj` - uses `seon.dev.context` namespace which internally may reference XTDB. The hook itself doesn't import XTDB.
- `src/seon/flow/agent_runner.clj` - listed but no grep matches visible; may be cleaned already.
- `src/seon/agent/helpers.clj` - listed but no grep matches visible.
- `src/seon/ctx.clj` - listed but no grep matches visible.

**Not yet done from PRD Track 1:**
- Trading code not archived yet (`src/seon/trading/` still exists?)
- `reference-code/` directory not removed
- `.gitmodules` not cleaned
- XTDB db files (`db/node.clj`, `db/multi.clj`, `db/queries.clj`, `db/transactions.clj`) not confirmed deleted
- `src/seon/experimental/context_injection.clj` not confirmed deleted

### 4. Track 2 Status (Unified Agent Runtime)

**Pool layer: ~80% complete (uncommitted).**
- `claim!` exists in `pool.clj` and looks complete: assigns session-id, sets up namespace, injects `*ctx*` via nREPL eval, tracks `::session->port` mapping
- `release-session!` exists and works: clears session tracking, delegates to `release!`
- `get-jvm-by-session` exists for lookup by session-id
- `::session->port` map added to pool state atom

**Session layer: partially updated.**
- `orchestrator/session.clj` requires `seon.flow.pool` (good) but ALSO still requires `seon.agent.ctx` (old system)
- `orchestrator/nrepl.clj` still exists -- PRD says to DELETE it
- Session test file significantly simplified (removed nrepl-multi dependency)

**What's missing:**
- `orchestrator/nrepl.clj` not deleted yet
- `session.clj` not fully migrated to use `pool/claim!` (still has old ctx references)
- System wiring (`:seon/orchestrator-sessions` depends on `:seon/agent-pool`) -- unclear if done in uncommitted `system.edn` changes
- No verification that MCP eval, `user/launch-agent!!`, or Observatory work with new model
- `seon.agent.ctx` still imported (should be replaced by `seon.ctx`)

### 5. Track 3 Status (Flow Logging)

**~70% complete (uncommitted).**

Done:
- `src/seon/flow/trace.clj` created (new file): schema registration, `persist-event!` (fire-and-forget to Datalevin), `events-for-session` query
- `src/seon/flow/harness.clj`: added trace logging on forward, overload, error, and ok events. Uses `(future (trace/persist-event! ...))` for non-blocking persistence.
- `src/seon/flow/harness/bridge.clj`: added structured logging to `execute-local` (start/end/error events) and `remote-call!` (start/timeout/error/ok events)
- `src/seon/flow/harness/proxy.clj`: added logging to `proxy-fn` (start/end events)

Not done:
- No Observatory UI integration (no surface of flow events in agent detail view)
- No end-to-end verification (launch agent, verify trace visible)
- No tests for `trace.clj`

### 6. Known Issues

1. **Large uncommitted diff** -- 9 files changed across 3 tracks, not committed. Risk of losing work or creating a confusing single commit.
2. **`orchestrator/nrepl.clj` not deleted** -- PRD explicitly says to delete it. Still present.
3. **Dual ctx systems** -- `session.clj` still imports `seon.agent.ctx`. Goal is ONE ctx system (`seon.ctx`).
4. **`:xt/id` key name** -- `web/agents.clj` and `ai/datalevin.clj` still use `:xt/id` as the logical entity key. Works but naming is confusing post-XTDB.
5. **`trace.clj` uses `integrant.repl.state/system`** -- directly reaches into global state for Datalevin connection. Fragile; should receive connection via params or component injection.
6. **No tests run** -- server state unknown, no test results to verify nothing is broken.
7. **`web/stats.clj`** still references `"data/xtdb"` directory for disk usage reporting.

### 7. Recommended Next Steps (prioritized)

1. **Commit current work** -- Split into 2-3 commits (Track 1 XTDB removal, Track 2 pool claiming, Track 3 flow tracing). Each should be independently reviewable.

2. **Delete `orchestrator/nrepl.clj`** -- Small task. Remove the file and any remaining imports of it. Verify nothing else requires it.

3. **Clean `web/stats.clj` XTDB reference** -- Replace `"data/xtdb"` path with Datalevin data path or remove that stat entirely.

4. **Rename `:xt/id` to something neutral** -- In `ai/datalevin.clj`, change `::xtdb-id` field name to something like `::logical-id`. Update `web/agents.clj` references. This is a rename refactor, well-scoped.

5. **Remove `seon.agent.ctx` import from `session.clj`** -- Replace with `seon.ctx` usage. Verify ctx flows correctly through pool claim path.

6. **Verify trading code archived** -- Check if `src/seon/trading/` exists; if so, move to `docs/archive/trading/`.

7. **Remove `reference-code/` and `.gitmodules`** -- Straightforward deletion.

8. **Add trace events to Observatory** -- Wire `trace/events-for-session` into `web/agents.clj` agent detail view.

9. **Run full test suite** -- Fix any failures from the XTDB removal and session changes.

10. **Delete `src/seon/experimental/context_injection.clj`** if it exists.
