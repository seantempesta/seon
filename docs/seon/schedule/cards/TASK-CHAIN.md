---
title: "TASK-CHAIN: tasks that improve as they move through a pipeline of agents"
type: card
status: agreed design (owner, 2026-10-11, fable-replan §8 Q13–Q16 and the four rounds of runtime-follows-db); §1–§7 revised in place; §8 keeps the reviews as history
created: 2026-10-11
---

# TASK-CHAIN

What exists: tasks are entities (`seon.task/create!` upserts with `:seon.task/basis`; history is Datahike's), their
proofs are tagged deftests, an agent's context is rendered live from its task by `seon.context/current-state`, a task's
end sends root one message and closes the agent (DYNAMIC-CONTEXT stages 1–3), `seon.task/assign!` runs a task's proofs in
the agent's world before assigning, and `seon.cluster.source/land!` takes a green merge request to main, disk and git in
one call. The approach: one general mechanism (a task value, stage types as data, one context function, one stage-aware
proof selection) so pipeline variations are data, run side by side and measured, rather than code. No pipeline proc,
supervisor, poller, done-check registry, stored instructions, send-back cap or advance dial: the agent lifecycle that
exists is the transition, and a fence is refused (AGENTS.md, NO GUARDS OR FENCES).

## 1. The idea

Software work is a chain of stages, each worked by a fresh agent whose only context is the task as the previous stage
left it, rendered by one function. Each stage reads the subject task, does its work, and **edits the subject**: it adds
what it found, removes what misled it, and rewrites what it now understands better. The subject task is the handoff.
The next agent's work depends mostly on the quality of that task, above all on its references: removing a reference to
the wrong function or schema, or adding the right one, changes what the next agent reads and does. It is red-green TDD
across agents: the red is written by a different agent than the green, the interface (contracted stubs) before the
tests, the diff reviewed before it merges.

Owner (2026-10-11): build it generally, then experiment with variations step by step and keep the stages that make the
task better, as many as are worth their model calls. "We need passing tests at each level of the task pipeline, so the
agent explicitly knows what to do at each step before they can advance; that way it's the same system end to end." The
runtime that carries it is [runtime-follows-db](../../../research/agent-platform/2026-10-11/runtime-follows-db.md):
one path (an agent wakes and runs a task), a tree of tasks ordered by `needs`, intents, one reconciler.

## 2. The model

**One task per step; the subject task is the handoff.** The subject (`seon.task/create!`, at main) owns its steps as
component children (`:seon.task/children`); every step is an ordinary task with its own assignment, proofs, instructions
and end. Siblings run in parallel unless one `:seon.task/needs` another; a parent never runs and is done when its
children are. Every stage may update any field of the **subject** through `my.task/refine!`; Datahike keeps every
version and the transaction's provenance names the step and agent. The step an agent holds is never revised, so
`seon.task/assignment`'s `:revised-since-assignment` cannot fire on the agent's own edits.

| Field | On | What it holds | Edited by |
|---|---|---|---|
| `:seon.task/intent` | subject | what must become true, typed (fully qualified symbols and keywords) | every stage refines it |
| `:seon.task/must-read` | subject | the references the next agent reads first | every stage adds and removes |
| `:seon.task/evidence` | subject | reproduction forms, values, paths | Mine, Design |
| `:seon.task/children` | subject (component) | the step tasks; nested steps allowed | the creator; a stage may add steps |
| `:seon.task/needs` | step | the steps this one runs after; a join names several | the creator |
| `:seon.task/instructions` | step | **what this step's agent is to do, as data on the task**, written when the step is created (from the pipeline the creator chose); no template file, no stage-type render | the creator; the Retrospective edits future pipelines by editing what it writes |
| `:seon.task/execution` | step | the intent `:held :running :paused :stopped :cancelled` (runtime-follows-db §3); absent = running | root |
| the attempt's end | assignment row | `:done`, `:given-back`, `:capped`, `:stalled`, `:sent-back` + `:seon.task/target`, `:cancelled` | the agent's pass (`seon.turn/end-task-call`) |
| `:seon.task/decisions` | subject | cardinality-many component: step, decision (`:added :changed :removed :unchanged :send-back`), reason, keys, target | every stage, through `refine!` or `send-back!` |
| schema keys, contracted stubs, proofs | the working branch | reached by the successor because its branch forks from the predecessor attempt's recorded commit | Design, Prove, Implement |

"Nothing to do" is `:unchanged` with a reason and the observed interface or diff that supports it, never an absence.
A pipeline is the shape of steps and needs a creator writes (an EDN tree, runtime-follows-db §2); a variation is a
different tree or different instructions, all data, so variations run side by side and are compared.

**One context function.** Every agent's context is `seon.context/current-state` over the step it holds and the subject
it belongs to: the laws its instructions cite and the tool help; the step's instructions; the subject (intent, must-read
resolved to docs, sources and schemas, evidence, decisions); the step's proofs with their current red data; the agent's
own history. Byte stability buys cache hits; correctness of the task section matters most.

