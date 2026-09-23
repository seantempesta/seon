---
type: audit
status: static review
created: 2026-09-23
scope: 7e5cdea9e..HEAD
---

# Sol audit of the landings

Scope: all 19 commits in `git log --oneline 7e5cdea9e..HEAD -- src test resources`. I read their diffs, the changed owners at committed HEAD, and their named landing notes. The checkout has concurrent uncommitted work; line references below are **committed HEAD**, obtained with `git show HEAD:<path>`. No JVM, `bin/seon`, or test command ran. Findings are static unless a landing note supplies the observed result. The counts below count rows, not affected commits. The changed code shows no newly retained materialized database or unbounded projection/context cache; `source/database` is released at `cluster.clj:2639,2793`, and the packaged-population LRU is bounded to two entries. Persistent candidate branches remain a separate concern below.

## Bug (3)

| commit | file:line | finding | smallest fix |
|---|---|---|---|
| `c32a2c672`, `20a641b9b` | `src/seon/cluster.clj:2651-2665` | `reaching-run` intersects changed identities with declarations still present. Deleting a function or test yields no member and `passed? true`; the comment explicitly calls a retired test “none.” A deletion can break surviving callers while the gate advances without their reaching tests. | Select reaching tests from the **before** program for retired identities, then run them against the candidate; refuse if coverage cannot be established. |
| `20a641b9b` | `src/seon/cluster.clj:2572-2612` | Reload and instrumentation mutate JVM Vars before `adopt-rows!`. If the row write or following record refuses, loaded code is new while default's rows and adoption record are old. This is the inverse of the old rows-before-reload gap, not an atomic transition. | Stop execution at the evaluation boundary and make reload plus row publication one admitted transition, with an explicit failure recovery path. |
| `20a641b9b` | `src/seon/cluster.clj:2612-2630` | Program rows and the adopted source commit are still separate transactions. The landing note measured two transaction IDs and explicitly says the pair is not atomic. An acquisition between them sees new rows with the old adopted commit; a second-transaction refusal leaves that state indefinitely. | Append the adoption record to the row writer's transaction and guard the same expected head there. |

## Bloat (2)

| commit | file:line | finding | smallest fix |
|---|---|---|---|
| `c32a2c672` | `src/seon/cluster.clj:2668-2711` | Save-gate added a second orchestration layer (`candidate-gate!`, `save-gate!`, callbacks and a new result map), +125 net src lines per its landing note. The existing `seon.test/run` at `src/seon/test.clj:1584` already owns selection, execution and recording; the gate's local intersection at `cluster.clj:2651` duplicates selection and causes the deletion bug above. | Pass changed identities, including retired ones, to the test owner's one request; keep only branch acquisition and the guarded adoption decision here. |
| `20a641b9b` | `src/seon/cluster.clj:2641-2666,2728-2745` | `reaching-run` and `adopt-then-test!` accrete another path around the same test request and repeat gate verdict/tally construction. The split was driven by SCI inability to interpret host code (landing note), while the loaded/adopted transition remains unresolved. | Put the host execution choice in the existing test request's execution program and keep one gate result constructor. |

## Slow or retention (3)

| commit | file:line | finding | smallest fix |
|---|---|---|---|
| `edfa8b182` | `src/seon/schema/edn.clj:345-386` | Every warm `packaged-forms` call still lists and sorts every schema resource to form `declaration-stamp`; the landing note measured the stamp 1,407 times. The LRU changes the retained container, not the O(resource count) key computation. Name/length/mtime can also collide after an equal-length edit. | Invalidate once at publication from admitted content digests; use that content key for the existing core.cache entry. |
| `c32a2c672` | `src/seon/cluster.clj:2696-2705` | Each edit acquires a candidate, writes a new commit, and calls `seon.test/run`; the landing note measured `runner/program-digest` at 10.7 s on every new commit. The test added by this commit took 81–87 s twice. It makes an edit's feedback proportional to the program, violating the sub-second target. | Reuse per-member digest evidence and update only the changed declaration closure in `seon.test.runner/program-digest` (`src/seon/test/runner.clj:1024`); then rerun the gate timing. |
| `676f0ccb4` | `src/seon/test.clj:361-365,1408-1414` | The reverse path walk is now once per destructive owner, but `host` still calls it per queried member, and `host-exclusions` rebuilds paths on every request. The landing note's warm 158 ms is for 2,073 symbols; repeated one-member calls still redo the owner's closure. | Pass the already computed owner→path map through selection/host classification once per execution database value. |

