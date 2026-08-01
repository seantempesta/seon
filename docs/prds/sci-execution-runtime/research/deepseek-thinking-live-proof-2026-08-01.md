---
type: research
status: active
tags: [research, ai, live-proof]
---

# DeepSeek v4 thinking-mode live proof

## Verdict

The official OpenAI-format contract and the live
`deepseek-v4-flash` endpoint agree on the request toggle, separate reasoning
fields, usage accounting, and starvation shape. Thinking is enabled when the
`thinking` field is absent. Explicit disable removes reasoning from both the
message and usage. Explicit enable accepts `low`, `high`, `xhigh`, and `max`.

The live endpoint did **not** enforce one documented sharp edge: two separate
tool-call continuations that omitted the preceding assistant
`reasoning_content` returned HTTP 200 and correct subsequent results, while
the official page says such a request returns HTTP 400. This is not grounds to
discard reasoning. The vendor contract requires complete replay after a tool
call, and a permissive deployment today does not guarantee future or
cross-model acceptance.

The current fresh `seon.ai` owner cannot express any thinking state or effort.
Its streaming parser correctly ignores reasoning deltas rather than publishing
them as reply text, but its non-streaming parser drops usage and finish reason.
Neither path retains an assistant message suitable for thinking-mode tool
continuation.

## Dependency ledger and method

- Vendor boundary: raw OpenAI-format Chat Completions HTTP at
  `https://api.deepseek.com/chat/completions`, model
  `deepseek-v4-flash`.
