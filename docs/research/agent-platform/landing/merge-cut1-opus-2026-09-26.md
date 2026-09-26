---
type: landing
lane: merge-cut1-opus
design: docs/research/agent-platform/merge-task-design-2026-09-26.md §1 (slice 1), review F3
status: landed — owner chose option 2; 9c825ba11 (1a), d8742ab1f (1b, with host_test); RESET NEEDED
---
# merge-cut1-opus — the merge task (slice 1)

## Files (posted before writing; kept to)

Owned and committed by this lane:
- `resources/seon/schemas/seon.task.edn` — `:merge` kind; assignment and proposal entities; runtime-task
  `created-by`/`outcome`; refusal rules.
- `src/seon/task.clj` (new) — the 7.8 runtime entrance: `create!`, `assign!`, `assignment`, `propose!`,
  and the task home connection moved here from `my.issue`.
- `src/seon/issue.clj` — `task-revision`; file reconciliation leaves runtime tasks alone.
- `src/my/issue.clj` — uses the moved home connection (deletion).
- `src/my/program.clj` — `propose-merge!` gate and delivery; `proposals` reads open merge tasks
  (roster inference deleted).
- `resources/seon/schemas/my.program.edn` — the proposal view replaces the message/roster proposal.
- `resources/seon/tasks/*.edn` — unfixed findings only.
- this landing note.

- `test/seon/cluster/host_test.clj` — released by one-commit-a to this lane; the patch was applied and committed
  in 1b.

## Broken first

1. **Live default (e405f51be, one-commit A) cannot run an agent's own write.** Every write an agent form makes on
   its branch refuses `Nothing found for entity id [:seon.cluster.eval/id <this form>]`: the evaluation's receipt
   lookup ref (`seon.sci.eval/evaluate`, `src/seon/sci/eval.clj:4341-4343`) names the evaluation row written only at
   settlement. Reproduced twice on branch `merge-cut1-opus`: a direct `(seon.db/transact! …)` (eval
   `78f381684a44`) and `(my.test/run-owned {:seon.ns/name 'user})` (eval `82cf53bace3b`, admission write). So no
   agent can run tests at its REPL, and `propose-merge!` (which runs the proofs) cannot pass on live A. Owner:
   one-commit lane / `sci/eval.clj` holder. Task: `resources/seon/tasks/seon.sci.eval.edn`
   "agent-form-writes-name-an-unwritten-evaluation". Not checked against B (fcee85161).
2. **`(my.test/check {:seon.test/changed ['user/a-test]})` refuses at a branch REPL**: `seon.fn/gate-sets` receives
   `[<db> {:seon.db/db <db> :seon.fn/seeds …}]` against its positional arity (callers `test.clj:835`, `:1066`), and
   building that refusal throws `ClassCastException Keyword -> Map$Entry`. Task in `seon.fn.edn`.
3. **MCP branch evals intermittently answer `events []`** although the evaluation settled with saved shown text
   (evals `fea1a8955086`, `4d2fab4f0f7d`). Task in `seon.dev.mcp.edn`.
4. **Default is `faulted`** by the pre-existing fault `fa60ad84…` (`seon.schema/valid-candidate-value?`), not this lane.
5. **Size rule tripped.** Slice 1 as written is **+271 / −67 gross src+schema (net +204)** against the design's
   +130 / −35. Per the lane rule I stopped before committing code; options below.
6. **Nothing of slice 1 is installed or run.** Default runs the committed archive; the new schema needs adoption
   (RESET NEEDED after the commit root chooses). The regressions are written (patch) but unrun.

## What was written (uncommitted; `tmp/merge-cut1-opus/slice1.patch`, 457 lines, against the current tree; these six files are unchanged at HEAD 7838bef71)

