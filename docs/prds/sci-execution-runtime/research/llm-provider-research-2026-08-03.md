---
type: research
status: active
tags: [research, ai, provider, caching]
---

# LLM provider research — 2026-08-03

## Result

Seon's DeepSeek request is protocol-compatible with DeepSeek's current Chat
Completions and automatic context-cache contracts. It needs no explicit cache
marker. Its rendered context is deterministic at one database value, and the
loop captures the exact string it later sends, but it is not byte-stable across
turns: a changing database basis appears in the first line and the final
REPL-state line contains both that basis and its transaction instant. Volatile
bytes therefore precede nearly all reusable context and reduce automatic
prefix-cache reuse.

Kimi K3 is close to the existing OpenAI-compatible wire. Endpoint,
authentication, message, reasoning, streaming, usage, and automatic-cache
shapes are compatible. The remaining output-budget mismatch is explicit:
Kimi deprecates `max_tokens` in favor of `max_completion_tokens`, while Seon's
config fact always emits `max_tokens`.

Anthropic is not representable by the current descriptor row. Its native API
requires Anthropic headers and message/content-block structure, and caching is
opt-in with `cache_control`. Muse Spark 1.1 remains a research candidate because
Meta's public announcement does not verify the protocol facts required for a
row.

Langchain4clj is useful reference code but should not become Seon's runtime
core. The judgment is **REUSE OUR CORE**: retain the submodule and mine small
provider-wire or Malli JSON-Schema lessons only. Its high-level assistant,
memory, registry, retry, and tool layers duplicate Seon mechanisms.

## Scope and dependency ledger

| Dependency or owner | Selected version or source | Boundary read |
|---|---|---|
| Seon provider HTTP | current checkout | `src/seon/ai.clj:350-418,470-530,642-661,800-950` |
| Seon turn handoff | current checkout | `src/seon/cluster/loop.clj:1262-1334`; `src/seon/cluster/prompt.clj:40-69` |
| Seon context rendering | current checkout | `src/seon/render/walk.clj:68-89,515-539,595-625`; `src/seon/render.clj:293-307,386-391` |
| Seon retained HTML bytes | current checkout | `src/seon/render/web.clj:312-412,530-568` |
| Seon config/schema | current checkout | `config/default.edn:145-210`; `resources/seon/schema.edn:125-153,230-270,595-690,803-825,2157-2167` |
| langchain4clj | v1.6.2, `889f9e60e3d2bb13948f9a9921aa294712fcace7` | `reference-code/langchain4clj/` |
| LangChain4j artifacts | 1.9.1 | `reference-code/langchain4clj/deps.edn:8-24` |

The first-party desired idioms are the one JDK HTTP owner, exact pre-call
context capture, database-backed config facts, data retry strategy, and Flow
stream channel. The comparison is against those owners, not an abstract
feature checklist.

## Request and retained-byte audit

### Exact wire construction

`seon.ai/request-body` builds one OpenAI-compatible document with `model`,
`stream`, and ordered system/user messages. Streaming adds
`stream_options.include_usage`; schema-derived settings and a validated
string-keyed extra-body map merge without permitting overrides of builder-owned
fields (`src/seon/ai.clj:352-418`). The JDK HTTP leaf sends JSON with
`content-type` and optional Bearer authorization and performs one synchronous
request on the calling Flow I/O thread
(`src/seon/ai.clj:375-378,800-855,926-950`).

The loop renders once from a held run's immutable database value, commits the
exact prompt before the provider call, extracts only
`:seon.cluster.prompt/text`, and reuses it across backoff/failover attempts
(`src/seon/cluster/loop.clj:1262-1334`). The schema names the captured prompt
“THE BYTE GROUND TRUTH” and keeps text, contributions, and database value
together (`resources/seon/schema.edn:803-825,2157-2167`). There is no
post-capture prompt mutation in this path.

### Stable ordering

The inspected context path uses no random values:

- entity ref attributes sort by attribute string
  (`src/seon/render/walk.clj:68-89`);
