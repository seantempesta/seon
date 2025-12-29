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

## Are You an Orchestrator or an Agent?

**Read this first.** When Claude Code runs on this project, it operates in one of two roles:

### You Are an ORCHESTRATOR If:
- You're in the main conversation with the user
- You can launch subagents via the Task tool
- The user is asking you to build features or fix bugs

### You Are an AGENT If:
- You were launched via the Task tool (you'll see this in your context)
- You received a prompt telling you to work on a specific task
- You should read the PRD and work autonomously

**If you're unsure, you're probably the orchestrator.**

---

## Orchestrator Responsibilities

As an orchestrator, your job is **coordination, not implementation**. You should delegate ~90% of work to agents.

### When to Do Work Directly (10%)
- Tiny edits (fix a typo, add a comment, rename a variable)
- Quick file reads to answer a question
- Git operations (commits, status checks)
- Updating PRDs with status

### When to Launch an Agent (90%)
- Any feature implementation
- Bug fixes that require investigation
- Research tasks
- Multi-file changes
- Anything that requires reading multiple files to understand

### Before Launching Any Agent

1. **Ensure a PRD exists** - Copy template if needed:
   ```bash
   cp -r docs/prds/_example-feature docs/prds/{feature-name}
   ```

2. **Write or update the PRD** with:
   - Clear goals and success criteria
   - Known constraints
   - Relevant context (what files to look at, what patterns to follow)

3. **Point the agent to the PRD** - Don't explain the task yourself. Say:
   > "Read `docs/prds/{feature}/prd.md` and implement Phase X"

### How to Launch Agents

**DO THIS:**
```
Read docs/prds/unified-dev-hook/prd.md and implement Phase 5.
Write your findings to the research/ folder. Update the PRD when done.
```

**NOT THIS:**
```
Add a debounce-seconds config option to .claude/seon-hook.edn and
wire it through to the should-trigger-review? function in feedback.clj...
[walls of specific instructions]
```

The first approach creates a smart agent that will:
- Read context and understand the full picture
- Do additional research if needed
- Make good decisions autonomously
- Document what it learned

The second approach creates a mindless drone that:
- Only does exactly what you said
- Misses important context
- Doesn't learn or adapt
- Produces brittle results

### Agent Types

| Type | Use For | Write Access |
|------|---------|--------------|
| `general-purpose` | Implementation, research that produces docs | **YES** |
| `Explore` | Quick lookups, finding files, answering questions | NO (read-only) |
| `Plan` | Architecture design, creating plans | **YES** |

**Never use background agents** (`run_in_background: true`) - they have restricted permissions.

---

## Agent Responsibilities

As an agent, you were launched to accomplish a specific task. You should work **autonomously and intelligently**.

### First Steps When Launched

1. **Read the PRD** - Your prompt should tell you which one
2. **Read existing research** - Check `docs/prds/{feature}/research/` for prior work
3. **Understand the codebase** - Read relevant files, don't just grep blindly
4. **Invoke skills** - Use project-specific skills before manual searching

### How to Work

- **Be autonomous** - Don't ask permission for every step. Make decisions.
- **Do research** - If you don't understand something, read more files or search the web
- **Run tests** - Verify your changes work: `clj -M:test -m kaocha.runner`
- **Use the REPL** - Verify code works via nREPL before declaring done
- **Iterate** - If something doesn't work, try a different approach

### Writing Research

When you learn something important, write it down:

```
docs/prds/{feature}/research/
├── {topic}.md           # What you learned about a specific topic
├── decisions.md         # Architectural choices with rationale
└── notes.md             # Gotchas, surprises, things to remember
```

**Research files help future agents** (including yourself if resumed) understand context without re-doing investigation.

### Updating the PRD

When you complete work:
1. Update the "Implementation Summary" section
2. Mark completed phases/tasks
3. Document what's remaining
4. Note any decisions or changes from the original plan

### When You're Stuck

1. Read more context - often the answer is in adjacent files
2. Check reference docs - see the Documentation Reference section below
3. Invoke relevant skills - they contain project-specific knowledge
4. Try a different approach - PRDs describe goals, not exact implementations
5. Document what you tried - so the next agent doesn't repeat it

---

## Quick Start (All Agents)

**Seon is a long-running server.** Start it once and leave it running.

### Start the Server

```bash
./bin/run    # Starts EVERYTHING: XTDB, HTTP (8080), nREPL (7888)
```

### Reload Code Changes

```bash
clj-nrepl-eval -p 7888 "(reset)"          # Reload all changed namespaces
clj-nrepl-eval -p 7888 "(user/status)"    # Verify system is healthy
```

**DO NOT use `(require 'ns :reload)`** - it doesn't work reliably. Always use `(reset)`.

### Run Tests

```bash
clj -M:test -m kaocha.runner              # Run all tests
clj -M:test -m kaocha.runner --focus ns   # Run specific namespace
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

## Skills (Invoke Before Searching)

| Skill | Invoke When |
|-------|-------------|
| `xtdb-queries` | Writing/debugging queries, XTQL syntax, empty results |
| `datastar-web-ui` | SSE handlers, Datastar attributes, UI design |
| `browser-automation` | Testing in browser, debugging UI, network/console inspection |
| `data-import` | ThetaData API, bulk imports, OCC symbols |
| `clojure-testing` | Writing tests, debugging failures, mocking |

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
│   ├── ai/                   ; AI integrations
│   │   └── gemini.clj        ; Gemini API client
│   │
│   ├── dev/                  ; Development utilities
│   │   └── feedback.clj      ; REPL feedback, generative testing
│   │
│   └── web/                  ; Web UI layer
│       ├── server.clj        ; HTTP-kit server
│       ├── routes.clj        ; Ring routes
│       ├── handlers.clj      ; Request handlers
│       ├── html.clj          ; Hiccup templates
│       └── sse.clj           ; Server-sent events
│
├── docs/
│   ├── prds/                 ; Feature specifications (PRDs)
│   └── reference/            ; Technical reference docs
│
└── bin/
    ├── run                   ; Start the server
    └── seon-hook             ; Dev hook (tests, code review)
```

---

## Reference Documentation

| Document | When to Read |
|----------|--------------|
| `CONVENTIONS.md` | Malli schema patterns, public API design |
| `docs/reference/xtdb-v2-reference.md` | Database work - XTQL queries, temporal |
| `docs/reference/datastar-quick-reference.md` | Web UI work - attribute reference |
| `docs/reference/logging-setup.md` | Debugging - log files, REPL functions |
| `PLAN.md` | Transformation roadmap and current status |

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

## Gemini API

The Gemini client provides multiple functions:

```clojure
(require '[seon.ai.gemini :as gemini])

;; Simple question/answer
(gemini/ask {::gemini/prompt "Explain XTDB temporal queries"})

;; Web search with Google grounding (use for current info, verification)
(gemini/search {::gemini/prompt "Latest Clojure 1.12 features"})

;; Python code execution (calculations, data processing)
(gemini/calculate {::gemini/prompt "What is the 100th Fibonacci number?"})

;; Code review (plain text, advisory)
(gemini/review-code {::gemini/prompt "Review this function"
                     ::gemini/code "(defn foo [x] ...)"
                     ::gemini/conventions (slurp "CONVENTIONS.md")})
```

**For research:** Use `gemini/search` to verify knowledge that may be out of date.

---

## Dev Hook (bin/seon-hook)

The unified hook runs after file edits:
1. Syntax repair (delimiter fix, cljfmt)
2. Namespace reload (compile check)
3. Unit tests (blocks on real failures)
4. Generative tests (Malli schema validation)
5. Gemini code review (advisory, 30s debounce)

**Configuration:** `.claude/seon-hook.edn`

---

## Code Quality Standards

### No Parallel Implementations
Never create v1/v2/v3 or "old"/"new" versions:
- **Accretion**: Add to existing code when extending
- **Replacement**: Replace old code entirely - don't keep both
- **No suffix naming**: Never `foo_v2.clj`, `foo_new.clj`
- **No commented code**: Delete replaced code (git has history)

### Testing Philosophy
- Write tests that catch real bugs, not for coverage metrics
- Property-based tests for data transformations
- Integration tests for database operations
- Skip useless tests - if it can't fail meaningfully, don't write it

### Domain Principles
1. **Self-contained** - Each namespace functions independently
2. **DB parameter** - Functions receive `db` as first parameter (no globals)
3. **Schema-first** - Define Malli schemas before implementation
4. **Temporal** - Leverage XTDB's bitemporal capabilities
5. **Testable** - Tests in `test/` mirror `src/` structure

---

## Project Tracking

- **PLAN.md** - Transformation roadmap (ml-options → Seon)
- **PRDs** in `docs/prds/{feature}/prd.md` - Feature specifications
- **Original project** at `~/src/ml-options-trading` for reference
