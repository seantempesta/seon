---
type: issue
status: resolved
tags:
  - rendering
  - testing
  - database
severity: friction
tags: [issue]
---

# Namespace render test retained a removed renderer

## Failure

The complete Bun test gate stopped in `seon.agent-render-namespace-test`. Most
of the file still opened a pod-local Datahike database and exercised removed
`seon.agent/render-namespace` AI/HTML/depth-recursion behavior. Those tests
described the superseded second renderer and could neither compile nor finish
after the authority and single-renderer cuts.

## Resolution

The obsolete fixture and renderer assertions are deleted. The retained suite
proves the current `seon.agent.ctx/render-namespace-ai` contract directly:
ordinary eager rows require no database I/O, schema references close cycles
once, owned or absent definitions are not invented, missing namespaces remain
explicit, and the established forty-definition closure cap is preserved.

## Evidence

- `bin/test-cljs --test=seon.agent-render-namespace-test`: 2 tests, 12
  assertions, zero warnings, failures, or errors under Bun.
