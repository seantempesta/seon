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
2. **Intra-form (diffusion):** the 256-token code-buffer works the current
   form — holes, templates, free text. Clamps are hard guarantees.
3. **Between commits (reactive):** the encoder context re-renders as a
   function of `(db, committed, draft)` — measured ~5ms for 4k tokens,
   free relative to one 114ms forward. Menus, contracts, and the plan
   ledger live HERE, not on the code-buffer.

## The driver FSM

The model is stateless; the driver is the machine. State =
`(committed, code-buffer-plan, active-offer)` — all derivable, all datoms.

| State | Does | Exits |
|---|---|---|
| RENDER | derive encoder context via policy sections (below); re-encode | DENOISE |
| DENOISE | rounds to stability / proof-probe; logit masks active | INTERPRET |
| INTERPRET | total partition of the round output | ↓ |
| EXPAND | glyph seen (token scan) or auto-offer fired → clamp template | RENDER |
| PREFILL-EDIT | draft = an OPENED call to a `prefills` head (cursor-oracle head resolution) → skip the open-tail denoise, fill the registry-derived template whose argument hole is PRE-FILLED with the live projection (sticky init: unaccepted = unchanged; structure/keys/ids clamped) — the W2 plan pass invokes this by seeding the head as the draft | RENDER |
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

Plan = `my.plan` items in the DB (the existing todo tree), NOT code-buffer
text. Flow:

1. First RENDER of a run offers a plan template (plain-language `; ☐ ①`
   lines — measured: perfect format compliance). The model writes the
   plan; the driver parses lines → `todo/add!` datoms. (Or the agent
   calls todo functions directly — same datoms, no special path.)
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

- **Menu sources** = section fns (the `:function-menu` eval-log menu,
  schema-contracts-for-draft-head, kb-hits; the plan-ledger source
  retired 2026-07-11 — the `:plan` block owns the ▶/☐ step render).
  New source = new section fn. Per-agent loadout = ctx blocks, already durable,
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

## Wire modes (server.py additions)

- `mode=fill` — segments in → hole texts + per-hole worst-confidence +
  trims out.
- `mode=rank` — segments + candidates in → calibrated ranked list out.
- `mode=step` — one full FSM turn: `{committed, draft, context-render,
  policy, prefills?}` → `{transition, new-draft, locks, glyph?,
  posteriors, prefill_head?}`. `prefills` is the draft-head argument
  affordance map (`head → [["clamp",s]|["prefill",s]|["free",n]]`,
  registry-derived seon-side); "prefill" segments start holding their
  text (STICKY: unaccepted positions renoise back to the init ids, so
  an edit is small deltas by construction). The driver loop can then
  live either side of the wire; seon owns RENDER (it has the DB), the
  worker owns DENOISE.

In-band errors (`gen_error`), same contract as today.

## Evaluation (three surfaces, no fourth)

- **bin/test-cljs** — seon-side section fns + policy row plumbing.
- **pytest (src-diffusion)** — stub-model driver tests: every INTERPRET
  arm forced; oracle-op offline proofs (real bb/node oracles).
