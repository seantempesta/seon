---
type: research
status: blocked
date: 2026-09-08
tags: [research, test, runner]
---

# Runner paths and re-arm verdicts

Read `AGENTS.md` end to end, including its lane paragraph and §6; read
`docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md`
end to end, including §§2, 4a, 10 and 12. Read both assigned issues and
`custody-isolation-landing-2026-09-08.md` §4. Applied the data-oriented Clojure,
Clojure testing and REPL skills. No protected file was edited.

## Dependency and ownership evidence

- Git's archive of the captured HEAD supplies the selected snapshot; literal
  file copies overlay working bytes, and absence removes a tracked file.
  `bin/test` already owns checkout population, dependency preparation and
  isolated worker directories; these remain its owners.
- `src/seon/test/runner.clj` owns the line protocol, exchange id, process
  `onExit`, completion bound and terminal tally. The re-arm uses its existing
  `arming-decision`, `arm-contracts!` and `reassert-contracts!`; the initial
  caps were already carried at assignment start. History inspected with
  `git log -- bin/test src/seon/test/runner.clj`.
- The regression uses the existing subprocess exchange harness in
  `test/seon/test_runner_test.clj`, including exact process exit and cleanup.
  The launcher regression invokes the real `bin/test`, real Git and actual
  copied worker directories; its fake compiler checks the filesystem inputs
  at each compiler invocation, without compiling an unrelated program.

## Changes

`bin/test --paths FILE... -- NAMESPACE...` captures HEAD and overlays only
those files. A tier flag also ends the file list. Tracked deletions and new
files are supported. Absolute paths, traversal, directories, submodule paths
and symlink parents are refused. The dependency preparation and every worker
consume the coordinator's snapshot. Every invocation prints actual snapshot
file differences from its captured HEAD, including additions and deletions.

Workers publish correlated re-arm start/end events through the existing
protocol. The reader drains ordered protocol evidence before reporting exit,
so an immediately dying process cannot race away the evidence naming re-arm.
An exit during re-arm becomes `:seon.test.runner/re-arm-failed`, retaining
worker id, exit code, task identity and log. Existing tally classification
keeps it under worker exchange failures, never `parallel-only`.

## Proofs so far

- Standalone invocation of the launcher regression: exit 0, printed exactly
  `D src/deleted.txt`, `M src/owned.txt`, `A src/added.txt`; printed
  `SNAPSHOT_VERIFIED`. The foreign edit remained at HEAD at dependency
  preparation and in every worker. Successful isolated root removed.
- First namespace run, `run.5x7SDl`: 33 tests, 209 assertions, 2 failures,
  0 errors. Both were this slice's cache-recording regression: the chosen
  digest was not appended and the old test expected a failed pre-compilation
  invocation to have no snapshot. Both corrected before the next run.
- Default MCP JVM probe: `(+ 1 1)` returned 2. Initial advertisement absence
  coincided with another operator refork; it subsequently answered without
  this lane changing cluster lifecycle.
- Default JVM, hot-reloaded runner: the ordered protocol reader returned
  `{:seon.test.runner/phase :re-arming,
    :seon.test.runner/terminal {:seon.test.runner/exchange-terminal :exit,
                                :seon.test.runner/worker-exit 17}}` in 1 ms.
  This is a reader proof; subprocess death is the recurring regression.
- Development adoption completed once at commit
  `6aa04b6a-f03a-5a01-9aee-21dd445735ec`. Later publication reported source
  changes during adoption; no later convergence is asserted by that result.
- Default web endpoint `http://127.0.0.1:7994` returned HTTP 200. No browser
  paint change is asserted for this runner-only change.
- `bash -n bin/test` and path-limited `git diff --check` passed.

## Follow-up verification and commits

- `cd42689b2`: selected snapshots and typed re-arm exits.
- `9cfa856d3`: use the same HEAD-plus-overlays snapshot for default mode,
  guard empty Bash arrays, and stop on file-list generation failure.
