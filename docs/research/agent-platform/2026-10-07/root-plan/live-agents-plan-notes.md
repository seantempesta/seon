# Live agents: one loop, deleted down to what an agent needs (root's plan, for owner approval)

Status: PROPOSAL. Nothing in it is being implemented. Once the owner approves it, it replaces
`docs/seon/specs/agent-loop.md` in place: no second plan, and lanes work only from it.

## 1. Principles (owner rulings, 2026-10-07)

- **The agent is a REPL.** There is ONE path: a parser (read) → eval (SCI) → record (one transaction) → the value
  printer. Never a second parser, evaluator, printer, store, cache or loop, and never a separate harness.
- **Context is forms that ran.** It opens with `(help)`, the task's `doc` and the `doc`/`source` of each
  reference, generated from the task's data, followed by every form the agent sent, appended with its real
  result. It is rebuilt from the database on each call, and `seon.cache` makes the unchanged parts free; anything
  that can be rendered is never stored. A system prompt, if it measurably helps, carries exactly `(help)` and
  nothing else.
- **Lookups use standard Clojure:** `doc`, `source`, `dir`, `apropos` and `find-doc`, returning data and built on
  SCI's `clojure.repl`.
- **The reader:** fenced code is code; a form that starts a line is code; everything else (prose, Markdown) becomes
  a comment; text that imitates a result is never evaluated and is flagged back to the agent.
- **No invented results.** Agents have written forms and then made up their results. The stop sequence and the
  reader together prevent it, and the prevention is proven live.
- **Done means proof tests are green.** `my.task/complete!` exists only to bail out, with a reason. A task needs its
  proof tests before it is launched. Outside runs never enter an agent's history.
- **Delete what is slated before adding anything.** Reuse libraries. Understand before designing. Work on one
  happy path. Do not rush.

## 2. The loop today (observed 2026-10-07, default on `722f110df`)

The evidence: the one real model turn lane loop-pass captured
(`loop-pass/runs/00-installed-turn-lp-add-2/`), the stored facts on that agent's branch `:agent-4512788dda3b`,
and `src/seon/turn.clj` read from the driver down.

**Driver.** `seon.turn/step` (turn.clj:5789) is the agent proc's Flow transform. It does all of this:
- waits on a completion permit (`await-turn-permit!` :5600);
- arms a backstop thread (`arm-turn-completion-backstop!` :5731);
- counts consecutive refused writes and parks the agent at a bound (`write-refusal-bound` :5650,
  `record-write-refusal-fault!` :5691);
- derives work (`next-agent-work` :2833);
- runs `turn` (:5450), which dispatches on a "situation": `:open`, `:call`, `:generate`, `:resume` or `:close`;
- rewakes itself.

**One model reply takes several passes and 5-6 commits.**

| Turn | Commits | Time (provider included) |
|---|---|---|
| `76fb76c15e95` | 6: `open-call`, `plan-call`, 2 × `transact!`, `program-facts-call`, `error/commit-call` | 2.9 s |
| `5908ecba9ec4` | 5 | 2.7 s |

Each model turn is preceded by system-turn "refresh" runs of one commit each.
1. `open-turn` (:4282) runs `system-turn` (:2030, `:write? true`): the generated opening reads ("changed since your
   last turn": agent pull, steps, messages, settings, `dir`, runtime pull, `seon.error/for-agent` at 98,051 armed
   calls). Then a separate `open-call` commit.
2. `call-turn` (:4359):
   - renders the prompt through the old multi-hop walk (`seon.cluster.prompt/prompt` :422 → `render/acquire-context!`)
     and commits the context capture;
   - calls `seon.ai/complete` with ONE prompt string and no system message;
   - commits an attempt row, then an intent commit (`plan-tx`) holding the raw reply and result-less evaluation rows.
3. `resume-turn`/`evaluate-sources` (:4857): the ONE fold `seon.batch/evaluate-batch`. Read (`read-row` :4663 via
   `seon.sci.forgive`), eval (`evaluate-row` :4700 → `sci.eval/evaluate`), show (`show-row` :4728 → the value
   printer), then the settlement commit.
