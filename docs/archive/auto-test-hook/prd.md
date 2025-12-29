# PRD: Automatic Test Running on Clojure File Edits

**Status:** Implementation Complete - Testing in Progress
**Priority:** Medium
**Branch:** `feature/auto-test-on-edit`

---

## Goals

1. **Automatic test feedback** - When Claude edits Clojure files, relevant tests run automatically without manual invocation
2. **Non-intrusive workflow** - Don't block or interrupt multi-file edit sessions; provide async feedback
3. **Right-sized context** - Give agents enough info to debug failures without overwhelming the context window
4. **Fast feedback loop** - Tests should run quickly enough to be useful (target: <5 seconds for affected tests)

---

## Problem Statement

Currently, Claude agents must explicitly run tests after making changes. This leads to:
- Agents forgetting to test, resulting in broken code
- Full test suite runs that are slow and wasteful
- No immediate feedback during iterative development

**Impact:** Bugs slip through, agents waste time on manual test commands, development velocity suffers.

---

## Resources to Learn From

| Resource | What's There |
|----------|--------------|
| `~/.claude/settings.json` | Current global hook config (clj-paren-repair) |
| `/Users/sean/.gitlibs/libs/.../clojure_mcp_light/hook.clj` | Hook implementation pattern to study |
| `tests.edn` | Current Kaocha configuration |
| `research/initial-findings.md` | Initial architecture research |
| Claude Code docs on hooks | Hook input/output format, event types |

---

## Phase 0: Identify Real Use Cases (Do This First!)

