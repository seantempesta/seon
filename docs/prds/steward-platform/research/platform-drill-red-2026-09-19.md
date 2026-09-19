---
type: research
status: complete
created: 2026-09-19
tags: [testing, platform-tier, destructive]
---

# Platform cleanup drills: declaration drift

Read AGENTS.md sections 0–5 and every named grounding note end to end:
[a-platform-tier-test-wiped-the-checkouts-store](../../../seon/issues/a-platform-tier-test-wiped-the-checkouts-store.md)
(status resolved), [platform-tier-no-destructive-drill](platform-tier-no-destructive-drill-2026-09-17.md),
[platform-destroyer-file-boundaries](platform-destroyer-file-boundaries-2026-09-17.md),
[destructive-tests-derived](destructive-tests-derived-2026-09-17.md), and
[A0's landing](unbreak-bare-test-2026-09-19.md). Read the checker, platform-declarations,
both complete tests, the fixture cleanup owners and the test-system PRD.
Applied data-oriented-clojure, clojure-testing and repl skills.

## Verified cause and decision

Option (a): these are real destructive drills. Both invoke
`seon.operator.state/cleanup-root-under-lock!`, whose own declaration says it
removes managed data it did not create (`src/seon/operator/state.clj:1362`).
The syntax test's final child form deliberately removes its sentinel
(`test/seon/dev/fresh_operator_reset_test.clj:314`); the symlink test directly
removes its store (`:532`). This is not merely ordinary fixture cleanup.
`seon.test-support/delete-recursively!` (`test/seon/test_support.clj:850`)
restricts cleanup to descendants of project-local tmp and carries no destroyer
declaration; it is not the reached owner.

`git log -S 'cleanup-root-under-lock!' -- test/seon/dev/fresh_operator_reset_test.clj`
identifies `c4d1be3ac`: both paths existed when the file was introduced.
The owner declaration also came with that commit. `git log -S ':seon.test/platform'`
on the same file identifies **8e952be28**, which subsequently added the
namespace-wide platform marker. Neither path is new since **d65cc688c**;
that commit is the file-boundary fix, not the reset landing. `git log -S
'platform-declarations' -- src/seon/test/runner.clj` identifies `8d4b3689f`,
the indexed-tier partition. No resolution widening is needed to explain this red.

One read-only MCP JVM probe on default pid 41822 called the existing
`destructive-owner-rows`, `tests-reaching-rows` and `destructive-call-path`
against retained manifest
`target/test-published-bases/170c6f9ffe2db4d571c9a67e454ac393a8d5e5046341475f57ffd3e56b2a9cb9/base/manifest.edn`.
In **869 ms**, both tests returned reached=true and exactly the two-symbol
path `[test seon.operator.state/cleanup-root-under-lock!]`; both rows carried
the namespace platform reason and long reason. The complete unelided envelope
was inspected. Runtime status answered alive; its existing problem counts were
1 error signature and 29 errored evaluations. No runtime mutation or lifecycle
command was performed by this lane.

The file-boundary research concerns unresolved references widening to tests
in the wrong source artifact. It does not declare a scratch-root exemption.
A scratch root is required custody for these drills, not permission to run a
declared destroyer in the first tier. Keep the incident guarantee intact:
no destroyer exemption, no owner removal, no checker change. The existing
owner admits all paths and records deletion evidence before deleting
(`src/seon/operator/state.clj:1372–1389`).

Remove the namespace platform marker and place nonblank reasons on its nine
other tests. The two drills retain the existing long marker and 600000 ms
allowance. No test body or assertion is removed. This follows the earlier
per-test marker move in the store-wipe fix and the test-system PRD's marker
with a reason.

The read-only probe is reproducible in JVM mode (no test execution):

```clojure
(let [manifest (clojure.edn/read-string
                (slurp "target/test-published-bases/170c6f9ffe2db4d571c9a67e454ac393a8d5e5046341475f57ffd3e56b2a9cb9/base/manifest.edn"))
      artifacts (:seon.fn.manifest/artifacts manifest)
      rows (vec (mapcat :seon.fn.file/rows artifacts))
      owners (#'seon.test.runner/destructive-owner-rows rows)
      symbols (set (map :seon.fn/sym owners))
      reached (#'seon.test.runner/tests-reaching-rows artifacts (set owners))]
  (mapv (fn [test-symbol]
          {:test test-symbol
           :reached (boolean (reached test-symbol))
           :path (#'seon.test.runner/destructive-call-path rows symbols test-symbol)
           :markers (select-keys
                     (first (filter #(= test-symbol (:seon.test/sym %)) rows))
                     [:seon.test/platform :seon.test/long])})
        '[seon.dev.fresh-operator-reset-test/managed-root-cleanup-loads-no-program-and-never-follows-symlinks
          seon.dev.fresh-operator-reset-test/source-syntax-refuses-before-lock-or-destruction]))
```

## Regression and dependency ledger

Extend ONE existing canonical-fixture regression,
`the-canonical-platform-tier-preserves-file-local-uncertainty` in
`test/seon/test/runner_test.clj`. It still proves the nonempty platform set
passes and unrelated registry uncertainty remains local. Four additional
assertions prove the two drills lack platform declarations and that forcibly
submitting each to the checker still refuses with its exact destroyer path.
No synthetic schema or mocked program graph is introduced.

- `src/seon/test/runner.clj:804`: indexed platform declarations.
- `src/seon/test/runner.clj:1033`: intact artifacts passed to reach selection;
  `:1069` shortest call/reference path; `:1091` pre-dispatch refusal.
- `src/seon/test/selection.clj:60`: symbol edges; `:76` reverse selection,
  retaining actual file uncertainty. This held owner is read only.
- `test/seon/test_support.clj`: canonical `with-database` and source manifest.
- `reference-code/clojure/src/clj/clojure/test.clj:710`: ordinary test-var
  execution/reporting; `:725` fixture application. No dependency changes.

## Verification and integration boundary

Requested command, run from isolated HEAD worktree `tmp/platform-drill-wt`
at `0aee224c5` with only these two source-file changes:

```sh
bin/test-fast --paths test/seon/dev/fresh_operator_reset_test.clj test/seon/test/runner_test.clj -- seon.test.runner-test seon.dev.fresh-operator-reset-test
```

The existing dependencies and published-base cache were linked; no baseline
was prepared. The launcher selected graph
`9d9c2c84fec9b8aef8de7c475fa2467df95adf510e81f56222281da730e5b61b`,
0 commits behind that HEAD, and armed **1231** functions. The shared attempt
was stopped by this lane (exit 143) during initialization because its selected
runner-test file included foreign admission hunks. The isolated run excluded
them and ran to completion, exit **1**: **35 tests, 277 assertions, 1 failure,
4 errors**. Raw output: `tmp/platform-drill-isolated.log`.

The canonical checker regression passed between **18:06:32.298Z and
18:06:35.053Z**, printing **114 tests admitted**. Its four new negative
assertions passed. The runner namespace completed without outer failures;
its failure-fixture output is deliberate nested reporter coverage.
The managed-root symlink cleanup drill also executed and passed at
18:12:36Z. Thus the assigned platform declaration red is corrected, but
this is not a green full reset-namespace tally.

The fast loop did not skip long tests. Three errors came from the existing
preflight fixture passing an absolute Git common directory as an `io/file`
child; filed as
[preflight source fixture rejects an absolute Git common directory](../../../seon/issues/preflight-source-fixture-rejects-absolute-git-common-directory.md).
The boot test's one failure and one error were readiness closing in recovery,
then the declared 300000 ms lifecycle-holder bound; cause not established,
filed as
[isolated reset boot test closes readiness during recovery](../../../seon/issues/isolated-reset-boot-test-closes-readiness-during-recovery.md).
These are observed on HEAD plus this slice, not attributed to another lane.

The boot child's successful publication result log also carried a very large
unresolved-call report (40416 entries); inspection of its raw `SEON-INIT-RESULT`
line produced unreadable tool output. The terminal operator summary itself
was concise. No new clipping seam was added. This output observation is
recorded here under the standing ugly-output order.

The test fixture stopped child pid 85322, the fast JVM exited, and its
snapshot was removed by the launcher. The owned worktree and scratch thread
dump were removed before reporting. `git diff --check` passed. No default
reload/adoption or browser proof is claimed; this metadata change is verified
through the canonical fixture manifest in the isolated fast JVM.

Files touched: `test/seon/dev/fresh_operator_reset_test.clj`, only the named
regression in `test/seon/test/runner_test.clj`, this note, and the two issue notes linked above. All production
files and inherited edits remain untouched. No cold gate was run.

Orchestrator owes:

```sh
bin/test --paths test/seon/dev/fresh_operator_reset_test.clj test/seon/test/runner_test.clj -- seon.test.runner-test
bin/test --platform
```

The orchestrator also owns resolving and rerunning the recorded reset-namespace failures; the fast runner executed the long tests in this run.
