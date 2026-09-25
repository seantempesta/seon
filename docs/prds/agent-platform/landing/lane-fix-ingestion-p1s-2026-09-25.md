---
type: landing
lane: fix-ingestion-p1s-opus
status: committed-unverified
date: 2026-09-25
authority: docs/research/agent-platform/s0-and-ingestion-fixes-diff-review-2026-09-25.md (5fb38b91f) P1-1..P1-3; spec 7.13, 7.15, 7.16
---

# Lane fix-ingestion-p1s (2026-09-25)

The three P1s from the task-ingestion review are fixed in their owners, one commit each. They are
committed but **not yet run**: under the LOAD RULE this lane ran no test requests and loaded nothing
into default. Root runs the commands below.

## Broken or unresolved first

- **Unverified.** No test ran. The new files lint clean with clj-kondo. The namespaces were not
  loaded into default, because loading would redefine default's Vars. Every behaviour below was
  proven at the REPL only in scratch form: read-only `jvm` evals and `d/with` values, plus one SCI
  transaction on the disposable branch `fix-ingestion-p1s`, since retired.
- **Required basis needs the schema file.** `:seon.task/request` still makes `:seon.task/basis`
  optional. It lives in `resources/seon/schemas/seon.task.edn`, which this lane does not own.
  Publication now binds a real basis. The read-now default of `seon.issue/index-tasks!` is
  documented as guarding only writes after the call, not a stale file. Making the basis required
  (and having callers read it) is a follow-up for the schema owner.
- **Contract finding seen in passing (out of scope).** `seon.db/transact!` rethrows a
  transaction-function `ex-info` as a contract finding: "returned this error, and none of its
  declared error alternatives matches it". Seen on the lane branch. The task refusal data does
  survive: `:seon.task/refused` is in the thrown data. No issue filed; root should decide.
- **Adoption refusal is now loud.** A competing destination edit between adoption's read and its
  write now fails the whole `adopt-rows!` transaction with `:revision-conflict` (spec 7.16). It is
  no longer overwritten. The source head guard is unchanged.
- **Unrun end-to-end cost.** The end-to-end merge regression uses two `prepare-merge!` calls, each
  a nested `seon.test/run`. It declares `:seon.test/long-ms 120000`, based on the sibling merge
  tests' measured cost (lane-nsa-slice4). It has not been measured here.
- `cluster.clj` did not change. `adopt-rows!` already passes the held `(db/db connection)` as the
  destination value, and that read is now the basis.

## Fixes

1. **P1-1, `9bfa6fff2`.** `seon.issue/file-declarations` now treats a member as absent only when
   its value is `#{}` and it is an optional member whose schema is `:set`. That set is derived from
   `:seon.task/entry` (`#{:seon.task/must-read :seon.task/evidence}`). Malli `m/explain` then
   validates the whole entry, and the refusal keeps the authored `(first forms)`.
2. **P1-2, `d83286cee`.**
   - `adopt-tasks-call` reads a basis from the destination value it holds. `adopt-tasks-tx`
     compares against it inside the transaction, through the one `task-delta`.
   - A held handle that the source lacks is included only when the source's **history** retracted
     it, meaning it was published and then deleted. `task-handles` gained a `retracted?` read over
     `db/history`. A task the source never held stays.
   - Publication's `index-tasks!` (source.clj) binds `:seon.task/basis` to the `previous` published
     value the request derived from. The `force-branch!` head guard fences that value.
3. **P1-3, `9bff631b2`.**
   - New `seon.issue/candidate-dangling`. It takes the candidate's task edges for the merged
     identities and every other holder's edges from the head, and returns the handles that are not
     admitted on the head, keyed by holder.
   - `accept-merge!` refuses with those handles as `:seon.error/offending` before `db/transact!`.
     The write's `:datahike/expected-basis-t` binds the decision to that head.
   - Task rows never ride the merge.

## REPL evidence (default JVM, commit 6ab6bb1a lineage)

- **P1-1 before.** The old `file-declarations` accepted all four authored entries and minted
  `6e3b80c1ba12` for each: `:unknown []`, `:must-read []`, `:evidence {}` and `:id []`.
- **P1-1 after.** The same entries under the narrowed normalization give `:unknown` "disallowed
  key", `:must-read`/`:evidence` "invalid type" and `:id` "should be a string". `#{}` at both sets
  gives no explanation. Each probe took 5 ms.
- **P1-2, competing edit.** A `d/with` competing intent "C competing." against the basis held
  before it made `task-delta` refuse `:revision-conflict` with base, current and proposed. The
  unchanged head gave a 2-op replacement.
- **P1-2, deletion evidence.** On default the history query for retracted `:seon.task/id` values
  returned `[]` in 1.3 ms, with `:keep-history true`. After a `d/with` `retractEntity` of
  `300aca1ad57c` it returned `["300aca1ad57c"]`.
- **P1-3, combined-edge function.** Run in scratch namespace `fix-ingestion-p1s.scratch` over
  `d/with` candidates in 51 ms:
  - a new handle on a merged function gives `{"aaaaaaaaaaaa" #{seon.fn/gate-set}}`;
  - with nothing merged, the result is `{}`;
  - a destination missing its task keeps its own holders.

  The edge query took 9.8 ms over default (6 edges).

## Size

- Source: `issue.clj` +56/−22, `source.clj` +17/−9. That is +42 net across the three P1s,
  including docstrings.
- Tests: `task_ingestion_test.clj` +57/−17, `source_test.clj` +52/−1.

## Root runs (serially, quiet window)

```
bin/test-check default --ns seon.issue.task-ingestion-test
bin/test-check default --test seon.cluster.source-test/a-merge-naming-a-task-the-destination-lacks-refuses-and-an-admitted-task-survives-a-refused-merge
bin/test-check default --ns seon.cluster.merge-test
bin/test-check default --ns seon.cluster.source-test
```

The merge-test run is there to show `accept-merge!` still merges a program that names no tasks.
After adoption, also check `(seon.issue/task-findings db)` on default: it should still report
`{:dangling {} ...}`, and default's three tasks should survive the next publication and adoption.
