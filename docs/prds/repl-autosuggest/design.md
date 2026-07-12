---
type: prd
status: draft
tags: [prd, agent]
---

# REPL autosuggest — a tiny form-prediction model for every turn

A 26M-param encoder-decoder (needle / "Simple Attention Network",
`reference-code/needle/`) finetuned to predict the agent's next **REPL
forms** from a compact projection of the turn state. General
autocomplete for the whole REPL surface — data modeling
(`schema/register!`), querying (`db/query`), transacting
(`db/transact!`), writing functions (`defn`), plan work (`my.plan/*`),
all of it. The suggestion renders into context right before the prompt
(autosuggest, not autonomy): the frontier model sees the suggested
forms and can accept, modify, or ignore them. The tiny model is not
asked to think — it learns the MECHANICAL mapping from situation to
the forms a smart model writes in that situation.

## Why needle fits — the structural mapping

Needle's task shape is `[query <tools> tool-defs] → tool-call JSON`.
Ours is the same shape with each slot re-based:

| needle slot | ours |
|---|---|
| query | the situation projection (turn state, ≤~700 BPE) |
| `<tools>` + tool defs | top-k retrieved fn/schema CARDS from the program graph |
| answers (JSON calls) | multi-form Clojure (the REPL grammar) |

- Attention-only encoder-decoder (no FFN), d=512, 12 enc / 8 dec
  layers, GQA + RoPE, ZCRMSNorm + gated residuals, 8192 BPE
  (byte-fallback). Distilled from Gemini; finetunes locally.
- The CLIP-style **contrastive head** is the retrieval half: encode the
  situation and every candidate card into one space, take top-k into
  the encoder. Mined turns supply its training pairs for free
  (situation ↔ the fns the smart model actually called that turn).
- Trie-based **constrained decoding** (`needle/model/constrained.py`)
  ports to the Clojure grammar: clamp symbol position to the known fn
  surface (the program graph's `:seon.fn` rows), keyword position to
  registered schema keys, plan-id position to live ids. Values stay
  free, same as needle's argument values.
- Trained envelope: **1024 encoder / 512 decoder BPE tokens** — the
  binding constraint. The model can never see the full prompt; it sees
  a projection plus retrieved cards. RoPE has no hard cap but stay in
  the envelope.

## The byte-exact requirement (owner, 2026-07-12)

Training context MUST match inference context byte-for-byte, and MUST
be regenerable from a database point in time. Therefore:

- **One projection function** (`seon.tune/projection`, Track A) renders
  the encoder input. Pure over a db VALUE — at inference the live db,
  at export `(db/as-of conn rendered-as-of)` per historical turn. Same
  function, same bytes, by construction. Retrieval-selected cards are
  part of the rendered output (the card render is itself derived from
  the db, one mechanism with the `:namespaces` compact cards).
- The projection is NOT the prompt blob. Prompt/reply blobs
  (`:seon.agent.turn/prompt-blob` / `reply-blob` — content-addressed
  `my.blob` files on disk; datoms hold only hash/token projections)
  remain debug ground truth and a validation oracle, never the
  training input.
- If projection code changes, exported datasets are STALE — never
  patch them; re-export (derive-don't-store applied to training
  data). Each JSONL row stamps a projection version (git sha).

## The projection (situation slot)

Derived bands, general across all work kinds (final shape is Track A's
to settle; contract, not prose):

1. Current position — `current-ns`, the `▶` active plan step path +
   root goal (the windowed bands of `my.plan.internal/plan-body`,
   reused not re-derived).
2. Live warnings — the `:warnings` derivation, capped.
3. Recent tail — last turn's eval sources + result heads, heavily
   capped (this is what "what was I doing" looks like at 1024 tokens).
4. Pending inbound guidance — most recent unaddressed message text
   (frontier plan markdown, owner instruction), capped.

## The target (decoder output, ≤512 BPE)

Multi-form Clojure exactly as the pod parses it (`parse-forms`), any
domain: `schema/register!`, `db/query`, `db/transact!`, `defn`,
`my.plan/*`, `require`, … Plan authoring stays the flagship case —
markdown heredoc inside `(my.plan/reconcile! {::markdown …})` — but is
one case, not the scope.

Training pair for a mined turn: (projection at `rendered-as-of` +
retrieved cards) → the turn's forms from `:seon.agent.turn/evals`
rows **filtered to `:seon.eval/ok? true`**, in order, truncated to the
decoder budget. Failed forms excluded for free — the db already knows.

## Data sources

1. **Mined turns** — every turn with ok eval rows across the `acme`
   and `default` stores via `seon.agent.ctx/agent-turns`. No domain
   filter: querying, transacting, schema work, defns, plans all land
   in the same JSONL. Curation datoms (`:seon.tune/rating`,
   `:seon.tune/tag`) mark gold sessions (genuine judgments — stored,
   with provenance); training weights gold higher, excludes flagged.
2. **Gold exemplars** — orchestrator-authored: realistic seon
   situations mapped to exemplary form sequences, per domain (a
   schema-design set, a query set, a transact set, a defn set, a
   layered-plan set with `::expect`/`::needs`), agy
   paraphrase-augmented. Needle's guidance: ≥120 per "function"
   analog — here per form-kind — varied phrasing.

Format: JSONL —
`{"context": <projection text>, "cards": [<card texts>], "target":
<forms text>, "meta": {turn-id, agent, basis-t, store,
projection-sha}}`. This overwrites needle's `query/tools/answers`
schema; `src-needle` owns its dataset pipeline. Tokenizer kept
(byte-fallback covers Clojure); retrain only if measured token
efficiency blows the 1024 budget.

## Plans shaped for subagent dispatch (Track A, wanted regardless)

Layered plans record so each subtree is a dispatchable piece
(`my.plan/subtree`-style document read per subagent, depth = the
unit); markdown → `reconcile!` stays the canonical authoring act;
context teaching aligns so smart models emit it mechanically. Sibling
order is created-at + `::needs`; add an ordinal only if dispatch
proves to need it.

## Serving

- `src-needle/` local server on the `src-diffusion/server.py` wire
  pattern (own port, `bin/seon`-registered, idle-unload).
- A derived `:suggest` context section renders the suggested forms
  immediately before the prompt, EVERY turn. Fail-soft: server down /
  empty suggestion ⇒ section vanishes (reactive context — nothing
  stored, nothing to clear).
- MLX, not CPU JAX: inference and finetune both (M-series; tok/s
  reported, always).

## Measurement (three-surfaces rule)

- Trainer-internal val metrics (loss, parse rate) live with the
  trainer in `src-needle`.
- The gate for a suggestion: **parses ∧ evals clean against the
  turn's as-of world** ∧ matches the smart model's actual forms
  (exact or semantic-diff), reported per form-kind — a suggestion
  channel is only as good as its weakest domain.
- Agent-level "does autosuggest help" = a task/scorer INSIDE
  `src-inspect-ai`, never a bespoke drive script.
