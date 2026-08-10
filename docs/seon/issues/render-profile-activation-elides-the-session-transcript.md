---
type: issue
status: open
severity: blocker
tags: [issue, render, web, testing]
---

# Keep the session transcript reachable under the active render profile

## Problem

Supplying the agent id to neighborhood rendering activates the agent render
profile, but the resulting web package can omit the session transcript. A
settled message then appears only as an isolated message unit and a thinking
stream never publishes the expected transcript completion.

## Evidence

At clean commit `48eb25ab7`, after profile activation commit `3f382c83a`:

- `the-message-appears-on-the-page-wire-test` failed because the package did
  not contain `surface-transcript`; and
- `thinking-stream-morphs-into-the-settled-session-transcript` timed out waiting
  for its future completion.

The relevant 46-test render/web gate was green at 20:06Z with the print-face
worktree and before `3f382c83a` activated agent-id profile selection: 46 tests,
384 assertions, zero failures/errors (`tmp/orchestrator/ui-print-css-stdout.log:27705-27724`).
That directly excludes the print-face change `977f3a033` as the cause of this
class. No literal checkout of `3f382c83a^` was run, so the claim is bounded to
that recorded pre-activation gate plus the one-line activation diff. Clean-run
evidence: `tmp/full-gate-2026-08-10b.log:3712-3726`.

## Owner

Suspected owner: `seon.render` profile selection and the web render walk's
required transcript reachability. The profile-activation owner should decide
whether the profile budget, traversal priority, or required-root contract is
wrong.

## Acceptance

- Agent-profile rendering always includes the active/settled session
  transcript required by namespace pages.
- Message and thinking-stream web proofs pass with the profile active.
- A regression proves the profile remains bounded without making required
  session state budget-optional.
