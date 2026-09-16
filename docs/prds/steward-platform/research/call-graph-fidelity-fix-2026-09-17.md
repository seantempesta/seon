# Call-graph fidelity implementation — 2026-09-17

Status: committed for orchestrator review; adopted verification incomplete.
No test JVM launched; default PID 53320 has not been stopped or restarted.

## Grounding and dependency ledger

Read end to end: `call-graph-fidelity-2026-09-17.md` (Option B),
`test-system-is-the-database-prd-2026-09-17.md`, parent PRD §4b,
`tmp/orchestrator/wave2/repl-rule.txt`, `src/seon/fn/analyzer.clj`,
`reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj`, and
`reference-code/clj-kondo/analysis/README.md`. The assigned
`reference-code/clj-kondo/doc/analysis.md` does not exist; the latter is its
actual location. Read AGENTS.md §§0–5 and 7 and matching data-oriented-clojure,
repl, clojure-testing, data-modeling and datahike skills.

Read the owning function construction in `src/seon/fn.clj:317–607`,
`runtime-analysis-batch`, `analysis-rows-by-file`, `source-rows` (S1,
`6312fcef0`), artifact/manifest construction and reach queries. Read the
kondo implementation-body seam (`impl/analyzer.clj:2380–2487`) and HOF
recognition (`:3480–3510`). Kondo already emits protocol implementation
spans (`impl/analysis.clj:192`) and defmethod target markers (`:32–39`);
instance invocations (`:216`) have no resolved first-party target.
No dependency pin changes. `seon.program/canonical-row` owns row attribute
selection; `seon.fn/reconcile-tx` and `seon.db/transact!` own fixture writes.

## Baseline on default

Fresh JVM query reproduced the research's numbers: 5,019 function identities,
409 with no incoming edge, 312 public functions with no reaching test,
8 capability handlers with zero reach, 28 seon.print functions with zero
reach. Public-function difference: 286.34575 ms. Single gate-set samples:
`seon.id/digest` 495 tests / 9.026042 ms; `seon.print/emit` 460 / 5.80875 ms;
`seon.fs.jvm/read` 0 / 0.650458 ms; `seon.db/q` 1,058 / 19.311625 ms.

## Slice 1 — implementation attribution

Kondo analysis now requests protocol implementations and retains defmethod
and dispatch-value facts. Span containment attributes body usages to the
protocol method or multimethod identity. An unannotated defmulti is admitted
from its `defined-by->lint-as` declaration even when kondo provides no
arglists; its empty arglist sequence asserts no arity.

REPL-first probe: protocol body target attributed to `fidelity.probe/pm`,
multimethod body target to `fidelity.probe/dispatch`; both produce call edges.
Fixture: `test/fixtures/call_graph_fidelity/implementations.txt`.
Regression: `seon.fn-test/implementation-bodies-contribute-edges-and-test-reach`.
The proposed definitions were evaluated in default before editing source.
The regression used `seon.test/run` with its own loader, explicit connection,
`seon.test.runner/provenance`, and `:seon.test/remaining-ms 180000` in a future.
First result: **4 pass / 0 fail / 1 error**, the error reporting instrumentation
drift during concurrent adoption (20 added wrappers, including our analyzer
and operator functions). This is not claimed green. Adoption is queued behind
other lanes at the operator lifecycle lock. No foreign session was operated.

## Boundary

In-process results are iteration evidence only. The orchestrator must read
all diffs and the fixtures before requesting its batched cold gate.

## Slice 2 — declared function values

The regression `declared-function-values-contribute-edges-without-arities`
ran in default with the same three-argument run form: **12 pass / 0 fail /
0 error**, covering capability, graph Var, declared renderer and task function
values. It asserts both edges and reach and rejects invented call arities.
Kondo does not emit a usage for `var` itself; its parser's `:var` node owns
Var-quote span recognition. This was probed before implementation.

`declared-function-targets` classifies values through schema aliases and scalar
symbol/identity declarations, joining consumers via existing keyword facts.
`:seon.fn/reference-to` describes a reference's target identity, accretively;
capability and schedule function refs declare it. Stored task refs contribute
edges at query time through this property and attribute-consumer facts, so a
new task does not require reindexing source. Whole-graph reach and gate-set
share that relation. The artifact regression also covers literal task values.
No property-name roster or regular expression was added.

