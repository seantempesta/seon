# Seon - Claude Code Instructions

## What is Seon?

**Seon** - from the archaic "to see", and inspired by the Seons of Brandon Sanderson's *Elantris*: sentient, luminous beings that serve and assist their bonded humans. A personal operating system for life - a unified platform integrating multiple life domains: trading, health, finance, tasks, knowledge, and more. Built on a foundation of Clojure, XTDB, and AI agents.

### Vision
- **One database, many domains** - XTDB as unified temporal store across all life data
- **Protocol-based architecture** - Core defines interfaces, domains implement
- **AI-native** - Built for collaboration between humans and AI agents
- **Temporal by default** - Full history of all data, point-in-time queries
- **Local-first** - Your data lives on your machine

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

### 2. Launch via MCP

**Always check for existing agents first:**
```clojure
;; Check before launching to avoid duplicate agents on same namespace
(claude/agents {})
```

```clojure
;; Via MCP eval tool (preferred for orchestrator)
(claude/launch-agent! {::ai/node (:seon/xtdb-node integrant.repl.state/system)
                       ::ai/namespace 'seon.feature-name
                       ::ai/prompt "Read docs/prds/feature-name/prd.md and implement Phase 1."})

;; If you intentionally want multiple agents on the same namespace:
(claude/launch-agent! {::ai/node (:seon/xtdb-node integrant.repl.state/system)
                       ::ai/namespace 'seon.feature-name
                       ::ai/prompt "..."
                       ::ai/force? true})  ; Override duplicate namespace check
```

**Point agents to the PRD**, don't give walls of instructions:

```clojure
;; DO THIS:
::ai/prompt "Read docs/prds/namespace-ui/prd.md and implement Phase 0."

;; NOT THIS:
::ai/prompt "Add a debounce-seconds config to .claude/seon-hook.edn and wire it through..."
```

Smart agents read context and make decisions.

### 3. Monitor and Wait for Completion

**UI Monitoring:**
- Observatory: http://localhost:8080/agents
- Dashboard: http://localhost:8080/

**Wait for completion (background task pattern):**

```bash
# Start agent, get session ID (e.g., "a1b2")
# Then start background wait:
tail -f logs/agents/a1b2.log | grep -m1 "COMPLETE"  # run_in_background=true

# Continue other work...

# When ready to check, use TaskOutput with block=true
# The COMPLETE line includes: subtype, cost, messages, duration
```

**REPL monitoring:**
```clojure
(agent/agents {})                        ; List running agents
(agent/tail {::agent/session-id "xxxx"}) ; Stream messages channel
```

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

## Skills

Invoke skills before manual searching - they encode project-specific knowledge.

| Skill | Invoke When |
|-------|-------------|
| `xtdb-queries` | Database queries, SQL patterns |
| `datastar-web-ui` | SSE handlers, Datastar attributes |
| `browser-automation` | Testing in browser, debugging UI |
| `data-import` | ThetaData API, bulk imports |
| `clojure-testing` | Test patterns, mocking, generators |

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
| `CONVENTIONS.md` | Malli schemas, API design patterns |
| `docs/reference/xtdb-v2-reference.md` | Database queries (use SQL) |
| `docs/reference/datastar-quick-reference.md` | Web UI attributes |
| `docs/prds/namespace-ui/design-system.md` | UI colors, typography, spacing |
| `PLAN.md` | Transformation roadmap |

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
