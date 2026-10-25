# Related search, compared: which method finds what a task's work needed (2026-10-25)

Root asked, after the owner: "have you merged the tools and tested any different ways to find the best way to search and show related things (docs, functions, schemas, etc)?"

**Answer.** Fusing the reference graph from the plan's citations with embeddings, by reciprocal rank (`seon.vector/rrf`), finds more of what the work needed than any one method:
- functions: 0.51 at 5 against 0.38 for embeddings alone;
- sections: 0.71–0.75 against 0.67;
- schema keys: 0.56 against 0.37.

Tasks are the exception: every method is weak there (0.28 at best, by embedding).

So the screen shows the five sections, the five functions and the five schema keys nearest the plan, each list fused from graph and embedding. Tasks stay a query (`my.agent/similar`). The branch's defaults now follow this. The data, the scripts and every row are in `notes/related-search/`.

## Method

### Ground truth

Every merge of `jolt/<task>` on main whose task entry has a plan: 31 tasks, from `notes/related-search/truth.py`.
- **The plan.** In 18 tasks it is the plan as the worker got it: the entry at the merge's first parent. In 13 it is the entry at the merge, because the entry was written on its own branch; those plans may already carry the branch's decisions.
- **What the work needed is what the merge changed:**
  - functions: `src` top-level definitions whose text differs;
  - sections: `docs` sections whose text differs;
  - schema keys: `resources/seon/schemas` entries that differ;
  - tasks: other task entries the merge added or changed.
- **Mean per task:** 9.3 functions, 4.3 sections, 3.3 schema keys, 1.8 tasks.
- Items no longer on main are dropped from a task's truth.

### Methods

Each method ranks from the plan text alone, on this worktree's db (main as of the merge of 2026-10-25), in `notes/related-search/search.clj`, run in an agent:
- **embedding:** `seon.vector/near` on the plan's query vector, exact, over:
  - forms (to their vars, tests left out);
  - sections;
  - task plans;
  - schema forms (embedded for the comparison only, since main stores no schema vectors).
- **graph:**
  - the plan's citations (`seon.ref/references`) first;
  - then, by how many seeds reach them:
    - the cited vars' callers and callees (`seon.docs/callers`, `:form/refs`);
    - the sections naming a cited var (`:seon.section/names`);
    - the sections a cited section references, or is referenced by;
    - the schema keys in the plan and in the cited vars' text;
    - the tasks whose references include a cited var.
- **grep:** the plan's words of five or more letters (less a few stop words), ranked by how many each text contains.
- **graph+embedding:** `seon.vector/rrf` of the two (k 10).
- **seeds-then-embedding:** the graph's first three, then embedding's.
- **all three:** `rrf` of graph, embedding and grep.

### Scores

- recall@5 and @10: of what the work needed, the share in the first 5 or 10;
- tokens@5 and @10: the printer's estimate (`seon.print.select/estimate`) of those lines, each a name and its doc's or lead's first sentence (a schema key alone).

## Results

Mean over the tasks with any truth of that kind. All 31 tasks:

| functions (28) | R@5 | R@10 | tokens@5 | tokens@10 |
|---|---|---|---|---|
| embedding | 0.379 | 0.459 | 434 | 862 |
| graph | 0.399 | 0.443 | 244 | 369 |
| grep | 0.315 | 0.418 | 393 | 760 |
| **graph+embedding** | **0.514** | 0.559 | 374 | 672 |
| seeds-then-embedding | 0.419 | 0.525 | 346 | 783 |
| all three | 0.516 | **0.604** | 432 | 795 |

| sections (28) | R@5 | R@10 | tokens@5 | tokens@10 |
|---|---|---|---|---|
| embedding | 0.670 | 0.783 | 392 | 754 |
| graph | 0.402 | 0.541 | 222 | 332 |
| grep | 0.618 | 0.705 | 360 | 766 |
| graph+embedding | 0.713 | 0.753 | 369 | 713 |
| seeds-then-embedding | 0.729 | 0.793 | 368 | 756 |
| **all three** | **0.745** | **0.890** | 362 | 703 |

| schema keys (10) | R@5 | R@10 | tokens@5 | tokens@10 |
|---|---|---|---|---|
| embedding | 0.365 | 0.422 | 34 | 68 |
| graph | 0.343 | 0.560 | 23 | 38 |
| grep | 0.368 | 0.436 | 40 | 81 |
| **graph+embedding** | **0.556** | 0.583 | 32 | 66 |
| seeds-then-embedding | 0.526 | 0.571 | 33 | 66 |
| all three | 0.465 | **0.613** | 35 | 75 |

