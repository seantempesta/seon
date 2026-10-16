# Open-source decision models vs CLM (web research, 2026-10-16)

Question (owner): what is the best open-source decision-like model; can we upgrade CLM or take ideas?
Lane: Sonnet web research. Sources are cited inline. "Fetched" means a tool read the page and summarized it (summaries came from a small model, so exact numbers deserve a re-read before use). "Not verified" means a search snippet only.

## 0. Bottom line

1. **No open model matches CLM's exact shape** (frozen encoder, state and action embedded separately and cached, typed Noul/Choice/Score questions). The closest relatives are bi-encoder rerankers and pointwise or pairwise verifiers.
2. **CLM's headline numbers rest on tiny held-out sets.** The README says DeepSWE was 38 held-out tasks (31/38 = 81.6%) and Terminal-Bench 2.1 was 30 tasks; the 87.6% is not an integer fraction of 30 (26/30 = 86.7%, 27/30 = 90%), so it may be averaged over runs. Candidates came from Opus 5 and Fable 5, and the baseline is "Jev", a model we cannot identify. One task is about 2.6 points on DeepSWE. Treat both as vendor claims. (README read locally: `reference-code/CLM/README.md`, "Agentic benchmarks".) The HF org page lists a 62.5M-row pretrain set and a 406k-row DeepSWE embeddings set but shows no model cards, licenses or evals (fetched https://huggingface.co/Contrastive-LM). The repo code is Apache-2.0 (local LICENSE); the Qwen3-8B encoder is Apache-2.0.
3. **For a, c and d the strongest open baseline is the Qwen3-Embedding / Qwen3-Reranker family** (Apache-2.0, instruction-aware, 32K context, MLX conversions exist). It is the same Qwen3 backbone CLM uses, so the comparison is clean: what does CLM's contrastive post-training add over a stock instruction-aware encoder or reranker?
4. **For b (progress or verifier) there is no good open, cheap, general agentic PRM.** The published agentic PRMs are small, narrow (tool calling, ALFWorld) or not released. The independent benchmarks (AgentProcessBench, ToolPRMBench) say prompted open LLMs under 8B are weak step judges (about 55-59% StepAcc) and that a PRM below 50% accuracy can hurt best-of-N search. So b must be trained on our own trajectories regardless of base model.
5. **Cheapest fine-tune that fits d**: embed once with a frozen Qwen3 encoder (MLX, 4-bit), train small heads in MLX. This is CLM's own recipe, and it ports to MLX trivially. LoRA on a Qwen3-4B/8B classifier is the step up if heads plateau.

## 1. Comparison table

Evidence key: **I** = independent benchmark or replication, **P** = peer-reviewed or arXiv paper by the model's authors, **V** = vendor or README claim, **U** = unverified here.
Fit key (a classify turn against NL criteria, b verifier/progress, c rank context candidates, d cheap fine-tune on our trajectories in MLX): 3 = good, 2 = plausible, 1 = weak, ? = unknown.

