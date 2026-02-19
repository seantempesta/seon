# Session Transfer: Dynamic Context & Flow Architecture

**Date**: 2025-01-30
**Context Used**: ~190k tokens
**Transfer To**: New orchestrator session

## What Was Accomplished This Session

### 1. CRITICAL BUG FIX: "100 Message Limit"

**Root Cause**: NOT Claude Code's turn limit. It was our `(chan 100)` buffer filling up and blocking the reader loop with `>!!`.

**Fix Applied** (`src/seon/ai/claude.clj:685`):
```clojure
;; Before (blocked at 100 messages):
messages-ch (chan 100)

;; After (never blocks, drops oldest if full):
messages-ch (chan (async/sliding-buffer 1000))
```

**Status**: DEPLOYED. Tested. Agents can run indefinitely now.

### 2. Eliza Analysis

Added `reference-code/eliza` (git clone, not submodule due to gitignore).
Analysis written to `docs/research/eliza-analysis.md`.

**Key Finding**: Eliza solves conversational agents (chatbots), not code-writing agents. Worth borrowing: plugin dependency resolution, typed events, service lifecycle. Not applicable: character systems, memory embeddings.

### 3. core.async.flow Research

Rich Hickey's new dataflow system (April 2025). Three research documents created:

| Document | Purpose |
|----------|---------|
| `docs/prds/dynamic-context/async-flow-research.md` | Initial analysis |
| `docs/architecture/flow-foundation.md` | Full architectural proposal |
| `docs/prds/namespace-ui/sse-flow-solution.md` | Specific solution for SSE |

**Key Findings**:
- Flow is for internal orchestration, NOT external process management
- Claude CLI processes can't be Flow processes (opaque state)
- Flow IS right for: SSE streaming, status aggregation, API rate limiting
- Our sliding-buffer fix aligns with Flow's patterns

### 4. SSE Live Reload Solution

**Problem**: Not hot reload (already fixed via `after-ns-reload` hooks). The real gaps:
- No centralized event routing (broadcasts to ALL clients)
- No observability (can't see connected clients)
- Polling as primary mechanism (2s latency)
- No debouncing

**Solution**: Flow-based SSE infrastructure with 3 processes:
- `:aggregator` - debounces rapid changes
- `:registry` - tracks connected clients
- `:broadcaster` - fans out to relevant clients

Full design with Malli schemas in `docs/prds/namespace-ui/sse-flow-solution.md`.

### 5. Dynamic Context Injection (NOT YET TESTED)

Research found Claude Code supports `type: "system"` messages via stdin:
```json
{"type": "system", "content": "...", "session_id": ""}
```

This could enable per-turn context injection ("cockpit" model). BUT:
- Not tested whether it REPLACES or APPENDS to context
- Not tested if it works mid-conversation
- PRD created at `docs/prds/dynamic-context/prd.md`

## Files Changed/Created This Session

### Changed
- `src/seon/ai/claude.clj` - Channel buffer fix (line 685)
- `.gitignore` - Removed `reference-code/` ignore (allows submodules)
- `docs/prds/dynamic-context/prd.md` - Updated with findings

### Created
- `docs/research/eliza-analysis.md`
- `docs/prds/dynamic-context/async-flow-research.md`
- `docs/prds/dynamic-context/research-findings.md` (if agent wrote it)
- `docs/architecture/flow-foundation.md`
- `docs/prds/namespace-ui/sse-flow-solution.md`
- `reference-code/eliza/` (cloned repo)

## Immediate Next Steps

### Priority 1: Implement SSE Flow Solution
The design is complete. Implementation phases:
1. Create `src/seon/web/sse/flow.clj` with schemas
2. Hook into clj-reload
3. Replace `refresh-all!` with `emit-change!`

Launch agent:
```clojure
(user/launch-agent!! 'seon.web.sse.flow
  "Read docs/prds/namespace-ui/sse-flow-solution.md and implement Phase 1.
   Create src/seon/web/sse/flow.clj with the Malli schemas and step functions.
   Write tests. Do NOT modify existing SSE code yet.")
```

### Priority 2: Test Context Injection
Test if `type: "system"` messages work:
```clojure
(user/launch-agent!! 'seon.research.context-injection
  "Read docs/prds/dynamic-context/prd.md Phase 0.
   Test system message injection with unique markers.
   Verify replacement vs append behavior.
   Write findings to docs/prds/dynamic-context/research-findings.md")
```

### Priority 3: Commit Changes
The channel fix should be committed:
```bash
git add src/seon/ai/claude.clj
git commit -m "fix(claude): use sliding-buffer to prevent channel deadlock

The 100 message 'limit' was our channel buffer filling up.
Changed from (chan 100) to (chan (sliding-buffer 1000)).

Co-Authored-By: Claude <noreply@anthropic.com>"
```

## Key Architectural Decisions

1. **Flow for internal orchestration only** - NOT for Claude process management
2. **Adapters bridge external to Flow** - Claude CLI wrapped, communicates via channels
3. **XTDB remains persistence layer** - Flow state is in-memory
4. **Malli schemas everywhere** - All Flow data has schemas
5. **Selective adoption** - Use Flow where it fits, don't force it

## Open Questions

1. Does `type: "system"` replace or append context?
2. Can we monitor Claude Code's context window size?
3. Should we use flow-monitor for Observatory integration?
4. What's the right buffer size for production? (1000 is arbitrary)

## Reference Commands

```clojure
;; Check running agents
(user/agents)

;; Launch agent and wait
(user/launch-agent!! 'ns "prompt")

;; Reload code
(user/reload)

;; Search with Gemini
(user/search "query")
```

## Transfer Prompt for New Session

Copy this to continue:

---

**Continue the Seon dynamic context and Flow architecture work.**

Key context:
1. We fixed the "100 message limit" bug - it was our channel buffer, not Claude. Fix is in `src/seon/ai/claude.clj:685`.

2. We have detailed plans for:
   - SSE Flow solution: `docs/prds/namespace-ui/sse-flow-solution.md`
   - Flow architecture: `docs/architecture/flow-foundation.md`
   - Dynamic context injection: `docs/prds/dynamic-context/prd.md`

3. Next steps:
   - Implement SSE Flow (Phase 1)
   - Test context injection
   - Commit the channel fix

Read the PRDs and continue. Start with the SSE Flow implementation since it solves a real problem blocking development.

---
