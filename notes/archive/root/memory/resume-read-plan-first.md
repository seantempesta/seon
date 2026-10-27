---
name: resume-read-plan-first
description: "After a context reset, read the plan and docs before answering any session message"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 21e07b71-4cb1-4e53-a41e-5be4db8f99e1
  modified: 2026-10-23T04:09:58.322Z
---

On resume after a compaction, before replying to any session note, read: AGENTS.md, docs/plan.md (Now and In flight), docs/decisions.md, and root's schedule (notes/root-schedule-2026-10-23.md in the root worktree). Then act.

**Why:** the owner (2026-10-23): "on resume make sure you read the full plan and all relevant docs so you aren't just responding to things without understanding."

**How to apply:** the first tool calls after a resume are those reads; only then continue the merge queue. Related: [[serialize-heavy-runs]], [[prefer-background-agents]].
