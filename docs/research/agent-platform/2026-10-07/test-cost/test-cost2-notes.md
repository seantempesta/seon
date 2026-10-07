---
type: notes
status: landed
created: 2026-10-07
lane: test-cost2
---

# test-cost2: one named test check without whole-history scans or repeated reach walks

This lane continues lane test-cost (`test-cost-notes.md`, commit 4c9ccb3f7). Default was pid 83524, which runs the code
from before 4c9ccb3f7. `runtime_status` reported `:faulted` with 1 open fault, 4 errored receipts and 7 over-bound
warnings, all from other owners.

Data flow: a `my.test/check` is a `seon.test/run` request. Its steps are:

1. `seon.test/select` compares the request with the branch's last completed run and walks reach to pick the members.
2. `seon.test.runner/provenance` stamps the tested value.
3. `seon.test/admit-run` reserves the members at the writer.
4. The members run.
5. `seon.test.runner/commit-results!` records the results, which reads reach again.

The approach throughout is to compare Datahike's attribute revisions, which the program value already carries, instead
of scanning history or walking the graph again.

## 1. Fixtures (commit 6098f0359)

The two fixtures read `:seon.program/row` from an evaluation. Commit 5e22bc5f6 retired that key, when evaluate lost
admission. The fold owns program facts now. The fix is `seon.test-support/define!`, which runs the source through the
one path:

1. `code-request`
2. `seon.batch/evaluate-batch`
3. `seon.turn.batch/program-facts`
4. one `transacted!`

It throws at a form's error or at refused facts.

A second producer bug sat under it: `support/code-request` framed every form with `:seon.cluster.eval/source ""`, and
`seon.sci.eval/evaluate` refuses that. So every caller of `code-request` was broken (task
`seon.test-support/code-request-frames-an-empty-source-every-form-is-refused`, closed in that commit). The frame now
carries the run's text, as `support/evaluate`'s frame does. It does not carry each form's own text: the frame
function receives no text.

The overlay world test needs a world built from the execution's own context. `agent-world` forks its `base-ctx` onto
the connection. A fresh `sci.eval/base-ctx` carries an environment without the execution's routing, so the fork
re-carried the connection's projection state without it. `support/execution-handle` then fell back to the member's
handle, and the run did not see the fixture's test. The fix is to pass the handle's own context as the base.

The selection test now defines its fileless test in a scratch namespace. Defining it in the file namespace
`seon.test.selection-test` is a real defect: an agent's `deftest` there gets its program facts refused for a
definition-digest mismatch. I reproduced it on default from agent test-cost2. It is task
`seon.turn.batch/a-definition-in-a-file-test-namespace-refuses-its-produced-digest`, in the same class as
`seon.program/sci-scratch-definitions-refuse-their-produced-digest`.

Proof (check runs):

- `seon.test.overlay-world-test` (4 tests) and `fileless-sci-tests-use-the-same-selection`: 5 executed, 16 pass.
- With `seon.error.attribution-test` and the new `seon.test-support-test/a-code-request-batch-evaluates-each-form`:
  11 executed, 43 pass.
- Both target tests were red on HEAD 4c9ccb3f7 (`check-head.log`).

The runtime task `seon.test-support/sci-declaration-fixtures-read-a-retired-row-key` is proven by these two tests.
Its third instance, `seon.test-reaching-test/agent-admitted-tests-reach-their-tested-function`, was converted to
`define!` in the second commit.

## 2. Revisions in provenance

Tasks: `seon.test/selection-reads-the-whole-history-since-the-last-run` (persisted in
`resources/seon/tasks/seon.test.edn`) and `seon.test.runner/admission-filters-the-whole-program-history`.

Measured on default (JVM mode, its own basis 108 transactions after the last run):

| call | ms | MB | answer |
|---|---|---|---|
| `changed-definition-symbols`, cold | 930 | 681 | 52 symbols |
| `changed-definition-symbols`, warm | 99 | 114 | |
| `program-written-since?` | 1,872 | 1,580 | true |
| `program-revisions` | 1 | 0.06 | |

