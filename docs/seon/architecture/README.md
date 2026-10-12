---
type: architecture
status: active
created: 2026-09-21
tags: [architecture, agent-platform]
---

# Seon architecture

These documents explain the target system and the dependency mechanisms that make
it possible. The [agent-loop specification](../specs/agent-loop.md) and the other
owning specifications state the mechanism; the [ledger](../schedule/agent-loop-ledger.md)
owns delivery order and proofs. A target
is not a claim that the current implementation already satisfies it; each domain
below distinguishes existing mechanisms from the cuts still requiring proof.

| Document | Question it answers |
|---|---|
| [System architecture](architecture.md) | How program facts, publication, contracts, execution, tests and acceptance compose |
| [Agent execution](agent-runtime.md) | How a retained SCI context, turn, wake, evaluation and result work together |
| [Web UI](ui.md) | How the same facts become agent history, namespace pages and bounded delivery |
| [Data modeling](data-modeling-guide.md) | How to choose identities, refs, components, deletion and observation facts |
| [Function contracts](contracts-guide.md) | What a complete, cheap contract checks; contract tasks, their proof cases and findings |
| [Dependency source](reference-code.md) | Which upstream seams supply the guarantees and how to verify their actual revision |

The current specifications include [agent execution](../specs/agent-loop.md),
[merge](../specs/merge.md) and [errors](../specs/errors.md). The
[owner rulings](../../research/agent-platform/2026-10-08/review/owner-rulings.md)
resolve decisions; their mechanisms belong in these documents. The older
agent-platform plan supplies historical research, not a second delivery schedule
or an authority over the current specifications.

Source references identify the mechanism to read, not a live-system result.
Historical measurements remain in the linked specifications and research. Their
basis, harness and limits travel with them. Old PRDs and research remain available
until their binding rules and evidence have surviving homes and incoming links
are converted. This documentation revision does not remove those directories or
unvendor dependencies.
