---
name: llm-providers
description: Verify or plan Seon's LLM provider request construction, prompt caching, streaming, reasoning fields, usage normalization, and database-backed provider descriptor rows. Load before changing seon.ai, an AI config fact, a provider endpoint/model, or the rendered-context/cache boundary.
---

# LLM providers

## Overview

Keep one `seon.ai` HTTP owner and represent hosted targets as provider descriptor
rows, resolved by `resolved-target` and `targets` (`src/seon/ai.clj:383-418`,
`:443`). Re-open the linked primary source before editing a row or wire shape.
The maintained reference is `docs/seon/reference/llm-adapters.md`. The
2026-08-03 verified baseline and source inventory were deleted with the retired
program; read them with
`git show 215447c46^:docs/prds/sci-execution-runtime/research/llm-provider-research-2026-08-03.md`.

## Working rules

- Read `resolved-target` (`src/seon/ai.clj:383-418`), `retry-strategy` and
  `delays` (`:495-559`), `wire-settings` (`:598-632`), `request-body`
  (`:680`), `stream-fold` (`:848`), the capture handoff in `call-turn`
  (`src/seon/turn.clj:4466-4497`), and `resources/seon/schemas/seon.ai.edn`
  plus `resources/seon/schemas/seon.config.ai.edn` before changing the provider
  seam.
- Keep credentials outside the database. Descriptor rows name the environment
  variable; `seon.ai/credential` reads it only at the HTTP leaf
  (`src/seon/ai.clj:1054-1069`; `config/default.edn:439-441`).
- Keep retries and failover in Seon's attempt/disposition mechanism. The HTTP
  leaf performs one call (`send-request`, `src/seon/ai.clj:1330`; `complete`,
  `:1486`; `disposition`, `:1103`); do not add an SDK retry,
  circuit breaker, or second model registry.
- Normalize provider token counts with `seon.ai/normalize-usage`
  (`src/seon/ai.clj:1008`). The attempt writer stores its namespaced count
  attributes; readers query those facts rather than decoding usage EDN again.
  The retained provider document also contains fields without declared count
  equivalents, such as reported cost.
- Capture and reuse the exact rendered prompt. The loop commits the capture
  before calling the provider and passes only that string as `:seon.ai/prompt`
  (`src/seon/turn.clj:4466-4497`, prompt placed at `:4527`;
  `resources/seon/schemas/seon.context.capture.edn:1-26`).
- Historical evaluations render their saved shown text unchanged; system turns
  append changed reads and compaction regenerates the opening
  (`value-text`, `src/seon/repl.clj:142-146`; `system-turn`,
  `src/seon/turn.clj:2055`; `compact!`, `:2268`). Verify the provider prompt
  against those exact stored bytes, including handles and timing, as
  `test/seon/loop_proof_test.clj:510-558` does. Never strip timing or preserve old
  evaluations across compaction to manufacture stability.
- Omit an unverified endpoint, model identifier, field, or default. A missing
  provider fact is a research boundary, not permission to infer a value.

## Shipped provider and model rows

The default manifest declares provider rows for DeepSeek, Moonshot, Meta and
OpenRouter (`config/default.edn:541-566`). Each row owns its endpoint,
credential-variable name, OpenAI-compatible chat fact, and output-token wire
key. Model rows connect `deepseek-flash`, `deepseek-v4-pro`, `kimi-k3`,
`muse-spark-1.1` and OpenRouter's pinned
`deepseek/deepseek-v4-flash-20260731` to those providers
(`config/default.edn:568-649`). `deepseek-flash` is the cluster default by
the 2026-09-10 owner ruling (`config/default.edn:392-396`), with a
65,536-token completion budget (`:403`) and thinking disabled (`:425`).

`resolved-target` derives endpoint, credential-variable name, provider-specific
output-token wire key, output cap, and admitted thinking setting from those
database rows (`src/seon/ai.clj:383-418`). `wire-settings` reads each config
schema's `:seon.ai/wire` declarations and substitutes the resolved provider's
output-token key for the default `max_tokens` key
(`resources/seon/schemas/seon.config.ai.edn:43-50`;
`src/seon/ai.clj:598-632`, substitution at `:621`). Do not recreate either provider or request-field
selection as a conditional or hand-maintained model list.

## Anthropic — cache-contract reference

Anthropic's native Messages API is `POST https://api.anthropic.com/v1/messages`.
Direct API-key calls require `x-api-key`, `anthropic-version`, and
`content-type`; `system` is top-level rather than a system-role message
([Claude API overview](https://platform.claude.com/docs/en/api/overview)).
Current Seon emits Bearer authentication and OpenAI-style messages, so a native
Anthropic row is not representable (`request-headers` and `request-body`,
`src/seon/ai.clj:675-724`).

Anthropic caching is opt-in through `cache_control`: either one top-level field
for automatic breakpoint movement or explicit `cache_control` on content
blocks. The cached prefix order is tools, system, messages; place stable blocks
first. Verify hits with `cache_creation_input_tokens` and
`cache_read_input_tokens`
([Anthropic prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)).
This is not DeepSeek/Kimi automatic caching and must not become a
provider-neutral “cache enabled” boolean.

## Langchain4clj reference boundary

The vendored reference is `reference-code/langchain4clj` at
`889f9e60e3d2bb13948f9a9921aa294712fcace7`. Its provider builders, streaming
listener, Anthropic cache toggles, and Malli-to-JSON-Schema call are useful
examples. Its assistant loop, atom-backed memory, static model presets,
retry/circuit-breaker state, and tool registry duplicate Seon mechanisms and
must not be integrated. The source-by-source judgment is in the deleted
research note named in the overview (`git show 215447c46^:…`).
