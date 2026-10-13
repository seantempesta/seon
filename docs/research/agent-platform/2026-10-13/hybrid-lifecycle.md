---
title: "Hybrid: the step lifecycle — one judge, templates as task data, handoff proofs"
type: research
status: hybrid of plan-lifecycle-opus.md (4c641301c) and plan-lifecycle-astra.md (16326bf9e); decides the design round of 2026-10-13; the narrow check (§7) runs before any task is filed
model: Claude Fable 5.1 (wrote neither plan)
basis: "HEAD 16326bf9e; the dig (dig-step-lifecycle.md), audit-night.md, TASK-CHAIN §3/§3b, run-24.md, the round brief; source read at HEAD: seon.context, seon.cluster.source, seon.cluster.agent, seon.task, seon.turn, seon.turn.batch, seon.sci.eval, seon.fn; one runtime_status read on default (basis t 536871350, :faulted, open-fault 1, agent-error 1); no SCI as root, no write, no restart"
---

# Hybrid: the step lifecycle

Both plans reach the same law from different sides: **a step is done exactly when the merge would accept its branch
against main's head, judged by one function the merge itself calls.** They differ on where the verdict lives, what
happens when main moves between verdict and merge, how templates compose and how much new task data to add. This
hybrid takes the smaller composition on each point and says why (§6).

**Broken first** (verified by both planners on default, confirmed by this read: `:faulted`, one open fault). Run 24's
Implement branch `:attempt-8667f9088300` is judged **`:done`, `unmet {}`** by `seon.context/step-verdict` while the
merge's own static half, run by hand on the same branch against main's head, refuses **`:not-equivalent`** in
102–340 ms (Opus 120–340 ms, Astra 102/67 ms). The verdict and the merge disagree on live data; that is the defect
this round removes.

## 1. One judge (question 1)

### 1.1 The functions

- **`seon.cluster.source/merge-refusals`** `[head branch root proofs] → [:vector :seon.error/value]`, the **static
  half**: everything `prepare-merge!` does before `seon.cluster/candidate-gate!` plus the two static checks
  `accept-merge!` runs after the gate for no reason: `compare-branch` (conflict, empty delta), `replacement`,
  `seon.db/with`, `proven-files`, `lint-errors`, `contract-gaps` → `uncontracted`, `seon.task/candidate-dangling`. It
  takes **main's head as a held database value** (Astra: "an immutable target value through the execution inputs,
  never its writable connection") and the branch's head; the merge base identifies the proposal, never the target.
- **`seon.cluster.source/test-refusals`** `[tested results proofs product head-commit] → refusals`, the **test half**:
  `accept-merge!`'s membership (`unproven`), `seon.test.runner/designed-red?` on the product's recorded results,
  `red-test-verdicts` with `seon.test/fork-point-red` at **main's head commit**, and `uncovered`. It reads a vector of
  recorded results: S's run for the merge, the branch's latest results at reach (`seon.test.runner/result-at-reach`)
  for the step.
- **`seon.cluster.source/merge-verdict`** is `(into (merge-refusals …) (test-refusals …))`: the one judge. Empty means
  accepted.

`prepare-merge!` calls `merge-refusals`, then runs the gate on S as today. `accept-merge!` calls `test-refusals` on S's
run and keeps only the writer's basis checks: the run is S's, `program-written-since?`, `declarations-held-tx`
(decide at the authority; the writer validates the pre-read). `seon.context/step-status` becomes
`(if (empty? refusals) :done :working)`; `unmet` is the refusals grouped by `:seon.error/type`, and the step section
shows the merge's own messages, `path:row:col` included.

### 1.2 Cost, as measured by the planners

