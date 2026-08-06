---
type: issue
status: open
severity: friction
tags: [issue, web, render, performance, live-drive]
---

# Return the namespace debug page without blocking the response

## Problem

The canonical namespace debug route performs enough synchronous derivation
before writing headers that a live request receives no first byte for several
seconds. This makes the page unavailable precisely when the operator needs it
to inspect a broken agent context.

## Evidence

On the 2026-08-06 freshly reset default cluster:

- `GET /`, `/agent/root`, and `/ns/my.agents.root` each returned 200 in under
  3 ms through curl and produced identical 518,673-byte bodies;
- `GET /ns/my.agents.root/debug` returned zero bytes before curl's 5.003 s
  cutoff; and
- an earlier JVM HTTP probe that included the debug route failed its complete
  30 s evaluation cutoff.

The latest recorded exact prompt was 135,272 characters and the ordinary
namespace page was about 519 KB, making full pre-response dual projection the
visible cost boundary. No browser was connected, so layout and console state
remain unproven; the HTTP first-byte failure is independent of either.

## Owner

`seon.render.web/debug-response` / `debug-page-of` and the retained render
bytes that should serve the debug surface.

## Acceptance

- The debug route sends a successful bounded response without recomputing a
  complete current AI walk and every HTML unit synchronously on the request
  thread.
- The left pane still satisfies the separate exact-capture requirement.
- A recurring live measurement records time to first byte and response size
  for a context at least as large as this one.
