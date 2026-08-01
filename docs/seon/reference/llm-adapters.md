---
type: reference
status: active
tags: [reference, agent]
---

# LLM Adapters — providers, config, and usage

Seon talks to hosted LLMs through two portable wire cores and their native
transport leaves. Optional DiffusionGemma and typeahead workers remain a
separate pod-only local-worker mechanism. This is the complete reference for
configuring and calling them — including how to point the pod at a self-hosted
**Qwen3.6-35B-A3B** (or any OpenAI-compatible server), enable tool-calling, and
read back per-turn usage.

## The two hosted wire cores

| Portable core | Native leaf | Serves provider(s) | Wire |
|------------|-----|--------------------|------|
| `seon.ai.openai-compat.core` | Node `openai` (^6.42) or JVM HTTP | `:deepseek`, `:kimi`, `:zai`, `:openrouter`, `:gemini`, `:openai-compat` | OpenAI Chat Completions |
| `seon.ai.anthropic.core` | `@anthropic-ai/sdk` (^0.104) or JVM HTTP | `:anthropic` | Anthropic Messages |

Both are vendored as read-only source under `reference-code/openai-node` and
`reference-code/anthropic-sdk-typescript`. The active provider is chosen by the
resolved database configuration. Hosted provider identity selects a component
descriptor row, and dispatch uses only its `:seon.ai.provider/adapter-core`.
Missing or duplicate rows are configuration errors rather than provider-name
fallbacks.

### Two mechanisms, explicitly

The locality boundary is settled:

- **Hosted wire descriptors** are database-owned component rows interpreted by
  the fixed `:openai-compat` and `:anthropic` portable cores. Adding a compatible
  hosted provider is one descriptor row, one catalog entry, and qualification
  evidence.
- **Local-worker adapters** are the compiled `:diffusiongemma` and `:typeahead`
  provider registrations. They remain pod-only, experimental, and
  owner-preserved under the explicit D12 development preload door. They do not
  receive hosted descriptor rows, and the JVM HTTP leaf never gains
  local-worker dispatch arms.

This is not two competing hosted dispatch paths. The compiled local-worker
registry exists because those providers run a different step/job protocol, not
an OpenAI or Anthropic wire protocol. See the
[D12 provider registry ruling](../../prds/sci-execution-runtime/specs/ns-1b-provider-registry.md)
and the
[HTTP portability boundary](../../prds/sci-execution-runtime/research/llm-http-io-design-2026-07-23.md).

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

Generation fields exposed by the closed request schema, such as model,
temperature, maximum output, tools, and extra body, resolve explicit request
option → agent entity override → config row → shipped default. Routing and
transport fields—provider, endpoint, credential-variable name, timeout,
backend, and retry policy—are database-owned and resolve agent entity override
→ config row → shipped default; they are not arbitrary per-call options.

| Env var | Config-row attr | Type | Applies to | Notes |
|---------|-----------------|------|------------|-------|
| `SEON_AI_PROVIDER` | `:seon.ai/provider` | `deepseek`\|`kimi`\|`zai`\|`openrouter`\|`gemini`\|`anthropic`\|`openai-compat` | all | default `deepseek` |
| `SEON_AI_MODEL` | `:seon.ai/model` | string | all | per-provider default below |
| `SEON_AI_MAX_TOKENS` | `:seon.ai/max-tokens` | int | all | |
| `SEON_AI_COMPLETION_LIMIT_FIELD` | `:seon.ai/completion-limit-field` | `max-tokens`\|`max-completion-tokens` | openai-compat | wire name for the output cap; default `max-tokens` |
| `SEON_AI_TIMEOUT_MS` | `:seon.ai/timeout-ms` | int | all | wall-clock; default `60000` |
| — | `:seon.config.ai/thinking` | `:disabled`\|`:low`\|`:high`\|`:max` | fresh DeepSeek | optional database fact; absence leaves the provider default untouched |
| `SEON_AI_THINKING` | — | — | deleted pod only | no fresh runtime environment-variable path; use a typed config manifest/overlay |
| `SEON_AI_TEMPERATURE` | — | — | deleted pod only | fresh Seon sends no sampling controls; DeepSeek silently ignores them in thinking mode |
| `SEON_AI_BASE_URL` | `:seon.ai/base-url` | string | openai-compat | the `/v1` ROOT (preferred) — see baseURL |
| `SEON_AI_API_KEY_ENV` | `:seon.ai/api-key-env` | string | deepseek, openai-compat | NAME of the env var holding the key |
| `SEON_AI_EXTRA_BODY` | `:seon.ai/extra-body-edn` | EDN-map string | all | extra request fields for the agent loop — see Extra request fields |
| `SEON_AI_API_KEY` | — (never stored) | string | all | direct key fallback |
| `DEEPSEEK_API_KEY` | — (never stored) | string | deepseek | shipped deepseek default key |
| `ANTHROPIC_API_KEY` | — (never stored) | string | anthropic | the anthropic key |

