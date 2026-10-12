---
title: Task database survey
type: research
status: final
basis: HEAD 45bfab18e (resources/seon/tasks, src, test, script, bench, src-needle, evals taken from git archive HEAD). The working tree (8 task files and 5 test files with uncommitted edits) gives identical totals. default not queried; the survey is static.
method: scratchpad/survey/task_backlog.clj, run as `SURVEY_ROOT=<git archive HEAD> clojure -M task_backlog.clj` (writes task-backlog.edn); table, body, phase and dump scripts in the same directory render the sections. Test tags are read from `:seon/tasks` metadata on deftest names with clojure.core/read (read-cond :clj, `::alias/` rewritten to `:x-alias/` for structure only).
---

## For root

1. **Proofless:** 629 of 802 tasks (78%) have no deftest tagged `:seon/tasks`. 173 have one (231 tagged deftests, 173 distinct task symbols in test/, 0 stale tags). Most proofless: seon.sci.eval 60, seon.test 46, seon.db 37, seon.turn 36, my.program 29, seon.fn 27, seon.operator 23.
2. **Subject gone, retire after one read each (5):** seon.batch/every-world-is-built-by-one-constructor (2 proofs; seon.turn.world deleted), seon.db/an-or-clause-joined-to-a-pattern-costs-a-scan-per-binding (seon.turn/code-currency gone), seon.merge/an-accepted-merge-is-published-by-the-next-pass (seon.cluster.source/land-held! gone after M6), seon.test/a-reused-answer-names-a-recorded-run (my.program/proposal! gone; 1 proof), seon.test/reds-on-head-9d6b392b6-in-task-reaching-tests (dated red; steps! gone).
3. **Stale must-read, subject alive (5), re-point:** my.program/an-agent-cannot-see-or-choose-its-change (overrides is now seon.program/overrides). Four tasks name seon.print.value/bounded-text, which is not there; the function lives as private seon.render.value/bounded-text and seon.error/bounded-text, so the second printer those tasks forbid still exists and they are not done.
4. **Intent names absent symbols (78 tasks, 125 references):** 14 name only test vars (proofs not yet written, not moot); 64 name other symbols (new subjects or renames), each needs a read.
5. **Duplicates: none substantive.** The 45 pairs at intent Jaccard 1.0 are the 10 contract-generator tasks (`seon.ai/contract-output-observed?` and nine siblings) sharing template text over distinct functions. 45 identical must-read sets cover 150 tasks; they are subject hubs (seon.test/run 18, seon.cluster.store/datahike-configuration 12), not duplicates.
6. **Retired words in intent prose (regex, untriaged):** row 114 tasks, world 62, settle 46, attempt 44, land 40, custody 13, squash 3. "row" includes ordinary table-row prose.
7. **Phase tokens:** 33 open tasks cite a token §0 marks done (R1 6, P1 6, M1 5, R2 4, M5 3, E2 3, others 1–2). Three cite superseded U4, U5 and U7, which the plan reuses under M17, M16 and M3. Five cite G1 or G12, absent from §0's table. 21 cite AW or H, which §0 defers to their documents.
8. **Retire today: 5 (subject gone, after one read) and 0 duplicates.** Moot candidates per namespace are in the table's last two columns and the Notes.
9. **Key drift:** `:seon.task/needs` on 18 tasks; the key is not in AGENTS.md's task grammar.

Already filed: the stale-reference tasks above are themselves filed (`my.program/an-agent-cannot-see-or-choose-its-change`, `seon.print.value/printed-copies-stored-or-sent-instead-of-values`, and the other 8 in the Notes); the contract tasks come from `seon.contracts/task`.
Not filed: retirement of the 5 subject-gone tasks and re-pointing of the 5 stale must-read entries; a check that resolves `:seon.task/must-read` and intent references at ingestion (**unverified** whether ingestion already reports them, since this survey did not run ingestion); a task to remove the undocumented `:seon.task/needs` key or to rewrite the 33 phase-token intents to name their document.

## Table

Per task file (one namespace each). Sorted by proofless count. "Moot (must-read)" is the 10 must-read entries absent at HEAD; "Moot (any ref)" is any absent backticked or must-read symbol (88). Both include stale references, so they are upper bounds on retirement.


