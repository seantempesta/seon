# PRD System

Feature specifications for agent-based development. Each feature gets its own directory.

## Creating a New PRD

```bash
cp -r docs/prds/_example-feature docs/prds/your-feature-name
rm docs/prds/your-feature-name/README.md
```

Or manually:

```bash
mkdir -p docs/prds/your-feature-name/research
touch docs/prds/your-feature-name/{prd,decisions,notes}.md
```

## Directory Structure

```
docs/prds/{feature-name}/
+-- prd.md          # Main spec: goals, constraints, acceptance criteria
+-- decisions.md    # Why decisions were made (optional)
+-- notes.md        # Implementation learnings, gotchas (optional)
+-- research/       # Exploration findings (optional)
    +-- spike-*.md
    +-- findings-*.md
```

## PRD Template

```markdown
# PRD: {Feature Name}

**Status:** [Draft | In Progress | Complete]
**Priority:** [Low | Medium | High | Critical]

## Goals

1. **Goal** - Why it matters

## Problem Statement

What's broken or missing. Impact. Why now.

## Resources

| Resource | What's There |
|----------|--------------|
| `reference-code/foo/` | Library source to study |
| `src/seon/bar.clj` | Existing code to build on |

## Solution Design

High-level approach. Architecture, data flow, key decisions.
This is guidance, not prescription -- pivot if a better approach emerges.

## Constraints

- Must be REPL-friendly
- Must work with existing Datalevin schema
- Must follow CONVENTIONS.md patterns

## Success Criteria

1. Tests pass
2. REPL verification works
3. Documented in decisions.md
```

## Agent Workflow

1. **Explore** -- Read PRD, study resources, understand problem
2. **Research** -- Test assumptions in REPL, spike solutions, document findings
3. **Implement** -- Build incrementally, verify each step in REPL
4. **Validate** -- Run tests, verify success criteria, update docs

**Key principles:**

- PRDs are guidance, not gospel -- pivot and document why
- Verify everything in the REPL -- `(user/run-tests 'ns)` not `clj -M:test`
- Document decisions and learnings as you go
- Focus on goals, not prescribed implementation

## Research Documents

Use `research/` for spikes, evaluations, and findings. Prefix files descriptively:

- `spike-*.md` -- proof-of-concept experiments
- `findings-*.md` -- research summaries
- `eval-*.md` -- technology comparisons

## Decision Log Format

Each decision in `decisions.md` should answer:

- **What** was decided
- **Why** this approach
- **Alternatives** considered
- **Trade-offs** accepted

## Example

```
docs/prds/schema-unification/
+-- prd.md
+-- design.md
+-- research/
    +-- serialization-findings.md
    +-- nil-semantics-findings.md
```
