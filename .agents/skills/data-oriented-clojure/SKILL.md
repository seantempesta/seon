---
name: data-oriented-clojure
description: "Write and review Seon Clojure with explicit data, immutable transformations, declared schemas, and database authority. Use before Seon Clojure or maintained dependency-fork changes."
---

# Data-oriented Clojure

Read [AGENTS.md](../../../AGENTS.md) for binding laws and
[the turn PRD](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
§13–§15 for the current target. Inspect the owning dependency before
designing another mechanism. This skill retains the source-backed
decisions that change how Seon code should be written.

## Carry the world

A computation receives its environment, immutable database value,
schema projection, and render profile. Capture one database value for
one pure derivation; don't reread a connection at each leaf. The public
database functions accept explicit inputs and return boundary errors
(`q`, `src/seon/db.clj:2206`; `pull`, `:2439`; `transact!`, `:4546`).

Use `seon.db` for first-party database work. Agent elision is an
evaluation service, not permission for a JVM caller to omit custody.
For a write decision that can change, decide inside the writer's
transaction function. Datahike's implementation supplies the current
transaction database to `:db.fn/call`
(`reference-code/datahike/src/datahike/db/transaction.cljc:1171-1172`).

Prefer immutable transformations and explicit ordering. A set expresses
membership; it cannot supply semantic order or a tie-break. Stable
identities derive through `seon.id/digest`; evaluations use
`seon.id/evaluation`, not another generator
(`src/seon/id.clj:40`, `:55`).

## Declare the shape once

First-party schema resources form one population. Duplicate keys refuse
at `src/seon/schema/edn.clj:216-228` and `:307-330`; `packaged-forms` exposes
the population at `:415`. The bridge derives storage attribute declarations
from the supplied projection (`src/seon/schema/datahike.clj:141-180`).

Model attributes and refs, not entity-kind stamps. Identity, cardinality,
and component ownership are properties of those attributes.
Optional stored values are absent; stored nilable shapes refuse at
`src/seon/schema/datahike.clj:131`. Use the data-modeling skill for
shape choices and datahike for transaction/query mechanics.

Every function, private included, requires a complete Malli contract
(`AGENTS.md:265`). `collect-contracts!` (`src/seon/instrument.clj:810-820`)
walks every loaded namespace's `ns-interns`, so private declarations are armed
too. Authored incomplete slots and predicate-contract omissions are checked by
`assert-complete-schema!` (`src/seon/schema/internal.cljc:291`); live
instrumentation's owner is `apply!` (`src/seon/instrument.clj:839`). Named schemas describe genuinely
polymorphic values; do not use an undefined contract to suppress a refusal.
Map keys are fully namespaced. Failures at agent boundaries are flat
error values, not a second success/failure envelope.

## Derive state and preserve observations

Open/closed work, unanswered wakes, history, and routed faults are queries
over facts. A component holds an owned concern; it is not a copied query
result. Read dependencies already have an owner:
`seon.db/read-evidence` retains plans and revisions without database
values or result payloads by default (`src/seon/db.clj:912`).

The §15 target deliberately stores shown text: an observation of what
the value renderer produced at evaluation time, not derived current
state. Its exact old bytes cannot be recovered by printing a changed
object or applying a changed profile.

Actual results and private defs/atoms remain in memory. `base-ctx` derives
the program base from one database value, memoized by program identity
(`src/seon/sci/eval.clj:2475`); `fork-for-turn` forks it and carries those
private objects while preserving the agent's context handle (`:2287`,
`regenerate-agent-context!` at `:2224`). Installed functions, schemas, and tests remain durable program rows.
Restart loses private objects while saved shown text survives.

## Render as a function of the data — target

One entity schema declares one AI/HTML pair. Scalars share its block;
components and declared derived queries render their concerns. No pair
means the attribute-map printer. `dir` and `doc` return program data,
not separate teaching prose; a `my.*` write returns the changed entity.

System turns execute and store generated reads. Before each agent turn,
the since-query diff checks every distinct read form's latest evidence,
including agent-written reads. Changed reads append evaluations; writes
and effects never rerun. Earlier shown text is immutable.
Compaction wipes evaluations and regenerates the opening.

Do not repair an old restoration, manual curation, attribute-render-pair,
or history-assembly mechanism into greater complexity. The PRD replaces
those mechanisms. Current source details are evidence for the remaining
work, never authority to restore a superseded design.

Use the clojure-testing skill for the canonical fixture and gate, the
repl skill for a live proof, and seon-flow-architecture before changing
a proc, channel, executor, or other running mechanism.
