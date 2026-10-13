---
title: "Independent review of the locked TASK-CHAIN spec"
type: research
status: "review; findings for root"
date: 2026-10-13
model: Claude Opus 5.5 (wrote neither the spec nor its inputs)
basis: "docs/seon/schedule/cards/TASK-CHAIN.md at 40dd25678; source at HEAD 40dd25678 read with rg and direct reads; one JVM-mode read on default (no SCI as root, no write, no restart)"
---

# Review of TASK-CHAIN (40dd25678)

Spec §1–§7 hold up well. Every existing owner they name exists by symbol: `seon.task/{goal-owner,effective,
adopt-tasks-tx,task-handle,task-sym,ready-rules,ready,done?,assign!,declaration,obligations}`,
`seon.turn/{test-step,prove-turn,next-agent-work,task-end,end-task-call}`, `seon.context/step-status`,
`seon.cluster.agent/{plan,assignment-actions,next-actions,close!,failed-pass!}`,
`seon.cluster.source/{prepare-merge!,accept-merge!}`, `seon.merge/{merge!,merge-tx,publish!}`, `my.root/merge!`,
`seon.test.runner/designed-red?`, `seon.db/stored-costs`, `seon.batch/cost-diff`, `seon.profile/measure`,
`seon.fn/runtime-require-specs`, `seon.sci.eval/namespace-context-row`. The `seon.ref` CommonMark parser
(`seon.ref/document`, headings with levels and anchors) exists. `seon.task/chain!`,
`seon.cluster.source/merge-verdict`, `resources/seon/tasks/seon.step.edn` and all eight named acceptance deftests do
not exist yet. The spec marks the first three as new; the deftests are the gap in finding 9.

Live read on default (JVM mode, 151 ms evaluation, 266 ms request, 240 MB request allocation): the step enum in use is
`{:prove 1}`; there are no `seon.step/*` tasks; the walk task `seon.turn/a-send-inside-a-do-form-leaves-a-message` is
`:held`, with no children and no product, and its proof has no stored result on main. The declarations whose analysed
`:seon.fn/keywords` contain `:seon.task/tasks` are `seon.task/{obligations,task-edges,candidate-dangling,open-tasks,
task-findings}`, `seon.error/proof`, `seon.fn/task-links`, `seon.cluster.agent/render-identity-html` and two
`seon.turn.batch-test` helpers.

## Ranked findings

### 1. High: stage guidance has a second owner, and it contradicts the spec (a live Prove agent is told the deleted rule)

**Evidence.** `seon.bootstrap/help-value` picks a hard-coded "Done" group with `held-step`:
`(if (= :prove (held-step database agent-id)) prove-done implement-done)`. `held-step` returns `:implement` for any
task that is not a Prove step. `prove-done` says: "Your product is red deftests for the step after you, tagged with ITS
name from :seon.context/after, never your own". That is the successor-tag product inference that spec §3
(`obligations`) deletes, since §4 Prove now "declare[s] product on the subject". `implement-done` ("change the function
that produces the wrong value") is what Survey, Define and Design would all read. `seon.context` also has `:prove`-only
branches: the must-read at the `step` begin (`(when (and (= :prove step) instructions) …)`) and the
`:seon.context/after` section. The spec puts `src/seon/bootstrap.clj` only under `chain-templates` and the
namespace-source slice, and it gives no deletion for these groups. bootstrap line 29 also cites `:seon.context/unmet`,
which `chain-acceptance` deletes, but bootstrap is not in that phase's file list.

**Walk impact.** On the walk task, the Survey agent's system message tells it to fix a function, and the Prove agent's
tells it to tag tests with the successor's name. Under the new `obligations` those tests would never be selected.

**Smallest change.** In `chain-obligations`, the phase that deletes successor inference, delete `implement-done`,
`prove-done` and `held-step` and keep only the stage-independent help lines. Stage guidance then lives only in the
template's `:seon.task/instructions`. Add `src/seon/bootstrap.clj` to the file lists of `chain-obligations` and
`chain-acceptance`, and add the context `:prove` must-read and `:after` branches to `chain-obligations`' deletions.

### 2. High: handoff deftests have no defined result when run without `:seon.test/task`

**Evidence.** The five defaults in `test/seon/step_test.clj` read "the explicit task input" (§4). Several runs carry no
such input: every run outside a clone's obligations (the bulk tier, `bin/lane commit --test`, and reaching selection
on any branch that touches `seon.ref`, `seon.task` or `seon.context`, which these tests read). Under CLAUDE.md's default
filling, a missing required binding "refuses by name", so those runs are red. Run against their tagged template's own
handle, they are also red: a template's intent has no Problem or What exists sections. So the five tests are
permanently red on main. They are reached by unrelated changes, and CLAUDE.md's merge law ("a test already red on main
stops it for root") then stops unrelated merges. §5 and §7 Q4 ask whether two clones share evidence. They never say
what an inputless run means.

**Smallest change.** Define the runner's default `:seon.test/task` as the test's single `:seon/tasks` tag, and make
each template's own `:seon.task/intent` a short worked example that satisfies its handoff. Clones never copy intent
(§1 copies step, instructions and proofs), so the example never leaks into a chain. Run alone, the template then
self-tests green, and a clone's input replaces it. Add this case to A1's behavior table.

