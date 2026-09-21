---
type: issue
status: open
severity: friction
tags: [issue, test-fixture, publication, schema, performance]
---

# Full publication exceeds the test liveness bound at commit projection acquisition

On 2026-09-17, Stage 1's plain fast run in the detached worktree at
`f77fa320f` with its owned selector/cache/publication overlay exited 124.
The reporter observed 320 seconds without progress after beginning
`seon.cluster.source-test/stale-incremental-upsert-preserves-the-newer-publication`.
No complete suite tally exists. The test performs two complete publications;
it carries no `:seon.test/long-ms`, unlike several other complete-publication
tests in the same namespace. This observation alone does not justify changing
the bound or establish that compilation is redundant.

The virtual-thread-aware dump records the main thread in Malli schema
compilation reached through `seon.schema/derive-projection-from-database`,
`seon.db/carry-derived-projection`, `seon.cluster.source/database`, and
`seon.cluster.source/publish!`. No deadlocked thread ids were reported.
The owned run's earlier dump showed schema-shape encoding; the work had moved.
Neither timing nor these samples establish a projection-cache root cause.

Evidence is in `tmp/test-system-stage1-inputs/fast-8.log`,
`tmp/test-system-stage1-inputs/fast-8-all-threads.json`, and the retained
watchdog logs under `tmp/test-system-stage1-inputs/liveness-8/`.
Reproduce through the canonical armed harness:

```sh
timeout 2400 bin/test-fast seon.test.selection-test seon.fn-test seon.cluster.source-test
```

The database and schema files were concurrently held, so this lane changed
neither their implementation nor test liveness overrides. Verify the actual
publication cost and its repeated phases before choosing between fixing
redundant work and declaring the integration test's intended duration.
Acceptance is an armed complete run with a declared bound and a positive
publication verdict, not a larger silent wait.

## Slice 1 observation — 2026-09-22 redesign assignment

The armed `--paths` fast snapshot at `f08558cb5` ran
`seon.shell.jvm-test/an-evaluations-deadline-reaps-the-child-it-admitted`
in 177.3 s (2026-09-20T18:26:17.187Z–18:29:14.504Z, PID 41038).
The test passed. A sample at process elapsed 947.15 s had the main thread in
`seon.issue/index-tx`, reached during its fixture's complete publication.
`seon.test-support/populate-published-root!` publishes afresh when
`seon.test.published-base` is absent (`test/seon/test_support.clj:102–129`);
that is the fast harness's declared behavior. The shell tests' physical
blob/process observations remain intact. Their metadata now declares the
publication work and its bound; no fixture cache or second publication path
is added. Fixture implementation belongs to the concurrently assigned
bridge lane and is unchanged by this slice.

## Slice 2 observation — 2026-09-22 redesign assignment

The `05c77ac1d` fast snapshot plus slice 2's initial overlay, PID 62270,
spent **100.779 s** in
`seon.cluster.source-test/an-activation-closure-with-empty-member-collections-seals`
(20:23:04.693–20:24:45.472 UTC in the process log). Its thread sample shows
`schema/canonical-value-string` / `canonical-coll-string`, reached from
`source/database` → `db/carry-derived-projection`. The test did finish;
it was slow, not shown deadlocked. The following complete-publication test
was in contract projection (18.326 s) when the lane terminated this broad
run after six independent function-suite errors had already been reported.
Exit 143 is not a suite verdict. The focused publication regressions continue
separately. Neither schema owner nor the source test fixture was changed.

## Slice 3 observation — 2026-09-22 redesign assignment

A one-file docstring publication on the lane's own running host, after
removing repeated manifest searches and duplicate projection construction,
still spent 6,608 ms acquiring its manifest/database, 2,548 ms in the
reconciliation transaction and 4,355 ms in activation sealing. This is a
development reload measurement, not the final armed-boot result. The landing
note records the final run separately.

The remaining algorithms are identifiable in source. `source/database`
materializes an immutable published commit and calls
`schema/projection-from-database`; that function queries all schema,
contract and function-source rows, then parses and compiles their complete
projection (`src/seon/schema.clj:2705`). Activation independently derives
that projection to validate the complete activation closure. These are
O(program), not O(the changed declaration). Carrying the existing immutable
projection from its connection and updating it from the transaction report
is the relevant existing seam; adding another publication cache is not.

`db/write-report-error` also invokes `arity-mismatches-with` over the entire
resulting database after every transaction with affected entities
(`src/seon/db.clj:4061`). Its own `affected` set and the reverse call edges
provide the input for O(change + callers) validation. The publication lane
can reduce submitted datoms but cannot remove that whole-program query
without changing the database writer owned by the concurrent error lane.
Neither boundary is described as acceptable latency, and no timeout was
raised to hide it.

## Slice 4 item 1 — 2026-09-22 redesign assignment

Transaction-report adoption landed at `245f693f6`; the own-root live docstring
edit fell from 139,859 ms to 48,982 ms. Only the edited namespace reloaded and
only its three fresh Clojure roots received new wrappers. The remaining
activation/sealing and final deletion-comparison span was 8,264 ms. The publisher
still derives and writes complete activation membership, while boot requires
that stored closure. Keeping that contract with incremental membership versus
removing the duplicate membership is the explicit decision at the assignment's
permitted item 1 stop. The three scoped options and retained before/after
evidence are in the
[redesign landing note](../../prds/steward-platform/research/one-jvm-redesign-2026-09-22.md).

