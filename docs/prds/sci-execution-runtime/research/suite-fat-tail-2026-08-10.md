---
type: research
status: complete
tags: [testing, performance, operator, database]
---

# Complete-suite fat-tail attribution — 2026-08-10

## Result

The measured tail is four classes, not twenty independent slow tests:

1. **The same complete program population is rebuilt into many private
   roots.** A current in-process publication takes 45.079 s after namespace
   load; 34.781 s is program-row publication. Tests that need an independent
   cluster or file store call `refresh-source!` or `populate-database!`
   directly instead of forking the one immutable suite source base already
   owner-ruled in the test-infrastructure specification.
2. **Operator composition republishes even when the requested operation is
   only a branch fork.** `init NAME` always calls `refresh-source!`, and
   `reset --force` calls bare `init` and then named `init`, so reset publishes
   twice after cleanup. The cold parent also buffers every source-child line
   until exit, hiding the progress the child already emits.
3. **One property rebuilds a complete trial environment for every generated
   value.** The turn property spends 0.974 s on an empty `with-cluster` trial;
   48 empty trials project to 46.75 s of its observed 58.182 s.
4. **Two tests contain substantial intended generated or concurrency work.**
   The situation property executes 200 committed database scenarios. The
   concurrency test executes 270 guarded forms across 45 agents. Their
   sample counts cannot honestly be cut from the historical red run; first
   remove source publication and fixture reconstruction, then remeasure the
   green workload.

The co-hosted 2,616.797 s outlier is excluded. Commit `b661d7f48` already
made foreign entities unrepresentable in reconciliation's wildcard-pull
input. Its source restriction is the pattern for this work: narrow the work
input structurally rather than hide the cost with a longer backstop or a
`:seon.test/long` marker.

## Scope and evidence quality

Phase 1 was diagnosis only. No production or test file was edited. Probes
lived under `tmp/`, used independent roots, and did not address the shared
complete-gate process.

The historical timings come from `tmp/full-gate-2026-08-10.log`, whose suite
ran commit `e85b847f5`. That run overlapped source changes and several tests
failed, so its BEGIN/END deltas are valid tail measurements but its red
assertions are not used as cause evidence. Current-tree probes ran at
`88f921b58417c230610e3cad514e120b8e782992` with no source or test diff.

The following authorities were read end to end before attribution:

- `docs/prds/sci-execution-runtime/research/cohost-boot-speed-2026-08-10.md`;
- commit `b661d7f48`, including its production and regression diffs;
- `docs/prds/sci-execution-runtime/plan/test-infrastructure-spec-2026-08-07.md`;
- `docs/prds/sci-execution-runtime/research/suite-liveness-2026-08-02.md`;
- the active program roadmap and localized PRD instructions; and
- the relevant test and production owners named below.

## Dependency ledger

