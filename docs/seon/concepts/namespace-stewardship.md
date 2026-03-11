---
type: concept
status: active
---

# Namespace Stewardship

A playbook for agents assigned as permanent maintainers of Seon namespaces. The agent deeply understands their namespace AND how it fits into the living system, then writes a comprehensive assessment as the namespace docstring.

## Core Principle

You are a TEAM PLAYER. Your consumers matter more than your internals. Think: "how can I make life better for the namespaces that depend on me?"

## Audit Phases

### Phase 1: Understand the Vision
Read [[vision/index]] and CONVENTIONS.md. Think about how your namespace enables or hinders agent-driven development.

### Phase 2: Deep-dive Your Namespace
Read source completely. Read tests. Run tests and record pass/fail. Git archaeology on your files. Read child/sibling namespaces.

### Phase 3: Understand Your Consumers (most important)
Grep for your namespace across `src/` and `test/`. For each consumer: what do they use, how do they use it, what's clunky, are they fighting against you?

### Phase 4: Assess System Boundaries
Duplication? Boundary clarity? Missing consumers? Easy wins?

### Phase 5: Write the Namespace Docstring
A living document (~50 lines max) with: Purpose, Dependencies, Consumers, Watch-outs, Needs-work (P0-P3 priority), Incoming requests, Last audit metadata.

### Phase 6: Make Improvements
Only modify YOUR namespace source and test files. If fixes require changes in consumers, document them as Requested Changes for the orchestrator to delegate.

## Scope Rules

- Only modify files in your namespace (source + test)
- Report issues to the orchestrator for tracking in `orchestrator/issues/`
- Cross-namespace changes go through Requested Changes
- Testing is non-negotiable -- run after every change
- Update the docstring after every improvement

## Priority Scale

- P0: Existential (broken, crashes, data loss)
- P1: Blocks discoverability (missing schemas, wrong contracts)
- P2: Convention violations (naming, patterns, missing metadata)
- P3: Quality gaps (no generative tests, sparse docs)
