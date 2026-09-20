---
type: issue
status: resolved
severity: blocker
created: 2026-09-20
tags: [issue, test, gate, bounded-execution, publication]
---

# The named fast runner suite can launch cold gates and exceed a lane's JVM budget

The publication assignment requires `seon.test-runner-test` in its fast pass
while prohibiting cold gates and overlapping lane JVMs. These requirements
conflict with `concurrent-bin-test-invocations-both-reach-their-tallies`
(`test/seon/test_runner_test.clj:2086`): it launches two actual `bin/test`
processes and calls `as-orchestrator` on their environments. Its long-test
metadata supplies a time bound, but does not enforce the lane's resource or
validation boundary. Explicit namespace selection includes this test.

The publication lane's HEAD-plus-owned-paths run `run.O81Ldt` reached it at
2026-09-20T05:23:29Z, after source verification. Inspection found parent JVM
15851 and nested JVM 32918, plus two child gate launchers. Upon recognizing
the boundary, the lane terminated only this process tree, deepest children
first. The enclosing fast invocation exited 143 and removed its snapshot;
all enumerated PIDs were confirmed absent. No other lane was operated.
The lane did not intentionally request a cold gate, but one nested gate JVM
was running: this run cannot be described as remaining within one JVM.
Raw parent log: `tmp/publication-dissolution/owned-regression-final-fast.log`.

The prior [silence-bound issue](a-test-that-drives-two-real-gates-reports-no-progress-to-the-silence-bound.md)
is resolved; this is a resource/admission boundary, not a request to increase
its bound. The remaining runner tests and all function suites were unexecuted
when this invocation was stopped. Function suites continue separately through
the prescribed fast entry point. The nested-gate proof belongs to the orchestrator.

Recommended direction: separate the real child-gate fixtures into an explicitly
selected integration namespace, retaining their recurring platform coverage.
This keeps ordinary lane fast selection within one JVM without a new policy
schema or silently skipping an obligation. Changing validation selection is
an orchestrator decision; this lane has not changed runner policy or fixtures.


## Followup: existing metadata cannot express the requested isolation

At HEAD `8eec50b46`, the accepted namespace-split direction encounters a
validation-policy boundary before a safe metadata-only move:

- `src/seon/test/fast.clj:45` explicitly supplies `:seon.test/include-long? true`
  and `:named`; lines 48–51 enumerate every test in the requested namespaces.
  Moving a test protects the remaining runner namespace, but `:long` does not
  mechanically prevent a fast request naming the integration namespace.
- `src/seon/test/runner.clj:952–957` skips long tests before partitioning
  platform tests. A non-long platform test participates in bare cold gates;
  a long platform test is skipped by the ordinary platform request. The
  canonical selector likewise applies the long eligibility test at
  `src/seon/test.clj:1026–1027`, before platform reasons at line 1049.
- There is no existing orchestrator-only test declaration in the canonical
  `resources/seon/schemas/seon.test.edn` registry or the selector/runner.
- `concurrent-bin-test-invocations-both-reach-their-tallies` calls
  `populate-published-operator-root!` (`test/seon/test_runner_test.clj:2113`).
  That owner declares `:seon.fn/destroys` at `test/seon/test_support.clj:129`.
  `verify-platform-tier-carries-no-destructive-drill!`
  (`src/seon/test/runner.clj:1091–1126`) refuses that call path in platform.
  This is the existing store-wipe regression guarantee, not a hypothetical
  concern. The resolved store-wipe issue was read end to end.

The earlier recommendation to preserve platform coverage by namespace metadata
was incomplete: it did not account for these eligibility and destructive-owner
rules. No fixture was moved, no guard weakened, no test executed, and no cold
child process launched in this followup. Changing this policy needs a ruling;
the assignment explicitly permits stopping at a validation boundary.

Three options (engineering estimates, excluding the orchestrator's cold proof):

1. **Recommended smallest change: separate a long integration namespace and
   give the orchestrator an explicit recurring named cold invocation.**
   30–60 minutes for fixture separation and local selection verification.
   Preserves the destructive-platform guard and safe ordinary runner fast
   selection. Gives up coverage inside `--platform` and a mechanical refusal
   of an intentionally named fast integration invocation.
2. **Enforce orchestrator-only eligibility as a new declared test fact in the
   shared selector, keeping integration outside platform.** 1–3 hours for
   schema/indexer/selection/launcher integration and regression. Explicit fast
   requests refuse; bare requests exclude it; recurring orchestrator integration
   retains coverage. Gives up the no-new-policy shape and platform membership.
3. **Keep the requested explicit platform coverage by introducing a separately
   admitted integration phase and orchestrator-only declaration.** 3–6 hours
   for shared selection, root/resource admission, and cold verification. The
   existing ordinary platform guard stays intact; the new phase needs an
   explicit isolated-root guarantee for destructive child fixtures. Gives up
   the current single platform-phase semantics and broadens this lane into
   test execution architecture.

`src/seon/cluster.clj` remains dirty/held. The held function fixture is untouched.
Items 2–4 have not advanced during this read-only selection audit.


## Resolution — accepted invocation convention

The orchestrator ruled a separate long integration namespace, explicit cold
invocation only, with coverage outside platform for now. The 24 subprocess
fixtures now live in `test/seon/test_runner_integration_test.clj`; remaining
runner tests do not invoke those helpers. A positive local regression loads
the moved tests and verifies the existing selector skips all of them without
long admission. Fast run `658ac7335177` passes that regression; its unrelated
4 failures/2 errors remain named in the publication landing note. No nested
JVM or cold gate was launched by this run. The new namespace's real execution
is owed to the orchestrator under the exact command in that note.