A live build-artifact of `src/seon/print.cljc` returns 81 rows and now includes
`emit` → `emit-sequential`, `emit-map-like`, protocol methods and other body
callees. This is a JVM owner probe, not an adopted-database measurement.

## Slice 3 — reference facts and conservative selection

`:seon.fn/references` stores known targets with no resolved call shape.
Kondo's quoted-symbol analysis supplies requiring-resolve literals; source
spans supply their referencing definitions. apply/partial/comp and syntax
quotes use the same rule. File-level `:seon.fn/unresolved-references` retains
targets with no attributable callable definition and widens selection.
Gate-set and the whole-graph Datalog relation follow reference edges;
manifest selection follows the same facts. Missing identity / unattributed
reference selection widens instead of claiming an empty set is sufficient.
The existing result-reach recorder and destructive-call-path follow refs too;
those consumers were read before editing, are not protected paths, and are
included in this slice so the new relation cannot silently disappear there.

REPL probe before source edit recovered apply and requiring-resolve targets
without target arities. Fixture:
`test/fixtures/call_graph_fidelity/references.txt`; regression:
`seon.fn-test/unresolved-call-shapes-preserve-reference-edges-and-reach`.
Post-adoption result pending. Schema changes are new keys and optional entries;
no existing stored key changes meaning. **No RESET NEEDED for this slice.**

## Verification follow-up

The first reference regression returned 5 pass / 10 fail / 0 error against
pre-adoption definitions. Its recorded reach was explicitly unknown because
`:seon.fn/references` was not installed in that database. A follow-up probe
confirmed the loaded analyzer config still lacked `:symbols`, while disk had
it: the queued publication had captured the earlier slice. This result is
retained as a failed iteration, not attributed to another lane or called green.
Consumers of the uninstalled keys were returned to their pre-adoption live
forms until adoption completes; checked-in changes remain intact.

Review found that literal declaration targets must be scoped to the file
whose consumers see them. Full-manifest and single-file indexing now share
that rule; schema declarations remain supplied operation-wide. This prevents
full builds acquiring unrelated source-file literals that incremental builds
cannot see. The same existing row owner implements both paths.

## Publication and live verification boundary (follow-up)

Commits: `15a35c2a7` (implementation spans), `af800d1a0` (declared
function values), `7eeed900d` (reference facts and selection), `7907afc7a`
(file-local literal targets). All carry the requested co-author trailer.

Publication attempts were refused for a stale `current-src` head and then
for source changes during analysis. Default remains PID 53320. A later
in-place adoption made `:seon.fn/references` and analyzer `:symbols true`
visible, but the cluster's recorded source commit was still
`6aaaeabe-d99a-5eec-9e3e-9d254e71c9c6`; visibility alone is not completed
adoption evidence.

The three fixture regressions and S1 parity were each submitted through
`seon.test/run` with `:seon.test/remaining-ms 180000`. All four returned
`:seon.test/unknown` before execution: the loaded test owner requires
`:seon.fn/destroys` declarations, but none were present in its program
facts. No protected owner was changed to evade that refusal. These are
**four refused runs, not four passing tests**.

A current `gate-set` probe for `seon.fs.jvm/read` selected 1,016 tests in
472.446375 ms (baseline: zero / 0.650458 ms). Its schema-declared dynamic
edge relation alone returned 69 edges in 0.318 ms. These numbers are from
the partially adopted live program and do not replace the requested final
before/after table.

The whole-graph coverage measurement exposed an all-pairs recursive-query
cost and non-terminating cancellation observation, recorded in
[the coverage query issue](../../../seon/issues/recursive-call-coverage-outlives-its-cancelled-future.md).
A test-rooted unary closure returned 1,032 covered public functions in
5,093.7085 ms. The measurement future was cancelled; its thread was still
observed computing afterwards. No after-count is fabricated from absence
of a completed result.

## Coverage correction and cross-file completeness

The coverage report now computes unary `tested` and `failing-function`
closures from test roots in the existing Datalog owner, rather than every
function-to-function pair. The proposed forms were evaluated in default;
the existing `tests-reaching-follows-calls-and-explicit-subjects` regression
was submitted and refused with the same missing `:seon.fn/destroys` facts.
No green is claimed. The original cancelled measurement thread eventually
exited, confirmed by a subsequent stack observation.

