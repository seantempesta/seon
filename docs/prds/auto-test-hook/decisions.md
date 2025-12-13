# Architectural Decisions: Auto-Test Hook

**Last Updated:** 2025-12-05

---

## Pending Decisions

These decisions need to be made based on research findings:

1. **Test timing strategy** - After each edit, batched, or on explicit trigger?
2. **Test runner architecture** - nREPL-based, Integrant component, or Kaocha watch?
3. **File-to-test mapping** - Simple convention vs transitive dependency tracking?
4. **Output format** - How to summarize for context without losing detail?
5. **Result storage** - Where to persist full test output?

---

## Made Decisions

### Decision 1: Reduced Iterations for Generative Tests

**Date:** 2025-12-05
**Status:** Accepted

### Context

Property-based tests with malli generators can run 100+ iterations by default, taking several seconds. For quick feedback during development, we need faster execution.

### Decision

Run generative tests with reduced iterations (e.g., 10 instead of 100) when triggered by the auto-test hook.

### Rationale

- **Fast feedback > thoroughness** for development iterations
- Full test suite with max iterations still runs before commit/PR
- Most bugs are caught in first few iterations anyway

### Alternatives Considered

| Alternative | Pros | Cons | Why Not |
|-------------|------|------|---------|
| Skip generative tests | Very fast | Might miss regressions | Too risky |
| Always full iterations | Thorough | Slow, blocks workflow | Defeats purpose |
| Metadata filtering | Flexible | Complex configuration | Over-engineered |

### Consequences

**Benefits:**
- Faster test feedback (<5s target achievable)
- Still catches most generative test failures

**Costs:**
- Rare edge cases might slip through to CI

**Risks:**
- Developers rely on quick tests and skip full suite → Mitigation: CI runs full suite, pre-commit hooks

---

### Decision 2: Project-Level Hook Configuration

**Date:** 2025-12-05
**Status:** Accepted

### Context

Claude Code supports hooks at three levels: global (`~/.claude/settings.json`), project shared (`.claude/settings.json`), and project local (`.claude/settings.local.json`).

### Decision

Use project-level `.claude/settings.json` for the test-runner hook, keeping global paren-repair hook as-is.

### Rationale

- **Project-specific behavior** - Different projects have different test setups
- **Composable** - Project and global hooks run in parallel
- **Shareable** - Can commit to version control for team use
- **Doesn't break existing setup** - Global paren-repair continues working

### Consequences

**Benefits:**
- Each project can customize test behavior
- Team members get same test hook behavior

**Costs:**
- Need to configure per-project (minor, one-time)

---

## Research-Pending Decisions

*These will be filled in as research progresses.*
