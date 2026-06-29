---
type: research
status: active
tags: [agent, dashboard, flow]
---

# Gym SCORECARD — the fitness function for the context-improvement loop

## TL;DR

`bin/gym-scorecard` runs the whole competency battery, collects every
axis, and appends ONE git-SHA-keyed EDN line to
`docs/prds/agent-fsm/research/gym-scorecard.log`. That single number-set
is the fitness function: make a context change, re-run, keep it iff it
lifted the WHOLE battery — otherwise revert. The harness never sees the
answers (anti-cheat below), so you can't game the number by reading one.

- Implementation: `test/seon/gym/scorecard.cljs` (`run-battery!`,
  `aggregate`, `load-battery-scenarios!`, `format-line`, `append!`).
- Gated runner + pure unit tests: `test/seon/gym/scorecard_test.cljs`.
- Wrapper: `bin/gym-scorecard`.
- Builds on the existing `seon.gym.driver` (`measure-context!`,
  `run-scenario!`) — it does NOT re-implement scenario driving.

## How to run it

```bash
bin/gym-scorecard               # FREE battery (no LLM spend) — every loop iteration
bin/gym-scorecard --no-build    # reuse out/test/test.js (skip the ~90s compile)
bin/gym-scorecard --paid        # ALSO drive the paid-tier scenarios (COSTS MONEY;
                                # needs the active provider's API key set)
bin/gym-scorecard --log=PATH    # override the scorecard log path
```

The wrapper stamps the git SHA (`git rev-parse --short HEAD`) and an ISO
timestamp (a CLJS fn can't compute either) and hands them to the gated
runner `seon.gym.scorecard-test/battery-scorecard-run` (a no-op on the
normal suite — gated on `SEON_GYM_SCORECARD`). The runner drives
`run-battery!`, prints the one `SEON-GYM SCORECARD-BATTERY` line, and
appends the EDN to the log.

## Two modes

- **FREE (default).** For EVERY scenario: `measure-context!` — turn-1
  context SIZE in tokens, no LLM spend. PLUS the mechanical predicates on
  the runnable stub-tier scenarios via `run-scenario!` (paid/todo members
  refuse without spend — errors are values). Cheap enough to run every
  loop iteration.
- **PAID (`--paid`).** The same drive path, but paid-tier scenarios
  actually run — adding live pass-rate, eval-error-rate, canvas-updated,
  and the LLM-judge axis on real drives.

## The line format

One EDN map per line in the log, keyed by git SHA:

```clojure
{:seon.gym.battery/sha "b8685144"
 :seon.gym.battery/at  #inst "2026-06-29T00:25:49.000-00:00"
 :seon.gym.battery/per-competency
 {:honesty   {:seon.gym.battery/pass 2 :seon.gym.battery/total 2}
  :db-memory {:seon.gym.battery/pass 1 :seon.gym.battery/total 1}
  :planning  {:seon.gym.battery/pass 1 :seon.gym.battery/total 1}}
 :seon.gym.battery/total-tokens         482435
 :seon.gym.battery/eval-error-rate      0
 :seon.gym.battery/canvas-updated-count 0
 :seon.gym.battery/scenario-count       19
 :seon.gym.battery/scored-count         4}
```

(`:seon.gym.battery/judge-mean` — mean of the REAL, non-skipped LLM-judge
verdicts — is present only when a judge actually graded, i.e. PAID mode.)
Schema: `:seon.gym/battery-scorecard`, registered in `seon.gym.scorecard`.

## Baseline (sha `b8685144`, FREE mode, 2026-06-28)

| Axis | Value |
|------|-------|
| per-competency (scored) | honesty 2/2 · db-memory 1/1 · planning 1/1 |
| total-tokens (all 19 scenarios' turn-1 context) | 482435 |
| eval-error-rate (mean over scored) | 0 |
| canvas-updated-count | 0 (a paid-drive axis — stub runs don't drive the LLM) |
| scenario-count / scored-count | 19 / 4 |

FREE mode scores only the 4 runnable stub scenarios (planning ×1,
db-memory ×1, honesty ×2); the other 15 paid/todo members contribute
their free turn-1 token measure but refuse to drive. `--paid` lifts
scored-count toward the full roster and fills `judge-mean`.

## The axes (and what each instruments)

- **per-competency pass/total** — did the agent BEHAVE right per
  competency (`:planning :db-memory :error-recovery :honesty
  :over-retrieval :ui`). Only runnable members count toward `total`.
- **total-tokens** — summed turn-1 context size across every scenario.
  The lever the curation loop pushes DOWN while holding pass-rate.
- **eval-error-rate** — mean fraction of failed run-driven evals (the
  "agents make few REPL mistakes" instrument).
- **canvas-updated-count** — how many drives drove their own live tile
  as the primary surface.
- **judge-mean** — semantic correctness on the SEPARATE judge axis
  (paid only). "Behaved right, answered wrong" stays distinguishable:
  pass true + low judge score.

## Anti-cheat (load-bearing)

`aggregate` reads ONLY the axes — `pass?`, `total-tokens`,
`eval-error-rate`, `canvas-updated?`, judge SCORE. It never touches
`:seon.gym.scorecard/results` actuals or any `:reference`/`:expect`
answer text. The expected answers are graded INSIDE `run-scenario!` and
surfaced only as a boolean and a judge score, so it is impossible to
"improve the number" by reading an answer. The harness never sees the
answers; it sees whether the agent got them. (This complements the §3.4
self-bait load-time guard in the driver — a scenario's own message/
fixtures can't contain its answer either.)

## How a loop iteration uses it

1. `bin/gym-scorecard --no-build` → records the current SHA's line.
2. Make ONE context change (drop/trim a block, reorder, reword a manual).
3. `bin/gym-scorecard --no-build` again at the new SHA.
4. KEEP the change iff it lifted the WHOLE battery — no competency
   regressed, tokens didn't balloon, error-rate didn't rise (and in
   `--paid`, judge-mean held). Otherwise REVERT.

The SHA-keyed lines accumulating in the log ARE the trend; `grep -a
SCORECARD-BATTERY` across commits, or just `tail` the log.

## Gaps / notes

- FREE mode's behavioral signal is only the 4 stub scenarios; the rich
  pass-rate / judge signal needs `--paid` (money). The curation loop's
  cheap-every-iteration lever is `total-tokens` + the stub pass-rate;
  the expensive periodic checkpoint is `--paid`.
- `eval-error-rate` prints as `0` (CLJS float64 has no int/double
  distinction); it validates as `:double` and round-trips fine.
- The battery is sequential by construction — both `measure-context!`
  and `run-scenario!` swap the root `seon.db/*conn*`, so two can never
  overlap. ~17 measures + 4 stub drives ≈ a few minutes in FREE mode.
