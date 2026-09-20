---
type: research
status: in-progress
tags: [test-fixture, publication, measurement]
---

# Published test fixtures and incremental selection

Assignment: owner-approved test-system cut, 2026-09-23. The checkout clock
reports 2026-09-20; the filename follows the assignment date.

Read: AGENTS.md, the complete one-JVM publication redesign, the steward
working edge from `2026-09-22 ~22:40 local` through its end, and the
clojure-testing, datahike, data-oriented-clojure and repl skills.

## Dependency evidence

- Datahike `versioning.cljc:224–280` reads the selected stored database,
  writes its index roots under the new branch, then updates `:branches`.
  It does not analyze or transact the source population.
- `src/seon/cluster/registry.clj` owns production branch lifecycle;
  `test/seon/test_support.clj` already isolates fixtures with Datahike
  `branch!` and retires each connection before deleting its branch.
- The existing exported-store copy protects published bytes from Konserve
  connect-time migration. The fixture now connects that private file store
  directly. Datahike `store.cljc:91–104` invokes Konserve
  `sync-on-connect` for a tiered store: the memory frontend copies every
  missing backend key, and later connections still enumerate the keys.
  Removing that wrapper removes work proportional to the entire store from
  branch acquisition. No new cache is introduced.
- `src/seon/schema.clj:2737` derives a projection from database rows;
  `seon.db/carried-projection` reads the projection carried by that value.

## Initial findings

`bin/test --fast --paths` resolves `tmp/published-base.edn` but does not
pass `seon.test.published-base` to its JVM. Plain `bin/test-fast` also omits
it. The canonical fixture consequently takes the full population path.
Cold workers do receive this property. Two fixture regressions explicitly
call `create-base nil` and therefore repeat full population even there.

The liveness declaration now lives in `src/seon/test/bounds.clj`, rather
than the runner line cited by the assignment: 270 s ordinary exchange,
19,760 ms fixture preparation and 30 s reporting allowance, totaling 320 s.

## Verification boundary

Unrelated dirty error-lane files were preserved. Iteration uses
`bin/test-fast --paths` with only this lane's files. No default lifecycle
operation, cold gate or complete tier is authorized for this lane.
The orchestrator still owes the cold selected gate and platform proof.

## Measurements

Before (HEAD `423f62e5d9`, fresh JVM PID 76789, recorded run `c52aa334a6e2`):
first `with-database` **188,907.588458 ms**; subsequent p50 **5.007625 ms**.
Ten subsequent samples in ms: `16.401208 4.638 3.754583 17.395667 5.007625
3.75525 4.82225 14.964625 5.26975 14.722166`. The recurring timing regression
executed with 13 assertions, one expected first-use-bound failure, zero
errors. At 85.38 s of base construction, its thread was in
`seon.fn.schema-shape/shape-row`, under `seon.fn/index!`,
`seon.cluster/populate-source!`, `populate-database!`, `create-base`.

After removing indexing alone (recorded run `f9fba6b0612a`): first
**45,189.593334 ms**, subsequent p50 **183.183667 ms**. Observed real calls:
directory copy 177.724375 ms; database projection derivation 5,597.023333 ms;
SCI acquisition 33,223.458375 ms. SCI loaded namespace initialization that
called `seon.config/defaults` and compiled database attribute declarations.

After deferring SCI until requested and removing the redundant branch
reconnect (recorded run `f8e48aecca1d`): first **11,868.850292 ms**,
subsequent p50 **96.067041 ms**. Projection derivation 4,901.5795 ms;
directory copy 201.354583 ms; no SCI acquisition. The first-use target
remains red. This is not a landed slice or a satisfied 10-second owner bound.

The measurement lives in
`test/seon/test/fixture_timing_test.clj`, measuring the first fixture plus
ten subsequent acquisitions, including release, with the ordinary armed
runner. It also measures projection, SCI acquisition and directory copying
through their real functions.

Before narrowing the liveness declaration, the remaining acquisition cost
must be explained. A 2,000 ms allowance would currently expire real setup.
The proposed bound edit was withdrawn rather than treating a target as a
measured allowance. Scope clarification is pending before edits to owners
outside the assignment's listed files; slice (2) has not started.

