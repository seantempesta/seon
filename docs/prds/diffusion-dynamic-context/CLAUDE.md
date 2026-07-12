---
type: orchestrator
status: active
tags: [orchestrator, agent]
---

# Diffusion dynamic-context — auto-loaded index (one-stop shop)

> **The verified code-buffer**: a diffusion LLM (DiffusionGemma, local MLX)
> generates Clojure fast while Seon's oracle proves every commit — denoise
> → oracle check → lock/harvest eval-proven forms into the encoder KV →
> repair the rest. The typeahead provider (`SEON_AI_PROVIDER=typeahead`)
> is that loop as a swap-in agent LLM. This file is the INDEX + runbook;
> depth lives in the linked docs. Keep it tight + current.

## ▸ Current state (2026-07-11 PM)

**NEW (2026-07-11): the `:typeahead-steps` ctx block** — one block, both
slots (live step-trace tile + provider-gated ai instructions), in
`seon.agent.ctx.typeahead-steps`; the P3b self-install is retired,
enabling is opt-in only (manifest overlay row or per-agent
`ctx/install!` — see [[typeahead-design]] §"The live block"). Live-proven
on acme (feed morph mid-call, prompt-blob ai section, remove!-vanish).
The RICH tile (span-painted code buffer + offers/holes/EOS panels) is
live-proven on the UNIFIED agent view 2026-07-11 PM — screenshot
`research/typeahead-tile-2026-07-11.png`; the drive also surfaced a
render-scale finding (grown acme store ⇒ ~22k-token renders, 21–34 s/
step — the ≤4k protocol needs the minimal tree at scale) and a core
fix (`seon.render.sci/invoke-bounded` now deep-forces lazy SCI render
results inside the deadline window; lazy hiccup from a bounded `my.*`
render fn was interrupting every feed push on the unified view).

P1–P6 shipped + measured — P6 CLOSED 2026-07-11 (full re-run of all
local arms, worker `c88acc1913c4`; evidence
`evals/runs/2026-07-11-typeahead-p6/` summary.json + `…-p6-close:`
ledger rows; numbers + kill verdicts in [[typeahead-design]] Phases
§6). Headline on the FRESH corpus (harder; DeepSeek refs drop to .40):
**typeahead .633 outcome / .867 validity / 3.8 s median vs guided
.286 / 22.4 s and inert-menus .267** — better than the frontier
reference AND ~6× faster, free-local; no protocol leak (arm3−arm1 =
−.019, identical pass counts), "earns its render" (gain +.348). The
glyph SELECTION channel is marginal by measurement (uptake .019;
organic emissions 0/129 ever) and is now **FROZEN** — menus stay as
passive context only.

**Owner pivot (2026-07-11 PM):** fable agents re-cleared for all work;
focus = planning + executing + fixing CORE-SYSTEM problems, NOT
benchmark maxing. The lane's direction is now
[[planner-worker-design]] (P7): a frontier model hands down a plain-text
plan; the diffusion agent authors it as `my.plan` datoms (`plan!`),
refines it (`step!`/`reopen!`/`needs!`), keeps it in focus via the
`:plan-ledger` ▶ anchor (`active!`), and `done!`s steps only when
`::expect` verifies — multi-turn goal completion at a time budget, with
plan-survives-pod-restart in the win condition. In flight (fable
agents): the store-scale OOM root-cause (owner-directed, see Landmines)
and the src-diffusion grammar-drift sweep (`;; =>` is banned live
grammar; bare `⟹` is real).

## How to run it

- **Local model server**: `bin/seon start diffusion-server` (runs
  `python -m seon_diffusion.server --port 17860`;
  `SEON_DG_ENDPOINT=http://127.0.0.1:17860`, no bearer key needed for a
  full-URL endpoint — the `SEON_DG_*` env names and the `worker_sha`
  wire field are kept for continuity). **Restart after ANY
  src-diffusion edit and verify `/health` `worker_sha` before trusting
  a number.** Idle-unloads after 15 min (RSS → ~0.5 GB), reloads on
  next request.
- **Testbed = acme** (pod 7980 / wire 7981, `bin/acme` — ours to reset;
  code edits need `bin/acme build` before `bin/acme restart pod`). The
  default cluster (7890) belongs to other lanes — hands off.
- **Provider swap-in**: `SEON_AI_PROVIDER=typeahead` (step-loop; pod owns
  eval) or `=diffusiongemma` (plain guided). Both OFF by default.
- **Tests**: `cd src-diffusion && .venv/bin/pytest` (stub-model driver +
  real bb/node oracles); `bin/test-cljs` once per seon-side unit;
  bench = `seon_inspect/tasks/typeahead_replay.py` in `src-inspect-ai/`
  (README §run matrix; evidence under `evals/runs/<date>-…/`, ledger
  `evals/scorecard.jsonl`).
- **Bench discipline**: fresh-worker sha-verify first; k=3 seeds
  100–102; zero-scores → suspect the harness before the model (fired 3×
  this arc); one full measurement per unit, not per edit.

