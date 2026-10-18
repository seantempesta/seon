# Context judge 1: `seon.render.ns/ns-render-tests-are-red-on-head` as an outside agent

Lane context-judge (Sonnet), 2026-10-18, default JVM 23715 at HEAD fd373b0c1. The agent was the chain's own
`attempt/142f07c37ddb` (not `context-judge-1`: see friction 1). Token figures are estimates (about 3.5 characters per token).

## Result

The task was worked from the generated context only (apart from one lapse, below). Both proofs went green on the
chain branch and the attempt ended itself (the next evaluation answered `admission-closed`). The chain's merge was
**refused twice with `:seon.source/test-evidence-error`** (`(my.root/merge-steps)` shows only that keyword), so main does not
hold the fix; the merge steps `f512b12b76d3` and `19d7bf09c4d7` are held on `chain-95da3ba029e7` for root. The lapse: after the
second red I read `git log -S` and `test/seon/render/ns_test.clj` from disk to classify the test (the plan asks to
"classify by the commit"; Seon context has no history tool). Friction 12.

Actual cause found: the registered `:seon.task/plan` schema gained a `:description`, so four assertions in
`task-and-schema-entities-render-as-graph-nodes` that compared the whole form (`{:seon.schema/key ...}` alone, the
`:previous` form, the quoted form in `render-form`, and `registration-check` with a hand-written form) were stale
(retired assumption: the doc line is data in the listing). I redefined the deftest on the branch to assert key and doc
prefix, the first three elements of the form face, `:string`/`:min`, and the stored form for the unchanged-registration check.
The other proof named in the plan, `identity-reads-stewardship-without-an-agent-cluster-attribute`, no longer exists; the
test is `identity-reads-stewardship-from-the-namespace` and is green.

## Evaluation count

- Before the task: 6 (a failed first try as `context-judge-1`: no task; `(help)`; two `doc`/`dir` probes; filing as root).
- As the attempt agent: **16** evaluations: `current-state` x2 (the second refused), `search`, `update!`, `source` x2, 4 deftest redefinitions (the 4th green), and 5 wasted editing probes (friction 6). The `search` and `update!` were forced by the stale proof name (friction 3).
- Root: 5 (find the agent id, filing, `merge-steps`, `run!`, merge detail).
- Minimum possible with perfect context: about 5 (current-state, two `source`, one defn, done).

## Friction table

