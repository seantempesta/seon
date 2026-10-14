---
title: "TASK-CHAIN: a chain is linked task entities on one branch"
type: spec
status: READY for implementation (owner, 2026-10-14: "mostly sound; we refine as we work"); replaces the 2026-10-11/13 copies design (history in git)
created: 2026-10-11
revised: 2026-10-14
---

# TASK-CHAIN

A chain is a few ordinary task entities, created together, linked by `:seon.task/next`, worked one after another by
separate agents on one branch, and merged to main once at the end. Root finds every chain through one fact on main and
reads its branch directly. Everything is data in the database; root's listener reacts to it. No copies, no subject
task, no per-step merges, no automatic relaunch. A single task is a chain of one: there is one launch path.

Evidence for every mechanism below is in `docs/research/agent-platform/2026-10-14/`: REPL proofs
(`chain-in-one-transaction.md`, `chain-on-one-branch.md`, `root-mechanics.md`, `design-stage-table.md`,
`prove-stage-table.md`, `done-rule-table.md`, `doc-view-measure.md`, `render-reuse.md`, `sci-reuse-across-tasks.md`,
`reactive-scheduling.md`, `cross-branch-references.md`), a live Design prototype (`design-prototype-run-1.md`), and two
rounds of independent reviews (`review-task-chain-{opus,astra}.md`, `review2-task-chain-{opus,astra}.md`), all folded in.

**Owner rulings, 2026-10-13/14 (binding):**

- "The task is the unit." Each task has exactly one set of proofs; an agent gets a budget of turns to make them green
  or bails, "with root being sent the mess".
- "Every task is an entity and the entity data is updated merge style"; each task's template decides what it keeps and
  what it overrides.
- The whole chain runs on one branch; "if a chain fails ... we can just abandon the branch and garbage collection can
  free it". Main stays clean until the chain succeeds. Root monitors any branch; nothing merges to monitor.
- `:seon.task/next` links tasks; a `:seon.task/prev` only if a later need appears. One name per concept.
- The description is `:seon.task/plan` (was `intent`): it starts as the user's request and every task improves it.
- Proofs are a field; `:seon/tasks` tags are deleted entirely.
- No automatic relaunch: budget spent, a bail and a refused merge all go to root.
- Messaging stays as it is (channels; each recipient writes what it receives on its own branch).
- Prove's default proof checks the next task's proofs: at least one, all red by design, every function the chain
  defined reached by one. Design's checks that the plan models the data. Every task leaves every function it defined
  with a complete contract.
- One general chain serves bug fixes and new features; specialized chains (new feature, refactoring) come later as
  other templates on the same mechanism. Chains are vectors of plain task maps root builds with ordinary Clojure.
- Completed tasks are history; rerunning is resetting a held task's fields or a new chain.
- Proofs may be nested Clojure: a deftest may call other test functions conditionally or short-circuit.
- Agents edit with Clojure's own shapes: `my.task/update!` has `clojure.core/update` semantics; text changes by exact
  replacement (`my.edit/text`). Help is a Markdown document resolved like a plan.
- After the common platform, Design, Prove and Implement are developed in parallel with early live runs.

## 1. Data

### The task

A task is one entity. Its members (all `:seon.task/*`):

| Member | Type | Meaning | Kind |
|---|---|---|---|
| `ns`, `name` | `ns` a symbol, `name` a string (task files and runtime maps both use string names: 869 measured) | the task's name; `:seon.task/id` and `:seon.task/sym` derive from them (`seon.task/task-handle`, `seon.task/task-sym`) | identity |
| `plan` (renames `intent`) | Markdown string | the plan: blocks such as `## Problem`, `## What exists`, `## Approach`, with references (§3) | content, **flowing** |
| `instructions` | Markdown string | what this task's agent does | content, **owned** |
| `proofs` | set of qualified deftest symbols; absent means empty, never proven | the one set that must be green for this task to be done | content, **owned** |
| `next` (new) | one non-component peer task ref (cardinality one) | the task that follows; absent on the last | link |
| `execution` | `:held :running :paused :stopped :cancelled`, Malli `:default :held` | whether it runs; root is its only writer on a chain | control |

`seon.task/declaration` (a task file entry, the content agents author) holds identity and content members only;
`next` and `execution` are written by `chain!` and root, never declared. **Owned** content members carry
`{:seon.task/owned true}` on their schema: a template sets them when the chain is created, and an agent may change them
(Prove writes the next task's `proofs`); they never flow. **Flowing** content members (every other content member:
`plan` today) are carried to the next task at each advance, the finished task's value winning (§2.5). A new flowing
member needs no code change.

Derived, never authored: `:seon.task/references` (the names `plan` and `instructions` cite, by `seon.ref/names`),
derived by the one task writer. Kept as they are: `:seon.task/created-by`, `cron`, `zone`, `function`.

**Deleted** (each with the commit that converts its last reader, §6): `intent` (renamed), `step`, `product`,
`children`, `needs`, `:seon.task/tasks` and every `:seon/tasks` tag, `:seon.task.attempt/relaunch`,
`:seon.config.run/relaunches`. The 17 task-file entries carrying `needs` (19 links, between filed backlog tasks) lose
them: their order is root's schedule, not a chain. Stored data under a renamed or deleted attribute is not migrated:
the slice that retires it lands in a restart batch with a reset (recorded tasks re-ingest from files).

### The attempt

