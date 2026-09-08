---
type: research
status: active
tags: [research, operator, performance, test]
---

# Hook publication coalescing — unfinished checkpoint, 2026-09-08

Checkpoint `4bd2116a2` is being verified under the owner's restart instruction.
The named-instance guard permits default adoption while beta shares its JVM.
The cohosted regression checks beta's positive source digest and stored function
source. Scheduled maintenance advances its branch head even without adoption;
ordinary forks do not carry a development adoption marker. Compiled
host JVM Vars are shared; this is not a claim of per-cluster host Var isolation.

The initially unlanded instrumentation helper was first replaced locally.
It has since landed at HEAD (`test/seon/test_support.clj:631`); the boot fixture
now uses that canonical helper to restore callable roots and the Malli registry. The incremental edit
fixture now includes the complete canonical source population. Verification is
in progress; no green gate or successful convergence timing is claimed yet.

## Read authorities and dependency ledger

Read `AGENTS.md` end to end, including the default-cluster and hook
paragraphs; the turn PRD section 10 end to end; `bin/seon-hook` end to end;
`.claude/seon-hook.edn` end to end; and the incremental publication and
development adoption path in `src/seon/cluster.clj`. Read all of
`src/seon/cluster/source.clj`.

- `bin/seon-hook` already uses a pending-state file lock, one admitted
  process, and snapshot consumption for Gemini review. The draft reuses
  that lock and extracts its worker launcher for both consumers.
- Babashka process waiting and destruction:
  `reference-code/babashka-process/src/babashka/process.cljc:116` and `:165`.
  Publication waits use its bounded dereference; editors tail one
  newline-terminated result per publication and reap their tail process.
- `src/seon/fn.clj:1390` classifies file changes. `reconcile-tx` at `:1931`
  and `index!` at `:2005` already replace exact program definitions while
  retaining identity. The draft uses that reconciliation on the existing
  source publication branch for same-identity metadata changes.
- `src/seon/cluster/source.clj` already publishes through a scratch branch
  and Datahike's expected-current-commit check; that remains the owner.
- Development adoption already derives namespaces from changed program
  identities and orders only those namespaces using `:seon.ns/requires`.
  No broader namespace reload was found by source inspection. Its reload
  loop now excludes existing namespaces without classpath source: such namespaces
  cannot be required with `:reload`. A live reload-count proof remains outstanding.

## Measurements before changes

On the inherited default JVM, `bin/seon status` printed lifecycle-lock
waits through **36,552 ms**. The holder was PID 54144, running an
`init --dev default --changed ...` operation admitted at
2026-09-08T19:38:06.564Z. This is a contention observation, not an
edit-to-convergence measurement.

MCP JVM mode answered `(+ 1 1)` in 1 ms. Reading the current source
snapshot and cached artifact took 469 ms. A subsequent changed-file
planner census took 817 ms. These are the MCP envelope's execution times.

The census compared the artifact at `data/clusters/build/current-src.edn`
with `current-source-snapshot`, then called `seon.fn/build-artifact` and
`seon.fn/plan-file-change` on each differing path. It sampled **three file
plans**, not historical publication attempts:

| File | Plan | Reasons |
|---|---|---|
| `src/seon/problems.clj` | complete rebuild | Removed and added identities; changed calls and source; cardinality-many/component changes and additions |
| `src/seon/render/web.clj` | complete rebuild | Changed `:seon.fn/keywords` and source; cardinality-many change |
| `test/seon/cluster/agent_namespace_test.clj` | incremental upsert | No rebuild reason |

Thus 2/3 sampled plans fell back, with one clearly structural edit and
one body-metadata edit. The prior incremental owner also rebuilt whenever
any unreported source path differed. No historical frequency is inferred
from this small sample. The draft adds progress lines naming publication
mode and rebuild reasons for a future actual-publication census.

## Draft changes

- Configurable `:current-source :quiet-seconds`, default 5, and
  `:timeout-seconds`, default 180.
- One source worker drains the union of pending paths after the quiet
  window. Edits during publication form the next batch. Every covered hook
  receives the same publication identity and terminal result file.
- Snapshot differences add missed paths to incremental analysis instead
  of triggering full analysis solely because the hook did not name them.
- Scalar changes retain the existing upsert path. Same-identity metadata
  changes reuse the updated manifest with the existing program reconciler.
  Identity, schema, contract, and analysis changes retain complete fallback.
- Tests were added/updated for five real hook invocations sharing one
  absent-JVM refusal, missed paths, and metadata edits avoiding complete
  analysis. These new assertions have **not run to a verdict**.
