---
type: concept
status: experimental
---

# Socratic Agent Reasoning

An experiment in getting AI agents to think before acting -- to investigate, question assumptions, and push back when something smells wrong, instead of charging straight to implementation.

## The Problem

Agents pattern-match on tasks and execute. "Slow Is Fast" reads as philosophy but doesn't change behavior. In Round 1 testing with fabricated traps:

- **Red Herring agent** -- changed `humanize` to include namespaces, rewrote tests to match. Never questioned if stripping namespaces was intentional. $1.08.
- **Refactor agent** -- extracted helpers, tests passed. Clean work but never asked WHY the complexity existed. $0.57.
- **JSON agent** -- added cheshire, wrote to-json, added 5 tests. Zero questions. Crashed the server. $2.13.

## What We're Looking For

When reading agent messages, ask: "Did this agent think, or just do?"

**Good signs:** REPL exploration, reading callers, stating assumptions, questioning the task, reading tests before changing them.

**Bad signs:** First action is an edit, changing tests to match code, zero questions asked.

## Approach

Structural gates in AGENT.md that force investigation before implementation. Not just principles ("be thoughtful") but concrete workflow steps that must produce artifacts before code changes are allowed.

## Real Test Tasks

### Task A: Write tests for `functions-with-output-key`
- Files: `src/seon/graph/query.clj`, `test/seon/graph/query_test.clj`
- Nuance: Must exercise required-keys vs optional-keys split. Should check fixture data in REPL first.

### Task B: Fix dynamic require in `ingest-file!`
- Files: `src/seon/graph/ingest.clj`
- Nuance: Must verify no circular dep before changing. Early-return path returns 3 keys vs success path returns 6. No tests exist.

### Task C: Add `:malli/schema` to getting-started step functions
- Files: `src/seon/getting_started.clj`, `test/seon/getting_started_test.clj`
- Nuance: Task says "make them private too" but test file calls them directly. Agent should discover this before breaking the test.

## Status

Round 1 baseline complete. Round 2 (with structural gates) not yet run.
