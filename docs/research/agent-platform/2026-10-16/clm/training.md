---
title: CLM on MLX - memory, context length, a head trainer, and how many examples
date: 2026-10-16
tags: [research, context, agent-platform]
---

# CLM on MLX: memory, context length, head trainer, example counts

Lane clm-train. Machine: 128 GB Apple silicon, mlx 0.31.3. Everything below was run; scripts are under
`src-clm/scripts/` and the trainer is `src-clm/src/seon_clm/train/mlx_finetune.py`. Scratch data is in `tmp/clm-train/` (gitignored).

## 1. Memory: yes, the 8B encoder really takes about 16 GB, and 8-bit costs nothing

Qwen3-8B has 8.19 B parameters; bf16 is 2 bytes each, so the weights are 16.4 GB. `mx.get_active_memory()` after load reads 16.38 GB, and an
encode adds only 0.7 to 2 GB of activations. The heads (about 20 M params each) are noise. The 16.4 GB figure includes `lm_head`
(1.24 GB, 151936 x 4096), which a last-token encoder never uses; `Encoder` keeps `lm.model` but the `lm` object still holds it until dropped.

`scripts/quant_bench.py`, one process per precision (`nn.quantize` group size 64 on `lm.model`, in-memory, nothing written; no 4-bit Qwen3-8B
is in the HF cache). Texts: 50 slices of Seon's own Clojure source as states and 30 `defn` heads as candidates.

| precision | weights GB | peak GB, 2048-tok encode | peak GB, 80-text batch | ms @128 | ms @512 | ms @2048 | state cos vs bf16 (mean / min) | head top-1 agree | head rank corr |
|---|---|---|---|---|---|---|---|---|---|
| bf16 | 16.38 | 17.28 | 18.36 | 74 | 287 | 903 | 1 | 1 | 1 |
| q8 | 9.29 | 10.0 | 11.27 | 91 | 339 | 1000 | 0.9998 / 0.9991 | 0.98 | 0.994 |
| q4 | 5.50 | 6.21 | 7.48 | 87 | 331 | 942 | 0.9845 / 0.9591 | 0.56 | 0.860 |

- Encode time does not improve: prefill is compute-bound and MLX dequantises per matmul (q8 is about 10 % slower). Quantisation buys memory only.
- q8 is effectively the same encoder (cosine 0.9998, rank correlation 0.994). It saves 7 GB. q4 drifts (min cosine 0.96) and its head
  rankings change materially; the 0.56 top-1 agreement is a harsh test, because the 50 x 30 state/candidate pairs are mostly unrelated and
  their scores are close, but it says q4 embeddings must not be mixed with bf16 cached card vectors. Cache keys must name the encoder
  precision (a q8 vector is not a bf16 vector at the 1e-4 level, harmless for ranking, but a q4 one is not).
- Recommendation: serve q8 where memory is tight (about 10 GB, and 1.2 GB more is free by dropping `lm_head`); keep bf16 for training-data
  embedding so the cache is the reference space. On this 128 GB machine memory is not a constraint; on a 16 GB laptop only q8/q4 fit beside the JVM.
  Whichever encoder embeds the training data must be the one that serves.

## 2. Context length

**Native.** `Qwen3-8B/config.json`: `max_position_embeddings 40960`, `rope_theta 1e6`, `rope_scaling null`, no sliding window. The model card
says 32768 native, 131072 with YaRN. CLM's 2048 is only the serving default (`--max-tokens`, README: "raise both limits together, e.g. 8192").

**The heads were not trained at 2048.** The reference post-training precompute uses `max_len` 8192 (`embed_utils.Recipe`, `finetune.py` default;
`clm-recipe.md` §1: "state is the last 8191 tokens", dataset `deepswe-clm-train-embeddings-8k`). So states up to 8k tokens are inside the heads'
post-training distribution; only 8k to 40k is shift. Seon's current limit of 2048 is the artificial one.

