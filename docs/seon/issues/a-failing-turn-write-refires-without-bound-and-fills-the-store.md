---
type: issue
status: resolved
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

## Resolution (2026-09-17, write-storm lane)

Landing note with the storm's numbers and the live before/after:
[bounded-write-retry-and-registry-preservation-2026-09-17](../../prds/steward-platform/research/bounded-write-retry-and-registry-preservation-2026-09-17.md).

**Class 2, bounded write retry** (`f86ec57ed`). A turn pass whose DURABLE write
was refused names the refusal in its report (`:seon.turn.loop/refusal`);
`seon.turn/step` counts consecutive refusals in the proc's own state, and at
the declared per-agent dial `:seon.config.agent/write-refusal-bound` it parks
the agent, commits ONE fault through the cluster's fault committer naming the
refusal, the agent, the count and the bound, and stops offering the
self-rewake. A cluster declaring no bound refuses loudly rather than reading
absence as "no bound". Regression:
`seon.turn-test/a-refused-turn-write-is-bounded-and-commits-exactly-one-fault`
asserts the transaction count from the database — past the bound, `:max-tx` is
unchanged across further wakes.

**Class 1, the registry leak.** The literal reading of the first defect is
refuted: `seon.schema/register!` refuses outside an isolated candidate delta,
and the packaged declaration forms are re-derived from classpath resources with
no cache, so neither can be leaked into. The demonstrable root cause is
CUSTODY: an in-process test body runs on a thread that inherited the agent
evaluation's `seon.db/*conn*` binding (conveyed by `bound-fn` at
`src/seon/test.clj:128`), so a fixture helper using an elided `seon.db` arity
wrote the LIVE cluster's datoms — which is how the suite's synthetic
declaration became a durable fact on `default`. Fixed at three owners:
`seon.test.runner/run-var!` runs every test Var inside
`seon.db/call-without-custody`; the runner's drift detector gained
`::snapshot-schema-keys` so registry drift is reported BY KEY in both
directions; and `seon.test/run` restores a live cluster's projection when a run
changed its key set, naming the restored keys.
`seon.test-support/preserving-schema-registry` is the scoped helper, used by
`seon.schema-usage-guard-test`'s own fixture bracket. That suite's separate
~20-red cold failure had the same shape and a different cause — `install-forms!`
wrote the schema rows without advancing the fixture's projection state, so the
next `row-tx` compiled a declaration against a population missing its
references; it now installs through one seam both see.
