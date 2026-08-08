---
type: issue
status: resolved
severity: blocker
tags: [issue, render, web, flow]
---

# A reconnecting tab paints a render pass derived before it connected

## Problem

`seon.render.web/feed`'s `on-open` registered interest, tapped the pages
`mult`, and then painted the FIRST package that arrived on that tap. Nothing
related that package to the database value the tab connected at.

A render pass already in flight when the tab tapped was derived at an EARLIER
database value. Its publication reaches the fresh tap before the answer to
this tab's own `{::join true}` request, so the browser painted a superseded
page — and, because equality suppression then finds nothing changed, it stayed
on that superseded page until the next commit.

This is the standing "reconnect is repaint" claim failing on the wire: the
package already carries `:seon.render.package/basis-transaction`, the exact
fact that settles the question, and the writer ignored it.

## Evidence

Found 2026-08-07 while repairing `seon.render.web-test`. The two red tests
were `reconnect-is-repaint` (`test/seon/render/web_test.clj:566`) and
`reconnect-is-repaint-wire-test` (`:747`); both failed on
`(str/includes? repaint "def …")` with the pre-commit page on the wire.
`bin/test seon.render.web-test` reproduced them, and so did a direct
`clojure.test/test-vars` run outside the runner, which rules out the test
harness.

A scripted loop over the same shape — paint, close, commit, reopen, read one
patch, six trials — failed **3 of 6 trials**, and in every trial —
passing and failing alike — the diagnostic printed
`served-from-fresh-package? false`, so the tab was always on the join path and
`fresh-fact-package` was never the culprit. A forced settlement immediately
after each failure showed the proc's own retained keyframe DID carry the new
fact:

```text
trial 1 ok? false served-from-fresh-package? false basis-at-open 536870950
        pkg-basis 536870949 pkg-rev 2 registration {root 1}
   after a forced settlement: pkg-basis 536870950 rev 3 keyframe-has-tag? true
```

So the derivation was correct and the DELIVERY chose a stale value.

## Resolution

`src/seon/render/web.clj` reads the connection's basis transaction once in
`on-open`, before the writer thread starts, and the writer refuses any package
whose `:seon.render.package/basis-transaction` precedes it until it has
painted once — re-offering `{::join true}` so the proc answers with a current
derivation. It is a comparison of database bases the package already carries,
not a wait on a clock.

Same loop after the change: **6 of 6 trials correct**.

The class regression is
`a-reconnect-refuses-a-pass-derived-before-it-connected`
(`test/seon/render/web_test.clj`). It is deterministic rather than hopeful: it
holds one pass inside `seon.render.walk/neighborhood` while a newer fact
commits, so the stale publication is guaranteed to reach the fresh tap first.
Falsified against the unguarded writer — both assertions fail — and green with
the guard.
