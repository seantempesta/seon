---
title: BFCL + Qwen tool-call formats — the wire-level truth
source-url: https://gorilla.cs.berkeley.edu/leaderboard.html ; vLLM/SGLang qwen3_coder parser ; Hermes/nous prompt-format
retrieved: 2026-05-07
fetched-via: WebFetch + WebSearch
---

# Tool-call formats Qwen was trained against

## BFCL (Berkeley Function Calling Leaderboard)

The de-facto public benchmark for function calling. Versions:
- **v1**: AST-based eval metric.
- **v2**: enterprise + open-source contributed functions.
- **v3**: multi-turn interactions added.
- **v4**: holistic agentic evaluation.

Submission categories observed across versions: `simple`, `parallel`,
`multiple`, `parallel_multiple`, `irrelevance`, `java`, `javascript`,
`multi-turn`, `multi-step`. Submissions split into:
- **FC** — model has native tool-calling support (special tokens / chat
  template handles tool calls structurally).
- **Prompt** — prompt-template-only fallback ("walk-around for function
  calling using model's normal text generation capability").

Qwen2.5 / Qwen3 / Qwen3-Coder all submit in the **FC** lane. The Qwen team
publishes BFCL scores prominently in each release.

## Wire-level templates Qwen3 / Qwen3-Coder / Qwen3.6 expect

Three layers stack. From innermost to outermost:

### 1. The chat template

ChatML-derived. Special role tokens `<|im_start|>` / `<|im_end|>` wrap
turns. Tool-result turns use `role="tool"`.

### 2. The thinking / non-thinking switch

```
<think>
...reasoning...
</think>

...answer...
```

Token ID for `</think>` is **151668** (per Qwen3-235B model card). Code in
inference must split on this for `enable_thinking=True` outputs. Qwen3.6
adds `preserve_thinking=True` to keep the `<think>...</think>` block in the
*history* across multi-turn for agent iteration.

### 3. The tool-call format ("nous" template / Hermes-style)

Qwen-Agent default `fncall_prompt_type='nous'`. The model emits
`<tool_call>{...}</tool_call>` with a JSON object per call (parallel calls →
multiple `<tool_call>` blocks). Tool results come back as a `role="tool"`
turn with `name` and JSON content.

For Qwen3-Coder + Qwen3.6, **vLLM** uses a dedicated parser
`--tool-call-parser qwen3_coder` (and `--reasoning-parser qwen3` for the
`<think>` split). SGLang has equivalent flags. The vLLM serve line from the
Qwen3.6 model card:

```bash
vllm serve Qwen/Qwen3.6-35B-A3B \
  --reasoning-parser qwen3 \
  --enable-auto-tool-choice \
  --tool-call-parser qwen3_coder
```

This is the canonical wire-level surface — match this exactly for any the agent
harness primitive, and the model's training prior is preserved.

## Tool definition shape (OpenAI-compatible)

Qwen3 / Qwen3-Coder accept OpenAI-style `tools=[{type:"function", function:
{name, description, parameters:JSONSchema}}]` over the OpenAI-compatible
endpoint. Qwen-Agent's `BaseTool` subclass is a thin Python wrapper that
serializes to the same shape.

## MCP

Qwen3.6 model card explicitly demonstrates tool-loading via MCP servers (the
`mcpServers` block). MCP is the actively-supported extensibility surface —
the agent primitives could be exposed as an MCP server for free Qwen
compatibility, no harness-specific glue required.

## Phase-0 implication

For the agent's harness, the cheapest path that lands on the model's training
prior:
1. Run Qwen3.6-35B-A3B (or Qwen3-Coder family) in vLLM with
   `--reasoning-parser qwen3 --tool-call-parser qwen3_coder`.
2. Expose the agent primitives (assert/retract/query/project) as either:
   - OpenAI-compatible function-call tools at the API layer, OR
   - An MCP server (preferred — explicitly trained-against in Qwen3.6).
3. Match BFCL v3/v4's multi-turn shape: each turn is a chat-template message
   with role in {system, user, assistant, tool}; assistant turns can emit
   parallel `<tool_call>` blocks.

## Links

- BFCL: https://gorilla.cs.berkeley.edu/leaderboard.html
- Qwen-Agent fncall prompts: https://github.com/QwenLM/Qwen-Agent/tree/main/qwen_agent/llm/fncall_prompts
- vLLM tool-call parsers: https://docs.vllm.ai/en/latest/features/tool_calling.html
- Hermes function-call format: https://github.com/NousResearch/Hermes-Function-Calling
