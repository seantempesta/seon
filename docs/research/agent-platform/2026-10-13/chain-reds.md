---
title: "Chain reds on 251d40805: classification"
type: research
status: active
---

# Chain reds on 251d40805: classification (lane chain-reds, 2026-10-13)

Seven reds named in 251d40805's message, run once together in a check JVM at 251d40805 (all 7 red, 98 s).
Stored evidence: `data/clusters/default/logs/operator-check-*.edn` records red check runs only; the last
green claims come from commit messages. `bin/seon check --at` an older revision (ab893212d, 1a8aaabf7) does
not boot against today's check template ("Program deletion leaves 2 surviving referrers"), so bisection by
check runs is unavailable; the commits below are named from the code and the messages.

| Test | Class | Commit that changed the behavior | State |
|---|---|---|---|
| `seon.cluster.merge-test/an-end-message-naming-a-merge-task-is-not-a-request` | B | c1e0c97c9 (a send writes nothing unless it keeps; `seon.cluster.message/send!` with no routing answers `:no-graph`) | green: the test stores the end message through `seon.cluster.message/send-call`, as an agent's branch writes it; its task `my.program/merge-requests-refuses-its-own-result-on-main` retired (the proof is green) |
| `seon.cluster.merge-test/a-require-in-the-agents-user-namespace-lands-with-its-change` | C (design) | red by design since b6434001c (its proof commit) | stays red: `merge!` answers `:seon.source/write-back-error` `:one-file` for the agent's `user` namespace. Owner task `seon.cluster.source/an-agents-repl-scratch-namespace-never-blocks-its-landing` (already tagged): a scratch namespace is never program |
| `seon.cluster.db-flow-test/a-merge-refused-for-an-unrequired-namespace-relaunches-with-the-finding` | A | 68fd72410 (F67, root ruling option 1: `seon.turn.batch/auto-require!` requires the namespace, so no refusal exists) | delete; the surviving behavior is `seon.cluster.write-back-test/a-declaration-naming-an-unrequired-namespace-merges-with-its-ns-form-requiring-it`, and relaunch-after-refusal is `a-merge-refused-for-a-missing-contract-relaunches-with-the-refusal-shown`. In the patch |
| `seon.cluster.db-flow-test/an-ended-attempt-reaches-main-in-one-transaction-and-its-branch-is-deleted` | B, then C | 251d40805 ruling (a): a `:done` end merges only through an accepted candidate; the test ends `:done` on a red proof | patch converts it to the checkout-proof pattern (`done-checkout!`, `end-on-branch!` with work): the end, refinement and one copy now merge. Still red: the merge's publication records a fault from `seon.cluster.source/write-back!`, the branch is not deleted, and the body takes 6.8-8.6 s against the 5 s default bound |
| `seon.cluster.db-flow-test/a-merged-assignment-whose-graph-survived-a-crash-is-stopped-and-deleted` | B, then C | as above | patch converts it the same way: the merge and the graph stop now hold. Still red: the branch is not deleted (same publication fault) and the idle pass takes 227 ms against its 50 ms assertion |
| `seon.cluster.db-flow-test/a-prove-steps-red-product-merges-into-main` | B, then C | 251d40805: `seon.merge/merge-onto` reads the end from the source commit; `proved!` wrote p's end on main | patch writes p's end on p's branch. Still red: p's outcome stays absent and main lacks `twice-doubles` and `twice`: the Prove step's red product is not accepted. Cause not isolated (last green 6e8b157ed, 08:29) |
| `seon.cluster.db-flow-test/a-two-step-chains-implement-step-forks-main-holding-the-prove-steps-product` | C | same as the previous row (p never merges, so `i` is never assigned and `branch-i` is nil at the `active-branch-connection` call) | still red; fixed with the previous row |

## Counts

Before: 7 red. After: 6 red on HEAD plus this commit (1 green). With the patch applied: 5 red (A deletes one), of
which 2 now fail only at publication and timing, 2 at the Prove step's acceptance, 1 by design.

## For the owners

- The patch `docs/research/agent-platform/2026-10-13/chain-reds-db-flow.patch` edits `test/seon/cluster/db_flow_test.clj`,
  which lane chain-acceptance-4b holds; it applies on 251d40805 (`git apply --check`). Its probe commit
  41d17e41a (an unreferenced commit object) ran check run 2efbbe2b4384.
- Publication fault (Opus, `seon.merge/publish!` → `seon.cluster.source/write-back!`): after an accepted `:done`
  merge of `<space>-rows/twice`, the next pass's publication records an error whose operation is
  `seon.cluster.source/write-back!`, and the ended branch is never deleted. Its message was not captured; the
  next step is to pull the error entity in full in that test.
- Prove acceptance (Opus, `seon.cluster.source/prepare-merge!` / `seon.cluster.source/merge-verdict`): a Prove step
  ended `:done` on its branch with a red tagged product does not reach `:merged`. Next step: assert on
  `merge!`'s answer for `key-p` in `proved!` to see which refusal the verdict returns.
- The two converted tests need `:seon.test/long` reasons (two accepted merges, each a nested `seon.test/run`).
