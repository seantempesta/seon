---
type: research
status: measured
lane: predictability
date: 2026-09-26
---

# How predictable is an agent's next step? Measured on our own trajectories

**Answer in one paragraph.** An agent's next target is mostly something it
already touched. The previous step's file is the next step's file 46% of the
time, and 82% of file targets were touched earlier in the same session. A
logistic ensemble of cheap signals (recency, Markov transitions, files named in
recent tool results, the task text) ranks the next file at **37% top-1, 64%
top-5, 74% top-10, 90% top-50**. Recency alone reaches 36/61/70%. The agents'
own visible prose almost never names the next target: when text precedes a
step, it names that step's file only 10% of the time, and only 1.3% of all file
steps. The hard cases are the **first touches of a file** (18% of file steps,
40% of symbol steps). Nothing cheap ranks these well (8% top-10). They come from
earlier tool results (58%) or the assignment (19%), and for 32% there is no
visible source at all, mostly files being created. Zero-shot CLM-8B loses badly
to the cheap ensemble (8% vs 36% top-1 over the same 50-candidate pools). The
one exception is first touches, where it is 23% vs 10% top-10, but on only 70
steps.

All numbers below are measured on the test split, and every script is in
`tmp/next-turn-predictability/`. "Estimate" marks the few numbers that are not
measured.

## Data

| corpus | sessions | steps (tool calls) | source |
|---|---:|---:|---|
| Claude Code main sessions (`cc`) | 38 | 33,665 | `~/.claude/projects/-Users-sean-src-seon/*.jsonl` (sidechain lines skipped) |
| Claude Code subagents (`cc-sub`) | 1,130 | 109,638 | `…/<session>/subagents/*.jsonl` |
| Codex sessions, cwd = this repo (`codex`) | 2,798 | 287,500 | `~/.codex/sessions/**/*.jsonl` (2,817 of 2,987 files have this cwd; 6.7 GB) |
| **total** | **3,966** | **430,803** | 2026-07-15 → 2026-09-26 |

- **Extraction.** `extract.py` streams every file line by line (10 processes,
  about 25 s wall for about 11 GB) into `steps/` (362 MB). One step is one tool
  call. For Codex, one step is one inner `tools.X(...)` call of a JS `exec`
  cell. The Codex `wait` cell polls (which only collect output) are dropped.
- **What each step holds.** Each step records:
  - its tool;
  - a **kind** (read, search, edit, eval, test, git, shell, orchestrate, wait, lifecycle, meta, web, status, other);
  - the files it touched, its search patterns, and resolved Clojure symbols;
  - the visible assistant text/thinking since the previous call (`pre`);
  - files and symbols named in its tool result (first 40 k chars);
  - the running list of files and symbols named in user/assignment messages.
- **How Bash and exec commands are classified.** Commands are split on `\n ; && || |`.
  - `sed -n`, `cat`, `nl` and `head` are reads. `rg` and `grep` are searches (their pattern and scope are kept).
  - `sed -i`, heredoc Python that writes, and `>` into a repo path are edits.
  - `bin/test*` is a test. `clojure`/`bb` is an eval.
  - Filters after a pipe are ignored.
- **Symbols.** Code in `eval_clj` forms, edit hunks, `apply_patch` hunks and
  `rg` patterns is tokenized. Tokens are resolved against a static index of the
  current checkout (`build_graph.py`: 434 namespaces, 7,166 defs, 28,218 def→def
  reference edges, 2,743 require edges and 73 src↔test pairs, from regex over
  `src/` and `test/`). Qualified `alias/name` is resolved through the repo's
  `:as` table. A bare name counts only in its file's namespace or when it is
  unique.
- **Split.** Sessions are ordered by start time. The earliest 3,137 sessions
  (323,327 steps) train and the 829 sessions starting after
  **2026-09-16T22:46Z** test (107,476 steps: cc 13,373 / cc-sub 56,181 /
  codex 37,922). Predictors see train statistics plus only the test session's
  own past.
- **Split of the train set for the ensemble.** The ensemble's weights are fit on
  the last 25% of train. Its Markov and prior counts come from the first 75%.
  Fitting on the same sessions leaked the answer into those features: the first
  run scored 27% top-1, below recency.

## Method

A hit@k means that any of the step's targets is in the predictor's first k
candidates. The targets are:

