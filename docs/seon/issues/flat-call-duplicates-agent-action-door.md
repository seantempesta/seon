---
type: issue
status: open
severity: cleanup
tags: [issue, architecture, web, agent]
---

# Remove the flat call compatibility door

## Problem

The web UI has two public POST addresses for the same capability handler.
The database-seeded route `/agent/{id}/call` is the documented action door,
while the static router still publishes `/call` as a compatibility route.
Keeping both makes route ownership ambiguous and preserves an address that
cannot express the owning agent.

## Evidence

- `src/seon/route.cljs:74-80,116-118` calls `/agent/{id}/call` the one action
  door and seeds it as database route data.
- `src/seon/web/router.cljs:264-272,324-328` explicitly labels `/call` as
  back-compat and sends it to the same `seon.web.reactive.call/handle!` owner.
- `src/seon/web/reactive/transform.cljs:121-147` normally derives the owning
  agent from a `my.agent.*` namespace, but falls back to `/call` for any other
  namespace even though its own docstring says the capability gate refuses
  that request.
- Current first-party canvas generation and route tests name the agent-scoped
  path; the flat route is not the canonical client contract.

## Owner

`seon.route` owns the one database-derived public route. The render transform
may generate only that route after it proves an agent owner. The existing
`seon.web.reactive.call/handle!` remains the one capability handler; this issue
does not authorize a second handler or compatibility namespace.

## Acceptance

- Generated actions for supported agent functions contain only
  `/agent/{id}/call`.
- A function without an owning agent produces an established render error or
  no action; it never emits a knowingly refused `/call` request.
- `/call` is absent from the static router and returns no callable
  compatibility behavior.
- The database-seeded agent route retains same-origin admission and the
  existing capability gate.
- Focused route/transform tests and one rendered canvas prove the canonical
  address from one frozen source digest.
