---
title: Task census
type: research
status: active
created: 2026-10-08
---

# Task census (lane task-census, 2026-10-08)

The owner asked how tasks that are no longer relevant go away. Tasks are declared in `resources/seon/tasks/<ns>.edn`
and ingested by `seon.task/index-tasks!`. `seon.task/task-findings` reports dangling `:seon/tasks` tags (a tag with
no task) and triage (a task with no tag); write-back and merge refuse dangling tags (`seon.cluster.source`, through
`task-findings` and `candidate-dangling`). Nothing reports a task whose subject is gone, a task already fixed, or a
task made moot by a ruling. This census is the one-off sweep; §5 proposes the standing check.

## 1. Method

- [`census.clj`](census.clj) (Babashka, about 0.7 s) prints [`census.tsv`](census.tsv): one row per task with
  whether its namespace exists in `src`/`test`/`script`, which `:must-read` vars resolve to no definition, which
  files tag it, which ledger rows and cards name it as `ns/name`, missing evidence paths, and keyword signals for
  planned deletion rows. The signals are noisy (steps, receipts, faults and "fold" match prose): they pick
  candidates to read, never a verdict.
- [`prune.clj`](prune.clj) deletes named tasks from their files and removes their `:seon/tasks` tags in one
  scripted sweep, leaving everything else byte for byte.
- Each deletion was decided by reading its intent against `src` and Git history; the reader fixes were confirmed
  at the REPL on default (agent `task-census-probe`): `(do (println "kept") 42)` answers `:out "kept\n"`,
  `#_ #_ 1 2 3` answers 3 only, and `(+ 1 2)) (inc 2)` carries its repair on the first form.

## 2. Counts

Before: 571 tasks in 79 files. After the deletions: 544; with the four friction tasks of §6: 547 (one of them a revision of an existing task, so three new entries).

| Class | Count | Action |
| --- | --- | --- |
| (a) obsolete, deleted | 27 | entries and tags removed in this commit |
| (a) obsolete, held by another lane | 5 | listed below for the holder |
| (b) duplicate | 1 | merged into its survivor |
| (c) covered by a ledger row or card | 52 | kept; see `census.tsv` columns `ledger`, `cards` |
| (d) real and unscheduled | 495 | kept; listed in `census.tsv` (rows with empty `ledger` and `cards`) for root |

### (a) Obsolete, deleted

Subject deleted (the producer is gone, so the defect is gone with it):

- MCP projection retired by the thin bridge (`d89b1017a`): `seon.cluster/mcp-get-value-admits-a-negative-offset`,
  `seon.cluster/mcp-reads-the-jvm-wide-instance-roster`, `seon.cluster/the-mcp-value-is-a-converted-tree`,
  `seon.cluster/mcp-reply-without-a-database-prints-unbounded`,
  `seon.cluster/runtime-status-throws-on-a-boot-window-read`,
  `seon.cluster/status-casts-a-keyword-to-a-symbol-during-boot`,
  `seon.dev.mcp/consume-recorded-diagnostic-observation`, `seon.dev.mcp/branch-eval-answers-no-events`,
  `seon.dev.mcp/branch-mcp-takes-one-form`, `seon.dev.mcp/mcp-failure-answers-carry-a-babashka-stack-trace`.
- The call-preparation hook, replaced by the armed wrapper's defaults (`1b77876af`):
  `seon.call-preparation/acquired-functions-get-no-supplied-defaults` (its proofs were already green, per
  `seon.task/open-tasks-with-all-proofs-green-are-not-flagged`),
  `seon.call-preparation/supply-reads-the-connection-head`, `seon.db/the-sci-call-hook-pays-armed-database-resolution`.
- `seon.schema.datahike/the-codec-reprints-values-to-compare-them` (`canonical-print-string` deleted, `85d2ba676`);
  `seon.sci.batch/the-fold-sci-stage-binds-no-output` (`evaluate` deleted; output now captured, probed above).

Fixed by landed commits and left open:

