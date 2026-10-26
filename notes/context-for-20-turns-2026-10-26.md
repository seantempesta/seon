# What the capped runs lacked, and the context that would make them about 20 turns

Read-only, from the design-build live run db1: main f564b314c, thinking off, the ten requests, and the store kept at `.worktrees/template-plan-text/runs/store`. Turns were read off a copy of that store. The dumps are in `scratchpad/db1-dump/` (`-first.txt` is the first request, `-last.txt` the whole conversation, `-turns.txt` each turn's prose and code). Four readers analysed them, and their full analyses (with every turn quoted) are `scratchpad/analysis-{A,B,C,D}-*.md`, where scratchpad = `/private/tmp/claude-501/-Users-sean-src-seon-next/2489cf55-c241-4f3c-b807-be3323bdfd1f/scratchpad`. I checked the staleness against the run's base commit with git.

## The headline: four of the ten requests were already done on main

| request | why it was already done at f564b314c | turns spent |
|---|---|---|
| transport | aed2344517 (an ancestor of the base) made the docstring true; `model_test.clj:223` pins it | 22, then `fail!`, which was right |
| proof-never-ran | `seon.context/turn-state` already answers `:unknown` (context.clj:407); `context_test` `a-proof-with-no-result-reads-unknown` (:1067) | 30, capped |
| long-name | `print_test` `a-long-name-is-cut-in-chars-and-names-its-form` (:343) passes; turn 22: "Both pass" | 30, capped |
| map | `print_test` `a-map-shows-its-first-entry-whenever-its-least-form-fits` (:335) passes; turn 25 ran it green | 30, capped |

The request set (`seon.fixtures/fit-requests`, plus the fresh five) was written on 2026-10-24 against red proofs, and main has fixed these since. So 112 of db1's 273 turns went to requests with nothing to do. Only transport's agent said so, using the new `fail!` clause. The other three kept looking for a bug that was not there: long-name at turn 20 said "With a handle it already works", and map invented a different bug of its own at turn 26.

**Two consequences:**
- The measurement set must be refreshed before the next comparison: drop or replace the four. Until then, merged/green counts across today's runs are not comparable to an ideal of 10/10.
- The cheapest context fix is a page that shows, at turn 0, the existing tests that cover the plan's cited vars with their status. A green test named for the plan says "already done" before any reading starts.

## Request by request

| request | what the red proof needed | on the first page? | where the turns went | first turn with all it needed | outcome |
|---|---|---|---|---|---|
| summary (seon.ref) | the bug is in `seon.context/related-view` (context.clj:241, line 256), which drops `:seon.ref/left` and `:read-at` that `seon.ref/shown` already computes; a multi-sentence section; the test that pins the line format, `context_test` :929 | partly: it cited `shown` (correct, but already right), not `related-view`; names only, no source, no test | 0–4 reading `shown`; 5–17 `seon.docs/edge`, a misread nil from `run-test-var`, getting a db; 18–23 found `related-view` (turn 19: "Now I see the real gap"); 24–25 proof | 18 | registered a red proof at turn 25 on the live db; its assertion did not match its own fix, and the fix broke the pinned test; capped |
| proof-never-ran | already done (above) | no: `turn-state` was not cited, and the page carried the one-line request, not the task entry's plan (`seon.context.edn:59`) | 0–6 reading (turn 3: "turn-state looks right: no result -> :unknown"); 6–15 finding source files on disk; 16–27 grepping for "asserted nothing" (no hit in src) | 3 | no proof; capped |
| long-name | already done (above) | names only (`emit/cut-words`, `select/node-continuation`, …); no input, no opts, no test name | 0–10 finding how to read the cited doc sections; 11–13 reproducing; 13–21 reading select; 22–23 existing tests green; 24–29 hunting a bug that was not there | 12 | no proof; capped |
| map | already done (above) | only `select/classify` (irrelevant); the task entry's reproduction (`(zipmap (range 30) …)`, `{:budget 10}`, `seon.print.edn:14`) was not on the page | 0–6 guessing a reproduction; 7–10 reading; 11–22 `seon.docs` arities and paths ("I'm spending too long on docs plumbing"); 23–26 the existing test green; 27–29 a proof of its own invented case | 2 (with the reproduction) / 25 | a proof red for the wrong reason (`zipmap` hash order), at turn 28; capped |
| short-history | `seon.context/kept` (context.clj:570) over `parts` of `{:cost [whole collapsed]}`; e.g. parts `[100 5]` at budget 150 over growing k (the reader's hand trace, not run); the existing `kept` tests (context_test :282–300) as the template | names only (`kept`, `request`); no `parts` shape; "one history step" undefined (it is half the budget, in the docstring) | 0–1 `kept`'s source; 2–26 reading one cited doc section: `ref/lookup` missing, a wrong `parse` arity, 19 turns looking for a db handle; 27–29 looking for the tests | 1 | no proof; capped |
| mixed | `seon.print.select/fill-rows` (select.clj:733) with `alike?` and `row-class`; rows like `problem-rows` (print_test :248, private) plus one keyword; `{…7` means 7 keys | no: the cited function was `seon.print/seen` (wrong var); no test, no input shape | 0–5 orienting, the wrong print fn; 6–14 guessing inputs ("Still fine"); 14 reproduced with a stand-in; 15–23 tracing; 24 the cause; 25–28 a patch on the wrong branch | 15 (repro) / 24 (cause) | no proof; capped |

The contrast is help, green in 13 turns. Its page carried the cited function's whole source, with the offending sentence visible inline, and the request named exactly what was wrong. It never had to look for the edit, and it redefined the var at turn 4.

## What the turns were spent on, across the six capped runs

1. **A request already done** (four runs): 112 turns. Nothing on the page told the agent so, and the one instruction for it (`fail!` on a false premise) was used once, at turn 21.
2. **Reading a doc section the page cited only by address:** 11 (long-name), 12 (map), 25 (short-history) and 5 (transport) turns. No one form worked: `seon.docs/section` does not exist, `seon.ref/lookup` does not exist, `parse` arities were guessed, and `(my.db/db)` came back nil. The working call was found late, or never.
3. **The page cited names, not code, and sometimes the wrong names:** mixed cited `seon.print/seen` (the site is `fill-rows`), summary cited `shown` (the site is `related-view`), and proof-never-ran did not cite `turn-state`. Every run fetched each source itself, and 2 turns went to each private var.
4. **No reproduction in the request:** the one-line requests carry no input, budget or expected text, though the task entries on disk do (map). Map and mixed each spent 9 turns guessing inputs.
5. **Reading with no end, in the wrong order:** five runs had what they needed by turns 1–18 and never wrote the proof (short-history at turn 1, proof-never-ran at turn 3, long-name at 12, mixed at 15). The instruction says proof first, but nothing stops the reading once a reproduction shows the wrong output.
6. **Proof hygiene:** a proof on the live db (summary), and `zipmap` ordering (map).
7. **Finding source on disk instead of asking the REPL:** 10 turns (proof-never-ran).

## The context changes that would cut each to about 20 turns

**Data on the page:**
- **D1. The existing tests of the cited vars, with their status.** For each cited var, its callers among the deftests (`my.agent/callers` already finds them), each with its name, source and last status. Proof-never-ran, long-name and map would have seen their own proof green at turn 0 (each about 3 turns, then `fail!`). Summary would have seen the format test it had to update. Short-history and mixed would have had the input helper and the assertion style (`part`, `problem-rows`, `at`/`moved`). Saves 15–28 turns in each of those five runs.
- **D2. The cited functions' source, not their names.** Help's page already does this (the `cites` tile), and it was the fast run. The capped runs' related functions were names only. Saves about 2–5 turns a run, and the private-var detours.
- **D3. The cited doc sections' text, not their addresses.** Saves 5–25 turns a run (short-history lost 25).
- **D4. The request's full task plan when the request comes from a task entry,** including its reproduction line (map's `seon.print.edn:14`, proof-never-ran's `seon.context.edn:59`). The page carried the one-line request.
- **D5. The cited functions should come from what the plan names and what draws the output it describes, not only from the related search:** mixed got `seon.print/seen`. This belongs with related-sections/agent-view: the related vars were wrong in two of six runs.

**Instruction text** (the `:work` instructions, `seon.template/instructions`):
- **I1.** "First reproduce what the plan says is wrong in one eval and show its output. If the output is already right, the premise is false: call `(my.task/fail! \"…\" evidence)` with that output at once; do not keep reading." This would have saved proof-never-ran, long-name and map (each about 5 turns instead of 30) and transport (about 6 instead of 22).
- **I2.** "Once a reproduction prints the wrong output, stop reading: write it as a deftest with its expected value, register it and run it red before you change code." This is for short-history, mixed and summary.
- **I3.** "Build the proof's input like the existing tests of the function (their helpers and fixtures), never from the live db; use ordered literals (`array-map`, `sorted-map`), not `zipmap`, where order shows."
- **I4.** One form for a doc section, in the system prompt's tool list: the working call (`seon.docs/page-text` with the branch's db), or nothing if D3 puts the text on the page. D3 is better.

**And the measurement:** replace the four already-done requests before the next run (`seon.fixtures/fit-requests`, `fresh-requests.edn`). Otherwise every setup caps on them and the numbers measure staleness.

**What I'd do first,** by turns saved for the change: D1 together with I1 (both small; D1 reuses `my.agent/callers` and the proofs' recorded status), then D3, then D2. Each run's estimate is the readers' reading of its turns; none was measured. The one way to know is a rerun on a refreshed request set.
