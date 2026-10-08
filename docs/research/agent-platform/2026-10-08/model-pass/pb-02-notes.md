---
type: research
status: stopped
date: 2026-10-08
lane: led-pb02
---

# PB-02: advance an agent's world by what changed (lane led-pb02, 2026-10-08)

Stopped before any code change: HEAD disagrees with the row. The forms ran in JVM mode on default (source
`608305d3c`, no `src` difference from HEAD `e4d047e50`), agent `led-pb02`, fixture provider, no paid call.

## What HEAD does after a `defn`

The row assumes the pass after a `defn` re-forks the cluster base and re-runs the whole overlay. On HEAD it does
neither. Settlement installs the evaluated rows in place (`seon.sci.eval/install-evaluated-rows!`). It also marks the
context as having acquired the new value when `installation-covers-program-change?` holds. The next `acquire!`
then answers from `acquired-database?`. Neither `base-ctx` nor `overlay!` appears in the profile of the pass after
a `defn`, and both are armed and profiled: a direct `base-ctx` call lists `overlay!`, `overlay-plan` and
`interpret-overlay!`.

## Measurements (parent = HEAD)

MCP sequence (`pb-01-probes.clj` p3 form, one new agent; allocation is the JVM total):

| Step | ms | MB | Acquisition stages (calls, ms) |
|---|---|---|---|
| first `(+ 1 2)` | 342 | 279 | `base-ctx` 1/67 (the first fork of a new agent), `acquire!` 4/71 |
| second | 67 | 59 | `fork-for-turn` 1/6 |
| `(defn pu-f [x] (inc x))` | 179 | 162 | `fork-for-turn` 1/7 |
| after the `defn` | 72 | 72 | `fork-for-turn` 1/6 |
| after the `defn`, again | 57 | 67 | `fork-for-turn` 1/6 |
| second `defn` | 147 | 154 | `fork-for-turn` 1/6 |
| after it | 59 | 61 | `acquire!` 1/1, `fork-for-turn` 1/8 |

The pass after a `defn` (57–72 MB, 57–72 ms) costs the same as an ordinary pass (59–67 MB). The row's ≤ 60 MB is
the whole ordinary pass, not an acquisition delta.

Model passes (`run-model "default" 3 #{2}` shape, stages added, zero base misses):

| Pass | ms | MB | Acquisition stages (calls, ms) |
|---|---|---|---|
| ordinary | 531–563 | 667–700 | `acquire!` 2/1–2, `fork-for-turn` 2/12 |
| `defn` | 734 | 925 | `install-evaluated-rows!` 1/85, of which `installation-covers-program-change?` 1/64 |
| after the `defn` | 635 | 904 | `acquire!` 2/14, all of it in `acquired-database?` 2/14; no `base-ctx` or `overlay!` |
| ordinary | 640 | 875 | `acquire!` 2/1 |

The row's "drops ≥ 400 MB" is against the plan's 1,360 MB. HEAD's pass after a `defn` is 904–907 MB in two runs,
and an ordinary pass 667–875 MB. None of that is base or overlay work.

The miss path is still whole-overlay. It runs on another writer's program commit on the same branch, or when a
settlement is not covered. Calling `base-ctx` on the cluster head (35 authored rows, 7 interpreted, 5,674 function
rows) costs 20–21 ms / 32 MB warm:

| Part | ms / MB |
|---|---|
| `overlay-plan` | 10–11 / 15 (`authored-rows` of the value and of the loaded program: 5–6 / 10) |
| `interpret-overlay!` | about 17 ms, 8 `install-row!` |
| `function-digests` of a new commit | 3–4 / 13 (a whole-program query, run once per commit) |

## Findings

- `installation-covers-program-change?` costs 64 ms in a model `defn` pass. It runs `pull '[*]` before and after on
  every entity the settlement touched, turn and evaluation entities included, with their components. Existing
  task: `seon.sci.eval/program-change-check-scans-the-whole-history`, whose intent describes the older
  history-scan form. The cost now follows the settlement's datoms, but those include every non-program entity.
- `acquired-database?` costs 14 ms in the pass after a contracted `defn` and about 1 ms otherwise. Likely cause:
  `acquisition-attributes` re-derives for the new projection. Not verified. Covered by the PB-02b/S5-05 row and
  the task `seon.sci.eval/program-movement-is-checked-three-ways-per-form`.

## Options

1. **Retire PB-02's budget row as met. Re-scope the row to the miss path:** an in-place advance by digest diff,
   with the proofs as written. Gain: the 20 ms / 32 MB other-writer path drops to about the changed closure.
   Cost: a second acquisition path in `acquire!` (est. 60–100 lines in `eval.clj`). That path must reproduce
   `regenerate-agent-context!`'s private-layer rules, and the stale-caller and inherited-caller rows of
   `seon.repl-differential-test` depend on those rules.
2. **Recommended: point the row at the measured defn-pass owner instead.** `installation-covers-program-change?`
   should pull only the program rows among the touched entities, at 64 ms of the 85 ms `install-evaluated-rows!`.
   Then PB-02b removes `acquired-database?`'s 14 ms. This targets the costs that are actually on the `defn` path.
3. **Fold PB-02 into PB-03.** An adoption is the one loaded-commit change that must re-fork. Advance the base there,
   and take the miss path's other-writer case only if a probe shows it is frequent.