**Cost** (`scripts/context_bench.py`, bf16, one text, warm):

| tokens | encode ms | peak GB |
|---|---|---|
| 2048 | 934 | 16.1 |
| 4096 | 2300 | 16.4 |
| 8192 | 5044 | 16.2 |
| 16384 | 9944 | 17.2 |
| 32768 | 22994 | 19.1 |

Memory is flat (fused SDPA, no n x n matrix); time is roughly linear (about 0.6 ms/token, a little super-linear at 4k and 32k). Earlier note: 282 ms at 512 tokens.
Seon's per-turn states are 1k to 4k tokens, so 1 to 2.5 s per state, once per turn, and the cached prefix does not help a last-token encoder.

**Does the embedding still reflect the start of a long state?** Eight (task, action) pairs; a state is the task sentence at the START, then N tokens of
real Seon source filler (identical for every task), then a question. Self-retrieval: is the long state's head embedding nearest to the embedding of its own short
(task-only) state among the 8? `full` = limit raised; `tail-2048` = current behaviour (keeps the last 2048 tokens).

| filler tokens | full: top-1 (head) | full: own vs other cos | tail-2048: top-1 | tail-2048: own vs other |
|---|---|---|---|---|
| 256 | 0.875 | 0.882 / 0.798 | 0.875 | same (nothing cut) |
| 1024 | 1.0 | 0.877 / 0.788 | 1.0 | same |
| 2000 | 0.875 | 0.862 / 0.781 | 0.875 | same |
| 4096 | 0.75 | 0.848 / 0.775 | 0.125 (chance) | 0.786 / 0.786 |
| 8192 | 0.875 | 0.833 / 0.756 | 0.125 | 0.788 / 0.788 |
| 16384 | 0.75 | 0.818 / 0.756 | 0.125 | 0.783 / 0.783 |

Finding: with the limit raised, the start of the state still moves the last-token embedding at 16k tokens (top-1 0.75 to 1.0 vs 0.125 chance; the own/other
gap shrinks from 0.09 to 0.06). With `tail-2048` the start is simply gone (all 8 states become identical, similarity 1.000). The needle test is weak in one respect:
the zero-shot task-to-action top-1 is only about 0.5 even with no filler (the heads put all states close together, state-state similarity 0.82 to 0.92), so I could not
test "still scores the right action" directly, only "still encodes the beginning". No sign of collapse or garbage up to 16k.

**Options, in order of cost:**
1. Raise `max_tokens` to 8192 (one constant: `Encoder(max_tokens=...)`, `Engine(max_tokens=...)`). In-distribution for the heads, memory flat, 5 s worst case. Do this first.
2. Beyond 8k: keep the native rope up to 32768 (no YaRN needed; Qwen documents YaRN only for >32k). It works mechanically (tested to 32k) but is out of distribution.
3. Chunk-and-pool (embed 2k windows, mean the head vectors): 4 x 0.9 s = same cost as one 8k pass, but each chunk's last token sees only its chunk; position-in-state is lost. Worse than option 1 inside 8k; only useful past 32k.
4. Fine-tune the state head on long states (the trainer in §3 does this unchanged: embed with `--max-tokens 8192`). This is the real fix if long states matter, and it is where Seon's own distribution (history, forms, task text) enters.
Recommendation: set the limit to 8192 now; do not add YaRN; re-embed training data at the same limit; consider keeping the TAIL (recent turns) as today.
Because Seon's state is task first, history after, tail truncation drops the task text first, which is the worst thing to lose. Put the task/proof text last (the reference convention "context first, question last", `state_text`) or raise the limit.

## 3. MLX head trainer

Files (new): `src-clm/src/seon_clm/train/mlx_finetune.py`, `train/__init__.py`, `scripts/clm_finetune.py` (CLI), `scripts/make_toy_dataset.py`, `scripts/prove_engine.py`,
`scripts/quant_bench.py`, `scripts/context_bench.py`.

