---
type: research
status: draft
tags: [research, agent, web]
---

# Eval-renoise experiment plan — Capability #2 (whole-form refinement + eval feedback)

> Ready-to-run plan + worker stub so the MOMENT DiffusionGemma deploys we can run
> the most **Seon-native** dynamic-context experiment: generate a Clojure form,
> eval it in Seon's SCI cage, and on a `:seon/error` **re-noise ONLY the failing
> span** and re-denoise it IN PLACE — the move autoregression structurally cannot
> make. Companion to [[index]] (the 4 capabilities + the `accept_canvas` seam),
> [[infill-experiment-plan-2026-06-28]] (Capability #1 — share its introspect
> findings, do NOT re-derive them), and [[parser-as-generation-oracle-2026-06-28]]
> (the parser `:span`/`:error-kind` oracle this rests on).

## TL;DR

- **The win:** a diffusion canvas is **revisable in place**. Generate a form onto
  the 256-token canvas; Seon evals it; on `:seon/error` we set ONLY the failing
  canvas positions back to MASK and re-run the denoiser, **clamping every other
  (correct) position**. The model re-decides just the broken span, conditioned
  **bidirectionally** on the whole surrounding form. AR has no in-place edit — a
  wrong token forces regenerating everything after it (and it cannot see the
  already-written tail it must stay consistent with).
- **This is a ROUND-TRIP architecture, not a sampler trick.** GPU worker denoises
  → returns `{text, canvas_tokens, offset_map}` over HTTP → **Seon** evals in the
  SCI cage + locates the failing span (the oracle Seon already owns) → tells the
  GPU `{canvas_tokens, renoise_positions}` → GPU re-masks those positions and
  re-denoises in place → returns the revised canvas. Loop until clean-eval or a
  step cap. The worker is a **stateless denoiser**; Seon owns the eval oracle and
  the span→position mapping. (Stateless suits Flash scale-to-zero — no
  cross-request canvas state on the GPU.)
- **The linchpin is the char-span → canvas-token-position map.** Seon's oracle
  speaks **char offsets** (`parse-forms` `:span [start end]`; a runtime
  symbol-resolution error names a symbol we locate by substring). The canvas is
  **token positions**. The bridge is the tokenizer's per-token char ranges
  (`offset_map`): re-mask exactly the canvas positions whose char range overlaps
  the error span. This is the same masked-vs-char concern flagged in
  [[parser-as-generation-oracle-2026-06-28]], now made concrete as a token↔char
  index.
- **The mechanism is grounded** (bd3lms source + the Transformer Lab paper + the
  parser oracle measurements). The **honest gap** is the same family J hit: the
  public `generate()` seeds an all-MASK canvas; how to **re-seed a partially-good
  canvas with specific positions re-masked** (V1) and how the decode-side
  **offset map** reconstructs (V2) are confirm-on-deploy. The worker ships a
  `mode="introspect"` for the capability-#2-specific unknowns and **withholds the
  guessed generate call** (reports `STUB`), exactly like J.

## Why this is the most Seon-native capability

Seon already has the two oracles this needs, in production, with **zero model
calls**:

1. **Syntactic — `seon.repl.internal/parse-forms`.** A `:read` failure carries
   `:span [start end]` (ABSOLUTE char offsets of the bad span in the reply text)
   and `:error-kind` (`:eof` / `:unmatched-delimiter` / `:odd-map` /
   `:bad-metadata` / `:invalid-token` / `:read`). The parser oracle study measured
   92.7% of injected errors caught instantly, split into SAFE (mechanically
   repairable — `seon.repair`/parinfer, no model and no re-noise) vs FLAG (the
   model must re-decide). **`:span` is literally the canvas region to re-mask.**
2. **Semantic — the SCI eval cage.** `seon.eval` runs the form; a failure becomes
   `:seon/error` with a crystal-clear `:seon.error/message` (the deepest real
   message — `render-error-string` in `seon.eval`). An undeclared-var throw names
   the exact offending symbol; Seon locates that symbol's char span by substring.
   The parser-oracle study's "3.2% masked-divergent" class — parses clean, means
   something else — is exactly what only the eval tier catches.

