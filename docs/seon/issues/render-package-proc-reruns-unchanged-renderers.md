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

### Measured cost, 2026-08-07

The same construction, timed end to end through the proc's own settlement
fence (one watched agent, the canonical test population, no debug tab):

```text
pass 0 -> elapsed-ms= 10485      ; first derivation
pass 1 -> elapsed-ms=  1930      ; one namespace source fact changed
pass 2 -> elapsed-ms=  1909
pass 3 -> elapsed-ms=  1873
pass 4 -> elapsed-ms=  1844
pass 5 -> elapsed-ms=  1904
```

So a one-block change costs ~1.9 s of whole-walk derivation, and the first
pass costs ~10.5 s. Two consequences beyond the wasted work:

- every pass outlasts `flow/ping`'s 1000 ms reply window by about 2x, so the
  proc is routinely absent from a ping result (this produced two
  NullPointerExceptions in `seon.render.web-test`, now dead at the oracle —
  `archive/render-web-tests-read-a-missed-flow-ping-as-state.md`); and
- `seon.render.web-test` chains several passes per test against the shared
  20 s `test-support/event-backstop-seconds`, so tests in that namespace blow
  the backstop intermittently under ordinary machine load. Two of three
  focused runs on 2026-08-07 lost a different test to that timeout
  (`thinking-stream-morphs-into-the-settled-session-transcript`,
  `the-namespace-page-is-the-html-walk`-adjacent waits). The instability is a
  symptom of this cost, not of the tests.

## Owner

`seon.render.web/page-result` and the render walk's fact-derived evidence
boundary.

## Acceptance

With many page blocks settled, changing the facts for exactly one block causes
exactly one renderer invocation and one fragment serialization. Delivery still
emits only that fragment to a contiguous tab and the retained complete
keyframe to a stale tab.
