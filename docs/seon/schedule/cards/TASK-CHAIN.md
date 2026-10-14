---
title: "TASK-CHAIN: a chain is linked task entities on one branch"
type: spec
status: DRAFT (2026-10-14), being fleshed out with the owner; replaces the 2026-10-11/13 copies design (history in git)
created: 2026-10-11
revised: 2026-10-14
---

# TASK-CHAIN

A chain is a few ordinary task entities, created together, linked by `:seon.task/next`, worked one after another by
separate agents on one branch, and merged to main once at the end. Root finds every chain through one fact on main and
reads its branch directly. Everything is data in the database; root's listener reacts to it. No copies, no subject
task, no per-step merges, no automatic relaunch.

Evidence for every mechanism below is in `docs/research/agent-platform/2026-10-14/`: `chain-in-one-transaction.md`,
`chain-on-one-branch.md`, `reactive-scheduling.md`, `sci-reuse-across-tasks.md`, `cross-branch-references.md`,
`design-stage-table.md`, `prove-stage-table.md`, and the two independent reviews of the first draft
(`review-task-chain-opus.md`, `review-task-chain-astra.md`), whose findings are folded in below.

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
  other templates on the same mechanism.
- Proofs may be nested Clojure: a deftest may call other test functions conditionally or short-circuit.
- Agents edit with Clojure's own shapes: `my.task/update!` has `clojure.core/update` semantics; text changes by exact
  replacement (`my.edit/text`). Help is a Markdown document resolved like a plan.

## 1. Data

### The task

A task is one entity. Declared members (all `:seon.task/*`; `seon.task/declaration` lists them):

| Member | Type | Meaning | Kind |
|---|---|---|---|
| `ns`, `name` | symbol; name a string when stored (task files author a symbol, ingestion normalizes it) | the task's name; `:seon.task/id` and `:seon.task/sym` derive from them (`seon.task/task-handle`, `seon.task/task-sym`) | identity |
| `plan` (renames `intent`) | Markdown string | the plan: blocks such as `## Problem`, `## What exists`, `## Approach`, with references (§3) | **flowing** |
| `instructions` | Markdown string | what this task's agent does | **owned** |
| `proofs` | set of qualified deftest symbols; absent means empty, never proven | the one set that must be green for this task to be done | **owned** |
| `next` (new) | one task ref (cardinality one) | the task that follows; absent on the last | link |
| `execution` | `:held :running :paused :stopped :cancelled`, Malli `:default :held` | whether it runs; root is its only writer on a chain | control |