While `src/seon/fn.clj` held another lane's uncommitted changes, the query
correction was prepared in a detached snapshot with linked reference-code.
After that lane committed `4e6004789`, the same targeted change was applied
in the main tree, preserving its destructiveness declarations. The scratch
worktree was removed without running any test JVM.

A cross-file defmethod probe with a required dispatch namespace showed the
body target correctly attributed to `fidelity.dispatch/operation`. A partial
analysis lacking that dispatch definition previously had no artifact row on
which to retain the edge. `references-by-caller` now takes the operation's
actual declarations for file-level uncertainty: the full analysis has no
unresolved reference, while the partial analysis retains both dispatch and
body target. This causes conservative widening and a full publication when
incremental ownership is insufficient. A two-file fixture regression asserts
the full edge/reach and partial-artifact widening. `gate-set` checks unresolved
references on every reached caller, matching manifest selection's closure.

## Measured interim comparison — not final adoption proof

These are observed values on default's partially adopted program, with the
unary coverage correction hot-reloaded. They are **not** the requested
post-adoption acceptance measurements. Concurrent work also increased the
function population from 5,019 to 5,070.

| Measurement | Before | Interim |
|---|---:|---:|
| No incoming call edge | 409 | 301 |
| Public functions without reaching tests | 312 | 170 |
| Capability handlers with zero reach | 8 | 0 |
| seon.print functions with zero reach | 28 | 14 |
| Public coverage query, ms | 286.34575 | 4,239.94975 |
| gate-set id/digest: tests / ms | 495 / 9.026042 | 1,184 / 88.740875 |
| gate-set print/emit: tests / ms | 460 / 5.80875 | 1,207 / 76.671917 |
| gate-set fs.jvm/read: tests / ms | 0 / 0.650458 | 1,016 / 51.775458 |
| gate-set db/q: tests / ms | 1,058 / 19.311625 | 1,220 / 120.5375 |

The wider graph costs more to query. These single-call timings are not a
benchmark distribution. Scripts, exact selected symbols and raw results are
in [the evidence directory](call-graph-fidelity-evidence-2026-09-17/).
The database-derived verification selection contained 32 changed functions
and 1,203 tests, including both required namespaces. Refused smoke runs mean
that batch has not yet been executed.

The latest completed publication compiled 39,430 entities / 19,476 identities /
35,692 keyword facts, then its writer refused `my.agent/identity` at
`[4499 :seon.fn/keywords #{:seon.agent/id :seon.db/db}]`, with
`:seon.db/invalid-write`, expected set / offending `:seon.error/unknown`.
A direct `build-artifact` probe returned the correct two-keyword set. This
names the measured writer boundary without guessing its internal cause.

## Review map at the final source revision

- `src/seon/fn/analyzer.clj:323` attributes implementation bodies;
  `:361` obtains Var-quote spans from kondo's parser.
- `src/seon/fn.clj:350` retains unresolved call shapes;
  `:549` derives declared function values; `:1066` joins edges into rows;
  `:1130` routes runtime declarations through that same owner.
- `src/seon/fn.clj:1259` derives unary coverage; `:1309` derives gate-set
  through calls, references and schema-declared dispatch refs.
- `resources/seon/schemas/seon.fn.edn:4`, `:40`, `:41` declare the three
  accretive facts; `resources/seon/schemas/seon.schedule.task.edn:3`
  declares its function reference's target identity.
- `test/seon/fn_test.clj:2488`, `:2504`, `:2529`, `:2548` are the four
  fixture regressions. S1 parity remains
  `test/seon/program_test.clj:1066`; it has not been reported green here.

The final small follow-up computes the operation's declared caller set once,
then supplies it to every file's unresolved-reference derivation.

## Final review boundary

Additional commits: `b13705fc7` (test-rooted coverage plus partial artifact
uncertainty and its two-file regression), `9a0ae9bc9` (derive declared callers
once). The last six smoke submissions all returned `:seon.test/unknown`
before assertions; their exact values are in
[final-smoke-progress.edn](call-graph-fidelity-evidence-2026-09-17/final-smoke-progress.edn).
The full selected batch was not run; S1 parity is **unverified**, not green.

