---
type: architecture
status: active
created: 2026-09-21
tags: [architecture, agent-platform]
---

# Seon architecture

These documents explain the target system and the dependency mechanisms that make
it possible. The [agent-loop specification](../specs/agent-loop.md) and the other
owning specifications state the mechanism; the [plan](../plan.md) §0 owns the schedule and
[TASK-CHAIN](../schedule/cards/TASK-CHAIN.md) owns the task lifecycle and its slice order (§7). A target
is not a claim that the current implementation already satisfies it; each domain
below distinguishes existing mechanisms from the cuts still requiring proof.

| Document | Question it answers |
|---|---|
| [System architecture](architecture.md) | How program facts, publication, contracts, execution, tests and acceptance compose |
| [Agent execution](agent-runtime.md) | How a retained SCI context, turn, wake, evaluation and result work together |
| [Web UI](ui.md) | How the same facts become agent history, namespace pages and bounded delivery |
| [Data modeling](data-modeling-guide.md) | How to choose identities, refs, components, deletion and observation facts |
| [Function contracts](contracts-guide.md) | What a complete, cheap contract checks; contract tasks (the `proofs` field), their proof cases and findings |
| [Dependency source](reference-code.md) | Which upstream seams supply the guarantees and how to verify their actual revision |

The current specifications include [TASK-CHAIN](../schedule/cards/TASK-CHAIN.md),
[agent execution](../specs/agent-loop.md), [merge](../specs/merge.md) and [errors](../specs/errors.md). The
[owner rulings](../../research/agent-platform/2026-10-08/review/owner-rulings.md)
resolve decisions; their mechanisms belong in these documents. Research under
`docs/research/` is evidence, not a second schedule or an authority over the current
specifications.

Source references identify the mechanism to read, not a live-system result.
Historical measurements remain in the linked specifications and research. Their
basis, harness and limits travel with them. Old PRDs and research remain available
until their binding rules and evidence have surviving homes and incoming links
are converted. This documentation revision does not remove those directories or
unvendor dependencies.
