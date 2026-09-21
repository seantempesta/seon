---
type: architecture
status: active
created: 2026-09-21
tags: [architecture, agent-platform]
---

# Seon architecture

These documents explain the target system and the dependency mechanisms that make
it possible. The [implementation plan](../../prds/agent-platform/plan/README.md)
owns delivery order, exact contracts, measurements and acceptance proofs. A target
is not a claim that the current implementation already satisfies it; each domain
below distinguishes existing mechanisms from the cuts still requiring proof.

| Document | Question it answers |
|---|---|
| [System architecture](architecture.md) | How program facts, publication, contracts, execution, tests and acceptance compose |
| [Agent execution](agent-runtime.md) | How a retained SCI context, turn, wake, evaluation and result work together |
| [Web UI](ui.md) | How the same facts become agent history, namespace pages and bounded delivery |
| [Data modeling](data-modeling-guide.md) | How to choose identities, refs, components, deletion and observation facts |
| [Dependency source](reference-code.md) | Which upstream seams supply the guarantees and how to verify their actual revision |

The implementation specifications are [A1](../../prds/agent-platform/plan/lane-a1-projection-carried.md)
for projections/contracts, [A2](../../prds/agent-platform/plan/lane-a2-datahike-one-answer.md)
for Datahike/storage, [B1](../../prds/agent-platform/plan/lane-b1-one-publication-path.md)
for publication/operator/MCP, [B2](../../prds/agent-platform/plan/lane-b2-walk-flow-fork.md)
for execution/rendering, [B3](../../prds/agent-platform/plan/lane-b3-errors-tasks-dials.md)
for errors/tasks/configuration, [B4](../../prds/agent-platform/plan/lane-b4-tests-in-process.md)
for tests, [C1](../../prds/agent-platform/plan/lane-c1-wrapper-profiling.md)
for profiling and [D1](../../prds/agent-platform/plan/lane-d1-isolation-merge-writeback.md)
for candidates/merge/write-back. Their shared contracts live in the plan README;
architecture does not maintain a second implementation schedule.

Source references identify the mechanism to read, not a live-system result.
Historical measurements remain in the linked specifications and research. Their
basis, harness and limits travel with them. Old PRDs and research remain available
until their binding rules and evidence have surviving homes and incoming links
are converted. This documentation revision does not remove those directories or
unvendor dependencies.