The final publication reached branch creation and waited at
`datahike.gc-guard/acquire-reachability-permit!` (`gc_guard.cljc:203`) →
`datahike.versioning/branch!` (`versioning.cljc:231`) →
`seon.cluster.registry/branch!` (`registry.clj:206`). Thread:
`Clojure Connection seon.cluster/default 2806`, state WAITING. The existing
[roster-permit issue](../../../seon/issues/an-interrupted-fixture-leaks-datahikes-roster-permit-and-wedges-the-jvm.md)
now carries this observation. The holder's origin was not established.
No protected owner or other lane's session was operated.

Last checked published source: `6aaaf664-0ecd-5fae-a037-421814f727d6`;
adopted source: `6aaaeabe-d99a-5eec-9e3e-9d254e71c9c6`.
The lane ended only its own client PID 23608 (verified exact command, exit
143); the JVM-side wait is explicitly outstanding. Default PID 53320 was
never stopped, restarted or signalled. Every lane shell has ended, all lane
probe futures have completed or been cancelled with observed thread exit,
and the snapshot worktree has been removed. Evidence was copied out before
scratch removal.

**STOP FOR ORCHESTRATOR REVIEW; NOT GATE-GREEN.** No test JVM, `bin/test`,
or `bin/test-fast` was launched. The orchestrator must review all six source
commits, restore a usable adopted verification boundary through its owning
lane, then re-run `seon.fn-test`, `seon.program-test`, `seon.fn.analyzer-test`,
`seon.test-reaching-test` and the derived reaching tests. The interim table
must be re-measured after successful adoption. No existing key changed
meaning; **no schema RESET NEEDED from this implementation**.

## Batch 105 review follow-up — one slice

Read the orchestrator review end to end and every fn-test failure block in
`tmp/orchestrator/gate-results/batch-105.log:370–560`. Re-read the complete
REPL rule and performance issue. The original authority/seam reads above
still apply. Additional dependency ledger: kondo's parser initializes
`reader/*reader-exceptions*` to nil (`reference-code/clj-kondo/parser/clj_kondo/impl/rewrite_clj/reader.clj:15`),
its unmatched opening delimiter path swaps it (`reference-code/clj-kondo/parser/clj_kondo/impl/rewrite_clj/parser/core.clj:121`), and
`analyze-input` binds a fresh atom (`reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj:4454–4461`).
Our secondary Var-quote parse omitted that binding. This was not a cancelled
future or another lane's defect.

One review slice changes the existing owners:

- `src/seon/fn.clj:347`: kondo's macro fact excludes a usage from runtime
  calls and arities. First-party macro dependencies remain references
  (`:353`). The static-index regression checks every macro-marked usage in
  its source, including defining macros, and checks the retained reference.
- `src/seon/fn/analyzer.clj:362`: bind the parser's error collector at the
  secondary parse. `build-manifest` rejects blocking analysis before building
  rows (`src/seon/fn.clj:1958`); malformed-source unit evidence preserves the
  syntax findings. The original fresh-branch-unpublished regression remains.
- `src/seon/fn.clj:1263,1321`: resolved incoming calls (including declared
  dispatch) take precedence. References participate only when that target
  has no resolved incoming caller. Unknown identities match only explicit
  pending subjects. File references join to tests in that file. The coverage
  rules and manifest selector use the same policy
  (`src/seon/test/selection.clj:123`).
- `src/seon/fn.clj:1371`: `gate-sets` acquires declaration and file-reference
  relations once per operation and hands them to each indexed walk. The
  detector uses that bulk owner (`src/seon/issue/detect.clj:280`). No retained
  cache or new execution machinery.
- `test/seon/fn_test.clj:1403`: assert the exact AVET scan set, including the
  references scan only at unresolved incoming targets. The new scoped fixture
  (`test/fixtures/call_graph_fidelity/scoped.edn`) asserts exact database,
  Datalog-rule and manifest selections, file-local widening, missing identity,
  and one declaration acquisition per bulk request (`:2647`).

All nine batch-105 failing tests belong to this lane's earlier changes:
`call-arities-refine-exactly-the-stored-call-edges`,
`capability-metadata-is-one-program-graph-contract`,
`defining-forms-share-one-form-local-kondo-batch`,
`file-artifacts-and-manifests-are-byte-digested-and-deterministic`, and
`static-index-preserves-the-jvm-row-contract` share the macro classification
root. `declared-function-values-contribute-edges-without-arities` and
`tests-reaching-follows-calls-and-explicit-subjects` share global widening.
`gate-set-walks-only-indexed-callers-once` needs the exact scoped scan contract.
`blocking-analysis-keeps-the-fresh-branch-unpublished` is the parser binding.
No foreign commit is blamed for those failures.

