---
type: issue
status: complete
tags: [agent, flow, issue]
---

# Provider bridges dropped the frozen system prompt

## Evidence

The compiled execution child returned only context text. The parent reread the
live config singleton for capture and token accounting, and the OpenAI-compatible
and Anthropic agent bridges rebuilt provider requests without preserving an
explicit `:seon.ai/system-prompt`. A config write between prompt acquisition and
a retry could therefore make capture, accounting, and provider bytes disagree.

## Resolution

The compiled prompt owner acquires the agent and config singleton in one
coordinate-inherited `execute-many` request and returns the established
`:seon.ai/system-prompt` with the rendered context. Turn capture, token
accounting, retry requests, dispatch, and both provider bridges preserve that
ordinary string. Missing config uses the existing shipped system text; no new
config service or cache was added.

The execution and client artifacts compile with the unchanged migration-warning
inventories. Focused child, turn, and provider-bridge regressions compile in the
test artifact; executing them remains blocked before namespace startup by the
separately recorded obsolete replica import.
