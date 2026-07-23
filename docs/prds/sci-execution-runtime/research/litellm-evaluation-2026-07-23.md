---
type: research
status: active
tags: [research, agent, runtime]
---

# litellm-clj evaluation — verdict: mine, do not adopt (2026-07-23)

# Verdict

Do not switch Seon’s LLM adapter machinery to litellm-clj.

litellm-clj provides broader provider coverage, provider-normalized incremental chunks, embeddings, and convenience APIs. But for Seon it would not simplify the system: it is JVM-only, exception-oriented, dependency-heavy, backed by a process-local config atom, and its streaming lacks the cancellation and accounting guarantees Seon’s first-form execution depends on.

The best choice is option (c): retain Seon’s portable cores and native leaves, while selectively porting provider transforms and incremental-delta ideas from litellm-clj.

## 1. Capability delta

| Area | litellm-clj advantage | Limits relative to Seon |
|---|---|---|
| Providers | Ten built-ins: OpenAI, Anthropic, Gemini, Mistral, Ollama, OpenRouter, Azure, DeepSeek, Kimi, and Z.AI; optional Bedrock. See [litellm/core.clj:6](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/core.clj:6) and [litellm/deps.edn:57](/Users/sean/src/seon/reference-code/litellm-clj/deps.edn:57). | Seon exposes only Anthropic and OpenAI-compatible adapters, but the latter already reaches arbitrary compatible gateways using a database-resolved base URL and extra body: [llm-adapters.md:15](/Users/sean/src/seon/docs/seon/reference/llm-adapters.md:15), [openai_compat/core.cljc:195](/Users/sean/src/seon/src/seon/ai/openai_compat/core.cljc:195). Kimi, Z.AI, OpenRouter, and many local servers are therefore mostly provider-specific request-policy additions, not new transport capability. |
| Incremental output | A streaming completion returns a `core.async` channel of normalized chunks, with helpers for accumulation and callbacks: [core.clj:42](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/core.clj:42), [streaming.clj:57](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/streaming.clj:57). This is a real feature Seon’s agent loop does not expose; Seon folds deltas internally and returns only the first form or final completion: [llm-adapters.md:472](/Users/sean/src/seon/docs/seon/reference/llm-adapters.md:472). | The channel is not a cancellable transport. It cannot directly replace Seon’s stream consumer; details below. |
| Tools/functions | litellm-clj normalizes tools and incremental tool-call deltas across more providers: [openai_compatible.clj:97](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/openai_compatible.clj:97), [providers/core.clj:234](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/core.clj:234). | Seon already sends tools and returns tool calls for OpenAI-compatible and Anthropic calls: [openai_compat/core.cljc:54](/Users/sean/src/seon/src/seon/ai/openai_compat/core.cljc:54), [openai_compat/core.cljc:137](/Users/sean/src/seon/src/seon/ai/openai_compat/core.cljc:137). Neither system executes tools or performs the follow-up completion automatically; Seon’s ordinary loop does not yet expose the tool request fields: [llm-adapters.md:426](/Users/sean/src/seon/docs/seon/reference/llm-adapters.md:426). |
| Structured output | litellm-clj has named `:response-format` and `:stream-options` request fields: [schemas.clj:90](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/schemas.clj:90). | The implementation is passthrough, not schema enforcement or parsed-output validation, and is wired explicitly only in the new DeepSeek/Kimi/Z.AI adapters, e.g. [deepseek.clj:55](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/deepseek.clj:55). Even its OpenAI adapter omits `response-format` and `stream-options`: [openai.clj:156](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/openai.clj:156). Seon can already send these wire fields through `:seon.ai/extra-body`: [openai_compat/core.cljc:207](/Users/sean/src/seon/src/seon/ai/openai_compat/core.cljc:207). |
| Embeddings | OpenAI, Mistral, Gemini, and Azure embeddings are implemented: [providers/core.clj:391](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/core.clj:391). | This is new provider choice, but Seon already has the one `seon.embed`/Vertex mechanism; adopting litellm embeddings would create a second embedding path unless deliberately replacing that owner: [src/seon/AGENTS.md:30](/Users/sean/src/seon/src/seon/AGENTS.md:30). |
| Usage/cost | litellm-clj normalizes usage, including cached-token detail for its newer compatible providers: [openai_compatible.clj:164](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/openai_compatible.clj:164). It also has model cost tables. | Seon preserves the provider’s entire usage map and unknown provider fields, then persists attempt-level evidence: [llm-adapters.md:481](/Users/sean/src/seon/docs/seon/reference/llm-adapters.md:481). litellm’s rough request estimator double-counts the system message because it includes it in `message-tokens` and adds it again: [providers/core.clj:704](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/core.clj:704). |
| Routing/fallback | A convenient provider registry, dynamic router, and opt-in fallback wrapper exist: [config.clj:9](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/config.clj:9), [wrappers.clj:11](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/wrappers.clj:11). | The registry is a global atom and can hold secrets, conflicting with Seon’s database singleton and frozen resolution. Its fallback catches every exception indiscriminately and has no durable attempt receipt. Seon resolves config/fallback data with provenance and opens a durable attempt before dispatch: [ai/core.cljc:332](/Users/sean/src/seon/src/seon/ai/core.cljc:332), [turn/llm.cljc:171](/Users/sean/src/seon/src/seon/agent/turn/llm.cljc:171). |

