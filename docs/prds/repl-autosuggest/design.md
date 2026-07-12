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
- **Function creation = delegation, not generation (owner,
  2026-07-12).** KT5 carries a small defn ARM (test tranche only —
  confirm the envelope prediction cheaply; if it fails while
  plan/transact/register pass, DROP the kind, don't train against
  it). The suggestion for "this situation needs a new function" is a
  `(seon.ai/gen-fn {::intent …})` CALL — assembly of intent words
  already present in the plan step/guidance, squarely inside the copy
  envelope. gen-fn does the heavy lifting (below).

## The division of labor (owner, 2026-07-12 — the lane's thesis)

**Planner (frontier): intent + decomposition, in words. The tiny
model: words → mechanical REPL forms** (plan bookkeeping, transacts,
schema registrations, fn calls). **gen-fn/worker: the
generation-from-knowledge parts** (fn bodies, query joins), outsourced
on demand. The little model drives the REPL; nothing asks it to think.

Data modeling sits squarely in the tiny model's lane (`register!` ~70%
copy): the malli type vocabulary is CLOSED (constrained decoding
clamps most of the form), neighboring registrations in context are the
ideal ingredient, and the shared-shapes rule makes the correct answer
a reference-copy (`:seon.db/ref`, not an invented inline shape). The
structural judgments (identity/ref/component/optional/cardinality)
arrive as the planner's words; entity/connection COMPOSITION stays
with the planner. KT5's register! arm measures two strata — explicit
guidance ("the id is unique") vs implicit (infer from context) — an
implicit-stratum failure is a planner-prompt specificity finding, not
a model defect.

## `seon.ai/gen-fn` — words into a function (owner, 2026-07-12)

Agent-facing, valuable independent of the suggestion model: intent
words in → a specced `defn` + test out, **eval-proven through the
normal tee** — the same path as any agent-defined fn (instrumentation,
`:seon.fn` program-graph persistence, the publish gate). Zero new
mechanisms on the output side; errors as envelopes.

- **Job-owned special context** (the web `search-model` precedent —
  NOT the agent's turn context, which stays frozen): retrieved similar
  specced fns from the program graph as real examples (deterministic
  selection + existing `seon.embed` top-up), the relevant schema
  registrations, the docstring/spec conventions. Code over prose.
- **Role-routed model**: a `:seon.config/gen-fn` config section (same
  shape as `:seon.config/suggest`). Measured local candidate: the
  diffusion worker (domain demo 1.00 guided vs 0.00 free on
  PRD→schemas→functions) — local + oracle-proven; frontier is the
  config swap.

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

## The context holds the ingredients (owner, 2026-07-12)

The 26M no-FFN model is an assembly machine — it pieces the target
together from what the encoder shows it, it does not remember. Three
standing rules:

- **Ingredients-coverage gate (mechanical)**: for every training pair,
  the target's identifiers (fn symbols, `::` keywords, plan ids,
  entity names) must appear in the context+cards. Computed per pair at
  export/data-build time; pairs below threshold are not trained on —
  they are DIAGNOSED.
- **Coverage misses are context-gap REPORTS, never unilateral fixes.**
  The minimal context profile was hard-won; a single bad line can
  poison the agent. NO change to context generation — block content,
  wording, new sections — lands without an explicit owner clearance.
  The profile mechanism itself is selection + caps ONLY; it never
  rewrites what a block says. Gap reports go to the owner with the
  evidence (which turns, which missing ingredients, which target
  forms).
- **Code over prose.** Cards and suggestions surface GOOD CODE from
  this repo — real specced fns, docstring line-1 cards, real schema
  registrations — not instructional text. Code with tests is
  refinable; context that is words is not.
- **The model is a lint for agent-legibility (owner, 2026-07-12).** A
  frontier model papers over bad names and vague docstrings with its
  own knowledge; the 26M assembly machine cannot — so suggestion
  failures ATTRIBUTE to ingredients: the card, the name, the
  docstring line-1, the schema keys involved. The trainer/eval
  reports a per-fn/per-schema assembly-failure ranking (the
  worst-ingredients leaderboard, derived). The fix loop is better
  tools, better names, better docstrings, better schemas — ordinary
  tested code changes that flow into the next render by construction
  (docstrings render, cards derive from the program graph) — never
  context prose. Performance shifts by refining the repo.

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
- **A model output is a runtime artifact, not a derivation** — and
  **capture is dial-gated (owner ruling, 2026-07-12): the system must
  not generate storage nothing will read.** ONE capture dial
  (config→DB) gates ALL turn-capture blobs — prompt, reply, AND
  suggestion. Dev/acme (mining + measurement): on. Prod: off.
  `rendered-as-of` stays always-on (one int datom; the regenerability
  anchor). Capture ON ⇒ the pre-render step transacts the suggestion
  as a turn-scoped datom + tiny blob (~100-400 tokens,
  content-addressed) — the `:suggest` block is pure over that row, and
  mined turns carry contamination stamps + (suggested, actual)
  correction pairs for retraining. Capture OFF ⇒ the same pre-render
  call rides the volatile tier (`globalThis`, the sanctioned third
  tier) for the current turn's render only; the block reads
  row-else-stash, absent both ⇒ renders nothing. Prod turns are
  therefore not minable — consistent: mining/measurement is a
  dev/acme activity by construction.
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
- **KT2b legibility lint via JSON translation** (hours, stock
  checkpoint, owner-endorsed 2026-07-12): a TEMPORARY translation
  layer maps the fn index (`:seon.fn` + registered schemas) to
  needle's native JSON tool format; hand-curated natural situations +
  distractor menus, constrained decoding, needle's own F1 scoring.
  Output = the per-fn legibility leaderboard v0 (which names/
  docstrings/schemas a 26M can act on). Signal if name-selection ≫
  chance (>~50% on 8-distractor menus); <~30% ⇒ weak zero-shot signal
  (informs but doesn't kill — the finetune is the real test; the
  LEADERBOARD is valuable either way).
- **KT2.5 ingredients coverage** ($0, mechanical, on A1's exported
  rows): per-turn fraction of target identifiers present in
  context+cards. Median <~80% ⇒ the profile lacks ingredients — file
  the context-gap report and STOP for owner review before any
  training; the fix is context generation (owner-gated), not model
  capacity.
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