The design:

- **Provenance.** `seon.test.runner/provenance` adds `:seon.test.run/program-revisions`. It is
  `program-revisions-digest`, a structural digest of `program-revisions`: connection id, generation, conservative
  revision and the revisions of every attribute the program digest reads. It is stored on the run (new optional
  attribute in `seon.test.run.edn`).
- **Selection.** When the last completed run's stored digest equals the selected value's digest, no program attribute
  was written since that run's basis. In that case `changed-definition-symbols`, the removed-file read and the
  schema-change read are skipped (`changes-since` nil). A supplied basis still scans.
- **Admission.** The writer's in-transaction value cannot be compared by revisions. A probe on a memory connection
  showed a `:db.fn/call` value with `committed? false` and fresh random revisions even for untouched attributes,
  because the writer's local value has a speculative context. So the decision is made at the authority from a
  pre-read:
  - the admitting caller compares the recording head's digest with the provenance's;
  - when they are equal, it passes `:seon.test.run/unchanged-through-t`, the head's basis;
  - `admit-run` trusts it only when its own value is at that same basis (`db/basis-t`, proven equal in-transaction by
    the same probe: 536870913 before and inside);
  - otherwise it falls back to `program-written-since?`, unchanged.

Regression `seon.test.selection-test/an-unchanged-program-is-selected-and-admitted-without-reading-its-history` uses
two named requests of one test on a fixture branch and counts armed calls:

- the first request makes 0 `program-written-since?` calls;
- the second makes 0 `changed-definition-symbols` and 0 `program-written-since?` calls.

It was red on the parent (`check-s2-parent.log`: 1 and 1) and is green on the change.

## 3. Allocation

I traced it per stage with `seon.profile/begin`/`explain` and `getTotalThreadAllocatedBytes`. The probe source is in
the scratchpad, `test-cost2/cost_probe_test.clj`; I ran it as a disposable test and never committed it.

After step 2, the warm path's top cost was `reach-digests` → `reach-graph`: 155-196 ms and most of 32-49 MB. It
rebuilt every reached node's row from the cached declarations, then hashed the whole node map as the `::reach-index`
key, on every selection and again at recording (`reach-memberships`, `tests-reaching`). The reached graph is a
function of `reach-declarations` and the test set. So `reach-graph` is now cached as a `seon.cache` kind
(`::reach-graph`) under `reach-revision-key` plus the test set, the rule `seon.render/call-reach-key` uses. Selection,
recording and the membership readers share one walk. The identical node map then hashes once (hash memoized), so
`::reach-index` hits in O(1).

Regression: `seon.test.runner-test/reach-digests-and-memberships-share-content-valid-work` now also asserts 0
`reached-graph` calls on the repeated reads.

`acquire-program!` / `derive-base-ctx` (first request in a JVM: about 0.8-1.3 s and about 3 GB) belongs to lane
defn-cost. I did not touch it.

## Numbers

Same probe, throwaway check JVM, parent HEAD 6098f0359 against the change, load average 13-16. The ms are wall time
and the MB are total-thread allocated MB.

| test | phase | parent ms | parent MB | change ms | change MB |
|---|---|---|---|---|---|
| `seon.id-test/identity-is-a-function-of-the-values-structure` | first request in the JVM | 2,235 | 4,650 | 1,854 | 3,313 |
| same | warm (1st reuse) | 162 | 212 | 19 | 25 |
| same | warm (2nd reuse) | 35 | 53 | 7 | 7 |
| `seon.task-ingestion-test/a-task-whose-handle-changes-keeps-its-symbol` | cold (never run) | 661 | 714 | 413 | 537 |
| same | warm (1st reuse) | 149 | 213 | 40 | 26 |
| same | warm (2nd reuse) | 54 | 76 | 9 | 8 |
| `seon.test-support-test/environment-retains-the-database-projection` | `reach-digests` first | 84 | 159 | 104 | 159 |
| same | `reach-digests` again | 21 | 24 | 2 | 1 |
| same | cold run (reach already derived) | 359 | 456 | 269 | 240 |
| same | warm | 128 | 212 | 21 | 26 |

