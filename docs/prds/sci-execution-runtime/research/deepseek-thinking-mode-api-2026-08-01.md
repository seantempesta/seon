---
type: research
status: active
tags: [research, ai, reference]
---

# DeepSeek v4 thinking-mode API — vendor documentation capture

Captured 2026-08-01 from the owner's paste of the official DeepSeek API
documentation (current for deepseek-v4-flash version DeepSeek-V4-Flash-0731
and deepseek-v4-pro). This is the authority for Seon's request builder;
verify against the live API before relying on details the capture elides.

## Thinking mode toggle and effort control

| Control | OpenAI format | Anthropic format | Responses API format |
|---|---|---|---|
| Thinking toggle | `{"thinking": {"type": "enabled"}}` / `"disabled"` | `{"reasoning": {"effort": "none/low/high/max"}}` (`none` disables) | — |
| Effort control | `{"reasoning_effort": "low/high/max"}` | — | `{"output_config": {"effort": "low/high/max"}}` |

- Thinking mode is **enabled by default, with default effort high**.
- Requested effort → actual mapped effort:

| Requested | deepseek-v4-flash actual | deepseek-v4-pro actual |
|---|---|---|
| low | low | high |
| high | high | high |
| xhigh | high | max |
| max | max | max |

(The pro mapping is scheduled to update in early August 2026.)

- **OpenAI SDK gotcha:** `thinking` must ride `extra_body` — it is not a
  first-class SDK kwarg:

```python
response = client.chat.completions.create(
    model="deepseek-v4-pro",
    reasoning_effort="high",
    extra_body={"thinking": {"type": "enabled"}},
)
```

(For Seon's own HTTP client the field goes straight in the request JSON —
`extra_body` is an SDK artifact.)

## Parameters silently ignored in thinking mode

`temperature`, `top_p`, `presence_penalty`, `frequency_penalty` are **not
supported in thinking mode — setting them does NOT error, it silently has
no effect** (compatibility behavior). A builder that sends temperature in
thinking mode is lying to its caller about control it does not have.

## Response shape

Chain-of-thought returns via **`reasoning_content`, at the same level as
`content`** on the assistant message (both in non-streaming responses and
streaming deltas).

## Multi-turn concatenation rules (the sharp edge)

- **No tool call in the intermediate assistant turn:** its
  `reasoning_content` does NOT need to be concatenated into subsequent
  context; if passed anyway, the API ignores it.
- **Tool call in the intermediate assistant turn:** its
  `reasoning_content` MUST participate in context concatenation and MUST
  be passed back to the API **in all subsequent user interaction turns**.

This asymmetry is the correctness trap for Seon's continuation path: a
turn loop that strips reasoning_content uniformly breaks tool-call
continuations; one that replays it uniformly merely wastes tokens on the
no-tool path (ignored server-side).

## Vendor sample (non-streaming, context carry)

```python
messages = [{"role": "user", "content": "9.11 and 9.8, which is greater?"}]
response = client.chat.completions.create(
    model="deepseek-v4-pro", messages=messages,
    reasoning_effort="high",
    extra_body={"thinking": {"type": "enabled"}})
reasoning_content = response.choices[0].message.reasoning_content
content = response.choices[0].message.content
# Turn 2 — reasoning_content ignored by the API on the no-tool path
messages.append(response.choices[0].message)
messages.append({"role": "user", "content": "How many Rs in 'strawberry'?"})
```
