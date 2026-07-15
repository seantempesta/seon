---
type: issue
status: open
severity: friction
tags: [issue, test, tooling, worktree]
---

# Edit-hook feedback can target a different checkout

## Problem

Codex edit-hook and preloaded development-tool registrations are fixed when a
task starts. When work later moves into a Git worktree, edits can continue
without advancing that worktree's changed-test report while feedback still
belongs to the primary checkout. The task can incorrectly infer that automatic
feedback covered its actual files.

## Evidence

On 2026-07-14 edits under
`/Users/sean/src/seon-acme-agentic-tool-refinement` did not advance that
checkout's `tmp/test-changed/latest`, while the main checkout's hook remained
active. Repository-rooted MCP evaluation also required an explicitly launched
worktree server rather than the task's preloaded main-checkout registration.
The development MCP adapter itself supports explicit checkout and cluster
selection; task-level registration and edit-hook rooting are the remaining
mismatch.

## Owner

Codex task startup/edit-hook registration plus the repository development-tool
bootstrap that declares the selected checkout.

## Acceptance

- A task clearly reports which checkout owns automatic changed-test feedback.
- Moving into a worktree either re-roots the hook/tool registration or reports
  that automatic feedback is unavailable there.
- No worktree edit is represented as tested by a report produced for another
  checkout.
