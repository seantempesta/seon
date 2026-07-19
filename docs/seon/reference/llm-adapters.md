---
type: reference
status: active
tags: [reference, agent]
---

# LLM Adapters — providers, config, and usage

The Seon CLJS pod talks to hosted LLMs through two SDK-backed adapters and an
optional DiffusionGemma worker path. This is the complete reference for
configuring and calling them — including how to point the pod at a self-hosted
**Qwen3.6-35B-A3B** (or any OpenAI-compatible server), enable tool-calling, and
read back per-turn usage.

## The two adapters

| Adapter ns | SDK | Serves provider(s) | Wire |
|------------|-----|--------------------|------|
| `seon.ai.openai-compat` | `openai` (^6.42) | `:deepseek`, `:openai-compat` | OpenAI chat-completions |
| `seon.ai.anthropic` | `@anthropic-ai/sdk` (^0.104) | `:anthropic` | Anthropic Messages |

Both are vendored as read-only source under `reference-code/openai-node` and
`reference-code/anthropic-sdk-typescript`. The active provider is chosen by the
resolved database configuration; `seon.ai.dispatch/llm-fn` dispatches to the
matching adapter, falling back to a stub function when no API key resolves.

The adapter contract never changed across the migration:

```text
agent-adapter → (fn [:seon.ai/request]
                 → Promise< {:seon.ai/text "…" :seon.ai/raw <resp>}
                            | {:seon.ai/text "" :seon.ai/error <envelope>} >)
```

## Configuration surface

Every setting can come from an **environment variable** OR from the
`:seon.ai/config` singleton row in the database. **Env SEEDS, the DB OWNS**
(seed-once): at boot `seon.ai/sync!` writes the row from the `SEON_AI_*`
vars ONLY when the row is unconfigured (nil/`{}`); a row with ≥1 config
attr is left untouched — env is ignored and runtime switches persist
across boots. Nothing is ever retracted by sync. A runtime `transact!`
against the row takes effect on the **next call** (read per-call, no
cache, no restart). Consequence: to change a value on an EXISTING
deployment, transact the row (or wipe it) — editing the env alone does
nothing after first boot.

**Precedence for every setting:** explicit request opt → agent entity override
→ config row → shipped default.

| Env var | Config-row attr | Type | Applies to | Notes |
|---------|-----------------|------|------------|-------|
| `SEON_AI_PROVIDER` | `:seon.ai/provider` | `deepseek`\|`anthropic`\|`openai-compat` | all | default `deepseek` |
| `SEON_AI_MODEL` | `:seon.ai/model` | string | all | per-provider default below |
| `SEON_AI_MAX_TOKENS` | `:seon.ai/max-tokens` | int | all | |
| `SEON_AI_TIMEOUT_MS` | `:seon.ai/timeout-ms` | int | all | wall-clock; default `60000` |
| `SEON_AI_THINKING` | `:seon.ai/thinking` | `false`\|`true`\|`high`\|`max`\|… | all | see Thinking |
| `SEON_AI_TEMPERATURE` | `:seon.ai/temperature` | double | deepseek, openai-compat | **ignored by anthropic** (sampling params 400 on Opus 4.7+/Fable) |
| `SEON_AI_BASE_URL` | `:seon.ai/base-url` | string | openai-compat | the `/v1` ROOT (preferred) — see baseURL |
| `SEON_AI_API_KEY_ENV` | `:seon.ai/api-key-env` | string | deepseek, openai-compat | NAME of the env var holding the key |
| `SEON_AI_EXTRA_BODY` | `:seon.ai/extra-body-edn` | EDN-map string | all | extra request fields for the agent loop — see Extra request fields |
| `SEON_AI_API_KEY` | — (never stored) | string | all | direct key fallback |
| `DEEPSEEK_API_KEY` | — (never stored) | string | deepseek | shipped deepseek default key |
| `ANTHROPIC_API_KEY` | — (never stored) | string | anthropic | the anthropic key |

API keys are **read from `process.env` at call time and never transacted.**

Three settings have no env var and are per-call / runtime-transact only (they
also accept a config-row default): `:seon.ai/tools`, `:seon.ai/tool-choice`,
`:seon.ai/extra-body` (see below).

