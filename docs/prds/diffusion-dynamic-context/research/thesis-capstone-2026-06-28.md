---
type: research
status: active
tags: [research, agent, web, database]
---

# Diffusion dynamic-context — thesis capstone + first-light go/no-go

> **CORRECTION (post-synthesis):** capability **#4 (live human feedback) IS now
> prepped** — `[[research/live-feedback-experiment-plan-2026-06-28]]` (committed
> after this synthesis was written). All FOUR capability plans + the unified
> worker + the runbook now exist; the "#4 pending / unprepped" notes below are a
> snapshot-timing artifact. #4's deployable architecture is Route A (per-step
> round-trip — Flash serverless has no input-into-a-running-job surface).

> The one-page synthesis of this session's work: the measured oracle, how it
> feeds the four capabilities, the first-light GO/NO-GO against the T0–T5 ladder,
> and the gaps prep CANNOT close until real DiffusionGemma output exists. This is
> a synthesis — for depth, follow the links; it does not re-derive the thesis.
>
> - [[index]] — push-ready state, the env-fix recipe, deploy mechanics.
> - [[first-light-runbook-2026-06-28]] — the ordered execute sequence + C1–C4.
> - [[research/parser-as-generation-oracle-2026-06-28]] — the measured 3-tier oracle.
> - Capability plans: [[research/infill-experiment-plan-2026-06-28]] (#1),
>   [[research/eval-renoise-experiment-plan-2026-06-28]] (#2),
>   [[research/retrieval-denoising-experiment-plan-2026-06-28]] (#3).
> - [[../agent-fsm/research/diffusion-llm-test-plan-2026-06-27]] — the T0–T5 ladder.

## The thesis in one line

A strong AR model (Opus) sets direction; a **diffusion** model is the **buzzsaw** —
it refines whole blocks of Clojure *in place*, taking feedback **between denoise
steps** (eval errors, retrieved specs, human edits) — bounded only by how fast Seon
can eval or retrieve. AR can neither see past the cursor nor revise a committed
token; diffusion does both natively (the shipped model already re-masks ~7.5
positions/gen).

## 1. The measured oracle — three tiers, with real numbers

The buzzsaw needs a cheap, tight feedback collar between denoise steps. This
session **measured that collar's economics** end-to-end ([[research/parser-as-generation-oracle-2026-06-28]]),
using Seon's *real* shipping parser (`seon.repl.internal/parse-forms`, rewrite-clj),
its parinfer repair (`seon.repair`), and its SCI eval cage (`seon.eval`) — no model
calls. The detection story is three tiers, each with a **provable blind spot the
next tier covers**, exactly as the diffusion papers predicted:

| tier | mechanism (Seon, shipping) | catches | measured | provable blind spot |
|---|---|---|---|---|
| **syntactic** | `parse-forms` `:span`/`:error-kind` + `seon.repair` | delimiter/token corruption | **92.7%** of 124 injected errors, instant; SAFE class **100% (95/95)** re-parses clean after parinfer with **zero model calls** | a corruption that still *parses* but means something else (3.2% masked-divergent) |
| **semantic** | the SCI eval cage (`seon.eval`, errors-as-data `:seon/error`) | masked-divergent (parses clean, meaning changed) | **91.5%** of masked-divergent caught; **62.5% via a hard error alone, no reference needed**; 29% diverges-in-value (needs a comparator/test) | **dead-data mutation** — corruption off the program's live computation path (8.5% residual) |
| **factual** | program graph (`:seon.fn/source` + Vertex/Proximum) | wrong fn/API name committed *confidently* | the AUROC-0.471 band the model cannot self-detect; **not yet measured live** | a correct name absent from the index can't be retrieved |

**Combined parser + eval catch 93.5%** of all meaning-altering corruptions
(1131/1210); the residual **6.5% is the dead-data silent class** — neither parsing
nor running surfaces it, only an intent-level (factual/retrieval) oracle can. The
three-tier syntactic→semantic→factual story closes exactly where it must.

**The load-bearing finding for the whole thesis:** the strong-model A/B was
**doubly NULL**. gemini-3.5-flash writes clean Clojure regardless of the repl skill
(0 errors either way); a *noisier* live DeepSeek agent on acme was also null
(guided trended *worse*, ~1% read-failure rate, ~0–1 auto-repairs per 100 evals).
**Capable autoregressive models barely engage the repair collar at all.** That is
not a disappointment — it is the scoping result: **the oracle's value is on noisy
per-step generation** (a diffusion model's commits, or a genuinely weak generator),
NOT on capable AR output. The thesis is therefore only meaningfully testable
against real DiffusionGemma canvases — which is precisely what first light
produces.

## 2. How the oracle feeds the four capabilities — the spine

The capabilities are not four separate builds; they are **one `accept_canvas`
override surface + one offset_map/clamp mechanism, reused three (four) ways**, fed
by the three oracle tiers. Seon **drives the loop**; the GPU worker is a stateless
denoiser (the pod is loopback `127.0.0.1:7890`, unreachable from a RunPod worker —
so no tensors cross the wire, only canvas ints + a short string).

```
  oracle tier          →  signal                          →  capability that consumes it
  ───────────────────────────────────────────────────────────────────────────────────────
  syntactic (parser)   →  :span / :error-kind              →  #1 infill: the granularity dial
                          SAFE vs FLAG taxonomy               (re-mask token-span vs repair-for-free)
  semantic (eval)      →  :seon/error + char span          →  #2 eval-renoise: WHERE to re-mask
                          (masked-divergent class)            (span → offset_map → renoise_positions)
  factual (graph)      →  symbol ∉ :seon.fn/sym  (Trigger B) →  #3 retrieval: inject the real spec
                          + entropy high (Trigger A)          (closes the AUROC-0.471 wrong-name gap)
  human (SSE)          →  accept/clamp/re-noise a region    →  #4 live feedback (plan PENDING)
```

The **shared mechanism**, built once and reused verbatim down the chain:

| piece | introduced by | reused by | what it is |
|---|---|---|---|
| `accept_canvas` override (clamp / re-mask / entropy-read) | #1 (U2/U3) | #2, #3, #4 | the single per-step commit seam — `accept_canvas(current_canvas, denoiser_canvas, logits[1,256,262144], cur_step)`, open transformers 5.11.0, no `trust_remote_code` |
| canvas seed + mask id (U1, U4) | #1 | #2 (V1 re-seed), #3 | how a non-empty `[prefix … MASK*H … suffix]` canvas is seeded; `resolve_mask_id` (C4, defensive) |
| `offset_map` + `span_to_positions` (the linchpin) | #2 (V2) | #3 | char-offset (oracle speaks chars) ↔ canvas-token-position (canvas is tokens); ONE canonical `diffgemma_common.build_offset_map` (C3) |
| clamp the good, re-mask the failing span | #2 | #3, #4 | in-place revision: re-mask `renoise_positions`, hold every other position to its committed id every step |
| entropy read off `logits` | #1 | #3 (Trigger A) | free commit-entropy signal, computed in the same override |

So `#2 reuses #1's U-answers; #3 reuses #1's U + #2's V (offset_map, clamp,
stateless round-trip) verbatim` — the prep deliberately does not re-derive. The
**Seon-drives-the-loop** round-trip architecture is identical across #2 and #3
(strongest cross-doc agreement); #1 is one-shot, simpler, no round-trip, and needs
**zero Seon integration**.

## 3. First-light GO/NO-GO — the T0–T5 ladder against what is now prepped

Everything below is prepped: the worker image is push-ready + validated, the
unified `gpu_worker.py` (probe + generate + full introspect in ONE cold load —
C1/C3/C4 resolved), the three capability stubs, and the ordered runbook. The
**only** remaining gate is the image push completing → `flash deploy`. Each rung
maps to a concrete probe/test and has an explicit kill criterion.

| rung | what answers it now | SUCCESS (thesis survives) | FALSIFY (kills/reframes) |
|---|---|---|---|
| **T0 smoke + cost** | runbook §2.1 `probe` + §2.2 first `generate` (the mean prompt) | model loads on A100-80 BF16, fits VRAM, emits a coherent parseable `(defn mean …)` + sane tok/s | won't load / OOM / garbage text → infra or model-fit failure, stop |
| **T1 observe the loop** | unified `introspect` (entropy read + `accept_canvas` source/sig U3) + `generate_canvas` surfacing `entropy[256]` + `committed_symbols` | we can read intermediate canvases + per-position commit-entropy; entropy **spikes on code-shape regions** (paper AUROC 0.749) | entropy null on code too → Trigger A is dead (but #3's Trigger B membership check still works — **reframe, not kill**) |
| **T2 infill (#1)** | `gpu_worker_infill.py infill`, cases 1–4, vs suffix-blind AR (`gemma-4-26B-A4B-it`), scored by parse + eval | diffusion beats suffix-blind AR on suffix-constrained holes (the fill parses AND evals to the right value) | **KILL GATE** — no edge over suffix-blind AR → the structural (bidirectional) premise is weak; stop and reassess |
| **T3 eval-renoise (#2)** | `gpu_worker_renoise.py` round-trip: `generate_canvas` → SCI eval → span → `renoise`; the `ys`→`xs` case | in-place re-noise converges **faster/cleaner** than AR forward-regeneration on single-region errors; all clamped positions byte-identical | re-noise doesn't beat cold-regen → in-place-revision premise weak for code repair; pivot to #4-only or reassess |
| **T4 retrieval (#3)** | Part 1 provable **today** (no GPU): `spec-for-span`/`search-pull` over `:seon.fn/source`. Part 2: `inject`, the `reduce-kv`→`reduce` Trigger-B case | injecting the retrieved spec **changes the re-committed name** (`reduce-kv`→`reduce`) vs re-noise WITHOUT injection — the delta IS the capability; closes AUROC-0.471 empirically | injection doesn't change the committed name → cross-attention conditioning too weak to steer commits; reassess |
| **T5 guided buzzsaw (#4)** | — **plan PENDING** (no experiment doc, not in the runbook) | Opus-guides + diffusion-refines matches AR quality at lower latency, or exceeds at equal latency | — (cannot run until #4 is prepped + T2–T4 pass) |

**What first light MUST show for the buzzsaw thesis to survive:** T0 loads → T1
yields a readable per-step entropy signal → **T2 shows an infill edge over
suffix-blind AR**. T2 is the first hard kill gate and the cheapest "clearly better
than AR" win — if the bidirectional structural advantage doesn't show on
suffix-constrained holes, the premise is weak and nothing downstream rescues it.

**What would kill it:** a T2 null (no infill edge) ends the structural premise. A
softer failure mode: T1 entropy null on code AND T4 injection can't steer commits
— that would leave no working mid-generation trigger, collapsing #3 to "re-prefill
and hope." T3 failing alone (re-noise no better than regen) downgrades rather than
kills (the loop still has #1 + #4).

## 4. Remaining gaps — what prep CANNOT answer until real output exists

Honest accounting of the confirm-on-deploy unknowns, aggregated, plus the
irreducible oracle residual:

- **The seam unknowns (U/V/W) are reflected, not invented — and unresolvable on
  paper.** The model card only shows the all-MASK `generate()` call. **U1–U4**
  (canvas seed, sampler attach, `accept_canvas` return-vs-mutate, mask id/layout),
  **V1–V3** (re-seed a partially-good canvas, offset-map decode fidelity, in-place
  convergence), and **W1–W3** (encoder KV extensibility, cross-attn mask widening,
  mid-denoise inject point) ALL require the live A100. The stubs deliberately ship
  the plumbing and report `STUB`, withholding any guessed `generate`/`renoise`/
  `inject` until `introspect` resolves the seam. First light's first job is to
  turn these from `STUB` into wired calls.
- **C2 (architecture framing — a watch, not a blocker).** #3's injection assumes a
  *separate encoder* with a cross-attended KV cache, but the Transformer Lab paper
  describes a block-diffusion model with the prompt as **prefix context**, just as
  plausibly decoder-only / prefix-LM. W1's `has_encoder_attr` probe decides it; if
  **False**, W1/W2 reframe from "extend the encoder cache + widen cross-attn" to
  "extend the prefix KV cache" — and **Route RE-PREFILL works regardless**, so #3
  is still provable. Flagged so a False reads as "reframe," not "blocked."
- **The dead-data 6.5% residual needs a fourth oracle.** The combined parser+eval
  tiers leave a precisely characterized blind spot: **corruption to data the
  program never observes** (e.g. `:e [10 20 30]`→`[10 2 30]` when the form only
  reads `(nth (:e m) 2)`; an `:id`→`:d` mangle on a map the computation never
  reads). Running the form cannot catch what running never touches — only a tier
  that compares against **intent** (a test, a spec, the factual/retrieval oracle)
  can. This is the semantic→factual boundary, one tier out, and it is genuinely
  irreducible without a model of intent.
- **Every number above is on a *proxy*, not on DiffusionGemma.** The 92.7% /
  100% / 62.5% / 93.5% are measured against closer-drop + single-char-deletion
  corruption — a *subset* of real diffusion noise (token substitutions,
  transpositions, mid-token corruption are not modeled). The detection/recovery
  split MUST be re-measured against **real DiffusionGemma canvases** once the env
  is unblocked. And the strong-model A/B null means the end-to-end "oracle guides
  generation" number is still owed by the diffusion arm itself.
- **Capability #4 (live human feedback into the denoiser) is unprepped.** It exists
  only as concept #4 in [[index]] — no experiment plan, no worker stub, not in the
  runbook, no T5 execution path. The "all four capabilities prepped" framing
  applies to **#1–#3**; #4 is the next prep item, blocked behind the same deploy.

## FLAGS — contradictions surfaced while synthesizing

- **Hardware: the T0–T5 test plan is stale on the GPU/cost decision.**
  [[../agent-fsm/research/diffusion-llm-test-plan-2026-06-27]] (dated 06-27)
  prescribes "**rent one 5090, serve the NVFP4 18 GB checkpoint, ~$8–12 total**"
  and calls A100/H100 "NOT needed for any traction probe." The newer GPU-cost
  analysis + [[index]] (06-28) **supersede** this: the decision is **A100-80GB
  BF16 on Flash serverless**, because (a) BF16 is needed for a *confound-free*
  quality baseline (quantization perturbs the very entropy/commit dynamics the
  thesis hinges on) and (b) ≥100k context needs Flash Attention, which is disabled
  on the 5090's Blackwell SM120. **The T-ladder's go/no-go *structure* (T0–T5,
  gates) remains valid and is what this capstone maps against; its hardware/cost
  section is obsolete.** The test-plan doc should be marked superseded-on-hardware
  or updated to A100-80 BF16, so a fresh reader doesn't rent the wrong card. (Not
  fixed here — flagging per scope; it is a one-line status edit on that doc.)
- **"Four capabilities" vs three prepped.** [[index]]'s TL;DR enumerates four
  capabilities in build order, and the runbook's STOP banner says first light
  "reconciles the three prep docs." These are consistent once you know #4 is
  deliberately deferred — but a reader skimming "the 4 capabilities" will look for
  a fourth plan that doesn't exist. Noted in §4; worth a one-word "(prep pending)"
  on #4 wherever the four are listed.
</content>
</invoke>
