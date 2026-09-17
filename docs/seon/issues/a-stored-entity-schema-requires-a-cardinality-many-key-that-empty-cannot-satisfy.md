---
type: issue
status: open
severity: blocker
created: 2026-09-17
tags: [issue, schema, write-admission, seon.db, class/p1]
---

# A stored-entity schema requires a cardinality-many key that an empty collection can never satisfy

A cardinality-many attribute with NO members emits no datoms, so the stored
entity cannot carry the key. `seon.db`'s final-report validator rebuilds each
affected entity from the resulting datoms (`seon.db/write-entity-value`,
`src/seon/db.clj:2912`) and validates that row, so a required cardinality-many
key in a `{:seon.db/attributes true}` schema is unsatisfiable the first time an
honest empty occurs. This is the ruled case in AGENTS.md §3 (2026-09-16 §1f G4).

First sighting, and the one fixed: `:seon.activation/closure` refused every
source publication with an empty config/executable/lookup member set, taking
`seon.cluster.source-test` to 1 failure + 9 errors at HEAD `ca8fd63b9`. Evidence,
exact refusal and measurements:
[source seal refusal](../../prds/steward-platform/research/source-seal-refusal-2026-09-17.md).

## The remaining inventory, derived

Resolve every attribute form under `resources/seon/schemas/` to its
`[:set …]` / `[:vector …]` head, then list the non-optional entries of every
`{:seon.db/attributes true}` map. 25 keys in 9 schemas; the 6 in
`:seon.activation/closure` are fixed.

| schema | required cardinality-many keys |
|---|---|
| `:seon.cluster/cluster` | `instructions`, `toolkit` |
| `:seon.fn.arity/row` | `arguments` |
| `:seon.maintenance.result/cluster-cleanup-component` | `remaining`, `removed` |
| `:seon.maintenance.result/collect-component` | `collect-branches` |
| `:seon.maintenance.result/process-census-component` | `claim-errors`, `dead`, `processes`, `roots`, `unclaimed`, `unresponsive` |
| `:seon.maintenance.result/process-census-process` | `advertisements` |
| `:seon.maintenance.result/reap-component` | `reap-refused`, `reap-roots`, `reap-stopped-processes`, `eligible-root-claims` |
| `:seon.test/adoption` | `adoption-identities`, `adoption-inputs` |

A reap that removed nothing, a census with no unresponsive process, an arity
with no arguments: each is an honest empty, and each is refused on sight.

## What the fix is, and what it is not

Mark the member collections `{:optional true}` in the STORED-entity schema, and
keep the "must not be empty" decision at the authority that still sees the
supplied value — `seon.cluster.source/activation-seal-tx` already refuses
`::activation-empty` that way (`src/seon/cluster/source.clj:268`).

NOT a validator exemption and NOT a weaker attribute shape: each collection
still validates as its declared `[:set …]` / `[:vector …]` when present.

Relaxing required-many keys generically inside `seon.db` was considered and
rejected: it would turn every such declaration into a silent no-op, which is the
project's recurring failure class (a check that reads absence of signal as
health, AGENTS.md §"How we work here").

## The class regression

`seon.cluster.source-test/an-activation-closure-with-empty-member-collections-seals`
proves the class dead for the activation closure: the seal commits AND each
empty member collection stores no datom. A checker deriving the whole inventory
from the canonical projection is the durable form, and it can only be armed once
the eight remaining schemas are corrected — `seon.fn.edn` and `seon.test.edn`
are held by the codex integrator.

## Related

`complete-program-publication-is-refused-on-a-cardinality-many-set` is the other
half of the same attribute-versus-member confusion, from the per-member side.