An attempt (`:seon.task/assignment` row, its `:seon.task.attempt/*` members) is one agent's turn at one task, and on a
chain it lives on the chain branch, never on main. Its **outcome** is the one place completion lives:
`:seon.task/outcome` on the attempt row, `:done` (new value: its proofs passed and the chain moved on), `:merged` (the
terminal attempt, accepted into main), or `:closed` (root's decision, with `:seon.task/reason`). A task is done when its latest attempt's outcome is `:done` or `:merged`. Today `seon.merge/merge-tx` maps an end of
`:done` to outcome `:merged`, and readers such as `seon.task/ready-rules`' `leaf-done` and `seon.context` treat `:merged`
alone as complete; they convert to `:done`-or-`:merged` in S3a. Attempt numbers keep counting (`:seon.task/attempts-made`), so a
resumed task's next attempt has a new identity and a new agent id.

### Templates

Ordinary tasks, one file per stage so stage lanes never share a file (§7, "Parallel stages"):
`resources/seon/tasks/seon.step.design.edn` (`seon.step.design/template`), `seon.step.prove.edn`
(`seon.step.prove/template`), `seon.step.implement.edn` (`seon.step.implement/template`), each holding only owned
content (`instructions`, `proofs`), `:held` so they never run. File admission accepts a task without a plan (today's
non-parent `:intent` requirement goes). `seon.task/template` (new) answers a template's owned members as a map, from
an explicit database (omitted, the caller's bound connection's database).

### The chain fact on main

One entity on main per chain: `:seon.chain/branch` (the branch keyword) and `:seon.chain/first` (the first task's
`:seon.task/id`), identity `:seon.chain/first`. It is non-program (never merges as program), declared
`:seon.wake/starts true` (its installation wakes root: the route reacts to added datoms only), and counted by `seon.cluster.registry/cluster-claims` (a `[_ :seon.chain/branch ?b]` clause evaluated on main, since
`cluster-claims` answers only for a cluster branch, holding the branch and its `base/` ref) so
boot's `seon.cluster.registry/retire-unclaimed!` keeps its branch, failed or held chains included (measured: without the
clause all twenty probe chains were unclaimed). It is discovery, not status: root reads the chain's state on its branch.
(Not `datahike.reference`: with one store its store id is redundant, an eid selector cannot be written before the eid
exists, and `resolve-reference` needs a live connection.)

### One writer for tasks

Create, `update!` and advance all go through one task transaction producer in `seon.task`, which derives identity,
`:seon.task/references` (`seon.task/with-references`), revision and `:seon.task/created-by` from the final values. S1c
changes today's producer (`seon.task/adopt-tasks-tx`, which strips `:db/id`, links by lookup ref and forces `:held`
through `seon.task/held-tx`) to preserve negative tempids and a supplied `:execution`. A plain `seon.db/transact!` of task
maps is not that writer. Chain tasks are runtime tasks (`:seon.task/created-by` root): they live in the database and its
history, and the final merge never writes them into `resources/seon/tasks/*.edn`. `symbol-name?` moves into the
`:seon.task/name` schema, so the checked writer refuses a bad name for every writer and `seon.task/create!`'s separate
check goes.

## 2. Flow

1. **Create** — `seon.task/chain!` writes a vector of task maps as one linked chain:
   `(seon.task/chain! connection [task-map …])`, and `(seon.task/chain! connection [task-map …] {:seon.chain/from b})`
   to start from branch `b`'s head instead of main's. Contract:
   `[:function [:=> [:cat :seon.db/connection [:vector {:min 1} :seon.task/task-map]] [:or :seon.task/chain :seon.error/value]]
   [:=> [:cat :seon.db/connection [:vector {:min 1} :seon.task/task-map] :seon.task/chain-options] [:or :seon.task/chain :seon.error/value]]]`,
   answer `{:seon.chain/branch b :seon.chain/first h :seon.task/handles [h …]}`. Only the first map supplies identity
   (`:seon.task/ns`, `:seon.task/name`); every later task is named `"<first name>-<index>"` (1-based, same namespace);
   `:db/id`, `:seon.task/id`, `:seon.task/sym` and `:seon.task/next` in the maps are ignored, so two maps can never share
   an identity and no cycle can be written (nothing checks for one). Each map is ordinary data:
   `(merge (seon.task/template 'seon.step.design/template) {:seon.task/ns 'seon.ref :seon.task/name "fix-pathless-section" :seon.task/plan p})`.
   `chain!` knows nothing about templates: it fills schema defaults (Malli `:default` via `malli.core/decode` and
   `malli.transform/default-value-transformer` with optional keys added), names and links the maps by position. In
   order: fork the branch (`seon.cluster.registry/fork!` with `:seon.branch/target` always main, and `:seon.chain/from` passed
as its `:seon.cluster.registry/from`, selecting only the starting head, so the base and the final merge target stay main; branch `chain-<h>`), unless it
   exists; on it, write every task in ONE transaction through the task writer with negative tempids, the first
   `:running`, the rest `:held`; then write the chain fact on main. Idempotent: a repeated call with the same first task
   returns the existing chain and changes nothing (an edited plan stays). A crash after the fork and before the fact
   leaves an unclaimed, never-started branch, which boot retires; no agent starts before the fact is committed. Expected
   cost about 100 ms (fork 53–56 ms, two small committed transactions ~22 ms each); measure the durable create in S1c.