**A step starts as one assembly** (owner, 2026-10-11): `seon.cluster.host/assemble!` composes the world (one constructor),
an environment forked from the nearest cached fork point (the predecessor attempt's commit), the step task driving the
context, and the Flow graph the reconciler attaches; a test run, root's tasks and an ad-hoc agent are the same assembly
with another task and fork point (runtime-follows-db §1, R1).

**Vocabulary** (owner, 2026-10-11; catalogue §3). Agent side: `my.task/current` (my step, the subject, the proofs with
status and failing data, decisions, offered operations), `my.task/refine!` (edit any subject field, including a
decision), `my.task/send-back!` (reason, target step, counterexample; it writes the `:send-back` decision and ends the
attempt `:sent-back`), `my.task/create!` (steps with needs and instructions). Success has no verb: green proofs end the
step. `my.message/send` and `my.turn/wait` stay. Root side, `my.root`: `instructions`, `merge-requests`, `merge!`,
`close!`, `ended`; root launches nothing by hand (the reconciler launches ready steps). Platform: `seon.task/ready`,
`request-merge!`, `close!`; `seon.turn/end-task-call` records every end.

## 3. The stages (pipeline A)

Each is a step task with its instructions and one proof of its own (§3b), and what it typically adds, removes and
modifies on the subject.

0. **Mine** (a query, sometimes the owner): the smallest reproducible input, expected and actual data, execution mode,
   program basis and configuration; reproduced through the affected entrance; a product defect distinguished from a
   broken fixture; an existing task with the same subject and evidence is chosen over a new one. A new feature records an
   acceptance example, never a fabricated incident.
1. **Design** (survey, schemas, interface): trace producer → transforms → storage → consumers; verify the owner by a
   probe; reuse a library seam before designing; register missing schemas at their producers and declare consumers,
   with generated samples validated against real values; declare the new or changed symbols as contracted stubs (`defn`
   with its `:malli/schema`, body `(fail! …)`) so Prove's tests compile against real symbols; specify arities, error
   behavior, what the change replaces and which callers convert. Decision `:added`/`:changed`/`:removed` with keys and
   samples, or `:unchanged` with the observed interface.
2. **Prove** (needs Design): an `are` table holding the reported input plus boundary and negative cases; a seeded property
   only for a stated law; a deterministic work count for a cost claim; every existing test asserting the old behavior
   updated, or the decision that none exist. Proofs read the program through its owner, never files. Decision `:changed
   #{old tests}` or `:unchanged` with a reason.
3. **Implement** (needs Prove): read the tests first; the simplest body that turns the behavior proofs green with no newly
   broken reached test; change the producer, convert all callers and docs, remove what the change replaces; measure the
   same real workload before and after. Its first turn checks that the proofs encode the intent and may **send back**
   (`my.task/send-back!` to Prove or Design with the counterexample). Decision `:changed #{syms}` or `:send-back`.
4. **Land** (needs Implement; root): the green end records the merge request, a problem task assigned to root carrying
   the diff since the merge base, every reached test with its result, the proofs and the decisions (plan §0 item 8); root
   merges (`my.root/merge!` over `seon.cluster.source/land!`) or closes with a reason. A gate red is a send-back to
   Implement or Prove.
5. **Retrospective** (needs Land; root): the end summary (proofs, errors by signature, calls, send-backs, cost before and
   after); closure derived from green proofs on main; follow-ups filed as tasks with red proofs; the instructions a
   friction traced to are edited in the pipeline the next tree is created from, or the decision `:none`.

**Send-back** is an end like any other: the sending agent writes the decision with its target, reason and
counterexample and ends `:sent-back`; the target is no longer done, so it is ready again and the reconciler launches a
fresh agent on it with the decision in context; its successors wait again. All attempts charge the same subject budget.
There is no send-back cap; a loop (the same target twice) is a problem task for root (runtime-follows-db §5).

## 3b. Stages advance by proofs: one system end to end

A step is done when its **proofs** are green, exactly as any task. A step's proof is an ordinary deftest tagged with the
step, run against the subject's revision and the branch program supplied as explicit test inputs (never discovered by
asking which task the test's agent holds); the Implement step also carries the subject's behavior proofs. The agent's
context shows the red proof and its failing data, so it knows exactly what is left. A variation swaps the proof.

| Step | Its proof asserts (data in, data compared; a missing observation is unknown, never a pass) |
|---|---|
| Mine | expected input = observed input; expected main basis = observed; reported actual = observed actual; expected ≠ actual; a nonempty case set |
| Design | no unresolved reference; the required interface = the compiled interface (contracts compile in the candidate registry); each schema sample validates as expected; no consumer unaccounted; a decision present |
| Prove | required cases = observed cases; expected failures = observed baseline failures on the captured pre-change program with the candidate tests and declared stubs, and nonempty; no unexpected errors; companion tests still green; a decision present |
| Implement | required proofs nonempty and all green on the branch; no newly broken reaching test; no remaining replacement; the cost formula where the subject states one; a decision present (a send-back is an evidenced handoff, never green) |
| Land | reviewed program = merged program = written program; the recorded git commit; the proofs green on main |
| Retrospective | every observed friction has a follow-up, an instructions change or a reasoned `:none`; no follow-up without a red proof |

Reproduction, reference resolution, contract compilation, recorded test comparison and transaction observation need no
model. Localization, interface tradeoffs, proof relevance and diff review do; a model's decision recorded as data is
inspectable judgment and is scored downstream (send-backs, declines, calls), never claimed as proof.

## 4. Proofs: the weak point, and a catalogue

This weekend most failures traced to the proof or the task, not the implementer: a census that read files (unprovable
on a branch), an intent naming one of two changes, "keep the contract" on an uncontracted function, reached tests
asserting ruled-out behavior. With merges on green, a wrong proof lands wrong code or blocks right code. Keep a **proof
catalogue** page of good and bad proofs with why, seeded from Astra's table in §8.2 (example plus law; read cost plus
answer; writer behavior plus storage; and the bad kinds: arbitrary red, a single input as specification, a proxy for
success), `designed-task-examples.md` (minus its runtime guards) and this weekend's green and failed proofs. The Design,
Prove and Implement instructions cite it.

