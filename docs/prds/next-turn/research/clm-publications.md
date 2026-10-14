---
type: research
status: read in full 2026-09-26
tags: [research, agent, context]
---

# CLM and CoVer-VLA: what they tried and what they learned

Read in full on 2026-09-26:

1. **The CLM blog post.** Kwok et al., "Contrastive Language Models: A System One
   Model for Fast and Generalizable Decision-Making", Stanford / NVIDIA Research,
   posted 2026-09-23, <https://contrastive-lm.notion.site>, including its collapsed
   "experimental details" section. **This is the only CLM publication.** The
   citation says "Notion Blog"; there is no paper or arXiv PDF.
2. **The one peer-style PDF it links, its robotics precursor.** Kwok, Zhang, Xu,
   Liu, Mirhoseini, Finn, Pavone, "Scaling Verification Can Be More Effective than
   Scaling Policy Learning for Vision-Language-Action Alignment" (CoVer-VLA),
   arXiv 2602.12281v2, 18 Feb 2026, 9 pages.
   - The PDF ends at the references. The appendices it cites (§8.1–8.10:
     architecture, compute, prompts, latency) are not in it.

The code-level recipe (what `finetune.py` and `bon_eval.py` actually do) is in
`clm-recipe.md`.

## CLM, as published

**Architecture.**
- Frozen Qwen3-8B.
- The final token's hidden state, L2-normalised, goes into a trainable MLP head:
  4096 → 1536 → 1536 → 512, GELU, LayerNorm on the hidden layer.
- There is one head for states and one for actions; the score is their cosine.
- Only about 20M head parameters train. The LLM never gets gradients, so they
  compute LLM embeddings once and reuse them across every training setup.
- A full pre-training run takes about 1 hour on one RTX 4090.

**Training is three stages, each harder:**
1. **Pre-training:** about 60M Nemotron question–answer pairs (question = state,
   answer = action), bidirectional InfoNCE.
2. **Mid-training:** about 30M synthetic hard negatives. These are wrong but
   plausible answers written by Gemini 2.5 Flash-Lite, added to the loss's
   denominator.
3. **Post-training:** about 1M agent trajectory steps from ADP, Endless-Terminals
   and LiteCoder-Terminal-SFT (state = context, action = decision taken), with 40%
   Nemotron replay.

**What they learned (their measurements):**
- **Pre-training reshapes the space.** Raw Qwen3-8B embeddings rank the wrong
  answer first on a trivia example; the pre-trained head ranks the right one
  first at 54.2%.
- **Hard negatives are a refinement, not a substitute.** Evaluated on ~100k
  held-out questions, each with 1 gold answer and 10 hard negatives, top-1:
  - pre-training alone: 52.1%;
  - plus a short hard-negative stage: 69.2%;
  - hard negatives from the start: peaks at 62.4%, then overfits.
- **Replay prevents forgetting.** Post-training with 40% replay moves
  hard-negative top-1 from 69% to 68.5%; agentic data alone drops it to 56.2%.
- **Scaling laws** (fit on Nemotron, held-out test InfoNCE loss): loss falls as a
  power law in compute, data, head size and encoder size.
  - **Encoder size gives the largest gains.**
  - The optimal head size grows almost linearly with training tokens
    (N* ∝ D^1.02), at about **310 tokens per parameter**.
- **Latency** (200-token state, RTX 4090, 5 trials):
  - 36 ms with 1 candidate, 44 ms with 1,024;
  - flat at ~36 ms as candidate length grows from 1 to 2,048 tokens;
  - constrained decoding on the same backbone reaches 3.4–4.3 s;
  - Jev 131–579 ms.
- **Zero-shot results:** on computer-use, games (T-Rex, Super Mario), WikiRacing
  and tool calling, "on par with Jev", up to 9× faster. These are small, fixed
  action sets, or sets where actions repeat across states.
- **Agentic SOTA came from fine-tuning a trajectory reward model, not zero-shot.**
  - Setup: sample several full candidate solutions per task (Opus 5 for DeepSWE,
    Fable 5 for Terminal-Bench 2.1), and CLM picks the best.
  - Results: 81.6% on 38 held-out DeepSWE tasks, 87.6% on 30 held-out
    Terminal-Bench 2.1 tasks, measured on an H100.
  - Jev used as a verifier scored *below* random pick (pass@1).
  - n is small: 38 and 30 tasks.