4. `close-turn` (:5238): a close commit.

**What the agent saw.** A REPL transcript (`ns=> form`, then the real `#:seon.repl{…}`) interleaved with ten
"changed since your last turn" refresh units:
- root's setup runs made as the agent, including `seon.agent/settings!`;
- a 1,241-token error list carrying a Seon core fault (`seon.sci.eval/shown-failure` got nil from
  `seon.turn/show-row`);
- "turns left".

**What the model did.** In its first reply it wrote `scratch.add2=> (defn …)` AND a fabricated `#:seon.repl{…}`
result inside a code fence, then declared the task done. The reply reader strips prompt markers
(`planned-sources` :3031, ":reply mode: prompt markers go"), so the defn ran in `user`, not `scratch.add2`. Its
`clojure.test/run-test-var` could not resolve the test var from SCI. The shipped stop `user=>` would not have cut
the fabrication, because the prompt is namespace-specific (`scratch.add2=>`). The old stop `#:seon.repl` would
have.

**Related facts from today's lanes.**
- Bare `source`, `apropos` and `find-doc` don't resolve.
- `(dir ns)` costs 46-51 ms and about 9,000 armed calls to compute contracts that its render throws away.
- Two readers of a reply (segments, then a re-read in `:reply` mode).
- Two settlement writers (`seon.turn` and `turn.batch`).
- A Seon core fault is shown to an agent as its own error.

## 3. Phase 1: delete what is slated

Each deletion converts every caller in the same commit and keeps HEAD bootable, with eval_clj/MCP and the tests
working. The model pass keeps working throughout: deleting the old context and adding the forms context is one
slice.

| # | Delete | What replaces it |
|---|---|---|
| D1 | the old multi-hop context: `seon.cluster.prompt/prompt`'s walk, `render/acquire-context!`, `render.walk/history`, `render.web/derive-context!` and the debug context views, the context capture (`seon.context`), the system turn and its generated opening (`system-turn`, `declared-sources`, `generate-turn`, `append-generated-*`, `system-plan`), and the read evidence only the refresh read | `context`: the opening forms plus the agent's own evaluations, read from the database and cached (§4). The debug ledger reads `eval/of-agent`. |
| D2 | run machinery: `open-call`/`open-turn`, `next-id`, `plan-call`/`plan-tx` (the intent commit), `close-turn`/`close-tx`, `recover-tx`/`recover-call`, `close-escaped-run!`, the situations dispatch | one pass: context → provider → fold → ONE settlement (§4) |
| D3 | the completion permit and backstop (`await-turn-permit!`, `arm-turn-completion-backstop!`, `turn-completion-*`, `record-turn-backstop-fault!`), write-refusal parking (`write-refusal-*`), `agent-dial`, `episode-exhausted!`, turns-left/episodes, `opening-deferred?`, `unanswered-*`, `my.agent/done` disposition, `record-interruption!` | three bounds only: provider timeout, SCI time limit, model calls per wake. Disarm joins the transform under the caller's deadline. |
| D4 | the second settlement writer (`turn.batch/settle`'s run-shaped `record-facts`) | the one settlement owner, used by MCP, JVM and test entrants too |
| D5 | the second reply read (segments, then `:reply` re-read) | one read (§4) |
| D6 | duplicate lookup machinery (`directory-value`, `program-documentation`, the second refer installer, `my.program/source`) | the standard lookups on SCI's `clojure.repl` |

Spec §5's estimate: about 3,500 lines out, 550 in. Each slice reports its measured net.

## 4. The loop after (one pass)

```
wake (an outside message, or the agent still working with calls left)
  → context  = [(help)] [(doc 'the/task)] [(doc ref) / (source ref) …] [agent's own forms + their shown results]
                every opening form run through the one path, values cached by definition digest; history read
                from the agent's stored evaluations (origin :model; outside runs excluded)
  → seon.ai/complete (system = (help)'s text only if experiments show it helps; stop = the result marker)
  → read   ONE reader: fences are code, a form that starts a line is code, the rest are comments,
           imitated results are flagged and never run
  → eval   SCI, in the agent's world, in its current namespace (carried on the agent)
  → record ONE transaction: evaluation rows + the provider call row + agent facts (answered-t, current-ns, ordinal)
  → print  the value printer, per form; the shown text IS the history the next context appends
  → completion: if the forms changed code the task's proof tests reach, run them (my.test/check); all green →
           the task is complete and its creator is notified
```

**One presentation: pure data (owner, 2026-10-07).** We control the context. It is ONE value, rebuilt each call:
a vector of exchange maps, one per form, each holding its namespace, form, outcome and handle together. It is
printed by the one value printer, with the same budget and dig forms as any value. There are no prompts, no
`;; =>`, and no prose between exchanges. The opening forms are the first maps, and the agent's forms (multi-form
is fine) follow in order:

