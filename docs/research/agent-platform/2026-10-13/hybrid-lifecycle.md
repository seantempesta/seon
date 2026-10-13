---
title: "Hybrid: the step lifecycle — one judge, stage templates cloned as task data, exit contracts per stage"
type: research
status: hybrid of plan-lifecycle-opus.md (4c641301c) and plan-lifecycle-astra.md (16326bf9e), revised in place after the narrow check (narrow-lifecycle.md, e0e18c0d4) and the owner's rulings of 2026-10-13 (full chain, cloned templates, exit contracts); the new narrow check (§8) runs before the phases are filed
model: Claude Fable 5.1 (wrote neither plan)
basis: "HEAD e0e18c0d4; the dig, audit-night.md, TASK-CHAIN §3/§3b, run-24.md, narrow-lifecycle.md; source read at HEAD: seon.context, seon.cluster.source, seon.cluster.agent, seon.task, seon.turn, seon.turn.batch, seon.sci.eval, seon.fn, my.task; one runtime_status read on default (basis t 536871350, :faulted, open-fault 1); no SCI as root, no write, no restart"
---

# Hybrid: the step lifecycle

**The owner's rule (2026-10-13): "fully spec'ed so it can't partially fail anymore."** Three mechanisms, each the
owner of one way a chain failed this week:

1. **One judge.** A step is `:done` exactly when `seon.cluster.source/merge-verdict` returns no refusal against main's
   head: the function the merge itself calls. The verdict said `:done` while the merge refused (Run 24; reproduced in
   the narrow check, Q1: `step-verdict` 16 ms `:done`, static half `:not-equivalent`).
2. **Stage templates are ordinary task entities, cloned into each chain.** `resources/seon/tasks/seon.step.edn` holds
   one task entry per stage; instantiating a chain clones each into a step of the subject, with a `:seon.task/template`
   ref for provenance; root customizes the clone. The agent reads self-contained data; the Retrospective edits the
   template for the next chain.