### Verification and boundary

The new forms were evaluated in default before snapshot edits and exercised
through the existing test loader. Four original pure regressions passed
57 assertions, zero failures/errors: call-arities, capability metadata,
file artifact determinism, and static row contract. The strengthened static
regression plus malformed-source test and scoped selection checks then passed
48 assertions. The final Datalog-rule parity extension passed all 14 scoped
assertions. These overlap; they are not a summed suite count.

The scoped body used `datahike.api/with` on the held default database value
with its carried projection. This is actual complete program data, **not a
successful acquisition of the canonical test fixture**: that fixture reported
unrealized and was not forced or rebuilt. The checked-in deftest obtains its
database through `test-support/with-database`. The pure checks are not a claim
of armed, recorded, or adopted integration proof.

An exact three-argument `seon.test/run` attempt for the call-arities regression,
resolved through the existing loader with `remaining-ms 180000`, was refused
before the body: `:seon.test/unknown`, missing `:seon.fn/destroys` declarations.
The user's complete-publication write-admission boundary remains external to
this slice. No test JVM, gate, restart, manual fixture rebuild, or foreign
session operation was used.

Still requiring the cold gate: complete `seon.fn-test`, `seon.program-test`,
`seon.fn.analyzer-test`, `seon.test-reaching-test`, the original fresh-branch
unpublished assertion, canonical write admission, S1's
`indexed-and-evaluated-declarations-are-the-same-entities`, and every affected
reach-selected test. The orchestrator must review this diff before any gate.

Implementation and unit verification used isolated HEAD snapshot `19ee62479`
while `src/seon/fn.clj` and `test/seon/fn_test.clj` were concurrently modified.
The write-admission lane then landed `b1508dc8a`; this lane applied only its
own disjoint patch to the shared tree, preserving that diagnostic and
publication-refusal regression. No schema key changes meaning;
**RESET NEEDED: no**. Re-index/adoption is needed to replace old graph facts.

### Ten-function measurement

The review names four functions; the other six below exercise the changed
indexing, declaration, dispatch, and selection seams. All held-value samples
use default basis **536871803**. That value contains **0 references and 0
file unresolved references**, so the scoped policy alone leaves these ten
counts unchanged. The analyzed snapshot produces **1,642 references and 317
file unresolved references**. Comparing both policies over that SAME
read-only graph projection isolates the widening fix:

| function | held before → after | projected old policy → scoped policy |
|---|---:|---:|
| seon.id/digest | 1184 → 1184 | 1852 → 1385 |
| seon.print/emit | 1207 → 1207 | 1852 → 1382 |
| seon.fs.jvm/read | 1016 → 1016 | 1852 → 1061 |
| seon.db/q | 1220 → 1220 | 1852 → 1419 |
| seon.fn/build-manifest | 979 → 979 | 1852 → 1024 |
| seon.fn/source-rows | 335 → 335 | 1852 → 339 |
| seon.fn.analyzer/analyze | 1026 → 1026 | 1852 → 1071 |
| seon.program/canonical-row | 1027 → 1027 | 1852 → 1382 |
| seon.effect/request! | 18 → 18 | 1852 → 18 |
| seon.test.selection/reaching-tests | 3 → 3 | 4 → 4 |

These are not after-adoption numbers. Complete `datahike/with` reconciliation
was refused because held default declares `:seon.render/ai` as string while
current source declares symbol (`seon.plan/format-plan-ai` was the offending
value). The measurement therefore projected only graph/identity datoms from
`reconcile-tx`'s own transaction, preserving the held schema. It did not
commit anything or substitute for publication. Central functions still select
large closures; this slice does not claim minimal semantic dependencies.

Held-value per-call milliseconds before → after, in table order:
190.075→195.287, 22.829→18.980, 15.757→18.119, 31.867→25.770,
14.113→13.193, 6.543→5.953, 16.176→14.513, 16.543→14.336,
1.394→1.172, 1.084→0.928. These are individual observations in a shared JVM,
not statistical benchmarks. The graph-only `with` projection has different
read costs (163–196 ms per scoped call); its timings are not adoption costs.