### 3. High: Prove's product is judged twice, by a deftest and by the verdict

**Evidence.** `seon.step-test/prove-handoff` asserts "product nonempty, every product symbol … current designed-red
evidence" (§4). The `merge-verdict` row of §3 also keeps "Prove's nonempty designed-red product" as a verdict clause.
That clause is today's `accept-merge!` `designed` set, `(filter … seon.test.runner/designed-red?)`. These are two
owners for one decision, and the owner's goal is "deftests as the only gating proofs". prove-handoff's third assertion
("product is a subset of Implement's selected obligations") holds by construction: §3 gives Implement "effective
product" as an obligation.

**Smallest change.** Remove the designed-red judgment from `merge-verdict`. Its only remaining rule for reds is
membership: a red outside the green obligations is admitted when it is red on main or in the effective product.
prove-handoff stays the one judge of designed red. Delete the subset assertion.

### 4. High: `my.task/refine!` lets an agent rewrite its own gate

**Evidence.** §1: "`my.task/refine!` writes … instructions and copied proofs to the held step". §2 and A2 then accept
"the actual combined value" of the candidate's effective task. An agent can call refine! to remove its copied handoff
proof, shrinking its obligations, and the acceptance it proposes accepts that. Today's `my.task/refine!` routes only
intent and instructions. The proofs route is new. §1 says root, not the agent, customizes the clone.

**Smallest change.** Do not route `:seon.task/proofs` through refine!. Root customizes a clone's proofs on main with
the ordinary task writers before the `:running` fact, as §1 already says. This deletes a member from the routing; it
is not a check.

### 5. High: Design's stub-error deftests become reaching regressions at Implement

**Evidence.** §4 Design: "invoke a stub and assert its declared unimplemented error, supplying green coverage for the
Design merge". Coverage is real. `accept-merge!` refuses "A replaced function reaches no green test" unless a green or
designed-red test reaches it, and Design merges before Prove writes any designed red. When Implement replaces the stub
body, that green-on-main test goes red on the branch, which is a reaching regression the verdict refuses. Implement's
instructions ("delete replacements") do not name these tests. A live Implement agent must work out that it has to
delete another stage's test.

**Smallest change.** Design's interface deftest asserts the contract instead of the body: call the stub with an input
outside its schema and assert the instrumented input refusal, or validate its declared samples. That test stays green
after Implement and still covers the stub.

### 6. Medium: two owners decide which attempt ends are terminal

**Evidence.** `seon.task/ready` hard-codes `#{:given-back :capped :stalled :cancelled :error}` with
`(missing? $ ?a :seon.task.attempt/relaunch)`, while §2 makes `assignment-actions` the one end→action owner (relaunch
included). The commonality inventory (A6) calls the literal "a fourth copy"; §7 says A6 collapses, but the
`chain-actions` file list omits `src/seon/task.clj`, so the literal survives.

**Smallest change.** `ready` excludes any ended attempt that has no `:seon.task.attempt/relaunch`, and
`assignment-actions` alone writes relaunch. Add `src/seon/task.clj` to `chain-actions`. Before filing, add one §7 Q5
check: a `:sent-back` sender and a refused `:done` behave as today.

### 7. Medium: two assignment writers; only one admits

**Evidence.** §2 says `seon.task/assign!` "owns assignment writes for direct and reconciler entrances". Today the
reconciler's `seon.cluster.agent/write-attempt!` calls `seon.task/attempt-tx` directly. It skips
`seon.task/assignment-read`, which is where `:no-proofs` and obligations-based admission live. No phase names this
conversion. A1's census is by proof-selection queries, and it cannot find this writer because the writer selects
nothing.

**Smallest change.** Name `seon.cluster.agent/write-attempt!` → `assignment-read` and the attempt tx in
`chain-obligations`. A1's table adds "reconciler first attempt with only a template proof".

### 8. Medium: A1's census misses `seon.error/proof`; make the census the analysed keyword set

**Evidence.** `seon.error/proof` (recorded-error resolution) is an independent `:seon.task/tasks` → `:seon.test/sym`
query. It counts pass/fail/error itself, duplicating `seon.test/green?`, which §7 names as the count owner. It is not
in A1's consumer list or in `chain-obligations`' files. A1's phrase "no independent task-tag query" leaves the
implementer to decide what counts.

**Smallest change.** A1 asserts that, among declarations whose `:seon.fn/keywords` contain `:seon.task/tasks`, the
proof selectors are only `seon.task/obligations`. The live set is listed above; the writers and dangling and triage
readers are allowed by name, with their roles. Add `src/seon/error.clj` to `chain-obligations` and convert `proof` to
`obligations` plus `seon.test/green?`.

### 9. Medium: the reds do not exist, and nobody is assigned to write them

