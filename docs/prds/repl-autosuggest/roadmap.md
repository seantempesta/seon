---
type: prd
status: active
tags: [prd, agent]
---

# REPL autosuggest — roadmap (we-are-here)

Design: [[design.md]]. Started 2026-07-12. Scope widened same day
(owner): general REPL autocomplete for EVERY turn — data modeling,
querying, transacting, defn-writing, plans — not plan-only.

## Built

- `reference-code/needle` vendored as a submodule; architecture,
  dataset pipeline, finetune loop, constrained decoder fully read.
- Prior art confirmed shipped and reused (NOT rebuilt here):
  `my.plan/reconcile!` markdown round-trip (P7 W1/W2), turn capture
  (`prompt-blob`/`reply-blob`/`rendered-as-of`), per-form eval rows,
  `#code` heredocs, program-graph `:seon.fn` cards.

## The gap

No projection fn, no curation attrs, no exporter, no `src-needle`, no
trained checkpoint, no `:suggest` section.

## Ordered path

1. **A1 — `seon.repl.autocomplete` context + curation + exporter**
   (pod-side, CLJS). Context = `render-context` with a small block
   profile (no second renderer); exporter walks
   `agent-turns`, renders at `rendered-as-of`, extracts ALL ok forms
   (no domain filter), emits JSONL. Prove on acme store first (this
   lane's harness; mind commits on the shared branch).
2. **B1 — `src-needle` MLX inference port** + pkl→safetensors +
   JAX-CPU parity proof (parallel with A1). Measure Clojure token
   efficiency against the 1024 budget here.
3. **A2 — gold exemplars** per domain (schema/query/transact/defn/
   plan) once A1's format is proven on real mined rows.
4. **B2 — MLX finetune loop** (AdamW first) + Clojure-grammar
   constrained decoding + contrastive fn-retrieval finetune; train v0
   on mined+gold.
5. **B3 — serve + `:suggest` section**, fail-soft, every turn;
   held-out gate (parses ∧ evals-clean ∧ matches, per form-kind).
   Inspect-AI task for agent-level lift.

## Open questions

- Prompt-blob capture volume in prod (dial to disable capture while
  keeping `rendered-as-of`?) — flagged 2026-07-12, not blocking.
- Tokenizer efficiency on Clojure text vs the 1024 budget — measure in
  B1; retrain tokenizer only if measured to blow the budget.
- Sibling ordering: created-at only today; add an ordinal only if
  subagent dispatch proves to need it.
- Retrieval candidate pool size vs contrastive-encode latency at
  inference (whole fn surface each turn? cache card embeddings —
  content-addressed, embed once).