- a step's files (all of them; a multi-file shell step is lenient);
- its first 3 resolved symbols;
- its first 3 namespaces.

"First" is the first call of a parallel batch, a genuine decision after seeing
results. "In-batch" steps were decided together with the step before them.

Cheap candidate sources, each ranked:

| source | what it proposes |
|---|---|
| `mru` | the session's touched targets, most recent first |
| `sessfreq` | the session's most frequent targets |
| `markov` | train transitions from the last target |
| `res1` / `res3` | targets named in the previous 1 / 3 tool results |
| `pre` | targets named in the agent's own visible text/thinking just before the call: paths, namespaces, unique basenames, `ns/sym`, backticked names (the "announcement extractor") |
| `user` | targets named in user/assignment messages so far |
| `graph` | require/test neighbours (files) or callee/caller defs (symbols) of the last targets, ranked by prior |
| `prior` | globally frequent in train |
| `cascade` | fixed interleave: pre → res1 → mru → markov → graph → prior |
| **`ENSEMBLE`** | logistic regression (IRLS, 53 rank-bucket features) over the union of all sources |

## Results

### (a) Next tool kind and tool name

There are 14 kinds and 78 raw tool names. The table below covers all 106,647
test steps.

| predictor | kind @1 | kind @5 | tool @1 | tool @5 |
|---|---:|---:|---:|---:|
| prior | 29.3 | 77.7 | 25.6 | 92.0 |
| repeat last | 35.3 | 82.0 | 69.8 | 93.4 |
| Markov-1 | 36.7 | 82.6 | 73.6 | 95.9 |
| **Markov-2** | **38.4** | **84.9** | **77.1** | 96.3 |
| naive Bayes (visible pre-text words + last 2 kinds) | 37.5 | 84.2 | 73.4 | 95.7 |
| NB, only steps with ≥40 chars of visible pre-text (13,858) | 32.5 | 84.4 | 72.1 | 89.0 |

The raw tool is easy because Bash/`exec_command` dominate. The kind (read,
search, edit, eval…) is only about 38% predictable top-1. The words the agent
writes before a call add +3 points over Markov where text exists (32.5 vs 29.3).

### (b) Next file (63,143 test steps that touch a file)

| predictor | @1 | @5 | @10 | @50 |
|---|---:|---:|---:|---:|
| prior | 6.5 | 17.9 | 20.8 | 35.1 |
| graph neighbour of last file | 3.4 | 10.8 | 17.3 | 21.6 |
| **pre-text mention (announcement)** | **0.9** | **1.3** | **1.3** | **1.3** |
| named in the user/assignment text | 7.1 | 30.5 | 45.4 | 53.9 |
| named in last tool result | 15.9 | 30.3 | 34.3 | 36.3 |
| named in last 3 tool results | 19.1 | 39.8 | 47.5 | 52.5 |
| Markov (last file → next file) | 25.0 | 34.4 | 39.0 | 43.1 |
| session frequency | 21.3 | 45.3 | 57.2 | 67.7 |
| **recency (MRU)** | **35.6** | **60.8** | **69.6** | 76.2 |
| cascade | 26.3 | 55.0 | 68.1 | 78.3 |
| **ENSEMBLE** | **36.7** | **64.2** | **73.6** | **90.0** |

The union of all sources contains a target 91.0% of the time, from about 66
candidates.

Ensemble accuracy by slice:

| slice | n | @1 | @5 | @10 | @50 |
|---|---:|---:|---:|---:|---:|
| cc (orchestrator) | 6,172 | 26.3 | 53.6 | 65.3 | 83.9 |
| cc-sub | 34,195 | 37.3 | 62.8 | 71.1 | 89.0 |
| codex | 22,776 | 38.7 | 69.2 | 79.5 | 93.3 |
| next step is a read | 31,153 | 37.7 | 66.0 | 74.9 | 91.5 |
| next step is a search | 11,693 | 24.6 | 50.7 | 62.0 | 86.5 |
| next step is an edit | 9,917 | 36.3 | 63.7 | 73.8 | 87.6 |
| **file touched again (revisit)** | 51,924 | 44.6 | 77.5 | 87.7 | 98.3 |
| **file new to the session** | 11,219 | **0.1** | **2.8** | **8.0** | 51.8 |
| steps with ≥40 chars of visible pre-text | 7,040 | 28.6 | 57.6 | 69.0 | 87.2 |

