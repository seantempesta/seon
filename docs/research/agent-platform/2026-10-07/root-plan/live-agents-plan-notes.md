# Live agents: one pass, deleted down to what an agent needs (plan v2, for review)

Status: DRAFT v2, written from the five research notes in `../plan-research/`. It goes to a dual review, gets
revised, and then goes to the owner. Once approved it replaces `docs/seon/specs/agent-loop.md` in place. Numbers
below are measured on default unless marked otherwise; `r-*` names the research note that holds the evidence.

## 1. Principles (owner rulings, 2026-10-07)

1. **The agent is a REPL, and there is ONE path.** One read → eval (SCI) → record → the one value printer. One
   cache (`seon.cache`) and one loop. No second parser, evaluator, printer, store, cache or harness.
2. **Every entrant uses the same machinery.** Seon model agents, MCP `eval_clj` (root and lanes, SCI and JVM mode)
   and test runs go through the same pass. They differ only in where the forms come from and the origin they
   record. Lanes therefore exercise exactly the code Seon agents run.
3. **Context is forms that ran, shown with their results.**
   - The opening forms (`(help)`, the task's `doc`, each reference's `doc`/`source`) run ONCE, on the agent's
     first pass for the task, as ordinary evaluations.
   - Every later context is the agent's stored exchanges read back in order. Nothing is re-executed or
     re-rendered.
   - History goes in whole (the stored shown text exactly as the agent saw it), unclipped for now. Outside runs
     are excluded.
   - `result/<id>` is the live value, for the agent to dig into.
   - Two presentations (REPL, DATA; §4.3) are compared by experiment and by asking the agents. A tie goes to
     REPL.
   - There are NO `;; =>` result comments anywhere (owner: "that sucks").
4. **One pass per reply.** One read of the text and one clj-kondo analysis of the whole reply, joined to each form
   by span. Everything downstream reads those two plus ONE batched query, never a query per value.
5. **One machinery transaction per turn.** The turn pulls one database value at its start and writes its whole
   record once at the end. The agent's own writes (`transact!`, messages, tasks) commit when its forms run, as at
   a real REPL.
6. **Lookups are standard Clojure:** `doc`, `source`, `dir`, `apropos` and `find-doc`, returning data, built on
   SCI's `clojure.repl`. The database answers only what SCI cannot know: contracts, branch source, schema
   keywords, tasks and test status.
7. **The reader:** fenced code is code; a form that starts a line is code; everything else becomes a comment.
   Imitated results are never evaluated and are flagged to the agent.
8. **Done means proof tests are green.** `my.task/complete!` only bails, with a reason. A task needs its proof
   tests before launch, `assign!` refuses tasks with no proof or already green, and outside runs never enter an
   agent's history.
9. **Budgets, measured every slice (§3).** No multi-second, multi-MB or GB Seon work. A number that gets worse
   blocks the slice.
10. **Delete what is slated before adding anything.** Reuse libraries. Understand the code before designing. Don't
    rush. Use dual reviews. Owner approval comes before core code.

## 2. The loop today (measured)

| What | Measured | Source |
|---|---|---|
| A model turn, stub provider, warm | 2.1 s, 3.65 GB, ~400k checked calls, 0.94 MB written to the store, 6 machinery commits | r-turn |
| the same turn with DeepSeek | 3.4 s, of which the provider took 1.4 s | r-turn |
| its pre-turn system-turn "refresh" | ~80% of the turn: 1.3-2.3 s and 3-4 GB, mostly re-acquiring the agent's SCI context (`acquire-program!`) on every turn; 300-408 datoms written | r-turn, r-context |
| the 6 machinery commits | refresh, `open-call`, context capture (the whole prompt again, so storage grows quadratically), attempt, `plan-call`, settlement (the close is inside it) | r-turn, r-record |
| MCP SCI `(+ 1 2)` | already ONE commit (`submission-open-tx` + `with-submission`): 27 datoms, ~150 KB, 205-358 ms, 159-232 MB | r-record |
| a `defn` | settlement 254 ms, 712-733 MB; a JVM-mode call right after it 1.3 s and 3.2 GB (context reload) | r-record |
| the reply read | split, then a re-read per form, then a kondo parse per form (441 of 667 ms for 40 one-line forms). Splitting allocates roughly with the cube of the reply length: 76 KB took 4.1 s and 94 GB (lane `forgive-fix` is fixing this now) | r-read |
| the system turn's `code-currency` | one `or`-plus-join query per value: 65-88 ms and ~150 MB each through Seon's query wrapper (raw Datahike 8 ms in total), 42 s for root | r-context |
| reading 200 exchanges | raw index plus a narrow pull: ~8 ms and ~17 MB; `of-agent` 0.4-0.5 s and 0.8-0.95 GB; per-row text building 180-220 ms and 370 MB, against 3.3 ms for plain concatenation | r-context |
| the per-commit store floor | ~130-150 KB, mostly Datahike's 26.7 KB index-root record written twice, plus ~4 KB per attribute touched | r-record |
| profile measurement upserts | 51-78% of the datoms in larger settlements; read only by the health report | r-record |

