# What agents read by eval that the story or screen could have shown (read-only measurement, 2026-10-25)

Checkout `.worktrees/dogfood` (= main 929fc8b6e). Nothing run, nothing edited but this note.

## 1. What can and cannot be measured

The step 6 run stores are gone. `notes/measure/step6/` keeps only per-run aggregates (turns, tokens, and `.prose` / `.similar` counts). **It holds no eval forms.** The only agent eval forms that survive are in `notes/requests/*.json`: the last request of 8 runs (the final request carries the whole scrollback), from two variants of the same 4 tasks:

- `base3-*`: 4 runs, the old context (`:references` doc-only, 10 KB system page, no prose).
- `prose1-*`: 4 runs, the prose-first format step 6 measured (`:references`, `:found`, `:map`, 10.6 KB help page as system).

Tasks: `seon.ref/a-summary-says-there-is-more`, `seon.model/a-transport-failure-says-what-happens`, `seon.program/a-method-key-is-decoded-once`, `seon.context/a-red-proofs-calls-are-its-recorded-refs`. (Step 6's five fit tasks differ: two print tasks and `an-unrun-proof` instead of the program/context ones.)

So: the classification below is **120 eval calls / 143 read forms from 8 runs**, not the step 6 sets. Not measured: step 6's own forms (gone); whether a run read before the prose-first change (only the endpoints survive, and a request holds turns up to the capture, so runs that went on are cut: ref/base3 34 calls, program/base3 21, the others 9 to 16); how a read's answer was used (only that it was read). The story in those runs is the old `:references/:found/:map`; **today's story is different** (`seon.context/run-state`: `:write-set` = source + callers (names) of every cited var at any delimiter, `:to-prove`, `:sections`, `:similar` off). Reads of a cited var's source were not "shown" in those runs unless the plan used three backticks; I count them as shown *today*, which is what root asked.

Step 6 aggregates that do exist (15 runs on, 15 off, 3 each of 5 tasks): `.prose` and `.similar` give the only step-6 read signal (section 5).

## 2. Counts, all 8 runs (143 read forms; 102 of 120 eval calls contained at least one read)

| kind of read | base3 | prose1 | total |
|---|---|---|---|
| source of a **cited var** (the write set) | 7 | 6 | 13 |
| `callers` (names) | 2 | 2 | 4 |
| source of a **caller or callee of a cited var** (1 hop, from `:form/refs`) | 10 | 7 | 17 |
| `callees` / `source-fn` of a cited var | 2 | 0 | 2 |
| ns form of the **proof's test ns** (`(source seon.x-test)`) | 5 | 2 | 7 |
| source of a sibling test or test helper (5 of them on the call graph of a cited var) | 12 | 14 | 26 |
| source of another var, same ns (not 1 hop) | 11 | 7 | 18 |
| source of a var in another ns (not 1 hop) | 2 | 2 | 4 |
| whole-ns `source-fn` (returned only the ns form) | 0 | 3 | 3 |
| `grep` | 13 | 8 | 21 |
| schema forms (`(get (my.schema/forms) :k)`) | 7 | 0 | 7 |
| file / shell hunt for source (`slurp`, `sh/sh`, `file-seq`, git) | 1 | 16 | 17 |
| `dir` / task / wiki section | 3 | 1 | 4 |

Forms that were work, not reading (defn/deftest/in-ns/require/running the code under test/queries on the agent's own test db) are excluded; so are `println "-----"` separators.

Grouped by what the story or screen could do:

| group | forms | share |
|---|---|---|
| **Shown today** (cited var's source, callers' names) | 17 | 12% |
| **Pullable by a rule from what is already known** (1-hop callee or caller source 17, callees 2, proof ns form 7, a sibling test on the call graph 5, schema of a key in a cited contract 7, file:line hunt 17) | 55 | 38% |
| **Exploration no rule predicts** (sibling tests 21, other var same ns 18, other ns 4, whole-ns 3, grep 21, dir/task/wiki 4) | 71 | 50% |

Per run (shown today / pullable / exploration):

| run | base3 | prose1 |
|---|---|---|
| ref (summary) | 2 / 15 / 17 (34 calls, no write in 34) | 1 / 2 / 5 (6 calls) |
| model (transport) | 2 / 5 / 5 (12 calls) | 2 / 6 / 9 (10 calls) |
| program (method-key) | 3 / 3 / 12 (21 calls) | 4 / 3 / 12 (12 calls) |
| context (red proof calls) | 2 / 4 / 5 (16 calls) | 1 / 17 / 3 (9 calls) |

Reads before the first write (deftest/defn): base3 ctx 10 of 16 calls, model 9 of 12, program 18 of 21, ref never in 34; prose1 ctx never in 9, model 3 of 10, program never in 12, ref 4 of 6. Most of each run is reading.

Cautions on the "pullable" 55:
- 16 of the 17 file hunts are **one run** (prose1 context): the plan describes a state main no longer has (`seon.context/callees` was already the `:form/refs` query), so the agent doubted the loaded var and went looking for the file. A stale task, not a context gap; it is a task problem the story could not fix. Without it pullable is 39 of 127 (31%).
- Schema reads: 7, all in one run (ref/base3), all keys named in `shown`'s contract.
- Of the 17 one-hop sources, about half were callers and half callees (def-eid, def-ref, namespace-view, task-view, load-changes!, load-diff!, resolve-ref; view, lead-sentence, fx/outcome, fx/dispatch, owner, req).
- Read answer sizes (median, from the requests, chars): 1-hop source 915, cited source 717, proof ns form 771, sibling test/helper 1179, same-ns neighbour 1280, grep 333 (mean 959), schema 431. At ~3.5 chars per token that is 210 to 370 tokens per read.

## 3. How agents read versus how the story tells them to

- The help page says a var written `ns/name` in the prose is shown on the screen next turn. In the 8 sample runs, almost no prose uses that form (they write `` `seon.ref/shown` `` in backticks, never relying on the screen); none ever relied on it instead of an eval.
- Step 6 `.prose` stats (243 evals on, 202 off): 25 (10%) and 39 (19%) evals cited something the parser resolves; 11 and 14 of those (44%, 36%) then had the name used in the next three evals. So the prose-citation route is a small share of reads: agents read by eval.

## 4. `:similar`, from the step 6 `.similar` files

With 5 listed per task: 57 of 243 evals (23%) named a listed function. Those were the same functions runs with an empty list named (config comment: `my.agent/prove`, `seon.model/provider-error`, `seon.docs/view`, `seon.print/print-value`), most of them the callers/callees of the cited vars. Naming is not a saved read (green 8/15 on, 7/15 off). "Off" shows 0 naming because its list is empty; the off runs' reads are not comparable by this file.

## 5. Proposed pulls

Each goes through the one printer (cuts name the form that reads the rest). Token cost counts a story item that is sent every turn from turn 0 (cached prefix, so the dollar cost is about a tenth of the token count after the first turn, but the context window and history budget feel all of it). A read in the conversation costs its answer in every later turn, so a pull pays when an agent would read it with probability above about (turns left / turns). From the sample, ~13 evals per run, at turn 3: above ~75%; at turn 8: above ~40%.

1. **The proof's test ns form on the screen's `:proofs` (or the story's `:to-prove`), with the helpers the proof's siblings use** (`(source seon.x-test)`: its `:require`s and aliases). Read in 7 of 8 runs (hit rate 88%, before turn 4 mostly). Cost ~220 tokens/turn. Saves ~0.9 reads per run, the one pull well above break-even. Replaces nothing in src; it is one more field of an existing map.
2. **One example test: the nearest existing test in the proof's ns that calls a cited var or a caller of one (through `:form/refs`, the mechanism `callers` uses), cut to 40 lines with its called helpers named, not sourced.** Sibling tests were read 26 times (3.3 per run), 5 on the call graph. Cost ~350 tokens (a median test is 1.2K chars). Saves ~1 read per run (not all 26: only the call-graph ones and the "model my proof on this" ones are predicted; the rest is exploration). Break-even is met if an agent writing a proof reads one in ~3 of 4 runs; the sample says 7 of 8 runs read at least one sibling test.
3. **Callees of the cited vars, one hop, program vars only (not tests, not clojure.core): a line each (name, arglists, first sentence) up to 8, and the source of the first 3 whose source is at most 25 lines.** Callees read by eval: 2 plus about 8 of the 17 one-hop sources. Cost ~30 tokens per line plus ~260 per sourced callee: ~1,000 tokens/turn at most. Saves ~1 read per run, but each callee is read only with probability ~1/3 (five callees, one read), so sourced callees are below break-even; **the lines alone (~240 tokens) pay**, the source stays an eval. Recommendation: lines only.
4. **A caller's call site (the line naming the cited var, plus one line before and after) beside each caller name in `:write-set`.** Callers are already named; 8 to 10 of the 17 one-hop reads were callers' sources, to see how the var is used. Cost ~40 tokens per caller, ≤ 6 callers: ≤ 240 tokens. Saves ~0.5 to 1 read per run (a call site does not replace understanding the caller; half the reads would still happen).
5. **Not recommended: schema forms for keys in cited contracts** (7 reads, one run: ~450 tokens/turn for 1 run in 8), **and the file:line hunt** (a stale task, not a context gap). **Not predictable by any rule: grep (21), other same-ns vars (18), other-ns vars (4)**: this half is the agent exploring; `(my.agent/grep ...)` and `(clojure.repl/source ...)` already answer it in one call.

Summed pulls 1+2+4 and lines of 3: ~1,050 tokens/turn, predicted to remove about 3 reads per run of ~13 evals (about 25%), each worth ~260 tokens kept in the scrollback for the rest of the run. Net: the story grows by ~1.0K tokens (it is 661 tokens for the ref task in `notes/measure/clean-path`, and the whole prompt is 5K to 14K by turn 8 with the conversation as the growing part, 3.0K at turn 6). Expected: prompt tokens per run flat to -10%, turns to green -1 to -3 per run IF the saved reads are not replaced by others; the sample cannot say (no counterfactual run exists). Measure it the way step 6 did (5 tasks x 3 runs, thinking off) with these four pulls as one variant and the reads counted from the stored evals (keep the forms this time: step 6 kept counts only).

## 6. What to keep next time

The step 6 sets saved `evals` and aggregate `naming` counts but not the forms. `prose.clj` and `similar.clj` (notes/step0) already read the records; add a classifier there (source / callers / callees / grep / dir / schema / task) so a run store's reads are counted the day it ends, and file the counts in `notes/measure/`.
