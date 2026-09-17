---
type: research
status: complete
tags: [reset, write-admission, publication, performance]
---

# Reset prerequisite: publication validation cost

The edge retype must publish a complete program. This prerequisite retains
atomic final-report admission while removing repeated work from that path.
The reset batch remains RESET NEEDED; this change does not retype an attribute.
Default was observed alive at PID 33583 and was neither restarted nor reset.

The required-many blocker and writer-thread blocker were read end to end.
The sparse source-test assertion was inspected with its canonical fixture.
A single read-only default evaluation found `seon.id/id` present with a file
and core provenance (4 ms, complete returned envelope). The canonical test
then proved that a sparse update to that existing row is valid, while a new
incomplete function refuses without advancing the basis. The old expectation
confused an upsert with a create; the validator did not need weakening.

## Dependency ledger and decision

Tested gitlinks: Datahike `73afe78271a289861da236c5ac3457e64349653f`;
Malli `3517a3cd9271b2083780ac7be1725493905bca2e`. Neither fork was changed.

- `reference-code/datahike/src/datahike/db/transaction.cljc`, `validate-report`:
  the callback sees the final immutable database and both attempted and
  effective datoms. A non-nil refusal aborts the serial writer transaction.
- `src/seon/db.clj`, `write-validator`: Malli validators already live in the
  immutable projection's runtime cache. Another per-schema cache alone is
  not a fix.
- `src/seon/schema/datahike.clj`, `resolve-datahike-form-in`,
  `edn-encoded-attr-in?`, `decode-attribute-value-in`: the bridge owns alias
  resolution and EDN codecs. The optimized loop derives their decisions once
  and retains the existing decoder for encoded values.
- `src/seon/schema.clj`, `projection-cache-value`: derived values belong to
  their projection, not a process-global map. The installed schema is also
  part of the attribute-plan cache key, so changing storage cardinality cannot
  reuse a plan for the old database schema.

| Option | Cost and guarantee | Decision |
|---|---|---|
| Validate only effective changed entities | Still visits the entire new population at a reset. Dropping attempted assertions changes the contract for idempotent or repaired invalid assertions. | Not selected. Both attempted and effective entities remain included. |
| Reuse compiled validation and normalization at the final-report seam | Compile an attribute's member validator, codec choice and collection grammar once per projection/cardinality; one installed-schema plan lookup per report, then direct checks per datom. Retains every admission decision on the writer. | Selected. No schema-policy relaxation and no new dependency-fork protocol. |
| Validate off the writer before offering a commit | Same validation work plus a protocol to bind a fully expanded candidate report to its exact branch basis and retry stale candidates. Validating submitted forms cannot see sweep/transaction-function results. | Not selected: a pre-read alone cannot guarantee final admission, and a new commit protocol is larger than the measured local fix. |

The final-datom fast path validates the same resolved scalar or many-member
form. Its diagnostic fallback is the existing `write-attribute-error` owner.
Whole-entity validation still includes prior identities and swept referrers.
Render-target and preparation-aware call-arity checks remain in place.
A regression expands an invalid assertion followed by its repair inside a
transaction function: the whole transaction still refuses atomically.

Profiling also found repeated `pr-str` calls inside the program identity sort
comparator. `index-tempids` now formats each distinct identity once before
sorting, preserving deterministic tempid ordering. This removes repeated
printing without changing the transaction's identity domain.

## Measurement protocol

Both timed path snapshots use HEAD `defd915cd8474ecec87eb6b64d29572ad7338d09`.
The baseline overlays only the corrected/timed source test. The candidate
also overlays `src/seon/db.clj`, `src/seon/fn.clj`, and `test/seon/db_test.clj`.
Each runs the canonical armed harness with three shared test slots. The test
prints `complete-program-publication-ms` around the real `publish` call on a
fresh file-backed source store; manifest construction and initial canonical
fixture construction are outside that interval. These are loaded-host wall
clock observations, not a worst-case latency guarantee.

The initial baseline run used JDK Flight Recorder on its own test JVM 88643
only, from test startup into source publication. It recorded 24,168 execution
samples; 3,972 retained a `write-report-error` frame in the recorded stack.
The first Seon frame was the identity-sort comparator in 3,021 samples and
canonical schema serialization in 3,368. Within final-report stacks, repeated
instrumented calls/cache acquisition were prominent. These are samples,
not milliseconds, and truncated stacks can omit the validator frame.
Schema serialization remains a separate observed cost in the concurrently
edited schema owner.

