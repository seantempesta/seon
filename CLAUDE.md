# Seon — Shared Instructions

**Every Claude instance reads this file** — orchestrator, seon agents, and Claude Code subagents. Keep it universal. Role-specific instructions live in `ORCHESTRATOR.md` and `AGENT.md`.

## Agent Model Policy

**Never use haiku for coding tasks.** Only use haiku for quick file reads or context gathering. All implementation, bug fixes, and verification that involves writing code must use opus 4.6 (default model).

---

## What is Seon?

**Seon** - from the archaic "to see", and inspired by the Seons of Brandon Sanderson's *Elantris*: sentient, luminous beings that serve and assist their bonded humans.

Seon is **infrastructure for AI agents to write reliable software**.

The personal domains (trading, health, finance) are test cases, not the point. The real product is a codebase architecture where AI agents can own and evolve code responsibly - with contracts they can discover, history they can learn from, and isolation that prevents conflicts.

### Core Infrastructure

- **Datalevin** - Embedded Datalog database on LMDB. EAV datoms, Datomic-compatible queries, ACID transactions.
- **Malli** - Schema validation, generative testing, function contracts. The type system agents actually use.
- **Integrant** - Component lifecycle. Clean start/stop semantics for the whole system.
- **Datastar/SSE** - Real-time UI updates. Agents can see their work reflected immediately.

### Why Clojure?

- **Stable APIs** - 10-year-old documentation is still valid. Agents don't need to track API churn.
- **Data as interface** - Maps in, maps out. No hidden object state to reason about.
- **Homoiconicity** - Code is data. Agents can manipulate programs as data structures.
- **REPL-driven** - Interactive development matches how agents work (try something, see result, iterate).
- **Immutable by default** - No spooky action at a distance. Function outputs depend only on inputs.

---

## Slow Is Fast

**Your default training rewards task completion. Override that instinct.** Charging forward and declaring victory is worse than pausing to verify. Three agents "fixing" the same bug is more expensive than one agent understanding the problem first.

### Before writing code:

1. **Observe the live system.** Query the REPL. Establish current state with actual data, not assumptions.
2. **Define what failure looks like.** If you can't articulate how you'd know your change is broken, you don't understand the problem well enough to fix it.
3. **Read the source.** Read the existing code you're modifying. Read library source in `reference-code/` (Datalevin, Malli, Integrant, core.async — they're all there). Agents that guess instead of reading produce confident, wrong answers.
4. **Test assumptions in the REPL.** Before building a function that queries the graph, try the query manually. Before wrapping a library call, call it directly and see what it returns. A 30-second experiment prevents hours of debugging.

### After writing code:

5. **Verify in the REPL, not just with tests.** Tests passing is necessary but not sufficient. Query the live system and confirm the actual state matches your intent.
6. **Falsify, don't confirm.** Don't ask "does my change work?" Ask "how would I know if my change is broken?" Then check for that.

**The REPL is the oracle.** Not the code, not the tests, not the docs. The running system tells you the truth. Every claim should be verifiable with a REPL expression.

**Honesty is paramount.** It is far worse to hide remaining work than to report it. Never mark a task as "done" if there are known issues. Report what's actually working, what's broken, and what's left.

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
│   ├── datalevin/            ; Datalevin source (read when stuck)
│   ├── malli/                ; Malli source
│   ├── integrant/            ; Integrant source
│   └── core.async/           ; core.async + flow source
└── docs/
    ├── prds/                 ; Feature specifications
    └── reference/            ; Technical reference docs
