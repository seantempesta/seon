---
type: research
status: evidence
created: 2026-10-16
tags: [research, context, agent-platform]
---

# Where model turns are wasted: patterns across every readable attempt (2026-10-16)

Lane context-patterns (Sonnet). Read-only on default (JVM mode). Dataset: [context-patterns.edn](context-patterns.edn).
Reproduce: branch values via `seon.db/branch-database`, forms by `:seon.cluster.eval/origin :model` joined to the agent's call rows, history units via the private `seon.context/exchanges` on the branch value (what each later model call was sent).

## 1. Corpus (small, and why)

Only 4 model-driven attempts are readable: 117 forms over 46 model calls, all on `seon.ref` ("wildcard citations are prose"):

- `:chain-10d1fd5956a9`: `attempt/faec011a7057`, `attempt/54c541b9172a`
- `:chain-b7ab51e090d5`: `attempt/2aaec4432ae0`, `attempt/cec65a8d91f7`

Everything else is gone: merge-step attempt `attempt/cca4c2e69d66` (its branch `:merge-b953f374fe17` was retired; the row on `:chain-b7ab51e090d5` has no calls or forms), `attempt/c4424e366887` on `:chain-e439df9798c6` (18 call rows, no model evaluations), all `merge-*`, `agent-*`, `attempt-*` branches (zero model evaluations), and bench runs (only the aggregate `docs/research/agent-platform/2026-10-08/bench/e0-e1.md` survives). So the merge-step findings of the Haiku pass (25 `in-ns`, `:pending` confusion, prose replies) cannot be re-measured here; this report covers chain steps only. Conclusions come from 4 runs of one task: patterns, not rates.

## 2. Grades

`seon.competency/grade` runs on a chain branch value (`(seon.competency/grade branch-db "attempt/..." 'seon.ref/wildcard-citations-are-prose)`, 0.7 s, 1.3 GB allocated, over the one-second rule). Two defects: `:green?` and `:proofs` read the branch's current proof state, not the state at the attempt's end (all four print `green? false, proofs {:pending 1}` although two ended green), and the agent entity carries no task, so a caller must guess the task symbol. The table uses the agent's own end facts (`:seon.agent/end-calls`, `end-green`, `end-pending`) for the end.

| attempt | end | calls | to green | forms | agent err | platform err | no-form | repeated | prompt tok (cached) | wall s |
|---|---|---|---|---|---|---|---|---|---|---|
| faec011a7057 | pending | 12 | none | 35 | 1 | 3 | 0 | 7 | 119k (87k) | 40.8 |
| 54c541b9172a | green | 10 | 10 | 22 | 3 | 2 | 0 | 1 | 109k (67k) | 33.5 |
| 2aaec4432ae0 | pending | 12 | none | 35 | 3 | 0 | 0 | 5 | 84k (62k) | 24.7 |
| cec65a8d91f7 | green | 12 | 12 | 25 | 0 | 0 | 0 | 7 | 76k (58k) | 29.1 |

Every attempt used its whole call cap; both green ones defined the proof on the last call. A competent path is 3 to 4 calls: read `seon.ref/span-candidate`, `defn` the fix, `deftest` the proof. Nothing here is a no-form reply.

## 3. Classes (117 forms; first-match rules on source and error text, in `context-patterns.edn` per form)

| class | forms | share | turns it fills alone |
|---|---|---|---|
| targeted read (first read of a name) | 42 | 36% | productive |
| **re-read after its first reply was elided from history** | 16 | 14% | 6 whole turns |
| probe of own fix at the REPL | 15 | 13% | productive |
| `in-ns` and ns navigation | 14 | 12% | 0 (rides along) |
| search / discovery (`search`, `dir`, `callers`, `ns-decls`) | 9 | 8% | 2 |
| progress (`defn`, `deftest`) | 8 | 7% | productive |
| contract refusal (wrong call shape) | 4 | 3% | 2 |
| task read, cleanup, other, invented name, other error | 9 | 8% | 1 |

