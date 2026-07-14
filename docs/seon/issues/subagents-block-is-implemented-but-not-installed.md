---
type: issue
status: open
severity: cleanup
tags: [issue, agent, orchestrator]
---

# Subagents block is implemented but not installed

## Problem

`seon.agent.ctx.subagents` says the general direct-child block is wired into
the manifest, while the current minimal `:seon.config/agent-context` does not
install it. The architecture also described it as a standing volatile block.

Keeping it absent is currently correct: solo-agent navigation and completion
must graduate before multi-agent prompt experiments. The inaccurate wiring
claim makes that deliberate sequencing look like an accidental runtime bug.

## Evidence

`subagents-block` and focused tests exist. `config/system.edn` installs only
namespaces, canvas, plan, and transcript for ordinary agents; root additionally
gets warnings and orphaned-agents. There is no `:subagents` block entry.

## Owner

The active agentic-tool-refinement roadmap and the context manifest. The
existing renderer remains the one implementation; do not add another live
fleet mechanism.

## Acceptance

- Source and architecture state clearly that the renderer is dormant pending
  solo-agent graduation.
- After solo graduation, Inspect compares no fleet context with a compact
  direct-child view in the free dynamic tail.
- Child outcomes remain database-derived facts that survive restart; only
  heartbeat age is ephemeral.
- The winning form is installed through the ordinary database-backed context
  manifest and vanishes for agents without children.