```clojure
[#:seon.repl{:ns user, :form "(help)", :value {…the protocol as data…}, :result result/e0a1b2}
 #:seon.repl{:ns user, :form "(doc 'scratch.add2/add-one-returns-its-argument-plus-one)", :value {…task…}, …}
 #:seon.repl{:ns scratch.add2, :comment "I'll add and subtract.",
             :form "(+ 1 1)", :value 2, :result result/e1a2b3, :ms 0}
 #:seon.repl{:ns scratch.add2, :form "(- 10 2)", :value 8, :result result/e4d5e6, :ms 0}
 #:seon.repl{:ns scratch.add2, :form "(foo 1)", :error {…the error map…}, :result result/e7f8a9}]
```

- Each map is exactly the reply map MCP returns, plus `:form` and `:ns`, so there is one format everywhere.
- The agent's prose before a form becomes that exchange's `:comment`.
- The agent's own reply is plain code (fences, or forms that start a line; prose becomes comments), so it never
  resembles the transcript.
- A reply that writes an exchange-shaped map (`:value`/`:result` keys) as if it were a result is flagged by the
  reader and never run.
- "Calls left", if shown, is one key on a final map, not prose.

**One machinery transaction per turn (owner, 2026-10-07).** The turn pulls one database value at its start, and
the turn machinery writes ALL of its own record of the turn in ONE transaction at the end: evaluation rows, the
program facts of the turn's `defn`s, the provider call row and the agent facts (current namespace, answered basis,
ordinal). The agent's own writes (`transact!`, messages, task writes) are its actions: each commits when its form
runs, as at a real REPL, and later forms read them normally. A turn whose forms write nothing therefore costs
exactly one commit; each explicit agent write adds its own. A crash before the end loses the turn's record, never
an agent write that already committed.

