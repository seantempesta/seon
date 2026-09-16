---
type: issue
status: open
severity: friction
tags: [issue, runtime, schema, database, class/p3]
---

# Schema unregister leaves the installed Datahike attribute

At `a55bdfc80` plus the deletion-presence repair, the real turn test
`seon.cluster.turn-test/runtime-schema-unregister-removes-one-unused-global-schema`
passes its saved return-text, stable program identity, and projection-removal
assertions, but fails the existing assertion that the unused Datahike attribute
is removed: `(contains? (:schema db) :shared.runtime/unregister-me)` is true.

Recorded isolated in-process run 41291 at 2026-09-16T01:25:48Z: three passes,
one failure, zero errors, fresh canonical base and SCI context, armed contracts.
The prior retired-result assertion masked this later observable in the baseline.
The candidate observation rewrite is not retained until this boundary is fixed.

The relevant owner is `src/seon/turn.clj:1036` (`schema-attribute-change-tx`),
called by deletion `row-tx` at `:1205`. It derives a projection diff and emits
`:db.fn/retractEntity` for removed attributes. The exact reason the deletion
produces no effective attribute removal still requires a probe; no cause is
inferred solely from the failing assertion. `src/seon/turn.clj` was under
concurrent edits and protected during this lane.

Acceptance: the same real register/unregister turn removes the unused database
attribute and schema definition, preserves its program identity tombstone,
and retains the existing refusal when current data uses an affected attribute.
See [the lane evidence](../../prds/context-generation/research/turn-test-reds-2026-09-16.md).