What the agent sees and does:
- a transcript interleaved with ten "changed since your last turn" refresh units, outside setup runs, and a
  1,241-token error list carrying a Seon core fault;
- a reply full of fabrications, both `scratch.add2=> … #:seon.repl{…}` and `;; => 42` inside comments, with false
  "verified" claims;
- prompt markers stripped while their namespace is ignored, so a `defn` lands in `user`;
- a prose-only reply that evaluates its first word.

Origin is not one fact: `:seon.cluster.eval/author` lumps MCP runs and refreshes together as `:system`.

Broken now:
- agent test runs are refused by a stale row on the cluster head (`user.sgprobe/fix`);
- `of-agent` on root fails on stored read plans;
- 17 open faults.

## 3. Budgets (owner)

Measured on every slice with the S0 probe, before and after: wall ms, allocated bytes, checked calls, commits,
store bytes and tokens shown.

| Per pass, excluding the provider's own latency | Budget | Today |
|---|---|---|
| Seon's total work | < 200 ms | 2.1 s |
| allocation, beyond the agent's own forms | < 5 MB | 3.65 GB |
| machinery commits | 1 | 6 |
| context read at N = 200 exchanges | < 10 ms, < 5 MB | 8 ms, 17 MB (narrow read) |
| store bytes per pass | ~150 KB (owner: fine) | 0.94 MB |

The owner accepts ~150 KB per pass ("small amounts of data that are reasonable"). The ~130 KB floor is Datahike's
index-root record; cutting commits from 6 to 1 is the win.

Any non-agent, non-provider operation over 1 s or 10 MB is stop-and-fix at its producer.

## 4. The design

### 4.1 One pass, all entrants

```
entrant (model wake | MCP eval_clj | test member)
  → forms text   model: the provider reply to the context (4.3); MCP/test: the given code
  → READ once    the one reader (4.2) → ordered forms with their spans and comments
  → ANALYZE once ONE clj-kondo run over the whole text → definitions, calls, keywords, per form by span
  → EVAL         SCI in the agent's world, in its current namespace (carried on the agent), forms in order;
                 the agent's own writes commit as they run
  → RECORD once  ONE transaction from the turn's start value plus what it observed: evaluation rows
                 (source, shown text, origin, ordinal, ns), program facts of its definitions (from the
                 analysis + one batched digest query), the provider call row, agent facts (current-ns,
                 answered basis, ordinal); validated by the writer
  → PRINT        the value printer per form; the shown text is the agent's next context and the MCP reply
```

The existing one-commit MCP path (`submission-open-tx` + `with-submission`, r-turn §6) becomes the one owner;
model and test entrants join it. The two settlement writers become one: today `turn.batch/settle` serves only
JVM-mode MCP, and `seon.test.runner/record-tx` is a third (r-record).

Origin becomes one enum on the evaluation: `:opening`, `:model`, `:outside` or `:test`. "Answered" is
re-derived without the run's open-before-reply ordering, which one transaction removes (r-turn §5.9).

Bounds: the provider timeout, the SCI time limit, and model calls per outside wake, with root's messages counting
as wakes (r-turn finding 8).

### 4.2 The reader

- One read of the text. The single-read stage already exists (`turn.batch/code-request` + `sci.batch/read-next`,
  used only by tests); production adopts it, and split-then-reread is deleted.
- Rules:
  - a fenced body is code, a bare symbol included;
  - a form starting a line is code;
  - prose and Markdown become `;;` comments kept with the next form, and trailing prose is kept;
  - a prose-only reply gets the "no form" refusal.
- `ns=>` prompt markers: either removed together with the REPL presentation, or honored with their namespace
  (S6 decides; r-read finding 10).
- Imitated results (result-shaped maps, or result claims in comments after a form) are never evaluated and are
  reported to the agent as data.
- The reader is a function value chosen per agent through settings, for experiments.

### 4.3 Context

- **First pass of a task:** the opening forms evaluate through the pass with origin `:opening`.
- **Every pass:** read the agent's `:opening` and `:model` exchanges in order with ONE narrow index read
  (agent → ordinal → source + shown + comment), and concatenate. There is no per-row query and no value-printer
  re-print of history (re-printing clips it: r-context finding 7).
