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

### Slice (2) landed; reference parity bounded

Slice (2) landed as `cec1b6782`, excluding fn.clj. Both immediate post-commit
and final checks still found foreign `unresolved-callers` and adoption
identity hunks beside the owned `declared-reference-edges` change. Per the
orchestrator's explicit instruction, fn.clj and its pending regression
changes remain uncommitted; none of those foreign hunks were edited.

Reference parity run `81b1333a102d`: **1 test, 3 assertions, zero failures or
errors; 4981.556 ms total**. Indexed acquisition: **322.143 ms**, 10 edges.
The original three rules, queried separately and unioned as Clojure sets,
took **20.260 ms**, **1700.408 ms**, and **709.023 ms**. Their result equals
the indexed query's result. Splitting the old OR query removed the 10.188 s
overrun without increasing its declared bound. This compares reference-edge
sets; schema-projection parity was already measured and landed in item (0).

This last parity snapshot included the complete shared fn.clj, including
the two foreign hunks, but excluded foreign dirty caller files. It is a fast
iteration result, not the orchestrator's cold gate. Its JVM exited normally
and its scratch snapshot was removed. The abandoned zero-byte Git index
lock that briefly refused slice (2)'s commit had no Git process or open file
descriptor; it was preserved under tmp while retrying, then removed after
the successful commit. No default operation or cold test gate was run.

### Default per-test duration is an assertion failure

The single default is `:seon.test/time-limit-ms` in
`resources/seon/schemas/seon.test.edn`: **5000 ms**. Reporter options acquire
it once and carry it into each test. Clojure test emits `:begin-test-var`
and `:end-test-var` around the body (`reference-code/clojure/src/clj/clojure/test.clj:710–737`);
the existing capture stores those monotonic timestamps and emits an ordinary
`:fail` when their difference exceeds the bound. Existing failure recording
stores `{:seon.test/time-limit-ms bound}` as expected and
`{:seon.test/elapsed-ms measured}` as actual. There is no new execution loop.
Only a nonblank `:seon.test/long` reason together with positive
`:seon.test/long-ms` raises the default. An allowance alone does not.

The focused reporter regression uses an already elapsed timestamp, not a
sleep. Initial fast run `dfcaed01191c`: **2 tests, 23 assertions, no failures
or errors**; bodies **244.053 ms** and **349.029 ms**.

For the requested baseline, namespaces were derived from the newest exported
manifest's rows with `:seon.test/platform` and `:seon.test/sym`, excluding
`:seon.test/fixture`, then passed together to `bin/test-fast --paths
resources/seon/schemas/seon.test.edn src/seon/test/runner.clj
test/seon/test/duration_test.clj -- <derived namespaces>`. The snapshot
was HEAD `9c02384c8` plus exactly those three paths; the exported program
was 15 commits behind HEAD. No pending fn.clj changes were included.

Recorded run `7aa326277331`: **167 executed, 1183 assertions, 42 failures,
16 errors**. All 18 duration failures below have a 5000 ms bound:

| Test | Measured ms |
|---|---:|
| `seon.cluster.registry-test/reset-returns-a-cluster-to-source-state` | 5148.969416 |
| `seon.cluster.registry-test/retiring-one-cluster-reclaims-only-its-own-tail` | 7063.822625 |
| `seon.cluster.registry-test/two-clusters-write-independently` | 5307.07325 |
| `seon.cluster.source-lineage-test/existing-clusters-remain-on-their-chosen-source-commit` | 11080.855292 |
| `seon.cluster.source-lineage-test/stale-incremental-upsert-preserves-the-newer-publication` | 8986.837833 |
| `seon.cluster.source-test/incremental-first-party-publication-retains-complete-scalar-rows` | 7250.634 |
| `seon.cluster.source-test/incremental-publication-does-not-change-an-existing-cluster` | 6276.569292 |
| `seon.cluster.source-test/incremental-upsert-derives-scalar-safety-from-the-installed-schema` | 5963.102291 |
| `seon.cluster.source-test/incremental-upsert-records-source-identity-on-the-expected-commit` | 5704.52025 |
| `seon.flow-configuration-test/every-built-graph-proc-declares-a-specific-workload` | 11876.568208 |
| `seon.test-runner-test/gate-completions-travel-as-a-file-not-as-code` | 11834.050834 |
| `seon.test-support-test/simultaneous-fixture-bases-never-open-the-published-store` | 6542.267291 |
| `seon.test.runner-test/no-double-execution` | 5116.56675 |
| `seon.test.runner-test/platform-claims-and-original-bounds-govern-bulk` | 30929.636792 |
| `seon.test.runner-test/selection-is-one-function-on-both-hosts` | 13917.317125 |
| `seon.test.selection-test/fileless-sci-tests-use-the-same-selection` | 6337.354667 |
| `seon.test.selection-test/named-selection-reuses-green-members-by-reachable-content` | 14137.68825 |
| `seon.test.selection-test/omitted-dirty-callers-use-head-and-carry-recordable-provenance` | 15795.023541 |

A thread sample during `selection-derives-bases-obligations-and-exact-symbol-reach`
showed `runner/reach-refresh` → `db/pull-many` → `decode-pull-entity`;
its first two selections measured **23327.387 ms** and **23194.119 ms**.
This test already declares a 900000 ms allowance; it is not a default-bound
offender, and that declaration does not explain the work. The pending
declared-reference improvement is a separate measured query change.

The baseline also exposed existing publication and nested execution errors
at `schema/projection-registry` (`:malli.core/invalid-schema`), operator
assertion failures, and stale runner assertions. Their causes are not inferred
from the stack alone. The multi-gigabyte diagnostic is recorded in
[the existing output issue](../../../seon/issues/render-fixtures-dump-context-on-stale-assertions.md).

The final recorder confirmed all 167 results. The 16 errors comprise 14
[Malli schema acquisition errors](../../../seon/issues/platform-fixtures-refuse-function-schema-acquisition.md),
one operator boot error (the same test identity as the resolved
[operator issue](../../../seon/issues/isolated-reset-boot-test-closes-readiness-during-recovery.md)),
and one declared-reference error-schema contract refusal. Operator source
preflight assertions share test identities with the resolved
[absolute Git-directory fixture issue](../../../seon/issues/preflight-source-fixture-rejects-absolute-git-common-directory.md); these historical reports do not establish
the causes of this run’s operator failures.
The reporter-options default recomputation introduced during this slice was
removed before landing: supplied options are carried unchanged. Remaining
fixture-population and recorder assertions are retained reds, not claimed green.
The 3.79 GB raw log was deleted after extracting the bounded event summary;
the recorded run retains the failure facts. No foreign process was operated.

