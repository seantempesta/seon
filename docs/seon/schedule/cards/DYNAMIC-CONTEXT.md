---
title: "DYNAMIC-CONTEXT: regenerated context, one working set, one agent per task"
type: card
status: proposed
created: 2026-10-10
---

# DYNAMIC-CONTEXT

What exists: `seon.context/model-context` already regenerates every model call from stored facts (help, the agent's
evaluations, the current-state block) and stores nothing; but `seon.context/exchanges` replays each evaluation's stored
shown text (`:seon.eval/shown`) verbatim, so a definition read three times is sent three times on every later call, and
nothing ends an agent when its task ends. The Clojure approach: the block gains a working set (the current source of
every definition the agent read or defined, once), history entries that showed a definition become one-line
references to it, the writer refuses a second task per agent and closes a done agent's admission, and the end of a task
is one message to root. Owner ruling (2026-10-10): "the right context, not tokens; dynamic context; doc and source once;
agents always completing; the next task is a new agent; whole defns are fine".

## Evidence: the two chain-run-2 agents

Read on default in JVM mode, read-only (`seon.db/branch-database` for the released branch `:agent-e84b11b8bd78`,
`seon.db/branch-connection` for `:agent-4e349b905cf7`; `seon.competency`'s `model-calls` and `model-evaluations`;
`seon.eval/of-agent` with `:seon.eval/shown-tokens`). "Replayed" means an evaluation's shown tokens multiplied by the
calls after it, since every later call re-sends it.