```

---

## Skills (IMPORTANT)

**ALWAYS invoke the relevant skill FIRST** before searching, grepping, or trial-and-error. Skills encode project-specific knowledge that saves significant time.

| Skill | Invoke When |
|-------|-------------|
| `/datalevin` | Writing Datalog queries, transacting data, debugging empty results, working with `d/q` |
| `/datastar-web-ui` | SSE handlers, `data-*` attributes, streaming responses |
| `/browser-automation` | Testing UI in browser, debugging frontend issues |
| `/clojure-testing` | **Running tests**, test failures, kaocha, mocking, generators |

---

## Editing Tools

| Tool | Best For | Validation |
|------|----------|------------|
| `Edit` | Small, exact string replacements | Syntax (non-seon) or full lint (seon) |
| `Write` | New files, complete rewrites | Syntax (non-seon) or full lint (seon) |
| `clojure_replace` (MCP) | Clojure code changes | Full clj-kondo + tests + review |

**Prefer `clojure_replace` for Clojure** — whitespace-insensitive, structural matching, full lint before write.

All Clojure edits are validated:
- **PreToolUse:** Fast syntax check for non-seon files (~1ms), full clj-kondo for seon files (~50-100ms)
- **PostToolUse:** Full pipeline for seon files (repair, reload, tests, review)

Errors include "Did you mean?" suggestions for undefined symbols. If your edit is blocked, the error message tells you exactly what's wrong — read it.

### Code Smell: Functions Too Complex to Edit

If you repeatedly fail to edit a function, **the function is too complex**. Refactor it. Keep functions small and composable. If a function needs a comment explaining a section, that section should be its own function. **If an AI can't edit your code, it's too complex for humans too.**

---

## Dev Hook

After every Edit/Write, the hook automatically:
- Reloads code into the running server
- Runs tests for affected namespaces
- Validates schemas via generative testing
- Provides Gemini AI review

Config in `.claude/seon-hook.edn`. Hook blocks if tests fail.

**Read hook feedback.** The hook provides convention violations, lint warnings, and AI review after every edit. This feedback is good — it catches real problems. Don't skip it to move faster. Fix warnings, use existing aliases, address review concerns before moving on. Ignoring feedback to "save time" creates debt that costs more later.

---

## Code Reloading

The dev hook handles reloading automatically. You rarely need to reload manually.

```clojure
(user/reload)  ; Fast reload via clj-reload
(user/reset)   ; Full Integrant restart — use when changing config/components
(user/status)  ; Check system health
```

**Avoid** `(require 'ns :reload)` — it bypasses proper cleanup.

**If something breaks:**
1. **Observe first.** `(user/status)`, check logs, query the REPL. Understand what's broken and WHY.
2. **Diagnose the root cause.** Don't fix symptoms — find the disease.
3. Try `(user/reload)` — often fixes code-level issues.
4. Try `(user/reset)` — clean Integrant restart. Note: `resume-key` may preserve old state.
5. **Last resort only:** `pkill -9 -f "java.*seon" && ./bin/run` — document WHY.

---

## UI Development

Seon uses a **Phosphor Terminal** theme - warm blacks, cream text, amber accents. Think Lisp machine, not generic web app.

### Before Writing UI Code

1. **Read the design system:** `docs/prds/namespace-ui/design-system.md`
2. **Use the component library:** `src/seon/web/components.clj`
3. **Invoke skills:** `/datastar-web-ui` for SSE patterns, `/browser-automation` to test

### Key Rules

- **Density over whitespace** - `p-3` not `p-6`, `gap-4` not `gap-6`
- **Small text** - `text-xs` (11px) primary, `text-lg` max for titles
- **Warm colors** - `bg-base-*`, `text-text-*`, never `bg-white` or `text-zinc-*`
- **Dot+text status** - `● running` not pill badges
- **Monospace everywhere** - `font-mono` on body

---

## Domain Guidelines

1. **One file per namespace** - Don't split prematurely
2. **DB parameter** - Functions receive `db` as first parameter
3. **Schema-first** - Define Malli schemas before implementation
4. **Namespaced IDs** - `:trading/position`, `:health/workout`

See `CONVENTIONS.md` for full patterns.

---

## File Locations

**Never use `/tmp` or system temp directories.** Use project-local directories:

| Directory | Purpose | Git Status |
|-----------|---------|------------|
| `logs/` | Debug logs, hook logs, agent activity | Ignored |
| `tmp/` | Temporary test files, scratch data | Ignored |
| `data/` | Datalevin database files | Ignored |

---

## Logging

Seon uses **Timbre** for application logging and **logback** for library logs (SLF4J).

| File | Contents |
|------|----------|
| `logs/app.log` | All application log lines (Timbre) |
| `logs/startup.log` | Boot sequence only (wiped on startup) |
| `logs/error.log` | Errors only (logback/library) |
| `logs/lib.log` | Library logs (SLF4J) |

---

## Key Documents

| Document | Purpose |
|----------|---------|
| `VISION.md` | Full thesis, architecture layers, progress tracking |
| `CONVENTIONS.md` | Malli schemas, API design patterns |
| `ORCHESTRATOR.md` | Orchestrator-specific instructions (launching agents, system management) |
| `AGENT.md` | Subagent-specific instructions (investigation workflow, reporting) |
| `docs/reference/datastar-quick-reference.md` | Web UI attributes |
| `docs/prds/namespace-ui/design-system.md` | UI colors, typography, spacing |
