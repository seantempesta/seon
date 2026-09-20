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

## Committed slice and once-per-worker projection measurement

Slice (1) landed as `8aca66774`. Committed HEAD loaded successfully with
`clojure -M:test -e "(require 'seon.test-support 'seon.test 'seon.test.runner)"`.
No default operation or cold gate was performed.

The cited database line numbers have moved. The current seams are
`src/seon/db.clj:235` (`carry-connection-projection-state!`), `:249`
(`carry-projection-state`), `:295` (`resolve-database-value`), `:1219`
(`carried-projection`), and `:1226` (`read-declarations`). The connection
carries its state; `db/db` attaches that state's immutable projection to
the returned database value. Reads choose the carried projection first.
The metadata is a JVM object and is not serialized in the exported store.

`create-base` at `test/seon/test_support.clj:363` checks the raw connected
database for a carried projection before deriving one. The measurement
observed **no carried projection at that first connection** and exactly
**one** `projection-from-database`, `projection-from-rows`, `build-projection`
and `projection-registry` invocation across **eleven** fixtures. Every
fixture's `db/db` carried the **identical projection object**. The branch
path at `:946` reads the base's carried projection and passes it into each
branch's new state. There is no per-fixture projection derivation to remove.

Fresh worker PID 88983, snapshot HEAD `f564b7922`, armed timing namespace:

| Measured work | Milliseconds |
|---|---:|
| Whole `projection-from-database` | 5458.935792 |
| Four Datahike `q` calls: schemas, contracts, sources, admission provenance | 2871.858250 |
| Non-nested Malli `schema` / `function-schema` calls inside `projection-registry` | 47.737387 |
| Final `schemas` enumeration and `fast-registry` seal | 2.206417 |
| Other projection work, by subtraction | 2537.133738 |

The last row includes EDN parsing, predicate/form preparation, completeness
validation, reference graphs, shape rows and fingerprint construction. It
is not labeled compilation. `build-projection` totaled 2468.590541 ms;
its `projection-registry` call totaled 188.507541 ms. Those are nested
measurements, not additional costs. Population: **3358 schemas, 1521
function contracts**. No schema or SCI production code was changed.

The observations wrap the actual armed functions. Compilation timing
counts only outermost Malli calls during registry construction to avoid
counting nested calls twice. Sealing selects the exact registry returned
by `projection-registry` and its input table by object identity; no count
threshold or roster chooses it. Malli's implementation is
`reference-code/malli/src/malli/registry.cljc:17`, `:81`, `:102`.

Recorded run `c05b070b5428` measured first fixture **6221.350667 ms**, subsequent
p50 **39.888417 ms**. It does not overwrite the earlier 4647.819208 ms
measurement. It ran **1 test / 16 assertions / 1 failure / 0 errors**;
the sole failure remains the 2000 ms first-use target. Call count, missing
initial metadata, identical carried projection, and subsequent bound
assertions passed. The measured test took 6.874 s including all eleven
fixtures, so its metadata now declares a 10000 ms bound with that reason;
this does not widen the first-use assertion. The orchestrator's long-test
selection is required to rerun that measurement after this declaration.

Per the explicit instruction, work stops at this once-per-worker cost
decision. The owner must authorize that acquisition cost or choose an
algorithmic change. Compilation was not optimized; no cache was added.
Slice (2) remains unimplemented, and the liveness allowance remains
unchanged pending the independent SCI readiness proof.

## Indexed acquisition and bounded reference validation

The orchestrator rejected the preceding stop: the measured Malli compilation
and seal already prove that the remaining acquisition work is ours. The
once-per-worker target is 1000 ms; first fixture remains 2000 ms.

The three declaration joins now read identity ranges through Datahike AVET
and value ranges through AEVT, joining by entity id in Clojure. Identities
are indexed; form/spec/source strings are not. This is the dependency's
bound-attribute lookup at
`reference-code/datahike/src/datahike/db/search.cljc:140–157`.
`projection-rows` retains every historical value and removes duplicate
tuples, preserving the original query's duplicate-identity refusal.
`projection-admissions` joins the two identity attributes to the admission
attribute once. There are no per-entity pulls or queries.

