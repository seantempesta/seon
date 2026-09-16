---
type: issue
status: open
severity: cleanup
tags: [issue, schema, datahike, pull]
---

# Wildcard pulled collections do not satisfy entity set contracts

The projection-derived reference regression on 2026-09-16 enumerated 80 map
schemas with reference entries, including 48 storable maps. After the shared
reference schema admitted `{:db/id n}`, 17 stored maps still failed whole-map
validation: Datahike wildcard pull returns cardinality-many values as vectors,
where their declarations require sets. All 48 writes were admitted and their
reference targets pulled back correctly in that run.

The failure subjects were `:my.plan/entity`, `:my.plan.item/item`,
`:seon.activation/closure`, `:seon.ai.model/entity`, `:seon.cluster/cluster`,
`:seon.context.capture/capture`, `:seon.context.contribution/contribution`,
`:seon.error/error`, `:seon.fn/fn`, `:seon.fn.arity/row`, `:seon.issue/issue`,
`:seon.ns/ns`, `:seon.runtime/entity`, `:seon.schema/schema`,
`:seon.test/adoption`, `:seon.test/test`, and `:seon.turn/turn`.
This is a dated measured list, not the regression's selection mechanism.

For example, `:seon.ai.model/deepseek-off-peak-windows` pulled as
`[{:db/id 9819}]`; its declaration requires a set. Replacing the scalar
reference union cannot change that collection mismatch. The schema audit
must decide which grammar the entity declaration promises before changing
the reader or widening collection contracts. See
[the three-grammar study](../../prds/steward-platform/research/entity-schema-vs-pulled-shape-2026-09-16.md)
and [the repair evidence](../../prds/steward-platform/research/pulled-ref-is-a-ref-2026-09-16.md).

A separate dependency boundary was observed while constructing these rows:
nested maps in unique-identity ref attributes are rejected before `explode`
by Datahike's upsert resolution. The generator uses the same existing eid
directly for those identity attributes and exercises nested-map admission
on the ordinary refs. This is not evidence that all transaction positions
accept all four spellings interchangeably.
