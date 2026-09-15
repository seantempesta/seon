---
type: plan
status: active
tags: [prd, agent, context, architecture]
---

# Context-generation plan

Start with [The agent record and the turn loop](agent-record-and-turn-loop-prd-2026-09-07.md),
§0, then its binding §§14–17. It is the current design authority. Earlier
sentences marked superseded preserve the reasoning history; they are not
alternative implementation instructions.

[The working edge](unsettled.md) holds the latest dated implementation
checkpoint. [The ideas ledger](design-ideas-ledger-2026-08-13.md), including
rulings 69–72, records how the design arrived here. A historical lane list,
measurement, or earlier work order is not current process state.

[The record and REPL response PRD](agent-record-and-repl-response-prd-2026-09-07.md)
and [the entity-debug curation PRD](entity-debug-curation-prd-2026-09-06.md)
are SUPERSEDED. Other proposals in this directory remain dated evidence;
where their model differs, the turn-loop PRD governs. This README supplies
an entry, not a second ordered work queue.

Implementation claims must have source and live evidence. `[TARGET]` in
AGENTS.md distinguishes the accepted design from completed integration.
In particular, deterministic twelve-hex evaluation ids are implemented by
`src/seon/id.clj:50`; the 48-bit format cannot guarantee collision freedom.
That limit is recorded in the PRD's §0 rather than silently changing the
identity design.

> **2026-09-15: this program is wrapping up.** The next program is the [namespace steward platform](../../steward-platform/README.md); its ideas and research live there, nothing approved yet.