- Official sources: the live [Thinking Mode guide](https://api-docs.deepseek.com/guides/thinking_mode)
  and [Create Chat Completion reference](https://api-docs.deepseek.com/api/create-chat-completion).
  The guide returned HTTP 200 as `text/html` on 2026-08-01. Its rendered HTML
  contains the model-specific effort table captured in
  `deepseek-thinking-mode-api-2026-08-01.md`.
- First-party owner inspected: `src/seon/ai.cljc` at repository HEAD
  `393198915`, especially `targets` (lines 93-137), `request-body`
  (204-226), `stream-event` (228-272), `completion-text` (298-319), and
  `streamed-completion` (429-458). Recurring proof owner:
  `test/seon/ai_test.clj`.
- The credential was sourced from `.env` into `DEEPSEEK_API_KEY` only for the
  process running each probe. It was never printed, copied into a request
  fixture, or committed.
- All reported response bodies are sanitized summaries. Reasoning text, API
  credentials, response IDs, and tool-call IDs are omitted. The exact local
  raw responses remain only in ignored `tmp/deepseek-thinking-live/` for
  short-term review.

The ordinary prompt was `Reply with exactly OK.`. Each probe made one paid
call unless explicitly described as a continuation pair. Token counts are
provider-reported, not estimates.

## Official contract fetched live

For raw OpenAI-format HTTP, the body fields are direct JSON fields:

```json
{"thinking":{"type":"enabled"},"reasoning_effort":"high"}
```

```json
{"thinking":{"type":"disabled"}}
```

Omitting `thinking` means enabled, with default effort `high`. The current
model-specific effort table is:

| Requested | v4 Flash actual | v4 Pro actual |
|---|---|---|
| `low` | `low` | `high` |
| `high` | `high` | `high` |
| `xhigh` | `high` | `max` |
| `max` | `max` | `max` |

The guide says the v4 Pro mapping is scheduled to change in early August
2026. The direct page HTML carried this table on the probe date; a search
index excerpt still exposed an older generic mapping, so implementations
should cite the direct guide rather than cached search text.

In thinking mode, `temperature`, `top_p`, `presence_penalty`, and
`frequency_penalty` are silently ignored. `reasoning_content` is a sibling of
`content` in both an ordinary assistant message and each streaming delta.
After an assistant tool call, the complete `reasoning_content` must remain on
that assistant message in every subsequent request. On the no-tool path, the
server ignores replayed reasoning.

## Toggle and effort probes

| Request variant | HTTP | Message fields | Visible content | Reasoning | Finish | Usage evidence |
|---|---:|---|---|---:|---|---|
| `thinking.disabled` | 200 | `content`, `role` | `OK.` | absent | `stop` | 2 completion tokens; no `completion_tokens_details` |
| `thinking` absent | 200 | `content`, `reasoning_content`, `role` | `OK` | 42 characters | `stop` | 13 completion, 11 reasoning tokens |
| enabled + `low` | 200 | content + reasoning | `OK` | 49 characters | `stop` | 14 completion, 12 reasoning tokens |
| enabled + `high` | 200 | content + reasoning | `OK` | 40 characters | `stop` | 13 completion, 11 reasoning tokens |
| enabled + `xhigh` | 200 | content + reasoning | `OK` | 115 characters | `stop` | 29 completion, 27 reasoning tokens |
| enabled + `max` | 200 | content + reasoning | `OK` | 96 characters | `stop` | 22 completion, 20 reasoning tokens |

These calls prove request acceptance and response shape. They do not expose a
machine-readable "actual effort" field, so they cannot independently prove
the vendor's internal effort mappings. Reasoning length is nondeterministic
and is not an effort oracle.

The absent request is the decisive default-on falsifier:

```json
{
  "model": "deepseek-v4-flash",
  "messages": [{"role": "user", "content": "Reply with exactly OK."}],
  "max_tokens": 256
}
```

Its sanitized response shape was:

```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "reasoning_content": "<42 characters omitted>",
      "content": "OK"
    },
    "finish_reason": "stop"
  }],
  "usage": {
    "prompt_tokens": 88,
    "completion_tokens": 13,
    "total_tokens": 101,
    "completion_tokens_details": {"reasoning_tokens": 11}
  }
}
```

Explicit disable returned no `reasoning_content` key and no reasoning-token
detail. That is stronger evidence that thinking was actually disabled than an
empty reasoning string would have been.

## Sampling fields in thinking mode

One enabled request sent all four unsupported controls together:

```json
{
  "thinking": {"type": "enabled"},
  "reasoning_effort": "high",
  "temperature": 0.7,
  "top_p": 0.5,
  "presence_penalty": 0.25,
  "frequency_penalty": 0.25
}
```

It returned HTTP 200, visible `OK`, `finish_reason:"stop"`, and 23 reasoning
tokens out of 25 completion tokens. This verifies the documented
compatibility behavior that the fields do not cause rejection. A black-box
single sample cannot prove that each field had no statistical effect; the
official guide is the authority for the stronger "silently ignored" claim.
The request builder should omit these fields whenever thinking is explicitly
enabled or absent/default-on, so recorded configuration never claims control
the provider says it ignores.

## Non-streaming and streaming response shapes

The non-streaming response above places `reasoning_content` beside `content`
and reports reasoning under
`usage.completion_tokens_details.reasoning_tokens`. There is no top-level
`reasoning_tokens` field in the observed response.

The equivalent streaming request returned HTTP 200 and 27 JSON SSE chunks:

- 24 chunks carried non-empty `delta.reasoning_content`, totaling 105
  characters;
- one chunk carried visible `delta.content:"OK"`;
- the terminal chunk carried `finish_reason:"stop"` and cumulative usage;
- terminal usage was 26 completion tokens, of which 24 were reasoning tokens;
  and
- every observed reasoning delta was a sibling of `delta.content`, never
  embedded in content.

Sanitized chunk shapes:

```json
{"choices":[{"delta":{"reasoning_content":"<omitted>","content":null},"finish_reason":null}],"usage":null}
```

```json
{"choices":[{"delta":{"reasoning_content":"","content":"OK"},"finish_reason":null}],"usage":null}
```

```json
{"choices":[{"delta":{"reasoning_content":"","content":""},"finish_reason":"stop"}],"usage":{"completion_tokens":26,"completion_tokens_details":{"reasoning_tokens":24}}}
```

## Token-starvation shape

An enabled/high request asked for a small calculation but set
`max_tokens:1`. It returned HTTP 200 rather than a provider error:

```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "reasoning_content": "<2 characters omitted>",
      "content": ""
    },
    "finish_reason": "length"
  }],
  "usage": {
    "prompt_tokens": 97,
    "completion_tokens": 1,
    "total_tokens": 98,
    "completion_tokens_details": {"reasoning_tokens": 1}
  }
}
```

Thus the observed starvation predicate is not merely empty text. It is empty
visible `content`, terminal `finish_reason:"length"`, and all completion
tokens consumed by reasoning. It must be a failure value with finish and
usage evidence, never a successful empty reply.

## Multi-turn reasoning replay

### No-tool path

Two otherwise identical second-turn requests replayed an ordinary assistant
message with and without its 42-character `reasoning_content`. Both returned
HTTP 200 and visible `SECOND.`. Both reported exactly 98 prompt tokens,
despite the extra reasoning bytes in the full request. This is direct evidence
that prior no-tool reasoning was ignored rather than charged as prompt
context, matching the guide.

### Tool-call path

The deterministic initial request declared `get_magic_number` and told the
model to use it exactly once. It returned:

```json
{
  "message": {
    "role": "assistant",
    "content": "",
    "reasoning_content": "<107 characters omitted>",
    "tool_calls": [{
      "type": "function",
      "function": {"name": "get_magic_number", "arguments": "{}"}
    }]
  },
  "finish_reason": "tool_calls",
  "usage": {
    "completion_tokens": 53,
    "completion_tokens_details": {"reasoning_tokens": 23}
  }
}
```

A continuation replaying the complete assistant message plus tool result
`42` returned HTTP 200 and visible `42`. Contrary to the documented 400, an
otherwise identical continuation with `reasoning_content` deleted also
returned HTTP 200 and visible `42`.

A stricter sequential probe required `get_secret`, then a second
`confirm_secret` tool whose argument depended on the first result. The first
assistant message carried 136 reasoning characters and called only
`get_secret`. Both the complete replay and a replay with reasoning deleted
returned HTTP 200 and correctly called `confirm_secret` with the supplied
secret. This independently falsifies enforcement on the live v4 Flash
deployment; it does not falsify the documented semantic requirement.

The safe contract remains: retain and replay the complete assistant message,
including reasoning, whenever that message carries tool calls. Tests should
pin the documented required shape, not the current server's permissiveness.

## First-party defects found before repair

1. **No request control reaches the wire.** `targets` projects endpoint,
   model, max tokens, timeout, and authentication only. `request-body` has no
   `thinking` or `reasoning_effort`. The closed schemas in
   `resources/seon/schema/ai.edn` likewise expose no thinking field. Therefore
   current config cannot express explicit on, explicit off, or chosen effort;
   every v4 Flash call silently inherits provider default-on/high.
2. **The current default is vulnerable to thinking starvation.** The shipped
   8,192 cap includes hidden reasoning tokens. The live one-token probe proves
   the exact failure shape behind the prior 4,096-token scar. The requested
   interim 65,536 cap is warranted until the separate Flash calibration lands.
3. **Non-stream parsing loses decisive evidence.** `completion-text` reads
   only `choices[0].message.content`, returning only `:seon.ai/text` on
   success. It therefore discards `reasoning_content`, `finish_reason`, and
   all usage, including `reasoning_tokens`. It already rejects empty content,
   so starvation is not an empty happy reply, but it cannot report why the
   response failed or persist correct receipt evidence.
4. **Streaming does not leak reasoning into visible text.** `stream-event`
   appends only `delta.content`; the observed provider keeps reasoning solely
   in `delta.reasoning_content`. The suspected reasoning-as-reply UI bug is
   not present in this fresh owner. The fold does retain terminal usage,
   including nested reasoning tokens. It does not retain finish reason, so an
   all-reasoning length stop becomes a generic empty-stream error without the
   decisive evidence.
5. **Tool continuation has no faithful representation.** The current closed
   request/completion schemas and parsers have no `tool_calls`, assistant
   message, or reasoning fields. Any continuation assembled from only
   `:seon.ai/text` necessarily violates the documented replay contract after
   a thinking-mode tool call, even though the probed v4 Flash deployment was
   temporarily permissive.
6. **Ignored controls must be structurally omitted.** The fresh request
   builder currently sends none of the four ignored sampling fields, which is
   correct for the default-on model. Any restored temperature/config plumbing
   must conditionally omit temperature, top-p, and penalties whenever
   thinking is enabled or absent for v4 Flash.

## Recurring and live acceptance evidence required

- Recorded ordinary and SSE fixtures carry sibling reasoning/content fields,
  nested reasoning-token usage, and terminal finish reason; reasoning never
  enters `:seon.ai/text` or its sink snapshots.
- Explicit enabled with each admitted effort, explicit disabled, and absent
  each build the exact documented JSON shape.
- A recorded `length` + empty-content + reasoning-only response becomes a
  failure retaining finish and usage evidence.
- A recorded assistant tool call is replayed byte-for-byte with
  `reasoning_content` before its tool result and in subsequent turns.
- The real-door proof demonstrates one enabled turn with reasoning present,
  complete visible reply, and receipt usage, plus one disabled turn with no
  reasoning field or reasoning-token detail.

## Integrated scratch-cluster proof after repair

Commit `c1af16c89` was loaded into one operator process hosting three sovereign
scratch clusters. Each cluster received its thinking choice through a sparse
config manifest, committed an outside message to `root` through the web POST
route, called `deepseek-v4-flash` through `seon.ai/complete`, evaluated the
returned form where possible, and committed the model attempt as database
facts. No credential or reasoning text was printed or retained.

| Cluster | Config facts | Durable attempt | Terminal result |
|---|---|---|---|
| `codex-thinking-on` | thinking `:high`, max 512 | `stop`; 140 completion tokens, including 128 reasoning tokens | eval receipt completed with `THINKING_ON_OK`; run closed |
| `codex-thinking-off` | thinking `:disabled`, max 128 | `stop`; 12 completion tokens and no `completion_tokens_details` | eval receipt completed with `THINKING_OFF_OK`; run closed |
| `codex-thinking-starved` | thinking `:high`, max 1 | `length`; one completion token and one reasoning token; attempt points to `:seon.ai/token-starvation` | no eval receipt was invented; run closed with the starvation message |

The usage map remained provider-owned EDN. `finish_reason` persisted separately
as `:seon.ai.attempt/finish-reason`, including on the failed attempt. The
starvation error fact retained the complete usage and finish evidence in its
data while the attempt row provided the direct queryable receipt projection.

## Limitations

- The probes establish the v4 Flash behavior observed on 2026-08-01, not a
  timeless provider guarantee or v4 Pro behavior.
- The API exposes no actual-effort receipt, so model-specific effort mapping
  remains documentation-grounded rather than empirically distinguishable.
- No reasoning text is retained in this report. Character and token counts are
  sufficient to prove separation without publishing chain-of-thought.
- Tool-replay omission being accepted is explicitly a contradiction between
  the live deployment and current official prose. Compatibility code must
  follow the stricter prose contract.
