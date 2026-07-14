---
type: issue
status: open
severity: friction
tags: [issue, web, agent, flow]
---

# Canvas controls hide pending and handler failure

## Problem

`my.canvas` buttons and forms expose neither in-flight state nor a visible
structured failure. A slow action remains clickable, and a handler failure can
leave the human looking at an unchanged control with no indication that the
request completed unsuccessfully.

## Evidence

`my.canvas/button` and the form submit button carry static classes only; they
have no pending/disabled/error data. `seon.web.reactive.transform` rewrites the
handler to a default Datastar `@post`. The exact shipped Datastar source emits
`started`, `error`, and `finished` events for that request, but the generated
control does not consume them.

`seon.web.reactive.call/handle!` writes a 422 JSON response after `invoke!`
returns an error and logs it. Because the failed handler wrote no database fact,
the ordinary feed has no new value to morph. The focused 20-test/61-assertion
canvas/transform/call baseline covers construction, data refusal, capability
checks, and one successful transaction, but no visible pending/failure or rapid
duplicate-submit behavior.

## Owner

The one `my.canvas` control contract plus
`seon.web.reactive.transform`/`seon.web.reactive.call`. The solution must reuse
Datastar lifecycle events, the standard `:seon/error` value, and the existing
database feed; it must not add a canvas action bus, second renderer, or stored
presentation status.

## Acceptance

- A running action has one explicit visible/disabled behavior and cannot
  accidentally duplicate a non-idempotent effect.
- Handler and validation failures render a bounded structured error at the
  relevant control while preserving the raw error evidence.
- A failure does not transact partial domain data, wedge the feed, or prevent a
  corrected retry and subsequent action.
- Success still updates through the ordinary transaction listener/render-unit
  feed with no redundant action response morph.
- Focused tests plus narrow/wide real-browser interaction prove pending,
  success, failure, retry, and rapid duplicate submission.
