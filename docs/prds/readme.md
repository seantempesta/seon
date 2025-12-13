# PRD System for Agent-Based Development

**Last Updated:** 2025-12-05

---

## Overview

This directory contains feature specifications (PRDs) structured for agent-based development. Each feature gets its own directory with structured documentation that captures not just requirements, but also decision-making, implementation learnings, and research findings.

**Key Principles:**
- PRDs describe **goals and constraints**, not exact implementations
- PRDs may be wrong or incomplete - agents should use judgment
- Rapid prototyping beats upfront planning
- Document decisions and learnings, not just requirements

---

## Directory Structure

Each feature lives in its own directory:

```
docs/prds/{feature-name}/
├── prd.md          # Main specification (goals, constraints, acceptance criteria)
├── decisions.md    # Architectural decisions and trade-offs
├── notes.md        # Implementation notes, gotchas, learnings
└── research/       # Agent exploration findings
    ├── spike-*.md  # Proof-of-concept experiments
    ├── eval-*.md   # Technology evaluations
    └── findings-*.md # Research summaries
```

**Naming Convention:** Use `lowercase-with-hyphens` for all files and directories.

**Example:**
```
docs/prds/bulk-loader-resilience/
docs/prds/logging-system/
docs/prds/realtime-greeks-display/
```

---

## prd.md Template

Use this template for the main specification. Adapt sections as needed - not every PRD needs every section.

```markdown
# PRD: {Feature Name}

**Status:** [Draft | Ready for Implementation | In Progress | Complete]
**Priority:** [Low | Medium | High | Critical]
**Branch:** `feature/{feature-name}`

---

## Goals

What are we building and why? Keep this to 2-3 clear goals.

Example:
1. **Enable resumable bulk imports** - Don't lose 10 hours of work on failure
2. **Prevent infinite retry loops** - Detect and stop when terminal is down
3. **Track progress granularly** - Know exactly which dates are complete

---

## Problem Statement

What problem does this solve? Include:
- What's broken or missing
- Impact of the problem
- Why it matters now

**Format:**
```
[Description of the problem]

**Impact:** [Quantify the pain - lost time, user friction, etc.]
```

---

## Resources to Learn From

What docs, reference code, or examples should agents read before starting?

| Resource | What's There |
|----------|--------------|
| `docs/foo.md` | Current implementation details |
| `reference-code/bar/` | Example patterns to study |
| `src/baz/` | Existing code to build on |

---

## Solution Design

High-level approach. Focus on:
- Architecture (data flow, components)
- Key technical decisions
- What changes and why

**Keep it flexible:** This is guidance, not prescription. If a better approach emerges during implementation, document the decision and pivot.

### Component Breakdown

What are the main pieces?

```clojure
;; Code examples are good - show the intent
(defn example-function
  "What does this do and why?"
  [args]
  ...)
```

---

## Agent Role

**You are a principal engineer, not a code monkey.**

PRDs define WHAT needs to be built and WHY. Your job is to figure out HOW:
- Investigate the codebase to understand the current state
- Design an approach based on existing patterns
- Prototype and iterate until you find what works
- Document your implementation decisions

**Anti-patterns to avoid:**
- Blindly following PRD suggestions that don't work
- Asking for exact file/line changes upfront
- Over-specifying implementation details in PRDs before investigation
- Treating PRDs as unchangeable requirements

If the PRD contains detailed implementation steps, treat them as **hints from prior research**, not requirements. Verify they're still accurate before using them.

---

## Constraints

What are the guardrails?

- Must be REPL-friendly (no breaking `(reset)`)
- Must not blow agent context window (hard caps on output)
- Must work with existing XTDB v2 schema
- CSS: Evaluate Tailwind options, pick what works

---

## Testing Checklist

What must work before calling this done?

- [ ] Unit tests pass for new functions
- [ ] Integration test: {specific scenario}
- [ ] Manual verification: {specific action}
- [ ] REPL reset works without errors
- [ ] No performance regressions

---

## Success Criteria

How do we know this is done and working?

1. **Criterion 1** - Measurable outcome
2. **Criterion 2** - User-visible behavior
3. **Criterion 3** - Performance threshold

---

## Implementation Summary

**Fill this in AFTER implementation is complete.** This section documents what was actually built, not what was planned.

### Files Changed
| File | What Changed |
|------|--------------|
| `src/...` | Description |

### Key Implementation Decisions
Brief summary of important choices made during implementation. See `decisions.md` for details.

### Deviations from Original Plan
What changed from the original PRD design and why?

---

## Deliverables

Checklist of what gets created:

- [ ] New namespace `ml-options.foo.bar`
- [ ] Tests in `test/ml_options/foo/bar_test.clj`
- [ ] Documentation in `docs/foo-setup.md`
- [ ] Update `CLAUDE.md` quick reference

---

## Notes

Free-form section for anything else:
- Known issues to watch for
- References to GitHub issues or docs
- Ideas for future improvements
```

