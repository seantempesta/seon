---
title: "Red census 2: suspect namespaces on HEAD 241c8ed09"
type: research
status: evidence only; no code or doc changed beyond this file
date: 2026-10-16
basis: "HEAD 241c8ed09 checked with bin/seon check --at HEAD --include-long; stored operator-check logs in data/clusters/default/logs"
---
# Red census 2: the suspect namespaces on HEAD (2026-10-16)

Lane: red-census-head (Haiku, evidence only). No edits, no root SCI requests, default not stopped or restarted.

HEAD: `241c8ed09` (A definition's digest no longer reads its namespace's requires).

## Runs

Four `bin/seon check --at HEAD --include-long --test NS ...` batches over the 14 namespaces, then one rerun of every red var alone, batched, and one run of the four named tests.

| Batch | Namespaces | Executed | Result | Stored run |
|---|---|---|---|---|
| b1 | seon.cluster.db-flow-test | 54 | 7 red | `3f8ef3d74e10`, `operator-check-20261016T151643.325.edn` |
| b2 | seon.cluster.merge-test, seon.merge-test, seon.cluster.source-test, seon.cluster.write-back-test | 76 | 5 red (plus one error attributed in the output to merge-test line 254) | `4f25fd4d501f` |
| b3 | seon.context-test, seon.turn.test-step-verdict-test, seon.program-test, seon.turn.batch-test | 117 | 9 red | `2647bc96c5cd` |
| b4 | seon.cluster.root-seed-digest-test, seon.test-test, seon.test-reaching-test, seon.render.ns-test, seon.effect-test | 86 | 11 red | `c84a6f8fcd93` |
| rerun | all 32 red vars, alone in one JVM | 32 | 31 red | `ae4215d4b87c` |
| four | the four named db-flow vars | 4 | 1 red | `b168797f2586` |

Clean at HEAD in this census: seon.context-test, seon.turn.test-step-verdict-test, seon.merge-test, seon.cluster.write-back-test, and the db-flow-test vars that were red this morning. Not in this census: my.root-test and the other morning singles (lazy-agents, config, value, repl-differential, documentation), which are outside the 14 namespaces.

## The four named tests

| Test | Red on HEAD | Result |
|---|---|---|
| `a-prove-step-whose-product-main-holds-merges-and-its-successor-forks-main` | No | Green in the full db-flow run and in the four-var run. The morning red at `:1174` and `:1180` is gone at HEAD. Stored reds of this name first appear 2026-10-13 14:20 (candidate `65300bb5d`, base `5a8905a43`). |
| `a-refinement-on-a-branch-reaches-main-only-at-the-end` | No | Green in both runs. First stored red 2026-10-12 07:17 (candidate `c4060324a`). |
| `an-attempts-branch-holds-its-attempt-and-agent-under-mains-ids` | No | Green in both runs. First stored red 2026-10-12 06:53 (candidate `11af1753f`, which was a check of the test file under edit on base `e91100941`). |
| `a-requested-end-whose-files-no-checkout-holds-merges-nothing` | **Yes** | Fails at `test/seon/cluster/db_flow_test.clj:1640`: "main records no merge of its head". Expected `(not (merge/merged? database (db/commit-id head)))`, actual `(not (not true))`, so main does hold a merge of the branch head. The assertion was added by `4ca233c04` (2026-10-15, one merge base phase 2b). Earlier stored failures of this name: uncaught error on 2026-10-13 (candidate `f8251a9cd`), and failing line `:1371` on 2026-10-15 (candidate `88474708b`). First observed red 2026-10-13 00:02. Tag: pre-existing. |

## Red vars at HEAD

"First observed red" is the earliest stored run that listed the var as red, with the candidate's base commit. Stored runs record only reds, so a true first-red commit lies between the last green observation (not recorded) and this. Candidates in the store are `check: HEAD + <dirty files>`, so the base commit is the HEAD of that candidate and the dirty files are part of it.

Tags: **pre** = first observed red before 2026-10-16; **today** = first observed on 2026-10-16; **flaky** = red in the full run, green when rerun alone; **unknown** = no stored red.

