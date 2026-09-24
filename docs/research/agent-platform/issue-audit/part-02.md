---
type: research
status: current
created: 2026-09-24
scope: issue audit part-02 — 109 issue notes (instant-vector … runtime-status) read as candidate tasks
---

# Issue audit, part 02: 109 notes as candidate tasks

Input: `tmp/orchestrator/issue-audit/part-02` (109 paths, `instant-vector-…` through
`runtime-status-…`). Data: [`part-02.edn`](part-02.edn), one map per note. Method: I read
every note in full. A babashka script pulled cited qualified symbols and `src/ test/
resources/ bin/` paths and checked each one against the tree: does the namespace file exist,
does it define the name, does the path exist. For every note I then made one targeted
`rg`/`sed` check of the cited code. The JVM was not run, so "live" means the code and the
problem are still present in source. It does not mean the defect was reproduced.

## Counts

| Relevance | n | | Frontmatter status | n |
|---|---:|---|---|---:|
| live | 56 | | open | 104 |
| unknown | 35 | | closed (not a README status) | 3 |
| obsolete | 18 | | resolved | 2 |

Of the 18 obsolete notes, **13 still say `status: open`**. They are fixed or superseded
without being closed (e.g. `keep-history…` says "Applied in db4efb4fd"; `packaged-forms…`
has its proposed cache in place; `orderly-stop…` is now bounded through `seon.await/await!`;
`retiring-a-contract-referenced…` has a "Resolution" section). Five closed or resolved notes
were never moved to `archive/`.

| Trigger | all | live | | Work | n | | Exit kind | all | live |
|---|---:|---:|---|---|---:|---|---|---:|---:|
| error | 24 | 11 | | fix | 35 | | example-test | 51 | 29 |
| review-finding | 22 | 16 | | test (re-run/prove) | 17 | | other | 19 | 2 |
| test-failure | 21 | 4 | | docs (mostly archive) | 16 | | empty-query | 13 | 9 |
| measurement-over-bound | 14 | 8 | | design-decision | 12 | | bound-test | 9 | 5 |
| query-finding | 10 | 5 | | investigate | 11 | | property | 7 | 3 |
| design-gap | 6 | 5 | | refactor | 7 | | measurement | 6 | 5 |
| user-request | 4 | 1 | | measure | 5 | | root-decision | 4 | 3 |
| staleness / owner-ruling / dependency-change | 3/3/2 | 1/3/2 | | tooling/delete/upstream | 2/2/2 | | | | |

Size: 71 small, 27 medium, 11 large (all large notes carry a `:task/split`). 63 of 109 have a
plausible `:task/trigger-query`. Five are duplicates. The three `problems`/`runtime-status`
notes describe one producer. The platform-selection pair is another, and the
docstring/opening-comment pair a third.

## Patterns

1. **Test-failure notes decay fastest.** Of 21 test-failure notes, 4 are live. The rest cite
   tests, fixtures or launchers that have since been deleted: `bin/test-fast`,
   `fresh_operator_test`, the published test base, `test-runner-test` launcher tests. A task
   keyed to a test symbol loses its subject when the test is deleted, and nothing notices.
   Most live notes are **review findings**, and the code they name is still there.
2. **Most unknowns are one re-run away.** About 15 notes say "repaired, proof pending" or
   "observed once under concurrent edits": blob staging, codec fixture, selection
   completion, moved assertion, the platform fixtures. Each would close or re-confirm with one
   `seon.test/run` of the named test. That task is the same every time, and the database can
   derive it by joining an issue's test ref to that test's latest run.
3. **Notes are diaries, not tasks.** Many notes pile up dated "re-observation" sections from
   different lanes. Some of those sections contradict the headline:
   `issue-worker-opening-turn…` refutes its own title; `priming-indexes…` is reopened on top
   of a "Historical resolution"; `mcp-runtime-status…` is half unrelated REPL-unavailable
   sightings. The subject, owner and exit are usually in the note, but they are buried.
4. **Recurring defect families** (each should be one task generator, not N notes):
   - *MCP projection totality*, 5 notes: exception opaque, map refused, small string
     refused, SCI nil db, and session loss that claims a restart. One property covers all
     five: for generated values, a projection never replaces a successful value.
   - *Absence read as health/nil*, 6 notes: `runtime_status` returning `[]`, an empty plan
     showing `nil`, `renderer unavailable`, "expected an integer, got an integer", pull
     capped at 1000, a listen whose deleted entity turns it into a wildcard.
   - *Fixed-but-open / closed-but-not-archived*, 18 notes.
   - *Whole-store or whole-program cost per event*, 6 notes: move-to-head, issue indexing,
     opening forms at 2.3 s each, `installation-covers-program-change?`, a 21 s / 4 GB
     `breaks` evaluation, generations of projections retaining memory.
   - *Unbounded wait or retry*, 4 notes: fixture retry on `run-exists`, a cancelled future
     that keeps running, `agent-already-running`, a backstop that fires 600 s after a failed
     step.
