# The printer's three candidates, decided by blind-scored evidence (2026-10-23)

Worktree session printer-eval (`jolt/printer-eval`, from main `93394fc`). The owner's ruling: "generate real data and have haiku agents score the different approaches and make sure the output is the best, not just the lowest lines of code". Candidates: rows 36-38 of the shrink research (`git show f8441db:notes/shrink-research-2026-10-22.md`).

| Row | Candidate | Decision | Evidence |
|---|---|---|---|
| 37 | `:cuts`: drop it, or show it to agents | **Drop it.** The inline cut, as today, is what agents read best. | Accuracy 100% for all three variants (60 answers); today's inline cut ranked first in 30 of 30 three-way rankings |
| 38 | One string cutter instead of two | **One cutter, prose's rule and words everywhere**, a string inside a value too | Prose's words beat select's 18 of 22 pairs; on value strings 8 of 8; select's break lost the content of a prose cut (2 of 2) |
| 36 | The stream's and history's render-once caches become units with a `:container` | **Built.** Same page; the history sends one row where it sent the whole list | 3,778 bytes against 132,443 for one new evaluation on a page of 100 |

`src`: +161 −235, **−74 lines**.

## 1. The corpus

`corpus.clj` writes `corpus.edn` (43 values, 122 KB), read off a store that scripted agents worked on this worktree's own build (`target/eval-store`; `./seon e2e calc`, `e2e stuck` and `e2e scenario conflict`, each on its own main, `SEON_PROFILE=scripted`): all of main's tasks and attempts (calc and stuck), single tasks with their schema render, problem reports (stuck facts, red proofs), the 90-key config, turns with their basis and calls, the conflict branch's merge entity, `:will-merge` (`seon.program/divergence`), proof results, every evaluation record of three branches (`work!` answers with their costliest functions), and the context text each agent read (`seon.context/recorded`). Deliberately large: 400 datoms, AGENTS.md (27 KB) and docs/render.md (14 KB) as strings, and an HTTP error map whose `:body` is docs/flow.md (11 KB).