| Test (ns/var) | Failure at HEAD (rerun alone unless noted) | First observed red (candidate, base) | Tag |
|---|---|---|---|
| db-flow `a-capped-attempt-with-changes-relaunches-from-its-commit` | Error: "The test channel did not publish its required event" (`proved`, 5000 ms); also 5665 ms over its 5000 ms bound in the full run | 2026-10-13 02:58 (`8d6afec91`, base `f7a87784e`) | pre |
| db-flow `a-done-merge-step-merges-its-branch-onto-main` | `db_flow_test.clj:2702` reason starts with "The final merge refused it." but reason is "...:seon.source/write-back-error"; rerun `:2702` | 2026-10-15 14:19 (`04d73ec5a`, base `8116a003f`) | pre |
| db-flow `a-failed-start-ends-its-attempt-and-the-next-plans` | `:1856` end is `:open`, expected `:error`; `:1857` attempt reason is nil | 2026-10-12 16:08 (`059f0db1a`, base `fc26d432b`) | pre |
| db-flow `a-merge-refused-for-a-missing-contract-relaunches-with-the-refusal-shown` | `:2123` no relaunch row; the test then errors at `:2133` (`await-on!` got nil connection) | 2026-10-13 05:18 (`90a073101`, base `a150c4346`) | pre |
| db-flow `a-refused-final-merge-bails-to-root-once-with-its-refusal` | `:2430` problem task does not name the refusal; `:2432` reason lacks the refusal type | 2026-10-15 02:27 (`4e569ce0b`, base `e69de067d`) | pre |
| db-flow `a-requested-end-whose-files-no-checkout-holds-merges-nothing` | `:1640` main holds a merge of the head | 2026-10-13 00:02 (`f8251a9cd`, base `2265d84fd`) | pre (see above) |
| db-flow `an-error-end-copies-nothing-and-writes-roots-problem-task` | `:1442` uses "Do e." as intent, expected "Refined on the branch."; `:1443` merge commit nil | 2026-10-12 16:55 (`f1cca55b7`, base `ad44a3ba7`) | pre |
| merge-test `a-done-branch-merges-with-no-merge-request-written-by-the-agent` | `:638` (fail); in the full run `:637` and `:639` | 2026-10-12 20:56 (`feb4cb62e`, base `7a4fba11c`) | pre |
| merge-test `a-requested-green-end-merges-and-publishes-with-no-hand-act` | `:603` outcome nil, expected `:merged`; the same test block reports `merge_test.clj:254` (`git rev-parse HEAD^` unknown revision: the fixture has one commit). Line 254 is inside the deftest at line 194 (`a-red-reaching-test-refuses-acceptance`) by awk; the report attributes it to this var. Unresolved, see risks. | 2026-10-12 19:33 (`46ae440a6`, base `05d3eb2ed`) | pre |
| merge-test `a-red-reaching-test-refuses-acceptance` (error at line 254) | Not listed as its own red by the report; see above. Needs its own rerun to confirm. | not checked | unknown |
| root-seed-digest `root-seed-declarations-come-from-source` | `root_seed_digest_test.clj:17` (rerun), `:38` (full run) | 2026-10-10 18:32 (`7891d519a`, base `3c4f94227`) | pre |
| source-test `flat-scratch-write-refusal-retires-the-candidate` | fail; full run 45109 ms against 5000 ms bound | 2026-10-10 09:06 (`dd5aa34a6`, base `388423db4`) | pre |
| source-test `incremental-publication-does-not-change-an-existing-cluster` | fail; full run 41127 ms against 5000 ms bound | 2026-10-10 09:06 (`dd5aa34a6`, base `388423db4`) | pre |
| source-test `publication-hands-its-commit-projection-unstamped` | fail; full run 40428 ms against 5000 ms bound | 2026-10-10 09:06 (`dd5aa34a6`, base `388423db4`) | pre |
| effect-test `guarded-sci-evaluation-supplies-the-effect-identity-context` | `effect_test.clj:842` | 2026-10-08 23:50 (`73673fc57`, base `0717d5a5f`) | pre |
| effect-test `the-effect-ai-render-returns-data` | `effect_test.clj:1106` | 2026-10-16 11:41 (`470a728ca`, base `a91b62ca0`; the candidate also changes several test files including `test/seon/render/...`) | today (candidate 470a728ca) |
| program-test `a-declaration-added-after-the-first-call-is-a-cache-miss` | error | 2026-10-07 11:54 (`76a490909`, base `433214bc5`) | pre |
| program-test `a-schema-key-source-separates-takers-guards-and-members` | `program_test.clj:1590` | 2026-10-14 14:31 (`322f083f9`, base `607b45419`) | pre |
| program-test `declaring-an-attribute-on-a-program-entity-schema-is-sufficient` | error: the attribute retraction expectation (`contains?` on a set) | 2026-10-07 11:54 (`76a490909`, base `433214bc5`) | pre |
| program-test `indexed-and-evaluated-declarations-are-the-same-entities` | `program_test.clj:1553` | 2026-10-07 11:54 (`76a490909`, base `433214bc5`) | pre |
| program-test `runtime-schema-declarations-project-namespaced-properties` | `program_test.clj:1110` | 2026-10-07 11:54 (`76a490909`, base `433214bc5`) | pre |
| program-test `typed-cross-namespace-deletion-retracts-function-and-test` | `program_test.clj:1181` | 2026-10-07 11:54 (`76a490909`, base `433214bc5`) | pre |
| render-ns-test `task-and-schema-entities-render-as-graph-nodes` | `ns_test.clj:197` (rerun), `:210` (full run) | 2026-10-07 22:08 (`5b0a3327c`, base `bde4519d4`) | pre |
| test-reaching `a-program-declaring-no-destroyer-refuses-instead-of-admitting` | error: "seon.test/host-exclusions refused argument 1 `declared-root`: expected nil, got a map" (`test_reaching_test.clj:452`) | none stored | unknown (reproducible in both runs) |
| test-reaching `an-unchanged-branch-derives-its-changes-once` | `test_reaching_test.clj:798` | 2026-10-14 20:18 (`26038e837`, base `6d51c7aee`) | pre |
| test-test `a-branch-test-world-runs-the-jvm-loaded-program-not-its-fork-point-record` | `test_test.clj:456` | 2026-10-12 19:11 (`d962d4c47`, base `d50a30d21`) | pre |
| test-test `admission-refuses-stale-program-and-records-empty-selection` | error (uncaught exception in the test body) | 2026-10-13 16:43 (`045b8d678`, base `6a421d96e`) | pre |
| test-test `one-interpreted-test-run-allocates-a-bounded-multiple-of-its-body` | `test_test.clj:361` in the full run; **green when rerun alone** | 2026-10-10 19:11 (`f36bd925e`, base `2cf1ba292`) | flaky (allocation bound) |
| test-test `recording-distinguishes-run-replay-from-a-new-event` | `test_test.clj:216` | 2026-10-08 20:55 (`dcd76ac5a`, base `ade6e3059`) | pre |
| test-test `resolution-follows-admitted-source-and-acquisition` | error | 2026-10-08 17:37 (`87cb0ec89`, base `b23b52ca6`) | pre |
| turn.batch-test `a-namespace-row-is-its-ns-text` | `batch_test.clj:599` | 2026-10-16 11:10 (`e5c13285a`, edits only `test/seon/turn/batch_test.clj`, base `6a0da3dba`) | today (candidate e5c13285a) |
| turn.batch-test `a-renamed-refer-keeps-its-call-edge-in-the-record-stage` | error: the record stage's `:recorded`/`:calls` expectation | 2026-10-16 11:37 (`e8b9de025`, base `a91b62ca0`; the candidate also changes `src/seon/turn/batch.clj`, `src/seon/fn.clj`, `src/seon/fn/analyzer.clj`) | today (candidate e8b9de025) |
| turn.batch-test `a-repl-definition-digests-as-its-file-does` | `batch_test.clj:625` | 2026-10-16 11:10 (`e5c13285a`, base `6a0da3dba`) | today (candidate e5c13285a) |

