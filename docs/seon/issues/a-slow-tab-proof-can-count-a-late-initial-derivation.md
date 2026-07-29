---
type: issue
status: open
tags: [testing, render, concurrency]
---

# A slow-tab proof can count a late initial derivation

## Evidence

The 2026-07-29 full `bin/test` checkpoint reported:

```text
FAIL in (slow-tab-newest-complete-page-test) (web_test.clj:485)
coalescing means passes never exceed commits
expected: (<= passes k)
actual: (not (<= 6 5))
```

The test reads the fast feed's two initial patches and then snapshots the shared
derivation counter before five commits. The wire reads prove delivery, but do
not themselves prove that every initial derivation has left the proc before
the counter snapshot. One late initial pass can therefore enter the measured
window.

This was discovered by the delegation-preconditions lane. `render/**` is owned
by another lane and was explicitly protected, so no production or test change
was made here.

## Owner and acceptance

Owner: `seon.render.web` and its Flow proof.

Replace the sampling race with an event-driven readiness boundary proving the
initial render work has settled before `before` is captured. The focused test
and a subsequent full `bin/test` checkpoint must then pass without timing
constants or relaxed pass-count assertions.
