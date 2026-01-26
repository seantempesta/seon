# Seon - Claude Code Instructions

## What is Seon?

**Seon** - from the archaic "to see", and inspired by the Seons of Brandon Sanderson's *Elantris*: sentient, luminous beings that serve and assist their bonded humans.

Seon is **infrastructure for AI agents to write reliable software**.

The personal domains (trading, health, finance) are test cases, not the point. The real product is a codebase architecture where AI agents can own and evolve code responsibly - with contracts they can discover, history they can learn from, and isolation that prevents conflicts.

### The Problem with AI-Assisted Development Today

Current approaches bolt AI onto codebases designed for humans:
- No contracts → agents hallucinate interfaces
- No history → agents can't learn from what worked
- No isolation → agents step on each other
- No verification → bad code ships

### Seon's Answer

Build a codebase from the ground up optimized for agent ownership:

- **Schema-first** - Every function has Malli schemas. Agents know the shape before writing code. Property tests validate contracts automatically.
- **Namespaced keys everywhere** - Fully qualified keys (`:seon.trading/position`) are queryable. "What functions accept this input shape?" is a database lookup, not a hallucination.
- **Temporal database** - XTDB stores full history. Agents can query "this function used to return X, now returns Y, what changed?"
- **Namespace isolation** - One agent owns `seon.trading.signals`, another owns `seon.trading.execution`. They communicate through schemas, not shared state.
- **Dev hooks** - Every edit triggers tests + AI review. Bad changes are blocked before they land.

### Why Clojure?

Not despite the small community - because of the language properties:

- **Stable APIs** - 10-year-old documentation is still valid. Agents don't need to track API churn.
- **Data as interface** - Maps in, maps out. No hidden object state to reason about.
- **Homoiconicity** - Code is data. Agents can manipulate programs as data structures.
- **REPL-driven** - Interactive development matches how agents work (try something, see result, iterate).
- **Immutable by default** - No spooky action at a distance. Function outputs depend only on inputs.

### The Vision

Agents own namespaces long-term. They see:
- Live system health and status
- Other agents' work and outputs
- Function signatures with examples and documentation
- Usage history (who called what, with what, when)
- Test results and coverage

Over time, agents learn from this data. They evolve their code based on actual usage patterns. The system grows more reliable as agents accumulate experience.

**Success looks like:** A non-technical person gives the system a real problem. From scratch, agents build it to spec. It's useful. It responds to feedback. It grows with the user over months and years.

### Core Infrastructure

- **XTDB** - Bitemporal database. Every fact has valid-time and transaction-time. Query any point in history.
- **Malli** - Schema validation, generative testing, function contracts. The type system agents actually use.
- **Integrant** - Component lifecycle. Clean start/stop semantics for the whole system.
- **Datastar/SSE** - Real-time UI updates. Agents can see their work reflected immediately.

### What's Built So Far

- Agent orchestration with isolated resources (nREPL, database, logs per agent)
- Dev hooks that validate edits with tests + AI review
- Observatory UI to watch agent progress
- Health checks and resource cleanup
- Schema registry with introspection
- Message persistence for replay and learning (data collection phase)

---

## Your Role: Orchestrator

You coordinate work and delegate to agents. **Delegate ~90% of implementation to agents.**

### Do Directly (10%)
- Tiny edits (typos, comments, renames)
- Quick file reads to answer questions
- Git operations (commits, status)
- PRD updates

### Delegate to Agents (90%)
- Feature implementation
- Bug investigation and fixes
- Research tasks
- Multi-file changes

---

## Launching Agents

Use the Clojure agent system (`seon.ai.claude`) to launch agents. This gives you:
- **Isolated nREPL** - Each agent gets its own REPL on a unique port
- **Isolated database** - Each agent gets its own XTDB database
- **Observatory UI** - Watch agent progress at http://localhost:8080/agents
- **Message persistence** - All messages saved to XTDB for review
- **Dev hook integration** - Agent edits trigger reload/test/review

### 1. Ensure a PRD Exists

```bash
cp -r docs/prds/_example-feature docs/prds/{feature-name}
```

Write clear goals, success criteria, and relevant context.

### 2. Launch via MCP (Blocking - Default)

**Always check for existing agents first:**
```clojure
(user/agents)
```

**Launch and wait for completion** using `user/launch-agent!!` (double-bang = blocking):

```
eval(session_id="orchestrator", timeout_ms=600000,
     code="(user/launch-agent!! 'seon.feature-name \"Read docs/prds/feature-name/prd.md and implement Phase 1.\")")
```

**MCP Timeout Behavior:** The MCP eval has a 30-second default timeout. If it times out:
- The agent **keeps running** in the background
- Use `(user/agents)` to see it's still running
- Use `(user/wait-for-agent!! "session-id")` to re-attach and wait for completion

Set `timeout_ms=600000` (10 min) to avoid premature timeouts on blocking calls.

**Returns result directly when agent completes:**
```clojure
{::claude/result-text "## Summary\n\n..."
 ::claude/agent-status :completed
 ::claude/cost-usd 0.23
 ::claude/duration-ms 45000
 ::claude/num-turns 5}
```