| Probe | Measured | By |
|---|---|---|
| Static half on Run 24's branch, warm, 3 runs (compare 8–10, replacement 2–7, `db/with` 35–70, `proven-files` 54–84, `contract-gaps` 0.6–1.2, dangling 0.3, lint 22 one file / 52 two files) | **125–225 ms, ~175 MB**; cold 340 ms | Opus |
| Static owners (replacement, `db/with`, `proven-files`, lint, contracts, dangling) on a one-function probe branch | **74/37/36 ms, 100/39/39 MB**; on `seon.edit/single-form?` unchanged: 144/142/111 ms, 125/101/101 MB | Astra |
| Replay of Run 24's branch into held main: `:not-equivalent`, 21 identities | 102/67 ms, 162/132 MB | Astra |
| Today's `step-verdict` on the same branch | 9–15 ms | Opus |
| `prepare-merge!` (gate included) | 1.7–4.06 s | dig §4.1 |

The static half is sub-second but ~15× today's verdict, so it never runs per model call.

### 1.3 How often, and where the verdict lives

- **Once per eval request that could change the answer**: the first pass (`seon.turn/prove-turn`, which today runs only
  the proofs) and every pass where `seon.turn/step-due?` holds (its recording wrote program rows, or a proof is stale),
  inside `seon.turn/test-step`, after the proofs ran. Run 24's 39-call attempt wrote program in about 6 requests:
  ~1.2 s total against 180 wasted calls in Run 21.
- **Once at `seon.turn/task-end`** when main's head commit differs from the one the last verdict was judged against.
- **The verdict is a fact on the agent's row on its branch**, replacing `:seon.agent/broken` and `:seon.agent/new-red`
  (which `seon.turn/record-broken!` writes today for the same purpose): `:seon.agent/refusals` (the vector) and
  `:seon.agent/judged-against` (main's commit id). `step-verdict` and the context block **read the row**; a render does
  no file proof, lint or test (both plans). The basis is explicit data: "done" means "the merge would accept at main
  commit X", which `accept-merge!`'s digest compare-and-swap re-validates (Astra). No `seon.cache` kind: the row is the
  one place, and it is already there (D3).

### 1.4 The refusal ownership rule (no list)

A refusal is **returned** by `merge-refusals`, shown in the step section, and the agent's to fix when it is about the
**content of the candidate**: a contract gap, a lint error, a namespace the file does not require
(`:not-analyzable`), a dangling task reference, a red, an uncovered replacement, a conflict. It is **thrown** as Seon's
own error, recorded once with its task (CLAUDE.md "nothing crashes"), and shown beside a `:working` step when two of
Seon's own derivations disagree: `proven-file`'s `:not-equivalent` (the reindex of the file Seon itself produced does
not declare the program Seon itself accepted). That refusal also **names its cause**: the reindexed `ns` form beside
the expected one and the identities the reindex lost, not all 21 declarations by digest (Run 24 O3). Either way the
agent sees it in its next context and the step is not done; the attempt never ends `:error` for it (O2's two paths
become one). The rule is "who produced the bad value", so no set grows.

### 1.5 Main moves between verdict and merge (D6)

The judge makes an agent-fixable refusal at the merge impossible. The one class left is **main moved**: another merge
landed between the `:done` end at commit X and root's `db<->flow` merge. That is not the agent's mistake and not a new
attempt. `db<->flow` writes **no `:error` end**: the refusal is delivered as a message to the agent (the delivery path
`db<->flow` already owns), the message wakes it (`seon.turn/a-wake-reopens-a-finished-task` is the installed
behavior), its next pass judges against the new head and ends `:done` again. Writer basis: `accept-merge!`'s
`declarations-held-tx` on the touched digests, and Datahike's `:datahike/expected-basis-t` on the final merge
transaction when a proof read beyond the touched identities (verified: `datahike.writing` 1044–1051, `versioning` 746).
No relaunch, no dial spent, no retry loop: a wake with new information.

### 1.6 Deleted

- `seon.context/unmet`'s six clauses, `seon.context/reds`, the private `seon.context/designed-red?` (one predicate stays:
  `seon.test.runner/designed-red?`), the private `seon.context/contract-gaps`, the fork-point proxy in `step-status` and
  `step-observation`, `base-results`' role in the verdict.