API keys are **read from `process.env` at call time and never transacted.** If
a local `.env` or shell startup file contains a live key, keep that file
owner-only (`chmod 600 <file>`); repository examples contain names and
placeholders only.

`:seon.ai/tools` and `:seon.ai/tool-choice` are direct-call options only; they
have no environment variable or stored config-row form. `:seon.ai/extra-body`
is the decoded direct-call option, while `SEON_AI_EXTRA_BODY` and the stored
`:seon.ai/extra-body-edn` string provide its agent-loop configuration (see
below).

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
| model | `deepseek-v4-flash` | `claude-opus-4-8` |
| endpoint | `https://api.deepseek.com/v1` | `https://api.anthropic.com/v1/messages` (SDK-owned) |
| temperature | n/a (never sent) | n/a (never sent) |
| max_tokens | `65536` interim | `16000` |
| completion-limit-field | `max-tokens` | n/a |
| timeout-ms | `60000` | `60000` |
| thinking | field absent; v4 Flash defaults on/high | adaptive when truthy |

`:openai-compat` has **no shipped endpoint or temperature** —
`SEON_AI_BASE_URL` is required (missing → a legible error envelope at call
time, never a throw). Sampling is omitted unless explicitly configured because
some compatible models fix those fields and reject supplied values.

### Hosted descriptor catalog

Descriptor rows carry endpoint, authentication, completion-limit, thinking,
stream-usage, tool, response-format, and usage-field policy. Secrets are never
descriptor values: a row stores only the environment-variable name.

| Provider ID | Core | Base URL | Completion cap | Stream usage | Cached-token detail |
|---|---|---|---|---|---|
| `:deepseek` | `:openai-compat` | `https://api.deepseek.com` | `max_tokens` | request `include_usage` | direct `prompt_cache_hit_tokens`/`cached_tokens`, then `prompt_tokens_details.cached_tokens` |
| `:kimi` | `:openai-compat` | `https://api.moonshot.ai/v1` | `max_completion_tokens` | request `include_usage` | direct `cached_tokens`, then nested compatible detail |
| `:zai` | `:openai-compat` | `https://api.z.ai/api/paas/v4` | `max_tokens` | request `include_usage` | direct `cached_tokens`, then nested compatible detail |
| `:openrouter` | `:openai-compat` | `https://openrouter.ai/api/v1` | `max_tokens` | always in the final SSE chunk | `prompt_tokens_details.cached_tokens`; preserve `cache_write_tokens` |
| `:anthropic` | `:anthropic` | `https://api.anthropic.com/v1` | `max_tokens` | native start/delta events | direct `cache_read_input_tokens` |
| `:gemini` | `:openai-compat` | `https://generativelanguage.googleapis.com/v1beta/openai` | `max_tokens` | request `include_usage` | `prompt_tokens_details.cached_tokens` when supplied |

DeepSeek, Kimi, and Z.AI request fields are grounded in the maintained
LiteLLM source:
[DeepSeek](../../../reference-code/litellm-clj/src/litellm/providers/deepseek.clj),
[Kimi](../../../reference-code/litellm-clj/src/litellm/providers/kimi.clj),
[Z.AI](../../../reference-code/litellm-clj/src/litellm/providers/zai.clj), and
the shared
[compatible usage transform](../../../reference-code/litellm-clj/src/litellm/providers/openai_compatible.clj).
That shared transform proves direct `cached_tokens` and nested
`prompt_tokens_details.cached_tokens`; it does **not** prove DeepSeek's
`prompt_cache_hit_tokens` spelling.