| Model | Type | Size | License | MLX? | Context | Evidence | a | b | c | d |
|---|---|---|---|---|---|---|---|---|---|---|
| **CLM-v0.1-8B** (Contrastive-LM) | frozen Qwen3-8B last-token encoder + state/action heads, InfoNCE, typed questions | 8B encoder + 75 MB head | repo Apache-2.0; weights license not shown | no; vLLM pooling server. Encoder swap to MLX is easy (see sec. 5) | 2048 default, 8192 with flags | V (tiny held-out sets, unknown baseline) | 3 | 2 | 3 | 3 |
| **Qwen3-Reranker-0.6B/4B/8B** | cross-encoder as LLM, yes/no logit given Instruct+Query+Document | 0.6/4/8B | Apache-2.0 (fetched card) | no official MLX; plain Qwen3 causal LM, so mlx-lm loads it, but check the `yes`/`no` logit extraction | 32K | P+V (MTEB-R 69.76, MTEB-Code 81.20 for 4B) | 3 | 2 | 3 | 2 |
| **Qwen3-Embedding-0.6B/4B/8B** | last-token embedder, instruction on query side, MRL 32-4096 | 0.6/4/8B | Apache-2.0 | yes: community 0.6B 8-bit and 8B 4-bit DWQ builds; `mlx-embeddings` supports Qwen3 | 32K | P+V (8B: MTEB multilingual 70.58, English v2 75.22) | 2 | 1 | 3 | 3 |
| **jina-reranker-v3** | listwise reranker, all candidates in one context | 0.6B | CC-BY-NC-4.0 (non-commercial) | MLX build advertised (not verified) | 131K | V (BEIR 61.94) | 1 | 1 | 3 | 2 |
| **GLiClass** (modern-base/large v2/v3) | ModernBERT zero-shot multi-label classifier, labels given at inference | ~150M-400M | Apache-2.0 (v3 card; not checked per variant) | no known; ModernBERT is supported by mlx-embeddings | ModernBERT-base is long-context; token figure not verified | P (arXiv 2508.07662) | 2 | 1 | 1 | 2 |
| **ToolRM-Qwen3-4B/8B** | generative or discriminative pairwise reward for tool calls | 4B/8B | CC BY-NC-SA 4.0 (README) | no; Qwen3 arch converts | model default | P (arXiv 2510.26167: up to +17.9% over baselines on its own TRBench) | 1 | 2 | 1 | 2 |
| **ToolPRM (ToolPRMBench)** | step PRM, Base / CoT / GRPO variants | Qwen3-4B | not found | not found | n/a | P (best non-API model on its own benchmark; OOD gap ~20% for SFT variants) | 1 | 2 | 1 | 1 |
| **SWE-RM** (Qwen, ICLR 2026) | execution-free SWE verifier, trajectory-level | 30B MoE, 3B active | not stated; no HF release found | n/a | not stated | P (Qwen-Coder-Flash 51.6 -> 62.0 on SWE-Bench Verified with TTS) | 1 | 3 if released | 1 | 1 |
| **SWE-Gym verifier / R2E-Gym verifier** | outcome-trained verifier, yes/no token probability | Qwen2.5-Coder 14B/32B | not checked | n/a | long (full trajectories) | P (SWE-Gym 32.0% SWE-Bench Verified with verifier) | 1 | 2 | 1 | 1 |
| **AgentPRM / InversePRM** | step PRM with Monte Carlo targets or expert-vs-agent discrimination | 3B Llama | no weights found | n/a | n/a | P (ALFWorld only; reward hacking after ~400 RL steps) | 1 | 2 (recipe) | 1 | 2 |
| **Skywork-Reward-V2** (0.6B-8B) | general pointwise RM on Qwen3/Llama | 0.6-8B | open (collection) | Qwen3 arch converts; no classifier head in mlx-lm per search | 32K class | P+V (chat-preference benchmarks, not agentic) | 1 | 1 | 1 | 2 |
| **Prompted Qwen3-4B/8B (generative judge)** | LLM-as-classifier, read logits of option tokens | 4B/8B (4-bit ~2.5/5 GB) | Apache-2.0 | yes, fully supported (mlx-lm, LoRA via `mlx_lm.lora`) | 32K+ | I (AgentProcessBench: Qwen3-4B-Instruct-2507 58.9% StepAcc, Qwen3-8B 57.1%, best open Qwen3-30B-A3B-Thinking 68.5%, Gemini-3-Flash-Thinking 81.6%) | 2 | 1 (zero-shot) | 2 | 3 (LoRA) |
| **Generative judge LoRA'd on our data (Luna-2 style)** | LoRA on 3B/8B, output logprobs of label tokens | 3-8B | base license | yes | 32K+ | V (Splunk: F1 0.95 vs GPT-4.1 0.96 on one task) | 3 | 2 | 2 | 3 |

## 2. Findings by research question

### 2.1 Open PRMs and verifiers for agents and coding

