# Task write-back: a merged task is written to its task file

Root's design slice, 2026-10-12 (lane task-writeback; docs and red proof only). What exists: tasks are
ingested from `resources/seon/tasks/<ns>.edn` into `current-src` (`seon.cluster.source/publish!` →
`seon.task/index-tasks!`) and adopted onto main (`seon.cluster/adopt-entities!` →
`seon.task/adopt-tasks-call`); `my.task/refine!` (7e2544e38) rewrites the held task's intent on the agent's
branch; the facts merge (`seon.merge/squash-merge`, tasks are `:seon.program/partition :seon.cluster`)
carries it to main; `seon.merge/publish!` (05d3eb2ed, fa0b0b6cb) writes merged declarations back to the
checkout and commits exactly those paths. The principal approach: a task entry is a declaration whose file
is its write-back, exactly as a schema key's resource is (`proven-schema-file`), so publication's existing
derivation, proof and commit carry it.

## 1. What happens today to a refined task on main

Read by symbol at de3244669, verified on default in JVM mode (root, `d/with` only, nothing written).

- **Refinement keeps the task a file task.** `my.task/refine!` calls `seon.task/create!`, whose
  `seon.task/task-entity` stamps `:seon.task/created-by`, but for a task that already exists
  `seon.task/task-delta` writes `seon.task/replacement-tx` over the declaration's members only. Verified:
  the refine transaction for `my.task/a-send-back-stores-its-counterexample-for-the-relaunched-agent` is
  `[[:db/retract 210420 :seon.task/intent] {:db/id 210420 :seon.task/intent "REFINED intent"}]`; afterwards
  the task has no `:seon.task/created-by`. So every later file reconciliation treats it as the file's.
- **The next publication reverts it, silently.** `current-src` is built from files only, so it never
  holds the refinement. When a new publication is adopted (`seon.cluster/development-source-refresh!`,
  i.e. every start or reload whose source changed), `seon.task/adopt-tasks-call` reconciles **the whole task
  set** from `current-src` onto main with main's own current value as the basis. Main's value is the
  basis, so no revision conflict fires, and `task-delta` asserts the file's intent back. Verified on
  the refined `d/with` value: `adopt-tasks-call` answers one call whose `adopt-tasks-tx` is
  `[[:db/retract 210420 :seon.task/intent] {:db/id 210420 :seon.task/intent "<the file's intent>"}]`.
  `seon.task/tasks-tx` over the files gives the same revert. Nothing flags it.
- **A fresh install loses it.** Ingestion reads only the files.
- **Today main agrees with its files.** `tasks-tx` of main against `seon.fs/source-directory`'s 96 task
  files is empty (808 tasks on main, 1 runtime task).
- **Publication never sees a task change.** `seon.cluster.agent/publication-due?` requires
  `:seon.task/merged-declarations`, which `seon.merge/merge!` writes only on the gated path (program
  declarations the branch changed). A merge that only refines a task is a facts-only merge. Also
  `seon.cluster.source/merge-accepted` derives identities over `seon.program/identity-attributes`, and
  `:seon.task/id` is not one of them.

## 2. The rule

**A task entry is a declaration; its task file is its write-back.** A merge that changes a task's
declaration (`:seon.task/declaration`: name, step, intent, must-read, evidence, children, needs) is
publication due work like a merge that changes a function. `seon.merge/publish!` writes that one entry into
`resources/seon/tasks/<ns>.edn` with rewrite-clj (other entries byte-identical) and commits it with the
merge's other paths, in the same git commit, under the same claim.

The smallest set of changes, each at the part that owns it:

1. **Derivation: `seon.cluster.source/merge-accepted`** adds `[:seon.task/id handle]` identities: the handles
   touched since the parent commit whose `seon.task/task-revision` (the digest of the declaration, already
   public) differs between parent and merge commit. Same commits, same `d/since` datoms, one more identity
   family. No new fact.
2. **Path: `seon.cluster.source/change-path`** maps a task identity to `resources/seon/tasks/<ns>.edn`
   (its `:seon.task/ns` before the merge, else after for an added task), next to `schema-path`.