**Owned** members carry `{:seon.task/owned true}` on their schema: a task's template sets them when the chain is
created, and an agent may change them (Prove writes the next task's `proofs`); they never flow. **Flowing** members
(every other declared member that is not identity, link or control: `plan` today) are carried to the next task at each
advance, the finished task's value winning (§2.5). A new flowing member needs no code change.

Derived, never authored: `:seon.task/references` (the names `plan` and `instructions` cite, by `seon.ref/names`),
derived by the one task writer (§1, "One writer"). Kept as they are: `:seon.task/created-by`, `cron`, `zone`,
`function`.

**Deleted:** `intent` (renamed), `step`, `product`, `children`, `needs`, `:seon.task/tasks` and every `:seon/tasks`
tag, `:seon.task.attempt/relaunch`, `:seon.config.run/relaunches`. The 17 task-file entries carrying `needs` (19
links, between filed backlog tasks) lose them: their order is root's schedule, not a chain.

### The attempt

An attempt (`:seon.task/assignment` row, its `:seon.task.attempt/*` members) is one agent's turn at one task, and on a
chain it lives on the chain branch, never on main. Its **outcome** is the one place completion lives:
`:seon.task/outcome` on the attempt row, `:done` (new value: the task's proofs passed and the chain moved on),
`:merged` (the terminal attempt, accepted into main), or `:closed` (root's decision with a reason). A task is done when
its latest attempt's outcome is `:done` or `:merged`; nothing else stores completion. Attempt numbers keep counting
(`:seon.task/attempts-made`), so a restarted task's next attempt has a new identity and a new agent id.

### Templates

Ordinary tasks in `resources/seon/tasks/seon.step.edn`: `seon.step/design`, `seon.step/prove`,
`seon.step/implement`, each holding only its owned members (`instructions`, `proofs`), `:held` so they never run.
File admission accepts a task without a plan (today's non-parent `:intent` requirement goes). Their proof predicates
are contracted functions in `src/seon/step.clj`; their deftests are in `test/seon/step_test.clj` (§4).

### The chain fact on main

One entity on main per chain: `:seon.chain/branch` (the branch keyword) and `:seon.chain/first` (the first task's
`:seon.task/id`, a value), identity `:seon.chain/first`. It is non-program (never merges as program), declared
`:seon.wake/starts true` (its installation and retraction wake root), and counted by
`seon.cluster.registry/cluster-claims` so boot's `seon.cluster.registry/retire-unclaimed!` keeps its branch, failed or
paused chains included. It is discovery, not status: root reads the chain's state on its branch. (Not
`datahike.reference`: with one store its store id is redundant, an eid selector cannot be written before the eid
exists, and `resolve-reference` needs a live connection that a stopped task has released.)

### One writer for tasks

Create, `update!` and advance all go through one task transaction producer in `seon.task`, which derives identity,
`:seon.task/references` (`seon.task/with-references`), revision and `:seon.task/created-by` from the final values, and
preserves the negative tempids `chain!` uses. A plain `seon.db/transact!` of task maps is not that writer. Chain tasks
are runtime tasks (`:seon.task/created-by` root): they live in the database and its history, and the final merge never
writes them into `resources/seon/tasks/*.edn`. `symbol-name?` moves into the `:seon.task/name` schema, so the checked
writer refuses a bad name for every writer and `seon.task/create!`'s separate check goes.

## 2. Flow

1. **Create** — `seon.task/chain!`:
   `[:=> [:cat :seon.db/connection :seon.task/chain-request] [:or :seon.task/chain :seon.error/value]]`, the request
   `{:seon.task/ns ns :seon.task/name n :seon.task/plan p :seon.task/templates [sym …] :seon.task/overrides {sym {…}}}`,
   the answer `{:seon.chain/branch b :seon.chain/first h :seon.task/handles [h …]}`. It derives each task's name as
   `"<template name>-<n>"` (`design-n`, `prove-n`, `implement-n`) and its identity with `task-handle`; links each to
   the next from the ordered vector (so no cycle and no wrong id can be written; nothing checks them). In order:
   fork the branch (`seon.cluster.registry/fork!`, `chain-<h>`), unless it exists; on it, write every task in ONE
   transaction through the task writer, each `(merge defaults template overrides)` with a negative tempid, `plan` on
   the first task only, the first `:running`, the rest `:held`; then write the chain fact on main. It is idempotent:
   a repeated call with the same first task returns the existing chain and changes nothing. A crash after the fork
   and before the fact leaves an unclaimed, never-started branch, which boot retires; no agent starts before the fact
   is committed. Expected cost: one fork (53–56 ms measured), one committed task transaction and one main transaction
   (about 22 ms each measured for small writes): about 100 ms. The checked in-memory application alone measured
   3.6 ms; measure the whole durable create in S1c.
2. **Run a task** — root's listener `seon.cluster.agent/db<->flow`, each pass, reads every chain fact on main and each
   chain's branch head with `seon.db/branch-database` (a store read; it needs no open connection, as
   `seon.cluster.agent/branch-end` does today), cached by head commit id. A chain's work is only its first task's
   `next` path and those tasks' attempts: the branch also inherits main's unrelated tasks, which are not its work. For
   a `:running` task with no open attempt, root writes the attempt row on the chain branch (through
   `seon.cluster.registry/active-branch-connection`, else the store owner's `open-branch!`), then starts a NEW agent id
   on that branch (`seon.cluster.agent/acquire-context!` with `{:seon.agent/branch b}`: 3.9 ms while another agent
   holds the branch, 22.7 ms when nobody does). It never forks a branch per task and never reuses an agent's SCI
   context (that would carry its private defs and result handles). `:seon.config.agent/max-running` counts open
   attempts across all chains.
3. **Work** — the agent reads its block (`seon.context/current-state`: its task's plan, instructions and proofs with
   their status, the tasks before and after it) and works at the REPL. An agent reads its own task's execution on its
   own branch (`seon.turn/attempt-running?` changes from main's head to the branch: root is the only writer of a chain
   task's execution, on the chain branch). After every eval request that evaluated a form, the runner runs: every test
   defined or changed on the branch since its fork (`seon.test/changes`, the existing fork-relative owner, by behavior
   change), the tests reaching changed functions, the next task's proofs when the task's own proofs read them, and
   **then** the task's own proofs, so a proof that reads other tests' results reads current ones. Unchanged results are
   reused by reach digest. All green at the start ends the task with no model call.
4. **Done** — the done rule, for every task: new `seon.cluster.source/done-refusals`
   `[:=> [:cat :seon.db/database-value :seon.db/database-value :seon.source/judged] :seon.source/refusals]`,
   arguments `[branch main judged]`, a function of recorded results that runs no test. It refuses: a proof with no
   current result; a proof not green; a test the branch holds red whose result at the same reach main holds green; and
   a function new or changed on the branch with non-empty `seon.contracts/gaps`. A test main holds no result for is
   unknown, not a regression: the final merge runs its baselines (`prepare-merge!`). New red tests outside the task's
   proofs (Prove's) are visible and never stop the task. The agent's turn proposes `:done` (`seon.turn/task-end`);
   root re-judges `done-refusals` over the branch value at the attempt's end commit (`:seon.task.attempt/commit`).
   `seon.cluster.source/results-verdict` stays the judge of the final merge only, its product clause deleted.
5. **Advance** — root, in the lifecycle phase of `seon.cluster.agent/run-pass!`, one transaction on the chain branch
   through the task writer, decided at the writer (a `:db.fn/call` over the held value, so a repeated pass finds the
   outcome and derives no work): the finished attempt gets `:seon.task/outcome :done`; the next task receives the
   finished task's flowing members (the finished value wins) with re-derived references, and `:execution :running`.
   The plan phase of the same pass reads the branch after the advance and starts the next agent, as `run-pass!`
   already re-derives after `:stop` and `:merge`. Each task keeps the plan it was given: the plan's history is the
   chain's history.
6. **Finish** — a done task with no `next`: root merges the chain branch to main once. The merge owner
   (`seon.merge/merge!` → `seon.cluster.source/prepare-merge!` → `seon.cluster.source/accept-merge!`) takes the chain
   branch and the terminal attempt as its source; `seon.merge/assignment-row`, `seon.merge/assigned-task` and
   `seon.cluster.source/tested-task-basis` read the held source branch, main only as the destination. The merge
   judges the whole fork-to-head delta with `results-verdict`: the last task's proofs, every test the branch defines or
   changes and every reaching test green, contracts complete, replaced functions covered. In its one transaction it
   admits all the chain's tasks and attempts, closes every intermediate attempt (`:done`) and marks the terminal one
   `:merged`, so no earlier attempt appears on main as an open merge request. Then, in order: `seon.merge/publish!`
   (a `:seon.merge/waiting` or a publication refusal is not success: the chain fact stays and root sees it); on
   success join the chain's graphs, release its connections, retire the branch and its base
   (`seon.cluster.registry/retire-branch!`), then retract the chain fact. On restart, a chain fact whose branch is gone
   after a recorded merge is cleaned up; one whose branch is gone otherwise is shown to root as unknown.
7. **Bail or fail** — every end that is not done: `:capped` (turns spent; `seon.turn/turns-left` is the budget),
   `:stalled`, `:given-back`, `:sent-back` (`my.task/send-back!` with its reason), `:error`, a done that
   `done-refusals` refuses, or a refused final merge. Root processes it once, atomically on the chain branch: the task
   moves to `:held`, the end and its refusal stay, and root writes one problem task on main, its identity derived from
   the terminal attempt so repeated wakes write no second one. Nothing else runs; the branch and the chain fact stay.
   `:cancelled` ends the chain with nothing for root. Root then decides: change a task's proofs or plan, set a task
   `:running` again (`my.root/run!`, one root function that writes the branch and touches the chain fact on main, the
   main write being the wake), or abandon: record root's `:closed` decision with its reason, then the same retirement
   as step 6. A send-back is a bail: the chain never reruns an earlier task by itself. Reopening an earlier task means
   root sets it `:running`; its new completion advances again and the successor receives the revised plan.
8. **Restart** — recovery of facts, never of execution, through the existing owners (`seon.cluster/recover-runs!` on
   each retained chain branch; no replay of forms or private SCI values). An attempt interrupted during active work ends
   `:error` (committed work and spent budget kept) and goes to root as step 7. An attempt committed but never started
   is hosted under the same id. A committed end is judged on the next pass; a committed advance starts its successor;
   an accepted merge resumes publication.

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
references; `path:N` is a problem. A name the plan means to create is not rejected: it is data the task's proofs judge
(Design stays red until it exists as a contracted stub or the typo is fixed). The blocks each task writes are a
starting convention, revised as live runs teach us: the chain's creator writes `## Problem`; Design adds
`## What exists` and `## Approach`.

## 3a. Agent verbs and what references show

**One write verb, `update` semantics.** `my.task/update!` replaces `my.task/refine!`:

```clojure
(my.task/update! task k f & args) ; like clojure.core/update, on a task entity's member k
(my.task/update! 'seon.ref/implement-fix :seon.task/proofs into '[seon.ref-test/a-reference-with-no-path-is-a-missing-file])
(my.task/update! 'seon.ref/design-fix    :seon.task/plan my.edit/text "throws on" "answers a missing-file error for")
(my.task/update! 'seon.ref/design-fix    :seon.task/plan (constantly whole-new-plan))
```

- `task` is a task symbol; `k` a declared member, `execution` and `next` excepted (root's and `chain!`'s).
- It reads the member on the caller's branch, applies `f` there (an ordinary call, in the agent's own context), and
  writes the result through the task writer with the read value as its basis, so a member changed in between is
  refused with the current value (`:seon.task/conflict-error`) rather than lost, like `compare-and-set!`. Nothing
  retries; the agent reapplies.
- The new value is validated against the member's schema before writing.
- It answers `{:seon.task/id … :seon.task/changed? bool}` plus `:my.task/problems`, each reference in a text member
  that does not resolve (`seon.ref/references`). An unresolved name never rejects the write.
- Its callers convert from `refine!` in the same commit; `refine!` and its goal-owner routing are deleted.

**One text function, `my.edit/text`.** `(my.edit/text s old new)` answers `s` with the one exact occurrence of `old`
replaced by `new`, or `:my.edit/no-match-error` / `:my.edit/ambiguous-match-error` (with the matching lines) when
`old` occurs zero or several times. It is the string core of `seon.edit/exact` (which `my.edit/exact!` applies to
files), split out as a pure function over strings and shared by both: no second editor. Exact replacement finds its
place by content, so it survives edits above it, unlike line-numbered patches.

**Help is a Markdown document like the plan.** Its text moves out of code (`seon.bootstrap/help-value` builds it
from fixed strings today) into `resources/seon/help.md`, read by `seon.ref/document` and resolved by
`seon.ref/references`: every reference in it shows its doc view, cited once. `(help)` answers the document's sections
with their resolved references; `(help "change-a-member")` answers one section by its heading anchor, like
`my.fs/section`. The help value is cached by the file's content digest, so it is parsed and resolved once per version;
its size in tokens is measured with the block's. A deftest proves every reference in help resolves
(`seon.bootstrap-test/every-reference-in-help-resolves`). Its first sections teach three moves:

- change a member: `(my.task/update! task k f & args)`;
- change text: `(my.edit/text s old new)`;
- see what changed: `(editscript.core/diff before after)` (editscript is the library for diffs of Clojure data; it is
  not an editing language for agents).

**What a reference shows: its doc view, the full form one evaluation away.** Every reference, in a plan, in help,
in a docstring, shows the doc view of its kind; the full definition is the read form it offers. Nothing shows a whole
source unless the agent asks.

| Kind | Doc view | Full form |
|---|---|---|
| Function, library Var | docstring summary, arglists, contract with its named input and output schemas, each schema's one-line description | `(my.program/source 'f)` |
| Schema key | its form and description | `(my.program/source :k)` |
| Deftest | docstring summary, the functions it reaches, its latest status | `(my.program/source 't)` |
| Document section | heading, the headings below it, its size in tokens | `(my.fs/section "path#anchor")` |
| Task | name, the plan's first line, its proofs with their status | `(my.task/current …)` |

The doc view is each kind's declared AI render at brief depth (distance 1), with the schemas it names one level
below, cited once (`seon.ref/cite-once`). Today the block renders a task's must-reads whole; the raw block measured
about 38k characters, 31k of them must-reads (an estimate of about 9.5k tokens from characters; the rendered AI context
was not measured). The doc view replaces that, measured before and after on the same task's block.

## 4. Templates and their proofs (version 1; S5's one-task live runs revise them)

Each template's proof is one contracted predicate in `src/seon/step.clj`,
`[:=> [:cat :seon.db/database-value :seon.task/task] :seon.source/refusals]` (empty means satisfied; each refusal names
the failed check), plus one deftest in `test/seon/step_test.clj` that (a) runs the predicate over a table of
hand-built task values through the canonical writers on a test branch, red and green rows, so it is meaningful and
green on main and on merge branches, and (b) when a task is bound (`(seon.test/task)`, the runner's one task input),
asserts the predicate is empty for that task on the agent's branch. A task-reading test invoked without a task never
falls back to a tag. Every failed check is reported, not only the first. Assertion messages are fixed prose.

**Shared helpers** (in `seon.step`, each contracted):

- `sections [plan] → {anchor text}`: each heading anchor (`seon.ref/document`) with its text up to the next heading.
- `problems [database text] → [problem]`: `seon.ref/references` entries carrying `:seon.ref/problem-kind`.
- `fork-database [database] → database`: `(seon.db/commit-database database (seon.cluster.registry/base-commit-id
  database branch))`, `branch` being `(get-in database [:config :branch])`.
- `changed [database attribute] → #{identity}`: the values of identity `attribute` (`:seon.fn/sym`,
  `:seon.test/sym`, `:seon.schema/key`) whose `:seon.program/definition-digest` on the branch differs from, or is
  absent on, the fork database.
- `results [database tests] → {test result}`: `seon.context/current-results` under the bound task input.

### Design: `seon.step/design`

Instructions:

```markdown
## Work

Make the plan say exactly what the problem is, in terms of the data. Read `## Problem` first. Then search the system
for what already exists: `my.program/search` with the words of the problem, `docs/seon/architecture/vocabulary.md`
for the words we use, `my.program/source` on each owner you find. Change the plan with `my.task/update!`
(`(my.task/update! this-task :seon.task/plan my.edit/text old new)` for one passage), keeping `## Problem` and adding:

- `## What exists`: the producer of the wrong or missing value, its callers, the schemas its inputs and outputs use,
  and the tests that cover it, each cited in backticks as a fully qualified symbol or keyword.
- `## Approach`: the data first (the schema keys involved, and any schema to add or change, registered on this
  branch), then each function to change or add with its name, arity and complete contract over named schemas, and
  what it replaces or deletes. A new function is a `defn` in its owner's namespace with its full contract and the
  body `(throw (ex-info "not yet" {:seon.error/type :seon.error/unimplemented}))`.

Backtick every name you cite: a backticked name that does not resolve shows up as a problem to fix.

## Done

The three sections have content, every reference resolves, the plan names the schemas involved, and every function
you defined or changed has a complete contract. Your proofs show what is missing after each evaluation.
```

`seon.step/design-handoff` (proofs `#{seon.step-test/design-handoff}`) refuses:

1. `:sections`: `problem`, `what-exists` or `approach` absent or blank.
2. `:references`: `(problems database plan)` not empty.
3. `:data-not-modeled`: `(changed database :seon.schema/key)` empty AND no resolved schema key among the plan's
   references. This is a floor that serves bug fixes (existing schemas cited) and new features (a schema registered);
   a new-feature chain may later demand a registered schema.

Contracts are judged by the done rule (§2.4) for every task, over the functions new or changed on the branch (judging
the plan's cited functions instead was measured wrong: an existing function's old gap turned Design red, and an
uncited stub with gaps stayed green). A library Var a plan cites must resolve but acquires no Seon contract. A new
function's output, like its inputs, names a registered schema (`seon.contracts/gaps` refuses a bare `:map`). A stub
naming an unregistered schema is refused at the REPL and never exists, so it fails check 2. Measured warm: about 2 ms
per run (7 ms worst case for `changed` over functions).

### Prove: `seon.step/prove`

Instructions:

```markdown
## Work

Write the deftests that will show the task done, one behavior each, asserting data: every exact and boundary case the
plan implies as a row of a table test (`are`), and a property with a bounded generator and a fixed `:seed` where a
general law holds. Prefer a new row in an existing table test. Run them: they must fail now because the behavior is
missing, by a failing assertion or an error the code under test raises, never because the test does not load. Then
write their symbols into the next task's proofs:
`(my.task/update! next-task :seon.task/proofs into '[the.ns-test/a-case …])`, the next task being the one your block
shows after you. Unmap scratch helpers (`my.program/ns-unmap!`): everything on the branch merges at the end.

## Done

The next task has proofs, each ran red for the right reason, and every function defined or changed on the branch is
reached by at least one of them.
```

`seon.step/prove-handoff` (proofs `#{seon.step-test/prove-handoff}`) refuses:

1. `:no-proofs`: the next task (`:seon.task/next`, pulled on the branch) has no proofs.
2. `:not-a-deftest`: a proof names no deftest on the branch (`:seon.test/sym`).
3. `:not-designed-red`: a proof has no current result, or its result does not satisfy
   `seon.test.runner/designed-red?` (a load failure has no result; a green proof proves nothing).
4. `:unreached`: a function in `(changed database :seon.fn/sym)` is reached by none of them (`seon.fn/tests-reaching`).

### Implement: `seon.step/implement`

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

`seon.cluster.wake/route!` wakes root's plan channel for declared starting and matching datoms (an attempt end is
one; the chain fact is declared one by S1). The wake carries no data and `run-pass!` re-derives every chain each pass;
the plan channel is a sliding buffer of one, so wakes coalesce into full derivations. Measured today:
`seon.cluster.agent/plan-facts` at about 355 ms warm and 2.3 s cold over a hypothetical 834 ready leaves (1.3 s of it
`basis-valid?`), about 20 ms summed for an idle pass. Order: first make `seon.cluster.agent/basis-valid?` keyed by what
it read (or delete it with `obligations`), remove the relaunch derivations, then measure a pass over chain facts.
Only then decide whether wakes should carry changed ids (each with its branch); not a commitment of this spec.

## 6. What is deleted

Each deletion lands in the commit that converts its last caller.

- Task data: `seon.task/effective`, `goal-owner`, today's `chain!` and `chain-copy`, `children`, `needs`, `step`,
  `product`, `intent`; the `:seon.task/needs` readiness clauses of `seon.task/ready-rules`.
- Proof selection: `seon.task/obligations` and `assigned-obligations` (a task's proofs are its field),
  `seon.test.runner/single-tags` and the run input's extra names (`:seon.test/task` stays the one request key,
  `seon.test/task` the one reader; one value flows from selection through execution, the recorded input identity and
  `seon.context/current-results`, for task-reading tests only), `seon.turn/step-proved?`, `proved-unanswered?`,
  `task-obligations`, `task-proofs`, `step-tests`, `seon.context/task-done?`, `seon.context/step-status` (callers call
  `done-refusals`), `seon.cluster.agent/basis-valid?`'s `:prove` successor branch and its commit-keyed cache.
- Tags: tag ingestion in `seon.fn` and `seon.fn.analyzer`, `seon.fn/task-links`; the tag queries in
  `seon.task/task-edges`, `open-tasks`, `task-findings`, `candidate-dangling`; `seon.cluster.agent/render-identity-html`'s
  namespace tags; the `:seon.task/tasks` members of the fn, test and ns schemas; `:seon.task/annotation` and its links;
  the help and `:seon.task/assignment-view` text naming tags. The sweep is one committed script that reads syntax
  through the existing reader (rewrite-clj, aliases resolved as ingestion does): each deftest's symbol joins the
  `:seon.task/proofs` of every task it tagged (a union with existing proofs); `defn` and `ns` tags name subjects, not
  proofs, and become backticked task references in the var's docstring or are dropped; tags inside strings (fixtures)
  are converted by hand where a test needs them. Measured: 344 symbols in test tag sets naming 247 tasks (39 of them
  fixture names inside strings), 33 in `src`. Acceptance: before/after task→proof sets equal for retained tests; no
  non-deftest proof; no live old attribute or ingestion path; reload retracts the old tag datoms.
- Lifecycle: the relaunch path (`seon.cluster.agent/assignment-actions`' relaunch rows, `allowance-left`,
  `:seon.flow.plan/allowance-left`, `work-left?`, `:seon.flow.plan/relaunch`, the fork-point relaunch clauses in
  `plan` and `fork-attempt!`, `seon.task/relaunched`, the `:relaunch` kind of `seon.task/end-kinds`, the relaunch clause
  of `seon.task/ready`); `copied?` in `assignment-actions`; `seed-attempt!` and the per-attempt main merge-base write
  for chains; per-step `:merge`, `:publish` and `:delete-branch` for tasks with a `next`.
- Text: `my.task/refine!`, the string replacement inside `seon.edit/exact` (it calls `my.edit/text`'s core), the
  fixed help strings in `seon.bootstrap/help-value`.
- Templates: the survey and define templates and their deftests, the `worked-results` constant.
- Dependencies: the unused `io.github.nextjournal/markdown` (a second Markdown parser nothing requires), in root's next
  restart batch, since `deps.edn` changes only there.
- Laws and documents that state tags, `intent` or `refine!` convert in the same commits: CLAUDE.md "Write tasks as
  data" and "Documentation is data", `.agents/skills/clojure-testing/SKILL.md`, `docs/seon/architecture/vocabulary.md`.

## 7. Slices

Serial where owners are shared (`seon.task`, `seon.turn`, `seon.context`, `seon.cluster.agent`, `seon.test.runner`,
`seon.cluster.source`, `seon.merge`). Each slice: a Haiku pre-brief (every caller, test, quoted symbol and schema
reference it changes or deletes; the reds on HEAD), its red deftest first, HEAD boots after it, committed through
`bin/lane`, and this table's status column updated in the same commit. Fixtures use the canonical writers and agent
fixtures, never hand-built production entities; `:seon.turn.loop/complete` is the injected model, never a replacement
for real test execution.

| # | Slice | Red proof (exact assertions) | Model | Status |
|---|---|---|---|---|
| S1a | The `intent` → `plan` rename: one rewrite-clj script over the task files (856 entries in 101 files), src, tests, schema, CLAUDE.md, vocabulary | none new (existing reds unchanged, named in the commit) | Sonnet; in root's restart batch (a rename touching most files) | — |
| S1b | Schema: `:seon.task/next` (one ref), `{:seon.task/owned true}` on `instructions` and `proofs`, outcome `:done` on the attempt, Malli `:default :held` on execution decoded in `seon.task/task-delta` and `held-tx`, `symbol-name?` in the name schema, the chain fact (`:seon.chain/*`, non-program, `:seon.wake/starts`) and its claim in `seon.cluster.registry/cluster-claims` | `seon.task-test/task-members-have-defaults-and-kinds`: `(m/decode :seon.task/task {} (mt/default-value-transformer {::mt/add-optional-keys true}))` gives `{:seon.task/execution :held}`; the owned members are exactly `#{instructions proofs}` by schema property; a bad name is refused by the writer; a branch named by a chain fact survives `retire-unclaimed!` | Sonnet | — |
| S1c | The one task writer and `chain!` (§2.1); delete old `chain!`/`chain-copy`, `children`, `needs`, `effective`, `goal-owner` with their callers and tests | `seon.task-test/a-chain-is-linked-tasks-written-in-one-transaction-on-its-branch`: the branch exists; on `(seon.db/branch-database main b)` the first task's `next` path names the three derived symbols in order, all written in one transaction; each has its template's instructions and proofs, references derived; only the first has the plan; executions `[:running :held :held]`; main holds no chain task or attempt and holds the chain fact; a second identical call answers the same value and transacts nothing; the durable create measured end to end (`seon.profile/measure`) | Opus | — |
| S1d | Agent verbs: `my.task/update!` (replacing `refine!`), `my.edit/text`, help as `resources/seon/help.md` through `seon.ref` | `my.task-test/update-applies-a-function-to-a-member-with-its-basis`: `into` on proofs, `my.edit/text` on the plan, a stale basis refused with the current value, an invalid value refused, an unresolved reference answered as a problem with the write kept; and `seon.bootstrap-test/every-reference-in-help-resolves` | Sonnet | — |
| S2a | One task input: the explicit task value from selection through execution, recorded input identity and `seon.context/current-results` (Run 25's root cause, reproduced live: `seon.test.runner/reach-digests` with and without the task disagree) | `seon.task-test/a-tasks-proof-is-recorded-and-read-under-its-task`: two tasks sharing one template proof with different plans each record and read their own result at their own key; an unchanged second request reuses; a plan or next-proof change invalidates; a task-reading test with no task reports missing input, no tag fallback | Opus | — |
| S2b | The done rule (`done-refusals`) and whole-branch selection (`seon.test/changes` after every request; the task's own proofs run last) | `seon.cluster.source-test/done-refusals-judges-every-task-alike`, a table: proof red → refused; proof green with a new red test outside the proofs → done; a test main holds green now red → refused; a new function with a contract gap → refused; main holding no result → not a regression | Opus | — |
| S2c | Proofs are the field: the tag sweep script and its commit; delete tags and every tag reader (§6), `obligations`, `single-tags`, the turn wrappers | the sweep's acceptance table (§6); every retained task's proofs equal its old tagged set | Sonnet | — |
| S3a | Root reads chains: chain facts, `branch-database` reads cached by head, a chain's own `next` path only, attempts on the chain branch, a new agent per task, `attempt-running?` on the branch, the advance (§2.5) | `seon.cluster.db-flow-test/a-two-task-chain-reaches-its-second-model-call`: after `chain!` alone, task one ends done by the scripted model, task two's first model call happens with zero hand acts, three distinct agent ids would appear across three tasks, no chain task or attempt on main, an unrelated `:running` task on main is not launched on the chain | Opus | — |
| S3b | The final merge of a multi-attempt branch (§2.6) | `seon.cluster.merge-test/a-chain-merges-once-and-closes-every-attempt`: before acceptance main holds no chain task; one acceptance admits all three tasks and every earlier code change; `my.root/merge-requests` is empty after; publication commits once; branch, base and chain fact are gone; a second pass is a no-op; a conflicting second chain reports its conflict and keeps its branch | Opus | — |
| S3c | Bail, abandon and restart (§2.7, §2.8) | `seon.cluster.db-flow-test/a-bail-goes-to-root-once`: a `:capped` end leaves the branch and chain fact, the task `:held`, exactly one problem task after repeated wakes, no successor started, nothing merged; plus a restart table over the durable stopping points of §2.8 | Sonnet (after S3a) | — |
| S3d | Doc views (§3a): references render at brief depth; must-reads stop rendering whole | `seon.context-test/a-referenced-function-shows-its-doc-view`: the block shows docstring, contract and schema descriptions, no source, and the read form; the block's tokens measured before and after on one task (`seon.profile/measure`) | Sonnet (after S3a) | — |
| S4 | Template predicates and deftests (§4), after S2a fixes the task input | `seon.step-test/design-handoff` and `prove-handoff`, each its table (rows from `design-stage-table.md` and `prove-stage-table.md`) plus the bound-task assertion | Sonnet | — |
| S5 | Live, one task at a time: a one-task chain of Design on a real defect, graded against its predicate; then Prove from a hand-written plan; then Implement from hand-written proofs. A one-task chain of Design or Prove is not mergeable on purpose (stubs, red tests): it is graded and retained, never merged. Revise instructions and predicates from what each run shows | each run's record: hand acts, calls, tokens, and whether the predicate judged the work right | Opus run lane | — |
| S6 | Run 26: the full Design → Prove → Implement chain, live DeepSeek | 0 hand acts after `chain!`, one merge, the defect fixed | Opus run lane | — |

Beside the slices, file-disjoint: the reference-syntax page `docs/seon/architecture/references.md` (cited by help) and
a read form for task references in `seon.ref/brief` (Sonnet); the costs measured 2026-10-14 filed as tasks.

Retired acceptance tests (they test the copies design): A1, A3, A4, A6's relaunch rows, A7, I3 and the rest of the
2026-10-13 table; I0 and A5/A5b stay green as they are. I1/I2 wait for the user-conversation task.

**Status (2026-10-14):** spec being fleshed out with the owner; Design's predicate proven at the REPL
(`design-stage-table.md`); Prove's table running. Nothing implemented.

## 8. Open questions

1. Green at the start ends a task (owner ruling). For a first task this contradicts "a task already green proves
   nothing" (`seon.task/assign!` refuses it today). Proposed: a chain task green at its start advances with nothing
   merged (its handoff is already met); `assign!`'s refusal stays for single tasks only.
2. Should a reopened earlier task's completion clear its successors' completions, or does root restart them one by
   one? Proposed: root restarts what it chooses; nothing clears automatically.
