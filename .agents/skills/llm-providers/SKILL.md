---
name: llm-providers
description: Verify or plan Seon's LLM provider request construction, prompt caching, streaming, reasoning fields, usage normalization, and database-backed provider descriptor entities. Load before changing seon.ai, an AI config fact, a provider endpoint/model, or the rendered-context/cache boundary.
---

# LLM providers

## Overview

Keep one `seon.ai` HTTP owner and represent hosted targets as provider descriptor
entities, resolved by `resolved-target` and `seon.ai/targets`. Re-open the linked primary source before editing an entity or wire shape.
The maintained reference is `docs/seon/reference/llm-adapters.md`. The
2026-08-03 verified baseline and source inventory were deleted with the retired
program; read them with
`git show 215447c46^:docs/prds/sci-execution-runtime/research/llm-provider-research-2026-08-03.md`.

## Working rules

- Read `seon.ai/resolved-target`, `seon.ai/wire-settings`, `seon.ai/request-body`,
  `seon.ai/stream-fold`, the handoff in `seon.turn/call-turn`, and `resources/seon/schemas/seon.ai.edn`
  plus `resources/seon/schemas/seon.config.ai.edn` before changing the provider
  seam.
- Keep credentials outside the database. Descriptor entities name the environment
  variable; `seon.ai/credential` reads it only at the HTTP leaf
  (`:seon.config.ai/api-key-variable` in `config/default.edn`).
- Nothing in `seon.ai` retries (owner ruling 2026-07-27, in the docstring of `seon.ai/truncated-completion`): the HTTP leaf performs one call (`seon.ai/send-request`, `seon.ai/complete`) and a
  re-request is never issued; do not add an SDK retry, circuit breaker, or second model registry.
- Normalize provider token counts with `seon.ai/normalize-usage`, which answers
  `:seon.ai.usage/*` count attributes; readers query stored attempt facts rather than decoding usage EDN again.
- The prompt is the rendered `seon.context/current-state` block, built when the
  model call is made and passed as `:seon.ai/prompt` (`seon.context/model-context`);
  no capture is stored, so the provider call and its attempt facts are the record.
- Omit an unverified endpoint, model identifier, field, or default. A missing
  provider fact is a research boundary, not permission to infer a value.

## Shipped provider and model entities

The default manifest declares provider entities for DeepSeek, Moonshot, Meta and
OpenRouter (the `:seon.ai.model/provider-id` entries of `config/default.edn`). Each entity owns its endpoint,
credential-variable name, OpenAI-compatible chat fact, and output-token wire
key. Model entities connect `deepseek-flash`, `deepseek-v4-pro`, `kimi-k3`,
`muse-spark-1.1` and OpenRouter's pinned
`deepseek/deepseek-v4-flash-20260731` to those providers
(the `:seon.ai.model/id` entries). `deepseek-flash` is the cluster default by
the 2026-09-10 owner ruling (`:seon.config.ai/model`), with a
65,536-token completion budget (`:seon.config.ai/max-tokens`) and thinking disabled (`:seon.config.ai/thinking`).

`seon.ai/resolved-target` derives endpoint, credential-variable name, provider-specific
output-token wire key, output cap, and admitted thinking setting from those
database entities. `seon.ai/wire-settings` reads each config
schema's `:seon.ai/wire` declarations and substitutes the resolved provider's
output-token key for the default `max_tokens` key
(`resources/seon/schemas/seon.config.ai.edn`). Do not recreate either provider or request-field
selection as a conditional or hand-maintained model list.

## Anthropic — cache-contract reference

Anthropic's native Messages API is `POST https://api.anthropic.com/v1/messages`.
Direct API-key calls require `x-api-key`, `anthropic-version`, and
`content-type`; `system` is top-level rather than a system-role message
([Claude API overview](https://platform.claude.com/docs/en/api/overview)).
Current Seon emits Bearer authentication and OpenAI-style messages, so a native
Anthropic entity is not representable (`seon.ai/request-headers` and `seon.ai/request-body`).

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
