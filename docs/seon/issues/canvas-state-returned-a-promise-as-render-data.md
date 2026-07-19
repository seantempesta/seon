---
type: issue
status: active
tags: [issue, cljs, database, web]
---

# Canvas state returned a Promise as render data

## Evidence

The exact source-free canvas control fixture rendered this value in its visible
state paragraph:

```text
#object[Promise [object Promise]]
```

`my.canvas/state` retained its former synchronous signature after `seon.db/pull`
became the asynchronous JVM-authority interface. It returned that Promise
directly while its Malli contract and the maintained `ui-canvas` skill still
described an ordinary map. Authored renderers and handlers therefore treated a
database request as state data.

## Expected owner

`my.canvas/state` is the one common agent-local read helper. It is explicitly
`^:async`, awaits `seon.db/pull`, and resolves to the same ordinary map shape.
Renderers and handlers that need state are `^:async` and await the helper. There
is no synchronous cache, alternate canvas API, or Promise-aware renderer.

## Acceptance criteria

- `my.canvas/state` resolves to an ordinary map and never exposes its Promise as
  render data.
- The maintained canvas skill shows `^:async` renderers and awaited state.
- Button and form handlers await state before deriving a transaction.
- The real canvas control matrix renders database values, and input, select,
  toggle, button, and form interactions update through the one Datastar feed.

## Implementation evidence

The helper and skill now expose the asynchronous contract directly. Focused
canvas proof passes 6 tests and 22 assertions, including a delayed remote pull
that must resolve before state is returned. Exact self-host program loading and
the browser control matrix remain open.
