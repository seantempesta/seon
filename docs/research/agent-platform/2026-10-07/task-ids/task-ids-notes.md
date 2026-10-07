# task-ids — notes (lane task-ids, 2026-10-07)

STATUS: STOPPED at design, for one owner decision (what an annotation names and what `:seon.task/tasks` stores) and
because 20 of the files that carry handle literals are held by printers-round2. Only this file is claimed; no code
changed.

## What exists

`seon.task/task-handle` = `seon.id/id [ns name kind]` (src/seon/task.clj:46). `file-declarations` (task.clj:89-112)
already derives the handle for every entry and refuses `:handle-mismatch` only when an entry supplies a different
`:seon.task/id`. Of 368 file tasks, 130 carry a literal `:id`, 238 already do not. The `:seon.task/entry` schema
(resources/seon/schemas/seon.task.edn) allows the optional `:seon.task/id`.

The handle literals that break when `seon.id` changes are mostly NOT the task-file `:id`s:

- `:seon/tasks #{"<handle>"}` annotations on namespaces, defns and deftests: 53 files in src and test.
  `seon.fn/task-links` (src/seon/fn.clj:626-644) copies them verbatim into the program entity's `:seon.task/tasks`
  (a set of handle strings, indexed). Readers: `seon.task/task-edges`, `open-tasks`, `task-findings` (dangling),
  `candidate-dangling` (merge gate), `seon.cluster.source`, `seon.turn`.
- Handles of the 130 `:id` tasks: src 45, test 47, resources 146 (task `:id`s plus handles quoted in other tasks'
  intents), docs 557 occurrences. Handles of the 238 id-less tasks also appear in annotations (derived, hand-copied).
- Runtime handles (`:seon.task/assigned`, `:seon.task/merge`, steps, `create!`) are derived at the call; they keep
  working under any hash and need no edit.

So stripping `:id` from task files alone (the mechanical part of this assignment) removes 130 literals and leaves
every annotation literal in place: it does not unblock structural hashing, and it makes an annotation's handle
harder to find (no `rg` from handle to task file).

## What must not change

The handle stays `id/id [ns name kind]`, the entity identity of `:seon.task/task`; under today's hash every stored
task keeps its identity, and assignments, proposals and steps keep referring to it by handle.

## Decision needed: what an annotation names

`:seon/tasks` must stop holding a hash. The ruling says tasks are referenced as `ns/task-name` symbols; that
reference omits `kind`, which is part of identity.

- **A (recommended).** Annotations name tasks by quoted symbol, like `:seon/must-read`:
  `{:seon/tasks #{'seon.task/no-repl-entrance-creates-a-task}}`, resolved through the namespace's aliases.
  Ingestion stores `:seon.task/sym` (`ns/name`, indexed, unique) on each task beside the derived handle; a program
  entity's `:seon.task/tasks` becomes a set of symbols; `task-edges`, `open-tasks`, `task-findings`,
  `candidate-dangling` join on `:seon.task/sym`. Ingestion refuses a duplicate ns+name (stricter than ns+name+kind;
  0 of 368 today), so a symbol names exactly one task. Three names contain spaces and get hyphenated
  (seon.sci.eval "a fresh context's result handles have no regression", seon.sci.reader "schema entities lose their
  declaring namespace", "a value deeper than the reader's stack cannot be read back"; their handles change, they
  carry no `:id`). Cost: one scripted sweep handle→symbol over the 53 annotation files (mapping computed by
  `task-handle` over the task files), test literals the same; the task-file `:id` strip rides along. Dated docs keep
  their handles as history. Guarantee: no hash literal remains in src/test/resources; a renamed task becomes a
  dangling symbol the merge gate already refuses. Sacrifice: the annotation schema and stored attribute change
  (data is disposable; reset after landing).
- **B.** Annotations name `ns/name` symbols, but `seon.fn` resolves them to handles while indexing the program.
  Stored data unchanged. Couples program indexing to task files (it must read every task's kind, and a symbol for a
  task not yet ingested has no handle), so dangling detection moves into the indexer. Not recommended.
- **C.** Do only the task-file strip now (src/seon/task.clj, seon.task.edn, 70 task files, task_ingestion_test).
  Small and unblocked, but structural hashing stays blocked by the ~450 annotation/test literals.

## Blocked files (independent of the decision)

Annotation or handle literals in files printers-round2 holds: src/seon/test/runner.clj, error/refusal.clj,
render.clj, error.clj, fault.clj, test.clj, batch.clj, health.clj, sci/eval.clj, problems.clj, render/web.clj,
render/walk.clj, schema.clj, db.clj, flow.clj, src/my/background.clj, test/seon/sci/eval_test.clj,
test/seon/error/refusal_test.clj, test/seon/cluster/agent_test.clj, test/seon/flow_test.clj. Option A's sweep is
one commit over all annotation files, so it waits for printers-round2 to release (or root lands it in a quiet window).

## Slice once decided (option A)

1. Schema: `:seon.task/sym` (qualified symbol, identity), `:seon.task/tasks` → `[:set :qualified-symbol]`,
   `:seon.task/annotation` `:seon/tasks` → quoted symbols, `:entry` loses `:seon.task/id`, `:refused` loses
   `:handle-mismatch`, gains `:duplicate-name`. `:dangling` keyed by symbol.
2. `seon.task/file-declarations`: derive handle and sym; drop the mismatch branch; `tasks-tx` refuses a duplicate
   ns+name by name. `task-links` resolves `:seon/tasks` like `:seon/must-read`.
3. Readers: `task-edges`, `open-tasks`, `task-findings`, `candidate-dangling`, `seon.cluster.source`, `seon.turn`.
4. Script (scratch, rewrite-clj): strip `:id` from resources/seon/tasks/*.edn; rewrite every `:seon/tasks` string
   set in src/test to the symbol set; replace handle literals in tests by `(seon.task/task-handle ...)` or the symbol.
5. Regression (test/seon/task_ingestion_test.clj): an id-less entry ingests with `task-handle` of its ns+name+kind
   and its sym; a duplicate ns+name refuses `:duplicate-name` naming the holder.
6. Prove on a branch of the live store (schema change); RESET NEEDED after landing.
