---
type: prd
status: completed
tags: [prd, archive, agent]
---

# Fresh Prompt: Agent Isolation - Phase 4 Completion

You are the ORCHESTRATOR. Your job is coordination, not implementation. Delegate ~90% of work to agents via the Task tool.

## Project Context

Seon is building an agent isolation architecture. Read these docs:

1. `docs/prds/agent-isolation/prd.md` - Full architecture and Phase 4 spec
2. `CONVENTIONS.md` - API design patterns (map in, map out, Malli schemas)

## What's Done

- **Phase 1**: `seon.db.multi` - XTDB multi-database
- **Phase 2**: `seon.orchestrator.nrepl` - Multi-server nREPL with *ctx* injection
- **Phase 3b**: `seon.agent.ctx` - Persisted context atom with validation
- **Phase 4 (partial)**: `seon.orchestrator.session` - Session API exists but has bugs

## Current Issue

The session tests are failing. The implementation has a bug where it uses XTQL syntax (`:put-docs`) instead of SQL for storing sessions in XTDB:

**Bug in** `src/seon/orchestrator/session.clj`:
- Line ~185 was using `:put-docs` which is invalid
- Partially fixed to use SQL INSERT but tests still fail (port conflicts, possibly other issues)

## Recent Changes (from another session)

New SQL helpers added in `seon.agent.helpers`:

```clojure
(sql "SELECT * FROM signals")           ; query
(sql! "INSERT INTO signals ..." ...)    ; single write
(sql-batch! "INSERT ..." [...] [...])   ; batch insert

```

Breaking changes to be aware of:
- All ctx keys must be namespaced (`:seon.trading/signals` not `:signals`)
- `:seon.agent/render-fn` renamed to `:seon.agent/render`
- Level 3 SQL should use helpers, not raw XTDB API

## What Needs to Happen

1. **Fix `seon.orchestrator.session` tests** - Currently failing with port conflicts and SQL issues
2. **Verify `bin/agent-eval` script works** - Should map session-id → port and eval code
3. **Manual test the full flow**:

   ```clojure
   (def s (session/start-agent-session! {::session/node (xtdb-node) ::session/namespace 'test.agent}))
   ;; Then from CLI: bin/agent-eval <session-id> '(+ 1 1)'

   ```
4. **Commit Phase 4** when tests pass

## Branch

`feature/agent-isolation`

## Key Files

| File | Purpose |
|------|---------|
| `src/seon/orchestrator/session.clj` | Session lifecycle API (needs fixes) |
| `src/seon/orchestrator/nrepl.clj` | Per-namespace nREPL servers |
| `src/seon/agent/ctx.clj` | Persisted context atom |
| `src/seon/agent/helpers.clj` | SQL helpers (NEW) |
| `bin/agent-eval` | CLI tool for agents |
| `test/seon/orchestrator/session_test.clj` | Tests (currently failing) |

## Run Tests

```bash
clj -M:test -m kaocha.runner --focus seon.orchestrator.session-test

```

## Critical Working Principles

1. **Use gemini search when stuck** - `clj-nrepl-eval -p 7888 "(search \"query\")"`
2. **REPL-driven development** - Server runs on port 7888, use `clj-nrepl-eval`
3. **SQL not XTQL** - We use SQL syntax for all XTDB operations
4. **Follow CONVENTIONS.md** - Public functions take single map with namespaced keys