- `seon.turn/record-broken!`'s independent judgment (it becomes the call that records the verdict).
- `seon.cluster.agent/fixable-refusals`, `refused-merge-relaunch`, `failed-pass!`'s `fixable?` arm.
- **`:satisfied` as a live path**: the `step-status`, `seon.turn/task-end` and `close!` arms, the second `leaf-done` rule
  in `seon.task/ready-rules`, be25d0dfb's branch rule, `prove-turn`'s "satisfied opens no model turn" wording. An empty
  program delta is an **accepted no-op merge** (git's "already up to date": outcome `:merged`, merge commit = head)
  **only when `test-refusals` is empty on results whose reach equals main's** (Astra's condition on Opus's mechanism;
  an empty diff alone infers nothing). The enum value `:seon.task/end :satisfied` stays as historical data until the
  next reset clears it, then its member goes (a schema member with live datoms is not dropped mid-day).
- Kept: `relaunch-reason` and the relaunch dial for `:capped`/`:stalled` ends; `write-merge-base!`; branch cleanup.

### 1.7 The namespace form (Run 24), fixed at its producer

Verified cause at HEAD. `seon.sci.eval/namespace-context-row` stores `:seon.ns/source` built by `declared-source`,
which **appends** a libspec for every namespace that SCI's live bindings (`seon.turn.batch/namespace-entity` →
`seon.sci.eval/binding-rows`) hold beyond the stored row, by rewrite-clj on the stored text. A `(require 'seon.edit)`
inside `seon.edit` is a live binding, so it is appended as `[seon.edit]`; a bare `(require 'seon.sci.reader)` then
`(require '[seon.sci.reader :as reader])` append two specs for one target. The analysis form already does this right:
`seon.fn/runtime-namespace-form` removes the namespace itself and `seon.fn/runtime-require-specs` emits **one spec per
target** (aliases and refers folded in). The fix is one derivation for both: the stored `ns` form's `:require` clause
is derived from the row's requires, aliases and refers by `runtime-require-specs` (self excluded), replacing the
clause in place (the rest of the text exact, as `declared-source` already does for the clause's siblings); the
append-only edit goes. The agent **sees** it: `my.program/source 'seon.edit` (a namespace symbol) answers the `ns` form
as it will be written beside its declarations, and corrects it by evaluating `(ns seon.edit (:require …))`, which the
installed path already carries to the entity and the file (`seon.cluster.write-back-test/a-require-added-at-the-repl-merges-and-writes-back-its-ns-form`).
The help's Implement group names both forms (help teaches installed forms only).

## 2. Stage templates as task data (question 2)

**Where.** `resources/seon/tasks/seon.step.edn`: ordinary task entries `seon.step/prove` and `seon.step/implement`,
each declaring `:seon.task/step` and `:seon.task/instructions` (today's per-kind help text, moved out of code), ingested
`:held`, never launched (no children, no execution). Their proofs are the deftests tagged with them (§3).

**Built now: Prove and Implement.** Count: 832 filed tasks, 6 carry a step (all `:prove`), 29 turn-area defects carry a
red proof and name their owner already; 0 need a new symbol or schema. Design's template, `seon.step/design`, is filed
as a task with its handoff proof (§3) red and built for the first subject that needs interface work (a new symbol or
schema key); root's hand-written subject is the design output until then (owner: the hand-written tasks were really the
design part). Mine stays a query, Land is root's `db<->flow` merge after each step, Retrospective a root task later.

**How `seon.task/effective` composes.** Today it is `clojure.core/merge` of `[:seon.task/intent :seon.task/instructions]`
from the root subject down `:seon.task/children` to the step, the step last. The change is one cons: the step's
template (`seon.step/<kind>` from its `:seon.task/step`, default `:implement`) is the **first** map of the lineage.
That is the whole merge semantics:

| Key | Semantics | Why nothing new is needed |
|---|---|---|
| `:seon.task/instructions` | template's default; any task down the lineage that declares one overwrites; `my.task/refine!` writes the held step's | the existing merge |
| `:seon.task/intent` | the subject's goal; `refine!` **revises it on the subject** (`seon.task/goal-owner`), so every later step reads the refined goal; Datahike's history is the ordered list of refinements | the existing merge and D3 of hybrid-chains; the template instructs "extend, never replace" |
| `:seon.task/product` | union of the step's declared set and the tests tagged to tasks that need it | `seon.task/obligations` today |
| `:seon.task/references` | derived from the effective intent and instructions (`seon.ref/names`), never authored | `seon.task/with-references` today |
| identity, `:needs`, `:children`, `:execution`, attempts | the instance's, never the template's | `effective` selects only content keys |

No `:seon.task/compose` property (Opus), no `:seon.task/template` ref, `:seon.task/refinements` or `:seon.task/handoff`
(Astra): count 0 for each; see D8–D10.

**A subject, rewritten.** Explicit children (round-trip through `proven-task-file`, which compares declarations), no
`:chain` shorthand; the 29 conversions are one script. `seon.turn/a-send-inside-a-do-form-leaves-a-message`:

```clojure
#:seon.task{:name "a-send-inside-a-do-form-leaves-a-message"
            :intent "`seon.cluster.turn-test/a-turn-delivers-what-a-form-asks-to-send-and-still-finishes` finds `message-tx` nil on HEAD … fix the producer that drops it."
            :children #{[:seon.task/sym seon.turn/prove-a-send-inside-a-do-form]
                        [:seon.task/sym seon.turn/implement-a-send-inside-a-do-form]}}
#:seon.task{:name "prove-a-send-inside-a-do-form" :step :prove}
#:seon.task{:name "implement-a-send-inside-a-do-form"
            :needs #{[:seon.task/sym seon.turn/prove-a-send-inside-a-do-form]}}
;; effective Prove: intent above; instructions = seon.step/prove's; proofs = seon.step/prove's handoff test;
;;   product = the deftests it tags to the Implement step (the existing red test retagged, or a new row)
;; effective Implement: the intent as Prove refined it; instructions = seon.step/implement's;
;;   proofs = the tagged deftest (red today) + seon.step/implement's handoff test
```

Prove here adds the reported-input row to the existing table, or finds nothing to add and tags the existing red test
to Implement: the §1.6 no-op path, exercised ("use existing reds unchanged" is a legitimate Prove outcome; both plans).

## 3. Handoff proofs (question 3)

One deftest per template, tagged `:seon/tasks #{'seon.step/prove}` etc., reading **task data for the step under test**
passed as an explicit run input, never by asking which task the test's agent holds (TASK-CHAIN §3b, both plans).
Plumbing, required work: `seon.turn/step-tests` names the judged step in the test request (`:seon.test/task`), the runner
binds it, and the contracted assertion helper (`seon.step/prove-handoff [database task]`) receives it the way every
omitted binding-schema input is filled (CLAUDE.md "fill defaults by declared schema name"; task is a listed binding).
The reach digest of a template test includes the effective task's content digest, so a refinement re-runs it and two
instances of one template never reuse each other's result (Astra's row).