3. **Proof and bytes: `proven-task-file`** (new, beside `proven-schema-file` in `seon.cluster.source`, chosen
   by `proven-files` like schemas are). It reads the file and finds each written entry by
   `:seon.task/name`. An entry that declares neither the before nor the after value refuses
   `:conflict`. The edit replaces the entry's map node through the existing `relaid`, so unchanged members
   keep their nodes and only the intent's string node changes. An added task is appended to the vector,
   separated like its neighbours. A retracted one is removed with the whitespace before it. The new
   value's nodes come from `rewrite-clj.node/coerce`, the library's value-to-node function, never `pr-str`
   (one printer). The candidate is proven by reading it back through `seon.task/file-declarations` (the
   ingestion reader). Each written entry must give the after declaration and every other entry must read
   as before. Then publication's atomic rename installs it, as for every file.
4. **Due-ness: one count, written once.** `:seon.task/merged-declarations` keeps its name and widens to
   "declarations the merge moved, program and task". `seon.merge/merge-onto`'s `answer`, the transaction
   that already writes `:seon.task/merge-commit`, writes it as the count of `merge-accepted`'s identities,
   and only when that count is positive. Both paths go through it: the facts-only path and the gated path.
   **Deleted:** the `moved` add in the accept transaction (`[:db/add a :seon.task/merged-declarations
   (count expected-digests)]`), so one producer counts. `publication-due?` is unchanged. Its docstring
   "a merge of facts alone is never due" becomes "a merge moving no declaration is never due". A
   crash between accept and `answer` leaves no merge commit, and therefore no due work, which is
   unchanged from today.
5. **Adoption stops reverting main: `seon.task/adopt-tasks-call` becomes a three-way merge** from the
   prior adopted `current-src` commit (the merge base: `seon.cluster/development-source-refresh!`'s
   `prior-commit`) to the new published commit. Only handles whose revision changed between those two
   commits are reconciled (plus retractions). The first adoption, which has no prior commit, still takes
   the whole set. When a handle changed on `current-src` and main's revision is still the base's, theirs
   is taken. When main changed it too, which means a merged refinement whose publication is still due,
   main keeps its value and root gets the problem task, exactly like `seon.merge/squash-merge`'s `:root`
   conflict (`conflict-task!`). The publication then meets the same disagreement in the file and refuses
   `:conflict`, which is recorded on the assignment. This is not a fence. It is the merge rule git and
   `squash-merge` already use, with the base main already records. It is needed because publication
   waits while a lane claims the path: today lane `task-fields` claims all 96 task files (`bin/lane
   status`), so a refinement merged now would wait in its due publication across any restart. Doing
   this also turns adoption's per-publication work from all 808 tasks into the changed ones.

Steps 1–4 make the refinement durable. Step 5 makes it safe while the publication waits. Both are
small. Steps 1–4 are phase A; step 5 is phase B, each with its proof below.

## 3. The red proofs

**Phase A: `seon.cluster.merge-test/a-refined-task-is-written-to-its-task-file-and-survives-re-ingestion`**,
tagged `seon.merge/a-refined-task-is-written-to-its-task-file`. It uses the fixtures already in that
namespace: `checkout!`, `checkout-dial!`, `assigned-agent!`, `merge!` and `pass!`, inside
`support/with-running-agent`.

1. `checkout!` holds `test/tq/land.clj` (`land-source`) and `resources/seon/tasks/tq.land.edn`, a vector of
   two entries: `area` (`"Fix."`) and `side` (`"Other."`), each on its own lines. Ingest it on main with
   `seon.task/index-tasks!` over `(seon.task/task-files root nil)`. (`assigned-agent!` grows a variant that
   assigns the ingested task instead of `create!`-ing a runtime one.)
2. The agent evaluates `(my.task/refine! {:seon.task/intent "Make `tq.land/area` square its side."})` on its
   branch. `merge!` then performs a facts-only merge, because no declaration is changed.
3. Assert main's `tq.land/area` intent is the refined one and the assignment row holds
   `:seon.task/merged-declarations 1`. **Red on HEAD:** absent, because a facts-only merge is never due.
4. `pass!` once. Assert `:seon.task/git-commit` equals the checkout's HEAD, and that `git diff-tree` of
   that commit names exactly `resources/seon/tasks/tq.land.edn`. Assert the file's `area` entry reads
   (`seon.task/file-declarations`) with the refined intent, and that the text of the `side` entry, and of
   everything outside the `area` entry's intent string, is byte-identical to the fixture's.
