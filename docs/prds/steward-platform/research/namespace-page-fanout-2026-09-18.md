---
type: research
status: open
created: 2026-09-18
tags: [render, web, performance]
---

# Namespace page fanout

## Result

Commits `b2f62f288` and `f5bbecde0` repair the namespace-page fanout in the
walk's value-restoration seam. The regression is green in a clean HEAD
worktree, but this note remains open because the development cluster did not
adopt the change: two edit-hook publications stopped in preflight when the
clj-kondo dependency-cache subprocess exceeded its declared deadline. No
post-change HTTP measurement is therefore claimed.

RESET NEEDED: `default` predates the symbol-valued `:seon.ns/requires` change
at `0e8f7d323`. The orchestrator owns that reset; this lane did not stop,
refork, or restart it.

## Route and expansion seam

The route table selects `::route/namespace` at
`src/seon/render/route.clj:9-10`. The ordinary handler does not render that
namespace directly: `canonical-namespace-response` resolves or creates its
owner and calls the ordinary agent `page-response`
(`src/seon/render/web.clj:3207-3222`). That page performs the depth-two walk.

The exact expansion was not `full-html-view` independently querying every
namespace. It was the interaction of two walk stages:

1. `namespace-connections` resolves the stored symbol values in
   `:seon.ns/requires` and the reverse requirement query to namespace entity
   maps so the walk can traverse them (`src/seon/render/walk.clj:392-408`).
2. `shallow-entity` formerly restored only attributes installed as Datahike
   refs. Since `:seon.ns/requires` became a symbol-valued observation at
   `0e8f7d323`, the resolved maps remained nested in the namespace render
   value. The value no longer matched `:seon.ns/ns`; renderer selection fell
   through to the structural value renderer, which recursively selected the
   namespace HTML pair for the nested program graph. Reverse requirements made
   that graph approach the whole namespace population.

That explains the saved-page counts from the issue: 503 `Referenced schemas`
headings, 1,168 `namespace source` summaries, and 1,014 `member definition
sources` summaries. Those sections were nested namespace render pairs inside
one structural rendering, not 503 independent top-level route entries.

## Fix

`shallow-entity` now treats `:seon.ns/requires` as a traversal connection while
restoring the render value to its stored namespace symbols and removing the
derived reverse connection (`src/seon/render/walk.clj:181-206`). The selected
namespace consequently matches its declared pair and renders its own source
and definitions through the unchanged full HTML path. `requires-html` renders
each required namespace as a link (`src/seon/render/ns.clj:669-683`).

As a second guard, namespace render distance is now 1 only for the acquired
root namespace and 0 for any other namespace entity
(`src/seon/render/walk.clj:593-597`, `:636-645`). Distance-zero HTML is an
identity link (`src/seon/render/ns.clj:771-780`), never source or member
definitions.

No HTML presentation clipping was added. The selected namespace still reaches
`full-html-view` (`src/seon/render/ns.clj:685-737`) with every source byte.
The existing connection query-work observation names either
`:seon.config.eval.result/max-collection` or
`:seon.render.profile/max-children` when that acquisition bound cuts work
(`src/seon/render/walk.clj:208-235`); this change introduced no new cut or bare
truncation, and the regression did not hit a query-work bound.

## Measurements

All byte counts are curl `%{size_download}` and times are `%{time_total}`.

| Surface | Clean before | 2026-09-17 recheck before adoption | After |
|---|---:|---:|---:|
| `/ns/seon.id` | 27,590,915 B / 28.8 s | 11,582 B / 62.483691 s, not comparable: the namespace unit rendered `renderer unavailable` | not measured — publication preflight failed |
| `/` | 3,753,105 B / 6.51 s | 3,686,594 B / 17.478868 s | not measured — publication preflight failed |

The root recheck contained 264 namespace entries, 260 `namespace source`
summaries, 201 `member definition sources` summaries, 64 `Referenced schemas`
sections, 2,854 `details`, and 2,582 `pre` elements. It is the same nested
namespace-graph expansion class, not a different cause.

Two adoption attempts failed before publication at 5,319 ms and 5,329 ms.
`logs/current-source-failure.log` names
`:seon.operator.subprocess/deadline-exceeded` for
`seon.dev.clj-kondo/ensure-dependency-cache!`; the declared remaining deadline
was 4,944 ms and 4,906 ms. A read-only JVM probe then returned `2` from the
loaded `namespace-render-distance` where this source returns `0`, proving the
HTTP process still served the old Var. The existing issue
`docs/seon/issues/concurrent-publications-serialize-past-the-hook-bound.md`
owns this publication class.

## Regression and fast boundary

`seon.render.entity-pairs-test/namespace-page-renders-one-full-namespace-and-linked-references`
uses `seon.test-support/with-database`, seeds the complete cluster path, creates
one small namespace requiring `seon.id`, and renders through
`seon.render.walk/neighborhood`. It asserts exactly one namespace unit, exactly
one full source disclosure, the selected namespace's source marker, a
`/ns/seon.id` identity link, and absence of `seon.id/id` member detail.

Clean HEAD worktree fast tally: **3 tests, 62 assertions, 0 failures, 0
errors**, with 1,210 contracts armed. The initially requested combined run
completed **29 tests / 386 assertions / 45 failures / 6 errors**. Its failures
are the concurrent reset boundary: the published fast base's older fixtures
write string `:seon.fn/sym` and ref-shaped `:seon.ns/requires` values against
HEAD's symbol schemas, while web-debug fixtures also meet the in-flight
component validation work. The overlay then demanded dirty
`test/seon/sci/eval_test.clj`; that invocation was interrupted before contract
arming, and the clean worktree proof excluded it. The orchestrator still owes
the cold path-limited gate and platform proof after reset/publication.
