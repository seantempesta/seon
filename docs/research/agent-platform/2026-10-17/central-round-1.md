---
title: "Central round 1: declared-long tests and the five merge-area namespaces on HEAD"
type: research
status: evidence only; no source or test edited; three owner tasks filed in two task files
date: 2026-10-17
basis: "bin/seon check --at HEAD --include-long, candidate 5c983f382 (docs-only commits past d03b184bf), lane central-round (Sonnet)"
---
# Central round 1 (2026-10-17)

Namespaces: `seon.cluster.source-test`, `seon.cluster.write-back-test`, `seon.merge-test`, `seon.cluster.merge-test`, `seon.cluster.db-flow-test`. Each ran whole with `--include-long`, so the ordinary tests and the declared-long ones ran in one pass per namespace. The check's request bound (about 120 s) cut `seon.cluster.source-test` off after twelve vars; its four pending vars ran in a second request (green). Baseline: red census 2 (`docs/research/agent-platform/2026-10-16/red-census-2.md`). Stored runs: `3d2fde96b202` (db-flow), `77a8d15826a2` (cluster merge-test), `1719c0a487e0` (merge-test), `9340114aa286` (write-back), `a3fbaa3af0f7` and `ad6a6330f5de` (source). The six new or changed reds were rerun alone (run `0d5f1159d4b5`, all six red) and bisected with `bin/seon check --at REV` on those same vars.

## Totals

| Namespace | Executed | Declared long | Red | Green |
|---|---|---|---|---|
| `seon.cluster.merge-test` | 19 | 16 | 4 | 15 |
| `seon.merge-test` | 28 | 3 | 0 | 28 |
| `seon.cluster.db-flow-test` | 53 | 25 | 8 | 45 |
| `seon.cluster.write-back-test` | 15 | 12 | 0 | 15 |
| `seon.cluster.source-test` | 16 | 1 | 3 | 13 |
| total | 131 | 57 | 15 | 116 |

- Declared-long tests: 57; 50 green, 7 red (`a-prove-steps-red-product-merges-into-main`, `a-refused-final-merge-bails-to-root-once-with-its-refusal`, `a-two-step-chains-implement-step-forks-main-holding-the-prove-steps-product`, and in seon.cluster.merge-test `a-candidate-merges-only-its-tested-green-program`, `a-done-branch-merges-with-no-merge-request-written-by-the-agent`, `a-function-and-its-schema-land-as-one-unit`, `a-requested-green-end-merges-and-publishes-with-no-hand-act`).
- Reds by class: 9 pre-existing (db-flow 4, merge-test 2, source-test 3), 5 changed or new at merge P1 `5f5eb1855` (db-flow 4, merge-test 1), 1 new before the phases (`5b71e4670`).
- Green here that census 2 had red: db-flow `a-capped-attempt-with-changes-relaunches-from-its-commit`, `a-done-merge-step-merges-its-branch-onto-main`, `a-merge-refused-for-a-missing-contract-relaunches-with-the-refusal-shown`. All seon.merge-test and seon.cluster.write-back-test vars are green, including all their declared-long tests.

## New and changed reds first

Cause, read from the diff of the function on the failing line:

- `a-candidate-merges-only-its-tested-green-program` `:77`: P1 made `seon.cluster.source/merge-verdict` call `seon.cluster.source/done-refusals`, whose evidence map sets `:seon.error/layer :seon.source/done`; the deleted `results-verdict` set `:seon.source/merge`. P1's message says the merge path "stays proven by seon.cluster.merge-test", but this assertion was not converted. Green at `4190df1c0`, red at `5f5eb1855`.
- The four Prove-step db-flow tests: green at `4190df1c0`, red at `5f5eb1855`. P1 replaced `:seon.task/product` (which `results-verdict` exempted through `seon.test.runner/designed-red?`) with `:seon.source/next-proofs` in `merge-verdict` and `seon.context/step-observation`; the Prove step now ends `:stalled` where its successor's product is a red deftest or stub. The first refusal was not read; the new task says to read it first. P2's commit message (`14448514a`) already lists `a-prove-step-whose-product-...` as red on `0be7a404d` with the same failure, which agrees.
- `a-function-and-its-schema-land-as-one-unit`: not a phase red. Green at `241c8ed09`, `56038bbb1`, `3bd761ccf`, `63aac4169` and `61fa26616`; red at `5b71e4670` (Make unchanged eval requests reuse branch changes and test evidence) and at every later revision tried (`6f1d9b0cc`, `53fc1da51`, `4190df1c0`). At `4190df1c0` only `:703` fails; P1 adds the other four. Census 2 did not list it.