The whole population now has **1,202 candidates**, rather than the performance
issue's earlier 1,196. On the same held value, old single-function loop:
**9,041.292 ms**; final bulk walk: **7,517.956 ms**; detector:
**7,044.122 ms**, **166 subjects**. The declaration relation is acquired once
per bulk operation, asserted independently of elapsed time.

After applying the disjoint patch to main and reloading `seon.fn-test` through
the existing loader, the final unit pass was **85 assertions / 0 failures /
0 errors**: five pure deftests plus the scoped selection body on the held
value. The fixture-owning wrapper was not claimed executed. Raw counts,
selection digests, timings, the recorded refusal, and a repeatable read-only
measurement form are in
[call-graph-fidelity-review-evidence-2026-09-17](call-graph-fidelity-review-evidence-2026-09-17/measurements.edn).

The final scoped check also asserts the stored resolved call and the stored
reference it suppresses: **16 pass / 0 fail / 0 error**. All lane futures
finished; temporary bindings, the isolated worktree, named scratch, and the
nine fixture directories created by these unit invocations were removed.
The retained batch-105 root and foreign files were untouched.

## Orchestrator correction — references always contribute reach

The orchestrator rejected the reference fallback in `3f0be21ed`. The earlier
fallback claims and associated green assertions above describe that rejected
policy, not the current guarantee. A resolved caller never suppresses another
caller's uncertain reference.

`gate-set-in` now scans both `:seon.fn/calls` and `:seon.fn/references` for
every visited identity and unions both with declared and file-scoped incoming
edges (`src/seon/fn.clj:1318`). The Datalog relation has direct clauses for
all four sources; the negative join and its intermediate resolved-edge rule
are gone (`src/seon/fn.clj:1263`). The manifest selector likewise unions
calls and references (`src/seon/test/selection.clj:123`). The detector's
explanation now states that both contribute (`src/seon/issue/detect.clj:237`).

The existing fixture has two distinct callers of the same target: the
`direct` test calls it, while the `indirect` test references it through
`partial` (`test/fixtures/call_graph_fidelity/scoped.edn:4`). The extended
`reference-selection-includes-resolved-and-reference-only-callers` regression
asserts both tests are selected, retains assertions that both edge facts are
stored, and checks database/Datalog/manifest parity (`test/seon/fn_test.clj:2649`).
The indexed-walk regression now requires both exact AVET scans at each visited
identity, including the cycle: counts `[6 6 0 2 0]` (`test/seon/fn_test.clj:1403`).
File-local widening and one declaration acquisition per bulk operation remain.

### Single read-only measurement and verification boundary

Before any prepl use, `bin/seon status` reported the replacement default PID
**41413 alive**, generation `0feea9b2-6557-43eb-9b2a-553599147c57`. This lane
did not operate the restart. Exactly one read-only evaluation followed. It
compiled the historical and proposed owner forms into local closures, without
replacing runtime Vars, loading test namespaces, or committing database facts.
Both received the same default database at basis **536871817**:

| bulk selection, 1,202 candidates | milliseconds |
|---|---:|
| historical 3f0be21ed fallback | 8,876.878375 |
| calls plus references | 8,449.030708 |
| earlier recorded sample, old PID/basis | 7,517.955584 |

The paired after time is 4.8% lower; the after time is **12.4% higher than the
previous 7.52-second observation** across different JVMs/bases. These single
samples do not establish a performance improvement. Crucially, this default
value has **zero reference facts**: zero selections widened and zero tests
were lost. The additional cost of traversing populated references is therefore
**unmeasured**, not asserted cheap; fidelity is retained regardless of that
future measurement.

The repeatable form and exact returned numbers are
[reference-union-measure.clj](call-graph-fidelity-review-evidence-2026-09-17/reference-union-measure.clj)
and [reference-union-measurement.json](call-graph-fidelity-review-evidence-2026-09-17/reference-union-measurement.json).
The proposed indexed walk compiled and ran in that evaluation. The extended
regressions and Datalog/manifest behavior have **not been executed** in this
follow-up: the one-read-only-evaluation restriction excludes test loading and
fixture runs. `git diff --check` passed; the measurement form passed a native
Babashka reader check. No test JVM, gate, extra prepl evaluation, default
restart, or scratch worktree was used. No schema changes; no reset required
by this correction. STOP for orchestrator review before any gate.
