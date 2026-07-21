---
type: research
status: active
tags: [agent, gym, research]
---

# Gym curation axes + green baseline (context-curation Phase A)

## TL;DR

Phase A of the context-curation plan added two measured axes to the gym and a
competency grouping, then captured the green baseline (issue #51).

- **eval-error-rate axis** — every scorecard now carries
  `:seon.gym.scorecard/eval-error-rate` = failed RUN-DRIVEN evals
  (`:seon.eval/ok? false`, which includes issue-#44 segmenter
  orphan/empty-span no-ops) ÷ total. A new `:eval-error-rate` predicate kind
  asserts it is `≤ :seon.gym.predicate/max-error-rate`. **Error-as-VALUE is
  not a failed eval** — envelope-honesty's bogus transact resolves to
  `{:seon.db/ok? false}` as a value, the eval is `:ok? true`, rate stays 0.0.
- **canvas (UI-update) axis** — every scorecard carries
  `:seon.gym.scorecard/canvas-updated?` = did the primary agent set
  `:seon.render.live-canvas/content` on its OWN `[:seon.agent/id …]` entity. A
  new `:canvas-updated` predicate kind asserts it per-agent.
- **competency tag** — `:seon.gym.scenario/competency` (required enum:
  `:planning :db-memory :error-recovery :honesty :over-retrieval`); all 16
  scenario maps (14 files; todo-prompt-thin holds 3) + the 7 inline
  driver-test scenarios are tagged. `run-competency-battery!` runs only the
  scenarios of one competency, in order.
- **Baseline**: the 4 runnable (stub + active) scenarios PASS at the default
  config with eval-error-rate 0.0; the 12 paid/todo scenarios refuse the run
  (no spend) but measure context size. Turn-1 default context ≈ **19.8k–20.1k
  tokens**. **No current scenario drives the canvas** — `canvas? false`
  everywhere — so `:drives-canvas` has no persisted scenario yet (see
  Follow-ups).

## What was built

All in the gym lane (`test/seon/gym/**`):

- `test/seon/gym/driver.cljs` — new schemas (`:seon.gym/eval-error-rate`,
  `:seon.gym.predicate/max-error-rate`, `:seon.gym.scenario/competency`, the
  two scorecard slots), two axis-enum members (`:makes-few-errors`,
  `:drives-canvas`), two predicate kinds (`:eval-error-rate`,
  `:canvas-updated`), helpers `run-eval-oks` / `eval-error-rate*` /
  `agent-canvas-updated?` (caused-run scoping, identical to `eval-at+source`),
  scorecard wiring, and `run-competency-battery!`.
- `test/seon/gym/driver_test.cljs` — 3 new tests (curation axes scored +
  error-value-is-not-a-fail + competency battery); all 7 inline scenarios
  tagged with a competency.
- `test/seon/gym/baseline_test.cljs` — the gated green-baseline sweep.
- 14 scenario EDN files tagged with `:seon.gym.scenario/competency`.

## Green baseline (default config, 2026-06-28)

Run: `SEON_GYM_BASELINE=1 node out/test/test.js --test=seon.gym.baseline-test`
(free — paid/todo refuse the drive; context tokens measured for all).

| scenario | competency | tier | status | tokens | verdict | err-rate | canvas? |
|---|---|---|---|---|---|---|---|
| s01-stub-pipeline-smoke | planning | stub | active | 19844 | PASS | 0.0 | false |
| blank-message-refusal | honesty | stub | active | 19858 | PASS | 0.0 | false |
| envelope-honesty | honesty | stub | active | 19861 | PASS | 0.0 | false |
| finding-storage-shape | db-memory | stub | active | 19891 | PASS | 0.0 | false |
| stores-findings-unprompted | db-memory | paid | todo | 19878 | refused | - | - |
| reuses-stored-functions | db-memory | paid | todo | 19881 | refused | - | - |
| writes-tests-for-own-fns | planning | paid | todo | 19882 | refused | - | - |
| todo-resume | planning | stub | todo | 19897 | refused | - | - |
| s12-run8-two-agent-consultation | db-memory | paid | active | 19915 | refused | - | - |
| todo-multistep-tracking | planning | paid | active | 19928 | refused | - | - |
| s21-log-workout-existing-schema | db-memory | paid | active | 20007 | refused | - | - |
| x3-expense-reuse-and-category-total | db-memory | paid | active | 20019 | refused | - | - |
| x1-subscriptions-total-and-max | db-memory | paid | active | 20025 | refused | - | - |
| err-recovery-unregistered-attr | error-recovery | paid | active | 20033 | refused | - | - |
| s32-consult-before-research | db-memory | paid | active | 20050 | refused | - | - |
| x12-narrow-question-no-over-retrieval | over-retrieval | paid | active | 20104 | refused | - | - |

Notes:

- **err-rate 0.0 for every runnable scenario** is correct: the stub scripts
  only emit forms that succeed (or, for envelope-honesty, return an error
  VALUE — still an `:ok? true` eval). The real eval-error-rate signal needs
  the PAID agentic drives, where the LLM actually mistypes / hits the
  segmenter no-op path. The axis is now instrumented and free to read on
  those runs.
- **canvas? false everywhere** — see Follow-ups.
- Token totals span ~260 tokens (19844 → 20104); the spread tracks the
  per-scenario seeded fixtures/findings that render into turn-1 context, not
  the always-on blocks.

## Suite status

`bin/test-cljs`: **731 tests, 3329 assertions, 0 failures, 0 errors** (327s).
The new curation/battery tests run inside that suite; the baseline sweep is
gated off by default.

## Follow-ups / Core-lane needs

1. **No scenario drives the canvas (scenario-authoring, paid).** The
   `:drives-canvas` axis + `:canvas-updated` predicate are wired and tested
   (inline), but no PERSISTED scenario asks an agent to make the live tile its
   primary surface — so the curation goal "agents drive the canvas" has no
   standing measurement on the real (paid) drives. Recommend a new paid
   scenario (e.g. a planning/db-memory task whose pass bar includes
   `:canvas-updated`) so the axis moves on live runs. This is gym-lane work
   but wanted a routing decision on which competency it anchors.
2. **eval-error-rate only bites on paid runs.** Same shape: the axis is real
   but the stub tier never produces organic errors. When the next paid sweep
   runs, add an `:eval-error-rate` predicate (suggest `max-error-rate ~0.2`)
   to the active paid scenarios so a context change that raises REPL-error
   noise reds the card.
3. **Config-seam observation (no change needed, FYI for Core).** The gym
   steers the loadout purely through `apply-run-config!` setting
   `SEON_PROFILE` / `SEON_CONFIG` env vars around the run and restoring them
   in `finally`. This works and is clean, but it is process-global mutation of
   `process.env` — if anything ever runs gym scenarios concurrently in one
   process it would race. Today everything is strictly sequential (the root
   `*conn*` swap already forces that), so it is safe; flagging only so Core
   knows the config seam is env-var-global, not a per-run binding.
