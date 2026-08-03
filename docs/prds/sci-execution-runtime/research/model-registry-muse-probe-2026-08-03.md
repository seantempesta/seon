---
type: research
status: active
tags: [research, ai, model-registry]
---

# Muse Spark 1.1 streamed API probe

## Scope and authorities read

This probe used exactly one paid HTTP request on 2026-08-03. Before the call,
the implementing lane read
[[model-registry-design-2026-08-03|the model-registry design]] and
[[llm-adapters|the maintained LLM provider reference]] end to end. The request
tested only the OpenAI-compatible Chat Completions shape required by the
design. It did not test the Anthropic-compatible endpoint or run any Kimi
calibration.

## Sanitized request

The credential came from `META_MODEL_API_KEY`. The command expanded it only
into the bearer authorization header; the value was not printed, stored, or
committed.

```http
POST https://api.meta.ai/v1/chat/completions
Authorization: Bearer <redacted>
Content-Type: application/json
```

```json
{
  "model": "muse-spark-1.1",
  "messages": [
    {"role": "user", "content": "Reply only: OK"}
  ],
  "stream": true,
  "stream_options": {"include_usage": true},
  "max_tokens": 4
}
```

The client used HTTP/1.1, accepted a streamed response, and imposed a 120
second external-call backstop. No retry or second request was made.

## Sanitized raw response evidence

The one request completed in approximately 2.1 seconds with HTTP 200. The
relevant response headers were:

```http
HTTP/1.1 200 OK
Content-Type: text/event-stream
Cache-Control: no-cache
x-ratelimit-limit-requests: 3000
x-ratelimit-remaining-requests: 2999
x-ratelimit-limit-tokens: 4000000
x-ratelimit-remaining-tokens: 4000000
x-request-id: <redacted-request-id>
Date: Mon, 03 Aug 2026 19:48:16 GMT
x-route: model-api-rust
Transfer-Encoding: chunked
Connection: keep-alive
```

The complete response body, with only the generated completion identifier
redacted, was:

```text
data: {"id":"<redacted-completion-id>","choices":[{"delta":{},"finish_reason":"stop","index":0}],"created":1785786496,"model":"muse-spark-1.1","object":"chat.completion.chunk"}

data: [DONE]
```

## Findings

- `muse-spark-1.1` was accepted at the OpenAI-compatible
  `/v1/chat/completions` endpoint and echoed as the response `model`.
- The response media type was `text/event-stream`. Each observed event used an
  SSE `data:` line; termination was the literal `data: [DONE]` sentinel.
- The JSON event used the top-level fields `id`, `choices`, `created`, `model`,
  and `object`. The only choice used `delta`, `finish_reason`, and `index`.
- The event carried an empty `delta` and `finish_reason` equal to `stop`; no
  visible content was returned. This evidence does not establish why.
- No `usage` field or separate usage event appeared, even though the request
  sent `stream_options.include_usage` as `true`. Therefore this probe does
  **not** establish any Muse usage field names, and no usage schema should be
  declared from inference. The absence itself is the observed contract for
  this request shape.
- The response exposed the paid-tier limits documented by the design: 3,000
  requests per minute and 4,000,000 tokens per minute. The headers do not
  establish pricing.

## Registry consequence

The model row may declare the verified model identifier, endpoint-compatible
provider relationship, context/modalities, and documented economics from the
design's primary-source retrieval. It must not claim provider usage field
names based on this probe. Durable attempt usage remains the provider-owned
open map, while settlement gauges can update only from values Seon's existing
normalizer actually derives.

## Tool and render feedback

The raw SSE form was compact and readable: one JSON chunk plus the conventional
`[DONE]` sentinel. The absence of any usage event is easy to miss if a consumer
looks only for content deltas; provider diagnostics should preserve the full
event stream or explicitly report that requested usage was absent.
