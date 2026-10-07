---
type: notes
status: landed
created: 2026-10-07
lane: test-cost
---

# test-cost: one named test check costs seconds around a ~100 ms body

Lane test-cost, 2026-10-07, HEAD 77dbdd38b (default pid 41301, then 83524 after root's 09:08 restart).
Task: `seon.test/one-test-run-costs-seconds-around-a-147-ms-body`; related
`seon.test/a-changed-check-builds-a-world-per-request`, `seon.test.runner/admission-filters-the-whole-program-history`.

## Where the time went (one cold `my.test/check` of one named test, default, agent test-cost)

Probe: `(my.test/check {:seon.test/changed ['seon.id-test/a-host-object-has-no-identity]})`, 2,288 ms, body 23 ms,
161k armed calls; allocation 5.4 GB total-thread (`getTotalThreadAllocatedBytes`, idle JVM measured 0 MB/2 s).
Profile (`seon.profile/begin`/`explain`) plus a 5 ms stack sampler on the evaluating thread:

| Stage | ms | What it iterates over |
|---|---|---|
| selection (`seon.test/select`) | 88-675 | `changed-definition-symbols`: history-since scans over 13 program attributes (whole history, not the datoms since the basis); `definition-digests` x2 incl. an as-of read; `reach-digests` -> `reach-declarations` (10 whole-attribute `datoms` reads) re-derived per commit |
| world (`clean-request-handle`) | 1,941 | `fork-cluster-ctx` -> `acquire!` 1,757: the request branch is a new commit on a new connection, so its program identity (a revision basis keyed by connection) never equals anything acquired; `derive-base-ctx` 810 (whole-program `function-rows` query, `seon/sci/eval.clj` `function-rows`, ~110 samples) plus `load-core-namespaces!` whole-program query on the cold overlay connection (~89 samples, ~600 ms) |
| member run | 23-110 | the test body |
| recording (`commit-results!`, `admit-run`) | 33-130 cold, 628 in a fresh check JVM | `program-written-since?`: the same history-since scan as selection |
| reply | ~0 | |

So the overhead is proportional to the program (every function row, every reach datom, the history of program
attributes), never to the named test's reach. The overlay for root on default holds 4 rows (namespaces `user`
and `my.agents.root`), so even an agent with no program change paid a whole-program acquisition per request.

Finding for root (defn-cost lane, same cause): the per-request whole-program acquisition is
`seon.sci.eval/acquire-program!` -> `function-rows` (`src/seon/sci/eval.clj:2294`) and `copy-host-var`
(`src/seon/sci/eval.clj:236`, x4,166 per derive), plus the `load-core-namespaces!` query
(`src/seon/sci/eval.clj:1708`). This lane removes it from the test path by not acquiring; the derive itself stays
whole-program wherever it runs.

## The fix