3. **Exit contracts.** Each stage's template names the registered Malli schema the subject task must satisfy when the
   stage ends (= the next stage's entry contract). The writer that records the end validates the task against it, so
   a step cannot end with the task half-ready. Done = exit contract valid ∧ the stage's proofs green ∧ the judge accepts.

Each stage's context is better than the last because the previous stage wrote its findings into the task as typed
data, and the contract guarantees they are there.

## 0. The rules (owner, 2026-10-13: "one simple system that just works")

Everything is an agent (root or SCI) with a task; tasks chain by linking database refs; agents start and stop by
modifying data; every state is a database value. The whole lifecycle is the simplest set of rules this round could
find (ten; the number is a finding, not a bound), each a datum → one reaction, each implemented by one named function
that nothing else duplicates. A behavior that needs a second function for the same datum is the thing to look at
first, not a violation to enforce.

| # | Datum (on main unless said) | Reaction | The one function |
|---|---|---|---|
| R1 | A subject holds an intent and root names a template list | its steps exist: clones of the templates, each needing the one before, `:seon.task/template` set | `seon.task/chain!` |
| R2 | A leaf is `:running` (its own or inherited `:seon.task/execution`), not done, not blocked by an open need, with no attempt open or relaunched | it is ready | `seon.task/ready` |
| R3 | A ready leaf has no live agent | an attempt exists: an agent on a branch forked from main's head, the merge base recorded | `seon.cluster.agent/next-actions` (its `:attempt` action) |
| R4 | An agent's eval request wrote program rows or left a proof stale (also: its first pass) | its proofs and reaching tests run, and `merge-verdict` is recorded on its row with the head it was judged against | `seon.turn/test-step` |
| R5 | The row's refusals are empty, the exit contract holds and the proofs are green | the step is `:done` | `seon.context/step-status` |
| R6 | An agent has no turn open and a `:done`, `:sent-back`, `:given-back`, `:capped` or `:stalled` observation | the end and its commit are written **once** on the attempt; a `:done` whose exit contract fails is refused with its explain | `seon.turn/end-task-call` |
| R7 | An attempt on a branch holds an end | the end is squash-merged to main; a `:done` end is merged by the judge (no-op when nothing is new); a `:capped`/`:stalled`/refused-after-done end with work relaunches while the dial lasts; else root's problem task; the merged branch is deleted | `seon.cluster.agent/db<->flow` (its `next-actions`) |
| R8 | An assignment is `:merged` on main | its declarations are written back, reindexed and committed; the task entries with them | `seon.merge/publish!` |
| R9 | A step is `:merged` | its successor (R2) is ready: nothing to do; the subject is done when no leaf is open | `seon.task/ready` (the `leaf-done` rule) |
| R10 | A friction is observed after a chain | the Retrospective edits the template entry; the next `chain!` clones the edit | `seon.task/chain!` reads the entry as it is |

R1–R10 are what exists today with three substitutions: R4/R5 replace `unmet`'s copied clauses with the judge, R6 adds
the exit validation at the writer that already records the end, R1 replaces hand-written steps with clones. No
scheduler, no state tag, no second rule for the same datum.

**Expected cost, as information (owner: not a limit; the right numbers are unknown).** The lifecycle lives in
`seon.context` 778, `seon.cluster.agent` 2,458, `seon.cluster.source` 2,079, `seon.task` 1,054, `seon.turn` 5,876,
`seon.merge` 439 lines (12,684 at HEAD). Per phase (§5), estimated before the work and replaced by the measured numbers
in each commit message:

| Phase | Lines deleted (est.) | Lines added (est.) | Runtime the phase touches (est.) |
|---|---|---|---|
| A1–A2 | ~60 (`accept-merge!`'s static checks, the duplicated `red` logic in `prepare-merge!`) | ~70 (two functions, one concat) | `prepare-merge!` unchanged, 1.7–4 s with the gate |
| A3–A4 | ~140 (`unmet`, `reds`, `designed-red?`, `contract-gaps`, `base-results`, fork-point proxy, `record-broken!`'s judgment) | ~50 (record the row, read the row) | +152–213 ms per program-writing request (7 of 39 in Run 24); context render −16 ms (reads the row) |
| A5 | ~60 (nine `:satisfied` readers, six members, the branch rule) | ~15 (no-op merge arm) | a no-op merge: the test half on recorded results, ~135 ms (`accept-merge!` refused path) |
| A6 | ~20 (`fixable-refusals`, the class match) | 0 | a relaunch that ends `:done` with 0 model calls: one `prove-turn`, proofs at reach |
| B1–B2 | ~40 (`declared-source`'s append path) | ~35 | ns-row write per request unchanged (~10 ms); `source` of a namespace one pull |
| C1 | 0 | ~40 (schema EDN) | publication of one schema file on a branch, measured at C1 |
| C2 | 0 | ~40 (`chain!`: `create!` in a loop; much more than that is worth a look) | five `create!` transactions, ~100 ms each today |
| C3 | 0 | ~15 | one Malli validation inside the end transaction, expected under 5 ms (§8 Q3) |
| C4–C5 | 0 | ~30 code, ~120 test | one handoff deftest per step, run with the proofs inside the step's dial |
| C6 | ~20 (`refine!`'s hand-listed keys) | ~10 | unchanged |
| Sum | ~340 | ~305 code, ~160 test and data | one verdict per program-writing request; the rest sub-100 ms |

Where the code is already large: `seon.turn` (5,876) holds R4, R6 and the turn loop, and R4 (`test-step`, `run-proofs`,
`run-reaching`, `record-broken!`) is over 100 lines before this round; `seon.cluster.agent`'s R7 (`next-actions`,
`apply-lifecycle!`, `close!`, `failed-pass!`) is ~250 lines. A3–A6 are expected to shrink both; a phase that finds
itself growing one of them reports the growth with its reason rather than hiding it, and Astra's simplicity review
reads these numbers against the commits.

## 1. One judge

### 1.1 Functions (corrected per narrow Q1)

- **`seon.cluster.source/merge-refusals`** `[head branch root proofs] → [:vector :seon.error/value]`, the **static half**,
  everything `prepare-merge!` runs before `seon.cluster/candidate-gate!`: `compare-branch` (conflict), `replacement`,
  `seon.db/with`, `proven-files`, `lint-errors`; plus `contract-gaps` → `uncontracted` and `seon.task/candidate-dangling`,
  which `accept-merge!` runs after the gate today though neither needs a test. `head` is main's head as a **held
  value**, never a connection. **An empty delta is not a refusal here** (narrow P1): it is the no-op case §1.5 decides.
- **`seon.cluster.source/test-refusals`** `[tested results proofs product head-commit] → refusals`, the **test half**:
  `accept-merge!`'s membership (`unproven`), `seon.test.runner/designed-red?` on the product's recorded results,
  `red-test-verdicts` with `seon.test/fork-point-red` at main's head commit, `uncovered`. It reads a vector of recorded
  results: S's run for the merge; the branch's results at reach (`seon.test.runner/result-at-reach`) for the step.
- **`seon.cluster.source/merge-verdict`** = `(into (merge-refusals …) (test-refusals …))`. Empty means accepted.

`prepare-merge!` calls `merge-refusals` then runs the gate on S (unchanged this round, D2). `accept-merge!` calls
`test-refusals` on S's run and keeps the writer's basis checks (run is S's, `program-written-since?`,
`declarations-held-tx`).

### 1.2 Cost and frequency (measured)

Static half warm on Run 24's branch: **152–213 ms, 137–149 MB** (narrow Q2; Opus 125–225 ms; Astra 36–144 ms on small
branches), cold 367 ms; lint adds 22–52 ms on a passing candidate. Today's verdict is 16 ms; `prepare-merge!` with the
gate is 1.7–4.06 s. The judge runs **once per eval request whose recording wrote program rows or left a proof stale**
(`seon.turn/step-due?`, inside `seon.turn/test-step`), on the first pass (`seon.turn/prove-turn`), and at
`seon.turn/task-end` when main's head commit moved since. Run 24: 7 of 39 requests (narrow Q3), ~1.4 s total.

### 1.3 Where the verdict lives (D3)

A fact on the agent's row on its branch, replacing `:seon.agent/broken` and `:seon.agent/new-red` (which
`seon.turn/record-broken!` writes for the same purpose today): **`:seon.agent/refusals`** (the vector, each an error
value) and **`:seon.agent/judged-against`** (main's commit id). `seon.context/step-verdict`, the context block and
`my.task/current` read the row; a render runs no file proof, lint or test. "Done" means "the merge would accept at main
commit X"; `accept-merge!`'s digest compare-and-swap re-validates it at the writer.

### 1.4 Refusal ownership (D5, no list)

A refusal about the **candidate's content** (contract gap, lint error, unrequired namespace `:not-analyzable`, dangling
task reference, red, uncovered replacement, conflict) is **returned** by `merge-refusals`, shown in the step section
with `path:row:col`, and the agent's to fix. A disagreement between two of **Seon's own derivations** (`proven-file`'s
`:not-equivalent`: the reindex of the file Seon produced does not declare the program Seon accepted) is **thrown** as
Seon's error, recorded once with its task, and shown beside the `:working` step; it names its cause, the reindexed `ns`
form beside the expected one and the identities the reindex lost (Run 24 O3). Both reach the agent's next context;
neither ends the attempt `:error`.

### 1.5 `:satisfied` goes; the no-op merge (D4, narrow Q8)

An empty program delta is an **accepted no-op merge** (outcome `:merged`, merge commit = head) **only when
`test-refusals` is empty on results whose reach equals main's** (Prove: product designed-red on main; Implement: proofs
green on main). Default holds 0 `:satisfied` ends, so the nine readers in six files and the six schema members go in
one slice (A5): `seon.task/ready-rules`' second `leaf-done` clause, `close!`, `next-actions`' delete-branch arm,
`task-finished?`, the step render, `task-end`, `step-status`, the enum members in `seon.task.edn`,
`seon.task.attempt.edn`, `seon.context.edn`, the parity file; `db_flow_test` 356, 379, 1252, 1292 and `context_test`
442–461 convert.

### 1.6 Main moves between verdict and merge (D6, reverted per narrow Q7)

No wake reopens a finished agent (`seon.turn/next-agent-work`: a closed admission gets no work; the task
`seon.turn/a-wake-reopens-a-finished-task` is a defect name). So a merge refused for a **declared** reason after a
`:done` end, which after the judge can only mean main moved, **relaunches under the existing dial**
(`seon.cluster.agent/refused-merge-relaunch`, `relaunch-reason`, `:seon.config.run/relaunches` default 2): the next
attempt forks the end commit, `prove-turn` records the verdict against the new head, and an accepted candidate ends
`:done` with **0 model calls** (`seon.turn/a-fresh-attempt-is-work-without-a-message`). Only **`fixable-refusals`** is
deleted: the relaunch no longer selects by class, because every agent-fixable refusal is now caught before the end.
`declarations-held-tx` on the touched digests stays the merge's basis check; Datahike's `:datahike/expected-basis-t`
(verified, narrow Q11) is a whole-head fence and is used only when a proof read beyond the touched identities.

### 1.7 The namespace form (Run 24), fixed at its producer (narrow Q4–Q6)

Cause, verified: `seon.sci.eval/namespace-context-row` stores `:seon.ns/source` built by `declared-source`, which
**appends** a libspec for every namespace in SCI's live bindings beyond the stored row, by rewrite-clj on the stored
text. A `(require 'seon.edit)` inside `seon.edit` appends `[seon.edit]`; a re-require across requests appends another
`[seon.sci.reader]`; and evaluating a clean `(ns …)` over the corrupted row **appends again and repairs nothing**
(Q5 refuted the old advice). The analysis form already derives correctly: `seon.fn/runtime-namespace-form` excludes the
namespace itself and `seon.fn/runtime-require-specs` (made public) emits one spec per target with aliases and refers
folded in. **Fix:** the stored `ns` form's `:require` clause is a function of the fact (requires, aliases, refers; self
excluded), rebuilt in place by that one derivation; the rest of the text stays exact. Then `(ns …)` is authoritative
because it sets the fact. `my.program/source 'seon.edit` answers the `ns` form as it will be written (today:
declarations only, Q6). The help teaches the `(ns …)` repair only after B1's proof is green (narrow P3).