| tasks (16) | R@5 | R@10 | tokens@5 | tokens@10 |
|---|---|---|---|---|
| **embedding** | **0.284** | 0.319 | 483 | 875 |
| graph | 0.080 | 0.105 | 305 | 449 |
| grep | 0.041 | 0.124 | 552 | 1002 |
| graph+embedding | 0.228 | 0.361 | 490 | 940 |
| all three | 0.122 | **0.450** | 488 | 946 |

On the 18 plans as the worker got them, every method scores lower, the graph most. A plan written at the merge cites what was changed. The ranking holds, though:

| at 5, plans as given | functions (15) | sections (16) | schema keys (6) | tasks (5) |
|---|---|---|---|---|
| embedding | 0.320 | 0.584 | 0.233 | 0.218 |
| graph | 0.237 | 0.219 | 0.238 | 0.032 |
| graph+embedding | 0.356 | 0.551 | **0.426** | **0.236** |
| all three | **0.376** | **0.598** | 0.276 | 0.050 |

The whole table, at 10 too, is in `notes/related-search/table.txt`.

### What it means

- **Graph and embedding find different things.** Fused, they beat either alone by about a third for functions and a half for schema keys.
- **For sections, embedding alone is already strong.** Fusion adds 4–8 points at 5. Grep helps sections at 10 (all three, 0.89), because a section's words are its plan's words.
- **Grep costs a stop-word list,** a literal and a heuristic, for a gain at 10 only. The screen shows 5, so the default leaves grep out: `my.agent/grep` stays a query.
- **Tasks: every method is weak.** What a merge changed in other task entries is mostly follow-up tasks it filed. Plan text does not predict those, so tasks stay a query (`my.agent/similar`).

## Not measured, and the biases

- **The db is main today,** after every one of these merges. The forms and sections the methods read are the changed ones, which flatters embedding and grep. The citations a plan names are as the plan wrote them, so the graph is not flattered; for the 13 merge-time plans it is.
- **Truth is what a merge changed,** not what its worker read. A section read and not edited counts as a miss. This understates every method by the same reads.
- **Recall only.** Precision is not scored, so a list padded with near misses is not penalized here.
- **No agent read these lists in this comparison.** Whether an agent uses them is the paid A/B's question (below).

## The paid A/B so far (related on and off, deepseek, cap 30)

Spent: $0.112 over 4 rounds:

| round | spent |
|---|---|
| pilot | $0.024 |
| on-1 | $0.031 |
| off-1 | $0.024 |
| on-2 (partial) | $0.033 |

Each run's context was checked first through the server (the pilot's screen and its stats), not on the debug page.

**Two of the five step-6 tasks are already fixed on main.** Both print tasks are green at turn 0, so the A/B runs on the three still red:
- unrun-proof;
- transport-failure;
- summary.

| round | green | turns | tokens | spent |
|---|---|---|---|---|
| on-1 | 1 of 3 | 25, 18, 15 | 726k | $0.031 |
| off-1 | 2 of 3 | 23, 17, 14 | 495k | $0.024 |
| on-2 (stopped) | 2 of 3 + 1 hung | –, 7, 26 | 767k | $0.033 |

Too few rounds to say anything yet. Three findings:
1. **The dig-deeper form cost turns.** In the pilot, the unrun-proof run followed `:read` to a related section (`docs/agent.md#what-you-read`). It then spent about 8 evals reading it with `(:text …)`; the key is `:seon.ref/text`. The form is now `(:seon.ref/text (my.agent/help address))`, with the key named.
2. **A run hung.** In on-2, the unrun-proof run hung past its 30-turn cap (36 evals, the agent `:busy`, its stderr empty) for over an hour, and the round never ended. The agents' directory is kept in the session scratchpad. The cause is not found; filed for root.
3. **The rest of the A/B waits for the new defaults.** Measuring the old screen form would measure what no longer merges. Off-2, on-3 and off-3 run after this branch's new defaults (below), about $0.10 more.

## Widened: every schema kind, data and function edges, others' work, renders (root, after the owner)

The same harness ran over 32 merged tasks, with the schema keys split by kind and a config-key column added to the truth. Code: `notes/related-search/search2.clj`; data: `truth2.edn`, `results2.edn`, `table2.txt`.

**The kinds searched.** Every registry entry carries a vector now, from `:schema/form`, embedded on main by this branch:
- 243 Datahike attributes;
- 131 config keys;
- 278 other entries (records, tasks, values).

### Recall at 5, with its tokens