Final focused run `ffe963b4ba1e`, after preserving carried reporter options
and measuring the end event before reporter work: **2 tests, 23 assertions,
zero failures or errors**. Both results were durably recorded.
Final bodies: **180.675 ms** and **308.035 ms**. The pre-commit
`clojure -M:test -e` require of `seon.test-support`, `seon.test`,
`seon.test.runner` and `seon.test.duration-test` exited zero. The fast
snapshot independently loaded HEAD plus only the three item-1 code paths.
Markdown validation reports two pre-existing Datahike gitlink citations in
wave-3a and wave-3bc plan documents; this slice does not edit those owners.

### Declared-reference query released and verified

After `b17ad6ef1`, `git diff --unified=0 src/seon/fn.clj` contained only the
owned `declared-reference-edges` hunk. The query uses Datahike's bound
attribute search (`reference-code/datahike/src/datahike/db/search.cljc:140–157`):
acquire declared reference attributes once, query their function-owned and
data-row edges with the attribute bound, and union capability edges.
Reverse caller walks retain their existing indexed queries.

Final parity run `e5c72fe4e486`: **1 test, 3 assertions, zero failures/errors**,
**4925.371 ms** including canonical fixture acquisition. The indexed query
took **326.389 ms**; the three original rule queries took **19.718 ms**,
**1639.793 ms**, and **723.792 ms** and produced the same 10 edges. Earlier
old/new acquisition was **3563.590 → 223.269 ms**. The phase reporter is
retained as `selection-timing-2026-09-23.clj` beside this note, outside gate
discovery; the recurring regression is the bounded parity test.

The prior explicit `clojure -M:test -e` require loaded `seon.fn` transitively
with this unchanged production hunk. The final fast snapshot independently
loaded it on HEAD. The refusal-injection assertions now identify the bound
attribute query instead of the retired OR-rule query. The large selection
regression's other recorded reds are not claimed fixed by that assertion update.

### Small publication inputs; fresh-export proof still required

The source/lineage/evidence fixtures inherit the exported canonical schema
and program in a private, reidentified store through
`test-support/populate-published-operator-root!`. The canonical
`with-database` fixture hands the already acquired projection. Only the
private copy's extra branches and copied source identity are removed; the
fixture authors its own source history. No development cluster is operated.

`test/resources/publication-program/seon/{id.clj,id_test.clj}.txt` supplies
two namespaces. The fixture copies them into its owned source root and calls
`seon.fn/build-manifest`; `.txt` prevents duplicate program identities during
ordinary checkout discovery. `populate-program!` calls the existing
`fn/index!` with only those changed files, the prior database and the carried
projection. Marker publications only write their marker rows. Scalar updates
call the same `source/publish!` / `source/populate-upserts!` seam and retain
expected-head custody. Changed paths exclude unrelated Markdown indexing.
No complete program manifest is rebuilt or indexed by these fixtures.

Before: the 05:00 ledger records **150–202 seconds per publication test**.
The first successful two-file publication measured **4793.213 ms** (4631 ms
before source identity, 88 ms identity, 73 ms branch-head update). The next
change hands the existing compiled projection rather than reacquiring it.
Its final timing proof is **blocked by the old export**, not established by
fast failures: the export is 22 commits behind HEAD and its request schema
still requires retired `:seon.source/activation`. The final snapshot ran
14 tests / 37 assertions / 2 failures / 9 errors, all nine errors naming that
contract mismatch. See the existing
[fixture-contract issue](../../../seon/issues/canonical-fixture-retains-old-function-contracts-after-adoption.md).

The preceding run reached branch-history assertions and exposed an existing
expectation that equal digests rebuild stale rows; current publication
returns unchanged for equal source identities. That assertion is retained,
not weakened to obtain a green run. The fixture's branch-roster assertion
now includes its explicit canonical ancestor branch. No test was deleted.
The three old 600000 ms declarations are reduced to 10000 ms, naming their
multiple publication/recording operations. The scalar-publication test also
declares 10000 ms for two analyses, database validation, publication and
scalar update. A single ordinary marker publication remains under 5000 ms.

Repeated failure recording independently exposed
[immutable report/source-position conflict](../../../seon/issues/moving-a-failing-assertion-conflicts-with-its-immutable-report.md).
Those later runs have no durable tally; their local execution counts are
not represented as recorded facts. The item-1 baseline remains durably
recorded. Cold publication and the complete successful timing proof remain
with the orchestrator after refreshing the export. No foreign source hunk
was included in a fast overlay or edited to work around this boundary.
The pre-commit `clojure -M:test -e` require of all three affected namespaces
exited zero under one acquired test slot. fn.clj is clean after its separate
commit. The only remaining clj-kondo notice in the fixture is the existing
private digest Var used by the lineage namespace.

### Terminal observations and correction of the 270-second attribution

`test-support/await-event!` now accepts an explicit fourth argument in
milliseconds, retaining its existing channel/latch/future/reference behavior.
Its timeout exception names both the expected event and the observation bound.
The turn-backstop regressions use 1000 ms to observe cancellation, or the
production fault admitted for 200 ms of work plus margin. The latter awaits
the exact `::flow/op :seon.agent/turn-completion-backstop` event and asserts
that the bounded join returns its identical `::flow/ex` exception. A missing
event throws; the harness timeout never satisfies the assertion. The producer
and consumer seams are `turn/offer-turn-backstop-fault!` and
`agent/await-turn-completion!`.

Focused fast run **1fb10a26d1ba** durably recorded **2 executed, 7 assertions,
0 failures, 1 error**. Reporter begin/end times were **3005 ms** for
`cancelled-completion-observer-releases-every-existing-waiter` (passed) and
**1578 ms** for `completion-observer-uses-the-admitted-provider-and-evaluation-parts`
(error). These are reporter wall intervals, not precision claims about stored
elapsed values. The latter observes no fault because `turn-completion-error`
calls `error/diagnostic` without required `:seon.error/at`, `/layer`, and
`/operation`. The producer is outside this lane's ownership; see the
[existing error-schema issue](../../../seon/issues/error-class-catalog-and-renderers-disagree.md).
The export was 25 commits behind HEAD; no claim of a successful production
fault delivery is made.

The broader measurement falsifies the 05:00 ledger's attribution for
`turn-work`: that namespace has no timed await. **270 seconds is the runner's
ordinary worker exchange bound**, not a test success condition. Its generated
property instead performs 200 independent fixture/configuration admissions.
After approximately **163 seconds**, a thread sample found it RUNNABLE in
Datahike query estimation through `configure-cap!` / `config/apply!`. The lane
terminated its own JVM; that incomplete run has no durable tally. Six earlier
turn-work tests completed in **472–3903 ms**; a seventh refused a retired
`:seon.error/kind` fixture write. The separate algorithm defect is recorded in
[the property issue](../../../seon/issues/turn-work-property-repeats-config-admission-for-every-generated-case.md).
No trial count or assertion was weakened and no long allowance was added.
The old worker exchange bound is not claimed repaired by this event change.
The explicit `clojure -M:test -e` require of test-support, turn-backstop-test
and turn-work-test completed without a load error. The load command's slot
helper was accidentally sourced by zsh and refused its Bash substitution;
the subsequent single JVM load completed, but is not claimed slot-protected.
Both fast runs used the launcher's slot normally. No lane JVM remains.