### API-key resolution order

OpenAI-compatible path (`:deepseek` / `:openai-compat`):

1. the env var **named by** `:seon.ai/api-key-env` (`SEON_AI_API_KEY_ENV`), if set;
2. `DEEPSEEK_API_KEY` — only when the provider is `:deepseek`;
3. `SEON_AI_API_KEY`.

Anthropic path: `ANTHROPIC_API_KEY`.

If none resolve, the pod uses the stub llm-fn (no calls) rather than erroring.

### baseURL reconciliation (openai-compat)

Set `SEON_AI_BASE_URL` to the **`/v1` root** — e.g. `http://localhost:8000/v1`.
The SDK appends `/chat/completions` itself. The **legacy full
chat-completions URL** (`http://localhost:8000/v1/chat/completions`) is still
accepted — the adapter strips a trailing `/chat/completions` (or
`/completions`) automatically. The `/v1` root form is now preferred.

### Shipped defaults

| | `:deepseek` | `:anthropic` |
|---|---|---|
| model | `deepseek-v4-pro` | `claude-opus-4-8` |
| endpoint | `https://api.deepseek.com/v1` | `https://api.anthropic.com/v1/messages` (SDK-owned) |
| temperature | `0.7` | n/a (never sent) |
| max_tokens | `4096` | `16000` |
| timeout-ms | `60000` | `60000` |
| thinking | disabled unless turned on | adaptive when truthy |

`:openai-compat` has **no shipped endpoint or temperature** —
`SEON_AI_BASE_URL` is required (missing → a legible error envelope at call
time, never a throw). Sampling is omitted unless explicitly configured because
some compatible models fix those fields and reject supplied values.

### Thinking

- `:deepseek` always sends an explicit toggle: `{:thinking {:type "disabled"}}`
  unless `SEON_AI_THINKING` is truthy (`"true"` → enabled; `"high"`/`"max"` →
  enabled + that `reasoning_effort`).
- `:openai-compat` sends ONLY the STANDARD OpenAI param (fixed 2026-07-10):
  an effort string (`"minimal"`…`"xhigh"`) goes out as `reasoning_effort`;
  the vendor `:thinking` field is NEVER sent (strict gateways — Meta Model
  API, vLLM — HTTP-400 unknown params). `"true"` sends nothing (no standard
  wire form; reasoning models reason by default). For servers that gate
  reasoning differently (e.g. Qwen), use `:extra-body`.
- `:anthropic` maps any truthy thinking to `{:thinking {:type "adaptive"}}`;
  falsy omits the key entirely.

## Model catalog — the top models and their good configs (2026-07-19)

The one place that lists CURRENT recommended models per provider with the
config that actually works. Update this table when a provider ships or
deprecates a model — same discipline as code. ✔ = verified live from seon;
◇ = from provider reference, not yet driven live.

This catalog tracks every model Seon actively recommends, defaults to, or uses
as a maintained specialist. It deliberately does not mirror every vendor SKU:
adding a row means Seon has a concrete role and configuration for that model.
Each tracked entry must retain model id, provider/adapter, current price,
context, maximum output, reasoning/latency behavior, verification status,
recommendation, primary refresh link, and checked date. “Vendor” below means a
public primary source; “Seon” means a dated local measurement. An unverified
price never silently becomes a vendor fact.

Prices are snapshots, not configuration truth. Each hosted-provider section
carries a primary pricing link and an “as of” date; refresh the number from
that link before a large run. The durable decision is the relative role (daily
loop, planning, or specialist), which should be revalidated with Seon's Inspect
scorecard whenever model behavior or pricing changes.

### Hosted-model cost and role snapshot (2026-07-19)

USD per one million tokens. Input columns distinguish automatic prefix-cache
hits where the provider publishes a separate rate.

