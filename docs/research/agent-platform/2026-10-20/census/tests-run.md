---
title: "Cited-proof run: do the 20 ideas' deftests run green and assert something? (census-tests)"
type: research
---

# Cited-proof run (census-tests, 2026-10-20)

Every deftest cited in [ideas.md](ideas.md) (the four wildcards expanded to their namespaces: 228 distinct vars) ran on throwaway check JVMs booted by `bin/seon check --live-shape` at HEAD bc1a20635, 8 batches of 30 (the batch reports are `SEON_TEST_RESULT_FILE` EDN). Reds were re-run once in a batch of their own. Per-test data is in [tests-run.edn](tests-run.edn); the builder is [tests-run.clj](tests-run.clj), the idea-to-tests map [tests-idea-map.edn](tests-idea-map.edn).

Assertion counts are the report maps' pass+fail+error counters; `:meaningful` means green with at least one counted assertion. Whether an assertion is on data rather than printed text was not judged (inspect the test before porting). The run-ms column is the runner's own member time inside a batch, so a test near its 5 s bound can flip with load.

## Totals

| class | tests |
|---|---|
| `:meaningful` | 210 |
| `:no-assertions` | 0 |
| `:red` | 12 |
| `:timeout` | 2 |
| `:retired-assumption` | 0 |
| `:flaky` | 4 |
| total | 228 |

Batch runs: 8 batches, all 228 executed, 0 reused. Batches 1-6 reported red, 7-8 green. Re-run of the 18 first-run reds: 4 turned green (`:flaky`: three missed their 5 s bound or a 10 ms read bound under load, one is a cost-table row), 14 stayed red (12 `:red`, 2 `:timeout`: `seon.mine-test/an-ended-attempt-is-graded-once-on-main` ran 50 s against a 20 s bound; `seon.cluster.db-flow-test/a-filed-tasks-design-prove-implement-chain-merges-with-no-hand-act` ran 262 s, with 7 failures and 2 errors, the chain stalled instead of ending done).

No test was classed `:retired-assumption`: none of the reds could be tied to a deleted behaviour without more than a read of the test; the porter confirms each red against the code before deciding whether the idea or the test is stale. No cited test has zero assertions, and none is a stored-result-only pass: every green here ran this session.

## Reds, timeouts and flakes (first failure)

| test | class | ideas | first failure | re-run | ms |
|---|---|---|---|---|---|
| `seon.cluster.db-flow-test/a-filed-tasks-design-prove-implement-chain-merges-with-no-hand-act` | :timeout | 12 | step 2 ends done (line 2322) | red | 262700 |
| `seon.config-test/the-default-document-has-one-canonical-complete-location` | :red | 20,19 | the shipped EDN itself, not a second registry, covers every production attribute (line 154) | red | 37 |
| `seon.context-test/a-chain-of-one-is-judged-by-the-done-rule` | :red | 9 | its red proof is the done rule's refusal, never a merge's (line 289) | red | 2336 |
| `seon.context-test/a-merge-branchs-unmerged-entry-shows-in-the-block-until-resolved` | :red | 13 | theirs, main's version, shows its source (line ) | red | 1663 |
| `seon.context-test/a-warm-model-request-allocates-in-proportion-to-what-it-shows` | :red | 1 | a warm build allocates under two hundred bytes per character it shows (line 574) | red | 1853 |
| `seon.context-test/broken-tests-show-with-their-failures` | :red | 1 | my.task/current shows the same step (line 870) | red | 1774 |
| `seon.cost-estimate-test/no-ordinary-operation-grows-past-its-accepted-overrun` | :flaky | 16 |  (line 379) | pass | 29606 |
| `seon.cost-estimate-test/ordinary-operations-cost-no-more-than-their-estimates` | :red | 16 |  (line 372) | red | 16339 |
| `seon.help-test/help-names-only-installed-forms` | :red | 3 | every taught head resolves at the agent's REPL (line 111) | red | 793 |
| `seon.mcp-entrance-test/a-jvm-reply-keeps-its-duration-after-an-earlier-run` | :red | 4 |  (line 285) | red | 417 |
| `seon.mcp-entrance-test/an-sci-reply-is-what-the-run-saved` | :red | 4 | the row's evaluation answers the key it is about (line 253) | red | 3263 |
| `seon.mine-test/an-ended-attempt-is-graded-once-on-main` | :timeout | 14 | Test seon.mine-test/an-ended-attempt-is-graded-once-on-main exceeded its declared duration: 50385.319375 ms; bound 20000 ms. (line ) | red | 50404 |
| `seon.ref-test/all-prose-uses-one-reference-rule` | :flaky | 7 | Test seon.ref-test/all-prose-uses-one-reference-rule exceeded its declared duration: 8002.77825 ms; bound 5000 ms. (line ) | pass | 8041 |
| `seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart` | :red | 8 |  (line 223) | red | 47 |
| `seon.sci.forgive-test/forgiving-forgiven-text-changes-nothing` | :red | 8 | Generative check failed. Seed 20261010, 1 trials, shrunk in 0 nodes visited. (line ) | red | 19 |
| `seon.sentinel-test/a-sentinel-run-leaves-the-stores-branches-as-it-found-them` | :flaky | 16 | Test seon.sentinel-test/a-sentinel-run-leaves-the-stores-branches-as-it-found-them exceeded its declared duration: 6625.268625 ms; bound 5000 ms. (line ) | pass | 6661 |
| `seon.task-test/ready-answers-in-under-10-ms-for-fifty-leaves` | :flaky | 12 | the two reads over fifty leaves: P1 measured 4 ms at worst (line 263) | pass | 210 |
| `seon.turn.test-step-verdict-test/a-test-run-in-the-request-that-redefined-its-callee-answers-the-new-definition` | :red | 2 | the test run after the redefinition, in the same request, answers the definition the form made (line 110) | red | 2154 |