| Test | Long | Result | Failure | Classification | Owner task |
|---|---|---|---|---|---|
| `seon.cluster.db-flow-test/a-prove-step-admitted-by-its-successor-opens-a-model-turn` | no | red | `:1916` end `:stalled`, expected `:done`; `:1918` two model calls, expected one | changed: not red in census 2; merge P1 `5f5eb1855` | `seon.cluster.source/done-refusals-leaves-a-prove-steps-product-unfinished` (new) |
| `seon.cluster.db-flow-test/a-prove-step-whose-product-main-holds-merges-and-its-successor-forks-main` | no | red | `:1168` end `:stalled`; `:1171` no `:merged` outcome; `:1173`-`:1177` successor not ready, never forked, branch kept | changed: green in census 2, red since merge P1 `5f5eb1855` | `seon.cluster.source/done-refusals-leaves-a-prove-steps-product-unfinished` (new) |
| `seon.cluster.db-flow-test/a-prove-steps-red-product-merges-into-main` | long | red | `:2020` no `:merged` outcome; `:2022` and `:2023` main holds neither the red deftest nor its stub | new: not red in census 2; merge P1 `5f5eb1855` | `seon.cluster.source/done-refusals-leaves-a-prove-steps-product-unfinished` (new) |
| `seon.cluster.db-flow-test/a-two-step-chains-implement-step-forks-main-holding-the-prove-steps-product` | long | red | error at `:2056`: `seon.cluster.registry/head-commit-id` refused argument `branch`, got nil | new: not red in census 2; merge P1 `5f5eb1855` | `seon.cluster.source/done-refusals-leaves-a-prove-steps-product-unfinished` (new) |
| `seon.cluster.merge-test/a-candidate-merges-only-its-tested-green-program` | long | red | `:77` refusal layer is `:seon.source/done`, expected `:seon.source/merge` | new: not red in census 2; merge P1 `5f5eb1855` | `seon.cluster.source/a-red-candidate-is-refused-at-the-done-layer-since-merge-p1` (new) |
| `seon.cluster.merge-test/a-function-and-its-schema-land-as-one-unit` | long | red | `:694` no published git commit; `:695` `:699` `:703` `:706` schema resource and consumer contract not carried; error `:254` `git rev-parse HEAD^` | new: not red in census 2; first red `5b71e4670`, before the phases | `seon.merge/a-function-and-its-schema-land-as-one-unit-is-red-since-5b71e4670` (new) |

## Pre-existing reds

