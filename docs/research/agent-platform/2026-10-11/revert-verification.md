---
type: research
title: Fence revert verification
date: 2026-10-11
---

# Fence revert verification, 2026-10-11

Lane revert-verify, for root (fable-replan §8 Q15). Question: does the batched fence revert c53c9306e lose anything
the code did before it? Method: the compiled full tier on the commit before (fee847013, whose `src` is identical to
8766caefc) and on c53c9306e, compared test by test.

## Procedure

- Each tier ran on its own isolated root, booted by `bin/seon --root tmp/test-runs/revert-{baseline,after}-* nuke --force`
  from the committed archive (never the working tree, never default).
- Root on the cluster's own execution (the compiled evaluator), `seon.test/run` with `:named` batches of 25,
  `:seon.test/rerun? true`, declared-long tests included, `:seon.test/check-time-limit-ms` 240 000: the request
  `script/seon/interpreted_census.clj` makes with AGENT `root`. Tests still unrun when a batch hits its limit are
  `:unknown`.
- When a batch refused for memory pressure, the root's JVM was relaunched on the same archive and the batch retried.
  Fixture material (`seon.test-resolution-fixture`, `seon.test-runner-failure-fixture`, 6 symbols) is refused by
  `seon.test/run` and is `:excluded` in both runs.
- Per-test status, run id and failure text: [revert-baseline.edn](revert-baseline.edn) and
  [revert-after.edn](revert-after.edn).

## Counts

| | fee847013 (before) | c53c9306e (after) |
|---|---|---|
| tests | 2,788 | 2,776 |
| green | 2,398 | 2,365 |
| red | 225 | 232 |
| error | 109 | 106 |
| unknown (unrun at the batch limit) | 50 | 67 |
| excluded (fixture material) | 6 | 6 |
| wall time | about 64 min (34 + 78 + 1 batches) | 61.6 min (111 batches) |
| batches at the 240 s limit | 6 | 7 |
| memory-pressure relaunches | 1 (after about 850 tests) | 1 (after about 2,600 tests) |
| peak old generation in a batch | 2,720 MB at the refusal | 3,104 MB |

The test sets differ only as the revert says: 12 tests deleted and
`seon.turn-continue-test/an-end-delivered-before-a-crash-is-not-sent-again` renamed to
`a-task-end-is-its-message-and-the-agents-close-in-one-transaction`.

Transitions (before → after): green → green 2,358; red → red 216; error → error 103; unknown → unknown 48;
green → missing 13, green → red 13, green → unknown 13, green → error 1; red → green 4, error → green 2,
missing → green 1.

## Losses: 40 tests green before and not green after

| Class | Count | Tests |
|---|---|---|
| (a) deleted with the reverted machinery (in the commit message) | 13 | merge-test: a-callee-changed-on-the-target-after-prepare-refuses-acceptance, a-path-changed-after-write-back-refuses-the-commit, a-landing-write-back-waits-for-the-lane-commit-lock, a-landing-stopped-after-acceptance-resumes-to-one-git-commit, two-concurrent-resumes-of-a-stopped-landing-make-one-commit-and-no-refusal, a-landing-into-a-file-with-uncommitted-edits-waits-for-their-commit, a-second-open-proposal-for-one-assignment-is-refused-at-the-writer; turn-continue-test: a-landing-stopped-after-acceptance-is-resumed-by-the-next-end, a-permanently-refused-landing-is-not-retried-and-blocks-its-file-by-name, a-landing-that-throws-records-a-typed-refusal-on-its-merge-task, an-end-delivered-before-a-crash-is-not-sent-again (renamed, green after); evaluator-evidence-test: an-interpreted-green-never-verifies-for-root, a-compiled-green-verifies-for-an-interpreted-agent |
| (b) kept as red proofs for root fixes (in the commit message) | 3 | seon.cluster.merge-test/a-test-never-run-on-the-target-is-not-blamed-on-the-merge, seon.turn-continue-test/a-green-proof-with-reached-tests-only-unverified-or-red-on-main-ends-done, seon.cluster.merge-test/a-require-in-the-agents-user-namespace-lands-with-its-change |
| load and order in the bulk run: each green alone on c53c9306e | 22 | 9 red in the bulk run: seon.sci.branch-execution-test (4: interpreted-callers-resolve-unchanged-names-to-compiled-vars, a-docstring-only-change-keeps-the-compiled-var, an-unchanged-host-bound-caller-of-a-changed-definition-runs-as-compiled, a-branch-forked-before-a-platform-change-binds-the-loaded-compiled-var), seon.sci.eval-test (3: database-provenance-regenerates-the-base-and-reverts-for-retained-forks, a-branch-change-leaves-its-compiled-callers-compiled, an-instrumented-multi-arity-miss-reads-like-clojure), seon.test.evaluator-evidence-test/a-compiled-green-is-not-reused-by-an-interpreted-request, seon.test.interpreted-fixture-test/a-checked-test-runs-inside-its-namespace-fixture; 13 unrun at a batch limit: supplied-documentation-test (4), publication-serialize-test (3), source-test (2), publication-report-test, publication-test, registry-test, status-test |
| (c) real regressions: red alone on c53c9306e, green alone on fee847013 | 2 | below |