The diffusion model contributes the third thing neither oracle has: **the ability
to act on the located span without regenerating forward.** Parser/eval say WHERE
and WHY; the re-noise loop FIXES it in place. That is the buzzsaw: a tight
eval→re-noise collar with at most one canvas region in flight per round.

This also lands on the **`accept_canvas` seam already enumerated in [[index]]**:
re-noise + clamp is one more override of the same per-step commit decision that
Capability #1 (infill) overrides — `current_canvas` positions forced to MASK for
the failing span, forced to their committed ids everywhere else.

## The round-trip architecture (GPU worker ↔ Seon eval cage over HTTP)

```
            ┌──────────────────────── Seon pod (SCI cage + oracles) ───────────────────────┐
            │                                                                               │
  (1) generate_canvas {prompt}                                                              │
   ─────────────────────────────►  GPU worker (A100, DiffusionGemma)                        │
            │                         denoise all-MASK 256-canvas                            │
            │                       ◄─── {text, canvas_tokens[256], offset_map}              │
            │                                                                                │
            │   (2) eval text in SCI cage  ──►  :seon/error ?                                │
            │         ├─ no  → DONE (clean form)                                             │
            │         └─ yes → locate failing span:                                          │
            │                  · parse error  → parse-forms :span [s e] (char offsets)       │
            │                  · runtime error → name the symbol, substring → [s e]          │
            │                                                                                │
            │   (3) map [s e] → renoise_positions  via offset_map  (the linchpin)            │
            │                                                                                │
  (4) renoise {canvas_tokens, renoise_positions}                                            │
   ─────────────────────────────►  GPU worker                                               │
            │                         current_canvas[renoise_positions] = MASK               │
            │                         clamp all OTHER positions to their ids                 │
            │                         re-denoise in place (<=48 steps)                       │
            │                       ◄─── {text, canvas_tokens, offset_map}                   │
            │                                                                                │
            │   (5) goto (2)  until clean-eval OR round-cap (e.g. 3)                         │
            └────────────────────────────────────────────────────────────────────────────┘
```

- **Transport is JSON over HTTPS**, identical to the proven generate path
  (`gpu_worker.py`): out `{"input":{mode,…}}` → RunPod `/run` → poll `/status`.
  **No tensors cross the wire** — `canvas_tokens` is a plain `list[int]` (≤256
  ids), `offset_map` is a list of `[pos, char_start, char_end]`, both tiny.
- **The worker is stateless across the round-trip.** Seon holds `canvas_tokens` +
  `offset_map` between calls and passes them back. This matches Flash
  scale-to-zero (no GPU-resident per-conversation canvas) and makes the loop
  resumable/inspectable from Seon's side.
- **Who maps span→positions: Seon, using the worker-returned `offset_map`.** Keeps
  the worker a dumb denoiser and the oracle logic in one place (Seon, where the
  parser + eval already live). The worker re-masks exactly the positions it is
  told.

## The re-noise mechanism (clamp the good, re-mask the failing span, re-denoise)

Grounded in bd3lms (`reference-code/bd3lms/diffusion.py`) and the Transformer Lab
`accept_canvas` reverse-engineering (arXiv:2606.14620):

1. **The masking primitive** (bd3lms `q_xt`, ~L519):
   `xt = torch.where(move_indices, self.mask_index, x)` — a position is "noised"
   by overwriting it with `mask_index`. **Re-noise is a TARGETED `move_indices`:**
   `True` for exactly `renoise_positions`, `False` everywhere else. So
   `canvas[renoise_positions] = mask_index`; all other positions keep their
   committed token ids.