---

## decisions.md Template

Record **why** decisions were made, not just what was decided. This prevents future confusion and helps agents understand the constraints.

```markdown
# Architectural Decisions: {Feature Name}

**Last Updated:** {date}

---

## Decision Log Format

Each decision should answer:
- **What** was decided
- **Why** we chose this approach
- **Alternatives** considered
- **Trade-offs** accepted

---

## Decision 1: {Title}

**Date:** YYYY-MM-DD
**Status:** [Proposed | Accepted | Superseded]

### Context

What situation led to this decision?

### Decision

What did we decide to do?

### Rationale

Why is this the right choice?

- **Reason 1:** Details
- **Reason 2:** Details

### Alternatives Considered

| Alternative | Pros | Cons | Why Not |
|-------------|------|------|---------|
| Approach A | Fast | Complex | Too much risk |
| Approach B | Simple | Slow | Not viable at scale |

### Consequences

What are the implications?

**Benefits:**
- Benefit 1
- Benefit 2

**Costs:**
- Cost 1
- Cost 2

**Risks:**
- Risk 1 + mitigation
- Risk 2 + mitigation

### Related Decisions

Links to other decisions this depends on or affects.

---

## Decision 2: {Title}

[Repeat format above]

---

## Superseded Decisions

Keep a record of what changed and why.

### ~~Decision X: Old Approach~~ → See Decision Y

**Why superseded:** New information, changed requirements, better approach emerged
```

---

## notes.md Template

Capture implementation learnings, gotchas, and tips for future maintenance.

```markdown
# Implementation Notes: {Feature Name}

**Last Updated:** {date}

---

## Overview

Quick summary of what was actually built (may differ from PRD).

---

## Key Learnings

What did we learn during implementation?

### Learning 1: {Title}

**What we discovered:**
- Finding 1
- Finding 2

**Why it matters:**
Explanation

**Example:**
```clojure
;; Code example showing the learning
```

---

## Gotchas

Things that tripped us up or will trip up future maintainers.

### Gotcha 1: {Title}

**The problem:**
Description of the footgun

**How to avoid:**
What to do instead

**Why this happens:**
Root cause explanation

---

## Code Patterns

Useful patterns that emerged during implementation.

### Pattern 1: {Name}

**When to use:**
Situation description

**Implementation:**
```clojure
(defn example-pattern
  "What this does"
  [args]
  ...)
