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

Your job is **coordination, not implementation**. Delegate ~90% of work to agents.

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
- Anything requiring reading multiple files to understand

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
Update the PRD when done.
```

**NOT THIS:**
```
Add a debounce-seconds config option to .claude/seon-hook.edn and
wire it through to the should-trigger-review? function in feedback.clj...
[walls of specific instructions]
```

The first approach creates a **smart agent** that will:
- Read context and understand the full picture
- Do additional research if needed
- Make good decisions autonomously
- Document what it learned

The second approach creates a **mindless drone** that:
- Only does exactly what you said
- Misses important context
- Doesn't learn or adapt
- Produces brittle results

---

## Agent Responsibilities

You were launched to accomplish a specific task. Work **autonomously and intelligently**.

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

### Writing to the PRD Folder

```
docs/prds/{feature}/
├── prd.md               # The source of truth - keep updated!
├── decisions.md         # Architectural choices with rationale
├── notes.md             # Gotchas, surprises, things to remember
└── research/            # Standalone research files
    └── {topic}.md       # Focused investigation on a specific topic
```

**The PRD must always be up to date.** Before completing your work:
1. Update the "Implementation Summary" section
2. Mark completed phases/tasks
3. Document what's remaining
4. Update decisions.md with any architectural choices you made
5. Add notes.md entries for gotchas future agents should know

### When You're Stuck

**Think first, don't guess.** Spend more time researching and understanding, less time trying random things.

1. **Use `(search "query")` via REPL** - This is your best research tool:
   ```bash
   clj-nrepl-eval -p 7888 "(search \"How to do X in XTDB v2\")"
   ```
   This gives you web-grounded, current information with source citations. Use it liberally.

2. Read more context - often the answer is in adjacent files
3. Check reference docs - see Documentation Reference section below
4. Invoke relevant skills - they contain project-specific knowledge
5. **For XTDB issues** - We have the full source code at `reference-code/xtdb/`. Read it.
6. Try a different approach - PRDs describe goals, not exact implementations
7. Document what you tried in notes.md - so the next agent doesn't repeat it

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
clj-nrepl-eval -p 7888 "(status)"                   # Check status
clj-nrepl-eval -p 7888 "(search \"XTDB SQL syntax\")" # Research when stuck
```

### REPL Helper Functions

The `user` namespace (loaded automatically) provides these helpers:

| Function | Purpose |
|----------|---------|
| `(go)` | Start system |
| `(halt)` | Stop system |
| `(reset)` | Reload code and restart |
| `(status)` | Show system status |
| `(search "query")` | Web search via Gemini (use when stuck!) |
| `(ask "query")` | Ask Gemini (no web search) |
| `(xtdb-node)` | Get XTDB node |
| `(db-reset!)` | Delete all data and restart fresh |

**Location:** `env/dev/clj/user.clj` (NOT `dev/user.clj`)

### If You Need to Restart

```bash
# Ctrl+C the running ./bin/run, then:
./bin/run
```

---

## Critical Rules

1. **Invoke skills before searching** - Check if a skill applies before manually grepping/reading
2. **Use SQL for XTDB** - We've moved to SQL syntax (not XTQL). See `docs/reference/xtdb-v2-reference.md`
3. **Write tests** - All code changes require appropriate tests
4. **REPL-driven development** - Verify changes work via REPL before declaring done
5. **PRDs may be wrong** - Understand what you're building, don't blindly follow specs
6. **Domain isolation** - Domains communicate through core protocols, not direct calls
7. **Research before guessing** - Use `gemini/search` via REPL when stuck. Don't try random things.

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

### Foreground Agents Only
**Never use background agents (`run_in_background: true`).** Background agents:
- Have restricted permissions and can't edit files normally
- Resort to workarounds like `cat >> file` instead of proper Edit/Write tools
- Can't be interacted with by the user during execution
- Are harder to debug when something goes wrong

Always use sequential foreground agents. Wait for each to complete before launching the next. This ensures full-powered agents with proper tool access.

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

See the Orchestrator and Agent Responsibilities sections above for detailed guidance.

**Subagent types:**

