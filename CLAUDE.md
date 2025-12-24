# Seon - Claude Code Instructions

## What is Seon?

**Seon** - from the archaic "to see", and inspired by the Seons of Brandon Sanderson's *Elantris*: sentient, luminous beings that serve and assist their bonded humans. A personal operating system for life - a unified platform integrating multiple life domains: trading, health, finance, tasks, knowledge, and more. Built on a foundation of Clojure, XTDB, and AI agents.

### Vision
- **One database, many domains** - XTDB as unified temporal store across all life data
- **Protocol-based architecture** - Core defines interfaces, domains implement
- **AI-native** - Built for collaboration between humans and AI agents
- **Temporal by default** - Full history of all data, point-in-time queries
- **Local-first** - Your data lives on your machine

### Current State
Transforming from `ml-options-trading` (options trading system) into Seon. Original trading functionality becomes the first domain. See `PLAN.md` for transformation stages.

---

## Quick Start

**Seon is a long-running server.** Start it once and leave it running.

### 1. Start the Server (once)

```bash
./bin/run    # Starts EVERYTHING: XTDB, HTTP (8080), nREPL (7888)
```

This is the canonical way to run Seon. Logs to stdout, Ctrl+C to stop.

### 2. Connect Your Editor

Connect to the running nREPL on port 7888. The server manages nREPL as an Integrant component.

**DO NOT** start a separate nREPL manually - it will conflict with the server's nREPL.

### 3. Reload Code Changes

After editing code, reload it into the running server:

```clojure
(reset)   ; Reloads ALL changed namespaces, restarts components
(status)  ; Verify system is healthy
```

### For Agents (non-interactive)

Use `clj-nrepl-eval` to send commands to the running server:

```bash
clj-nrepl-eval -p 7888 "(reset)"                    # Reload code
clj-nrepl-eval -p 7888 "(user/status)"              # Check status
clj-nrepl-eval -p 7888 "(integrant.repl/reset)"     # Alternative reset
```

### If You Need to Restart

```bash
# Ctrl+C the running ./bin/run, then:
./bin/run
```

---

## Critical Rules

1. **Invoke skills before searching** - Check if a skill applies before manually grepping/reading
2. **Use native XTQL, not SQL** - Use `seon.db.node/query`, never raw SQL strings
3. **Write tests** - All code changes require appropriate tests
4. **REPL-driven development** - Verify changes work via REPL before declaring done
5. **PRDs may be wrong** - Understand what you're building, don't blindly follow specs
6. **Domain isolation** - Domains communicate through core protocols, not direct calls

---

## Architecture Overview

```
seon/
├── src/seon/
│   ├── core.clj              ; System entry, protocols, shared utilities
│   ├── config.clj            ; Aero config loading
│   ├── system.clj            ; Integrant system map
│   │
│   ├── db/                   ; Database layer (shared across domains)
│   │   ├── node.clj          ; XTDB node management, query wrapper
│   │   ├── schema.clj        ; Malli schemas
│   │   ├── queries.clj       ; Common query patterns
│   │   └── transactions.clj  ; Transaction helpers
│   │
│   ├── domains/              ; Domain modules (future structure)
│   │   ├── trading/          ; Options trading (from ml-options)
│   │   ├── health/           ; Apple Health integration (planned)
│   │   ├── finance/          ; Personal finance (planned)
│   │   └── tasks/            ; Task/project management (planned)
│   │
│   └── web/                  ; Web UI layer
│       ├── server.clj        ; HTTP-kit server
│       ├── routes.clj        ; Ring routes
│       ├── handlers.clj      ; Request handlers
│       ├── html.clj          ; Hiccup templates
│       └── sse.clj           ; Server-sent events
│
├── data/
│   └── xtdb/                 ; XTDB storage (git-ignored)
│
└── docs/
    ├── prds/                 ; Feature specifications
    └── reference/            ; Technical reference docs
```

---

## Agent Principles

These apply to all agents working on this project:

### Prototype and Iterate, Don't Waterfall
- PRDs describe goals and constraints, not exact implementations
- PRDs may be wrong or incomplete - use your judgment
- Rapid prototyping beats upfront planning
- Get something working first, then refine
- If a PRD's approach isn't working, try a different approach

### Learn by Doing
- Read reference code, but don't cargo-cult - understand WHY it works
- Build small experiments to test your understanding
- When stuck, prototype multiple approaches rather than analyzing forever
- Search the web when you need to, but verify with working code

### Verify Everything via REPL

The server must be running (`./bin/run`). Then verify your changes:

```bash
clj-nrepl-eval -p 7888 "(user/status)"    # System running?
clj-nrepl-eval -p 7888 "(reset)"          # Reload code changes
curl http://localhost:8080/api/health     # HTTP working?
```

### Incremental Changes
- Make small changes, verify each works, then proceed
- Test the specific functionality you changed
- Test that reset/restart still works
- Test that nothing else broke

### No Parallel Implementations
**Never create v1/v2/v3 or "old"/"new" versions of code.** This clutters the codebase.

- **Accretion**: Prefer adding to existing code when extending functionality
- **Replacement**: If changing approach, replace the old code entirely - don't keep both
- **No suffix naming**: Never create `foo_v2.clj`, `foo_new.clj`, `foo_final.clj`
- **No commented old code**: Delete replaced code, don't comment it out (git has history)

