---
type: issue
status: open
severity: friction
tags: [issue, render, agent, wave/visual-qa]
---

# Root's empty plan read shows nil

The root-cluster prompt captured on 2026-09-09 contains the declared plan
read at turn 0, but its result is `nil` when root owns no plan component.
The notes concern already returns `[]` for its empty case. The empty plan
should likewise show useful empty data through its owning render function.

Evidence: [exact root prompt](../../prds/context-generation/research/root-cluster-prompt-2026-09-09.txt),
the plan entry after `(seon.cluster.status/agents {})`. The corresponding
HTML correctly says “No objective set” and “No steps yet.” This is an AI
empty-case presentation issue; the root-cluster lane does not own the plan
renderer. No plan data was fabricated to hide it.
