---
type: research
status: active
tags: [research, agent]
---

# Diffusion typeahead — hole-filling + candidate scoring (first light)

**TL;DR:** The verified-canvas primitives already support editor-style
typeahead: clamp a code skeleton, diffuse the slots. Measured on the local
MLX worker (26B-A4B 8-bit, M5): **hole fills in 0.8–4s**, **ranked
candidate menus in 0.5–0.7s (3 forwards)**, **encoder prefill ~5ms for 4k
tokens** — context rotation behind the canvas is effectively free, so
every fill can carry a fresh DB-derived context render. Semantic intent
lands reliably; the failure mode is hole-boundary syntax (suffix echo,
off-by-one parens) — exactly the class the existing oracle
repair/scramble loop fixes. Candidate scoring is the standout: rank
DB-legal values for a slot (enum values, verb names) from ~3 forwards —
a completion menu that cannot hallucinate because the DB supplies the
candidate set and the model only ranks.

Experiment scripts (session scratchpad, reproduce-by-rewrite):
`dg_typeahead{,2,3}.py`, `dg_prefill.py` — built directly on
`seon_diffusion.model/generate` internals (`_accept`, `_entropy`, a
hand-rolled canvas with clamp/free/pad-tail segments).

## What was measured (raw)

Model: `mlx-community/diffusiongemma-26B-A4B-it-8bit`, warm process,
`entropy_bound=0.5`, `max_steps=24`. Load from mmap: **0.6s**.

### A — schema arg-fill (call-site template)

Prompt carried the fn contract (`todo/add!` with `:my.plan/title` string,
`:my.plan/status` enum). Canvas:
`(todo/add! {:my.plan/title "` HOLE(8) `" :my.plan/status ` HOLE(3) `})`.

- 3/3 seeds filled the title semantically right: `review the quarterly
  report` — plus a **suffix echo** (`" :my.` regenerated inside the
  hole). Status hole degraded to EOS/newline junk while hole 1 was broken.
- 0.8–3.5s per fill. EOS-ban at holes and PAD-allowed-in-hole did NOT fix
  the echo — the model isn't FIM-trained, it regenerates the transition.
  Fix is oracle-side and mechanical: overlap-trim (hole tail vs following
  clamp) + hole-size search (fills are cheap enough to try n, n±4).

### B — body fill

`(defn sum-of-squares [xs]\n  ` HOLE `)` → 3/3 seeds:
`(reduce + (map (fn [x] (* x x)) xs) 0))` — right shape, misplaced init
arg + one extra paren. Classic provable near-miss (the repair loop's
bread and butter). 2.3–4.0s.

### C — infix hole (AR-impossible)

Text clamped on BOTH sides of two holes:
`(->> (range 20) (filter ` HOLE `) (map ` HOLE `))`. Second hole
consistently `(* % 2)` (needs `#(…)` — near-miss), first hole junk.
1.5–1.9s. Bidirectional infill works at all — an AR model structurally
cannot do this.

### S — candidate scoring (the ranked menu)

Clamp the full form with the slot as a hole sized to the longest
candidate; 3 settle forwards; score each candidate's token ids under the
final logits (mean logprob at slot positions).

- Enum slot `:my.plan/status` ← `:open` **−9.9** · `:done` −13.9 ·
  `:in-progress` −14.3 · `:closed` −15.9. Correct, **0.52s**.
- Intent "what plan items are still open?" over verb slot: `db/query`
  **−2.4** · `db/transact!` −8.7 · `todo/add!` −10.4 · `println` −10.5.
  Correct with wide separation, **0.72s**.
- "Persist a fact" case ranked `db/query` over `db/transact!` — but the
  clamped suffix wasn't a well-formed transact! call, so the test was
  confounded; redo with schema-true templates.

### Prefill (context rotation cost)

256 tok → 2.4ms · 1024 → 2.6ms · 4096 → **4.9ms** (~840k tok/s).
(Timing forces layer-0 K only, same as `generate.py` — may understate;
end-to-end fill times above are honest wall-clock.) Conclusion: swapping
a whole fresh reactive-context render behind the canvas per interaction
is free relative to a single decode forward (~114ms).

## Implications

1. **Templates from schemas.** Every specced fn's Malli schema mechanically
   derives a canvas template: name + arg-map keys clamped, values free.
   The DB program graph IS the typeahead database.
2. **Menus by scoring, not generation.** For enum/ref/verb slots the DB
   enumerates legal values; the model ranks in ~0.5s. Hallucination is
   impossible by construction.
3. **Clamps are guarantees.** Unlike prompt instructions, a clamped
   skeleton cannot be altered — "edit only this arg" is enforced, not
   requested.
4. **The wire gap:** worker speaks only `generate`/`guided`. Needs
   `mode=fill` (segments in, hole texts out, oracle-trimmed) and
   `mode=rank` (segments + candidates in, ranked list out).