| # | Form | Waste | Tokens | Producer | Fixed or task |
|---|------|-------|--------|----------|---------------|
| 1 | `(seon.task/chain! {} ...)` as root, then entering by id | No documented path for an outside agent to take a task: `eval_clj` as a new id has no task; `mode jvm` is root only; `chain!` must be filed by root with `:seon.task/model :outside`; the agent id `attempt/<(seon.id/id [handle 1])>` is not in the `chain!` answer and is computable only in jvm mode | ~6 evals | `seon.task/chain!` answer, TASK-CHAIN card | doc fixed (TASK-CHAIN §2); task `seon.context/outside-agent-enters-by-the-chain-answer` |
| 2 | `(seon.context/current-state)` first block | 3.5k tokens; of which the whole source of a 90-line proof test (1.1k), a 27.5k-token markdown must-read (cites `reconciled-plan.md` as `:no-lead`, costing a line but flagged `:no-lead` noise), and a proof entry that is `:unresolved` | 3,500 | `seon.context` proofs/must-reads | task `seon.context/first-block-shows-a-proofs-source-once` |
| 3 | proof `identity-reads-stewardship-without-an-agent-cluster-attribute` | The task plan names a proof that no longer exists; the block says "resolves to nothing" and offers `search`, which found the renamed test. It cost the search plus a `my.task/update!` | 600 | task data (filing) | the block did its job; no fix |
| 4 | every reply after the first, then `(my.program/search ...)` | Each reply carried a recorded error: "3 consecutive turn writes refused (bound 3); the turn proc is parked" for an `:outside` agent whose proof was unresolved; it is the unresolved proof surfacing as a platform fault, repeated on 3 replies | 450 x 3 | `seon.turn/write-refusal-error`, the test selection refusal of an unresolved proof for an outside agent | task `seon.turn/an-outside-agents-unresolved-proof-parks-no-turn-proc` |
| 5 | `[(dir my.task) (dir my.agent)]` | 4,797 tokens for two namespaces (`:large-value` finding appeared): `dir` prints every schema in full including the whole `:seon.config/agent-overlay` map, to find the three verbs I needed | 4,800 | `seon.repl/render-directory-ai` | task `seon.context/dir-lists-each-schema-by-name` |
| 6 | `(my.edit/text (pr-str (my.program/source ...)) old new)` twice, plus `(ns-publics 'my.edit)` | No way to edit part of a function or test on the branch: `source` answers a form (not text), `my.edit/text` takes text and answers text, `form!`/`exact!` are file operations, so a one-assertion change meant resending the 50-line deftest 4 times (about 1.5k tokens each) | ~7k | `my.edit` / `my.program/source` | task `seon.context/a-definition-is-edited-by-exact-text-on-the-branch` |
| 7 | the green deftest redefinition | The reply for the final green says only `:passed? true`; the task being done and the branch ending are not said anywhere | 0 | `seon.turn.loop` reply | folded into 8 |
| 8 | `(seon.context/current-state)` after done | `run transition refused: admission-closed` with no cause: the entrance checks main's agent row, but a chain agent lives on its branch, so the writer's bare rule name reached the agent | 80 | `seon.turn/refuse!` | **fixed** commit 11390aa9f: the refusal carries `:seon.error/fix`; test `seon.db-test/a-closed-admission-refusal-tells-the-agent-its-attempt-has-ended`. Not yet seen in a live reply: default runs the old code until restart |
| 9 | `(my.root/merge-steps)` | The refusal is only `:seon.source/test-evidence-error`; the uncovered function list (`:seon.error/offending`) and the red tests are in the error but the step shows the keyword alone | 300 | `my.root/merge-steps` render | task `seon.context/merge-steps-shows-the-refusals-offending-and-red-tests` |
| 10 | the merge itself | A task whose only change is a redefined test is refused as test evidence (the changed declaration is reached by no test). Cause unverified: this is the finding to chase, with the exact forms: the four redefinitions of `seon.render.ns-test/task-and-schema-entities-render-as-graph-nodes` on `chain-95da3ba029e7` | n/a | `seon.cluster.source/uncovered-refusal` | task `seon.context/merge-steps-shows-the-refusals-offending-and-red-tests` (reproduce first) |
| 11 | `(my.program/source 'seon.render.schema/render-ai)` and `.../selection` | Perfect: 120 and 190 tokens, docstrings accurate, contract visible. `doc` was not needed | 0 | none | none |
| 12 | no form | Classifying a red by "the commit that changed its behavior" (the plan's instruction) has no Seon-side tool: no history of a definition from the REPL. I fell to `git` on disk | 400 | `my.program` | task `my.program/history-of-a-definition-at-the-repl` |
| 13 | `bin/seon status` after start | A JVM that is booting answered both `FAILED ... endpoint unavailable` and then 7 minutes of `not ready` with a diagnostic path: the wait is unobservable from `eval_clj` | n/a | `runtime_status` | task `seon.cluster.status/a-booting-cluster-reports-its-layer-and-wait`; not mine |

## Block size at each turn (tokens, estimated)

| Turn | Reply | Block part |
|------|-------|-----------|
| 1 `current-state` | ~3,900 | ~3,500 (task 200, proofs 1,300, step 40, must-reads 1,900) + findings 150 |
| 2 `search` | ~900 | ~450 (fault in each) |
| 3 `update!` after proofs red | ~1,300 | ~700 (failure text 600) |
| 4-13 | 100-900 each | ~150-700 (red failure detail then green `:passed? true`, ~40) |

The compact per-reply `:seon.turn.loop/tests` block is excellent: it showed which assertion failed with the data diff
that named the cause (`:description` in the stored form) every time. The first block is 5 to 10 times larger than any later one.

## What a perfect context would have contained

1. The task's proof `identity-reads-...` already marked "renamed to identity-reads-stewardship-from-the-namespace" (the index knows the rename), or the block's `search` run for me.
2. The failing proof's current failure inside the first block (red test run at the attempt's start). It was `:pending`; the failure arrived on evaluation 3.
3. Only the failing deftest's source and its owner `render-ai`'s source, with the stored schema form for the key the test touches (the cause is visible only in the stored form: `[:string {:min 1 :description ...}]`).
4. A line saying how a definition on the branch is edited by text, and that the task ends by itself when its proofs are green, with the merge's evidence requirements (friction 10).
5. No 27k-token markdown must-read and no `dir` on a namespace; a function list.
