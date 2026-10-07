# lane-tool: bin/lane commit defects (2026-10-07)

`bin/lane commit` = staged-index check → `bin/seon check PATHS` (operator `check!`: candidate archive,
`seon.cluster/rehearse-source!` publication + adoption on overlay branches of default, optional throwaway-JVM
tests) → `clj-kondo --lint src script test` over the working tree → `git add` of new files →
`git commit --only -m MSG -- PATHS`.

## 1. Crossed commits (649829332)

- Observed: 649829332 (11:44:22) carries lane commit-validation's five paths (src/seon/db.clj,
  src/seon/schema/datahike.clj, test/seon/db_overhead_test.clj, test/seon/db_test.clj,
  test/seon/schema/vector_storage_test.clj) under lane heap-retained's message ("Branch writers and Flow graphs
  start from root bindings, never an evaluation's frame"). heap-retained's own commit did not land then; it
  re-ran `bin/lane commit heap-retained` at 11:45:43 (its files are still uncommitted at 433214bc5).
  commit-validation recorded its intended message in its notes (433214bc5). Affected lanes:
  commit-validation (wrong message on its code), heap-retained (commit attempt lost, retried).
- Mechanism: `git commit` passes the message through the shared `.git/COMMIT_EDITMSG` (written, then read back)
  and the real index lock does not cover the whole commit: a scratch repo with two loops of concurrent
  `git commit --only -m` (git 2.54.0) produced `cannot lock ref 'HEAD': is at X but expected Y` failures, i.e. both
  processes reached the ref update after writing objects, and once left the repository with a reflog entry naming
  a missing commit (`git fsck`: invalid reflog entry, broken link). A process that loses the HEAD race after the
  winner read COMMIT_EDITMSG back exits non-zero (heap-retained's refused attempt) while its message rides on the
  winner's commit. The crossed message was not reproduced in the scratch loops; the mechanism is inferred from the
  shared file plus the observed concurrent-commit races.
- Fix: the commit section (staged-index re-check, new-file `git add`, `git commit --only --cleanup=whitespace -F`,
  verification) runs under `perl flock` on `.git/lane-commit.lock`; the long check and lint run outside it. After
  the commit, bin/lane asserts parent = the HEAD it started from, committed paths ⊆ requested, every requested
  path equal to the commit, and message = request (`git stripspace`); otherwise `LANE COMMIT MISMATCH` (exit 3),
  history untouched. A failed commit un-stages the new files it added.
- Proof: scratch repo, three lanes × 15 concurrent `bin/lane commit`s: 45/45 commits, 0 failures, 0 mismatches;
  a commit-msg hook rewriting the message is reported as a mismatch (exit 3); a new file and a deletion commit
  through the lock.

## 2. Lint of the working tree

- Before: `clj-kondo --lint src script test --cache false` over the shared working tree, 17.0 s, after the check;
  any lane's half-finished edit refused every other lane (131 transient errors refused a docs-only commit).
- After: the candidate tree (HEAD + exactly the commit's paths, `git read-tree HEAD` + `git add -A -- PATHS` +
  `write-tree` in a temporary index, as `seon.operator/candidate-revision!` builds it) is `git archive`d (src,
  script, test, .clj-kondo) into a temporary directory; the analyzer's ignored configuration (.clj-kondo/imports/*,
  inline-configs/*, clj_kondo) is symlinked from the checkout; no worktree. `--parallel`, running concurrently with
  the check. HEAD candidate: 8.4 s wall including archive (lint 7.8 s), 0 errors (working tree: 0 errors).
  Scratch repo: an untracked broken file is ignored; a committed path with an unresolved symbol refuses and
  prints its error lines.

## 3. Check publishes documentation; cost follows drift

- `bin/seon check` of HEAD itself (no paths) on 2026-10-07, default running a8e3bde5b (15 commits, 89 program
  files behind HEAD 433214bc5): 55.4 s = published 45.5 s + adopted 9.5 s (2,008 identities); archive 0.1 s.
  `rehearse-source!` runs a FULL refresh (`:seon.source/changed-paths []`) against the program the running JVM
  published, so every check pays the drift since default's last restart, whatever it changes. The 42.6 s (docs,
  5cdb15a87) and 16.5 s (task files) were this drift, not their paths: whole-program work per change, not task
  indexing. Task files remain program inputs and keep the check.
- Fix here: a commit whose every path is under docs/ and that names no `--test` does not run `bin/seon check`
  (documentation is no program input). The owner fix belongs in `seon.operator/check!` (classify by
  `seon.test.cache/input-roots`) and `rehearse-source!` (rehearse the candidate's delta from HEAD), both in files
  held by other lanes now (script/seon/operator.clj: printers-errors; src/seon/cluster.clj: printers-rest), and the
  babashka operator cannot load seon.test.cache (hasch). Tasks: seon.operator.edn
  `check-rehearsal-cost-follows-drift-not-the-change`, `check-decides-documentation-by-input-roots`.
  Until then, restarting default at HEAD (`start --head`) is what shortens every lane's check.

## Timings

- Docs-only commit, before: 56 s check (42.6 s publication) + 17 s lint.
- Task-file + script commit 1758624f4 (check runs): 64.6 s wall = check 62.5 s (published 48.8 s, adopted 9.0 s:
  default's drift again) with the 13.0 s lint concurrent, not after it (before: + 17 s serial).
- Docs-only commit 90fd4919e: 25.2 s wall, all of it the candidate lint (23.8 s under ten lanes' load), no check.
  Before: ~56 s check + 17 s lint. A lint of a candidate whose src, script, test and .clj-kondo are HEAD's
  repeats HEAD's lint, so a commit touching none of those roots now skips clj-kondo too (docs or task files only).