The 22 were rerun one per request on a freshly launched c53c9306e JVM: all green. Their bulk failures are
`identical?` checks of compiled Var roots against SCI (instrumentation state left by earlier members) and batches
cut at 240 s; the before run had its own such tests, which is why 4 red → green and 2 error → green appear the other way.

### (c) Regressions

1. `seon.context-test/the-outcome-recorded-at-the-end-equals-the-one-the-branch-shows`: error. `seon.turn/land-end!`
   acquires root's world with `{:seon.agent/branch nil}` when root's agent row holds no branch (the
   `seon.test-support/with-running-agent` world), `seon.cluster.agent/acquire-context!` refuses its input contract, and
   the throw escapes `seon.turn/end-task!`. 9d4884373's catch in `land-end!` (reverted) turned this into a typed
   refusal on the merge task, so the test passed. Green alone at fee847013 (run 58b6455e2f9d), error alone at
   c53c9306e. Not fixed here: the fix decides what root's world is without a branch row, and with
   `:seon.config.root/land?` true in `config/default.edn` (a9a0c23d1, enabled because the landing was fenced) a green
   end in a test world would then reach `seon.cluster.source/land!` into `:seon.config.fs/working-root`. Root should
   decide the dial now that the fences are gone. Task `seon.turn/a-landing-acquires-roots-world-when-root-has-no-branch-row`,
   the test tagged as its proof.
2. `seon.render.web-context-test/unrelated-adoption-preserves-pages-with-an-unchanged-sci-snapshot`: red, 10 pass 1 fail.
   After the test writes `:seon.source/commit-id` on its cluster row, the re-rendered /agent/root page no longer shows
   the commit, though the status renderer is re-invoked. Green alone at fee847013 (run 13b5fa3bb400), red alone at
   c53c9306e twice. The cause is not found: none of the reverted namespaces is named by `seon.cluster.status/render-html`.
   Task `seon.render.web/the-root-page-shows-a-newly-adopted-commit-after-the-fence-revert`, the test tagged as its proof.

## Red → green

`seon.turn-work-test/situation-totality-property`, `seon.test-support-test/a-canonical-database-is-the-production-source-population`,
`seon.config-test/config-loads-the-packaged-population-in-a-fresh-jvm`,
`seon.test.interrupted-request-test/latest-results-reads-one-batch-whatever-the-runs-it-spans` (red before);
`seon.test.interrupted-request-test/a-huge-assertion-message-is-a-blob-and-readiness-reads-only-counts`,
`seon.test.interrupted-request-test/a-thrown-request-records-its-unfinished-members-as-failed-obligations` (error
before); the renamed `a-task-end-is-its-message-and-the-agents-close-in-one-transaction` (new). These were not rerun
alone, so load in the before run may explain some.

## Default after the revert

- `bin/seon start --head` onto c53c9306e: 32.3 s total, ready 16.7 s, adopted.
- Census before reset: 59 roster branches (56 agent branches), 116 agents, 714 tasks, 9 merge tasks, 29 test runs.
- `bin/seon reset default --force`: 1.3 s. Census after: 3 roster branches (`db`, `current-src`, `cluster-default`),
  0 agent branches, 1 agent (root), 705 tasks (from the task files), 0 merge tasks, 0 work tasks, 0 test runs.
  Reset alone gave a clean database; nuke was not needed.

## Findings

- Orphaned drill JVMs, reproduced: each tier left two `seon.cluster.boot-test` drill JVMs (roots
  `data/source/<sha>/tmp/b1b-*` under the isolated root) running with parent pid 1 after their member ran past the
  240 s batch limit and the launching JVM was replaced; this lane terminated them by verified root path. Task
  `seon.operator/a-test-launched-jvm-exits-when-its-launcher-exits` (also the nine root found on 2026-10-11).
- The full tier cannot finish on one JVM: memory-pressure refusal after about 850 tests (before) and 2,600 (after);
  after two full GCs the old generation held 2.35 GB with `seon.cache` at 511 MB, 7.4 M Datahike datoms resident, and a
  boot-failure member's cluster (`arming-window`) still registered. Task
  `seon.test/a-full-tier-on-one-jvm-retains-what-its-members-built`.
- `script/seon/interpreted_census.clj` records nothing for a batch whose `seon.test/run` refuses (it reads `[]` results
  as an answer), so its tier files can silently miss tests; the first baseline pass lost 78 batches this way before
  the refusal was noticed. The rerun used a driver that records refusals and relaunches.

Limits: one bulk run per commit; the 22 load-sensitive tests were checked alone only on c53c9306e, the two regressions
alone on both commits. Bulk failures in both runs (216 red → red, 103 error → error) are not classified here.
