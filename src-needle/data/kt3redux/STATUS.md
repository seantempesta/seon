# KT3-redux run state — HALTED 2026-07-12 (owner stop order)

Halted mid-matrix: the v2 display under test is defective (cards
over-compacted — `{:malli/schema …}` stripped so the arg contract is
missing; stale deleted-fn cards ride as distractors; glyph decoration
taxes every model). Re-run pending the v3 export (spec-bearing cards,
ASCII render, stale-card filter). Report:
`docs/prds/repl-autosuggest/research/kt3-redux-full-index-2026-07-12.md`.

All raw predictions in this directory are keeper evidence (owner: the
thinking arm's reasoning traces are a candidate data-generation recipe).

## Arm inventory

COMPLETED (n=210 unless noted):
- deepseek/instr (full index, plain layout)
- deepseek/instr-rowcards (row's 4 cards, new instruction — control)
- deepseek/instr-sandwich · instr-structured · instr-retrieve (layout sweep)
- muse/instr (full index; complete first-class run)
- qwen25c-1.5b-instruct/instr-few · instr-zero (full index)
- qwen25c-1.5b-base/cont-few (exemplar-continuation, ⟹-boundary reclean)

PARTIAL (halted mid-run):
- qwen25c-1.5b-base/cont-bare — 165/210 preds, scored on the 165
- deepseek/instr-think — 3-row mechanical smoke only (content non-empty,
  reasoning separate; full run never started)

NEVER STARTED (stop order landed first):
- qwen35-0.8b-base cont-few/cont-bare
- qwen35-2b-base cont-few/cont-bare · qwen35-2b-instruct instr-few
  (checkpoints converted and generation-smoked: `src-needle/checkpoints/
  qwen3.5-2b-base-4bit`, `qwen3.5-2b-4bit` — ready for the v3 re-run)
- qwen25c-1.5b-instruct instr-few-rowcards (collapse-attribution control)
- 1.5B-instruct local layout sweep; muse best-layout / muse thinking

Sibling-lane rescores in this dir (LoRA lane, via lora_rescore_4card.py):
- deepseek/instr-4card · qwen25c-1.5b-instruct/instr-{few,zero}-4card ·
  qwen25c-1.5b-lora/instr-zero-4card — old 4-card preds under scoring v2.