Run `a5f4a52b05ce`: row acquisition fell from 2871.858250 to
204.554375 ms. The additional phase observations exposed 2153.318042 ms
in `fold-contract-validations`: the existing build-scoped delayed results
retained leaf validation but repeated the full descendant walk for every
caller. Retaining the complete walk with the existing key (reference,
input/output/schema role, admission source) reduced that phase to
348.953958 ms in `fa233a3841be`. The acyclic graph is checked before the
fold; different roles retain distinct validation results.

Run `03e0dd8d60a8` additionally removed the fingerprint calculation when
there is no reusable projection to compare, and handed validation the
compiled Malli schemas rather than binding all forms a second time:

| Work | Milliseconds |
|---|---:|
| Complete acquisition | 1457.945792 |
| Three indexed row joins | 184.112167 |
| Admission join | 50.272625 |
| Registry construction, including form preparation | 273.600666 |
| Complete contract validation | 310.515417 |
| Canonical reference graph (nested arity totals) | 216.739083 |
| Acyclic check | 12.035959 |
| Shape rows | 119.790711 |
| Fingerprint, one invocation | 108.557167 |
| Config display declarations | 54.610625 |
| Render contracts | 21.741334 |

The registry contains 79.903666 ms of observed Malli compilation and
2.432584 ms of sealing. Nested phase totals are not additive. First
fixture was 2402.177541 ms, subsequent p50 48.799709 ms. The armed run
recorded 3 tests / 27 assertions / 2 failures / 0 errors: only the timing
targets failed; indexed-row equivalence and shared-descendant validation
passed. The next timing observation removes the per-call Malli wrappers
used for the compilation split, retaining the projection-phase reporter.

This run's admission waited in `seon.test.runner/record-snapshot!` →
`seon.fresh-operator/live-root-value!` → the existing prepl response read,
before any test began (worker PID 94446 thread sample). No foreign process
was operated. The overlay explicitly used HEAD for dirty
`src/seon/cluster.clj`, `src/seon/cluster/source.clj`, `src/seon/fn.clj`, and
`test/seon/fn_test.clj`. A transient shared-tree hook syntax refusal at
`src/seon/cluster/source.clj:612:31` was not repaired by this lane.

Correction to the preceding note: fast snapshot requests explicitly include
declared long tests (`src/seon/test/fast.clj`, `snapshot-request`). No
orchestrator long-test selection was needed for these measurements.

Subsequent observations, without per-call Malli instrumentation:

| Recorded run | Acquisition ms | First fixture ms | Subsequent p50 ms | Result |
|---|---:|---:|---:|---|
| `a2f7fabf25ac` | 1394.270666 | 2257.230000 | 46.079875 | 3 tests, 27 assertions, 2 timing failures |
| `b714d06ac813` | 2018.158333 | 2799.871417 | 43.147792 | Serial validation was slower; parallel fold restored |
| `69f94b475fdd` | 1142.835708 | 1981.302375 | 46.504709 | 3 tests, 29 assertions, acquisition target failed |
| `981d6edf81d5` | 1056.777042 | 2002.302208 | 46.034000 | 4 tests, 32 assertions, 2 timing failures |
| `36267da33e7c` | 1671.789667 | 2735.116833 | 45.211666 | 4 tests, 33 assertions, 2 timing failures |
| `ae264c8ac38e` | 1041.672458 | 2110.440334 | 53.211083 | 5 tests, 39 assertions, 2 timing failures |

Run `a909255da291` had four fixture errors from an incorrectly closed
transducer in this lane's edit. That was corrected before `981d6edf81d5`.
All correctness regressions in the other rows passed. No timing limit was
widened. `36267da33e7c` specifically measured 542.398792 ms reading rows
and admissions, compared with 220.225208 ms in `981d6edf81d5`; it is not
silently replaced by the smaller observation.