### Testing Philosophy
- Write tests that catch real bugs, not tests for test coverage
- Property-based tests for data transformations
- Integration tests for database operations
- Don't add useless tests - if a test can't fail meaningfully, skip it
- Run tests before completing: `clj -M:test -m kaocha.runner`

---

## Feature Development Workflow

**PRDs define WHAT/WHY, agents figure out HOW.**

PRDs specify goals, constraints, and success criteria - not exact implementations. Agents are principal engineers who:
- Investigate the codebase to understand current state
- Design an approach based on existing patterns
- Prototype and iterate until it works
- Update the PRD's "Implementation Summary" section when done

**For orchestrators starting a feature:**
1. Create feature dir: `cp -r docs/prds/_example-feature docs/prds/{feature-name}`
2. Write `prd.md` with goals, constraints, success criteria
3. Launch subagents for exploration/implementation

**For all agents:**
- **Write findings to disk** - Write to `docs/prds/{feature}/research/`
- **Update decisions.md** - Record architectural choices with rationale
- **Update notes.md** - Capture gotchas, learnings, things that surprised you
- **Update PRD after completion** - Fill in "Implementation Summary"

**Subagent types:**

| Type | Write Access | Use For |
|------|--------------|---------|
| `general-purpose` | **YES** | Research that writes findings, implementation work |
| `Explore` | **NO** (read-only) | Quick searches, finding files, answering questions |
| `Plan` | **YES** | Architecture planning |

See `docs/prds/readme.md` for full templates and workflow details.

---

## System Lifecycle

### The Server Model

Seon runs as a **persistent server** started with `./bin/run`. This single process manages:
- XTDB database node
- HTTP server (port 8080)
- nREPL server (port 7888)
- All Integrant components

**You don't start/stop components manually.** The server handles lifecycle.

### Reloading Code (CRITICAL)

**DO NOT use `(require 'ns :reload)`** - it doesn't work reliably.

**ALWAYS use `(reset)`** - properly reloads ALL changed namespaces in dependency order, then restarts components.

```bash
# From command line (for agents)
clj-nrepl-eval -p 7888 "(reset)"
```

```clojure
;; From connected REPL
(reset)
```

**Before resetting, ask: "What's currently running?"** A reset interrupts any running futures, background jobs, or active queries. Run `(status)` first.

### If Reset Fails

1. Fix the compile error
2. Run `(reset)` again - it should recover
3. If still broken: Ctrl+C and restart `./bin/run`

### Checking System Health

```bash
clj-nrepl-eval -p 7888 "(user/status)"
```

This shows all running components and XTDB metrics.

---

## XTDB Quick Reference

```clojure
;; CORRECT - use our wrapper with XTQL
(require '[seon.db.node :as node])
(node/query (xtdb-node) '(from :option-greeks [asset/ticker quote/iv]))

;; CORRECT - with dynamic values
(require '[xtdb.api :as xt])
(node/query (xtdb-node)
  (xt/template (from :option-greeks [{:asset/ticker ~ticker} quote/iv])))

;; WRONG - don't use SQL strings or xt/q directly
```

**Key gotchas:**
- `[*]` returns empty maps - always list columns explicitly
- Use `xt/template` for dynamic values
- See `docs/reference/xtdb-v2-reference.md` for full reference

---

## Documentation Reference

### Skills (Invoke Before Searching)

| Skill | Invoke When |
|-------|-------------|
| `xtdb-queries` | Writing/debugging queries, XTQL syntax, empty results |
| `datastar-web-ui` | SSE handlers, Datastar attributes, UI design |
| `browser-automation` | Testing in browser, debugging UI, network/console inspection |
| `data-import` | ThetaData API, bulk imports, OCC symbols |
| `clojure-testing` | Writing tests, debugging failures, mocking |

### Reference Docs
| Document | When to Read |
|----------|--------------|
| `docs/reference/xtdb-v2-reference.md` | Database work - XTQL queries, temporal |
| `docs/reference/datastar-quick-reference.md` | Web UI work - attribute reference |
| `docs/reference/logging-setup.md` | Debugging - log files, REPL functions |
| `PLAN.md` | Transformation roadmap and current status |

---

## Testing

```bash
clj -M:test -m kaocha.runner           # Run all tests
clj -M:test -m kaocha.runner --watch   # Watch mode
```

**Invoke the `clojure-testing` skill** for test patterns, mocking, and common gotchas.

---

## Domain Design Guidelines

When adding new domains to Seon:

### Standard Domain Structure
```
src/seon/domains/{domain}/
├── core.clj          ; Public API, protocol implementations
├── schema.clj        ; Malli schemas for domain entities
├── queries.clj       ; Domain-specific XTDB queries
├── ingest.clj        ; Data import (if applicable)
└── {domain}_test.clj ; Colocated tests
```

### Domain Principles
1. **Self-contained** - Domain can function independently
2. **DB parameter** - All functions receive `db` as first parameter (no globals)
3. **Schema-first** - Define Malli schemas before implementation
4. **Temporal** - Leverage XTDB's bitemporal capabilities
5. **Testable** - Colocated tests, property-based where appropriate

### Entity ID Conventions
- Use namespaced keywords: `:trading/position`, `:health/workout`
- Include domain prefix in xt/id: `{:xt/id :trading/position-123}`

---

## Project Tracking

- **PLAN.md** - Transformation roadmap (ml-options → Seon)
- **PRDs** in `docs/prds/{feature}/prd.md` - Feature specifications
- **Original project** at `~/src/ml-options-trading` for reference