Mirrors `reference-code/CLM/train/finetune.py --task clm`: symmetric in-batch InfoNCE over `min(exp(logit_scale), 100) * cos`, `logit_scale` learned, same-group
(task_id, step_idx) off-diagonals masked in both directions, AdamW wd 0, grad-norm clip 1.0, one-cycle (10 % warm-up, cosine), lr
`2e-3*sqrt(1024/width)*sqrt(batch/1024)`, task-disjoint validation split, early stopping on `top1` (patience 5), init from `CLM_v0.1-8B.safetensors`.
Additions: (a) explicit hard negatives: a record's `negatives` (the same state's other available actions) join the batch's action pool as extra columns for every
state; the action-to-state direction stays over gold actions; (b) a float16 embedding cache keyed by sha1(encoder id, side, text) that is resumable and shared across
runs, so the 8B encoder only sees new texts; (c) atomic safetensors write in `convert.py`'s layout (`state.*`, `action.*`, `clm` metadata with cfg, projection_dim, logit_scale),
so `HeadPair` reloads it on the mtime change. Not mirrored: `task-blocked` sampler, multi-fold CV. No MLX test files were added (new files only, per assignment).

Usage: `uv run --no-project --with pyarrow python scripts/make_toy_dataset.py d.jsonl 5000`; then
`.venv/bin/python scripts/clm_finetune.py --data d.jsonl --cache DIR --out head.safetensors --lr 5e-4 --batch 256` (`--curve 0,100,300,...` for a learning curve, `--embed-only` to just cache).
JSONL record: `{"state","action","negatives":[...],"task_id","step_idx"}`; only the first two are required.

**Toy task** (argilla/Synth-APIGen-v0.1, already in the HF cache): state = a user query; action = "tool_name: description" of the tool it was written for; negatives = up to
3 other tools offered in the same row; one row per tool, so the validation split (10 % of tools) is unseen tools. 5000 rows, 13170 distinct actions. Embedding that once through
the bf16 encoder took about 14 minutes (about 18k texts of 30 to 60 tokens, so the encoder dominates everything). One training epoch then takes about 0.3 s, so the whole curve below took under a minute.

**Learning curve** (validation: 500 unseen tools; `top1` = gold action first among all 500 gold actions, `hard_top1` = gold first among its own 1 to 3 same-row negatives; batch 256, up to 20 epochs):

| train rows | top-1 lr 1e-4 | top-1 lr 5e-4 | MRR (5e-4) | hard top-1 (5e-4) | val loss (5e-4) |
|---|---|---|---|---|---|
| 0 (zero-shot) | 0.350 | 0.350 | 0.475 | 0.946 | 4.17 |
| 100 | 0.530 | 0.512 | 0.617 | 0.960 | 2.81 |
| 300 | 0.574 | 0.592 | 0.696 | 0.974 | 2.39 |
| 1000 | 0.648 | 0.704 | 0.790 | 0.984 | 1.89 |
| 3000 | 0.698 | 0.724 | 0.816 | 0.994 | 1.51 |
| 4500 | 0.716 | 0.758 | 0.839 | 0.996 | 1.41 |

Accuracy rises about 6 to 7 points per 3x data (log-linear), the first 100 rows give the largest single jump (+16 points, mostly adapting the heads to the query-and-tool format), and nothing
has flattened by 4500. Best epoch is early (3 to 16): the heads overfit fast (train loss 0.05 against val 1.1), so early stopping on a task-disjoint split is mandatory, and lower lr is safer at n of 100 or less.

**Loaded in `seon_clm.Engine` and hot reloaded** (`prove_engine.py`): trained on the first 3000 rows (2700 train / 300 val tools, val top-1 0.81, hard 0.997), then
`Engine(checkpoint=live)` served the reference head, the checkpoint was atomically `os.replace`d over the live path, and without restarting: generation 1 to 2
(`clm-latest@2`), and accuracy on 200 later rows the head never saw (choose the right tool among its own 1 to 3 negatives) went 0.95 to 1.0.