2. **Clamp the good positions.** The whole point is in-place revision: every
   non-re-noised position must survive every subsequent denoise step unchanged.
   bd3lms already has this exact "don't overwrite committed" pattern in
   `_ddpm_caching_update` (~L592): `copy_flag = (x != mask_index)` →
   `x_block = copy_flag * x + (1 - copy_flag) * x_block`. Our override forces the
   clamped positions back to their ids every `accept_canvas` step (same shape as
   the infill clamp in Capability #1), so the denoiser only re-decides the holes.
3. **Re-denoise in place.** Re-run the denoise loop on the re-masked canvas. Only
   `renoise_positions` are MASK, so only they get re-decided; commits are NOT
   frozen in DiffusionGemma (the paper measured ~7.5 re-mask events/gen — in-place
   revision is native), and bidirectional attention conditions the re-decided span
   on the entire surrounding (now-correct) form, including tokens AFTER the span.
4. **Why it beats AR.** For `(/ (reduce + xs) (count ys))` with `ys` undefined: AR
   would regenerate from the error point forward and is blind to the already-good
   prefix/suffix it must stay consistent with. Diffusion re-masks the **single**
   `ys` span and re-decides it seeing `[xs]` in the param vector and `xs` already
   used as the dividend — so it commits `xs`. One region, one round, no forward
   regeneration.

### The control seam (shared with Capability #1)

`EntropyBoundSampler.accept_canvas(current_canvas, denoiser_canvas, logits,
cur_step)`, logits `[1, 256, 262144]`, open `transformers` 5.11.0, model class
`DiffusionGemmaForBlockDiffusion`, model_type `diffusion_gemma`, no
`trust_remote_code`. Capability #2 overrides it to: (a) on the re-noise call, hold
`renoise_positions` at MASK until the model commits them, and (b) clamp all other
positions to their incoming ids every step. **U2/U3 (sampler attach + accept_canvas
return-vs-mutate contract) are RESOLVED BY J's `mode="introspect"`** — this plan
reuses those answers; it does not re-derive them.

## The char-span → canvas-token-position mapping (the linchpin)

The oracle speaks **char offsets**; the canvas is **token positions**. The bridge:

- **On every worker return, build `offset_map`:** for each committed (non-MASK)
  canvas position `p`, the half-open char range `[char_start, char_end)` that
  position's token occupies in the decoded `text`. Built by **cumulative
  per-token decode**: walk the canvas tokens, decode each, append to a running
  string, and record `(p, len_before, len_after)`. (Single-pass decode of the
  whole canvas gives `text`; the per-token walk gives the ranges. The two must
  agree — see V2: piece-wise vs joint decode can differ by merged spaces/BPE
  boundary artifacts; the introspect mode measures the discrepancy.)
- **Map a char span `[s e)` → positions:** `renoise_positions = [p for (p, cs, ce)
  in offset_map if cs < e and ce > s]` (any overlap). For a runtime
  undeclared-symbol error, `[s e)` is the symbol's substring range in `text`; for
  a parse error it is `parse-forms`'s `:span` directly. The same machinery serves
  both tiers.
- **Granularity dial by `:error-kind`** (reusing the parser-oracle taxonomy):
  - `:invalid-token` → re-mask the **token span only** (the FLAG class — the model
    must re-decide a single bad token; also the embedding-lookup hook for
    Capability #3).
  - `:eof` / `:unmatched-delimiter` → these are the **SAFE** class: prefer
    `seon.repair` (parinfer) with **no model round-trip** at all; only fall back to
    re-noising the form's tail if repair fails. (Do not burn a GPU round-trip on a
    missing closer the parser can balance for free.)
  - runtime (`:seon/error`, undeclared var / wrong-arity / bad-fn) → re-mask the
    **named symbol/subform span**; the surrounding form is correct and clamps.
- **Why overlap, not containment:** the parser span and the tokenizer boundaries
  do not align — a symbol may be split across BPE pieces or share a piece with an
  adjacent paren. Overlap re-masks the minimal set of positions that fully cover
  the error span; a one-position over-mask is harmless (the model re-commits it
  identically under the surrounding clamp).

## Capability-#2-specific unknowns — CONFIRM ON FIRST DEPLOY

J's introspect resolves the **shared** seams (U1 canvas-seed kwarg, U2 sampler
attach, U3 accept_canvas return/mutate contract, U4 mask id + 256 layout). This
plan adds three NEW unknowns, resolved by `gpu_worker_renoise.py mode="introspect"`
in one A100 call. **Do not issue a guessed re-denoise before these resolve.**

