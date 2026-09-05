---
type: issue
status: open
severity: friction
tags: [issue, operator, database, class/n9, wave/publication-velocity]
---

# Complete source publication takes ~70 s against the ten-second law

## Problem

`seon.cluster/refresh-source!` (the complete `current-src` publication) took
70.2 s over the live prepl on 2026-08-06 (orchestrator probe, RUNNABLE in
malli var registration throughout) and drove the fresh-operator multi-init
lifecycle test into its 300 s liveness backstop (gate-fix-operator lane, same
day). The 2026-08-05 working edge recorded reset→republish→refork→READY at
2.7 s — either that measured a different operation (incremental? warm path?)
or publication genuinely regressed ~25×. Every init, reset, and fresh-boot
cycle pays this, taxing every fix loop (velocity outranks the queue).

## Needed

Attribution first: profile one complete publication (analysis, schema
population, malli registration, transaction batches) and name where the time
goes; compare against the 08-05 measurement's actual scope. Then either fix
the regression at cause or record the honest cost and revisit the
incremental path (`bin/seon init --changed`) as the default fix-loop surface.
Publication progress events (landed with the init-silence fix `b465b4613`)
give per-phase timestamps for free — use them.

The 2026-08-10 attribution is now recorded in
[the suite fat-tail report](../../prds/sci-execution-runtime/research/suite-fat-tail-2026-08-10.md).
On the current tree, namespace load took 11.334 s and the following complete
refresh took 45.079 s. Program rows consumed 34.781 s: declarations alone
took 22.786 s, keywords 3.770 s, and calls 4.351 s.

The progress callback is not observational today. Its presence divides every
ordered program phase into up to six writer transactions. A direct falsifier
using the same population with one transaction per phase reduced program rows
to 27.143 s and complete refresh to 36.487 s. Separately, the listed suite tail
contains at least 22 complete populations because tests and operator commands
rebuild private roots instead of forking one immutable source base. Fix both
classes: progress must not change transaction mechanics, and the owner-ruled
`source-base!`/`start-fork!` path must make repeated population structurally
unavailable to ordinary tests.

## Acceptance

- Progress and no-progress publication issue identical writer transaction
  shapes.
- One suite source digest produces exactly one base-build event; ordinary
test forks cannot invoke complete population.

## Re-observed in the 2026-08-13 boot gate

`bin/test seon.cluster.boot-test` measured
`incremental-source-refresh-publishes-without-touching-existing-clusters` at
156.980 seconds before advancing normally to the next test. This is the same
known complete-publication class, not the co-hosted-second-boot stall: the test
is declared long because complete incremental publication dominates, and it
then proves an existing cluster remains sovereign while a later cluster forks
the published commit (`test/seon/cluster/boot_test.clj:932-985`). The 2026-08-11
fat-tail measurement was 186.733 seconds for that same boundary, so the new
sample is faster but still violates the fast-by-default law by more than an
order of magnitude.
- A named cause with numbers; complete publication is back near its historical
  cost or an owner-ruled accepted budget has the fix-loop default adjusted.

## Re-profiled 2026-09-05

A fresh private operator root took 68.36 s real before the indexing repair.
Phase instrumentation found 56.323 s inside publication: clj-kondo plus source
row construction took 7.103 s, schema population took 6.023 s, and the five
program-graph writer phases took 39.096 s (namespaces 3.157 s, declarations
23.237 s, keywords 4.323 s, calls 8.379 s). Contract projection and per-row
contract derivation together took 1.235 s. The analyzed input is exactly the
236 `.clj`/`.cljc` files under `src/` and `test/` (5,077,708 bytes on this
checkout); `reference-code/` is not analyzed, and clj-kondo used the existing
19 MiB `.clj-kondo/.cache`.

The 2026-09-05 indexing repair reads and line-indexes each source file once,
parses each namespace form once per population, and compiles all program refs
to transaction-local tempids. That replaced five dependent program commits
with one. The matched instrumented publication fell from 56.323 s to 38.415 s;
the program boundary became 4.030 s of transaction compilation plus one
26.025 s Datahike commit for 207,915 datoms. The full cold command improved
only from 68.36 s to 63.29 s real, so this issue remains open: the irreducible
observed floor is now the real 208k-datom database population plus roughly
25 s of cold operator/source-JVM loading, not repeated per-row source reads or
per-phase commits. Reaching the target requires reducing the admitted fact
population or accelerating the Datahike bulk-load/store boundary without
weakening durability or program-graph queryability.

## Bulk-commit decomposition and repair, 2026-09-05

The 26.025 s population commit was not Datahike's transaction floor. A fresh
isolated file store using the production configuration reported
`:datahike.index/persistent-set`, `:keep-history? true`, a 256-entry diff
buffer, and no explicit branching factor, so it used persistent-set's
512-entry default
(`reference-code/datahike/src/datahike/config.cljc:18-24`,
`reference-code/datahike/src/datahike/index/persistent_set.cljc:471-471`).
The population transaction at that checkout asserted 207,924 current datoms.
Applying those datoms immutably with `datahike.api/with` took 2.728 s cold and
1.286-1.356 s warm. Flushing that value produced 3,035 pending index values;
root fusion removed six, leaving **3,029 durable key/value writes**.

