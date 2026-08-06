---
name: llm-providers
description: Verify or plan Seon's LLM provider request construction, prompt caching, streaming, reasoning fields, usage normalization, and database-backed provider descriptor rows. Load before changing seon.ai, an AI config fact, a provider endpoint/model, or the rendered-context/cache boundary.
---

# LLM providers

## Overview

Keep one `seon.ai` HTTP owner and represent hosted targets as provider descriptor
rows (`src/seon/ai.clj:339-408,1040-1086`). Re-open the linked primary source
before editing a row or wire shape. The
verified baseline and source inventory are in
`docs/prds/sci-execution-runtime/research/llm-provider-research-2026-08-03.md`.

## Working rules

- Read `src/seon/ai.clj:339-408,477-555,589-625,856-917`,
  `src/seon/cluster/loop.clj:1371-1414`, and
  `resources/seon/schemas/seon.ai.edn:1-191` plus
  `resources/seon/schemas/seon.config.ai.edn:1-80` before changing the provider
  seam.
- Keep credentials outside the database. Descriptor rows name the environment
  variable; `seon.ai/credential` reads it only at the HTTP leaf
  (`src/seon/ai.clj:903-917,1168-1209`; `config/default.edn:317-330`).
- Keep retries and failover in Seon's attempt/disposition mechanism. The HTTP
  leaf performs one call (`src/seon/ai.clj:952-1008,1051-1086,1168-1209`); do not add an SDK retry,
  circuit breaker, or second model registry.
- Capture and reuse the exact rendered prompt. The loop commits the capture
  before calling the provider and passes only that string as `:seon.ai/prompt`
  (`src/seon/cluster/loop.clj:1371-1414`;
  `resources/seon/schemas/seon.context.capture.edn:1-26`).
- Do not claim cross-turn cache stability today. Ordering is deterministic, but
  rendered text starts with a changing database basis and ends with basis plus
  transaction time (`src/seon/render/walk.clj:595-625`;
  `src/seon/render.clj:293-307,386-391`). The acceptance boundary is in
  `docs/seon/issues/ai-context-bypasses-render-proc-retained-bytes.md`.
- Omit an unverified endpoint, model identifier, field, or default. A missing
  provider fact is a research boundary, not permission to infer a value.

## Shipped provider and model rows

The default manifest declares provider rows for DeepSeek, Moonshot, and Meta.
Each row owns its endpoint, credential-variable name, OpenAI-compatible chat
fact, and output-token wire key. Model rows connect `deepseek-v4-flash`,
`deepseek-v4-pro`, `kimi-k3`, and `muse-spark-1.1` to those providers
(`config/default.edn:390-409,414-485`). DeepSeek Flash remains the cluster
default, with thinking disabled and a 65,536-token completion budget
(`config/default.edn:277-304`).

`resolved-target` derives endpoint, credential-variable name, provider-specific
output-token wire key, output cap, and admitted thinking setting from those
database rows (`src/seon/ai.clj:339-408`). `wire-settings` reads each config
schema's `:seon.ai/wire` declarations and substitutes the resolved provider's
output-token key for the default `max_tokens` key
(`resources/seon/schemas/seon.config.ai.edn:19-24,63-70`;
`src/seon/ai.clj:495-555`). Do not recreate either provider or request-field
selection as a conditional or hand-maintained model list.

## Anthropic — cache-contract reference

Anthropic's native Messages API is `POST https://api.anthropic.com/v1/messages`.
Direct API-key calls require `x-api-key`, `anthropic-version`, and
`content-type`; `system` is top-level rather than a system-role message
([Claude API overview](https://platform.claude.com/docs/en/api/overview)).
Current Seon emits Bearer authentication and OpenAI-style messages, so a native
Anthropic row is not representable (`src/seon/ai.clj:589-625`).

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
must not be integrated. The source-by-source judgment is in
`docs/prds/sci-execution-runtime/research/llm-provider-research-2026-08-03.md`.