Against the targets:

| target | result |
|---|---|
| warm under 150 ms | met: 7-40 ms |
| warm allocation in single-digit MB | 7-8 MB on a repeated reuse; 25 MB on the first reuse after a run, where the new commit's queries are uncached in Datahike's query cache (`seon.db/q` 16-19 ms) |
| cold under 300 ms | not met: 413 ms for a never-run test with a large reach |

The cold remainder, from the profile:

| cost | ms |
|---|---|
| the test body (task ingestion) | ~100 |
| 4-6 transactions | 80-160 |
| `clean-request-handle` | ~95 |
| first reach derivation of a large reach (proportional to reach, 159 MB) | ~100 |
| `arm-first-loads!` | 64 |

The first request in a JVM is the defn-cost lane's derive.

Timing note: two earlier probe runs had load average 31-46, with other lanes' throwaway JVMs running. They showed
inflated times: `cache/lookup` 4,582 calls took 117 ms against 11 ms. Only the allocation numbers from those runs are
comparable.

## Findings and tasks

| finding | evidence | task |
|---|---|---|
| `named-selection-reuses-green-members-by-reachable-content` red on HEAD | observes `reach-digests` through `with-redefs`; check run d1959122ffe6 | `seon.test.selection-test/named-selection-observes-reach-requests-by-redefinition` (created) |
| `run-carries-the-connections-projection-to-the-test-thread` errors in a check JVM | "clj-kondo holds no analysis of seon.schema" (first-party) | `seon.fn.analyzer/a-check-jvm-has-no-analysis-of-first-party-namespaces` (created) |
| `a-fresh-branch-with-unchanged-program-entities-derives-nothing` named a deleted test | `seon.id-test/data-shape-and-explicit-length-determine-identity` | fixed: now names a test that exists |
| `agent-admitted-tests-reach-their-tested-function` read the retired row key | | fixed: converted to `define!` |
| `seon.test/select`'s schema-change refusal never fires on a schema form write | reads `:seon.schema/edn`, which nothing declares | `seon.test/schema-change-refusal-reads-a-nonexistent-attribute` (created) |
| an agent's `deftest` in a file test namespace is refused | digest mismatch | `seon.turn.batch/a-definition-in-a-file-test-namespace-refuses-its-produced-digest` (created) |
| `seon.cluster.source` merge acceptance still scans history | calls `program-written-since?` (`requiring-resolve`) | not my file; it can take the same pre-read and basis check |
| test failure reports print whole frame vectors and values | about 100 lines per red in check logs | not filed here; this belongs to the printer owners' runner-report class |

## Design check

What held against the code:

- `program-revisions` already existed for `destructive-reach`'s memo, so selection's equality cost one digest.
- `reach-revision-key` already keyed `reach-declarations`, so the graph cache reuses that key.

What was wrong or awkward:

- I first planned to compare revisions inside the writer. The probe showed that the in-transaction value's revisions
  are speculative random uuids, so that design was impossible. The pre-read plus basis check is the "validate a
  pre-read's basis at the authority" rule, and it costs one long comparison at the writer.
- An `:unchanged-through-t` from a stale head is harmless: the writer's basis has moved, so the scan decides.
- The stored digest is connection-scoped. After a restart, or across connections, it never matches, so the scan
  remains on that path. That is correct but uncached.
- The `define!` overlay test now defines callee, caller, the redefined callee and the test in one batch. The world
  still runs the candidate's callee (`= 2`), but there is no longer an earlier admitted callee row on the branch.

One happy path:

- Serves the agent's eval → record step for `my.test/check`.
- Owners reused: Datahike revisions and `seon.cache`.
- Deleted: per-request history scans and the repeated reach walks.
- Added: one stored digest, one admission key and one cache kind. No new mechanism.

No RESET NEEDED. Root's restart loads the landing (`seon.test`, `seon.test.runner`, schema `seon.test.run`).