| Template | Its handoff deftest asserts, on data |
|---|---|
| `seon.step/prove` | product nonempty; each product test exists on the branch, is tagged to the successor and is `seon.test.runner/designed-red?` at reach; the successor's `seon.task/obligations` proofs nonempty (F68); every reference in the successor's effective intent and instructions resolves (`seon.ref/references`, no problem) |
| `seon.step/implement` | every reference in the effective task resolves on the branch (what the task file will carry); nothing else: green proofs, reach and static acceptance are the judge's, never copied into a deftest (Astra) |
| `seon.step/design` (filed, built later) | each symbol the intent names as created resolves with no contract gap; each schema key resolves and its sample validates (`malli.core/validator`); the successor's instructions present |

`seon.task/obligations` returns the step's tagged proofs plus its template's. Done and merge read them like any proof:
they are in `proofs`, so `test-refusals` refuses a red handoff. Readiness (`leaf-done`, `:merged`) needs no new rule,
and no proof calls `seon.task/ready` after a fabricated merge (circular; Astra).

## 4. Phases

Each converts every caller, leaves HEAD booting, and has a red proof. Files are claimed per phase; **P1, P3, P5 are
file-disjoint and run in parallel first; P2 and P6 follow (P2 needs P1's functions, P6 needs P5's `obligations`); P4
last (needs P1, P2, P5 and agent.clj)**. P3's `proven-file` change is a patch to P1's owner (same file).