The remaining construction now avoids collecting advisory vectors that
its result never returns. Core completeness inspection has no refusing
inline rule; its refusing error-schema inheritance checks remain. Agent
declarations retain their completeness and output checks. Only when every
referenced declaration explicitly records core admission can one completed
reference walk serve every role. The governing code is
`src/seon/schema/internal.cljc:290–378`. Regressions assert refusal for an
agent nilable return, an unresolved contract reference, and a malformed core
error schema, as well as equal entity shape rows.

The armer already owns a compiled declaration projection. The fixture now
hands that value to the existing second argument of
`schema/projection-from-database`. `declaration-projection` carries the
dependency graph it already derives and the predicate bindings used to
compile its roots. The builder retains those roots through the existing
`projection-registry` retained-entry argument only when all stored forms
and predicate bindings match exactly and the supplied declaration registry
has no function-contract population. Changed forms take ordinary complete
construction. Run `ae264c8ac38e` retained all 3358 schema roots and compiled
only the 1521 contracts: registry construction was 65.652166 ms, including
36.204208 ms preparing those contract forms. No cache was added.

Carrying the reference graph also removes repeated reference derivation by
`seon.instrument/contract-definitions` (`src/seon/instrument.clj:832`),
which reads the supplied graph before its per-reference fallback. In that
run, the armer's projection-acquired and contracts-armed announcements were
5.984 seconds apart, versus 43.524 seconds in `981d6edf81d5`. Instrumentation
source was not edited.

The timing test is explicitly a fixture observation: cold worker readiness
has already acquired its base before any test body, so a test asserting the
first acquisition cannot honestly run after readiness or another fixture.
Its declared command starts a fresh armed fast JVM. Its aggregate long-test
allowance is removed; the 1000 ms acquisition, 2000 ms first fixture, and
100 ms subsequent p50 assertions remain. Subsequent correctness tests use
the same canonical branched fixture.

Additional foreign boundaries observed: the shared hook reported an
unclosed form in `src/seon/cluster.clj:1765`; later snapshots also excluded
dirty `src/seon/error.clj` and `src/seon/sci/eval.clj`. None was edited by
this lane. The liveness constants now reside in `src/seon/test/bounds.clj`,
not the runner line in the original assignment; their SCI/startup allowance
is still independent of plain database fixture acquisition.

### Passing acquisition measurement

Recorded run **`a642f8d78e9f`**, fresh worker PID 6521, HEAD-plus-owned-paths
snapshot of `d998358224`: **5 tests / 39 assertions / 0 failures / 0 errors**.
Every test completed under 5 seconds; the legacy query comparison took
3.689 seconds. The exact command was:

```sh
bin/test-fast --paths src/seon/schema.clj test/seon/test_support.clj test/seon/test/fixture_timing_test.clj test/seon/schema/projection_acquisition_test.clj -- seon.test.fixture-timing-test seon.schema.projection-acquisition-test
```

| Fixture measurement | Before | After |
|---|---:|---:|
| First `with-database`, fresh JVM | 188907.588458 ms | **1604.175917 ms** |
| Subsequent p50 | 5.007625 ms (memory fixture) | **45.936584 ms** (isolated file-store branch) |
| Once-per-worker projection acquisition | 5458.935792 ms | **809.586000 ms** |

The intermediate branch landing was first use 4647.819208 ms / p50
37.035500 ms. These are distinct observations, not overwritten baselines.

The final acquisition's non-overlapping work is:

| Work | Milliseconds |
|---|---:|
| Three indexed declaration joins | 210.706041 |
| Admission join | 56.471750 |
| EDN parsing and identity/admission maps (rows minus join and builder) | 65.567625 |
| Registry construction over retained roots and new contracts | 51.288167 |
| Completeness and error-schema validation | 99.399125 |
| Entity shape rows | 83.918018 |
| One fingerprint | 91.941959 |
| Render contract validation | 18.992417 |
| Predicate bindings, root lookups, validation requests, forward/reverse references, shape indexes and projection/arity assembly | 130.249814 |
| Database acquisition dispatch and input-map construction | 1.051084 |
| **Total** | **809.586000** |

