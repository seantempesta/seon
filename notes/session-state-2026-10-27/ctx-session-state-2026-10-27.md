# The ctx-atom session's branches, at the account switch (2026-10-27)

Every branch is in its own worktree under .worktrees/. None is pushed.

| branch | state | the one next step |
|---|---|---|
| jolt/a-cited-vars-tests-are-its-own-query (HEAD ebc2b91a79, main merged in) | verification running (build, contracts, prove, main/ctx/repl/agent tests) | when green, commit the task entries below and report ready to root |
| jolt/an-incremental-index-equals-the-full (e69c82d27e) | ready, verified (PROVE OK, main-test OK); reported | root merges |
| jolt/an-index-is-observed-at-the-cost-of-what-changed (b59c7f6f43) | ready, verified (CONTRACTS, PROVE, main-test OK); reported | root merges (it files task seon.reconcile/one-observe-descends-once, with its numbers) |

## jolt/a-cited-vars-tests-are-its-own-query

1. seon.render.ui/cited-of: a cited var's tests are one query (deftests whose form names v, newest by the refs datom's tx, the name breaking a tie, v left out, cut at :seon.config.ctx/callers).
   - At 60 / 600 newer callers it costs 176k / 330k ticks, against 401k / 3.83M for ctx's window filtered by kind.
   - Proof: seon.ctx-test/a-cited-vars-tests-are-shown-past-its-callers-window.
2. (my.agent/tests-reaching 'v): every test whose reach includes v, nearest first, each with its last result.
   - seon.program/reached-by is the reverse walk reach-step's re-fold uses, and seon.program/tests-reaching is the tests among it.
   - seon.agent/branch-graph, factored from test-plan and keyed by db value, supplies the graph; seon.agent/judges? is shared with test-plan; seon.proofs/latest-status is the one status read.
   - The cited render names the form (:reaching).
   - Proof: seon.program-test/the-tests-reaching-a-var-are-what-its-change-selects (equals the tests whose reach digest moves).
   - Cost on the program's graph: 2k ticks (related-view, 3 tests), 15k (bytes-str, 33), 48k (lead-sentence, 77; its pinning summary test is 13th), 1.55M (cut-words and print-value, about 855).
   - In a process test's harness the root imports no test forms, so it answers []. No repl assertion is possible there.
3. live-agents adds the :work/:whole clause, replacing the (my.agent/callers 'ns/f) mention with (my.agent/tests-reaching 'ns/f), after this branch merges. seon.repl-test/the-template-instructions-run needs the name to exist first.
4. Measured and not built (root agreed):
   - A depth-2 reach walk in the render: 0.1–1.3M ticks a page, and it missed every pinning test.
   - One-hop callees on the page: 1 of the uncited fix sites gained, for 300–650 tokens.

## Queued, not started: related-view's hints (root)

seon.context/related-view's :read and :more hint strings print first on every page, about 330 chars, ahead of the sections. Put them after the lists, or drop them if the cut's own "how to read the rest" already says it, whichever leaves one statement of it. Measure tokens before and after on two of the audit's pages. The task entry is seon.context/related-view-states-how-to-read-once, on the cited-tests branch.

## Other numbers already filed

- seon.reconcile/one-observe-descends-once, on the index branch: the observe on LMDB is 1.11M ticks / 5.8 MB at 10 tasks, 1.03M / 4.6 MB at 500 and 1.47M / 5.8 MB at 1,000, of which 84 cached descents (5–11k ticks each) are the change's fixed part. A multi-prefix diff would save about 1M ticks a turn.
- Datahike, cost proofs: the test store keeps no node at an address, so a datoms-diff proof that rests on skipping shared subtrees must measure on an LMDB store of its own (docs/cost.md#how-we-measure).
