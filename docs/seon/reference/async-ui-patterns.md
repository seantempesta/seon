---
type: reference
status: abandoned
tags: [reference, web, history]
---

# Historical async UI pattern survey

> Historical dependency research only. Its polling, background-job,
> `refresh-all!`, and application-state recipes are not Seon APIs. Use
> [[datastar-quick-reference]].

The 2025-12-02 survey explored generic SSE responsiveness before Seon's
database-woken Flow renderer existed. Its implementation recipes were deleted
because they encouraged timers, atom watches, and a second update mechanism.
Git preserves the original research.

The surviving design constraints are implemented in fresh source: remote calls
and SSE writes run on `:io`; settled presentation derives from database facts;
in-flight partials use lossy channels; and a slow tab receives the newest
complete page snapshot. Current evidence is in `src/seon/render/web.clj` and
`src/seon/cluster/loop.clj`.