| Namespace (task file) | Open | With proof | Without proof | Moot (must-read) | Moot (any ref) |
|---|---:|---:|---:|---:|---:|
| seon.sci.eval | 81 | 21 | 60 | 0 | 9 |
| seon.test | 57 | 11 | 46 | 2 | 12 |
| seon.db | 48 | 11 | 37 | 1 | 5 |
| seon.turn | 49 | 13 | 36 | 0 | 4 |
| my.program | 34 | 5 | 29 | 1 | 3 |
| seon.fn | 30 | 3 | 27 | 0 | 0 |
| seon.operator | 25 | 2 | 23 | 0 | 2 |
| seon.cluster.source | 24 | 7 | 17 | 0 | 2 |
| seon.error | 19 | 3 | 16 | 0 | 0 |
| seon.schema | 16 | 2 | 14 | 0 | 0 |
| seon.repl | 14 | 1 | 13 | 0 | 0 |
| seon.turn.batch | 13 | 0 | 13 | 0 | 0 |
| seon.cluster | 13 | 1 | 12 | 0 | 0 |
| seon.render.value | 12 | 0 | 12 | 1 | 1 |
| seon.render | 11 | 0 | 11 | 0 | 0 |
| seon.cluster.agent | 14 | 4 | 10 | 0 | 5 |
| seon.test.runner | 13 | 3 | 10 | 0 | 1 |
| my.test | 11 | 2 | 9 | 0 | 1 |
| seon.instrument | 10 | 1 | 9 | 0 | 0 |
| seon.render.web | 14 | 5 | 9 | 0 | 0 |
| seon.sci.kernel | 10 | 1 | 9 | 0 | 1 |
| seon.task | 17 | 8 | 9 | 0 | 5 |
| seon.print | 9 | 1 | 8 | 0 | 0 |
| seon.cluster.branch-evidence | 7 | 0 | 7 | 0 | 1 |
| seon.cluster.registry | 8 | 1 | 7 | 0 | 1 |
| seon.cluster.store | 9 | 2 | 7 | 0 | 0 |
| seon.context | 15 | 8 | 7 | 0 | 4 |
| seon.error.refusal | 7 | 1 | 6 | 0 | 1 |
| seon.flow | 8 | 2 | 6 | 0 | 1 |
| seon.maintenance | 6 | 0 | 6 | 0 | 0 |
| seon.test-support | 6 | 0 | 6 | 0 | 0 |
| my.agent | 5 | 0 | 5 | 0 | 0 |
| seon.fault | 5 | 0 | 5 | 0 | 1 |
| seon.merge | 8 | 3 | 5 | 1 | 6 |
| seon.profile | 7 | 2 | 5 | 0 | 1 |
| seon.program | 7 | 2 | 5 | 0 | 1 |
| seon.schedule | 5 | 0 | 5 | 0 | 0 |
| seon.ai | 5 | 1 | 4 | 0 | 0 |
| seon.cluster.message | 5 | 1 | 4 | 0 | 1 |
| seon.fn.analyzer | 4 | 0 | 4 | 0 | 0 |
| seon.mcp | 6 | 2 | 4 | 0 | 1 |
| seon.render.test | 4 | 0 | 4 | 0 | 0 |
| seon.render.var | 4 | 0 | 4 | 0 | 0 |
| seon.schema.datahike | 5 | 1 | 4 | 0 | 0 |
| seon.sci.arm | 5 | 1 | 4 | 0 | 0 |
| seon.sci.reader | 4 | 0 | 4 | 0 | 1 |
| my.edit | 3 | 0 | 3 | 0 | 0 |
| seon.batch | 7 | 4 | 3 | 1 | 3 |
| seon.call-preparation | 3 | 0 | 3 | 0 | 0 |
| seon.cluster.boot | 4 | 1 | 3 | 0 | 0 |
| seon.config | 5 | 2 | 3 | 0 | 0 |
| seon.print.value | 3 | 0 | 3 | 3 | 3 |
| seon.problems | 5 | 2 | 3 | 0 | 2 |
| seon.ref | 4 | 1 | 3 | 0 | 2 |
| seon.sci.branch-execution-test | 3 | 0 | 3 | 0 | 0 |
| seon.test.platform | 5 | 2 | 3 | 0 | 1 |
| my.fs | 2 | 0 | 2 | 0 | 0 |
| my.task | 2 | 0 | 2 | 0 | 1 |
| seon.bootstrap | 3 | 1 | 2 | 0 | 1 |
| seon.contracts-compile-test | 2 | 0 | 2 | 0 | 0 |
| seon.dev.hook | 2 | 0 | 2 | 0 | 0 |
| seon.dev.mcp | 2 | 0 | 2 | 0 | 0 |
| seon.documentation | 2 | 0 | 2 | 0 | 0 |
| seon.effect | 2 | 0 | 2 | 0 | 0 |
| seon.health | 2 | 0 | 2 | 0 | 0 |
| seon.render.ns | 2 | 0 | 2 | 0 | 0 |
| seon.sci.forgive | 3 | 1 | 2 | 0 | 0 |
| seon.sci.lookup | 4 | 2 | 2 | 0 | 0 |
| seon.turn.world | 2 | 0 | 2 | 0 | 1 |
| seon.background | 1 | 0 | 1 | 0 | 0 |
| seon.cache | 1 | 0 | 1 | 0 | 0 |
| seon.cluster.host | 5 | 4 | 1 | 0 | 0 |
| seon.cluster.instruction | 1 | 0 | 1 | 0 | 0 |
| seon.cluster.status | 1 | 0 | 1 | 0 | 0 |
| seon.cluster.wake | 1 | 0 | 1 | 0 | 0 |
| seon.dev.markdown | 1 | 0 | 1 | 0 | 0 |
| seon.eval.drive | 1 | 0 | 1 | 0 | 1 |
| seon.html-views | 1 | 0 | 1 | 0 | 0 |
| seon.render.transcript | 2 | 1 | 1 | 0 | 0 |
| seon.schema.edn | 2 | 1 | 1 | 0 | 0 |
| seon.sci.admit | 1 | 0 | 1 | 0 | 0 |
| seon.test.accretion | 1 | 0 | 1 | 0 | 0 |
| seon.test.cache | 1 | 0 | 1 | 0 | 0 |
| seon.vocabulary | 1 | 0 | 1 | 0 | 1 |
| seon.web.jvm | 1 | 0 | 1 | 0 | 0 |
| seon.census | 1 | 1 | 0 | 0 | 1 |
| seon.competency | 9 | 9 | 0 | 0 | 0 |
| seon.fs | 1 | 1 | 0 | 0 | 0 |
| seon.interpreted-census-test | 1 | 1 | 0 | 0 | 0 |
| seon.print-census-test | 1 | 1 | 0 | 0 | 0 |
| seon.reconcile | 1 | 1 | 0 | 0 | 0 |
| seon.source-census-test | 2 | 2 | 0 | 0 | 0 |
| seon.state-census-test | 1 | 1 | 0 | 0 | 0 |
| seon.test-census-test | 1 | 1 | 0 | 0 | 0 |
| seon.test-failure-facts-test | 1 | 1 | 0 | 0 | 0 |
| **Total** | 802 | 173 | 629 | 10 | 88 |