On those pre-text steps, `pre` alone is 6.9 @1 and 10.0 any.

### (c) Next Clojure symbol and namespace

| target | n | MRU @1/@10 | Markov @1/@10 | graph @1/@10 | pre @10 | **ENSEMBLE @1 / @5 / @10 / @50** | union covers |
|---|---:|---|---|---|---:|---|---:|
| symbol (`ns/name`) | 26,699 | 18.6 / 42.0 | 11.4 / 18.2 | 6.3 / 13.1 | 1.0 | **20.5 / 39.8 / 49.2 / 68.4** | 71.8 |
| symbol, when the step is an eval | 6,412 | 36.3 / 68.7 | | | | **41.3 / 66.2 / 75.6 / 90.4** | |
| symbol, when the step is an edit | 8,424 | 13.1 / 44.2 | | | | 17.1 / 41.3 / 54.6 / 79.3 | |
| symbol new to the session | 10,819 | – | | | | 0.7 / 4.2 / 10.3 / 31.3 | 36.9 |
| namespace | 51,601 | 41.7 / 83.5 | 40.3 / 71.4 | 13.7 / 51.1 | 2.2 | **45.1 / 79.6 / 88.6 / 97.7** | 97.7 |

REPL evaluations are the most predictable action. The symbols an agent
evaluates next are mostly symbols it just evaluated, read or edited: 41% top-1
and 76% top-10.

### (d) Is the next target "near" the last one?

Each column counts file steps whose target meets the condition; conditions
overlap. The file graph has a median of 6 require/test neighbours per file and
the symbol graph a median of 5 callee/caller neighbours.

| relation to the last-touched target | file | symbol | namespace |
|---|---:|---:|---:|
| same target as the last step | 46.5 | 23.5 | 54.1 |
| same namespace (symbols) / same directory (files) | 58.8 dir | 42.0 | – |
| graph neighbour (requires / refs / callers) | 26.6 | 18.5 | 72.4 |
| test ↔ source pair | 5.3 | 0.8 | – |
| among the last 5 distinct targets | 60.8 | 34.1 | 74.3 |
| touched anywhere earlier in the session | 82.2 | 59.5 | 90.6 |
| named in the last 3 tool results | 56.2 | 40.2 | 74.7 |
| named in the user/assignment text | 53.9 | 14.9 | 64.9 |
| **named in the agent's own preceding text** | **1.3** | **1.0** | **2.2** |

The exclusive cascade assigns each step its first matching reason, in the
order: same, seen, graph, recent, directory, results, user, pre.

- **Files:**
  - same 46.5;
  - seen earlier 13.3;
  - graph neighbour 10.0;
  - recent 5 8.9;
  - same directory 7.4;
  - results 4.0;
  - user 1.8;
  - test pair 1.1;
  - pre 0.1;
  - **novel 7.0**.
- **Symbols:**
  - same 23.5;
  - seen 16.3;
  - same namespace 14.3;
  - graph 9.1;
  - results 7.5;
  - recent 6.5;
  - user 1.6;
  - **novel 20.5**.

### Does the agent announce its next target?

Rarely, in the text we can see.

| measure | value |
|---|---:|
| batch-first steps with ≥40 chars of visible pre-text | 5,746 |
| that pre-text names any file at all | 30.9% |
| … names **this** step's file | 9.8% |
| … names a file touched within the **next 6** steps | 15.2% |
| Claude thinking blocks with visible text (all transcripts) | 22,337 / 84,783 = **26%** |

- **Claude.** Thinking is redacted in 74% of blocks.
- **Codex.** Reasoning is encrypted. Only its short commentary messages are visible.
- **Seon's own agents.** Their provider reasoning is recorded
  (`:seon.ai.attempt/reasoning`, README), so this could differ there. That is
  worth one probe on recorded Seon turns before any extractor is built.
- **Visible prose.** It says things like "let me look at what its signature
  expects" and names the function only a tenth of the time. The call itself
  carries the target. A cheap extractor over visible prose is **not**
  competitive with recency: 1.3% of all file steps, against 35.6% for recency.

### Reads versus actions

Reads are read plus search; actions are edit, eval and test.

