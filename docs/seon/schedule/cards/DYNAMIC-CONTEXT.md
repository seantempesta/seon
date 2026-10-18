---
title: "DYNAMIC-CONTEXT: regenerated context, one working set, one agent per task"
type: card
status: proposed (stages 4-7 deferred, plan §6; the task members are TASK-CHAIN §1)
created: 2026-10-10
---

# DYNAMIC-CONTEXT

What exists: `seon.context/model-context` already regenerates every model call from stored facts (help, the agent's
evaluations, the current-state block) and stores nothing; but `seon.context/exchanges` replays each evaluation's stored
shown text (`:seon.eval/shown`) verbatim, so a definition read three times is sent three times on every later call, and
nothing ends an agent when its task ends. The Clojure approach: the block gains a working set (the current source of
the task's references, the agent's admitted edits and its unresolved failed definitions, each once), history entries
that showed an admitted definition become one-line references to it, the writer refuses a second task per agent and
closes a done agent's admission, and the end of a task is one message to root. Owner rulings (2026-10-10): "the right
context, not tokens; dynamic context; doc and source once; agents always completing; the next task is a new agent;
whole defns are fine"; reflection is debugging on a snapshot ("record; if it's not clear ask the agent"); one happy
path for task ends, no throwaway derivation. Reviewed independently by Astra
(`docs/research/agent-platform/2026-10-10/astra-chain-friction.md`, "Assessment of DYNAMIC-CONTEXT"); its five
corrections are folded into the owning sections below.

**Chain run 3** moves to the small ordinary task `comp.text/slug-removes-edge-hyphens` (lane `slug-task`, per Astra);
`seon.sci.forgive/forgiving-forgiven-text-changes-nothing` becomes a later, separate platform acceptance. The stage
order below puts what a small task needs first (namespace, end message, lifecycle) and the working set, which a
180-line read made urgent, after.

## Evidence: the two chain-run-2 agents

Read on default in JVM mode, read-only (`seon.db/branch-database` for the released branch `:agent-e84b11b8bd78`,
`seon.db/branch-connection` for `:agent-4e349b905cf7`; `seon.competency`'s `model-calls` and `model-evaluations`;
`seon.eval/of-agent` with `:seon.eval/shown-tokens`). "Replayed" means an evaluation's shown tokens multiplied by the
calls after it, since every later call re-sends it. These are prompt-size measurements; neither run succeeded, and a
smaller prompt is not itself a measured gain in task success (Astra). The success measure is stage 8's rerun.

| | `chain2-forgive-1` | `chain2-forgive-2` (at 30 calls) |
|---|---|---|
| calls, forms, repeated forms | 15, 61, 19 | 30, 77, 19 |
| prompt tokens (cached) | 155,932 (108,288) | 844,843 (732,416) |
| per-call prompt, first → last | 2,333 → 16,852 | 2,327 → 50,320 |
| shown text of all evaluations | 7,718 | 19,736 |
| replayed shown text, share of prompt | 71,261 (46%) | 324,717 (38%) |
| `source`/`doc`/`dir` reads: tokens, replayed | 14 reads, 4,123, 53,715 (34%) | 24 reads, 17,804, 306,193 (36%) |
| `seon.sci.forgive/scan` (about 180 lines) read | once, 1,989 tokens (cut), replayed 27,846 (18%) | 3 times (calls 1, 16, 19), 3,519 each; 10,557 tokens = 21% of the last prompt; replayed 190,026 (22%) |
| other repeated reads | `code-line!` twice | `a-stray-closer-keeps-tokens-apart` and the task's proof twice (calls 2 and 8) |
| definitions attempted | 0 `defn`s | five `(defn- scan …)` pastes (about 10 kB each), none admitted: four carry the model-authored typo `@balanced?` (the local is `balanced`, a volatile), the fifth a literal `...` body; "Unable to resolve symbol: balanced?" each time. Stale admitted copies: 0 |
| `in-ns` forms; of them with `:seon.cluster.eval/ns` already that namespace | 15; 13 | 14; 9 |
| "Unable to resolve symbol" evaluations | 7 (a guessed `seon.print/inspect`, …) | 21: locals of `scan` evaluated as forms (`(at-code! o line)`, `(when (and @form …))`, `(if inserted …)`: 9), the five `defn- scan`, guessed names (`my.cluster.source/write`, `seon.task/current`, `(my.program)`) |
| other repetition | the agent `slurp`ed `forgive.clj` three times (2,872 shown tokens): the same source as text, outside any read of a declaration | `(dir my.program)` 1,472 tokens at call 22; `write-branch!` and `write-back!` sources at 25 |

