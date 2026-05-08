---
title: Qwen Code (CLI) — agentic terminal harness from the Qwen team
source-url: https://github.com/QwenLM/qwen-code
retrieved: 2026-05-07
fetched-via: WebFetch
---

# Qwen Code — terminal-first agentic harness

Qwen Code is an open-source agent CLI for the Qwen3 / Qwen3-Coder family.
Equivalent in spirit to Anthropic's Claude Code or OpenAI's Codex CLI; lineage
is a fork of Google's Gemini CLI tooling adapted for Qwen models.

## What it exposes

- **Interactive shell mode** (`qwen`) and **headless** (`qwen -p "..."`).
- File-context references via `@file/path` syntax.
- **Skills and SubAgents** for agentic workflows (Skill = re-usable tool
  bundle; SubAgent = spawned worker scoped to a sub-task).
- **YOLO mode** — disables some safety prompts; auto-image-handling.
- IDE integrations: VS Code, Zed, JetBrains.
- SDKs: TypeScript, Python, Java.
- Project config at `.qwen/settings.json`.

## What it implies about Qwen3-Coder's training prior

The Qwen team built Qwen Code to mirror the agent loop they trained against.
Useful inferences:
- The model expects a **tool-loop structure**: assistant emits tool call →
  harness executes → tool result returned → assistant continues.
- File-system + shell are first-class environments. Browser is a separate
  add-on (Qwen-Agent's BrowserQwen, not the CLI default).
- "Skills" = reusable tool packs — the Qwen team appears to have trained on
  a flexible-tool-pack interface, not a fixed-tool API.

## Caveats

- The README does NOT spell out the wire format of the tool calls — that is
  determined by the underlying model + Qwen-Agent / vLLM tool-parser layer.
  Treat Qwen-Agent's "nous" template (file 03) as the wire-level truth.
- Qwen Code itself does not provide training data or training scripts; it is
  inference-time only.

## Phase-0 implication

For the agent's harness, Qwen Code is useful as a **reference implementation of
the tool-loop the model expects**, not as a code dependency. Mirror its
shape (tool emit → execute → return → continue, with skill/subagent
patterns), then expose the agent's primitives (`assert`/`retract`/`query`/
`project`) as tools in that loop.

## Links

- Repo: https://github.com/QwenLM/qwen-code
- Settings docs: https://qwen.readthedocs.io/en/latest/
