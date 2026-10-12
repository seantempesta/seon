---
title: Acquisition and recording cost
type: research
status: in progress
---

# Cost acquire, 2026-10-12

Default runs `94f03ca5c`, including E4. Both status entrances answer but
report existing acquisition errors, cache warnings, 15 errored evaluations,
one file without a namespace and one unowned namespace. This lane leaves
default running. The task for acquisition is
`seon.mcp/a-fresh-agents-first-eval-request-acquires-its-branch-cheaply`.

## Fresh acquisition

Two concurrent pairs, released by one promise inside root's JVM MCP request,
evaluate `(+ 1 2)` under distinct fresh agent ids. Each pair is measured by
`seon.profile/measure`. Pair one: 329.93 ms, 399.70 MB allocated JVM-wide,
522.60 ms process CPU; individual `:custody` stages 221.4 and 237.1 ms.
Pair two: 278.61 ms, 390.63 MB, 517.44 ms CPU; individual stages 201.9 and
165.6 ms. The 48.4-second outlier did not reproduce. These windows include
both requests and any other concurrent activity.

Each pair calls `seon.cluster.registry/branch!` four times,
`seon.cluster.store/open-branch!` four times, and `seon.db/transact-call`
eight times. Per agent, the fork, parent admission and branch admission
precede the evaluation's recording transaction. SCI still forks a branch
base and an agent context; E3's dependency on E5 remains as described in
program-context-notes.md.

### Datahike branch head

Datahike pin `ec907fb51e10b5562652c2ab33b8b460abe0e6f7` already provides
`datahike.writing/compact-head` and `datahike.writing/resolve-head`. Ordinary
commits use the pointer representation, but `datahike.versioning/branch!`
serializes the complete selected database again. Its indexes and commit
already exist. The smaller composition uses the existing commit pointer
when the branch has no newly derived secondary-index state and the store
retains commits. Stores without a commit graph and branches with derived
secondary-index state keep their complete stored value.

This is equivalent for readers: `resolve-head` restores the destination
branch in configuration. Work is proportional to a commit pointer rather
than the selected database's fused index roots. No new state or encoding.

On default, Fressian encoding the retained base of `cost-acquire-fresh-a`
with the dependency's index, root and datom handlers costs 0.70 ms,
611,136 allocated bytes, and writes 38,782 bytes. Encoding its existing
compact-head equivalent costs 0.04 ms, 6,368 allocated bytes, and writes
55 bytes. This isolates serialization; it does not establish an end-to-end
acquisition improvement. An earlier probe omitted the index handlers and
failed; it is excluded from the comparison.

## Schema derivation

A subsequent request on `cost-acquire-fresh-a` measures 51.92 ms overall,
47.7 ms inside the request and 33.4 ms recording. It calls
`seon.schema.datahike/compiled-storage` 45 times and calls neither
`seon.schema.datahike/storable-attribute-in?`,
`seon.schema.datahike/declared-storable?` nor
`seon.schema.internal/reference-id`. The reported whole-schema counts do
not reproduce in this warm request. Further evidence must distinguish
first use of a declaration registry from repeated use.

The repeated whole-schema operation is independently reproducible at
`seon.schema/canonical-database-attributes`. The same held immutable value
selects 1,160 attributes; a second call makes 6,475
`seon.schema.datahike/declared-storable?` calls, 6,347
`seon.schema.datahike/compiled-storage` calls and 6,423
`seon.schema.internal/reference-id` calls. It costs 12.22 ms and
18,749,640 calling-thread bytes. The explicit zero-call proof returns
false (6,475 calls; 11.24 ms; the same allocation).

`seon.cluster/declared-attributes` already retains its outer result, which
explains why the ordinary warm probe need not reach this repeated work.
Other callers reach `seon.schema.datahike/database-attributes-in`,
`seon.schema.datahike/database-attributes-core-in` and
`seon.schema.datahike/database-attributes-for-in` directly. The selection
owner now uses the existing `seon.schema/projection-cache-value`, keyed by
registry and selected forms, so a subset cannot replace the complete
population. The first derivation remains proportional to the declarations;
unchanged calls retrieve the held value. Malli's `malli.core/-cached`
already retains each compiled schema's storage facts; it does not retain
this population selection. No new cache or invalidation is introduced.