### Accepted-offender follow-up: retain the compiled projection

`test-support/environment` now reads `db/carried-projection` before acquiring
stored declarations. The simultaneous-store test uses the projection that
`create-base` already acquired, and the both-host test hands the carried
projection to `run-task!`. These are the same database values, not projections
shared across unrelated databases. The new identity regression verifies the
environment retains the exact projection object (41.826 ms including its fork).

Final local fast execution: **21 tests, 168 assertions, 1 failure, 0 errors**.
The failure compares current schema keys with the stale exported population;
the existing fixture-contract issue owns that boundary. Recording then refused
with “The live process answered with a prepl reply this operator cannot read.”
This run therefore supplies local reporter intervals, not durable result facts.

| Test | Before ms | After ms | Decision |
|---|---:|---:|---|
| `seon.flow-configuration-test/every-built-graph-proc-declares-a-specific-workload` | 11876.568208 | 2537.603 | Use the carried projection; retain 5000 ms bound. |
| `seon.test-support-test/simultaneous-fixture-bases-never-open-the-published-store` | 6542.267291 | 6339.881 | Remove duplicate acquisition; declare 10000 ms for three physical store copies and two complete file-hash comparisons. |

The physical-byte assertion requires visiting every published file: a branch
pointer cannot establish that concurrent independent store opens preserved all
source bytes. The declaration names that work, not its observed slowness.
The both-host test's remaining operations will be measured with its selection
class; no after-duration is claimed for it yet.

The **4793.213 ms** two-file publication remains explicitly **the publication
seam's measurement for the orchestrator and redesign lane**. It is not a
fixture-only duration and this lane does not widen a fixture bound to justify it.

### Registry setup extends the supplied projection

The registry's physical-store tests now extend the supplied compiled projection
with their two synthetic blob attributes. They no longer read and compile the
whole declaration population per fixture. `probe-schema-rows` follows the
projection's existing dependency map before calling `canonical-schema-rows`,
instead of deriving every canonical row and discarding unrelated rows afterward.
The store remains physically isolated: garbage collection and non-temporal
storage are the subjects, and a branch of a shared worker store would not
provide that isolation. Production registry operations and assertions stay intact.

Fast run **b6ad7885e5ea** recorded **12 tests, 73 assertions, 0 failures,
0 errors**. Reporter intervals, with all original 5000 ms bounds retained:

| Test (`seon.cluster.registry-test/`) | Before ms | After ms |
|---|---:|---:|
| `reset-returns-a-cluster-to-source-state` | 5148.969416 | 2310.110 |
| `retiring-one-cluster-reclaims-only-its-own-tail` | 7063.822625 | 3895.885 |
| `two-clusters-write-independently` | 5307.07325 | 2310.345 |

The explicit test-alias namespace load passed before commit. No cold gate or
default-cluster operation was run.

### Reach acquisition uses an indexed relation

`runner/reach-refresh` replaces per-entity schema-aware pulls with one
`db/q` over the same entity IDs and declared reach attributes. Datahike's
bound entity/attribute lookup uses EAVT (`reference-code/datahike/src/datahike/db/search.cljc:140–147`).
The installed database schema supplies cardinality when assembling the rows;
no attribute-type roster or additional cache is introduced. Incremental
identity selection, deletion handling and reach invalidation remain unchanged.
The recurring parity regression compares acquired function, test and schema
rows with the original pull selector, including identity presence. It passed
in **4777.401 ms**, including first fixture acquisition.

Fast run **9d5265c4e61d** recorded **68 executed, 438 assertions, 14 failures,
10 errors**. This is not a green selection proof. The unchanged Malli
function-schema acquisition failures and SCI contract refusals remain.
The named-selection failure in this run was introduced by this indexed-row
change, as diagnosed and corrected below. Reporter intervals:

| Test | Before ms | After ms | Current outcome |
|---|---:|---:|---|
| `seon.test.runner-test/no-double-execution` | 5116.566750 | 4868.095 | Malli acquisition error |
| `seon.test.runner-test/platform-claims-and-original-bounds-govern-bulk` | 30929.636792 | 8776.342 | Malli error and duration failure |
| `seon.test.runner-test/selection-is-one-function-on-both-hosts` | 13917.317125 | 13034.602 | SCI contract error and duration failure |
| `seon.test.selection-test/fileless-sci-tests-use-the-same-selection` | 6337.354667 | 2272.113 | SCI contract error |
| `seon.test.selection-test/named-selection-reuses-green-members-by-reachable-content` | 14137.688250 | 7700.761 | Two selection assertions and duration fail |
| `seon.test.selection-test/omitted-dirty-callers-use-head-and-carry-recordable-provenance` | 15795.023541 | 4444.378 | Passed |
| `seon.test-runner-test/gate-completions-travel-as-a-file-not-as-code` | 11834.050834 | 5750.695 | Existing form-size/refusal assertions and duration fail |

The broad selection regression also still performs whole-program reach work:
a dump including virtual threads found `reach-entry` assembling schema
dependencies during its 1/10/100 changed-definition measurement loop. That
test's old 900000 ms declaration is not justification for this work. No new
long allowance was added to hide these remaining overruns. The snapshot used
HEAD bytes for the concurrently edited SCI caller, explicitly excluding its
working-tree changes. The exported base was 30 commits behind HEAD.

#### Temporal cardinality correction

The first indexed-row change incorrectly read `:schema` directly from an
`as-of` wrapper. That collapsed cardinality-many values to scalars, producing
“Don't know how to create ISeq from: clojure.lang.Keyword” during named reuse.
This failure was ours, not foreign breakage. The query now obtains installed
cardinality through `db/schema-database`, which follows Datahike's origin.
The parity regression now acquires the complete index through an `as-of`
value and compares its selected rows with the original pull selector.
Run **6ac7a28556dd** recorded **1 test, 4 assertions, 0 failures, 0 errors**;
reporter duration **4718.540 ms** including initial fixture acquisition.

### Select before deriving reuse evidence

`seon.test/select` previously requested reach digests for green members even
when `reached` already prohibited their reuse. It now excludes those members,
and members with a different explicitly requested basis, before acquisition.
The named-selection regression observes the real `runner/reach-digests` calls
and proves the changed function's reaching test is absent from those requests.
The runner also walks schema dependencies once from the requested test's keys;
it no longer constructs a transitive closure for every installed schema first.