| corpus | steps | reads | actions | reads per action | share of steps that are reads |
|---|---:|---:|---:|---:|---:|
| all | 430,803 | 187,931 | 108,044 | **1.74** | 43.6% |
| cc (orchestrators) | 33,665 | 9,959 | 9,875 | 1.01 | 29.6% |
| cc-sub (subagents) | 109,638 | 56,835 | 33,726 | 1.69 | 51.8% |
| codex | 287,500 | 121,137 | 64,443 | 1.88 | 42.1% (+13.8% wait) |

- **Re-reads.** Of 163,786 read/search steps that name a file, **68% touch a
  file the same session already read** and 43% one it read within the last 5
  steps. Only 3.5% re-read after the agent's own edit (`rereads.py`). Re-reads
  mostly fetch another slice of a file already partly seen (`sed -n` ranges,
  `rg` within one file). Whether the ranges overlap was not measured.
- **Could the read have been predicted from the previous 3 steps?** This
  covers 43,540 test read/search steps (`predict.py`, `reads_predictable`).

  | candidate set from the previous 3 steps | read targets covered |
  |---|---:|
  | their files | 51.0% |
  | files named in their results | 59.1% |
  | graph neighbours of their files | 36.9% |
  | the union of those three (mean **105** candidate files) | 76.3% |
  | + visible pre-text | 76.6% |
  | + assignment text | 85.3% |
  | **first reads only** (21%): union / + assignment | 45.5% / 61.5% |

- **Ranked by the ensemble.** Read targets land in the top-10 75% of the time
  and in the top-50 92% of the time.

### Where first touches come from

These are the 20,872 test steps that touch a file new to their session. The
first four rows overlap; the last row is exclusive.

| earlier channel that already named it | all | read | search | edit |
|---|---:|---:|---:|---:|
| a tool result earlier in the session | 57.7 | 64.2 | 68.1 | 12.7 |
| **only** a tool result | 26.2 | 30.5 | 26.8 | 6.1 |
| the assignment/user text | 18.5 | 20.1 | 13.3 | 19.6 |
| a graph neighbour of an earlier-touched file | 26.9 | 27.3 | 37.9 | 7.4 |
| the agent's own earlier visible text | 1.2 | 1.3 | 1.1 | 0.8 |
| **nowhere** | 32.4 | 26.2 | 27.1 | **67.9** (mostly created files) |

When a result named the file first, the median lag from that result to the
first touch is 16 steps (56 for the orchestrator).

### CLM-8B (MLX) against the baselines

`clm_rerank.py` samples 300 batch-first test file steps: 206 cc-sub, 89 codex
and 5 cc, one per session where possible. It reranks each step's ensemble top-50
pool, which contains the target 91% of the time. The inputs are:

- **State:** the assignment (1,500 chars), the last 6 steps as one-line
  summaries, and the visible pre-text (800 chars).
- **Candidates:** `Open <path> (namespace …): <ns docstring or md title>`.
- **Instruction:** "Which file will the agent open or edit next?"

| ranker (same 50-candidate pools) | @1 | @5 | @10 | first touches only, n=70: @1 / @10 |
|---|---:|---:|---:|---|
| recency (MRU) | 34.0 | 63.0 | 71.7 | – |
| ENSEMBLE | **35.7** | **66.7** | **75.7** | 0.0 / 10.0 |
| CLM-8B zero-shot | 8.0 | 21.3 | 37.0 | 4.3 / **22.9** |
| RRF(ensemble, CLM) | 17.0 | 47.3 | 64.3 | 2.9 / 14.3 |

- **Random baseline.** Random @10 is about 18% (0.91 × 10/50, an estimate).
  CLM carries some signal, but it cannot see recency, which is most of the
  answer.
- **Fusing CLM with the ensemble.** Naive fusion destroys the ensemble's top ranks.
- **First touches.** Only there did CLM beat the ensemble, but 70 steps give
  a wide interval (roughly ±10 points).
- **Cost.** Model load took 2–4 s. Scoring took a median 0.53–0.63 s per step
  with 50 uncached cards, and 3.6 min for 300 steps. The cheap ensemble takes
  about 1 ms per step in Python (97 s for 3 items × 107 k test steps, including
  fitting).

## What the numbers imply

**For context selection:**

1. **The working set is most of the answer.** 82% of file targets and 60% of
   symbol targets were already touched in the session, and the last target
   alone is 46% / 24%. Keep the session's touched entities rendered and current;
   the owner's render LRU is the right shape. That is cheaper and better than
   predicting.
