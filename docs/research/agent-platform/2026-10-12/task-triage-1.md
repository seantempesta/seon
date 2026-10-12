# Task triage 1: 150 untagged tasks in the agent-facing namespaces

Date: 2026-10-12. Read-only research; no source, test or resource was edited, and no JVM was booted or written to.

## Scope and method

- Population: `resources/seon/tasks/*.edn` entries with no deftest in `test/` tagged `:seon/tasks` with their `ns/name`. A static scan found 803 entries, 638 untagged (the brief said about 628; the scan's regex may differ by about ten).
- Batch: 150 of the 161 untagged tasks in the agent-facing namespaces: seon.test (45), seon.turn (35), seon.render.value (14), seon.cluster.agent (13), seon.render (11), seon.render.web (11), seon.task (11), seon.context (7), seon.render.var (3). The 11 left out are `seon.render.test` (4), `seon.render.ns` (2), `seon.render.transcript` (1), `my.root` (1), `my.task` (2), and one `seon.render.var` task.
- Filed today: no commit since 2026-10-12 touches `resources/seon/tasks`, so no task was excluded on that basis.
- Checks used: each `must-read` symbol searched for a definition in `src`, `src-clm` and `test`; targeted greps and reads of the code each intent names; `git cat-file` and `git log -S` for commits named by the intent. No reproduction form was run, so every verdict is static unless its evidence says otherwise.
- Classes: FIXED (the named behavior is already correct today), DUPLICATE (another task covers it), VALID (still wrong), OBSOLETE (about deleted machinery or a superseded design). UNRESOLVED means this pass did not gather evidence either way; it is not a verdict.

## Counts

| Class | Count |
|---|---|
| VALID | 23 |
| FIXED | 4 |
| DUPLICATE | 7 |
| OBSOLETE | 2 |
| UNRESOLVED | 114 |
| total | 150 |

## The ten VALID tasks most worth a proof

Ranked by what a wrong answer costs, then by how cleanly a deftest or table row can state the behavior:

| # | Task | Why it is worth a proof | Proof to write |
|---|---|---|---|
| 11 | `a-capped-attempt-has-no-relaunch` | A capped or stalled assignment has no way back; work stops silently | Proof: `seon.task/ready` table row (relaunch fact makes it ready). |
| 117 | `a-reload-between-forms-is-undetected` | A reload between two forms of one run is missed, so evaluation can use stale definitions | Proof: `seon.turn` row: reload between forms yields a diagnostic on the run. |
| 150 | `a-form-without-source-is-shown-as-a-fault` | Breaks the total REPL path: a failed entry with no source records a fault | Proof: failed entry with no source returns a failure value, no fault recorded. |
| 63 | `task-text-references-are-never-resolved` | 34 non-library `must-read` symbols across 28 tasks resolve to nothing | Proof: table law that every `must-read` resolves (fails on the 34). |
| 119 | `receipt-attributes-are-a-hand-list` | A new receipt key is silently dropped until someone edits a hand list | Proof: every receipt schema key is stored; adding a key needs no list edit. |
| 110 | `a-changed-dial-reruns-a-test-that-reads-it` | A test that reads a dial is reused green after the dial changes: a stale green | Proof: `seon.test/select` row on a dial change. |
| 145 | `ai-attempt-stores-printed-copies-of-settings-and-usage` | Stores a printed copy beside the data, breaking the storage rule | Proof: attempt row holds settings and usage as data. |
| 3 | `interrupted-rewalks-the-cause-chain` | Second copy of `cause-links` | Proof: law that `interrupted?` equals some InterruptedException over `cause-links`, on generated chains. |
| 53 | `the-web-worker-executor-is-never-shut-down` | Each stopped web server leaks an executor | Proof: after `stop!` the server's executor reports shut down. |
| 21 | `captured-invocations-blank-source-faces` | Passing captured invocations changes what a source face returns | Proof: table row, non-nil AI face for source output with captured invocations. |

## Triage table

| # | Task | Class | Confidence | Evidence or note |
|---|---|---|---|---|
| 1 | `seon.cluster.agent/an-escaped-pass-closes-the-reply-without-its-fault` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 2 | `seon.cluster.agent/a-wrapped-stop-interrupt-is-recorded-as-a-core-fault` | FIXED | medium | `turn-step` answers an interrupt through `interrupted?` (`src/seon/cluster/agent.clj:578`), which walks the cause chain (`:524-532`); the bare-catch described in the intent is gone. Not run. |
| 3 | `seon.cluster.agent/interrupted-rewalks-the-cause-chain` | VALID | high | `interrupted?` still carries its own seen-set loop (`src/seon/cluster/agent.clj:524-532`); `seon.error.refusal/cause-links` exists (`src/seon/error/refusal.clj:195`) and is not used there. Proof: a table test that `interrupted?` equals `some InterruptedException` over `cause-links` for nested, cyclic and interrupt-at-depth chains. |
| 4 | `seon.cluster.agent/reply-size-prints-sources-to-measure-them` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 5 | `seon.cluster.agent/finished-agents-and-their-branches-accumulate` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 6 | `seon.cluster.agent/each-intent-names-one-roster-state` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 7 | `seon.cluster.agent/disarm-splits-into-stop-and-cancel` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 8 | `seon.cluster.agent/a-paused-intent-starts-a-pingable-graph-that-runs-nothing` | FIXED | high | `src/seon/cluster/agent.clj:1409-1412`: `paused?` is true for any intent outside `#{nil :running}`, so `:seon.flow/resume?` is false for `:paused`; `start-graph!` (`src/seon/flow.clj:119-128`) honors it. |
| 9 | `seon.cluster.agent/a-successor-starts-from-its-predecessors-program-context` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 10 | `seon.cluster.agent/a-failed-start-ends-its-attempt-and-the-next-plans` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 11 | `seon.cluster.agent/a-capped-attempt-has-no-relaunch` | VALID | high | No `my.root/relaunch!` definition and no `:seon.task.attempt/relaunch` attribute anywhere in `src` or `resources` (0 hits). Proof: a `seon.task/ready` table row: a `:capped` assignment with a relaunch fact is ready; without one it is not. |
| 12 | `seon.cluster.agent/the-real-chain-runs-with-no-nudges` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 13 | `seon.cluster.agent/a-join-forks-main-after-both-needs-merge` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 14 | `seon.context/the-current-state-block-misleads-and-is-large` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 15 | `seon.context/an-agents-context-omits-the-errors-mcp-shows` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 16 | `seon.context/proofs-of-one-task-allocates-close-to-a-gigabyte` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 17 | `seon.context/the-current-state-shows-the-branch-diff-since-its-merge-base` | VALID | high | `:seon.context/changes` appears only in the task text (`resources/seon/tasks/seon.context.edn:56`); absent from `src` and the context schema. Proof: `seon.context-test` row, one defn then `:seon.context/changes` has one `:functions` entry. |
| 18 | `seon.context/a-red-with-no-base-result-is-unknown` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 19 | `seon.context/every-agent-sees-where-its-reds-came-from` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 20 | `seon.context/a-held-steps-instructions-are-shown-whole-in-its-context` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 21 | `seon.render/captured-invocations-blank-source-faces` | VALID | medium | `src/seon/render.clj:1626-1640` returns `:seon.render.call/output` of the retained entry whenever `captured-invocations` is given, so a source-producing face gets what the intent says is nil. Not run; proof: a table row asserting a non-nil AI face for source output with captured invocations. |
| 22 | `seon.render/cold-renders-recompute-read-evidence` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 23 | `seon.render/face-vocabulary-remains-in-code` | VALID | medium | 416 occurrences of the substring `face` in `src/seon` and `resources/seon/schemas` (a substring count; includes unrelated words). The vocabulary is still present. Not a behavior: its proof is a vocabulary lint, not a deftest. |
| 24 | `seon.render/unit-sweep-retyped-non-map-render-inputs` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 25 | `seon.render/a-transaction-report-prints-its-runs-bookkeeping` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 26 | `seon.render/a-render-against-another-database-reads-the-context-custody` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 27 | `seon.render/an-unchanged-retained-call-selects-and-invokes-again` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 28 | `seon.render/a-nested-declared-value-prints-as-an-object` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 29 | `seon.render/declared-metadata-selects-a-value-that-no-longer-fits` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 30 | `seon.render/landing-reds-2-render-bow-outs` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 31 | `seon.render/the-plan-render-layers-a-tree-by-needs` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 32 | `seon.render.value/continuations-cannot-navigate-derived-nodes` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 33 | `seon.render.value/nested-renders-bypass-the-floor` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 34 | `seon.render.value/test-messages-print-whole-worlds` | VALID | medium | About 70 `(is ... pr-str ...)` lines remain under `test/` (a line grep, not a parse). Proof: the assertion-message lint from the test-writing rules, plus a `seon.cluster.host-test` row that asserts data, not printed text. |
| 35 | `seon.render.value/a-lazy-seq-that-throws-shows-as-empty` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 36 | `seon.render.value/an-uncut-string-node-carries-no-total` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 37 | `seon.render.value/a-short-identifier-string-is-cut-at-its-first-chunk` | DUPLICATE | medium | Same breadth-before-depth budget symptom as task 41 (strings cut at their first chunk, a 16-character `subs` form). |
| 38 | `seon.render.value/a-cut-operation-in-render-data-is-not-runnable` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 39 | `seon.render.value/short-strings-in-pairs-are-cut` | DUPLICATE | medium | Same breadth-before-depth budget symptom as task 41 (per-row string cuts with dig forms); the fix is the one task 41 names. |
| 40 | `seon.render.value/bounded-text-survives-in-the-render-namespace` | VALID | medium | `src` no longer calls `seon.render.value/bounded-text` (0 hits), but its definition remains and `test/seon/render/floor_test.clj` and `test/seon/sci/branch_execution_test.clj` still call it. The cleanup is partly landed. |
| 41 | `seon.render.value/cuts-cost-more-than-they-hide-across-siblings` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 42 | `seon.render.value/failed-selection-escapes-the-profile-budget` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 43 | `seon.render.value/printing-thirty-two-numbers-allocates-megabytes` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 44 | `seon.render.value/transacted-passes-a-basis-descriptor-to-stored-collection` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 45 | `seon.render.value/a-cut-set-or-map-names-the-whole-collection` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 46 | `seon.render.var/a-deftest-reply-shows-only-its-var` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 47 | `seon.render.var/reaching-tests-are-never-run-and-a-first-red-is-silent` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 48 | `seon.render.var/the-offered-check-names-an-unquoted-symbol` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 49 | `seon.render.web/the-render-cache-keeps-every-debug-view` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 50 | `seon.render.web/a-page-renders-with-a-refused-database` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 51 | `seon.render.web/reconnect-wire-test-walks-the-whole-window` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 52 | `seon.render.web/an-unreportable-page-failure-is-only-a-log-line` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 53 | `seon.render.web/the-web-worker-executor-is-never-shut-down` | VALID | high | `src/seon/render/web.clj:3539` creates the virtual-thread executor; the only `shutdownNow` is on the bind-failure path (`:3563`). `stop!` never shuts it down. Proof: after `stop!` the executor the server was started with reports `isShutdown` true. |
| 54 | `seon.render.web/stale-debt-tags-remain-in-web-and-schema` | VALID | medium | Debt tags remain: `src/seon/render/web.clj:3001` (the task moved from line 3193) and `src/seon/schema.clj:20` (`seon.schema/agents-can-validate`, a task in `resources/seon/tasks/seon.schema.edn`). Whether those tasks count as open is not checked. |
| 55 | `seon.render.web/pages-print-values-instead-of-the-printer-html-face` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 56 | `seon.render.web/the-built-stylesheet-is-stale-against-its-source` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 57 | `seon.render.web/a-reconnect-refuses-a-pass-derived-before-it-connected-is-red` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 58 | `seon.render.web/a-route-throw-no-longer-answers-a-bounded-500` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 59 | `seon.render.web/unrelated-transaction-no-longer-reuses-the-debug-observation` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 60 | `seon.task/no-repl-entrance-creates-a-task` | FIXED | medium | `src/seon/task.clj:544` `create!` takes a declaration with `:seon.task/basis` and derives its handle as the aspect `[ns name kind]` (docstring). The duplicate and refusal rules were not exercised. |
| 61 | `seon.task/open-tasks-with-all-proofs-green-are-not-flagged` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 62 | `seon.task/tasks-have-no-done-when-and-name-subjects-only-through-must-read` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 63 | `seon.task/task-text-references-are-never-resolved` | VALID | medium | Global scan of all 803 task entries: 34 non-library `must-read` symbols across 28 tasks have no definition in `src`, `src-clm` or `test`. The intent counted 41 on the 392-task install. Proof: a table test asserting every `:seon.task/must-read` symbol resolves; it fails today on those 34. |
| 64 | `seon.task/a-runtime-created-task-is-never-written-back` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 65 | `seon.task/assign-cannot-see-a-red-proof-the-gate-will-not-land` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 66 | `seon.task/open-tasks-whose-proofs-are-green-are-never-retired` | DUPLICATE | medium | Same behavior as task 61 (open tasks whose proofs are green); 61 covers flagging, 66 covers retiring. Merge them into one task with two proofs. |
| 67 | `seon.task/a-sent-back-end-reopens-its-target` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 68 | `seon.task/a-repeated-problem-writes-nothing` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 69 | `seon.task/each-problem-class-is-detected-from-its-facts` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 70 | `seon.task/a-done-branch-merges-with-no-merge-request-written-by-the-agent` | VALID | medium | `request-merge!` is still defined (`src/seon/task.clj`, `src/my/program.clj`); the commit `074a2c045` retired the merge-request tasks and filed this one as M8. `land-end!`, `landing-tx`, `red-first`, `end-merge-request` and `root-execution` are absent from the tree. |
| 71 | `seon.test/readers-of-the-latest-result-ignore-its-evaluator` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 72 | `seon.test/a-changed-check-builds-a-world-per-request` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 73 | `seon.test/a-check-with-nothing-to-run-answers-green` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 74 | `seon.test/a-test-world-has-no-agent` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 75 | `seon.test/property-members-pay-a-commit-per-step` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 76 | `seon.test/tests-redefine-default-vars` | VALID | high | `test/`: 162 `with-redefs` and 27 `alter-var-root` lines (a line grep). The intent counted about 300; sites remain. Proof: a `^{...}` test-level check is not a deftest; the proof is the lint that refuses Var redefinition in `test/`. |
| 77 | `seon.test/reuse-only-selection-costs-a-second` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 78 | `seon.test/an-agent-branch-test-run-cannot-branch-its-fresh-checkpoint` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 79 | `seon.test/a-test-request-refuses-whole-when-one-namespace-has-no-eligible-tests` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 80 | `seon.test/a-test-run-refusal-escapes-as-an-exception` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 81 | `seon.test/release-and-rethrow-sites-repeat-instead-of-one-with-release` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 82 | `seon.test/the-transitions-test-runs-six-to-seven-seconds-against-a-five-second-bound` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 83 | `seon.test/red-tests-on-head-are-unclassified` | UNRESOLVED | low | Process task: classify reds by the commit that changed them; no code state to check. Its named reds were not rerun. |
| 84 | `seon.test/test-release-closes-branch-connections-out-of-order` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 85 | `seon.test/reds-on-head-9d6b392b6-in-task-reaching-tests` | OBSOLETE | high | Its must-read `seon.task/steps!` is defined nowhere in `src`, `src-clm` or `test`. The HEAD it names (`9d6b392b6`) is an old commit, retained only in history. |
| 86 | `seon.test/reds-on-head-178aa24e0-in-db-program-schema-tests` | DUPLICATE | low | Same class as task 83 (reds on an old HEAD, unclassified); its named HEAD `178aa24e0` is historical. |
| 87 | `seon.test/a-check-answer-shows-digests-the-agent-cannot-use` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 88 | `seon.test/a-stale-stored-definition-digest-refuses-every-test-world` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 89 | `seon.test/an-agent-branch-sees-none-of-its-clusters-test-evidence` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 90 | `seon.test/a-one-test-proof-check-allocates-a-gigabyte` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 91 | `seon.test/a-core-test-in-a-namespace-the-loaded-program-lacks-is-refused-as-host-bound` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 92 | `seon.test/a-check-compares-every-definition-digest-of-the-program-twice` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 93 | `seon.test/a-repl-test-after-a-root-ns-form-has-no-executable-var` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 94 | `seon.test/a-proof-that-evaluates-agent-code-reuses-its-red` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 95 | `seon.test/destructive-reach-keeps-a-second-cache` | VALID | medium | `src/seon/test.clj:4` requires `clojure.core.cache.wrapped`, the second cache the intent names. Its use inside `destructive-reach` was not read. |
| 96 | `seon.test/a-malformed-task-tag-stops-the-whole-check` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 97 | `seon.test/changed-definition-symbols-scans-whole-attributes-since-the-last-run` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 98 | `seon.test/reach-attribute-advances-from-the-recording-report` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 99 | `seon.test/an-sci-deftest-with-tasks-reports-no-executable-test-var` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 100 | `seon.test/a-first-deftest-in-a-new-namespace-costs-seconds-of-test-step` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 101 | `seon.test/a-named-check-drops-declared-long-tests-without-saying-so` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 102 | `seon.test/select-names-a-changed-test-as-a-function` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 103 | `seon.test/a-named-check-on-a-branch-carrying-platform-edits-allocates-gigabytes` | DUPLICATE | medium | Same allocation symptom as task 90 (one named check allocates gigabytes), on a branch with platform edits. |
| 104 | `seon.test/arm-first-loads-costs-many-times-its-main-baseline` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 105 | `seon.test/a-full-tier-on-one-jvm-retains-what-its-members-built` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 106 | `seon.test/an-observed-change-a-script-test-reaches-does-not-refuse-the-step` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 107 | `seon.test/a-test-run-is-an-attempt-on-the-path` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 108 | `seon.test/redefining-seon-task-agent-task-replies-too-large-to-reread` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 109 | `seon.test/the-merge-branch-key-is-seon-source-merge-branch` | VALID | high | `:seon.source/candidate` has 13 hits in `src` (a grep). Proof: a `seon.cluster.source` row that reads the merge branch under `:seon.source/merge-branch`. |
| 110 | `seon.test/a-changed-dial-reruns-a-test-that-reads-it` | VALID | high | `config-digest` has 0 hits in `src`. Proof: a `seon.test/select` row: change a dial read by a test, the test is no longer reused (its key moves). |
| 111 | `seon.test/a-base-run-is-kept-on-the-agents-branch` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 112 | `seon.test/a-contract-predicates-body-is-in-its-schemas-reach` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 113 | `seon.test/an-interpreted-callable-holds-its-definition-digest` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 114 | `seon.test/the-selection-test-observes-reach-digests-without-redefining-it` | VALID | medium | `with-redefs` over `seon.test.runner/reach-digests` is in `test/` (a named-selection test; line 165 of the file that holds it). The intent asks for an observable value instead. |
| 115 | `seon.test/two-tests-never-share-a-test-world` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 116 | `seon.turn/close-turn-is-a-third-commit` | VALID | medium | `close-turn` is still a separate pass (`src/seon/turn.clj:4846`, its `:close` request at `:4994`) rather than folded into settlement. Proof: a timing row on a trivial run's commit count (the intent cites 122-136 ms; not re-measured). |
| 117 | `seon.turn/a-reload-between-forms-is-undetected` | VALID | high | `call-detecting-program-change` is used by `src/seon/test.clj:305` and defined in `src/seon/sci/eval.clj:1178`, and does not appear in `src/seon/turn.clj`. Proof: a `seon.turn` row where a reload between two forms of one run yields a diagnostic on that run. |
| 118 | `seon.turn/an-agent-form-is-read-again-by-each-consumer` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 119 | `seon.turn/receipt-attributes-are-a-hand-list` | VALID | high | The hand list `receipt-terminal-attributes` is still a vector at `src/seon/turn.clj:1103`, used at `:1158`. Proof: every key of the receipt schema is stored; a new receipt key is stored without editing the list. |
| 120 | `seon.turn/a-gate-exception-refuses-the-whole-run` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 121 | `seon.turn/a-nested-in-ns-loses-the-whole-run` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 122 | `seon.turn/a-refused-multi-form-settlement-returns-nothing` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 123 | `seon.turn/schema-redeclaration-revalidates-unchanged-attributes` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 124 | `seon.turn/a-refused-refusal-settlement-throws-past-the-reply` | DUPLICATE | low | Same symptom as task 1 (`:seon.await/closed-error` with no value, from `turn-step`'s finally); the refusal-of-refusal trigger is additional. |
| 125 | `seon.turn/a-transient-write-refusal-parks-the-agent-until-restart` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 126 | `seon.turn/a-definition-is-settled-twice` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 127 | `seon.turn/row-tx-is-a-second-declaration-writer` | VALID | high | `seon.turn/row-tx` has no production caller: its remaining hits in `src` are docstrings (`src/seon/turn.clj:987`, `src/seon/cluster.clj:704`, `src/seon/sci/eval.clj:1053`). Its definition is still present. Proof: deleting it leaves the declaration writer's table tests green. |
| 128 | `seon.turn/eval-origin-has-no-producer-or-consumer` | VALID | medium | `:seon.eval/origin` has 9 hits in `src`; the intent counted about 15 in `turn.clj` alone. Not checked for producers or consumers beyond the grep. |
| 129 | `seon.turn/turn-continue-read-results-arrive-before-done-takes-six-seconds` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 130 | `seon.turn/a-deep-value-loses-the-run` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 131 | `seon.turn/each-trivial-evaluation-retains-two-hundred-kilobytes` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 132 | `seon.turn/a-deferred-wake-is-answered-by-silence` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 133 | `seon.turn/a-message-from-root-never-refills-the-turn-bound` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 134 | `seon.turn/a-prose-only-reply-evaluates-its-first-word` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 135 | `seon.turn/a-not-admitted-diagnostic-prints-its-write-attempt` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 136 | `seon.turn/an-identical-re-record-conflicts-with-itself` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 137 | `seon.turn/a-pass-reads-its-run-through-whole-branch-queries` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 138 | `seon.turn/turns-left-allocates-836-kilobytes-per-call` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 139 | `seon.turn/the-test-step-results-never-reach-the-eval-reply` | FIXED | medium | `src/seon/cluster/agent.clj:870` now selects `:seon.turn.loop/tests` and `:seon.turn.loop/stages` into the answer beside the refusal. Only the refused/outcome branch was read; the success branch was not. |
| 140 | `seon.turn/an-executed-test-request-allocates-seven-hundred-megabytes-in-the-step` | DUPLICATE | low | Same allocation family as task 90 (test step allocation), measured through the turn pass. |
| 141 | `seon.turn/two-branches-share-reach-declarations-is-red-beside-the-turn-tests` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 142 | `seon.turn/the-reply-shows-unreached-only-for-names-this-request-defined` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 143 | `seon.turn/a-bounded-step-that-leaves-queued-tests-wakes-nothing-when-no-member-ran` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 144 | `seon.turn/a-red-proof-carries-no-reason` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 145 | `seon.turn/ai-attempt-stores-printed-copies-of-settings-and-usage` | VALID | high | `src/seon/turn.clj:3833-3835` still stores `(pr-str settings)` and `(pr-str usage)` as `:seon.ai.attempt/settings-edn` and `usage-edn`. Proof: an attempt row holds the settings as data, not a string. |
| 146 | `seon.turn/a-model-pass-never-sees-its-test-step` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 147 | `seon.turn/a-throwing-test-step-loses-its-cause` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 148 | `seon.turn/a-landing-whose-outcome-write-is-refused-is-never-silent` | OBSOLETE | medium | Its must-read symbols `seon.turn/land-end!` and `seon.turn/landing-tx` are absent from the tree; `land-end!` was last touched by `074a2c045`, which retired the merge-request tasks (hybrid done-and-merge M8). The landing outcome now belongs to the merge task (`resources/seon/tasks/`). |
| 149 | `seon.turn/a-held-task-opens-no-model-turn` | UNRESOLVED | low | Not checked in this pass: the intent needs a run, a profile or a read of code beyond the grep budget. |
| 150 | `seon.turn/a-form-without-source-is-shown-as-a-fault` | VALID | medium | `src/seon/turn.clj:4312` still passes `(or source "")` as `:seon.cluster.eval/source`; `:seon.sci.eval/shown-request` is at `src/seon/sci/eval.clj:4238,4305` (its minimum length not read). Proof: a failed entry with no source yields a failure value, not a fault. |

## Notes on the verdicts

- UNRESOLVED (114) is the honest bulk: most intents need a run, a profile, or a read of a long function. The next pass should run them in parallel, one namespace per agent, with the must-read bodies and the reproduction form each intent gives.
- Task 85 reads the old HEAD `9d6b392b6` (OBSOLETE). The hybrid done-and-merge commits (`074a2c045`, `82e56df7f`, `f70c98ee8`) retired the merge-request tasks and filed the M-phases; several UNRESOLVED merge tasks may now be retired by the same commits.
- Line numbers in intents have drifted (for example the web debt tag moved from `web.clj:3193` to `:3001`). Intent line references are not evidence.
- Not run: `bin/css` (writes `output.css`), any test run (writes evidence), any eval_clj call. The stylesheet task (56) and the red-test tasks (57-59) need that.

