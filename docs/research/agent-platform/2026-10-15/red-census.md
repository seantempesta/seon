# Red census on HEAD, 2026-10-15

Lane `red-census`, research only. HEAD checked: `551d629b2`.

## Runs (one `bin/seon check --at REV --test NS ...` per revision, all 11 namespaces)

| Revision | Time | Run id | Red count | Note |
|---|---|---|---|---|
| 76c4bed09 | 10-14 17:02 | ab956760ac1d | 21 | before S3b (70fdd8ffb) |
| 8926d8d61 | 10-14 22:57 | 63f80896435d | 32 | |
| 5384340ba | 10-14 23:57 | 28f0f9c33e9c | 30 | |
| 551d629b2 (HEAD) | 10-15 08:09 | 8b5b95aead74 | 32 (+4 errors) | |
| 1e7ed56dd | 10-15 07:41 | 9afbef46a9f2 | 11 red + 1 error (log `operator-check-20261015T074413.694.edn`) | the lanes' run; its 11 are a subset of today's HEAD reds |

The per-test bisect was cut short by the 45-minute box: only four revisions were checked, so "changing commit" below
is a window between the last green and first red check, not a single commit, unless marked.

## Rows (HEAD = 551d629b2, run 8b5b95aead74)

Red-set columns give which checked revisions were red: A=76c4bed09, B=5384340ba, C=8926d8d61, H=HEAD. "–" = green there.