2. **Rank the working set by recency plus Markov; add the assignment and recent
   results.** This logistic mix of about 50 weights beats every single source.
   It is "System 1" at 1 ms and needs no model. Its top-10 covers 74% of next
   files and 76% of next eval symbols.
3. **Don't build a "parse the agent's plan" extractor from visible text.** In
   these corpora it finds the next target 1% of the time. Recorded reasoning in
   Seon may differ: probe it before building anything.
4. **The task text matters.** It names 54% of all file targets and 18.5% of
   first touches. Rendering what the assignment names, at turn 0, is a large,
   cheap win, consistent with the "context from the task" ruling.
5. **The static graph is a weak predictor by itself** (file 21.6% any; symbol
   neighbours 18.5%). It works as a feature, not as a walk that dumps the
   neighbourhood. A 2-hop walk over requires would be much wider than the 6
   median neighbours, for 27% coverage.
6. **First touches are where better context would pay.** They are 18% of file
   steps, 40% of symbol steps, and 21% of reads. Cheap predictors fail on them
   (8% top-10), and 58% were first seen in an earlier tool result. A semantic
   ranker (CLM, zero-shot 23% top-10 on n=70) is only worth testing there,
   after fine-tuning on these trajectories. It should be gated: recency first,
   semantics only for the unseen remainder.

**For prefetching:**

- **Budget.** Prefetching the ensemble's top 10 files would have covered 74% of
  next file touches, the top 20 83%, and the top 50 90%.
- **Reads saved.** Reads are 44% of all steps (1.74 per action). If every
  predicted read were served (an estimate that assumes a hit removes the call
  and ignores partial-range re-reads), a 10-file budget would remove about 0.75
  × 44% ≈ **one third of all steps**.
- **Re-reads.** 68% of reads are re-reads of files already read this session, so
  re-reads are the first target. Show the current slice of what is in the
  working set instead of making the agent `sed -n` it again.

## Limits

- **Symbols are resolved against today's checkout.** Deleted vars count only as
  plain qualified names, and bare names only when unique. The symbol and graph
  numbers are therefore lower bounds, and the graph is regex-built, not
  clj-kondo.
- **Bash and exec classification is heuristic.**
  - A multi-command step that touches several files counts as a hit if any file
    is predicted, which is lenient.
  - A `>` into a repo path is an edit even when it writes a scratch report
    under `tmp/`, although `tmp/` targets are excluded from that rule.
- **"Named in text" only sees paths, namespaces, unique basenames and qualified
  or backticked symbols.** Prose references like "the walk" are missed, so the
  pre-text and assignment numbers are lower bounds.
- **Hidden reasoning is invisible.** 74% of Claude thinking is empty and Codex
  reasoning is encrypted, so the "announcement" result covers visible text only.
- **Composition and split.** The test set is 52% Claude subagents, and results
  differ by agent type; see the slice rows. The split is by session start, and
  long main sessions that span the cutoff count wholly as train.
- **CLM sample.** CLM was zero-shot, with one card format and one prompt, on
  300 steps. It was not tuned; this is a floor for "semantic scoring", not a
  verdict.
- **What "predicted" measures.** A predicted read is a ranking hit, not proof
  that the read was unnecessary. The agent may need a different slice, or may
  be re-verifying.

## Reproduce

Run from the repo root:

```bash
python3 tmp/next-turn-predictability/build_graph.py            # graph.json (0.7 s)
python3 tmp/next-turn-predictability/extract.py 10             # steps/ (≈25 s wall, 10 procs)
src-clm/.venv/bin/python tmp/next-turn-predictability/predict.py     # results.json (≈95 s)
src-clm/.venv/bin/python tmp/next-turn-predictability/provenance.py  # provenance.json (≈11 s)
src-clm/.venv/bin/python tmp/next-turn-predictability/rereads.py     # rereads.json
src-clm/.venv/bin/python tmp/next-turn-predictability/clm_rerank.py sample   # clm_sample.json (≈33 s)
src-clm/.venv/bin/python tmp/next-turn-predictability/clm_rerank.py score    # clm_results.json (≈3.7 min, MLX)
python3 tmp/next-turn-predictability/show.py; python3 tmp/next-turn-predictability/show2.py   # tables
```
