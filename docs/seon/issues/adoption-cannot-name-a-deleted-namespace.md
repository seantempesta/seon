---
type: issue
status: open
severity: friction
created: 2026-09-23
tags: [issue, publication, adoption, deletion]
---

# Adoption cannot name a deleted namespace, and a full refresh panics on the deleted file

Lane cut-l1, 2026-09-23, default pid 5070, after `git rm src/seon/bootstrap_drive.clj
test/seon/bootstrap_drive_test.clj` (committed `68eab438d`):

1. `bin/seon init --dev default --changed src/seon/bootstrap_drive.clj
   test/seon/bootstrap_drive_test.clj …` refuses with "Changed paths are neither in the JVM's
   source directory nor published" (0.41 s), and names the two deleted paths. There is no way to
   state "this file was deleted" to the adoption.
2. A request that reached `seon.cluster/full-source-refresh!` (`cluster.clj:2055`) panicked in
   `seon.fn/source-context` (`fn.clj:129`, `Files/readAllBytes`) with
   `NoSuchFileException /Users/sean/src/seon/test/seon/bootstrap_drive_test.clj` (1.92 s). The
   refresh reads a stored row's file without first asking whether the file still exists.

**Wanted.** A deleted source path is a change. Adoption retracts its namespace's program
rows and unloads nothing else, and a full refresh treats a missing file as that deletion.
Regression: delete one namespace file that has no dependents, adopt it by path, and assert
that its rows are gone and the adoption succeeds.

**Owner.** `src/seon/fn.clj` (`source-contexts`) and `src/seon/cluster.clj` (changed-path
classification).
