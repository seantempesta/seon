---
type: issue
status: open
severity: friction
tags: [issue, search, schema, architecture]
---

# Separate declared search metadata from the process index ID

## Problem

`:seon.search/index` now declares an attribute's Lucene indexing mode as the
enum `:text | :symbol`, but the cluster search wiring still uses the same key
for a process-local Lucene index ID string. Those meanings cannot share one
globally declared key without making one side's contract false.

## Evidence

- `resources/seon/schemas/seon.search.edn:3` declares
  `:seon.search/index` as the indexing-mode enum.
- `src/seon/cluster.clj:1462-1465`, `src/seon/cluster.clj:1519`, and
  `src/seon/cluster.clj:1763-1764` put the process-local index ID under the
  same key in Flow state.
- `src/seon/search.clj:431-441` currently consumes that legacy Flow-state
  meaning. The search metadata implementation did not change the protected
  cluster-owned wiring.

## Owner

The cluster search wiring owner after the stop-retry lane releases
`src/seon/cluster.clj`.

## Acceptance

- Declare a separately named string key such as `:seon.search/index-id` for
  the process-local resource.
- Move the cluster graph and `seon.search/index-step` to that key.
- Keep `:seon.search/index` exclusively as the Malli declaration property
  whose values are `:text | :symbol`.

