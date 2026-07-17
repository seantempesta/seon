---
type: issue
status: resolved
severity: cleanup
tags: [issue, architecture, web, agent]
---

# Remove the flat call compatibility door

## Problem

The web UI had two public POST addresses for the same capability handler. The
database-seeded route `/agent/{id}/call` was the documented action door, while
the static router also published `/call` as a compatibility route. Keeping
both made route ownership ambiguous and preserved an address that could not
express the owning agent.

## Evidence

- `src/seon/route.cljs` calls `/agent/{id}/call` the one action door and seeds
  it as database route data.
- `src/seon/web/router.cljs` sent the static `/call` route to the same
  `seon.web.reactive.call/handle!` owner.
- `src/seon/web/reactive/transform.cljs` normally derived the owning agent from
  a `my.agent.*` namespace, but fell back to `/call` for any other namespace.
- Current first-party canvas generation and route tests name the agent-scoped
  path; the flat route was not the canonical client contract.

## Owner

`seon.route` owns the one database-derived public route. The render transform
may generate only that route after it proves an agent owner. The existing
`seon.web.reactive.call/handle!` remains the one capability handler.

## Acceptance

- Generated actions for supported agent functions contain only
  `/agent/{id}/call`.
- A function without an owning agent produces no action; it never emits a
  knowingly refused `/call` request.
- `/call` is absent from the static router and has no callable compatibility
  behavior.
- The database-seeded agent route retains same-origin admission and the
  existing capability gate.
- Focused route and transform tests prove the canonical address from one
  frozen source digest.

## Resolution

The static router no longer publishes `/call`. Render transformation now emits
an action only for functions whose namespace identifies an owning agent, and
always targets that agent's database-seeded `/agent/{id}/call` route. An
unsupported non-agent handler is omitted instead of producing a request known
to fail the capability boundary. The existing capability handler remains the
only execution owner.

## Verification

- From one coordinated frozen source digest,
  `bin/test-cljs --test=seon.web.router-test` passed 7 tests and 24 assertions.
  The retained log is `tmp/test-cljs-20260717-015736-34342.log`.
- From the same digest,
  `bin/test-cljs --test=seon.web.reactive.transform-test` passed 12 tests and
  30 assertions. Its render-transformation cases prove agent-scoped action
  generation and omission for non-agent handlers. The retained log is
  `tmp/test-cljs-20260717-015752-35775.log`.
- The router build reported five pre-existing undeclared-var warnings in
  unowned namespaces changed by the database API cut; the transform build was
  warning-free.