`variants.clj` makes production-shaped evaluations from the corpus (a `slurp`, `my.task/tasks`, the root log as `:out`, an error with a body, datoms, attempts, a real `clojure.test` run's output, the config, turns) and renders them where an agent or a person reads them, at the real budgets: the REPL's answer (`seon.render/printed`, 6,144 tokens), a turn's evaluations in the model's context (`seon.context/shown-evals`, 8,000 shared), a value inside another (256), prose (an output, a plan, an error's body at 256 and 600) and one-line cells (25 and 50). Only entries where the variants differ are kept (`renders.edn`, `renders2.edn`). These scripts ran against the base printer (`93394fc`): they read `:cuts` and `seon.print/cut-words`, which this branch removes.

## 2. The variants

- **37** A: today (each cut inline with its form; `:cuts` unread). C: A plus a closing ";; left out" list built from `:cuts` (where, how many, the form). D: inline markers `«k»` with the forms as footnotes.
- **38** A: today (a string in a value `"prefix"…+N (subs …)`, cut at the last line end inside its room; prose `…+N chars in M more lines (subs …)`, cut at a line break in the room's second half else a space in its final quarter). B: select's cut everywhere. C: prose's cut everywhere. Round 2, one-line cells only: E, prose's break with `…+N chars` (no line count).

## 3. Blind scoring (21 Haiku agents)

Packets (`packets.clj`, `packets2.clj`; keys `key.edn`, `key2.edn`) show renderings unlabelled, in an order fixed per scorer. Raw answers: `scores/*.json` (`collect.py`); unblinded by `tally.clj` and `tally2.clj`.

**Accuracy** (10 agents; each saw one variant of a value, never two; each rendering answered twice): a concrete question per value ("which evaluation raised an error and what reads the rest of its body", "is section 8 shown, how much is not, and what form returns it", "what status did calc/neg#1 end with"), graded on the exact read form and the count left out.

| | A | B | C | D |
|---|---|---|---|---|
| 37: exact form, count | 20/20, 20/20 | | 20/20, 20/20 | 20/20, 20/20 |
| 38: exact form, count | 22/22, 22/22 | 14/14, 14/14 | 8/8, 8/8 | |

Every variant let Haiku answer and read the rest. One content difference: prose cut by select's rule (38 B) showed the context's task id and nothing of its instructions, so both readers answered "not shown" to "what do the instructions say to do first", where both readers of A answered "Explore first".

**Pairwise preference** (5 agents, 52 pairs):

| Pair | Wins | Mean rubric: answer / left out / not misleading / concise |
|---|---|---|
| 37 A vs C | C 10, A 0 | A 3.8 2.5 4.7 3.4; C 4.2 5.0 4.7 3.0 |
| 37 A vs D | A 10, D 0 | A 4.9 5.0 5.0 4.0; D 4.0 5.0 5.0 3.0 |
| 37 C vs D | D 10, C 0 | C 4.3 5.0 4.0 3.0; D 4.3 5.0 5.0 4.0 |
| 38 A vs C (strings in values) | C 8, A 0 | the line count tells the reader how much is missing |
| 38 A vs B (prose, cells) | A 10, B 4 | B won 4 of 6 one-line cells (shorter words show more text) |

Row 37's pairs form a cycle (C > A > D > C, each 10 to 0): each pairing rewarded whatever differed in it. **Round 2** (6 agents) ranked all variants of a value at once, each rendering's size in tokens shown:

| | first | last | mean rank |
|---|---|---|---|
| 37 A (inline) | **30/30** | 0 | **1.00** |
| 37 D (footnotes) | 0 | 1 | 2.03 |
| 37 C (left-out list) | 0 | 29 | 2.97 |
| 38 cells A (prose's words) | 11/24 | 6 | **1.79** |
| 38 cells E (`…+N chars`) | 7 | 4 | 1.88 |
| 38 cells B (select's `…+N`) | 6 | 14 | 2.33 |

Disagreements: in round 1, pairs judged C's left-out list "clearer" against A, though both carry the same forms and C repeats every one (at a value inside another, C is 230 → 660 tokens against a 256-token budget, which the printer's law forbids). With every variant and its size side by side, all three rankers put A first every time ("the exact form inline at the lowest cost"). On one-line cells the readers split between showing more text (B, E) and saying the unit and line count (A); B's bare `…+N` was ranked last most often ("no unit"), and A and E are within 0.1 of each other.

## 4. Decisions, and what was built

- **37: `:cuts` is dropped**, with the cut node's keys nothing read (path, total, next offset, profile, bound-by, requery id): a cut node is its omitted count, unit, prefix, lines and form, or why it has no form. The text agents read is unchanged. The tests that read `:cuts` now read each cut off the text and evaluate its form, as an agent does (`seon.print-test/cuts-of`, `reads-exactly?`): a stronger law than before, since it proves the printed words, not the data beside them.
- **38: one string cutter** (`seon.print.select/break-at`, `lines-after`, and `seon.print.emit/cut-words`), used by a string inside a value and by `seon.print/prose` alike: `"prefix"…+7279 chars in 88 more lines (subs result/h3 19523)`. Prose sizes with the printer's own `estimate-chars` (the config's prior, not a second 3.2). One-line cells keep A's words: E was not better, and one set of words is simpler.
- **36: stream and history rows are units with a `:container`** (`seon.render.proc/step-units` adds a new one to it, prepended for rows; `finish-patch` removes any containered unit the layout dropped; a changed input replaces it by id). Gone: `:event-rows`, the late re-render loop, the patch merging in `pass`, `events-list`, the zoom's `:rows` cache and `history-list` (now `history-more`, the list's last line). A stop row learns how its attempt ended from `system-value` (`ui/ended-as` over the kept events, identical when unchanged). No Haiku scoring: the page a person sees holds the same rows in the same order (`seon.render-test/history-prepends-new-rows`, `stop-row-says-how-it-ended-once-main-knows`); `units36.clj` measures the bytes.

## Reproduce

```
HOME=target/home JOLT_PWD=$PWD target/jolt/jolt -A:test run notes/printer-eval/tally.clj
HOME=target/home JOLT_PWD=$PWD target/jolt/jolt -A:test run notes/printer-eval/tally2.clj
HOME=target/home JOLT_PWD=$PWD target/jolt/jolt -A:test run notes/printer-eval/units36.clj
```

`corpus.clj` needs the kept store `target/eval-store` (rebuilt by the three e2e runs above); `variants.clj`, `packets*.clj` need the base printer (`git worktree add … 93394fc`).