Two findings beside the measurement. The reply's elision forms (`(get-in result/… […])`) do not run in JVM mode
("No such namespace: result"), so an outside agent reading a cut value re-queries instead; filed with this card.
And nobody was told either run ended: both hit the cap with 7 and 14 agent errors, and root's later questions to
`chain2-forgive-2` reopened the same task as more task work (11 more calls, no answer).

## The owner's reading, checked against the code

- *The context is regenerated on every call, not appended.* True in mechanism: `seon.context/model-context` reads the
  facts at the call's basis (`:seon.ai.attempt/basis-t`) and stores nothing. False in effect: `seon.context/exchanges`
  renders every evaluation through `seon.repl/entity-emission` and `seon.repl/reply-text`, which splice the stored
  `:seon.eval/shown` text as shown, so the history is a transcript by content. The one place that already rewrites a
  past reply from current facts is `:seon.repl/changed-since?` (a value changed since the agent's last turn gets
  ";; changed since your last turn" and the form that reads it whole). That is the seam this card generalizes.
- *A function's doc and source appear once, current.* False today: a read is shown where it happened and never
  updated. The branch holds the current definition (`:seon.fn/source`, `:seon.program/definition-digest`) and the
  evaluation records the definitions it entered (`:seon.cluster.eval/executed`, `[sym digest]`); the definitions it
  showed are not recorded.
- *One agent works one task.* Derived, not enforced: `seon.task/agent-task` answers the agent's latest assignment,
  `seon.task/assign!` refuses only a task without red proofs, and `my.task/claim!` refuses only a task another agent
  holds. Nothing refuses a second assignment to the same agent. The chain-run-2 failure was not a second assignment but
  the same task reopened by a wake (Astra), which the lifecycle rule below addresses directly; the one-task refusal is
  the owner's policy, not the fix for that run.
