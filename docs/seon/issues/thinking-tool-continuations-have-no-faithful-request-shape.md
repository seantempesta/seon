---
type: issue
status: open
severity: friction
tags: [issue, ai, architecture]
---

# Give thinking tool continuations one faithful request shape

## Problem

Fresh `seon.ai` represents one prompt and one visible completion. Its open
request and completion schemas have no assistant message history, tool calls,
tool results, or reasoning content, so a DeepSeek thinking-mode tool
continuation cannot preserve the provider's required context.

## Evidence

`src/seon/ai.cljc` builds only system/user messages and projects only visible
assistant content. The official contract captured in
`docs/prds/sci-execution-runtime/research/deepseek-thinking-mode-api-2026-08-01.md`
requires the complete assistant message, including `reasoning_content`, after a
tool call to be replayed in every subsequent request. The dated live proof in
`deepseek-thinking-live-proof-2026-08-01.md` found Flash temporarily permissive
when reasoning was omitted; that does not weaken the documented contract.

## Owner

`seon.ai` owns one future provider-message and tool-continuation shape. The
agent loop must not invent a parallel history or reconstruct reasoning from
visible reply text.

## Acceptance

- One open request/completion schema represents assistant tool-call messages,
  tool results, visible content, and complete reasoning content.
- Every request following an assistant tool call replays that complete
  assistant message, including non-null content and `reasoning_content`.
- A no-tool assistant turn can omit reasoning from later context.
- Recorded response fixtures prove both branches, and a dated live DeepSeek
  probe verifies the complete-replay request through the real HTTP owner.
- The ordinary one-shot path remains unchanged when no tools are present.
