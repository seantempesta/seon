---
type: issue
status: resolved
tags:
  - web
  - rendering
  - async
---

# Datastar serialized a nested asynchronous render value

## Failure

The shared Datastar feed recognized the first asynchronous return from its
render function, but the instrumented ClojureScript child boundary could
settle that value to another Promise. The serializer received the nested
Promise and emitted `elements [object Promise]` instead of HTML.

## Resolution

The one feed renderer now settles Promise-like values recursively at the
serialization boundary. Plain hiccup and the existing observed render map keep
the same path; no second renderer or feed was added.

## Acceptance

- Focused Datastar tests cover a nested asynchronous render and pass under Bun.
- The supervised root feed emits rendered HTML and never Promise text.