- The pre-existing schema-hook regression hit its 30-second subprocess
  bound twice. Its subprocess allowance was changed to 120 seconds;
  this adjustment is also unverified.

## Gate evidence and exact stop boundary

The baseline scoped gate used HEAD
`f7977c0e286ab0365dc6bc94ed79c50d87a0f7a3`, before these source edits.
It ran 19 tests and 112 assertions: zero assertion failures, one error.
`seon.dev.edit-feedback-test/split-schema-edits-run-admission-before-publication`
exceeded its 30-second subprocess deadline, reproduced in isolated
confirmation. There is no evidence attributing that timeout to another
lane. Its retained root was `tmp/test-runs/run.jbjTxv` at completion.

The implementation gate was:

```text
bin/test --paths bin/seon-hook .claude/seon-hook.edn src/seon/cluster/source.clj src/seon/cluster.clj test/seon/cluster/boot_test.clj test/seon/dev/edit_feedback_test.clj -- seon.dev.edit-feedback-test seon.cluster.source-test seon.cluster.boot-test
```

It overlaid the owned paths onto HEAD
`38d2fc91bdf42d944ead9e8d3998ca33cabc1415` and exited **before test loading**:

```text
Cannot open <nil> as a Reader.
(slurp (:seon.dev-cache/test-classpath-file selection))
```

Verified cause: the executing, uncommitted `bin/test` consumes the new
`:seon.dev-cache/test-classpath-file` field, while the isolated HEAD
snapshot's `dev_cache.clj` predates the uncommitted producer change. The
cache result printed digest, source-digest, namespace count, status, and
path, but no test-classpath-file. Both foreign files were dirty when
inspected. Neither was edited by this assignment. This is already recorded
in [the shared gate issue](../../../seon/issues/bin-test-shared-base-compiles-other-lanes-half-edits.md).

The assignment required stopping at this boundary. No subsequent gate was
started until the owner explicitly resumed the assignment. No other lane
was messaged, resumed, or modified. Shared workers belonging to later
edit requests are not killed.

On resumption, the compatibility fix `f7aeaffbf` let the launcher consume
the older snapshot cache result. The same scoped gate was relaunched at
HEAD `e3efb12cc62af61b1e0f6081edb5ac46c641b198`, root
`tmp/test-runs/run.isvFwA`. Its snapshot took 3 seconds, dependency cache
and classpath preparation 58 seconds, and worker checkouts 2 seconds.
The new cohosted regression uses the now-required
`test-support/preserving-instrumentation-state`; its definition was still
an uncommitted foreign edit when that snapshot was taken. This dependency
was reported without copying or changing the foreign file.

That resumed gate exited 1 during shared-base analysis, before any test:
`test/seon/cluster/boot_test.clj:1037:8`,
`Unresolved var: test-support/preserving-instrumentation-state`.
The complete gate error was written to
`/var/folders/d6/78_m9wb92wg1f3qbt85s1r400000gn/T/clojure-4914760642410709072.edn`;
the exact diagnostic is preserved here because that system file is
disposable. The named helper is present in the dirty working-tree
`test/seon/test_support.clj` and absent in the gate's HEAD snapshot.
The assignment's stop rule fired again. No platform gate was started.
All owned shell sessions have returned.

## Live adoption and remaining work

Several new hook batches delivered explicit refusals to their callers.
Initially, both `default` and `beta` were registered in JVM PID 14049;
MCP confirmed the two names. The existing adoption guard requires the
development cluster to be the JVM's only running instance. `beta` was
left untouched.

A later publication reached analysis and refused the concurrent tree at
`src/seon/turn.clj:135:63`: `Unmatched bracket: unexpected )`. That file
was dirty and outside this assignment. It was not edited.

Remaining: review and run the draft regressions, obtain the scoped and
platform green gates, restart/adopt the new publication code under the
normal operator once the shared development environment permits it,
measure one editor and five editors touching five files within two seconds,
record actual publication fallback counts and reload counts, and verify
database/tree convergence plus browser responsiveness. Before/after
convergence timings are **not available**. Existing authority prose still
describes the old hook behavior and needs updating when this change is
verified and accepted.

## Files in this checkpoint

`bin/seon-hook`, `.claude/seon-hook.edn`, `src/seon/cluster/source.clj`,
`src/seon/cluster.clj` (publication helpers only),
`test/seon/cluster/boot_test.clj`, `test/seon/dev/edit_feedback_test.clj`,
and this note.

## Restart observations (2026-09-08, 14:09–14:20 local)

