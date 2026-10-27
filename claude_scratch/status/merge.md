# merge session status (rewritten in place)
Updated ~21:15. Main a617563977 is live (bin/update OK). Nothing running. No full suite has run since the reseed (c6b36bac67); per the owner I run `bin/test --all` on a clean main only after a pin bump, once after tonight's merges settle, and before a cutover (reasons go in claude_scratch/merge-log.md).

On main since 20:00: tests-are-facts, one-ms-record, wiki-reads-agents-and-readme, an-outside-agent-lives-until-done, a-daily-full-run-is-the-net (cron OFF; full-run-at not set), relevance-lab, registry-when-nothing-changed, prove-test-follows-result-tx, a-reused-red-cost-keeps-its-verdict, problems-check-ends-failed, agent-repl (holder/held gate), reach-step-builds-in-bulk, unarmed-needs-no-rebinding, print-lift-decided-once, reasons-in-docstrings, ctx-scorer-contract (+ctx-reverse-joins), config-reads-from-facts, jolt-keyed-by-source-group, an-outside-agent-proves-its-process-tests-at-the-judge, docs-problems-are-chased, dead-citations-repointed, contracts-on-main.
Pins: jolt 329c39f2, datahike 24e7ecda, konserve a4ad1305, lmdb 9ae760e9.

HOLD (owner: no agent run): ctx-run-results, results-ref-their-run-and-var, a-prove-is-a-part-of-the-page.
HELD (Jolt pin, one a day): a-one-file-change-builds-only-what-it-reaches (6c4f23a0), all-asleep (f6850ca8; to be superseded by jolt-compare's a-threaded-process-whose-every-thread-parks-ends-its-waits).
DROPPED: main-cost-proofs-are-green, an-unchanged-schema-registry-declares, dangling-asks-are-recorded-at-quiet (withdrawn).

Bounced for conflicts (owner merges main, reruns targeted tests, reports):
- process-tests: process-tests-reach-their-ops (server.clj), reconcile-env-once (reconcile.clj)
- test-facts: results-unread-is-an-error (results_test)
- health: an-old-word-is-a-problem (docs/server.md)
- merge-readiness: search-has-no-sibling-and-no-unmeasured-weights
- reach-index: cited-vars-carry-their-sections, every-var-cites-its-section, schema-entries-describe-themselves (in that order)
- the-old-word-check-reads: claude-scratch-is-the-scratch-space (AGENTS.md + hang notes)
Not yet seen: jolt/ctx-reverse-joins alone is inside ctx-scorer-contract (merged).
21:25 merged claude-scratch-is-the-scratch-space (notes/ -> claude_scratch/, merge-log is claude_scratch/merge-log.md now), an-old-word-is-a-problem, reconcile-env-once, process-tests-reach-their-ops, search-has-no-sibling, results-unread-is-an-error. Remaining bounced: reach-index's three citation branches.
21:30 RUNNING: bin/test --all on clean main (reason: Jolt pin bump 329c39f2 -> 7627453d, plus the one full run after tonight's merges). Do not merge tracked files until it ends. Merged just before: merge-declares-the-attributes-it-uses, a-threaded-process-whose-every-thread-parks-ends-its-waits (Jolt pin 7627453d), hang-tasks-filed.
- queued behind the full run: a-test-childs-line-always-reads 7c337bfbab (process-tests; 0.7 seon_done blocker, second half), a-one-file-change-builds-only-what-it-reaches (tasks/forks.md; take pin 7627453d on conflict)
