---
type: issue
status: open
severity: blocker
tags: [issue, render, boot]
---

# Root blocks carry two key vocabularies, so the page 500s

## Problem

A cluster whose branch predates the block-attribute rename holds BOTH
vocabularies for the same four root blocks: the old `:seon.block/name` /
`:seon.block/priority` rows and the current `:seon.render.block/name` /
`:seon.render.block/priority` rows. `seon.render.block/blocks` returns all
eight; the four old rows fail `:seon.render.block/block`, its output contract
fails, and every request for `/` answers 500.

The seed is an idempotent upsert BY NAME, so it rewrites the current rows and
never retracts the differently-keyed ones. Nothing in the boot path notices,
because the old rows are valid datoms under a schema that still installs both
attribute families.

## Evidence

Observed on the live `default` cluster, 2026-07-28. The 500 begins at that
cluster's OWN boot — `data/clusters/default/logs/seon.log` line 3 is the
21:04:36Z view banner and line 6 is the first violation — so it is boot state,
not request state, and it was not introduced by the F2 wave.

```
seon.render.block/blocks violated its contract (invalid-output):
[{:seon.render.block/name ["missing required key"],
  :seon.render.block/priority ["missing required key"]} …×4]
```

The instrumented args show the duplication directly: four maps keyed
`:seon.block/*` followed by four keyed `:seon.render.block/*`, same names,
same priorities, same projections.

`curl -s http://127.0.0.1:7994/` returns the violation text with status 500.

## Owner

`seon.render.root/seed-tx` (the upsert that cannot see the old rows) with
`seon.cluster/seed-root-agent!` as the caller, and the schema population that
still installs the superseded attribute family.

## Acceptance

- a cluster branch carrying old-vocabulary block rows serves `/` with 200
  after one boot, with each block present exactly once;
- the superseded attribute family is either retracted at the seed or removed
  from the installable set, so the duplication is unrepresentable rather than
  repaired per boot — one mechanism, not a cleanup pass;
- a reset-boundary proof covers it: a fixture cannot see this class, because
  a fresh in-memory database never carries the old rows.

## Notes

Instrumentation is the DETECTOR here, not the cause — `seon.instrument/remove!`
makes the page render again and is emergency recovery only. The stale rows
would otherwise reach the browser as duplicate morph targets.