- *An agent ends when its task completes.* Half true: `seon.turn/next-agent-work` opens no continuation once
  `seon.context/task-done?` holds, but any new wake opens a turn on a done or capped agent, which is how root's questions
  became 11 more calls. `my.task/complete!` does not exist; `my.turn/complete` is the disposition that ends the reply
  and is the give-back (root's ruling). `seon.agent/archive!` (`:seon.agent/archived-tx`) filters presentation only;
  `:seon.agent/admission-closed-tx` (`my.agent/retire-own-branch!`, `seon.cluster.host`) is the fact that makes the
  writer refuse later turns.
- *The next task is a new agent.* Already how `seon.competency/launch!` and chain run 2 work (a fresh id per run);
  nothing in the writer requires it.

## Data first

Everything the regenerated context is built from is already stored; one fact is added.

| Source | Stored (entity, attributes) | Derived at the call |
|---|---|---|
| The agent | `:seon.agent/id`, `:seon.agent/branch`, `:seon.agent/settings`, `:seon.agent/admission-closed-tx` | its namespace: the `:seon.sci.eval/ending-ns` of its latest evaluation, else the task namespace |
| Its task | the task entity, the assignment (`:seon.task/assignment`), `:seon.task/references` (the names its `plan` and `instructions` cite) | `seon.context/held-task`, `:seon.task/status` (`:working :done :capped`), `seon.context/proofs` from the test runner's latest results, `seon.ref/references` |
| Its evaluations | `:seon.cluster.eval/receipt` (`:seon.cluster.eval/source`, `:seon.eval/shown`, `:seon.eval/shown-tokens`, `:seon.cluster.eval/error`, `:seon.cluster.eval/executed`, `:seon.sci.eval/ending-ns`, `:seon.cluster.eval/run`) | the exchanges, in agent order (`seon.eval/of-agent`); the latest failed definition per symbol (a `defn` form with `:seon.cluster.eval/error` and no admitted digest) |
| The definitions it touched | branch head: `:seon.fn/sym`, `:seon.fn/source`, `:seon.program/definition-digest`; `:seon.test/sym`, `:seon.test/source` | the working set |
| **New**: the definitions an evaluation showed | `:seon.cluster.eval/shown-declarations`, a set of `[sym digest]`, written in the one recording transaction when the evaluation's value satisfies `:my.program/declaration-source` (the value of `my.program/source`) or the doc map of a program symbol | whether a history entry refers to the working set, and whether the definition changed since it was shown |

The new attribute is named at its producer, the recording step in `seon.turn` (`receipt-terminal-attributes`), beside
`:seon.cluster.eval/executed`, which already records `[sym digest]` for definitions entered. It is selected by the
value's declared schema, as the render law selects renders, never by parsing the form's text. "Changed" is decided by
the admitted declaration identity (`:seon.program/definition-digest` now against the digest shown or entered), never
by the executed set being non-empty (Astra). Context is rendered live and never stored; an attempt keeps its basis
(`:seon.ai.attempt/basis-t`), so what a call saw is derivable again.

Schemas reused: `:seon.context/current-state`, `:seon.ai/exchange`, `:my.program/declaration-source`,
`:seon.cluster.eval/executed`, `:seon.program/definition-digest`, `:seon.task/status`, `:seon.message/message`. New
names, each a new concept at its producer: `:seon.cluster.eval/shown-declarations` (above), `:seon.context/working-set`
and `:seon.context/namespace` (sections of the block, `seon.context/current-state`), `:seon.task/ended` (the end-of-task
message's value, below), `:seon.message/reflection` (a question to a finished agent, below).

## The rendering rule for history

Each exchange keeps the agent's forms and their short results, exactly as `seon.ai/messages` lays them out today. Three
kinds of entry change:

1. **An evaluation that showed a declaration** (its `:seon.cluster.eval/shown-declarations` is non-empty): the form
   stays (`(my.program/source 'seon.sci.forgive/scan)`), and the reply becomes the reply map without its `:value`,
   plus one data key naming where the definition now is:
   `#:seon.repl{:result result/e…, :working-set [seon.sci.forgive/scan], :ms 12}`.
   If the current digest differs from the one shown, the key says so: `:working-set [seon.sci.forgive/scan :changed]`,
   the same mark `:seon.repl/changed-since?` makes today; `seon.repl/response-entries` gains the key and the entry
   order holds it after `:result`.
2. **An evaluation that admitted a definition** (`:seon.cluster.eval/executed` holds the symbol with a digest and the
   form is a `defn`, `deftest` or `def`): the form is replaced by its head, `(defn scan …)` printed by the value
   printer's elision (`seon.print/form-text` of the symbol with the body cut), and the reply keeps its `:result`,
   `:ms`, errors and findings. The whole current body is in the working set once (root's ruling); the agent's message
   no longer carries a 10 kB paste on every later call. Only an admitted body that a later admission superseded is
   collapsed this way.
3. **A definition that failed** (a `defn` form with `:seon.cluster.eval/error` and no admitted digest) **stays a failed
   attempt** (Astra): its reply keeps the diagnostic as today, and the form shows its head plus the offending span
   (the source line the error names, `(REPL:165:61)` → that line), with the retrieval form `(get-in result/e… [:source])`
   for the whole rejected text. The latest failed attempt per symbol is also the working set's entry for that symbol
   (below), so the exact `@balanced?` the model must correct is in front of it, not the unchanged installed body.

**The working set** is a section of the block, `:seon.context/working-set`, ordered by what the task needs first
(Astra): (1) the task's `:seon.task/references` that are program declarations, current; (2) the agent's
admitted edits on the branch (the symbols in any evaluation's `executed` whose digest differs from main's); (3) its
unresolved failed definitions, each as the latest rejected source with its diagnostic; (4) then the other declarations
it read (`shown-declarations`), first-touched order. Each entry is the declaration as `my.program/source` answers it
now on the branch (`:sym :kind :source :contract :file :line`, rendered by `my.program/render-source-ai`, the code an
agent can copy into a `defn` unchanged), with `:changed-since-shown true` when its digest differs from the one an
earlier read showed; a deleted one (`my.program/ns-unmap!`) is one line, `:deleted`. Group (4) is bounded by the
profile's one budget like any value, oldest reads cut first with the requery `(my.program/source 'sym)`, so earlier
reads stay discoverable and the task, its counterexample and the current edit never fall out. The block is last in the
prompt (today's placement), so the working set sits nearest the model's turn.

What the model sees when a definition changed under it: the old read's reply says `:working-set [sym :changed]`, its
own `defn` is a head with its result, and the working set holds the current body once. Nothing says what the old body
was; `(my.program/history 'sym)` is the requery, offered on the changed entry as `:seon.render/operations`.

**Effect on the two runs (estimated from their data).** Run 1's final prompt 16.9k → about 12.5k (help and block
2.3k, working set 4.1k, the three `slurp`s stay as ordinary values 2.9k, forms and short replies about 3k); the
`slurp` copies are not declarations and are the agent's own doing, which the probing help line below addresses.
Run 2's final prompt 50.3k → about 24k (2.3k, working set about 11k since `scan`, the proofs and the two helpers appear
once, plus the latest rejected `scan` paste about 3.5k as a failed attempt, 77 form heads and short replies about 5k,
`dir` 1.5k). Across run 2's 30 calls the replayed shown text falls from 325k to about 100k of 845k prompt tokens.
Ordinary values, submitted forms and unique reads still grow with the run, so a history bound remains a real
question (Astra): `:seon.config.run/max-episode-runs` bounds the run, the profile's budget bounds the block, and
BENCH-9's window over exchanges is kept as a later dial if a long run shows the exchanges themselves outgrowing the
working set. The provider cache (87% of run 2's prompt tokens were cached) keeps paying for the stable prefix either
way; what the model attends to shrinks by about half.

## Two more context frictions (coordinator, 2026-10-10)

**The agent re-enters its namespace on almost every call** (13 of 15 and 9 of 14 `in-ns` forms were already there).
The block carries no namespace: `seon.context/current-state` has task, proofs, referenced names, cost, unread and turns;
`seon.repl/response-entries` shows `:ns` only when a form changed it (`seon.repl-test/the-namespace-appears-only-when-the-form-changed-it`),
so after call 1 nothing in the regenerated context states where the agent is, and the help's one example starts with
`(in-ns 'the.task.ns)`. Fix at the producer: the block gains `:seon.context/namespace`, the agent's current namespace
derived from its latest evaluation's `:seon.sci.eval/ending-ns` (the fact already stored), shown as
`:seon.context/namespace seon.sci.forgive` in the turns section; the help line becomes "Your block names your namespace;
define a function in the namespace that owns it: (defn increment …)" with `in-ns` only for a change. No re-entry is
ever needed because the agent's SCI context keeps `*ns*` across calls (`seon.sci.eval/enter-namespace!`).

**Body fragments evaluated as forms** (`(at-code! o line)` and eight more, all locals of `seon.sci.forgive/scan`).
The model read a 180-line function and tried to step through it; the REPL evaluates top-level forms, and a line of a
body names locals that exist only inside their `let`, `loop` or argument vector. The help teaches `doc`, `source` and
`dir` but not probing. Smallest fix, one help line that states the lexical rule (Astra): "A line of a function's body
names locals that exist only inside their binding; it cannot be evaluated alone. Probe by calling the function on
inputs, or redefine it on your branch with the probe inside." The example is left out of the line so a quoted form is
never read as input (the E1 F8 class). No tool: a stepping or `inspect` tool is machinery with one friction behind it,
and the whole-definition redefinition the owner accepted is the REPL way. The five failed `defn- scan` pastes are the
other half of plan §0 friction 2 (a small edit over a definition's source is the model's unit while the whole
definition stays the evaluator's unit, Astra), decided by the owner separately; this card's failed-attempt entry makes
the typo visible but does not supply the edit operation.

## The lifecycle rule

One agent works one task; the agent ends when the task ends; a new task is a new agent with regenerated context.

**Today.** An agent is created on first use (`seon.cluster.agent/creation-tx`, through `seon.eval.drive`'s first
request or MCP's first `eval_clj` with a new id), on its own branch. Root assigns one task (`seon.task/assign!`,
which refuses a task without a red proof) and wakes it once. The turn loop (`seon.turn/next-agent-work`) opens a turn
for any unanswered wake, or continues a reply that evaluated a form, under `:seon.config.run/max-episode-runs`; once
`seon.context/task-done?` holds it continues nothing, but a new wake still opens a turn. The agent's branch stays
until root retires it (`my.agent/retire-branch!`); `seon.agent/archive!` only filters presentation.

**What changes.**

- *A task ends* in one of three ways, all already derivable: its proofs are green (`:seon.task/status :done`), the cap
  is spent (`:capped`), or the agent gives it back with `my.turn/complete` and a `:my.turn/result` naming the reason
  (root's ruling: the existing disposition is the give-back; no `my.task/complete!` until the agent-loop cut names it).
- *The end closes the agent*: the settlement that observes the end (the turn's close in `seon.turn`, one writer)
  asserts `:seon.agent/admission-closed-tx` on the agent, the fact `seon.cluster.host` and `seon.turn`'s run writer
  already refuse later runs by. The branch is kept for root's review and deleted when the merge request closes
  (today's `my.agent/retire-branch!` from `seon.competency/launch!` or root).
- *The end notifies root*, once, on every task end, with no throwaway distinction (owner: one happy path; the stage below).
- *A question to a finished agent is reflection, not work* (owner: "record; if it's not clear ask the agent; this is
  debugging utilizing our ability to snapshot and restore"). With admission closed no work turn opens. Root reads the
  agent's record first: its final context (block with working set, exchanges, the end message) is one read,
  `(seon.context/model-context (seon.db/branch-database main branch) request agent-id)`, offered on the end message.
  When the record does not explain a decision, root asks the snapshot: `my.message/send` with
  `:seon.message/reflection true` and `:seon.message/as-of t` (the `t` of the call whose decision is in question, from
  the exchanges) opens one answer-only turn on a disposable fork of the agent's branch at that `t` (`d/branch!` from
  `seon.db/as-of`, `sci/fork` of the agent's context, the same regenerated context as that call saw plus the question),
  bounded to one model call with no evaluation (forms in the answer are shown, never run), its reply recorded as
  `:seon.message/reflection-answer` on the question's message, the fork deleted after. A status render is never
  presented as the agent's answer (Astra): the record read is labelled as the record, and a reflection that got no
  model answer says so as a typed unknown.
- *One task per agent, at the writer*: `seon.task/assign!` refuses an agent that already has an assignment
  (`:seon.task/agent` present), with the fix "a new task is a new agent id". Root (`"root"`, the MCP default) is the
  orchestrator and holds no task, so it is unaffected. This is the owner's policy; the chain-run-2 reopening is fixed
  by the closed admission above.

**What is deleted**: the wake-reopens-a-done-agent path in `seon.turn/next-agent-work` (the `(seq wakes)` arm for an
agent whose admission is closed, replaced by the reflection arm), and `seon.competency/run-state`'s own reading of
done/capped once the agent's closed admission is the fact it polls.

### Stage E: one notification to root when a task ends (owner, 2026-10-10)

"I want root to be notified on agent errors that occur during a task (at the end when they've hit their turn limit or
completed early) and I want to prompt root to identify problems with the task and to refine it." Checked against the
code: the message path exists (`seon.cluster.message/send!` through `my.message/send`, durable in the agent's
transaction, delivered into root's next context as `:seon.context/unread` and handled at the turn's close,
`seon.turn/close-call`); the grade exists (`seon.mine/grade`: proofs, calls, errors, tokens); the error signature
exists (`seon.error/signature`, the grouping lane `agent-error-signatures` is building over `seon.problems`); the
task upsert with history exists (`seon.task/create!` with `:seon.task/basis`); write-back of a task file does not
(plan §8 Q2; root's ruling: by hand after the next hand-run round, then automated).

The stage: when the settlement closes the agent (above), it also writes one message to root whose content is a value,
`:seon.task/ended`, holding references only: the task (`:seon.task/sym`, its handle), how it ended (`:done :capped
:given-back` and the reason), the proofs' final state (`seon.context/proofs`'s counts and each red proof's symbol),
the agent's errors grouped by signature with counts and the distinct error values' handles (the lane's grouping, reused,
never duplicated), calls used and prompt, cached and completion tokens (the same reads `seon.mine/grade`
makes), and the agent and branch. Its AI render ends with the prompt and the offered operations: "What was wrong or
missing in this task: plan, instructions, proofs or context?", the record read above, the reflection send, and
`(my.task/update! task k f & args)` (TASK-CHAIN §3a). Every task end sends it;
whether to refine is root's discretion (owner).

Proof, red first: `seon.turn-continue-test/a-task-that-ends-sends-root-one-ended-message` (a fixture agent whose proof
goes green, one capped, one given back; asserts on root's inbox: exactly one message per agent, its content value's
`:seon.task/status`, the proof counts and the error-signature count; a second ordinary wake to the closed agent opens no
turn). Files: `src/seon/turn.clj` (the close), `resources/seon/schemas/seon.task.edn` (`:seon.task/ended`),
`src/seon/task.clj` (the value's render), `test/seon/turn_continue_test.clj`.

## The loop this enables (owner, 2026-10-10: "a recursive self improvement loop")

The lifecycle stages are the first links; the whole loop is a follow-up card.

1. **Work**: a fresh agent takes a task with red proofs from generated context (exists: `seon.task/assign!`,
   `seon.competency/start-run!`, this card's stages 1 and 4–6).
2. **End**: one notification to root with errors by signature, final proofs and calls (stage E; reuses the message
   path, `seon.mine/grade`'s reads, the lane's signature grouping).
3. **Reflect**: root reads the record, then asks the snapshot (stage 7: a fork at the decision's `t`, one answer-only
   call; missing: the answer-only turn kind and its bound).
4. **Diagnose**: each problem becomes a task with a red proof at its producer (context, tool, reader or the task
   itself), and durable tasks are refined in place (exists: `seon.task/create!` upsert with history; missing: task-file
   write-back, by hand first; a reviewer agent that drafts the diagnosis tasks for root to approve is the third
   automation step).
5. **Fix**: new agents work those tasks and root merges (exists: the chain, `bin/lane commit`, root's merge).
6. **Measure**: the same task set reruns and `seon.mine/grade` and `table` (green share, calls to green, errors by
   signature, tokens) are compared with the previous round; only a better grade counts (exists except the by-signature
   column and a stored previous round to compare against; `grade` gains `:seon.competency/replayed-tokens` and
   `:seon.competency/errors-by-signature`).

Recursive because 4 and 5 improve what 1–3 use. Owner rule: run it by hand once more before automating, in the order
end notification → reflection on a snapshot → reviewer agent.

## What disappears

- `:seon.repl/changed-since?`'s special case ("full value" line for a changed value) becomes the general working-set
  mark; its two render branches in `seon.repl/input-text` and `seon.repl/reply-text` collapse into the one key.
- The wake arm that reopens a done or capped agent (`seon.turn/next-agent-work`), and `seon.competency/run-state`'s
  own end derivation.
- `(in-ns …)` as the taught first step in the help; the `slurp`-the-file habit has no help to delete, only the probing
  line to add.
- Not deleted: BENCH-9's history window over exchanges stays a later dial (ordinary values and forms still grow);
  BENCH-7 stays unbuilt (the block is last and the working set belongs there); `:seon.config.run/max-episode-runs`
  stays as the cap that makes `:capped` an end.

## Stages, about 15 minutes each, in order

Each names its red proof (asserting on data, in the owning test namespace) and its files, so root can run disjoint
lanes. Lane `agent-runs-proofs` holds four paths since 4027882d4 (proofs runnable at the REPL, red data in the block);
stages touching `src/seon/context.clj` or `test/seon/context_test.clj` wait for its release or go to it as a patch.
Ordered for chain run 3 on `comp.text/slug-removes-edge-hyphens`, a small task with no long reads: stages 1–3 (the
namespace line, the end message, the closed agent) are what it needs; stages 4–7 (the working set and reflection)
serve the later `scan` acceptance; the loop works crudely after 1–3.

1. **The block names the agent's namespace.** `:seon.context/namespace` in `seon.context/current-state` from the latest
   evaluation's `:seon.sci.eval/ending-ns`; schema in `seon.context.edn`; help line changed (`seon.bootstrap/help-value`)
   and the probing line added. Proof `seon.context-test/the-block-names-the-agents-current-namespace` (an agent whose
   last form ended in `seon.sci.forgive` shows it; a fresh agent shows its task's namespace) and
   `seon.help-test/help-teaches-locals-need-their-bindings-not-in-ns` (asserts on the lines data). Files:
   `src/seon/context.clj`, `resources/seon/schemas/seon.context.edn`, `src/seon/bootstrap.clj`,
   `test/seon/context_test.clj`, `test/seon/help_test.clj`.
2. **The end closes the agent.** The close asserts `:seon.agent/admission-closed-tx` on `:done`, `:capped` or give-back
   (`my.turn/complete`); the reopen arm is deleted. Proof `seon.turn-continue-test/a-done-agent-opens-no-work-turn`
   (fixture agent goes green; a later ordinary wake opens no turn; `:seon.agent/admission-closed-tx` present). Files:
   `src/seon/turn.clj`, `test/seon/turn_continue_test.clj`, `test/seon/competency.clj` (`run-state` reads the closed
   admission).
3. **Stage E, the notification** (above): files `src/seon/turn.clj`, `src/seon/task.clj`,
   `resources/seon/schemas/seon.task.edn`, `test/seon/turn_continue_test.clj`; depends on stage 2 and on lane
   `agent-error-signatures`' grouping function.
4. **The evaluation records the declarations it showed.** `:seon.cluster.eval/shown-declarations` written in the
   recording transaction when the value satisfies `:my.program/declaration-source` (and `doc` of a program symbol),
   as `[sym digest]`. Proof `seon.repl-test/a-source-read-records-the-declaration-it-showed` (one `my.program/source`
   evaluation through the one path; asserts the set holds the symbol and the branch's current digest; `(+ 1 2)` records
   none). Files: `src/seon/turn.clj` (`receipt-terminal-attributes` and the settlement),
   `resources/seon/schemas/seon.cluster.eval.edn`, `test/seon/repl_test.clj`.
5. **The working set in the block.** `seon.context/working-set`: referenced declarations, admitted edits, latest failed
   definitions, then other reads, each current once with `:changed-since-shown`. Proof
   `seon.context-test/the-block-shows-each-touched-definition-once-current-and-failed-attempts-as-attempts` (read
   `slug` twice, redefine it: one entry, the current source, marked changed; a `defn` with an unresolved local fails and
   its entry is the rejected source with its diagnostic while the installed body is unchanged; a deleted one shows
   `:deleted`; the referenced entry comes first). Files: `src/seon/context.clj`,
   `resources/seon/schemas/seon.context.edn`, `test/seon/context_test.clj`.
6. **History refers instead of repeating.** `seon.context/exchange-ai` drops the `:value` of a declaration-showing
   reply for `:working-set [sym]` (`:changed` when the digest moved), replaces an admitted definition's form with its
   head, and shows a failed definition as its head plus offending span with the retrieval form; `seon.repl/response-entries`
   gains the key and `changed-since?` folds into it. Proof
   `seon.context-test/history-refers-to-the-working-set-and-keeps-failed-attempts` (asserts on the exchange data: the
   second read's reply carries `:working-set` and no source text; an admitted `defn` is its head; a failed `defn` keeps
   its diagnostic and offending line) and `seon.repl-test/a-changed-value-is-the-working-set-mark`. Files:
   `src/seon/context.clj`, `src/seon/repl.clj`, `test/seon/context_test.clj`, `test/seon/repl_test.clj`.
7. **Reflection on a snapshot.** `:seon.message/reflection` and `:seon.message/as-of` on the message schema; the
   reflection arm in `seon.turn/next-agent-work` for a closed agent: one answer-only turn on a disposable fork at that
   `t`, no evaluation, the answer recorded on the question. Proof
   `seon.turn-continue-test/a-reflection-question-gets-one-answer-from-the-snapshot-and-no-work` (fixture provider;
   asserts the answer fact, one attempt, zero evaluations, the fork gone). Files: `src/seon/turn.clj`,
   `resources/seon/schemas/seon.message.edn`, `src/seon/cluster/message.clj`, `test/seon/turn_continue_test.clj`.
8. **One task per agent, at the writer.** `seon.task/assign!` refuses `:already-assigned` for an agent with an
   assignment. Proof `seon.task-ingestion-test/an-agent-holds-one-task-ever`. Files: `src/seon/task.clj`,
   `resources/seon/schemas/seon.task.edn`, `test/seon/task_ingestion_test.clj`.
9. **The grade measures repetition and errors by signature.** `seon.mine/grade` gains `:seon.competency/replayed-tokens`
   and `:seon.competency/errors-by-signature`; `table` folds them. Proof
   `seon.competency-grade-test/a-grade-counts-replayed-tokens-and-errors-by-signature`. Files: `test/seon/competency.clj`,
   `test/seon/competency_grade_test.clj`.

Stages 1 and 8 are file-disjoint from 2–3; 4 shares `turn.clj` with 2–3 and 7 and follows them; 5–6 share
`context.clj` with 1 and follow 4.

## The simplest alternative considered

Keep the transcript and add BENCH-9's history window (drop the oldest N exchanges). It is one dial and one line, and
it would have cut run 2's prompt. It is not enough: it drops the agent's own reasoning and results indiscriminately
while keeping the three identical `scan` copies inside the window, it shows a stale body after a redefinition with no
mark, and it answers neither the lifecycle nor the end notification. The second alternative, deduplicating by shown
text digest (`:seon.eval/value-digest`) without recording the declaration, removes identical copies but cannot say
which definition changed or show its current body, so the agent would still paste from a stale read. Recording
`[sym digest]` once at the producer, as `executed` already does, is the smaller design.

## Decisions taken (owner and root, 2026-10-10)

- History shows the head of an admitted `defn`; the body lives once in the working set. A failed definition stays a
  failed attempt with its diagnostic and offending span.
- Give-back is `my.turn/complete` with a reason; no new name until the agent-loop cut.
- Every task end sends root the end message; no throwaway derivation; root decides whether to refine.
- Reflection: root reads the record first, then asks a snapshot of the agent at the decision's `t` in one answer-only
  call; a status render is never presented as the answer.
- Task-file write-back for the refine operation is by hand after the next hand-run round, then automated.
- Chain run 3 is `comp.text/slug-removes-edge-hyphens`; `scan` is a later platform acceptance.

## Open questions for the owner

1. **The reflection fork's lifetime.** Options: (a) delete the fork after the one answer (recommended: nothing durable
   lives there; the answer is on the question's message at the task home); (b) keep it until the merge request closes
   so root can ask again at the same `t` without re-forking (a branch per question, collected later).
2. **The working set's bound.** Options: (a) the profile's one budget, oldest reads cut first with the requery
   (recommended: one budget, no new dial); (b) a count dial for group (4). Measured on chain run 3 and the later `scan`
   acceptance before deciding (b).
