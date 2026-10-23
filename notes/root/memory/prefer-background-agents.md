---
name: prefer-background-agents
description: "Delegated sessions go in the \"seon-jolt\" sidebar group so Sean can follow and chat with them"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 21e07b71-4cb1-4e53-a41e-5be4db8f99e1
  modified: 2026-10-22T23:18:07.333Z
---

Separate worktree sessions are fine (Sean: "technically better that I can chat with them directly and see what they are doing"), but file every session I start into the sidebar group **seon-jolt** (`mcp__ccd_sidebar__move_sessions`, group found by `list_groups`) right after starting it.

**Why:** sessions run in `next-NAME` worktree folders, so ungrouped they scatter across projects in his interface and he couldn't follow them (2026-10-23).

**How to apply:** short read-only research still goes to background Agent-tool subagents; code work in a worktree may be a session, then grouped at once. See [[kill-only-verified-pids]].

**Models (Sean, 2026-10-23: "appropriate models for the task"):** pass `model` explicitly. Opus for design, dependency (Jolt/Chez/Datahike) and hard debugging; Sonnet for reviews against a checklist, docs, cleanup, merging-queue holders and mechanical edits; Haiku or Sonnet for browser testers. Switch a live session with `set_session_model` rather than restarting it (keeps its context).
