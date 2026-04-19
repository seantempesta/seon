---
type: prd
status: completed
tags: [prd, archive]
---

# Handoff: Complete Unified Dev Hook (Phases 3-5)

## Context

The unified dev hook (`bin/seon-hook`) is mostly complete. Phases 1-2 are done:
- Hook runs syntax repair, unit tests, generative tests, Gemini review
- Blocks on real test failures with helpful instructions
- Continues with warnings on infrastructure failures
- Gemini reviews are plain text (simplified from structured JSON)

## What's Remaining

### Phase 3: Optimize Caching Structure

**Goal:** Reduce Gemini API costs by 70-80% using implicit caching

**Files:** `src/seon/ai/gemini.clj`, `bin/seon-hook`

**Tasks:**
1. Move CONVENTIONS.md into system instruction (static, cached by Gemini)
2. Structure requests: `[system: conventions] → [user: code + test results]`
3. Add logging for `cachedContentTokenCount` from response metadata
4. Verify cache hits in `.claude/seon-hook.log`

**Research:** Use `gemini/search` to verify Gemini implicit caching behavior:

```clojure
(gemini/search {::gemini/prompt "Gemini 2.5 implicit caching requirements"})

```

### Phase 4: Helpful Error Context

**Goal:** All warnings include actionable fix hints

**Files:** `bin/seon-hook`

**Current warnings are informational but not actionable. Improve to:**

```
⚠ nREPL unavailable - restart server with ./bin/run
⚠ Namespace reload timed out - check for infinite loops at load time
⚠ Tests failed to parse - check test output format
⚠ Gemini timed out - will retry on next edit

```

### Phase 5: Configuration Cleanup

**Goal:** Make settings configurable

**Files:** `src/seon/dev/feedback.clj`, `.claude/seon-hook.edn`

**Tasks:**
1. Make debounce-seconds configurable (currently hardcoded 30s in feedback.clj line 421)
2. Add `log-tokens` toggle to config
3. Read config values in feedback.clj instead of using constants

## Key Files

| File | Purpose |
|------|---------|
| `bin/seon-hook` | Babashka hook script |
| `src/seon/ai/gemini.clj` | Gemini client |
| `src/seon/dev/feedback.clj` | REPL-side utilities |
| `.claude/seon-hook.edn` | Configuration |
| `docs/prds/unified-dev-hook/prd.md` | Full PRD with details |
| `/Users/sean/.claude/plans/crispy-mixing-kay.md` | Detailed implementation plan |

## Testing

```bash
# Verify hook works
echo '{"hook_event_name":"PostToolUse","tool_name":"Edit","tool_input":{"file_path":"/Users/sean/src/seon/src/seon/schema.clj"}}' | bb bin/seon-hook

# Test Gemini search (web grounding for research)
clj-nrepl-eval -p 7888 "(require '[seon.ai.gemini :as g])"
clj-nrepl-eval -p 7888 "(g/search {::g/prompt \"Your query here\"})"

```

## Process

1. **Work phase by phase** - Commit after each phase
2. **Verify after each change** - Run the hook test command
3. **Use subagents** - For focused implementation tasks
4. **Use `gemini/search`** - For web research when knowledge might be outdated

## Current Branch

`feature/unified-dev-hook` - All work continues here