On default, the proposed composition using that existing cache returns the
same value with zero storable checks, 760 calling-thread bytes and a
reported 0.00 ms (below the measurement's 0.01 ms resolution). This is a
composition probe, not a measurement of newly loaded source. The subject of
commit `17ec81f0b` incorrectly says 0.02 ms; 0.00 ms is the observed value.
Installed
before/after request timing still awaits root's restart.

Full working-tree lint during the schema slice finds two foreign errors in
`test/seon/cluster/db_flow_test.clj`: references to the renamed
`seon.cluster.registry/roster` and `seon.cluster.agent/roster-facts`.
The file belongs to the rename lane. The candidate check uses HEAD plus
this lane's paths; this lane does not edit that file.

## Verification

Schema commit `17ec81f0b`: candidate `193ee429` passed four tests,
37 assertions, run `a0d8ec3507cb`, 4.314 seconds in test bodies and
41.891 seconds for the check. Its candidate lint had zero errors.

## Recording transaction

Root independently restarted default at `0a8e85e1a`, with the branch-pointer
change installed. The same concurrent fresh pair costs 299.09 ms and
374.47 MB JVM-wide; the individual acquisition stages take 169.5 and
181.4 ms. This does not establish the requested few-millisecond acquisition
cost. Raw replies are in
docs/research/agent-platform/2026-10-12/cost-acquire-fresh-after.txt.

Three successive requests on one agent cost 192.55, 62.63 and 52.05 ms;
their recording stages cost 36.7, 42.3 and 34.4 ms. The third request's
writer allocates 7.6018 MB and takes 6.95 ms CPU; its commit allocates
14.8564 MB and takes 12.61 ms CPU. Raw replies are in
docs/research/agent-platform/2026-10-12/cost-acquire-warm-before-recording.txt.

The live measured call-count proof is red: a warm request calls
`seon.turn/system-run-call` twice. It costs 54.47 ms overall, 50.0 ms inside
the request, 36.0 ms recording. A later repetition during edit-hook work
still calls it twice, at 90.96 ms overall and 59.2 ms recording; that slower
sample is not attributed to this change. This confirms the open item in
cost-record.md on the newly restarted default.

`seon.turn/system-run-plan` emits the run's namespace reference before the
namespace identity declaration. Datahike's tempid upsert consequently
repeats the transaction. The owner now places its existing source data,
which begins with namespace declarations, before the run. There is no
additional state or transaction. The existing request-count test now
measures the MCP request and asserts one `seon.turn/system-run-call` entry.

An immutable `datahike.api/with` comparison on the same held default
database uses a transaction-function counter and the same materialized
transaction data. Before: two calls, 0.33 ms, 577,752 calling-thread bytes;
namespace declarations first: one call, 0.25 ms, 379,232 bytes. Both produce
13 datoms. These numbers exclude the repeated producer, writer validation
and durable commit; they are not an end-to-end recording improvement.

Full working-tree lint for this slice: zero errors, 1,580 warnings,
6.224 seconds. The earlier foreign rename errors have been corrected by
their owner. The source edit hook exceeded its PREPL bound and reported
an unknown outcome; candidate verification remains necessary.

Candidate `5f4be93f` ran five selected tests: 36 assertions passed, one
failed, no errors, 5.191 seconds in test bodies. The new one-call proof and
namespace/source recording checks passed. The failing
`seon.request-cost-test/a-jvm-reply-carries-its-request-cost` expects positive
calling-thread allocation but receives 0.0 MB. Checking committed baseline
`50f51c94f` alone reproduces the same failure: run `0ee50b8e9c22`, five
assertions passed, one failed, no errors. This is a pre-existing allocation
reporting defect, not changed by namespace ordering. The assertion remains
unchanged; the commit names the four other relevant tests individually.

The LZ4 frame allocation and `seon.db/write-owned-values-error` remain open.
The installed LZ4 frame stream has a 64 KB minimum block and no public
reset/reuse operation. Changing encoding needs its own compatibility proof.
The database owner remains claimed by the rename lane.

The edit hook cannot install the changed dependency acceptance namespace in
the lane's SCI context: acquisition reports errors, as the preceding cost
lane observed. This dependency slice uses the candidate JVM path.

First commit `189cb6dae` points Datahike to `c9e57c6f`. The dependency's
archived test passed 15 assertions. Candidate `c8cb0ac4` passed three tests,
22 assertions, run `2a7b220129c8`; its check took 84.887 seconds, including
27.869 seconds filling compiled classes and 24.428 seconds boot readiness.
The proof was red before the change: 547,408 allocated bytes and a full
head, run `c792bcfa689b`. Full lint: zero errors, 1,575 warnings.
The dependency hook now analyzes `superv.async/go-loop-try` by its actual
supervisor expression and ordinary loop; refreshing dependency analysis
alone did not resolve the prospective-edit errors.
