---
type: issue
status: resolved
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

- The agent profile remains active for AI rendering but never governs an HTML
  page target.
- Message and thinking-stream web proofs pass with the profile active.
- A regression proves the HTML transcript surface survives while the AI target
  retains its bounded profile.

## Resolution

Commit `3479a5a50` made render profile selection target-aware at the single
render-argument seam. The declared agent profile continues to govern
`:seon.render/ai`; `:seon.render/html` receives no fit profile until Phase 0 of
the transcript PRD declares the page profile. The walk and transcript producer
remain unchanged.

The behavioral regression is
`the-html-page-keeps-the-transcript-outside-the-agent-profile`: the real HTML
page must contain `surface-transcript` while the service has an active agent
profile. The two original red proofs also pass:

- `bin/test seon.render.web-test`: 38 tests, 330 assertions, zero failures and
  zero errors; and
- `bin/test --changed src/seon/render.clj --changed
  test/seon/render/web_test.clj`: 209 tests, 2,516 assertions, zero failures and
  zero errors (62 declared long tests skipped by the changed tier).