## Notes

**Method and limits.** Everything is static, read from the HEAD archive. A symbol counts as resolving when a `def*` form of that name exists, top-level or nested, in a tracked first-party file under src, test, script, bench, src-needle or evals; reference-code, docs, tmp, data and scratch copies are excluded. Library symbols (datahike, sci) are not checked. Intent symbols are backticked `ns/name` spans with a dotted namespace; keywords are not checked. A test var named in an intent with no deftest yet counts as absent, which is why the test-var split matters. Phase statuses come from the `docs/seon/plan.md` §0 table as read at HEAD. Tokens such as P1, R2 or E2 name different documents, so a token's status is the table's status for that letter and number, which can be wrong for a task citing another document. Duplicate pairs use word sets of 3+ letters in prose (backticks removed), threshold 0.6. Retired-word counts are regex matches in prose, not triaged. No default query was made, so no live numbers appear here.

**Reconciling with plan §0.** §0 reports 783 tasks, 155 with a proof, 180 distinct tags and 25 stale tags. At HEAD this survey finds 802 tasks, 173 with a proof, 173 distinct tags and 0 stale tags. The stale tags §0 counts are no longer present; the task count grew.

**Must-read entries absent at HEAD, classified.**

| Task | Proofs | Absent must-read | Classification |
|---|---:|---|---|
| `my.program/an-agent-cannot-see-or-choose-its-change` | 0 | `my.program/overrides` | stale: now `seon.program/overrides` |
| `seon.batch/every-world-is-built-by-one-constructor` | 2 | `seon.turn.world/agent-world`, `jvm-world` | subject gone (namespace deleted); candidate to retire after reading its 2 proofs |
| `seon.db/an-or-clause-joined-to-a-pattern-costs-a-scan-per-binding` | 0 | `seon.turn/code-currency` | subject gone; candidate to retire or re-point |
| `seon.merge/an-accepted-merge-is-published-by-the-next-pass` | 0 | `seon.cluster.source/land-held!` | subject gone (M6); its successor `seon.merge/publish!` is also absent at HEAD |
| `seon.print.value/a-contract-refusal-prints-a-generated-example-of-the-whole-schema` | 0 | `seon.print.value/bounded-text` | stale ns: `bounded-text` is a private defn in `seon.render.value` and `seon.error`; the printer it forbids is live |
| `seon.print.value/printed-copies-stored-or-sent-instead-of-values` | 0 | same | stale ns, still live |
| `seon.print.value/ad-hoc-text-clips-outside-the-printer` | 0 | same | stale ns, still live |
| `seon.render.value/bounded-text-survives-in-the-render-namespace` | 0 | same | stale ns; its claim holds at HEAD |
| `seon.test/reds-on-head-9d6b392b6-in-task-reaching-tests` | 0 | `my.program/prepare-merge!`, `seon.task/steps!` | dated to 9d6b392b6; `prepare-merge!` is now `seon.cluster.source/prepare-merge!`; `steps!` is gone |
| `seon.test/a-reused-answer-names-a-recorded-run` | 1 | `my.program/proposal!` | subject gone; candidate to retire after reading its proof |

**Surprises.** (1) The four bounded-text tasks are not moot. They name a namespace that never held the function, and the function they forbid still exists in two places. (2) The 33 tasks citing a done phase are the largest cleanup candidate after proofs: each is open while its phase is done, and each needs its proof checked before retirement. (3) All 95 task files read as EDN; each is one vector of maps, and no file failed to read.

**Unverified.** Whether ingestion already reports unresolved must-read or intent references; whether the 14 test-var references will become real deftests; whether the 33 done-phase tasks have green proofs (not run; default is behind HEAD and was not queried).

### Must-read symbols absent at HEAD (raw list, 10 tasks)

- `my.program/an-agent-cannot-see-or-choose-its-change` (0 tagged proofs): absent `my.program/overrides`
- `seon.batch/every-world-is-built-by-one-constructor` (2 tagged proofs): absent `seon.turn.world/agent-world`, `seon.turn.world/jvm-world`
- `seon.db/an-or-clause-joined-to-a-pattern-costs-a-scan-per-binding` (0 tagged proofs): absent `seon.turn/code-currency`
- `seon.merge/an-accepted-merge-is-published-by-the-next-pass` (0 tagged proofs): absent `seon.cluster.source/land-held!`
- `seon.print.value/a-contract-refusal-prints-a-generated-example-of-the-whole-schema` (0 tagged proofs): absent `seon.print.value/bounded-text`
- `seon.print.value/printed-copies-stored-or-sent-instead-of-values` (0 tagged proofs): absent `seon.print.value/bounded-text`
- `seon.print.value/ad-hoc-text-clips-outside-the-printer` (0 tagged proofs): absent `seon.print.value/bounded-text`
- `seon.render.value/bounded-text-survives-in-the-render-namespace` (0 tagged proofs): absent `seon.print.value/bounded-text`
- `seon.test/reds-on-head-9d6b392b6-in-task-reaching-tests` (0 tagged proofs): absent `my.program/prepare-merge!`, `seon.task/steps!`
- `seon.test/a-reused-answer-names-a-recorded-run` (1 tagged proofs): absent `my.program/proposal!`

### Intent-only absent references (78 tasks)

Of these 78 tasks, 14 name only test vars (proofs not yet written, so not moot) and 64 name other absent symbols (new subjects or renamed ones; each needs a read). 125 absent references in all.

