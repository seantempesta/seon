---
type: prd
status: draft
tags: [prd, agent]
---

# Diffusion typeahead — the guided REPL surface (design)

**Goal:** a general-purpose, swap-in generation surface for seon agents.
The agent types Clojure into a live REPL; the system offers — never
imposes — completions, arg templates, plan tracking, and proof. The
diffusion model generates; the DB supplies every candidate; the oracle
proves every commit. Swap-in = the existing `seon.ai` provider seam
(`SEON_AI_PROVIDER` + `SEON_DG_ENDPOINT`) — zero changes to the agent
loop.

Evidence base (all measured on the local MLX worker, 2026-07-10):
[[research/typeahead-hole-filling-2026-07-10]] (rounds 1–6),
[[research/diffusion-infill-survey-2026-07-10]],
[[research/agent-typeahead-aci-2026-07-10]],
[[research/clojure-completion-stack-2026-07-10]].

## The one law: suggestions are strictly optional, forever

Every documented catastrophe in the ACI literature is downstream of
FORCING the structured channel (constraint tax, tool-choice
hallucination, JSON-mode reasoning loss). Our measured protocol already
has the mitigation built in: the model may always type plain Clojure and
every affordance degrades to "just better context". This is a design
invariant, not a tunable — no future knob may make selection mandatory.

## Three time scales (the AR-emulation frame)

1. **Commit axis (AR, form-at-a-time):** eval-proven forms harvest into
   the encoder KV cache and never pay decode cost again (existing
   `control.py` lock path).
2. **Intra-form (diffusion):** the 256-token canvas works the current
   form — holes, templates, free text. Clamps are hard guarantees.
3. **Between commits (reactive):** the encoder context re-renders as a
   function of `(db, committed, draft)` — measured ~5ms for 4k tokens,
   free relative to one 114ms forward. Menus, contracts, and the plan
   ledger live HERE, not on the canvas.

## The driver FSM

The model is stateless; the driver is the machine. State =
`(committed, canvas-plan, active-offer)` — all derivable, all datoms.

| State | Does | Exits |
|---|---|---|
| RENDER | derive encoder context via policy sections (below); re-encode | DENOISE |
| DENOISE | rounds to stability / proof-probe; logit masks active | INTERPRET |
| INTERPRET | total partition of the round output | ↓ |
| EXPAND | glyph seen (token scan) or auto-offer fired → clamp template | RENDER |
| GROW | clean-but-unfinished, or ⤵ → grant free space | DENOISE |
| REPAIR/SCRAMBLE | broken spans (existing) | DENOISE |
| LOCK/HARVEST | eval-proven prefix → encoder (existing) | RENDER |
| PROVE | EOS + caller checks (existing) | DONE / RESTART |

