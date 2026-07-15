---
type: issue
status: resolved
severity: cleanup
tags: [issue, agent, web]
---

# Keep the generated Datastar skill adapter synchronized

## Problem

The maintained Codex `datastar-web-ui` development skill described complete
historical database coordinates, while its generated Claude adapter still
described the retired `?t=`-only feed contract.

## Evidence

The complete operator gate failed `checked-in-adapters-match-the-canonical-corpus`
with exactly one changed path: `.claude/skills/datastar-web-ui/SKILL.md`.

## Resolution

The canonical `seon.dev.skills/sync!` projection regenerated shared adapters.
No hand-edited compatibility copy or parallel skill authority was introduced.

## Verification

The repeated complete operator gate passed 103 tests and 606 assertions with
zero failures or errors.