- Inherited default and beta both ran in PID 36758. Beta's actual branch commit
  was `6aa06bd9-6299-5049-b99d-7c7bbcf9008a`; its ordinary cluster entity had no
  `:seon.source/commit-id` adoption marker.
- The first one-editor hook returned refusal in **6.775 s**: the one-arity
  `seon.fn/index!` delegated to an armed overload with a nil callback. The source
  publication caller now supplies a real no-op progress callback.
- A subsequent publication reached development reconciliation and refused on a
  removed function retaining only identity, namespace ref, and admission
  provenance. `remaining-definition-facts` now excludes admission provenance;
  the source regression exercises actual SCI removal with a canonical database.
  This necessary boundary fix adds `src/seon/sci/eval.clj` to the owned changes.
- Another one-editor hook refused in **18.819 s** on an analyzer source-range
  exception while other edits were arriving.
- Five touches and hook invocations launched within **1.553 s**. Every editor
  received publication `77c5a115-0a69-4cbf-a748-8b1c8e197470` and its own path.
  Their response times were **29.266, 28.959, 28.644, 28.334, 28.023 s**.
  The batch refused on `test/seon/fn/analyzer_test.clj:57:56`, an unmatched
  closing parenthesis in the live tree. These are refusal-response timings,
  not edit-to-convergence measurements.
- The dedicated five-editor hook regression passed under the fast armed runner.
  The source/feedback fast run total was 21 tests, 140 assertions, 0 failures,
  1 error: canonical fixture analysis hit a source-range exception while the
  shared tree changed. The isolated gate is the proof for a stable snapshot.
- At the initial census cutoff there were only **29** `SOURCE_BATCH` records,
  all publication failures (11 used the older advisory spelling). The log did
  not retain incremental-versus-complete mode for those records. A last-50
  rebuild percentage therefore cannot be reconstructed honestly. New
  `SOURCE_PROGRESS` records retain publication mode, reasons, and reload lines.

## Latest restart evidence, 15:08

The completed scoped gate at `tmp/test-runs/run.HMevqN` ran 55 tests and
223 assertions: 15 failures, 20 errors, 519 seconds. The source-tombstone
regression completed in 3,986 ms without failure. Boot failures include the
existing `stop!` input contract refusing an already released connection;
subsequent starts encounter a released connection in `acquire-root-store!`.
The root cause of the latter retained holder has not been independently
proven. See `docs/seon/issues/stop-contract-rejects-stopped-instances.md`.
The refresh-only scope expansion for the stop contract is awaiting an owner
decision; no lifecycle code was changed.

A fresh HEAD-plus-owned-paths fast run passed
`incremental-source-refresh-preserves-agreement-across-real-edits` in
169,685 ms. It asserts one initial complete build followed by four
publications without another complete build, including a same-identity
metadata edit and missed-path discovery. The fast run was terminated during
the next boot test after a thread dump showed first-party test namespace
loading inside SCI acquisition, compiling configuration schema. It has no
suite verdict. The canonical scoped gate and platform gate are running.

The live default+beta probe retained the same HTTP server object. Beta's
program digest stayed
`4061e67f50c7a7a7d8991302fe36ce938651825d791370d35324d39be250920d`.
Its branch head changed from `6aa07534-883d-518c-9b00-f7dcae4bea50` to
`6aa07714-f8d8-5a43-a4b1-0a81f6499768`; maintenance and schedule datoms
advance an ordinary cluster independently of source adoption. Adoption
refused at SCI acquisition. All five page requests timed out, including the
one before adoption. This is neither convergence nor an availability pass.

The reusable live form is
`docs/prds/context-generation/research/hook-coalesce-live-probe-2026-09-08.clj`;
run it through the explicitly selected default JVM with beta running. The
real hook timing command is
`python3 test/seon/dev/hook_convergence_probe.py`. The latest one-editor
measurement was **216.064273 seconds to refusal**, not convergence, because
publication exited 124 while operator lifecycle work was queued. The five
editors are still awaiting their result. Earlier five-file measurement
launched within 1.552757 seconds and delivered one shared publication result
to all five hooks in 28.023–29.266 seconds, but that result was an analyzer
refusal. No successful before/after convergence comparison is claimed.

The hook log is capped at 1 MiB. At the 15:05 census only 13 terminal
`SOURCE_BATCH` lines remained, fewer than the requested 50. Historical lines
do not consistently record the incremental/full decision, so a historical
fallback percentage cannot be reconstructed. New `SOURCE_PROGRESS` records
include publication identity, paths, mode/reasons, and changed namespace reloads
without copying the full error envelope into the log.