| Phase | Change | Files | Red proof |
|---|---|---|---|
| **P1** source owns one verdict | extract `merge-refusals`, `test-refusals`, `merge-verdict`; `prepare-merge!`/`accept-merge!` call them; behavior unchanged | `src/seon/cluster/source.clj`, `test/seon/cluster/merge_test.clj` | `seon.cluster.merge-test/the-judge-answers-what-the-merge-refuses`: table (contract gap, lint, unrequired ns, `:not-equivalent`, dangling, conflict, missing proof, uncovered, designed error-red, newly broken reach). Red: the function does not exist |
| **P2** the step is judged by the judge | `step-status` reads `:seon.agent/refusals` / `:seon.agent/judged-against`; `test-step`, `prove-turn` and `task-end` (head moved) record the verdict; delete §1.6's context items; step section shows refusals; `context_test` cases 482/503/623 convert | `src/seon/context.clj`, `src/seon/turn.clj`, `resources/seon/schemas/seon.agent.edn`, `test/seon/context_test.clj`, `test/seon/test_failure_facts_test.clj` | `seon.context-test/a-step-is-done-only-when-its-merge-would-accept`: rows from Runs 22–24 (scratch helper, unrequired ns, corrupted ns form). Red today: `:done` |
| **P3** the ns form has one producer | `:seon.ns/source`'s `:require` clause derived by `runtime-require-specs` (self excluded, one spec per target); `my.program/source` of a namespace symbol answers the ns form; `:not-equivalent` names the ns-form cause (patch to P1); help names `(ns …)` | `src/seon/sci/eval.clj`, `src/seon/fn.clj`, `src/my/program.clj`, help resource, `test/seon/cluster/write_back_test.clj` | `seon.cluster.write-back-test/repl-requires-never-corrupt-the-written-ns-form`: Run 24's six forms, then the written file equals one spec per target and no self-require. Red today |
| **P4** one successful end | `:satisfied` live path deleted (§1.6); no-op merge when `test-refusals` empty at main's reach; `fixable-refusals`/`refused-merge-relaunch` deleted; `failed-pass!` one rule; head-moved refusal → message wake; `db_flow_test` 379/1252/1292 convert | `src/seon/cluster/agent.clj`, `src/seon/task.clj` (`ready-rules`), `src/seon/merge.clj`, `test/seon/cluster/db_flow_test.clj` | `seon.cluster.db-flow-test/a-prove-step-whose-product-main-holds-merges-up-to-date` and `…/a-merge-refused-after-main-moved-wakes-the-same-attempt` (no new attempt, no `:error`, verdict against the new head). Red today |
| **P5** templates | `seon.step.edn` (prove, implement; design filed `:held` with its red proof); `effective` conses the template; `obligations` adds the template's tagged tests; schema descriptions; the 29 turn subjects converted by one script | `src/seon/task.clj` (`effective`, `obligations`), `resources/seon/tasks/seon.step.edn`, `resources/seon/tasks/seon.turn.edn`, `resources/seon/schemas/seon.task.edn`, `test/seon/task_test.clj` | `seon.task-test/a-step-reads-its-templates-instructions-beneath-its-subjects-goal` (instructions overwrite, intent is the subject's, references derive, two instances share no result). Red today |
| **P6** handoff proofs | `:seon.test/task` on the run request, bound for the body; `seon.step` helpers; the two handoff deftests; task digest in reach | `src/seon/test.clj`, `src/seon/test/runner.clj`, `src/seon/step.clj` (new), `test/seon/step_test.clj` (new), `src/seon/turn.clj` (`step-tests`, after P2) | `seon.step-test/prove-leaves-its-successor-red-proofs` on Run 23's F68 fixture (successor with no proofs). Red today |

