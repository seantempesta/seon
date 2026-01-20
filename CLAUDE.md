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

### 1. Ensure a PRD Exists

```bash
cp -r docs/prds/_example-feature docs/prds/{feature-name}
```

Write clear goals, success criteria, and relevant context.

### 2. Point the Agent to the PRD

**DO THIS:**
```
Read docs/prds/unified-dev-hook/prd.md and implement Phase 5.
```

**NOT THIS:**
```
Add a debounce-seconds config option to .claude/seon-hook.edn and
wire it through to the should-trigger-review? function...
[walls of instructions]
```

Smart agents read context and make decisions. Mindless drones follow exact instructions and miss the bigger picture.

### 3. Choose the Right Agent Type

| Type | Use For |
|------|---------|
| `general-purpose` | Implementation, research, multi-file changes |
| `Explore` | Quick searches, finding files (read-only) |
| `Plan` | Architecture planning |

**For implementation tasks**, the `seon-agent` subagent should auto-delegate based on task description. If not, mention it explicitly:
```
Use the seon-agent subagent. Read docs/prds/feature/prd.md and implement Phase 1.
```

**Never use background agents** - they have restricted permissions.

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

## Key Documents

| Document | Purpose |
|----------|---------|
| `CONVENTIONS.md` | Malli schemas, API design patterns |
| `docs/reference/xtdb-v2-reference.md` | Database queries (use SQL) |
| `docs/reference/datastar-quick-reference.md` | Web UI attributes |
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

## AI Namespace Hierarchy

The AI namespaces provide a clean, provider-agnostic architecture for AI agent management:

```
seon.ai                    ; Base schemas + session/message persistence
├── seon.ai.agent          ; Provider-agnostic agent lifecycle, registry, observatory
└── seon.ai.claude         ; Claude provider implementation
    └── seon.ai.claude.sdk ; Claude CLI process management
```

### seon.ai (Base)

Provider-agnostic schemas and functions for AI sessions and messages:

```clojure
(require '[seon.ai :as ai])

;; Start a session
(ai/start-session! {::ai/node xtdb-node
                    ::ai/namespace 'seon.trading
                    ::ai/prompt "Analyze data"})

;; Add messages
(ai/add-message! {::ai/node xtdb-node
                  ::ai/session-id "ses-abc123"
                  ::ai/role "assistant"
                  ::ai/content "I'll analyze..."})

;; Query
(ai/get-session {::ai/node xtdb-node ::ai/session-id "ses-abc123"})
(ai/get-messages {::ai/node xtdb-node ::ai/session-id "ses-abc123"})
(ai/list-sessions {::ai/node xtdb-node ::ai/limit 20})
```

### seon.ai.agent (Provider Extension Points)

Defines multimethods that providers implement and centralized agent registry:

```clojure
(require '[seon.ai.agent :as agent])
(require '[seon.ai.claude]) ; Load Claude implementations

;; Multimethods (implemented by providers):
;; - agent/normalize-message - Convert provider message to ::ai/message
;; - agent/result-message?   - Check if message is final result
;; - agent/parse-result      - Extract stats from result message

;; Observatory API (works across all providers):
(agent/agents {})                              ; List all running agents
(agent/get-agent {::agent/session-id "a1b2"})  ; Get agent handle
(agent/tail {::agent/session-id "a1b2"})       ; Stream messages
(agent/interrupt! {::agent/session-id "a1b2"}) ; Stop agent
```

### seon.ai.claude (Claude Provider)

Claude-specific agent lifecycle with automatic message persistence:

```clojure
(require '[seon.ai.claude :as claude])

;; Launch agent (auto-persists all messages to XTDB)
(claude/launch-agent! {::ai/node xtdb-node
                       ::ai/namespace 'seon.trading
                       ::ai/prompt "Implement feature"})

;; Claude-specific observatory (delegates to seon.ai.agent)
(claude/agents {})                           ; List Claude agents
(claude/tail {::ai/session-id "a1b2"})       ; Stream messages
(claude/interrupt! {::ai/session-id "a1b2"}) ; Stop agent
```

### seon.ai.claude.sdk (Claude CLI)

Low-level process management for Claude Code CLI:

```clojure
(require '[seon.ai.claude.sdk :as sdk])

;; Spawn a Claude Code process
(let [{:keys [process stdin stdout]} (sdk/spawn-claude-code {})]
  (sdk/write-message! stdin (sdk/make-user-message "Hello"))
  ;; Read responses from stdout...
  )
```

### Research Namespace

The `seon.claude.exploration` namespace is kept as a **research/development tool** for protocol investigation, not for production use.

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