| Boundary | Selected source | First-party use |
|---|---|---|
| Datahike serialized writer | `10540578248eaa686c1f88a7fe57644ee4c9f993`; `reference-code/datahike/src/datahike/writer.cljc:80-190,393-420` | `src/seon/fn.clj:1276-1380`; every extra program phase is another complete writer transaction |
| Datahike branches | same pin; `reference-code/datahike/src/datahike/versioning.cljc:212-330` | `test/seon/test_support.clj:320-349`; production source-base target in the test-infrastructure specification |
| SCI copy-on-write fork | `6ee57c9c3e73e5b8224fde851e33a1e2a8e08383` | `src/seon/sci/eval.clj:180-230,1505-1545`; owner-ruled `start-fork!` target |
| `clojure.test` reporting | `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d` | `src/seon/test/runner.clj:150-185`; only namespace and Var boundaries currently advance suite progress |
| Flow graph construction | core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051` | `test/seon/cluster/turn_test.clj:149-225`; measured below and falsified as the turn-property cost |

## Publication breakdown

The probe bound `seon.cluster/*source-progress!*` around one fresh
`cluster/refresh-source!`. Namespace loading and refresh were measured
separately.

| Phase | Elapsed | Increment |
|---|---:|---:|
| Require `seon.cluster` | 11.334 s | 11.334 s |
| Source analysis complete | 6.051 s | 5.706 s |
| Schema population complete | 8.398 s | 2.169 s |
| Contract projection complete | 9.171 s | 0.676 s |
| Contract rows complete | 9.875 s | 0.704 s |
| Namespace rows complete | 12.328 s | 2.452 s |
| Declaration rows complete | 35.114 s | 22.786 s |
| Keyword rows complete | 38.884 s | 3.770 s |
| Call rows complete | 43.234 s | 4.351 s |
| Branch publication complete | 44.876 s | 1.641 s |
| `refresh-source!` returned | 45.079 s | 0.203 s |
| Fresh process total | 56.413 s | 11.334 s load + 45.079 s refresh |

`seon.fn/index!` currently lets the optional progress callback determine
transaction mechanics: callback presence partitions each ordered phase into
at most six transactions. `populate-source!` always supplies that callback,
including when nobody can observe it.

A direct falsifier preserved the same population but made the two-argument
`index!` call use the one-transaction-per-phase arm. Program rows changed from
34.781 s to 27.143 s and the refresh changed from 45.079 s to 36.487 s, an
8.592 s or 19.1% reduction. This does not make publication fast enough, but it
proves instrumentation-induced transaction multiplication is one cause.

The larger correction is one source base. The listed tail contains at least
22 complete populations: direct cluster roots, two explicit file-store
populations, cold operator initialization, and reset's two publications. Even
if four publication-specific proofs retain a real rebuild, avoiding 18
current 36.487–45.079 s populations removes a projected 657–811 s
(11.0–13.5 minutes) from this tail without weakening one assertion.

## Operator breakdown and the reported wedge

Three direct observations separate JVM startup, publication, and branch work:

| Operation | Wall time | Observation |
|---|---:|---|
| Cold `bin/seon --root ROOT init` | 80.45 s | no parent output before the terminal line |
| Offline `status`, no clusters | 9.53 s | cold JVM roster read; no publication |
| `init NAME` on an already-published root | 64.73 s | no parent output; republished before a cheap branch fork |
| In-process start from published source | 4.908 s | new branch and READY |
| In-process reopen of the same branch | 2.866 s | existing branch and READY |

The 64.73 s named init is source-proven, not an inferred cache miss.
`init-form` executes `refresh-source!` in both its cold and live named arms
(`script/seon/fresh_operator.clj:1966-2081`) before calling the branch-only
`named-init-form` (`:1932-1964`). `reset!` (`:2655-2678`) then composes bare
`init!` and named `init!`, paying publication twice after cleanup.

The old forced-reset test's 185.468 s and its 90 s helper failure follow from
that composition: `run-operator` has a 90 s last-resort backstop around the
observable child `.onExit`, while one reset command contains cleanup plus two
complete source children. The backstop is exposing slow composition; raising
it would hide the defect.

The alleged 90-minute `await-process!` wedge is a different claim and is
refuted by the retained log:

- the whole suite ran 5,397.21 s before exit 124;
- `init-owns-current-source-and-dormant-cluster-lifecycle` began at
  20:16:56.765Z;
- its final `down --force` Babashka process began at 20:21:55.563Z; and
- the suite watchdog fired at 300 s of reporter silence, seconds into that
  final cleanup.

The test therefore spent about 299 s successfully traversing earlier init,
status, repeated named-init, refork, and start commands. It was not parked for
90 minutes in one child wait. `await-process!` correctly waits on the
observable `.onExit`; the missing event is subtest progress. The runner only
recognizes begin/end namespace and Var reports, while both the production cold
source parent (`source-process-value!`, `:2084-2102`) and the test output
future (`test/seon/dev/fresh_operator_test.clj:535-572`) `slurp` child stdout
to EOF. Existing source progress is therefore buffered instead of advancing
either the operator or the suite.

The clean landed run in `tmp/full-gate-2026-08-10b.log` confirms the narrower
claim. The test did not wedge the suite for 90 minutes, but it did reach its
own 300-second last-resort process backstop: it ran for 297.514 s before
`await-process!` reported the missing child-exit event. The forced-reset test
independently hit the same backstop class after 157.617 s. The incident is
therefore load/mid-edit sensitive at suite scale, while the repeatable defect
remains slow, buffered multi-command operator composition rather than an
unobservable process exit.

## Generated fixture breakdown

### Turn attempts

An empty current `seon.cluster.turn-test/with-cluster` was measured after the
one process base had warmed:

| Component | Measured time |
|---|---:|
| Entire empty trial, five-trial mean | 1.020 s |
| Entire instrumented empty trial | 0.974 s |
| `sci.eval/cluster-ctx` acquisition | 0.539 s |
| `seed-cluster!` | 0.007 s |
| Work-launcher start + stop | 0.0003 s |
| Two Flow create/start/stop pairs | below 0.001 s |
| First process-base materialization | 13.274 s |

The property performs this construction 48 times. Empty reconstruction alone
projects to 46.75 s, 80.4% of its historical 58.182 s. Flow is decisively
falsified as the cause; repeated branch/projection and cold SCI acquisition
are the cost.

### Situation totality

Twenty empty calls through the work test's private fresh-database helper took
1.029 s, or 51.5 ms per database. Across 200 generated cases that projects to
10.29 s of the observed 49.550 s. The remaining roughly 39 s is generated
schema/transaction/query work, not an unexplained fixture pause. No sample
count change is justified by this measurement.

### Concurrency independence

The historical test reached its first READY line after 92.185 s, then spent
128.034 s in six scenarios and failure reporting. Its fixed
`scenario-sizes` is `[5 5 5 10 10 10]`: 45 agents, each executing six forms,
for 270 guarded evaluations plus database and transcript assertions.

The historical run failed before printing its built-in scenario timings, and
a current isolated probe encountered a foreign source/test mismatch. That is
not evidence for a per-evaluation cause. The exact prepared correction is to
remove the one source publication through `start-fork!`, keep the existing
long classification for the first green measurement, and use the already
returned per-scenario `::elapsed-ms` values before deciding whether repeated
sizes express necessary concurrency coverage. Cutting the vector now would
be green-washing.

## Test-to-class attribution

`P` is repeated complete population, `O` is operator composition/cold child
handling, `F` is per-generated-value environment reconstruction, and `W` is
intended generated or concurrent work.

| Historical test | Time | Class | Measured attribution and prepared owner |
|---|---:|---|---|
| `concurrency-independence/n-agents-fold-independently-on-one-live-cluster` | 220.219 s | P + W | 92.185 s to READY; 270 evals remain. `seon.cluster/start-fork!`, then green scenario timings. |
| `fresh-operator/forced-reset-clears-an-exact-dead-process-record` | 185.468 s | O + P | reset composes cleanup plus two publications and crosses the helper's 90 s backstop. `script/seon/fresh_operator.clj`. |
| `fresh-operator-export/export-verb-produces-an-openable-queryable-store` | 185.273 s | O + P | one cold publication, a cold 9.53 s refusal path, cluster boot, and several CLI processes. Shared operator process helper plus source base. |
| `program-restart/an-agent-definition-survives-restart-and-another-agent-calls-it` | 123.422 s | P + W | first READY at 50.992 s; current fork/reopen boot is 4.908/2.866 s. Source base removes the setup, not the restart proof. |
| `boot/explicit-refork-destroys-the-old-branch-and-forks-current-source` | 93.180 s | P | creates a fresh full publication before the refork proof. Fork from suite source base. |
| `fresh-operator/fresh-process-loads-schema-before-every-operator-instrumentation` | 79.684 s | O + P | deliberate cold process, but its source base need not be rebuilt by later commands. Operator init owner. |
| `boot/a-dead-holders-run-is-unclaimed-by-the-time-start-returns` | 78.936 s | P + W | shared namespace publication plus two real starts. Source base preserves both recovery boots. |
| `armed/two-clusters-in-one-jvm-own-distinct-live-program-contexts` | 72.583 s | P + W | one publication plus two distinct contexts. Source base and production fork constructor. |
| `curate/proof-acceptance-and-atomic-adopt-curate-one-messy-span` | 65.508 s | P + W | one publication precedes the real proof/adopt sequence. Source base. |
| `armed/an-escaped-throwable-becomes-a-fact-and-a-message` | 63.365 s | P + W | one publication precedes the live fault proof. Source base. |
| `turn/generated-model-attempt-traces-preserve-presence-and-episode-laws` | 58.182 s | F | 46.75 s projected empty reconstruction. Source-base SCI fork and branch fixture. |
| `background-blob/background-binary-results-remain-exact-across-the-inline-threshold` | 56.736 s | P + W | private file store calls full `populate-database!`. Clone/fork one immutable file-store base. |
| `boot/operator-root-history-policy-is-creation-fixed` | 55.804 s | P + W | `fresh-root-with-history-policy` performs complete publication. Suite base parameterized only by creation-fixed store policy. |
| `blob-publication/publication-and-collection-are-exclusive-in-both-orderings` | 53.717 s | P + W | private file store calls full `populate-database!`. Clone/fork one immutable file-store base. |
| `config-application/no-auth-is-consumed-as-the-credential-alternative` | 51.754 s | P | `fresh-root` publishes before one config assertion. Source base. |
| `boot/refork-does-not-collide-with-the-store-its-caller-already-holds` | 50.668 s | P | complete publication precedes the self-held-store boundary. Source base. |
| `bootstrap-drive/one-fake-o1-drive-grades-on-its-ending-commit` | 50.410 s | P + W | production `run-drives!` refreshes a new root before one drive. Accept an acquired source base and fork normally. |
| `work/situation-totality-property` | 49.550 s | F + W | 10.29 s projected database construction; about 39 s real 200-case work. Remeasure after production fork fixture; do not cut samples yet. |
| `armed/boot-seeds-the-root-agent-and-arms-the-loop` | 48.255 s | P + W | one publication before live boot assertions. Source base. |
| `config-application/applied-values-shape-the-running-system` | 48.110 s | P + W | one publication before live config application. Source base. |
| `armed/a-message-committed-during-boot-arming-is-conserved` | 45.065 s | P + W | one publication before the actual race construction. Source base. |
| `armed/booting-spends-no-model-call` | 43.627 s | P + W | one publication before the no-call proof. Source base. |

The current 4.908/2.866 s boot probe and the already-landed co-host fix mean
the historical boot-heavy rows must be remeasured; their old durations are
not evidence that current `cluster/start!` itself still costs 40–90 s.

## Prepared phase-2 fixes

### 1. Make progress observational

Owned files:

- `src/seon/fn.clj`
- `test/seon/fn_test.clj`

`index!` uses one ordered transaction per phase regardless of whether a
progress callback exists. Progress reports pure preparation milestones and
the completion of each writer transaction; it never changes batch count.
The one class regression instruments `db/transact!` and asserts identical
transaction shapes with and without progress. Expected direct improvement:
45.079 s to approximately 36.487 s before deeper writer work.

### 2. Land the owner-ruled source base and fork constructor

Owned files:

- `src/seon/cluster.clj`
- `test/seon/test_support.clj`
- `src/seon/test/runner.clj`
- the direct-refresh/file-population tests in the attribution table

Implement the already-ruled `seon.cluster/source-base!` and
`seon.cluster/start-fork!` from the complete test-infrastructure
specification. One suite builds the immutable source branch, schema
projection, and acquired base SCI `ctx`; each test gets a new Datahike branch,
generation-aware SCI fork, connection, and production instance. Delete the
test-side `database-base`, branch lease, projection reconnect, and public
access to `populate-database!` as the specification requires.

File-store tests that need blob-key isolation clone one immutable prepared
file store into the suite's isolated root before taking their branch; they do
not repopulate the program graph. The class regression mutates one fork and
asserts the base and sibling remain unchanged, then checks that the suite
emits exactly one base-build event.

### 3. Stop operator verbs from publishing by accident

Owned files:

- `script/seon/fresh_operator.clj`
- `test/seon/dev/fresh_operator_test.clj`
- `test/seon/dev/fresh_operator_export_test.clj`

Bare `init` remains the explicit complete publication operation. Named
`init NAME` reads the already-published `current-src` commit and performs only
`ensure-cluster!` or `refork-under-lock!`; absence refuses and names bare
`init`. `reset!` publishes once and passes that result directly to the default
fork instead of invoking the full init entry twice.

`source-process-value!` relays each child line as it is read while retaining
the structured terminal result. The process completion remains `.onExit`; the
clock remains only the loud silence backstop. The regression binds
`refresh-source!` and proves one bare init publishes once, named init publishes
zero times, and reset publishes once.

The CLI test helper reports structured `:seon.test/progress` events at each
operator command boundary, and the runner accepts that event as semantic
progress. It does not treat arbitrary stdout as a heartbeat. This makes a
multi-command integration test observable without allowing output spam to
mask a wedged child.

### 4. Remove per-trial cold context acquisition

Owned files:

- `test/seon/cluster/turn_test.clj`
- production source-base/fork owners from fix 2

The property receives a fresh `start-fork!` instance from the suite base.
Fresh facts and SCI mutations remain isolated, while program acquisition is a
copy-on-write fork rather than 48 cold `cluster-ctx` calls. The regression is
the unchanged 48-case property plus an instrumentation assertion that the
base is acquired once and each trial forks it. Target: remove the measured
46.75 s reconstruction term before considering any long classification.

The situation property keeps 200 cases through this wave. Its phase-2 result
decides whether the remaining roughly 39 s is irreducible enough to mark long
or whether its database projection can be simplified structurally. No
metadata change is prepared now.

## Projected gates

The historical non-cohost tests above consume 1,779.516 s (29.659 minutes).
The phase-2 lower-bound saving is:

- 657–811 s from removing 18 redundant complete populations;
- 46.75 s from turn-trial reconstruction; and
- 8.592 s for every complete publication still required after progress stops
  multiplying transactions.

Those terms overlap with named init and reset, so they are not added twice.
The conservative integrated projection is 11.8–14.3 minutes removed from the
listed tail before any improvement to the 270-eval concurrency workload or
the 200-case situation property. A full-suite projection needs a green
post-cohost baseline because the historical run terminated before completing
all namespaces.

For the bare tier, the immediate measurable target is the turn property from
58.182 s to under 15 s when selected. Curate and blob publication are not
currently long; source-base conversion should each lose one 36–45 s
population without hiding them from bare selection.

## Phase-2 proof order

1. Land observational progress and record full publication before/after.
2. Land `source-base!`/`start-fork!`; prove one base and isolated siblings.
3. Convert operator named init/reset and stream cold child progress; reproduce
   forced reset without the 90 s backstop.
4. Convert the listed live and file-store fixtures.
5. Rerun turn and situation properties with phase counters.
6. Run the concurrency test green and consume its per-scenario timings.
7. Run `bin/test --changed` for every owned path, then one full suite and one
   bare tier from the same frozen source digest.

No phase-2 edit is authorized until the orchestrator reports `gate landed`.

## Phase-2 landing evidence

The orchestrator released the tree after the clean complete run. The four
implemented class boundaries are commits `69d95a4be`, `59e71e0cd`,
`a0738794f`, and `d50275487`.

| Class boundary | Before | After | Evidence |
|---|---:|---:|---|
| Progress changed writer mechanics | 45.079 s complete in-process publication; 34.781 s program rows | 36.487 s complete publication; 27.143 s program rows | The same population probe with progress present in both cases. Transaction-shape regression proves callback presence cannot change batches. |
| Named/reset operator composition | named init 64.73 s; clean-run forced reset 157.617 s and failed | named init 21.46 s; reset 61.22 s and passed | Isolated operator roots. Reset now has one child publication and one fork; child phase lines were visible before exit. |
| Per-trial database projection/context | 0.974 s empty turn trial; generated property 58.182 s in the clean suite | 18–20 ms warmed empty trial; generated property 2.562 s inside `bin/test` | Five-trial empty probe and the unchanged 48-case property. Fresh-JVM command wall was 27.14 s including namespace load and the one 11.9 s base construction. |
| Destroy-then-fail refork | branch retirement committed before replacement | expected-head replacement is one `force-branch!`; operator has no retire-then-refork call | Injected replacement failure retains the exact prior commit and facts; focused registry/operator gate: 31 tests, 166 assertions, green. |

`source-base!` now returns the published commit, digest, activation closure,
immutable database value, canonical projection, and acquired SCI `ctx`.
New production cluster branches receive connection custody and their own
projection/call-preparation state through `fork-cluster-ctx`; existing
sovereign branches still acquire their own older program. The sibling
regression mutates one SCI fork and one database branch and proves neither
mutation reaches its sibling. The ordinary test database fork reuses the
base projection and constructs a basis-specific projection state instead of
re-deriving the complete registry per test.

The clean run's `init-owns-current-source-and-dormant-cluster-lifecycle` did
not wedge for 90 minutes; it reached its own 300 s child backstop after
297.514 s. That remains load/mid-edit sensitive evidence, not a missing child
exit event. The fixed parent streams phase progress and named commands no
longer publish, removing both the repeated work and the silent interval.

### Revised projections

The measured lower bound is about 6.4 minutes: 96.4 s on reset, 55.6 s on the
turn property, and approximately 189 s from the 8.592 s publication saving
across the 22 populations identified above; named-init removals in the
lifecycle test add roughly another minute without double-counting reset.
Projection reuse also benefits every ordinary `with-database` test, but no
suite-wide count is claimed without a new complete run.

Against the 4,827 s clean baseline, the conservative revised complete-suite
projection is **about 4,250 s (70.8 minutes)**. This deliberately does not
claim the larger phase-1 11.8–14.3 minute estimate, because the direct
scratch-root publication proofs still intentionally rebuild source.

The last retained bare-tier timing was about 9.5 minutes for 987 tests. Its
ordinary database tests now avoid roughly 0.34 s of projection work per fork,
and the turn property loses 55.6 s. Without inventing an exact invocation
count, the revised bare-tier projection is **5.5–6.5 minutes**; the next frozen
bare run must replace that range with an observed total.