## Load-bearing findings (timeless — earned the hard way)

- **The oracle/eval loop is the value, not generation steering.**
  Learned twice: the June kill-gate capstone and again in P4–P6 (the
  lift decomposes as step-regime + lock/commit/repair + menu TEXT;
  the selection channel added ~nothing). Invest in context + proof,
  not steering apparatus.
- **Menu/context SOURCE is the binding constraint** (P6: task-required
  fns on-menu moved task-relevant fires 0/13 → 3/3). A plan step naming
  its intent is the strongest source — hence the P7 pivot.
- **The budget is prefill latency, not model quality**: no quality wall
  through 32k ctx; decode ~0.2 s flat; prefill ~2.6–3 k tok/s. ≤4k
  render ≈ 1.7 s/step; 32k ≈ 14 s. Size renders from the interactivity
  target; harvest-encode (~0.15 s/256 tok) beats re-prefill for
  session continuity.
- **Specced `^:async` fns must NEVER reject with expected errors** — a
  rejection hits the instrument wrapper → `:core` fault → `:crash`
  exits the pod (stability fix e6295ecd; audit task open).
- The round-8 "8k context cliff" was OUR encoder bug (cache
  transplant), not the model — mlx_vlm adapter replaced the port.
- **Seon-side renames must not cross the mlx_vlm boundary** —
  `ModelConfig.canvas_length` is the checkpoint's OWN field name; the
  canvas→code_buffer sweep (33ee4673) renamed the read in `model.py`
  and every worker call AttributeError'd (P6 close, 30/30 flake). The
  external name is read ONCE, at the `DiffusionGemmaVLM` adapter seam.
- Perf is ALWAYS tokens/second; brute force on the cheap model is a
  legitimate strategy (owner convention).

## Landmines / open issues

- **Store-scale OOM: FIXED + live-scale CONFIRMED 2026-07-11** (fork
  1598a824; confirmation drive d1253588: store grown to 52k keys /
  192k datoms, fresh mint peaked +300 MB over idle and settled, the
  once-exploding `ready-leaves` rule returns correct results at scale,
  0 core faults). The fresh-store drive rule is RETIRED; acme is left
  AS-GROWN as a realistic-scale testbed. History: research/
  store-scale-oom-2026-07-11.md (agent-ctx).
- tx-feed pub reader logs `pub frame decode failed … not valid JSON`
  on every acme pod boot (reconnects 2 s; smell, task filed).
- Default cluster DeepSeek key: 402 Insufficient Balance (owner top-up;
  Muse key IS on disk — `META_MODEL_API_KEY`, see memory).
- `:plan` vs `:plan-ledger` block overlap — owner ruling pending; P7 W1
  should land ONE plan surface.

## Settled — do NOT re-litigate (measured; new data required to reopen)

- Suggestions strictly OPTIONAL forever; no forcing knob may exist.
- The calibrated POSTERIOR is the selection channel; organic glyph
  emission is dead (0/129); ☑ derived never asked; margins not tunable
  on current evidence.
- parinfer rejected; edamame owns repair. Frontier drafts never clamp a
  partial symbol.
- Plan ledger = `my.plan` datoms rendered; done items dropped from the
  render. No new config surface: ctx blocks + one policy row.
- Scaffold-infill steering demoted; free-gen/typeahead + oracle is the
  product path. GPU gating obsolete — local MLX runs everything free.
- Older GPU-era settlements (A100/FP8/TPU/deploy stability): see
  [[roadmap]] "Settled".

## Entry points (the depth)

- [[planner-worker-design]] — **the active direction (P7)**: roles, turn
  loop, win conditions, W1–W4 phases.
- [[typeahead-design]] — the shipped surface: FSM, glyphs, cursor
  oracle, policy row, and per-phase SHIPPED notes with ALL the numbers
  (P1–P6). The context-budget table lives here.
- [[research/typeahead-hole-filling-2026-07-10]] — rounds 1–10
  measurements (incl. the 8k-cliff forensics).
- [[roadmap]] + [[architecture]] + [[grounding]] — the verified code-buffer
  spine and the (dormant) CUDA/RunPod era: worker modes, validation
  ladder, speed levers, deploy-stability procedure, research index.
  The RunPod worker is FROZEN in `src-diffusion/…/cuda/`; revive by
  need.
- `docs/prds/agent-ctx/coordination.md` tail — cross-lane state.
- `src/my/plan.cljs` + `src/my/CLAUDE.md` — the plan system the worker
  integrates with (P7).

## How to work here

- Fable agents for implementation (owner 2026-07-11 PM); tight written
  specs first — this file + the design docs are the spec surface.
- Read the source before building: `reference-code/` for libraries,
  [[grounding]] for the worker seams. Guessing produces confident,
  wrong code.
- Every measurement: sha-stamped evidence + ledger row; three testing
  surfaces only (bin/test-cljs · src-inspect-ai · gym).
- Update THIS file's Current state + the relevant design doc in the
  same unit as the change — it auto-loads for every future session.
