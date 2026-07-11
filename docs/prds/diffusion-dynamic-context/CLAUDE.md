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

P1–P5 shipped + measured; P6 PARTIAL (arm3 + summary.json pending on the
fixed bench runner — see [[typeahead-design]] Phases §6, the resume
point). Headline on the FRESH corpus (harder; DeepSeek refs drop to
.40): **typeahead .633 outcome / .867 validity / 4.8 s median vs guided
.286 / 29 s** — better than the frontier reference AND ~5× faster,
free-local. The glyph SELECTION channel is marginal by measurement
(uptake .019; organic emissions 0/129 ever) and is now **FROZEN** —
menus stay as passive context only.

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

- **Local worker**: `bin/seon start dg-worker` (runs
  `python -m seon_diffusion.worker --port 17860`;
  `SEON_DG_ENDPOINT=http://127.0.0.1:17860`, no bearer key needed for a
  full-URL endpoint). **Restart after ANY src-diffusion edit and verify
  `/health` `worker_sha` before trusting a number.** Idle-unloads after
  15 min (RSS → ~0.5 GB), reloads on next request.
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
- Perf is ALWAYS tokens/second; brute force on the cheap model is a
  legitimate strategy (owner convention).

## Landmines / open issues

- **Store-scale OOM (CORE, owner-directed fix in flight)**: pod heap
  450 MB → 4.4 GB (Node's default V8 cap) in ~16 s on fresh-agent mint
  once the store hits ~40k konserve keys. NOT this lane's code (pre-P6
  builds crash identically). 2.9 GB heap snapshot preserved untracked
  at repo root. Will recur as ANY store grows — the top blocker for
  multi-turn workhorse runs.
- **P6 close pending**: re-run arm2+arm3 under the fixed runner
  (run_id now carries the run label), land summary.json + ledger rows,
  update the PARTIAL markers. Worker sha will differ if the grammar
  sweep lands first — that's fine, record it.
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
