---
type: issue
status: resolved
severity: friction
tags: [issue, render, web]
---

# Give debug pages the real live-process set

## Problem

The debug page supplies an empty live-process set. That value means every held
run is dead, so debug can invent wedged-run problems rather than reporting that
liveness evidence is unavailable.

This finding is **in flight (schema-edn-consolidation lane)**.

## Evidence

- `src/seon/render/web.clj:306-321` says the web owner must supply the real set
  and must neither invent `#{}` nor assume processes alive.
- `src/seon/render/web.clj:457-472` nevertheless calls `page-of` with
  `:seon.cluster.run/live-processes #{}`.
- `src/seon/problems.clj:391-423` states that an empty set invents problems and
  that an absent input must render a missing-input card.
- `test/seon/render/web_test.clj:347-365` checks debug structure but not liveness
  truth.

## Owner

`seon.render.web/debug-page-of` and the existing process-liveness input used by
ordinary namespace pages.

## Acceptance

A debug page receives the observed live set or omits it and renders the honest
missing-input state. A held live run and a held dead run are both falsified
through the debug route.

## N5 disposition — deferred 2026-08-12

`src/seon/render/web.clj` is protected by the compiled-pull-plan lane. After
handoff, remove the literal `#{}` supplied by `debug-page-of` and pass the
identical live-process observation used by the ordinary namespace-page
transition. If that observation is unavailable, omit the set and construct the
missing-input diagnostic with `seon.error/diagnostic`; its evidence is
`:seon.error/unknown`, never an empty set. Add focused debug-route cases for
one held live run and one held dead run.

## Resolution — 2026-08-12

Resolved in `fee09f551`. Debug derivation now carries the service's same
observed run-holder process set as ordinary namespace derivation. If that
observation is unavailable, the key is omitted and the result carries a flat
`seon.error/diagnostic` with typed unknown evidence; no empty set is invented.

`debug-pages-distinguish-held-live-and-dead-runs` is the focused debug-feed
regression. It captures the liveness input delivered through the route and
proves that the held dead run alone is derived as wedged while the run held by
the service process is not.