Registry construction includes 30.256500 ms preparing the 1521 contract
forms. All **3358 schema roots** were retained from the explicitly supplied
declaration projection. Canonical reference derivation, acyclic checking,
and config-display checks did not repeat. The unconditional non-nilable
check now belongs to `projection-registry` when each new root is compiled;
retained roots do not repeat it. Reference validation uses the existing
build-scoped delayed results, reads an already installed immutable result
without another atomic update, and never collects unused advisory vectors.
No new cache or execution mechanism was introduced.

The immediately preceding run `0c0b08e9f15f` still failed both targets
(1409.224834 ms acquisition, 2318.914208 ms first fixture, 47.710000 ms p50;
5 tests / 39 assertions / 2 failures / 0 errors). The final change moved
the non-nilable check to compilation and selected entity shapes through
the existing bounded entity-schema traversal, preserving local-cycle
refusal rather than using unbounded alias dereference.

Pre-commit loading passed in the shared tree:
`clojure -M:test -e "(require 'seon.schema 'seon.test-support 'seon.test 'seon.test.runner 'seon.schema.projection-acquisition-test 'seon.test.fixture-timing-test)"`.
No worktree or additional gate was needed. The full cold gate and platform
proof remain the orchestrator's responsibility.

## Item 0: mixed Malli registry identities

The orchestrator interrupted slice (2) for the cold-publication failure in
`projection-registry`. Both inputs were verified: `seon.fn/add-contract-facts`
builds keyword schema forms and symbol function contracts as separate sorted
maps. `(merge forms contracts)` retained the keyword comparator. The fix
starts the merge with `{}`. Compilation order remains the two explicit
`sort-by str` passes; Malli's provider uses key lookup, `lazy-registry` holds
an ordinary map, and `fast-registry` uses Java HashMap semantics
(`reference-code/malli/src/malli/registry.cljc:17,81`).

The canonical parity regression builds from sorted declaration maps without
retained roots, then compares declaration maps, the complete registry key
set, and each compiled schema/function form against indexed acquisition.
Fast run `1888eb5cccc3`, HEAD-plus-owned-paths snapshot of `1983ca39c`:
**5 tests, 30 assertions, 0 failures, 0 errors**. The new parity body took
**690.676 ms**. The existing four-projection refusal regression took
5626.326 ms including first fixture acquisition; it now declares a 10 s
bound with that work as its reason. The indexed-query oracle already
declares 10 s and took 5207.169 ms. The other bodies took 553.740 and
1059.852 ms.

The snapshot excluded foreign edits in `src/seon/config.clj`,
`src/seon/error.clj`, and `test/seon/error_result_test.clj`, and preserved
this lane's unfinished slice (2) edits in the test selector and runner.
Cold scratch-root publication and load verification are recorded below.

`bin/seon --root tmp/test-system-fork-root init` succeeded from an empty
directory, exit 0, **254.81 s** end to end. Published commit:
`6ab065e5-78f2-56c3-ad52-f69eaaf1eb01`; digest:
`2802842562b49ae794d7b1a76b6d665f2751dcafb23afbb30cd262220078e9fc`.
The cold compiler processed **3333 schema forms and 1527 function contracts
in 357 ms**. This is the explicitly authorized publication-from-zero proof,
not a changed-publication latency claim. The proof used the shared source
tree, including concurrent activation/error work and the preserved slice (2)
edits; the isolated fast proof above used only item 0's paths.
[Operator output](test-system-cold-publication-2026-09-23.txt).
`down` reported zero recorded JVMs and a free store lock; the root was deleted.
Pre-commit loading passed:
`clojure -M:test -e "(require 'seon.schema 'seon.test-support 'seon.test 'seon.test.runner 'seon.schema.projection-acquisition-test)"`.

