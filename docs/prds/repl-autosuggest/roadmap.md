---
type: prd
status: active
tags: [prd, agent]
---

# REPL autosuggest — roadmap (we-are-here)

Design: [[design.md]] · Review: [[research/design-review-2026-07-12.md]].
Started 2026-07-12; scope = general REPL autocomplete (owner), v0 model
contract = copy-heavy form kinds (plan/transact/register).

## Built

- `reference-code/needle` vendored (submodule); source fully read.
- **B1 SHIPPED** (`5481ab36`, `src-needle/`): MLX port, parity 20/20
  greedy-exact vs JAX; prefill ~128k tok/s @1024 (8ms), decode 428
  tok/s single-stream → ~0.25s per full suggestion; Clojure 2.45
  chars/token (1.82× English/JSON), envelope tight-but-workable;
  overfit smoke green (f32 master weights). Findings: pretrained
  contrastive head is all zeros (retrieval must be trained or stay
  deterministic); constrained decoding is load-bearing.
- **KT0 census FIRED**: ~224 ok-eval turns total (all acme; default
  history wiped). Data recipe inverted — synthetic/gold primary, mined
  turns = held-out eval only.
- Adversarial design review (9 agents): right-track-with-changes; all
  11 changes folded into design.md.
- Reused, not rebuilt: `my.plan/reconcile!` round-trip, turn capture
  (`prompt-blob`/`reply-blob`/`rendered-as-of`), per-form eval rows,
  program-graph cards, the menu candidate derivation.

## In flight

- Vocabulary cleanup: "verbs"-named surfaces → functions names;
  deprecated `:relevant-source` deletion.

## A1 — SHIPPED (`af67b188`, 2026-07-12)

- **`seon.repl.autocomplete`**: `context` (the byte-exact encoder input —
  `seon.agent.ctx/render-context` + the `:autocomplete` profile),
  `rate!` + `::rating`/`::tag` curation datoms on turns, `export!`
  (JSONL to `data/tune/`, per-row ingredients coverage, built-in
  double-render determinism self-check, token summaries).
- **Profile mechanism** (selection + caps ONLY — frozen-context
  compliant): `:seon.agent.ctx/profile` on `render-context`/`context-root`
  (absent ⇒ byte-parity); per-block `:seon.agent.ctx/token-cap`/`cap-keep`;
  transcript `::readline?`/`::result-handles?` dials (default true);
  profiles config→DB via `:seon.config/context-profiles` (as-of-versioned;
  seeded in `config/system.edn` — keep in sync with the code default).
- **Live proof (acme store)**: 214 rows / 262 turns walked / 17 agents;
  **0 determinism mismatches** over 214 double-renders; contexts 206–678
  tokens (all under the 700 budget); targets p50 34 tokens (7 rows over
  the 512 decoder budget); 0/214 handle or readline leaks; coverage
  mean .64 (40 rows < .25 — context-gap evidence). Form kinds
  (overlapping): my.plan 111 · db/query 57 · in-ns/ns/require 53 ·
  register! 24 · defn 15 · transact! 10 · other 34. File:
  `data/tune/acme-2026-07-12.jsonl` — this IS the held-out
  real-distribution eval set (KT1/KT3 unblocked).

## Ordered path (spend-gated; thresholds in design.md §Measurement)

1. **KT1 tokenizer envelope** — needs A1's real exported rows; hours.
2. **KT2 zero-shot copy fidelity** — stock checkpoint, hours.
3. **KT3 frontier signal-ceiling on the profile** — the 224 held-out
   turns; fixes the projection before any training.
4. **KT4 oracle-injection uptake** (inspect-ai) — proves the channel
   before training spend.
5. **A2 data build** — synthetic/gold primary (staged db states +
   real profile renders; structured-markdown cheap supervision with
   reconcile-oracle filtering; quotas per design.md), agy
   augmentation.
6. **KT5 finetune reachability** (plan domain) → **B2 full train**
   (incl. Clojure-grammar constrained decoding; retrieval stays
   deterministic v0).
7. **B3 serve + `:suggest`** — call-once-then-derive, volatile-segment
   priority, diffusion offer channel; ship gate = $0 baseline loses +
   dead-weight criterion + frontier with/without arm.
8. **DG finetune (second consumer, owner 2026-07-12)** — the SAME
   canonical JSONL feeds a DiffusionGemma finetune ((context, forms) ≡
   (conditioning prefix, denoise region)); per-model formatter in the
   trainer, no second dataset pipeline. LoRA-on-MLX first (8-bit base
   + adapters), RunPod CUDA artifacts as fallback; training loop lives
   in the diffusion-gemma repo (model infra, not seon core); own
   reachability smoke before any long run. Independent of needle's
   KT5 outcome — gated only on the data build passing curation.

## Open questions

- Any persisted needle-embedding index = second index next to
  `seon.embed` — **owner-ruling gate**, must not land silently.
- ~200-line Python server scaffold copy from src-diffusion: recommend
  accept-for-v0, extract on a third server (flagged to owner).
- One `:openai-compat` gateway per cluster / one `SEON_DG_ENDPOINT`
  per pod — extend `agent-override-attrs` only when a second is
  actually needed.
- ~~Prompt-blob capture volume in prod~~ — RULED 2026-07-12: ONE
  capture dial gates prompt/reply/suggestion blobs; prod off, dev/acme
  on; `rendered-as-of` always-on. Lands with B3 (small turn.cljs +
  config change).