One important litellm-clj correctness gap: Mistral and Ollama claim streaming support, but there is no `make-streaming-request` multimethod for either. Compare the implementations at [providers/core.clj:99](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/core.clj:99) with the positive support declarations at [providers/core.clj:213](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/core.clj:213). A streaming call passes validation and then has no dispatch method.

## 2. Streaming comparison

### Incremental deltas

litellm-clj wins on exposure. Consumers can read content, reasoning, and—in the newer compatible providers—partial tool-call deltas from a channel. Seon currently keeps streaming internal: it folds text until it has one reader-confirmed form and returns a completed result.

That capability is worth porting if Seon wants UI progress or observable generation. It does not justify adopting the transport.

### First-form stopping and cancellation

Seon has real upstream cancellation:

- The pod’s OpenAI stream accumulates deltas, confirms a form with the actual REPL parser, then calls `.abort()` on the SDK stream: [openai_compat.cljs:290](/Users/sean/src/seon/src/seon/ai/openai_compat.cljs:290).
- The JVM leaf applies the same portable predicate after each SSE event and exits immediately: [http.clj:111](/Users/sean/src/seon/src/seon/ai/http.clj:111), [ai/core.cljc:144](/Users/sean/src/seon/src/seon/ai/core.cljc:144). Its `with-open` closes the response stream when returning.
- Attempt deadlines propagate cancellation or thread interruption, and the JVM leaf deliberately rethrows interruption: [http.clj:177](/Users/sean/src/seon/src/seon/ai/http.clj:177).

litellm-clj returns only a channel. `close-stream!` closes that channel and has no reference to the HTTP response, reader, request future, or cancellation token: [streaming.clj:20](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/streaming.clj:20). The provider loop continues reading without testing whether `>!` succeeded: [openai_compatible.clj:261](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/openai_compatible.clj:261). If a consumer merely stops reading, the producer eventually parks on the hardcoded 64-entry buffer; if it closes the channel, the producer still reads to EOF. Either behavior defeats Seon’s cost- and latency-saving first-form abort.

Consequently, litellm’s deltas can feed the predicate, but not correctly stop the provider request without modifying/forking its provider loops.

### SSE parser quality

Both implementations are line-oriented rather than complete SSE event parsers; neither combines multiple `data:` lines into an event.

Seon is nevertheless materially stronger:

- Accepts `data:` with or without a following space and trims it.
- Handles `[DONE]`.
- Applies one database-configured maximum byte bound to batch and streaming responses.
- Treats malformed JSON as a bounded error value.
- Closes the input stream on natural completion, abort, or exception.

See [http.clj:95](/Users/sean/src/seon/src/seon/ai/http.clj:95) and [http.clj:106](/Users/sean/src/seon/src/seon/ai/http.clj:106).

litellm-clj accepts only lines beginning exactly `data: ` and silently logs/drops malformed JSON: [streaming.clj:196](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/streaming.clj:196). It has no response-byte limit. Most providers also perform blocking `http/post` and `readLine` inside a `core.async/go` block; Anthropic alone uses `async/thread`: [openai_compatible.clj:268](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/openai_compatible.clj:268), [anthropic.clj:408](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/anthropic.clj:408).

### Usage chunks

Seon’s OpenAI-compatible request automatically asks for terminal usage, tolerates the usage-only chunk with no choices, and captures it on natural completion: [openai_compat/core.cljc:42](/Users/sean/src/seon/src/seon/ai/openai_compat/core.cljc:42), [openai_compat/core.cljc:171](/Users/sean/src/seon/src/seon/ai/openai_compat/core.cljc:171). When early abort necessarily loses terminal usage, Seon estimates prompt/completion usage and marks it explicitly as estimated: [openai_compat/core.cljc:158](/Users/sean/src/seon/src/seon/ai/openai_compat/core.cljc:158), [openai_compat/core.cljc:240](/Users/sean/src/seon/src/seon/ai/openai_compat/core.cljc:240).