## Slice (2), in progress: published selection

`seon.test.cache/ensure-base!` retains an immutable export on a cache hit;
it cannot acquire later green result facts by reopening that export.
The existing source recording authority already opens the latest published
branch and hands its database value to `seon.test/selection-admission`
(`src/seon/cluster/source.clj:384`). The pending coordinator change uses
that existing admission seam, retains the tested base's provenance, and
passes executable membership to the existing worker stages. Completion
uses the existing recorder, then queries `recorded-run!` so a zero-execution
repeat prints its unchanged members. No recording mirror or new cache was
introduced. Published-branch selection requires matching program and
external-input identities; an ordinary cluster still requires its cluster
reference.

The initial regression `ce2e2c8a6f52` exposed an error in the new fixture:
it wrote a derived program digest as another source seal. That fixture
error was corrected; the run had two failures and one error. A subsequent
run was stopped after a thread sample found the selector joining historical
definitions before narrowing changed identities. The pending selector now
reads changed identities from the since-value first. Its selected-fact read
also reads each entity range once instead of binding every entity/attribute
pair.

The next changed-function run reached an excluded owner: the existing
`seon.fn/declared-reference-edges` query. Evidence and the precise ownership
request are in
[the existing gate-set issue](../../../seon/issues/gate-set-rederives-the-declared-reference-population-on-every-call.md).
The canonical changed-reach/repeat regression is **not green** and slice (2)
is **not committed**. No cold bare invocation was run by this lane.

The selector's own measurements continue through
`test/seon/test/selection_timing_test.clj`. Run `8f7c18d7e7b3` measured
fixture acquisition 3547.398 ms; the initial program-identity query over
history 4409.765 ms; and initial selection 1485.847 ms. Run `cba6c03ca925`
separated changed-entity acquisition from current identity lookup: the
empty history query still took 4403.228 ms. The next change compares the
seal transaction with the supplied database basis before asking for changes.
On the following run, initial identity acquisition took **16.076 ms**, its
repeat **3.818 ms**, and initial selection **3116.719 ms**; fixture acquisition
was **3332.013 ms**. This worker used the exported base eight commits behind
HEAD, not the matching declaration population used for slice (1)'s 1604 ms
fixture proof. The timing observation has a declared 10 s aggregate bound.
The first two timing runs exceeded or approached that aggregate bound while
the old reporter still recorded green; default per-test bound enforcement
is the explicitly queued work after slice (2).

## Declared-reference acquisition, 2026-09-23 assignment

The orchestrator extended ownership to `seon.fn/declared-reference-edges`.
The file was clean immediately before the edit. The existing reverse walk
already uses `db/datoms :avet` for calls, references and test subjects; it
is unchanged. The slow operation was the preceding rule query combining
three differently shaped joins over an unbound declared attribute.

`selection_timing_test` measured that query at **3563.590 ms**, returning
10 edges. That baseline body completed two assertions, but the worker was
terminated before durable result recording, so it is timing evidence only.
The replacement binds each declared reference attribute before reading its
rows. Datahike chooses AEVT for a bound attribute, EAVT for bound entities,
and AVET for indexed attribute/value lookup
(`reference-code/datahike/src/datahike/db/search.cljc:140–157`). Capability
references, references owned by function declarations, and references on
data rows keep their original meaning. There is no stored derived state.

Armed fast run `c3088e2ae08e`: **223.269 ms**, the same 10 edges:

| Operation | Milliseconds |
| --- | ---: |
| Read declared reference attributes | 34.492 |
| Read capability references | 66.863 |
| Read function-owned references | 9.644 |
| Read data-row references and attribute consumers | 109.865 |

The run recorded 1 executed test, 2 assertions, zero failures/errors.
Initial published selection measured 3278.385 ms separately; this does not
claim slice (2) complete. The parity regression compares the new result with
the original Datalog rules over the canonical published fixture. Existing
query-structure assertions now identify the declared-attribute query rather
than requiring the retired rule-query representation; indexed reverse-walk
and typed-refusal assertions remain.

