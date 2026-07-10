---
type: research
status: active
tags: [research, agent]
---

# Diffusion-LM infill / template-filling — literature survey

**TL;DR:** The literature confirms our three measured problems are the
field's known problems, and gives inference-time answers to two of them.
(1) **Hole length**: the fixed-size-mask canvas is THE named limitation of
dLM infilling; the trained fix is DreamOn's `<|expand|>`/`<|delete|>`
tokens (needs fine-tuning — not for us), but two **training-free** methods
exist: **CAL** (probe candidate hole lengths by average first-step denoise
confidence, hill-climb to the local max — a principled version of our
planned "hole-size search") and **ρ-EOS** (monitor implicit EOS-token
density mid-denoise: high density ⇒ contract, low ⇒ expand). (2)
**Suffix-echo**: no paper names or solves it at inference; DreamOn's
motivation section describes exactly this failure class ("infillings that
are 'stitched' to the suffix… redundant or truncated code"), and the
prompt-infilling paper shows the root cause is a **training convention**
(response-only masking in SFT locks instruct dLMs out of infilling), so
for a pretrained instruct model the honest answer is: shrink the hole to
near-true length (CAL) + oracle-side overlap-trim — our plan is the state
of the art, keep it. (3) **Constrained decoding** is solved and
inference-only: eth-sri's CFG completability logit-masking and DINGO's
distribution-preserving regex/DFA dynamic program both run on pretrained
LLaDA/Dream/DiffuCoder with small overhead — token-level logit masks at
hole positions are proven tech and we should extend our EOS/special-token
ban into a small grammar mask (paren balance, token-class masks at slot
positions). (4) **Remasking**: ReMDM gives a principled confidence-based
remasking sampler for pretrained MDLMs — an in-round complement, not a
replacement, for our oracle-driven scramble; FK-steering/SVDD show
mid-trajectory reward resampling beats best-of-N, which our
lock/harvest/scramble loop already instantiates with a stronger
(verifier) reward.

## 1. Who supports infilling natively, and how

| Model | Infill support | Mechanism | Notes for us |
|---|---|---|---|
| **LLaDA (8B)** | Native in the BASE model | Any-position masking is the training objective; condition on prefix+suffix by clamping them unmasked and denoising the mask span | Instruct variants degrade — see SFT-convention finding below |
| **Dream-7B / Dream-Coder / DiffuCoder** | Yes (fixed-length mask) | Same clamped-context masked-denoise; Dream-Coder is the base for DreamOn | The standard research substrate for infill papers |
| **DreamOn-7B** (HKU, arXiv:2602.01326) | Yes, **variable-length** | `<|expand|>` → becomes two `<|mask|>`; `<|delete|>` → removed; model predicts these from mask positions | **Requires fine-tuning** — mechanism unavailable to us |
| **Mercury Coder** (Inception, arXiv:2506.17298) | Yes — commercial `fim/completions` API endpoint | Proprietary; prefix+suffix in, infill out; 84.8% avg FIM accuracy (beats Codestral 2501's 82.5%) | Proof FIM-tuned diffusion coders work well; closed |
| **Gemini Diffusion / DiffusionGemma** | Advertised, not documented | DeepMind page: "Bi-directional attention… significant advantages for non-linear domains such as in-line editing and code infilling" | No published FIM prompt format or pad semantics — matches our finding that it is not FIM-trained |
| **SEDD / MDLM** | Structurally yes (research models) | Arbitrary masking; MDLM (arXiv:2406.07524) is the substrate for ReMDM | Too small/raw to matter for us directly |
| **BD3-LM (block diffusion)** | Within-block only | Attention is causal ACROSS blocks — a suffix in a later block is invisible | Our 256-token canvas is one block, so clamped suffix inside the canvas does condition; never rely on text beyond the block |

**Key mechanism finding (root cause of our echo):** *Unlocking Prompt
Infilling Capability for Diffusion Language Models*
([arXiv:2604.03677](https://arxiv.org/abs/2604.03677)) — "this capability
remains locked for infilling prompts due to the current supervised
finetuning (SFT) convention of applying **response-only masking**"; full-
sequence masking during SFT restores it, and "training practices, not
architectural limitations, are the primary bottleneck." An instruct-tuned
dLM (DiffusionGemma-it included) has been trained to see masks only as
"the response region after the prompt" — a mid-sequence hole with a
clamped suffix is out-of-distribution, and the model's best guess for the
hole tail is the distributional continuation, i.e. **a copy of the
suffix**. Our suffix-echo is this, mechanically. No retraining ⇒ we
mitigate, not cure.

Corroborating evidence that bidirectional edge-conditioning is real and
strong: *Extracting Training Data from Diffusion Language Models via
Infilling* ([arXiv:2605.24173](https://arxiv.org/abs/2605.24173)) —
"edge-conditioned masks extract up to **three times more** verbatim
sequences than prefix-conditioning, evidencing the bidirectional inductive
bias of DLMs." The suffix pulls hard; when the hole has slack, what it
pulls in is itself.

## 2. Hole length — the fixed-canvas problem and its solutions

The field names our problem exactly. DreamOn
([arXiv:2602.01326](https://arxiv.org/abs/2602.01326), [HKU blog](https://hkunlp.github.io/blog/2025/dreamon/)):
dLM utility for infill "is blocked by a critical limitation: the
requirement of a **fixed-length masked sequence** for generation."
Standard dLMs "handle length variations by initializing an oversized
canvas and filling unused positions with `<eos>` or padding tokens" — and
that does NOT work reliably (our measurement agrees: PAD-allowed did not
absorb slack).

### 2a. DreamOn (trained — SKIP for us, but know it)

- `<|expand|>` predicted at a mask position → "deterministically expanded
  into two `<|mask|>` tokens at the same position"; `<|delete|>` → "simply
  removed from the sequence."
- Training: mask-noise the data, then "randomly merging consecutive mask
  tokens into `<|expand|>` tokens" and "inserting random `<|delete|>`
  tokens"; the model learns to denoise these auxiliary states.
- Results: 90.8% avg pass across initial mask lengths {4,8,16,32,64} vs
  93.2% oracle-length — i.e. **near-oracle without knowing hole length**;
  DiffuCoder/DreamCoder+DreamOn beat Qwen2.5-Coder-7B on multi-line
  infilling.
- **Requires fine-tuning DreamCoder-7B** — not applicable to a frozen
  DiffusionGemma. Related trained approaches: DDOT (joint token+position
  denoising), FlexMDM ([arXiv:2509.01025](https://arxiv.org/abs/2509.01025),
  retrofits LLaDA-8B in 3 days on 16×H100: code infill 52%→65%) — all
  training-side.

### 2b. CAL — training-free length probing (ADOPT)

*Diffusion LMs Can Approximate Optimal Infilling Lengths Implicitly*
([arXiv:2602.00476](https://arxiv.org/abs/2602.00476)): inference-only,
"a length probing stage prior to formal decoding."

- Signal: **average first-step denoising confidence** over the masked
  region from a fully-masked hole, Φ(L). "The average first-step
  confidence exhibits a **local maximum when the mask length is close to
  the reference answer length**" — semantic completeness has a confidence
  peak.
- Calibration: confidence decays systematically with L; fit a
  double-exponential bias B(L) and search on Φ_c(L)=Φ(L)/B(L).
- Search: bidirectional hill-climb from an initial estimate, stop after D
  non-improving steps. Cost: **11–18 extra forward passes** (our forwards
  are ~114 ms warm; and our candidate-scoring experiments already showed
  1-forward probes are informative).
- Results: code-infill Pass@1 **+47.7% over fixed-length** baselines.

This is our planned "hole-size search (try n, n±4)" upgraded from grid
search to a calibrated 1-forward-per-candidate probe. Directly compatible
with the MLX loop — we already compute per-position confidence/entropy.

### 2c. ρ-EOS — training-free mid-denoise length adjustment (TRY)

*ρ-EOS: Training-free Bidirectional Variable-Length Control for Masked
Diffusion LLMs* ([arXiv:2601.22527](https://arxiv.org/pdf/2601.22527)):
"the implicit density (ρ) of end-of-sequence (EOS) tokens serves as a
reliable signal of generation sufficiency" — high EOS density among the
still-masked positions ⇒ contract the mask region; low ⇒ expand; single
unified denoising pass, demonstrated on LLaDA without training. Note the
inversion vs our experiment: we tried to make the model *emit* PAD for
slack (failed); ρ-EOS instead *reads* the EOS/pad probability mass as a
slack meter and has the **loop** delete mask positions. The signal exists
even when sampling never emits it. Caveat: abstract does not demonstrate
the suffix-clamped infill setting; needs a local A/B.

Related length-side reading: *Improving Variable-Length Generation via
Length Regularization* ([arXiv:2602.07546](https://arxiv.org/html/2602.07546))
(training-side); *Diffusion Language Models Are Natively Length-Aware*
([arXiv:2603.06123](https://arxiv.org/pdf/2603.06123)).

### 2d. Suffix-echo verdict (honesty)

**No published inference-time fix for the model regenerating the clamped
suffix inside an oversized hole.** The papers route around it: make the
hole the right size (CAL, DreamOn) so there is no slack to fill with an
echo. DreamOn's blog describes the failure class (stitched/redundant
infills at wrong mask sizes) as the motivation, and solves it with
training. So the plan of record stands: (1) CAL-style length probing to
remove the slack (removes the *cause*), (2) oracle-side overlap-trim of
hole-tail vs following clamp (removes the *residue*). Overlap-trim is our
invention as far as the literature shows — cheap, mechanical, keep it.

## 3. Constrained / grammar-masked decoding (inference-only, proven)

- **eth-sri, Constrained Decoding of Diffusion LLMs with Context-Free
  Grammars** ([arXiv:2508.10111](https://arxiv.org/pdf/2508.10111),
  [github.com/eth-sri/constrained-diffusion](https://github.com/eth-sri/constrained-diffusion)):
  per-step **completability check** — at each denoise step, for each hole,
  compute the token set that still permits a complete parse of the WHOLE
  sequence (prefix + holes + suffix), and mask the rest to −∞ before
  sampling. Rejection-free, inference-only; runs on LLaDA, Dream-Coder,
  DiffuCoder AND on AR FIM models (StarCoder etc.) — explicitly "the first
  generalized method for constrained decoding of **multi-region infilling**
  and out-of-order generation models." Guarantees syntactic correctness;
  functional correctness +up to 7%; "minimal computational overhead."
- **DINGO** ([arXiv:2505.23061](https://arxiv.org/abs/2505.23061)):
  regular-expression constraints as a dynamic program over a DFA product
  with the block's parallel token distribution — **provably
  distribution-preserving** (samples the highest-probability string
  satisfying the regex), up to +68 points on JSON/symbolic-math. Regex
  only, but exactly the "slot must be a keyword / enum literal / balanced
  simple form" shape our templates need.
- **Guidance from verifiers/rewards mid-denoise:** SVDD
  ([arXiv:2408.08252](https://arxiv.org/html/2408.08252v2)) — value-based
  best-of-k at *each step* with non-differentiable rewards, no fine-tune,
  explicitly applicable to discrete diffusion; **FK-steering**
  ([arXiv:2501.06848](https://arxiv.org/html/2501.06848v1)) — Feynman-Kac
  particle resampling along the trajectory, works with non-differentiable
  rewards, and the general finding is **mid-trajectory steering beats
  terminal best-of-N at equal compute**; D-CBG / simple discrete guidance
  ([arXiv:2412.10193](https://arxiv.org/html/2412.10193v1)) — FUDGE-style
  classifier guidance adapted to discrete diffusion; EntRGi
  ([arXiv:2602.05000](https://arxiv.org/pdf/2602.05000)) — entropy-aware
  reward guidance for dLM alignment.

Implication for us: our loop is already an FK/SVDD-shaped system with the
strongest possible reward (parse/lint/eval/behavioral oracle) applied
between rounds. The gap is **within-round token-level masking**: we
currently ban only EOS (and should ban special/channel tokens — the
`<|channel>thought` leak is a one-line extension of the same mask). The
literature says pushing constraints INTO the sampling step (paren-class
masks at boundary positions, enum-token DFA masks at slot positions) is
cheap, inference-only, and strictly reduces the oracle's repair load. We
do not need the full CFG machinery: Clojure's read-grammar for a
single-slot hole is mostly "balanced delimiters + token class," i.e.
DINGO-shaped regex, not eth-sri-shaped CFG.

## 4. Remasking strategies vs our entropy-bound acceptance

**ReMDM** — *Remasking Discrete Diffusion Models with Inference-Time
Scaling* ([arXiv:2503.00307](https://arxiv.org/abs/2503.00307), ICLR
2025): a sampler, "applied to pretrained masked diffusion models in a
principled way," derived as a custom remasking backward process. Fixes
the core MDLM flaw: "when a token is generated, it cannot be updated
again, even when it introduces an error." Variants include
**ReMDM-conf**: remask probability proportional to the model's (lack of)
confidence in already-committed tokens. More steps ⇒ better quality
(inference-time scaling); also "facilitates diffusion guidance" in
molecule design.

Relation to ours: our `_accept`/`_entropy` gate decides what to COMMIT
per forward (like LLaDA's low-confidence remasking / top-k confidence
decoding); ReMDM additionally UN-commits low-confidence tokens later.
Our oracle-driven scramble is a remasking policy too — but it remasks on
*verified* wrongness (parse/eval), which is strictly better-informed than
model confidence, at coarser granularity (spans, between rounds). The
literature does NOT show confidence-remasking beating verifier-remasking
(nobody has a verifier as good as ours); it shows confidence-remasking
beating *no* remasking. Cheap hybrid worth one experiment: within a
round, allow re-noising of committed-but-low-confidence tokens *inside
the current hole only* (never clamped positions) — ReMDM-conf scoped to
the hole. Also note bd3lms' own `_resample_q_xt`
(`reference-code/bd3lms/diffusion.py` ~L477: resample x_t while
`perc_masked` is outside `[sampling_eps_min, sampling_eps_max]` per
block) — that is a *training-time* noise-schedule clamp, not an
inference remasking policy; don't over-read it.

Also relevant: ParallelBench ([arXiv:2510.04767](https://arxiv.org/pdf/2510.04767))
documents the parallel-decoding quality trade-off our entropy bound tunes.

## 5. Diffusion + CODE with syntax-aware guidance — prior art

- **Mercury Coder FIM** ([arXiv:2506.17298](https://arxiv.org/html/2506.17298v1),
  [Inception API blog](https://www.inceptionlabs.ai/blog/introducing-inception-api)):
  production FIM endpoint; also "apply-edit" workflows — diffusion coders
  are commercially FIM-viable when trained for it.
- **eth-sri constrained-diffusion + DINGO** (above): the two real
  syntax-guidance-for-diffusion-code systems; both inference-only.
- **DreamOn / Dream-Coder** ([arXiv:2509.01142](https://arxiv.org/pdf/2509.01142)):
  the open training recipe for diffusion code models.
- **Template Infilling** ([arXiv:2510.13870](https://arxiv.org/abs/2510.13870)):
  generate a template with structural anchors first, then infill segments
  — "establishing a global blueprint before filling in the masked
  segments"; +9.40% on math/code/planning plus multi-token speedups.
  Independent validation of our schema→template direction (they have the
  model make the template; our DB makes a *verified* one — stronger).
- Nothing found that combines a live eval/REPL oracle with mid-denoise
  steering. Our lock/harvest/repair loop appears to be ahead of the
  published literature on that axis; the survey
  ([arXiv:2508.10875](https://arxiv.org/pdf/2508.10875)) lists no
  equivalent.

## Ranked recommendations

**ADOPT (do in the fill_guided build):**

1. **CAL-style hole-length probing** — replace grid "try n, n±4" with the
   calibrated first-step-confidence hill-climb (Φ(L) peak near true
   length; calibrate the length-decay bias once per model). ~1 forward
   per candidate length, 0.1–2 s total. Kills slack ⇒ kills most echo at
   the cause. [arXiv:2602.00476]
2. **Special/scaffold-token logit ban at ALL free positions** — extend the
   EOS ban to the whole special/channel token class (fixes the
   `<|channel>thought` leak; one line; DINGO/eth-sri prove token-level
   masks mid-denoise are sound).
3. **Keep oracle overlap-trim** for residual suffix-echo — no published
   inference-time alternative exists; ours is the mechanism.

**TRY (one measured A/B each):**

4. **ρ-EOS slack meter** — read EOS/pad probability mass over the hole's
   remaining masked positions mid-denoise; high density ⇒ shrink the hole
   (loop-side deletion) instead of hoping the model emits PAD.
   Training-free, but unproven in the suffix-clamped setting.
   [arXiv:2601.22527]
5. **DINGO-lite slot masks** — for enum/keyword/delimiter slots, a tiny
   DFA/regex logit mask (legal enum token ids, paren-class at boundary
   positions). Complements candidate-RANKING: ranking picks among DB
   values, masking guarantees free generation stays in class.
   [arXiv:2505.23061, arXiv:2508.10111]
6. **ReMDM-conf inside the hole** — allow re-noising committed
   low-confidence tokens within the current hole between our oracle
   rounds; measure against entropy-bound-only. [arXiv:2503.00307]

**SKIP (with reasons):**

7. **DreamOn / FlexMDM / DDOT expand-delete tokens** — need fine-tuning;
   revisit only if we ever fine-tune DiffusionGemma (then DreamOn is the
   canonical recipe).
8. **Full CFG completability decoding (eth-sri)** — machinery weight not
   justified when our oracle already parse-gates between rounds and holes
   are single-form; DINGO-lite covers the slot-level need.
9. **Classifier/reward guidance nets (D-CBG, EntRGi)** — training a
   guidance classifier is a worse verifier than the bb/node oracle we
   already run for ~0.1 ms.
10. **Best-of-N at the trajectory end** — literature (FK-steering, SVDD)
    agrees mid-trajectory steering dominates; we already steer
    mid-trajectory.

## Raw sources

- DreamOn: <https://arxiv.org/abs/2602.01326> · <https://hkunlp.github.io/blog/2025/dreamon/> — "deterministically expanded into two `<|mask|>` tokens"; 90.8% vs 93.2% oracle; requires fine-tuning DreamCoder-7B.
- CAL: <https://arxiv.org/abs/2602.00476> — "average first-step confidence exhibits a local maximum when the mask length is close to the reference answer length"; +47.7% Pass@1; 11–18 forwards.
- ρ-EOS: <https://arxiv.org/pdf/2601.22527> — "implicit density (ρ) of end-of-sequence (EOS) tokens serves as a reliable signal of generation sufficiency"; training-free, LLaDA.
- Prompt-infilling SFT convention: <https://arxiv.org/abs/2604.03677> — "locked… due to the current supervised finetuning (SFT) convention of applying response-only masking"; "training practices, not architectural limitations."
- Edge-conditioned extraction: <https://arxiv.org/abs/2605.24173> — "edge-conditioned masks extract up to three times more verbatim sequences than prefix-conditioning."
- eth-sri constrained decoding: <https://arxiv.org/pdf/2508.10111> · <https://github.com/eth-sri/constrained-diffusion> — completability logit-masking; LLaDA/Dream-Coder/DiffuCoder + multi-region infilling; +7% functional.
- DINGO: <https://arxiv.org/abs/2505.23061> — DP over DFA, distribution-preserving, +68 points JSON.
- ReMDM: <https://arxiv.org/abs/2503.00307> — remasking sampler for pretrained MDMs; ReMDM-conf; inference-time scaling.
- SVDD: <https://arxiv.org/html/2408.08252v2> — value-based per-step selection, non-differentiable rewards, discrete diffusion.
- FK-steering: <https://arxiv.org/html/2501.06848v1> — particle resampling along the trajectory.
- Discrete guidance (D-CBG): <https://arxiv.org/html/2412.10193v1> · EntRGi: <https://arxiv.org/pdf/2602.05000>.
- FlexMDM: <https://arxiv.org/abs/2509.01025> · <https://flexmdm.github.io/> — retrofit LLaDA-8B, code infill 52→65%.
- Template Infilling: <https://arxiv.org/abs/2510.13870> — "global blueprint before filling in the masked segments"; +9.40%.
- Mercury: <https://arxiv.org/html/2506.17298v1> · <https://www.inceptionlabs.ai/blog/introducing-inception-api> — FIM endpoint; 84.8% FIM accuracy.
- Dream-Coder 7B: <https://arxiv.org/pdf/2509.01142>.
- DiffusionGemma page: <https://deepmind.google/models/gemma/diffusiongemma/> — "significant advantages for non-linear domains such as in-line editing and code infilling" (no FIM format documented).
- Survey: <https://arxiv.org/pdf/2508.10875> · ParallelBench: <https://arxiv.org/pdf/2510.04767> · MDLM: <https://arxiv.org/pdf/2406.07524>.
- LLaDA: <https://arxiv.org/pdf/2502.09992> · improved: <https://arxiv.org/pdf/2606.25331>.
- Length regularization (training-side): <https://arxiv.org/html/2602.07546> · natively length-aware: <https://arxiv.org/pdf/2603.06123>.