| | `chain2-forgive-1` | `chain2-forgive-2` (at 30 calls) |
|---|---|---|
| calls, forms, repeated forms | 15, 61, 19 | 30, 77, 19 |
| prompt tokens (cached) | 155,932 (108,288) | 844,843 (732,416) |
| per-call prompt, first → last | 2,333 → 16,852 | 2,327 → 50,320 |
| shown text of all evaluations | 7,718 | 19,736 |
| replayed shown text, share of prompt | 71,261 (46%) | 324,717 (38%) |
| `source`/`doc`/`dir` reads: tokens, replayed | 14 reads, 4,123, 53,715 (34%) | 24 reads, 17,804, 306,193 (36%) |
| `seon.sci.forgive/scan` read | once, 1,989 tokens, replayed 27,846 (18%) | 3 times (calls 1, 16, 19), 3,519 each; 10,557 tokens = 21% of the last prompt; replayed 190,026 (22%) |
| other repeated reads | `code-line!` twice | `a-stray-closer-keeps-tokens-apart` and the task's proof twice (calls 2 and 8) |
| stale copies after a redefinition | none (0 `defn`s) | none: all five `(defn- scan …)` pastes (10 kB each) failed, "Unable to resolve symbol: balanced?" (the source's `::balanced?` mangled in transit) |
| `in-ns` forms; of them with `:seon.cluster.eval/ns` already that namespace | 15; 13 | 14; 9 |
| "Unable to resolve symbol" evaluations | 7 (a guessed `seon.print/inspect`, …) | 21: body fragments of `scan` (`(at-code! o line)`, `(when (and @form …))`, `(if inserted …)`: 9), the five `defn- scan`, guessed names (`my.cluster.source/write`, `seon.task/current`, `(my.program)`) |
| other repetition | the agent `slurp`ed `forgive.clj` three times (2,872 shown tokens): the same source as text, outside any read of a declaration | `(dir my.program)` 1,472 tokens at call 22; `write-branch!` and `write-back!` sources at 25 |

Two findings beside the measurement. The reply's elision forms (`(get-in result/… […])`) do not run in JVM mode
("No such namespace: result"), so an outside agent reading a cut value re-queries instead; filed with this card.
And nobody was told either run ended: both hit the cap with 7 and 14 agent errors, and root's later questions to
`chain2-forgive-2` were worked as more task work (11 more calls, no answer).

## The owner's reading, checked against the code

- *The context is regenerated on every call, not appended.* True in mechanism: `seon.context/model-context` reads the
  facts at the call's basis (`:seon.ai.attempt/basis-t`) and stores nothing. False in effect: `seon.context/exchanges`
  renders every evaluation through `seon.repl/entity-emission` and `seon.repl/reply-text`, which splice the stored
  `:seon.eval/shown` text as shown, so the history is a transcript by content. The one place that already rewrites a
  past reply from current facts is `:seon.repl/changed-since?` (a value changed since the agent's last turn gets
  ";; changed since your last turn" and the form that reads it whole). That is the seam this card generalizes.
- *A function's doc and source appear once, current.* False today: a read is shown where it happened and never
  updated. The branch holds the current definition (`:seon.fn/source`, `:seon.fn/definition-digest`) and the
  evaluation records the definitions it entered (`:seon.cluster.eval/executed`, `[sym digest]`); the definitions it
  showed are not recorded.
- *One agent works one task.* Derived, not enforced: `seon.task/agent-task` answers the agent's latest assignment,
  `seon.task/assign!` refuses only a task without red proofs, and `my.task/claim!` refuses only a task another agent
  holds. Nothing refuses a second assignment to the same agent.
- *An agent ends when its task completes.* Half true: `seon.turn/next-agent-work` opens no continuation once
  `seon.context/task-done?` holds, but any new wake opens a turn on a done or capped agent, which is how root's questions
  became 11 more calls. `my.task/complete!` does not exist; `my.turn/complete` is a disposition that ends the reply.
  `seon.agent/archive!` (`:seon.agent/archived-tx`) filters presentation only; `:seon.agent/admission-closed-tx`
  (`my.agent/retire-own-branch!`, `seon.cluster.host`) is the fact that makes the writer refuse later turns.
- *The next task is a new agent.* Already how `seon.competency/launch!` and chain run 2 work (a fresh id per run);
  nothing in the writer requires it.

## Data first

Everything the regenerated context is built from is already stored; one fact is added.

| Source | Stored (entity, attributes) | Derived at the call |
|---|---|---|
| The agent | `:seon.agent/id`, `:seon.agent/branch`, `:seon.agent/settings`, `:seon.agent/admission-closed-tx` | its namespace: the `:seon.sci.eval/ending-ns` of its latest evaluation, else the task namespace |
| Its task | the task entity at the home (`seon.task/home`), the assignment (`:seon.task/assignment`) | `seon.context/held-task`, `:seon.task/status` (`:working :done :capped`), `seon.context/proofs` from the test runner's latest results |
| Its evaluations | `:seon.cluster.eval/receipt` (`:seon.cluster.eval/source`, `:seon.eval/shown`, `:seon.eval/shown-tokens`, `:seon.cluster.eval/error`, `:seon.cluster.eval/executed`, `:seon.sci.eval/ending-ns`, `:seon.cluster.eval/run`) | the exchanges, in agent order (`seon.eval/of-agent`) |
| The definitions it touched | branch head: `:seon.fn/sym`, `:seon.fn/source`, `:seon.fn/definition-digest`; `:seon.test/sym`, `:seon.test/source` | the working set |
| **New**: the definitions an evaluation showed | `:seon.cluster.eval/shown-declarations`, a set of `[sym digest]`, written in the one recording transaction when the evaluation's value satisfies `:my.program/declaration-source` (the value of `my.program/source`) or the doc map of a program symbol | whether a history entry refers to the working set, and whether the definition changed since it was shown |

The new attribute is named at its producer, the recording step in `seon.turn` (`receipt-terminal-attributes`), beside
`:seon.cluster.eval/executed`, which already records `[sym digest]` for definitions entered. It is selected by the
value's declared schema, as the render law selects renders, never by parsing the form's text. Context is rendered live
and never stored; an attempt keeps its basis (`:seon.ai.attempt/basis-t`), so what a call saw is derivable again.

Schemas reused: `:seon.context/current-state`, `:seon.ai/exchange`, `:my.program/declaration-source`,
`:seon.cluster.eval/executed`, `:seon.task/status`, `:seon.message/message`. New names, each a new concept at its
producer: `:seon.cluster.eval/shown-declarations` (above), `:seon.context/working-set` and `:seon.context/namespace`
(sections of the block, `seon.context/current-state`), `:seon.task/ended` (the end-of-task message's value, below).

## The rendering rule for history

Each exchange keeps the agent's forms and their short results, exactly as `seon.ai/messages` lays them out today. Two
kinds of entry change:

1. **An evaluation that showed a declaration** (its `:seon.cluster.eval/shown-declarations` is non-empty): the form
   stays (`(my.program/source 'seon.sci.forgive/scan)`), and the reply becomes the reply map without its `:value`,
   plus one data key naming where the definition now is:
   `#:seon.repl{:result result/e…, :working-set [seon.sci.forgive/scan], :ms 12}`.
   If the current digest differs from the one shown, the key says so: `:working-set [seon.sci.forgive/scan :changed]`,
   the same mark `:seon.repl/changed-since?` makes today; `seon.repl/response-entries` gains the key and the entry
   order holds it after `:result`.
2. **An evaluation that entered a definition** (`:seon.cluster.eval/executed` non-empty and the form is a `defn`,
   `deftest` or `def`): the form is replaced by its head, `(defn scan …)` printed by the value printer's elision
   (`seon.print/form-text` of the symbol with the body cut), and the reply keeps its `:result`, `:ms`, errors and
   findings. The whole current body is in the working set; the agent's message no longer carries a 10 kB paste on
   every later call.

**The working set** is a section of the block, `:seon.context/working-set`: one entry per symbol in the union of every
evaluation's `shown-declarations` and `executed`, in first-touched order, each the declaration as `my.program/source`
answers it now on the branch (`:sym :kind :source :contract :file :line`, rendered by `my.program/render-source-ai`,
the code an agent can copy into a `defn` unchanged), with `:changed-since-shown true` when its digest differs from the
one any earlier read showed. A definition deleted since (`my.program/ns-unmap!`) is one line, `:deleted`. The block is
last in the prompt (today's placement), so the working set sits nearest the model's turn.

What the model sees when a definition changed under it: the old read's reply says `:working-set [sym :changed]`, its
own `defn` is a head with its result, and the working set holds the current body once. Nothing says what the old body
was; `(my.program/history 'sym)` is the requery, offered on the changed entry as `:seon.render/operations`.

**Effect on the two runs (estimated from their data).** Run 1's final prompt 16.9k → about 12.5k (help and block
2.3k, working set 4.1k, the three `slurp`s stay as ordinary values 2.9k, forms and short replies about 3k); the
`slurp` copies are not declarations and are the agent's own doing, which the probing help line below addresses.
Run 2's final prompt 50.3k → about 20k (2.3k, working set about 11k since `scan`, the proofs and the two helpers appear
once, 77 form heads and short replies about 5k, `dir` 1.5k). Across run 2's 30 calls the replayed shown text falls
from 325k to about 100k of 845k prompt tokens. The provider cache (87% of run 2's prompt tokens were cached) keeps
paying for the stable prefix either way; what the model attends to shrinks by half.

## Two more context frictions (coordinator, 2026-10-10)

**The agent re-enters its namespace on almost every call** (13 of 15 and 9 of 14 `in-ns` forms were already there).
The block carries no namespace: `seon.context/current-state` has task, proofs, must-reads, cost, unread and turns;
`seon.repl/response-entries` shows `:ns` only when a form changed it (`seon.repl-test/the-namespace-appears-only-when-the-form-changed-it`),
so after call 1 nothing in the regenerated context states where the agent is, and the help's one example starts with
`(in-ns 'the.task.ns)`. Fix at the producer: the block gains `:seon.context/namespace`, the agent's current namespace
derived from its latest evaluation's `:seon.sci.eval/ending-ns` (the fact already stored), shown as
`:seon.context/namespace seon.sci.forgive` in the turns section; the help line becomes "Your block names your namespace;
define a function in the namespace that owns it: (defn increment …)" with `in-ns` only for a change. No re-entry is
ever needed because the agent's SCI context keeps `*ns*` across calls (`seon.sci.eval/enter-namespace!`).

**Body fragments evaluated as forms** (`(at-code! o line)` and eight more, all locals of `seon.sci.forgive/scan`).
The model read a 250-line function and tried to step through it; the REPL has no way to run a body fragment, and the
help teaches `doc`, `source` and `dir` but not probing. Smallest fix, one help line: "A function's body is one form:
call it on inputs, (seon.sci.forgive/forgive \"a]a\" :code), or redefine it on your branch with a probe inside; a line
of its body is not a form." No tool: a stepping or `inspect` tool is machinery with one friction behind it, and the
whole-definition redefinition the owner accepted is the REPL way. The five failed `defn- scan` pastes are the other
half of plan §0 friction 2 (an edit over a definition's source), decided by the owner separately.

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
  is spent (`:capped`), or the agent gives it back. Give-back is `my.turn/complete` with a required
  `:my.turn/result` naming the reason (the disposition that exists), recorded on the turn; `my.task/complete!` in
  AGENTS.md is this call under the task's name, added only as an alias when the agent-loop cut lands.
- *The end closes the agent*: the settlement that observes the end (the turn's close in `seon.turn`, one writer)
  asserts `:seon.agent/admission-closed-tx` on the agent, the fact `seon.cluster.host` and `seon.turn`'s run writer
  already refuse later runs by. The branch is kept for root's review and deleted when the merge request closes
  (today's `my.agent/retire-branch!` from `seon.competency/launch!` or root).
- *The end notifies root*, once (the stage below).
- *A question to a finished agent is answered, not worked*: with admission closed no model turn opens, so root's
  question goes to the record instead: `my.message/send` to a closed agent is answered by the agent's final context
  rendered once more (its block with the working set and the end summary) as the reply to root, through the message
  path, with no model call. Reflection on resume (a model call that answers only, with a one-call bound) is the second
  automation step of the loop below, not this card.
- *One task per agent, at the writer*: `seon.task/assign!` refuses an agent that already has an assignment
  (`:seon.task/agent` present), with the fix "a new task is a new agent id". Root (`"root"`, the MCP default) is the
  orchestrator and holds no task, so it is unaffected.

**What is deleted**: the wake-reopens-a-done-agent path in `seon.turn/next-agent-work` (the `(seq wakes)` arm for an
agent whose admission is closed), and `seon.competency/run-state`'s own reading of done/capped once the agent's closed
admission is the fact it polls.

### Stage E: one notification to root when a task ends (owner, 2026-10-10)

"I want root to be notified on agent errors that occur during a task (at the end when they've hit their turn limit or
completed early) and I want to prompt root to identify problems with the task and to refine it if it is not a throwaway
task." Checked against the code: the message path exists (`seon.cluster.message/send!` through `my.message/send`,
durable in the agent's transaction, delivered into root's next context as `:seon.context/unread` and handled at the
turn's close, `seon.turn/close-call`); the grade exists (`seon.competency/grade`: proofs, calls, errors, tokens); the
error signature exists (`seon.error/signature`, the grouping lane `agent-error-signatures` is building over
`seon.problems`); a task's file is not a stored fact (`seon.task/task-files` reads `resources/seon/tasks/<ns>.edn` at
ingestion and the entity keeps no path), and write-back of a task file does not exist (plan §8 Q2, option (a)).

The stage: when the settlement closes the agent (above), it also writes one message to root whose content is a value,
`:seon.task/ended`, holding references only: the task (`:seon.task/sym`, its handle), how it ended (`:done :capped
:given-back` and the reason), the proofs' final state (`seon.context/proofs`'s counts and each red proof's symbol),
the agent's errors grouped by signature with counts and the distinct error values' handles (the lane's grouping, reused,
never duplicated), calls used and prompt, cached and completion tokens (the same reads `seon.competency/grade`
makes), and the agent and branch. Its AI render ends with the prompt and the offered operations: "What was wrong or
missing in this task: intent, must-read, proofs or context?" and `(seon.task/create! {…the same declaration with the
change… :seon.task/basis <revision>})`, the upsert that already keeps history (`seon.task/create!` with `:seon.task/basis`;
the file write-back is Q2's open item and is named, not built, here). A throwaway task gets the notification without
the review prompt: derived, not authored, as a task whose `:seon.task/ns` file in `resources/seon/tasks` holds no entry
of its name (the bench's `seon.competency/*` tasks are file-backed, so the derivation is "runtime-created", which fits
`my.task/own-task`'s `:work` tasks and probes; see Q3 below if that is wrong).

Proof, red first: `seon.turn-continue-test/a-task-that-ends-sends-root-one-ended-message` (a fixture agent whose proof
goes green, one capped, one given back; asserts on root's inbox: exactly one message per agent, its content value's
`:seon.task/status`, the proof counts and the error-signature count; a second wake to the closed agent opens no turn and
root's follow-up question is answered with the agent's final context, asserted on the message, not on text). Files:
`src/seon/turn.clj` (the close), `resources/seon/schemas/seon.task.edn` (`:seon.task/ended`), `src/seon/task.clj` (the
value's render), `test/seon/turn_continue_test.clj`.

## The loop this enables (owner, 2026-10-10: "a recursive self improvement loop")

The lifecycle stages are the first links; the whole loop is a follow-up card.

1. **Work**: a fresh agent takes a task with red proofs from generated context (exists: `seon.task/assign!`,
   `seon.competency/start-run!`, this card's stages 1–4).
2. **End**: one notification to root with errors by signature, final proofs and calls (stage E; reuses the message
   path, `seon.competency/grade`'s reads, the lane's signature grouping).
3. **Reflect**: root's question to the finished agent is answered and recorded, not worked (stage 6 answers from the
   record; a one-call model answer on resume is the second automation step; missing: a bound for answer-only turns).
4. **Diagnose**: each problem becomes a task with a red proof at its producer (context, tool, reader or the task
   itself), and durable tasks are refined in place (exists: `seon.task/create!` upsert with history; missing: task-file
   write-back, Q2; a reviewer agent that drafts the diagnosis tasks for root to approve is the third automation step).
5. **Fix**: new agents work those tasks and root merges (exists: the chain, `bin/lane commit`, root's merge).
6. **Measure**: the same task set reruns and `seon.competency/grade` and `table` (green share, calls to green, errors by
   signature, tokens) are compared with the previous round; only a better grade counts (exists except the by-signature
   column and a stored previous round to compare against; `grade` gains `:seon.competency/replayed-tokens` and
   `:seon.competency/errors-by-signature`).

Recursive because 4 and 5 improve what 1–3 use. Owner rule: run it by hand once more before automating, in the order
end notification → reflection on resume → reviewer agent.

## What disappears

- BENCH-9 (history window dial) is moot: history shrinks structurally; nothing to drop or to name as dropped. BENCH-7
  (block placement) stays unbuilt: the block is last, and the working set belongs there.
- `:seon.repl/changed-since?`'s special case ("full value" line for a changed value) becomes the general working-set
  mark; its two render branches in `seon.repl/input-text` and `seon.repl/reply-text` collapse into the one key.
- The wake arm that reopens a done or capped agent (`seon.turn/next-agent-work`), and `seon.competency/run-state`'s
  own end derivation.
- `(in-ns …)` as the taught first step in the help; the `slurp`-the-file habit has no help to delete, only the probing
  line to add.
- Not deleted but noted: `:seon.config.run/max-episode-runs` stays as the safety cap that makes `:capped` an end.

## Stages, about 15 minutes each, in order

Each names its red proof (asserting on data, in the owning test namespace) and its files, so root can run disjoint
lanes. Lane `agent-runs-proofs` holds four paths since 4027882d4 (proofs runnable at the REPL, red data in the block);
its paths are not visible in `bin/lane status`, so stages touching `src/seon/context.clj` or `test/seon/context_test.clj`
wait for its release or go to it as a patch. The loop works crudely after stages 1–3 (namespace line, the recorded
fact, the working set), so chain run 3 can use it; stage 4 removes the repetition, stages 5–6 the lifecycle, E the
notification.

1. **The block names the agent's namespace.** `:seon.context/namespace` in `seon.context/current-state` from the latest
   evaluation's `:seon.sci.eval/ending-ns`; schema in `seon.context.edn`; help line changed (`seon.bootstrap/help-value`)
   and the probing line added. Proof `seon.context-test/the-block-names-the-agents-current-namespace` (an agent whose
   last form ended in `seon.sci.forgive` shows it; a fresh agent shows its task's namespace) and
   `seon.help-test/help-teaches-probing-not-in-ns` (asserts on the lines data). Files: `src/seon/context.clj`,
   `resources/seon/schemas/seon.context.edn`, `src/seon/bootstrap.clj`, `test/seon/context_test.clj`,
   `test/seon/help_test.clj`.
2. **The evaluation records the declarations it showed.** `:seon.cluster.eval/shown-declarations` written in the
   recording transaction when the value satisfies `:my.program/declaration-source` (and `doc` of a program symbol),
   as `[sym digest]`. Proof `seon.repl-test/a-source-read-records-the-declaration-it-showed` (one `my.program/source`
   evaluation through the one path; asserts the set holds the symbol and the branch's current digest; `(+ 1 2)` records
   none). Files: `src/seon/turn.clj` (`receipt-terminal-attributes` and the settlement), `resources/seon/schemas/seon.cluster.eval.edn`,
   `test/seon/repl_test.clj`.
3. **The working set in the block.** `seon.context/working-set`: the union of `shown-declarations` and `executed` over
   the agent's evaluations, each the current declaration once with `:changed-since-shown`. Proof
   `seon.context-test/the-block-shows-each-touched-definition-once-and-current` (read `scan` twice, redefine it, read it
   again: one entry, the current source, marked changed; a deleted one shows `:deleted`). Files: `src/seon/context.clj`,
   `resources/seon/schemas/seon.context.edn`, `test/seon/context_test.clj`.
4. **History refers instead of repeating.** `seon.context/exchange-form` drops the `:value` of a declaration-showing
   reply for `:working-set [sym]` (`:changed` when the digest moved) and replaces a definition's form with its head;
   `seon.repl/response-entries` gains the key and `changed-since?` folds into it. Proof
   `seon.context-test/history-refers-to-the-working-set-instead-of-repeating-source` (asserts on the exchange data: the
   second read's reply carries `:working-set` and no source text; the `defn` form is its head) and
   `seon.repl-test/a-changed-value-is-the-working-set-mark`. Files: `src/seon/context.clj`, `src/seon/repl.clj`,
   `test/seon/context_test.clj`, `test/seon/repl_test.clj`.
5. **One task per agent, at the writer.** `seon.task/assign!` refuses `:already-assigned` for an agent with an
   assignment. Proof `seon.task-ingestion-test/an-agent-holds-one-task-ever` (second assignment refused by name with the
   fix; a second agent id takes it). Files: `src/seon/task.clj`, `resources/seon/schemas/seon.task.edn`,
   `test/seon/task_ingestion_test.clj`.
6. **The end closes the agent and answers questions from the record.** The close asserts `:seon.agent/admission-closed-tx`
   on `:done`, `:capped` or give-back; the reopen arm is deleted; a message to a closed agent is answered with its final
   context. Proof `seon.turn-continue-test/a-done-agent-opens-no-turn-and-answers-from-its-record` (fixture agent goes
   green; a later wake opens no turn; the reply message to root carries the block value). Files: `src/seon/turn.clj`,
   `test/seon/turn_continue_test.clj`, `test/seon/competency.clj` (`run-state` reads the closed admission).
7. **Stage E, the notification** (above): files `src/seon/turn.clj`, `src/seon/task.clj`, `resources/seon/schemas/seon.task.edn`,
   `test/seon/turn_continue_test.clj`; depends on stage 6 and on lane `agent-error-signatures`' grouping function.
8. **The grade measures repetition and errors by signature.** `seon.competency/grade` gains `:seon.competency/replayed-tokens`
   (the per-call sum this card measured by hand) and `:seon.competency/errors-by-signature`; `table` folds them. Proof
   `seon.competency-grade-test/a-grade-counts-replayed-tokens-and-errors-by-signature`. Files: `test/seon/competency.clj`,
   `test/seon/competency_grade_test.clj`.

Stages 1 and 2 are file-disjoint from 5; 3 and 4 follow 2 and share `context.clj`; 6, 7 share `turn.clj` with 2 and
follow it.

## The simplest alternative considered

Keep the transcript and add BENCH-9's history window (drop the oldest N exchanges). It is one dial and one line, and
it would have cut run 2's prompt. It is not enough: it drops the agent's own reasoning and results indiscriminately
while keeping the three identical `scan` copies inside the window, it shows a stale body after a redefinition with no
mark, and it answers neither the lifecycle nor the end notification. The second alternative, deduplicating by shown
text digest (`:seon.eval/value-digest`) without recording the declaration, removes identical copies but cannot say
which definition changed or show its current body, so the agent would still paste from a stale read. Recording
`[sym digest]` once at the producer, as `executed` already does, is the smaller design.

## Open questions for the owner

1. **Should a definition's head stay in the agent's own message, or the whole form?** Options: (a) head only, body in
   the working set (recommended: the body appears once, current); (b) the whole form stays and only read replies are
   referenced (simpler, but a 10 kB paste repeats on every call and goes stale on the next redefinition).
2. **Give-back before `my.task/complete!` exists.** Options: (a) `my.turn/complete` with a required reason is the
   give-back (recommended now; it exists and the turn records it); (b) add `my.task/complete!` in this card (one more
   name before the agent-loop cut names it).
3. **What is a throwaway task?** Options: (a) a task created at runtime with no entry in `resources/seon/tasks/<ns>.edn`
   (recommended: derived from the file the ingestion already reads, so the bench tasks are durable and probes and
   `:work` tasks are throwaway); (b) an authored flag (refused by "derive, don't stamp"); (c) every task gets the review
   prompt (noise on every bench run).
4. **Answering root's question to a finished agent.** Options: (a) from the record only, no model call (recommended
   first: zero cost, and the final context with the working set already answers "what did you see"); (b) one bounded
   answer-only model call on resume (the loop's second automation step); (c) reopen the agent (what happens today,
   refused by the owner's ruling).
5. **Task-file write-back for the refine operation** (plan §8 Q2 (a)): build it in the follow-up card or let root edit
   the EDN by hand after the first hand-run? Recommended: hand first, per "run it by hand once more".
