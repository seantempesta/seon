---
type: issue
status: open
severity: blocker
created: 2026-09-17
tags: [turn-loop, bounded-execution, store, writer, class]
---

# A failing turn write re-fires without bound and fills the store

## Problem

On 2026-09-17 ~12:20–12:40Z default's store grew 2.5 GB → 19 GB (~1 GB a
minute, 1,329 new files in two minutes) while default's own log showed the
Datahike writer throwing continuously — two `:datahike/write-error` entries
inside one 150 ms probe:

```
:malli.core/invalid-schema {:schema :seon.schema-usage-guardb/entity-id}
  seon.schema/direct-reference-keys-in ← projection-with-schema
  ← seon.turn/row-tx (turn.clj:1344) ← [:db.fn/call retain-transaction]
  ← datahike.writer
```

Two defects, one storm:

1. **Worker-global mutation by an in-process test.** `:seon.schema-usage-guardb/entity-id`
   is `seon.schema-usage-guard-test`'s probe schema. An in-process
   `seon.test/run` of that namespace inside default's JVM (one of the
   fixture-sweep's 46 namespaces) registered a synthetic schema into the
   shared registry/projection and did not restore it (§5 "own nothing
   global"). `seon.test-support/preserving-instrumentation-state` exists for
   instrumentation; nothing preserves the schema registry.
2. **Unbounded re-firing.** Every turn write then failed, and the turn loop
   re-attempted without bound; each failed attempt still flushed dirty
   index leaves, so the store filled at a gigabyte a minute. §2.3: a bound
   firing is a bug report; a repeated write error must stop the proc and
   become one fault for the steward, never a storm.

Log kept at `tmp/orchestrator/refork/seon-log-write-storm-2026-09-17T1240Z.log`.
Recovery: `bin/seon reset --force` (fifth reset of the day).

## Fix shape

- Registry preservation: a `seon.test-support/preserving-schema-registry`
  (or widen `preserving-instrumentation-state` to the merged registry and
  the cluster's projection state) used by every test that registers a
  synthetic schema; `seon.test/run` restores it after an in-process run the
  way the runner's drift detector re-arms — and the drift detector reports
  registry drift by key, not only Var drift.
- Bounded write retry: the turn proc counts consecutive write refusals per
  agent; past a declared bound it parks the agent with one committed fault
  naming the refusal (the fault committer's job), and the debug page shows
  it. Regression: a write that refuses N times produces one fault and no
  further transactions.
- The storm's cost is also the `:db.fn/call` path recomputing a projection
  per attempt (`projection-with-schema` inside the writer) — the settlement
  lane's finding that row-tx rebuilt the projection (2da44c50d) reached
  only the declaration path; the retain path still derives at write time.

Related: `in-process-test-runs-poison-the-shared-fixture-base`,
`the-store-grows-without-collection` (owner options page).