INTERPRET's partition `{glyph-select, text-progress, clean-unfinished,
broken, eos-complete, stuck}` is a **total function** (oracle parse +
single-token glyph scan); anything unmatched falls to the plain-guided
default arm. Nondeterminism lives only inside DENOISE (seedable). Every
transition is a datom → full replay, same as turns.

## The glyph vocabulary (all single tokens, measured)

| Glyphs | Channel | Meaning |
|---|---|---|
| ①–⑩ | model → driver | select menu entry / template offer |
| ⤵ | model → driver | grant more space now (mechanical GROW is the guarantee; this just saves a round) |
| ☐ ▶ ☑ | driver → model (render only) | plan-item status in the plan ledger |
| ▶ + item glyph | model → driver | "I'm working on N" (measured reliable) |

Rules learned from measurement + literature:

- **☑ is never asked.** The done-probe mismarks (round 5); done-ness is
  DERIVED — eval-proven forms matched against plan items. ▶ may be asked
  (measured correct) but is advisory.
- **Glyph posteriors are calibrated before thresholding**: subtract a
  null-intent baseline (position bias measured: first-slot inflation
  −0.0 vs −6.4 margins). Auto-offer fires only when (a) calibrated
  margin clears threshold AND (b) the free region is still noise — never
  override typing.
- **Auto-accept gates use WORST-token confidence** over a span, not
  mean (probability dilution).
- Glyphs are stripped in token space before the oracle ever sees text;
  they exist only between driver and model.

## Logit readouts (the signal inventory, all from the same forwards)

| Signal | Measured | Drives |
|---|---|---|
| Glyph posterior at cursor | ≈1.0 on-menu; collapses −22…−28 off-menu | menu selection, none-of-the-above, current-step |
| Per-hole entropy (mean/worst) | 0.03–0.57 determined vs 1.3–3.0 underdetermined | which slot gets candidates/docs next RENDER; auto-accept gate |
| EOS logprob at tail | −2.8 done vs −7.0 more-work | done-ness meter (ρ-EOS reproduced) |
| First-step hole confidence (CAL, from survey) | +47.7% code-infill Pass@1 in lit | hole-length search: probe K lengths, pick the confidence peak |

## Slot masking (own-the-sampler privileges)

- Special/channel/EOS tokens banned at free positions (one logit mask —
  fixes the measured `<|channel>thought` leak).
- Enum/ref/keyword slots get DINGO-lite masks: legal candidate token
  sets from the DB (registered enum values, live var names). The model
  cannot emit an unregistered attr in a masked slot — hallucination
  impossible by construction where the schema is closed.
- Full CFG machinery: skip (survey verdict) — the oracle loop already
  provides syntax proof at commit granularity.

## Suffix-echo mitigation (the known model weakness)

No published inference-time fix (instruct dLMs are FIM-OOD). Composite
mitigation, all cheap: CAL length probing (kills the slack that invites
the echo) + oracle overlap-trim (kills the residue) + generous-hole +
trim as fallback. Accept imperfect: the oracle loop catches what leaks.

## The planning phase

Plan = `my.plan` items in the DB (the existing todo tree), NOT canvas
text. Flow:

1. First RENDER of a run offers a plan template (plain-language `; ☐ ①`
   lines — measured: perfect format compliance). The model writes the
   plan; the driver parses lines → `todo/add!` datoms. (Or the agent
   calls todo verbs directly — same datoms, no special path.)
2. Every subsequent RENDER derives the plan ledger from the DB: done
   items **dropped from the render** (hermes precedent = our
   derive-don't-store), current item marked ▶, open items ☐.
3. ☑ transitions are computed (eval-proven forms ↔ plan items, plus the
   EOS done-ness meter as a hint); ▶ may come from the model's own
   emission.

Nothing here is a mode: an agent that never plans just never gets the
ledger section (query returns empty → section vanishes).

## The cursor-intelligence oracle (`op:"cursor"`, bin/oracle-server)

Measured composition, ≲5ms/call vs 114ms/forward (stack proven in bb):

1. **edamame repair** — bounded append-loop on
   `:edamame/expected-delimiter` ex-data (sub-ms; parinfer REJECTED —
   indent-mode trusts indentation, JVM impl won't load in bb).
2. **clj-kondo analysis** (2.0ms resident) — `:locals` w/ scope ranges,
   `:var-usages` w/ arity, `:keywords`. Zero locals on unbalanced input,
   so repair is a hard prerequisite (proven).
3. **Compliment context port** (~200 lines; context.clj runs in bb
   unmodified) — `__prefix__` slot-kind (`:map-role :key` etc.).
4. **malli** — `-function-info` on the head symbol's registered schema →
   arg template with typed holes; enum values for masks; `mg/sample`
   for ghost defaults. clojure-lsp's priority ladder (locals ≻ keywords
   ≻ core) cloned for ranking (~40 lines).

Contract: `{draft, cursor} → {slot-kind, locals, candidates (ranked,
typed), template, repaired-text, balance-delta}`.

## Flexibility: everything is policy data, one mechanism

The owner requirement: "a design that allows us flexibility in what we
set up." The answer is the existing block system — **the driver consumes
a RENDER**, and what's configurable is which section fns run, per agent,
via `install!`/`remove!` on ctx blocks. No new config surface:

- **Menu sources** = section fns (recent-verbs-from-eval-log,
  schema-contracts-for-draft-head, plan-ledger, kb-hits). New source =
  new section fn. Per-agent loadout = ctx blocks, already durable,
  already inspectable.
- **Driver policy** = one `:seon.typeahead/policy` map (DB-config row):
  auto-offer margin, worst-token gate, hole-length probe budget, masks
  on/off per slot-kind, planning ledger on/off, glyph page size. All
  read at RENDER time — change a row, next step behaves differently.
- **Model-side knobs** ride the wire payload (steps, entropy-bound,
  seed) — already the pattern for `mode=guided`.

Because every layer degrades to the layer below (masks → menus → plain
guided → free generation), any policy combination is safe; the FSM's
default arm is always plain guided generation.

## Wire modes (worker.py additions)

- `mode=fill` — segments in → hole texts + per-hole worst-confidence +
  trims out.
- `mode=rank` — segments + candidates in → calibrated ranked list out.
- `mode=step` — one full FSM turn: `{committed, draft, context-render,
  policy}` → `{transition, new-draft, locks, glyph?, posteriors}`.
  The driver loop can then live either side of the wire; seon owns
  RENDER (it has the DB), the worker owns DENOISE.

In-band errors (`gen_error`), same contract as today.

## Evaluation (three surfaces, no fourth)

- **bin/test-cljs** — seon-side section fns + policy row plumbing.
- **pytest (src-diffusion)** — stub-model driver tests: every INTERPRET
  arm forced; oracle-op offline proofs (real bb/node oracles).
- **src-inspect-ai** — the swap-in bench: replay corpus from real
  turn-capture blobs across capability rungs; arms = free-guided /
  typeahead / typeahead-with-menus-rendered-but-model-instructed-to-
  ignore (degradation arm). Metrics: form validity, verb-choice
  accuracy, tokens-to-valid-form, wall-clock, **uptake rate**, rounds-
  to-lock. Kill criteria: degradation arm regresses baseline → protocol
  leaks; uptake ≈ 0 with no accuracy gain → dead weight. Plus one live
  observed drive on acme before any claim.

## Phases

1. **P1 oracle** — `op:"cursor"` (edamame + clj-kondo + compliment-port
   + malli), offline proofs. No model dependency; useful standalone.
   **SHIPPED 2026-07-10** (`bin/oracle-server` + `Oracle.cursor()` +
   9 pytest proofs in `src-diffusion/tests/test_cursor.py`; warm
   3.1–4.5 ms/call measured through the pipe client; malli templates
   deferred to the pod side — `template` is `null` in P1).
2. **P2 driver** — `cursor.py` FSM + glyph interception + masks + CAL
   hole probe + wire modes. Stub-model tests. **SHIPPED 2026-07-10**
   (`src-diffusion/src/seon_diffusion/cursor.py` — `CursorDriver` +
   `Policy`; worker modes `fill`/`rank`/`step` stateless per call;
   19 stub-model pytest proofs, suite 53 green). Live smoke on the MLX
   worker: fill exact-form 3.3s (CAL probe 1 fwd/length; closed
   candidate holes sized-and-SNAPPED to the argmax candidate — the
   token mask alone let mask-legal junk repeat), rank calibrated
   `db/query` top 1.1s, step done-in-6-forwards 1.3s / free-typed
   `(reduce + (range 1 101))` 0.7s with the no-menu-fit posterior
   collapse reproduced. Two live-found corrections: the tokenizer
   declares EOS special, so the open-tail special ban must EXCLUDE
   EOS/pad (banning it killed the done-ness meter and invited trailing
   prose); and uncalibrated Φ(L) was MONOTONE for an enum hole (the
   CAL peak reproduced only for open text holes — B(L) length-decay
   calibration not fitted; closed slots use candidate sizing instead).
   Eval-proven locking in `step` needs a caller-supplied session
   (parse-gated by default); P3 wires it.
3. **P3 seon** — menu/plan section fns, `:seon.typeahead/policy` row,
   provider wiring, inspector tile (posterior bars + plan ledger).
   **P3a (section fns + policy row) SHIPPED 2026-07-10**:
   `seon.agent.ctx.menu` owns `:recent-verbs` (eval-log-derived,
   glyph-numbered fn menu — glyph + compact-card arity hint + docstring
   line 1; aliased calls resolve via each eval ns's STORED
   require-edges) and `:plan-ledger` (▶ active / ☐ open steps, done
   dropped from the render), both seeded in
   `seon.config/default-ctx-blocks` at priorities 46/47 (volatile tail,
   between `:plan` and `:relevant-source`), plus the
   `[:seon.typeahead/id "policy"]` row (code defaults in
   `menu/default-policy` — auto-offer-margin / worst-token-gate /
   probe-budget / menu-cap; per-knob DB override read at every render;
   the config→DB migration lane owns moving the defaults later). 4
   behavior tests in `test/seon/agent/ctx/menu_test.cljs`; live-proven
   on acme (both sections in the byte-exact turn prompt blob; an
   agent-transacted `menu-cap 2` row truncated both menus on the very
   next render). Open owner call: the `:plan` vs `:plan-ledger` overlap
   (both render the same open steps today).
   **P3b (provider + inspector tile) SHIPPED 2026-07-10**:
   `seon.ai.typeahead` — the `SEON_AI_PROVIDER=typeahead` step-loop
   provider (OFF by default; endpoint/key config shared with
   `:diffusiongemma`, and a full-URL `SEON_DG_ENDPOINT` local worker
   now needs NO bearer key). One provider call = the mode=step loop
   through the ONE wire path (`dg/complete`; `::mode :step` +
   committed/draft/offers/policy fields added): offers =
   `menu/verb-offers` (glyph-aligned with the rendered `:recent-verbs`
   menu by construction), policy = the P3a row via `policy->wire`
   (worst-token-gate deliberately unmapped — probability vs the
   worker's nats gate), committed/locked + draft threaded per round,
   stop on done / stuck×2 / the new `:seon.typeahead/max-rounds` knob.
   THE POD OWNS EVAL: the reply is the locked forms as plain LLM-reply
   text, eval'd ONCE by the turn pipeline — which is also why the
   mid-loop `;; => result` feedback (control.py's `_result_comment`)
   is NOT wired: it would need pod-side eval inside the provider =
   double-eval of side-effecting verbs. Results reach the model next
   turn via the transcript's real `⟹` rows; a turn-level driver (the
   loop hoisted out of the provider seam) is the clean fix if mid-loop
   results prove necessary. Per-step `:seon.typeahead/*` datom
   projections (transition/glyph/margin/eos-logprob/forwards) + the
   self-installing `:typeahead-steps` agent-page tile
   (`steps-tile-html`; reactive — no rows → no body). 7 tests / 42 assertions in
   `test/seon/ai/typeahead_test.cljs`. Live-proven on acme
   (2026-07-10, local MLX worker): swap-in via env alone, steps ran
   end-to-end, datoms + tile + the byte-exact 36k-token prompt blob
   captured — but the MODEL collapsed under the full acme prompt
   (progress→grow→stuck×2, zero locked forms, a pipe-noise draft; the
   EOS meter honest: −1.67→−6.95→−7.4). The 30k+-token encoder context
   is far beyond every measured protocol (all ≤4k); P4's replay corpus
   must measure context-length vs lock-rate before any swap-in claim.
   Null-render calibration is also not wired yet (no auto-offers —
   explicit glyph selection only).
4. **P4 bench** — the replay-corpus task in src-inspect-ai + live acme
   drive; merge decision on the measured deltas.

## The two cursor regimes (both measured; the regime is DERIVED)

- **Frontier (single position):** free typing; the cursor = end of the
  clean prefix. A draft ending MID-SYMBOL is never clamped through the
  partial — a hard-clamped typo is permanent (measured:
  `(todo/ad` → the undeclared `todo/ad!`). `split_partial_symbol`
  backs the clamp off so the model rewrites the symbol whole, exactly
  like editor typeahead replacing the partial word; the partial rides
  the render via the cursor-op candidates line. Measured after the
  fix: 6/6 correct symbol completions.
- **Multi-position (template):** after EXPAND, fields converge
  independently — per-hole entropy state, settle rounds (settled holes
  clamp, only unsettled re-noise), per-hole `accepted`/`round`.
- Not a mode: INTERPRET branches on whether a template expansion is
  active; nothing is asked of the model.

## The context budget (MEASURED — round 10, mlx_vlm model layer)

Round 8 measured a cliff at ~8–10k and called it a model constraint.
**Wrong — it was our port's encoder bug** (round 9: transplant test).
Round 10 swapped the model layer to mlx_vlm 0.6.4 (P2.5) and
re-measured the real curve on the new stack (M-series, 8-bit,
seed-averaged; research note §Round 10 has the full tables):

| context | prefill | decode forward | harvest-encode (256) | frontier task |
|---|---|---|---|---|
| 2k | 0.5 s (4.4k tok/s) | 193 ms | 148 ms | 3/3 |
| 8k | 2.6 s (3.0k tok/s) | 183 ms | 157 ms | 3/3 |
| 16k | 5.5 s (2.9k tok/s) | 188 ms | 165 ms | 3/3 |
| 32k | 12.2 s (2.6k tok/s) | 229 ms | 189 ms | 3/3 |

There is NO quality wall through 32k: needle retrieval 7/9 exact (the
two misses emitted `391` — a first-token denoise drop, not a retrieval
failure) and the frontier typeahead task is 3/3 at every size. The
budget is therefore pure LATENCY: decode forwards are ~flat (~0.2 s),
so per-step cost is dominated by the fresh-render prefill at ~2.6–3
k tok/s. A stateless step over an N-token render costs ≈ N/2800 s +
rounds×~0.2 s: ~1.7 s/step at 2k, ~3.6 s at 8k, ~6.7 s at 16k, ~14 s
at 32k (measured end-to-end). **P4 render profile: pick the render
size from the interactivity target** — ≤4k for sub-2s typeahead
steps, ≤8k when ~4 s is acceptable; beyond that, keep context across
steps (harvest-encode is ~0.15–0.19 s per 256-token commit) instead
of re-prefilling, which is the P3+ session-cache lever.

## Settled by measurement (do not re-litigate without new data)

- Selection strictly optional; no forcing knob exists.
- ☑ derived, never asked; ▶ askable.
- Glyph posteriors calibrated (position bias measured).
- parinfer rejected for repair; edamame owns it.
- Plan ledger = todo datoms rendered, done items dropped from render.
- No new config system: ctx blocks + one policy row.
- Frontier drafts never clamp a partial symbol (backoff, round 8).
- The round-8 "≤8k" cliff was OUR encoder bug, not the model (round 9);
  model layer moves to mlx_vlm, render budget re-measured after.