That count names the dominant cost. Datahike flushes EAVT, AEVT, AVET and,
when history is retained, all three temporal indexes
(`reference-code/datahike/src/datahike/writing.cljc:48-84`), collects their
pending values, and passes one causally ordered batch to Konserve
(`reference-code/datahike/src/datahike/writing.cljc:482-525`). Konserve first
serializes every batch value into byte arrays
(`reference-code/konserve/src/konserve/impl/defaults.cljc:417-475`),
then handles that batch as a sequential loop. For **every** key it writes and
forces the staged file, renames it, opens the store directory, calls
`FileChannel.force`, and closes it
(`reference-code/konserve/src/konserve/filestore.clj:65-75,91-155`).
A live JVM sample during the original population caught the writer exactly in
`filestore/sync-base` -> `FileChannel.force`. The identical history-retaining
commit with Konserve `:sync-blob? false` took 2.623 s end to end. Disabling
durability is not a fix; it is the falsifier showing that serialization plus
ordinary file writes are approximately the pure transaction cost, while the
roughly three thousand file and directory force barriers account for about
90% of the durable commit.

The owned repair is to create persistent-set stores with branching factor
4,096 while retaining the 256-entry diff buffer, history, root fusion, the
self writer, and Konserve's default durability. A larger node reduces the
number of immutable node versions that one bulk population must force. The
measured progression was monotonic:

| persistent-set configuration | pure `with` | durable index KVs after fusion | population commit |
|---|---:|---:|---:|
| factor 512, diff 256 (before) | 2.728 s cold; 1.286 s warm | 3,029 | 29.029 s direct; 26.025 s matched publication |
| factor 1,024, diff 256 | 2.021 s | 1,476 | 3.773 s with force disabled |
| factor 2,048, diff 256 | 1.661 s | 734 | 2.600 s with force disabled |
| factor 4,096, diff 256 (after) | 1.762 s | **357** | **4.870 s durable** |

The factor-4,096 durable run asserted the same 207,923 benchmark datoms and
retained history. Reopen/read checks did not trade the write win for a visible
read regression: one cold unique-identity query was 1.80 ms versus 2.87 ms,
1,000 warm identity queries were 3.16 ms versus 8.47 ms, and a full 222k-datom
EAVT scan was 149 ms versus 414 ms in the factor-512 comparison. These are
directional single-host samples, not a general read benchmark, but they
falsify an immediate large-node read penalty on the population this setting
exists to hold.

A completely fresh operator root after the change completed `bin/seon init`
in **39.63 s real**, down from the matched pre-repair 63.29 s. The population
commit itself fell from 26.025 s to 4.870 s (81.3%). The remaining roughly
35 s is JVM/operator loading, source analysis, contract derivation, schema and
other publication work, so this issue stays open against the ten-second law.
The index configuration is creation-fixed
(`src/seon/cluster/store.clj:136-173`): existing operator roots keep their old
factor until their disposable store is reset; no migration path is implied.

### Rejected levers

| lever | measurement | ruling |
|---|---|---|
| Chunk to 50,000 operations | 5 commits, 42.968 s | Worse than one 29.029 s commit because paths, commit record, and head are forced repeatedly. |
| Chunk to 10,000 operations | 21 commits, 44.327 s | Same class; smaller chunks do not recover throughput. |
| Diff buffer 1,024 / 4,096 / 16,384 at factor 512 | 3,012 / 2,959 / 2,952 durable KVs | Does not address leaf/node fanout; factor 4,096 produces 357. |
| Numeric entity IDs | 1.286-1.356 s warm pure transaction | Current compilation already replaces lookup refs with transaction-local string tempids (`src/seon/fn.clj:1816-1848`). An intentionally heavier 30,469-string-tempid replay took 1.358-1.501 s; ID resolution is hundreds of milliseconds, not tens of seconds. |
| `:keep-history? false` | 0.861 s pure, 1,956 durable KVs | It removes 121,915 temporal entries, exactly the population's cardinality-one assertions. The policy is creation-fixed for the whole operator-root store (`src/seon/cluster.clj:753-786`), and a cluster branch inherits its source database configuration (`reference-code/datahike/src/datahike/versioning.cljc:251-270`; `src/seon/cluster/registry.clj:271-280`). Running clusters need retractions in history for call-preparation revision derivation and blob reachability (`src/seon/call_preparation.clj:272-292`; `src/seon/cluster/registry.clj:345-361`). There is no honest publication-only switch. |
| Drop `:seon.fn/calls` and `:seon.fn/keywords` | Removes 69,981 current datoms; 3,029 -> 2,340 durable KVs | A useful later reduction, but ruling 50 requires replacing these sets with queryable usage entities before deletion. Removing them alone breaks current program-graph consumers. The branching-factor repair preserves queryability. |
| Hitchhiker-tree | not run | The live store and dependency default are persistent-set, and Hitchhiker-tree is an optional dependency absent from the runtime classpath. Persistent-set's owned creation setting removes the measured write amplification without adding a second index mechanism. |

The remaining dependency opportunity is a Konserve file-batch durability
primitive that forces staged files, publishes child renames, forces the
directory once, then publishes and forces the mutable head last. Implementing
that causal barrier inside Seon would duplicate the storage authority, so it
is not part of this repair.

Integration note: `test/seon/cluster/store_test.clj:85` mirrors the former
exact `{:diff-buf-size 256}` creation map. That test is outside this lane's
owned paths and must accrete `:branching-factor 4096` before the full suite;
the focused `seon.fn-test` regression above owns the repair in this slice. A
direct `bin/test seon.cluster.store-test` confirmed this one stale expectation:
17 tests, 62 assertions, 1 reproducible failure and 0 errors.
