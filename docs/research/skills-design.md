# Skills-Based Documentation System Design

## Overview

Skills provide domain-specific documentation that loads automatically when Claude determines the context is relevant. The key is the `description` field - Claude uses this to decide when to invoke the skill.

## Proposed Skills

### 1. datastar-web-ui

**Trigger contexts:**
- Web UI work, SSE, streaming
- Datastar attributes, signals
- Hyperlith patterns, brotli compression
- HTML rendering, Hiccup

**SKILL.md description:**
```yaml
---
name: datastar-web-ui
description: "Use when working on web UI, SSE streaming, Datastar attributes, HTML rendering, or Hyperlith patterns. Covers frontend patterns, component structure, and real-time updates."
---
```

**Docs to include:**
- `datastar-quick-ref.md` - Core patterns (inline or import)
- `datastar-extended-patterns.md` - Advanced patterns (reference)
- `hyperlith-patterns.md` - Architecture reference

### 2. xtdb-queries

**Trigger contexts:**
- Database queries, XTQL
- Data retrieval, aggregation
- Temporal queries, valid-time
- Schema design

**SKILL.md description:**
```yaml
---
name: xtdb-queries
description: "Use when writing XTDB queries, working with the database, or designing data schemas. Covers XTQL syntax, gotchas, and temporal query patterns."
---
```

**Docs to include:**
- `xtdb-v2-reference.md` - Core reference (inline critical parts)
- Key gotchas (always show)

### 3. data-import

**Trigger contexts:**
- Loading options data
- ThetaData API
- OCC symbol format
- Bulk ingestion

**SKILL.md description:**
```yaml
---
name: data-import
description: "Use when loading options data, working with ThetaData API, parsing OCC symbols, or running bulk imports. Covers the data ingestion pipeline and API usage."
---
```

**Docs to include:**
- `thetadata-v3-api.md` - API reference
- `data-ingestion.md` - Pipeline overview

---

## Directory Structure

```
.claude/
└── skills/
    ├── datastar-web-ui/
    │   ├── SKILL.md              # Main skill definition
    │   └── patterns.md           # Extended patterns (optional load)
    ├── xtdb-queries/
    │   └── SKILL.md              # Includes critical gotchas inline
    └── data-import/
        └── SKILL.md              # API and pipeline overview
```

---

## SKILL.md Design Philosophy

### What goes in SKILL.md (loaded when skill triggers):
- Most critical patterns and gotchas
- Quick reference tables
- Common code examples
- Links to detailed docs for further reading

### What stays in docs/ (loaded on demand):
- Detailed reference material
- Extended patterns
- Historical research

### Size Guidelines:
- SKILL.md should be scannable (~200-400 lines)
- Include the 20% of info that covers 80% of use cases
- Link to detailed docs for edge cases

---

## Draft SKILL.md Files

### datastar-web-ui/SKILL.md

```yaml
---
name: datastar-web-ui
description: "Use when working on web UI, SSE streaming, Datastar attributes, HTML rendering, or Hyperlith patterns. Covers frontend patterns, component structure, and real-time updates."
---
```

# Datastar Web UI Patterns

## Core Pattern: View = f(State)

```clojure
;; State atom with SSE refresh watcher
(defonce app-state (atom {:data nil}))

(add-watch app-state :sse-refresh
  (fn [_ _ old new]
    (when (not= old new)
      (sse/refresh-all!))))
```

## Key Datastar Attributes

| Attribute | Purpose | Example |
|-----------|---------|---------|
| `data-signals` | Declare reactive state | `{:count 0}` |
| `data-text` | Bind text content | `"$count"` |
| `data-on-click` | Handle clicks | `"$count++"` |
| `data-indicator` | Show during requests | `"#loading"` |

## SSE Response Pattern

```clojure
(defn sse-handler [request]
  (sse/streaming-response request
    (fn [send!]
      (send! (sse/merge-fragment (render-view @app-state))))))
```

## Files for Detailed Reference
- `docs/reference/datastar-quick-ref.md` - Complete attribute reference
- `docs/reference/datastar-extended-patterns.md` - Charts, modals, auth patterns
- `docs/reference/hyperlith-patterns.md` - Brotli compression, architecture

---

