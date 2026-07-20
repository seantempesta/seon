---
type: issue
status: open
severity: cleanup
tags: [issue, architecture, web, database]
---

# Move product routes out of the static router supplement

## Problem

The active router has two route authorities: database-derived
`:seon.route/*` rows and a hard-coded static supplement. The supplement is
larger than the unavoidable pre-database boundary and includes product,
lifecycle, debug, and operator routes.

## Evidence

`src/seon/web/router.cljs:228-305` declares `/data`, its live endpoint,
`/chat`, `/stop`, `/resume`, `/clear`, `/log`, `/agents/run`, agent completion,
configuration apply, and four operator evidence/control routes as literal
reitit rows. Its own comment says the secondary POST doors should become route
facts. `src/seon/route.cljs:89-113` separately owns the seeded database route
population.

The target architecture says the router is a pure value derived from
`:seon.route/*` datoms. Static assets and the pre-admission readiness endpoint
may require a bootstrap route, but that does not justify a second product-route
registry.

## Owner

`seon.route` owns declared route data and `seon.web.router` owns its reitit
projection. Launch-bound optional operator capabilities must translate
directly at this boundary rather than remaining an unrelated literal route
catalog.

## Acceptance

- Every current static-supplement row is classified as database route,
  launch-bound operator route, or unavoidable pre-database bootstrap route.
- Product, lifecycle, debug, and data-browser routes are declared once as
  route data and projected through `db->routes`.
- Optional operator routes derive from explicit launch capabilities without
  becoming agent-writable grants.
- Static code retains only assets and proven pre-database health/admission
  endpoints.
- Route conflict, middleware, reverse-routing, config reconciliation, and live
  router-refresh tests cover the migrated rows.
- A source scan finds no second literal product-route catalog.