5. Survives re-ingestion: `(seon.task/tasks-tx (db/db connection) (seon.task/task-files root nil) basis)`
   is `[]`, so the file declares exactly what main holds and a fresh install ingests the refined intent.
   A second `pass!` changes nothing.

**Phase B: `seon.task-test/an-adoption-keeps-a-merged-refinement-the-files-have-not-seen`**, tagged
`seon.task/an-adoption-keeps-a-merged-refinement-the-files-have-not-seen`. This is a table over
`seon.task/adopt-tasks-call` with three published values: base, the same base, and base with the `side`
entry changed. Main holds base with `area` refined. The rows:

- An unchanged republication adopts nothing. **Red on HEAD:** today it reverts `area`, the transaction
  verified above.
- A republication changing only `side` takes `side` and keeps `area`.
- A republication changing `area` too keeps main's `area` and writes one problem task.

## 4. What changes, what is deleted, the files

- `src/seon/cluster/source.clj`: `merge-accepted` adds task identities; `change-path` adds the task file
  path; `proven-files` dispatches to `proven-task-file` (new), and `task-file-text` (new) is the
  rewrite-clj edit sharing `relaid`; the write-back check that each written declaration still holds the
  merge commit's digest reads `task-revision` for a task identity.
- `src/seon/merge.clj`: `answer` writes `:seon.task/merged-declarations`; the accept transaction's `moved`
  add is **deleted**; `publish!`'s docstring names task entries.
- `src/seon/cluster/agent.clj`: `publication-due?` docstring only.
- `src/seon/task.clj` and `src/seon/cluster.clj` (phase B): `adopt-tasks-call` takes the prior
  adopted commit and reconciles the changed handles three-way. The **deleted** behavior is the whole-set
  reconciliation on every adoption.
- `resources/seon/schemas/seon.task.edn`: `:seon.task/merged-declarations` description (program and task).
- `test/seon/cluster/merge_test.clj`, `test/seon/task_test.clj`: the two proofs.
- `resources/seon/tasks/seon.merge.edn`, `resources/seon/tasks/seon.task.edn`: the two task entries (both
  files are held by lane `task-fields` now; phase A waits for its release or goes as a patch).
- `docs/seon/specs/merge.md#4-merge`: publication writes task entries; AGENTS.md "Write-back" gains
  "task entries included" in the same commit as phase A.

Cost: phase A adds one `task-revision` pair per touched task to `merge-accepted`, plus one rewrite-clj
parse and one `file-declarations` read of one task file per publication. That is proportional to the
file, far below the measured 259.8 ms publication pass. Phase B makes adoption proportional to the tasks
the publication changed, not to all 808.

## 5. Open questions, with recommended answers

1. **Is a task an agent created and merged (`create!` on its branch, `:seon.task/created-by`) written back
   too?** Recommended: yes. It is a tracked fact that reaches main through the merge, and a fresh install
   should hold it. `created-by` stays on main as provenance and is not written to the file. Ingestion
   then finds it declared by the file, and adoption's comparison sees equal declarations.
2. **Root's own tasks written directly on main** (problem tasks such as `squash-conflict-…`, generated
   contract tasks): written back? Recommended: not by this rule. They never pass through a merge, so they
   are never publication due work. They are rederivable data. Revisit only when one is observed lost
   after a fresh install.
3. **Member order and formatting of a new entry.** Recommended: copy the neighbouring entry's layout
   (`relaid` for changed entries; an appended entry takes the last separator) and keep members in
   declaration-schema order. Never re-sort a file.
4. **Phase B before phase A?** Recommended: phase A first, because it is the owner's goal and it is
   green-provable alone, then phase B at once in the same restart batch. A refinement merged before phase
   B is exposed only when a restart happens while its publication waits.
5. **One fact or a new one (`:seon.task/merged-tasks`)?** Recommended: keep the one count. Due-ness is
   one question ("did this merge move declarations?"). A second attribute would split it, and the
   identities that `merge-accepted` derives already say which kind each one is.

Phase status: A — merged in 7c87dfb8e (task identity is `[:seon.task/sym ns/name]`, not `[:seon.task/id handle]`: the declaration-identity schema admits a symbol value and the path derives from it). B — merged in the commit after it (three-way adoption from the prior adopted commit).