### xtdb-queries/SKILL.md

```yaml
---
name: xtdb-queries
description: "Use when writing XTDB queries, working with the database, or designing data schemas. Covers XTQL syntax, gotchas, and temporal query patterns."
---
```

# XTDB Query Patterns

## CRITICAL GOTCHAS

1. **Never use `[*]`** - Returns empty maps. Always list columns explicitly.
2. **Use native XTQL** - Never raw SQL strings.
3. **Use `xt/template`** for dynamic values - Not string interpolation.

## Correct Query Pattern

```clojure
(require '[ml-options.db.node :as node])
(require '[xtdb.api :as xt])

;; Simple query - list columns explicitly
(node/query (xtdb-node)
  '(from :option-greeks [asset/ticker quote/iv]))

;; With dynamic values - use xt/template
(node/query (xtdb-node)
  (xt/template
    (from :option-greeks [{:asset/ticker ~ticker} quote/iv])))
```

## Common Operations

```clojure
;; Aggregation
(-> (from :option-greeks [asset/ticker xt/id])
    (aggregate {:cnt (count xt/id)} asset/ticker))

;; Filtering
(-> (from :option-greeks [{:asset/ticker "AAPL"} quote/iv])
    (where (> quote/iv 0.3)))

;; Ordering
(-> (from :option-greeks [asset/ticker quote/iv])
    (order-by [[:quote/iv :desc]]))
```

## Files for Detailed Reference
- `docs/reference/xtdb-v2-reference.md` - Full XTQL reference, temporal queries

---

### data-import/SKILL.md

```yaml
---
name: data-import
description: "Use when loading options data, working with ThetaData API, parsing OCC symbols, or running bulk imports. Covers the data ingestion pipeline and API usage."
---
```

# Data Import Patterns

## Quick Start

```bash
# Start ThetaData Terminal (required for data loading)
./bin/thetadata

# Start import via HTTP API
curl -X POST http://localhost:8080/api/import/start \
  -H "Content-Type: application/json" \
  -d '{"symbols": "AAPL", "startDate": "2024-01-01", "endDate": "2024-12-31"}'

# Check status
curl http://localhost:8080/api/import/status | jq '.current.status'
```

## OCC Symbol Format

```
AAPL  240119C00150000
│     │     │  │
│     │     │  └── Strike price × 1000 (150.00)
│     │     └───── Call (C) or Put (P)
│     └─────────── Expiration YYMMDD
└───────────────── Underlying symbol (padded to 6 chars)
```

## Key Points

- **Weekends auto-skipped** - `plan-daily-work` skips Sat/Sun
- **Resumable** - Re-running same date range skips completed dates
- **Progress tracking** - `:bulk-progress` table tracks completion
- **ThetaData Terminal required** - Must be running on port 25503

## Files for Detailed Reference
- `docs/reference/thetadata-v3-api.md` - Full API reference
- `docs/reference/data-ingestion.md` - Pipeline architecture

---

## Updated CLAUDE.md Structure

With skills handling domain-specific docs, CLAUDE.md becomes leaner:

```markdown
# ML Options Trading - Claude Code Instructions

## Quick Start
(keep as-is - essential)

## Critical Rules
(keep as-is - essential)

## Agent Principles
(keep as-is - essential)

## System Lifecycle
(keep as-is - essential)

## Documentation
Skills auto-load relevant docs based on context:
- **datastar-web-ui** - Web UI, SSE, Hyperlith patterns
- **xtdb-queries** - Database queries, XTQL syntax
- **data-import** - ThetaData API, ingestion pipeline

For detailed references, see `docs/reference/`.

## Testing
(keep as-is)

## Project Tracking
(keep as-is)
```

---

## Implementation Steps

1. Create `.claude/skills/` directory structure
2. Write SKILL.md files with critical patterns inline
3. Move detailed docs to `docs/reference/`
4. Slim down CLAUDE.md documentation table
5. Test skill triggering with sample prompts
6. Verify subagent behavior

---

## Questions to Resolve

1. **Skill size**: How much to inline vs. reference?
2. **Subagent inheritance**: Do subagents get skills?
3. **Multiple skills**: Can multiple skills trigger at once?
4. **Testing**: How to verify skills are triggering correctly?