## Ordinary error-write measurement — 2026-09-23 assignment

The error lane's canonical armed probe measured an unrelated complete arity
scan at 121.77 ms on one error write. Final-report validation now uses the
changed roots already discovered by its owning-value walk to enter arity
admission only for program declarations, shared schema shapes, and supplied
defaults. Ordinary error writes execute zero arity scans. Program-changing
writes still enter the existing complete arity check; incremental checking of
program changes is not claimed by this result.

Two retention snapshots also read every issue's activation, creator, members
and member identities on every write: 273.83 ms in the split. Retention now
uses the final report's touched entities, reverse AVET seeks for retaining
owners, and per-entity EAVT history. The measured replacement is 4.82 ms.
The regression covers expanded transaction-function output, retained-target
identity removal, enduring creator authority, and nonempty membership.

The supplementary program-write probe reaches arity admission but returns
`seon.call-preparation/incoherent` for `:seon.db/connection`: the published
`seon.db/supplied-connection` return declaration does not agree with its
supplied-default value schema. That is the actual refusal, not the intended
argument-count mismatch. The new test proves that program writes still enter
arity admission; it does not claim to verify the mismatch diagnostic past
this refusal. The complete return is recorded in
`tmp/error-write-split-selection.log`; the reproducible probe is
`test/seon/error_write_timing_test.clj`. Supplier coherence is outside this
bounded error-write performance assignment.

The final split, per-test durations and remaining cold gate are in the
[error landing note](../../prds/steward-platform/research/error-family-1a-2026-09-19.md).
This issue remains open for the publication work and program-write behavior
described above; the ordinary error-write scan is fixed.

The remaining admission gap was also measured: integer normalization walked
the complete projection in a `:db.fn/call` argument, taking 177.29 ms and
replacing in-memory argument objects. Two canonical regression assertions
falsified that behavior before the fix. The same normalizer now handles
submitted storage entries and metadata, leaves function arguments unchanged,
and normalizes the function's returned transaction data. This is necessary
because Datahike requires Java Long values for `:db.type/long`; merely skipping
the arguments exposed Integer values produced by the error recorder. The
regression verifies preserved request identity and successful stored integers
from both native entries and transaction-function output.
No latency target or cold gate pass is claimed.

## Error result recording observation — 2026-09-23 assignment

The refreshed-base error regression confirms the same writer cost outside
publication. In `tmp/error-result-carried-fast.log`, result preparation and
recording take 19.55–274.79 ms, while the five corresponding transactions
take 863.54–3,040.20 ms. The one-second recording-plus-transaction assertions
remain intact and fail; this is not a successful latency proof.

A thread sample of that run's JVM (PID 13330,
`tmp/error-result-carried-threads.txt`) catches the canonical cluster seed's
writer in `seon.db/arity-mismatches-with`, reached from `write-report-error`.
Source confirms the query walks all stored call-arity edges and declaration
bounds whenever any entity is affected (`src/seon/db.clj:3802`, `:4061`),
including ordinary error writes. That sample establishes the repeated
whole-program work, not the fraction of each transaction it consumes.
The existing affected-entity set and reverse call edges are the inputs for
checking changed declarations and their callers at the writer.

The error owner separately discarded carried projections and reparsed all
program declarations. A first-run sample caught
`render-faults-html → projection-from-database → projection-from-rows →
clojure.edn/read-string` (`tmp/error-result-refreshed-threads.txt`). The
error recording, transaction function and readers now reuse the database's
immutable carried projection or the explicitly supplied projection. No
process-global cache or second registry was added. The remaining database
validation algorithm is outside this assignment's three permitted database
reader sites; it was not edited.

The agent regression also measures its first canonical SCI acquisition at
35,255.36 ms, followed by a 29.42 ms agent fork. Its sample
(`tmp/error-result-sci-threads.txt`) reaches `load-core-namespaces!` and
namespace initialization through `host-namespace!`; this is full initial
program acquisition. That integration test declares a 60-second bound and
the acquisition reason, while retaining the separate one-second writer
assertion. The ordinary object and nested-value tests retain their default
five-second bound. See the per-test table and final tally in the
[error landing note](../../prds/steward-platform/research/error-family-1a-2026-09-19.md).


## Documentation-only report validation — 2026-09-23

The redesign lane's isolated five-entity docstring write ran full arity checks
at 702.666/481.017 ms despite no changed calls/contracts. Effective report
changes now decide arity relevance, including changed owned components;
idempotent function identity assertions no longer scan render declarations.
Final validation measured 76.501/7.935 ms with both unrelated scans absent.
The fixture still returns the supplied-connection coherence refusal above:
`2e9031a125aa`, base `0e3ced...`, 39 commits behind HEAD. The intended arity
mismatch diagnostic is not proven past that boundary. Full evidence and
remaining ordered work are in the redesign landing note.