The vendored OpenRouter streaming transform drops usage, so it is not the
authority for current behavior. The
[OpenRouter API reference](https://openrouter.ai/docs/api/reference/overview)
documents exactly one final choices-empty usage chunk and nested cached-token
detail; its current
[usage accounting guide](https://openrouter.ai/docs/cookbook/administration/usage-accounting)
states that `stream_options.include_usage` is now a deprecated no-op because
usage is always returned.

Gemini's native GenerateContent protocol remains a different protocol. The row
uses only Google's beta
[OpenAI compatibility surface](https://ai.google.dev/gemini-api/docs/openai).
A 2026-07-23 live qualification proved two complete streamed Clojure forms,
`data:` SSE framing, `[DONE]`, real cumulative usage, JSON-schema response
format, OpenAI tool calls, and bounded HTTP error status. Gemini attaches
cumulative usage to content chunks rather than emitting a choices-empty
usage-only chunk; the portable fold intentionally retains the newest usage map
independently of choices.

Z.AI permits only automatic tool choice. Its `do_sample`, `tool_stream`, and
`thinking.clear_thinking` fields remain explicit extra-body policy. Kimi's
thinking constraints are model data: K2.7 Code cannot disable thinking.

### Thinking

Fresh Seon's DeepSeek surface is one optional database-backed dial,
`:seon.config.ai/thinking`, projected onto the target as `:seon.ai/thinking`.
There is no `SEON_AI_THINKING` runtime read and no agent-local mirror today.

- absent → omit both wire fields. DeepSeek v4 Flash applies its documented
  default: thinking enabled at effort high;
- `:disabled` → `{"thinking":{"type":"disabled"}}`; and
- `:low`, `:high`, or `:max` → explicit enabled plus the matching
  `reasoning_effort` string.

The vendor's 2026-08-01 model mapping is requested low/high/xhigh/max → Flash
low/high/high/max and Pro high/high/max/max (the Pro low mapping was announced
to change in early August). Seon exposes low/high/max and sends the chosen
value directly; it never branches on a model name.

DeepSeek silently ignores `temperature`, `top_p`, `presence_penalty`, and
`frequency_penalty` in thinking mode. Fresh Seon sends none of them, so its
request and recorded config do not claim sampling control the provider says it
does not have. Response `reasoning_content` is a sibling of visible `content`;
the parser excludes it from reply text while retaining provider usage,
`completion_tokens_details.reasoning_tokens`, and `finish_reason`.

Authoritative contract and dated live evidence:
`../../prds/sci-execution-runtime/research/deepseek-thinking-mode-api-2026-08-01.md`
and
`../../prds/sci-execution-runtime/research/deepseek-thinking-live-proof-2026-08-01.md`.

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
| `deepseek-v4-flash` (version DeepSeek-V4-Flash-0731) | $0.0028 | $0.14 | $0.28 | 1M | 384K | Vendor price/limits; Seon ~68 tok/s (pre-0731) | THE DEFAULT (owner ruling 2026-08-01): all Seon turns |
| `deepseek-v4-pro` | $0.003625 | $0.435 | $0.87 | 1M | 384K | Vendor price/limits; Seon ~43 tok/s | Quality comparison reference only |
| `muse-spark-1.1` | unpublished | $1.25* | $4.25* | 1M | unpublished | Vendor existence/context; Seon transport | Experimental fast specialist pending task-success evaluation |
| `kimi-k3` | $0.30 | $3.00 | $15.00 | 1M | 1,048,576 | Vendor price/limits; three paid Seon calls | Selective long-horizon planning escalation |
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

At these rates, K3 costs about 21.4×/53.6× the default V4 Flash on
uncached input/output (6.9×/17.2× the V4 Pro reference). Cache behavior,
reasoning-token volume, retries, and task success can dominate sticker price,
so compare cost per successful task in `evals/scorecard.jsonl`, not only cost
per token.

### DeepSeek direct (`:deepseek` — the shipped default)

Price source: [DeepSeek models and pricing](https://api-docs.deepseek.com/quick_start/pricing),
checked 2026-07-19.

| Model | In/out $/M (cache-hit in) | Notes |
|---|---|---|
| ✔ `deepseek-v4-flash` (default, owner ruling 2026-08-01; version DeepSeek-V4-Flash-0731) | $0.14 / $0.28 ($0.0028) | 1M context, 384K maximum output, thinking DEFAULT-ON (version 0731 released 2026-07-31; calibration in `research/deepseek-v4-flash-calibration-2026-08-01.md`). |
| `deepseek-v4-pro` | $0.435 / $0.87 ($0.0036) | 1M context, 384K maximum output. Comparison reference only — not a Seon default. |

```bash
SEON_AI_PROVIDER=deepseek        # endpoint + DEEPSEEK_API_KEY defaults apply
DEEPSEEK_API_KEY=<key>
# SEON_AI_MODEL=deepseek-v4-pro   # optional comparison override; omit -> deepseek-v4-flash
```

- **Retired legacy slugs (gone since 2026-07-24):** `deepseek-chat` and
  `deepseek-reasoner`. Current identifiers, verified against the live
  `/models` endpoint 2026-08-01: `deepseek-v4-flash` / `deepseek-v4-pro`.
- Fresh Seon has no tool-call or provider-message continuation representation.
  Its maintained model path is one prompt to one completion, so it cannot yet
  claim thinking-mode tool continuation. The vendor contract requires every
  assistant tool-call message—including complete `reasoning_content` and
  non-null content—to be replayed in all subsequent requests. That acceptance
  contract is tracked in
  `../../prds/sci-execution-runtime/research/deepseek-thinking-mode-api-2026-08-01.md`;
  the live Flash endpoint was permissive when reasoning was omitted on the
  probe date, which is not permission to weaken the documented shape.

### Meta Model API (`:openai-compat`) — Muse Spark 1.1

Release source: [Meta's Muse Spark 1.1 announcement](https://ai.meta.com/blog/introducing-muse-spark-meta-model-api/).
Meta's public announcement establishes the model, preview, 1M context, and
agentic role, but does not publish its API slug, output limit, effort values, or
prices. The remaining values below are dated Seon transport evidence.

| Model | In/out $/M | Notes |
|---|---|---|
| ✔ `muse-spark-1.1` | $1.25 / $4.25, secondary-source estimate | Seon discovered the slug and endpoint live and measured ~900–1,060 tok/s, 3.9s TTFT, and 7.7s wall time at `minimal`. Two of three driven runs still ended with no forms, so this remains an experimental fast specialist rather than a proven daily executor. |

Full recipe, measured tables, gotchas:
`docs/prds/archive/agent-ctx/research/meta-model-api-muse-spark-2026-07-10.md`.

### Moonshot AI (`:openai-compat`) — Kimi K3

Price source: [Kimi K3 pricing](https://platform.kimi.ai/docs/pricing/chat-k3),
checked 2026-07-19. Capability/config source:
[Kimi K3 quickstart](https://platform.kimi.ai/docs/guide/kimi-k3-quickstart).

| Model | Context | Completion limit | Notes |
|---|---:|---:|---|
| ✔ `kimi-k3` | 1M | 131,072 default; 1,048,576 max | Flagship long-horizon coding/knowledge model. Thinking is always enabled; as of 2026-07-19, `reasoning_effort` accepts only `max` (also the default). Sampling values are fixed, so omit temperature/top-p rather than sending them. Automatic prefix caching is available. The check means live single-response transport, not full task-quality proof. |

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
tradeoff. A 4,096-token cap took 107.17 seconds, reported 4,093 completion
tokens (all reasoning), returned no visible answer, and cost about $0.062. An
8,192-token cap took 47.18 seconds and reported 1,106 completion tokens,
including 975 reasoning tokens; the remaining visible answer was roughly 131
tokens. It stopped normally and cost about $0.017. The second answer was useful
but still invented an
unavailable namespace and misplaced tests. K3 therefore needs at least 8K
completion headroom, retrieved real contracts, behavioral verification, and a
per-run cost ceiling; it is not a blind execution model. Full evidence lives in
`docs/prds/generate-code/research/design-seam-audit-2026-07-19.md`.

An earlier isolated-cluster probe completed a trivial one-form request in
13.30 seconds with 1,809 input tokens and 2 completion tokens. It established a
low-context transport floor, but predates the final batch and completion-field
contract and is not acceptance evidence for that contract.

The exact-artifact acceptance run at `6e3b741d` used a fresh
`kimi-k3-audit` database and the current shipped `:planning` variant. A normal
batch request completed in 11.22 seconds, stopped normally, and evaluated
`(+ 20 22)` to `42`. It used 32,551 input tokens and 40 completion tokens,
including 19 reasoning tokens: approximately $0.098 at the dated cache-miss
prices above. A separate agent-local 32-token cap completed in 11.36 seconds
with `finish_reason=length`, 32 completion tokens including 29 reasoning
tokens, no visible text, retained provider usage, and the expected nonretryable
truncation error. A config-free pod restart preserved both distinct agent
profiles and their complete attempt facts. This proves the current one-shot
contract and also makes context economics explicit: even a trivial K3 planning
turn costs nearly ten cents when the current development context contributes
about 32.5K prompt tokens. Retrieval and progressive context are cost controls,
not merely quality improvements.

The planning variant selects `:max-completion-tokens` as ordinary model
capability data, so K3 receives `max_completion_tokens` while DeepSeek retains
`max_tokens`; there is no model-name branch. K3 also requires the complete
assistant message, including reasoning content, for tool-call continuation.
Seon's single-response path is verified, but the generic adapter intentionally
drops reasoning content from agent-visible text and the agent loop does not yet
prove K3 multi-turn tool continuation. Do not claim that stronger compatibility
from the send/parse-only tool tests.

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

Fresh Seon has no per-agent provider or thinking override yet. The cluster's
descriptor is derived from its effective config facts at arm time. The active
roadmap requires future model configuration to be overridable at the agent or
situation without creating a second config mechanism, but that work is coupled
to agent modes and is not implemented. Names such as `:seon.ai/agent-thinking`
and `:seon.config/model-variants` describe deleted pod machinery and must not be
used as current APIs.

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

Fresh Seon exposes no tool definitions, tool results, assistant message
history, or `tool_calls` in its closed request/completion schemas. There is no
continuation path to configure or call. The old explicit-adapter example was
deleted with the pod and is not a supported debugging surface.

Any future tool continuation must add one faithful message representation at
the `seon.ai` owner. In thinking mode it must retain the complete assistant
message after a tool call and replay its `reasoning_content` in every later
request, while ordinary no-tool turns may omit reasoning. The open issue
`thinking-tool-continuations-have-no-faithful-request-shape` owns that missing
capability and its acceptance contract.

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

## Batch and streaming

Wire transport and reply evaluation are independent:

- `:seon.ai/wire-stream? true|false` chooses streaming or an ordinary response.
- `:seon.ai/reply-evaluation :first-form|:batch` chooses whether evaluation
  consumes one complete form or the complete parsed program.

First-form evaluation with wire streaming preserves the upstream abort and
explicitly estimated usage. Batch evaluation reads streaming to natural EOF,
retains provider usage, parses once, and evaluates every form. Partial display
is presentation-only and cannot affect transport, parsing, usage, or
evaluation.

> **Loud deprecation:** `:seon.config/repl-mode` is legacy compatibility
> vocabulary. `:stream` means exactly
> `{:seon.ai/wire-stream? true, :seon.ai/reply-evaluation :first-form}`;
> `:batch` means exactly
> `{:seon.ai/wire-stream? false, :seon.ai/reply-evaluation :batch}`. New
> configuration must set the two R36 facts directly. The legacy names must
> never be reinterpreted silently.

## Provider metadata (#25)

On every successful call the result carries:

- `:seon.ai/usage` — the provider's usage map when the provider supplies one.
- `:seon.ai/provider-fields` — every unrecognized top-level response field
  (governance scores, cost ledgers, cache stats, …), preserved open-world.

These are also **persisted per turn** on the turn entity:

- `:seon.agent.turn/llm-usage` (EDN string)
- `:seon.agent.turn/llm-meta` (EDN string of the provider-fields)

so spend/metadata is queryable as datoms.

Each `:seon.ai.attempt/*` component also retains its finish reason, explicit
truncation marker, provider usage EDN, configured completion-limit field, and
both applied timeout layers. Failed/truncated calls therefore remain visible
as cost and deadline evidence rather than disappearing from the turn.

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

The agent loop applies its bounded retry policy to transient transport, 429,
and 5xx failures and records `:seon.agent.turn/llm-retries`. Named variants can
lower that cap; the shipped planning/execution variants use one retry. The SDK
clients are constructed with `maxRetries: 0`, so the agent loop is the single
retry authority. Anthropic refusals (`stop_reason "refusal"`) become a legible
`:seon.ai/error` envelope.

## Direct calls (REPL / debugging)

Every adapter call requires one immutable `:seon.ai/config-resolution`. In the
running agent path, the execution boundary derives it from the config and agent
rows acquired at one database value. For a disposable adapter probe, construct
the same ordinary value explicitly:

```clojure
(def resolution
  (seon.ai/resolved-config-from-rows
    {:seon.ai/provider :openai-compat
     :seon.ai/model "kimi-k3"
     :seon.ai/base-url "https://api.moonshot.ai/v1"
     :seon.ai/api-key-env "MOONSHOT_API_KEY"}
    {}))

(seon.ai.openai-compat/complete
  {:seon.ai/ctx "Return one Clojure form that adds 20 and 22."
   :seon.ai/config-resolution resolution})

;; Inspect exactly what goes over the wire without calling:
(seon.ai.openai-compat/request-params
  {:seon.ai/ctx "hi"}
  resolution)
```

This literal construction is only a probe seam. Production turns use the
database rows captured by the execution runtime; do not replace that authority
with an environment-reading or process-local config cache. Agent-facing batch
evaluation awaits the whole returned Promise automatically; do not write a
bare top-level `(await ...)`, which is invalid outside a `^:async` function.

Override per call with any of `:seon.ai/model`, `:seon.ai/temperature`
(openai-compat), `:seon.ai/max-tokens`, `:seon.ai/system-prompt`,
`:seon.ai/tools`, `:seon.ai/tool-choice`, `:seon.ai/extra-body`.

## Verifying a deployment

1. Set the credential environment variable and select/apply the configuration,
   then boot the pod.
2. Pull the config row and target agent row from one database value and derive
   `resolution` with `seon.ai/resolved-config-from-rows`.
3. Confirm the non-secret projection under
   `[:seon.ai/resolved-config :seon.ai/provider]`, model, endpoint, and
   credential-variable name.
4. Confirm the credential resolves with
   `(seon.ai.openai-compat/api-key-configured? resolution)` (or the Anthropic
   equivalent).
5. Run the explicit adapter probe above or, preferably, launch one bounded
   agent turn and inspect its persisted `:seon.ai.attempt/*` evidence.
   → `{:seon.ai/text "…" :seon.ai/usage {…}}`, no `:seon.ai/error`.
6. Qwen extra-body: confirm the server log shows `chat_template_kwargs` and
   reasoning is suppressed when you set `{:enable_thinking false}`.
7. Tool-calling: send a `:seon.ai/tools` request → `:seon.ai/tool-calls` on the
   result.
8. Induced timeout: `SEON_AI_TIMEOUT_MS=1` → `:seon.ai/error` with
   `:seon.ai/timeout? true`. Unreachable host → `:seon.ai/transport? true`.
9. Per-turn metadata: run a real turn, then pull the turn entity →
   `:seon.agent.turn/llm-usage` populated, `:seon.agent.turn/llm-meta` readable EDN.