- **V1 — re-seed a partially-good canvas.** Capability #1 seeds human-typed tokens
  at canvas edges; here we must seed a canvas that is **mostly model-committed
  tokens with a few positions forced back to MASK**, then resume denoising. Is the
  seam the SAME as J's U1 (a `generate()` canvas kwarg accepts arbitrary
  `[ids… MASK… ids…]`), or must re-seeding be driven entirely inside the
  `accept_canvas` override on `cur_step==0` (write `canvas_tokens`, set
  `renoise_positions`→MASK, then clamp)? Introspect checks whether `generate()`
  accepts a full 256-id canvas with embedded MASK ids and a fresh step budget.
- **V2 — decode offset-map fidelity.** Does piece-wise per-token decode reconstruct
  the SAME string (and clean char boundaries) as joint `tokenizer.decode(canvas)`?
  SentencePiece/BPE can render a token differently in isolation (leading-space
  metasymbol `▁`, byte-fallback pieces). Introspect decodes a known canvas both
  ways and reports the per-position char-range table + any mismatch, so the
  mapping uses whichever method is faithful (likely: decode jointly for `text`,
  derive ranges from `tokenizer(text, return_offsets_mapping=True)` re-encode, or
  from the fast tokenizer's `encodings[0].offsets` — introspect confirms which is
  available on this tokenizer).
- **V3 — in-place re-denoise convergence.** When only K of 256 positions are MASK
  and the rest are clamped, does the denoiser (a) actually re-commit the K
  positions under the entropy bound within the step budget, and (b) leave clamped
  positions untouched? Introspect runs a trivial self-test: seed a fully-committed
  canvas, re-mask one interior position, re-denoise, assert all other positions
  unchanged and the masked one re-committed. This validates the clamp before any
  real experiment.

## Worker-mode design (`gpu_worker_renoise.py`)

Standalone `@Endpoint` `diffgemma-renoise` (same A100 / NetworkVolume / env as
`gpu_worker.py`; nothing in the proven generate path is touched). Modes:

| mode | input | output | purpose |
|---|---|---|---|
| `env` | — | import/health info | cheap liveness |
| `introspect` | — | V1–V3 answers (canvas re-seed seam, offset-map fidelity table, in-place convergence self-test) + the offset-map of a known canvas | **the first-deploy oracle for capability #2** |
| `generate_canvas` | `{prompt, max_new_tokens}` | `{text, canvas_tokens, offset_map, steps}` | round-trip leg 1 — first denoise (STUB until U1/U2/U3 confirmed) |
| `renoise` | `{canvas_tokens, renoise_positions}` | `{text, canvas_tokens, offset_map, steps}` | round-trip leg 4 — re-mask + re-denoise in place (STUB until V1 confirmed) |

`generate_canvas`/`renoise` build the canvas layout + the `accept_canvas` override
and report `renoise_status: STUB` **without** issuing a guessed generate — wired to
the real sampler only after introspect (this plan's V1–V3 + J's U2/U3) resolves the
seam. Deliberate: ship the plumbing + the offset-map math (which runs locally and is
testable without the model), not a fabricated result.

The **offset-map construction** and the **span→positions** mapping are pure
functions and ship LIVE (not stubbed) — they need only a tokenizer, so they are
exercised in `introspect` against the real tokenizer the moment it loads.

## The concrete first test case

