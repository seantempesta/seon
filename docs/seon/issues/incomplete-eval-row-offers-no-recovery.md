---
type: issue
status: open
tags: [agent, context, issue]
severity: friction
---

# Incomplete eval row offers no recovery

## Problem

`seon.agent.ctx/format-eval-row` renders an eval that has neither a result nor
an error as only `⟹ ✗ <no result>`. The projection hides which eval is
incomplete and gives the agent no corrective action.

## Evidence

The `:else` branch in `src/seon/agent/ctx.cljs` emits the fixed placeholder
without the row's `:seon.eval/id`. The Stage 1.6 corrective-steering audit
records this as gap G6.

## Owner

`seon.agent.ctx/format-eval-row` owns the pure transcript projection.

## Acceptance

- An incomplete non-comment eval names its eval ID and honestly says that no
  result was recorded.
- The projection tells the agent to re-run the form.
- Focused context tests pass.
