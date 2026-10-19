# Local fine-tuning and RL tooling for Seon agents (lane learn-tools)

Scope: what can be trained on an M5 Max, 128 GB unified memory, from the reward signals Seon already records (proofs
green, turns, forms, cost). Research of 2026-10-19 by web search and page fetch. **Every claim is marked
[V] verified on a fetched primary page, [S] from a search snippet or secondary page only, or [E] my estimate.** Gaps
are listed in the last section; several asked-for numbers do not exist in public sources and must be measured here.

## Summary

| Goal | Cheapest viable path | Hardware fit (128 GB) | Time | Data needed | Expected gain |
|---|---|---|---|---|---|
| Ranking model for context selection | Train a small head (logistic or MLP) on the frozen EmbeddingGemma 768/256-dim vectors, labeled from Seon's own traces (was this unit read, cited or edited before proofs went green) | Trivial (CPU, seconds to minutes) | Hours of work, minutes of compute | 1k to 10k labeled (task, unit, used?) pairs from existing traces | Unmeasured for trained heads. Literature shows the headroom: LLM rerank of top-50 files lifted hit@1 0.58 to 0.78 [S]; context efficiency correlates 0.95 with resolve rate [S]. A head is a hypothesis to measure, not a published result |
| LoRA agent model | `mlx_lm.lora` QLoRA SFT on rejection-sampled, proof-green traces of **Qwen3-Coder-30B-A3B** (primary) or a 7B/14B dense Qwen coder; start with 7B to learn the loop | 30B-A3B 4-bit weights about 17 to 20 GB [S], training fits with large margin [E]. DeepSeek V4 Flash (284B/13B active) does not fit for training [S] | [E] 7B: roughly 15 to 40 min per 1k examples at 2k tokens; must be measured | 500 to 5k proof-green traces. SWE-Gym got a gain from 491 trajectories (32B) [S] | Style/format/tool-use gain is near certain; resolve-rate gain on open-ended tasks is uncertain. Unknown for Clojure |
| RL loop | Do **not** start with RL. First: rejection-sampling SFT (RFT) on own traces, then DPO on (green, red) pairs from the same task with `mlx-lm-lora`; GRPO only on a 0.5B to 8B model for a pipeline smoke test | GRPO on small models fits; on a 30B MoE untested | GRPO is slow: 0.02 it/s on Qwen3-0.6B [V] | Tasks with proofs, 100s of distinct tasks | Published RL gains used 64 H100 for 6 days (DeepSWE, 23% to 42%) [V]; laptop scale is two to three orders of magnitude smaller |

## 1. Models that fit

Facts per model.