The existing 1/10/100-definition measurement, using the canonical database,
reported **5198.634 / 5052.171 / 3438.956 ms**. The preceding run measured
**18954.686 ms** for one definition before it was stopped. These are measured
selection operations, not a claim that the full regression meets its bound.
Run **ca092fdd7fcf** recorded **11 tests, 103 assertions, 9 failures, 3 errors**.
Indexed-row parity passed in **4577.818 ms**. Named reuse's semantic assertions,
including the new acquisition assertion, passed; its **9198.621 ms** duration
failed. The omitted-caller test also passed its semantic assertions but failed
its duration at **6974.017 ms**. Fileless SCI acquisition errored at the
`seon.instrument/compiled-wrapper` contract boundary and took **17130.972 ms**.
The complete selection history took **79971.829 ms**, with assertion and
contract failures; its pre-existing 900000 ms declaration does not settle
the algorithm problem. It still admits and records the entire canonical test
population repeatedly. These remaining failures are not green proof.

All runs used HEAD-plus-owned-paths snapshots. The exported publication was
37 commits behind HEAD; this fact alone does not establish the cause of a
contract failure. No default cluster or cold gate was operated.

### Completion transport uses the smallest input proving the threshold

`gate-completions-travel-as-a-file-not-as-code` now derives its result count
from the JVM's 65536-byte method threshold and a result row's printed size,
instead of constructing 2000 results. It still proves exact EDN round-trip,
that the sent form excludes result data, and that the sent form stays identical
when the staged payload's size changes. The independent absent-definition
write checks one transported result and queries that no synthetic definitions
were fabricated. The old 1024-byte assertion was an arbitrary form-length
limit, not the JVM threshold or a proof of constant size.

Before **11834.051 ms**; final measured run **3274.513 ms**, all semantic
assertions and the unchanged 5000 ms bound passed. An earlier first-use run
was **5448.810 ms**, so this does not establish a worst-case latency guarantee.
The writer's unclassified-refusal log still serializes transaction arguments:
`reference-code/datahike/src/datahike/writer.cljc:105–114,152–161` recognizes
the retired error-kind marker, whereas the recorder's missing-definition
exception carries `:seon.test/symbols`. That logging boundary remains outside
this fixture change; no logging suppression or retired marker was added.

Run **849f54d2bc0b** locally completed **46 tests, 216 assertions, 3 failures,
11 errors**, then recording failed on immutable report-line disagreement and
an unreadable prepl reply. These are local measurements, not recorded proof.
The same run measured the six publication offenders below. All six refused
before publication because the exported `:seon.source/publish-request`
requires retired `:seon.source/activation`; these are refusal durations,
not successful after measurements:

| Test | Before ms | Refusal ms |
|---|---:|---:|
| `source-test/incremental-first-party-publication-retains-complete-scalar-rows` | 7250.634 | 4814.141 |
| `source-test/incremental-publication-does-not-change-an-existing-cluster` | 6276.569 | 2780.170 |
| `source-test/incremental-upsert-derives-scalar-safety-from-the-installed-schema` | 5963.102 | 2450.895 |
| `source-test/incremental-upsert-records-source-identity-on-the-expected-commit` | 5704.520 | 2777.880 |
| `source-lineage-test/existing-clusters-remain-on-their-chosen-source-commit` | 11080.855 | 2642.989 |
| `source-lineage-test/stale-incremental-upsert-preserves-the-newer-publication` | 8986.838 | 2406.790 |

The exact contract boundary is tracked in
[`canonical-fixture-retains-old-function-contracts-after-adoption`](../../../seon/issues/canonical-fixture-retains-old-function-contracts-after-adoption.md).
The earlier successful **4793 ms** two-file publication remains evidence for
the orchestrator's redesign lane; the test lane has not optimized or claimed
ownership of that publication seam.

### Program digest reads carried shapes once

A thread sample during selection found `program-fact` calling the authored
program-shape reader for each changed row. That reader computes declaration
stamps from schema resource files. The runner now derives `program/shapes-in`
once from the database's carried projection and supplies it to both old and
current row comparisons. It no longer reads every schema resource per row.
The regression changes two declarations and refuses any call to the authored
reader during the digest comparison.

Run **a44830cc2fd1** measured this regression at **2215.63 ms**, and indexed
reach-row parity at **4362.93 ms**, both passing the unchanged 5000 ms bound.
This does not establish that every selection test meets its bound. The broad
selection fixture experiment was removed; its population changes did not
produce a valid improvement. The existing named-selection duration remains
unresolved. This run's combined tally was **26 tests, 151 assertions,
4 failures, 3 errors**; no full selection pass is claimed.

### Turn-work: immutable cases and the requested setting only

The 200-case property previously remained running after **163000 ms**. One
measured old case spent **1264 ms** admitting a complete configuration,
**338 ms** writing state, and **60 ms** deriving work. Repeating configuration
and durable store commits tested work the pure derivation does not require.

The fixture now writes the agent's bound once through
`seon.agent/update-settings-call`, then derives each isolated case with
Datahike `with`, retaining Seon's `write-report-validator`. Datahike's writer
uses this same evaluator (`reference-code/datahike/src/datahike/writing.cljc:872–889`);
the final-report callback runs in `db/transaction.cljc:1206–1225`.
The property still has 200 cases, the same seed and all its assertions.
A real-writer parity test checks the result, unchanged ancestor basis and
identical carried projection. The exhaustive table still commits its states.

The production read `turn/max-episode-runs` now queries only the requested
agent setting through its component ref instead of acquiring all settings
with `ai/agent-overlay`; issue-budget, agent override and cluster default
retain their precedence.

| Test | Before ms | After ms |
|---|---:|---:|
| `situation-totality-property` | >163000, incomplete | 4457.36 |
| `the-derivation-is-total-over-every-state` | 12612.460 | 3939.94 |
| `generated-state-agrees-with-the-writer` | new parity regression | 620.53 |
| `only-a-turn-whose-reply-came-from-a-model-attempt-answers` | refused retired error fixture | 1774.61 |

The last test now uses `seon.error/recording` rather than a hand-written
retired error-kind entity. One generated case measured **64.235583 ms**
preparation (once), **40.504917 ms** state transactions and **21.711625 ms**
derivation. Intermediate property runs measured **5500.855**, **4545.159**,
and **5278.902 ms**; only the middle one passed. The final measurements above
include the narrower production query. All **14 turn-work and cost tests**
passed in `a44830cc2fd1`; the combined run's selection failures are recorded
above. The default 5000 ms bound remains, with no long-test declaration.

The final namespace load completed successfully with `clojure -M:test`.
The shared shell hook temporarily refused even `git status` for foreign
`test/seon/fn/schema_shape_test.clj:84:70`; the next status succeeded without
any edit to that file. Markdown checking reports two foreign stale Datahike
gitlink citations in the wave-3a and wave-3bc plan documents. Neither boundary
was bypassed or edited. The orchestrator still owns cold proof.

### Physical-copy fixture selects the published head

