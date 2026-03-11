---
type: prd
status: draft
tags: [prd]
---
# PRD: Logging System - Agent-Optimized Functions + Web UI

## Goals

Build two things:

1. **REPL log functions** that are safe for AI agents (won't blow context window)
2. **Web UI log viewer** with live updates (tests our Datastar/SSE patterns)

---

## Resources to Learn From

| Resource | What's There |
|----------|--------------|
| `docs/DATASTAR_QUICK_REF.md` | SSE/Datastar patterns for web UI |
| `docs/DATASTAR_EXTENDED_PATTERNS.md` | Advanced patterns, streaming logs section |
| `docs/logging-setup.md` | Current log file structure |
| `reference-code/hyperlith/` | Original SSE examples |
| `reference-code/sail/` | Tailwind CSS for Clojure (evaluate if useful) |
| `src/ml_options/web/` | Current SSE implementation to build on |

---

## Part 1: Agent-Safe Log Functions

### The Problem

Current `(logs)` and `(log-summary)` in `env/dev/clj/user.clj` are dangerous:

- Can return thousands of lines, blowing AI agent context
- Print strings instead of returning data structures
- No way to get context around a specific line number

### Goals

Create new log query functions that:

- **Never return more than ~100 lines** (hard cap, silent truncation)
- **Return data structures** (maps, vectors) not printed strings
- **Prioritize errors** - quick way to see "what went wrong"
- **Support context lookup** - "show me 5 lines around line 1056"

### Suggested Functions (adapt as needed)

```clojure
(log-health)     ; Quick status: error count, last error, warnings
(log-errors)     ; Recent errors with surrounding context
(log-context n)  ; Lines around line number n
(log-tail)       ; Safe tail with hard caps
```

### Constraints

- Add to `env/dev/clj/user.clj` (dev-only tooling)
- Parse the logback format: `2025-12-02 11:39:25,396 [main] INFO  ml-options.core - Message`
- Hard cap all output to prevent context blowout

---

## Part 2: Web UI Log Viewer

### Goals

Create a `/logs` page that:

- Shows live log updates (SSE streaming)
- Lets user filter by log level
- Has a terminal-style dark UI
- Auto-scrolls to bottom (with pause option)

### Constraints

- Must use our existing SSE infrastructure (`ml-options.web.sse`)
- Should follow Datastar patterns in our docs
- Needs CSS - evaluate Tailwind options (Sail, CLI, CDN) and pick what works

### CSS Note

We want Tailwind. Options to evaluate:

- `reference-code/sail/` - Clojure-based approach (2 years old, may need updating)
- Tailwind CLI with watch
- CDN for quick prototyping

Pick what works best. Document your choice in `docs/css-setup.md`.

---

## Success Criteria

1. **Log functions work**: `(log-health)` returns useful status in <20 lines
2. **Context is safe**: Can't accidentally return 10k lines
3. **Live UI works**: Opening `/logs` shows streaming log updates
4. **Filtering works**: Can filter to errors-only in the UI
5. **CSS is set up**: Tailwind or equivalent working, documented

---

## Deliverables

- New log functions in `env/dev/clj/user.clj`
- New web UI at `/logs` route
- CSS setup documented in `docs/css-setup.md`
- Tests for log parsing functions