## Per idea

Proofs = distinct cited tests (wildcards expanded). A test cited by two ideas counts for both.

| # | idea | proofs | meaningful | red | timeout | flaky | verdict |
|---|---|---|---|---|---|---|---|
| 1 | The task's generated context is the whole briefing | 11 | 9 | 2 | 0 | 0 | 2 not green |
| 2 | Proofs run by themselves; a red assertion is data; green at the start  | 10 | 9 | 1 | 0 | 0 | 1 not green |
| 3 | The agent's orientation text is checked against the program | 8 | 7 | 1 | 0 | 0 | 1 not green |
| 4 | One reply is one data map; the path is total; the cost is on every rep | 9 | 7 | 2 | 0 | 0 | 2 not green |
| 5 | Refusals say what failed and what to do | 9 | 9 | 0 | 0 | 0 | all meaningful |
| 6 | The value printer: one budget, whole when small, cut with a runnable c | 11 | 11 | 0 | 0 | 0 | all meaningful |
| 7 | References in prose are found by one rule and resolved to what the age | 10 | 9 | 0 | 0 | 1 | 1 not green |
| 8 | The forgiving reader: prose is a comment, delimiters are repaired wher | 9 | 7 | 2 | 0 | 0 | 2 not green |
| 9 | One done rule judges every task, and the merge uses the same list | 8 | 7 | 1 | 0 | 0 | 1 not green |
| 10 | Contracts at every boundary, armed from the definition | 9 | 9 | 0 | 0 | 0 | all meaningful |
| 11 | Between turns: an unanswered wake is the only reason to run an agent | 9 | 9 | 0 | 0 | 0 | all meaningful |
| 12 | Tasks are data, tasks chain by `needs`, one reconciler closes the gap | 14 | 12 | 0 | 1 | 1 | 2 not green |
| 13 | Merge is a three-way over program keys that only a green gate advances | 12 | 11 | 1 | 0 | 0 | 1 not green |
| 14 | Turns to green, charged to the producer of what the agent was shown | 6 | 5 | 0 | 1 | 0 | 1 not green |
| 15 | Write-back: accepted entities become exact-span edits to files, proved | 12 | 12 | 0 | 0 | 0 | all meaningful |
| 16 | Estimate first; an overrun is a failing test | 6 | 3 | 1 | 0 | 2 | 3 not green |
| 17 | One model call, one attempt, failure is a value | 7 | 7 | 0 | 0 | 0 | all meaningful |
| 18 | The MCP entrance: two tools, string in, string out | 7 | 7 | 0 | 0 | 0 | all meaningful |
| 19 | Dials are facts; the shipped default is one decision per attribute | 7 | 6 | 1 | 0 | 0 | 1 not green |
| 20 | Datahike attributes are derived from the Malli entity schemas | 56 | 54 | 1 | 0 | 0 | 2 not green |

## Per idea after spec-reds

[spec-reds.md](spec-reds.md) judged the 12 reds:
- 5 were fixed or rewritten green and 3 were rewritten green: config, help, conflicts, chain-of-one, broken-tests and the two MCP tests.
- 4 stay red as valid spec that Seon fails: the warm-context cost, the strict cost table and the forgiving reader's two.
- 1 was retired: the SCI `test-vars` request-view test.

The rewritten and fixed vars were verified green on the candidate (check run 0831f2e66e03). The table above is the census run at bc1a20635 and is not edited. The rows below are only the ideas whose counts moved:

| # | proofs | meaningful | red (all `:spec-holds`) | timeout | flaky | verdict |
|---|---|---|---|---|---|---|
| 1 | 11 | 10 | 1 | 0 | 0 | 1 red spec: warm build cost |
| 2 | 9 | 9 | 0 | 0 | 0 | all meaningful (1 retired) |
| 3 | 8 | 8 | 0 | 0 | 0 | all meaningful |
| 4 | 9 | 9 | 0 | 0 | 0 | all meaningful (1 renamed) |
| 9 | 8 | 8 | 0 | 0 | 0 | all meaningful |
| 13 | 12 | 12 | 0 | 0 | 0 | all meaningful |
| 19 | 7 | 7 | 0 | 0 | 0 | all meaningful |
| 20 | 56 | 55 | 0 | 0 | 0 | 1 not green: `ns-test/name` in tests-idea-map.edn is a placeholder, not a test |

Unchanged:
- #8 is 7 of 9 meaningful. Its 2 reds are the reader's idempotence law and its example table.
- #16 is 3 of 6. Its red is the strict cost table, plus 2 flaky.
- #12 and #14 each carry one timeout.
- #7 carries one flaky.

## Ideas lacking a meaningful proof

Ideas with no cited deftest at all (their spec lives in prose and must be written as proofs before porting): none.

Weakest health (fewer than 70% of cited proofs meaningful): #16 (3/6).

Ideas whose cited proofs are all non-green: none.

