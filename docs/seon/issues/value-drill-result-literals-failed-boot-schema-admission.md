---
type: issue
status: open
tags: [rendering, schema, runtime, issue]
severity: blocker
---

# Value-drill result literals failed boot schema admission

## Evidence

The first restart after Unit 1E (`c1618e22` / `099f7e99`) failed before pod
readiness. Complete boot schema admission rejected
`:seon.render.value/available-result`: its closed map referenced literal child
`[:= true]`, which the maintained storable-schema compiler reports as
`:malli.core/child-error`. The unavailable and failed result branches use the
same literal pattern and are implicated.

Unit 1E's focused 78 tests / 492 assertions did not exercise the exact complete
boot population. The operator exited the pod cleanly with no fallback; default
is down and no Stage 1.6 evidence from this artifact counts.

## Expected owner and acceptance

Preserve the disjoint result union without broadening it to arbitrary booleans
or adding a hand-written discriminator. Strengthen the one schema bridge or use
the existing pure-EDN enum/literal idiom that the complete Seon population can
store and compile, then test all three exact result branches.

Acceptance requires candidate and complete boot-population compilation, the
focused renderer/config gates, a successful exact-HEAD `bin/seon up`, and the
real `/agents/run` checkpoint after all concurrent source owners release.