- Reader rules (`84a3c5e4d`, `e24fda839`, `c98ade404`, `18a68b248`, `4c3be3361`): `seon.sci.forgive/` tasks
  `a-prose-repair-replays-the-whole-reply`,
  `structural-trials-refuse-var-quote-anonymous-fn-deref-regex-and-syntax-quote`,
  `discard-consumes-prose-and-fence-boundaries`, `reader-at-restarts-line-and-column-numbering`,
  `prose-after-a-form-on-its-line-is-evaluated`, `a-bare-token-line-in-a-reply-is-prose`,
  `a-nested-discard-evaluates-a-discarded-form`, `a-bare-true-false-or-nil-line-is-prose`,
  `a-repair-goes-to-the-last-form-starting-on-its-line`. Their deftests stay as regressions.
- Intents that say "Fixed": `seon.fn/identity-names-refuses-set-callers` (`e9b2066ac`),
  `seon.sci.eval/required-library-vars-read-as-definitions` (`926326d8c`),
  `seon.render/error-operation-read-as-returner` (`5d353149b`, `b51fbb71d`).

### (a) Obsolete, held by another lane (not edited)

`resources/seon/tasks/seon.turn.edn` is held by lane f4b, `src/my/program.clj` by another lane:

- `seon.turn/analyze-settlement-is-unreached`: `analyze-settlement` is deleted.
- `seon.turn/the-unbound-check-walks-the-live-value-without-a-bound`: deleted by `9e61340b2`.
- `seon.turn/repairs-are-stored-as-printed-text`: done by `4c3be3361` (RP-12); untag
  `seon.repl-differential-reader-test`'s proof in the same commit.
- `seon.turn/compaction-erases-no-history-form-text`: compaction is deleted by S4-07b3 (now listed on its card).
- `my.program/an-agent-cannot-see-or-choose-its-change`: `my.program/overrides` is deleted; selection is MA-5a's
  `checkout!` and GV-6's diff value. Its tag sits in `src/my/program.clj`.

### (b) Duplicate

`seon.schema/validation-throws-on-non-keyword-sorted-maps` merged into
`seon.schema/map-validation-throws-on-sorted-maps-with-foreign-keys`, which gains its REPL consequence and issue
evidence. Neither had a tag.

### (c) Covered by a row

All 52 are named by a ledger row or card (MS-11b now cites `my.test/check-in-the-defining-pass-says-the-test-does-not-exist`). Root's issue index `docs/seon/schedule/README.md` still lists the 21
deleted tasks it names; it is the owner's schedule, so this lane leaves it to root.

### (d) Real and unscheduled

494 tasks, by home namespace (top): `seon.turn` 42, `seon.sci.eval` 39, `seon.db` 36, `seon.fn` 24, `seon.test` 22,
`seon.render.value` 20, `seon.error` 20, `seon.repl` 14, `my.program` 14, `seon.render` 13, `seon.cluster` 13,
`seon.operator` 12. Many of them fall inside planned deletions (S4, the recording rows, E-SDELETE) without naming
them; those rows should list the tasks they retire, as S4-07b3 now does.

Still naming deleted vars, kept because the defect may survive in the replacement:
`seon.call-preparation/the-call-state-is-an-atom`, `seon.cluster/record-jvm-evaluations-on-shared-path`,
`seon.dev.mcp/a-live-io-prepl-session-has-no-idle-bound`, the three `seon.print.value/` tasks (that namespace was
folded into the value printer, `fa31a1a97`), `seon.program/a-docstring-edit-changes-the-definition-digest`,
`seon.render.value/an-uncut-string-node-carries-no-total`,
`seon.render.value/bounded-text-survives-in-the-render-namespace` (PR-09).

## 3. Compaction and steps (coordinator item)

`seon.task-steps-test/steps-persist-across-a-compaction` is red at HEAD (`turn/compact!` refuses an open turn).
`test/seon/task_steps_test.clj` is held by another lane, so the test and every other compaction caller are listed
for deletion on the [S4-07b3 card](../../../../seon/schedule/cards/S4-07b3.md). No task is about steps as such;
the task signals for "step" are prose.

## 4. Kind tag

All 547 entries carry `:seon.task/kind` (491 `:function`, 53 `:namespace`, 3 `:schema`); NK-7 deletes it. Not
touched here.

## 5. Proofs and references (coordinator items)