Deletions happen in the commit that converts the last caller; P2 and P4 are measured end to end (`seon.profile/measure`
on the whole eval request with and without the verdict, on a quiet JVM) before commit. Plan edits ride in these
commits: plan.md §0 rows (made below), TASK-CHAIN lines 14/51/166/191 (made below).

## 5. Acceptance: Runs 25 and 26

**Run 25** (after the P1–P4 restart batch): chain 5's Implement step, fresh, on a reset default (Prove is on main,
62eae1983). Before the paid run, the P3 test replays Run 24's six forms with injected replies. Expect: the step section
shows the derived `ns` form or the thrown `:not-equivalent` beside a `:working` step; the agent corrects it with
`(ns …)`; `:done` only when the row's refusals are empty; the merge accepts with 0 hand acts; `seon.edit/single-form?`
green on HEAD. Count calls spent on the ns form (target under 3) and verdict acquisitions per eval request with their
ms/MB from the row's recording.

**Run 26** (after P5–P6): two filed turn defects from §2, each Prove → Implement under the templates, launched together
(the first concurrency run). Expect: each handoff test recorded red-then-green, each refinement on main and in the task
file, both chains `:merged`, 0 hand acts; a deterministic test of a concurrent unrelated main write runs before the
paid run (Astra), not inside it.

## 6. Decision table

| # | Disagreement | Decision | Reason |
|---|---|---|---|
| D1 | Judge shape: two named halves (Opus) vs one observation value (Astra) | Two functions, `merge-refusals` and `test-refusals`, concatenated by `merge-verdict`; both take main's head as a held value | the halves have different inputs and costs (125–225 ms vs a test run); one value would carry both |
| D2 | Does `prepare-merge!` still run the gate on S? | Yes, unchanged in this round | Astra's reuse of branch evidence on S is a measured change to the gate (3.24 s of the 4.06 s), its own phase with its own cost proof; not this round's defect |
| D3 | Where the verdict lives: `seon.cache` by content (Opus) vs acquisition after each settled request (Astra) | A fact on the agent's row (`:seon.agent/refusals`, `:seon.agent/judged-against`), replacing `:seon.agent/broken`/`new-red` | one place that already exists; the render stays read-only; the basis is explicit data; no new cache kind |
| D4 | `:satisfied`: delete (both); empty delta = merged (Opus) vs only when obligations met on main (Astra) | Delete the live path; no-op merge only when `test-refusals` is empty at main's reach; enum value kept historical until a reset | an empty diff proves nothing; a schema member with live datoms is not dropped mid-day |
| D5 | Refusal ownership: thrown vs returned by a rule (Opus) vs same observation for all (Astra) | Rule by producer: candidate content returned, Seon's own disagreement thrown and recorded once; both reach the step section | no list; CLAUDE.md "nothing crashes" and "errors are values" both hold |
| D6 | Main moved between verdict and merge: relaunch while the dial lasts (Opus) vs no new attempt (Astra) | Message wake of the same attempt; writer basis via `declarations-held-tx` and `:datahike/expected-basis-t` | a relaunch with a counter is a retry; a wake with new information is the ordinary derivation |
| D7 | Templates now: Prove+Implement (Opus) vs Design+Prove+Implement (Astra) | Prove and Implement built; Design filed with its red proof, built on the first subject needing a symbol or schema | count: 0 of 29 filed defects needs interface work |
| D8 | Template lookup: `seon.step/<kind>` by `:seon.task/step` (Opus) vs `:seon.task/template` ref (Astra) | Derived from `:seon.task/step` | no new attribute on every step; variations are already instructions on the step |
| D9 | Intent: per-key `:seon.task/compose` with accumulation along `:needs` (Opus) vs `:seon.task/refinements` ordered (Astra) | Neither: `refine!` revises the subject's intent; history is Datahike's; no merge along `:needs` | hybrid-chains D3 stands; both proposals add a property or an attribute for what `merge` and history already do |
| D10 | `:seon.task/handoff` value and decisions (Astra) | Not now | count 0; handoff proofs compare data that exists (product, proofs, references), never prose |
| D11 | Chain expansion `:seon.task/chain` at ingestion (Opus) vs explicit children (Astra) | Explicit children, one conversion script | `proven-task-file` compares declarations; derived children would refuse the task file's write-back |
| D12 | Implement handoff asserts replaced symbols gone (Opus) vs nothing copied from the judge (Astra) | References resolve only | "symbols the intent names as replaced" is text parsing; `uncovered` is the judge's |
| D13 | `:not-equivalent` cause named (Opus) | Yes, in P3 as a patch to P1's file | Run 24 O3: 21 offending identities hid one ns form |

