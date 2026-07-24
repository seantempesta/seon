---
type: issue
status: open
severity: blocker
tags: [issue, runtime, agent]
---

# seon.agent.ctx file reads bypass the filesystem grant

## Evidence (containment audit 2026-07-23, verified)

`seon.agent.ctx/read-file-text` and `list-skill-files` reach the
filesystem directly, bypassing the fs capability grant surface.
Citations: research/sci-containment-surface-audit-2026-07-23.md
(High #6).

## Direction

Route through the one fs capability family (portable core + platform
leaf) with its grants; no direct platform residue mid-logic.

## Acceptance

- Both call sites consume the fs capability; a denied grant steers.
- rg proves no remaining direct fs residue in agent-facing ctx code.
