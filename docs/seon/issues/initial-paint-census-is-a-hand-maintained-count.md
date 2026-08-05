---
type: issue
status: open
severity: friction
tags: [issue, render, testing]
---

# The initial-paint census is a hand-maintained count every lane must re-bump

## Problem

`seon.render.web-test/the-initial-paint-sends-every-walk-surface-once` asserts
a literal surface count (`test/seon/render/web_test.clj:458`). The count is a
function of how many namespaces and schema declarations currently reach the
walk, so ANY lane that adds a schema resource or a namespace turns this test
red for reasons unrelated to its change — and the fix is always to edit a
number in a file that lane does not own.

That is the hand-maintained list this repository bans, in test clothing. Its
own comment records the treadmill: "Legitimate schema accretion moves it —
15 -> 17 on 2026-08-03 when effect receipts and fs config declarations entered
the walk."

## Evidence

Measured 2026-08-03, after the bump to 17 landed in `98fbe9a05`:

```text
FAIL in (the-initial-paint-sends-every-walk-surface-once) (web_test.clj:458)
expected: (= 17 (count page))
  actual: (not (= 17 21))
```

Between that bump and this run, five schema resources landed from three
concurrent lanes (`my.edit.edn`, `my.edit.form.edn`, `seon.config.flow.io.edn`,
`seon.edit.edn`, `seon.operator.edn`) plus one from the kernel-merge lane
(`seon.sci.kernel.edn`). No lane's change was wrong; the count was.

The bare 2026-08-05 gate moved the same derived count again:

```text
FAIL in (the-initial-paint-sends-every-walk-surface-once) (web_test.clj:458)
expected: (= 17 (count page))
  actual: (not (= 17 25))
```

A focused reproduction at pre-rename commit `401fd300e` also produced 25.
That identical pre-rename value proves the current red is this filed literal-
census class, not rename fallout.

## Owner

`test/seon/render/web_test.clj`, and whatever fact the census should be derived
from instead.

## Acceptance

- The test asserts the PROPERTY it exists for — every walk surface is painted
  exactly once, with no separate transcript surface — by comparing the painted
  set against the walk's own emitted units, not against a literal.
- Adding a schema declaration or a namespace cannot turn this test red.
- If a literal must survive somewhere, it is a lower BOUND with a stated
  reason, never an equality on a growing derived set.

## Provenance

Hit by the guarded-kernel-merge lane, which contributed one of the six new
schema resources and none of the behavior under test.