| Model | Cache hit in | Cache miss in | Output | Context | Max output | Evidence | Seon role |
|---|---:|---:|---:|---:|---:|---|---|
| `deepseek-v4-flash` | $0.0028 | $0.14 | $0.28 | 1M | 384K | Vendor price/limits; Seon ~68 tok/s | Cheapest fast worker / routine turns |
| `deepseek-v4-pro` | $0.003625 | $0.435 | $0.87 | 1M | 384K | Vendor price/limits; Seon ~43 tok/s | Default quality/cost baseline |
| `muse-spark-1.1` | unpublished | $1.25* | $4.25* | 1M | unpublished | Vendor existence/context; Seon transport | Experimental fast specialist pending task-success evaluation |
| `kimi-k3` | $0.30 | $3.00 | $15.00 | 1M | 1,048,576 | Vendor price/limits; two paid Seon calls | Selective long-horizon planning escalation |
| `claude-haiku-4-5` | $0.10 | $1.00 | $5.00 | 200K | see vendor | Vendor; not live in Seon | Quick specialist when Anthropic behavior matters |
| `claude-sonnet-5` | $0.20 | $2.00 | $10.00 | 1M | see vendor | Vendor; not live in Seon | Coding alternative during introductory pricing |
| `claude-opus-4-8` | $0.50 | $5.00 | $25.00 | 1M | see vendor | Vendor; not live in Seon | Hard cross-boundary coding and judgment |
| `claude-fable-5` | $1.00 | $10.00 | $50.00 | 1M | see vendor | Vendor; not live in Seon | Rarest hardest long-horizon work only |
| `Qwen/Qwen3.6-35B-A3B` | local | local | local | server-dependent | server-dependent | Configuration recipe | Self-hosted OpenAI-compatible baseline |
| DiffusionGemma | local / RunPod | local / RunPod | local / RunPod | backend-dependent | backend-dependent | Seon experimental measurements | Experimental local specialist, opt-in only |

`*` Muse Spark's $1.25/$4.25 preview rates came from the dated Seon research
capture's secondary source, not Meta's public announcement. Treat them as an
unverified budgeting estimate until an authenticated official pricing page is
captured.

At these rates, K3 costs about 6.9×/17.2× DeepSeek V4 Pro on uncached
input/output and 21.4×/53.6× V4 Flash. Cache behavior,
reasoning-token volume, retries, and task success can dominate sticker price,
so compare cost per successful task in `evals/scorecard.jsonl`, not only cost
per token.

### DeepSeek direct (`:deepseek` — the shipped default)

