---
type: research
status: active
tags: [research, ai, provider, model-registry]
---

# DeepSeek V4 model-registry pricing — 2026-08-03

## Result

DeepSeek's current official rate card publishes one regular-price table for
`deepseek-v4-flash` and `deepseek-v4-pro`. It also announces a future daily
peak/off-peak policy: every billing item will cost 2× the regular price during
09:00–12:00 and 14:00–18:00 Beijing time (UTC+8). The page does not give the
effective date; it says that date remains subject to an official announcement.

Therefore the model rows can declare the current regular rates below, but they
must not claim that time-dependent billing is active as of this retrieval. If
the announced schedule is represented before activation, it needs an explicit
future/not-yet-effective status rather than an active off-peak discount claim.

I read
[`model-registry-design-2026-08-03.md`](docs/prds/sci-execution-runtime/research/model-registry-design-2026-08-03.md)
and
[`llm-adapters.md`](docs/seon/reference/llm-adapters.md)
end to end before performing this verification.

## Primary-source evidence

Retrieved 2026-08-03 from DeepSeek's official
[Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing/) page.
Prices are USD per one million tokens.

| Model ID | Input, cache hit | Input, cache miss | Output |
|---|---:|---:|---:|
| `deepseek-v4-flash` | $0.0028 | $0.14 | $0.28 |
| `deepseek-v4-pro` | $0.003625 | $0.435 | $0.87 |

The same page identifies the models as `deepseek-v4-flash` and
`deepseek-v4-pro`, gives both a one-million-token context window and a maximum
384,000-token output, and states that the announced peak multiplier applies to
all three billing items, including cache-hit input.

## Announced schedule in UTC

The official source supplies Beijing-time peak windows and the 2× multiplier.
The UTC ranges below are a direct timezone conversion and complement, not
additional provider claims:

| Daily UTC range | Classification | Factor against published regular price | Factor against peak price |
|---|---|---:|---:|
| 00:00–01:00 | off-peak | 1× | 0.5× |
| 01:00–04:00 | peak | 2× | 1× |
| 04:00–06:00 | off-peak | 1× | 0.5× |
| 06:00–10:00 | peak | 2× | 1× |
| 10:00–24:00 | off-peak | 1× | 0.5× |

For completeness, applying the announced multiplier produces these peak
prices if and when DeepSeek activates the policy:

| Model ID | Peak cache-hit input | Peak cache-miss input | Peak output |
|---|---:|---:|---:|
| `deepseek-v4-flash` | $0.0056 | $0.28 | $0.56 |
| `deepseek-v4-pro` | $0.00725 | $0.87 | $1.74 |

## Ambiguity and implementation boundary

- The official page calls the listed numbers "regular prices," not
  "off-peak discounts." Calling off-peak 0.5× is meaningful only relative to
  the announced future peak price; relative to the published regular rate,
  off-peak is 1×.
- The source says the service "will soon adopt" the policy and supplies no
  effective instant. As of 2026-08-03, the evidence supports current flat
  regular prices plus an announced schedule, not an active schedule.
- DeepSeek says product prices may change and recommends checking the page
  regularly. These database facts are therefore discovery-time declarations,
  consistent with the model-registry design, rather than a runtime pricing
  feed.
- No paid API call was made for this verification.
