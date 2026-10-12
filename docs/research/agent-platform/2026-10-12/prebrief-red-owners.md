---
title: "Pre-brief: four owners that block the most red tests, checked against HEAD"
type: research
status: prebrief, 2026-10-12. Read-only; no source, test or resource was edited.
basis: HEAD 1b09d8043. Central round on HEAD (cd59c9fa3 successor) has not landed (no central-round-head.md). Stored runs read on default, jvm mode, root, query only.
plan: docs/research/agent-platform/2026-10-12/red-triage.md (cf8b69549), "Owners that would unblock the most lanes"
---

# Pre-brief: the four owners ranked by red-triage

## Headline correction to red-triage

red-triage ranks four owners as live blockers. Three of them are no longer live as tasks, and the ranking was built on the
round at cd59c9fa3, which the later round superseded:

- The task rows and their tagged proofs for all four were **retired** in commit aee027806 (central round 3 on 2319152dc).
  Round 3 reports all 125 round-1 regressions green. In `git show aee027806` the removed rows are
  `seon.config/a-reapplied-manifest-converges-with-zero-operations`, `seon.sci.eval/a-changed-programs-overlay-reads-the-agents-database`
  and `seon.test/a-member-body-reads-the-world-its-seeds-prepared`. The C1 row
  (`seon.db/a-write-to-a-store-without-declaration-rows-takes-the-projection-its-caller-hands`) was retired in 5d7567be1.
- A search of the whole tree (`src`, `test`, `resources`, `script`, `docs/seon`) finds none of the four names outside
  `docs/research`. There is no tagged deftest left to run by tag.
- So red-triage's "still red at HEAD" is unproven. The honest state: each owner was green at 2319152dc; whether it is red
  now needs one per-test run on HEAD, or the central round when it lands.

Red-triage's row counts (37, 48, 11, 7) are the cd59c9fa3 counts. Rank by the new evidence, not by those numbers.

## Stored runs: what they can and cannot say

- Member entities carry `:seon.test.member/symbol`, `pass-count`, `fail-count`, `error-count`. I queried them on default.
  25 members have a non-zero fail count and 23 a non-zero error count. The counts are per member entity; the query did not
  establish which run wrote them, so these are not "latest result" evidence. Treat them as "red at some recorded point".
- None of the four owners' test names appears in the failing or erroring lists by its owner name. The owner names are task
  names, not test symbols; the reds are reached through the deftests that carry them.
- Erroring members that touch the four owners' families:
  `seon.sci.eval-test/database-provenance-regenerates-the-base-and-reverts-for-retained-forks` (sci.eval family),
  `seon.cluster.registry-test/a-deleted-attempts-storage-is-reclaimed-by-the-next-collection` (db family),
  and the test-runner family `seon.test-test/same-test-symbol-on-two-branches-runs-each-bodys-content`,
  `seon.test-reaching-test/two-branches-of-one-program-share-reach-declarations`,
  `seon.test.runner-test/a-program-change-reads-only-the-reach-attributes-it-wrote`,
  `seon.test-failure-facts-test/a-rethrown-error-records-each-execution`.
- No `seon.test.seed-preparation-test` member and no `seon.config` reconcile or config-test member appears in the stored
  failing or erroring lists. That is weak evidence the seed and config families are green on their latest runs.

## 1. `seon.sci.eval/a-changed-programs-overlay-reads-the-agents-database` (C2 family, was 37 tests)

**Status at HEAD:** task retired (aee027806); green at 2319152dc in round 3. Not known red now.

**Owner and callers.** The overlay path runs `seon.sci.eval/interpret-source-namespaces!` (private; called from the overlay
derivation) and `seon.sci.eval/derive-overlay-ctx` (private; its one src caller is the overlay derivation; the test
`seon.sci.eval-test` asserts its call count; `script/start_flow_benchmark.clj` names it). The database for a branch comes
from `seon.sci.eval/context-database`, used by the namespace-entry path, the commit path and the base-evaluation path.