The edit hook observed a foreign syntax error at `src/seon/cluster.clj:1465`
during the redesign lane's edits. The running baseline snapshot was
unaffected. The Markdown hook also reports stale Datahike gitlinks in the
wave-3a and wave-3bc specifications; those documents were not edited here.

After removing the tiered store (fresh JVM PID 83582): first
**4,647.819208 ms**, subsequent p50 **37.0355 ms**. Subsequent samples:
`40.703708 37.068208 39.004875 36.9705 36.894208 37.151 37.0355
35.922209 40.151042 36.93025`. Database projection derivation alone took
**3,995.958667 ms**. Across all eleven acquisitions, connect totaled
112.649831 ms, branch creation 244.117875 ms, branch deletion 94.056124 ms,
release 11.928329 ms, copying 143.393583 ms and reidentification
386.764584 ms. Nested timings overlap; they are not additive totals.

The first-use assertion remains red. `schema/projection-from-database`
queries the schema forms, function contracts and function sources, parses
their admission metadata, and constructs the Malli projection. It is
outside the assignment's listed files. No projection edit, new cache,
timeout increase or claim that the 2 s target passed has been made.

The ordinary file-store run also observed an existing source-population
test calling `seon.fn/rows` over the entire source tree to compute its
expected schema keys (39.859 s). That assertion now obtains its expected
rows from the exact exported manifest already handed to the worker. This
last edit is not included in the running snapshot and still needs checking.

Cold worker readiness still acquires the real SCI context because
`seon.test/resolve-test` requires the acquired program even for a host
test. Deferring SCI in an ordinary database fixture does not prove that
cold readiness now meets a smaller bound. The liveness declaration remains
unchanged pending that proof.

The four-namespace snapshot also reproduced the existing error-contract
boundary in
`docs/seon/issues/test-refusal-observations-overflow-in-projection-acquisition.md`:
`seon.test-test/recording-distinguishes-run-replay-from-a-new-event`
assertions at lines 289, 296, 297 and 300 receive an instrumentation error
from `seon.blob/with-publication!` instead of the expected error map.
`recording-preserves-admission-and-refuses-a-deleted-definition` at line
247 expects `:seon.error/kind`, but receives the database writer's
`:seon.db/transaction-outcome-unknown` error map. These are observations,
not proof of the cause of the latter mapping. Blob and database owners
were not edited. The run continued through these failures.

Final recorded fast result: run `9925b3f8cf7c`, **52 executed, 0 unchanged,
434 assertions, 7 failures, 3 errors**. This is red. It includes the
first-use timing failure and runner claim deadline failures; no cold gate
or platform proof was run. Shell syntax and `git diff --check` pass.
The changed production-source assertion and final shell cleanup ordering
were edited after this snapshot started, so they are not claimed verified
by that run. Slice (2) is unimplemented.

## Slice (1) landing authorization

The orchestrator explicitly accepted 4.65 s first use / 37 ms subsequent p50
as the slice (1) landing and extended ownership to `schema.clj` and
`sci/eval.clj`. Compilation optimization is not authorized: first determine
whether the remaining cost is the one worker acquisition, then measure
read rows / compile / seal and report it.

Failure classes in recorded run `9925b3f8cf7c`:

- First-use 2 s target: accepted pending the once-per-worker split;
  `docs/seon/issues/the-cold-fixture-base-outruns-the-liveness-silence-backstop.md`.
- Error-map propagation through blob/database contracts: existing
  `docs/seon/issues/test-refusal-observations-overflow-in-projection-acquisition.md`.
  The owned obsolete `:seon.error/kind` assertion now checks the error map's
  required timestamp; its unchanged database-basis and admission assertions
  still prove that recording did not recreate the deleted definition.
- Recording work exceeds claim deadlines: named tests and exact deadline
  evidence in `docs/seon/issues/test-recording-work-exceeds-claim-deadlines.md`.
  No timeout was widened. The complete 7/3 membership attribution is still
  owed; the note explicitly preserves this unknown rather than assigning a
  cause from the aggregate count.

The requested `clojure -M` load check cannot find `seon.test-support` because
`test/` is absent from that classpath. The same require is run with `-M:test`.
That load check passed before the slice commit. The one-line error-map
assertion correction has not yet been rerun.
