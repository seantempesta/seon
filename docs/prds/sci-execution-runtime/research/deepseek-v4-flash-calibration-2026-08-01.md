---
type: research
status: active
tags: [ai, research]
---

# DeepSeek V4 Flash calibration — 2026-08-01

## Decision

Use API model slug `deepseek-v4-flash` (version label
`DeepSeek-V4-Flash-0731`) as Seon's default provider model. For ordinary
agent-loop turns, explicitly disable thinking. Retain a 65,536-token output
budget so calls that deliberately enable thinking are not truncated, and raise
the provider timeout from 60,000 ms to 180,000 ms.

The old 8,192-token calibration is falsified for this model. The representative
code task used 8,807–13,340 reasoning tokens under Flash high thinking and
9,792–14,279 completion tokens in total. Both 4,096- and 8,192-token pilot
calls spent the entire budget on reasoning, returned no implementation, and
finished with `length`. Every final matrix call used `max_tokens: 65536`;
all 27 finished with `stop` and structurally valid output.

## Headline comparison

Each cell below summarizes nine calls (three task shapes × three repetitions).
Values are median `[minimum–maximum]`. Output tok/s excludes reasoning tokens
and measures only visible-content delivery. This makes the disposition range
noisy: dividing a three-token visible answer by a millisecond-scale burst can
produce thousands of tok/s. The task-level table is the useful comparison for
that metric.

| Configuration | Output tok/s | Thinking time (s) | TTFT (s) | Total runtime (s) | Off-peak cost/turn | Peak cost/turn |
|---|---:|---:|---:|---:|---:|---:|
| Flash + thinking high | 216.8 `[101.8–12,129.4]` | 13.979 `[1.209–98.924]` | 0.937 `[0.777–1.124]` | 21.345 `[2.151–103.817]` | $0.000654 `[$0.000038–$0.004008]` | $0.001308 `[$0.000076–$0.008015]` |
| Flash + non-thinking | 100.4 `[12.3–158.0]` | 0 | 0.949 `[0.596–1.220]` | 9.843 `[0.703–11.534]` | $0.000253 `[$0.000008–$0.000437]` | $0.000506 `[$0.000017–$0.000875]` |
| Pro, provider default/high | 67.0 `[52.0–4,240.3]` | 17.802 `[3.450–94.745]` | 1.092 `[0.751–1.383]` | 33.522 `[4.882–108.783]` | $0.001586 `[$0.000248–$0.006347]` | $0.003172 `[$0.000497–$0.012693]` |

All paid matrix runs started from 22:26 through 22:40 UTC, outside the stated
2× peak window, so their actual charges were off-peak. Both rates are reported
using the owner-supplied prices: Flash cache-miss input $0.14/M, cache-hit
input $0.0028/M, output $0.28/M; Pro $0.435/M, $0.003625/M, and $0.87/M.
Peak projections double every component.

### Task-level medians and spreads

Each row is three runs at `max_tokens: 65536`; values are median
`[minimum–maximum]`.