5. **Boundary artifacts are the whole remaining problem** — and they're
   oracle-shaped (trim, size-search, repair), not model-shaped.

## Round 4 — the selector-glyph protocol (measured same day)

Enclosed-number glyphs ①–⑩ are **single tokens** in the Gemma vocab
(so are ⤵ and ⋯). Test: menu rendered in the encoder context ("output a
glyph to select, or type any Clojure — both work"), canvas fully free,
10 tokens. Script: `dg_selector.py` (session scratchpad).

- "add a todo: buy milk" → model **emitted ①** (2/2 seeds); cursor
  posterior p(①) ≈ 1.0 (logprob −0.0) vs −10 for the rest.
- "how many plan items are open?" → model IGNORED the menu and free-typed
  `(db/query …` — and the cursor glyph posterior still ranked ② top
  (−8.1 vs −12.7). Free-typing never blocked; intent still readable.
- "record deploy finished" → free-typed `(db/transact! [{:event …`;
  posterior ranked ③ top. Correct again.
- Off-menu task ("sum 1..100") → free-typed `(reduce + (range 1 10…`;
  ALL glyph posteriors collapsed to −22…−28 — a calibrated
  **none-of-the-above** signal.

So: selection works by emission when natural, the posterior is a free
calibrated intent classifier at the cursor (same forwards, no extra
cost), and ignoring the menu degrades gracefully to plain generation.
Hygiene note: one seed leaked a `<|channel>thought` scaffold token into
free space — ban special/channel tokens at free positions (same one-line
logit mask as the EOS ban).

## Round 5 — planning-phase protocol (measured same day)

Status glyphs ☐ ☑ ☒ ▶ ✓ ✗ ⏳ are all **single tokens**. Script: `dg_plan.py`.

- **Plan production:** prompted for a plain-language plan as `; ☐ ①` comment
  lines → perfect format compliance, 2/2 seeds, coherent 3-step plans
  (~1.4–4.2s). (Content showed atom-based instincts — a context problem the
  real DB render fixes, not a protocol problem.)
- **▶ current-step probe works:** with step ① visibly committed, posterior
  after a clamped `▶` ranked ② (the true next step) at −10.3 vs −24/−26.
- **☑ done-probe does NOT:** the model marked ② done when ① was the
  completed step. Done-ness must be DERIVED (eval'd forms matched against
  plan items — derive-don't-store applies to the model's self-report too),
  not asked. ▶ = ask the model; ☑ = compute it.
- **Position bias confirmed** (per the ACI research warning): rotating the
  menu, the correct item still wins but first-position inflation is large
  (top margin −0.0 vs −6.4 after rotation; non-first items compress).
  Glyph posteriors need a null-intent calibration (measure with a
  content-free prompt, subtract) before thresholding.

## Round 6 — logit-readout inventory (measured same day)

Script: `dg_logits.py`. What the canvas logits give us per settle pass:

| Signal | Measured | Use |
|---|---|---|
| Per-hole entropy (mean/worst) | determined slot 0.03–0.57 nats vs underdetermined 1.3–3.0 | "which slot needs help" heat-map; route candidates/docs to the uncertain hole; gate auto-accept on WORST token (per ACI research: mean dilutes) |
| EOS logprob at first tail position | task-complete −2.8 vs more-work-needed −7.0 (argmax `(`) | done-ness meter (ρ-EOS from the infill survey, independently reproduced) |
| Glyph posterior at cursor | rounds 4–5 | menu intent, current-step, none-of-the-above |

## Round 7 — sizing battery: ρ-EOS, dead-space glyph, orientation line

Scripts: `dg_size.py`, `dg_size_a.py`, `dg_size_c.py`.

- **ρ-EOS: NEGATIVE on DiffusionGemma.** Stop-token (EOS+pad) posterior
  mass inside an oversized hole never approaches a usable absolute
  threshold (peaks ≈−8 logprob), and the relative-argmax read looked
  right once (max exactly at the true boundary, 3/3 seeds) but did NOT
  generalize — with other hole sizes/prompts the argmax drifts to the
  hole end. Do not build on it; sizing stays CAL-where-it-works +
  longest-candidate snapping + overlap-trim.
- **Teachable dead-space glyph (`·`): weak.** 1/2 seeds used it (after
  junk), doesn't prevent echo. Consistent with the PAD result: untrained
  self-report mechanisms underperform mechanical derivation. Skip.
- **Orientation line: STRONG.** Telling the model where it is —
  "you are filling the `:my.plan/status` slot of `todo/add!`; legal
  values: :open :done" — took slot-value correctness **0/3 → 3/3**
  (bare arm hallucinated `to-do`/`in-progress`); the only residue was
  suffix-echo-in-slack, which overlap-trim already strips. ADOPT: the
  driver derives this line from `op:"cursor"` slot facts and clamps it
  as a hint comment on template EXPAND. (Also the honest resolution of
  the earlier "0/3 both arms" read — the harness's correctness check
  required a colon the model legitimately omits mid-canvas; zero-scores
  rule vindicated again.)

## Round 8 — two cursor regimes + the context cliff (measured same day)

Scripts: `dg_modes2.py` (v1 mis-scored `new_draft` instead of
`locked` — zero-scores rule, third strike). Through the REAL driver.

- **Frontier regime (single cursor) needed a protocol fix, now 6/6.**
  Hard-clamping a draft that ends MID-SYMBOL pins the typo: the model
  completed `(todo/ad` → the undeclared `todo/ad!` because it cannot
  insert the missing char into a clamp. Editor typeahead REPLACES the
  partial word — so does the canvas now: `split_partial_symbol` backs
  the clamp off to the symbol start; the partial rides the render as
  the candidates line. Measured after the fix: `(todo/ad`→`todo/add!`
  3/3, `(db/qu`→`db/query` 3/3, full correct forms.
- **Template regime (multi-position): correct 3/3** — two seeds
  free-typed the full correct form (`done`, 0.5–0.8s), one landed a
  near-form for the next round. Per-hole settle rounds shipped: settled
  holes clamp, only unsettled re-noise; overflow-truncated holes report
  honestly instead of crashing (latent bug found by the stub canvas).
- **The context cliff (the P3b degeneration, quantified):** frontier
  task correct 2/2 @ 1.7k tok (1.3s/step) and 2/2 @ 7.7k (4.5s) —
  then 0/2 @ 15.7k (junk, 23s) and 0/2 @ 31.7k (stuck, 67s). The
  swap-in constraint is hard: **the typeahead render must stay ≤~8k
  tokens** on this model. The 36k full agent render is out of range —
  the provider needs a slim block loadout (the ctx-lane minimal-context
  work is exactly the required profile), not a bigger canvas.

## Round 9 — the "8k context cliff" ROOT-CAUSED: our port's encoder, not the model

The owner challenged the round-8 "model can't handle >8k" claim. He was
right. Scripts: `dg_needle.py`, `dg_bisect.py`, `dg_transplant.py`,
`dg_kdiff.py`, `vlm_needle.py`.

The elimination chain (each step measured):

1. Config: `max_position_embeddings=262144`; official card rates 256k
   and reports MRCR retrieval AT 128k. Not a capacity limit.
2. Needle-in-haystack through OUR port: perfect at ≤8k (even 7.6k-deep
   needles — long-range attention works), total collapse at ≥10k at
   EVERY depth incl. 800 tokens from the question. Threshold ≈8–10k.
3. Eliminated by direct check: RoPE formula (matches reference
   line-for-line), rope_scaling (none exists), raw MLX sdpa kernel
   (exact at all lengths, masked and unmasked), masks (reference
   semantics match), clip buffers (unused), cache numerics (clean),
   logit softcapping (applied). Per-layer stats: diffuse drift, no
   single broken layer.
4. **mlx_vlm 0.6.4 ships DiffusionGemma** (post-dating our port). With
   the SAME 8-bit weights it retrieves the needle at 10k AND 16k in
   7–8s total. The model and the quantization are both fine.
5. **Transplant test: THEIR 10k prefill cache + OUR decoder = perfect
   retrieval.** Our decoder is correct; the defect is in OUR ENCODER
   at length (exact mechanism unidentified — layer-0 attention output
   diverges from theirs uniformly per-position while its K matches;
   composition-confounded comparison, archaeology stopped as moot).
6. Their prefill is also ~4× faster (3.4s for 10k tokens, chunked).

**Decision: adopt mlx_vlm's model layer under our verified-canvas
control** (their `diffusion_prefill_cache` / `diffusion_update_cache` /
`diffusion_decoder_logits` API exposes exactly the seams control.py and
cursor.py need). Our from-scratch port predates upstream support; the
IP worth keeping is the oracle loop + driver FSM + glyph protocol, not
the transformer forward. The round-8 "≤8k render budget" is VOID as a
model constraint — re-measure the real context/latency curve after
adoption (a slim render likely still wins on latency, but as a knob,
not a wall). Note: our port's bf16 loading (dispatch shims added this
session) produces garbage even short — unvalidated path, moot after
adoption; shims kept for the 8-bit-path dispatch cleanliness.

## Next steps (proposed)

1. `fill_guided` in `control.py` style: fill → oracle parse of the whole
   template → overlap-trim → per-hole rescramble; reuse repair/hints.
2. `mode=fill` / `mode=rank` in `worker.py` (same in-band error contract).
3. Seon side: `schema→segments` derivation fn + an inspector "ghost form"
   tile (ranked fills for the agent's next verb).
4. Bench task inside `src-inspect-ai` for fill/rank accuracy (three-surfaces
   rule — no new harness).