- reverse refs sort by entity id (`src/seon/render/walk.clj:91-136`);
- flattened units sort root first, then by changed transaction, branch, and
  path (`src/seon/render/walk.clj:515-539`); and
- transcript entries sort by transaction time, entry class, and identity
  (`src/seon/render/transcript.clj:139-162,290-311`).

At the same immutable database value and same process-liveness input, these
rules produce the same context string. The HTML projection already retains
bytes: unchanged evidence reuses a prior fragment string, the page is explicitly
ordered, and a keyframe serializes sorted fragments
(`src/seon/render/web.clj:312-412,530-568`). Prompt construction does not consume
those retained bytes; it performs a fresh walk
(`src/seon/cluster/prompt.clj:40-69`).

### Cross-turn instability

The byte-stability answer is **no across turns**:

1. The first prompt line embeds `basis=<max-tx>`
   (`src/seon/render/walk.clj:595-625`).
2. The final REPL-state line embeds that basis and its `:db/txInstant`
   (`src/seon/render.clj:293-307,386-391`).
3. A later turn follows new message/run/attempt facts, so basis and transaction
   instant differ even when substantive instructions and long context do not.

Semantic timestamps already present in database facts are legitimate content;
the defect is unconditionally rendered current basis/time metadata around every
context. No prompt randomness was found. Exact capture proves the unstable
string is provider input rather than a debug-only projection.

For an automatic prefix cache, the first-line basis changes before nearly all
context. A stable JSON envelope does not recover the reusable
instruction/document prefix. The open retained-byte issue now records this
acceptance boundary:
`docs/seon/issues/ai-context-bypasses-render-proc-retained-bytes.md`.

## Provider contracts

### DeepSeek — current

DeepSeek accepts `POST /chat/completions`, models `deepseek-v4-flash` and
`deepseek-v4-pro`, `thinking.type`, `reasoning_effort`, `max_tokens`, and
streaming `reasoning_content`
([Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)).
Seon's builder and parser match those fields
(`src/seon/ai.clj:380-418,470-530,630-640`). The shipped descriptor uses
`https://api.deepseek.com/chat/completions`, `deepseek-v4-flash`, a 65,536
output cap, disabled thinking for ordinary turns, `DEEPSEEK_API_KEY`, and the
locally calibrated 180-second deadline (`config/default.edn:145-198`).