2. **Run a task** — root's listener `seon.cluster.agent/db<->flow`, each pass, reads every chain fact on main and each
   chain's branch head with `seon.db/branch-database` (a store read needing no open connection, as
   `seon.cluster.agent/branch-end` does today), released after use (`seon.cluster.source/release!`); a chain value is never read after the same pass deletes its
   branch (retention reclaims it; the window is `:seon.config.db/snapshot-window-ms`). Measured
   0.45–0.7 ms and 1.4–1.8 MB per chain read and walked (20 chains 9–14 ms with a warm store); no cache until load
   justifies one. A chain's work is only its first task's `next` path and those tasks' attempts: the branch also inherits
   main's unrelated tasks, which are not its work. **A task starts** when its execution is `:running`, its latest
   attempt is neither `:done` nor `:merged`, and no attempt lacks an outcome. Root writes the attempt row on the chain
   branch, through a connection it opens for that action and releases when the action ends (the registry owner,
   `seon.cluster.registry/active-branch-connection`, else the store's open), then starts a NEW agent id on that branch
   (`seon.cluster.agent/acquire-context!` with `{:seon.agent/branch b}`: 3.9 ms while another agent holds the branch,
   22.7 ms when nobody does). It never forks a branch per task and never reuses an agent's SCI context.
   `:seon.config.agent/max-running` counts open attempts across all chains.
3. **Work** — the agent reads its block (`seon.context/current-state`: its task's plan, instructions and proofs with
   their status, the tasks before and after it through `next`) and works at the REPL. It reads its own task's execution
   on its own branch (`seon.turn/attempt-running?` changes from main's head to the branch: root is the only writer of a
   chain task's execution, on the chain branch). Its first model turn comes from its task's proofs being red
   (`seon.turn/proved-unanswered?` and `seon.turn/step-proved?` stay, reading the task's `proofs` field: a chain agent
   starts with no wake, so they are its path to a first turn). After every eval request that evaluated a form, and at
   the attempt's start, the runner runs, in order: every test defined or changed on the branch since its fork (the pure
   branch-delta derivation beneath `seon.test/changes`, which answers an empty selection when nothing changed; the
   public `seon.test/changes` keeps its refusal for callers that named nothing), the tests reaching changed functions,
   and **then** the task's own proofs, with the branch value captured after the earlier tests recorded, so a proof that
   reads other tests' results reads current ones. The next task's proofs run as branch-changed tests (Prove writes
   them on the branch); nothing runs a later task's proofs otherwise. A test world that cannot be built is the
   request's error, never a member's recorded result (fixed at its producer in 4bb413774). Unchanged results are reused
   by reach digest. All green at the start ends the task with no model call.
4. **Done** — the done rule, for every task: new `seon.cluster.source/done-refusals`
   `[:=> [:cat :seon.db/database-value :seon.db/database-value :seon.source/judged] :seon.source/refusals]`,
   arguments `[branch main judged]`, a function of recorded results that runs no test. It refuses:
   - a proof with no current result, or one not green (an empty proof set never finishes);
   - a test the branch holds red that main holds green at main's own current reach (`seon.context/current-results`
     over main's value; equal reach digests would never fire, measured), unless it is one of the next task's proofs
     (Prove adding a red row to an existing table test is its job);
   - a test defined or changed on the branch that is red and is not one of the next task's proofs (`:stray-red`: on the
     last task, which has no next, every such red refuses, as the final merge does);
   - a function new or changed on the branch with a contract gap (`seon.cluster.source/contract-gaps`, the installed
     merge completeness rule: missing spec, `:any`, `:some`; value `def`s are not functions, de1f48c23).
   A test main holds no result for is unknown, not a regression: the final merge runs its baselines
   (`prepare-merge!`). Measured: about 2 ms per judgment, plus about 7 ms to build the judged input. Refusals reuse
   existing variants where their meanings match; a new one is registered in `:seon.source/refusals` before its body.
   The agent's turn proposes `:done` (`seon.turn/task-end`); root re-judges `done-refusals` over the branch value at the
   attempt's end commit (`:seon.task.attempt/commit`). `seon.cluster.source/results-verdict` stays the judge of the
   final merge only, its product clause deleted. The block shows the task's own done refusals; mid-chain it never shows
   merge refusals (there is no merge; the Design prototype's first block showed a merge refusal before any work).
5. **Advance** — root, in the lifecycle phase of `seon.cluster.agent/run-pass!`, one transaction on the chain branch
   through the task writer (`seon.task/advance-tx`, new: database and attempt key → transaction data, the next task read
   from the finished task's `:seon.task/next`, decided at the writer as a `:db.fn/call` over the held value, so a
   repeated pass finds the outcome and writes nothing): the finished attempt gets `:seon.task/outcome :done`; the next
   task receives the finished task's flowing members (the finished value wins) with re-derived references, and
   `:execution :running`. The finished task stays `:running` but can never start again (its latest attempt is `:done`).
   The plan phase of the same pass reads the branch after the advance and starts the next agent, as `run-pass!` already
   re-derives after `:stop` and `:merge`. Each task keeps the plan it was given: the plan's history is the chain's
   history.
6. **Finish** — a done task with no `next`: root merges the chain branch to main once. The merge owner
   (`seon.merge/merge!` → `seon.cluster.source/prepare-merge!` → `seon.cluster.source/accept-merge!`) takes the chain
   branch and the terminal attempt as its source; `seon.merge/assignment-row`, `seon.merge/assigned-task` and
   `seon.cluster.source/tested-task-basis` read the held source branch, main only as the destination. The candidate
   test world carries the branch's task entities as well as its program (the Design prototype's merge refused because
   today's candidate carries program declarations only, so a task-reading proof is red there). The merge judges the
   whole fork-to-head delta with `results-verdict`, its members being the last task's proofs, every test the branch
   defines or changes (with members = proofs alone, a red test left on the branch merged, measured) and every reaching
   test, all green, contracts complete, replaced functions covered. In its one transaction it admits all the chain's
   tasks and attempts, closes every intermediate attempt (`:done` stays its outcome) and marks the terminal one
   `:merged`, so no earlier attempt appears on main as an open merge request. Acceptance's transaction records the
   identity of the merge to publish, so a restart between acceptance and its answer publishes exactly once. Then, in
   order: `seon.merge/publish!` (a `:seon.merge/waiting` or a publication refusal is not success: the chain fact stays
   and root sees it); on success join the chain's graphs, release its connections, retire the branch and its base
   (`seon.cluster.registry/retire-branch!`), then retract the chain fact. On restart, a chain fact whose branch is gone
   after a recorded merge is cleaned up; one whose branch is gone otherwise is shown to root as unknown.
7. **Bail or fail** — every end that is not done: `:capped` (turns spent; `seon.turn/turns-left` is the budget),
   `:stalled`, `:given-back`, `:sent-back` (`my.task/send-back!` with its reason), `:error`, a done that
   `done-refusals` refuses, or a refused final merge. Root processes it once: first the problem task on main (its
   identity derived from the terminal attempt, so a repeat writes no second one), then on the chain branch in one
   transaction the attempt's outcome `:closed` with its reason and the task's `:execution :held`. Two databases cannot
   share one transaction; this order makes each stopping point derivable on restart without an outbox. Nothing else
   runs; the branch and the chain fact stay. `:cancelled` closes and retires through the existing cleanup with no
   problem task. Root then decides, all by writing data: change the held task's plan or proofs and run it again
   (`my.root/run!`: one branch transaction that changes `:held` to `:running`, then a payload-free wake offered to root's
   existing plan channel; boot's full derivation sees a committed run even if the process stopped before the offer); or
   abandon (record `:closed` with its reason, then the retirement of step 6); or build a new chain (`chain!` with any
   vector of maps, `:seon.chain/from` the old chain's branch to keep its work). A send-back is a bail: the chain never
   reruns an earlier task by itself. Completed tasks and their attempts are history and are never reopened.
8. **Restart** — recovery of facts, never of execution, through the existing owners (`seon.cluster/recover-runs!` and
   `seon.turn/recover-call` on each retained chain branch; no replay of forms or private SCI values). An attempt
   interrupted during active work ends `:error` (committed work and spent budget kept) and goes to root as step 7. An
   attempt committed but never started is hosted under the same id. A committed end is judged on the next pass; a
   committed advance starts its successor (the boot pass starts flows for every chain task step 2 says to start); an
   accepted merge resumes publication.

Root watches every chain live by reading its branch head and walking `next`. Nothing merges to monitor. Root's tools
(`my.root/merge-requests`, `my.root/ended`) enumerate chain attempts on their branches; `my.root/ended` derives ends
from them, not from end messages.

## 3. The plan and its references

The plan is CommonMark parsed by the one parser, `seon.ref/document` (headings become anchors: `## What exists` →
`#what-exists`). References resolve through `seon.ref/references`; the agent is offered a form to read each one:

| Kind | Written as | Read form |
|---|---|---|
| Function, library Var | `` `seon.ref/section` ``, `` `malli.core/validate` `` | `(my.program/source 'seon.ref/section)`, `(doc malli.core/validate)` |
| Deftest | `` `seon.ref-test/a-reference-with-no-path-is-a-missing-file` `` | `(my.program/source '…)` |
| Schema key, attribute, error type, dial | `` `:seon.ref/section-error` `` | `(my.program/source :seon.ref/section-error)` |
| Namespace | `` `seon.ref` `` (only when it exists) | `(dir seon.ref)` |
| Task | `` `seon.ref/a-pathless-reference-section-is-a-missing-file-error` `` | `(my.task/current …)` (to add to `seon.ref/brief`) |
| Document, section | `` `docs/seon/plan.md#0-where-we-are` `` or a Markdown link | `(my.fs/section "docs/seon/plan.md#0-where-we-are")` |

Backticked means a reference, and an unresolved one is a problem. A bare qualified name (one containing `/`) counts
only when it resolves; ordinary words never do. Fenced code, URLs, directories and self-citations are never
references; `path:N` is a problem. A name the plan means to create is not rejected: it is data the task's proofs judge.
The blocks are a convention revised as live runs teach us: the chain's creator writes `## Problem`, which no later
task changes; Design adds `## What exists` and `## Approach`.

## 3a. Agent verbs and what references show

**One write verb, `update` semantics.** `my.task/update!` replaces `my.task/refine!`:

```clojure
(my.task/update! task k f & args) ; like clojure.core/update, on a task entity's content member k
(my.task/update! 'seon.ref/fix-pathless-section-3 :seon.task/proofs (fnil into #{}) '[seon.ref-test/a-reference-with-no-path-is-a-missing-file])
(my.task/update! 'seon.ref/fix-pathless-section   :seon.task/plan my.edit/text "throws on" "answers a missing-file error for")
(my.task/update! 'seon.ref/fix-pathless-section   :seon.task/plan (constantly whole-new-plan))
```

- `task` is a task symbol; `k` a content member (the schema of `k` derives from the same member kinds advance uses;
  identity, link and control members are not content).
- It reads the member on the caller's branch, applies `f` there (an ordinary call, in the agent's own context), and
  writes through the task writer with the declaration it read as its basis; a basis changed in between is refused with
  base, current and proposed (`:seon.task/conflict-error`, carrying `:seon.error/type`). Nothing retries. In a linear
  chain one agent works at a time, so no member-level comparison is needed. If `f` returns an error value, that error
  is the answer and nothing is written; an unchanged value writes nothing.
- The new value is validated against the member's schema before writing. Measured with the real task writer:
  33–36 ms per write.
- It answers `{:seon.task/id … :seon.task/changed? bool}` plus `:my.task/problems`, each reference in a text member
  that does not resolve. An unresolved name never rejects the write.
- Its callers convert from `refine!` in the same commit; `refine!` and its goal-owner routing are deleted.

**One text function, `my.edit/text`.** `(my.edit/text s old new)` answers `s` with the one exact occurrence of `old`
replaced by `new`, or `:my.edit/no-match-error` / `:my.edit/ambiguous-match-error` (with the matching lines). It is a
new pure function over strings, and `seon.edit/exact` (which `my.edit/exact!` applies to files, with a path and a
digest) calls it for its replacement, so there is one editor. Exact replacement finds its place by content.

**Help is a Markdown document like the plan.** Its text moves out of code (`seon.bootstrap/help-value`) into
`resources/seon/help.md`, read by `seon.ref/document` and resolved by `seon.ref/references`. `(help)` answers its
sections with their resolved references; `(help "change-a-member")` one section by its heading anchor. Cached by the
file's content digest; its tokens measured with the block's. A deftest proves every reference in it resolves
(`seon.bootstrap-test/every-reference-in-help-resolves`). Its first sections teach: change a member
(`(my.task/update! task k f & args)`); change text (`(my.edit/text s old new)`); see what changed
(`(editscript.core/diff before after)`).

**What a reference shows: its doc view, the full form one evaluation away.** Every reference, in a plan, in help, in a
docstring, shows the doc view of its kind; the full definition is the read form it offers.

| Kind | Doc view | Full form |
|---|---|---|
| Function, library Var | docstring summary, arglists, contract with its named input and output schemas, each schema's one-line description | `(my.program/source 'f)` |
| Schema key | its form and description | `(my.program/source :k)` |
| Deftest | its name, the functions it reaches, its latest status (deftests have no docstring attribute) | `(my.program/source 't)` |
| Document section | heading, the headings below it, its size in tokens | `(my.fs/section "path#anchor")` |
| Task | name, the first sentence of its plan's Problem, its proofs with their status | `(my.task/current …)` |

The doc view is a new render tier (today: name and doc line at distance 0, whole source at distance 1; the doc view
becomes distance 1 and the source distance 2). Its nested references are only the schemas the contract names, cited
once (`seon.ref/cite-once`). Summaries keep the existing first-sentence rule; a producer whose first sentence is long is
shortened at its docstring. Schemas a contract names carry a description; `seon.render.schema/description` becomes
public; `seon.ref/render-doc-ai` keeps a section's token size and the headings below it. Measured
(`seon.ai.tokens/estimate`): raw block 37.4k characters, about 11.7k tokens, must-reads 9.8k; the shown prompt about
6.0k (91 elisions). With doc views the raw block falls to about 7.1k tokens (5.0k with schema-only nesting) and
elisions to 30.

**Context assembly costs what changed.** Every unit of the context is cached in `seon.cache` by its content and by what
it read (`seon.db/read-evidence-current?`), so a turn re-renders only the units whose inputs changed. Installed for
must-reads: render invocations are one `seon.cache` kind and must-reads render through `seon.render/render-call`
(35cafc1dc; a repeated render measured about 10× faster). Still to convert and measure: the block's sections and the
history (`seon.context/exchanges` walks all evaluations; `seon.context/model-context` rebuilds the block), and the
errors cut's removal of the time stamp that today makes refusal values differ per call
(`docs/research/agent-platform/2026-10-14/errors-cut-plan.md`). A cached outcome that depends on load or time (a
timeout) is not reused by read evidence (task `seon.render/a-timeout-or-load-dependent-render-outcome-is-never-reused-by-read-evidence`).

## 4. Templates and their proofs (version 1; live runs revise them)

Each stage's predicate is one contracted function, `[:=> [:cat :seon.db/database-value :seon.task/task]
:seon.source/refusals]` (empty means satisfied; each refusal names its failed check; every failed check is reported),
in its own namespace, with one deftest that (a) always runs a table of hand-built task values written through the
canonical writers on a test branch, red and green rows, so it is meaningful and green on main and merge branches, and
(b) when the runner supplies a task, also asserts the predicate is empty for that task. `seon.test/task` is an optional
reader: without a supplied task it answers nil and never resolves tags; a request naming a nonexistent task reports
missing input before execution. The supplied value is `{:seon.test/task <the task entity> :seon.test/branch <the
requesting branch's database, captured after the earlier tests of the request recorded>}`: a member body runs in its own
test world, where `(seon.db/db)` has no fork point and no recorded results (measured), so predicates read these values.
Assertion messages are fixed prose.

**Shared helpers** (`src/seon/step.clj`, `seon.step`, each contracted, landed once before the stage lanes):

- `sections [plan] → {anchor text}`: each heading anchor (`seon.ref/document`) with its text up to the next heading.
- `problems [database text] → [problem]`: `seon.ref/references` entries carrying `:seon.ref/problem-kind`.
- `changed [database attribute] → #{identity}`: identities of `attribute` changed on the branch since its fork, from
  the same pure branch-delta derivation the runner uses (§2.3), not a third computation.
- `results [database tests] → {test result}`: each test's result at its current reach for the supplied task input
  (`seon.test.runner/result-at-reach`, so another task's newer run of a shared template proof cannot hide it).
- `deftest? [database sym] → boolean`; `cited-fns [database text] → #{sym}`: first-party functions (`:seon.fn/sym` on
  the branch) a text cites.

### Design: `seon.step.design/template`

Instructions:

```markdown
## Work

Make the plan say exactly what the problem is, in terms of the data. Read `## Problem` first and leave it as it is: it is
the request you were given. Then search the system for what already exists: `my.program/search` with the words of the
problem, `docs/seon/architecture/vocabulary.md` for the words we use, `my.program/source` on each owner you find. Add to
the plan with `my.task/update!` (`(my.task/update! this-task :seon.task/plan my.edit/text old new)` for one passage):

- `## What exists`: the producer of the wrong or missing value, its callers, the schemas its inputs and outputs use,
  and the tests that cover it, each cited in backticks as a fully qualified symbol or keyword.
- `## Approach`: the data first (the schema keys involved; a schema to add or change is registered on this branch,
  not described in prose), then each function to change or add with its name, arity and complete contract over named
  schemas, and what it replaces or deletes. A new function is a `defn` in its owner's namespace with its full contract
  and the body `(throw (ex-info "not yet" {:seon.error/type :seon.error/unimplemented}))`.

Backtick every name you cite: a backticked name that does not resolve shows up as a problem to fix.

## Done

Problem is unchanged, What exists names the producer and a test that covers it, Approach names the function to change,
every reference resolves, the plan names the schemas involved, and every function you defined or changed has a
complete contract. Your proofs show what is missing after each evaluation.
```

`seon.step.design/handoff` refuses:

1. `:sections`: `what-exists` or `approach` absent or blank.
2. `:problem-changed`: the `problem` section differs from the chain's creating transaction's (the plan as written when
   `chain!` committed, read with Datahike history; the prototype appended to Problem despite the instruction).
3. `:references`: `(problems database plan)` not empty.
4. `:producer-not-cited`: `what-exists` cites no first-party function, or no deftest; `approach` cites no first-party
   function (`cited-fns`; the prototype showed "Fix it." as an Approach passing a presence-only check).
5. `:data-not-modeled`: `(changed database :seon.schema/key)` empty AND no resolved schema key among the references of
   `what-exists` and `approach` (Problem's citations do not count: in the prototype the check was green before work).

Contracts are judged by the done rule (§2.4) for every task. A library Var a plan cites must resolve but acquires no
Seon contract. A stub naming an unregistered schema is refused at the REPL and never exists, so it fails check 3.
Measured warm: about 2 ms per run. Prototype run 1 (`design-prototype-run-1.md`): the agent found the producer, both
callers, five tests and the schemas in 12 calls, 41 s, 0 hand acts; checks 2, 4 and 5 come from what it showed.

### Prove: `seon.step.prove/template`

Instructions:

```markdown
## Work

Write the deftests that will show the task done, one behavior each, asserting data: every exact and boundary case the
plan implies as a row of a table test (`are`), and a property with a bounded generator and a fixed `:seed` where a
general law holds. Prefer a new row in an existing table test. Run them: they must fail now because the behavior is
missing, by a failing assertion or an error the code under test raises, never because the test does not load. Then
write their symbols into the next task's proofs:
`(my.task/update! next-task :seon.task/proofs (fnil into #{}) '[the.ns-test/a-case …])`, the next task being the one
your block shows after you. Unmap scratch helpers and any deftest you do not mean as a proof (`my.program/ns-unmap!`): a
red test outside the next task's proofs keeps you from done, and everything on the branch merges at the end. Define
schemas in one request and the functions that use them in the next.

## Done

The next task has proofs, each ran red for the right reason, and every function the plan's Approach names or the branch
defines is reached by at least one of them.
```

`seon.step.prove/handoff` refuses:

1. `:no-proofs`: the next task (`:seon.task/next`, pulled on the branch) has no proofs.
2. `:not-a-deftest`: a proof names no deftest on the branch (a deftest that fails to load has no entity; the reply's
   diagnostics say why).
3. `:not-designed-red`: a proof has no current result, or its red does not come from its own assertion or the code
   under test (`seon.test.runner/designed-red?`); a green proof proves nothing.
4. `:unreached`: a function in `(changed database :seon.fn/sym)`, or in `(cited-fns database approach)`, is reached by
   none of the proofs (`seon.fn/tests-reaching`). The cited functions matter for a bug fix, which changes no function
   during Prove (measured: without them a red proof that never touches the producer passed).

Measured warm: 11–19 ms per run (15–26 MB). A one-task Prove trial needs a successor to receive its proofs: run Prove
followed by a held Implement task, inspect the handoff, and stop the trial before the final merge until S3b is
installed; never weaken the predicate for a missing successor.

### Implement: `seon.step.implement/template`

Instructions:

```markdown
## Work

Read the plan and your proofs first: they start red; your block shows each failure. Change the producer the Approach
names, and convert its callers in the same change; read each owner with `my.program/source` before changing it.
A proof that contradicts the plan, or a row you believe is wrong, is not yours to satisfy: send it back with
`my.task/send-back!` and the row and why. Never special-case one row to pass.

## Done

Every proof is green, no test that is green on main is red here, and every function you changed has a complete
contract.
```

`proofs` is absent on the template; Prove writes the real set. No predicate: the done rule is the whole check.

The user conversation (today's Survey, I1–I3) becomes a task before Design after the MVP: its proof will be that the
user's latest answer approves the current plan.

## 5. Scheduling

`seon.cluster.wake/route!` wakes root's plan channel for added datoms of starting attributes (an attempt end is one;
the chain fact's installation is one; `my.root/run!` offers its own wake, §2.7). The wake carries no data and
`run-pass!` re-derives every chain each pass; the plan channel's sliding buffer of one coalesces wakes into full
derivations, which is correct. Measured over chains: 9–14 ms for 20. First delete the relaunch derivations and
`basis-valid?`'s commit-keyed cache with `obligations` (S2/S3), then measure a pass over real chains; only then decide
whether wakes should carry changed ids. Not a commitment of this spec.

## 6. What is deleted

Each deletion lands in the commit that converts its last reader; the slice pre-brief assigns every remaining caller to
one named commit. No compatibility copy.

- Task data: `seon.task/effective` (with its last task-input/context caller, S2a/S3a), `seon.task/goal-owner` (with
  `refine!` and `obligations`, S1d/S2c), today's `chain!` and `chain-copy` (S1c), `children` and `needs` with their
  admission, readiness (`seon.task/ready-rules`' needs clauses, `seon.task/execution`'s inheritance) and merge readers
  (`seon.cluster.source/tested-task-basis`) and `seon.context/chain`'s (S3e), `step`, `product`, `intent` (S1a renames).
- Proof selection: `seon.task/obligations` and `assigned-obligations` (a task's proofs are its field),
  `seon.test.runner/single-tags` and the run input's extra names (`:seon.test/task` stays the one request key,
  `seon.test/task` the one reader), `seon.turn/task-obligations`, `task-proofs`, `step-tests`,
  `seon.context/task-done?`, `seon.context/step-status` (callers call `done-refusals`),
  `seon.cluster.agent/basis-valid?`'s `:prove` successor branch and its commit-keyed cache. `seon.turn/step-proved?`
  and `proved-unanswered?` stay, reading the proofs field (§2.3).
- Tags: tag ingestion in `seon.fn` and `seon.fn.analyzer`, `seon.fn/task-links`; the tag queries in
  `seon.task/task-edges`, `open-tasks`, `task-findings`, `candidate-dangling`; `seon.cluster.agent/render-identity-html`'s
  namespace tags; the `:seon.task/tasks` members of the fn, test and ns schemas; `:seon.task/annotation` and its links;
  the help and `:seon.task/assignment-view` text naming tags; the tag output of `seon.contracts/task` (the generator survives and writes the `proofs` field instead). The
  sweep is one committed rewrite-clj script (aliases resolved as ingestion does): each deftest's symbol joins the
  `:seon.task/proofs` of every task it tagged, unioned with existing proofs; `defn` and `ns` tags name subjects and
  become backticked task references in the var's docstring or are dropped; tags inside strings (fixtures) convert by
  hand where a test needs them. Measured: 344 symbols in test tag sets naming 247 tasks (39 fixture names inside
  strings), 33 in `src`. Order: the sweep writes the proofs fields first (S2c-1), readers switch to the field (S2a/S2b),
  then tags and their readers are deleted (S2c-2). Acceptance: before/after task→proof sets equal (union) for retained
  tests; no non-deftest proof; no live old attribute or ingestion path.
- Lifecycle: the relaunch path (`seon.cluster.agent/assignment-actions`' relaunch rows, `allowance-left`,
  `:seon.flow.plan/allowance-left`, `work-left?`, `:seon.flow.plan/relaunch`, the fork-point relaunch clauses in `plan`
  and `fork-attempt!`, `seon.task/relaunched`, the `:relaunch` kind of `seon.task/end-kinds`, the relaunch clause of
  `seon.task/ready`); `copied?` in `assignment-actions`; `seed-attempt!` and the per-attempt main merge-base write;
  per-step `:merge`, `:publish` and `:delete-branch` for tasks with a `next`; the per-assignment branch fork of today's
  single-task launch (a single task is a chain of one).
- Text: `my.task/refine!`, the string replacement inside `seon.edit/exact` (it calls `my.edit/text`'s core), the
  fixed help strings in `seon.bootstrap/help-value`.
- Templates: `resources/seon/tasks/seon.step.edn`, `test/seon/step_test.clj` and the survey/define templates and their
  deftests, the `worked-results` constant (S4a, with their last readers).
- Dependencies: the unused `io.github.nextjournal/markdown`, in root's next restart batch (the check path that proves it
  is e748f08c2).
- Laws and documents that state tags, `intent` or `refine!` convert in the same commits: CLAUDE.md "Write tasks as
  data" and "Documentation is data", `.agents/skills/clojure-testing/SKILL.md`, `docs/seon/architecture/vocabulary.md`.

## 7. Slices

S1a lands first: today's reference derivation (`seon.task/with-references`) reads `intent`, and S1b and S1c are
written against `plan`. `seon.task/with-references`, `task-delta` and `held-tx` are private: their red proofs reach them
through the public writers (pre-brief `prebrief-s1.md`). Serial where owners are shared (`seon.task`, `seon.turn`, `seon.context`, `seon.cluster.agent`, `seon.test.runner`,
`seon.cluster.source`, `seon.merge`). Each slice: a Haiku pre-brief (every caller, test, quoted symbol and schema
reference it changes or deletes; the reds on HEAD; the task's census numbers rerun), its red deftest first, HEAD boots
after it, committed through `bin/lane` (a message per lane, never a shared message file), and this table's status
column updated in the same commit. Fixtures use the canonical writers and agent fixtures, never hand-built production
entities; `:seon.turn.loop/complete` is the injected model, never a replacement for real test execution. Opus for the
lifecycle, runner and restart cuts until their red proofs and contracts exist; Sonnet for specified mechanical work.

| # | Slice | Red proof (exact assertions) | Model | Status |
|---|---|---|---|---|
| S1a | The `intent` → `plan` rename: inventory and script, then one atomic conversion commit over task files, src, tests, schema, CLAUDE.md, vocabulary, fixtures and namespaced maps | old/new declarations differ only by the renamed key; existing reds unchanged | Sonnet; restart batch + reset | — |
| S1b-1 | Defaults and names: Malli `:default :held` on execution decoded in `seon.task/task-delta` and `held-tx` (a supplied `:running` stays running; editing a running task never resets it held), `symbol-name?` in the name schema | `seon.task-test/task-members-have-defaults-and-kinds`: decoding `{}` gives `{:seon.task/execution :held}`; a bad name is refused by the writer; a supplied `:running` survives the writer | Sonnet | — |
| S1b-2 | Chain schemas: `:seon.task/next` (one peer ref, non-component), `{:seon.task/owned true}` on instructions and proofs, outcome `:done` on the attempt, the chain fact (`:seon.chain/*`, non-program, `:seon.wake/starts`) and its claim clause | owned members are exactly `#{instructions proofs}` by schema property; a branch and its base named by a chain fact survive `retire-unclaimed!`, held chains included | Sonnet | — |
| S1c-1 | The one task writer: preserve tempids and supplied execution through `seon.task/adopt-tasks-tx`'s replacement; `seon.task/template` | three maps with tempids written in one transaction pull back linked, references derived, identities from `task-handle` | Opus | — |
| S1c-2 | `chain!` (§2.1): request/answer schemas first, fork with main target, one transaction, chain fact, idempotence | `seon.task-test/a-chain-is-linked-tasks-written-in-one-transaction-on-its-branch`: the branch exists; on `(seon.db/branch-database main b)` the first task's `next` path names `[n n-2 n-3]`, all identity datoms in one transaction; template fields present; only the first has the plan; executions `[:running :held :held]`; main holds no chain task or attempt and holds the chain fact; a repeat answers the same value, preserves an edited plan and commits nothing; a `:seon.chain/from` chain's base is main; the durable create measured | Opus | — |
| S1d-1 | `my.edit/text` and `seon.edit/exact` calling it | zero, one and several matches; `exact!` file behavior unchanged | Sonnet | — |
| S1d-2 | `my.task/update!` replacing `refine!` and its goal-owner routing | `my.task-test/update-applies-a-function-to-a-member`: `(fnil into #{})` on absent proofs; `my.edit/text` on the plan; same value writes no transaction; f's error value is the answer with no write; an invalid value refused; a stale basis refused with base/current/proposed and `:seon.error/type`; identity, `next` or `execution` as k is refused by the request schema; an unresolved reference answered as a problem with the write kept | Sonnet | — |
| S1d-3 | Help as `resources/seon/help.md` through `seon.ref` (doc views of its references wait for S3d) | `seon.bootstrap-test/every-reference-in-help-resolves`; `(help "change-a-member")` answers that section | Sonnet | — |
| S2a | One task input: the task and the requesting branch value supplied to the member; reuse identity is content (task revision, next task's proofs, program revisions digest), never database objects or commit ids; lookup by `seon.test.runner/result-at-reach` under the recomputed identity | `seon.task-test/a-tasks-proof-is-recorded-and-read-under-its-task`: tasks A (red) and B (green) share one template proof with different plans; both results stay readable without rerunning either; an unrelated evaluation preserves reuse; changing A's plan invalidates A only; in one request a successor proof's new result is seen by the handoff; an unbound task-reading test gets nil and never a tag | Opus | — |
| S2b-1 | Whole-branch selection: the pure branch-delta beneath `seon.test/changes` (zero delta = empty selection), ordered recording (own proofs last) at each request and at the attempt's start | a plan-only update runs the task's proof and makes no selection error; a deftest defined two requests earlier that reaches no changed function is still selected | Opus | — |
| S2b-2 | `done-refusals` (§2.4) | `seon.cluster.source-test/done-refusals-judges-every-task-alike`, a table: own proof green → done; own proof red, missing or the set empty → refused; branch-new red outside the next proofs → `:stray-red`; the same red in the next proofs → done; terminal task with a branch-new red → refused; a main-green test red here → refused, unless it is a next proof (a row added to an existing table) → done; main holding no result → not a regression; a new function with an `:any` slot → refused; a contracted uncovered stub → done | Opus | — |
| S2c-1 | The tag sweep writes every task's `:seon.task/proofs` (union with existing), before readers switch | before/after task→proof sets equal for every retained task | Sonnet | — |
| S2c-2 | Readers on the field; delete tags and every tag reader (§6), `obligations`, `single-tags`, the turn wrappers | no live `:seon.task/tasks` attribute or ingestion path; the retained reds unchanged | Sonnet | — |
| S3a-1 | Root reads chains: chain facts, `branch-database` reads released after use, a chain's own `next` path only, attempt rows on the chain branch through per-action connections | after `chain!` alone the first task's attempt and agent exist on the chain branch, none on main; an unrelated `:running` task on main is not launched by the chain | Opus | — |
| S3a-2 | Turn authority and context: `attempt-running?` on the branch, `proved-unanswered?`/`step-proved?` on the field, `seon.context/chain` through `next` | the first task's agent makes its first model call with no wake; its block shows the tasks before and after it; before any work the block shows no merge verdict or refusal and lists each red test once (context-sanity.md defect 2); the block a reply carries is rendered after that reply's test step, so its proof states equal the reply's (defect 3) | Opus | — |
| S3a-3 | Advance (§2.5, `seon.task/advance-tx`) | `seon.cluster.db-flow-test/a-two-task-chain-reaches-its-second-model-call`: task one ends done (scripted model), task two's first model call happens with zero hand acts; exactly two distinct agents share one branch; task one never starts again over repeated passes; a repeated advance writes no task datoms; the plan flows with references re-derived | Opus | — |
| S3b-1 | Final acceptance from the source branch (§2.6): the candidate world carries task entities; all attempts closed; publication identity recorded at acceptance | `seon.cluster.merge-test/a-chain-merges-once-and-closes-every-attempt`: before acceptance main holds no chain task; one acceptance admits all three tasks and every earlier code change; `my.root/merge-requests` is empty after; a task-reading proof is green on the candidate; a conflicting second chain reports its conflict and keeps its branch | Opus | — |
| S3b-2 | Publication and retirement after acceptance | publication commits once, including across a restart between acceptance and its answer; branch, base and chain fact gone after; `:seon.merge/waiting` keeps the chain fact | Opus | — |
| S3c-1 | Bail, resume and abandon (§2.7) | a `:capped` end gives exactly one problem task after repeated wakes, the attempt `:closed` with its reason, the task `:held`, the branch and chain fact kept, no successor started; after `my.root/run!` with no other main write exactly one new attempt and agent start on the same branch, with the next attempt number | Sonnet (after S3a) | — |
| S3c-2 | Restart (§2.8) | one row per durable stopping point: attempt identity, model-call count, outcome, retained branches, pending publication | Opus | — |
| S3d-1 | Doc view render tier (§3a) | `seon.context-test/a-referenced-function-shows-its-doc-view`: distance 1 shows docstring, contract, schema descriptions and the read form, no source; distance 2 keeps the source | Sonnet (after S3a) | — |
| S3d-2 | Context section and history reuse | measured on target HEAD first; then a `seon.profile/measure` assertion that a second turn's `model-context` after one new evaluation costs in proportion to that unit, with the raw block's tokens and the elision count before/after | Opus (after the errors cut's E4a) | — |
| S3e | Delete the task tree (`children`, `needs`, `effective`) with their last readers (§6) | no live reader; retained reds unchanged | Sonnet | — |
| S4a | Stage preparation: `seon.step` shared helpers, the task-input fixture in `seon.test-support`, the three template files, deletion of `seon.step.edn`, `step_test.clj` and their readers, a grading form for live runs | each helper's contract; each template ingests | Sonnet (after S2a, S2b, S3a) | — |
| S4b-design / S4b-prove / S4b-implement | One lane per stage, in parallel: its predicate and deftest table (rows from the stage tables), its template's instructions, and its live runs (one-task Design chains; Prove followed by a held Implement; Implement from hand-written proofs once S3b lands) | each predicate's table: Design rows (sections, problem changed, unresolved reference, producer not cited, data not modeled, existing-schema bug fix, registered-schema new feature) and Prove rows (no successor, empty proofs, not a deftest, missing or green result, world error, unreached changed or cited function, assertion-red and body-error success); refusal sets asserted exactly | Sonnet per stage; Opus run lane for live runs | — |
| S5 | Run 26: the full Design → Prove → Implement chain, live DeepSeek | 0 hand acts after `chain!`, one merge, the defect fixed | Opus run lane | — |

**Parallel stages.** After S4a, each stage lane owns `src/seon/step/<stage>.clj` (`seon.step.<stage>/handoff`; none
for Implement), `test/seon/step/<stage>_test.clj`, `resources/seon/tasks/seon.step.<stage>.edn` and its own research
note for live observations; root applies each lane's status text to this table serially. Design and Prove are graded
after S3a at the boundaries in §4; Implement's full trials need S3b, failure trials S3c.

Beside the slices, file-disjoint: the reference-syntax page `docs/seon/architecture/references.md` (cited by help), a
read form for task references in `seon.ref/brief`; the errors cut (`errors-cut-plan.md`, E1 landed 5a08c13af).

Retired acceptance tests (they test the copies design): A1, A3, A4, A6's relaunch rows, A7, I3 and the rest of the
2026-10-13 table; I0 and A5/A5b stay green as they are. I1/I2 wait for the user-conversation task.

**Settled beside the slices (2026-10-14):** a REPL `def` is a program entity like a file's (de1f48c23); render
invocations live in one `seon.cache` kind and must-reads render through `seon.render/render-call` (35cafc1dc); a
constructed error carries no world or producer (5a08c13af, errors-cut E1).

**Status (2026-10-14):** ready for implementation; nothing of S1–S5 implemented. Design prototyped live once
(`design-prototype-run-1.md`); its findings are in §2.4, §2.6, §3 and §4.

## 8. Open questions

None open.