Reproduction of the sample summary is committed as
[`reset-writer-profile-2026-09-17.py`](../../steward-platform/research/reset-writer-profile-2026-09-17.py).
Capture the lane's own test JVM with `jcmd PID JFR.start name=reset-writer
settings=profile filename=ABSOLUTE_PATH dumponexit=true`, stop that recording
with `jcmd PID JFR.stop name=reset-writer`, then follow the script's usage.
The recording does not replace the wall-clock publication measurement.

The unoptimized timed publication took **75,941.046542 ms**. Its enclosing
regression ran from `02:06:51.656166Z` to `02:11:30.669807Z` (279,013.641 ms),
which demonstrates why the whole test duration is not the publication duration.
The measured case completed without failures. The timed baseline remainder was
stopped deliberately (exit 143) to release its slot: the earlier full baseline
namespace already passed 17 tests / 149 assertions, exit 0. No full-namespace
green verdict is claimed for the stopped measurement run.

Candidate attempt 1 failed fixture construction because its plan map omitted
Datahike's implicit schema datoms; it completed 127 tests with 10 failures and
94 errors. The revised candidate derives those plans from the dependency's
`implicit-schema-spec` plus installed schema, preserving the original check
for undeclared attributes. Two queued obsolete candidate snapshots were stopped
before launching a JVM. These attempts establish no speedup or green result.

## Verification boundary

The coordinated edge publication still needs the producing digest constructors,
program row schema and schema-key deletion consumers. At entry,
`src/seon/program.cljc`, `resources/seon/schemas/seon.program.edn`, and
`src/seon/schema.clj` carried foreign edits. No foreign hunk was included in
these test snapshots. Selection is released. Required-many corrections are
merged into each owning resource table in the reset plan; they are not claimed
implemented by this performance change.

No `bin/test`, default publication, default restart/reset, or dependency fork
change is claimed. Fast harness evidence is iteration evidence; the
orchestrator owns the cold gate and the eventual reset/live proofs.

Shared-tree lint additionally observes the in-flight `build-base-ctx` change:
HEAD accepts zero arguments, while dirty `src/seon/sci/eval.clj` requires an
explicit projection. `source_test.clj` still contains the HEAD-compatible call
in `:removed-source`; the release must update that caller together with the
SCI constructor. It is not an input to the HEAD-plus-owned-paths snapshot.
The other standalone lint error is the already documented macro-generated
`parser.type/->Variable` constructor (`kondo-does-not-resolve-datalog-parser-generated-variable-constructor`).

## Measured candidate

The optimized complete publication took **41,644.180417 ms**, versus
**75,941.046542 ms** before: **34,296.866125 ms less (45.16%)**. Both calls
publish all program rows to a fresh file-backed source store with an already
built canonical manifest. This is not the wall time of `bin/seon reset`, and
it does not include manifest analysis. The candidate's enclosing regression
started at `02:17:04.675580Z` and ended at `02:17:50.248934Z`; its fixture and
manifest had already been built by preceding namespaces, so that whole-test
interval must not be compared to the baseline's cold whole-test interval.

The first successful-fixture candidate run reached one stale assertion in
`seon.db-test/diff-refuses-missing-identity-and-external-sinks`: after
`cdfc01058`, config/effective needs an explicit cluster. The corrected test
supplies `"default"` to the diff call and still expects row-identity-absent.
Production files were unchanged for the corrected rerun.

The corrected database/schema snapshot at `ac375cd8613e21174227b238601ed6062c0a98b6`
passed **75 tests, 1,007 assertions, 0 failures, 0 errors**, exit 0. Between
its base and the timed base `defd915cd`, only the working-edge document changed.
This run covers final expanded assertions, required-ref sweeps, preparation-aware
arity admission, render targets, dynamic declarations, EDN values, and distinct
projection validator ownership. It is fast armed iteration, not a cold gate.


Final combined candidate result at `defd915cd`: **127 tests, 964 assertions,
1 failure, 0 errors**, exit 1. The sole failure was the stale config/diff
fixture described above. `seon.fn-test` (59 tests) and
`seon.cluster.source-test` (17 tests) completed without failures or errors;
the corrected `seon.db-test` subsequently passed with `seon.schema-test`
(75 tests / 1,007 assertions, exit 0) against unchanged production bytes.
This is four verified namespaces across two runs, not a claim that the first
combined command exited green. No broader repetition was needed after the
focused fixture correction.

Commands (all with `SEON_TEST_SLOTS=3`):

```sh
bin/test-fast --paths test/seon/cluster/source_test.clj -- seon.cluster.source-test
bin/test-fast --paths src/seon/db.clj src/seon/fn.clj test/seon/db_test.clj test/seon/cluster/source_test.clj -- seon.db-test seon.fn-test seon.cluster.source-test
bin/test-fast --paths src/seon/db.clj src/seon/fn.clj test/seon/db_test.clj test/seon/cluster/source_test.clj -- seon.db-test seon.schema-test
```

Final ownership check at `7e193e85b`: `seon.program.edn` and selection are
released; `src/seon/program.cljc`, `src/seon/schema.clj`, and
`src/seon/sci/eval.clj` remain modified by other lanes. The edge/provenance
publication remains a separate coordinated unit, **not landed here**.
Its RESET NEEDED marker stays in the single reset plan. The performance
prerequisite itself changes no stored type and needs no reset. Default was
rechecked alive at PID 33583. The runner removed this lane's finished/stopped
snapshot roots; no lane worktree or test JVM remains after completion.

Owned paths in this checkpoint: `src/seon/db.clj`, `src/seon/fn.clj`,
`test/seon/db_test.clj`, `test/seon/cluster/source_test.clj`, the reset batch,
this landing note, the profile summary script, the required-many issue,
the writer-thread issue, and the related test/check cluster-identity issue.
