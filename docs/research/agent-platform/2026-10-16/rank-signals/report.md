---
title: "Rank signals: what predicts the declarations a task needs (offline, from git history)"
type: research
status: lane rank-signals (Opus), 2026-10-16; offline experiment, nothing built into Seon
---

# Rank signals

Owner, 2026-10-16: get signal for ranking context from what we index about the graph, from ordinary embeddings, and
from something unique to Seon. This measures it on our own history before anything is built.

## Benchmark

- **Source.** `git log` of this repository. A candidate is a non-merge commit that changes `src/**/*.clj` and `test/**/*.clj`
  (3,549 candidates).
- **Two sets.**
  - *task*: 139 commits that delete a filed task from `resources/seon/tasks/*.edn`. The query is the deleted task's
    `:seon.task/plan` (newer format) or `:intent` plus `:evidence` (older format). These texts were written before the
    work, so they are the honest set.
  - *message*: 320 random other candidates (seed 7). The query is the commit subject and body. It is written after the
    fact and often names the changed symbols, so it overstates plan references. Treat it as an upper bound.
- **Ground truth.** The top-level `src` declarations at the parent whose lines the commit's `-U0` diff touches,
  computed from clj-kondo `:row`/`:end-row` of each var definition. Declarations the commit adds are not in the
  candidate universe and are not counted.
- **Filters.** Instances with 0 retrievable declarations or more than 15 were dropped. That leaves 382 instances:
  103 task, 277 message, and 2 live.
  - Median ground truth: 3 declarations.
  - Median candidate universe: 3,812 declarations; random R@10 is about 0.003.
- **Split.** By time within each set: the oldest 60% train and the newest 40% test (task 61/42, message 166/111).
  Each task is retired once, so the split is also by task.
- **Live set.** The four readable attempts of `seon.ref/wildcard-citations-are-prose` (`context-patterns.edn`), at
  b77f17413, the last commit before the 2026-10-15 run. There are two targets:
  - *live-owner*: `seon.ref/span-candidate`, the owner of the fix;
  - *live-reads*: the 10 `seon.ref` declarations the agents read or probed.

  Its "test calls" are the calls of the proof the agents wrote (`seon.ref/references`).
- **Candidate universe.** Every top-level declaration in `git archive <parent> src`, analysed by clj-kondo in a scratch
  directory. The working tree was never checked out.

## Signals

Each signal is computed at the parent commit.