## 4. How many examples does Seon need?

Evidence:
- The CLM recipe is data-hungry at pretraining (60 M QA pairs, 30 M hard negatives, 1 M trajectories) but the released fine-tunes are small:
  DeepSWE heads trained on 22,576 pairs (59 train tasks); TB 2.1 on 5,768 pairs (374 successful trajectories, 83 tasks) and that bought only +1.35 points of best-of-N over random
  (`clm-recipe.md` §1). `typed-decisions` fine-tunes on 4 x 300 rows. These are task-disjoint, success-only, and the gain is small whenever the zero-shot head is not the bottleneck.
- Our toy curve: about 100 rows give the first large jump, 1k gets most of the cheap gain (0.35 to 0.70), 3k to 5k is the knee. The toy is easier than Seon (in-domain QA-like text,
  clean labels, a self-evident action); Seon states are 1k to 4k tokens of code and history, labels are the next form, and the zero-shot CLM already loses to structural baselines.
  Expect a worse curve and a higher floor: treat toy numbers as a lower bound on what is needed.
- Seon pair counts: 10 to 40 forms per attempt. If only attempts that end green count (the reference never trains on failures), assume about half. At about $0.0015 per DeepSeek bench run,
  1k pairs is about 50 to 100 successful attempts, so about 100 to 200 runs and well under $1; 5k pairs is about 500 runs, still under $1 of API. Embedding is the real cost:
  states of 1k to 4k tokens cost 1 to 2.5 s each, so 5k states is 1.5 to 3.5 hours on one Mac (hours of the shared GPU); actions are cheap. Truncate to 2k to 4k for training embeddings, and
  budget the encoder, not the API.
- The constraint is task diversity, not pair count: forms within an attempt are strongly correlated, and the split must be task-disjoint (as built). The reference's useful regime is tens of
  tasks and thousands of pairs; I would not trust a result from fewer than about 50 distinct tasks.

Recommendation (a plan, not a decision):
1. Minimum for a first real signal: 1,000 labelled (state, gold form) pairs from at least 50 distinct tasks, task-disjoint 10 to 20 % validation, hard negatives = the other forms/cards the agent was offered at that step (`negatives`), lr 1e-4 to 5e-4, early stopping on val top-1.
   That is a few hundred runs and one to two hours of encoder time. Judge it against the structural baselines on held-out tasks, not against zero-shot CLM.
2. If it beats baselines: grow to 5,000 to 20,000 pairs (the knee in the toy and the reference sizes) and re-run the curve on Seon's own data to confirm it keeps rising.
3. If 1k pairs do not move within-task top-1, more data will not rescue it (zero-shot CLM losing to recency/transition counts says the encoder space may not carry the signal);
   stop and keep the structural path.
4. Before collecting: set the limit to 8192, embed with the exact encoder that will serve (bf16 or q8, not mixed), and put the task text where truncation keeps it.

## Known limits of this lane
- Quantisation quality was measured on 50 states and 30 candidates, not on Seon's task labels; rank agreement is not downstream accuracy.
- The needle test shows the start of a state still shapes the embedding, not that scoring stays correct (zero-shot head top-1 is about 0.5 even without filler on that probe).
- The toy curve is one seed and one validation split of 500 tools; differences under 2 points at n of 3000 or more are noise. Hard-negative accuracy is near ceiling (0.95 zero-shot), so `top1` over the 500-pool is the informative column.
- The trainer was not run against a real Seon dataset; there is no Seon-to-JSONL adapter yet (needs the attempt's forms, per-step state text, and the offered forms as negatives).
- Not committed. The venv has no pyarrow; the toy dataset script runs under `uv run --no-project --with pyarrow`.