- **`seon.task/create!`** (7.8): runtime entrance over the existing writer `seon.issue/adopt-tasks-tx` (same
  declaration/basis comparison as `index-tasks!`): unchanged is a no-op without a write, a revised declaration
  refuses with base/current/proposed unless `:seon.task/basis` names the read revision. Writes on the task home
  (root's branch), recording `:seon.task/created-by`.
- **`seon.task/assign!`**: root writes an assignment entity (task handle, reviewed revision = `seon.id/digest 64`
  over the declaration, agent ref, branch), identity `seon.id/id [task agent branch]`.
- **`seon.task/assignment`**: resolves an agent+branch's assignment on the cluster, refusing
  `:revised-since-assignment` when the task changed; else the legacy issue (`:seon.issue/agent`, proofs =
  `:seon.issue/tests`); else `:unassigned` by name.
- **`my.program/propose-merge!`** takes no task argument: reads the assignment, proofs = task edges on the cluster
  ∪ on the branch, refuses `:no-proofs`; runs `seon.test/run {:policy :named :identities proofs}` on the agent's
  world; every proof must be `seon.test/verified?` on the post-run value (red, unknown, excluded, unfinished and
  unrecorded all read false) else `:unproven` naming them and **no message**; then `seon.task/propose!` writes in
  one cluster transaction the merge task (aspect `[ns "merge <key>" :merge]`, found again on re-proposal) and the
  proposal (recurrence key `[key C]`, C = the run's `:seon.source/commit-id`, proofs as `[sym digest]`, run id,
  tested program digest); then the message to root naming the merge task. A failed message leaves the facts.
- **`my.program/proposals`** reads proposals whose merge task has no `:seon.task/outcome`. The roster/message
  inference is deleted.
- **Design gap found and fixed:** `tasks-tx` (file reconciliation) selected stored tasks by namespace and would
  **retract a runtime task** whose namespace has a task file on the next reindex; runtime tasks
  (`:seon.task/created-by`) are now outside file reconciliation (`src/seon/issue.clj`).
- **Deleted:** `my.issue` `home`/`task-connection` (moved to `seon.task/home`, `home-connection`), the proposal
  roster inference, the message-content proposal, `:my.program/proposal`.

## Evidence (REPL, live default pid 59671, archive e405f51be)

| Probe | Result |
|---|---|
| MCP SCI on own branch: `seon.task/create!` resolves? | nil (absent, as expected); `seon.issue/index-tasks!`, `task-edges`, `seon.test/verified?`, `my.test/check`, `my.message/send` resolve; connection supplied by schema name for `seon.issue` fns (`index-tasks!` answered `{:seon.task/changed? false}`, 368 ms) |
| `adopt-tasks-tx` pre-check on branch value: same row twice / changed intent | both answered the insert row (the task was never stored: the write failed on broken item 1, 27 ms), so the no-op and conflict branches are unproven live |
| REPL `(deftest ^{:seon/tasks #{"023acee5f88d"}} …)` on branch | stored `:seon.task/tasks ["023acee5f88d"]` and a definition digest; branch `task-edges` lists it as a proof beside the cluster's |
| A red REPL deftest | admitted (entry does not refuse red), so `:unproven` is reachable through the ordinary path |
| Contracts compile (`seon.contracts-compile-test/check`, checkout projection) over the 4 src files + the test copy | 0 findings (371 ms); control with an unknown key reported 1 |
| Storage derivation of the 15 new/changed `:seon.task/*` attributes (`malli->datahike-attr-in`, checkout projection) | all derive: tuple `[symbol string]` many, uuid, identities unique, refs indexed |
| Schema validation of sample task/assignment/proposal rows, assigned-work, refusal | all `nil` explain |
| `seon.task` compiled in a scratch ns (JVM) | compiles; revision of task `023acee5f88d` = 64-hex; absent task nil; cluster home is itself; `assignment` answers the typed "uninstalled attribute :seon.task/agent" read error before adoption |
| Real-data reads | legacy-issue query 23.5 ms (0 assigned issues on default); `task-edges` 6.9 ms |
| New task EDN entries | validate against `:seon.task/file` |
| `clj-kondo --lint src script test` | 0 errors |

Branch `merge-cut1-opus` created and retired.

## Lines

| File | + | − |
|---|---:|---:|
| `src/seon/task.clj` (new; 37 docstring + 26 contract lines) | 163 | 0 |
| `src/my/program.clj` | 54 | 37 |
| `resources/seon/schemas/seon.task.edn` | 36 | 3 |
| `src/seon/issue.clj` | 13 | 2 |
| `src/my/issue.clj` | 5 | 22 |
| `resources/seon/schemas/my.program.edn` | 0 | 3 |
| **src + schema** | **271** | **67** |
| `test/seon/cluster/host_test.clj` (patch `tmp/merge-cut1-opus/host_test.patch`) | ≈85 | ≈20 |

Why over the +130 price: docstrings and complete contracts on 9 functions (~60 lines in `seon.task` alone), the
proposal view schema beside its stored row, and the runtime-task reconciliation fix the design missed.

## Options for root (priced; simplest first)

1. **Land 1a only now** — `seon.task.edn`, `issue.clj`, `my.issue`, and `seon.task` `create!`/`assign!`/`home`
   (≈ +150 / −27). Hold `assignment`/`propose!`/`propose-merge!`/`proposals` (≈ +120 / −40) for Astra review.
   Guarantee: agents create and root assigns tasks. Sacrifice: no proposal gate; the dogfood step stays open.
   ~0.5 h to split and commit.
2. **(Recommended) Land slice 1 as written, as two commits (1a, 1b)**, +271 / −67 (net +204), plus the host_test
   patch. Guarantee: the whole §1 behaviour (create, assign, gate, merge task, inbox) behind one Astra review.
   Sacrifice: +74 lines over the design's price, mostly docs and contracts. ~0.5 h to commit; proof waits on reset
   and on broken item 1.
3. **Smaller shape** — assignment as two attributes on the agent's cluster row instead of an entity, and no
   legacy-issue resolution: ≈ −25 lines. Costs an edit to `seon.agent.edn` (held by one-commit-a) and drops the
   design's legacy-issue proof. ~1.5 h.

## What root must run (after the chosen commit, adoption/RESET, and a fix for broken item 1)

- `git apply tmp/merge-cut1-opus/host_test.patch` (converts `a-hosted-agent-proposes-a-merge…` and
  `root-reads-a-branch-agents-proposal…`; adds the two regressions).
- `bin/test-check default --test seon.cluster.host-test/an-agent-creates-a-task-at-its-repl-and-it-is-a-cluster-task`
- `bin/test-check default --test seon.cluster.host-test/a-proposal-waits-for-its-assigned-tasks-proofs-then-reaches-root`
- `bin/test-check default --test seon.cluster.host-test/a-message-is-no-proposal-and-only-roots-custody-accepts`
- the two static checks (contracts compile, schema EDN) on the committed tree.

Unproven by any regression: legacy-issue resolution; delivery failure recoverable from facts (it is by
construction: facts commit before the message).

## Landed (owner chose option 2)

- **9c825ba11 (1a):** `seon.task.edn`, `src/seon/issue.clj`, `src/seon/task.clj`, `src/my/issue.clj`.
- **d8742ab1f (1b):** `src/my/program.clj`, `my.program.edn`, `test/seon/cluster/host_test.clj`. Both commits
  load in order (1a's `my.program` is the old one and needs nothing from 1b).
- **Pre-commit checks:**
  - Task ids: every explicit id in the three task files I touched equals `seon.issue/task-handle` evaluated in
    the JVM (pid 75849): 0 mismatches over 36 entries. My three entries carry no hand-minted id, and no source
    or test hardcodes one (the tests call `task-handle`).
  - `clj-kondo --lint src script test`: 0 errors.
  - Contracts compile over the five changed code files: 0 findings; the 3,542 packaged schema forms read.
- **RESET NEEDED**: the schema adoption for the new `:seon.task/*` attributes.

**Proofs waiting on task c9e0cde3edb3** (agent-form writes naming an unwritten evaluation; owner deciding). The
following cannot pass until it is resolved, and I did not work around it:
- all of `an-agent-creates-a-task-at-its-repl-and-it-is-a-cluster-task`, which writes from an agent form;
- all of `a-proposal-waits-for-its-assigned-tasks-proofs-then-reaches-root`, where the agent-form `create!`,
  the proof run's admission and the proposal write all write during a form;
- the converted `a-message-is-no-proposal-and-only-roots-custody-accepts`, whose `my.message/send` also writes
  from a form.

The static checks do not wait on it.
