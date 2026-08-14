---
type: research
title: Provider routing — should the default route move to OpenRouter?
date: 2026-08-14
status: complete
subject: seon.ai provider descriptor rows, :seon.config.ai/* dials
---

# Provider routing: OpenRouter vs DeepSeek direct

## Question

The owner suspects DeepSeek direct is oversubscribed (announced 2× peak-hour
pricing at UTC 01–04 and 06–10, occasional 402s under load) and has an
OpenRouter API key available. Should the default provider route switch?

## Verdict, first

**Stay direct. Add OpenRouter as the configured backup target, not the
primary.** Two premises behind the question do not survive checking, and the
one real economic fact — DeepSeek's 50× cache-hit discount — is exactly the
thing an intermediary is least likely to preserve for us.

## 1. Premise checks (both partly falsified)

**Peak surcharge is announced, not active.** DeepSeek's own pricing page,
retrieved 2026-08-13, records the peak/off-peak schedule as *"announced but
not yet active — disabled"* as of 2026-08-02, with a separate report of new
pricing taking effect 16:00 UTC 2026-08-16. The announced windows are
01:00–04:00 and 06:00–10:00 UTC (09:00–12:00 and 14:00–18:00 Beijing), 2×
multiplier, flat outside. The tree already models this honestly:
`config/default.edn:494` carries
`:seon.ai.model/deepseek-pricing-schedule-status :announced`, and the schema
enumerates `[:enum :announced :active]`
(`resources/seon/schemas/seon.ai.model.edn`, `:seon.ai.model/deepseek-pricing-schedule-status`).
So today we are paying flat rates; the surcharge is a *scheduled* risk to
plan for, not a current cost.

**The 402s are not a pricing problem and would not be fixed by OpenRouter.**
DeepSeek returns 402 for insufficient account balance. See §4 for what our
own code currently does with it — which is worse than what OpenRouter would
do, and is fixable without changing provider.

## 2. Price table (USD per 1M tokens, retrieved 2026-08-13)

### DeepSeek-family, the model we actually run

| Route | Model / slug | Input (cache miss) | Input (cache hit) | Output |
|---|---|---|---|---|
| **DeepSeek direct** | `deepseek-v4-flash` (0731 GA) | **0.14** | **0.0028** | **0.28** |
| DeepSeek direct, announced peak (01–04, 06–10 UTC, not yet active) | same | 0.28 | 0.0056 | 0.56 |
| DeepSeek direct | `deepseek-v4-pro` | 0.435 | 0.003625 | 0.87 |
| OpenRouter | `deepseek/deepseek-v4-flash-20260731` (GA build) | 0.08 | see §3 | 0.25 |
| OpenRouter | `deepseek/deepseek-v4-flash` (**0423 preview build**) | 0.0679 | see §3 | 0.168 |
| OpenRouter | `deepseek/deepseek-v4-pro` | 0.435 | — | 0.87 |
| OpenRouter | `deepseek/deepseek-v3.2` | 0.2145 | — | 0.3218 |
| OpenRouter | `deepseek/deepseek-r1` | 0.70 | — | 2.50 |

The 0.14/0.28 direct row matches what is already in the database seed at
`config/default.edn:486-489`, so the registry is current.

### Third-party direct hosts, same open weights (V4-Pro, the only tier with a clean cross-host quote)

| Host | Input | Output | vs DeepSeek direct |
|---|---|---|---|
| DeepSeek direct | 0.435 | 0.87 | 1× |
| DeepInfra | 1.30 | 2.60 | 3.0× |
| Novita | 1.60 | 3.20 | 3.7× |
| Fireworks | 1.74 | 3.48 | 4.0× |
| Baseten | 1.74 | 3.48 | 4.0× |
| Together | 2.10 | 4.40 | 4.8× |

**This is the whole story of the alternative-host question.** DeepSeek's
first-party API is 3–5× cheaper than every Western host running the same
open weights, because DeepSeek is subsidising its own inference and the
others are selling GPU time. No alternative direct host is a cost argument;
they are only an availability argument.

### The number that decides it

Seon renders a large, largely-repeated context every turn. On DeepSeek
direct, a cache hit costs **0.0028** against a 0.14 miss — a **50× (98%)**
discount, already recorded at `config/default.edn:487`. OpenRouter's own
caching documentation describes DeepSeek cache reads as **0.1× of normal
input pricing** — a 10× discount, not 50×. On the OpenRouter GA slug that is
0.008/Mtok cached versus 0.0028 direct: **~2.9× more expensive on precisely
the token class that dominates an agentic workload's input bill.** (Flagged
as the one number I could not confirm on a first-party OpenRouter page for
the V4 generation specifically; the 0.1× figure may date from V3. It is the
single measurement that should gate any future switch.)

Sticker-price comparison says OpenRouter is ~43% cheaper on cache-miss
input and ~11% cheaper on output. Cache-aware comparison says direct wins
by a wide margin as soon as the cache-hit rate is meaningful. For a system
that re-sends the same rendered context every turn, the cache-hit rate is
the dominant term.

## 3. Compatibility: what a new provider row concretely requires

Good news: **the seam already exists and needs no code change.** A provider
descriptor row is exactly five facts, declared at
`resources/seon/schemas/seon.ai.model.edn` under
`:seon.ai.model/provider-entity`:

- `:seon.ai.model/provider-id`
- `:seon.config.ai/endpoint`
- `:seon.config.ai/api-key-variable`
- `:seon.ai.model/openai-chat-completions`
- `:seon.ai.model/output-token-wire-key`

Three such rows already sit in the config manifest at
`config/default.edn:461-477` (deepseek, moonshot, meta). The docstring at
`src/seon/ai.clj:35-38` states the rule directly: the descriptor row is
endpoint, model, timeout, and exactly one authentication declaration — the
NAME of an environment variable, or explicit `:seon.config.ai/no-auth true`.

Point-by-point against OpenRouter's OpenAI-compatible API:

| Requirement | OpenRouter | Seon impact |
|---|---|---|
| Base URL | `https://openrouter.ai/api/v1/chat/completions` | one string in the row |
| Auth header | `Authorization: Bearer <key>` — identical | none; `src/seon/ai.clj:622-625` builds exactly this |
| Credential read | env var by name | none; `src/seon/ai.clj:967-981` is the one place `System/getenv` is called |
| Model string | namespaced slug, `deepseek/deepseek-v4-flash-20260731` | one string in a `:seon.ai.model/id` row |
| Max-output wire key | `max_tokens` (OpenAI classic) | `:seon.ai.model/output-token-wire-key "max_tokens"`, same as the deepseek row |
| Request shape | OpenAI chat-completions, `messages`/`stream` | none; `src/seon/ai.clj:626-640` builds string-keyed OpenAI JSON |
| Streaming | SSE, OpenAI format | none; JDK leaf at `src/seon/ai.clj:1277-1300` |
| Tool calling / structured outputs | supported, with `sort`/`only`/`order` routing controls and an `Exacto` mode for tool-call accuracy | routing controls would go through `:seon.config.ai/extra-body-edn` (`resources/seon/schemas/seon.config.ai.edn:5-10`, parsed at `src/seon/ai.clj:597-620`) — an EDN map with string keys, no schema change |
| Reasoning toggle | `reasoning: {enabled: true}` / `reasoning_effort` | already expressible via extra-body |

**Multiple provider rows with per-agent overlay: already supported.** Rows
are entities keyed by `:seon.ai.model/provider-id`
(`:seon.db/identity true` in the schema), a model row points at one by ref
(`config/default.edn:483`), and every `:seon.config.ai/*` dial including
`endpoint`, `model`, and `api-key-variable` carries
`:seon.config/per-agent true` — as does every
`:seon.config.ai.backup/*` dial
(`resources/seon/schemas/seon.config.ai.backup.edn`). Provider resolution
happens per call at `src/seon/ai.clj:351-385`, and a caller-supplied
`:seon.ai/api-key-variable` deliberately wins over the descriptor's default
(`src/seon/ai.clj:366-372`).

**The backup target is already built and is the cheapest option here.**
`seon.ai/targets` (`src/seon/ai.clj:387-432`) assembles primary + optional
backup, where the backup is *overrides over the primary* so a partial backup
is unrepresentable; `:seon.config.ai.backup/model` alone decides whether a
backup exists, and endpoint / key-variable / timeout inherit unless set.
Failover is already evidence-driven at `src/seon/ai.clj:1016-1071`:
`:rate-limit` (429/408) and `:server` (5xx) return `:failover-now` when a
backup exists and only `:backoff` when it does not. **Today they back off,
because no backup is configured.**

## 4. Risks, and one defect found

**Model version pinning is the sharpest risk.** OpenRouter's bare
`deepseek/deepseek-v4-flash` slug resolves to the **0423 preview build**,
not the 0731 GA build our default names (`config/default.edn:315-321`
explicitly records "version DeepSeek-V4-Flash-0731"). At the time the 0731
slug launched, only two providers hosted it and **only DeepSeek's own
endpoint served the official 0731 build — other providers were still running
the 0423 preview.** So the cheapest OpenRouter row in the price table is
cheap because it is a *different model*, and even the correct slug can route
to a host serving different weights unless pinned with `only`/`order`. A
silent weight change under a stable model name is exactly the class of
defect this project treats as worse than a failure.

**Intermediary risks, in descending weight:**

- *Provider heterogeneity.* Across OpenRouter, one DeepSeek slug is served
  by ~16–19 companies at ~4× price spread, 4–57 tok/s throughput, and
  97%–~100% uptime. Default load balancing deprioritises a provider that had
  an outage in the last 30s and weights the rest by inverse square of price
  — which optimises for cost, i.e. steers toward the slow tail. Our
  `:seon.ai.model/last-tokens-per-second` observation would become a blend
  of hosts rather than a property of one, degrading a diagnostic we rely on.
- *Fee.* 5.5% platform fee on pay-as-you-go credits ("no markup" on provider
  pricing on top of that); BYOK is 5% of what the same call would cost
  through credits, after the first 1M BYOK requests/month.
- *Latency.* ~25–40 ms added routing overhead (edge Workers); one benchmark
  found OpenRouter faster to first token than a direct provider. Not
  material at our scale.
- *Data policy.* OpenRouter does not train on traffic and does not log
  prompts/completions by default, retaining request metadata only; a Zero
  Data Retention control can restrict routing to ZDR endpoints per account,
  model group, or request. Acceptable, but it is one more party in the path.
- *Price volatility.* Per-provider rates move under a stable slug; our
  `:seon.ai.model/input-usd-per-mtok` facts would become an estimate of a
  moving blend instead of a quoted rate.

**Defect found while reading the failover code (unrelated to the switch).**
`error-class` at `src/seon/ai.clj:989-997` maps 401→`:authentication`,
403→`:authorization`, 404→`:model`, 408/429→`:rate-limit`, 5xx→`:server`,
and everything else→`:request`. **DeepSeek's 402 (insufficient balance)
therefore lands in `:request`**, which `disposition`
(`src/seon/ai.clj:1016-1071`) treats as "not this request" — terminal
`:fail`, no failover, no backoff, *even when a backup target exists*. A 402
is unambiguously "not here, but a different target would work", the same
family as `:authentication`. Since the owner reports seeing 402s, this is
the concrete reason those events kill a run rather than degrading. Worth an
issue note; fixing it is a one-line addition to `error-class` plus one
regression asserting `:failover-now` for 402 with a backup present.

## 5. Options and the exact config change each needs

### Option A — Stay direct, add OpenRouter as backup *(recommended)*

Keeps the 50× cache-hit economics on every ordinary call; converts today's
`:backoff` on 429/5xx into `:failover-now`; costs nothing when DeepSeek is
healthy, because the backup is only called on a provably-free rejection.

Config change — add a provider row and one model row alongside the existing
three at `config/default.edn:461-477`:

```clojure
{:seon.ai.model/provider-id "openrouter"
 :seon.config.ai/endpoint "https://openrouter.ai/api/v1/chat/completions"
 :seon.config.ai/api-key-variable "OPENROUTER_API_KEY"
 :seon.ai.model/openai-chat-completions true
 :seon.ai.model/output-token-wire-key "max_tokens"}

{:seon.ai.model/id "deepseek/deepseek-v4-flash-20260731"
 :seon.ai.model/provider [:seon.ai.model/provider-id "openrouter"]
 :seon.ai.model/context-window-tokens 1000000
 :seon.ai.model/input-usd-per-mtok 0.08
 :seon.ai.model/output-usd-per-mtok 0.25
 :seon.ai.model/input-modalities #{:text}
 :seon.ai.model/thinking-dials #{:disabled :low :high :max}}
```

…and then the **one-line** dial that arms it (replacing the
`:seon.config/absent` backup dials):

```clojure
:seon.config.ai.backup/model "deepseek/deepseek-v4-flash-20260731"
```

Endpoint and key-variable inherit from the model row's provider via
`resolved-target` (`src/seon/ai.clj:351-385`), so no other backup dial is
needed — that is the design intent recorded at `src/seon/ai.clj:393-403`.
Also fix the 402 classification (§4) so the backup can actually be reached
on the failure the owner is seeing.

### Option B — Switch the default primary to OpenRouter

Change `:seon.config.ai/endpoint`, `:seon.config.ai/model`, and
`:seon.config.ai/api-key-variable` at `config/default.edn:313`, `:321`, and
`:365`. **Not recommended**: pays the 5.5% fee, loses (or degrades ~2.9×)
the cache-hit discount that dominates our input bill, blends the
tokens-per-second observation across hosts, and risks silently running 0423
weights. Revisit only if a measured cache-hit price on the GA slug proves
the 0.1× figure wrong.

### Option C — Add a non-DeepSeek direct host (Together/Fireworks/DeepInfra/Novita)

Same descriptor-row shape as Option A. **Not recommended on price**: 3–5×
DeepSeek direct on identical weights. Only worth it if a specific compliance
or residency requirement appears, which none has.

### Deferred trigger

If DeepSeek activates the 2× peak surcharge (watch for
`:seon.ai.model/deepseek-pricing-schedule-status` moving `:announced` →
`:active`), peak-window cache-miss input becomes 0.28 against OpenRouter's
0.08 and the cache-aware arithmetic should be re-run rather than assumed.
The per-agent overlay already makes a time-of-day or per-agent route
possible without a code change.

## Sources

- [DeepSeek API Pricing 2026: V4-Flash & V4-Pro Per-Token Costs](https://deepseek.ai/pricing)
- [DeepSeek V4 Flash 0731 — OpenRouter](https://openrouter.ai/deepseek/deepseek-v4-flash-20260731)
- [DeepSeek V4 Flash 0423 — OpenRouter](https://openrouter.ai/deepseek/deepseek-v4-flash)
- [DeepSeek V4 Pro — OpenRouter](https://openrouter.ai/deepseek/deepseek-v4-pro)
- [Why Use OpenRouter for DeepSeek — OpenRouter Blog](https://openrouter.ai/blog/insights/why-openrouter-for-deepseek/)
- [OpenRouter Prompt Caching](https://openrouter.ai/docs/guides/best-practices/prompt-caching)
- [OpenRouter vs Together vs Fireworks for DeepSeek V4 (2026)](https://andrew.ooo/answers/openrouter-vs-together-vs-fireworks-deepseek-v4-2026/)
- [DeepSeek V4 Pro Pricing Guide 2026 — DeepInfra](https://deepinfra.com/blog/deepseek-v4-pro-pricing-guide-2026-providers-cost-analysis)
- [OpenRouter Pricing: Fees, Credits & BYOK Explained — Amnic](https://amnic.com/blogs/openrouter-pricing)
- [OpenRouter Data Retention Policy 2026 — Meetily](https://meetily.ai/llm-privacy/openrouter)
- [OpenRouter Latency Benchmark 2026 — Markaicode](https://markaicode.com/benchmarks/openrouter-production-benchmark-latency/)

## Prior research

- [model-registry-deepseek-pricing-2026-08-03.md](model-registry-deepseek-pricing-2026-08-03.md)