DeepSeek caching is enabled by default and needs no client marker. It reuses
overlapping prefixes only when a complete persisted prefix unit matches; usage
reports `prompt_cache_hit_tokens` and `prompt_cache_miss_tokens`, and
persistence is best effort
([context caching](https://api-docs.deepseek.com/guides/kv_cache/)). Seon
normalizes the hit field (`src/seon/ai.clj:642-661`). Verdict: **compatible but
poorly prefix-stable across turns**.

### Kimi K3 — planning candidate

Kimi documents `https://api.moonshot.ai/v1/chat/completions`, Bearer
authentication, and model `kimi-k3`
([Chat API](https://platform.kimi.ai/docs/api/chat),
[troubleshooting](https://www.kimi.com/help/kimi-api/api-troubleshooting)). K3
always thinks, accepts `low`, `high`, or `max` `reasoning_effort`, defaults to
`max`, and supports a one-million-token context
([model selection](https://www.kimi.com/help/kimi-api/api-model-selection)).
Those facts map directly to Seon's endpoint/model/credential/thinking row.

Kimi SSE uses `data:` JSON and `[DONE]`; final included usage is top-level, and
`reasoning_content` is the reasoning field
([Chat API](https://platform.kimi.ai/docs/api/chat)). Seon's parser handles
those shapes and requests included usage (`src/seon/ai.clj:470-530,630-661`).

Kimi automatically attempts to cache repeated initial context without a cache
ID, TTL, or extra field; prefix stability improves hit rates
([caching guidance](https://www.kimi.com/help/kimi-api/api-troubleshooting)).
Optional `prompt_cache_key` is recommended for multi-turn agents and should
remain stable for a resumed session or task
([Chat API](https://platform.kimi.ai/docs/api/chat)). Seon's static
`extra-body-edn` cannot derive that per-session value
(`resources/seon/schema.edn:674-681`), but the key is not required for ordinary
automatic caching.

Kimi deprecates `max_tokens`, recommends `max_completion_tokens`, and documents
131,072 as K3's default output budget
([Chat API](https://platform.kimi.ai/docs/api/chat)). Seon maps its required
output fact to `max_tokens` (`resources/seon/schema.edn:607-613`). Verdict:
**candidate-compatible except for the preferred output-budget field; no
optimized shipped config until that wire fact and paid calibration land**. See
`docs/seon/issues/provider-output-token-wire-key-is-hard-coded.md`.

### Anthropic — explicit cache reference

The Claude Messages API is `POST https://api.anthropic.com/v1/messages`.
API-key calls require `x-api-key`, `anthropic-version`, and `content-type`; the
request uses top-level `system` and Messages content rather than an OpenAI
system-role message ([API overview](https://platform.claude.com/docs/en/api/overview),
[Messages API](https://platform.claude.com/docs/en/api/messages/create)). Seon's
Bearer-only headers and OpenAI document cannot represent that native contract
(`src/seon/ai.clj:375-418`).

Anthropic prompt caching needs a request instruction. Supply top-level
`cache_control` for automatic breakpoint movement or block-level
`cache_control` for explicit breakpoints. Prefix order is tools, system, then
messages; stable content belongs first. Usage reports
`cache_creation_input_tokens` and `cache_read_input_tokens`
([prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)).
Verdict: **unsupported by the current descriptor and wire**. An extra-body
marker alone is insufficient because authentication and content structure also
differ.

### Muse Spark 1.1 — research candidate

Meta verifies Muse Spark 1.1 as a multimodal reasoning model for agentic tasks,
available through the public-preview Meta Model API, with a one-million-token
context
([Meta announcement](https://ai.meta.com/blog/introducing-muse-spark-meta-model-api/)).
The announcement does not establish the endpoint, request model identifier,
authentication, cache controls, stream events, reasoning fields, or usage
schema. Those values were omitted. Verdict: **not enough primary documentation
for a descriptor row or optimized config**.

## Descriptor rows

The current DeepSeek row is executable and source-backed:

```clojure
{:seon.config.ai/endpoint "https://api.deepseek.com/chat/completions"
 :seon.config.ai/model "deepseek-v4-flash"
 :seon.config.ai/max-tokens 65536
 :seon.config.ai/thinking :disabled
 :seon.config.ai/api-key-variable "DEEPSEEK_API_KEY"
 :seon.config.ai/timeout-ms 180000}
```

The Kimi row is a partial `[TARGET]` planning overlay. Its 131,072 budget is the
provider default, not a Seon optimum; current code emits it under the deprecated
field, and the operational timeout remains deliberately unsettled:

```clojure
;; [TARGET] Provider-specific facts only. Resolve output-budget projection,
;; then calibrate effort, budget, and timeout before applying.
{:seon.config.ai/endpoint "https://api.moonshot.ai/v1/chat/completions"
 :seon.config.ai/model "kimi-k3"
 :seon.config.ai/max-tokens 131072
 :seon.config.ai/thinking :max
 :seon.config.ai/api-key-variable "MOONSHOT_API_KEY"}
```

No Anthropic or Muse row is supplied: required facts cannot be represented or
verified, respectively. This is deliberate omission, not an implied default.

## Langchain4clj inventory

The submodule is pinned to v1.6.2 at
`889f9e60e3d2bb13948f9a9921aa294712fcace7`. It wraps LangChain4j 1.9.1 and
provider modules (`reference-code/langchain4clj/deps.edn:8-24`).

| Area | What langchain4clj has | Seon comparison | Judgment |
|---|---|---|---|
| Provider builders | OpenAI, Anthropic, Gemini, Ollama, and Mistral builders; lower-level Anthropic fields include cache toggles (`core.clj:107-164,253-401`) | Seon has one ordinary-data descriptor and JDK owner; generic `build-model` drops several lower-level options | Mine provider field names; do not adopt builder objects |
| Streaming | Callback handler and provider-specific streaming multimethod for OpenAI, Anthropic, Ollama (`streaming.clj:20-124`) | Seon parses raw SSE into snapshots and publishes through Flow | Duplicates transport; listener shape is reference only |
| Caching | Anthropic builder booleans `cacheSystemMessages` and `cacheTools` (`core.clj:143-164`) | No DeepSeek/Kimi cache abstraction; generic Anthropic path omits these options | Does not fill prefix-stability or marker gap |
| Retries/failover | Exception-text classification, `Thread/sleep` retries, atom/time circuit breakers, failover (`resilience.clj:10-121,171-287,289-464`) | Seon owns one paid call, durable attempt evidence, retry data, disposition | Direct duplicate; do not integrate |
| Model registry | Static presets with hard-coded defaults and names (`presets.clj:29-100`) | Seon config facts and per-agent overlays are queryable descriptor rows | Direct duplicate and hand-maintained registry |
| Memory/assistant | Atom message window and automatic tool loop with mutable first-system-message state (`memory/core.clj:9-120`; `assistant.clj:18-156`) | Database facts, rendered context, per-agent Flow own memory and progression | Duplicate of transport law, blob tier, and flow |
| Tool schemas | Malli validation/coercion and `malli.json-schema/transform` (`tools/malli.clj:13-45`); broader registry auto-detects schema systems (`tools.clj:20-101,243-423`) | Seon has one Malli/schema fact authority and is currently tool-less | JSON-Schema transform is a future lesson; registry/executor duplicate contracts/effects |
| Message conversion | Java ChatMessage ↔ EDN/JSON (`messages.clj:79-205`) | Seon uses ordinary wire data and separately records reasoning | Useful only if an SDK is selected; current AI EDN omits reasoning (`messages.clj:111-117`) |
| Structured output | JSON mode, prompt rewriting, tool output, validation retries (`structured.clj:27-168`) | Seon schemas and effect owner must govern this; retries repeat paid work | Do not integrate; no second schema or retry path |

Langchain4clj fills no present runtime gap that warrants integration. It shows
provider-specific builder surfaces and a direct Malli-to-JSON-Schema conversion
that may inform future tool calling. Everything else would duplicate at least
one of the `seon.ai` transport owner, database/blob facts, config descriptor
facts, retry strategy and attempts, per-agent Flow, or Malli schema authority.

Before any integration proposal, the orchestrator should review this table for
duplication. Leave the submodule as reference code, strengthen Seon's existing
owner, and copy no assistant/runtime layer.

## Defects and next proofs

- Strengthened
  `docs/seon/issues/ai-context-bypasses-render-proc-retained-bytes.md` with the
  cross-turn prefix defect and provider-usage acceptance evidence.
- Filed `docs/seon/issues/provider-output-token-wire-key-is-hard-coded.md` for
  the schema-level `max_tokens` assumption exposed by Kimi K3.
- Before changing prompt assembly, compare exact captures from consecutive
  semantically similar turns and report longest common-prefix tokens plus
  DeepSeek/Kimi cache-hit usage.
- Before shipping Kimi, calibrate `reasoning_effort` high/max, output budgets,
  real stream termination, usage, cache hits, latency, and cost.
- Revisit Muse only when Meta publishes primary protocol documentation.

## Primary external sources

- [DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)
- [DeepSeek context caching](https://api-docs.deepseek.com/guides/kv_cache/)
- [Kimi Chat API](https://platform.kimi.ai/docs/api/chat)
- [Kimi API overview](https://www.kimi.com/help/kimi-api/api-overview)
- [Kimi model selection](https://www.kimi.com/help/kimi-api/api-model-selection)
- [Kimi caching and troubleshooting](https://www.kimi.com/help/kimi-api/api-troubleshooting)
- [Claude API overview](https://platform.claude.com/docs/en/api/overview)
- [Claude Messages API](https://platform.claude.com/docs/en/api/messages/create)
- [Anthropic prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)
- [Meta Muse Spark 1.1 announcement](https://ai.meta.com/blog/introducing-muse-spark-meta-model-api/)
