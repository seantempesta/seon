---
title: "TASK-CHAIN: tasks that improve as they move through a pipeline of agents"
type: card
status: draft for review (root, 2026-10-11); Fable then Astra review in sequence, then owner discussion
created: 2026-10-11
---

# TASK-CHAIN

What exists: tasks are entities (`seon.task/create!` upserts with `:seon.task/basis`; history is Datahike's), their
proofs are tagged deftests, an agent's context is rendered live from its task by `seon.context/current-state`, a task's
end sends root one message (DYNAMIC-CONTEXT stages 1–3) and a green end lands automatically (ROOT-AGENT stages 1–2).
The approach: one general mechanism (a task value, a list of stage types as data, one context-generation function) so
pipeline variations are data, run side by side and measured, rather than code.

## 1. The idea

Software work is a chain of stages, each worked by a fresh agent whose only context is the task as the previous stage
left it, rendered by one function. Each stage reads the task, does its work, and **edits the task**: it adds what it
found, removes what misled it, and rewrites what it now understands better. The task is the handoff. The next agent's
work depends mostly on the quality of that task, above all on its references: removing a reference to the wrong function
or schema, or adding the right one, changes what the next agent reads and does.

Owner (2026-10-11): build it generally, then experiment with variations step by step and keep the steps that make the
task better at each stage, as many as are worth their model calls.

## 2. The model

**The task is one evolving entity.** Every stage may update any field. Datahike keeps every version, so nothing is
lost and every change is attributable to the stage and agent that made it (transaction provenance). Fields:

| Field | What it holds | Usually edited by |
|---|---|---|
| `:seon.task/intent` | what must become true, typed (fully qualified symbols and keywords) | every stage refines it |
| `:seon.task/must-read` | the references the next agent reads first | every stage adds and removes |
| `:seon.task/evidence` | reproduction forms, values, paths | mine, survey |
| schema keys | the data the work produces and consumes | survey, model |
| proofs | deftests tagged with the task | prove, check |
| `:seon.task/notes` (new) | one line per edit: what changed and why ("removed `seon.foo/bar`: not on the data flow") | every stage |
| `:seon.task/stage` (new) | the stage type now working it | the pipeline, not the agent |
| `:seon.task/instructions` (new) | the stage's instruction template, rendered | overwritten by the pipeline per stage |

**The pipeline overwrites a few fields between stages; agents own the rest.** When a stage is done, the pipeline sets
`:seon.task/stage` to the next stage type and `:seon.task/instructions` from that type's template, assigns a fresh agent,
and nothing else changes. Agents may edit any other field.

**Stage types are data.** A stage type is an entity: its name, its instruction template, its done-check (a function over
the task value), its next stage, and where it may send work back. A pipeline is a vector of stage types. A variation is a
different vector, a different template, or a different done-check — all data, so variations can run side by side on the
same task family and be compared.

**One context function.** Every agent's context comes from `seon.context/current-state` over its task, so changing the
task changes the context with no new code. Order it for caching, most stable first:
1. the laws excerpt and tool help (stable across all stages);
2. the stage's instructions (stable across all tasks at that stage);
3. the task: intent, must-read resolved to docs, sources and schemas, evidence, proofs with their current red data;
4. the agent's own working set and history (DYNAMIC-CONTEXT).

Byte stability buys cache hits; correctness of section 3 is what matters most.

## 3. The stages (the first pipeline to try)

Each has a template, a done-check expressed as data, and what it typically adds, removes and modifies.

0. **Mine** (often a query, sometimes the owner): the reproduction form, evidence values, the task kind. Done when the
   reproduction shows the bad behavior on main and no open task duplicates it.
1. **Survey**: search what exists (`my.program/search`, `callers`, `reads-key`, `source`, docs); name the owner and the
   data flow (producers, consumers, keys); sketch the change and how it can be tested. Adds must-read, data-flow keys,
   reuse candidates; removes references off the flow; rewrites the intent from symptom to change. Done when every
   reference resolves and the intent names the owner and the change in typed symbols.
2. **Model** (skipped when no schema is new): register missing schemas at their producers, declare consumers, generate
   samples, validate real values, measure validation cost; the samples and cost are shown and the agent confirms they
   are what it means. Done when schemas compile, real values validate and cost is within budget.
3. **Prove**: an `are` table holding the reported input; a seeded property where a one-line law exists (samples and
   runtime shown); one `is` on a count for a cost task; update every existing test that asserts the old behavior.
   Proofs read the program, never files, and cover every change the intent names. Done when the proofs are red on main
   for the stated reason and cheap to run.
4. **Check proofs** (a separate, short agent): do the proofs encode the intent, are they provable on a branch, red for
   the right reason, cheap? Sends back to Prove with a reason, or passes.
5. **Implement**: the simplest body that turns the proofs green with no known break to a reached test. May send back to
   Prove (or Survey) with data when a proof or the intent is wrong; never patches around it.
6. **Land** (automatic): gate, merge, write-back, git commit. A refusal routes back: a break to Implement, a wrong proof
   to Prove.
7. **Review** (root, later the root agent): reads the end summary (proofs, errors by signature, calls, send-backs);
   green proofs on main retire the task automatically; files follow-ups; and edits the instruction template of the
   stage that caused any friction. This is the self-improvement step: templates get better round over round.

**Send-back** is a stage transition like any other: the sending agent writes the reason in `:seon.task/notes`, the
pipeline sets the earlier stage, a fresh agent picks it up with the notes in context.

## 4. Proofs: the weak point, and a catalogue

This weekend most failures traced to the proof or the task, not the implementer: a census that read files (unprovable
on a branch), an intent naming one of two changes, "keep the contract" on an uncontracted function, reached tests
asserting ruled-out behavior. Keep a **proof catalogue** page of good and bad proofs with why, seeded from
`docs/research/agent-platform/2026-10-10/designed-task-examples.md` (a pure transform with a seeded law; a read doing
whole-program work with a count budget; a write path with storage cost) and this weekend's green proofs (`slug`,
`edn-symbol-text?`, the search snippet, refined `ref-section`) and failures. The Prove and Check templates cite it.