**Point agents to the PRD**, don't give walls of instructions:
```clojure
;; DO THIS:
"Read docs/prds/namespace-ui/prd.md and implement Phase 0."

;; NOT THIS:
"Add a debounce-seconds config to .claude/seon-hook.edn and wire it through..."
```

### 3. Non-Blocking Launch (Parallel Work)

Only use `launch-agent!` (single-bang) when running multiple agents in parallel:

```clojure
;; Launch without waiting
(user/launch-agent! 'seon.feature-name "Implement feature X")
;; => {::ai/session-id "a1b2" ...}

;; Check result later
(user/agent-result "a1b2")
;; => {::claude/result-text "..." ::claude/agent-status :completed}
```

### Agent Helper Functions

All available in the `user` namespace:

| Function | Purpose |
|----------|---------|
| `(user/launch-agent!! 'ns "prompt")` | Launch and wait for result (blocking) |
| `(user/launch-agent! 'ns "prompt")` | Launch without waiting (returns handle) |
| `(user/agents)` | List running agents |
| `(user/agent-messages "a1b2")` | Check agent progress (recent messages) |
| `(user/agent-result "a1b2")` | Get result from completed agent |
| `(user/wait-for-agent!! "a1b2")` | Re-attach and wait for running agent |
| `(user/interrupt-agent! "a1b2")` | Stop a running agent |

**UI Monitoring:**
- Observatory: http://localhost:8080/agents
- Dashboard: http://localhost:8080/

### 4. Agent Instructions (Automatic)

Agents automatically receive `.claude/AGENT.md` as part of their prompt. This tells them:
- They're subagents being observed via Observatory
- To think out loud and summarize results
- Project conventions and tool usage

Edit AGENT.md to change what all agents know.

### 5. Choosing Namespaces

The `::ai/namespace` parameter sets:
- The **default REPL namespace** for the agent's session
- The **isolated XTDB database** name

**Choose the namespace the agent will primarily work in:**

```clojure
;; GOOD - agent working on web agents code
::ai/namespace 'seon.web.agents

;; GOOD - agent building new trading signals feature
::ai/namespace 'seon.trading.signals

;; BAD - throwaway/task-based names
::ai/namespace 'seon.fix-bug-123
::ai/namespace 'seon.observatory-fix
```

The namespace doesn't restrict the agent's work - they can edit any file and switch REPL namespaces. But it sets their starting context and database isolation.

---

## Quick Reference

### Server

```bash
./bin/run    # Start everything: XTDB, HTTP (8080), nREPL (7888)
```

The server must be running for agents to work.

### Health Checks

```bash
curl http://localhost:8080/api/health
```

Returns component status (XTDB, nREPL, agents) with latencies. HTTP 200 = healthy, 503 = unhealthy.

```clojure
;; In REPL - check health
(require '[seon.health :as health])
(health/deep-check {::health/node (:seon/xtdb-node integrant.repl.state/system)})

;; Clean up orphaned resources after crash
(health/cleanup-orphaned-resources! {::health/node node})
```

### Running Tests

```bash
# Specific namespace (preferred - fast feedback)
clojure -M:test -m kaocha.runner --focus seon.ai.claude-test

# Specific test var
clojure -M:test -m kaocha.runner --focus seon.ai.claude-test/constants-test

# All tests (slow - use sparingly)
clojure -M:test -m kaocha.runner

# Watch mode (re-runs on file changes)
clojure -M:test -m kaocha.runner --watch --focus seon.ai.claude-test
```

**Always run focused tests first** when fixing bugs or verifying changes. Only run the full suite before committing or when changes affect multiple namespaces.

For test patterns, mocking, and debugging test failures, invoke `/clojure-testing`.

### Your REPL

Connect your editor to nREPL on port 7888. Use these helpers:

| Function | Purpose |
|----------|---------|
| `(reload)` | Fast reload changed code (~2ms) |
| `(reset)` | Reload + restart components |
| `(status)` | Show system status |
| `(search "query")` | Web search via Gemini |

### MCP Tools (for orchestrator)

```
eval(session_id="orchestrator", code="(user/status)")
eval(session_id="orchestrator", code="(user/search \"query\")")
```

---

## Architecture

```
seon/
├── src/seon/
│   ├── core.clj              ; System entry, protocols
│   ├── config.clj            ; Aero config loading
│   ├── system.clj            ; Integrant system map
│   ├── db/                   ; Database layer
│   ├── domains/              ; Domain modules (trading, health, etc.)
│   └── web/                  ; HTTP server, SSE, handlers
├── reference-code/           ; Git submodules of dependency source
│   └── xtdb/                 ; XTDB source (read when stuck)
└── docs/
    ├── prds/                 ; Feature specifications
    └── reference/            ; Technical reference docs
```

---

## Skills (IMPORTANT)

**ALWAYS invoke the relevant skill FIRST** before searching, grepping, or trial-and-error. Skills encode project-specific knowledge that saves significant time.

