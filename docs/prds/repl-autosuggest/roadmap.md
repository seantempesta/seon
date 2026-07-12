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

- **A1** — `seon.repl.autocomplete` (context profile via ONE renderer
  `seon.agent.ctx/render-context` + `:seon.agent.ctx/profile` key,
  config→DB profiles, curation attrs, exporter). Acceptance now
  includes the three determinism holes (result-handles, warnings
  wall-clock, `@db/*conn*` reads).
- Vocabulary cleanup: "verbs"-named surfaces → functions names;
  deprecated `:relevant-source` deletion.

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