litellm’s newer DeepSeek/Kimi/Z.AI paths can preserve a usage chunk if the caller explicitly supplied `:stream-options`; it does not request usage automatically. Its ordinary OpenAI stream transformer drops usage entirely: [openai.clj:254](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/openai.clj:254). Its callback collector constructs the final response with `:usage nil`: [streaming.clj:135](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/streaming.clj:135).

Anthropic is worse: litellm handles content-block events and `message_stop`, but ignores `message_start` and `message_delta`: [anthropic.clj:329](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/anthropic.clj:329). The vendored Anthropic contract places initial usage in `message_start`, and output usage plus stop reason in `message_delta`: [messages.ts:1322](/Users/sean/src/seon/reference-code/anthropic-sdk-typescript/src/resources/messages/messages.ts:1322), [MessageStream.ts:562](/Users/sean/src/seon/reference-code/anthropic-sdk-typescript/src/lib/MessageStream.ts:562). Therefore litellm’s Anthropic stream loses usage and usually derives its finish reason from the wrong event.

Seon’s portable Anthropic fold handles those two locations and estimates usage after an early abort: [anthropic/core.cljc:88](/Users/sean/src/seon/src/seon/ai/anthropic/core.cljc:88). The older pod Anthropic adapter is the exception—it buffers the full SDK message and ignores stream mode: [anthropic.cljs:351](/Users/sean/src/seon/src/seon/ai/anthropic.cljs:351).

### Streaming tests

litellm’s generic streaming test covers content accumulation plus one exact `data: <json>` line and `[DONE]`; it has no early-stop, cancellation, malformed-event, usage-only, byte-bound, or transport-interruption test: [streaming_test.clj:32](/Users/sean/src/seon/reference-code/litellm-clj/test/litellm/streaming_test.clj:32). Its basic OpenAI integration test only checks that the result is a channel: [core_test.clj:330](/Users/sean/src/seon/reference-code/litellm-clj/test/litellm/core_test.clj:330).

## 3. Fit with Seon’s constraints

### Errors as values

This is a poor fit. litellm validates by throwing, HTTP helpers throw typed `ex-info`, and even `with-error-handling` logs and rethrows: [core.clj:82](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/core.clj:82), [core.clj:371](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/core.clj:371), [errors.clj:476](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/errors.clj:476). Streaming failures are value chunks, creating two failure modes.

Seon has one stable value vocabulary with status, timeout, transport, and Retry-After fields: [ai/core.cljc:16](/Users/sean/src/seon/src/seon/ai/core.cljc:16). An adoption layer would need to catch every litellm call, translate exceptions and streaming error chunks, preserve interruption, and bound error evidence.

### One retry authority

litellm’s core provider calls do not automatically invoke its retry wrapper. Therefore library-level retries can be avoided by never using `with-retry`. However:

- Its default provider config advertises `:max-retries 3`, but that value is not consumed by provider request code: [providers/core.clj:670](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/core.clj:670).
- The opt-in wrapper has independent hardcoded retry defaults, retries every exception unless given a predicate, sleeps directly, and has no total-wait ceiling: [wrappers.clj:61](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/wrappers.clj:61).
- There is no explicit transport setting equivalent to the JS SDKs’ `maxRetries: 0`.

Seon’s one portable authority selects transport/429/5xx, clamps each wait, bounds retries and total wait, honors bounded Retry-After, and records every dispatched attempt: [turn/core.cljc:185](/Users/sean/src/seon/src/seon/agent/turn/core.cljc:185), [retry.cljc:177](/Users/sean/src/seon/src/seon/retry.cljc:177), [turn/llm.cljc:278](/Users/sean/src/seon/src/seon/agent/turn/llm.cljc:278).

One current Seon gap surfaced in this audit: fallback configuration is resolved and `llm-fallback-eligible?` exists, but the new portable `llm-phase!` never consumes `:seon.ai/fallback-config-resolution`. That should be wired in the existing durable phase; litellm’s exception-based fallback wrapper would not solve it.

### R27 limits and configuration

litellm accepts a per-call `:timeout`, which is useful. But provider calls embed a 30-second default, health checks embed five seconds, and the stream buffer embeds 64 entries: [openai_compatible.clj:244](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/providers/openai_compatible.clj:244), [streaming.clj:12](/Users/sean/src/seon/reference-code/litellm-clj/src/litellm/streaming.clj:12). It has no distinct call-time connect timeout or response-byte limit.