## 2. The chain: stages as cloned templates

### 2.1 The stages (owner, 2026-10-13; replaces D7)

| # | Stage | Who | Writes into the subject | Exit contract (§3) |
|---|---|---|---|---|
| 0 | **Intake** | root (or the owner) | the subject: `:seon.task/intent` from the natural-language problem; the chain cloned (§2.2) | the subject validates as `:seon.task/task` with an intent; `seon.task/chain!` refuses otherwise |
| 1 | **Survey** | agent | `:seon.task/references` (relevant schemas and keywords, functions, tests, docs, ranked by recorded use: `seon.profile/calls`, `seon.test/reach`, `seon.fn/references`); for a bug the reproducing form as the first `:seon.task/examples` entry with its observed `:actual` | `:seon.task/surveyed` |
| 2 | **Define** | agent | the PRD in the task: `:seon.task/problem`, `:seon.task/goals`, `:seon.task/non-goals`, `:seon.task/examples` (acceptance examples as data: form, expected, optional actual); **the expected cost** (`:seon.task/expected-cost`, placeholder §3a: what the work is proportional to and the expected ms/MB for the acceptance examples' inputs); implementation detail is added later by other stages, never here | `:seon.task/defined` |
| 3 | **Design** | agent | schemas registered at their producers with consumers declared; contracted stubs whose body is `(fail! …)` with `:seon.error/unimplemented`; `:seon.task/declares` (the identities created or changed) | `:seon.task/designed` |
| 4 | **Prove** | agent | generative tests over the declared schemas (`malli.generator`, test-support fixtures, the testing skill cited in its instructions); `:seon.task/product` (the tests tagged to Implement), red by design; where Define states an expected cost, a `seon.profile/measure` assertion on it among the product | `:seon.task/proven` |
| 5 | **Implement** | agent | the simplest body turning the product green; callers converted; what it replaces removed; the cost measured before and after on the acceptance inputs and checked against the expected cost (§3a) | `:seon.task/proven` still valid ∧ the judge accepts |
| 6 | **Land / Retrospective** | root's `db<->flow`; a root task | the merge, write-back and git commit after **every** step (Land is not a separate agent); the Retrospective edits the **template entries** a friction traced to, and files follow-ups with red proofs | existing merge and write-back tests |

Five templates are built now: `seon.step/survey`, `seon.step/define`, `seon.step/design`, `seon.step/prove`,
`seon.step/implement`. `:seon.task/step` gains `:survey :define :design`. Intake is the subject itself; Land is
`db<->flow`.

### 2.2 Clone at instantiation, not live merge (D8, D9)

Templates are ordinary task entries in `resources/seon/tasks/seon.step.edn`, ingested like any task (`:held`, never
launched, no children). Each declares `:seon.task/step`, `:seon.task/instructions`, `:seon.task/exit` (its exit schema
key) and tags its handoff deftest (§4).

**`seon.task/chain!`** `[connection subject-handle template-syms]` (root's, through `seon.task/create!`, the owner that
mints identities): for each template in order, creates the step `<stage>-<subject-name>` in the subject's namespace as
a **copy** of the template's `:seon.task/step`, `:seon.task/instructions` and `:seon.task/exit`, with
`:seon.task/template` (a ref to the template, provenance) and `:seon.task/needs` the previous step, and adds it to the
subject's `:seon.task/children`. Root then customizes the clone exactly as any task: `my.task/refine!` on its
instructions, a deftest tagged to it as an added gating proof, an untagged test as a removed one. Decision: **clone**,
not a live merge of the template into `seon.task/effective`:

- the step is self-contained data; the agent reads exactly what root read when it customized it;
- a template edit (the Retrospective's) reaches the **next** chain only, never a running one;
- `proven-task-file` round-trips the clones as ordinary entries of the subject's task file (narrow Q10), and the
  template ref is a plain peer ref.

**What `seon.task/effective` still merges**: the subject's **accumulating** content down `:seon.task/children`:
`:seon.task/intent` (revised in place by `refine!` on the goal owner; Datahike's history is the ordered refinements),
`:seon.task/references`, `:seon.task/examples`, `:seon.task/problem`, `:seon.task/goals`, `:seon.task/non-goals`,
`:seon.task/declares`, `:seon.task/product`. These live on the subject and every later step reads them. **What is
cloned**: `:seon.task/step`, `:seon.task/instructions`, `:seon.task/exit`. Nothing merges along `:needs`.

### 2.3 A subject, instantiated

`seon.turn/a-send-inside-a-do-form-leaves-a-message`, as root files it (Intake) and as `chain!` leaves it; the clones are
written into `resources/seon/tasks/seon.turn.edn` by write-back as ordinary entries:

```clojure
#:seon.task{:name "a-send-inside-a-do-form-leaves-a-message"
            :intent "`seon.cluster.turn-test/a-turn-delivers-what-a-form-asks-to-send-and-still-finishes` finds `message-tx` nil on HEAD … fix the producer that drops it."
            :children #{[:seon.task/sym seon.turn/survey-a-send-inside-a-do-form]
                        [:seon.task/sym seon.turn/define-a-send-inside-a-do-form]
                        [:seon.task/sym seon.turn/design-a-send-inside-a-do-form]
                        [:seon.task/sym seon.turn/prove-a-send-inside-a-do-form]
                        [:seon.task/sym seon.turn/implement-a-send-inside-a-do-form]}}
#:seon.task{:name "survey-a-send-inside-a-do-form" :step :survey :template [:seon.task/sym seon.step/survey]
            :exit :seon.task/surveyed
            :instructions "Find the owner of the value `message-tx` and every function on its flow … rank by `seon.profile/calls` … write the reproducing form as the first example with its observed actual."}
#:seon.task{:name "define-a-send-inside-a-do-form" :step :define :template [:seon.task/sym seon.step/define]
            :exit :seon.task/defined :needs #{[:seon.task/sym seon.turn/survey-a-send-inside-a-do-form]}
            :instructions "Write the problem, goals, non-goals and acceptance examples as data …"}
#:seon.task{:name "design-a-send-inside-a-do-form" :step :design :template [:seon.task/sym seon.step/design]
            :exit :seon.task/designed :needs #{[:seon.task/sym seon.turn/define-a-send-inside-a-do-form]}
            :instructions "Register any missing schema at its producer … declare contracted stubs with `(fail! :seon.error/unimplemented …)` … list them in `:seon.task/declares`."}
#:seon.task{:name "prove-a-send-inside-a-do-form" :step :prove :template [:seon.task/sym seon.step/prove]
            :exit :seon.task/proven :needs #{[:seon.task/sym seon.turn/design-a-send-inside-a-do-form]}
            :instructions "Generative tests over the declared schemas (`malli.generator/generate`, `seon.test-support` fixtures; .agents/skills/clojure-testing/SKILL.md) … tag them to the Implement step."}
#:seon.task{:name "implement-a-send-inside-a-do-form" :step :implement :template [:seon.task/sym seon.step/implement]
            :exit :seon.task/proven :needs #{[:seon.task/sym seon.turn/prove-a-send-inside-a-do-form]}
            :instructions "Read the product tests first; the simplest body … `my.task/send-back!` to Prove or Define with the counterexample when the proofs contradict the goal."}
```

The step's own instructions are the clone's copy (shown abbreviated; the template holds the full text). Its proofs
are the template's handoff deftest plus anything root tags to it; Implement's include the subject's existing red proof.

## 3. Exit contracts: the data contract per stage (D14)

Registered in `resources/seon/schemas/seon.task.edn`, each an open `:map` over the subject's effective task value
(`seon.task/effective`, extended to the keys above). Each is the **next stage's entry contract**.

| Schema | Requires (beyond `:seon.task/task`) |
|---|---|
| `:seon.task/surveyed` | `:seon.task/references` `[:set {:min 1} :seon.program/edge-symbol]`; `:seon.task/examples` `[:set {:min 1} :seon.task/example]` where `:seon.task/example` is `[:map [:seon.task.example/form :string] [:seon.task.example/expected {:optional true} :string] [:seon.task.example/actual {:optional true} :string]]` (printed values, through the one printer) |
| `:seon.task/defined` | `:seon.task/surveyed` ∧ `:seon.task/problem :string`, `:seon.task/goals [:vector {:min 1} :string]`, `:seon.task/non-goals [:vector :string]`, every example with `:seon.task.example/expected` |
| `:seon.task/designed` | `:seon.task/defined` ∧ `:seon.task/declares [:set {:min 1} :seon.program/identity]` (the stubs and schema keys Design created or changed) |
| `:seon.task/proven` | `:seon.task/designed` ∧ `:seon.task/product [:set {:min 1} :seon.test/sym]` |
| Implement | `:seon.task/proven` (unchanged) ∧ the judge empty |

Shape is the schema's; **resolution and behavior are the proofs'** (§4): the schema says references are a nonempty
set, the Survey handoff deftest says each resolves on the branch; the schema says `:seon.task/declares` is nonempty,
the Design handoff says each stub resolves, is contracted, and throws `:seon.error/unimplemented`.

**Where it is enforced.** `seon.turn/end-task-call`, the transaction function that records a step's end, validates the
subject's effective value against the step's `:seon.task/exit` with `malli.core/validator` from the projection's
registry and **throws the explain** (`:seon.task/exit-invalid`, the Malli problems as data) for a `:done` end that does
not satisfy it, so the end is never written: validate final owning values, at the writer. The same explain appears in
`step-status` beside the judge's refusals, so the agent sees what is missing before it tries to end. The schema is
cloned onto the step, so a variation can declare a stricter or looser exit and root can customize it; no `case` on the
kind anywhere. `my.task/refine!` accepts every subject content key above (one map, validated by the task schema at the
writer, no hand list), so a stage writes its findings through the one verb.

### 3a. Placeholder: the expected cost as task data

The owner wants expected runtime as part of the chain itself. Reserved here, to be filled from root's research on prior
work in runtime estimation (not yet received): **`:seon.task/expected-cost`** on the subject, written by Define, of a
shape to be decided there (at least: the input the work is proportional to, as a reference to an acceptance example;
the expected `:seon.profile/ms` and `:seon.profile/alloc-mb` on that input; the reasoning as prose). Prove turns it into
a `seon.profile/measure` assertion in the product (TASK-CHAIN §3b already names "the cost formula where the subject
states one"), Implement's measured before/after is compared with it at the judge (a measured regression is a refusal
the agent fixes, CLAUDE.md "make cost follow changed inputs"), and the Retrospective compares estimate with measurement
to improve the Define template's instructions. Whether `:seon.task/defined` requires the key, or only when the
problem states a cost, is decided with root's material; until then it is optional and nothing reads it.

## 4. Handoff proofs

One deftest per template, tagged `:seon/tasks #{'seon.step/survey}` etc., reading the step **passed as an explicit run
input**: a new optional request key `:seon.test/task` (`seon.test.edn`), carried through custody to
`seon.test/bounded-result`, which adds `:seon.task/id` to the member world so the contracted helper
(`seon.step/survey-handoff [database task]`) has it filled by schema name (narrow Q9 names the seam). The reach digest
of a template test includes the effective task's content digest, so two instances share no result and a refinement
re-runs it.

| Template | Handoff deftest asserts, on data |
|---|---|
| `seon.step/survey` | every reference resolves on the branch (`seon.ref/references`, no problem); each example's form reads as one form; for an example with `:actual`, evaluating the form on the branch answers that printed value |
| `seon.step/define` | every example has an expected value distinct from its actual where both exist; goals cite at least one reference |
| `seon.step/design` | each identity in `:seon.task/declares` resolves; functions have no `contract-gaps`; stubs throw `:seon.error/unimplemented`; each schema key resolves and `malli.generator/generate` of it validates |
| `seon.step/prove` | product nonempty; each product test exists on the branch, is tagged to the successor and is `seon.test.runner/designed-red?` at reach; the successor's `seon.task/obligations` proofs nonempty (F68) |
| `seon.step/implement` | every reference resolves (what the task file will carry); nothing else, the rest is the judge's |

`seon.task/obligations` returns the step's tagged proofs plus its template's (through `:seon.task/template`). They are in
`proofs`, so `test-refusals` refuses a red handoff; readiness needs no new rule; no proof calls `seon.task/ready`.

## 5. Phases (each 15–30 min for one lane, one red proof, HEAD boots after each)

Lanes: **A** judge (source, context, turn, agent, task), **B** namespace form (sci/eval, fn, my.program), **C** chain data
(schemas, task, test, step, my.task). Parallel at start: **A1 ∥ B1 ∥ C1 ∥ C4**. Then **A2 ∥ B2 ∥ C2 ∥ C6**. A3→A4→A5→A6
serial (they share `context.clj`/`turn.clj`/`agent.clj`); C3 after A4 and C1; C5 after C2 and C4; C7 last.

| Phase | Change | Files | Red proof |
|---|---|---|---|
| A1 | `merge-refusals` extracted (static; empty delta not a refusal); `prepare-merge!` calls it | `src/seon/cluster/source.clj`, `test/seon/cluster/merge_test.clj` | `seon.cluster.merge-test/the-static-judge-answers-what-prepare-merge-refuses` (table: contract gap, lint, unrequired ns, `:not-equivalent`, dangling, conflict; an empty delta answers `[]`) |
| A2 | `test-refusals` extracted; `merge-verdict`; `accept-merge!` calls it, keeps basis checks | same files | `…/the-test-judge-answers-what-accept-merge-refuses` (missing proof, uncovered, designed error-red, newly broken reach) |
| A3 | `:seon.agent/refusals`, `:seon.agent/judged-against` on the agent row; `test-step`, `prove-turn`, `task-end` record the verdict (`record-broken!` becomes this call) | `src/seon/turn.clj`, `resources/seon/schemas/seon.agent.edn`, `seon.turn.loop.edn`, `test/seon/turn_test.clj` | `seon.turn-test/a-program-writing-request-records-the-merge-verdict-on-the-row` (judged-against = main's commit) |
| A4 | `step-status` reads the row (+ exit problems after C3); `unmet`, `reds`, private `designed-red?`/`contract-gaps`/`base-results`, fork-point proxy, `:satisfied` arms in context deleted; step section shows refusals | `src/seon/context.clj`, `resources/seon/schemas/seon.context.edn`, `seon.render.walk.edn`, `test/seon/context_test.clj`, `test/seon/test_failure_facts_test.clj` | `seon.context-test/a-step-is-done-only-when-its-merge-would-accept` (rows from Runs 22–24; red today: `:done`) |
| A5 | `:satisfied` deleted everywhere else (§1.5); no-op merge when `test-refusals` empty at main's reach | `src/seon/task.clj` (`ready-rules`), `src/seon/cluster/agent.clj`, `src/seon/merge.clj`, `seon.task.edn`, `seon.task.attempt.edn`, parity file, `test/seon/cluster/db_flow_test.clj` | `seon.cluster.db-flow-test/a-step-whose-product-main-holds-merges-up-to-date` (Prove and Implement rows) |
| A6 | `fixable-refusals` deleted; `refused-merge-relaunch` on any declared refusal after `:done` | `src/seon/cluster/agent.clj`, `db_flow_test.clj` | `…/a-merge-refused-after-main-moved-relaunches-and-ends-done-without-a-model-call` |
| B1 | `:seon.ns/source`'s `:require` clause derived from the fact by `runtime-require-specs` (public); `declared-source`'s append gone | `src/seon/sci/eval.clj`, `src/seon/fn.clj`, `test/seon/cluster/write_back_test.clj` | `seon.cluster.write-back-test/repl-requires-never-corrupt-the-written-ns-form`: Q4(a)'s three-request history order, then a clean `(ns …)` leaves one spec per target and no self-require, and the write-back proof passes (Q5) |
| B2 | `my.program/source 'ns` answers the ns form; help's Implement group teaches `(ns …)`; `:not-equivalent` names the ns-form cause (patch to A1's owner) | `src/my/program.clj`, help resource, `test/my/program_test.clj` | `my.program-test/source-of-a-namespace-shows-its-ns-form-as-written` |
| C1 | Schema: `:seon.task/step` + `:survey :define :design`; `:seon.task/template`, `:exit`, `:examples`, `:problem`, `:goals`, `:non-goals`, `:declares`; exit schemas §3; entry/declaration/task/effective maps extended | `resources/seon/schemas/seon.task.edn`, `test/seon/task_test.clj` | `seon.task-test/each-exit-contract-validates-its-sample-and-refuses-the-stage-before` (published on a branch of the live store, reloaded) |
| C2 | `seon.step.edn` five templates; `seon.task/chain!` clones | `resources/seon/tasks/seon.step.edn`, `src/seon/task.clj`, `test/seon/task_test.clj` | `seon.task-test/a-chain-is-cloned-from-its-templates` (children, needs, template ref, instructions and exit copied; a template edit after cloning changes no clone; the file round-trips) |
| C3 | `end-task-call` validates the exit; `step-status` shows exit problems | `src/seon/turn.clj`, `src/seon/context.clj`, `test/seon/turn_test.clj` | `seon.turn-test/a-step-cannot-end-with-the-task-half-ready` (a `:done` end with no references is refused with the explain; with them it is written) |
| C4 | `:seon.test/task` request key → member world `:seon.task/id`; task content digest in a tagged test's reach | `src/seon/test.clj`, `src/seon/test/runner.clj`, `resources/seon/schemas/seon.test.edn`, `test/seon/test_test.clj` | `seon.test-test/a-run-request-hands-its-task-to-the-test-body` |
| C5 | `seon.step` handoff helpers and the five deftests; `obligations` adds the template's tagged tests | `src/seon/step.clj` (new), `test/seon/step_test.clj` (new), `src/seon/task.clj` (`obligations`) | `seon.step-test/prove-leaves-its-successor-red-proofs` on Run 23's F68 fixture; one row per template |
| C6 | `my.task/refine!` writes any subject content key (one validated map, no hand list); `my.task/current` shows the exit explain | `src/my/task.clj`, `resources/seon/schemas/my.task.edn`, `test/my/task_test.clj` | `my.task-test/refine-writes-the-surveys-findings-as-data` |
| C7 | Conversion: `chain!` run by root on one filed turn defect; its entries written back; help per stage | `resources/seon/tasks/seon.turn.edn`, help | the acceptance chain below |

Measured before commit: A3/A4 end to end (`seon.profile/measure` on the whole eval request with and without the
verdict) and C3 (the end transaction stays under 100 ms). Deletions ride the commit that converts their last caller.

## 6. Acceptance

**Run 25** (after A and B restart batch): chain 5's Implement step fresh on a reset default (Prove is on main); B1's
proof already replays Run 24's forms with injected replies. Expect: the step section shows the derived `ns` form or
the thrown `:not-equivalent` beside a `:working` step; `:done` only when the row's refusals are empty; merged with 0
hand acts; `seon.edit/single-form?` green on HEAD; under 3 calls on the ns form.

**Run 26** (after C): **one of the 17 turn defects with proofs**, from intent alone: root writes the subject's intent
(Intake), `(seon.task/chain! … [seon.step/survey seon.step/define seon.step/design seon.step/prove seon.step/implement])`,
one `:running` fact, nothing else by hand. Expect: each step ends `:done` only with its exit valid (the recorded refusal
of an early end attempt, if any, is evidence the writer held), each handoff deftest red then green, the PRD and
references on main and in `seon.turn.edn`, every step `:merged`, the subject `done?`, 0 hand acts. Record per step:
calls, first-call tokens, verdict acquisitions with ms/MB, and whether each step's context was smaller than the last
(the owner's measure of "better and more focused"). **Run 27**: two such defects together.

## 7. Decision table

| # | Disagreement | Decision | Reason |
|---|---|---|---|
| D1 | Judge shape: two halves (Opus) vs one observation (Astra) | `merge-refusals` + `test-refusals`, concatenated by `merge-verdict`; both take main's head as a held value | different inputs and costs |
| D2 | Does `prepare-merge!` still run the gate on S? | Yes, this round | reusing branch evidence on S is a measured gate change (3.24 of 4.06 s), its own phase |
| D3 | Verdict in `seon.cache` (Opus) vs re-acquired (Astra) | A fact on the agent's row (`:seon.agent/refusals`, `:seon.agent/judged-against`) replacing `broken`/`new-red` | one existing place; read-only render; explicit basis |
| D4 | `:satisfied`: empty delta merged (Opus) vs only when obligations met on main (Astra) | Delete all nine readers and the enum members now (0 live datoms); no-op merge only when `test-refusals` empty at main's reach | an empty diff proves nothing; nothing historical to keep |
| D5 | Refusal thrown vs returned | By producer: candidate content returned; Seon's own disagreement thrown once, cause named | no list grows |
| D6 | Main moved between verdict and merge: relaunch under the dial (Opus) vs same attempt (Astra) | **Relaunch under the dial**; delete only `fixable-refusals`; relaunch ends `:done` with 0 model calls when still accepted | narrow Q7: no wake reopens a closed admission, by design and by three red tests |
| D7 | Stage set | **Intake (root), Survey, Define, Design, Prove, Implement, Land/Retrospective (root)**; five templates built now | owner's chain; each stage's findings are typed data the next stage's contract requires |
| D8 | Template reaches the step: live merge in `effective` (both plans, differently) vs **clone at instantiation** | Clone: `seon.task/chain!` copies step, instructions, exit; `:seon.task/template` ref for provenance | self-contained step data; a template edit reaches the next chain only; round-trips the task file |
| D9 | Intent: `:seon.task/compose` + merge along needs (Opus) vs `:seon.task/refinements` (Astra) | Neither: `refine!` revises the subject's content; history is Datahike's; `effective` merges down children only | hybrid-chains D3 stands; `merge` and history already do it |
| D10 | `:seon.task/handoff` value (Astra) | The handoff is the subject's typed content (§3), not one attribute; each key has a schema and its consumer (the next stage's contract and proof) | one open map, accretion by key |
| D11 | `:seon.task/chain` expansion at ingestion (Opus) vs explicit children (Astra) | Explicit children, written by `chain!`, round-tripped as entries | `proven-task-file` compares declarations (narrow Q10) |
| D12 | Implement handoff asserts replaced symbols (Opus) | References resolve only | text parsing; `uncovered` is the judge's |
| D13 | `:not-equivalent` names cause (Opus) | Yes, B2 as a patch to A1's owner | Run 24 O3 |
| D14 | Done rule | Exit contract valid ∧ proofs green ∧ judge accepts; the exit validated at `end-task-call`, the writer | partial readiness impossible by construction, not by a check beside the end |
| D15 | Exit schema per kind in code vs on the step | On the step (`:seon.task/exit`, cloned from the template) | no `case` on the kind; variations and root's customization are data |

## 8. Narrow check for what changed (before the phases are filed)

1. `seon.task/create!` admits `:seon.task/template` as a peer ref and a step whose `:seon.task/step` is `:survey`
   once the enum is extended, and `proven-task-file` round-trips a clone with `:template` and `:exit` as an entry.
2. `seon.turn/end-task-call` is the one writer of a `:done` end on the attempt (no other path writes
   `:seon.task.attempt/end :done`), so validating the exit there is sufficient.
3. A Malli validator for a registered open `:map` over the effective task value runs under 5 ms with the projection's
   registry (`malli.core/validator` compiled once at publication).
4. `seon.task/effective`'s pull of the subject's content keys (now eight) stays under 10 ms on a 50-task namespace.
5. `seon.test/bounded-result`'s member world accepts an added `:seon.task/id` and a test body reads the step's
   declaration there (narrow Q9's open risk: task declarations as program rows on the member branch).
6. A `:seon.task/examples` entry's printed `:actual` round-trips through the one printer: evaluating the form on the
   branch and printing answers the stored string (Survey's handoff).
7. `malli.generator/generate` of every schema an existing filed task's producer registers validates (Design's handoff
   assumes generators exist for registered schemas; count the ones that lack a generator).
8. The relaunch after a `:done` end whose merge was refused for a declared reason ends `:done` with 0 model calls on the
   current `prove-turn` (`a-fresh-attempt-is-work-without-a-message` green on HEAD).
9. Of the 17 turn defects with proofs, name the one for Run 26: a small pure owner, its red proof readable on a branch
   (main holds no runs, narrow Q12), no interface work.
10. `my.task/refine!` writing a set-valued key (`:seon.task/references` is derived; `:seon.task/examples` authored)
    passes `seon.task/task-delta`'s declaration compare and the task's write-back.