The rejected memory-store experiment exposed a separate fixture defect:
Datahike `fork-database` reads the source `:db` head explicitly, ignoring the
configuration's `:current-src` branch. `with-fresh-database` now points the
private copied store's unused `:db` head at its published value before the
physical copy. The dependency seam is `datahike/versioning.cljc:620`.
Before this repair, the probe lacked the agent identity schema. Afterwards
its three state derivations passed, with writes **121.585 / 94.617 / 76.681 ms**
and reads **103.887 / 22.428 / 25.529 ms**. The total **13097.799917 ms**
failed the default 5000 ms bound; this physical-copy approach was rejected for
the property. No performance exemption was added. The normal branch fixture
does not enter this path. The final namespace load included this repair.

### Remaining platform proof at this checkpoint

Six of the 18 original offenders have passing after measurements: the three
registry tests, graph workload declaration, completion transport and simultaneous
physical fixture isolation (the last under its explicit 10000 ms copying and
hashing declaration). Six publication tests have only the refused measurements
listed above, not successful after measurements. The other six are:

| Test | Latest observed outcome |
|---|---|
| `runner-test/no-double-execution` | 4868 ms; Malli schema acquisition error |
| `runner-test/platform-claims-and-original-bounds-govern-bulk` | 8776 ms; error and duration failure |
| `runner-test/selection-is-one-function-on-both-hosts` | 13034 ms; SCI contract error and duration failure |
| `selection-test/fileless-sci-tests-use-the-same-selection` | 13687 ms; error and duration failure |
| `selection-test/named-selection-reuses-green-members-by-reachable-content` | 7935 ms; semantic assertions pass, duration fails |
| `selection-test/omitted-dirty-callers-use-head-and-carry-recordable-provenance` | 6974 ms; semantic assertions pass, duration fails |

These observations preclude claiming the 18-offender task complete. The
exported publication's age alone does not establish the cause of every error.
The named and omitted-caller durations remain algorithm work, not reasons for
new allowances. The **4793 ms** successful two-file publication remains the
orchestrator's evidence for the publication owner.

### Six owned tests: complete refusals, 2026-09-23 continuation

Read the replacement AGENTS.md and the clojure-testing, data-oriented-clojure,
Datahike and REPL skills end to end. No publication test was changed.
The focused namespace invokes the six original test bodies under the same
fast reporter and contracts; it is a measurement script, not six additional
permanent regressions. Snapshots excluded the dirty `src/seon/cluster.clj`
caller and announced that its HEAD bytes were used. Foreign files were untouched.

The complete SCI evaluation maps disproved an evaluation refusal: both had
`:seon.eval/outcome :ok`, the expected Var and no `:seon.program/row`.
`acquire!` had discarded SCI's generation when replacing the environment.
Installing `(sci/fork generated)` preserves definition provenance. The existing
fileless assertions then passed; the both-host test reached execution.
The dependency boundaries and correctness result are in
[the generation issue](../../../seon/issues/sci-acquisition-drops-definition-generation.md).

The complete nested-run exception data was:

```clojure
{:type :malli.core/invalid-schema
 :message :malli.core/invalid-schema
 :data {:schema :seon.test/time-limit-ms :form :seon.test/time-limit-ms}}
```

The failing function was `seon.test.runner/duration-failures`: the loaded
host reporter used a schema absent from the older fixture's projection.
Reporter options now carry their supplied projection into that function.
The two host-only nested-run fixtures retain their entering host projection;
the SCI execution fixture carries the reporter options separately from its
database custody. No missing schema is silently substituted in the database.

Claim assertions also counted inherited results from every run: **60 reports**
instead of two, and **290 completed members** instead of four. They now scope
by run identity and include both admitted and covered members, following the
same two relations as `runner/execution-members`. The first scoped attempt
missed covered members and returned nil; that correction was made after the
run below and still requires the next focused execution.

Run **ac51f1a8b816**: **8 tests, 79 assertions, 8 failures, 1 error**.
Both-host and fileless semantic assertions passed, as did both duration
reporter regressions. Six duration failures remain. The two remaining count
failures are the covered-member correction above. The platform claim test
also exhausted its existing 20-second execution deadline while expected
refusals serialized full projection arguments into the writer log. That bound
was not widened. These results are not a green six-test proof.

| Original test | Baseline ms | Latest ms |
|---|---:|---:|
| `runner-test/no-double-execution` | 5116.567 | 26119.140 |
| `runner-test/platform-claims-and-original-bounds-govern-bulk` | 30929.637 | 30307.987 |
| `runner-test/selection-is-one-function-on-both-hosts` | 13917.317 | 58060.672 |
| `selection-test/fileless-sci-tests-use-the-same-selection` | 6337.355 | 13030.504 |
| `selection-test/named-selection-reuses-green-members-by-reachable-content` | 14137.688 | 20871.426 |
| `selection-test/omitted-dirty-callers-use-head-and-carry-recordable-provenance` | 15795.024 | 15086.699 |

These are failed bounds, not improvements. The focused measurement source is
preserved as [an applicable test patch](test-system-six-offenders-2026-09-23.patch).
Apply it, run the `seon.test.offenders-test` namespace through `bin/test-fast
--paths` with the selected implementation/test paths, then remove that
disposable file. The complete missing-row evaluation envelopes and Malli
exception data were read; the summaries above name their actual values.

### Decision: writer invocation logging

Four completed focused logs contained over **3 GB** of writer output before
cleanup. `datahike.writer/expected-refusal-face` at `writer.cljc:105–114`
recognizes retired `:seon.error/kind` or native `:error`. The claim/report
writer throws the complete flat Seon error and its declared execution refusal,
which has neither marker. The writer therefore logs the complete invocation
and arguments (`:158–161`), including the projection passed to
`seon.schema.datahike/encode-call-output-in`. This is the existing
[writer logging issue](../../../seon/issues/expected-refusal-logs-raw-datom-error-twice.md),
whose working-tree note is held by another lane and was not edited here.
The original callback still delivers the refusal correctly; serialization
cost also caused the platform claim fixture's 20-second deadline to expire.
Completed raw logs were deleted only after extracting summaries and checking
that no process held those exact files. No run root or foreign log was swept.

Three concrete choices, before changing the dependency's diagnostic policy:

1. **Recommended:** log the operation and complete exception, omitting the
   invocation and argument vector. One small maintained-fork slice and its
   existing writer regression. Exception data, stack and callback remain
   complete; raw input arguments no longer appear automatically. The
   [reviewable draft](test-system-writer-log-proposal-2026-09-23.patch) is not
   applied or tested.
2. Extend the existing refusal classifier to recognize flat Seon errors.
   One fork slice, retaining raw unexpected-failure logging, but it must define
   which application error maps are expected; classifying every flat core fault
   as a short refusal would lose its exception stack from the log.
3. Keep the logger and remove projection arguments from the transaction bridge,
   carrying the projection with the writer's database instead. This requires a
   coordinated database/schema bridge change and does not bound other large
   invocation arguments.