| Skill | Invoke When |
|-------|-------------|
| `/xtdb-queries` | Writing queries, debugging empty results, working with `xt/q` |
| `/datastar-web-ui` | SSE handlers, `data-*` attributes, streaming responses |
| `/browser-automation` | Testing UI in browser, debugging frontend issues |
| `/clojure-testing` | **Running tests**, test failures, kaocha, mocking, generators |

**Examples of when to invoke skills:**
- "How do I run tests?" → `/clojure-testing` (don't guess at CLI commands)
- "Query returns empty" → `/xtdb-queries` (don't grep for examples)
- "SSE not updating" → `/datastar-web-ui` (don't read random handler files)

The skill provides the exact commands, patterns, and gotchas for this codebase.

---

## UI Development

Seon uses a **Phosphor Terminal** theme - warm blacks, cream text, amber accents. Think Lisp machine, not generic web app.

### Before Writing UI Code

1. **Read the design system:** `docs/prds/namespace-ui/design-system.md`
2. **Use the component library:** `src/seon/web/components.clj`
3. **Invoke skills:** `/datastar-web-ui` for SSE patterns, `/browser-automation` to test

### Component Library

```clojure
(require '[seon.web.components :as ui])

(ui/page-header "Title" :subtitle "optional")
(ui/section-header "SECTION")
(ui/card (ui/section-header "Card") content...)
(ui/status-dot :running :label "running")  ; NOT pill badges
(ui/log-line {:timestamp ts :type "TOOL" :content "..."})
```

### Key Rules

- **Density over whitespace** - `p-3` not `p-6`, `gap-4` not `gap-6`
- **Small text** - `text-xs` (11px) primary, `text-lg` max for titles
- **Warm colors** - `bg-base-*`, `text-text-*`, never `bg-white` or `text-zinc-*`
- **Dot+text status** - `● running` not pill badges
- **Monospace everywhere** - `font-mono` on body

### Reference Files

| File | Purpose |
|------|---------|
| `docs/prds/namespace-ui/design-system.md` | Full color palette, typography, spacing |
| `src/seon/web/components.clj` | Reusable UI components |
| `src/seon/web/html.clj` | Base template, nav, shared functions |

---

## Key Documents

| Document | Purpose |
|----------|---------|
| `VISION.md` | Full thesis, architecture layers, progress tracking |
| `CONVENTIONS.md` | Malli schemas, API design patterns |
| `docs/reference/xtdb-v2-reference.md` | Database queries (use SQL) |
| `docs/reference/datastar-quick-reference.md` | Web UI attributes |
| `docs/prds/namespace-ui/design-system.md` | UI colors, typography, spacing |

---

## Dev Hook

After every Edit/Write, the hook automatically:
- Reloads code into the running server
- Runs tests for affected namespaces
- Validates schemas via generative testing
- Provides Gemini AI review

Config in `.claude/seon-hook.edn`. Hook blocks if tests fail.

---

## Code Reloading

**The dev hook handles code reloading automatically** after every Edit/Write. You rarely need to reload manually.

### Safe operations:
```clojure
(user/reload)  ; Fast reload via clj-reload (what the hook uses)
(user/reset)   ; Full system restart - use when changing config/components
(user/status)  ; Check system health
```

### Avoid raw require with :reload:
```clojure
;; Don't do this - bypasses proper cleanup:
(require 'some.namespace :reload)
(require 'some.namespace :reload-all)
```

### If something breaks:
Restart the server cleanly: `pkill -f "clojure.*seon" && ./bin/run`

---

## Domain Guidelines

When adding domains:

1. **One file per namespace** - Don't split prematurely
2. **DB parameter** - Functions receive `db` as first parameter
3. **Schema-first** - Define Malli schemas before implementation
4. **Namespaced IDs** - `:trading/position`, `:health/workout`

See `CONVENTIONS.md` for full patterns.

---

## AI Architecture (Reference)

```
seon.ai                    ; Base schemas + session/message persistence
├── seon.ai.agent          ; Agent registry, observatory API
└── seon.ai.claude         ; Claude provider (what you use)
    └── seon.ai.claude.sdk ; Low-level CLI process management
```

As orchestrator, you primarily use `seon.ai.claude`:

```clojure
(claude/launch-agent! ...)       ; Launch (see "Launching Agents" above)
(claude/agents {})               ; List running agents
(claude/tail {::ai/session-id "xxxx"})  ; Stream messages
(claude/interrupt! {::ai/session-id "xxxx"})  ; Stop agent
```

The lower-level namespaces (`seon.ai`, `seon.ai.agent`, `seon.ai.claude.sdk`) are for extending the system, not daily use.

---

## File Locations

**Never use `/tmp` or system temp directories.** Use project-local directories instead:

| Directory | Purpose | Git Status |
|-----------|---------|------------|
| `logs/` | Debug logs, hook logs, agent activity | Ignored |
| `tmp/` | Temporary test files, scratch data | Ignored |
| `data/` | XTDB database files | Ignored |

These directories are gitignored and local to the project. This ensures:
- Logs are findable and debuggable
- Multiple projects don't conflict
- Agents can access their own logs