Totals: 32 red at HEAD in the full runs. 31 red in the rerun. 1 flaky (test-test allocation). 0 vars newly red in the morning's census that are not listed here.

## Changed since this morning's census (b7d7632fc)

- Gone (green at HEAD): db-flow `a-prove-step-...`, `a-refinement-...`, `an-attempts-branch-...`, `every-agent-start-has-a-task`, merge-test `a-chain-merges-once-and-closes-every-attempt`, context-test `a-step-is-done-only-when-its-merge-would-accept` (not reached in this census's namespaces except context-test, where it is green), `seon.turn.batch-test` git-ls-tree errors (now real assertions).
- Still red: `a-requested-end-...`, `a-refused-final-merge-...` (morning list named `a-refused-final-merge-bails-to-root-once-with-its-refusal`, same var), `a-merge-refused...` (new in this list as red), source-test three, root-seed-digest, program-test, render-ns, test-test.
- New to the list since this morning: `a-capped-attempt-...`, `a-done-merge-step-...`, `a-failed-start-...`, `a-merge-refused-for-a-missing-contract-...`, `an-error-end-...`, `merge-test` two, `effect-test` two, `test-reaching` two, `test-test` four, `turn.batch-test` three (its morning error was environmental).

## Open risks

- Merge-test line 254 (`git rev-parse HEAD^`) is reported under the `a-requested-green-end` block but awk places it inside `a-red-reaching-test-refuses-acceptance` (line 194). Either the report attributes the error to the wrong var or the error belongs to the red-reaching test; a one-var rerun of each settles it. Not done in this lane.
- Source-test reds take 40 to 45 s against a 5 s bound, which is a cost finding, not a flake.
- "First observed red" uses only recorded reds. Greens are not stored, so none of the first-red commits here is proven to be the first red commit; each is the earliest candidate where the red was seen.
- Candidates in the store contain uncommitted files of other lanes (see the `check: HEAD + ...` subjects), so a candidate's red can come from its dirty files rather than its base.

## Not done

- Bisection (the brief forbids it for pre-today reds; the stored candidates cannot boot through check).
- `seon.cluster.lazy-agents-test`, `config-test`, `value-test`, `repl-differential-test`, `documentation-test` and `my.root-test` are outside the 14 namespaces.