## 5. Measurement and experiments

Per stage: calls, tokens, errors by signature, send-backs, time. Per task: did the next stage succeed without sending
back; did Implement go green; did it land. Per variation: the same task set through pipeline A and pipeline B, compared
in one table (`seon.competency/table` grows a stage column). Keep a step only if it pays for its calls: fewer
downstream send-backs, fewer implementer calls, more green landings.

Questions each experiment answers: does Survey's reference pruning cut Implement's calls? Is Check-proofs worth a
separate agent? Should Survey and Model merge? Which template edits from Review actually helped?

## 6. What must be built (smallest first)

1. `my.task/refine!`: an agent edits its task's fields at its REPL (over `seon.task/create!`'s upsert with
   `:seon.task/basis`), recording a note per edit. The database is the task's source of truth; files are written back
   in batches. (Today there is no REPL face; this is the missing handoff medium.)
2. Stage types and pipelines as entities (`:seon.task/stage`, instruction templates, done-checks as named functions).
3. The transition: when a stage's done-check holds at the end of an agent's work, set the next stage, render its
   instructions, assign a fresh agent (`seon.task/assign!` already runs the proofs in the agent's world).
4. Auto-retire: a task whose proofs are green on main retires itself.
5. `current-state` renders the stage instructions and the task sections in the stable order above.
6. The proof catalogue page; first templates drafted from it and this weekend's evidence.
7. The per-stage measurement columns.

## 7. Open questions

- Is Check-proofs a separate agent or the start of Implement?
- Survey and Model separate or one Design stage?
- May Implement send back by itself, or only through root?
- Done-checks: which are pure data predicates, which need a judgment call (a short model call)?
- How much of the laws excerpt and help belongs in the stable prefix for each stage?
- How are pipeline variations assigned (per task family, random, round-robin) so comparisons are fair?

## 8. Reviews

(Fable, then Astra, append their improvements below; root and the owner discuss.)