| Type | Write Access | Use For |
|------|--------------|---------|
| `general-purpose` | **YES** | Research that writes findings, implementation work |
| `Explore` | **NO** (read-only) | Quick searches, finding files, answering questions |
| `Plan` | **YES** | Architecture planning |

**Never use background agents** (`run_in_background: true`) - they have restricted permissions.

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

**We use SQL syntax** - it's more stable than XTQL and better documented.

```clojure
(require '[seon.db.node :as node])

;; CORRECT - use SQL with our wrapper
(node/q (xtdb-node) "SELECT ticker, iv FROM option_greeks WHERE ticker = ?" ["SPY"])

;; CORRECT - simple queries
(node/q (xtdb-node) "SELECT * FROM trades ORDER BY timestamp DESC LIMIT 10")
```

**Key resources:**
- `docs/reference/xtdb-v2-reference.md` - Our SQL patterns and examples
- `reference-code/xtdb/` - **Full XTDB source code** - read this when stuck!
- Use `gemini/search` for XTDB questions - it has current docs

**When you hit XTDB issues:** Don't guess. Read the source code in `reference-code/xtdb/` or use `gemini/search` to get current documentation. XTDB v2 is different from v1.

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
| `CONVENTIONS.md` | Malli schema patterns, public API design |
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

### File Organization

Keep it simple - one file per namespace:

```
src/seon/
├── ai/
│   └── gemini.clj        ; seon.ai.gemini (schemas + API in one file)
├── trading/
│   └── signals.clj       ; seon.trading.signals
└── polymarket/
    └── api.clj           ; seon.polymarket.api
```

Don't spread code across multiple files (core.clj, schema.clj, etc.) prematurely. Keep schemas with their functions until a file gets unwieldy.

### Domain Principles
1. **Self-contained** - Each namespace can function independently
2. **DB parameter** - Functions receive `db` as first parameter (no globals)
3. **Schema-first** - Define Malli schemas before implementation (see `CONVENTIONS.md`)
4. **Temporal** - Leverage XTDB's bitemporal capabilities
5. **Testable** - Tests in `test/` mirror `src/` structure

### Entity ID Conventions
- Use namespaced keywords: `:trading/position`, `:health/workout`
- Include domain prefix in xt/id: `{:xt/id :trading/position-123}`

---

## Project Tracking

- **PLAN.md** - Transformation roadmap (ml-options → Seon)
- **PRDs** in `docs/prds/{feature}/prd.md` - Feature specifications
- **Original project** at `~/src/ml-options-trading` for reference

---

## Dev Hook (bin/seon-hook)

The project uses a unified Babashka hook script for code review and test automation.

### What It Does

After file edits, the hook:
1. **Detects changed namespaces** from edited files
2. **Runs targeted tests** for affected namespaces
3. **Sends code to Gemini** for AI review (via native API integration)

### Configuration

Hook config in `.claude/seon-hook.edn`:

```clojure
{:gemini {:review-enabled true}     ; Enable/disable Gemini review
 :testing {:auto-run true           ; Run tests automatically
           :focus-ns nil}}          ; Focus on specific namespace
```

### Hook Internals

- **Script**: `bin/seon-hook` (Babashka)
- **State**: `.claude/test-hook.db` (SQLite for tracking)
- **Gemini**: Uses `seon.ai.gemini` namespace for API calls

### Gemini API Pattern

The Gemini client provides multiple functions for different use cases:

```clojure
(require '[seon.ai.gemini :as gemini])

;; Simple question/answer
(gemini/ask {::gemini/prompt "Explain XTDB temporal queries"})

;; Web search with Google grounding (use for current info, verification)
(gemini/search {::gemini/prompt "Latest Clojure 1.12 features"})
;; Returns ::grounding-metadata with source URLs

;; Python code execution (calculations, data processing)
(gemini/calculate {::gemini/prompt "What is the 100th Fibonacci number?"})

;; Code review with structured output
(gemini/review-code {::gemini/prompt "Review this function"
                     ::gemini/code "(defn foo [x] ...)"})
```

**For agents doing research:** Use `gemini/search` to verify knowledge that may be out of date. It returns web-grounded responses with source citations.

See `CONVENTIONS.md` for the full schema pattern.
