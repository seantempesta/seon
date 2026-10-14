---
type: research
status: read in full 2026-09-26
tags: [research, agent, context]
---

# CLM: what the code and the Hub cards actually did

The blog and README summarise the agentic results in one line each. This note records
what the released code does and what the Hugging Face cards say about the data behind
those results. The companion note on the publications is `clm-publications.md`.

**Sources.** Read end to end: `reference-code/CLM` at `bb42c6c`, specifically
`train/finetune.py` (749 lines), `train/adapters.py`, `train/embed_utils.py`,
`evaluation/bon_eval.py`, `preprocessing/hf_embeddings.py`, `src/clm/schema.py` and
`examples/t_rex/README.md`, plus `src/clm/heads.py`, `src/clm/embedder.py`,
`docs/FINETUNING.md` and the README. The repository history has 8 commits and the first
is "Initial Commit", so no ablation history survives in it. The Hub cards were read on
2026-09-26 through the HF API and raw READMEs. The column statistics come from
`datasets-server.huggingface.co/statistics`. Access limits:

- `Contrastive-LM/deepswe-clm-embeddings-8k` returns HTTP 401. This is the evaluation
  set that the README's reproduce command names (README.md:171-175).
- The Terminal-Bench repositories `Contrastive-LM/tb21-clm-cv-{heads,embeddings}-8k` and
  `tb21-gpt6-low-clm-cv-{heads,embeddings}-8k` also return 401. Their contents are known
  only from web-search snippets of their cards.
- The research repository (`jackyk02/contrastive_learning`) returns 404 on GitHub. It
  holds the DeepSWE trace builder and `embed_shard.py`.

The earlier public copies under `tarsur385/*` are readable. They are the same pipeline
under its pre-release name, "PRM".

---

## 1. The DeepSWE / Terminal-Bench "trajectory reward model"

**This is behavior cloning, not a reward model.** Training uses only the steps of
*successful* rollouts. Each pair is the state at a step and the action actually taken
at that step. The loss is in-batch InfoNCE. Pass/fail labels are used only to select
the training data, to stratify folds and to score the evaluation. No loss term ever
sees a failed trajectory.