The five-editor retry completed: touches spanned **1.526695 seconds**; all
five hooks named publication `e6e7bf7d-0260-4e1b-a935-cd90692990dd`, with
terminal latencies **6.526554, 6.206027, 5.904029, 5.603564, 5.300778 seconds**.
All refused before operator startup: `script/seon/dev/clj_kondo.clj:3`
requires `seon.id`, which the Babashka operator classpath cannot locate.
`bin/seon status` independently reproduced the same boundary. That owner
has concurrent edits and was preserved. Beta stopped successfully through
the prepl after lifecycle-lock waits through **485,995 ms**.

The census command is
`python3 test/seon/dev/hook_convergence_probe.py --census`; its result at
this checkpoint is 19 retained terminal publications, 0
converged, with modes {"unrecorded": 19}. Unrecorded is unknown,
not an incremental pass or a complete rebuild.

## Platform and operator checkpoint

`bin/test --paths bin/seon-hook .claude/seon-hook.edn src/seon/cluster.clj
src/seon/cluster/source.clj src/seon/sci/eval.clj
test/seon/cluster/boot_test.clj test/seon/cluster/source_test.clj
test/seon/dev/edit_feedback_test.clj --platform` passed: **81 tests,
447 assertions, zero failures and zero errors**, coordinator/test phase
239 seconds. The successful root was removed by the runner.

After the operator dependency change landed, `bin/seon` was clean and
still failed because its explicit Babashka classpath omitted `src`. The
launcher now adds `$SEON_SOURCE_ROOT/src` between `script` and `resources`,
so it resolves the existing `seon.id` owner. `bin/seon status` then succeeded
and confirmed beta stopped. This adds `bin/seon` to the file scope; its
separate gate is `bin/test --paths bin/seon -- seon.dev.edit-feedback-test`.

Default's old PID 36758 was stopped through `bin/seon stop default`. Its
prepl stop exceeded the operator's declared 30,000 ms silence bound, so the
operator sent SIGTERM to its exact recorded JVM identity. Only default
remained in that JVM. Restart is in progress through `bin/seon start default`.

## Final verification checkpoint

Commit `81575eb5c` restores the operator classpath. Its path-limited gate
passed **9 tests, 62 assertions, zero failures and zero errors**.
The refresh gate at `tmp/test-runs/run.aMBCnw` completed in **791 seconds**
(coordinator/test phase): **55 tests, 223 assertions, 15 failures, 20 errors**.
The real-edit incremental test passed isolated confirmation after failing
in the contaminated pooled worker; the cohosted adoption test still failed
confirmation. The attributed original cohosted error was a released
connection at `cluster/acquire-root-store!:799`, before its first publication.
No green refresh gate is claimed.

Default restarted as PID 45036. The fresh default+beta live form completed
in **91,415 ms** and refused before any adoption progress stage: static
analysis reported an unresolved `transcript` namespace at
`test/seon/render_source_test.clj:317:33`. That file had concurrent edits.
Beta's positive program digest remained the exact same value recorded above.
The page timed out before publication and refused its old URL afterward;
the registry subsequently reported default at port 7994 instead of 58444.
The server object comparison was false. This observation happened without
entering development adoption, so it cannot establish a stop on that path.
A subsequent request to the currently registered URL timed out after
**15.003895 seconds**. HTTP availability remains unproven.

Source changes in this checkpoint: pass a callable progress observer to
manifest reconciliation under armed contracts; exclude namespaces without
classpath source from `require :reload`; permit tombstone provenance while
removing a committed definition from real SCI; log publication modes with
publication identities; bound each hook result wait by both admitted queue
and publication phases; use the now-landed instrumentation fixture; and
retain exact errors in reproducible live/timing probes.

Files beyond the original owned list are `src/seon/sci/eval.clj` (the
adoption tombstone refusal), `bin/seon` (the launcher dependency),
`test/seon/dev/hook_convergence_probe.py`, the adjacent live-probe Clojure
file, and the two issue notes named in this document. No foreign session
was operated and no protected edit was changed.

Unfinished: the stopped-instance input-contract decision outside the held
refresh-only region; a green three-namespace refresh gate; successful
cohosted default convergence; a 200 page response during adoption; and
successful before/after one/five-editor convergence measurements. The log
cannot recover unrecorded historical fallback decisions. Shared compiled
JVM Vars are not sovereign per-cluster behavior; beta's unchanged program
facts do not prove that stronger guarantee.
