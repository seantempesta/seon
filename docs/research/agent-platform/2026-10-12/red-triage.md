---
title: "Red triage: tests red on HEAD, 2026-10-12"
type: research
status: evidence
created: 2026-10-12
---

# Red triage, 2026-10-12

Lane `red-triage`. Question: which tests are red that lanes excluded by hand today, what changed each one's behavior, and
which class each belongs to (A delete, B update the retired assumption, C fix the surviving owner, D flaky or collision
task, E unknown, needs a run). Read-only on src, test and resources.

## Sources and limits

- **Stored runs on default (main's database, read at 2026-10-12, jvm root, query only).** 800 member results across all
  runs on main, 163 tests with results, **20 whose latest result is red** (fail or error). Latest is the run with the
  greatest `:seon.test.run/at`. Only main's runs are visible; agent-branch runs were not queried.
- **Commit messages since 2026-10-12 08:00** (`git log --since`), searched for "red", "pre-existing", "not reached".
- **central-round.md** (cd59c9fa3, a fresh-JVM run of every green test at c53c9306e). It attributes causes C1-C9 by
  symptom and suspect commit. Several of those causes may have landed fixes since (83acdfbf3, 88e7651da, e71e60a47,
  de56c2570); the stored runs are the only evidence about the current state, and they predate or ignore some of them.
- **Git blame.** For each test: the file that holds its deftest (`git log -1 -- <test file>`) and the commit that
  added or last changed its body (`git log -S<name> -- test`). Blame shows where the test last changed, not what
  changed its outcome; "suspect" below means a commit that touched the code the assertion reads, not a bisect.
- **Not run.** No test was run in this lane. Every class E row needs a run on a fresh root before it can move.

Discrepancy with the request: commit de56c2570 (CN-15, `with-branch`) does not mention seon.call-preparation-test or
seon.cluster.host-test reds. Those names appear in e71e60a47 (call-preparation: a genuine compiled red of the custody
split, not a test named red) and in 483b3e9b6 and central-round.md (host-test). No grep of test/ or src/ finds a
remaining reference to `call-with-custody`, `call-without-custody`, `squash-merge!`, `selected-coordinates` or `copy!`,
so the de56c2570 rename left no caller behind.

## A. Stored-run reds on main (20 tests)

| # | Test (namespace/deftest) | Recorded failure (latest run) | Commit most likely changing behavior | Class | Confidence |
|---|---|---|---|---|---|
| 1 | `chain2.proofs/design-prove-wrote-the-implement-proof` | fail 1: `(seq (:seon.task/proofs …))` on a chain experiment proof | 606e5c198 / 1b896748b (chain experiment 2) | E (intended red: a task proof, not a deftest; exclude from the red count) | high |
| 2 | `chain3.proofs/design-prove-tagged-the-implement-proofs` | fail 1: same shape, `seon.sci.forgive/idempotence` | f06521aff (chain experiment 3) | E (intended red, as #1) | high |
| 3 | `my.program-test/a-function-called-through-its-callers-is-reached-by-their-tests` | fail 1: `(reaching 'seon.render.value/classify)` is `#{}` | b1b94c0d6 retired its task (proof green in 98720b4eb); deftest kept as regression | E | low: check whether the stored run predates 98720b4eb |
| 4 | `seon.cluster.db-flow-test/a-prove-step-admitted-by-its-successor-opens-a-model-turn` | fail 2 error 1: `ledger` count 0, not 1; elapsed 21,084 ms over the 5,000 ms bound; error "its fresh attempt opened a model turn" | 6a687ac17 (a fresh attempt opens the model turn), f54bc0437 (task retired, deftest kept untagged) | B (the deftest asserts the retired rule that a successor's Prove step opens no turn) | medium |
| 5 | `seon.cluster.registry-test/a-deleted-attempts-storage-is-reclaimed-by-the-next-collection` | error 1 | central-round C1 family (a write to a store with no declaration rows is refused for want of a carried projection); registry.clj last changed d0ebba656 | C: `seon.db/a-write-to-a-store-without-declaration-rows-takes-the-projection-its-caller-hands` | medium |
| 6 | `seon.cluster.turn-test/acquisition-orders-agent-authored-refer-targets-and-ignores-alias-cycles` | fail 1: installed count 17, expected 6 | 172b23e7c (E4: a fork carries only the agent's own namespaces) changes the installed count it asserts | E (if the new count is intended, B) | low |
| 7 | `seon.context-cost-test/one-agents-current-state-costs-only-its-attachments` | fail 1: jvm-window alloc 63.4 MB against < 50 MB | 9e7958e30 (P4 slice 2: an agent's tasks and end written in its own world) | E (cost regression; owner unknown) | medium |
| 8 | `seon.repl-differential-test/promoting-a-closure-costs-its-walk-and-the-callers-called` | fail 2: hub promotion 1,529 ms against < 1,000 ms | e1c424db5 (overlay source namespaces from the database it acquires) is the nearest change to the promotion path | E (cost regression) | low |
| 9 | `seon.sci.eval-test/database-provenance-regenerates-the-base-and-reverts-for-retained-forks` | error 1 | central-round C2: `seon.db/q` gets a nil database in `seon.sci.eval/interpret-source-namespaces!`; 7fea1f2e7 touched this file today | C: `seon.sci.eval/a-changed-programs-overlay-reads-the-agents-database` | medium |
| 10 | `seon.start-flow-benchmark-test/commit-to-paused-flow-costs` | fail 2: cold sci 1,365.8 ms against 1,300 ms; cold branch 8.2 MB against 4 MB | central-round: still red; d02a78686 changed accounting, not flow start | E (cost; the number is a finding) | medium |
| 11 | `seon.test-failure-facts-test/a-changed-reach-member-retracts-only-itself` | fail 2: datoms `[:seon.test/reach]` not as expected | 474174b52 notes this red as present on its parent ("red on HEAD too, not named") | E (predates today) | low |
| 12 | `seon.test-failure-facts-test/an-unchanged-re-record-emits-no-datom` | fail 4: `[2]` not as expected | as #11 | E | low |
| 13 | `seon.test-provenance-test/runs-are-distinct-immutable-and-linked-atomically` | fail 5: second provenance run string | body last changed 2026-09-28 (aad8ed0d9); run recording moved since | E | low |
| 14 | `seon.test-provenance-test/verified-requires-the-subject-positive-assertions-and-exact-program` | error 1: uncaught exception outside an assertion | as #13 | E | low |
| 15 | `seon.test-reaching-test/two-branches-of-one-program-share-reach-declarations` | error 1 | 50ca41030 (R1: selection reuses a result at its key) is the nearest change to reach selection | E | low |
| 16 | `seon.test-test/one-interpreted-test-run-allocates-a-bounded-multiple-of-its-body` | fail 1: allocation bound | 474174b52 (R2: measured body records dynamic calls) adds work per run | E (cost) | low |
| 17 | `seon.test-test/recording-distinguishes-run-replay-from-a-new-event` | fail 1 | 50ca41030 (R1: a red is answered from its record) changes what a replay records | E | low |
| 18 | `seon.test.reach-test/a-digest-follows-its-closures-content-and-nothing-else` | error 1 | 474174b52: the file has three reds red on its parent, "a fixture names a test absent from the check's store" | D (fixture depends on store state) | low |
| 19 | `seon.test.request-acquisition-test/a-member-installs-its-program-unobserved-by-the-calling-evaluation` | error 1 | body last changed 73490175c (2026-09-28, observers) | E | low |
| 20 | `seon.test.runner-test/a-program-change-reads-only-the-reach-attributes-it-wrote` | error 1 | 346645d56 (M1) changed the same test file | E | low |

## B. Named by the request or by today's commit messages, not in stored main runs

| # | Test | Source of the red | Commit most likely changing behavior | Class | Confidence |
|---|---|---|---|---|---|
| 21 | `my.task-test/current-answers-the-held-steps-instructions` | 9116692ea: red on HEAD, not reached by it | ea27cde53 (a held step's instructions reach its agent), 1b896748b | E (may be the proof of ea27cde53; needs a run) | medium |
| 22 | `seon.error-test/a-guessed-schema-key-is-answered-with-the-nearest-registered-schemas` | 9116692ea | none found since 9116692ea | E | low |
| 23 | `seon.instrument-test/a-sovereign-sci-fork-refuses-by-throwing-under-both-dials` | 9116692ea | 9116692ea edits the arity refusal | E | low |
| 24 | `seon.instrument-test/refusal-value-projection-obeys-the-profile-in-both-faces-and-the-whole-value-stays-retrievable` | 9116692ea | as #23; the refusal projection is the printer's | E | low |
| 25 | `seon.instrument-test/registry-sized-contract-evidence-retains-the-offending-object` | 9116692ea | as #23 | E | low |
| 26 | `seon.run6-db-test/database-read-arities-refuse-stray-inputs` | 9116692ea | none found since | E | low |
| 27 | `seon.call-preparation-test` (any red) | e71e60a47: world split, supply-from-world read the member's own branch; the commit names error-test, not call-preparation-test | e71e60a47, a74fd3031, 4347f6cce (test-member world) | C: `seon.call-preparation/supply-from-world` (custody read) | low |
| 28 | `seon.cluster.host-test/a-hosted-run-lands-on-its-branch-and-the-parent-head-is-unchanged` | 83acdfbf3: random digit-leading fixture handle, 10 of 16 | 83acdfbf3 (fix), 88e7651da (test-support classes keyed by test tree) | D (fixed in 83acdfbf3; needs one run to confirm) | medium |
| 29 | `seon.cluster.host-test/a-message-to-a-stopped-admitted-agent-hosts-it-and-its-db<->flow-takes-it` (and `retirement-closes-admission-at-the-writer-and-races-submission`) | 3780e1107 parent reds, reproduced on the exact parent | central-round C9: `seon.cluster.agent/a-proc-step-runs-in-its-environments-world-again` | C (owner `seon.cluster.agent`) | low |
| 30 | `seon.cluster.agent-test/pause-during-in-flight-call-test` | named by the request; no commit message mentions it | not found in git history | E | none (not in test/ by the name searched; needs a run) |
| 31 | `seon.cluster.turn-test/a-batched-turn-commits…` | named by the request | central-round lists other turn-test reds (C9 family); this one not named | E | none |
| 32 | `seon.sci.eval-test/acquisition-binds-loaded-first-party-compiled-vars` | 7fea1f2e7: red on HEAD fada01e55 alone, "unchanged by this slice" | seon.sci.eval acquisition (C2 family) | C (owner `seon.sci.eval`) | low |
| 33 | `seon.source-census-test` (any red) | named by the request; 0526cc0ad retired two source-census tasks | 0526cc0ad | E | none (needs a run) |
| 34-36 | `seon.db-test/a-store-without-program-schema-admits-its-writes`, `…a-definition-write-keys-its-projection-by-its-own-datoms`, `…a-retained-branch-reads-with-the-jvm-builder-not-its-recorded-program` | central-round C1/C5 and aee027806 (new regression, stored digest of `seon.schema/shape-entity-in` after d02a78686) | d02a78686, c566e0401, d281616fd | C: `seon.db/a-write-to-a-store-without-declaration-rows-takes-the-projection-its-caller-hands` (first two); `seon.schema/shape-entity-in` (third) | medium |
| 37 | `seon.test/two-tests-never-share-a-test-world` (task) and the tq-chain collision in `seon.cluster.db-flow-test` | 2de748e41: "two tests never share a test world (g1's tq-chain collision)" | 2de748e41 | D (collision; the task is filed) | medium-high |

## Counts by class

Counted per test. Rows 1-20 are the stored-run reds (one test each). Rows 21-33 are one test each, row 34-36 are three
db-test tests, row 37 is one task. Confidence is the evidence's, not the class's.

| Class | Tests | Rows |
|---|---|---|
| A (delete: machinery deleted) | 0 | None found. No red names a symbol that no longer exists. |
| B (update: retired assumption) | 1 | #4 |
| C (fix surviving owner) | 8 | #5, #9, #27, #29, #32, #34, #35, #36 |
| D (flaky or collision) | 3 | #18, #28 (fixed by 83acdfbf3), #37 |
| E (unknown, needs a run) | 25 | #1-#3, #6-#8, #10-#17, #19-#26, #30, #31, #33 (#1 and #2 are intended red chain proofs, not deftests) |

Total: 37 tests; 0 A, 1 B, 8 C, 3 D, 25 E. Excluding the two intended chain proofs, the real red count is 35.

## Owners that would unblock the most lanes

Counts are from central-round.md (cd59c9fa3) unless marked. Some may have been fixed since; a fresh-root run confirms.

1. **`seon.sci.eval/a-changed-programs-overlay-reads-the-agents-database`** (C2, 37 tests). Every defn's automatic test
   step answers `:seon.turn.loop/test-error`, observed live on default at cd59c9fa3. This blocks every lane that writes
   a defn, not only the tests listed here. Suspect bdf7411b5 with 4f81411fa.
2. **`seon.db/a-write-to-a-store-without-declaration-rows-takes-the-projection-its-caller-hands`** (C1, 48 tests),
   covering the stored registry-test and db-test reds (#5, #34). Suspects: test-member world cut 4347f6cce, 1d2d57a62,
   b53cbeb38, a74fd3031; Datahike bumps c566e0401, d281616fd.
3. **`seon.test/a-member-body-reads-the-world-its-seeds-prepared`** (C4, 11 tests). Test-runner semantics: seeds, redefined
   callees, reaching selection. It shares suspects with #2, so re-run after #2 lands.
4. **`seon.config/a-reapplied-manifest-converges-with-zero-operations`** (C3, 7 tests). Re-applying an applied manifest is
   not converged; no stored-run red depends on it, so it unblocks only the config lanes.
5. **`seon.test/two-tests-never-share-a-test-world`** (D, one collision task, 2de748e41). Small and unblocks the
   tq-chain tests in db_flow_test. Alternates: `seon.db/an-unrelated-entity-write-keeps-a-reads-evidence-current` (C5, 6 tests).

## Findings for the owner

- Two stored reds are intended task proofs (#1, #2). They should be excluded from the red count; their presence in
  `:seon.test` results makes the scoreboard noisier.
- Four stored reds are cost assertions (#7, #8, #10, #16) and one is a timing failure (#4). Per the owner's rule (cost
  regressions fail tests), these stay red until their owners fix the cost; none is a timeout to raise.
- `seon.test.reach-test` and `seon.test-failure-facts-test` have reds that 474174b52 says predate that commit. Nobody has
  filed them under their own task. Two tasks should be filed with these rows as their red proofs.
- This query recorded one eval request on main (a transaction for the evaluation record); no test ran and nothing was
  written to the tests' results.

## Next steps

- Run the stored-run reds (#3-#20) on a fresh root, one batch, to move E rows to A-D.
- Run #21-#33 by name once. Their absence from stored runs is itself a finding: either they were never run on main or
  their results were excluded.
