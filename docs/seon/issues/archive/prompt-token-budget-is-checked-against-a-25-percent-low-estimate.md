---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, agent, ai, live-drive]
---

# Bound the prompt by the provider's token count, not a chars/4 estimate

## Problem

`:seon.config.ai/prompt-token-budget` is enforced against
`seon.ai.tokens/estimate`, which is a flat `chars/4`. DeepSeek's own tokenizer
counts 23–26% more. The guard therefore does its job correctly against a number
that is systematically low, and prompts leave the process well over the
declared budget with no refusal, no warning, and no durable fact saying so.

The declared bound is 32,768. The effective bound is roughly 40,000 provider
tokens. A dial that says one thing and does another is the failure class this
project treats as a defect even while it "works": it holds until a prompt sits
near the real context window, and then the overrun arrives as a provider error
instead of a loud local refusal.

This also reaches further than the budget. `AGENTS.md` makes
`seon.ai.tokens/estimate` the one unit for every human-visible size ("Human-visible
sizes are always estimated tokens through `seon.ai.tokens/estimate`"), so every
displayed size is low by the same quarter.

## Evidence

Cluster `default` (pid 31475), whole-system-arc observer lane, 2026-08-08.
`:seon.ai.attempt/settings-edn` on the attempts confirms
`:seon.config.ai/prompt-token-budget 32768` was the effective setting.

Exact captured prompt bytes compared with the provider's own count from
`:seon.ai.attempt/usage-edn`:

| Run | Prompt chars | `tokens/estimate` | Provider | Under by | Ratio |
|---|---:|---:|---:|---:|---:|
| `5dceb446` | 53,137 | 13,284 | 16,772 | 3,488 | 1.26 |
| `6a20658d` | 56,073 | 14,018 | 17,695 | 3,677 | 1.26 |
| `dc1c7df7` | 63,538 | 15,884 | 19,811 | 3,927 | 1.25 |
| `f79b24c3` | 108,032 | 27,008 | **33,476** | 6,468 | 1.24 |
| `ec979da7` | 115,415 | 28,853 | **35,453** | 6,600 | 1.23 |
| `9c7fa70f` | 116,572 | 29,143 | **35,827** | 6,684 | 1.23 |
| `26594ee9` | 54,026 | 13,506 | 16,812 | 3,306 | 1.24 |
| `188de1d3` | 53,864 | 13,466 | 16,778 | 3,312 | 1.25 |
| `4ea21f09` | 54,260 | 13,565 | 16,900 | 3,335 | 1.25 |
| `b0f70394` | 32,786 | 8,196 | 9,496 | 1,300 | 1.16 |

The estimate is exactly `chars/4` in every row (53,137/4 = 13,284.25 → 13,284).

Three prompts exceeded the declared budget — by 708, 2,685 and **3,059**
tokens — and nothing fired. No `::budget-exceeded` error exists in the
cluster's 55 error facts.

The guard itself is correct in isolation
(`src/seon/cluster/prompt.clj:105-123`): it compares `estimated` against
`budget` and drops render distance until it fits, refusing with
`::budget-exceeded` at distance 0. The defect is the measurement it trusts.

## Acceptance

- The prompt bound is expressed in the same units the provider bills in, so a
  prompt cannot leave the process over its declared budget. Either the
  estimator is calibrated per provider model against recorded
  `usage-edn.prompt_tokens`, or the budget is checked against a provider-supplied
  count before the call.
- A prompt that would exceed the budget is refused loudly with a flat error
  naming the budget, the measured size, and the render distance tried — never
  sent and silently over.
- One regression proves the class: a prompt whose real token count exceeds the
  budget while its `chars/4` estimate does not must be refused, not sent.
- Recorded drift between estimate and provider count is queryable, so the
  calibration can be checked rather than assumed.

## Resolution

The estimator now calibrates against recorded reality instead of asserting a
constant. Nothing new is written to reach it: every settled attempt already
records what the provider counted (`:seon.ai.attempt/usage-edn` →
`prompt_tokens`), so the only addition is `:seon.ai.tokens/characters` on the
capture for the same run — the exact character count recorded beside the prompt
string it counts, so the join is over scalars rather than every recorded
prompt.

- `seon.ai.tokens/calibrate` fits one characters-per-token ratio per model over
  the totals of those observations and reports the worst relative miss that
  ratio makes as `:seon.ai.tokens/relative-error` — the honest margin;
- `seon.ai.tokens/budget-report` is the ONE budget judgement. `::over` refuses
  or shrinks; `::near-limit` means the point estimate fits but the
  calibration's own measured error reaches past the budget, and it is admitted
  with a loud `log/warn` rather than silently; `::within` fits with its band;
- `seon.cluster.prompt/model-calibration` is the derivation and the query. With
  no recorded usage for that model it returns
  `seon.ai.tokens/shipped-calibration`, which carries NO error band (with no
  observations the error is unknown, and an invented band would be a tuned
  constant) and names itself `:seon.ai.tokens/shipped-constant`. Every report
  and refusal names the basis it used;
- the refusal now names the measured size, the basis, the fitted ratio, the
  sample count, the band and the render distance tried.

`seon.ai.tokens/estimate` remains the one sizer — the calibration is an
argument to it, not a second estimator. Callers that had reached for the raw
constant (`seon.effect`, `seon.render.transcript`) now go through
`estimate-of-characters`, so the constant is no longer an interface.

### Measured

Re-running the exact ten prompts from the report's (e2) table against the
32,768-token budget (`tmp/token-calibration-probe.clj`):

| Prompt chars | Provider | `chars/4` | verdict | calibrated | error | verdict |
|---:|---:|---:|---:|---:|---:|---|
| 116,572 | 35,827 | 29,143 | within | 36,076 | +0.70% | **over** |
| 115,415 | 35,453 | 28,853 | within | 35,718 | +0.75% | **over** |
| 108,032 | 33,476 | 27,008 | within | 33,433 | −0.13% | **over** |
| 63,538 | 19,811 | 15,884 | within | 19,663 | −0.75% | within |
| 32,786 | 9,496 | 8,196 | within | 10,146 | +6.84% | within |

Fitted ratio 3.23 characters per token, band 6.9%, ten samples. All three
prompts that really exceeded the budget are refused; none of the seven that
did not is. Independently, the same join over the live `default` cluster's own
committed facts (14 attempts of `deepseek-v4-flash`) fits 3.28 with a 9.3%
band — the calibration reproduces from real data, not only from the report.

### Regressions

- `seon.ai.tokens-test` — the fitted calibration predicts every observation
  within its own reported band; the calibrated verdict agrees with the budget
  about which prompts are really over while the uncalibrated basis admits all
  ten; the fallback path is exercised and asserted to name itself and to carry
  no invented band; the near-limit verdict is loud.
- `seon.cluster.prompt-test/a-prompt-the-provider-counts-over-budget-is-refused-not-sent`
  — plants attempt and capture facts at a real 3.2 characters per token, then
  sends 340 characters against a 100-token budget: `chars/4` says 85 and would
  admit, the calibrated estimate says 106 and the seam refuses with
  `::budget-exceeded` naming the observed basis. The fallback branch is
  asserted first, before any usage exists.

One defect found while writing that regression, worth recording because it is
the observer's own trap #4: a `d/q` result is a SET, so two attempts recording
the same characters and the same count collapsed into one sample and quietly
reweighted the fit. The query projects `?attempt` so a sample count means what
it says.