Price source: [DeepSeek models and pricing](https://api-docs.deepseek.com/quick_start/pricing),
checked 2026-07-19.

| Model | In/out $/M (cache-hit in) | Notes |
|---|---|---|
| ✔ `deepseek-v4-pro` (default) | $0.435 / $0.87 ($0.0036) | 1M context, 384K maximum output. Seon measured ~43 tok/s decode and TTFT ~1.3s on 2026-07-10 with thinking off. `SEON_AI_THINKING=true\|high` enables reasoning. |
| ✔ `deepseek-v4-flash` | $0.14 / $0.28 ($0.0028) | 1M context, 384K maximum output. Seon measured ~68 tok/s and TTFT ~1.0s on 2026-07-10. |

```bash
SEON_AI_PROVIDER=deepseek        # endpoint + DEEPSEEK_API_KEY defaults apply
DEEPSEEK_API_KEY=<key>
# SEON_AI_MODEL=deepseek-v4-flash   # optional; omit -> deepseek-v4-pro
```

- **Scheduled to retire 2026-07-24 15:59 UTC:** `deepseek-chat` and
  `deepseek-reasoner`, the legacy v4-flash slugs. Do not add new uses.

### Meta Model API (`:openai-compat`) — Muse Spark 1.1

Release source: [Meta's Muse Spark 1.1 announcement](https://ai.meta.com/blog/introducing-muse-spark-meta-model-api/).
Meta's public announcement establishes the model, preview, 1M context, and
agentic role, but does not publish its API slug, output limit, effort values, or
prices. The remaining values below are dated Seon transport evidence.

| Model | In/out $/M | Notes |
|---|---|---|
| ✔ `muse-spark-1.1` | $1.25 / $4.25, secondary-source estimate | Seon discovered the slug and endpoint live and measured ~900–1,060 tok/s, 3.9s TTFT, and 7.7s wall time at `minimal`. Two of three driven runs still ended with no forms, so this remains an experimental fast specialist rather than a proven daily executor. |

Full recipe, measured tables, gotchas:
`docs/prds/agent-ctx/research/meta-model-api-muse-spark-2026-07-10.md`.

### Moonshot AI (`:openai-compat`) — Kimi K3

Price source: [Kimi K3 pricing](https://platform.kimi.ai/docs/pricing/chat-k3),
checked 2026-07-19. Capability/config source:
[Kimi K3 quickstart](https://platform.kimi.ai/docs/guide/kimi-k3-quickstart).

| Model | Context | Completion limit | Notes |
|---|---:|---:|---|
| ✔ `kimi-k3` | 1M | 131,072 default; 1,048,576 max | Flagship long-horizon coding/knowledge model. Thinking is always enabled; as of 2026-07-19, `reasoning_effort` accepts only `max` (also the default). Sampling values are fixed, so omit temperature/top-p rather than sending them. Automatic prefix caching is available. The check means live single-response transport, not full task-quality graduation. |

Kimi uses the conventional `MOONSHOT_API_KEY` name and the existing generic
adapter; no Kimi-specific provider or retry path is needed:

```bash
export MOONSHOT_API_KEY=<key>
export SEON_AI_PROVIDER=openai-compat
export SEON_AI_BASE_URL=https://api.moonshot.ai/v1
export SEON_AI_MODEL=kimi-k3
export SEON_AI_API_KEY_ENV=MOONSHOT_API_KEY
```

Leave `SEON_AI_THINKING` unset or `false`. For `:openai-compat`, that means
“omit the reasoning field,” not “disable model reasoning,” so K3 applies its
required `max` default. Setting `max` produces the same effort and is not a
latency optimization. Lower effort levels are announced but not yet available;
there is currently no supported K3 dial that trades quality for lower latency.
For ordinary low-latency turns, keep a faster model as the cluster default and
route only long-horizon agents to K3.

Seon's paid 2026-07-19 planning probes establish the actual latency/headroom
tradeoff. A 4,096-token cap took 107.17 seconds, spent 4,093 reasoning tokens,
returned no visible answer, and cost about $0.062. An 8,192-token cap took
47.18 seconds, used 975 reasoning plus 1,106 visible tokens, stopped normally,
and cost about $0.017. The second answer was useful but still invented an
unavailable namespace and misplaced tests. K3 therefore needs at least 8K
completion headroom, retrieved real contracts, behavioral verification, and a
per-run cost ceiling; it is not a blind execution model. Full evidence lives in
`docs/prds/generate-code/research/design-seam-audit-2026-07-19.md`.

A third isolated-cluster compatibility probe used the shipped `:planning`
variant with the reasoning field omitted and a deliberately trivial one-form
request. It completed in 13.30 seconds with 1,809 input tokens, 2 completion
tokens, one successful attempt, and a successful eval result of `42`; estimated
cache-miss cost was $0.0055. This establishes the low-complexity floor, not a
planning-task latency promise: K3 may still spend substantial reasoning tokens
on difficult requests, as the two planning probes above demonstrate.

The current adapter sends the deprecated-but-accepted OpenAI `max_tokens`
field, while K3 documents `max_completion_tokens`. This is recorded
compatibility debt, not a reason to change the shared field blindly because
DeepSeek still consumes `max_tokens`. K3 also requires the complete assistant
message, including reasoning content, for tool-call continuation. Seon's
single-response path is verified, but the generic adapter intentionally drops
reasoning content from agent-visible text and the agent loop does not yet prove
K3 multi-turn tool continuation. Do not claim that stronger compatibility from
the send/parse-only tool tests.

The useful mixed-cost topology in one cluster is therefore **DeepSeek globally,
Kimi for selected planning agents**. The shipped manifest names this sparse
variant once, and every birth function accepts the request-only selector:

```clojure
(seon.agent/delegate!
  {:seon.config/model-variant :planning
   :seon.agent/purpose "Plan a cross-namespace implementation."
   :seon.agent.message/content "Produce the implementation plan and code."})
```

The selector is not stored. Its ordinary `:seon.ai/agent-*` values are copied
onto the new agent in the atomic birth transaction. Unknown names allocate
nothing, and a selector cannot silently retune an existing namespace resident.
The database stores only `MOONSHOT_API_KEY`, the variable's name; the adapter
reads its secret from the process environment at call time. A Muse agent can
simultaneously carry its own endpoint, model, and credential variable; neither
agent changes the other or the global default. Directly transacting those
ordinary attributes remains the explicit way to retune an existing agent.

### Anthropic (`:anthropic`)

Price source: [Claude API pricing](https://docs.anthropic.com/en/docs/about-claude/pricing),
checked 2026-07-19. Cache-hit prices and context sizes are included in the
cross-provider table above. Sonnet 5's $2/$10 introductory price ends after
2026-08-31; its published standard price is $3/$15.

| Model | In/out $/M | Notes |
|---|---|---|
| ◇ `claude-opus-4-8` (our default) | $5 / $25 | The default Opus tier; adaptive thinking via `SEON_AI_THINKING=true`. Sampling params NOT sent (400 on 4.7+) — the adapter already omits temperature. |
| ◇ `claude-sonnet-5` | $3 / $15 ($2/$10 intro through 2026-08-31) | Near-Opus coding/agentic at Sonnet cost; new tokenizer (~30% more tokens/text). |
| ◇ `claude-fable-5` | $10 / $50 | Hardest long-horizon work only. Thinking ALWAYS on (explicit disable 400s — our truthy→adaptive mapping is compatible; never send a disable). Requires 30-day retention org config. |
| ◇ `claude-haiku-4-5` | $1 / $5 | Quick cheap calls; 200K ctx. |

```bash
SEON_AI_PROVIDER=anthropic
ANTHROPIC_API_KEY=<key>
# SEON_AI_MODEL=claude-sonnet-5     # optional; omit -> claude-opus-4-8
```

### Local / self-hosted (`:openai-compat` / `:diffusiongemma`)

- **Qwen3.6-35B-A3B via vLLM/SGLang** — see the serving section below.
- **DiffusionGemma** (`:diffusiongemma`) — RunPod or the local MLX worker
  (`~/ml/diffusion-gemma`, ~120 tok/s on M-series); see the repo CLAUDE.md
  §DiffusionGemma.

### Per-agent routing

Every non-secret provider field has a per-agent mirror:
`agent-provider`, `agent-model`, `agent-temperature`, `agent-max-tokens`,
`agent-thinking`, `agent-timeout-ms`, `agent-base-url`, `agent-api-key-env`,
`agent-dg-backend`, and `agent-extra-body-edn`; `agent-max-retries` controls the
turn retry policy. Absent or `:inherit` values fall through to the global row.
These attributes can be transacted onto an existing agent, supplied through
the manifest's ordinary/root agent context for atomic birth, or included in a
per-mint context override. Prefer named birth variants when the same role is
reused:

```clojure
:seon.config/model-variants
{:planning {:seon.ai/agent-provider :openai-compat
            :seon.ai/agent-model "kimi-k3"
            :seon.ai/agent-base-url "https://api.moonshot.ai/v1"
            :seon.ai/agent-api-key-env "MOONSHOT_API_KEY"}
 :execution {:seon.ai/agent-provider :deepseek
             :seon.ai/agent-model "deepseek-v4-flash"
             :seon.ai/agent-thinking "false"}}
```

Pass `:seon.config/model-variant :planning` to `create!`, `mint!`, `start!`, or
`delegate!`. The name is resolved from the same acquired configuration on each
stale-database retry, while only the selected agent attributes persist.

## Serving Qwen3.6-35B-A3B (or any OpenAI-compatible server)

Qwen3.6-35B-A3B (35B total / 3B active MoE) rides the `:openai-compat` path —
no Qwen-specific code. Serve it with reasoning enabled:

```bash
# vLLM
vllm serve Qwen/Qwen3.6-35B-A3B --reasoning-parser qwen3
# → OpenAI-compatible at http://localhost:8000/v1

# SGLang
python -m sglang.launch_server --model-path Qwen/Qwen3.6-35B-A3B --reasoning-parser qwen3
# → OpenAI-compatible at http://localhost:30000/v1
```

Point the pod at it:

```bash
export SEON_AI_PROVIDER=openai-compat
export SEON_AI_BASE_URL=http://localhost:8000/v1     # /v1 root
export SEON_AI_MODEL=Qwen/Qwen3.6-35B-A3B
export SEON_AI_API_KEY=EMPTY                          # or whatever the server requires
```

**Toggling Qwen's thinking mode** is a `chat_template_kwargs` body field, which
Seon passes through generically via extra-body (NOT a Seon-modeled knob). For
the **agent turn loop** (which builds the adapter with no per-call opts), the
data-only door is the env var / config row — an EDN-map STRING:

```bash
export SEON_AI_EXTRA_BODY='{:chat_template_kwargs {:enable_thinking false}}'
```

equivalently, transact the row once (note: the stored attr is the EDN string
`:seon.ai/extra-body-edn`, NOT a map — datahike can't store a raw map):

```clojure
(seon.db/transact!
  {:seon.db/tx-data
   [{:seon.ai/id "config"
     :seon.ai/extra-body-edn "{:chat_template_kwargs {:enable_thinking false}}"}]})
```

For a **direct (non-loop) call**, pass the decoded map as a per-call opt
(which wins over the row): `{:seon.ai/extra-body {:chat_template_kwargs {:enable_thinking false}}}`.

**Reasoning separation depends on the server.** With `--reasoning-parser qwen3`
(a dedicated server), Qwen's reasoning tokens arrive in a separate
`reasoning_content` field; the adapter drops them from `:seon.ai/text` (so the
agent loop never parses them as code) and keeps them in metadata. **A server
WITHOUT the reasoning parser does not separate them** — with thinking on, the
reasoning text leaks straight into `content` (and thus into `:seon.ai/text`).
On such a shared endpoint the `reasoning_content`-drop is a no-op, and
`:extra-body {:chat_template_kwargs {:enable_thinking false}}` is **mandatory,
not optional**, to keep reasoning out of the reply.

## Tool / function calling (default off)

Pass OpenAI-format tool definitions and a tool-choice; they are included in the
request only when present. Returned `tool_calls` surface as
`:seon.ai/tool-calls` on the result (and inside `:seon.ai/raw`). The default
emit-and-eval loop is unaffected when you pass no tools.

```clojure
(seon.ai.openai-compat/complete
  {:seon.ai/ctx         "what's the weather in Paris?"
   :seon.ai/tools       [{:type "function"
                          :function {:name "get_weather"
                                     :description "Current temp for a city."
                                     :parameters {:type "object"
                                                  :properties {:city {:type "string"}}
                                                  :required ["city"]}}}]
   :seon.ai/tool-choice "auto"})
;; → {:seon.ai/text "…" :seon.ai/tool-calls [{...}] :seon.ai/usage {...} :seon.ai/raw …}
```

`:seon.ai/tools` / `:seon.ai/tool-choice` can also be set as config-row defaults.

## Extra request fields (`:seon.ai/extra-body`)

Any map under `:seon.ai/extra-body` is **merged into the request params (the
1st arg)** — openai-node passes unknown top-level params through to the wire
verbatim. Use it for per-server knobs Seon doesn't model (`chat_template_kwargs`,
custom sampling, routing hints).

Two ways to set it:

- **Per-call opt** (`:seon.ai/extra-body <map>`) — wins; for direct `complete`
  calls. Unreachable from the agent turn loop (it builds the adapter with no
  opts).
- **Config row / env** — the data-only door for the loop. Store the EDN-map
  STRING under `:seon.ai/extra-body-edn` (env `SEON_AI_EXTRA_BODY`); a raw map
  is not datahike-storable, so the row holds the string and the adapter decodes
  it (`seon.ai/config-extra-body`) per call. `:seon.ai/tools` / `:seon.ai/tool-choice`
  remain per-call-opt only.

> Do **not** use the SDK's 2nd-arg request-options `{body: …}` for this — in
> openai-node `options.body` **replaces** the request body (it does not merge),
> which drops `model`/`messages` and 400s every call. The Node passthrough is
> inlining into params, not the Python `extra_body` and not `options.body`.

## Streaming

Both adapters **request a stream** (`stream_options:{include_usage:true}` for
openai-compat) and the SDK assembles it into one structured object before the
adapter returns. This is a transport/robustness change — the agent loop still
receives one complete `{:text …}` and parses whole forms. There is no
token-by-token surface to consumers.

## Provider metadata (#25)

On every successful call the result carries:

- `:seon.ai/usage` — the provider's usage map (always set on success).
- `:seon.ai/provider-fields` — every unrecognized top-level response field
  (governance scores, cost ledgers, cache stats, …), preserved open-world.

These are also **persisted per turn** on the turn entity:

- `:seon.agent.turn/llm-usage` (map)
- `:seon.agent.turn/llm-meta` (EDN string of the provider-fields)

so spend/metadata is queryable as datoms.

## Result + error contract

Success: `{:text "…" :seon.ai/raw <full response map>}` (plus
`:seon.ai/usage`, `:seon.ai/tool-calls`, `:seon.ai/provider-fields`,
`:seon.ai.openai-compat/finish-reason` as applicable).

Failure: `{:text "" :seon.ai/error <envelope>}` — never a rejected Promise.
The envelope:

| Key | Meaning |
|-----|---------|
| `:seon.ai/msg` | human-readable message (always present) |
| `:seon.ai/status` | HTTP status — set on 4xx/5xx (processing error, not retried) |
| `:seon.ai/timeout?` | `true` on wall-clock timeout / abort (already burned the budget) |
| `:seon.ai/transport?` | `true` when the connection failed before any status — the **one retryable class** |
| `:seon.ai/raw-body` | raw body when a response couldn't be parsed |

The agent loop (`seon.agent/call-llm!`) retries **once** on a `:transport?`
error and records `:seon.agent.turn/llm-retries`. The SDK clients are
constructed with `maxRetries: 0`, so the agent loop is the single retry
authority. Anthropic refusals (`stop_reason "refusal"`) become a legible
`:seon.ai/error` envelope.

## Direct calls (REPL / debugging)

```clojure
;; OpenAI-compatible (deepseek / openai-compat — provider from config/env):
(seon.ai.openai-compat/complete {:seon.ai/ctx "say hi"})

;; Anthropic:
(seon.ai.anthropic/complete {:seon.ai/ctx "say hi"})

;; Inspect exactly what goes over the wire WITHOUT calling:
(seon.ai.openai-compat/request-params {:seon.ai/ctx "hi"})
```

Override per call with any of `:seon.ai/model`, `:seon.ai/temperature`
(openai-compat), `:seon.ai/max-tokens`, `:seon.ai/system-prompt`,
`:seon.ai/tools`, `:seon.ai/tool-choice`, `:seon.ai/extra-body`.

## Verifying a deployment

1. Set the env vars, boot the pod, confirm the provider:
   `(seon.ai/provider)` → your provider keyword.
2. Confirm a key resolves: `(seon.ai.openai-compat/api-key-configured?)` (or
   `ANTHROPIC_API_KEY` for anthropic).
3. Smoke a completion: `(seon.ai.openai-compat/complete {:seon.ai/ctx "say hi"})`
   → `{:seon.ai/text "…" :seon.ai/usage {…}}`, no `:seon.ai/error`.
4. Qwen extra-body: confirm the server log shows `chat_template_kwargs` and
   reasoning is suppressed when you set `{:enable_thinking false}`.
5. Tool-calling: send a `:seon.ai/tools` request → `:seon.ai/tool-calls` on the
   result.
6. Induced timeout: `SEON_AI_TIMEOUT_MS=1` → `:seon.ai/error` with
   `:seon.ai/timeout? true`. Unreachable host → `:seon.ai/transport? true`.
7. Per-turn metadata: run a real turn, then pull the turn entity →
   `:seon.agent.turn/llm-usage` populated, `:seon.agent.turn/llm-meta` readable EDN.