- **REPL presentation:** each form exactly as sent, then immediately its shown result, like a real REPL. The
  agent's prose comments sit above the form. No result comments, no `;; =>`.
- **DATA presentation:** each exchange as a map holding its stored strings (`:ns :form :shown`), concatenated, never
  re-printed as one value.
- Two small functions over the same read, picked by agent settings; S6 keeps one.
- A system message only if S6 shows it helps, and then exactly `(help)`.

### 4.4 Lookups

The repl-lookup design (`../repl-lookup/design-notes.md`), whose claims r-context verified. SCI's `clojure.repl`
does names, apropos and find-doc; Seon adds `doc` for program functions (contracts), schema keywords, tasks and
tests, and `source` from the branch. Duplicates are deleted. `(help)` is rewritten: the formatted protocol, no
retired mechanics.

## 5. Order of work

Each slice is preceded by understanding (traced) and a design note, followed by a dual review of the design, owner
approval, code, a dual review of the diff, then root's restart. Every slice reports its S0 numbers.

| # | Slice | Deletes | Adds | Gate |
|---|---|---|---|---|
| P0 | **Stability.** Land `forgive-fix` (cubic read). Bump the Datahike gitlink to the reviewed fork (wrong-answer and stale-plan fixes). Reset done (08:54Z): the stale row is gone and agent test runs work. Retire leftover probe branches. | dead code (444 lines, r-delete) | — | diff reviews |
| S0 | **Baseline probe.** One turn per entrant (model through a local HTTP fixture endpoint set in agent settings, which needs no code change, r-turn; MCP SCI; MCP JVM; test), recording every budget metric. | — | a test plus a probe form | root |
| S1 | **Lookups** (repl-lookup design) and **help** rewrite | D6 (`directory-value`, …, ~133 lines) | thin layer on SCI | design → owner |
| S2 | **The system turn.** Delete it and the generated opening seeded at agent creation; debug views read `of-agent` with an explicit selector. This is the biggest budget win (~80% of a turn). Re-measure `acquire-program!` after it. | D1 parts a-b (~1,970 lines) | — | design → owner |
| S3 | **Context:** the opening as origin `:opening`, the narrow history read, REPL and DATA functions at the existing prompt seam. Delete the old prompt, walk and capture. | D1 part d (~850 lines) | context functions | design → owner |
| S4 | **One pass:** one read, one kondo analysis, one batched digest query, one machinery transaction, the origin enum, re-derived answeredness, three bounds, all entrants. Delete the run machinery, permits, backstop, parking, episodes, the second and third settlement writers, split-then-reread, and the refresh-only attributes (read evidence, `executed`, `code-evidence`, …). | D2-D5 + D1 part c (~3,000 lines) | the one pass (target < 600 lines) | design → owner |
| R | **Resets** whenever a slice retires schema or state needs cleaning (~7 s). Runtime tasks are dumped first. | — | — | no approval needed |
| S5 | **Completion:** proof tests run after a pass that changed reached code; `complete!` bails; `assign!` refuses. SCI test running fixed (`run-test-var` throws; r-read finding 7). | `my.agent/done`, turns-left | ~100 lines | design → owner |
| S6 | **Live experiments on DeepSeek** (unlimited) through the real loop. REPL vs DATA; prompt-marker handling; system role vs first form; stop on vs off. Measured: protocol followed, invented results, calls to finish, correctness. A separate feedback turn after each run. | the losing variants | — | the owner reads the results |

Net: about 5,700 lines are slated (r-delete). Adds are targeted under 1,000.

Stop-and-fix items folded into the slices above, each with its owner:
- `acquire-program!` re-acquisition per turn: S2, then measure;
- the per-value query in `code-currency`: deleted with S2; the query shape itself (`seon.db/an-or-clause-joined-to-a-pattern-costs-a-scan-per-binding`) is fixed in `seon.db` within S4's batched digest query;
- per-form kondo: S4;
- `defn` at 700 MB: S4, measured;
- profile measurement upserts: S4 writes them only when significant, or they move off the turn transaction;
- the 17 MB narrow history read: S3.

## 6. Decisions for the owner (after review)

Resolved by the owner, 2026-10-07:
- Reset whenever needed (~7 s, no migrations); default was reset at 08:54Z.
- ~150 KB written to the store per pass is fine.

Open:
1. **Subtree counts** (dh-fix): store child counts in branch nodes (persistent-sorted-set format change; it also
   ends whole-range counting). Alternatives: count at each split (up to 64 reads per split), or a recount job.
2. Approve this plan as the replacement for `docs/seon/specs/agent-loop.md`.