1. **The world reuses the candidate's acquisition** (`seon.test/clean-request-handle`, new `seon.sci.eval/program-equal!`).
   The overlay makes the world's program the candidate's by construction (`program-overlay-tx` puts every
   `digest-differences` row of the candidate onto the base; config is seeded from the candidate's effective config).
   `program-equal!` records that: the world's commit takes the candidate's program identity in the existing
   `program-identity` memo, the documented rule "a commit proven program-equal to an acquired value takes that
   value's basis". `acquire!` then answers at once when the execution's context acquired the candidate, and
   otherwise derives under the candidate's identity, so later requests from the same program hit `base-ctx`.
   Nothing new is cached; the existing memo holds one more key per request commit.
2. **Reach declarations keyed by revisions** (`seon.test.runner/reach-declarations`, new `reach-revision-key`).
   The ten-attribute read was cached by commit id, so every recording commit re-derived it and kept another
   whole-program copy (health showed the kind at 221 MB, 40% hits). It is now keyed by the connection, generation,
   conservative revision and those ten attributes' Datahike revisions, the rule `seon.render/call-reach-key` and
   `seon.db` projection keys already use: a results-only commit keeps the key.

## Numbers (same probe, throwaway check JVM, parent vs change)

Probe: a disposable test (never committed) running `seon.test/run` `:named` `:seon.test/changed [t]` twice per test
from a fixture branch, cold then warm, wall ms and total-thread allocated MB.

| test | phase | parent ms | parent MB | change ms | change MB |
|---|---|---|---|---|---|
| seon.id-test/identity-is-a-function-of-the-values-structure | first request in the JVM | 1,690 | 4,791 | 2,649 / 3,358 | 6,717 / 6,764 |
| same | warm (reused) | 178 | 267 | 132 | 185 |
| seon.task-ingestion-test/a-task-whose-handle-changes-keeps-its-symbol | cold (never run) | 1,170 | 2,917 | 584 / 702 | 817 / 822 |
| same | warm (reused) | 167 | 277 | 126 | 191 |

The first request in a fresh JVM/lineage is unchanged in kind: its execution context (the fixture's) is not
acquired at the candidate, so it derives once (`derive-base-ctx` 993 ms) and prepares the checkpoint; the two runs'
difference (1.7 vs 2.6-3.4 s) is first-in-JVM variance, not this change (same stages, same derive count). Every
later cold request with an unchanged program now skips acquisition (`fork-cluster-ctx` 61 ms vs 1,512-1,896 ms).
On default, an agent whose own context acquired its branch takes the fast path from its first check.

Targets not met: cold 584-702 ms vs 300 ms target; the remainder is selection's history scans
(`changed-definition-symbols` 100-150 ms), admission's `program-written-since?`, transactions (6 x ~30 ms) and the
first derive per program. Filed below; the history scans are a design change in selection/admission (carry
attribute revisions in provenance), which I stop at.

Default was not re-measured with the change: it runs HEAD; root's restart after landing loads it.

## Regressions (deterministic, no timing)

- `seon.test.overlay-world-test/a-request-world-reuses-the-executions-acquired-program`: the request world's
  context holds the execution context's acquisition (`identical?`), i.e. no reacquisition. Red on parent
  (check 0ae626332a00), green on the change.
- `seon.test-reaching-test/reach-digests-follow-only-changed-closures` gains: a data write derives no new
  `::reach-declarations` entry for the connection. Red on parent (59b21d96da90, line 111), green on the change.

## Findings and tasks

- `seon.test-support/sci-declaration-fixtures-read-a-retired-row-key` (created): `seon.test.selection-test/fileless-sci-tests-use-the-same-selection`
  is red on HEAD (b6ba1bfe2573) and `seon.test.overlay-world-test/an-agent-branch-request-runs-its-candidate-on-a-clean-world`
  errors in its `admit!` helper; the latter is the only proof that a changed callee runs on the test world, so that
  property of slice 1 rests on the construction argument above until it is converted.
- `seon.test/selection-reads-the-whole-history-since-the-last-run` (created) with
  `seon.test.runner/admission-filters-the-whole-program-history` (existing).
- `my.program/supplied-context-is-not-supplied-its-environment` (created): `(my.program/supplied-context)` from SCI
  refuses arity 0 instead of supplying its world member.
- A cold check allocates 2.9-6.8 GB (total-thread): the whole-program derive; same cause as the defn-cost lane.
- Defining a function in the same evaluation as `my.test/check` answers `:seon.test/program-mismatch` after 2 s and
  3.8 GB (the definition moves the program between selection and admission): expected refusal, but its cost is the
  derive above.

## Design check

- Held against the code: the identity memo and `acquired-database?` already define program equality by recorded
  identity per commit; the change only states an equality the overlay establishes. Awkward: the equality is
  asserted by construction, not re-proven (re-running `digest-differences` costs 25-137 ms); and an empty overlay
  makes the world commit the shared checkpoint head, which concurrent candidates may each claim. Every claimant is
  program-equal to that commit, so any claimed identity is a true one; a mismatch only sends `acquire!` down its
  derive path.
- One happy path: serves the eval -> record step of `my.test/check`; deletes per-request acquisition and per-commit
  reach derivation; no new mechanism, cache or atom.
- No RESET NEEDED. Restart needed to load the landing (compiled `seon.sci.eval`, `seon.test`, `seon.test.runner`).