5. **Queries could have raised a third of these automatically.** The strongest cases, where
   the query also defines done:
   - orphan AST nodes: a count of component-attribute entities that no holder refers to;
   - callerless functions (`restorable-node`): `seon.fn/callers` is empty;
   - error signatures with no occurrences;
   - more than one Datahike listener key per cluster;
   - `:seon.render/form` occurrences, and callers of `projection-fallback`: counts that should
     fall to 0;
   - required contract keys that a docstring never mentions (`my.fs/write!`);
   - self-addressed messages;
   - multi-map `:or` unions where merged arms validate against both branches;
   - profile cells whose max exceeds a bound. The 600,001 ms max is how the nil-source fault
     was found.

## Most instructive notes for task design

- **`mcp-jvm-eval-drops-reader-metadata-on-a-defn-name`**: raised by a dogfood agent, marks
  its cause UNVERIFIED, and gives a paste-ready success test. This is the target shape.
- **`lint-identities-collide-for-multiple-findings-at-one-location`**: small, one owner, the
  error is the trigger, and an example test is the exit.
- **`orphan-ast-nodes-outlive-the-declaration-that-held-them`**: born from a query, and the
  same query at 0 is done. There is no prose judgement in between.
- **`repl-parity-divergences`**: the best existing generator. A metadata-tagged table of
  `:known-divergence` rows where each red row is a task, and the gate fails if a row starts
  passing without being promoted.
- **`move-to-head-takes-a-minute-or-more-not-seconds`**: its phase table (measured cost,
  owner, cause) already is the task split, one measurable subtask per row.
- **`published-head-references-never-written-index-nodes`**: critical, with forensic
  evidence and three priced options. It shows a task that must stay open after its main
  fixes land (a and b landed; the open-time node check did not).
- **`namespace-responsibility-and-issue-workers-are-singular`**: this is the task model
  itself. The note asks to generalise issue workers into non-defect tasks with several agents
  per namespace. It blocks the whole direction.
- **`runtime-listens-do-not-yet-participate-in-turn-eligibility`**: "data triggers a focused
  turn" is exactly what the listen patterns already express, but turn eligibility ignores
  them. This is the missing wire for data-triggered tasks.
- **`keep-history-is-on-by-default-without-a-decision`**: the fixed-but-open archetype. The
  body says applied; the status says open.

## What does not fit the task model

- **Visual evidence.** Layout, raw ids in HTML, and namespace pages need a browser paint or
  screenshot as the exit. `:measurement`/`:other` do not say how that is verified. A
  `:visual` exit kind with a named probe script would.
- **Tool-output exits.** clj-kondo resolution, output size, and heap retained after N
  generations are neither Clojure tests nor database queries. The model needs "a declared
  command's output is empty or under a bound".
- **Deferred or parked work.** Owner-deferred notes (`namespace-require-summary…`) and notes
  blocked on file ownership or an upstream fix (negative imports, Posh) need `:task/state`
  (`:ready | :blocked-on | :deferred`) and a `:blocked-on` ref, separate from `:task/needs`.
- **Closing and staleness.** Nothing marks a task done when its subject is deleted or its
  exit already passes. Useful detectors:
  - an issue's cited symbol no longer exists;
  - an issue's test ref is green later than the note's date;
  - cited line numbers are past the end of the file (`nested-test-snapshot…` cites
    `bin/test:493` in a 65-line file);
  - the body says Resolved/Applied while the status is open.
- **Supersession by design change.** About 8 notes are answered by a direction change, not
  by a fix: one JVM and no scratch roots, agents on their own branches, deletion of the test
  base, the new context design. A task needs a `:superseded-by` edge to a ruling, not only to
  another issue.
- **Aggregated observations.** Several notes are logs of sightings with no single cause. The
  model should keep sightings as occurrence facts on one task (like error occurrences), not
  as appended prose sections.
- **Non-Seon subjects.** `posh-cardinality-one…` concerns a comparative reference checkout.
  Its subject is outside the program graph, so it cannot be scoped by the namespace/symbol
  subject.
- **Lifecycle vocabulary drift.** Notes use `closed` (3), `defect`, `blocking` and `critical`,
  none of which are README statuses or severities. The indexer should refuse them.