| kind (tasks with truth) | embedding | graph | graph+embedding | tokens@5, fused |
|---|---|---|---|---|
| functions (29) | 0.366 | 0.385 | **0.496** | 366 |
| sections (29) | 0.647 | 0.406 | **0.706** | 367 |
| Datahike attributes (3) | 0.056 | **0.333** | 0.056 (0.556 at 10) | 24 |
| config keys (6) | 0.661 | 0.500 | **0.786** | 45 |
| other registry entries (5) | 0.164 | 0.285 | **0.308** | 27 |
| tasks (16) | **0.284** | 0.080 | 0.228 | 489 |

The schema columns are small, 3 to 6 tasks each. They say which way the methods lean, not by how much.

### Data and function edges

**What the db records today:**
- `:form/refs` holds only vars. The analyzer sees keywords in its `:const` case but records none.
- `:var/contract` is an EDN string. `seon.program/schema-dependents` decodes every contract on each call to find the keys it names.

So neither "the functions a schema or attribute is used by" nor "the schemas a function touches" is a stored edge. The measurement approximates the edge from each function's text: the qualified keywords it names, contract included. 931 of 1,656 functions name at least one registry key, and 592 keys are named by some function.

**The proposal for root's gate.** The smallest fact that would store this edge is `:form/keywords`, `[:set {:seon.db/index true} :qualified-keyword]`:
- the analyzer records the qualified keywords it already walks past in a form's code and contract;
- a function's schemas become one EAVT seek;
- a key's functions become one AVET seek, the same shape as callers on `:form/refs`;
- `schema-dependents` could then read it rather than decode every contract.

### Which neighbours earn their tokens

Each comparison is against the next five of the same fused list:

| shown | added | recall before → after | added tokens | next five instead |
|---|---|---|---|---|
| top 5 functions | the record keys they name | 0.308 → **0.526** | 74 | 0.477 |
| top 5 functions | the Datahike attributes they name | 0.056 → **0.456** | 47 | 0.556 (3 tasks) |
| top 5 functions | the config keys they name | 0.786 → 0.798 | 25 | 0.839 |
| top 5 keys | the functions that name them | 0.496 → 0.503 | 404 | **0.575** for ~305 |

**A function's schema keys earn their tokens:** a few keys, about 5 tokens each, beside each function. **A key's functions do not:** they cost a function's line each and add almost nothing that the fused function list does not already hold.

### Other agents' work

**The truth.** Pairs of merged tasks that changed the same var or section: 28 of 32 tasks have partners, 3.9 each on average.

**How well the others' texts find them.** For a task's plan, the others are ranked by their text's vector:

| the others' text | recall@3 | recall@5 |
|---|---|---|
| plan | 0.277 | 0.402 |
| producer text: plan, merge subject, changed names | 0.300 | 0.430 |
| chance | 0.13 | 0.16 |

So embedding finds about 2.5× chance, and producer text adds 2–3 points. Each branch's plan is already embedded on its branch (this branch), so the plan column is what a live agent can rank today. Producer text would mean embedding each branch's turn prose and changed names at its turns' end. That is a small gain for a new kind of text, so I don't recommend it yet.

### Renders and hiccup

**Renders as definitions** cost nothing new:
- the 178 `seon.render.*` functions are already embedded forms;
- the 28 registry entries declaring `:seon.render/ai` or `:seon.render/html` name their render, and `:form/keywords` would make that schema ↔ render edge one seek.

There is no render-specific truth column. Renders are in the functions column.

**Embedding rendered hiccup output.** Estimated, not measured. Each agent turn renders about five new records (four screen evals and the model's), and its page parts change with them. At the 400–2,000 tokens a record prints (the screen's related eval alone printed 1.3–1.9k characters), that is about 2–10k tokens embedded per agent turn. Each turn's records are new, so nothing is reused, and the vectors would grow with every turn of every run. That is more than everything above combined, re-made every turn. As root expected, renders as definitions and others' plans give the sharing for a fraction of the cost. I recommend against embedding hiccup output.

## What the screen shows now, and what stays a query

- **On the screen,** in `(my.task/related)`, made again only when the plan's vector changes, k 5 each (`:seon.config.render/related`):
  - the sections nearest the plan, fused from graph and embedding (about 370 tokens);
  - the functions nearest it, fused the same way (about 370 tokens);
  - the schema keys, fused the same way (about 33 tokens).
- **Left out of the screen,** as the story already shows them: what the plan cites.
- **Queries:**
  - `(my.agent/related "words" k)`: sections by any text;
  - `(my.agent/similar "words")`: functions, sections and tasks by meaning;
  - `(my.agent/grep "text")`: text;
  - `callers` and `callees`: the graph;
  - tasks.
