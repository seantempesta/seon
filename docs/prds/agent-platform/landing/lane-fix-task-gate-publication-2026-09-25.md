---
type: landing
lane: fix-task-gate-publication-opus
status: landed
date: 2026-09-25
authority: tmp/orchestrator/init-p0.txt (symptom); spec 7.13 (task admission before the program gate)
---

# Lane fix-task-gate-publication (2026-09-25)

The publication task gate (`seon.cluster.source/index-tasks!`, slice 1b) now checks
annotations against the tasks admitted from the same published source. Development
clusters now get those tasks on adoption, as data and never as program. Publication
was blocked; it runs again.

## Broken or unresolved first

- **Bootstrap gap.** A publication runs with the code already loaded in the JVM, so
  this fix could not take effect in the init that installs it. Getting it into default
  took three inits:
  - init 1: `--changed` with only the fix files. It refused the same way as before,
    because the loaded analyzer re-analyzed every file and the old code still skipped
    task files that had not changed.
  - init 2: `--changed` with the fix files plus `resources/seon/tasks/seon.fn.edn`.
    The old code ingested the task, the gate passed and adoption reloaded the new code.
  - init 3: a later publication. The new adopter carried the task into default.
- init 2 took **56.5 s**, because the analyzer digest changed and every file was
  re-analyzed (`seon.fn/analyzed-artifacts` 37 s). That is over the 10 s limit. It is
  the known whole-program re-analysis cost, not something this lane added. It has no
  new issue.
- **Same defect class, out of scope:** `index-issues!` still reindexes only the
  changed note paths. A recorded note digest is trusted the same way the task-file
  digest was. No issue has been filed; root should decide whether to file one.
- Default health is still `:faulted` from older faults. Two occurrences of the
  historical `dangling-id` fault (signature 9d66292b…) are still open.
- **Test-file fix.** Two tests in `test/seon/issue/task_ingestion_test.clj` were red
  before this change and never passed with contracts armed:
  - the publication-seam test passed `"d"`, which is not a valid `:seon.source/digest`;
  - it expected pulled `:seon.fn/see-also` to be a set, but Datahike returns a vector.
  Both are fixed.

## Cause (verified at the REPL)

- **Hypothesis (a) confirmed.** The published head `6ab6a945` had:
  - the file row for `resources/seon/tasks/seon.fn.edn` at its current digest `ca85c0be…`;
  - zero `:seon.task/id` entities in all of history.
- Both the attribute and the file digest were admitted in the 16:52Z publication, but
  the code loaded then did not ingest tasks.
- Every later publication read the unchanged task-file digest as "tasks admitted",
  because `task-paths` held only changed task files. Once `fn.clj`'s annotations were
  published, the gate read a database with no tasks.
- **Hypothesis (b) also true.** `adopt-rows!` ignored `:seon.task/id` identities, so the
  default cluster never received tasks.

## Fix

- **Publication** (`src/seon/cluster/source.clj`). `index-tasks!` now reconciles every
  task file in the published directory, plus the file of every stored task namespace,
  so a deleted file still retracts its tasks. It no longer uses only the changed task
  paths. The whole set reconciles in about 2 ms: `task-files` 0.3 ms, `tasks-tx` 1.8 ms.
  The findings read costs 55 ms, as before.
- **Adoption** (`src/seon/cluster.clj` `adopt-rows!`, new `src/seon/issue.clj`
  `adopt-tasks-call` and `adopt-tasks-tx`). Each adoption reconciles the whole
  published task set into the cluster:
  - the writer's current value is the basis;
  - a handle missing from the source is retracted;
  - the call is empty when the cluster already agrees.
- `task-delta` is split out of `tasks-tx` so both paths use it. `stored-tasks` now
  takes the attribute and reads nothing when tasks are not declared.
- Tasks never become program rows: `program/identity-attributes` is unchanged.

## Evidence

- Final init: commit `6ab6ae2a-b260-5484-9b28-11a9dc16c2cd`, converged, 5.2 s.
- Default cluster:
  - adopted commit matches the published head;
  - `:seon.task/id` holds `["300aca1ad57c"]`;
  - `task-findings` shows `{:dangling {} :triage #{}}`.
- MCP `runtime_status`: `replaced-root-count 0`, `replaced-roots []`. No stale Vars.
- `bin/test-check default --ns seon.issue.task-ingestion-test` (run e0815888d0fb):
  7 tests, 40 assertions, passed, 11.8 s. The class regression is
  `a-source-only-publication-admits-the-unchanged-task-file-its-annotations-name`.
  The adoption test is `adoption-carries-published-tasks-as-data`.
- Raw output is in `tmp/fix-task-gate-init{1..6}.txt` and `tmp/fix-task-gate-test{,2,3,4}.txt`.

## Commits and size

`f06358c74`, `90922ac97`, `244aac352`, `4c710328c`, `3b1091d3b`. Across the four
owned files: +133/−46, 87 net lines (source and tests).

## Root runs

- `bin/test-check default --ns seon.cluster.source-test`. It exercises `publish!`
  through `index-tasks!`, and this lane did not run it.