| Test | Long | Result | Failure | Classification | Owner task |
|---|---|---|---|---|---|
| `seon.cluster.db-flow-test/a-failed-start-ends-its-attempt-and-the-next-plans` | no | red | `:1848` end `:open`, expected `:error`; `:1849` attempt reason nil | pre-existing (census 2, same failure) | `seon.cluster.agent/a-failed-start-ends-its-attempt-is-red-on-head` |
| `seon.cluster.db-flow-test/a-refused-final-merge-bails-to-root-once-with-its-refusal` | long | red | `:2323` problem task does not name the refusal; `:2325` reason lacks `:seon.source/write-back-error: ` | pre-existing (census 2, same assertions, lines moved) | `seon.cluster.agent/a-refused-final-merge-bail-problem-task-names-no-refusal` |
| `seon.cluster.db-flow-test/a-requested-end-whose-files-no-checkout-holds-merges-nothing` | no | red | `:1632` main holds a merge of the head | pre-existing (census 2, same assertion) | `seon.cluster.agent/a-requested-end-without-a-checkout-still-merges-its-head` |
| `seon.cluster.db-flow-test/an-error-end-copies-nothing-and-writes-roots-problem-task` | no | red | `:1436` no `:seon.task/merge-commit`; `:1437` intent "Do e." not "Refined on the branch." | pre-existing (census 2, same assertions) | `seon.cluster.agent/an-error-end-copies-nothing-is-red-on-head` |
| `seon.cluster.merge-test/a-done-branch-merges-with-no-merge-request-written-by-the-agent` | long | red | `:637` no `:merged` outcome, `:638` no merged-declarations count, `:639` main digest unchanged | pre-existing (census 2) | `seon.merge/a-done-branch-merges-with-no-merge-request-is-red-on-head` |
| `seon.cluster.merge-test/a-requested-green-end-merges-and-publishes-with-no-hand-act` | long | red | `:603` no `:merged` outcome; `:605` old body kept; `:610` `:612` nothing published; error `:254` | pre-existing (census 2) | `seon.merge/a-requested-green-end-merges-and-publishes-is-red-on-head` |
| `seon.cluster.source-test/flat-scratch-write-refusal-retires-the-candidate` | no | red | 48.2 s against the 5 s bound | pre-existing (census 2: 45 s) | `seon.cluster/flat-scratch-write-refusal-retires-the-candidate` family in `resources/seon/tasks/seon.cluster.edn` |
| `seon.cluster.source-test/incremental-publication-does-not-change-an-existing-cluster` | no | red | 42.4 s against the 5 s bound | pre-existing (census 2: 41 s) | same file, named by var |
| `seon.cluster.source-test/publication-hands-its-commit-projection-unstamped` | no | red | error: did not exit within the check's time limit (red and unfinished; census 2: 40 s against 5 s) | pre-existing | same file, named by var |

## Declared-long tests that are green

