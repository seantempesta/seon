---
type: issue
status: resolved
severity: blocker
created: 2026-09-18
tags: [issue, boot, cluster, database-read, absence-as-health, wave/core]
---

# The initialization readiness probe read a refused pull as an absent entity

`seon.cluster/transact-initialization!` transacts the config initialization
rows in readiness waves. A row was "ready" when every lookup ref it carries
answered `(:db/id (seon.db/pull database [:db/id] [attribute value]))`. That
expression has two outcomes for three states: a resolved entity answers an
id, an ABSENT entity answers `nil`, and a REFUSED READ also answers `nil`.

On 2026-09-18 the third state happened. `bin/seon reset --force` and every
isolated `bin/seon init` refused in republish with

    Initialization lookup refs do not resolve.
    :seon.activation/missing [{:lookup-attribute :seon.ai.model/provider-id
                               :lookup-value "openrouter"} …]

about provider descriptors that were in the database all along. An instrumented copy of the readiness loop, run against the
canonical fixture base (`seon.test-support/with-database`, 2026-09-18), showed
wave 1 ready 12 rows and committing them cleanly, and wave 2 — the five model
rows — reading every provider lookup ref as:

    {:seon.error/kind :seon.db/unknown-pull-schema
     :seon.error/message "seon.db/pull returned a pulled value whose entity
       schema is not uniquely declared by the attributes present…"}

while `(d/datoms database :avet :seon.ai.model/provider-id "openrouter")`
showed the entity present. The probe read that refusal as absence, found
nothing ready, and reported the opposite of the truth. The reported cause
named the config manifest; the real cause was in the read seam.

The readiness probe now answers three states. A refused readiness read
refuses the population immediately, before any judgement about what resolves,
and carries the read's own refusal verbatim on `:seon.boot/result` with the
offending lookup attribute and value. It never loops on absence.

Proof: with the fix in place, the same canonical fixture base build refuses
with `An initialization readiness read was refused: seon.db/pull returned a
pulled value whose entity schema is not uniquely declared by the attributes
present.` — the real cause, at the seam that owns it.

Fix: `src/seon/cluster.clj` (`lookup-resolution`, `row-lookup-refusal`,
`row-ready?`, `transact-initialization!`).
Regressions: `seon.cluster-test/initialization-readiness-surfaces-a-refused-read-instead-of-absence`
and `seon.cluster-test/initialization-orders-provider-rows-before-the-models-naming-them`.
Both are written and lint-clean but HAVE NOT RUN: every canonical fixture base
in the tree refuses while the pull issue below is open, so no test on that
fixture can execute. They owe a gate the moment it is fixed.

The refusal that the probe was hiding is its own issue:
[a `[:db/id]` pull refuses on an entity no row schema claims](pull-validation-refuses-a-db-id-selector.md).
