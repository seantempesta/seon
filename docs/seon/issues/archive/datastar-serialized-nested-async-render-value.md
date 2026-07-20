---
type: issue
status: resolved
tags:
  - web
  - rendering
  - async
severity: friction
tags: [issue]
---

# Datastar serialized a nested asynchronous render value

## Failure

The shared Datastar feed tested native JavaScript `.then` with ClojureScript's
`fn?`. Native Bun functions do not implement ClojureScript `IFn`, so genuine
Promises were not recognized and the serializer emitted
`elements [object Promise]` instead of HTML.

## Resolution

The one feed renderer now recognizes native `.then` functions with JavaScript
type semantics and settles every asynchronous render layer before retaining or
serializing an event. Plain hiccup and the existing observed render map keep
the same path; no second renderer or feed was added.

## Acceptance

- Focused Datastar tests cover a nested asynchronous render and pass under Bun.
- The supervised root feed emits rendered HTML and never Promise text.