A separate thread sample found SCI's first acquisition loading core namespaces
whose top-level defaults call `config/compile-settings`, then rebuild the
complete declaration projection. Named selection and omitted-caller setup still
need their operation splits; no causal attribution is made solely from their
total durations. No new cache or long-test allowance was added.

The direct `clojure -M:test` namespace load succeeded. A subsequent duration-only
fast snapshot was refused **before tests** by `runner/record-snapshot!`:
`:seon.config/compiled` could not resolve `:seon.config/applied-manifest-digest`.
The recorder returned the complete missing-reference envelope. Concurrent edits
currently hold `src/seon/config.clj`, its resource and `src/seon/schema/edn.clj`;
none was included or edited. This is an additional verification boundary,
not a result for either duration regression. The last executed duration
regressions passed in `ac51f1a8b816`.

### Writer log ruling implemented — 2026-09-23

Owner selected option 1. Datahike fork **7e1af7ddbe697d7cf7a0291ad432faf683b7d2bc**
was pushed to `seantempesta/datahike` `main`. `writer.cljc:85` derives
exception class/message/stack without exception data; `:141` logs that value
instead of invocation/arguments. Branch and commit come from the writer's
immutable database. Explicit cluster, test, run and error identities are retained
from the request, transaction metadata or error evidence when supplied; absent
application identities are not guessed. The callback retains the original
exception and offending objects. The retired discriminator and truncated-message
branch were deleted. This supersedes the earlier draft patch.

The maintained-fork regression ran in **28.875 ms**, **12 assertions, zero
failures/errors**. It checks branch/commit, supplied cluster/test identity,
exception type/message/stack, absence of invocation/argument/exception-data
fields, and identical callback exception. Dependency namespace load passed.
Fast overlay admission excludes gitlinks (`--paths` accepts first-party files),
so the fork pin lands before measuring the six on that HEAD.

The final fork pin is **006e634ae955c186619adb5f3868cca29d8c97fb**, pushed to
`origin/main`. It also retains the run identity from the error map's actual
`:seon.error/data` → `:seon.error/diagnostic-evidence` path. The final regression
passed **13 assertions in 36.042 ms**. The same JVM required the owned runner,
runner-test and selection-test namespaces successfully.

### Empty result recording performs no reach derivation — 2026-09-23

The shared recorder refused before execution because its loaded config declaration
still referenced removed `:seon.config/applied-manifest-digest`. Per the assignment's
explicit fallback, a detached HEAD worktree at `f0e2fa7c8` linked `reference-code`
and used a private copy of the existing export. `seon.cluster.export/reidentify!`
rewrote that copy's store identity before opening it. No publication or shared
process operation occurred. This got through source admission in the test JVM.

Run **c1390e43daee**, same six original bodies through the preserved focused
patch: **14 assertions, 1 duration failure, 5 errors**; log **17,047 bytes**.
Five bodies now refuse at `seon.config/compile-manifest` called by the canonical
`seed-cluster!` helper: its returned `:seon.config/compiled` map has effective,
desired-row and resolved-attributes entries, while the exported declaration still
requires retired `:seon.config/applied-manifest-digest` (also in desired-row).
The complete exception identifies an output-contract failure. Those short setup
refusals are not successful test timings, nor evidence that log writes explained
the previous duration. The covered-member correction remains unexecuted beyond
this setup. The orchestrator has been asked to refresh the exported base; no
retired key or replacement production schema was inserted into the fixture.

The omitted-caller body reached all assertions and took **6,832.831 ms**
(previous six-test measurement **15,086.699 ms**). A focused operation split,
run **2992c9f3ccef**, measured **10,137.423 ms**, of which `commit-results!`
took **4,483.187 / 2,467.469 ms**. Its EMPTY test sets still called
`reach-digests`, costing **2,851.197 / 1,207.137 ms**. `reach-entries` built
rows for the entire program before discovering there were no requested members.
The two-file manifest took **608.841 ms**, overlay check **46.175 ms**, and
projection acquisition **416.290 ms**. Timings are inclusive; nested values
must not be added together.

`src/seon/test/runner.clj:2260` now returns the empty map before acquiring any
reach state when the requested vector is empty. Both digest and membership
callers use that one seam. No cache or allowance was added. The existing
omitted-caller regression asserts both empty results and still verifies real
HEAD bytes and idempotent durable provenance.

Run **f338c6166d5c**: **1 executed, 10 assertions, zero failures/errors**;
**3,054 ms** between reporter begin/end events, below the unchanged 5-second
bound. Manifest **491.431 ms**, overlay check **37.128 ms**, projection
**376.596 ms**; empty digest calls **0.299 / 0.013 / 0.010 ms**; recording
**275.041 / 67.536 ms**. The [operation-measurement patch](test-system-omitted-cost-2026-09-23.patch)
reproduces the split around the unchanged original test body. Required namespace
load also passed in the final writer-regression JVM.

Remaining decision boundary: a matching published schema is needed to execute
the five config-dependent bodies. The existing
[fixture contract issue](../../../seon/issues/canonical-fixture-retains-old-function-contracts-after-adoption.md)
owns this exact boundary. Cold proof and full six-test remeasurement remain owed.
The disposable measurement source and worktree were removed after their JVMs
exited; summaries and complete config-refusal envelopes remain under `tmp/`.

### Scratch namespace removed — reset publication follow-up

`test/seon/test/offenders_test.clj` was disposable measurement code, not a
maintained regression. It was removed at the end of the preceding slice;
`test -e test/seon/test/offenders_test.clj` is false at `f850562da`, and
`git status --short` shows no untracked test namespace. The reset had indexed
it before that removal. Its published row is therefore stale relative to HEAD;
this lane does not operate the shared publication to retract it. Subsequent
iterations select the original maintained test namespaces directly. No temporary
test namespace will be recreated in the shared tree.

### Refreshed export: covered members pass; reach reads follow requested tests

The refreshed export is `f0f25b5f3b991e835f81854fd95296c84e7053d484f4b53b59e98eff75fbac75`.
Run **e63e0d5af33e**, original maintained namespaces, executed 34 tests / 371
assertions / 5 failures / 2 errors. The covered-member correction is now verified:
`no-double-execution` passed in **4,686 ms**, including the two report and four
completed-member counts across admitted/covered membership. Platform claims
passed in **2,709 ms**. The deliberate nested failure/error events in those
fixtures are expected inputs, not outer failures.

Two assigned durations remained: both-host SCI selection **15,814.632 ms** and
named selection **5,638.320 ms**. Operation instrumentation was applied to the
tracked original tests, preserved in
[test-system-selection-cost-2026-09-23.patch](test-system-selection-cost-2026-09-23.patch),
and removed after measurement. No untracked test namespace was created.

