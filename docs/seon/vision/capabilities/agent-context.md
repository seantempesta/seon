---
type: capability
status: partial
tags: [vision, agent]
---
# Structured Agent Context

When an agent starts, it receives context about its namespace: functions, schemas, dependencies, test status. This context is built from the code graph, not hardcoded. The context building works but uses a fixed text format rather than the vision's schema-based resolution, and multiple overlapping builders exist.

## What Exists

- `context-for-agent` called from `build-agent-prompt`
- Graph context builder produces topologically sorted text blocks
- `seon.repl.context` provides REPL-accessible wrapper

## Gaps

- Uses hardcoded text format instead of `:seon.render/ai` resolution
- Three overlapping context builders (should be one discovery-based system)
- Agents cannot reshape or query their own context
- Turn limit continuation (`error_max_turns` → send "Continue." → resume with full context) is a known-good mechanism but not wired into the orchestrator for automatic long-running agent support

## Related

- Components: [[components/code-graph]], [[components/renderer]], [[components/agent-system]]
- PRDs: [[prds/spec-driven-rendering/prd]], [[prds/graph-cleanup/prd]]
- Issues: [[orchestrator/issues/overlap-three-ai-context]]