- **Qwen3-Coder-30B-A3B-Instruct**: 30.5B total, 3.3B active, 48 layers, 128 experts with 8 active, 262k native context,
  Apache-2.0 [V] (https://huggingface.co/Qwen/Qwen3-Coder-30B-A3B-Instruct). Model card cites no numbers for SWE-bench or
  tool use [V]. Inference on an M5 Max 128 GB: about 101 tok/s at 1k context, 30 tok/s at 64k, prefill about 3,800 tok/s
  (community oMLX submission for the Qwen3-30B-A3B-Instruct-2507 sibling) [S] (https://omlx.ai/benchmarks). 4-bit weights
  about 17 to 20 GB [S, HN comment says 20 GB].
- **Qwen3-Coder-Next**: 80B total, 3B active, Q4 needs at least 48 GB; 84 tok/s on M5 Max is an **index estimate, not a
  measured run** [V, labeled estimate] (https://llmcheck.net/models/qwen3-coder-next-on-m5-max/). Weights 4-bit about
  46 GB, so QLoRA could fit in 128 GB but is untested here [E]. Its hybrid/linear attention is "detected automatically" by
  mlx-lm-lora [V].
- **DeepSeek-Coder-V2-Lite-Instruct**: 16B total, 2.4B active MoE [S]. About 20.9 GB at Q4_K_M [S, calculator]. This is the
  "DeepSeek-lite" candidate, but it is a 2024 model, older than Qwen3-Coder; I did not verify it against current code
  benchmarks.
- **DeepSeek V4 Flash (what Seon calls via API)**: 284B total, 13B active, MIT, 1M context; GGUF 4-bit about 155 GB, so it
  does not fit 128 GB even for inference without extreme quantization [S, secondary blogs disagree on sizes]. There is no
  small "lite" V4 that I could verify. A LoRA on it is not a laptop job.
- **Dense 7B / 14B Qwen coder models**: not fetched; sizes are standard (7B 4-bit about 4.5 GB, 14B about 9 GB) [E].

Trainers.

- **mlx-lm** (`mlx_lm.lora`): LoRA, DoRA, full fine-tune; QLoRA is automatic when `--model` is a quantized checkpoint [V]
  (https://github.com/ml-explore/mlx-lm/blob/main/mlx_lm/LORA.md). The page lists model families such as Mistral, Llama,
  Qwen2, Gemma; newer families (Qwen3 MoE, Qwen3-Next) are claimed by mlx-lm-lora "any model supported by MLX-LM" [V] but I
  did not verify LoRA on MoE expert layers specifically. Probe on a small MoE first.
- **mlx-lm-lora** (Goekdeniz-Guelmez): SFT, DPO, CPO, ORPO, GRPO, GSPO, Dr. GRPO, DAPO, online DPO, PPO, REINFORCE;
  4/6/8-bit quantized training; custom Python reward functions [V] (https://github.com/Goekdeniz-Guelmez/mlx-lm-lora).
- **Unsloth**: no official Apple Silicon support found; community wrappers `unsloth-mlx` and `mlx-tune` re-expose MLX under
  Unsloth's API [S]. Not worth adopting over mlx-lm-lora.
- **PyTorch MPS / TRL**: TRL's vLLM path is CUDA oriented (versions 0.16 to 0.24, separate CUDA devices) [S]; some ops are
  missing on MPS [S]. A 2025 profile found MLX has the highest sustained generation throughput on a Mac Studio versus MPS
  [S] (https://arxiv.org/pdf/2501.14925). `vllm-metal` runs vLLM on MLX but I found no TRL integration [S].
  Conclusion: MLX stack, not TRL, on this machine.

Clojure/Lisp ability: **no Clojure-specific benchmark found**. Only generic low-resource-language surveys exist
(https://arxiv.org/pdf/2410.03981). Seon should measure it: run the existing proof tasks against each candidate through
the same REPL path and compare turns to green.

## 2. LoRA in practice on MLX

Verified from the mlx-lm docs [V]:

- Data (`train.jsonl`, `valid.jsonl`, one JSON object per line, format auto-detected): `chat` (`messages` with roles),
  `tools` (`messages` with `tool_calls` plus a `tools` schema list), `completions` (`prompt`/`completion`), `text`.
  The `chat` format fits a Seon turn: assistant message = the form, user message = the rendered reply.
- Memory levers in order: QLoRA, `--batch-size` (default 4) down to 1 or 2 with `--grad-accumulation-steps`,
  `--num-layers` (default 16) down, shorter sequences, `--grad-checkpoint`.
- Reference run: Mistral-7B, batch 1, 4 layers, about 250 tok/s on an M1 Max 32 GB. Sequence length not stated.
- Adapters: `mlx_lm.fuse` merges into `fused_model/` (and optional GGUF for Llama/Mistral-style fp16 only);
  `mlx_lm.generate --adapter-path` applies at runtime. The doc page does not describe serving. `mlx_lm.server` taking an
  adapter path is likely but **unverified** by me.
- Adapter size: rank-8 on 16 layers is tens of MB for a 7B [E]; not found in a source.

Measured community numbers (M1 64 GB, Qwen3.5 small models, LoRA): 0.8B 475 tok/s at 3.9 GB; 2B 180 tok/s at 5.9 GB;
4B 115 tok/s at 11.1 GB [S] (https://github.com/sciences44/mlx-lora-finetune). One blog found mlx-lm's logged tokens/sec
counts masked tokens only, real rate about 39 tok/s on a Mac mini [S]. mlx-lm-lora: Qwen3-8B 4-bit SFT about 4.1 it/s at
6 to 10 GB on an M4 Pro 24 GB, batch size not stated [V].

**Not found:** any published training wall time per 1k examples for 7B, 14B, or a 16B/30B MoE on any Mac, and any M5 Max
training benchmark. My estimate [E]: an M5 Max has roughly 2x the memory bandwidth and more GPU throughput than an M1 Max,
so a 7B QLoRA run at 2k-token examples should land around 300 to 800 tok/s of real tokens, i.e. 1k examples of 2k tokens
(2M tokens) in about 40 to 110 min. A 3B-active MoE should be similar to or faster than a 7B dense per token in compute but
heavier in memory traffic. Treat these as guesses; the first deliverable of any LoRA task is a measured table (tokens/s,
peak memory, examples/hour) for 7B dense and 30B-A3B using `seon.profile`-style recording.

Merge or runtime adapter: runtime loading keeps one base in memory and lets branches or agents pick adapters (matches
"cheap branches"); fusing avoids per-token adapter overhead and is needed for GGUF export. With a quantized base, fusing
needs care (fuse into a de-quantized copy or keep the adapter) [E].

## 3. RL on a laptop

- Tooling: `mlx-lm-lora` GRPO/GSPO/Dr. GRPO/DAPO, rewards as Python callables over the whole batch returning one float per
  completion [V]. Seon's reward (proofs green) is a function of a completion only if the rollout is a single generation;
  for multi-turn REPL episodes the "completion" is a whole trajectory, which this library does not model [E, inferred from
  the documented reward signature].
- Throughput: GRPO on Qwen3-0.6B at 0.02 it/s, 9 to 20 GB, on an M4 Pro 24 GB; Unsloth on A100 0.04 it/s [V]. Generation
  dominates. No numbers for 7B+ GRPO on Mac.
- Published scale for verifiable-reward SWE RL:
  - DeepSWE-Preview: Qwen3-32B, RL only, 64 H100, about 6 days, 64 problems x 8 rollouts per step, 512 containers, 200
    steps, about 4.5k R2E-Gym problems, sparse reward (all Fail2Pass and Pass2Pass tests pass), Pass@1 on SWE-bench Verified
    about 23% to 42.2% [V] (https://together.ai/blog/deepswe). Recipe "GRPO++": clip-high, no KL, no std normalization,
    length normalization, leave-one-out advantage, compact filtering of overlong trajectories.
  - SWE-Gym: 2,438 tasks; 32B SFT on 491 sampled trajectories gave 20.6% Verified; verifier-based best-of-n scales roughly
    log-linearly [S] (https://arxiv.org/abs/2412.21139).
  - R2E-Gym: 8.1k procedurally built tasks; 32B reaches 34.4% Pass@1, hybrid execution-based plus execution-free verifier
    51% [S].
  - SWE-smith: 50k task instances from 128 repos; SWE-agent-LM-32B 40.2% Pass@1 Verified [V]
    (https://arxiv.org/abs/2504.21798). Training cost not stated on the abstract page.
- Implication: demonstrated gains come from SFT on thousands of verified trajectories (cheap, laptop-scale for a 7B to 14B)
  or from RL at 512 parallel environments over days. Laptop RL on a 30B MoE is not a documented regime. Recommended order:
  (1) rejection-sampling SFT on Seon's own green traces; (2) DPO on same-task green/red pairs (offline, no rollouts during
  training, supported by `mlx-lm-lora`); (3) GRPO only after (1)-(2) show a gain and rollouts are cheap (a sandboxed SCI
  branch is far cheaper than a Docker container, which favors Seon).

## 4. Ranking and context selection

- No paper found that trains a ranking head on frozen embeddings for a coding agent and reports agent success gains [S, one
  search each]. Nearest results:
  - LLM judgment over top-50 candidate files: hit@1 0.58 (BM25) to 0.78, 50 SWE-bench Verified instances [S]
    (https://arxiv.org/pdf/2606.08151).
  - SHERLOC (training-free localization): +5.95 points average resolve, -36.7% localization tokens, -23.1% total tokens [S]
    (https://arxiv.org/pdf/2606.24820).
  - SWE-Explore: context efficiency correlates with resolve rate at Pearson 0.950 [S].
  - SWE-Exp: frozen E5-large embeddings plus an LLM reranker over past experiences, 41.6% Pass@1 [S]
    (https://arxiv.org/pdf/2507.23361). ExpRAG: LoRA-tuned agents using retrieved trajectories; argues fine-tuning alone and
    retrieval alone each underperform the combination [S] (https://arxiv.org/pdf/2603.18272).
  - Repoformer: retrieval did not help in about 80% of completions; a fine-tuned selector decides when to retrieve [S]
    (Amazon Science blog).
- Seon's advantage: labels are free. For each rendered context unit (pulled doc, source, reference) a turn either cites it
  in the form, reads it again, or ignores it; the task outcome gives the weak label. A head over the 256-dim vectors
  (concatenated task and unit vectors, plus cheap features like unit kind, age, size in tokens) is minutes to train and
  cheap to A/B by turns-to-green and context tokens. The tests-per-token metric already recorded is the evaluation.

## 5. Teaching a model a codebase

- Evidence is thin. One 2026 preprint on two proprietary repos (C/C++ and Java; Granite-8B and Llama-3.1-8B, SFT on 200k to
  316k prefix/completion pairs) found fine-tuning beat top-3/5 embedding RAG on completion edit distance by orders of
  magnitude (e.g. 45 vs 9,560) [V] (https://arxiv.org/html/2602.05780v1). Caveats from the same page: tiny test sets (156
  and 173), RAG used MiniLM embeddings and an untuned setup, Levenshtein is not correctness, numbers partly garbled, no
  agent task. It is not evidence for agent success.
- No controlled study of repo fine-tuning versus good retrieval for **agent** success found [S]. Plausible reading [E]:
  SFT on traces teaches the *interface* (tool/REPL protocol, naming, house style) well; facts about code change per commit
  and belong in retrieval, which Seon already does by content digest. Continued pretraining on a repo that changes every
  commit goes stale; Seon's "grow by accretion" argues against baking code into weights.
- Recommended split: weights learn Seon's REPL dialect and idioms (SFT on green traces); the database supplies code.

## Recommended sequence (cheapest first)

1. Ranking head on existing embeddings and trace labels. No GPU needed. Measure context tokens and turns-to-green.
2. Export proof-green traces as `chat` or `tools` JSONL; run `mlx_lm.lora` QLoRA on a 7B dense Qwen coder to measure
   throughput and memory; then Qwen3-Coder-30B-A3B. Evaluate on held-out proof tasks through the real REPL path, comparing to
   the base model and to DeepSeek-flash by turns, cost and green rate.
3. DPO with green/red pairs via `mlx-lm-lora`.
4. GRPO only if 2-3 show a gain; start tiny to prove the reward plumbing.

## Could not verify

- Any training speed, peak memory, or adapter size for 7B, 14B, or 16B/30B MoE on any Apple chip, and anything on M5 Max.
- Whether mlx-lm LoRA targets MoE expert (switch) layers well for Qwen3 MoE or DeepSeek-V2 architectures.
- `mlx_lm.server` adapter serving flags.
- Clojure ability of any candidate model; no benchmark exists that I found.
- DeepSeek-Coder-V2-Lite's standing against current models; whether a smaller open DeepSeek V4 variant exists.
- Qwen3-Coder SWE-bench and tool-use figures (model card gives none).
- SWE-RL (Meta) details; ReasoningBank results; any trained-ranking-head result for agents.
- Search-engine snippets of 2026 arXiv preprints (SHERLOC, SWE-Explore, memory-cards, CORE-Bench) were not opened; ids
  are as returned by search and unreplicated.
- mlx-lm-lora numbers are the project's own, approximate, and from a 24 GB M4 Pro.
