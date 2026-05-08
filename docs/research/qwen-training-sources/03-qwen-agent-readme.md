---
title: Qwen-Agent README — tool-call format and built-in environments
source-url: https://github.com/QwenLM/Qwen-Agent
retrieved: 2026-05-07
fetched-via: WebFetch
---

# Qwen-Agent — tool-call format and sandbox shape

This is the **canonical agent harness from the Qwen team**. The chat template
and tool-call conventions here are what the post-trained Qwen3 / Qwen3-Coder
checkpoints have been aligned against during SFT/RL.

## Tool definition shape

Tools subclass `BaseTool` with:
- `description` (natural-language)
- `parameters` (JSON-schema-style)

Agent is constructed with `function_list=[...]` and `system_instruction`.

## Function-calling template

- Default: **"nous" template** (recommended for Qwen3).
- Configurable via `generate_cfg.fncall_prompt_type`.
- Parallel function calls are supported by default.
- Tool responses are parsed from the LLM output by the agent (no separate
  JSON harness — the model emits structured text the harness parses).
- `use_raw_api=True` switches to native tool-call interface (newer
  inference-server-side handling vs. prompt-template handling).

## Built-in tools / environments

| Tool | Shape | Notes |
|------|-------|-------|
| **Code Interpreter** | Docker container, mounts working directory only | "basic sandbox isolation" — not production-safe per docs |
| **Browser Assistant (BrowserQwen)** | Chrome extension | for web browsing tasks |
| **RAG** | Document QA over up to 1M-token context | Qwen-specific 1M context support |
| **MCP** | Wraps external MCP servers | Modern path — most extensibility |

## LLM config keys (relevant to harness builders)

```python
llm_cfg = {
    'model': 'Qwen3-235B-A22B',
    'model_server': 'http://localhost:8000/v1',
    'api_key': 'EMPTY',
    'generate_cfg': {
        'top_p': 0.95,
        'fncall_prompt_type': 'nous',  # default for Qwen3
        'max_input_tokens': ...,
    },
}
```

## Phase-0 implication

If the agent Phase 0 wants tool calls to hit Qwen3's training prior:
- Use the **"nous" template** for function-calling prompt format, OR use
  `use_raw_api=True` with vLLM/SGLang's tool-call parser.
- Qwen-Agent itself is a thin wrapper — the harness the agent builds should
  emit/parse the same prompt envelope.
- **Code interpreter** is Docker-based and matches the SWE-Bench-style env
  Qwen3-Coder was trained against. This is the cheapest off-the-shelf
  alignment with Qwen's training prior.

## Where to look in the source

- `qwen_agent/llm/fncall_prompts/` — exact template strings the model was
  aligned to.
- `qwen_agent/tools/code_interpreter.py` — Docker sandbox interface.
- `qwen_agent/tools/mcp.py` — MCP integration, since Qwen3 was tuned with
  MCP-style tool-use during late post-training.

## Links

- Repo: https://github.com/QwenLM/Qwen-Agent
- Examples: under `examples/` in the repo (assistant_with_*, group_chat_*).