### Explicit ownership stop

Before committing, review found new foreign hunks in `src/seon/fn.clj`:
`unresolved-callers` now restricts callees to indexed namespaces, and the
adoption-identity query replaces per-entity pulls. Neither hunk was present
at the immediate pre-edit clean check. The assignment explicitly requires
stopping when this shared file acquires foreign edits; no commit was made
and slice (2) remains pending. All hunks are preserved. This lane's next
fast run was terminated during JVM loading (own PID 31445), and its launcher
exited 143 and removed its snapshot.

The isolated parity run `581316d906e0` matched all 10 edges, with acquisition
765.880 ms and three passing assertions, but its full test took 10.188 s:
that exceeds its declared 10 s and is not accepted as green evidence despite
the current reporter's tally. The regression now unions the three original
rule queries separately to avoid the original broad OR query; that revision
has not run because of the ownership stop. HEAD loading and the path-limited
commit remain owed after the foreign hunks land.

## Slice (2) resumed independently of shared fn.clj

The orchestrator explicitly directed slice (2) to land first, excluding
`src/seon/fn.clj`. The coordinator now uses `record-snapshot!` to ask the
existing source admission authority for selection. That authority supplies
its current published database value to `seon.test/selection-admission`;
`:current-src` provenance supplies published-branch custody. The selector
uses recorded basis facts and the existing reverse graph, while ordinary
cluster selection keeps its explicit cluster requirement. Completion goes
through the same recorder and queries the complete recorded run, including
reused members. An empty executable set reaches an empty worker stage.

The regression establishes a two-member named green basis through real
admission and validated transactions, changes stored
`seon.test.bounds/silence-seconds`, observes its reaching bounds test while
excluding the unrelated id test, records completion, and verifies the repeat
has no executable members. Terminal evidence is explicitly synthetic; this
does not claim those canonical test bodies executed. The real empty worker
stage is exercised separately and launches no worker.

The first sequence took 35.402 s and exceeded its former 10 s declaration;
run `50ad746cd9f6` passed its assertions but is not accepted as bounded proof.
The fixture was doing two unnecessary selections and recording a needless
source-input change. Removing those reduced the sequence to **25.654 s**
in run `d5a3640574ec` (2 tests, 8 assertions, zero failures/errors).
Measured phases: fixture plus named admission 4531.828 ms; source edit
434.106 ms; changed selection plus completion 16220.835 ms; unchanged
selection 4443.725 ms. The empty worker stage took 3.524 ms.

This is a declared integration test, not a map-comparison test: its 30 s
bound allows three selection decisions at 5 s each, two terminal admission
writes at 5 s each, a source edit at 3 s and fixture acquisition at 2 s.
The separate declared-reference parity regression remains subject to its
own measured bound. These slice (2) runs excluded fn.clj and therefore used
HEAD's slower reference query. The orchestrator still owns the cold bare
invocations and platform proof; this lane has run neither.

Final slice (2) fast run `c7b80aed81fd`: **2 tests, 9 assertions, zero
failures/errors**. The integration sequence took **22529.381 ms**, including
an assertion that `print-recorded-tally!` prints `Recorded 0 executed,` for
the repeat's actual unchanged results. Empty worker stage: **3.106 ms**.
Phases: fixture plus named admission 4003.747 ms; source edit 315.080 ms;
changed selection plus completion 13166.640 ms; unchanged selection
4990.921 ms. This snapshot excluded all fn.clj edits and foreign dirty
paths. The two slow query improvements remain independently landable.
Pre-commit load passed with `clojure -M:test -e` requiring
`seon.test-support`, `seon.test`, `seon.test.runner`,
`seon.test.published-selection-test`, `seon.test.selection-test` and
`seon.test.runner-test`. The fast snapshot independently loaded HEAD plus
only this slice's paths.