- `my.program/the-map-argument-proof-errors-reading-its-reply` (0 proofs): `my.program/diff-with-a-map-argument-is-refused-as-a-context`
- `my.program/restore-returns-an-entity-to-its-merge-base-value` (0 proofs): `my.program/restore!`, `my.program-test/restore-returns-an-entity-to-its-merge-base-value`
- `my.task/task-creation-is-not-an-agent-call` (0 proofs): `my.task/refine!`
- `my.test/a-check-of-named-identities-answers-a-reused-reds-failure-reports` (1 proofs): `my.test/a-check-failure-shows-no-message`, `seon.turn/a-red-proof-carries-no-reason`
- `seon.batch/a-request-cost-counts-only-the-evaluation-arm` (1 proofs): `seon.pass-cost-test/a-warm-requests-reported-allocation-is-within-twice-its-measured-window`
- `seon.batch/an-unchanged-interpreted-definition-shows-no-cost-entry` (4 proofs): `seon.batch/an-entrances-self-time-is-its-forms-work`
- `seon.bootstrap/help-evidence-test-red-on-head` (0 proofs): `seon.bootstrap/help-evidence-is-stale-after-an-unrelated-write`, `seon.bootstrap/help-evidence-test-red-on-head`
- `seon.census/a-census-reads-the-program-its-branch-holds` (1 proofs): `seon.problems/problems-prose-carries-no-date-or-file-line`, `seon.census/docstrings`
- `seon.cluster.agent/each-intent-names-one-roster-state` (0 proofs): `seon.cluster.db-flow-test/a-cancelled-assignment-closes-without-a-task-for-root`
- `seon.cluster.agent/a-fresh-agents-environment-costs-one-fork` (1 proofs): `seon.sci.eval/fork-for-agent`
- `seon.cluster.agent/a-capped-attempt-has-no-relaunch` (0 proofs): `my.root/relaunch!`, `seon.cluster.db-flow-test/a-capped-attempt-relaunches-only-by-roots-fact`
- `seon.cluster.agent/the-real-chain-runs-with-no-nudges` (0 proofs): `seon.cluster.db-flow-test/a-green-first-step-ends-itself-and-its-end-starts-the-next-from-its-commit`, `seon.vocabulary/the-merge-path-names-no-retired-identifier`
- `seon.cluster.agent/a-join-forks-main-after-both-needs-merge` (0 proofs): `seon.cluster.db-flow-test/a-join-forks-main-after-both-needs-merge`
- `seon.cluster.branch-evidence/a-reaching-red-is-proven-red-before-by-its-result-at-the-base-key` (0 proofs): `seon.test.runner/results-at`, `seon.turn/a-reached-test-unrun-or-red-on-main-does-not-block-done`, `seon.cluster.source/a-test-never-run-on-the-target-is-not-blamed-on-the-merge`, `seon.cluster.branch-evidence/the-branch-evidence-gate-rarely-sees-red-before-on-main`
- `seon.cluster.message/root-offers-a-sent-message-once-and-merges-its-record` (0 proofs): `seon.turn/a-held-task-opens-no-model-turn`
- `seon.cluster.registry/a-merged-base-older-than-the-window-survives-collection` (0 proofs): `seon.cluster.registry-test/a-merged-base-older-than-the-window-survives-collection`
- `seon.cluster.source/a-docstring-only-change-lands-without-interpreting-its-namespace` (2 proofs): `seon.sci.eval/remaining-interpreted-only-test-differences`
- `seon.cluster.source/an-agents-scratch-schema-never-rides-its-landing` (0 proofs): `seon.cluster.source/an-agents-repl-scratch-namespace-never-blocks-its-landing`
- `seon.context/the-current-state-shows-the-branch-diff-since-its-merge-base` (0 proofs): `seon.context-test/the-current-state-shows-the-branch-diff-since-its-merge-base`, `my.task/refine!`
- `seon.context/a-red-with-no-base-result-is-unknown` (0 proofs): `seon.test.runner/results-at`, `seon.context/a-proof-green-before-the-work-is-not-done`, `seon.test.runner-test/results-at-reads-each-tests-newest-result-at-its-key`
- `seon.context/every-agent-sees-where-its-reds-came-from` (0 proofs): `my.test/reds`
- `seon.context/a-held-steps-instructions-are-shown-whole-in-its-context` (0 proofs): `seon.context-test/a-held-steps-instructions-are-shown-whole-in-its-context`
- `seon.db/a-deftest-after-a-same-request-refer-is-refused-by-digest` (0 proofs): `seon.db/a-cross-pass-alias-retarget-leaves-dependant-digests-stale`
- `seon.db/a-reopened-commit-carries-its-content-revisions` (1 proofs): `seon.db/remember-revisions!`, `seon.db/content-revision`, `seon.db/recorded-revisions`
- `seon.db/an-explicit-eid-outside-the-allocators-range-is-refused` (1 proofs): `seon.db/entities-born-on-two-branches-of-one-commit-have-different-ids`
- `seon.db/entities-born-on-two-branches-of-one-commit-have-different-ids` (1 proofs): `seon.db/an-explicit-eid-outside-the-allocators-range-is-refused`
- `seon.error.refusal/an-interpreted-callback-is-blamed-on-the-compiled-function-calling-it` (1 proofs): `seon.cluster.evaluate-source-test/an-agents-positional-call-of-a-my-tool-records-no-fault`
- `seon.eval.drive/unreachable-definitions-remain-in-src` (0 proofs): `seon.turn.world/test-agent-world`
- `seon.fault/a-fault-on-a-branch-is-recorded-once-there-and-counted-on-main-at-the-next-auto-merge` (0 proofs): `seon.fault/a-fault-on-a-branch-is-recorded-once-there-and-counted-on-main-at-the-next-auto-merge`
- `seon.flow/a-restart-starts-the-same-attempt-on-its-existing-branch` (0 proofs): `seon.flow/start-flow!`, `seon.flow/db<->flow`
- `seon.mcp/a-fresh-agents-first-eval-request-acquires-its-branch-cheaply` (2 proofs): `seon.cluster.agent/a-fresh-agents-environment-costs-one-fork`, `seon.mcp-test/a-fresh-agents-first-eval-request-acquires-its-branch-cheaply`
- `seon.merge/an-attempts-cluster-facts-copy-onto-main-by-identity` (11 proofs): `seon.db/entities-born-on-two-branches-of-one-commit-have-different-ids`, `seon.db/an-explicit-eid-outside-the-allocators-range-is-refused`
- `seon.merge/a-conflict-keeps-its-three-values-on-the-merge-branch` (0 proofs): `seon.merge-test/a-conflict-keeps-its-three-values-on-the-merge-branch`
- `seon.merge/a-merge-whose-reached-definition-moved-writes-nothing` (0 proofs): `seon.cluster.merge-test/a-merge-whose-reached-definition-moved-writes-nothing`
- `seon.merge/a-sibling-merge-that-breaks-a-proof-undoes-done` (0 proofs): `seon.merge/update!`, `seon.merge/update-tx`, `seon.cluster.merge-test/a-sibling-merge-that-breaks-a-proof-undoes-done`
- `seon.merge/main-keeps-the-merge-results-after-the-branch-is-deleted` (0 proofs): `seon.test.runner/results-at`, `seon.cluster.merge-test/main-keeps-the-merge-results-after-the-branch-is-deleted`
- `seon.operator/a-booting-jvms-class-directory-is-pruned-under-it` (0 proofs): `seon.operator-move-test/a-class-directory-a-live-process-names-is-never-pruned`
- `seon.operator/a-check-red-main-holds-red-does-not-refuse` (0 proofs): `seon.operator/check-verdict`, `seon.test.runner/results-at`, `seon.operator/bin-lane-commit-cannot-accept-a-test-already-red-on-head`, `seon.operator-test/a-check-red-main-holds-red-does-not-refuse`
- `seon.problems/problems-prose-carries-no-date-or-file-line` (1 proofs): `seon.census/a-census-reads-the-program-its-branch-holds`
- `seon.problems/two-outcomes-at-one-key-are-a-nondeterministic-test` (0 proofs): `seon.test.runner/results-at`, `seon.problems-test/two-outcomes-at-one-key-are-a-nondeterministic-test`
- `seon.profile/call-counters-are-jvm-wide-so-counting-tests-race` (0 proofs): `seon.render/acquire-context!`
- `seon.program/cluster-attributes-are-the-declared-cluster-entities` (1 proofs): `seon.task/task`, `seon.task/assignment-row`, `seon.error.occurrence/occurrence`
- `seon.ref/a-doc-section-test-takes-eighteen-seconds` (0 proofs): `seon.test.runner/a-timed-out-unrelated-test-restarts-in-every-eval-request`
- `seon.ref/a-task-target-reads-as-an-unresolved-reference` (0 proofs): `seon.competency/area-is-contracted-typed`, `seon.competency/quantity-schema-then-function-typed`
- `seon.sci.eval/a-trivial-form-makes-fifty-thousand-armed-calls` (1 proofs): `seon.instrument/hot-internals-pay-armed-validation`, `seon.sci.eval/own-binding?`
- `seon.sci.eval/a-fork-snapshots-every-program-var` (0 proofs): `seon.sci.eval/base-bindings`
- `seon.sci.eval/an-alias-required-at-the-repl-is-missing-when-a-test-row-installs` (0 proofs): `seon.sci.eval/interpret-test!`
- `seon.sci.eval/a-test-namespace-an-agent-requires-is-interpreted-not-host-loaded` (3 proofs): `seon.sci.eval/interpret-test!`, `seon.sci.eval/sci-binding-of-a-host-dynamic-var-never-reaches-host-code`
- `seon.sci.eval/a-dev-namespace-test-cannot-run-interpreted-on-a-cluster` (2 proofs): `seon.sci.eval/a-script-namespace-contract-naming-a-local-def-crashes-publication`, `seon.dev.markdown/repository-doc-lints-are-red-on-head`
- `seon.sci.eval/a-fresh-fork-of-an-unchanged-program-acquires-from-the-base` (1 proofs): `seon.sci.eval/program-identity`, `seon.sci.eval/program-identity-cache`, `seon.sci.eval/remember-program-identity!`, `seon.sci.eval/inherited-basis`, `seon.sci.eval/revision-basis`, `seon.sci.eval/watch-program-identity!`, `seon.sci.eval/program-cache-policy`, `seon.sci.eval/refusal-recording-cache`
- `seon.sci.eval/a-script-namespace-contract-naming-a-local-def-crashes-publication` (1 proofs): `seon.sci.eval/a-dev-namespace-test-cannot-run-interpreted-on-a-cluster`
- `seon.sci.eval/equal-programs-on-two-branches-share-one-program-context` (1 proofs): `seon.schema/projection`
- `seon.sci.eval/a-multimethod-and-its-test-in-one-request-acquire-on-a-branch` (0 proofs): `seon.sci.eval/a-defmethod-is-a-declaration-on-a-branch`
- `seon.sci.kernel/a-file-function-that-calls-slurp-cannot-be-redefined` (1 proofs): `seon.ref/a-missing-anchor-ranks-headings-by-shared-prefix`, `seon.sci.kernel/host-function-tools`
- `seon.sci.reader/schema-entities-lose-their-declaring-namespace` (0 proofs): `seon.loop-proof-test/virtual-loop-end-to-end`
- `seon.task/open-tasks-with-all-proofs-green-are-not-flagged` (0 proofs): `seon.error/an-unresolved-symbol-shows-an-empty-neighbour-list`, `my.program/source-shows-code-as-an-escaped-string`, `seon.bootstrap/help-*`
- `seon.task/a-task-evidence-path-names-no-gitignored-file` (1 proofs): `seon.fn.analyzer/local-root-dependency-analysis-goes-stale`
- `seon.task/a-sent-back-end-reopens-its-target` (0 proofs): `seon.task/done`
- `seon.task/a-refinement-on-a-branch-reaches-main-only-at-the-end` (4 proofs): `my.task/refine!`
- `seon.task/a-done-branch-merges-with-no-merge-request-written-by-the-agent` (0 proofs): `seon.cluster.agent/a-running-agents-refinement-reaches-main-at-the-next-pass`, `my.root-test/a-done-branch-merges-with-no-merge-request-written-by-the-agent`
- `seon.test/an-unchanged-red-is-reused-after-the-agent-redefines-what-it-reaches` (1 proofs): `tq.land/area`
- `seon.test/a-test-world-has-no-agent` (0 proofs): `my.task/steps`
- `seon.test/a-test-green-on-a-fresh-jvm-is-green-on-the-live-root` (1 proofs): `seon.cluster.merge-test/a-green-proposal-lands-on-main-and-disk-in-one-call`, `seon.turn-continue-test/a-task-that-ends-green-sends-root-one-merge-request`
- `seon.test/an-observed-change-a-script-test-reaches-does-not-refuse-the-step` (0 proofs): `my.agents.root/old-mb`
- `seon.test/a-test-run-is-an-attempt-on-the-path` (0 proofs): `seon.batch/world`
- `seon.test/the-merge-branch-key-is-seon-source-merge-branch` (0 proofs): `seon.cluster/source-prepare`
- `seon.test/a-changed-dial-reruns-a-test-that-reads-it` (0 proofs): `seon.test.selection-test/a-changed-dial-reruns-a-test-that-reads-it`
- `seon.test/a-base-run-is-kept-on-the-agents-branch` (0 proofs): `seon.test-test/a-base-run-is-read-at-the-next-step-without-running`
- `seon.test/a-test-reading-task-tags-reruns-when-a-test-is-tagged` (1 proofs): `seon.context/a-step-proof-cannot-observe-its-agents-test-evidence`
- `seon.test/since-previous-run-narrowing-has-no-production-caller` (0 proofs): `seon.test/an-observed-change-a-script-test-reaches-does-not-refuse-the-step`
- `seon.test.platform/platform-code-has-no-repl-proof-path-lanes-use` (0 proofs): `seon.test.platform/a-schema-resource-change-cannot-be-proven-by-check-platform`, `seon.test.platform/a-test-the-branch-adds-is-not-eligible-in-the-check`, `seon.test.platform/a-refused-platform-check-names-no-diagnostic`
- `seon.test.runner/the-first-reach-digest-after-a-change-costs-what-changed` (1 proofs): `seon.test.runner/tests-reaching-rebuilds-the-whole-program-reach-graph-per-change`
- `seon.turn/a-reached-red-test-keeps-a-task-from-ending-done` (1 proofs): `seon.batch/an-unchanged-interpreted-definition-shows-no-cost-entry`
- `seon.turn/a-stalled-agent-ends-its-task-to-root` (1 proofs): `seon.ref/a-reference-with-no-path-is-a-missing-file`
- `seon.turn/the-test-step-writes-nothing-on-main` (2 proofs): `seon.turn/record-reached-red!`, `seon.turn/reached-red`
- `seon.turn/a-test-step-running-one-tiny-test-costs-half-a-second` (0 proofs): `seon.turn/a-first-deftest-in-a-new-namespace-costs-seconds-of-test-step`, `seon.test/one-test-run-at-the-repl-allocates-over-a-gigabyte`, `seon.pass-cost-test/a-step-running-one-tiny-test-costs-tens-of-milliseconds`
- `seon.turn.world/root-and-an-agent-differ-only-by-their-worlds-connection` (0 proofs): `my.task/refine!`
- `seon.vocabulary/the-merge-path-names-no-retired-identifier` (0 proofs): `seon.vocabulary-test/the-merge-path-names-no-retired-identifier`

