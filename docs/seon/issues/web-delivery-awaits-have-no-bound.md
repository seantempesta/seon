---
type: issue
status: open
severity: friction
tags: [issue, runtime, render, wave/ui-watchability]
---

# Web delivery awaits have no bound

## Problem

Two one-shot web delivery joins can park forever: socket queue drain after an
accepted write, and the initial package requested from the render proc. The
open SSE package loop is intentionally connection-lived; these two joins are
not.

## Evidence

- `src/seon/render/web.clj:1283-1289` uses un-timed
  `CompletableFuture.join` on http-kit's drain-or-close publication.
- `src/seon/render/web.clj:1295-1318` takes render packages until the requested
  registration appears, with no bound if publication never arrives.

`src/seon/render/web.clj` was modified-uncommitted by another lane during the
2026-08-13 census and was not edited by this lane.

## Acceptance

Socket drain remains event-driven and gains a declared connection-write
backstop that closes the exact response with a loud diagnostic. Initial paint
retains its registration/package event and gains a declared render backstop
naming the registration and basis. Regressions prove a never-draining socket
and missing package cannot retain their virtual threads indefinitely.
