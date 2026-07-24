---
type: issue
status: open
severity: blocker
tags: [issue, schema, runtime]
---

# Contract predicate transitive purity awaits execution planner

## Overnight triage — 2026-07-23

**FOLD-INTO-UNIT — P3 read-side admission.** `plan-execution` now walks
predicate bundles, but acquired projections still default the pure-predicate
population empty. P3 owns deriving and admitting that graph-proven set.

## Problem

R33 admits an agent-authored `[:fn]` predicate only when its complete indexed
program-graph call graph is pure and capability-free. Schema admission now has
the explicit `:seon.schema/pure-predicate-symbols` compiler input and rejects a
direct predicate absent from that set, but this lane does not own the existing
program-graph execution walk that must derive the set.

Building another recursive graph walker in `seon.schema` would create a second
authority and violate the execution-planning ownership ruling.

## Evidence

- `seon.schema/assert-complete-contract!` checks every direct guarded predicate
  against the supplied pure-predicate set before Malli compiles its body.
- `seon.schema/build-projection` carries the set in the immutable projection
  and includes it in the semantic fingerprint.
- The committed projection producers currently supply no transitive proof, so
  the empty-set default fails closed for agent-authored predicates.
- Core predicate function bindings are reloadable Malli SCI compiler inputs;
  they do not assert purity or admission source.

## Owner

The execution-planning unit that owns the indexed program-graph call walk.
Derive one pure, capability-free predicate-symbol set from that existing walk
and pass it to the schema projection input. Do not add a schema-local graph
traversal.

## Acceptance

- The existing execution graph walk rejects a predicate when any transitive
  callee is impure, unresolved, or capability-bearing.
- A completely pure corpus-loaded predicate reaches every projection compiler
  through `:seon.schema/pure-predicate-symbols` and compiles in Malli's SCI
  tier.
- Restart and cold-reload tests prove the result is derived from indexed graph
  edges, not function names or a stored purity flag.
- The writer never resolves or executes an agent-authored predicate.