Seon obtains request, connection, response-size, retry-wait, and total-retry-wait bounds from frozen configuration: [ai/core.cljc:179](/Users/sean/src/seon/src/seon/ai/core.cljc:179), [config/resolve.cljc:124](/Users/sean/src/seon/src/seon/config/resolve.cljc:124).

### Portability

litellm-clj is JVM-only: its source is `.clj`, uses Hato, Java readers, futures, threads, and `core.async`. Seon’s architecture requires one portable `.cljc` capability core with small platform leaves: [architecture.md:46](/Users/sean/src/seon/docs/seon/architecture/architecture.md:46).

It therefore cannot replace the portable request builders, response interpreters, retry decisions, or config resolution shared with the pod. At most, it could replace the JVM leaf and simultaneously duplicate or bypass those portable transforms—the opposite of simplification.

### Dependency surface

The current JVM leaf uses JDK `java.net.http` and the already-present JSONista stack: [http.clj:1](/Users/sean/src/seon/src/seon/ai/http.clj:1). Seon’s maintained JVM basis explicitly selects `slf4j-simple` as the sole logging provider: [deps.edn:15](/Users/sean/src/seon/deps.edn:15).

litellm-clj directly adds Hato, Cheshire, Logback Classic, tools.logging, core.async, and an older Malli; Bedrock adds three AWS libraries: [litellm/deps.edn:3](/Users/sean/src/seon/reference-code/litellm-clj/deps.edn:3). Logback would introduce a second SLF4J provider unless excluded. The checked-in litellm implementation is roughly 7,200 source lines versus about 1,000 for Seon’s three portable cores plus JVM leaf.

### Maintenance

The vendored checkout is clean at `14bcdd9` dated 2026-06-17, ten commits after tag `v0.3.159`; it has 169 commits total. There was a useful June burst adding DeepSeek, Kimi, Z.AI, shared compatible helpers, and E2E tests, following a January release period and no commits during February–May. That is active enough to use as reference, but the streaming dispatch gap and shallow cancellation tests argue against making it load-bearing yet.

## 4. Options

### (a) Full switch

Not recommended.

Benefits: immediate provider breadth, embeddings, normalized chunks, and convenience routing.

Costs: abandon or duplicate the portable-core architecture; retain a separate pod solution until all model calls are JVM-only; translate exceptions to values; replace the config atom; reimplement cancellation, byte bounds, Retry-After parsing, usage estimation, interrupt semantics, durable attempt receipts, and R27 facts. Provider-specific live qualification would still be necessary.

Size: XL—approximately 20–35 engineer-days for one engineer, excluding credentials and paid multi-provider live qualification.

### (b) Partial adoption as the JVM transport

Also not recommended in its present form.

Keeping Seon’s portable cores while invoking litellm’s transport would require an awkward bridge because litellm combines provider-specific request transformation, HTTP, response transformation, and channel production. The result would add dependencies while retaining Seon’s builders and retry/config machinery. Its streaming would need a fork to expose transport cancellation and first-form termination.

Size: L—approximately 7–12 engineer-days, followed by ongoing fork maintenance. It only becomes attractive if several genuinely non-OpenAI-compatible JVM providers are required immediately.

### (c) Stay, selectively port ideas

Recommended.

Keep:

- Portable `.cljc` request/response cores.
- JDK HTTP JVM leaf.
- Official JS SDK leaves while the pod remains active.
- Database-resolved immutable config.
- Seon’s error, retry, cancellation, usage, and durable-attempt contracts.

Port selectively:

- Provider request/response transforms for Gemini, Azure, Mistral, Ollama, or Bedrock only when a real use case appears.
- Named `response-format`, multimodal content, and normalized reasoning/tool-call delta shapes.
- An optional incremental-delta callback or event sink around Seon’s existing stream fold, while retaining first-form cancellation.
- Full SSE event framing—especially multi-line `data:` assembly—by strengthening `seon.ai.http`, not by copying litellm’s parser.
- Cached-token usage normalization, but preserve the raw provider map as evidence.
- Finish wiring Seon’s already-resolved fallback variant into the durable attempt phase.

Size: zero migration. A useful first enhancement—incremental deltas plus a stronger SSE parser—would be M, roughly 5–10 engineer-days with dual-tier and cancellation/accounting regressions. Individual OpenAI-compatible provider descriptors should generally be S, around 1–3 days each.

Bottom line: litellm-clj is a valuable provider-source catalog, not a better Seon runtime substrate. Its breadth is worth borrowing; its transport, configuration, failure, and streaming lifecycles are weaker than the contracts already present in this branch.

This was a strictly read-only source and test audit. No files, builds, tests, or REPL sessions were run.