---
type: issue
status: open
tags:
  - agent
  - context
  - namespaces
severity: friction
tags: [issue]
---

# `my.ns/functions` points to a removed namespace renderer

## Failure

The agent-facing `my.ns/functions` docstring and empty-result hint tell an agent
to call `seon.agent.ctx/render-namespace`. That operation was removed when
namespace rendering consolidated into the database-derived `:namespaces`
context block. The stale instruction sends an agent toward a nonexistent API
and bypasses the intended per-agent `full-source` policy.

## Required resolution

- Remove every reference to the removed renderer from `my.ns` and its tests.
- Add agent-facing full/compact operations that read the caller's existing
  `:namespaces` block, preserve its other controls, and apply the updated block
  through `seon.agent.ctx/install!` under the current agent scope.
- Use canonical namespace symbols only and return errors as values.
- Repeated promotion/compaction must be idempotent; reassignment and explicit
  generated-code overlays must remain derived rather than accumulating stale
  stored selections.
- The next context render, not the operation response, is the one source display.

## Acceptance evidence

- focused `my.ns-test` coverage for promotion, compaction, unknown namespaces,
  empty presence sets, preservation of namespace dials, and idempotency;
- a context test proving promoted source appears in the existing namespaces
  block and compaction returns it to its ordinary density; and
- no remaining source or durable documentation reference to the removed
  agent-facing renderer.