```

**Why this works:**
Explanation

---

## Testing Notes

What's important to know when testing this feature?

- How to set up test data
- Key edge cases to check
- Performance characteristics
- REPL commands for manual testing

```clojure
;; Useful REPL commands
(require '[ml-options.foo :as foo])
(foo/test-scenario)
```

---

## Performance Characteristics

What should operators know about performance?

| Operation | Typical Time | Max Time | Notes |
|-----------|-------------|----------|-------|
| Initial load | 2s | 5s | First query is slower |
| Incremental update | 50ms | 200ms | Depends on batch size |

---

## Deployment Notes

What to know when deploying this?

- Environment variables needed
- Migration steps
- Rollback procedure
- Monitoring to watch

---

## Future Improvements

Ideas that emerged but weren't implemented (low priority or not needed yet).

1. **Idea 1:** Description - Why not now, when might we need it
2. **Idea 2:** Description - Dependencies or prerequisites
3. **Idea 3:** Description - Cost/benefit trade-off

---

## References

Links to relevant docs, issues, or external resources.

- [XTDB Documentation](https://docs.xtdb.com)
- [GitHub Issue #123](https://github.com/...)
- [Relevant blog post](https://...)
```

---

## Agent Workflow

The recommended development flow for agents working on a feature:

### Phase 1: Exploration (1-2 hours)

**Goal:** Understand the problem space before diving into implementation.

**Activities:**
1. **Read the PRD** - Understand goals, constraints, and suggested approach
2. **Study resources** - Read referenced docs and example code
3. **Explore codebase** - Find relevant files, understand existing patterns
4. **Verify assumptions** - Use REPL to test understanding

**Output:**
- Mental model of how things work
- Questions or concerns about the PRD approach
- Ideas for alternatives (if current approach seems wrong)

**Document in:** `research/exploration-notes.md` (optional, if findings are significant)

### Phase 2: Research (2-4 hours)

**Goal:** Validate approach through small experiments before committing to full implementation.

**Activities:**
1. **Spike solutions** - Build minimal proofs-of-concept
2. **Evaluate alternatives** - If multiple approaches possible, test both
3. **Test assumptions** - Verify performance, compatibility, edge cases
4. **Document findings** - Capture what worked, what didn't, and why

**Output:**
- Working spike code (doesn't need to be production quality)
- Performance data or benchmark results
- Decision on which approach to take
- Updates to PRD if original approach won't work

**Document in:** `research/` folder (see Research Folder Guidelines below)

### Phase 3: Implementation (main work)

**Goal:** Build production-quality feature incrementally.

**Activities:**
1. **Start with tests** - Write failing tests for key functionality
2. **Implement incrementally** - Small changes, verify each with REPL
3. **Document decisions** - Update `decisions.md` as you make choices
4. **Capture learnings** - Add to `notes.md` as you discover gotchas
5. **Verify with REPL** - Test each change before moving on

**Critical practices:**
- Use `(integrant.repl/reset)` to reload code, not `require :reload`
- Check system status before resetting: `(user/status)`
- Run tests frequently: `clj -M:test -m kaocha.runner`
- Commit incrementally, don't wait until "done"

**Output:**
- Working, tested code
- Updated documentation
- Completed `decisions.md` and `notes.md`

### Phase 4: Validation (before completion)

**Goal:** Ensure feature meets success criteria.

**Activities:**
1. **Run full test suite** - `clj -M:test -m kaocha.runner`
2. **Manual testing** - Verify each success criterion
3. **REPL verification** - Test that reset/restart works
4. **Documentation review** - All deliverables complete?
5. **Update CLAUDE.md** - Add new patterns/commands if needed

**Output:**
- All tests passing
- Success criteria verified
- Documentation complete and accurate

---

## Research Folder Guidelines

The `research/` directory is for agent exploration findings. It's a scratchpad for learning before committing to an implementation.

### What Goes in Research

**DO include:**
- Proof-of-concept spike code
- Technology evaluations (comparing libraries, approaches)
- Performance benchmarks
- Failed experiments (with lessons learned)
- Analysis of alternative approaches
- Questions and uncertainties discovered

**DON'T include:**
- Production code (goes in `src/`)
- Final documentation (goes in main `docs/`)
- Test code (goes in `test/`)

### File Naming Conventions

Use descriptive prefixes to organize research:

```
research/
├── spike-circuit-breaker.md        # Proof-of-concept for specific approach
├── spike-work-queue.clj            # Runnable spike code
├── eval-css-options.md             # Evaluation: Tailwind vs Sail vs CDN
├── eval-sse-libraries.md           # Evaluation: comparing libraries
├── findings-xtdb-compaction.md     # Research summary on XTDB behavior
├── findings-arrow-memory.md        # Deep dive into Arrow memory management
├── benchmark-query-performance.md  # Performance testing results
└── questions.md                    # Open questions during exploration
```

### Research Document Template

```markdown
# {Spike/Eval/Findings}: {Topic}

**Date:** YYYY-MM-DD
**Agent:** {agent-name or "Claude"}
**Status:** [In Progress | Complete | Superseded]

---

## Goal

What are we trying to learn or validate?

---

## Approach

How did we investigate this?

---

## Findings

What did we discover?

### Finding 1
Details

### Finding 2
Details

---

## Code Examples

```clojure
;; Minimal working example
(defn spike-test []
  ...)
```

---

## Performance Data

If applicable, include benchmark results, timings, memory usage.

---

## Recommendation

Based on findings, what should we do?

**Recommendation:** [Approach X because...]

**Rationale:**
- Reason 1
- Reason 2

**Next Steps:**
1. Action 1
2. Action 2

---

## References

Links to docs, articles, or discussions that informed this research.
```

### When to Create Research Documents

**Create research docs when:**
- Evaluating multiple technical approaches
- Uncertain about performance characteristics
- Learning a new library or technology
- Encountering unexpected behavior
- Need to validate assumptions

**Skip research docs when:**
- Path forward is clear and straightforward
- Just following established patterns
- Problem is simple and well-understood

Use judgment - research docs add value but shouldn't slow down obvious work.

---

## Migration: Converting Old PRDs

The project has existing PRDs at `docs/PRD-*.md`. These can stay as-is (they're linked from `CLAUDE.md`). New features should use the directory structure.

**If converting an old PRD:**

1. Create directory: `docs/prds/{feature-name}/`
2. Move content to `prd.md`
3. Extract decisions to `decisions.md`
4. Extract learnings to `notes.md`
5. Update `CLAUDE.md` reference

**No need to convert all at once** - do it when actively working on that feature.

---

## Examples

### Good PRD Structure

```
docs/prds/bulk-loader-resilience/
├── prd.md              # Clear goals, solution design, success criteria
├── decisions.md        # Why work queue over streaming? Why per-exp tracking?
├── notes.md            # XTDB compaction gotchas, ThetaData quirks
└── research/
    ├── spike-circuit-breaker.clj      # Minimal working circuit breaker
    ├── eval-parallelism.md            # Testing pmap vs core.async
    └── findings-xtdb-compaction.md    # Deep dive on compaction failures
```

### Lean PRD Structure

Not every feature needs everything. For a simple feature:

```
docs/prds/add-symbol-search/
├── prd.md              # Just goals, constraints, and success criteria
└── notes.md            # Brief implementation notes
```

If there are no significant decisions or research, skip those files.

---

## Tips for Agents

### Working with PRDs

1. **Read PRDs critically** - They're guidance, not gospel
2. **Question assumptions** - If something seems wrong, investigate
3. **Update PRDs as you go** - If you discover a better approach, document it
4. **Focus on goals, not implementation** - The PRD's suggested solution is optional

### When PRDs Conflict with Reality

**If the PRD approach isn't working:**

1. **Stop and assess** - Don't force a bad approach
2. **Document why** - Update `decisions.md` with what you found
3. **Propose alternative** - Test a different approach
4. **Update PRD** - Revise the solution design
5. **Continue** - Implement the better solution

**Example decision note:**
```markdown
## Decision: Switch from pmap to core.async

**Original PRD:** Suggested `pmap` for parallelism

**Problem discovered:** `pmap` blocks threads, causing issues with long-running fetches

**New approach:** Using `core.async` with pipeline-blocking for bounded concurrency

**Why this is better:**
- Non-blocking thread usage
- Better error handling
- More control over backpressure
```

### Documentation Hygiene

- **Update as you go** - Don't wait until the end
- **Be concise** - Future readers will thank you
- **Include examples** - Code examples are worth 1000 words
- **Explain the "why"** - The "what" is in the code

---

## Quick Reference

### Create a new feature PRD

```bash
# Create directory structure
mkdir -p docs/prds/{feature-name}/research

# Create files
touch docs/prds/{feature-name}/prd.md
touch docs/prds/{feature-name}/decisions.md
touch docs/prds/{feature-name}/notes.md

# Use templates from this document
```

### Agent checklist for starting a feature

- [ ] Read `docs/prds/{feature-name}/prd.md`
- [ ] Read referenced resources (docs, reference code)
- [ ] Explore codebase, verify assumptions via REPL
- [ ] Run spikes if approach is uncertain
- [ ] Document research findings
- [ ] Make decision on approach
- [ ] Implement incrementally with REPL verification
- [ ] Update `decisions.md` and `notes.md` as you go
- [ ] Run tests and verify success criteria
- [ ] Update `CLAUDE.md` if needed

---

## Questions?

If you're an agent reading this and something is unclear:

1. **Check existing PRDs** - See how they're structured
2. **Use your judgment** - These are guidelines, not rules
3. **Document your questions** - Add them to `research/questions.md`
4. **Adapt as needed** - If a template section doesn't fit, skip it

The goal is **clarity and continuity**, not bureaucracy. Make it work for the feature you're building.