| # | Test | Red set (A/B/C/H) | Failing assertion (HEAD, as data) | Changing commit | Class | Proposed action | Owner symbol |
|---|---|---|---|---|---|---|---|
| 1 | my.program-test/an-agent-writes-back-a-file-whose-analysis-runs-a-hook | –/R/R/R | `:seon.source/changed-paths` nil, expected `["src/wbhookbaa05a63262c/core.clj"]` (test:353) | window (76c4bed09, 8926d8d61]; probable 70fdd8ffb (S3b: write-back reads git HEAD) | UNKNOWN (probably the same cause as row 2; the error map was not printed) | print the value; if it is the git-HEAD refusal, give the fixture root a git checkout with the file committed | `my.program/write-branch!`, `seon.cluster.source/write-back!` |
| 2 | my.program-test/write-branch!-writes-my-defn-into-its-file | –/R/R/R | error: `git ls-tree failed in the checkout: fatal: not a git repository` at `src/wbranch…/core.clj` | 70fdd8ffb (10-14 17:08, S3b: a merge reads its files at git HEAD); green at 76c4bed09 | retired assumption (fixture root is a temp dir, not a git checkout) — not yet confirmed by a fixture change | initialise the fixture as a git repo with the file committed; do not change the producer | `seon.cluster.source/committed-bytes` (called from `proven-file`) |
| 3 | my.root-test/a-jvms-first-facts-merge-follows-the-changed-facts | –/–/–/R | `:seon.profile/alloc-bytes` 94,101,128 > 67,108,864 (test:88) | window (8926d8d61, H]; not in B or C | UNKNOWN; one red run only, no repeat run done | run alone 3x at HEAD; if red, bisect the window | `my.root/merge!` |
| 4 | my.root-test/a-merge-is-keyed-by-the-assignment | R/R/R/R | merge answers `:seon.error/message` "…:seon.agent/context-source is unavailable… pass it explicitly" (test:123); row outcome nil | ≤76c4bed09; candidate 9694592c4 (10-12 "Contracts on the merge path declare absence as no key") — not verified | hypothesis: retired assumption (test's `root-context` omits a binding the merge contract now fills); not reproduced | add the binding in the test's world the way call preparation does, then re-run | `seon.cluster.source/prepare-merge!`, `my.root/merge!` |
| 5 | seon.context-test/a-task-naming-a-missing-symbol-shows-a-problem | –/–/R/R | problems `[]`, expected `[{:seon.ref/name tq.cs/absent :problem-kind :unresolved}]` (test:281) | window (76c4bed09, 8926d8d61]; candidate 5c78940d9 (10-14 21:53 "an unresolved reference says it resolves to nothing") | UNKNOWN (candidate not verified) | bisect the window with `--test seon.context-test` at 5c78940d9^ and 5c78940d9 | `seon.ref` (unresolved problem), `my.task/update!` |
| 6 | seon.context-test/an-update-naming-nothing-answers-its-problem | –/–/R/R | same problem list `[]` (test:304) | same window as row 5 | UNKNOWN | same as row 5 | `seon.ref`, `my.task/update!` |
| 7 | seon.context-test/each-must-read-shows-its-declared-render | –/–/R/R | `(count reads)` 5, expected 6 (test:353) | same window as row 5 | UNKNOWN | same as row 5 | `seon.context` (must-read render) |
| 8 | seon.db-test/equal-declaration-content-shares-one-projection-across-values | R/R/R/R | `(= (commit-id outer) (commit-id inner))` false (test:2994) | ≤76c4bed09; HEAD candidate 7e2546112 (00:10) is after B, not the cause of the red seen since A | UNKNOWN for the changing commit | bisect before A (not run); the assertion itself is about one projection, which is the owner's contract | `seon.db` (projection sharing) |
| 9 | seon.db-test/equal-declarations-in-another-transaction-share-one-projection | –/R/R/– | not red at HEAD; red at B and C (test:1027/1028) | flapping: red at B and C, green at A and H | flaky suspect (2 of 4 runs red); not repeated | run alone 3x at HEAD and at C | `seon.db` |
| 10 | seon.db-test/the-stable-walk-costs-less-than-encoding-the-read-result | –/–/R/– | red only at C (test:1384) | flapping; red in 1 of 4 runs | flaky suspect; not repeated | run alone 3x at C | `seon.db` (stable walk) |
| 11 | seon.help-test/help-is-an-orienting-guide-in-titled-groups | R/R/R/R | `every?` false (test:211) | ≤76c4bed09 | UNKNOWN | open the assertion's data in a bisect run | `seon.help` (`seon.bootstrap/help-value`) |
| 12 | seon.help-test/help-names-only-installed-forms | –/R/R/R | `[def false]` in the resolved heads, all others true (test:111) | window (76c4bed09, 8926d8d61]; candidate 38a5039e2 (10-14 20:42) added help.md's "every def and defn you evaluate…". `def` is a special form; by reasoning `(resolve 'def)` is nil, not reproduced | hypothesis: surviving owner (help.md teaches `def` as a resolvable head) — fix the producer that resolves taught heads, not the test | not reproduced; check `(resolve 'def)` in the REPL | `seon.bootstrap/help-value`, `help.md` |
| 13 | seon.help-test/help-teaches-the-chain-block | R/R/R/R | `every?` false (test:236) | ≤76c4bed09 | UNKNOWN | bisect before A | `seon.bootstrap` help |
| 14 | seon.help-test/help-teaches-the-common-gotchas-and-names-only-symbols-that-exist | R/R/R/R | `every?` false (test:223); error at B | ≤76c4bed09 | UNKNOWN | bisect before A | `seon.bootstrap` help |
| 15 | seon.help-test/source-finds-a-schema-registered-in-the-same-eval-request | R/R/R/R | `:seon.program/not-found` is `:comp.order/qty`, expected nil (test:168) | ≤76c4bed09 | UNKNOWN | bisect before A | `my.program/source` |
| 16 | seon.merge-test/a-retraction-on-the-branch-retracts-on-an-unchanged-main-only | R/R/R/R | error (uncaught; not in an assertion) | ≤76c4bed09 | UNKNOWN | print the error's `:seon.error/message` | `seon.merge` retraction |
| 17 | seon.merge-test/merge-and-agent-facing-contracts-declare-absence-as-no-key | R/R/R/R | `maybe-schemas` returns `[my.program/task-source my.root/chain-requests]`, expected `[]` (test:616) | the test was added in 9694592c4 (10-12); the two schemas were added later | surviving owner: two agent-facing merge-path contracts still use `:maybe` where the rule says absence is no key | fix the two schemas (`my.program/task-source`, `my.root/chain-requests`) to declare absence as no key | `my.program/task-source`, `my.root/chain-requests` |
| 18 | seon.schema-test/a-refused-projection-source-never-yields-a-projection-with-no-forms | –/–/R/R | `:seon.error/offending` not `[poisoned]` (test:1390) | window; green at A and B | flaky suspect (2 of 4 runs red) or window change; not repeated | run alone 3x at HEAD | `seon.schema` projection |
| 19 | seon.schema-test/canonical-reference-values-use-the-pull-collection-grammar | R/–/–/R | `(vector? value)` false (test:308; 309 at B-era) | ≤76c4bed09 and HEAD; green at B and C | flapping; not repeated | run alone 3x at HEAD | `seon.schema` (pull grammar) |
| 20 | seon.schema-test/every-predicate-schema-declares-what-it-accepts | R/–/–/R | `missing` non-empty (test:456) | flapping as row 19 | flaky suspect or window change; not repeated | run alone 3x at HEAD | `seon.schema` predicates |
| 21 | seon.schema-test/pulled-forms-derive-from-the-entity-schema-and-selector | –/–/R/R | `m/validate` false (test:348) | window (76c4bed09, 8926d8d61] | UNKNOWN | bisect window | `seon.schema` pulled forms |
| 22 | seon.schema-usage-guard-test/generic-schema-deletion-refuses-committed-dependencies | R/R/R/R | `:seon.schema/schema-in-use` is nil, expected it (test:254; 256 at B) | ≤76c4bed09 | UNKNOWN | bisect before A | `seon.schema` deletion refusal |
| 23 | seon.sci.acquisition-cost-test/a-cold-base-derivation-is-proportional-to-declarations | R/R/R/R | `contract-references` calls 10,903; cold alloc 1,890,730,152 > 300,000,000; 1,737 ms > 300 (test:79-85) | ≤76c4bed09 | UNKNOWN | bisect before A; per-run numbers are the evidence | `seon.schema/contract-references`, `seon.schema.edn/read-schema-resource` |
| 24 | seon.sci.acquisition-cost-test/a-config-read-reuses-its-carried-projection | R/R/R/R | `contract-references` 14,712 calls; `read-schema-resource` 224 calls (test:98-99) | ≤76c4bed09 | UNKNOWN | bisect before A | `seon.schema.edn/read-schema-resource` |
| 25 | seon.sci.acquisition-cost-test/a-change-reinterprets-only-its-dependent-declarations | –/–/R/R | step alloc 149.8 MB, expected under 145.0 (test:208) | window (5384340ba, 8926d8d61]; candidate 50037b679 or e79736317 (predicates phases, 10-14 23:20-23:55) | UNKNOWN; near threshold, so flakiness not excluded | run alone 3x at HEAD | `seon.sci.eval` predicate re-arm |
| 26 | seon.sci.eval-test/a-context-definition-survives-its-symbols-first-install | R/R/R/R | error (uncaught `ExceptionInfo`) | ≤76c4bed09 | UNKNOWN | print the error's message | `seon.sci.eval` install |
| 27 | seon.sci.eval-test/a-selected-render-inherits-the-live-arm-or-owns-one-when-unarmed | R/R/R/R | `unarmed` is not `"rendered:value"` (test:2111) | ≤76c4bed09 | UNKNOWN | bisect before A | `seon.sci.eval` render selection |
| 28 | seon.sci.eval-test/acquisition-binds-loaded-first-party-compiled-vars | R/R/R/R | `(failed? external)` false (test:1650) | ≤76c4bed09 | UNKNOWN | bisect before A | `seon.sci.eval` acquisition |
| 29 | seon.sci.eval-test/an-acquired-function-uses-the-current-evaluation-limit | R/R/R/R | `sci/resolve` of `authored.interrupt/spin` is nil (test:1402) | ≤76c4bed09 | UNKNOWN | bisect before A | `seon.sci.eval` acquisition |
| 30 | seon.sci.eval-test/bare-dir-and-program-derived-doc-are-repl-native | R/R/R/R | `(= 2 2 3)` false (test:1264) | ≤76c4bed09 | UNKNOWN | bisect before A | `seon.sci.eval` doc |
| 31 | seon.sci.eval-test/root-acquisition-and-the-web-share-one-pull-plan | R/R/R/R | `(and …)` false (test:1814) | ≤76c4bed09 | UNKNOWN | bisect before A | `seon.sci.eval`, `seon.test` web pull |
| 32 | seon.sci.eval-test/showing-a-value-realizes-and-stores-only-what-is-shown | R/R/R/R | large-bytes 127,150,080 (or 135,227,056 at B/C) not under small + 16 MB (test:2592) | ≤76c4bed09 | UNKNOWN; the allocation numbers are the evidence | bisect before A | `seon.sci.eval` realization |
| 33 | seon.sci.eval-test/the-evaluator-remains-live-after-its-namespace-reloads | –/–/–/R | `replaced` is empty, expected `seon.sci.eval/evaluate` (test:497) | window (8926d8d61, H]; candidates are the 10-15 morning commits touching eval.clj (e.g. bd220e2b3 `my.program/ns-unmap!` shapes) — not verified | UNKNOWN | bisect the window with `--test seon.sci.eval-test` | `seon.sci.eval` reload |
| 34 | seon.test.overlay-world-test/a-request-world-reuses-the-executions-acquired-program | R/R/R/R | `identical?` false (test:149) | ≤76c4bed09 | UNKNOWN | bisect before A | `seon.test` request world |

HEAD-red rows are 1-8 and 11-34: 32 rows, matching the 32 red entries of run 8b5b95aead74. Rows 9 and 10 were red on
earlier checks only and are green on HEAD.

## Summary by class (32 HEAD-red rows, run 8b5b95aead74)

| Class | Count | Rows |
|---|---|---|
| Retired assumption (test fixture or world; cause not yet confirmed by an edit) | 2 | 2 (confirmed root: fixture root not a git checkout, from the error map), 4 (hypothesis) |
| Surviving owner broken (fix the producer) | 2 | 12 (hypothesis: help.md teaches `def`), 17 (two `:maybe` contracts) |
| Flaky suspect (red in 1 to 3 of 4 checks; not repeated) | 3 | 18, 19, 20 |
| Test of deleted machinery | 0 | — |
| UNKNOWN | 25 | 1, 3, 5, 6, 7, 8, 11, 13, 14, 15, 16, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34 |

Flaky suspects not red at HEAD: rows 9 and 10 (red at 5384340ba and 8926d8d61 only).
Counts are by the first class in the row. No test was confirmed flaky by repeated runs: the repeats were not done in the box.

## Unresolved and next steps

1. Rows 2 and 4: change the fixture (git init with the file committed; add the binding) and re-run both namespaces at HEAD. This is the confirmation step not yet done.
2. Row 12: check `(resolve 'def)` in an agent REPL on HEAD. If nil, the help head check needs a special-form branch in the producer.
3. Rows 5-7: bisect `seon.context-test` at `5c78940d9^` and `5c78940d9`.
4. Rows 1-34 with UNKNOWN: bisect before 76c4bed09 (10-14 17:02). Not done in the box.
5. Flaky suspects 9, 10, 18, 19, 20: run each alone 3 times at HEAD. Not done.
6. The lanes' run 9afbef46a9f2 at 1e7ed56dd is a subset of HEAD's reds (its 11 were the acquisition-cost and eval tests). No new reds were seen beyond HEAD's list.