**Suspects and what came after round 3.** The round-2 suspects were bdf7411b5 (`derive-overlay-ctx` sets `::custody {}`)
and 4f81411fa (`context-database`). Both are ancestors of 2319152dc, so round 3 already ran with them. Changes after round 3
that touch the same path: 35e906c38 (overlay key and weight), 786915ca3 (an overlay's key is its content, not entity ids; the
program-context key fix), 172b23e7c (E4: an unchanged program forks nothing, a moved program forks once), 2eafd5a28 (query
patterns derived once). Any red now is more likely a post-round-3 regression in these than the retired C2 cause.

**Tests that reach it.** `seon.sci.eval-test` (the overlay and provenance tests), `seon.repl-differential-test` (promotion and
inherited callers), `seon.cluster.turn-test` and `seon.test-test` (a defn's automatic test step goes through the overlay).
The earlier symptom (every defn answers `:seon.turn.loop/test-error`) was observed at cd59c9fa3 only. Confirm it on HEAD with
a throwaway agent, never root on default.

**Gotcha.** A fix here must add a tagged red proof again: the retired task left no proof behind.

## 2. `seon.db/a-write-to-a-store-without-declaration-rows-takes-the-projection-its-caller-hands` (C1 family, was 48 tests)

**Status at HEAD:** task retired (5d7567be1, "C1 48 retired"); the fix e0bd37443 (`seon.db/cost-upserts` receives the basis
projection as a `:db.fn/call` argument) is an ancestor of round 3, which reports green. Not known red now.

**Owner and callers.** `seon.db/cost-upserts` (callers: `seon.db` transaction assembly with `basis-projection`, and
`script/astra_cost_handoff.clj`); `seon.db/carried-projection` (about twenty src callers in `seon.fn`, `seon.schedule`,
`seon.call-preparation`, `script/` and `test/my/program_mutation_test`); `seon.db/with-branch` (renamed from
`call-with-custody` in de56c2570; callers in `my.agent`, `seon.task`, `script/seon/central_round`, `seon.db-test`,
`seon.test.one-request-test`, `seon.test.effect-attempt-test`, `seon.task-ingestion-test`).

**Stored signal.** `seon.cluster.registry-test/a-deleted-attempts-storage-is-reclaimed-by-the-next-collection` has a recorded
error count. Its one-test run on HEAD decides whether it is a post-round-3 regression.

**Suspects.** e0bd37443 and 5d7567be1 are before round 3 and were green there. Round 3's green list does not cover
the `seon.db-test` tests that aee027806 filed (`a-retained-branch-reads-with-the-jvm-builder-not-its-recorded-program`,
stored digest of `seon.schema/shape-entity-in` after d02a78686); that one is a separate open task. The post-round-3 change
to the write path is de56c2570 (`with-branch` rename, CN-15), so a red in its callers is a rename or caller miss to check
first.

## 3. `seon.test/a-member-body-reads-the-world-its-seeds-prepared` (C4 family, was 11 tests)

**Status at HEAD:** task retired (aee027806); green at 2319152dc. The stored reds in the test-runner family are not the
retired cause; they follow commits that landed after round 3.

**Owner and callers.** The owner is the seed-and-body path of `seon.test` (member world built from seeds, then the body in
its own world; 4347f6cce moved the member body out of any requester's world). Tests: `seon.test.seed-preparation-test`
(four deftests), `seon.test.redefined-callee-test`, `seon.test-reaching-test`, `seon.test.runner-test`.

**Post-round-3 suspects, which are the real current candidates.**
- 50ca41030 (R1: selection reuses a result at its key, red and error included). Nearest change to
  `seon.test-reaching-test` and `seon.test-test` reds.
- 474174b52 (R2: a result is keyed by what its body executed). Adds per-run work; nearest to
  `seon.test-test/one-interpreted-test-run-allocates-a-bounded-multiple-of-its-body`.
- 346645d56 (M1: a proof judged at its fork point; `:satisfied` opens no model turn). Nearest to
  `seon.test.runner-test` and `seon.cluster.db-flow-test/a-prove-step-admitted-by-its-successor-opens-a-model-turn`.

**Working-tree collision.** `src/seon/test.clj` has an uncommitted edit (`git status`, M). It is in the `root-drift`
claim area. Any fix to `seon.test` waits for that edit to be committed or released.

## 4. `seon.config/a-reapplied-manifest-converges-with-zero-operations` (C3, was 7 tests)

**Status at HEAD:** task retired (aee027806); green at 2319152dc. No commit after round 3 touches `seon.config` in `src/seon/config.clj`.
Lowest risk of the four.

**Owner and callers.** `seon.config/apply!` (callers: `script/seon/operator.clj` in a comment; `seon.config` itself at
its manifest path; tests `seon.cluster-test`, `seon.transaction-result-test`, `seon.data-shapes-test`, `seon.shell.jvm-test`).
The reconcile it relies on is `seon.fn/reconcile-tx` (callers in `seon.task-ingestion-test`, `seon.program-test`,
`seon.cluster.merge-test`).

**Tests that reach it.** `config-test` (four deftests), `reconcile-test` (`first-apply-writes-and-reapply-is-zero-writes`),
`index-reconcile-test`. None appears in the stored failing lists.

## Filed tasks and proofs

- The four owner rows: retired; no proof left in the tree. Nothing to run by tag.
- `seon.test/two-tests-never-share-a-test-world` (2de748e41): present in `resources/seon/tasks/seon.test.edn`. It has an
  intent but no deftest tag found by search, so its proof is unverified. red-triage named it as the small unblocker; it still
  needs its own tagged proof before assignment.
- The post-round-3 test-runner reds (rows 11–20 of red-triage) have no owner task. They need one each, with their deftest as
  the red proof, before any owner is assigned.

## Prior attempts and gotchas

- The owners were fixed once and then re-observed red on a different commit. The lesson from round 2 to round 3 is that the
  retired rows were green in the round that retired them, and red-triage's live reading came from an older round.
- Stored counts are per member, not per run, and carry no run stamp in the query. A "stored red" today is a reading from an
  unknown point, not a HEAD result.
- Two intended chain proofs (`chain2.proofs`, `chain3.proofs`) are red by design and should not enter any red count.

## Collisions with running lanes

`bin/lane status` shows these claims (path lists not expanded by the summary):

- `m5` (17 paths) and `reach-incr` (4 paths): the M5 lifecycle and the reach increments both touch the agent and test
  paths in the fix areas above. Do not claim `src/seon/test.clj` while `m5` or `reach-incr` hold it.
- `root-drift` (8 paths): holds `src/seon/turn.clj`, `src/seon/test.clj`, `test/seon/turn_test.clj`, the turn task file and
  `resources/seon/schemas/seon.turn.loop.edn`, per prebrief-m5-m7.md. It has uncommitted edits.
- `cache-weights-2` (6 paths) and `error-locals` (3 paths): not in the owners' paths as far as the summary shows.

## Riskiest step, and how HEAD stays working

The riskiest step is changing `seon.sci.eval` or `seon.test` before the HEAD round says which owner is red. A fix there that
is based on the round-2 reading (a defn's test step answers test-error) would change the overlay path with no proof of its
current state. Keep HEAD working by:

1. Running the candidate tests once on HEAD (one test per request, fresh JVM) before any edit. The central round on HEAD
   does this; its output decides which of the four is live.
2. Adding a tagged red proof for each live red before the fix, at its producer, and committing the proof alone.
3. Committing each fix only with `bin/lane commit --test` naming the tests that reach it, so HEAD loads and the proof turns
   green in the same commit.