**Prose proofs to tagged proofs.** [`proofs.clj`](proofs.clj) reads the qualified test symbols an untagged task's
intent names after "Proof" or "Done when" and resolves each to an existing deftest (`…/name` only when one deftest has
that name). Root counted 487 untagged tasks; only 85 intents say "Proof:" at all, and most of those name a test "to be
written". Result: 7 tasks name an existing deftest (10 links). Tagged now, in free files: 
`seon.error/a-converted-error-keeps-its-transient-type` (two `seon.error-test` deftests),
`seon.error/s1-left-metadata-readers-and-stamps-in-three-test-files`
(`seon.error.declared-identity-test/producers-declare-the-identity-settlement-records`) and
`seon.render.value/string-fill-arms-a-call-for-each-character`
(`seon.render.value-test/a-long-string-fills-the-budget-and-is-cut-at-a-line-end`). Held files, left for their owners
(`proofs.clj --apply --files <file>` adds them): `test/seon/cluster/turn_test.clj` (proofs of
`seon.sci.arm/arming-prints-the-authored-contract-to-read-it-back`,
`seon.sci.arm/arming-projection-is-rebuilt-per-caller-within-one-form`,
`seon.turn/the-test-step-results-never-reach-the-eval-reply`, and the second proof of the s1 task) and
`test/seon/sci/documentation_test.clj` (`seon.sci.eval/a-test-error-report-prints-the-whole-host-stack`). The
remaining 438 tasks need a proof written first; `proofs.clj` prints them as `needs-proof`. A tagged existing test is
often already green, so S6-06a's `assign!` still refuses such a task until the proof is red for the defect.

**Bare names to references.** [`backtick.clj`](backtick.clj) backticks, inside `:intent` strings only, each bare token
that resolves exactly to a var in `src`/`test`/`script` or a registered schema key, expanding a short alias
(`host/host!`) only when one namespace ending in that segment defines the name; text already in backticks, file paths
and everything unresolved stay prose. Applied: 851 references in 68 files. Left for their holders: `seon.turn.edn` (92,
lane f4b) and `seon.config.edn` (8, lane dial-adopt); `backtick.clj --apply --files <file>` finishes each.

## 6. REPL friction from lane impl-hook (RF-4, `3c15a3434`)

- `my.test/a-check-no-test-reaches-reads-as-a-failure`: new.
- The same-pass check: `my.test/check-in-the-defining-pass-says-the-test-does-not-exist` already existed with a
  "better refusal" done-when that contradicts the confirmed ruling; revised to MS-11b's (the check sees the
  definition), and MS-11b now cites it. No second task in `seon.turn`.
- The `(in-ns …)` noise comes from the hook: `bin/seon-hook` (~1247) prepends `(in-ns 'ns)` to the code. eval_clj's
  `namespace` argument already works, also for a namespace the branch never saw (probed: `scratch.census-never-seen`).
  Filed as `seon.dev.hook/the-hook-prepends-in-ns-instead-of-passing-the-namespace`.
- `seon.dev.hook/the-hook-sends-only-forms-with-a-var-definition`: new. UL-1c classifies definitions from clj-kondo; it
  does not decide which changed forms the hook sends, so it does not cover this.

## 7. Proposed standing check (row proposal, not code)

What exists: dangling tags refuse at write-back and merge (0 dangling on default per root); `seon.task/task-findings` returns `:seon.task/triage`
(tasks with no tag); `seon.task/assign!` refuses a proofless task (`d9789e22e`). The unscheduled task
`seon.task/task-text-references-are-never-resolved` asks for must-read and intent references to resolve.

CS-01c1 (in flight) reports a backticked name resolving to nothing as a problem when the task's block renders;
nothing reports it at ingestion, and no row reports a proofless task before someone tries to assign it.

Proposed row TASK-CHECK (owner: `seon.task`): at ingestion, `task-findings` reports per task (a) no tagged proof,
(b) a `:must-read` symbol or backticked reference resolving to nothing (CS-01c1's resolver, called once at ingestion),
(c) every must-read subject gone. The
findings are a problem on that task, shown where the task renders. A task whose subjects are all gone, or whose
proofs are all green, is listed for deletion; deletion is still a reviewed edit (rulings cannot be detected).
Proof: a deftest ingests a fixture task file with one proofless task, one dangling reference and one task whose
subject is deleted, and asserts the three findings as data. It absorbs `seon.task/task-text-references-are-never-resolved`
and `seon.task/open-tasks-with-all-proofs-green-are-not-flagged`.