| Configuration | Task | Output tok/s | Thinking (s) | TTFT (s) | Total (s) | Off-peak cost | Peak cost |
|---|---|---:|---:|---:|---:|---:|---:|
| Flash + thinking high | Plan reply | 129.1 `[101.8–134.4]` | 13.979 `[8.823–17.373]` | 0.838 `[0.777–1.124]` | 21.345 `[16.221–22.966]` | $0.000654 `[$0.000436–$0.000737]` | $0.001308 `[$0.000872–$0.001474]` |
| Flash + thinking high | Code generation | 237.4 `[216.8–243.2]` | 72.635 `[70.759–98.924]` | 1.032 `[0.840–1.055]` | 78.022 `[75.950–103.817]` | $0.003220 `[$0.002751–$0.004008]` | $0.006439 `[$0.005502–$0.008015]` |
| Flash + thinking high | Disposition | 6,139.7 `[139.0–12,129.4]` | 1.496 `[1.209–3.478]` | 0.937 `[0.778–1.026]` | 2.300 `[2.151–4.508]` | $0.000048 `[$0.000038–$0.000103]` | $0.000095 `[$0.000076–$0.000207]` |
| Flash + non-thinking | Plan reply | 100.4 `[83.5–102.1]` | 0 | 1.161 `[0.949–1.220]` | 10.435 `[9.843–11.534]` | $0.000253 `[$0.000253–$0.000281]` | $0.000506 `[$0.000506–$0.000563]` |
| Flash + non-thinking | Code generation | 155.2 `[136.2–158.0]` | 0 | 0.959 `[0.785–1.026]` | 10.102 `[9.587–10.485]` | $0.000388 `[$0.000371–$0.000437]` | $0.000776 `[$0.000743–$0.000875]` |
| Flash + non-thinking | Disposition | 16.3 `[12.3–19.3]` | 0 | 0.736 `[0.596–0.946]` | 0.864 `[0.703–1.111]` | $0.000008 `[$0.000008–$0.000008]` | $0.000017 `[$0.000017–$0.000017]` |
| Pro, provider default/high | Plan reply | 56.3 `[52.0–57.0]` | 17.802 `[14.508–18.428]` | 0.965 `[0.751–1.314]` | 33.522 `[29.448–33.768]` | $0.001586 `[$0.001436–$0.001616]` | $0.003172 `[$0.002871–$0.003233]` |
| Pro, provider default/high | Code generation | 89.9 `[69.4–94.1]` | 78.520 `[48.653–94.745]` | 1.176 `[1.092–1.232]` | 95.410 `[71.965–108.783]` | $0.005900 `[$0.003916–$0.006347]` | $0.011801 `[$0.007832–$0.012693]` |
| Pro, provider default/high | Disposition | 67.0 `[63.6–4,240.3]` | 4.338 `[3.450–6.904]` | 0.955 `[0.894–1.383]` | 5.234 `[4.882–7.905]` | $0.000299 `[$0.000248–$0.000447]` | $0.000598 `[$0.000497–$0.000893]` |

Flash high thinking delivered visible code 2.64× faster than Pro at the
task-level median (237.4 versus 89.9 tok/s), but thinking dominated both
models' wall time. Flash non-thinking completed the code task in 10.10 s,
versus 78.02 s for Flash high and 95.41 s for Pro. These prompts checked
structural compliance, not semantic quality; the latency and cost differences
therefore do not establish a quality ranking.

### Projected 20×20 generation

For a 20-agent × 20-generation workload (400 turns), the equal task-mix
projection averages the three task-level median costs before multiplying by
400.

| Configuration | Off-peak 400-turn cost | Peak 400-turn cost | Cost relative to Pro |
|---|---:|---:|---:|
| Flash + thinking high | $0.5228 | $1.0457 | 0.504× |
| Flash + non-thinking | $0.0865 | $0.1730 | 0.083× |
| Pro, provider default/high | $1.0380 | $2.0761 | 1.000× |

The workload's absolute price depends on its prompt and output distribution;
the ratios above are a measured three-shape projection, not a rate-card-only
estimate. Flash non-thinking was 6.04× cheaper than Flash high on this mix.

## Tuning surface discovered against the live API

### Dependency ledger

- Provider boundary: DeepSeek OpenAI-compatible `/chat/completions` and live
  `/models`, observed 2026-08-01; current Thinking Mode and JSON Output docs.
- Seon request/stream owner: `src/seon/ai.cljc`, especially descriptor-to-
  request projection, OpenAI-compatible SSE decoding, timeout, and usage
  capture.
- Seon data contracts: `resources/seon/schema/ai.edn` and
  `resources/seon/schema/config.edn`; default descriptor row:
  `config/default.edn`.
- Live transport and durable proof: per-agent flow graph, web SSE feed,
  `:seon.ai.attempt/*`, `:seon.cluster.run/*`,
  `:seon.cluster.run.form/*`, and `:seon.cluster.eval/*` facts on source commit
  `092a24bf6`.

### Model identifiers

`GET /models` advertised exactly `deepseek-v4-flash` and
`deepseek-v4-pro`. Those lowercase slugs succeeded in requests. The release
name `DeepSeek-V4-Flash-0731` is a version label and returned HTTP 400 when
used as `model`. `DeepSeek-V4-Pro` likewise did not identify a live API model.
The former baseline names `deepseek-chat` and `deepseek-reasoner` were not in
the catalog and their model-detail requests returned 404; they cannot be used
to infer a current mapping. The comparison therefore names both accepted
slugs and the provider's version labels explicitly rather than calling Pro
`deepseek-chat`.