## 7. Narrow check: claims that must reproduce on default before tasks are filed

1. On `:attempt-8667f9088300`, `seon.context/step-verdict` answers `:done` while `merge-refusals`' components (the §1.1
   list run by hand) refuse `:not-equivalent`, with the reply quoted and timed (both planners measured it; reproduce once
   more at the current basis).
2. The static half's cost on that branch, warm, three runs, through `seon.profile/measure`: within 125–225 ms / ≤175 MB.
3. `seon.turn/step-due?` fires on exactly the eval requests that wrote program rows or left a proof stale: count on Run
   24's recorded requests (expected about 6 of 39).
4. Run 24's six `require` forms, replayed on a disposable branch, produce a `:seon.ns/source` with `[seon.edit]` and two
   `[seon.sci.reader]` specs, and the `:seon.ns/requires` fact omits `seon.edit` (the cycle refusal at
   `seon.turn.batch` removes it after the text was edited): confirm the order.
5. Evaluating `(ns seon.edit (:require …))` on that branch replaces the stored `ns` form and the write-back proof then
   passes (the installed behavior `a-require-added-at-the-repl-merges-and-writes-back-its-ns-form` claims).
6. `my.program/source 'seon.edit` today answers declarations only, not `:seon.ns/source`.
7. A message delivered to an agent whose admission is closed wakes it and reopens the task (`a-wake-reopens-a-finished-task`
   is tagged to a red task: confirm whether the behavior is installed or itself a red proof; D6 depends on it).
8. `seon.task/ready-rules`' second `leaf-done` clause is the only live reader of `:satisfied` besides `close!`,
   `task-end` and `step-status` (rg at HEAD; the dig lists them).
9. A test run request can carry a named input bound for the body by schema name (the runner's binding seam), or P6 must
   add it: name the function.
10. `proven-task-file` refuses a task file whose stored tasks hold children the file does not declare (D11's reason).
11. A Datahike `merge!` with `:datahike/expected-basis-t` refuses when main's basis moved (fork at gitlink `c9e57c6f`).
12. 29 filed turn-area defects carry a red tagged proof and name an existing owner symbol (D7's count).

Every deletion in §1.6 is in the commit that converts its last caller; HEAD boots after each phase; each phase's red
proof resolves green on its commit.