## Half-done (5)

| commit | file:line | finding | smallest fix |
|---|---|---|---|
| `78a35a2c1` | `test/seon/namespace_agent_loop_test.clj:86-90` | Commit says the regression proves the worker loop, but its landing reports the reply wait red and no positive evaluation on C. The later render fix still left the test red on “no reply turn opens.” The assertions establish branch custody and arming, not the full loop. | Fix the turn/wake owner and run this same regression green; narrow the claim until then. |
| `c32a2c672`, `20a641b9b` | `test/seon/cluster/save_gate_test.clj:29-91` | The regression drives synthetic deftest rows through `candidate-gate!` and a stub `advance!`; it never exercises `save-gate!`/`refresh-source!` on a changed file. The landing note's live docstring probes were red or refused; the later host test proves adopt-before-red, not a green save. | Add one real changed-file save request, checking selected reaching tests and the target commit on green/red; keep the synthetic test only for the generic branch seam. |
| `edfa8b182` | `test/seon/schema/declaration_population_test.clj:46`; `test/seon/sci/admit/declaration_population_test.clj:63` | The converted tests call `cache/seed` on the production cache and were not run after adoption. The landing note explicitly says default still loaded the deleted `forget-packaged-population!` and labels tests unrun. The commit's cache behavior has only a throwaway-namespace probe. | Adopt this file on a stable system and run the two targeted members; avoid mutating the production cache in concurrent tests by injecting an isolated cache. |
| `ad1f8158e` | `test/seon/turn_backstop_test.clj:94-140` | The landing note says the final 2 s regression never ran green: the store returned “Node not found in storage.” A REPL probe establishes the local cancellation state, but the committed test result is unavailable. | Run the committed member after store recovery and record its terminal result; retain the REPL proof as narrower evidence. |
| `a85bf004b` | `src/seon/cluster/boot.clj:531-541` | The commit says a request's refusal crosses prepl as bounded shown text, but `readable-response` still `pr-str`s the entire response and repeats `pr-str response` in its catch. The landing note names this raw printer as unconverted; the regression sends a diagnostic path only. A non-diagnostic refusal can still exceed the prepl limit. | Render/bound at the one `readable-response` boundary and test a non-diagnostic large refusal. |

## Law (1)

| commit | file:line | finding | smallest fix |
|---|---|---|---|
| `edfa8b182` | `src/seon/schema/edn.clj:345-355,384-386` | The new cache is keyed by a filesystem metadata **stamp**, not the resource content it reads. The comment claims equal length/mtime cannot survive an edit; that is false (timestamp granularity, restored timestamps). A valid changed resource can reuse stale schema forms, contrary to “never redo valid cached work” and content identity. | Key by admitted content digest or invalidate from the source publication event. |

## Top five to fix first

1. Gate deletions through reaching tests instead of treating absent rows as green (`cluster.clj:2651`).
2. Close the two-transaction adoption gap and failed-reload state (`cluster.clj:2572-2630`).
3. Make the gate's new-commit test request incremental; the measured 10.7 s digest dominates save feedback (`cluster.clj:2696`).
4. Make schema-population cache identity content-based and stop per-call directory scans (`schema/edn.clj:345`).
5. Prove the real changed-file save path with a green and a red request (`save_gate_test.clj:29`).