Named selection acquired the entire program's reach rows twice, costing
**992.503 / 1,189.495 ms** in `reach-refresh`. The acquisition now starts with
the requested function/test identities and follows their stored calls,
references, test subject and schema references. Identity queries bind one
attribute and its requested values; row reads bind the resulting entity ids.
Datahike's AVET/EAVT dispatch is `reference-code/datahike/src/datahike/db/search.cljc:140–157`.
No new cache was added. The existing retained entries still skip program reads
after result-only writes; a new regression refuses any such read. The private
`reach-refresh` callers were updated together with its requested-symbol argument.

A combined heterogeneous identity-relation query refused with a null-comparison
exception. The shipped read groups identity values by attribute before querying;
the [query issue](../../../seon/issues/heterogeneous-identity-query-refuses-during-reach-acquisition.md)
records the failed form without attributing an unverified dependency cause.
The row-parity regression compares requested indexed rows with native pull and
checks that the acquisition excludes the unrelated program population.

Run **75801c3ccaed**, 36 original tests / 378 assertions / 4 failures / 2 errors,
verified these six bodies (elapsed milliseconds, reporter begin/end for passes):

| Test | Previous measured ms | Latest ms | Verdict |
|---|---:|---:|---|
| `runner-test/no-double-execution` | 26,119.140 | 4,472.982 | pass |
| `runner-test/platform-claims-and-original-bounds-govern-bulk` | 30,307.987 | 2,639.568 | pass |
| `runner-test/selection-is-one-function-on-both-hosts` | 58,060.672 | 15,220.769 | duration failure |
| `selection-test/fileless-sci-tests-use-the-same-selection` | 13,030.504 | 2,722.371 | pass after the first SCI acquisition |
| `selection-test/named-selection-reuses-green-members-by-reachable-content` | 5,638.320 | 3,133.237 | pass |
| `selection-test/omitted-dirty-callers-use-head-and-carry-recordable-provenance` | 15,086.699 | 543.164 | pass |

Named reach acquisition is now **17.559 / 3.430 ms**, with its semantic assertions
passing. Final focused run **32fd2d583265**, including the result-only-write
regression, passed **3 tests / 10 assertions**, zero failures/errors.
`clojure -M:test` required runner, runner-test, selection-test, reach-test and
test-reaching-test successfully before commit. The latter's private call was
converted; its unrelated older fixture bodies were not claimed green.

### Remaining decision: repeated config compilation during SCI namespace load

The first SCI acquisition loads the published JVM namespaces. In the latest
split, `load-core-namespaces!` took **10,780.097 ms**; actual SCI base construction
was **466.425 ms**. Namespace initialization called `config/compile-settings`
**35 times, 6,282.947 ms total**, including **35 complete declaration projections,
3,428.804 ms**. These are inclusive timings, not additive components. The
largest individual namespace load was `seon.db-test` at roughly 852 ms in the
preceding split; the cost is spread across many initializers, not one huge test.
`src/seon/config.clj:540–543` constructs a fresh complete declaration projection
on each call. This is the next algorithmic target; no longer allowance, eager
fixture publication or clock exclusion was introduced. The first SCI caller
pays it, so the subsequent fileless pass is not a standalone acquisition proof.

The lane requested ownership of `src/seon/config.clj` for that bounded correction;
it is outside the named test-system files and was not edited. The existing
[publication/SCI acquisition issue](../../../seon/issues/full-publication-tests-exceed-liveness-while-compiling-the-commit-projection.md)
holds this evidence. Five assigned bodies pass under bound; the sixth still fails.

The broad runs also expose older, independent reds: an unacquired task context
in `default-red-does-not-launch-confirmation`; platform rejection of the
publication fixtures' destructive observations (the publication lane's held
class); and the complete-population selection regression's refusal-contract
check plus pending members after recorded completion. These were present before
the scoped reach change. Their issue notes below record the boundaries; the
six-test result is not a claim that the two entire namespaces are green.

Broad-run triage: [unacquired task fixture](../../../seon/issues/task-execution-fixture-has-no-acquired-sci-program.md),
[publication fixture eligibility](../../../seon/issues/platform-tier-rejects-small-publication-fixture-observations.md),
and [pending selection after completion](../../../seon/issues/recorded-selection-completion-leaves-changed-members-pending.md).

### Config correction: caller scope and scratch-boot boundary

At `ae6a0cdd4`, the initial working tree and `src/seon/config.clj` were clean.
No production edit was made in this investigation. The recompiling caller is
`src/seon/config.clj:644`: zero-argument `defaults` calls `compile-settings`,
which constructs the declaration projection at `:542` and reads the shipped
document at `:545`. The measured 35 calls / **6282.947 ms**, including
**3428.804 ms** of declaration projections, remain the before measurement.

This API receives no manifest, projection, or environment. Concrete load-time
callers include `test/seon/sci/eval_test.clj:46` and
`test/seon/render/value_test.clj:28`, both top-level `caps` definitions.
`src/seon/sci/eval.clj:1311` loads the published core namespaces, triggering
those initializers. A textual inventory (`rg -n 'config/defaults' src test`)
found 168 references in 83 files, including comments and indirect references;
this is not a count of executable calls. Production consumers include
`src/seon/instrument.clj:982`, `src/seon/render.clj:88`, and
`src/seon/render/transcript.clj:896`. A closure or global immutable map behind
the existing zero-argument function would still fetch shared state at call time;
it would not implement the requested explicit carried-value rule.

The compliant change is to acquire compiled config at the owning boot/fixture
boundary and pass its effective value through consumers, converting load-time
initializers into consumers of that value. That requires a caller conversion,
not just a change within config.clj. Under AGENTS.md's owner design gate, the
lane requested a scope decision before production edits: (1) explicit carried
config through callers (recommended; cross-owner conversion), (2) an explicit
exception for one immutable shipped-default value (smaller, but relaxes the
no-global-fetch ruling), or (3) retain the current API and leave the sixth red.
No exception was inferred and no cache, memoization, or timing allowance added.

The requested baseline command was attempted on a newly created private root:
`bin/seon --root tmp/test-system-root start test-system`. It failed in the
`namespaces` phase, before config, with `Could not locate seon/cluster__init.class,
seon/cluster.clj or seon/cluster.cljc on classpath.` Start elapsed **2473 ms**;
that is a failed-start interval, not a config measurement. MCP runtime status
for that explicit root returned no clusters or sessions. `down` completed;
the process table showed no scratch JVM, and the root was deleted. The
[scratch-boot issue](../../../seon/issues/scratch-boot-cannot-load-the-cluster-namespace.md)
records this foreign verification boundary. No boot before/after improvement
or sixth-test improvement is claimed.

### Original 18 offenders: final measurements for the config-constant slice

These are dated measurements from the runs recorded above, not a new suite run.
Publication refusal durations are deliberately not presented as after results.