### Duplicate pairs (intent Jaccard >= 0.6; words of 3+ letters, prose only)

- `seon.ai/contract-output-observed?` / `seon.cluster/contract-instruction-entity-changes` 1.0
- `seon.ai/contract-output-observed?` / `seon.cluster.registry/contract-cluster-branch` 1.0
- `seon.ai/contract-output-observed?` / `seon.db/contract-edn-encoded?` 1.0
- `seon.ai/contract-output-observed?` / `seon.fs/contract-attributes` 1.0
- `seon.ai/contract-output-observed?` / `seon.render.transcript/contract-runtime-duration` 1.0
- `seon.ai/contract-output-observed?` / `seon.render.web/contract-query-params` 1.0
- `seon.ai/contract-output-observed?` / `seon.schema/contract-function-arities-in` 1.0
- `seon.ai/contract-output-observed?` / `seon.schema.edn/contract-resource-population` 1.0
- `seon.ai/contract-output-observed?` / `seon.sci.eval/contract-context-projection` 1.0
- `seon.cluster/contract-instruction-entity-changes` / `seon.cluster.registry/contract-cluster-branch` 1.0
- `seon.cluster/contract-instruction-entity-changes` / `seon.db/contract-edn-encoded?` 1.0
- `seon.cluster/contract-instruction-entity-changes` / `seon.fs/contract-attributes` 1.0
- `seon.cluster/contract-instruction-entity-changes` / `seon.render.transcript/contract-runtime-duration` 1.0
- `seon.cluster/contract-instruction-entity-changes` / `seon.render.web/contract-query-params` 1.0
- `seon.cluster/contract-instruction-entity-changes` / `seon.schema/contract-function-arities-in` 1.0
- `seon.cluster/contract-instruction-entity-changes` / `seon.schema.edn/contract-resource-population` 1.0
- `seon.cluster/contract-instruction-entity-changes` / `seon.sci.eval/contract-context-projection` 1.0
- `seon.cluster.registry/contract-cluster-branch` / `seon.db/contract-edn-encoded?` 1.0
- `seon.cluster.registry/contract-cluster-branch` / `seon.fs/contract-attributes` 1.0
- `seon.cluster.registry/contract-cluster-branch` / `seon.render.transcript/contract-runtime-duration` 1.0
- `seon.cluster.registry/contract-cluster-branch` / `seon.render.web/contract-query-params` 1.0
- `seon.cluster.registry/contract-cluster-branch` / `seon.schema/contract-function-arities-in` 1.0
- `seon.cluster.registry/contract-cluster-branch` / `seon.schema.edn/contract-resource-population` 1.0
- `seon.cluster.registry/contract-cluster-branch` / `seon.sci.eval/contract-context-projection` 1.0
- `seon.db/contract-edn-encoded?` / `seon.fs/contract-attributes` 1.0
- `seon.db/contract-edn-encoded?` / `seon.render.transcript/contract-runtime-duration` 1.0
- `seon.db/contract-edn-encoded?` / `seon.render.web/contract-query-params` 1.0
- `seon.db/contract-edn-encoded?` / `seon.schema/contract-function-arities-in` 1.0
- `seon.db/contract-edn-encoded?` / `seon.schema.edn/contract-resource-population` 1.0
- `seon.db/contract-edn-encoded?` / `seon.sci.eval/contract-context-projection` 1.0
- `seon.fs/contract-attributes` / `seon.render.transcript/contract-runtime-duration` 1.0
- `seon.fs/contract-attributes` / `seon.render.web/contract-query-params` 1.0
- `seon.fs/contract-attributes` / `seon.schema/contract-function-arities-in` 1.0
- `seon.fs/contract-attributes` / `seon.schema.edn/contract-resource-population` 1.0
- `seon.fs/contract-attributes` / `seon.sci.eval/contract-context-projection` 1.0
- `seon.render.transcript/contract-runtime-duration` / `seon.render.web/contract-query-params` 1.0
- `seon.render.transcript/contract-runtime-duration` / `seon.schema/contract-function-arities-in` 1.0
- `seon.render.transcript/contract-runtime-duration` / `seon.schema.edn/contract-resource-population` 1.0
- `seon.render.transcript/contract-runtime-duration` / `seon.sci.eval/contract-context-projection` 1.0
- `seon.render.web/contract-query-params` / `seon.schema/contract-function-arities-in` 1.0
- `seon.render.web/contract-query-params` / `seon.schema.edn/contract-resource-population` 1.0
- `seon.render.web/contract-query-params` / `seon.sci.eval/contract-context-projection` 1.0
- `seon.schema/contract-function-arities-in` / `seon.schema.edn/contract-resource-population` 1.0
- `seon.schema/contract-function-arities-in` / `seon.sci.eval/contract-context-projection` 1.0
- `seon.schema.edn/contract-resource-population` / `seon.sci.eval/contract-context-projection` 1.0

