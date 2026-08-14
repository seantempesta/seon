---
type: issue
status: open
severity: friction
tags: [issue, render, web, performance, class/n9, wave/render-package-economics]
---

# Invalidate retained render calls when a selected producer changes

## Problem

The package proc can retain an old renderer result after the selected
producer's live Var has been replaced. A fresh render call uses the repaired
producer, while the served page continues returning the retained broken HTML.
This is the stale-output half of
[`render-package-proc-reruns-unchanged-renderers`](render-package-proc-reruns-unchanged-renderers.md):
the current evidence boundary both reruns renderers that did not change and can
fail to rerun a renderer that did.

## Evidence

On the running Drive 1 cluster at `http://127.0.0.1:55156`, the original root
page contained 67 exact placeholders:

```html
<div class="seon-render-unavailable">renderer unavailable</div>
```

Removing 55 traversal-only units reduced that retained page to 12 real
`seon.error/render-html` failures. Hot-loading the repaired error producer made
a direct call return a `seon-error-entry`; a fresh live root neighborhood then
contained 82 units with zero `:seon.error/value` render failures and zero
missing outputs. `/agent/root/debug`, which derives afresh, also curled with
zero placeholders.

The retained `/` package nevertheless continued to curl with 13 placeholder
blocks and unchanged bytes. Two forced render-proc passes advanced its pass
count from 142 to 144, including a `:seon.render.web/join` pass, without
replacing those retained call outputs. A later namespace-producer replacement
showed the same split: a fresh neighborhood contained 14 namespace units and
no `register! :seon.error/value` source, while `/` still served the three old
registration blocks.

`seon.render/render-call` reuses a prior output when its static and read
evidence compare current. `seon.render.web/page-refresh` can force a page pass,
but still supplies each block's retained calls and candidate-call ids to that
reuse decision. Live Var replacement is therefore not represented at the
retained-call invalidation boundary.

## Owner

`seon.render/render-call` static producer evidence and
`seon.render.web/page-refresh` candidate-call derivation.

## Acceptance

Replacing one selected producer in the running cluster invalidates every
retained call that selected that producer, and no unrelated call. A recurring
live-package regression proves both rails: the next curl contains the new
producer HTML, and an unchanged producer is not invoked again.
