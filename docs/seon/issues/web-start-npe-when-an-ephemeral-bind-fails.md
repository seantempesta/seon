---
type: issue
status: open
severity: friction
tags: [issue, render, web]
---

# A failed ephemeral bind NPEs instead of saying what happened

## Problem

`seon.render.web/start!` handles a taken port by rebinding on port 0, but the
fallback is guarded by `(when-not (zero? wanted) …)`. When `wanted` is already
0 and the OS refuses the bind, that `when-not` yields nil, the `[server
fell-back?]` destructuring binds `server` to nil, and the next line calls
`(http/server-port nil)` — so a bind failure surfaces as a
NullPointerException with no mention of a port.

The taken-port path is deliberately loud and honest about both numbers; this
one path throws away the one fact a reader needs.

## Evidence

`src/seon/render/web.clj`, `start!`:

```clojure
[server fell-back?]
(try
  [(bind! wanted) false]
  (catch java.net.BindException _
    (when-not (zero? wanted)
      [(bind! 0) true])))
bound (http/server-port server)
```

Pre-existing; unrelated to the F2 render conversion, which changed the service
map this function receives but not its binding logic.

## Acceptance

- an ephemeral bind that fails throws an ex-info naming the attempted port and
  carrying the BindException as its cause, never an NPE;
- the taken-port fallback behaviour and its two-number report are unchanged.