A runtime (`:seon/error`) case — the most Seon-native, single-token span, requires
the model (parinfer can't fix a semantic error):

```clojure
;; what the canvas denoises to (leg 1) — a wrong divisor symbol:
(defn mean [xs] (/ (reduce + xs) (count ys)))
```

- **Eval in the SCI cage →** `:seon/error`, `:seon.error/message` names the
  undeclared symbol: `"Unable to resolve symbol: ys"` (SCI's undeclared-var throw;
  Seon's `render-error-string` surfaces the deepest real message). This is a
  RUNTIME error — the form **parses clean** (`parse-forms` returns a `:form`
  entry, no `:read`), so only the eval tier catches it. It is precisely the
  "masked-divergent" class the parser-oracle study isolates.
- **Span the oracle flags:** Seon locates the substring `ys` in the form source →
  char offsets, e.g. `[s e)` covering `ys` (the `(count ` and `)` around it are
  correct and excluded). One symbol.
- **Map → canvas positions:** the offset_map yields the 1–2 canvas token positions
  covering `ys` (`ys` is likely a single token; the trailing `)` is a separate
  position and is NOT re-masked).
- **Re-noise + re-denoise:** set those positions to MASK, clamp every other
  position. The denoiser re-decides the divisor span seeing `[xs]` in the params
  and `xs` already used in `(reduce + xs)` (bidirectional — it sees BOTH sides).
- **Expected in-place fix:**

```clojure
(defn mean [xs] (/ (reduce + xs) (count xs)))   ; ys → xs, one region, one round
```

- **Eval again →** clean: `(mean [1 2 3 4]) ;=> 5/2`. Loop terminates.
- **AR contrast (the handicap):** an AR model that emitted `ys` would have to
  regenerate the suffix `(count …))` forward, blind to the fact that `xs` is the
  established seq name in the already-written prefix — it can re-error or diverge.
  Diffusion edits the one symbol with full bidirectional context. Run the same
  prompt through the AR sibling `gemma-4-26B-A4B-it` and a forward-regeneration
  repair to quantify rounds-to-clean: diffusion (1 region re-noised) vs AR
  (regenerate-forward).

### Secondary case (parse tier, exercises `:span` directly)

```clojure
(defn mean [xs] (/ (reduce + xs) (count 3xs)))   ; 3xs is an invalid token
```

`parse-forms` → `:read`, `:error-kind :invalid-token`, `:span` over `3xs`. FLAG
class (the model must re-decide — parinfer can't). Map the span → positions,
re-mask, re-denoise → expected `xs`. This case proves the **parser `:span` →
canvas position** path end-to-end without needing the eval tier, and is the
natural Capability-#3 retrieval trigger (`:invalid-token` = the wrong-name blind
spot, AUROC 0.471, entropy can't self-detect it).

## Run order on first deploy

1. `python gpu_worker_renoise.py introspect` → resolve V1–V3 off the live A100;
   confirm J's U1–U4 carry over. Read the offset-map fidelity table (V2) and the
   in-place convergence self-test (V3).
2. Wire `generate_canvas`/`renoise` to whichever re-seed seam V1/U1 revealed (edit
   the marked CONFIRM-ON-DEPLOY spots).
3. `generate_canvas` the primary prompt → confirm a coherent `(defn mean …)`
   canvas + a clean `offset_map`. (If the first denoise happens to be correct,
   inject the `ys` corruption to exercise the loop deterministically.)
4. Eval in Seon's SCI cage (this leg runs in the pod, not the GPU), get the span,
   map to positions, `renoise` → confirm the divisor span re-commits to `xs` and
   ALL other positions are byte-identical (the clamp held).
5. Eval again → clean. Record rounds-to-clean. Run the secondary `:invalid-token`
   case. Run the AR forward-regeneration contrast.
6. Decision gate: if re-noise does NOT converge faster / cleaner than AR
   forward-regeneration on these single-region errors, the in-place-revision
   premise is weak for code repair — reassess (per the T-ladder gates in
   [[../../agent-fsm/research/diffusion-llm-test-plan-2026-06-27]]).

## Honesty / limits

- The `generate()` re-seed API (V1) and the sampler/accept_canvas contract (U2/U3)
  are **confirm-on-deploy** — the worker reflects rather than guesses and withholds
  the generate call until the seam is known (same discipline as
  [[infill-experiment-plan-2026-06-28]]). No DiffusionGemma output has been produced
  yet (blocked on the custom torch image — see [[index]]).
- The offset-map + span→positions math ships LIVE and is testable against the real
  tokenizer at `introspect` time, independent of the model generate path — so the
  linchpin is provable before the full loop is.
- The runtime test case assumes SCI's undeclared-var message names the symbol
  verbatim and that Seon's substring-locate is unambiguous (a symbol appearing
  twice needs the eval error's position, not just its name — SCI may carry a
  `:line`/`:column` in `:seon.error/data`; if not, re-mask all occurrences and let
  the clamp+context re-commit the correct one — the over-mask is harmless). This
  ambiguity is the one runtime-span subtlety to confirm against real SCI errors.
- Cases are small, single-region, single-form — chosen because that is exactly
  where in-place re-noise is unambiguously cheaper than AR forward-regeneration.
  They are a clean proxy for eval-driven refinement, not a general repair benchmark.
- This plan is to EXECUTE fast once the GPU is live, not a result.