- **Independent evidence says the gap to frontier models is large, and small open PRMs are fragile.**
  - AgentProcessBench (1,000 trajectories, 8,509 human-labeled steps, 89.1% inter-annotator agreement): Gemini-3-Flash-Thinking 81.6% StepAcc; best open model Qwen3-30B-A3B-Thinking-2507 68.5%; Qwen3-4B-Instruct-2507 58.9%. Models over-predict "good step". Small models lose about 23 points moving from HotpotQA to GAIA. Dedicated open PRMs were not tested. https://huggingface.co/papers/2603.14465 (fetched).
  - ToolPRMBench: API LLMs lead overall; open LLMs and general PRMs (WebShepherd-8B, Math-Shepherd, Qwen2.5-Math-PRM) mostly under about 55% on a pairwise correct-vs-plausible-wrong action task; PRMs under 50% accuracy often hurt best-of-8. ToolPRM-GRPO (Qwen3-4B) is best among non-API models, but SFT variants drop about 14-20% out of distribution while the GRPO variant gains. https://huggingface.co/papers/2601.12294 (fetched).
  - Takeaway: a step judge needs in-domain training, and RL-style training (outcome-verifiable) generalizes better than SFT on labels. Our "does this REPL form move toward the goal" signal is closest to ToolPRMBench's pairwise action task.
