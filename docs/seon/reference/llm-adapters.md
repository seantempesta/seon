---
type: reference
status: active
tags: [reference, agent]
---

# LLM Adapters — providers, config, and usage

The Seon CLJS pod talks to LLMs through two adapters, both on official Node
SDKs (migrated 2026-06-16). This is the complete reference for configuring and
calling them — including how to point the pod at a self-hosted
**Qwen3.6-35B-A3B** (or any OpenAI-compatible server), enable tool-calling, and
read back per-turn usage.

## The two adapters

| Adapter ns | SDK | Serves provider(s) | Wire |
|------------|-----|--------------------|------|
| `seon.ai.openai-compat` | `openai` (^6.42) | `:deepseek`, `:openai-compat` | OpenAI chat-completions |
| `seon.ai.anthropic` | `@anthropic-ai/sdk` (^0.104) | `:anthropic` | Anthropic Messages |

Both are vendored as read-only source under `reference-code/openai-node` and
`reference-code/anthropic-sdk-typescript`. The active provider is chosen by
`SEON_AI_PROVIDER` (see below); `seon.client/current-llm-fn` dispatches to the
matching adapter, falling back to a stub llm-fn when no API key resolves.

The adapter contract never changed across the migration:

```text
agent-adapter → (fn [ctx-text] → Promise< {:text "…" :seon.ai/raw <resp>}
                                          | {:text "" :seon.ai/error <envelope>} >)
```

## Configuration surface

Every setting can come from an **environment variable** OR from the
`:seon.ai/config` singleton row in the store. **Env owns the row**: at boot
`seon.ai/sync!` writes the row to match the `SEON_AI_*` vars (set → asserted,
unset → retracted), so booting without the env vars yields the shipped
defaults. A runtime `transact!` against the row takes effect on the **next
call** (read per-call, no cache, no restart) but is re-synced from env at the
next boot.

**Precedence for every setting:** explicit request opt → config row → shipped
default.

| Env var | Config-row attr | Type | Applies to | Notes |
|---------|-----------------|------|------------|-------|
| `SEON_AI_PROVIDER` | `:seon.ai/provider` | `deepseek`\|`anthropic`\|`openai-compat` | all | default `deepseek` |
| `SEON_AI_MODEL` | `:seon.ai/model` | string | all | per-provider default below |
| `SEON_AI_MAX_TOKENS` | `:seon.ai/max-tokens` | int | all | |
| `SEON_AI_TIMEOUT_MS` | `:seon.ai/timeout-ms` | int | all | wall-clock; default `60000` |
| `SEON_AI_THINKING` | `:seon.ai/thinking` | `false`\|`true`\|`high`\|`max`\|… | all | see Thinking |
| `SEON_AI_TEMPERATURE` | `:seon.ai/temperature` | double | openai-compat only | **ignored by anthropic** (sampling params 400 on Opus 4.7+/Fable) |
| `SEON_AI_BASE_URL` | `:seon.ai/base-url` | string | openai-compat | the `/v1` ROOT (preferred) — see baseURL |
| `SEON_AI_API_KEY_ENV` | `:seon.ai/api-key-env` | string | all | NAME of the env var holding the key |
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

`:openai-compat` has **no shipped endpoint** — `SEON_AI_BASE_URL` is required
(missing → a legible error envelope at call time, never a throw).

### Thinking

- `:deepseek` always sends an explicit toggle: `{:thinking {:type "disabled"}}`
  unless `SEON_AI_THINKING` is truthy (`"true"` → enabled; `"high"`/`"max"` →
  enabled + that `reasoning_effort`).
- `:openai-compat` sends the `:thinking` field **only when truthy** — absent /
  `"false"` sends nothing (graceful no-op on gateways that don't know it). For
  servers that gate reasoning differently (e.g. Qwen), use `:extra-body`.
- `:anthropic` maps any truthy thinking to `{:thinking {:type "adaptive"}}`;
  falsy omits the key entirely.

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
Seon passes through generically via `:extra-body` (NOT a Seon-modeled knob).
Disable thinking by setting the config row once:

```clojure
(seon.db/transact!
  {:seon.db/tx-data
   [{:seon.ai/id "config"
     :seon.ai/extra-body {:chat_template_kwargs {:enable_thinking false}}}]})
```

or per call: `{:seon.ai/extra-body {:chat_template_kwargs {:enable_thinking false}}}`.

Qwen's reasoning tokens arrive in `reasoning_content`; the adapter drops them
from `:seon.ai/text` (so the agent loop never parses them as code) and keeps
them in the response metadata.

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

Any map under `:seon.ai/extra-body` is merged into the request via the SDK's
2nd-arg request-options `{body: …}` (the Node SDK passthrough — **not** the
Python `extra_body`). Use it for per-server knobs Seon doesn't model
(`chat_template_kwargs`, custom sampling, routing hints). Per-call opt or config
row.

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
