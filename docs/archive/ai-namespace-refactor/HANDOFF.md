# Session Handoff: AI Namespace Refactor

**Date:** 2026-01-19
**Context:** Continuing from bidirectional-control exploration

---

## Where We Are

We explored the bidirectional-control PRD and ended up designing a new schema architecture. **PRDs are written - time to execute, not explore.**

---

## PRD Status

| PRD | Status | Location |
|-----|--------|----------|
| Bidirectional Control | Phase 1 done, paused | `docs/prds/clojure-claude-sdk/bidirectional-control.md` |
| Schema Research | Complete (research only) | `docs/prds/clojure-claude-sdk/schema-research.md` |
| **AI Namespace Refactor** | **Ready - Phase 1** | `docs/prds/ai-namespace-refactor/prd.md` |

---

## What Exists (Exploratory Code - Needs Refactoring)

| File | What It Does | Fate |
|------|--------------|------|
| `src/seon/claude/sdk.clj` | Agent launch, observatory (`agents`, `tail`, `interrupt!`) | Move to `seon.ai.claude` |
| `src/seon/claude/conversation.clj` | Conversation persistence (Claude-specific schemas) | Replace with `seon.ai` |
| `src/seon/claude/exploration.clj` | Protocol research tools | Keep as dev tool |
| `src/seon/orchestrator/session.clj` | Session isolation (REPL + XTDB per agent) | Keep as-is |

---

## The Design Decision

**Problem:** `seon.claude.*` namespaces have Claude-specific schemas that should be generic.

**Solution:** New namespace hierarchy:

```
seon.ai              ; Generic schemas (role, content, tokens, cost)
seon.ai.claude       ; Claude-specific (model, cache-tokens, tool-calls)

```

Entities can have attributes from both namespaces on the same entity - that's the XTDB/Datomic way.

---

## Your Task: Execute Phase 1

**Read the full PRD first:** `docs/prds/ai-namespace-refactor/prd.md`

### Phase 1 Deliverable

Create `src/seon/ai.clj` with:

1. **Schema registrations** for generic AI attributes:
   - `::type`, `::role`, `::content`, `::timestamp`, `::session-id`
   - `::status`, `::input-tokens`, `::output-tokens`, `::cost-usd`
   - `::namespace`, `::prompt`, `::error`

2. **Domain functions** (use `seon.db.node` for XTDB ops):
   - `start-session!` - Create AI session
   - `end-session!` - Close session with stats
   - `add-message!` - Add message to session
   - `get-session` - Get session by ID
   - `get-messages` - Get messages for session
   - `list-sessions` - List recent sessions

3. **Tests:**
   - Generate sample data with `mg/generate`
   - Round-trip: start-session -> add-message -> get-messages -> validate

**Commit:** `feat: add seon.ai base namespace with session/message functions`

---

## Do NOT

- Start more exploratory work or new PRDs
- Refactor `seon.ai.gemini` (that's Phase 6)
- Try to do all phases at once
- Skip writing tests

---

## After Phase 1

Move to Phase 2: Create `src/seon/ai/claude.clj` that extends the base schemas.

Each phase = one agent task = one commit.
