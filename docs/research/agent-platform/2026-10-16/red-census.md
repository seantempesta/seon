# Red census on HEAD b7d7632fc (2026-10-16)

Run: `bin/seon check --at b7d7632fc --include-long --test ...` over the 18 reported vars that exist in the tree. Result: run b3b8197abae8, executed 18, pass 98, fail 34, error 7, passed? false. All 18 are red (VERIFIED). Diagnostic: `data/clusters/default/logs/operator-check-20261016T071231.045.edn`. Full log: scratchpad `check-batch1.log`.

Not verified: the 15-var group from run 61344fcd437a (88474708b) and the 6-var group from run 23de9e1c0998 (c63a8eb22). Those runs are not in the default store (10 runs there, none match) and their var names were not in the lane brief, so they are NOT RUN.

Suspected causes are from `git log` on each test file. No bisection was run; the check queue was busy.

| Test (ns/var) | Red on HEAD | First failure | Suspected cause (commit) | Filed task | Group |
|---|---|---|---|---|---|
| my.root-test/a-jvms-first-facts-merge-follows-the-changed-facts | VERIFIED | `root_test.clj:90` 137.6 ms, bound 100 ms; `:91` alloc 80.3 MB, bound 64 MB | Reactive start P1-P3 or one-merge-base 4ca233c04 (touches root and merge schemas) | seon.merge (filed) | A merge/root |
| seon.cluster.db-flow-test/a-prove-step-whose-product-main-holds-merges-and-its-successor-forks-main | VERIFIED | `:1174` main merge nil, not :merged; `:1176-1177` successor not ready; `:1180` branch not deleted; duration 5435 ms > 5000 | Reactive start P4 62bb68f13 or P2 9a8d09431 (chain routes) | none | A chain/db-flow |
| seon.cluster.db-flow-test/a-refused-final-merge-bails-to-root-once-with-its-refusal | VERIFIED | `:2321` refusal type keyword? nil; duration 61637 ms > 60000; await event `merge-bail-green` timed out | Reactive start P3 8bdd673b2 or P4 62bb68f13 | none | A chain/db-flow |
| seon.cluster.db-flow-test/every-agent-start-has-a-task | VERIFIED | `:2195` (error and fail at `:2193`) | Reactive start P2 9a8d09431 | seon.task (filed) | A chain/db-flow |
| seon.cluster.merge-test/a-chain-merges-once-and-closes-every-attempt | VERIFIED | `merge_test.clj:1020` open merge request remains; `:1023` conflict-error expected, got nil | one-merge-base 4ca233c04 or 3c5f650a0 | seon.cluster.agent (filed) | A merge/root |
| seon.cluster.lazy-agents-test/idle-agents-wait-for-work-but-schedule-owners-need-their-timers | VERIFIED | `lazy_agents_test.clj:33` `agents-to-start` returns "waiting" which is expected absent | Unknown; the test was last touched 241b28434 (R1 vocabulary) and 838708cc8. Likely Reactive start P1 88474708b or P2 changing when waiting agents are launched | none | Single |
| seon.cluster.merge-scenarios-test/a-conflict-merge-step-resolves-the-conflict-and-merges-onto-main | VERIFIED | `:133-142` main's head does not hold resolution | 96d505ad0 (branch verification, conflict merge overwrites main) | MERGE-STEP card (docs) | intentional red, lane merge-conflict |
| seon.merge-test/a-retraction-on-the-branch-retracts-on-an-unchanged-main-only | VERIFIED (error) | error inside `program/changed-identities` expecting `#{[:seon.task/references ...]}` | 4ca233c04 (merge from fork point) | seon.merge (filed) | A merge/root |
| seon.config-test/the-default-document-has-one-canonical-complete-location | VERIFIED | `config_test.clj:153` dial attributes differ from shipped EDN set | 62bb68f13 or 3418b8d7b (config apply, root writes) | none | Single |
| seon.context-test/a-step-is-done-only-when-its-merge-would-accept | VERIFIED | `context_test.clj:718` judged step: red-test-error expected, got test-evidence-error | 11469958e or 9626357a5 (merge list as one derivation) | none | Single |
| seon.render.value-test/a-renderer-that-throws-still-prints-through-the-floor-and-says-large-value | VERIFIED | `value_test.clj:1454` no "large value" marker in shown output | 923123589 (display budget) or 4825cac93 | none | Single |
| seon.repl-differential-test/promoting-a-closure-costs-its-walk-and-the-callers-called | VERIFIED | `:270` alloc 53.9 MB, bound 38.3 MB; `:273` hub closure not sub-second | 57e359aa9 (reach walk stops at compiled callers) | none | Single (perf) |
| seon.sci.documentation-test/a-contract-mistake-refuses-and-doc-answers-its-contract | VERIFIED | `documentation_test.clj:270` body lacks the expected term | 40c9bbae5 or b773d1c7c | none | D documentation |
| seon.sci.documentation-test/documentation-is-returned-data-without-a-second-printed-copy | VERIFIED | `:93` doc keys include `:example`, expected set without it | 40c9bbae5 (dir lists deftests) or ea6617e3b | none | D documentation |
| seon.sci.documentation-test/retained-context-receives-the-current-repl-macro-through-its-core-alias | VERIFIED | `:222` macro var identity differs after update | ea6617e3b or 237721b6a | none | D documentation |
| seon.turn.batch-test/a-repl-require-is-written-back-in-the-ns-form-and-reindexes-equal | VERIFIED (error) | `git ls-tree failed in the checkout: not a git repository` | Suspected environmental: `git archive` snapshot has no `.git`, not a product red. Candidate change 3da13c834 | seon.cluster.source, seon.flow, my.program (filed, related) | C turn.batch |
| seon.turn.batch-test/a-repl-deftest-in-a-marked-namespace-is-written-back-and-reindexes-equal | VERIFIED (error) | same `git ls-tree` error | same as above | none | C turn.batch |
| seon.turn.batch-test/an-unchanged-committed-nilable-contract-is-admitted-and-written-back | VERIFIED (error) | same `git ls-tree` error | same as above | seon.flow (filed) | C turn.batch |

## Groups

- **A. Merge, root and chain (6 reds, one area):** `a-prove-step`, `a-refused-final-merge`, `every-agent-start`, `a-chain-merges-once`, `a-jvms-first-facts`, `a-retraction-on-the-branch`. These share the reactive-start and one-merge-base changes of 2026-10-15 and 2026-10-16 (88474708b through 62bb68f13; 4ca233c04). Bisect one of them (`a-chain-merges-once`) before assigning fixes.
- **C. turn.batch write-back (3 reds):** all error on the same `git ls-tree` call. Test: is `git` run inside a `git archive` checkout? If yes, the red is the check-mode harness, not HEAD code. Verify by running one of these on a real checkout before filing.
- **D. documentation (3 reds):** all in `seon.sci.documentation-test`, changed by 40c9bbae5 and ea6617e3b.
- **Singles:** lazy-agents, config, context, renderer, repl-differential. Each has its own suspected commit above.
- **Intentional:** `a-conflict-merge-step` is the merge-conflict lane's task.

## Not run

- The 15-var group (88474708b): db-flow, host, my.agent and my.root tests. Var names not in the brief and the run is not stored.
- The 6-var seon.program-test group (c63a8eb22): same.
- Bisection: not done. The check queue was busy and the batch took about 92 s of run time.