### Thinking switch and effort

Omitting `thinking` leaves the provider default in force: thinking on at high
effort. The exact OpenAI-compatible request shapes, with the key supplied only
through an authorization header and never printed, are:

```json
{
  "model": "deepseek-v4-flash",
  "messages": [{"role": "user", "content": "Reply with WAIT."}],
  "thinking": {"type": "enabled"},
  "max_tokens": 65536,
  "stream": true,
  "stream_options": {"include_usage": true}
}
```

```json
{
  "model": "deepseek-v4-flash",
  "messages": [{"role": "user", "content": "Reply with WAIT."}],
  "thinking": {"type": "disabled"},
  "max_tokens": 65536,
  "stream": true,
  "stream_options": {"include_usage": true}
}
```

Omitting the `thinking` field produces the same provider default as the first
shape: thinking on at high effort. Effort is a separate top-level
`"reasoning_effort":"low"|"high"|"max"`. Flash honors all three
levels. Current documentation says Pro maps `low` to high. The matrix used
enabled/default-high Flash, explicitly disabled Flash, and provider-default/
high Pro. A Flash `low` pilot completed the code prompt in 20.284 s using
1,877 reasoning and 962 visible-content tokens, versus the high-thinking
median of 78.022 s and 10,484 reasoning tokens.

The provider states that thinking is also available on its Anthropic-
compatible endpoint, but this calibration did not probe that wire shape.
Temperature, `top_p`, presence penalty, and frequency penalty are accepted but
ignored in thinking mode; they are not useful calibration dials there.

### Response and usage shape

Thinking streams separately from visible content. The significant response
shape observed was:

```json
{
  "choices": [{
    "delta": {"reasoning_content": "...", "content": null},
    "finish_reason": null
  }]
}
```

followed by visible `delta.content` events and a terminal usage event:

```json
{
  "usage": {
    "prompt_tokens": 2336,
    "completion_tokens": 374,
    "total_tokens": 2710,
    "prompt_tokens_details": {"cached_tokens": 0},
    "completion_tokens_details": {"reasoning_tokens": 355},
    "prompt_cache_hit_tokens": 0,
    "prompt_cache_miss_tokens": 2336
  }
}
```

`completion_tokens` includes reasoning. Visible content is therefore
`completion_tokens - completion_tokens_details.reasoning_tokens`. Cache hit
and miss fields are directly usable for billing. A minimal default-thinking
probe at `max_tokens: 1` returned one reasoning token, no visible content,
and `finish_reason: "length"`; the explicit-disabled equivalent returned
visible `WAIT` and no reasoning-token detail. The same logical prompt was
reported as 134 prompt tokens with thinking and 55 with thinking disabled,
showing hidden mode overhead.

### Output budget and JSON mode

The provider advertises a 1M-token context and up to 384K output. `max_tokens`
is one shared completion budget for reasoning plus visible output; it is not a
visible-answer budget. This is why an 8,192 setting can yield no answer even
when the requested implementation is under 1,000 tokens.

JSON mode is selected with `"response_format":{"type":"json_object"}`.
The prompt must explicitly request JSON and should include an example. The
current documentation warns that JSON mode can otherwise emit empty content.
Tool calls and JSON mode are supported on the OpenAI-compatible path; the
Responses API is currently advertised for Flash, not Pro.