- Corrected namespace gate `run.4MDC19`: **35 tests / 223 assertions /
  0 failures / 0 errors**, exit 0; successful root removed. Both new
  regressions ran under the ordinary armed worker harness.
- Before that green, `run.4ihBGX` was **35 / 223 / 2 / 0**: the injected
  Python process tried to parse a namespaced EDN command as an expanded map
  and exited 1. The fixture now emits its known correlated event and exits
  17. Direct subprocess check printed exactly:
  `SEON_TEST_WORKER_EDN {:seon.test.runner/worker-event :re-arming :seon.test.runner/worker-id "re-arm-death" :seon.test.runner/exchange-id "re-arm-death"}`.
- The first platform launch exposed empty-array expansion under Bash 3 and
  artificial diffs from the old default snapshot's symlinks. It was stopped
  through its own launcher (pid 11841, exit 143); its output is not a test
  verdict. The second commit removes both causes. The launcher regression
  now also checks default mode. Standalone execution returned exit 0 and
  both `SNAPSHOT_VERIFIED` and `DEFAULT_SNAPSHOT_VERIFIED`; the default diff
  contained only the same owned changes plus `M src/foreign.txt`.
- Latest development adoption converged at
  `6aa04f2d-55d7-5cb0-8f09-cf9a2fd2e7c1`, digest
  `417e207443e4d79a65cadc184cc267f7fa93e2633a90e62a4c0111118fceb1f9`.
- The shared Git index lock initially refused the first commit attempt. It
  disappeared before the successful retry; this lane did not remove it.

## Final gate boundary

`bin/test --platform` after `9cfa856d3`: **73 tests / 398 assertions /
0 failures / 0 errors**, exit 0. Its root `run.gqkw7b` was removed.

Bare `bin/test`, snapshot HEAD `f1ebd475400322b89bc3424e295d20f338a6175c`,
exit 1 **before tests** at shared published base preparation. Its printed
snapshot diff explicitly included the foreign working edit `src/my/plan.clj`.
`seon.fn/assert-clean-analysis!` refused with `:seon.fn/index-refused`:

| Snapshot file | line:column | blocking analysis |
|---|---|---|
| `test/my/plan_test.clj` | 73:18 | `my.plan/render-plan-html is called with 2 args but expects 1` |
| `test/my/plan_test.clj` | 196:18 | same |
| `test/my/plan_test.clj` | 230:18 | same |
| `test/my/plan_test.clj` | 440:29 | same |

The corresponding snapshot definition is `src/my/plan.clj:1165–1168`:
`render-plan-html`, contract `[:=> [:cat :my.plan/component-view]
:seon.render/hiccup]`, argument vector `[view]`. This is the exact boundary,
not a runner test failure. No test tally exists for this bare invocation.
The runner retained `tmp/test-runs/run.inT6IZ` with that snapshot and its
failed preparation evidence.

**Stopped here under the assignment's concurrency rule.** No foreign lane
was messaged, resumed or edited. The bare gate must be rerun after the plan
renderer and its callers agree. The namespace gate after the last default-mode
follow-up remains unrun: the earlier complete namespace gate is green, the
extended default/selected launcher regression passed standalone, and the
final platform gate is green. This is not a claim that the whole required
gate passed.

Latest default development adoption after the follow-up converged at
`6aa04f2d-55d7-5cb0-8f09-cf9a2fd2e7c1`, digest
`417e207443e4d79a65cadc184cc267f7fa93e2633a90e62a4c0111118fceb1f9`.

## Files and cleanup

Code: `bin/test`, `src/seon/test/runner.clj`,
`test/seon/test_runner_test.clj`. Documentation: the two assigned issues
and this landing note. Both assigned defects are implemented and marked
resolved with their bounded proof and this explicit broader-gate limitation.
No protected hunk is required.

All launched shell sessions finished; the deliberately stopped invalid
platform launcher exited 143 after reaping its child. Successful test roots
were removed by the runner. The final failed bare root is retained as new,
unresolved cross-lane evidence under the repository's test-root retention
rule. Earlier corrected failed roots and standalone probe checkouts are
removed after confirming no live process holds them.