| Test (namespace prefix `seon.`) | Original ms | Latest successful ms | State |
|---|---:|---:|---|
| `cluster.registry-test/reset-returns-a-cluster-to-source-state` | 5148.969 | 2310.110 | pass |
| `cluster.registry-test/retiring-one-cluster-reclaims-only-its-own-tail` | 7063.823 | 3895.885 | pass |
| `cluster.registry-test/two-clusters-write-independently` | 5307.073 | 2310.345 | pass |
| `cluster.source-lineage-test/existing-clusters-remain-on-their-chosen-source-commit` | 11080.855 | — | awaiting redesign slice 4 |
| `cluster.source-lineage-test/stale-incremental-upsert-preserves-the-newer-publication` | 8986.838 | — | awaiting redesign slice 4 |
| `cluster.source-test/incremental-first-party-publication-retains-complete-scalar-rows` | 7250.634 | — | awaiting redesign slice 4 |
| `cluster.source-test/incremental-publication-does-not-change-an-existing-cluster` | 6276.569 | — | awaiting redesign slice 4 |
| `cluster.source-test/incremental-upsert-derives-scalar-safety-from-the-installed-schema` | 5963.102 | — | awaiting redesign slice 4 |
| `cluster.source-test/incremental-upsert-records-source-identity-on-the-expected-commit` | 5704.520 | — | awaiting redesign slice 4 |
| `flow-configuration-test/every-built-graph-proc-declares-a-specific-workload` | 11876.568 | 2537.603 | pass |
| `test-runner-test/gate-completions-travel-as-a-file-not-as-code` | 11834.051 | 3274.513 | pass |
| `test-support-test/simultaneous-fixture-bases-never-open-the-published-store` | 6542.267 | 6339.881 | within declared 10000 ms; three physical copies and two full file-hash comparisons |
| `test.runner-test/no-double-execution` | 5116.567 | 4452.159 | pass |
| `test.runner-test/platform-claims-and-original-bounds-govern-bulk` | 30929.637 | 2625.914 | pass |
| `test.runner-test/selection-is-one-function-on-both-hosts` | 13917.317 | — | latest 10075.075 ms; duration failure |
| `test.selection-test/fileless-sci-tests-use-the-same-selection` | 6337.355 | 2671.323 | pass after first SCI acquisition |
| `test.selection-test/named-selection-reuses-green-members-by-reachable-content` | 14137.688 | 3086.924 | pass |
| `test.selection-test/omitted-dirty-callers-use-head-and-carry-recordable-provenance` | 15795.024 | 492.542 | pass |

### Shipped defaults are an immutable program constant

Owner ruling after `04da80fe9`: option 2, narrowly. The shipped manifest is a
resource of the program. `config/defaults` is now a plain `def`, compiled when
`seon.config` loads and recomputed when that namespace reloads. There is no
atom, delay, memoization or cache behind this value. Cluster configuration
still travels through existing explicit inputs. AGENTS.md §2.1 records this
exception. Config was clean immediately before editing. Every executable
zero-argument call was converted in the same slice; the renderer's indirect
Var invocation became a dereference. Instrumentation no longer delays an
acquisition of this constant, and its regression retains policy equality,
wrapper reuse, changed-policy and interpreted-wrapper assertions.

The first eager-load attempt exposed why a complete schema projection cannot
belong to this constant: `config/compile-settings` reached
`schema/compilable-form` → `seon.test.runner` → `seon.cluster.source` →
`seon.fn` → `seon.test.accretion`, while `seon.effect` was still loading.
The complete refusal was `namespace 'seon.effect' not found` at
`seon/test/accretion.clj:1:1`. No loader exception or deferred default value
was added. `src/seon/config.clj:540` now follows Malli references from the two
config composites, using `schema/direct-references` (`src/seon/schema.clj:818`)
and Malli's existing reference walker (`:60–78`). Only those declarations enter
`schema/declaration-projection`. This is a derivation from canonical forms,
not a schema roster. Explicit `compile-manifest` still acquires the complete
projection once and validates initialization entities against it; the shared
settings compiler receives that projection and document as arguments.

The reproducible [fresh-load measurement](test-system-config-load-2026-09-23.clj)
observed **one projection construction, 108 schemas, 9.366 ms**, and 79 effective
defaults. Config namespace loading, including its other required namespaces,
took **1437.165 ms** after schema was loaded. This is not a boot-config interval.
The earlier scratch-boot classpath failure remains the boot measurement boundary;
no shared/default process was operated or adoption proof claimed.

The original-test operation measurement, run **10c7914dcd7c**, reports:

| Operation during both-host selection | Before | After |
|---|---:|---:|
| Settings compilation | 35 calls / 6282.947 ms | 1 explicit manifest compile / 2.800 ms |
| Declaration projection construction | 35 calls / 3428.804 ms | 1 / 97.099 ms |
| Load published JVM namespaces | 10780.097 ms | 5794.833 ms |
| SCI base construction | 466.425 ms | 547.255 ms |
| Complete test | 15220.769 ms | **10075.075 ms — still fails 5000 ms** |

The settings timing excludes its supplied projection, whereas the old compiler
constructed that projection internally; the projection row reports that work
separately. The constant was already compiled at namespace load, so the remaining
compile is the test's explicit cluster manifest. Timings are inclusive where
operations nest; do not add them. The largest remaining namespace loads were
`seon.db-test` **955.328 ms**, `seon.cluster.store-transact-test` **242.472 ms**,
and `seon.flow-test` **163.876 ms**. `src/seon/sci/eval.clj:1311` still loads the
whole published core namespace population inside first SCI acquisition. Moving
that work outside the measured body or widening its bound was not part of this
constant ruling and was not done. The sixth test's requested under-bound result
is therefore **not achieved**; this is the next bounded decision, not a green
claim about the config slice.

The full two-namespace iteration recorded **34 executed / 370 assertions /
4 failures / 2 errors**. Besides the assigned duration failure, the pre-existing
unacquired task, publication-fixture eligibility and complete-population selection
failures remain the same issue classes linked above. The temporary timing
wrappers were removed. The new maintained config parity regression passed
**1 test / 2 assertions**, zero failures/errors, in **211.150 ms** (run
**86f36a2f0f57**); the final armed rerun after the compiler contract change also
passed, run **bf694745ede5**. A `clojure -M:test` load required all **84 changed
namespaces**, including the converted callers, successfully.

Cluster-config fallback callers found and deliberately not redesigned:

- `src/seon/render/transcript.clj:896`: `agent-config` promises the agent's
  cluster configuration but returns shipped defaults when that read is absent
  or refused.
- `src/seon/sci/eval.clj:1738`: `record-acquisition-refusals!` replaces a refused
  database config read with shipped defaults before choosing error limits.

Their call syntax changed with the constant; their existing fallback behavior
did not. Fast overlays explicitly excluded foreign edits in `src/seon/cluster.clj`,
`src/seon/fn.clj`, and `test/seon/cluster/publication_delta_test.clj`; those
callers used HEAD bytes. No publication test, foreign hunk, or session was edited.