| Test | Long | Result |
|---|---|---|
| `seon.cluster.merge-test/a-replaced-function-no-test-reaches-stays-and-a-later-program-write-refuses` | long | green |
| `seon.cluster.merge-test/a-red-reaching-test-refuses-acceptance` | long | green |
| `seon.cluster.merge-test/an-accepted-merge-is-published-by-the-next-pass` | long | green |
| `seon.cluster.merge-test/a-merge-conflict-reports-its-variant` | long | green |
| `seon.cluster.merge-test/an-updated-task-is-written-to-its-task-file-and-survives-re-ingestion` | long | green |
| `seon.cluster.merge-test/a-new-namespace-is-published-to-its-own-file` | long | green |
| `seon.cluster.merge-test/an-end-message-naming-a-merge-task-is-not-a-request` | long | green |
| `seon.cluster.merge-test/a-test-never-run-on-the-target-is-not-blamed-on-the-merge` | long | green |
| `seon.cluster.merge-test/a-require-in-the-agents-user-namespace-lands-with-its-change` | long | green |
| `seon.cluster.merge-test/a-file-another-lane-left-dirty-accepts-the-merge-and-its-publication-waits` | long | green |
| `seon.cluster.merge-test/only-an-accepted-candidate-completes-a-task` | long | green |
| `seon.cluster.merge-test/a-chain-merges-once-and-closes-every-attempt` | long | green |
| `seon.merge-test/the-gate-runs-a-file-reading-test-against-the-candidate-and-refuses` | long | green |
| `seon.merge-test/a-prepared-merge-carries-its-merge-list-and-a-staying-conflict-refuses` | long | green |
| `seon.merge-test/a-merge-reuses-the-chain-branchs-green-at-an-unchanged-reach` | long | green |
| `seon.cluster.db-flow-test/a-chain-agent-makes-its-first-model-call-with-no-wake` | long | green |
| `seon.cluster.db-flow-test/a-capped-chain-attempt-bails-to-root-once-and-root-runs-it-again` | long | green |
| `seon.cluster.db-flow-test/a-held-chain-with-no-graph-runs-from-run-alone` | long | green |
| `seon.cluster.db-flow-test/a-chain-route-outlives-its-attempts` | long | green |
| `seon.cluster.db-flow-test/a-chain-written-with-a-provider-has-its-first-attempt-see-it` | long | green |
| `seon.cluster.db-flow-test/config-converges-before-the-agent-starts` | long | green |
| `seon.cluster.db-flow-test/a-green-recorded-without-the-task-never-proves-a-chain-task` | long | green |
| `seon.cluster.db-flow-test/a-two-task-chain-reaches-its-second-model-call` | long | green |
| `seon.cluster.db-flow-test/an-outside-task-takes-no-model-turn-and-is-worked-through-the-repl` | long | green |
| `seon.cluster.db-flow-test/an-outside-agents-send-back-ends-its-attempt-and-root-bails-it-once` | long | green |
| `seon.cluster.db-flow-test/an-ended-attempt-reaches-main-in-one-transaction-and-its-branch-is-deleted` | long | green |
| `seon.cluster.db-flow-test/a-merged-assignment-whose-graph-survived-a-crash-is-stopped-and-deleted` | long | green |
| `seon.cluster.db-flow-test/a-bailed-attempt-says-why-as-data` | long | green |
| `seon.cluster.db-flow-test/a-three-task-chain-ends-merged-with-no-hand-act` | long | green |
| `seon.cluster.db-flow-test/a-refused-final-merge-keeps-its-merge-branch-and-holds-a-merge-step` | long | green |
| `seon.cluster.db-flow-test/abandoning-a-chain-with-a-held-merge-step-closes-its-attempts-and-ends-its-branches` | long | green |
| `seon.cluster.db-flow-test/a-task-naming-a-branch-starts-its-attempt-there` | long | green |
| `seon.cluster.db-flow-test/a-done-merge-step-merges-its-branch-onto-main` | long | green |
| `seon.cluster.db-flow-test/a-rerun-predecessor-of-a-held-merge-step-merges-and-the-step-stays-held` | long | green |
| `seon.cluster.db-flow-test/a-merge-step-green-at-its-start-ends-done-with-no-model-call` | long | green |
| `seon.cluster.db-flow-test/an-attempt-whose-proofs-never-ran-ends-stalled-and-bails` | long | green |
| `seon.cluster.db-flow-test/a-pending-proof-opens-the-first-model-turn` | long | green |
| `seon.cluster.write-back-test/an-agent-replacement-merges-and-writes-back-its-exact-span` | long | green |
| `seon.cluster.write-back-test/an-agent-addition-merges-and-writes-back-both-declarations` | long | green |
| `seon.cluster.write-back-test/a-declaration-naming-an-unrequired-namespace-merges-with-its-ns-form-requiring-it` | long | green |
| `seon.cluster.write-back-test/a-file-clj-kondo-reports-an-error-in-refuses-at-prepare-naming-the-finding` | long | green |
| `seon.cluster.write-back-test/a-require-added-at-the-repl-merges-and-writes-back-its-ns-form` | long | green |
| `seon.cluster.write-back-test/a-merge-writes-each-change-into-its-own-file` | long | green |
| `seon.cluster.write-back-test/a-branch-change-calling-another-first-party-namespace-writes-back` | long | green |
| `seon.cluster.write-back-test/a-sibling-change-landed-in-the-file-is-kept-and-the-same-declaration-conflicts` | long | green |
| `seon.cluster.write-back-test/an-added-helper-is-written-before-the-replaced-function-calling-it` | long | green |
| `seon.cluster.write-back-test/added-functions-are-written-before-the-added-functions-calling-them` | long | green |
| `seon.cluster.write-back-test/a-function-in-a-new-namespace-writes-back-with-its-ns-form` | long | green |
| `seon.cluster.write-back-test/a-write-back-whose-second-file-cannot-be-staged-installs-no-file` | long | green |
| `seon.cluster.source-test/work-on-an-acquired-branch-runs-as-that-branchs-own-work-under-an-agents-custody` | long | green |

Ordinary green tests: 66 (every other deftest of the five namespaces).

## Verification limits

- `bin/seon check --at HEAD` boots a throwaway JVM from the archive of HEAD; default was not touched and no SCI request ran on it. Commits past `d03b184bf` (up to `6c44a3046`) change only docs and AGENTS.md.
- The three source-test reds were not rerun alone here (census 2 did and found the same); in the first request they ran in one JVM beside the other source tests.
- The `5b71e4670` bisection is by single-var runs at ten revisions; the `5f5eb1855` attribution is by the same six vars at `4190df1c0` and `5f5eb1855`.