## 5. Measurement and experiments

Per step: calls, tokens, errors by signature, send-backs, time, cost before and after, and the path's seams (wake →
work → task loaded → context in ms and MB → proofs → first call or end; end → successor hosted). Per subject: did the
successor need root; did Implement go green; did it merge. Per variation: paired subjects at the same starting program
through pipelines A and B, one table. Keep a step only if it pays for its calls.

**The first experiment** (owner, Q13; plan §0 item 7) waits for the reconciler's launch (runtime-follows-db §10 card
R1) and the first-pass proofs (S3): one small existing defect with settled policy and an existing symbol; root mines it
and creates a two-step tree, Design+Prove → Implement (needs); the reconciler launches Design+Prove, its green end makes
Implement ready, the reconciler launches it from the predecessor attempt's commit so the stub and the red test reach it;
root works the merge request and writes the retrospective. Questions the next experiments answer: does Design's
reference pruning cut Implement's calls; is a separate Check step worth its call (pipeline B); should Design split into
survey and model (pipeline B); which instructions edits helped.

## 6. What must be built

The build order is runtime-follows-db §10 (22 cards with red proofs). Of it, this card owns: S1's
`:seon.task/instructions` on the step (with `steps`, `needs`, `execution`), `my.task/refine!`, `my.task/send-back!`
and `my.task/create!` (the vocabulary cut, plan §0 item 5), the `:sent-back` end on the attempt (S3), the two pipeline-A trees (Design+Prove
→ Implement first; the full six later) as EDN the bench writes, and the proof catalogue page. Gone from earlier drafts:
the stage type entity, the rendered template, `:seon.task/stage`, `:seon.task/basis-commit`, the stage-aware proof
selection (each step's proofs are its own), `advance-end!` and its dial, `my.root/launch!`.

## 6b. Learning from guessed names (owner decision 7; plan §0 item 9)

Every "Unable to resolve symbol X" is a signal of what an agent expected to exist (health's agent-error signatures group
them; 58 occurrences of the top one on 2026-10-11: `seon.task/current`, `seon.print/inspect`, `my.cluster.source/write`,
`my.agent/messages`).

1. **Answer at the failure.** The refusal carries the nearest real symbols across public declarations and `my.*` (name
   parts, not only spelling distance within the guessed namespace) with each one's one-line doc and calling form as an
   offered operation; tool markup is stripped at read.
2. **Learn the mapping from behavior.** When the agent's next successful form uses an offered candidate, record guessed
   X → resolved Y (agent, task) as a fact.
3. **Link it to the task.** A resolved guess adds Y to the subject's references, so the next agent on the subject reads Y
   and never guesses X; the Retrospective lifts recurring mappings into the instructions it writes.
4. **Repeated guesses become mined tasks.** A name guessed by two or more agents is a missing affordance: teach it, build
   it (`my.task/current` was one), or alias it only when the guess is the standard Clojure or library word.

Measured by guessed-name occurrences and calls spent on failed guesses per run; a help line only if the count does not
fall.

## 7. Resolved questions, and what stays open

Resolved (reviews §8.1–§8.2, owner Q13–Q16, and the four rounds of runtime-follows-db): one task per step, the subject
the handoff; instructions are data on the step, never a template or a stage type; order is `needs`, parallel by default;
the reconciler launches, nobody by hand; Check is not a separate agent in pipeline A (Implement's first turn, with a
send-back right); Survey and Model are one Design step in A; Implement sends back by itself; every step proof is a data
predicate, no model call; no send-back cap or advance dial, a loop is a problem task; variations are compared on paired
subjects at the same starting program; root reviews each diff before it merges until criteria for automatic merges exist.

Open: how a test declares itself host-bound (plan §0 open question 2); how much of the laws excerpt belongs in each
step's stable prefix (measure cache hits per step); the first experiment's subject (plan §0 open question 3).

## 8. Reviews

(Fable, then Astra, append their improvements below; root and the owner discuss.)

### 8.1 Fable (2026-10-11)

Verified against HEAD be10632f3 by symbol: `seon.turn/step` (the pass runs `test-step`, then, when a model pass closed
its turn, `end-task!`), `seon.turn/task-end` (`:done` is `seon.context/task-done?`: every tagged proof green on the
branch and no reached red), `seon.turn/end-task!` (proposal → `land-end!` under the dial `:seon.config.root/land?` →
`seon.context/outcome-tx` at the task home → `end-message` to root → `end-task-call` closes admission),
`seon.task/assign!` (runs the proofs in the agent's own world first; refuses `:proofs-green`, `:proofs-unobserved`,
`:no-proofs`), `seon.task/task-edges` (proofs = deftests tagged with the task symbol), `seon.task/refuted` (proofs red at a
reach no green member shares), `seon.task/resolution` (closedness derived: faults, signal, outcome; nothing stored),
`seon.cluster.message/send!` (writes on the recipient's home branch), `seon.cluster.agent/graph-definition` (one graph
per agent: mailbox → turn, plus schedule), `clojure.core.async.flow/impl` (`report-chan` and `error-chan` are
`sliding-buffer 100`: lossy by design, so nothing durable may depend on them).

#### 8.1.1 Is this correct software engineering?

The chain is the discipline in the right order: reproduce (Mine) → localize and read the data flow (Survey) → model the
data (Model) → specify by failing tests (Prove) → implement to green (Implement) → integrate under a gate (Land) →
retrospect (Review). It is red-green TDD with the red written by a different agent than the green, design-by-contract
in Model, and the reproduce → localize → patch → validate shape the assignment names (I did not verify outside
sources; the comparison is to the shape as given). Four corrections:

| Finding | Change |
|---|---|
| **The interface is nobody's stage.** Prove needs the function names, arities and contracts to exist as red stubs; Model only covers schemas. Design-by-contract puts the contract before the tests. | Model becomes **Design**: schemas *and* the contracted stub declarations (`defn` with its `:malli/schema`, body `(fail! ...)`), so Prove's deftests compile against real symbols. Its `:unchanged` decision covers "no new schema, no new symbol". |
| **Survey and Model separate costs a model call to say `:unchanged`** on most tasks (this weekend's tasks added no schema). | One **Design** stage in pipeline A (survey + design); the split is pipeline B. Stage types stay separate entities so the data can say which paid. |
| **Check as its own agent duplicates Prove's stage proof.** Everything mechanical in Check (tagged, red on main for the stated reason, reads the program, cheap) is Prove's proof below; what remains ("encodes the intent") is judgment. | Fold Check into Implement's first turn with a send-back right (§8.1.4); measure Prove→Implement send-backs. A Check agent is pipeline B. |
| **Review is a retrospective, not code review.** Established practice reviews code before merge; here Land auto-lands on green and no one reads the diff first. That is a deliberate choice (immutable store, root's `decide!` can decline later), but name it. | Rename Review to **Retrospective**; keep root's `seon.task/decide!` as the post-landing code review of record, with the agent's diff value in root's block. |

Nothing is mis-ordered. Mine is correctly a query most of the time; its reproduction form on the task is Survey's input.

#### 8.1.2 One mechanically checkable stage proof per stage

A stage proof is an ordinary deftest that reads **the task the running agent holds** from its world
(`seon.context/held-task`, exactly as `task-done?` does), so it is parameterized by custody, not by argument. Each is
owned by a stage type entity and tagged by **stage type**, not task; `seon.turn/task-proofs` unions the task's own
tagged proofs with the current stage's proofs (one `or` in one function; no new runner). Then `task-done?`,
`task-end`, `assign!`'s refusals and the automatic test step are the stage machinery, unchanged.

| Stage | Reads | Red when | Green by |
|---|---|---|---|
| **Mine** | the task row; `:seon.task/evidence` ∋ a reproduction form; `seon.test/run` of that form on main | the form answers the expected (good) value on main, or an open task has the same subject symbol and intent digest | record the form that shows the bad value; name the subject |
| **Survey / Design** | `:seon.task/must-read` resolved on the branch (`[?e :seon.fn/sym s]`, `:seon.ns/name`, `:seon.test/sym`); the intent's backticked symbols resolved the same way; the decision component; for `:added` keys, `malli.core/schema` compiles and `m/validate` holds on each `:seon.task.stage/sample` | any reference unresolved; intent names no existing symbol; no decision; an added key that does not compile or whose samples fail; a declared stub symbol without a complete contract | prune and add references; rewrite the intent in typed symbols; register schemas and contracted stubs; write the decision with `:unchanged` + reason when nothing is new |
| **Prove** | the deftests tagged with the task (`task-edges`); `seon.task/refuted` on the branch at the head program; each proof's run result (`:seon.test/elapsed-ms`); the proof's var usages (`clojure.core/slurp`, `clojure.java.io/*`) ; the decision | no tagged proof; a proof green at the head program (`assign!` would refuse `:proofs-green`); a proof whose failure data is not the stated class (an `:error` where a `:fail` is stated); elapsed over the dial `:seon.config.test/step-time-limit-ms`; a proof reading files; no decision on "tests asserting the old behavior" | tag proofs; make each fail with the stated data; keep them program-reading and cheap; record `:changed #{old tests}` or `:unchanged` + reason |
| **Implement** | today's `task-done?`; the stage decision | any proof red, a reached test red on branch and not on main, or no decision | the body; the decision `:changed #{syms}` (or `:send-back` with target and reason: the proof or intent is wrong, with the data) |
| **Land** | the merge task (`:seon.task/landed-git`, `:seon.task/refusal`, `:seon.task/refused-tests`) | neither landed nor refused with a routed target | automatic (`seon.turn/land-end!`); a refusal writes the decision `:send-back` to Implement (red reached test) or Prove (refused proof) |
| **Retrospective** | `seon.task/resolution` on the home; the task's open follow-ups; the stage type's template revision | the task's proofs are green on main but it is still open (no retirement); friction recorded with no template change and no `:none` decision | retire (derived, §8.1.3); file follow-ups as tasks with red proofs; edit the template or decide `:none` |

Judgment stays in two places only, both recorded as data and judged downstream: Survey's choice of references (scored
by Implement's calls and send-backs) and Check-in-Implement's "the proofs encode the intent" (scored by whether the
landed change was declined by `decide!`). No stage proof calls a model.

#### 8.1.3 Advancement with no machinery: the end already does it

Today's facts: an agent's proofs run inside its own pass; a green end runs `end-task!`, which proposes, lands under a
dial, writes the outcome at the task home, sends root one message and closes the agent. That *is* a stage transition
for the last stage. Make it the transition for every stage:

1. **The task carries its stage** (`:seon.task/stage`, a ref to the stage type entity) and its decisions
   (`:seon.task/decisions`, cardinality-many component of the §3b map plus `:seon.task.stage/target` for send-backs).
   The stage type entity holds `:seon.task.stage/proofs` (test symbols), `:seon.task.stage/next`,
   `:seon.task.stage/template`. Pipelines are a vector of stage ids on a pipeline entity; the task refs its pipeline.
2. **`seon.turn/task-proofs`** returns the task's tagged proofs ∪ the current stage's proofs. Nothing else changes:
   `task-done?` is "stage done", `task-end` answers `:done`, the block shows the red stage proof with its data.
3. **`seon.turn/end-task!` gains one branch, `advance-end!`, beside `land-end!`**: for a `:done` end whose task has a
   next stage (or a `:send-back` decision with a target), in the same transaction as `outcome-tx` it sets
   `:seon.task/stage` and calls the one launch path the chain runs already (`seon.competency/start-run!`: a fresh agent,
   its branch, `seon.task/assign!`, which runs the new stage's proofs red first). Proposal and landing run only when the
   stage is Implement. Under a dial `:seon.config.chain/advance?`, as landing is under `:seon.config.root/land?`.
4. **The next agent's first wake is its assignment**, as today. Its context is `seon.context/current-state` rendering
   the stage template from the stage entity (derived at render, never stored: the card's `:seon.task/instructions`
   field goes), then the task as the previous stage left it.
5. **Root observes, it does not drive.** The end message it already receives carries the task; its block derives the
   stage column. Root acts only on a typed refusal that routes nowhere or a send-back count over
   `:seon.config.chain/max-send-backs`.

In Flow terms the edge between stages is **not a channel**: each stage is a fresh agent graph
(`seon.cluster.agent/graph-definition`), the handoff is the task entity at the task home, and the event is the
recording transaction of the previous agent's last pass. No pipeline proc, no stage proc, no supervisor, no poller, no
`::flow/report` consumer (lossy), no `ping` loop, no `pause`/`resume`, no done-check registry, no second state machine.
**What disappears from the card**: §2's done-check functions and `:seon.task/instructions`; §6 item 3's "transition"
as a mechanism (it is three lines in `end-task!`); the Check stage of §3 for pipeline A.

**Task edits land at the task home.** `my.task/refine!` is `seon.task/create!`'s upsert with `:seon.task/basis`,
written through `seon.task/home-connection` with the agent as transaction provenance, exactly as
`seon.cluster.message/send!` writes on the recipient's home branch: the handoff must be visible to a fresh agent on a
fresh branch, so it cannot live on the editor's branch. A conflicting basis refuses with current and proposed, as today.

**Auto-retire is derived, not written.** `seon.task/resolution` gains a fourth clause: `:seon.task/closed-by
:seon.task/proofs` when every tagged proof's latest member on the home is green and the task was ever assigned
(`assign!` refuses an already-green task, so "ever assigned" rules out the never-red). The `:seon/tasks` tag removal
from definitions is part of the landing's write-back, not a separate sweep.

#### 8.1.4 Answers to §7

| Question | Recommendation |
|---|---|
| Check a separate agent? | No, in pipeline A: Prove's stage proof carries the mechanical half; Implement's first turn may `send-back` to Prove with data. Pipeline B adds the agent; the send-back rate decides. |
| Survey and Model separate? | One Design stage (references, flow, schemas, contracted stubs) in A; split in B. |
| May Implement send back itself? | Yes: `my.task/send-back!` writes the decision with target and reason; the stage proof then holds and `advance-end!` routes to the target. Root is involved only over the send-back cap. |
| Which done-checks need judgment? | None: every stage proof above is a data predicate. Judgment is in agents' decisions and is scored by downstream data (send-backs, declines, calls). |
| How much law excerpt in the stable prefix? | Only the laws the stage's template cites (Prove cites "Proof and performance" and the catalogue; Design cites "Data and contracts"), never the whole file; measure cache hits per stage in the competency table. |
| How are variations assigned? | Deterministically by task id parity (A on even digests, B on odd), per round; no randomness, fair across families once the set is larger than a few tasks. |

#### 8.1.5 Making the first experiment smaller

Pipeline A for one mined task, three DeepSeek agents: **Design → Prove → Implement**, Mine done by root's query, Land
automatic, Retrospective by root. Build only: (1) `my.task/refine!` at the home; (2) `:seon.task/stage`,
`:seon.task/decisions` and the stage type entity with three stage proofs (three deftests); (3) the `or` in
`seon.turn/task-proofs`; (4) `advance-end!` in `end-task!` under its dial; (5) the template rendered in
`current-state`. Not yet: the pipeline entity (A is the only one; a vector in the stage entities' `next` suffices),
measurement columns, the catalogue page (seed it with the three proofs the stages produce), §6b steps 3–4. Expected
cost of the whole experiment: one task's three stage proofs plus one `end-task!` branch; the transition itself adds no
model call.

**Not verified:** that `seon.competency/start-run!` is callable from inside `end-task!`'s proc thread without the
acquisition fences root's REPL path avoids (it runs today from root's JVM REPL); and the var-usage attribute by which a
proof's file reads are detected, which Prove's proof needs and I did not confirm by symbol.

### 8.2 Astra (2026-10-11)

**Keep the engineering sequence; revise the proof selection and handoff before building advancement.** A task at its
home carries intent and evidence references; schemas, stubs and tests live on the working program branch. Both must
reach the next agent. The smallest composition is stage-selected ordinary tests and the existing task/agent owners.
It replaces generic done-checks, stored instructions and a separate Check agent; it needs no stage supervisor.

#### Engineering coverage and agreement

| Topic | Verdict on Fable | Place and concrete correction |
|---|---|---|
| Mine | Agree: normally a query | Record the smallest reproducible input, expected and actual data, execution mode, program basis and relevant configuration. Reproduce through the affected entrance; distinguish a product defect from a broken fixture. Match existing subjects and evidence, not just an intent digest. New features need an acceptance example, not a fabricated incident. |
| Design = Survey + Model + interface | Agree | Trace producer → transforms → storage → consumers; verify localization by an owner probe. Reuse a library seam before designing. Specify arities, named input/output schemas, error behavior, compatibility, replacement/deletion and caller conversion. Stubs are for new meanings; do not replace a working existing body merely to manufacture red. |
| Prove | Agree on its place, strengthen its meaning | Exact reported inputs plus boundary/negative cases; a property only for a stated law; deterministic work counts for a cost claim. Include integration/lifecycle behavior when that is the defect. Existing preservation tests may already be green; require a relevant new red, not every test red. |
| Implement, including send-back | Agree | Read the tests first; change the producer, update all callers and docs, remove what the change replaces. Measure the same real workload before/after, with like evaluators; interpreted allocation versus compiled allocation is not a regression. These are implementation work and proof/review evidence, not extra stages. |
| Land automatic | Disagree with review only afterward | Root's merge decision owns review of the actual diff, including special cases, lost behavior and unfinished deletions. Keep the required independent reviews there; an existing review call can do this. Mechanical merge/write-back/commit needs no model call. `seon.task/decide!` only writes an outcome: declining an accepted change does **not** undo it. |
| Retrospective | Agree on rename; avoid a mandatory call | Derive green-on-main closure and summarize costs mechanically. Use root's existing review for specific friction and follow-ups; change a template only when evidence supports it. Rollback belongs to Land's integration plan: retain the accepted/base identities and use the existing merge/write-back path to revert, with dependent changes considered. |

Reproduction, reference resolution, contract compilation, recorded test comparison and transaction observation do not
need a model. Localization, interface tradeoffs, proof relevance and diff review do. A model's decision recorded as
data is inspectable judgment, not a mechanical proof that the judgment is correct. Immutable history aids rollback;
it does not make external effects reversible or replace pre-merge review.

#### Stage proofs: concrete assertions, without pretending judgment is decidable

The following are **proposed deftest bodies**, not installed APIs. Locals denote projections from a captured task
revision, candidate program and recorded observations; names below introduce no production attributes. Bind those
inputs explicitly in the ordinary test world. Each equality reports the offending data; missing observations remain
unknown. Run reproduction, schema sample evaluation and tests through their owners first, then inspect their records;
calling `seon.test/run` from a stage predicate would make it effectful and risk recursive test execution.

| Stage | Concrete assertions inside its deftest | What red means; limitation of Fable's proof |
|---|---|---|
| Mine | `(is (= expected-input observed-input))` `(is (= expected-main-basis observed-basis))` `(is (= reported-actual observed-actual))` `(is (not= expected reported-actual))` | Missing/mismatched reproduction data names what to rerun. Bind a nonempty case set first. A duplicate lookup chooses the existing task; it is not a second refusal mechanism. Feature work compares an acceptance example with the current missing behavior. |
| Design | `(is (= #{} unresolved-refs))` `(is (= required-interface compiled-interface))` `(doseq [[schema sample expected-valid?] samples] (is (= expected-valid? (malli.core/validate schema sample))))` `(is (= #{} unaccounted-consumers))` | Compile with `malli.core/schema` in the candidate registry, including changed schemas, and expose compile errors. Compare actual producer/consumer contracts with the proposed interface. Consumer evidence records conversion or preservation, including deletion and docs. Resolving a bag of references proves neither relevance nor that anybody read them; render the same references in the next agent's context and assess their usefulness there. |
| Prove | `(is (= required-cases observed-cases))` `(is (= expected-failures observed-baseline-failures))` `(is (seq observed-baseline-failures))` `(is (= #{} unexpected-errors))` | Case identity includes entrance, input, expected/actual data and proof digest; red must name the behavior, not merely `:fail`. Baseline is the captured pre-change program **with the candidate tests**, plus declared stubs for new symbols. A known stub error can be explicit; an unrelated compile error cannot count. Preserve already-green companion tests. `seon.task/refuted` supplies historical red reach pairs, not this oracle. |
| Implement | `(is (seq required-proofs))` `(is (= required-proofs current-green-proofs))` `(is (= #{} newly-broken-reaching-tests))` `(is (= #{} remaining-replacements))` | Current means the tested definitions and task inputs still match. Assert the task's work-count formula when applicable; keep before/after CPU/allocation/storage observations alongside it. Test evidence plus diff review establishes the deletion/caller work; a self-authored `:changed` list alone does not. A send-back is an evidenced handoff, never green implementation. |
| Land | `(is (= reviewed-program accepted-program written-program))` `(is (= accepted-git recorded-git))` `(is (= required-proofs current-main-green-proofs))` | These are observed program identities and positive commit/test evidence, not optional keys whose simultaneous absence passes. A refusal is an unsuccessful attempt routed to the responsible work; it must not satisfy successful Land. New integration failures need localization, not automatic classification as a bad proof. |
| Retrospective | `(is (= observed-friction (set (keys dispositions))))` `(is (= #{} follow-ups-without-red-proofs))` | Every observed friction has an explicit follow-up, template change or reasoned no-change decision. Empty friction is explicit `:none`. Derive retirement separately from current main proofs; no test should demand a written retirement flag that the design deliberately removes. |

Every row also reads the stage's explicit decision required by §3b. Use a schema for its required fields, rather than
duplicating shape assertions. A decision of `:unchanged` needs the observed interface/diff that supports it, not just
a nonempty reason. Report subject, expected and actual data through `seon.context/proofs`; current failure claims are
stored shown text, so exact machine comparisons may require a named observation value, not parsing that text.
Do not add a file-I/O blacklist: it misses indirect reads and mistakes fixture reads for the defect. The proof must
observe the branch program through its owner; demonstrate that changing that program changes its result.
Finite tests cannot exclude every special-cased implementation or irrelevant reference; no extra verdict tier fixes
that. Broaden examples by behavior class, use independent laws where applicable, and read the diff before merging.

**Catalogue seed** (adapt the research examples; their added runtime guards conflict with c722d8d22 and are not adopted):

| Kind | Example assertion | Why |
|---|---|---|
| Good: example + law | For `seon.sci.forgive/forgive`, assert token separation for `"a]a"`, `"1]2"`, empty and nested inputs; seeded bounded idempotence and repair round-trip | Finds more than the reported token; round-trip detects silently unrecorded edits. Fix token production, not a post-result guard. |
| Good: read cost + answer | For `seon.fn/gate-set`, assert equality with the independent full-walk model and `(is (<= reads (+ 80 (/ callers 2))))` on leaf/hub fixtures | Preserves semantics and charges only relevant callers; add unrelated transitive nodes to distinguish whole-program work. The numerical budget comes from the research measurement, not a universal policy. |
| Good: writer behavior + storage | Publish one declaration edit through the real owner; assert exactly that declaration changed, pull it back, and `(is (<= datoms (+ (* 40 changed-count) 10)))` | Exercise real publication and deletion/caller cases, at several file positions/sizes. The research's pure selector alone does not prove storage cost. |
| Bad: arbitrary red | `(is false)` or a failing equality with constants | Tagged, fast and red, but no behavior can turn it green. Failure type alone admits it. |
| Bad: single input as specification | Only the reported slug input, then accept a constant answer | A special case passes while nearby titles fail. Test empty, already-normalized and varied titles against the promised behavior. |
| Bad: proxy for success | Nonempty references, a `:pass` note, two assertion events, or a census using `slurp` of checkout source | Measures paperwork or the wrong program. None proves the branch's intended behavior; the loop rounds already demonstrated the file-census failure. |

#### Advancement: one owner, but not one union and three lines

Verified at HEAD b1fa25d4e; the named production sources equal default's f51240f34 archive. Core.async pin
dc35f3e0d7 and Malli pin dd13ff8113 were read locally. Read-only JVM probes on default returned `:io` for
`seon.turn/step`; root held no task, had no proofs, `task-done?` false and `task-end` nil (3 ms evaluation,
55.5 ms request, 1.2 MB evaluation allocation). Symbol inspection took 2 ms, 70.8 ms request, 0.3 MB;
`seon.competency` was absent. An initial `find-var` probe errored on that absent namespace (112.5 ms request,
0.2 MB); the corrected namespace-aware inspection succeeded. Health was `:unknown` with untested-hot warnings;
these are source/read observations, not an end-to-end launch proof.

| Verified seam | Consequence |
|---|---|
| `seon.turn/task-proofs` selects execution; `seon.context/task-done?` independently calls `seon.context/proofs`; `seon.task/assignment-read` independently calls `seon.task/task-edges` | A union changed in the turn does not change admission or completion. Even a union everywhere is wrong: Prove succeeds when implementation proofs are **red**. Select the current stage's obligations once in the task owner and share that selection with assignment, test execution, context and completion. Only Implement requires the task's behavior proofs green. |
| `seon.task/assign!` runs proofs before writing the assignment; test members have their own execution worlds; `seon.context/held-task` reads the live home and pulls only four declaration fields | A generic test cannot discover its candidate task/stage by asking which task its test agent holds. Supply the task revision/stage as test input before assignment; include that input in evidence validity. A new task revision invalidates only dependent observations, preventing old Design decisions from completing a send-back. |
| `seon.task/create!` selects six declaration fields; `seon.task/assignment-read` reads proofs at home | The proposed refine face is not merely an alias: new decisions must be admitted and retained by the owner. Branch-only proofs remain invisible at assignment: existing task `seon.task/assign-cannot-see-a-red-proof-the-gate-will-not-land`. |
| `seon.turn/step` records, runs tests, then calls `seon.turn/end-task!`; the latter proposes, lands, writes home outcome, sends a message and closes branch admission through separate writes | The last evaluation's recording transaction is not an atomic stage transition today. A launch performs effects and cannot run inside a transaction-data function. Do not describe home writes and branch writes as one transaction. |
| `seon.competency/start-run!` calls `seon.test-support/own-branch-agent!`, `seon.task/assign!`, then `seon.test-support/outside-wake-on-branch!` | This private test helper forks from main, submits and waits for a setup MCP evaluation, and explicitly sends a wake: assignment alone is not today's first wake. Forking main loses the previous stage's unmerged stubs/tests. Carry the predecessor's commit as the next branch basis, referenced by the home task. |

**Proc-thread answer:** `clojure.core.async.flow/process` and `clojure.core.async.flow.impl/proc` run an `:io`
transform on its proc thread and permit blocking; `seon.cluster.agent/graph-definition` gives each agent its own
mailbox/turn/schedule graph. Waiting for a *different* new agent's setup has no inherent same-proc dependency.
The helper's writes use explicit custody via `seon.test-support/transacted!`, `seon.task/written` and the wake helper;
there is no source basis for inventing an acquisition exemption. However, the helper is not loaded on default and
its exact nested launch has not been exercised here. Reuse the production acquisition/assignment owners with explicit
home and predecessor basis; make the bench call that composition. Prove it once through a real turn with injected
provider replies, including successor visibility and actual predecessor exit, before claiming it installed.

`clojure.core.async.flow.impl/create-flow` gives report/error channels sliding buffers of 100. They are observations,
not durable handoffs; Flow stop is not a join. Keep one transition at `seon.turn/end-task!`: commit the stage result,
next assignment's identity/input and predecessor program basis at the task home, then let the existing agent
lifecycle acquire and wake that assignment after the commit. Identity comes from that assignment, not a new random
launch on repeated observation. Boot derives outstanding assignments from those same facts; it does not resume a
partially executed launch. Make that existing lifecycle own acquisition and retirement, including actual exit.
This is a proposed owner change, not a currently atomic composition. Its cost is proportional to the task's changed
facts and next proof set, plus the existing branch acquisition; no whole-program scan or per-stage monitoring.

| Remove | Design that makes it unnecessary |
|---|---|
| Send-back cap and a root watcher for it | Return counterexample, affected obligation and target as the task's ordinary given-back outcome; route to its owning stage. Charge all successor calls to the same existing task budget, so fresh agents do not replenish it. Exhaustion remains the normal resumable task end, not a second send-back state machine. |
| Advance dial | An assigned chain has its next work; execute it. Experiments choose a finite stage sequence as task data. Use existing task launch/budget controls for execution, not another boolean that can leave completed work stranded. |
| “Ever assigned” auto-retirement condition | `seon.task/resolution` derives closure from a nonempty set of current green behavior proofs on main, including tasks satisfied by another change. Old green or absent evidence is unknown. Do not remove the proof links while closure still derives from them. Existing owner: `seon.task/open-tasks-whose-proofs-are-green-are-never-retired`. |
| Repeated stage/proc state | One current stage in the task, one predecessor program basis, and ordinary assignment facts; no parallel running/done flags, polling, report-channel consumer or refusal tier. Send-back changes those inputs; it never masquerades as successful Implement. |

#### Smallest experiment and top changes

Use one small existing defect with settled policy and an existing symbol, like the successful slug/search runs.
**Two agents: Design+Prove → Implement.** Root mines it and performs the existing diff review/landing and retrospective.
First rehearse one explicit handoff through existing owners with injected replies: the successor sees the predecessor's
test and stub, the Design+Prove obligation is green while the behavior proof is red, then Implement makes it green.
Root can launch this one successor explicitly; that tests task quality without first implementing automatic chaining.
Then make that same handoff automatic and run the two-model experiment. Do not yet add pipeline entities, send-back
automation, automatic retirement, template-learning or measurement columns. Keep ordinary cost evidence; compare
paired tasks at the same starting program when comparing pipelines—task-id parity does not balance a small sample.

Top three changes: **(1)** one stage-aware proof selection with explicit task inputs, **(2)** predecessor-program
handoff through the task/agent owners, **(3)** pre-merge diff review and behavior-based proofs, deleting the three fences
above. This lane changes only this review; no new runtime task or code was written. The branch-proof admission and
retirement gaps above already have task identities; new chain-specific gaps are requirements for this unbuilt card.