- **src-inspect-ai** — the swap-in bench: replay corpus from real
  turn-capture blobs across capability rungs; arms = free-guided /
  typeahead / typeahead-with-menus-rendered-but-model-instructed-to-
  ignore (degradation arm). Metrics: form validity, function-calling
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
   `seon.agent.ctx.menu` owns `:function-menu` (named `:recent-verbs`
   until 2026-07-12; eval-log-derived,
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
   next render). The `:plan` vs `:plan-ledger` overlap was RULED +
   implemented 2026-07-11: `:plan` is THE plan surface —
   `plan-ledger-block` deleted, its ▶/☐/done-dropped contract folded
   into `my.plan.internal/plan-block` (its glyphs were never wire
   offers, so `function-offers` alignment was untouched).
   **P3b (provider + inspector tile) SHIPPED 2026-07-10**:
   `seon.ai.typeahead` — the `SEON_AI_PROVIDER=typeahead` step-loop
   provider (OFF by default; endpoint/key config shared with
   `:diffusiongemma`, and a full-URL `SEON_DG_ENDPOINT` local worker
   now needs NO bearer key). One provider call = the mode=step loop
   through the ONE wire path (`dg/complete`; `::mode :step` +
   committed/draft/offers/policy fields added): offers =
   `menu/function-offers` (glyph-aligned with the rendered
   `:function-menu` menu by construction), policy = the P3a row via
   `policy->wire`
   (worst-token-gate deliberately unmapped — probability vs the
   worker's nats gate), committed/locked + draft threaded per round,
   stop on done / stuck×2 / the new `:seon.typeahead/max-rounds` knob.
   THE POD OWNS EVAL: the reply is the locked forms as plain LLM-reply
   text, eval'd ONCE by the turn pipeline — which is also why the
   mid-loop `;; => result` feedback (control.py's `_result_comment`)
   is NOT wired: it would need pod-side eval inside the provider =
   double-eval of side-effecting functions. Results reach the model next
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
   drive; merge decision on the measured deltas. **SHIPPED 2026-07-10**
   (`seon_inspect.typeahead_corpus` — 10 real acme sessions across the
   capability rungs, byte-exact prompt blobs + verbatim menu/card
   sections + host-side outcome predicates in
   `evals/typeahead_replay.corpus.json`; task
   `seon_inspect/tasks/typeahead_replay.py`, 9 offline pytest proofs;
   evidence + ledger rows `evals/runs/2026-07-10-typeahead/`). Measured
   (local MLX worker, k=3, ≤4k renders): **typeahead .533 outcome /
   .90 validity / 3.0 s per reply** vs guided-no-menus .286/.46/20.5 s
   and guided-with-inert-menus .267/.37/33.4 s — the step surface earns
   (+.25 outcome at ~7× lower latency); no protocol leak (arm3−arm1 =
   −.02 = one execution, noise). **BUT uptake = 0.0** — zero glyph
   emissions in 66 steps, and auto-offers can't fire without null-render
   calibration, so the SELECTION channel specifically is unexercised:
   the lift is menu-text-in-a-step-regime + lock/commit/repair. DeepSeek
   references on the same corpus: .70 outcome (both the captured 36k
   production turns and a one-shot on the same 4k render — where its
   function-calling accuracy drops .71→.57 via hallucinated function
   names; Muse arm skipped, no key on the machine). Merge decision input: the swap-in is NOT
   correctness-parity with DeepSeek yet (.53 vs .70) but ~7× faster and
   local; the next lever per the data is wiring null-render calibration
   (auto-offers) + a glyph-emission teaching pass, then re-measuring
   uptake.
5. **P5 uptake** — null-render calibration wired + glyph-emission
   teaching; re-measured. **SHIPPED 2026-07-11** (same corpus
   796e81c9…badb, k=3 seeds 100–102, worker e3e4fc3668d3, evidence
   `evals/runs/2026-07-11-typeahead-uptake/`). Wiring:
   `seon.ai.typeahead/null-render` derives the null-intent baseline
   from the rendered prompt (the transcript EVENT LOG dropped, masthead
   + `ns=>` cursor kept; the intent-DERIVED `plan`/`plan-ledger`
   sections dropped WHOLE (since the 2026-07-11 ledger retirement,
   `plan` is the one intent-derived section) — verified on a captured acme blob that they
   restate the task) and rides every step beside the offers; bench arm2
   mirrors it (`build_null_render`), and the menu header gained two
   additive example lines (the bench overlays the CURRENT teaching on
   the corpus's frozen entries — teaching is code, entries are data).
   Measured: **arm2 .567 outcome / .90 validity / fn-call acc .429 /
   uptake .077 / 4.3 s** (was .533/.90/.333/0.0/3.0 s); arm3
   .20/.30/36.5 s (was .267/.37/33.4 s — 4 outcome flips, all on the
   REPL rung, noise-scale at n=10 k=3; ZERO glyph chars in arm3
   replies, so the teaching does not leak glyph-alone replies into the
   inert arm). The channel now FIRES: 13/63 steps expanded — **all 13
   calibrated auto-offers, still zero organic glyph emissions** (with
   P4's 0/66: the posterior channel is the viable selection path in
   the step regime; the model does not emit glyphs even
   taught-with-example). Margin evidence (per-step `margins` now in the
   traces): median 2.51 nats, 20/63 > 3.0 (the shipped seon default),
   only 4/63 > 6.0 (the design default); all 7 above-threshold
   non-fires were correctly suppressed by the free-region-typed gate.
   Fired selections were on-menu argmax but **0/13 selected a
   task-REQUIRED function — the captured `recent-verbs` menus do not
   contain the planning functions the tasks need** (warmups never called
   them): a MENU-SOURCE limitation, not a calibration failure. Costs:
   calibration ≈ +0.5 s median on no-expand executions (3.05→3.54 s;
   the worker caches the baseline per (null-render, glyph-set));
   EXPAND is the expensive arm (~18 s median per expand step at ~3.5k
   ctx — CAL probe + settle rounds; 47 s median for executions that
   fired). `auto-offer-margin` defaults left UNTUNED (3.0 seon / 6.0
   worker): fires between 3–6 nats were correctness-mixed, so the data
   justifies moving neither knob. Next lever per the data: menu
   SOURCES (schema-contracts / task-relevant offers so the menu holds
   the functions the task needs) + cheaper expansion (candidate-sized
   holes / fewer settle rounds) — not threshold tuning.
6. **P6 levers** — task-relevant menu source + cheaper/convergent
   EXPAND. **SHIPPED 2026-07-11** (measured PARTIAL, then CLOSED same
   day with a full re-run of all local arms; evidence
   `evals/runs/2026-07-11-typeahead-p6/` incl. summary.json, FRESH
   corpus regenerated on acme with the new menu live (2a31a33d…),
   close-run worker `c88acc1913c4` (all local arms one sha), k=3 seeds
   100–102, ledger rows under the `2026-07-11-typeahead-p6-close:`
   run_id label). Built:
   (a) **Toolkit menu group** — `seon.agent.ctx.menu` renders a second
   group under ONE glyph numbering with the recency group: public
   SPECCED program-graph fns of the nses the agent's CURRENT ns
   requires (the same stored-require-edges set whose compact cards the
   `:namespaces` section renders — computed, no hand list), ranked by
   cross-agent global call frequency (newest-200 eval window), per-ns
   round-robin admission, new `:seon.typeahead/toolkit-cap` knob
   (default 4); `function-offers` mirrors the concatenation by
   construction, and the corpus generator captures it unchanged (same
   entry grammar). Regenerated corpus: task-required functions are ON
   the menu for 9/10 samples (k2's world-state picks missed a DB-read
   function — honest gap).
   (b) **The non-converging repeat root-caused** (P5's p1 e3: the same
   failed auto-offer re-fired 4x at identical margins, empty reply):
   TWO mechanical defects — the EXPAND arm handed an unchecked junk
   fill forward as draft (live-reproduced: `(…/user ready")…` — the
   next step's repair dropped the whole broken region INCLUDING the
   clamped function call, returning the state to its pre-step value, and
   the per-step seed made the re-fire deterministic), and the driver
   loop had no memory of failed offers. Fixes: expansions are
   HARVESTED inside the expand step (`_lock_prefix` shared with the
   main path — parse/eval-gated; a broken closed-template expansion
   keeps the CALLER's draft + reports `expand-failed` + hints), and
   BOTH loop mirrors (seon.ai.typeahead + the bench `run_step_loop`)
   suppress a glyph whose expansion locked nothing for the rest of the
   call — the step trace IS the memory; the worker stays stateless.
   (c) **Expand cost** — measured: the 18 s was ONE denoise round
   burning the full 48-step budget (whole-code-buffer stop criteria never
   satisfied by a mostly-clamped code-buffer) + settle re-noise that rescued
   0/2 junk holes. Fixes: hole-stability early stop (denoise ends when
   the hole belief is unchanged across two probes), the CAL probe
   ladder now alternates geometric-DOWN with +Δ up (live: Φ(12)=.40 >
   Φ(24)=.27 > Φ(28)=.21 on the offer-args hole — the old ±Δ ladder
   never reached short lengths; the slack is what invites the echo),
   and `expand_settle_rounds=1` for the step regime.
   Measured — close run 2026-07-11 (fresh corpus; NOTE it scores
   harder than P5's: the DeepSeek references dropped .70→.40 on it):
   **arm2 .633 outcome / .867 validity / .524 fn-call acc / 3.8 s wall
   median / uptake .019** vs arm1 guided .286 / .464 / .0 / 22.4 s
   (n_scored 28 — 2 worker flakes) and arm3 inert-menus .267 / .333 /
   .0 / 24.8 s; DeepSeek references arm0 .40/.80/.571 (production 36k
   render) and arm0b one-shot .40/1.0/.143 on the same 4k render. The
   close run REPRODUCED the partial arm2 readings (.633/.867/.524;
   wall 3.8 vs the partial's 4.8 s) on a fresh worker sha. Expand
   fires: 3 — **3/3 on the task-REQUIRED function** (sample p3, one
   fire per seed, calibrated auto at ~3.3 nats; P5: 0/13
   task-relevant), median expand step **6.7 s** (was ~18 s pre-P6),
   ZERO repeat fires, 1/3 firing execs passed. Uptake .019 (3 fires /
   30 execs; P5's .077 was inflated by the 4x re-fire loop).
   **Kill-criteria verdicts (summary.json):** protocol-leak —
   arm3−arm1 = −.019; the report's strict-inequality check prints
   "LEAK", but the pass COUNTS are identical (8/28 vs 8/30 — the delta
   is arm1's flake-excluded denominator), i.e. no regression beyond
   noise, the same call as P4's −.02. Dead-weight — uptake .019 < .05
   BUT accuracy gain +.348 → "earns its render"; per the P5/P6
   decomposition the earn is menu TEXT + step regime +
   lock/commit/repair, and the glyph SELECTION channel stays FROZEN.
   Close-run mechanics, for the record: the original invocation
   crashed appending arm2's ledger row on a same-day run_id collision
   with P5 (fixed: run_id carries the run LABEL); the FIRST re-run
   flaked 30/30 on every arm because the canvas→code_buffer rename
   (33ee4673) crossed the mlx_vlm boundary (`ModelConfig.canvas_length`
   is the checkpoint's own field name — fixed at the `model.py`
   adapter seam, the one external-config read); its flake_rate=1.0
   rows remain in the append-only ledger under the
   `2026-07-11-typeahead-p6:` label, the real rows carry `…-p6-close:`.
   `_arm_summary` gained read-side `verb_*`→`fn_*` compat so frozen
   pre-rename jsonls (arm0b — DeepSeek key 402, not re-runnable) still
   reduce. Ops findings
   (same day, both OUTSIDE this lane's code): the acme pod OOM'd (4 GB
   heap in ~16 s) on FRESH-AGENT mint/render once the store hit ~40k
   konserve keys — proven NOT this work (a pre-P6 stash build crashed
   identically; heap = ~18M small arrays + ~20M objects, snapshot
   preserved, exact retainer unpinned; cluster reset cleared it) — and
   the DEFAULT cluster's DeepSeek key returns 402 Insufficient Balance.

## The live block (`:typeahead-steps`) — observability + provider instructions

ONE ctx block (`seon.agent.ctx.typeahead-steps/steps-block`, name
`:typeahead-steps`, priority 95), BOTH render slots, both reactive.
The provider loop (`seon.ai.typeahead`) stays hiccup-free — it only
transacts the per-step `:seon.typeahead/*` projections; the block ns
derives everything at render. The P3b self-install is RETIRED: nothing
installs this block by default, anywhere.

- **`:seon.render/html`** (`steps-tile-html`) — the agent-page tile,
  composed top to bottom (every panel reactive — it vanishes when its
  rows lack the data): the STATE BANNER (FSM state now as dot+text,
  provider, step k/N, rounds j/budget, wall so far, `ctx ~N tok`
  render size, worker sha), THE CODE-BUFFER PANE (the last step's
  `buffer_text` painted by `buffer_spans` status — locked / clamped /
  settled / resolving / repaired / frontier — with a legend line so
  the encoding never needs recall), the OFFERS panel (per-offer
  fired / suppressed / below-margin + a calibrated-margin mini-bar
  against the auto-offer threshold tick), the HOLES panel (per-hole
  worst/mean entropy, CAL-chosen length, accepted/round, snap→candidate
  after an EXPAND), the DONE-NESS strip (EOS-logprob meter + locked
  forms + harvested-token totals) and the compact STEP HISTORY (per
  step: transition dot+text, glyph, `→⊢`/`✗offer` expand outcome,
  margin Δ, locks + forwards, wall `gen-s`, worst `H`, EOS meter).
  The per-step transacts make it live over the normal datastar SSE
  morph (live-proven 2026-07-11 on the UNIFIED agent view: 92
  `/agent/{id}/feed` frames across one 8-min provider call; the tile
  renders both as a right-rail card and as the selectable primary
  panel — screenshot:
  `research/typeahead-tile-2026-07-11.png`, an 8-step call showing
  repair + expand + suppressed-offer + settled-hole states at once).
  No step rows → nil → the tile vanishes.
- **`:seon.render/ai`** (`steps-ai`) — the provider protocol teaching,
  rendered ONLY when the agent's RESOLVED provider is `:typeahead`
  (`seon.ai/resolved-config` over the render db — global config row
  with the per-agent `:seon.ai/agent-provider` overlay). Content is
  what the menu headers do NOT teach (glyph selection stays colocated
  with the menu blocks): the lock/draft reply mechanics, template
  expansion, and the result grammar (results arrive as the
  transcript's bare `⟹` rows; `;; =>` is not part of the grammar).
  Any other provider → `""` → the section vanishes at zero token cost.

Additive per-step datom fields (all sourced from the existing step
wire response, no worker change): `:seon.typeahead/gen-s`,
`:seon.typeahead/entropy-worst`, `:seon.typeahead/draft-preview`
(160-char cap), `:seon.typeahead/draft-tokens`,
`:seon.typeahead/prompt-tokens`, `:seon.typeahead/worker-sha`.

### Enable story (opt-in only — never a shipped default)

The PRIMARY enable path is CONFIG-DRIVEN at cluster scope: add the
block row to a manifest overlay's `:seon.config/agent-context`
`:seon.agent/ctx` tree (resolved once at boot into the DB; blocks are
upserted by name). The row, verbatim:

```clojure
{:seon.agent.ctx/name :typeahead-steps :seon.agent.ctx/priority 95
 :seon.render/ai seon.agent.ctx.typeahead-steps/steps-ai
 :seon.render/html seon.agent.ctx.typeahead-steps/steps-tile-html}
```

Do NOT add it to `config/system.edn` / `config/acme.edn` defaults —
a cluster that wants it adds the row to ITS overlay.

The RUNTIME path diffs the block onto one LIVE agent without a
restart — the same one mechanism (`install!`/`remove!`), agent scope:

```clojure
;; enable (eval'd by the agent, or any driver inside its scope)
(seon.agent.ctx/install! seon.agent.ctx.typeahead-steps/steps-block)

;; disable — both slots vanish on the next render (reactive)
(seon.agent.ctx/remove! :typeahead-steps)
```

Note the ai slot is DOUBLY gated: even installed, it renders only for
a `:typeahead`-resolved agent — flipping `:seon.ai/agent-provider`
back to `:deepseek` blanks the instructions without uninstalling the
tile (live-proven: post-flip prompt blobs carry zero instruction
lines while the step tile stays).

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