| signal | what it scores |
|---|---|
| `plan_ref_bt` / `plan_ref_any` | backticked, or any, fully qualified symbols in the query (exact match) |
| `task_must_read` | the older task format's authored `:must-read` set |
| `plan_ns` | declarations in namespaces the query mentions |
| `graph_plan` | plan refs plus their 1-hop callers and callees, and a degree-normalised 2-hop, from kondo `var-usages` |
| `test_calls` / `test_reach` / `graph_test` | src declarations called by the deftests the commit adds or changes; their transitive callees decayed by depth; their 1/2-hop neighbourhood |
| `schema_contract` / `schema_text` | namespaced keywords in the query that appear in a declaration's `:malli/schema` attribute map, or anywhere in its source |
| `embed_src_{code,search}` | cosine between the query (EmbeddingGemma 2 prefix `code retrieval` or `search result`) and the declaration source (`document` prefix, 256 dimensions, 2,048-token cap); 28,423 distinct forms, embedded in 196 s |
| `embed_doc_*` | the same, against docstrings only |
| `cochange` | declarations that changed together with the plan refs or test calls in earlier commits (git hunk headers with a column-0 `(def` xfuncname; commits with more than 30 declarations skipped; history strictly before the parent's commit time) |
| `recency` | declarations changed in the 20 commits before the parent |
| `task_citation` (unique, offline) | declarations whose source cites the task's `ns/name` at the parent (CLAUDE.md: a definition cites its tasks) |
| combinations | `RRF4_untrained` (reciprocal-rank fusion of `plan_ref_any`, `graph_plan`, `embed_src_code` and `test_reach`); `COMBINED_lr` (logistic regression); `COMBINED_gbt` (gradient boosting on score plus 1/rank per signal, log tokens, private flag, trained on the train split only) |

## Held-out results, task set (n = 42; the honest set)

R@k is the mean fraction of ground truth in the top k. R@2k, 4k and 8k are the same within a token budget of the
rendered source (4 chars per token). "Reach" is the share of the ground truth the signal scores above zero at all.

| signal | R@5 | R@10 | R@20 | MRR | R@2k | R@4k | R@8k | reach | R@10 95% CI |
|---|---|---|---|---|---|---|---|---|---|
| **COMBINED_gbt** | **0.406** | **0.457** | **0.537** | **0.561** | 0.277 | 0.390 | 0.471 | | 0.34-0.58 |
| RRF4_untrained | 0.348 | 0.441 | 0.505 | 0.539 | **0.314** | 0.352 | 0.425 | | 0.34-0.55 |
| COMBINED_lr | 0.340 | 0.391 | 0.495 | 0.466 | 0.250 | 0.330 | 0.383 | | 0.29-0.51 |
| graph_plan | 0.333 | 0.386 | 0.455 | 0.423 | 0.234 | 0.318 | 0.400 | 0.654 | 0.29-0.48 |
| plan_ref_bt | 0.286 | 0.330 | 0.330 | 0.459 | 0.241 | 0.282 | 0.322 | 0.322 | 0.24-0.43 |
| plan_ref_any | 0.286 | 0.322 | 0.330 | 0.450 | 0.229 | 0.274 | 0.322 | 0.322 | |
| embed_src_search | 0.213 | 0.274 | 0.351 | 0.339 | 0.125 | 0.196 | 0.232 | 1.0 | 0.17-0.38 |
| embed_src_code | 0.205 | 0.259 | 0.350 | 0.326 | 0.139 | 0.184 | 0.233 | 1.0 | |
| test_reach | 0.162 | 0.234 | 0.263 | 0.192 | 0.156 | 0.228 | 0.276 | 0.506 | |
| embed_doc_code | 0.163 | 0.227 | 0.279 | 0.287 | 0.162 | 0.194 | 0.255 | 0.911 | |
| test_calls | 0.134 | 0.182 | 0.189 | 0.181 | 0.140 | 0.165 | 0.189 | 0.196 | |
| schema_text | 0.124 | 0.146 | 0.220 | 0.153 | 0.081 | 0.120 | 0.139 | 0.394 | |
| graph_test | 0.104 | 0.136 | 0.196 | 0.113 | 0.064 | 0.104 | 0.151 | 0.724 | |
| cochange | 0.072 | 0.074 | 0.114 | 0.110 | 0.020 | 0.067 | 0.072 | 0.549 | |
| plan_ns | 0.024 | 0.067 | 0.193 | 0.055 | 0.060 | 0.117 | 0.253 | 0.699 | |
| schema_contract | 0.040 | 0.052 | 0.060 | 0.046 | 0.040 | 0.052 | 0.060 | 0.075 | |
| task_citation | 0.016 | 0.024 | 0.024 | 0.076 | 0.014 | 0.024 | 0.024 | 0.016 | |
| recency | 0.000 | 0.000 | 0.044 | 0.012 | 0.000 | 0.012 | 0.033 | 0.095 | |
| random | 0.000 | 0.008 | 0.008 | 0.005 | | | | 0 | |

`task_must_read` exists only in the older task format, so all of it falls in the train split; there are none in task
test.

**Ablation (GBT minus a group, task test, R@10).** Every result lies inside the combined model's CI (0.34-0.58), so
none is significant at n = 42.

| removed | R@10 |
|---|---|
| embed | 0.414 |
| test | 0.431 |
| cochange | 0.441 |
| refs | 0.458 (MRR 0.561 → 0.499) |
| graph, schema, recency, task_citation | 0.45-0.48 |

Embeddings and the test signals carry the most unique information beyond the plan refs.

## Held-out, message set (n = 111; post-hoc queries, upper bound)

| signal | R@5 | R@10 | R@20 | MRR | R@4k |
|---|---|---|---|---|---|
| COMBINED_gbt | 0.483 | 0.588 | 0.706 | 0.688 | 0.462 |
| RRF4_untrained | 0.469 | 0.583 | 0.665 | 0.633 | 0.514 |
| embed_src_code | 0.341 | 0.435 | 0.554 | 0.448 | 0.376 |
| graph_plan | 0.216 | 0.252 | 0.284 | 0.308 | 0.241 |
| test_reach | 0.168 | 0.234 | 0.299 | 0.227 | 0.229 |
| plan_ref_any | 0.189 | 0.196 | 0.201 | 0.328 | 0.192 |

- **Embeddings are the strongest single signal here.** Removing them drops GBT R@10 from 0.588 to 0.446, a drop larger
  than the CI.
- **Commit messages rarely backtick.** `plan_ref_bt` scores 0.028 here, against 0.33 on task plans.

## Live set (one task, two targets)

- **The owner is found.** `seon.ref/span-candidate` is rank 1 by `plan_ref_any`, `graph_plan` and `embed_src_*`
  (MRR 1.0), and by GBT and RRF.
- **The agents' reads are mostly found.** Of the 10 declarations they read, RRF4 puts 0.80 in the top 10, GBT 0.85,
  `graph_plan` 0.80 and `test_reach` 0.80.

So in that run the right context was cheaply rankable at the start: about 3k tokens of source would have pre-empted
most of the 42 targeted reads and 16 re-reads (context-patterns P1). One task is anecdote, not a rate.

## What this says

1. **Plan references are the most precise signal.** Their MRR is 0.46 on tasks: when a plan names the owner, it is
   usually right. But they reach only 32% of the ground truth.
   - The graph neighbourhood of the refs (`graph_plan`) doubles reach to 65% and is the best single signal on R@10.
   - Typed plans (E1) pay off twice: once to the reader, and again as the seed for graph expansion.
2. **Embeddings are complementary, not a replacement.** On tasks they are weaker than refs plus graph (0.27 against
   0.39 R@10), but they are the only signal with full reach. They carry the post-hoc set and the unnamed ground truth.
   - Source text beats docstring text.
   - The `search result` and `code retrieval` query prefixes are equal.
3. **The structural union generates candidates; it does not rank them.** The union of every non-embedding signal
   reaches 93% of the task ground truth, but with a median of about 2,000 candidates. Ranking inside that set is the
   remaining problem, and that is what the combination buys.
4. **A trained combination helps modestly; the untrained fusion is nearly as good.** GBT reaches R@10 0.457 against
   RRF4's 0.441 and the best single signal's 0.386.
   - At the 2k-token budget, RRF4 is the best (0.314).
   - With 61 training tasks, RRF4 is the robust choice; GBT is not significantly better.
5. **Weak or useless here:**
   - `schema_contract`: few task plans name a schema keyword that appears in a contract.
   - `cochange`: hunk-header co-change is noisy at our churn.
   - `recency`.
   - `task_citation`: tasks are rarely cited in docstrings before they are retired, with 1.6% reach. The docstring
     convention is too new to carry signal.
6. **Ground truth is about half "named or adjacent" and half incidental.** For 69% of task test commits the commit also
   adds new declarations, which no ranking over the parent can retrieve. The effective ceiling at R@10 is well below 1.

## The unique signal: dynamic reach is not obtainable for history

The query was run read-only, in JVM mode on default (`:seon.test.member/top`, `:dynamic-calls`, `:calls`). It found:

- `:seon.test.member/top` on only 23 members (20 tests);
- `:dynamic-calls` on 7 members;
- `:calls` stored only as a count.

Each `top` is the 10 hottest vars by call count: `seon.cache/cache?`, `seon.cache/claim`, `seon.db/database-value?` and
the like. That is plumbing, not relevance; it is saved in `dynamic_top_main_2026-10-16.json`. Stores are reset often,
so there is no evidence for any historical parent, and spectrum-based fault localisation (red/green against called
vars) has no input at all.

What would make it measurable:

- per test member, the full **set** of distinct called declarations (digests, not counts), and
- for a red run, the same set from its last green run,

both kept durable on main across resets, keyed by the commit or program identity. With that,
`test_reach` (0.23 R@10, static) could be compared with dynamic reach, and Ochiai suspiciousness could be scored on
the instances whose proof was red at the parent.

The static `test_reach` that kondo provides offline already scores 0.80 R@10 on the live reads.

## Leakage audit

- **Signals read the parent tree or history strictly before the parent's commit time.**
  - Co-change and recency exclude the instance commit.
  - Commit time stands in for ancestry; a rebased commit with an earlier time could leak, which is negligible on this
    mostly linear history.
- **Test signals read the commit's own added or changed deftests.** This is admissible only under the TASK-CHAIN model,
  where the proof exists before the body. Without it, `test_*` must be dropped; GBT minus test still scores 0.431 R@10.
- **Message-set queries are post-hoc.** Their plan-ref numbers are optimistic.
- **The combination is trained on the train split only, by time.** Embedding weights are a fixed pretrained model.

## Next experiment

1. **Two-stage ranking.** Generate candidates from the structural union (refs, graph, test reach), rerank with
   embeddings plus a cross-encoder (Qwen3-Reranker, from the CLM survey), and measure R@4k on task test.
2. **Embed with structure.** Prepend each declaration's callers and callees names to its text. Also embed the
   *proof test source* as a second query.
3. **Replay check.** Feed the top 4k tokens of RRF4 as must-reads into the 5-run replay bench (self-improvement-loop.md)
   on 5 replay tasks, and count turns against the current must-reads. Only turns saved prove the ranking matters.
4. **Instrument dynamic reach.** Record the durable dynamic call set per test member (above), then re-run this
   harness with `dynamic_reach` and Ochiai on the next 2 weeks of retired tasks.

## Reproduce

All scripts are in this directory. Scratch data (pickles, texts, embeddings) goes in a scratch dir `$S`.

```sh
python3 mine_commits.py $S/cand.jsonl
bb deleted_tasks.bb $S/cand.jsonl $S/deleted.jsonl
python3 build.py $S $S/inst 320        # ~13 min, 8 workers, clj-kondo on git archive of each parent
python3 live_instance.py $S
python3 cochange_history.py $S/cochange.jsonl   # 3 s
# parent_times.json: `git show -s --format='%H %ct'` of each parent (inline in the lane run)
python3 prep_embed.py $S
# embed queries (--task code and --task query), docs and forms (--task document, --max-tokens 2048) with
# embedding/fast/embed_batch.py --dim 256 --cache $S/ecache --dtype float32, outputs $S/emb_{queries_code,queries_search,docs,forms}.safetensors
~/src/embed-models/embeddinggemma-2/.venv/bin/python -I signals.py $S $S/run   # 75 s
```

Files:

- `instances.jsonl`: 382 instances, with query, ground truth, test calls, split and universe size.
- `results.jsonl` and `results.txt`: every signal, split and set.
- `split.json`.
- `dynamic_top_main_2026-10-16.json`.