- **Next:** CLM-35B (multimodal) is in training, with release "early next month";
  larger backbones and more hard-negative mining are planned.

**What the blog does not report:**
- next-step selection accuracy on real coding agents;
- calibration or abstention;
- any per-step "which context is needed" task;
- confidence intervals.

The agentic claims are best-of-N *verification of whole trajectories*.

## CoVer-VLA (the PDF): the same idea in robotics

**Problem.** Vision-language-action robot policies misread instructions (the
"intention–action gap"). Instead of training the policy on more data, they
**verify at test time**.

**Method.**
- **Contrastive verifier.** SigLIP2 image and text encoders are frozen and fused
  with text-aware attention. A transformer action encoder reads the recent action
  history plus the future action chunk. Training is bidirectional InfoNCE with
  in-batch negatives.
- **No failure labels.** Robot data contains only successful demonstrations, and
  hand-made negatives look unrealistic. So the other pairs in the batch are the
  negatives (§4.2).
- **Rephrase augmentation.** Each training instruction is paired with N VLM
  paraphrases while observation and action stay fixed. Using the same data
  budget to train a verifier beat using it to augment policy training.
- **Hierarchical test-time selection** (Alg. 2):
  1. At boot, a VLM reasons over the scene once and precomputes K instruction
     rephrases, with their embeddings cached.
  2. At each chunk boundary, the policy samples M actions per rephrase.
  3. The verifier scores all K×M pairs.
  4. **It picks the rephrase with the highest mean score over its M actions, then
     the best action within that rephrase.**
- **Deployment:** an ensemble of 3 verifiers of 1B parameters each. Training used
  batch 32,768, 16× synthetic instructions, and 2k steps on 8 H200s.

**Findings (their measurements):**
- **The test-time scaling law.** Oracle action error falls as a power law in the
  number of candidates. Rephrasing the instruction reaches the right action
  faster than repeated sampling or Gaussian perturbation. **Hybrid sampling**,
  several actions per rephrase, is best (Fig. 3).
- **Verifier scaling** (Fig. 6): top-1 retrieval accuracy rises with each of:
  - model size, 250M → 1B;
  - synthetic instructions, 8× → 64×;
  - in-batch negatives, 2k → 8k;
  - epochs, 2 → 16;
  - ensemble size, 1 → 8.
- **SIMPLER benchmark** (Fig. 7):
  - training-time rephrase augmentation alone: small ID gain (41.5 → 44);
  - random rephrases without verification: unstable (e.g. 78% on one task, 1% on
    another; OOD 29.7 → 28.7);
  - π0 + CoVer vs π0: +16% ID and +31% OOD;
  - vs scaling policy learning: +15% ID and +12% OOD, at much less compute;
  - combined with rephrase fine-tuning: 65.5% ID and 62.0% OOD.
  - The abstract's headline figures are +22% ID / +13% OOD vs scaling policy
    pre-training on the same data.
- **Other benchmarks:**
  - PolaRiS with π0.5: +13.9 points task progress, +9.3 points success (50
    episodes × 3 seeds);
  - two real-world tasks: +45% absolute, n = 10 per task;
  - the stronger π0.5 still shows large variance (Table 1).
- **Latency** (Table 2, RTX 5090): the verifier's action encoder adds 7–8 ms at
  any batch size, because the image-text encoding **runs in parallel with the
  policy's forward pass**. Sampling and verifying 16 candidates takes about 453 ms.

## What transfers to Seon, and what doesn't

**Transfers:**
1. **Train only on successes and let the batch supply negatives.** Their robot
   data had only successful demonstrations. Ours should be outcome-filtered,
   successful trajectories plus execution-verified synthetic pairs, not every
   step agents took. Hard negatives come after, as a short refinement.
2. **Replay, or lose generality.** Fine-tuning on Seon data alone risks the
   69 → 56% collapse they measured. Mix in their Nemotron/ADP data (if the
   embedding datasets are published) or keep a KL/replay term.
