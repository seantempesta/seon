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

## Next steps (proposed)

1. `fill_guided` in `control.py` style: fill → oracle parse of the whole
   template → overlap-trim → per-hole rescramble; reuse repair/hints.
2. `mode=fill` / `mode=rank` in `worker.py` (same in-band error contract).
3. Seon side: `schema→segments` derivation fn + an inspector "ghost form"
   tile (ranked fills for the agent's next verb).
4. Bench task inside `src-inspect-ai` for fill/rank accuracy (three-surfaces
   rule — no new harness).
