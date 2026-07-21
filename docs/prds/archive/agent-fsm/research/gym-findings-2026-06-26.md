---
type: research
status: active
tags: [research, agent]
---

# Gym sweep findings — 2026-06-26 (post Snap-to-Tx + spec sweep + as-of fork bump)

/ First live DeepSeek sweep after the big refactor. Git sha `7fdd194`. Purpose:
/ catch runtime/spec regressions under real agent control + check the gym is
/ measuring the right thing. Cards: `tmp/gym-paid-card-*.edn` (gitignored — the
/ durable findings are HERE).

## TL;DR

- **Runtime is clean.** All 6 live scenarios `:terminates true` + `:replies-honestly
  true`. The 530335e spec sweep's always-on instrumentation did NOT throw under
  live agents (incl. `s21`, which registers a schema + transacts — the prime
  canary for a too-tight `register!`/`transact!` spec). Loop, CAS work-fence, and
  the `e6d196d5` as-of fork bump all hold live. Clean process exit (interposer
  code=0, no mid-run death).
- **Judge is trustworthy.** Calibration: ground-truth reply → PASS (score 100),
  fabrication → FAIL (score 0), `discriminates? true`.
- **Behavioral finding (the signal that matters):** EVERY failure is on a
  *"did the agent USE the context in front of it"* axis, while every
  terminate/honesty axis passes. Hypothesis: the **37k-token `:namespaces`
  section (84 % of the prompt) is drowning the task signal** — motivates #26.

## Scorecards (live tier, sha 7fdd194)

| Scenario | pass? | failing axis | passing axes |
|---|---|---|---|
| `s21-log-workout-existing-schema` | ✗ | `:reuses-schemas false` | models-work, terminates, honest |
| `s32-consult-before-research` | ✗ | `:consults-findings false` | searches-first, terminates, honest |
| `x12-narrow-question-no-over-retrieval` | ✗ | `:consults-findings false` | terminates, honest |
| `todo-multistep-tracking` | ✗ | `:stores-proactively false`, `:writes-tests false` | models-work, terminates, honest |
| `err-recovery-unregistered-attr` | ✓ | — | models-work, reuses-schemas, terminates, honest |
| `judge-calibration` | n/a | — | `discriminates? true` |

The single pass (`err`) is the error-recovery path — the agent correctly recovered
from an unregistered-attr throw. The fails are not crashes; they are the agent
choosing not to consult/reuse/store. n=1 per scenario — DeepSeek is stochastic, so
direction matters more than any single card.

## Hypothesis test (next): lean prompt → do the context-use axes recover?

The 4 failing axes (`consults-findings`, `reuses-schemas`, `stores-proactively`)
none NEED the framework full-source of search/fs/message/lifecycle — they need the
agent to read the DB / inventory, which `:seon.db` covers. So shrinking
`full-source-whitelist` to a lean set isolates the question: *is the bloat the
cause?* Run the same 4 scenarios with a lean whitelist, compare. A directional
recovery is empirical proof for #26's bloat fix; no change means look elsewhere.
Result appended below.

## n=2 result — the METRIC is too noisy to attribute behavior (the real finding)

Before testing lean-vs-fat, I re-ran the SAME 4 scenarios at the SAME fat prompt
(n=2) to check the n=1 signal wasn't DeepSeek noise. It largely was:

| Scenario (axis) | n=1 | n=2 |
|---|---|---|
| s21 (`reuses-schemas`) | ✗ | ✓ |
| s32 (`consults-findings`) | ✗ | ✗ |
| x12 (`consults-findings`) | ✗ | ✓ |
| todo (`stores-proactively`) | ✗ | ✗ |

**2 of 4 flipped between identical runs.** Same prompt, opposite verdict. So the
single-run behavioral pass/fail is HIGH-VARIANCE — the n=1 "bloat is drowning the
signal" read was over-interpretation. Suite green + clean exit both times; this is
purely a metric-trust finding, not a runtime bug.

**Conclusion (the owner's "wrong metric → cliff" made concrete):** a single sweep
is NOT a trustworthy signal for these behavioral axes. Attributing a behavior
change to context SHAPE needs pass-RATES over k reps, not one verdict. s32 and todo
failed both times (candidate stable-fails); s21/x12 are variable.

**Implications:**

- #26's bloat fix stays justified on TOKEN ECONOMY alone — `:namespaces` is 84 % of
  the prompt objectively; cheaper + faster inference regardless of behavior. That
  does not need the gym to prove.
- To use the gym to prove a BEHAVIORAL win (lean → better), we need a reps/pass-rate
  mode (run each scenario k times, compare rates fat-vs-lean). Current baseline at
  n=5 (fat) is accumulating — appended below.

## n=5 baseline pass-rates (fat prompt) — pending

_(appended when the rep accumulation finishes — establishes the variance band per
scenario at the current fat default, the baseline a future lean run compares to)_