Of 46 turns, 12 (26%) contained no progress, first read or probe. Spot check: 20 random forms read by hand against the stored source and error; 20 agree with their class. Known miss: the errored `deftest` in `attempt/54c541b9172a` turn 5 is classed progress (rule order); its error is an unresolved name.

## 4. Top patterns

### P1. The history elides exactly the source the agent needs, and the agent never follows the reference (16 forms, 6 pure turns, the cause of both `pending` ends)

`seon.context/exchange-form` replaces an older reply over `:seon.config.context/reference-tokens` (default 400) by its handle and size. The reply of `(my.program/source 'seon.ref/span-candidate)` is 412 tokens, 12 over the limit, so one turn after reading it the agent holds only `result/...` and "412 tokens". Over the 4 attempts 31 of 117 history units were elided (17,975 tokens of reply). The agent followed 0 of the 31 handles (no form contains `result/`); it reads again instead: `span-candidate` was requested 20 times in 4 attempts, `candidate` and `parsed` repeatedly.

- `attempt/2aaec4432ae0` turn 4: `; span-candidate source returned empty. Let me search for it.` (the reply was elided; it then ran `my.program/search`, `ns-decls`, `seon.ref/parser`, a string-argument `source`, and ended without defining anything)
- `attempt/cec65a8d91f7` turn 2: `; I need to see span-candidate itself (the source call earlier returned only references).`
- `attempt/faec011a7057` turns 4 to 9: `println` and `str` wrappers around the same source (output 1,476 characters), elided again the next turn.

### P2. `dir` of a test namespace answers `[]` (3 dead ends)

`(dir seon.ref-test)` answered `[]` three times (`faec` turn 1, `2aaec` turns 1 and 3). `seon.sci.lookup/directory-value` lists public functions only, so a namespace of `deftest`s is empty; each was followed by a `source` of the whole namespace (797 tokens, elided next turn, P1).

### P3. How does a proof get its database? (4 turns in `54c5`, several guessed calls)

`seon.db/database-value` (unresolved), `(seon.db/q)`, `(seon.db/database-value?)`, `(my.edit/text)` with no arguments, `(seon.db/db)`: the agent never saw how a `seon.ref-test` deftest obtains a database value, read two sibling tests, then guessed. The sibling `seon.ref-test/a-reference-with-no-path-is-a-missing-file` shows it (110 tokens) but was not a must-read.

- `seon.db/database-value? refused argument count: expected a declared argument count (1), got an argument count of 0. Fix: Call one of the declared arglists.`
- `Unable to resolve symbol: seon.db/database-value`

### P4. `in-ns` rides on 14 forms (12% of forms, 0 pure turns)

