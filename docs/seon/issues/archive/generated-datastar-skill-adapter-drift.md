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

## Final resolution

Owner ruling 29 superseded the generated-adapter design. Commit `f60a5ea79`
made `.agents/skills/` the one real directory and replaced `seon-skills` and
`.claude/skills` with links to it; commit `1ee741672` then deleted
`seon.dev.skills`, its old test, and the dying operator command that invoked
it. Commit `9e79b77e9` added a live `bin/test` structural gate that refuses
either link being replaced or resolving away from `.agents/skills`.

The same ruling deleted `browser-automation`; because all three consumer paths
resolve to the same directory, the deletion and the new
`seon-flow-architecture` skill (including its references) are immediately
identical for Codex, Claude, and runtime import. A repository-local falsifier
replaced both links with real directories and observed `bin/test` fail with
the repair instruction before loading Clojure.
