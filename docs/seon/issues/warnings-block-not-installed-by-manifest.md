---
type: issue
status: open
severity: friction
tags: [issue, agent, pod]
---

# Warnings context block is not installed by the manifest

## Observed

`config/system.edn` installs `seon.agent.ctx.warnings/core-faults-block` and
`instrumentation-gaps-block` but has NO block whose render is
`seon.agent.ctx.warnings/warnings-block`. Live check on the default cluster
(2026-07-20, during the stage-1 warn unit's proof): root's rendered block set
is `[:system :root-role :namespaces :core-faults :instrumentation-gaps :plan
:canvas :transcript]` and a task agent's is `[:system :namespaces :canvas
:plan :transcript]` — neither contains `:warnings`. The entire
`seon.warn/checks` registry (15 checks: spec hygiene, failed evals, bad-ref
translation, fs denials, hop dead-letters, failing tests, unresolved
canvases) therefore renders into NO agent's context in the default
configuration; it is exercised only by tests and direct invocation.

`warnings-block` itself works live: invoked directly it acquired at one
database value, fired `:failed-evals` on a seeded failed-eval row, rendered
the current-idiom guidance, and self-healed to `""` after the row's
retraction (commit `0887b1ea`'s unit proof).

## Open question for the owner

Is the missing `:warnings` block deliberate (context-size discipline during
the context rebuild) or drift from the manifest rewrite? If deliberate, the
warn registry's docstrings ("the caller (seon.agent/warnings-block) defaults
…", cross-agent visibility claims) should say the block is opt-in; if drift,
the manifest needs the block row restored.

## Acceptance

Either the manifest installs a `:warnings` block again (and an agent's
ctx-preview shows the WARNINGS section when a check fires), or the decision
that it stays uninstalled is recorded and `seon.warn`'s namespace docstring
reflects the opt-in reality.
