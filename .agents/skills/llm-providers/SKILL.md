---
name: llm-providers
description: Verify or plan Seon's LLM provider request construction, prompt caching, streaming, reasoning fields, usage normalization, and database-backed provider descriptor rows. Load before changing seon.ai, an AI config fact, a provider endpoint/model, or the rendered-context/cache boundary.
---

# LLM providers

## Overview

Keep one `seon.ai` HTTP owner and represent hosted targets as provider descriptor
rows. Re-open the linked primary source before editing a row or wire shape. The
verified baseline and source inventory are in
`docs/prds/sci-execution-runtime/research/llm-provider-research-2026-08-03.md`.

## Working rules

- Read `src/seon/ai.clj:375-418,470-530,642-661,800-950`,
  `src/seon/cluster/loop.clj:1262-1334`, and
  `resources/seon/schema.edn:230-270,595-690` before changing the provider seam.
- Keep credentials outside the database. Descriptor rows name the environment
  variable; `seon.ai/credential` reads it only at the HTTP leaf
  (`src/seon/ai.clj:663-670`; `config/default.edn:185-191`).
- Keep retries and failover in Seon's attempt/disposition mechanism. The HTTP
  leaf performs one call (`src/seon/ai.clj:811-950`); do not add an SDK retry,
  circuit breaker, or second model registry.
- Capture and reuse the exact rendered prompt. The loop commits the capture
  before calling the provider and passes only that string as `:seon.ai/prompt`
  (`src/seon/cluster/loop.clj:1291-1334`;
  `resources/seon/schema.edn:803-825`).
- Do not claim cross-turn cache stability today. Ordering is deterministic, but
  rendered text starts with a changing database basis and ends with basis plus
  transaction time (`src/seon/render/walk.clj:595-625`;
  `src/seon/render.clj:293-307,386-391`). The acceptance boundary is in
  `docs/seon/issues/ai-context-bypasses-render-proc-retained-bytes.md`.
- Omit an unverified endpoint, model identifier, field, or default. A missing
  provider fact is a research boundary, not permission to infer a value.

## DeepSeek — current provider

Use `POST https://api.deepseek.com/chat/completions` with Bearer authentication.
The current contract accepts `deepseek-v4-flash`, `deepseek-v4-pro`,
`thinking.type`, `reasoning_effort`, `max_tokens`, and streaming
`delta.reasoning_content`
([DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)).
Seon's builder, reasoning fold, and usage normalization match this contract
(`src/seon/ai.clj:380-418,470-530,642-661`).

