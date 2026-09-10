---
type: issue
status: open
severity: friction
tags: [issue, agent-context, wave/context-derivation]
---

# Effective settings read refreshes after a system turn

Canonical armed `seon.help-test` probe, 2026-09-09: immediately after storing
the opening, another `system-turn` selects `(seon.agent/effective-settings)`
as changed. Its turns-left derivation observes the runtime turn edge. The
opening adds that edge, while the effective limit and remaining count stay
100. The changed-read report names `:seon.runtime/turns`.

This is conservative read evidence, not a changed setting. Repeated system
passes can append identical settings output. Verify the join/absence evidence
in `seon.turn/episode-runs` and `outside-wake-t` before tightening it; suppressing
all turn dependencies would miss actual budget changes. The prompt cookbook
records the actual refresh rather than claiming the opening is quiescent.
