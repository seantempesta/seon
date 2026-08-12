---
type: research
status: active
tags: [research, ai, model-registry, datahike]
---

# Model-registry `:db/noHistory` evidence

## Result

The model registry's last-observation gauges can use
`:seon.db/no-history? true`, which Seon's Malli-to-Datahike bridge derives as
`:db/noHistory true`. At the pinned Datahike revision, that flag keeps the
current value in the ordinary indexes while preventing a replaced or
retracted value from entering the temporal indexes.

The exact recurring proof is therefore: transact a gauge, replace it, verify
the replacement is current, and verify the superseded value is absent from
`d/history`. An unconstrained history query is not empty while a current gauge
remains asserted; Datahike's own test explicitly includes current no-history
values until retraction.

## Dependency ledger

- Root gitlink and checked-out Datahike revision:
  `0e8601d7f2f6`.
- Seon's bridge maps `:seon.db/no-history?` to `:db/noHistory true` at
  `src/seon/schema/datahike.clj:209-244`.
- Datahike derives `keep-history?` by excluding `:db/noHistory` attributes at
  `reference-code/datahike/src/datahike/db/transaction.cljc:439-478` and
  `:538-570`. The ordinary EAVT/AEVT/AVET updates remain unconditional while
  temporal-index updates are conditional.
- Datahike's recurring test shows current no-history values in `d/history`,
  retracts one entity, and then verifies the retracted no-history value is
  absent at
  `reference-code/datahike/test/datahike/test/time_variance_test.cljc:217-243`.

## Model-registry consequence

The gauges remain current, queryable display facts without accumulating
superseded samples. Durable attempt rows remain the historical authority. The
design's phrase “history on that attribute returns nothing” must be applied
after replacement/retraction or narrowed to the superseded value; treating it
as an always-empty query would contradict the maintained dependency.

## Tool and render feedback

The dependency's own test makes the subtle behavior legible: the current value
appears in both current and history views, while retraction removes only the
no-history attribute's past value. A model-registry surface should call these
facts “latest observation” rather than implying that the value is absent from
every temporal query.