DeepSeek caching is automatic and prefix-based; send no explicit cache marker.
A hit requires a persisted prefix unit to match completely. Inspect
`usage.prompt_cache_hit_tokens` and `usage.prompt_cache_miss_tokens`; caching is
best effort
([DeepSeek context caching](https://api-docs.deepseek.com/guides/kv_cache/)).
Seon recognizes the hit field (`src/seon/ai.clj:642-661`).

The shipped, benchmark-backed row is authoritative
(`config/default.edn:145-198`):

```clojure
{:seon.config.ai/endpoint "https://api.deepseek.com/chat/completions"
 :seon.config.ai/model "deepseek-v4-flash"
 :seon.config.ai/max-tokens 65536
 :seon.config.ai/thinking :disabled
 :seon.config.ai/api-key-variable "DEEPSEEK_API_KEY"
 :seon.config.ai/timeout-ms 180000}
```

For planning agents, prefer a per-agent `:seon.config.ai/thinking :high` or
`:max` override only after quality/latency/cost calibration. DeepSeek documents
`high` as the default effort and model-specific effort mapping; Seon's shipped
default deliberately disables thinking for ordinary turns
(`resources/seon/schema.edn:615-629`; `config/default.edn:167-172`;
[DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)).

## Kimi K3 — planning candidate

Use `POST https://api.moonshot.ai/v1/chat/completions`, Bearer authentication,
and model `kimi-k3`. K3 always thinks; `reasoning_effort` accepts `low`, `high`,
and `max`, defaults to `max`, and the model has a one-million-token context
([Kimi Chat API](https://platform.kimi.ai/docs/api/chat),
[Kimi model selection](https://www.kimi.com/help/kimi-api/api-model-selection)).
Kimi streams SSE `data:` JSON, ends with `[DONE]`, uses OpenAI-compatible
content/reasoning deltas, and returns top-level usage when
`stream_options.include_usage` is true
([Kimi Chat API](https://platform.kimi.ai/docs/api/chat)). Seon's stream shape
matches those fields (`src/seon/ai.clj:470-530`).

Kimi automatically attempts to cache repeated initial context. No cache ID,
TTL, or extra parameter is required; keep the initial prefix stable. Optional
`prompt_cache_key` is recommended for multi-turn agents and should be a stable
session or task identity
([Kimi caching guidance](https://www.kimi.com/help/kimi-api/api-troubleshooting),
[Kimi Chat API](https://platform.kimi.ai/docs/api/chat)). Do not put a literal
session placeholder in cluster config: Seon's `extra-body-edn` is a static fact
(`resources/seon/schema.edn:674-681`).

Kimi deprecates `max_tokens` in favor of `max_completion_tokens`; Seon's
descriptor hard-wires `:seon.config.ai/max-tokens` to `max_tokens`
(`resources/seon/schema.edn:607-613`). This is a partial planning overlay, not a
complete descriptor or paid-call recommendation, until
`docs/seon/issues/provider-output-token-wire-key-is-hard-coded.md` is resolved:

```clojure
;; [TARGET] Provider facts are verified; output projection and Seon's
;; operational timeout are not settled.
{:seon.config.ai/endpoint "https://api.moonshot.ai/v1/chat/completions"
 :seon.config.ai/model "kimi-k3"
 :seon.config.ai/max-tokens 131072
 :seon.config.ai/thinking :max
 :seon.config.ai/api-key-variable "MOONSHOT_API_KEY"}
```

Kimi documents 131,072 as K3's default completion budget. It does not establish
that value as Seon's optimum; benchmark `:high` versus `:max`, output budget,
timeout, and cache-hit usage before shipping a row
([Kimi Chat API](https://platform.kimi.ai/docs/api/chat)).

## Anthropic — cache-contract reference

Anthropic's native Messages API is `POST https://api.anthropic.com/v1/messages`.
Direct API-key calls require `x-api-key`, `anthropic-version`, and
`content-type`; `system` is top-level rather than a system-role message
([Claude API overview](https://platform.claude.com/docs/en/api/overview)).
Current Seon emits Bearer authentication and OpenAI-style messages, so a native
Anthropic row is not representable (`src/seon/ai.clj:375-418`).

Anthropic caching is opt-in through `cache_control`: either one top-level field
for automatic breakpoint movement or explicit `cache_control` on content
blocks. The cached prefix order is tools, system, messages; place stable blocks
first. Verify hits with `cache_creation_input_tokens` and
`cache_read_input_tokens`
([Anthropic prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)).
This is not DeepSeek/Kimi automatic caching and must not become a
provider-neutral “cache enabled” boolean.

## Muse Spark 1.1 — research candidate

Verified only: Meta announced Muse Spark 1.1 as a multimodal reasoning model for
agentic tasks, exposed through the public-preview Meta Model API, with a
one-million-token context
([Meta announcement](https://ai.meta.com/blog/introducing-muse-spark-meta-model-api/)).
The announcement does not establish an endpoint, wire model ID, authentication
headers, caching controls, streaming events, reasoning fields, or usage schema.
Omit all of those rather than copying unofficial values.

No provider descriptor row is admissible yet because endpoint and model are
required facts (`resources/seon/schema.edn:247-250`). Re-verify Meta's primary
API reference when available, then add a `[TARGET]` row only with verified
fields.

## Langchain4clj reference boundary

The vendored reference is `reference-code/langchain4clj` at v1.6.2,
`889f9e60e3d2bb13948f9a9921aa294712fcace7`. Its provider builders, streaming
listener, Anthropic cache toggles, and Malli-to-JSON-Schema call are useful
examples. Its assistant loop, atom-backed memory, static model presets,
retry/circuit-breaker state, and tool registry duplicate Seon mechanisms and
must not be integrated. The source-by-source judgment is in
`docs/prds/sci-execution-runtime/research/llm-provider-research-2026-08-03.md`.