**Evidence.** `rg` finds none of A1–A5, `ends-share-one-action-owner`, `every-agent-start-has-a-task`,
`repl-requires-never-corrupt-the-written-ns-form` or `*-handoff`, and none of the phase tasks in
`resources/seon/tasks/`. §5 says "no phase is assigned until its red reproduces". CLAUDE.md says a task "is assigned
only with a proof". The spec names no slice that writes the reds.

**Smallest change.** Add a row 0: root, or a Sonnet lane, writes each phase's acceptance deftest red, tagged to its
phase task, and files the tasks in one commit. Each phase is assigned with that red already in place.

### 10. Medium: acceptance proofs that are not yet writable without decisions

- **A5 is many behaviors with no bound.** It covers five gated merges (the hybrid estimates 1.7–4 s each) against the
  default 5 s bound, plus six table variants in one deftest. Split it into the base loop plus one deftest per
  variant, each with a declared bound and the reason naming `prepare-merge!`. Two of the variants are already
  separate (`ends-share-one-action-owner`, `every-agent-start-has-a-task`).
- **Double tags.** A2 is tagged to `chain-acceptance` and `chain-actions`; A4 to `chain-declarations` and
  `chain-templates`. After the first phase merges, the second phase's copy is already green and proves nothing (the
  rule is "a task already green proves nothing"). Tag each to exactly one phase: `chain-actions` takes its two named
  variants, `chain-templates` takes A3.
- **A3 does not fix clone identity.** Name the clone's ns and name, e.g. the subject's ns with
  `(str (name template) "-" (name subject))`, and its task file, the subject's. "Repeating the same construction
  changes nothing" depends on that choice.
- **Cost deftest scope.** §3 says Prove "authors a cost deftest" against "captured baseline" without saying when one
  is required or where the baseline is captured. The walk task, a dropped message fact, has no cost claim. State: only
  when Define's Expected cost names a workload, with the baseline taken from main's `seon.db/stored-costs` at the
  candidate's merge base.

### 11. Medium: the Retrospective has no datum

**Evidence.** §2's last row is "Recorded friction → root works its ordinary retrospective task". No schema or source
produces a friction datum (`rg -i friction` finds only one help line in `seon.contracts`). `chain!` clones five
templates, and the subject is `done?` once those leaves merge, so nothing creates or readies a retrospective task.

**Smallest change.** Delete the row and describe the retrospective as root's ordinary review after `done?`, reading
stored costs. Alternatively, make it a sixth leaf needing Implement, assigned to root. Pick one; the first is smaller.

### 12. Low: handoff sections are not cumulative; five defaults can be one table test

`my.task/refine!` replaces the whole `:seon.task/intent` string, so Define can drop What exists without any check
noticing. implement-handoff only re-checks the reference resolution the earlier handoffs already proved. Replace the
five defaults with one table deftest, `seon.step-test/handoff`, with rows of step → required sections, cumulative,
plus references that resolve. Tag it to all five templates and select the row by the input's step. That is one test
in place of five, with no new mechanism.

### 13. Low: file collisions beyond "serial where files overlap"

Every phase touches `src/seon/task.clj` or `src/seon/turn.clj`, so the six phases are strictly serial; say so. The
namespace-source slice "may precede", but it shares `test/seon/cluster/write_back_test.clj` with `chain-declarations`
and `src/seon/bootstrap.clj` with `chain-templates`. It must merge before either phase claims those files.

## Walk: `seon.turn/a-send-inside-a-do-form-leaves-a-message`, Survey → Implement

- **Intake.** The subject intent is one paragraph with no Markdown sections. Root, or Survey, must restructure it
  before `survey-handoff` can be green. That is acceptable, but A3 should not assume the sections exist.
- **Survey.** Its context is `seon.context/held-task` → `seon.task/effective` (intent and instructions). Its proofs
  today are empty, because `seon.turn/task-proofs` and `seon.context/proofs` read `task-edges`, not copied proofs; A1
  addresses this. Its help is `implement-done` (finding 1). refine! writes only task facts, so its proof reruns only
  if task input is part of the evidence identity. That is planned in `chain-obligations`, and Q2 checks it. Its
  task-only merge has no gate today: `seon.merge/merge-onto` with `owed` nil transacts `merge-tx`, which writes
  `:merged`. A2 converts this.
- **Define and Design.** These are feasible. Design has no new interface here, so finding 5 does not bite on this
  task.
- **Prove.** The product is the existing red subject test
  `seon.cluster.turn-test/a-turn-delivers-what-a-form-asks-to-send-and-still-finishes`. That test is not designed red
  until a current result exists on Prove's branch (§3 order: product first). The help contradicts the spec
  (finding 1).
- **Implement.** Its obligations hold that test through the subject tag and the effective product. This is
  consistent once finding 3's subset assertion is gone.
- **Merges.** Default holds no result for the test yet, so the first Prove run is the first evidence.

## Not found

The following showed no defects: data model (§1), the single-attribute rule, effective/goal-owner routing apart from
finding 4, `test-step`/`prove-turn` ownership, publication separate from `:merged`, and the end-to-action collapse
apart from finding 6. I verified no runtime behavior of any proposed (new) symbol. Nothing in this review is a
hypothetical failure turned into a check; findings 4 and 5 remove a route or change an assertion.