The step block's start is `(in-ns 'seon.ref)`; agents repeat it when they cross to `seon.ref-test` and back, 6 times in `cec65` alone. The cost is forms and a few tokens, not turns, so it ranks low here (the Haiku pass saw 25 of 94 in the merge step; not re-measurable).

### P5. Wrong call shapes of Seon tools (4)

`(my.program/source "span-candidate")` ("expected :seon.program/subject (a symbol), got a string. Fix: Supply a symbol."), `(seon.db/q)`, `(my.edit/text)`, `(seon.db/database-value?)`. The refusal text is good and the agents recovered in one turn; the producers of the guesses are P3 and the missing verbs in context, not the messages.

## 5. The system message (root's addendum), per call

`seon.context/model-context` on `:chain-b7ab51e090d5` for the two chain attempts, parts rendered with `seon.sci.eval/shown-result` and counted with `seon.ai.tokens/estimate`. The merge step's branch no longer exists, so its 29.7k-character figure (about 7.4k tokens) cannot be reproduced; its parts add up to that order (2.5k help, 1.1k chain, 0.3k task and the merge step's 10k-character must-reads at 2.6k).

| part (per call) | tokens | share of the 5.4k to 5.7k system |
|---|---|---|
| `(help)` render | 2,504 | 44-46% |
| `:seon.context/must-reads` | 1,353 | 24% |
| `:seon.context/chain` | 1,136 | 20% |
| `:seon.context/attempts` | 149 (cec) / 420 (2aaec) | 3-7% |
| `:seon.context/task` | 310 | 5% |
| turn tail after the history: proofs 195, step 506, changes 133, cost 56, turns 39 | 929 | per call |

The system part is the provider's cached prefix: 58k to 87k of each attempt's 76k to 119k prompt tokens were cached, so these parts cost tokens, not turns. The turn-losing part is the history (P1). Measuring it cost 3.1 s and 7.1 GB of allocation (the block plus every section render), itself a finding against the one-second rule.

## 6. Ranked fix list

Turns saved are over the 46 turns of this corpus; divide by 4 for one attempt.

| # | fix (producer) | turns saved | maps to |
|---|---|---|---|
| 1 | Stop eliding what the agent just read as source: `seon.context/exchange-form` keeps a `my.program/source` or `doc` reply whole, or the `:seon.config.context/reference-tokens` default in `resources/seon/schemas/seon.config.context.edn` rises from 400 to 1,500 (covers every reply seen, the largest 1,419). 0 of 31 references were followed, so the reference form teaches nothing here. | 8 directly (6 pure re-read turns plus 2 re-read with `in-ns`), about 12 more downstream in the two `pending` runs (`2aaec` turns 4 to 11, `faec` 4 to 9), which stall before their first `defn` | BENCH-9 (history dial), BENCH-6 (reference depth); new card: source replies are not elided |
| 2 | `seon.sci.lookup/directory-value` lists a namespace's `deftest`s (a test namespace answered `[]` 3 times) | 2 to 3 | E1 result ("a `dir` that lists only what an agent calls") |
| 3 | Make the task's sibling proof a must-read and teach how a deftest gets a database (one line in `resources/seon/help.md`, or the sibling's source in the references); here `seon.ref-test/a-reference-with-no-path-is-a-missing-file` | 3 to 4 (all in `54c5`) | E1 (typed names), BENCH-6 |
| 4 | Revisit the call cap against need: all 4 attempts ended at the cap, the proof defined on the last call | 0 (margin, root decision) | none |
| 5 | Merge-step block shows the refused tests (Haiku 2.2; not re-measurable) | up to about 20 per merge step per the Haiku pass | `seon.cluster.agent/merge-step` |
| 6 | `seon.competency/grade` reads the end facts and the task | measurement | BENCH-1 |
| 7 | State the namespace once; drop the `in-ns` operation when already there (P4) | 0 turns, 14 forms | `seon.context` step section |
| 8 | Merge-step must-reads: the first entry is the task's own unresolved name (Haiku 1.1; 1,353 tokens per call on chain steps, cached) | 0 turns, tokens | `seon.context/must-read-ai` |

Fix 1 alone would move the two `pending` ends toward green and shorten the two green ones; it needs no new mechanism, only the dial or a rule in the unit that already decides elision.

## 7. Measurement gap: a durable per-attempt grade

The corpus is all that survived because evaluations, call rows and agent entities live on the chain or merge branch and are collected with it (on main `[:seon.agent/id "attempt/cec65a8d91f7"]` pulls nil while the attempt row merged). The agent row on the chain branch already carries `:seon.agent/end-calls`, `end-green`, `end-pending`, `end-prompt-tokens` and `end-completion-tokens`; they are the right start but do not reach main and miss most of a grade. A durable grade needs, written by the attempt's owner in its end transaction so root's merge carries it to main with the task row: `:seon.agent/task` (absent today), cached tokens, forms, agent and platform error counts, no-form calls, repeated forms, wall ms, calls to green, and the count of history units elided (P1's cause, which no fact shows). Per-form classes would need one fact per evaluation, or the retained branch; the cheaper durable form is the grade plus the first five distinct re-read sources.
