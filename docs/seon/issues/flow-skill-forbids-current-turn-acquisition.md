---
type: issue
status: open
severity: friction
tags: [issue, skills, flow, sci]
---

# Flow skill forbids the current turn acquisition mechanism

Observed by ops-effects-2 on 2026-09-20 while reading the mandatory flow skill.
The agent context ownership section of
`.agents/skills/seon-flow-architecture/SKILL.md` says “Do not construct a new
fork every turn.” Current `src/seon/sci/eval.clj:2083` implements
`fork-for-turn`, regenerating the program context and reinstalling retained
private objects. AGENTS.md describes that same mechanism. The skill must be
re-grounded against that owner; this lane changes error observations only and
does not alter acquisition to follow the stale instruction.

This is a recurrence of the documentation class previously resolved in
`docs/seon/issues/archive/flow-skill-teaches-overturned-runtime-facts.md`;
its historical resolution addresses a different implementation generation.
Acceptance: skill guidance states the actual acquisition and retained-handle
guarantees, citing the current owning functions.