**Nothing is re-executed (owner, 2026-10-07: "We don't want to keep re-executing forms so we need to cache
intelligently").** The opening forms (`(help)`, the task's `doc`, each reference's `doc`/`source`) are evaluated
ONCE, on the agent's first pass for its task, through the one path. They are recorded as ordinary evaluations with
stored shown text. Every later call's context is the stored exchanges read back (the opening ones, then the agent's
own) in order: one indexed read, nothing re-run or re-rendered. As in a real REPL session, earlier output does not
change. The agent sees change through its own later forms, and re-runs `(doc x)` when it wants fresh documentation.
`seon.cache` speeds up evaluation itself (`doc`/`source` keyed by definition digest, the printer's work) and is
never a second store of context.

**Two presentations, decided by evidence (owner, 2026-10-07).** "I'd prefer if it was closer to a normal REPL
but I want to follow the evidence and ask the agents." Both are small context functions over the same stored
exchanges, chosen per agent by a settings value. Neither is a second system.
- **REPL**, closest to a real REPL. Each form is shown as `ns=> form` and followed immediately by what a REPL
  prints: its value through the value printer, or its error. There is no `;; =>` and nothing interleaved. The
  agent's prose comments sit above the form, as it wrote them.
- **DATA**: the vector of exchange maps shown above.

S6 runs the same micro-tasks under both and measures:
- protocol followed;
- invented results, counted;
- forms that error;
- calls to finish;
- correctness.

After each run, a separate feedback turn asks the agent how the transcript read. If the evidence is a tie, REPL
wins (owner preference). The loser's function is deleted.

Targets: one commit per model reply; Seon's own work per pass under 200 ms beyond the provider; the context
render with no reference changed under 10 ms warm.

## 4b. Budgets: measured on every slice, never exceeded (owner, 2026-10-07)

"Measure everything. DO NOT LET MULTI SECOND, MULTI MB OR GB ANYTHING happen. Period."

The S0 probe measures each stage of one pass, and every slice reports the numbers before and after:
- wall ms;
- allocated bytes (`ThreadMXBean/getTotalThreadAllocatedBytes`, plus per-thread for the pass's own threads);
- armed calls;
- commits;
- bytes written to the store;
- tokens shown.

| Per pass, excluding the provider's own latency | Budget |
|---|---|
| Seon's total work (context read, read, eval overhead, record, print) | < 200 ms |
| allocation | < 5 MB plus what the agent's own forms allocate |
| commits | 1 |
| store bytes written | < 50 KB plus the agent's own data |
| context read for N stored exchanges | O(N) on one indexed read, < 10 ms at N = 200 |

Any operation over 1 s or over 10 MB that is not the agent's own work, or the provider's, is a STOP-AND-FIX
finding: it is fixed at its producer before the slice lands, never scheduled. Today's baseline breaks these
budgets: `(+ 1 2)` allocates about 150 MB, a one-line `defn` 0.3-0.8 GB, and a model turn makes 5-6 commits. The
Datahike planner fix and S1/S4 exist to bring them inside. A slice that makes any number worse does not land.

## 5. Order of work

| Slice | What | Proof | Gate |
|---|---|---|---|
| S0 | **Baseline probe** committed as a test: one model turn with a fixture provider reply (`:seon.ai/send!` as a value), measuring commits, ms per stage and armed calls | test plus numbers in notes | root |
| S1 | **D1 + the forms context** (help as data, the task's doc, references, own history), one slice so the model pass never breaks | the S0 probe: same forms evaluate, the context contains no walk unit, fewer commits | design note → dual review → owner |
| S2 | **The reader (D5)**: one read with the line-start rule and imitated-results flag; the read stage as a swappable function value | table test over live-run replies | design → dual review → owner |
| S3 | **Lookups (D6)**: the repl-lookup design (already written, under review) | table test, one row per kind | dual review → owner |
| S4 | **D2 + D3 + D4**: one pass, one settlement, three bounds | S0 probe: 1 commit per reply, ms per stage | design → dual review → owner |
| S5 | **Completion**: proof tests run after a pass, `complete!` for bailing, `assign!` refuses tasks with no proof or already green | tests on data | design → dual review |
| S6 | **Live experiments on DeepSeek**, unlimited usage, through the real loop. Variants are swappable read and context functions picked by agent settings (data). Measured: protocol followed, invented results, calls to finish, correctness. A separate feedback turn after each run. Results stay in the database. | transcripts plus tables | owner reads the results |

Datahike stability (fork fixes for wrong answers, then the gitlink bump) and the one approved reset run before S1,
so the slices work on a stable store. The `dh-fix` work is preserved in `stopped-wip/dh-fix-fork.patch`.

Every slice: understanding first (owners read and traced at the REPL), a design note before code, the smallest
diff, a net-lines count, before/after numbers on the S0 probe, a dual review of the diff, then root restarts
default.

## 6. Decisions for the owner

1. The guard against invented results, with the data transcript: the agent's reply is code and the transcript is
   data, so the formats differ. The reader flags exchange-shaped maps. A stop sequence is likely unnecessary
   (none shipped); S6 measures invented results on DeepSeek, and adds a stop only if they occur.
2. History is "all of it, unclipped, for now". Confirm that this means each stored evaluation's shown text exactly
   as the agent saw it (nothing re-rendered).
3. Approve this plan as the replacement for `docs/seon/specs/agent-loop.md` and the order S0 → S6.
