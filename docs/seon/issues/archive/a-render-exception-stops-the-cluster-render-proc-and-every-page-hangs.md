---
type: issue
status: resolved
severity: blocker
tags: [issue, flow, render, web, architecture, wave/no-crash]
---

# One render exception stops the render proc and every page then hangs silently

## Problem

A Throwable escaping one page derivation ends the cluster's single
`seon.render.web/render` proc. Nothing restarts it. From that moment:

- every namespace page, agent page and debug page returns its shell with HTTP
  200 and then never receives a paint;
- every `GET /feed/{id}` holds the connection open and writes zero bytes —
  observed for 120 s with `curl -N`, no error, no close;
- `bin/seon status` and the readiness row still report the cluster alive, and
  `runtime_status` shows the proc as `unknown` rather than dead, because an
  unanswered ping and a never-started proc are the same observation.

This is the project's named failure class in its worst form: absence of signal
read as health, plus a hang instead of a failure. The diagnosing agent gets a
page that loads and never fills, with nothing on the wire to explain it.

The fault itself IS committed — `seon.error/proc :seon.render.web/render`,
`seon.error/cid :seon.render.web/interest`, with the complete trace — so the
evidence exists; it just never reaches the surface that is now broken, and the
proc does not come back.

## Evidence, 2026-09-07

Development cluster `juniper-context` under `tmp/juniper-context-live`, pid
69815. A defect in the debug page's `referenced-entity-ids` (`(into []
distinct coll)` — the function where the transducer belongs) threw
`ClassCastException: class clojure.lang.LazySeq cannot be cast to class
clojure.lang.IFn`. The committed fault carries the whole path:

```text
clojure.core/transduce               core.clj:7028
seon.render.web/referenced-entity-ids web.clj:1240
seon.render.web/debug-found-value     web.clj:1333
seon.render.web/debug-page-result     web.clj:1878
seon.render.web/render-pass           web.clj:2325
seon.render.web/render-step           web.clj:2713
clojure.core.async.flow.impl/proc     impl.clj:305
```

After that fault (`:seon.error/at 2026-09-07T04:29:56Z`, signature
`7cd85980…`):

- `curl -N http://127.0.0.1:7766/feed/juniper?...` → 0 bytes in 120 s;
- `curl http://127.0.0.1:7766/agent/juniper` → no response before the client
  timeout, though `GET /ns/my.agents.juniper/debug` still returns its 200 shell
  in milliseconds;
- `runtime_status` proc rows: `seon.search/index` ping `reply` passes 6869,
  `seon.render.web/render` ping `unknown`, no buffers.

The render defect is fixed at `b7a78bd24`. The proc lifecycle is not: the same
class recurs for the next renderer that throws, and `:seon.config/on-core-error
:panic` (the dev dial) makes it likelier in exactly the environment where
someone is iterating on renderers.

## What the fix has to decide

The two halves are separate:

1. **The proc must survive one page.** A page derivation is per-registration
   work; one registration's Throwable should become that page's visible error
   value and leave the pass — and the proc — alive. The existing total render
   contract already says renders never throw; the render PASS needs the same
   guarantee at its own boundary.
2. **A wedged render must be loud.** A feed that cannot paint must refuse with
   a diagnostic instead of holding an open socket forever, and the proc row
   must distinguish "never answered a ping" from "not running" rather than
   reporting `unknown` for both.

Whatever is chosen, the regression is the one that fails today: a registration
whose derivation throws leaves every other page painting, and its own page
carries the fault.

## Owners

- `src/seon/render/web.clj` — `render-pass`, `page-refresh`, `render-step`,
  `settle-package!`, `feed`;
- `src/seon/flow.clj` — `var-process` and the error path into the fault
  committer;
- `src/seon/oversight.clj` — the proc ping observation.

## Resolution (2026-09-15 triage)

Commit `11176e6db` protects both page derivation and diagnostic construction. HEAD 7e35df213 `src/seon/render/web.clj:2291-2304` catches each registration's Throwable and returns `failed-page-result`, with `unreportable-page-result` if that diagnostic also throws; the reduce continues for other registrations. The reported single-page exception can no longer escape this boundary and stop every page. Verified by `git show HEAD:src/seon/render/web.clj` (render-pass). This is source proof of the named trigger, not proof against every possible Flow failure or a browser paint observation.

surface: render-debug-page