- The release training set `Contrastive-LM/deepswe-clm-train-embeddings-8k` has
  `reward` min = max = mean = 1.0 over the 329,810 rows the viewer processed. The card
  says 405,919 steps, 4,701 trajectories and 113 tasks.
  (<https://datasets-server.huggingface.co/statistics?dataset=Contrastive-LM/deepswe-clm-train-embeddings-8k&config=default&split=train>)
- The heads card agrees. It reports "Training tasks with successful trajectories: 66
  (59 train, 7 validation)" out of 75 training tasks.
  (<https://huggingface.co/Contrastive-LM/deepswe-clm-heads-8k>)
- The same card's `split_summary.json` has `"training_pairs": 22576`.
- `finetune.py` itself never filters on `reward`. `_load_clm_data` keeps every row of
  the kept tasks (finetune.py:151-171). The success-only filter therefore lives in the
  data, not the trainer. Anyone running `--task clm --data` on mixed trajectories
  trains on failures too, unless they filter first.

### State

- The state is the **whole chat-templated message history up to the step**, not a
  window of steps. `Recipe.state_ids` calls
  `apply_chat_template(state, tokenize=True, add_generation_prompt=False)` and keeps the
  **tail** `ids[-(max_len-1):]` (embed_utils.py:45-49).
- `max_len` defaults to 8192 for clm (finetune.py:723), so the state is the last 8191
  tokens.
- A plain-string state is tokenized without special tokens and also keeps its tail
  (embed_utils.py:46-47).
- The train card says the same: "the state is the chat-templated step context truncated
  to its last 8191 tokens".
- The transition record is `{state: chat messages, action, task_id, step_idx,
  trajectory_id, reward}` (adapters.py:3-5, 21).

### Action (the candidate)

- The action is "the raw action text", tokenized without special tokens with its
  **head** kept: `text_ids(..., keep="head")`, first 8191 tokens (finetune.py:140;
  embed_utils.py:39-43).
- For DeepSWE this is the mini-swe-agent action. The older SWE-bench card describes
  that action as the `<function=bash>{"command": ...}</function>` string.
- **Encoder:** Qwen3-8B, last-token pooling, L2-normalized, 4096-d, stored as float16
  (embed_utils.py:52-67, 101; hf_embeddings.py:6-10, 113).

### `--window K`

`--window` is **not** a context window. It is the trajectory aggregation:

- Each step gets a score equal to the plain cosine
  `(normalize(sh(s)) * normalize(ah(a))).sum(-1)`. The logit scale is not applied
  (bon_eval.py:46-56).
- Steps are sorted by `step_idx` (bon_eval.py:237-240).
- The trajectory score is the **mean of the last K step scores**, K = 12 by default
  (bon_eval.py:59-64, 150-151).

### Best-of-N

- Per task, the score is the exact expected reward of picking the argmax over a uniform
  random N-subset of the task's rollouts. Score ties are broken uniformly
  (`best_of_n`, bon_eval.py:67-86).
- "random" is the per-task pass fraction, which is expected pass@1. "oracle" is
  expected pass@N.
- Tasks with fewer rollouts than N are kept, with budget `min(n, len)`
  (bon_eval.py:95, 257-261).
- When several policies (`config`) are present, each (task, config) pair is one
  instance (bon_eval.py:247-253).
- `--fold-spec` scores each task only with the head that never saw it
  (bon_eval.py:171-197).

### DeepSWE numbers (heads card and `verification.json`)

- **Split:** seed 42, stratified by (candidate count, pass count); 75 train / 38
  held-out tasks, task-disjoint. The stratified fold builder is
  `_make_stratified_folds` (finetune.py:64-117). Of the 75 training tasks, 66 have
  successes: 59 train and 7 validation. The validation share is `--val-frac 0.1` of
  tasks (finetune.py:271-273).
- **Training data:** successful rollouts from **23 policy models across 56 configs**
  (claude-sonnet-5, claude-opus-4-8, gemini-3-7-flash, grok-4-6 and others; viewer
  stats). Step index has median 48 and max 591. The evaluation policy's own rollouts
  are not training data.
- **Evaluation data:** mini-swe-agent with Claude-Opus-5, 4 rollouts per task: 449
  rollouts, 44,409 steps, 113 tasks. The step-weighted reward mean is 0.744.
  (<https://huggingface.co/datasets/tarsur385/deepswe-prm-embeddings-8k>) The held-out
  38 tasks contribute 151 rollouts, so some groups are short.
- **N = 4, window = 12.** Result: 31/38 = 81.58%, against random 28/38 = 73.68% and
  oracle 34/38 = 89.47%.
- Only 13 held-out tasks are "mixed" (decidable). The headline gain is **3 tasks out of
  13 decidable**.
- **Training command:** init from `CLM_v0.1-8B.pt`, `--batch 512`, seed 1234, up to 20
  epochs, patience 5. Everything else is at its default (README.md:177-179).
- The 22,576-pair count is small next to roughly 405,919 × 75/113 success steps. Neither
  the card nor the code explains the gap; possible causes are subsampling, a different
  dump or deduplication. **Unresolved.**

### Terminal-Bench 2.1 (card snippets only; repositories now 401)

Sources: <https://huggingface.co/datasets/Contrastive-LM/tb21-clm-cv-embeddings-8k> and
<https://huggingface.co/Contrastive-LM/tb21-clm-cv-heads-8k>.

- **Data:** Harbor job `tbench-2-1-fable-5__xhigh-claude-code-simple` (Claude Fable 5,
  xhigh, Claude Code 2.1.167), 89 tasks × 5 rollouts.
- **Evaluation:** 7,160 state/action pairs from 445 trajectories. That is about 16 steps
  per trajectory, far shorter than DeepSWE.
- **Training:** 5,768 pairs from the **374 successful trajectories (83 tasks)** of the
  *same job*. Here the policy is the same as the evaluated one.
- **Protocol:** 3-fold task-disjoint cross-validation, folds of 30/30/29 tasks (seed
  20927). Best-of-5 using all rollouts, window 12.
- **Result:** fold-wise 76/89 = 85.39%, against random 84.04%, oracle 93.26% and
  zero-shot CLM v0.1 79.78%. **Zero-shot is below random, and fine-tuning adds 1.35
  points over random.**
- The README's 87.6% on "30 held-out tasks" (README.md:156-162) does not match these 89-
  task CV numbers. It may be one fold or the `gpt6-low` variant; public data cannot
  settle it.

## 2. `finetune.py` objectives and every default

### Shared settings

- **The encoder is frozen.** The script consumes precomputed embeddings only. It trains
  both heads fully plus `logit_scale`; nothing inside a head is frozen
  (finetune.py:296, 574).
- **Head:** `make_head` is `hidden → width` (GELU), then `depth-2` blocks of
  `Linear(width,width) → LayerNorm → GELU`, then `→ proj` (heads.py:41-65).
- **Head defaults:** width 1536, depth 3, proj 512, gelu, layernorm on, residual off
  (finetune.py:259-263, 496, 671).
- **Reference checkpoint:** cfg `{width 1536, depth 3, proj 512, layernorm true}`,
  18,887,680 parameters across both towers, 75.6 MB (inspected locally).
- **Logit scale:** starts at `log(1/0.07)` (scale 14.3), or is copied from the
  checkpoint. It is used as `exp().clamp(max=100)` (finetune.py:177, 289, 503, 513).
- **The temperature is frozen when warm-starting.** The reference head's
  `logit_scale` is 4.6132, and exp(4.6132) = 100.8, above the clamp. `clamp` passes no
  gradient above its max, so a warm start keeps τ fixed at 0.01 unless the scale
  drifts below log 100.
- **Optimizer:** AdamW, weight decay 0.0 (finetune.py:675), gradient-norm clip 1.0,
  OneCycleLR with `pct_start=0.1`, cosine annealing (finetune.py:298-306, 575-581).
- **Training length:** `--epochs 20`, `--patience 5`, `--val-frac 0.1`, `--seed 1234`.
- **Device:** `cuda` if available, else `cpu`. There is no MPS path (finetune.py:254,
  478).
- **Checkpoint format:** `{state_head, action_head, logit_scale, cfg, epoch, metrics}`.
  The script writes `best_head.pt` at epoch 0 (the init) and then on improvement, and
  `final_head.pt` every epoch.

### `--task clm`

- **Loss:** symmetric in-batch InfoNCE over a B×B matrix of `scale · cos`
  (finetune.py:174-183).
- **The masking is the notable part.** Off-diagonal pairs that share the group code
  `(task_id, step_idx)` are set to −inf in both directions (finetune.py:168-170,
  179-182). Rollouts of the same task at the same step index are near-duplicates, so
  this removes false negatives.
- **Hard negatives come only from batch composition.** There are no explicit hard
  negatives and no BCE. `--sampler task-blocked` fills each batch with `--tasks-per-batch
  4` whole tasks, so other steps of the same task become in-batch negatives
  (finetune.py:232-250). The default is `random`.
- **Batch** 2048 by default; the release used 512 (finetune.py:724).
- **LR** is `2e-3 · sqrt(1024/width) · sqrt(batch/1024)` (finetune.py:297). For
  width 1536 that gives 1.63e-3 at batch 2048 and 1.15e-3 at batch 512.
- **Validation** is on task-disjoint validation tasks. It reports:
  - `val_loss` and `val_top1` (in-batch);
  - `within_task_top1`: is this step's own action ranked first among the actions of all
    steps of the same task, with the same-step mask applied, capped at 4096 members per
    task?
  - `within_task_meanrank` (finetune.py:186-229).
- **Selection:** `--clm-select-metric within_task_top1` by default, or `val_loss`.
  Early stopping needs an improvement above 1e-4 (finetune.py:308-358).
- **The selection metric matches training, not the benchmark.** It never looks at
  pass/fail.
- **Data sources:** exactly one of `--emb-dir`, `--hf-dataset` (default is the DeepSWE
  training set) or `--data` (JSON/JSONL transitions, embedded from scratch).
- **Held-out tasks:** `--holdout-tasks`, `--holdout-folds` (one head per fold plus
  `fold_spec.json`), or `--folds K --fold-index` (finetune.py:365-396, 681-689).
- **Quirk:** with `--init-ckpt`, clm takes width and depth from the checkpoint but
  takes proj from `--proj` (finetune.py:259-260, 287). The choice task reads proj from
  the checkpoint (finetune.py:500).

### `--task choice` (typed decisions)

- **Embedding:** always from scratch. `max_len` is 2048 and **both state and candidate
  keep their tail** (finetune.py:476, 733). A sha1 text cache is stored as `.npz`
  (finetune.py:422-446).
- **`--loss infonce` (default):** bidirectional in-batch InfoNCE over the batch's
  deduplicated option texts, with **soft multi-positive targets** (finetune.py:520-549).
  - Forward: `-(tgt · log_softmax(logits over pool)).sum()`, where the target is the
    question's distribution scattered onto the deduplicated pool. Shared option texts
    (the templated noul options, for example) collapse into one column.
  - Backward: for each option column, a distribution over the states that hold it,
    normalized by column.
- **`--loss softce`:** a softmax over the question's own candidates only, with soft CE
  against the target (finetune.py:515-518).
- **`--targets soft` (default):** the annotator distribution. `hard` uses a one-hot on
  the gold label (finetune.py:528-530, 695-696).
- **Settings:** batch 256, LR 5e-4. Validation uses 10% of train *row ids*. Selection is
  by val accuracy, with test run once at the best epoch. The script also prints a
  per-question majority baseline (finetune.py:455-461, 567-572, 597-636).

## 3. Typed decisions (`choice`) data shape

**Dataset.** `LocalLLaMA/typed-decisions`, Apache-2.0, synthetic
(<https://huggingface.co/datasets/LocalLLaMA/typed-decisions>).

- **Size:** 4 workflows (agent_trace_observability, customer_service,
  invoice_processing, security_incidents) × 400 rows, split 300 train / 100 test.
- **Row format:** each row has `state`, `questions` (the System One wire format) and
  `gold` `{qid: {label, probabilities}}`, with 5 questions per row.
- **Labels:** 4-way labels such as outcome, action, risk and urgency. The card
  describes the probability labels as ensemble-derived.

**Pair construction** (`build_pairs`, schema.py:104-112):

- **State text** is `to_text(state)`, a blank line, then the question's `instructions`
  ("context first, question last", schema.py:62-65). `to_text` renders objects as
  `key: value` prose, not JSON (schema.py:27-59).
- **choice:** candidates are each option's description text, or the key when there is
  no description, with no prefix (schema.py:79-87).
- **score:** candidates are the ordered level texts, keyed `"0".."n"`
  (schema.py:88-92).
- **noul:** two candidates, `"false: …"` and `"true: …"`. When no description is given
  they default to `"true: Yes. This is true: {instructions}"` and the matching `false`
  form (schema.py:93-101).
- **Targets:** the gold probabilities are renormalized over the keys. When they are
  missing, the target is a one-hot on the label. An unknown label raises
  (adapters.py:69-87).
- **Serving** uses a softmax over `scale · cos` as the answer distribution
  (schema.py:115-149).

## 4. What they tried and learned

**Naming history.** The pipeline was a "PRM" (process reward model):

- `tarsur385/deepswe-prm-heads-8k` was trained with `train/finetune_prm_head.py` /
  `--task prm`.
- That run had 3 task folds and started from `jackyk02/qwen3_8b_head`
  `qwen3_8b_midtrained_head.pt`, not the released post-trained head.
- The release renamed it "clm", moved to a single fixed 75/38 split and warm-started
  from `CLM_v0.1-8B.pt`.

**SWE-bench PRM line, not in the release.**

- `tarsur385/swev-prm-embeddings-qwen36-27b`: Qwen3.6-27B, 5120-d, states truncated to
  keep their *head* at 32K, 401,509 train / 94,210 validation pairs, task-disjoint.
- Hard negatives were "real actions emitted by *failed* trajectories on the same
  `instance_id` (primary) or same repo (backoff)". There is a `swev-prm-neg16-ckpt`
  (16 negatives) with `preds_neg16_bon4.json`.
- `swev-prm-action-label-inputs` holds 12,473 transcripts. About 10% (1,248
  trajectories, 50,383 actions) carry Sonnet-judge GOOD/WRONG/NEUTRAL per-action labels.
- None of this reached `finetune.py`. The release keeps success-only positives with
  in-batch negatives. The failure-mined negatives and the judge labels were apparently
  dropped. **What was abandoned:** explicit failure negatives, per-action labels, and
  the 27B encoder.
- The card's lesson: "the cached-embedding setup works where end-to-end LoRA
  re-embedding does not", because large batches supply many in-batch negatives.

**Head-scale ablations** (`jackyk02/qwen3-8b-dqa-head-302m-{pretrained,midtrained}`):

- Pretraining: 2-layer heads of 76M / 151M / 302M reach validation InfoNCE 0.7588 /
  0.7553 / 0.7399. "A 3-layer head reaches a lower loss at a quarter of the
  parameters." That is presumably why the release head is depth 3 at 20M.
- Mid-training on hard negatives (K ≤ 10, doc-masked bidirectional InfoNCE):
  hard-negative top-1 went from 40.9% to 56.7% and best-of-10 from 46.4% to 67.2%.
  General validation loss worsened from 0.726 to 0.745.
- Mid-training ran at batch 4096 / LR 5e-4 instead of the reference 16384 / 1e-3,
  because of a 40 GB GPU.
- The README adds that 40% replay during post-training kept hard-negative top-1 at
  68.5%; without replay it fell to 56.2% (README.md:286-289).
- Hard negatives from the start peaked at 62.4%, against 69.2% for pretrain then
  midtrain (README.md:291-296).

**`docs/FINETUNING.md`** is an autoresearch loop: an LLM edits only `finetune.py` and
keeps a change if held-out best-of-N improves. Its example `results.tsv` rows are
illustrative, not results. `--sampler task-blocked`, `--clm-select-metric` and
`--loss softce` look like surviving ablation knobs; the defaults are `random`,
`within_task_top1` and `infonce`.

**Evaluation honesty.** Both agentic gains are small absolute moves on few decidable
tasks:

- DeepSWE: +3 of 13 decidable tasks.
- Terminal-Bench: +1.35 points over random, with zero-shot 4.3 points *below* random.
- The prm_score cosines on DeepSWE evaluation span only 0.18–0.42 (std 0.028).

## 5. Costs

- **Vectors:** 4096 × fp16 = 8 KiB per text, 16 KiB per step pair. 405,919 steps come
  to 6.20 GB (Hub `usedStorage` 6,195,715,874 bytes). DeepSWE evaluation is 44,409
  steps. The SWE-bench 27B set is 5120-d fp32.
- **Heads:** 18.9M parameters (75.6 MB fp32). Training them on precomputed vectors is
  small: 22.6k pairs × ≤20 epochs of MLP matmuls. **Neither the code nor the cards give
  a wall time.** `finetune_summary.json` records `minutes`, but none is published.
- **Encoder passes dominate.** One Qwen3-8B forward per state of up to 8K tokens and
  one per action.
- **Serving:** 2048-token truncation by default so vLLM fits on a 24 GB GPU
  (commit `09fcfd0`).
- **Upstream scale:** 61.9M QA pairs (50.8B nominal tokens) for pretraining at batch
  1024, and about 310 tokens per head parameter at the optimum (README.md:262-267).

## 6. What this means for fine-tuning CLM on Seon data

### Reusing `finetune.py` with MLX embeddings

- Write an **embedding dir**:
  - `state_embeddings.pt` and `action_embeddings.pt` as torch fp16 `[N,4096]`,
    L2-normalized;
  - `metadata.json` of the form `{num_samples, hidden_size: 4096, samples: [{trajectory_id,
    step_idx, task_id, reward, model, config}]}` (hf_embeddings.py:4-10, 30).
- Then pass `--emb-dir`. `hidden_size` is read from the tensors, so no code change is
  needed (finetune.py:276-280).
- It runs on CPU because there is no MPS path. That is fine for a 20M-parameter head on
  tens of thousands of pairs; porting the loss to MLX would be about 30 lines.
- `task_id` must be the unit of leakage: task or session, never step. `step_idx` must be
  the group within which rows are *not* negatives of each other.

### Truncation parity

- Their DeepSWE training recipe: states are chat-templated with the tail kept; actions
  keep their head, at 8191 tokens.
- Their serving embedder: plain text through vLLM `truncate_prompt_tokens: 2048`
  (embedder.py:42-43). That keeps the last 2048 tokens and applies no chat template.
- `choice` training: tail for both sides at 2048.
- Our `src-clm` `Encoder` keeps the tail at `max_tokens=2048` with no template
  (src-clm/src/seon_clm/encoder.py:7-8, 52). It therefore matches their *serving* path
  and the `choice` recipe, not the DeepSWE state recipe.
- **Rule for Seon:** embed training data with the same `Encoder` we serve with, in plain
  text, keeping the tail, with the question or instruction last as `schema.state_text`
  does. Do not add a chat template on one side only.
- Cards and function descriptions are short, so head versus tail matters only for
  states.

### (a) "Is this context item needed for the next step?" (multi-positive, per candidate)

- The clm path has single-positive rows. It can still express multi-positive: emit one
  row per (state, needed item), duplicating the state vector, with a shared
  `(task_id, step_idx)` code. The group mask then stops positives of the same state from
  acting as each other's negatives (finetune.py:179-180).
- **What that misses:** its negatives are random items from other states. The
  discriminating negatives are the items that were offered or reachable at this state
  but *not* used, and `finetune.py` has no hard-negative input.
- **The right existing objective is `choice`'s soft-target softmax.** Use the state's
  own candidate set, with a target uniform over the needed items: `--loss softce
  --targets soft`, finetune.py:515-518. The `infonce` variant adds in-batch pooling.
  This needs an adapter from Seon rows to `ChoiceExample`-like tuples. `run_choice` is
  hard-wired to typed-decisions parquet and embeds its own texts, so it needs a small
  data-loading change of about 20 lines to take precomputed vectors.
- **For a yes/no inclusion threshold:** a softmax gives a ranking, not calibration.
  Add a per-pair sigmoid/BCE term (SigLIP-style) on `scale·cos + b` with a learned bias
  `b`. **This is not in `finetune.py`**; about 15 lines.
- **Hard negatives:** follow the swev lesson and mine them from the actual offered but
  unused set at the same state. Keep mid-training-style replay of the original
  distribution, since their data shows forgetting without it (README.md:286-289).

### (b) Next-function ranking at a form hole (single positive)

- **Shape:** the state is the enclosing form with the hole plus the task context; the
  action is the function card; the positive is the function actually called.
- This fits the clm InfoNCE directly, with group code = (task, hole) and
  `--sampler task-blocked` so functions from the same namespace or task appear as
  negatives.
- **Better:** `softce` over the explicit in-scope candidate set (the arity- and
  type-compatible functions). In-batch negatives rarely contain the real competitors.
- **Select on held-out task-disjoint top-1 / MRR at the hole,** not on `val_loss`.
- **Warm-start caveat:** warm-starting from `CLM_v0.1-8B.pt` pins τ at 0.01 through the
  clamp. Either accept that or re-initialize `logit_scale` below log 100 so it can
  learn.

### Expectation-setting

- Their own zero-shot was below random on Terminal-Bench. Fine-tuning gained only a
  few decidable tasks, and the training signal was behavior cloning on successes.
- Our earlier zero-shot losses (8% versus 36% at next-file; see `synthesis.md`) are
  consistent with that. Only a fine-tune with hard negatives drawn from Seon's own
  candidate sets is likely to move the number.
