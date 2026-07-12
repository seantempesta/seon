---
type: prd
status: draft
tags: [prd, agent]
---

# REPL autosuggest — a tiny form-prediction model for every turn

A 26M-param encoder-decoder (needle / "Simple Attention Network",
`reference-code/needle/`, MLX port in `src-needle/`) finetuned to
predict the agent's next **REPL forms** from a compact projection of
the turn state. General autocomplete for the whole REPL surface — the
serving north star is suggestions at every turn for everything (data
modeling, querying, transacting, writing functions, plans); the v0
MODEL contract is narrower (see Target granularity). The suggestion
renders into context right before the prompt (autosuggest, not
autonomy): the driving model sees the suggested forms and can accept,
modify, or ignore them. The tiny model is not asked to think — it
learns the MECHANICAL mapping from situation to the forms a smart
model writes in that situation.

Adversarial review 2026-07-12 (verdict: right-track-with-changes, all
changes folded in below): [[research/design-review-2026-07-12.md]].

## What it replaces

- **`:relevant-source`** (the deprecated embedding KNN "possibly
  relevant entities" block) — weak signal, keyed on the latest message,
  wired nowhere; DELETED (owner, 2026-07-12).
- **The rendered function menu** (`menu.cljs`, the P6 section formerly
  named with the retired "verbs" coinage) as a *prompt surface*. Its
  candidate DERIVATION survives as the card pool: (recency group +
  required-ns toolkit group) feeds the encoder's cards slot. Once
  `:suggest` is measured, re-measure whether the menu still earns its
  render (the `:plan`/`:plan-ledger` consolidation precedent).

## Why needle fits — the structural mapping

Needle's task shape is `[query <tools> tool-defs] → tool-call JSON`.
Ours is the same shape re-based:

| needle slot | ours |
|---|---|
| query | the situation projection (autocomplete context profile) |
| `<tools>` + tool defs | fn/schema CARDS (deterministic selection + existing `seon.embed` top-up) |
| answers (JSON calls) | multi-form Clojure (the REPL grammar) |

- Attention-only encoder-decoder (no FFN), d=512, 12 enc / 8 dec
  layers, GQA + RoPE, ZCRMSNorm + gated residuals, 8192 BPE
  byte-fallback. Their own design doc is explicit about the envelope:
  no-FFN works because the task is **retrieval-and-assembly from the
  encoder input** (match, extract, copy) — not
  generation-from-knowledge. Our target scoping honors that.
- **B1 MEASURED (2026-07-12, M5 Max, commit `5481ab36`):** MLX port
  parity 20/20 greedy-token-exact vs JAX; prefill ~128k tok/s on the
  full 1024-token envelope (8ms); decode 428 tok/s single-stream; a
  full suggestion (max context + ~100-token forms) ≈ **0.25s**.
  Clojure tokenizes at 2.45 chars/token vs 4.48 for English/JSON
  (1.82×): ~2,500 chars of context, ~14 typical forms of output fit.
  Finetuning works (overfit smoke: loss 8.5→0.003, 10/10 memorized;
  f32 master weights required).
- **The pretrained contrastive head is dead weight** (B1 finding: all
  weights exactly zero — pretrain decay ate it; predicted by needle's
  own safe-L2-norm comment). It is NOT the retrieval half. v0 card
  selection is deterministic (current-ns fns, plan-step-referenced
  symbols, registered attrs of entities in the projection) with
  optional semantic top-up via the EXISTING `seon.embed`/Proximum
  stack. Any persisted needle-embedding index is a second index — an
  owner-ruling gate, never a silent landing.
- **Constrained decoding is load-bearing, not a nicety** (B1: even on
  its home task, unconstrained greedy garbles argument keys). The trie
  state machine (`needle/model/constrained.py`) ports to the Clojure
  grammar: symbol position clamps to the known fn surface (program
  graph `:seon.fn` rows), keyword position to registered schema keys,
  plan-id position to live ids. Values stay free.
- Trained envelope: **1024 encoder / 512 decoder BPE tokens** — the
  binding constraint. RoPE has no hard cap but stay in the envelope.
  The tokenizer CANNOT be swapped without losing the checkpoint
  (embeddings are shared and tied to the output projection,
  `architecture.py:331,363` — retokenize ≡ pretrain ≡ infeasible
  locally). KT1 decides whether the stock tokenizer holds.

## The byte-exact requirement (owner, 2026-07-12)

Training context MUST match inference context byte-for-byte, and MUST
be regenerable from a database point in time. Therefore:

- **No second context renderer.** The encoder input comes from the
  pod's ONE prompt producer — **`seon.agent.ctx/render-context`**
  (ctx.cljs; the `.clj` sibling is the paused JVM track) — invoked
  with a PROFILE: one new optional request key
  `:seon.agent.ctx/profile` (block-name list + per-block patch maps;
  absent ⇒ byte-parity with today). Profile definitions live
  config→DB (`:seon.config/context-profiles`) so an as-of export
  regenerates under the profile in force at that t.
  `seon.repl.autocomplete/context` is a thin wrapper: db value in,
  render-context with the autocomplete profile, text out.
- **Determinism holes are A1 acceptance criteria** (verified 2026-07-12):
  `result/<id>` handles depend on live process identity; `:warnings`
  reads wall-clock + live vars; cache-breakpoint/escape paths read
  `@db/*conn*` instead of the passed db. The profile render must be
  pure over the PASSED db value — strip/exclude/purify each.
- The projection is NOT the prompt blob. Prompt/reply blobs
  (`:seon.agent.turn/prompt-blob` / `reply-blob` — content-addressed
  `my.blob` files; datoms hold only hash/token projections) remain
  debug ground truth and a validation oracle, never the training
  input.
- If profile/render code changes, exported datasets are STALE — never
  patch them; re-export (derive-don't-store applied to training
  data). Each JSONL row stamps a projection version (git sha).

## The autocomplete profile (situation slot)

Block list (settled by A1, contract not prose): current position
(current-ns + the `:plan` block's windowed bands — the live
`:my.plan/id`s the model must emit are thereby IN the encoder input),
recent tail (previous turn's eval sources + result heads, capped),
pending inbound guidance (most recent unaddressed message, capped).
`:warnings` only if purified. The profile excludes `:suggest` by
construction (no self-reference).

## The target (decoder output, ≤512 BPE)

Multi-form Clojure exactly as the pod parses it (`parse-forms`).
Training pair for a mined turn: (profile context at `rendered-as-of` +
cards) → the turn's `:seon.eval/ok? true` form sources, in order.
Failed forms excluded for free — the db already knows.

**Target granularity (settled 2026-07-12, decides what v0 promises):**

- **Whole-form targets**: plan forms, `db/transact!` maps,
  `schema/register!` — the copy-heavy kinds (~70-80% of their tokens
  are copies from the projection/cards), inside the no-FFN envelope.
- **Query `:where` graphs and `defn` bodies are NOT v0 whole-form
  targets** (~45%/~25% copy — outside the envelope). They ship either
  as bounded continuations (next attr keyword, next form frame) or as
  explicit v0 exclusions routed to the frontier/worker. "General at
  every turn" is the serving surface's north star, not the v0 model's
  contract; the per-form-kind gate confines v0 honestly.

**The plan-domain target (the flagship, existing `my.plan` surface
ONLY — no new plan features):**

- Fresh authoring against an empty/near-empty tree → `reconcile!` with
  the markdown heredoc (authoring IS reconcile-against-empty; nothing
  to resolve). This is the ONLY situation where the markdown-wrap
  target is right.
- Incremental asks / non-empty trees → **shape selection + resolved
  delta forms**: `step!`/`needs!`/`active!`/`move!`/`done!` with real
  live `:my.plan/id`s read from the rendered `:plan` block; genuine
  re-plans → `reconcile!` with ids preserved from the rendered
  document. This is where the model earns its existence — the
  deterministic parser's ceiling is line syntax; judgment = identity
  resolution, ordering→`::needs` inference, `::expect` distillation,
  delta-vs-whole-document choice (whole-document-as-reflex is the
  drive-evidenced destructive shape: unexpected `:dropped` receipts).
- Dataset quotas are mandatory or the model learns a no-op: deliberate
  proportions of incremental-asks-against-non-empty-trees,
  id-resolution cases, ordering-bearing lists.

## Data sources (INVERTED 2026-07-12 — KT0 fired)

Live census: **~224 ok-eval turns exist, all in acme** (default's
history was wiped by cluster reset; its blobs are unminable orphans
under the byte-exact rule). Per-kind: `transact!` 4, `reconcile!` 5,
`defn` 21, `register!` 22, `query` ~58 — vs needle's ≥120-per-kind
guidance. Therefore:

1. **Primary: synthetic/gold.** Frontier-driven sessions on ephemeral
   clusters generating (profile-context, forms) pairs at scale —
   situations staged as REAL db states, contexts rendered by the real
   profile (byte-exact even for synthetic data) — plus
   orchestrator-authored gold with agy paraphrase augmentation.
   `reconcile!`-as-cheap-supervision applies NARROWLY: as a label
   source only for STRUCTURED frontier markdown where the parse is
   information-preserving; always as the oracle/filter (parse+compile
   every candidate label; an unexpected `::dropped` count
   auto-rejects). Mechanically compiling LOOSE markdown teaches
   ordering-erasure and id-destruction-as-gold — banned.
2. **Held-out eval: the ~224 mined turns.** Never training data. The
   exporter doubles as the forward-mining pipeline for live turns as
   they accumulate (turns carrying a `:suggest` row are
   suggestion-contaminated and filtered by the exporter).
3. **Curation datoms** (`:seon.repl.autocomplete/rating` / `tag`) mark
   gold/excluded turns — genuine judgments, stored with provenance.

Format: JSONL — `{"context": <profile text>, "cards": [...], "target":
<forms text>, "meta": {turn-id, agent, basis-t, store,
projection-sha}}`. `src-needle` owns its dataset pipeline.

## Model roles (owner direction, 2026-07-12)

Multiple models at work, each specifiable cleanly — zero new
mechanisms:

- **Planner — DERIVED, never stored**: a live agent whose resolved
  provider satisfies `seon.ai/frontier-provider?` (over
  `provider-locality`); consumed by `my.plan.internal/planner-for`.
  Role = a predicate over resolved provider locality, never a
  `:seon.agent/role` attr (no-kinds).
- **Worker — per-agent overlay datoms**: `:seon.ai/agent-provider` /
  `:seon.ai/agent-model` (+ dials), default `:inherit`; resolution
  request-opt → agent override → `:seon.ai/config` row → defaults;
  pure over any db value incl. as-of (which model ran at any past turn
  is a derivation).
- **Autocomplete — NOT a provider; a job-owned config section**:
  `:seon.config/suggest {:seon.repl.autocomplete/endpoint …
  :seon.repl.autocomplete/model …}` → boot-resolved into `:seon.config`
  datoms → read via `config/config-view` (live-tunable,
  as-of-replayable). Env seed `SEON_NEEDLE_ENDPOINT` — its own var.
  **Forbidden:** registering needle in `provider-locality` (a 26M
  encoder-decoder is not a chat provider) or overloading
  `SEON_DG_ENDPOINT` (needle can't serve `mode=step`).

## Serving — call once, then derive

- `src-needle/` local server speaks the src-diffusion wire contract
  (`POST /run` + `/runsync`, `GET /status/{id}`, `/health` with
  `worker_sha`, in-band `gen_error`) + `mode:"suggest"` —
  `bin/seon`-registered, idle-unload. The CLJS submit/poll transport
  is EXTRACTED from `seon.ai.diffusiongemma` and shared, not a second
  fetch loop; suggest uses `/runsync` (bounded timeout, single
  attempt, NO retry — fail-soft is absence).
- **A model output is a runtime artifact, not a derivation**: a
  pre-render step calls the server and transacts the suggestion as a
  turn-scoped datom (+ blob for the text); the `:suggest` block is
  then PURE over that stored row — absent row ⇒ renders nothing. This
  keeps as-of re-renders deterministic AND stamps every mined turn
  with suggestion-contamination for the exporter.
- `:suggest` renders with priority in the **volatile** segment (after
  the cache-breakpoint split) — a per-turn block in the stable prefix
  would bust the provider prompt cache every turn.
- **Second consumer — the diffusion worker**: needle-predicted forms
  drop into the typeahead offer channel as richer offers/prefill seeds
  (clamp/prefill wire segments the worker already understands). At
  0.25s worst-case it fits inside every cursor step-open.

## Measurement (three-surfaces rule) — kill-tests FIRST

Trainer-internal metrics live in `src-needle`; the suggestion gate =
**parses ∧ evals clean against the turn's as-of world ∧ matches the
smart model's actual forms**, per form-kind. Agent-level lift = an
inspect-ai task. The spend-gated ladder (full thresholds in
[[research/design-review-2026-07-12.md]] §6):

- **KT0 census — FIRED** (224 turns; recipe inverted above).
- **KT1 tokenizer envelope** (hours, sentencepiece only): >~25% of
  real projections+cards over 1024 enc, or median target >512 dec, or
  byte-fallback >~35% on targets ⇒ the vehicle dies (no retokenize
  fallback exists).
- **KT2 zero-shot copy fidelity** (hours): Clojure-shaped identifiers
  through the stock checkpoint; value-accuracy <~50% while English
  stays high ⇒ kill the retarget.
- **KT3 frontier signal-ceiling** (hours, ~$5): frontier model given
  the EXACT profile+cards for the 224 held-out turns; <~30-40%
  useful-match ⇒ the projection is the defect — fix it before any
  training.
- **KT4 oracle-injection uptake** (~1 day, inspect-ai): inject each
  turn's ACTUAL gold forms as `:suggest` vs control; ~zero
  uptake/outcome delta with perfect suggestions ⇒ kill B3 serving
  (keep the dataset).
- **KT5 finetune reachability** (~1-2 days, MLX): 300-500 plan-domain
  pairs; held-out exact-match <~40-50% after train >90% ⇒ the easiest
  kind doesn't generalize; stop.
- **B3 ship gate**: the $0 baseline (cursor oracle + menu-derived
  deterministic suggestions) must LOSE on the identical gate; the P6
  dead-weight criterion applies (uptake ≈ 0 with no outcome delta ⇒
  the section dies); frontier with/without arm included.