### Retired words in intent prose (count of tasks / occurrences)

- attempt: 44 tasks, 77 occurrences
- custody: 13 tasks, 17 occurrences
- land: 40 tasks, 54 occurrences
- row: 114 tasks, 196 occurrences
- settle: 46 tasks, 76 occurrences
- squash: 3 tasks, 6 occurrences
- world: 62 tasks, 109 occurrences

### Phase tokens (open tasks naming a phase)

- `my.program/restore-returns-an-entity-to-its-merge-base-value`: M14 M15
- `my.test/a-check-of-named-identities-answers-a-reused-reds-failure-reports`: G12
- `seon.cluster.agent/two-plan-passes-start-one-attempt`: H6 H7 R1
- `seon.cluster.agent/each-intent-names-one-roster-state`: M12 M5 P8 R2
- `seon.cluster.agent/disarm-splits-into-stop-and-cancel`: P3 R2
- `seon.cluster.agent/a-fresh-agents-environment-costs-one-fork`: E2 E3 H5
- `seon.cluster.agent/a-received-message-is-written-on-the-recipients-branch-and-rendered-in-its-context`: AW4 AW5 AW7
- `seon.cluster.agent/a-successor-starts-from-its-predecessors-program-context`: E5 E6 H7 H8 P1
- `seon.cluster.agent/a-successor-whose-proof-its-predecessor-wrote-starts`: P5
- `seon.cluster.agent/a-capped-attempt-has-no-relaunch`: M12 M5 P9
- `seon.cluster.agent/the-real-chain-runs-with-no-nudges`: M1 M18
- `seon.cluster.agent/a-join-forks-main-after-both-needs-merge`: M10 M8
- `seon.cluster.boot/a-paused-boot-arms-every-open-attempt-and-runs-nothing`: R3
- `seon.cluster.branch-evidence/a-reaching-red-is-proven-red-before-by-its-result-at-the-base-key`: R10 R9
- `seon.cluster.message/a-send-writes-nothing-unless-it-keeps`: AW3 AW7
- `seon.cluster.message/root-offers-a-sent-message-once-and-merges-its-record`: M13 M16 U5
- `seon.cluster.registry/a-fork-writes-no-transaction-on-its-branch`: H8
- `seon.cluster.registry/a-merged-base-older-than-the-window-survives-collection`: M13 M17 M6 U4
- `seon.cluster.status/heap-live-counts-garbage`: G1
- `seon.config/a-config-read-rebuilds-the-packaged-projection`: H10 H9
- `seon.context/a-proof-green-before-the-work-is-not-done`: M1
- `seon.context/the-current-state-shows-the-branch-diff-since-its-merge-base`: M14 M3
- `seon.context/a-red-with-no-base-result-is-unknown`: R5 R6
- `seon.context/every-agent-sees-where-its-reds-came-from`: R7
- `seon.context/a-held-steps-instructions-are-shown-whole-in-its-context`: H1
- `seon.db/a-report-db-before-names-a-stale-commit`: E2
- `seon.db/q-lets-a-dependency-throw-escape`: E3
- `seon.db/q-lets-a-stack-overflow-error-escape`: P1
- `seon.db/a-reopened-commit-carries-its-content-revisions`: H2
- `seon.db/entities-born-on-two-branches-of-one-commit-have-different-ids`: H2
- `seon.fault/a-fault-on-a-branch-is-recorded-once-there-and-counted-on-main-at-the-next-auto-merge`: M3 U7
- `seon.flow/a-restart-starts-the-same-attempt-on-its-existing-branch`: H5 H6 H7 R1
- `seon.instrument/arming-a-projections-contract-walks-no-references`: H10
- `seon.mcp/a-fresh-agents-first-eval-request-acquires-its-branch-cheaply`: E3
- `seon.merge/an-attempts-cluster-facts-copy-onto-main-by-identity`: AW0 AW1
- `seon.merge/disjoint-set-additions-merge-as-their-union`: M2
- `seon.merge/a-conflict-keeps-its-three-values-on-the-merge-branch`: M4
- `seon.merge/a-merge-whose-reached-definition-moved-writes-nothing`: M6 M7
- `seon.merge/an-accepted-merge-is-published-by-the-next-pass`: M8 M9
- `seon.merge/a-sibling-merge-that-breaks-a-proof-undoes-done`: M11 M7
- `seon.merge/main-keeps-the-merge-results-after-the-branch-is-deleted`: M7 R5 R8
- `seon.operator/a-check-red-main-holds-red-does-not-refuse`: R10 R9
- `seon.problems/two-outcomes-at-one-key-are-a-nondeterministic-test`: R12
- `seon.program/cluster-attributes-are-the-declared-cluster-entities`: AW0
- `seon.render/an-unchanged-retained-call-selects-and-invokes-again`: H1
- `seon.schema/pull-selector-predicate-compiles-per-check`: E6
- `seon.schema/schema-registration-rechecks-the-whole-program-per-key`: H10
- `seon.sci.eval/branch-change-measured-against-loaded-program`: M1
- `seon.sci.eval/analysis-order-differs-from-evaluation-order`: M1
- `seon.sci.eval/a-fresh-fork-of-an-unchanged-program-acquires-from-the-base`: H3
- `seon.sci.eval/a-second-acquisition-of-an-unmoved-branch-interprets-nothing`: P2 R1 R4
- `seon.sci.eval/branches-without-program-changes-add-no-overlay-entry`: H3 H4
- `seon.sci.eval/a-cold-base-derivation-is-proportional-to-declarations`: H10 H9
- `seon.sci.eval/the-program-context-is-a-value`: E2
- `seon.sci.eval/a-settled-program-context-equals-its-rebuild`: E5
- `seon.sci.kernel/memory-pressure-crossed-during-test-rounds`: G1
- `seon.task/the-ready-leaves-are-those-whose-needs-are-done`: M8 P1
- `seon.task/a-refinement-on-a-branch-reaches-main-only-at-the-end`: AW0 AW3 AW4 AW5
- `seon.task/a-step-selects-its-obligations-by-its-kind`: P2
- `seon.task/a-done-branch-merges-with-no-merge-request-written-by-the-agent`: M1 M7 M8
- `seon.test/a-full-tier-on-one-jvm-retains-what-its-members-built`: G1
- `seon.test/a-test-run-is-an-attempt-on-the-path`: R1
- `seon.test/a-changed-dial-reruns-a-test-that-reads-it`: R4
- `seon.test/a-base-run-is-kept-on-the-agents-branch`: R5
- `seon.test/a-contract-predicates-body-is-in-its-schemas-reach`: R2
- `seon.test/an-interpreted-callable-holds-its-definition-digest`: R3
- `seon.test/a-test-reading-task-tags-reruns-when-a-test-is-tagged`: G1 R2
- `seon.test.runner/failure-evidence-is-stored-as-text`: R10
- `seon.test.runner/a-green-re-record-sweeps-the-failure-cell`: E4
- `seon.test.runner/a-forked-value-reads-the-results-it-inherited`: P1
- `seon.test.runner/the-first-reach-digest-after-a-change-costs-what-changed`: R11
- `seon.turn.batch/a-core-unmap-with-a-surviving-caller-is-silently-undone`: M5 P1 P8
- `seon.turn/a-send-back-names-its-target-on-the-attempt`: R1
- `seon.turn/a-closed-attempt-releases-its-connection-before-branch-deletion`: R1
- `seon.turn/the-test-step-writes-nothing-on-main`: P1 P3
- `seon.turn/a-held-task-opens-no-model-turn`: M12 M13 P10
- `seon.turn.world/root-and-an-agent-differ-only-by-their-worlds-connection`: AW2 AW5 AW6
- `seon.vocabulary/the-merge-path-names-no-retired-identifier`: M10 M11 M12 M14 M15 M16 M17 M18 M9
