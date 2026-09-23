---
type: landing
status: landed
lane: nsa-s4 (Opus 5.5)
spec: docs/prds/agent-platform/plan/lane-nsa-slice4-prepare-accept.md (bb8ab9bb6)
created: 2026-09-23
---

# Namespace agents slice 4 — prepare, gate, named accept

`seon.cluster.source/prepare-merge!` and `accept-merge!` accept an isolated
candidate branch's function/test replacements into a cluster through the
existing save gate (`seon.cluster/candidate-gate!`, unchanged; cluster.clj was
held by m4-n1 and did not need to change: `advance!` is `(constantly #{})`).
No schema, no new key, no reason enum, no second run at accept.

## Paths and size

| path | added | removed |
|---|---:|---:|
| `src/seon/cluster/source.clj` | 139 | 0 |
| `src/seon/program.cljc` | 5 | 4 |
| `test/seon/cluster/merge_test.clj` (new) | 117 | 0 |

**Over budget** (spec: ≈80 src, ≤100 test). What the extra ≈60 src lines are
and the owner seam that would remove them:

- `merge-base` (22 lines): Datahike has no common-ancestor function; `branch-history`
  (`reference-code/datahike/src/datahike/versioning.cljc:191`) walks one side
  and materializes every commit. A fork `datahike.api/merge-base` would delete it.
- accept's evidence block (≈14 lines: members ∪ covered-by, `green-members`,
  per-function reach): the test owner has no "run R positively covers identities
  D" predicate. `seon.test` (held by test-overhead) is where it belongs.
- per-function coverage: the test owner does **not** refuse a changed function
  that reaches zero tests (open point 2, below), so accept does.
- `refused`, `replacement` (12 lines): shared by prepare and accept.

## Open points, resolved at the REPL (default, pid 63253)

1. **Basis for "no program change since the run".** `(db/as-of E t)` keeps the
   head's basis-t (probe: `(seon.db/basis-t (seon.db/as-of d (- t 50)))` =
   536871223 = `(:max-tx d)`), so `changed-identities (as-of E t) E` would answer
   `#{}` falsely. The test owner already has the check:
   `seon.test.runner/program-written-since? E (:seon.test.run/basis-t run)`
   (O(datoms since); 116 ms on default). Accept uses it; the run's own
   recording writes no program attribute (the "later program write" regression
   proves both directions). Second finding, from the first red run: a basis-t of
   H is **not** comparable on S's lineage once H moves (S's delta tx number can
   equal H's next tx), so `changed-identities` now also accepts a basis-t on the
   compared value's own lineage (input widened: `[:or :seon.db/database-value
   :seon.db/basis-t]`), and accept compares from the proposal's
   `:datahike/expected-basis-t`, the fork point of S. Prepare compares only the
   candidate's own changes since the merge base for the same reason (H's
   numbers are another lineage); head-only changes cannot conflict.
2. **Does the test owner refuse a changed function reaching zero tests?** No.
   `(seon.test/select {:seon.db/db d :seon.test.run/cluster [:seon.cluster/name "default"]
   :seon.test.run/policy :named :seon.test/changed ['my.agent/branch]})` →
   0 members, no refusal (243 ms); `seon.fn/gate-sets` of it → `[]`. Accept
   refuses with `:seon.source/test-evidence-error` naming each uncovered function.

## Algorithm and cost

| step | seam | cost |
|---|---|---|
| capture | `commit-database` (C), `db/db` of the source connection (H) | O(1) per value, 2.3 ms load |
| merge base | `merge-base`: alternate parent walks, `:datahike/parents` meta | O(commits since fork), 2.3 ms/commit, bound 10,000 |
| compare | `changed-identities B C`, `digest-map` ×3 scoped to it, `three-way` | O(D + digest history since B) |
| delta | `seon.fn/published-index-rows C D` + `seon.fn/reconcile-tx H rows D` | O(D + owned components) |
| gate | `candidate-gate!` → one `seon.test/run` (`:named`, changed D ∪ issue tests) | the test owner's run: 3.0 s of prepare's 3.8 s |
| evidence | `run-results`, `green-members`, `program-written-since?`, `gate-sets` per replaced fn | O(members + datoms since run) + reach index of E |
| write | `reconcile-tx` against the destination, `db/transact!` with expected basis-t and parents | O(D) |

## One real merge (throwaway branch of default, `tmp/nsa-s4/record.clj`)

H `6ab3dfd0-8530-5729-bce0-c07c00c676d5` (= B: H unchanged since the fork),
C `6ab3dfd2-a691-5e9b-9f57-960997e3af12`, S `:merge-ca455436004a`,
E `6ab3dfd6-c5ff-5a52-88de-d062881c1710`, run `7fda9c621ef8`
(executed 1, reused 0, pass 1), expected basis-t 536871336, merged commit
`6ab3dfd7-e67d-56a1-950b-81f38d589a5f` with parents #{H C E}. prepare 3,837 ms,
accept 1,187 ms, whole probe 8,298 ms (fixture writes and two context acquisitions).

## Regressions (`seon.cluster.merge-test`)

`a-candidate-merges-only-its-tested-green-program`: equal revert → `:seon.program/scope`
refusal; red replacement tested then refused at accept (head moved after the fork:
colliding branch eids); stale proposal → `:transaction/stale-basis`, head unchanged;
green through the indirect SCI call (test → caller → callee), exactly the tested
digest merged although C moved after prepare, parents #{H C E}; the merged proposal
refuses; a head replacement of the same function → `:seon.program/conflict`.
`a-replaced-function-no-test-reaches-and-a-later-program-write-refuse`: uncovered
function refused by name; a program write on S after the run refuses.
Not exercised: "unfinished" evidence (needs a member without terminal facts) and
"other-run" beyond the tested-branch check.

## Proof

- adoption: `bin/seon init --dev default --changed src/seon/cluster/source.clj
  src/seon/program.cljc test/seon/cluster/merge_test.clj`, token held;
  first attempt refused by other lanes' unpublished `agent.clj db.clj flow.clj
  db_test.clj` (edit shelved to `tmp/nsa-s4/prepare-accept.patch` and reverted,
  per the coordinator), adopted after the restart (10.7 s: publication's
  `full-source-refresh!` 7.1 s, the publication owner's cost), later edits 3 s and 7 s.
- `run 1ec3f05d60fd`: `--policy named --include-long --ns seon.cluster.merge-test`,
  executed 2, pass 16, passed? true, 35.1 s.
- `run 1344f99d7948`: merge-test + save-gate-test + tests reaching
  `seon.program/changed-identities`, `digest-map-refusal`: executed 9, pass 67,
  fail 0, passed? true, 52.6 s.
- contracts: all 7 touched contracts compile against the packaged projection
  (`seon.contracts-compile-test/contract-refusal`, 0 refusals, 320 ms).

## Timings over one second

| operation | ms | proportional to / justification |
|---|---:|---|
| prepare (real merge) | 3,837 | 3,003 ms is the nested `seon.test/run` on S's new commit (test owner) |
| accept (real merge) | 1,187 | ≈714 ms first `gate-sets` on E: seon.fn builds the reach index per new commit value (whole program; 21 ms warm). Owner fn.clj |
| named request, merge-test | 35,055 | over ten seconds: five nested runs, each a new candidate commit (3–7 s apiece); the save-gate class of cost, docs/research/agent-platform/cache-invalidation-audit-2026-09-23.md item 6 |
| combined request | 52,640 | same plus the save-gate tests' nested runs |
| first adoption | 10,711 | publication `full-source-refresh!` of the whole tree after the restart |

RESET NEEDED: no.