3. **Head size must fit the data.** At about 310 tokens per parameter, a
   10M-token Seon dataset fits a head of roughly 30k parameters, not 19M. So:
   - fine-tune the released head gently, with a low learning rate and early
     stopping on a held-out split, or
   - train a small residual adapter on top of it, or
   - fine-tune only its last layer.

   Training from scratch on our data would overfit.
4. **Instruction rephrasing is the task-focus mechanism the owner asked about.**
   - CoVer turns "which phrasing of the instruction makes the policy act right" into a
     verifier-scored choice, averaging over sampled actions.
   - The Seon analogue: generate several short, focused statements of the task's
     next step (from the obligation queue, templates or an LLM at boot time),
     embed them once, and pick the one whose candidate actions or context items
     the verifier scores highest.
   - The caution: random rephrases can be catastrophic without verification.
5. **Verify candidates rather than predict one answer.**
   - Their wins are all *selection among sampled candidates*: best-of-N
     trajectories, K×M instruction-action pairs.
   - For Seon: sample a few next forms or context sets cheaply, then let the
     verifier pick or abstain. That fits their evidence better than single-shot
     next-item prediction.
6. **Overlap scoring with generation.** Their verifier's state encoding runs
   during the policy's forward pass. Seon can encode the state while the
   provider call is in flight, as long as its inputs are final.
7. **Ensembles and bigger encoders** are the measured scaling levers. The encoder
   is frozen, so a larger one (CLM-35B next month) changes only the embedding
   cost.

**Does not transfer, or is unproven:**
- **Their headline numbers are best-of-N selection, not next-context selection.**
  No result speaks to "which of 100 task references is needed next".
- **The samples are small:** 38/30 agentic tasks and 10 real-world trials per
  task, with no intervals on the agentic results.
- **No calibration or abstention results anywhere.** The threshold for "show
  nothing" must be fit on our held-out data.
- **Both evaluations used their own held-out tasks.** No independent replication
  has been found.

## What the code shows that the blog doesn't (`clm-recipe.md`)

- **The "trajectory reward model" is trained only on successful steps.** Each state
  is paired with its taken action under in-batch InfoNCE, and failed runs never
  enter the loss.
- **Best-of-N picks the highest mean cosine over the last 12 steps.**
- **DeepSWE:** 31/38 vs 28/38 for a random pick. Only 13 held-out tasks mix passes
  and failures, so the gain is 3 of those 13.
- **Terminal-Bench (their own repo's figures):** 3-fold over 89 tasks gives 85.4% vs
  84.0% random. The untuned model scored 79.8%, below random. This does not
  reproduce the README's 87.6% on 30 tasks.
- **Abandoned attempts, visible in the code:**
  - failed-trajectory actions as hard negatives;
  - per-action labels from a Sonnet judge;
  - a 27B encoder.
- **The fine-tuning temperature is effectively frozen** by the clamp: exp(4.61) >
  100, so it gets no gradient.
- **The released trainer has no hard-negative input and no BCE loss.** Its `choice`
  task has a soft-target softmax (`softce`), which is the right starting point for
  multi-positive "which items are needed".

**Net:** the agentic gains are real but small, and the evaluation sets are small.
The zero-shot model is weak on long-horizon verification. Fine-tuning on
successes is what made it useful at all.

## Consequence for the plan

- **Our zero-shot result is consistent with their own evidence.** CLM's agentic
  wins required fine-tuning, and it lost zero-shot on our tasks. It does not
  refute fine-tuned use.
- **The fine-tuning experiment should follow their recipe:**
  - labels from successes plus execution-verified synthetic pairs;
  - in-batch negatives first, then a short hard-negative refinement from the
    structural candidates;
  - replay against forgetting;
  - a gentle fine-tune or small adapter;
  - large batches (cheap once embeddings are cached);
  - optionally a small ensemble.
- **Evaluate as selection with abstention,** reporting precision at the chosen
  threshold and coverage.
- **Test task rephrase selection (CoVer's language level) as the task-focus
  mechanism.**