The documentation consulted was DeepSeek's current
[Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode),
[JSON Output](https://api-docs.deepseek.com/guides/json_mode), and live model
catalog, all fetched on 2026-08-01. Live probes, rather than older model
memory, are the authority for the findings above.

## Recommended Seon dials

Do not apply these in this research commit; the orchestrator owns the reviewed
configuration change.

```clojure
:seon.config.ai/model "deepseek-v4-flash"
:seon.config.ai/thinking :disabled
:seon.config.ai/max-tokens 65536
:seon.config.ai/timeout-ms 180000
```

- **Model:** the accepted live slug is `deepseek-v4-flash`. Provenance: model
  catalog plus successful discovery, matrix, and live Seon calls.
- **Thinking:** disable it for ordinary run-loop turns. All nine non-thinking
  calls returned structurally complete output. Against high thinking, the
  task medians were 2.05× faster and 2.59× cheaper for plans, 7.72× faster and
  8.30× cheaper for code, and 2.66× faster and 5.77× cheaper for dispositions.
  Enable `:low`, `:high`, or `:max` deliberately for work whose quality gate
  warrants it; the next calibration should score answer quality before making
  adaptive selection automatic.
- **Maximum tokens:** retain 65,536. It is the smallest value actually proven
  across this matrix after 8,192 failed. The observed maximum was 14,279 total
  completion tokens, including 13,340 reasoning tokens; 16,384 would leave
  only 14.7% headroom over that stochastic sample and was not tested.
- **Timeout:** use 180,000 ms. Flash high reached 103.817 s and Pro reached
  108.783 s, so the current 60,000 ms value rejects otherwise successful
  calls. A 120,000 ms deadline would leave little spread allowance. This is a
  legitimate backstop around unobservable remote HTTP state, not an internal
  readiness clock. Non-thinking Flash itself stayed below 11.535 s.

## Live descriptor proof through Seon

At 23:10 UTC, scratch cluster `flash-v4-cal-0801` booted on the current source
with a sparse overlay selecting `deepseek-v4-flash`, `max-tokens 65536`, and
`timeout-ms 180000`. The proof crossed the real web and provider boundaries:

1. `POST /agent/root/message` returned HTTP 204.
2. `GET /feed/root` remained an SSE stream and emitted Datastar patches for
   the inbound message, running state, and terminal database-derived state.
3. The provider attempt recorded model `deepseek-v4-flash`, endpoint
   `/chat/completions`, `finish-reason "stop"`, 2,336 prompt tokens, 374
   completion tokens, and 355 reasoning tokens.
4. The run opened at 23:10:05.792Z and closed at 23:10:10.919Z. Its durable
   form source was exactly:

   ```clojure
   ;; Flash live proof: acknowledged and completing.
   (my.run/complete "flash live proof")
   ```

5. The durable eval result was `{:my.run/disposition :completed,
   :my.run/result "flash live proof"}`.

This proves request construction, provider streaming, the SSE render path,
reply reading/evaluation, and the settled database result together. A separate
pre-existing `seon.render.block/data-prose` invalid-output diagnostic appeared
on the namespace page; it did not affect the provider attempt, stream, run, or
settled disposition and is outside this calibration's owner.

## Method and reproducibility

The benchmark harness and raw SSE/JSON artifacts are in the repository-local,
gitignored `tmp/flash-bench/` directory. `matrix-65536/` contains 27 final run
records: three task shapes × three configurations × three repetitions. Calls
were strictly serial, one benchmark at a time. The API key came from the
environment, was written only to a mode-0600 temporary curl header file, and
was neither printed nor committed.

TTFT is request start to the first non-empty reasoning, content, or tool-call
delta. Thinking time is first reasoning delta through first visible content.
Total runtime is request start through terminal usage. Output tok/s divides
derived visible-content tokens by visible-content delivery duration. Costs use
the response's cache hit/miss and completion counts, with reasoning billed as
output because it is included in `completion_tokens`.

The three prompts represented a plan-style reply, a portable Clojure code
generation, and a short wait disposition. Every failure was diagnosed before
another call. The only truncated paid pilots were the deliberate 4,096 and
8,192 budget falsifiers; no final-matrix call was retried or discarded.

## Surprises

- Default thinking can consume the entire output budget without producing a
  visible token. The older 8,192 calibration was not merely conservative; it
  was functionally invalid for the representative code task.
- Flash's visible-token generator was faster with high thinking than without
  it on plan and code answers, but the hidden reasoning delay overwhelmed that
  advantage in wall-clock time.
- Flash high reasoned longer than Pro on the largest code sample (13,340 versus
  Pro's maximum 6,075 reasoning tokens), yet still finished faster and cheaper
  at the task median.
- Sub-second TTFT did not predict useful-answer latency in thinking mode. TTFT
  measured the first reasoning token; the first visible code arrived 49–99 s
  later on the thinking configurations.
- Cache-hit input was effectively free relative to output at Flash's rates,
  so thinking-token volume—not repeated prompt context—dominated cost in the
  calibrated workload.
