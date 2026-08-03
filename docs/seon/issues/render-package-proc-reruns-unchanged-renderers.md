---
type: issue
status: open
severity: friction
tags: [issue, render, flow, performance]
---

# Skip unchanged renderer invocations in the package proc

## Problem

The package proc retains serialized fragment bytes, but it derives the complete
render walk before comparing retained evidence. A one-block fact change
therefore invokes renderers for unchanged blocks even though their bytes are
reused afterward.

## Evidence

- A direct `render-pass` probe registered one 14-fragment namespace page,
  settled a baseline, changed one namespace source fact, and instrumented the
  actual renderer and serializer boundaries.
- The second pass invoked eight renderers. It called `surface-html` once,
  emitted a one-fragment delta, and selected that delta for a contiguous tab;
  a stale tab received the byte-identical retained keyframe.
- `src/seon/render/web.clj:329-378` calls
  `seon.render.walk/neighborhood` before comparing each unit's retained
  evidence. The comparison can suppress `surface-html`, but it cannot suppress
  renderer execution that has already happened.

## Owner

`seon.render.web/page-result` and the render walk's fact-derived evidence
boundary.

## Acceptance

With many page blocks settled, changing the facts for exactly one block causes
exactly one renderer invocation and one fragment serialization. Delivery still
emits only that fragment to a contiguous tab and the retained complete
keyframe to a stale tab.