Before designing the auto-test hook, we need to ground it in real work. An agent should analyze our codebase to find **low-hanging fruit improvements** - code quality issues that:
- Are safe to fix (won't change behavior)
- Have existing test coverage (or should have tests added)
- Would benefit from automatic test feedback during the fix

### What to Look For

| Category | Examples |
|----------|----------|
| **Dead code** | Unused functions, unreachable branches, commented-out code |
| **Inconsistencies** | Mixed naming conventions, inconsistent error handling |
| **Missing tests** | Public functions without test coverage |
| **Code smells** | Long functions, deep nesting, repeated patterns |
| **Documentation gaps** | Undocumented public APIs, outdated docstrings |
| **Performance opportunities** | Obvious N+1 queries, unnecessary recomputation |

### Output Expected

Write findings to `research/codebase-improvements.md` with:
1. List of specific improvements (file, line, what to fix)
2. Priority ranking (quick wins vs larger refactors)
3. Which improvements have existing tests vs need new tests
4. Recommendation for which 2-3 to tackle first as test cases for the auto-test hook

**This gives us real scenarios to design and test the hook against.**

---

## Research Questions (For Agents to Investigate)

These questions need answers before we can design a solution. The implementing agent should research, prototype, and document findings.

### Q1: Test Timing Strategy

**Problem:** Agent might edit 5 files to fix one issue. Running tests after file 1 would show failures that file 2-5 would fix.

**Research needed:**
- How do other tools handle this? (e.g., Jest watch mode, shadow-cljs hot reload)
- Can we detect "edit batches"? (timeout since last edit?)
- Should tests be entirely async with results appearing later?
- What does "reliable" look like here?

**Experiment:** Try different approaches with real editing sessions and measure usefulness.

### Q2: Test Result Storage

**Problem:** Full test output can be 100+ lines. We need summary for context, full output saved somewhere accessible.

**Research needed:**
- What's the standard Clojure convention for test result storage?
- Where do Kaocha/Leiningen/other tools write results?
- How should we handle results across sessions?
- Must be gitignored but easily accessible

**Experiment:** Look at kaocha plugins, test reporters, existing patterns.

### Q3: Test Runner Architecture

**Problem:** Need fast test execution. Options have trade-offs.

**Options to evaluate:**

| Approach | Startup | Reload | Complexity |
|----------|---------|--------|------------|
| Fresh JVM via Kaocha | Slow (~5s) | Full | Simple |
| nREPL-based (`clj-nrepl-eval`) | None | Must reload ns | Medium |
| Integrant test component | None | Managed by system | Complex |
| Kaocha watch mode | One-time | Automatic | Different model |

**Research needed:**
- Prototype nREPL-based approach - how to properly reload changed ns?
- Can we add a test-runner Integrant component that stays warm?
- How does Kaocha watch detect changes and reload?

### Q4: File-to-Test Mapping

**Problem:** When `foo.clj` changes, which tests run?

**Options:**
- Simple path convention: `src/x/foo.clj` → `test/x/foo_test.clj`
- Namespace dependency tracking with `clojure.tools.namespace`
- Run all tests (simple but slow)

**Research needed:**
- How complex is transitive dependency tracking?
- Is the simple path convention reliable enough for our codebase?
- Should we have "quick" (direct only) vs "thorough" (transitive) modes?

### Q5: Generative Test Handling

**Decision made:** Run with reduced iterations for quick feedback.

**Research needed:**
- How to configure iteration count at runtime?
- Can we use metadata like `:num-tests` or `:max-size`?
- Should we have test "profiles" (quick vs full)?

### Q6: Hook Integration Pattern

**Problem:** Need project-specific hook that works alongside global clj-paren-repair.

**Research needed:**
- Confirm project hooks in `.claude/settings.json` work as documented
- Do hooks run sequentially or truly parallel?
- What's the best event to hook: `PostToolUse` on Edit/Write?
- How to avoid blocking the agent?

---

## Constraints

1. **Non-blocking** - Agent must be able to continue editing while tests run
2. **Context-aware output** - Summary only in context, full logs saved elsewhere
3. **No /tmp pollution** - Use project-local or standard Clojure locations
4. **Gitignored results** - Test artifacts must not be committed
5. **REPL-friendly** - Must not break `(reset)` or the running system
6. **Composable with existing hook** - Work alongside clj-paren-repair, don't conflict

---

## Experiment Approach

After research is complete, we'll use **git worktrees** to test different hook strategies in parallel:

```
ml-options-trading/              # main - research committed here
ml-options-trading-strategy-a/   # worktree: per-edit hook
ml-options-trading-strategy-b/   # worktree: batched hook
ml-options-trading-strategy-c/   # worktree: no hook (baseline)
```

**Test Task** (covers all scenarios):
1. Refactor `handlers.clj` error handling → tests stay green
2. Add tests for `date_utils.clj` → new tests pass
3. Fix a TODO in `dsl/primitives.clj` → tests go red→green

**Success Metrics** (both objective + subjective):
- False positives (tests failed but shouldn't have)
- Time to complete task
- Context tokens consumed
- Agent's assessment of which approach felt most useful

---

## Success Criteria

1. **Tests run automatically** - When agent edits `.clj` files, relevant tests execute
2. **Fast feedback** - <5 seconds from edit to result for direct tests
3. **Non-intrusive** - Multi-file edit sessions aren't interrupted by failures
4. **Actionable output** - Agent can understand failures and find full logs
5. **Reliable** - Works consistently across sessions and system restarts

---

## Deliverables

- [x] Research findings in `research/` folder with recommendations
- [x] Decision document explaining chosen approach
- [x] Working test-runner hook (babashka or bb script) - `bin/auto-test-hook`
- [x] Project-level Claude hook configuration - `.claude/settings.json`
- [x] Test result storage location (gitignored) - `.claude/test-hook.db`
- [x] Documentation in CLAUDE.md - Auto-Test Hook section added
- [ ] Tests for any new code (ironic but required)

---

## Agent Instructions

**This PRD requires research, not immediate implementation.**

1. **Start with exploration** - Read the initial findings, understand the hook architecture
2. **Prototype each approach** - Build minimal working examples, measure them
3. **Document in research/** - Write up findings as you go
4. **Make decisions based on evidence** - Not gut feelings or first impressions
5. **Update this PRD** - As you learn, refine the approach

**Key principle:** The user wants the most reliable solution, even if it takes longer to research. "Reliable" means:
- Works consistently, not just in happy path
- Handles edge cases (system not running, tests don't exist, etc.)
- Provides useful feedback, not noise
- Doesn't slow down or block the agent

---

## Implementation Summary

### Current State (2025-12-05)

**Working implementation:**
- `bin/auto-test-hook` - Babashka script with 100ms debounce
- `.claude/settings.json` - PostToolUse hook on Edit|Write
- SQLite state tracking in `.claude/test-hook.db`
- nREPL-based testing (87x faster than Kaocha: 84ms vs 7.3s)

**Architecture decisions made:**
- nREPL for speed (requires system running)
- SQLite for debounce/history tracking
- Project-level hooks (not global)
- Never crash (exit 0 always, log errors)

### Failure Notification Strategy

When tests fail, how should Claude be notified? Options tested:

| Option | Behavior | Result |
|--------|----------|--------|
| **A. Silent** | Exit 0, stdout only | Claude doesn't see failures |
| **B. Inform** | JSON `decision: "continue"` | ❌ Claude doesn't see output |
| **C. Block (current)** | JSON `decision: "block"` | ✅ Claude sees and responds |

**Current:** Option C - Block on failure. Claude sees the error as a `<system-reminder>` and naturally responds to fix it.

**Findings from testing (2025-12-05):**
1. `decision: "continue"` does NOT make Claude see output - this was an incorrect assumption
2. `decision: "block"` works - Claude sees output and responds appropriately
3. Passing tests are silent (good - no noise)
4. Source namespace must be reloaded before test namespace (bug was fixed)

### Potential Issue: Multi-file Edits

Blocking may interrupt multi-file edit sessions where file 1's test fails but files 2-5 would fix it. Mitigation options:
1. Accept the interruption (Claude can explain and continue)
2. Add "acknowledged failure" tracking to skip re-blocking for same failure
3. Add debounce window where repeated blocks are suppressed

### Next Steps

1. ✅ Test Option C in real editing sessions - WORKS
2. Monitor for issues with multi-file edit interruptions
3. Consider adding failure acknowledgment if blocking is too aggressive