- **SWE verifiers** (SWE-RM, SWE-Gym, R2E-Gym) are trajectory-level, large, or not released. SWE-RM is 30B MoE with 3B active, reports Qwen3-Coder-Flash 51.6 -> 62.0 and Coder-Max 67.0 -> 74.6 on SWE-Bench Verified with test-time scaling, but I found no weights or license. https://arxiv.org/abs/2512.21919 (fetched). SWE-Gym: https://arxiv.org/abs/2412.21139. R2E-Gym: https://arxiv.org/pdf/2504.07164 (snippet only). SWE-TRACE: rubric PRM scores a completed trajectory; at test time it scores partial prefixes to prune; gains are 2-3 points, labels come from an LLM judge, no release statement, no human validation. https://arxiv.org/html/2604.14820v1 (fetched).
- **Agentic Rubrics** (https://arxiv.org/pdf/2601.04171): an LLM agent reads the repo and writes a rubric, then scoring is execution-free. Not read in detail; worth reading for how to turn criteria into checks.
- **Qwen's "Verification Horizon" paper** (https://arxiv.org/html/2606.26300v1, first 100K of 211K characters read): verifiers must co-evolve with the policy; a static judge is exploitable by length; over-specified evaluator prompts degraded best-of-N accuracy (67.4% -> 59.6%); a behavior monitor cut hacked-resolved from 28.6% to 0.6%. Strong evidence for the pitfalls in sec. 2.5.
- **Cheap to score (state, action)?** Only the bi-encoder family does it with cached state and action embeddings, which is CLM's design. Cross-encoder rerankers and generative judges need a full forward pass over state plus action per candidate (prefix caching on the state helps).

### 2.2 Embedders, rerankers, classifiers with natural-language criteria

- **Qwen3-Reranker** (card fetched, https://huggingface.co/Qwen/Qwen3-Reranker-4B): input is `<Instruct> <Query> <Document>`; the score is softmax over the `yes` and `no` logits at the last position. 0.6B/4B/8B, 32K, Apache-2.0. Instructions are worth about 1-5% on retrieval. Needs transformers >= 4.51 or vLLM >= 0.8.5. For (a), put the criteria in Instruct, the state in Query, and the REPL form in Document: the yes-probability is a criteria-conditioned classifier. For (c), it is the natural reranker. No official MLX build was found (search); since it is a Qwen3 causal LM with a `yes`/`no` readout, converting with `mlx_lm.convert` is plausible but untested here.
- **Qwen3-Embedding** (https://huggingface.co/Qwen/Qwen3-Embedding-8B, fetched): last-token pooling, query-side instruction (`Instruct: {task}\nQuery: {query}`), MRL dims 32-4096, 32K context, Apache-2.0. This is the same recipe as CLM's encoder (CLM uses plain Qwen3-8B last-token, not the Embedding variant). MLX: community 0.6B 8-bit and 8B 4-bit DWQ conversions exist (https://huggingface.co/AutomatosX/AX-Qwen3-Embedding-8B-MLX-4bit-DWQ; search result). `mlx-embeddings` (Blaizzy) lists Qwen3, ModernBERT, BERT, XLM-R. Note for conversions: the checkpoint is encoder-only Qwen3Model weights without an LM head, and key names need normalizing (per the Octen 4B MLX card: https://huggingface.co/davied-he/Octen-Embedding-4B-MLX-4bit).
- **GLiClass** (https://arxiv.org/abs/2508.07662; cards at huggingface.co/knowledgator/gliclass-*): labels in the input, one forward pass for all labels, Apache-2.0 on the v3.0 card. Good for open label sets over short text; I did not verify its context limit and it has no agent-trajectory training. Fast and tiny, so useful as a prefilter.
- **jina-reranker-v3**: listwise (candidates share one context), 131K, but CC-BY-NC. Useful as an idea (listwise scoring lets candidates see each other) rather than a dependency. https://jina.ai/news/jina-reranker-v3-0-6b-listwise-reranker-for-sota-multilingual-retrieval/
- **NLI and zero-shot cross-encoders** (DeBERTa-NLI family): not researched in this pass; they truncate at 512 tokens, which does not fit 8k-32k states.

### 2.3 CLM-like models and small generative judges

- I found **no open competitor with CLM's disaggregated state/action contrastive design** (searches for system-one, state-action contrastive and decision models returned PRMs, rerankers and verifiers only). Negative result is from search; not exhaustive.
- Nearest relatives: Qwen3-Embedding with instruction as the "question"; AgentPRM's InversePRM (discriminate expert transitions from agent transitions, https://huggingface.co/papers/2502.10325), which is the same signal as CLM's InfoNCE pull-toward-taken-action but trained as a classifier.
- **Small generative judges**: classification-head fine-tuning of a decoder (last-token hidden state to a head) matches or beats fine-tuned BERT on single-label tasks with 10-30x fewer trainable parameters (https://arxiv.org/html/2512.12677v1, snippet). A 2026 paper compares LoRA plus a score head against label generation on Qwen3 tiny models (https://arxiv.org/pdf/2607.03801; numbers not read). Splunk's Luna-2 uses LoRA on 3B/8B reading label-token logprobs (https://www.splunk.com/en_us/blog/learn/build-slm-judge.html, vendor claim). Fine-tuned topic-specific classifiers beat prompted LLM judges on relevance (https://arxiv.org/pdf/2510.04633, snippet).

### 2.4 Long context and Apple silicon memory

- Context: Qwen3-Embedding and Qwen3-Reranker support 32K (fetched). CLM's server defaults to 2048 and truncates; raising to 8192 needs `--max-model-len` and `clm-serve --max-tokens` together (README). Its DeepSWE head is trained on 8k embeddings.
- Memory arithmetic (my calculation, not a benchmark; verify with `mlx` memory counters): 4-bit weights are about 0.5 byte per parameter plus overhead, so 0.6B is about 0.4 GB, 4B about 2.5 GB, 8B about 4.5-5 GB. Qwen3-4B and 8B both use 36 layers, 8 KV heads and head dim 128 (from model configs as I recall them; confirm), which is about 147 KB per token in fp16, so a 32K state is about 4.7 GB of KV and an 8K state about 1.2 GB. Embedding or reranking runs one prefill with no decode, so KV is transient. 128 GB leaves room for 8B plus 32K plus batch-size tens.
- Prefill time dominates: state encoding is O(tokens). Because CLM caches state embeddings by content and only the small action text varies, its cost shape fits our "key it by content" law. The same trick works with any last-token encoder.

### 2.5 Training recipes and pitfalls

- **Heads-only on frozen embeddings** (CLM's recipe, `train/finetune.py`; its DeepSWE head is 75 MB). Pros: embed each state and action once, then training is seconds, thousands of examples is plenty, no catastrophic forgetting. Cons: ceiling set by the frozen representation. CLM's `docs/FINETUNING.md` is an agent loop that edits `finetune.py` against a fixed held-out best-of-N score; the idea (autoresearch on the head) is worth copying with our own held-out set.
- **LoRA on Qwen3-4B/8B** with mlx-lm (`mlx_lm.lora` works for Qwen3, e.g. the SwanLab Qwen3-0.6B guide, https://docs.swanlab.cn/en/guide_cloud/integration/integration-mlx-lm.html). I found **no confirmation that mlx-lm trains a sequence-classification or score head**; the practical route is generative: train the model to emit a single label token and read the logprob (Luna-2 style), which reuses `mlx_lm.lora` unchanged. A custom score head over the last hidden state is about 30 lines of MLX but is ours to write.
- **Contrastive training on MLX**: jina-ai/mlx-retrieval (https://github.com/jina-ai/mlx-retrieval, fetched) does LoRA plus InfoNCE/NT-Xent with hard negatives on Apple silicon, but only gemma-3-270m is tested, it is labeled educational, and rerankers are unclear. Useful as a loss and loop reference, not a drop-in.
- **Pitfalls with evidence**:
  1. Reward hacking of PRMs: AgentPRM saw success fall after about 400 RL steps while the PRM's validation reward kept rising (https://huggingface.co/papers/2502.10325). If we only rank or gate (never optimize against) the judge, this mostly does not apply; if agents are tuned against it, hold out a second judge.
  2. Weak labels: SWE-TRACE labels rubric pairs with one LLM judge and reports no human agreement; Qwen's paper says conservative annotation ("better to miss than to mislabel") and that over-specified evaluator prompts hurt. AgentProcessBench's human labels reached 89.1% agreement and models over-predict "good". Neutral steps are hardest.
  3. OOD collapse of SFT-trained step judges (ToolPRMBench: about 14-20% drop). Hold out by task, not by step. CLM's own heldout split is task-disjoint, which is right.
  4. Tiny eval sets: CLM's 38 and 30 tasks. Use paired comparison on our own data with bootstrap intervals, and report counts.
  5. Length and style exploitation by static judges (Qwen paper); strip length features or add a length-matched negative.
  6. Process vs outcome: AgentProcessBench found step accuracy correlates 0.81 with trajectory success but is not redundant, and combining outcome and process signals improved best-of-8 on GAIA. Keep both signals.

## 3. Ranked recommendation: what to try against CLM on our data

Common harness: one task-disjoint split of our trajectories; labels from tests-green and done-rule outcomes (not LLM labels) wherever possible; report accuracy or best-of-N with counts and bootstrap intervals; cost per call and tokens via `seon.profile/measure`. Use the same state text, truncated identically, for every model.

1. **Stock Qwen3-Embedding-8B (MLX 4-bit) + a small head trained on our data, instruction as the question** (control for "does CLM's pretraining matter"). Same architecture as CLM's serving path, Apache-2.0, native MLX, state embedding cached by content digest. If this matches CLM after head training, CLM's 60M-pair pretraining adds nothing for us and we drop the vLLM/CUDA dependency. Covers a, c, d.
2. **Qwen3-Reranker-4B with criteria as Instruct, zero-shot, then LoRA** (a, c). It is the open model purpose-built for criteria-conditioned relevance and takes 32K. Zero-shot on (c) is a day's work; compare against CLM `rank` on context-candidate selection with recall@k against what the agent actually used.
3. **CLM-8B as published, using its reference head**, run on the same split as the baseline we already have. Evaluate zero-shot on (a) and (c), and with our own head on (b). Use the existing local experiments in `docs/research/agent-platform/2026-10-16/clm/` as the starting point.
4. **Generative judge: Qwen3-4B or 8B with `mlx_lm.lora`, label-token logprob output** (a, b, d). Most flexible for open criteria and closest to what AgentProcessBench measured, so it has independent zero-shot baselines to compare ours against (58.9% StepAcc for 4B-Instruct-2507 as a reference point; expect to need training to beat that). One forward pass per (state, action, criterion); cache the state prefix.
5. **Pairwise progress judge trained as InversePRM**: for (b), train on (state, action taken by a successful run) vs (state, action from a failed run or a sampled alternative), on any of the above backbones. This is CLM's InfoNCE signal expressed as a classifier, and the only recipe here with published evidence for process rewards at 3B scale (AgentPRM, ALFWorld only).

Not recommended as primary: SWE-RM (no released weights found, trajectory-level, 30B), ToolRM (CC BY-NC-SA, tool-call focus), jina-reranker-v3 (non-commercial), Skywork-Reward-V2 (chat preference, not agentic), GLiClass (short text, but fine as a cheap prefilter).

## 4. Ideas worth taking even if we keep CLM

- **Disaggregate and cache by content.** CLM's best idea is that states and actions embed independently, so a state embedding is computed once and reused for every question and candidate. That is our "key it by content" rule applied to model inference. Any backbone can adopt it (cache the last-token state embedding by the digest of the rendered state).
- **Typed questions as a closed schema** (Noul probability, Choice distribution, Score expected level). These map straight to Malli schemas and make a judge's output data, not prose. Keep them even if the scorer changes.
- **Option texts embedded once, cached** (README: 106 tokens cold vs 38 warm). Criteria are static, so their embeddings are free after the first call.
- **Hard negatives and task-disjoint held-outs** as the training and evaluation discipline (CLM mid-trains on 30M hard negatives).
- **Autoresearch loop on the head** (`docs/FINETUNING.md`): an agent edits only `finetune.py` against a frozen eval, keeps commits that improve. It fits our dogfood stance.
- **Listwise scoring** (jina-reranker-v3): candidates share a context, so near-duplicates compete, which helps (c) when many context candidates overlap.
- **Rubric from criteria, then execution-free scoring** (Agentic Rubrics, SWE-TRACE): our task proofs are already executable criteria, so combine the learned judge with test outcomes and treat the judge only as a prior.
- **Judge hygiene from Qwen's paper**: keep evaluator prompts moderately detailed, validate against held-out test outcomes, and rebuild the judge as policies change.

## 5. Open questions and gaps

- I did not find a primary-source check of CLM's benchmark numbers; the local dig already could not reproduce them from public data. The README itself shows small sets and an unnamed baseline.
- MLX status of Qwen3-Reranker (and whether `yes`/`no` logit scoring works after conversion) is untested here.
- Whether mlx-lm or mlx-lm-lora can train classification or reward heads was not confirmed. The generative label-token route is the safe path.
- SWE-RM, ToolPRM and AgentPRM weight availability and licenses are unverified or absent.
- Qwen3 KV dimensions in sec. 2.4 are from memory; confirm with the config files.
- Not covered: NLI cross-encoders, Nemotron reward models, Themis code reward models (found only via snippet: https://huggingface.co/papers search "Themis"), Qwen3Guard-style classifiers.

## Sources

- https://huggingface.co/Qwen/Qwen3-Reranker-4B (fetched)
- https://huggingface.co/Qwen/Qwen3-Embedding-8B (fetched)
- https://arxiv.org/abs/2512.21919 SWE-RM (fetched)
- https://arxiv.org/abs/2412.21139 SWE-Gym; https://arxiv.org/pdf/2504.07164 R2E-Gym (snippets)
- https://arxiv.org/html/2604.14820v1 SWE-TRACE (fetched)
- https://arxiv.org/pdf/2601.04171 Agentic Rubrics (snippet)
- https://arxiv.org/html/2606.26300v1 Verification Horizon (partly fetched)
- https://huggingface.co/papers/2603.14465 AgentProcessBench (fetched)
- https://huggingface.co/papers/2601.12294 ToolPRMBench (fetched)
- https://huggingface.co/papers/2502.10325 AgentPRM (fetched)
- https://arxiv.org/abs/2510.26167 and https://github.com/lirenhao1997/ToolRM ToolRM (fetched)
- https://huggingface.co/Contrastive-LM (fetched); `reference-code/CLM/README.md`, `docs/FINETUNING.md` (read locally)
- https://github.com/jina-ai/mlx-retrieval (fetched); https://github.com/Blaizzy/mlx-embeddings (snippet)
- https://huggingface.co/AutomatosX/AX-Qwen3-Embedding-8B-MLX-4bit-DWQ; https://huggingface.co/davied-he/Octen-Embedding-4B-MLX-4bit (snippets)
- https://arxiv.org/abs/2508.07662 GLiClass (snippet)
- https://jina.ai/news/jina-reranker-v3-0-6b-listwise-reranker-for-sota-multilingual-retrieval/ (snippet)
- https://arxiv.org/html/2512.12677v1; https://arxiv.org/pdf/2607.03801; https://arxiv.org/pdf/2510.04633; https://www.splunk.com/en_us/blog/learn/build-slm-judge.html (snippets)
- https://arxiv.org/abs/2507.01352 Skywork-Reward-V2 (snippet)
