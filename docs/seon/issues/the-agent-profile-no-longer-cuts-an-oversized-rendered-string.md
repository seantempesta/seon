---
type: issue
status: open
severity: friction
tags: [issue, render, elision, profile]
---

# The agent render profile no longer cuts an oversized string

Observed 2026-09-17 on `steward-platform` HEAD `767ff6d75`, running
`bin/test-fast --paths src/seon/render/value.clj -- seon.render.value-test`.
Present identically with the HEAD test file, so it predates the
error-schema declaration change made in `seon.render.value/transacted` that
day.

`an-oversized-string-shows-its-prefix-not-only-a-count`
(`test/seon/render/value_test.clj:806`) evaluates
`(apply str (repeat 3000 "ab"))` in a real SCI context under the cluster's
agent render profile and looks for the elision value in the shown text. There
is none: `elision-in` returns `nil`, so five assertions fail and five more
throw `NullPointerException` reading members off it. The evaluation itself
reports no error — the 6,000-character string is simply shown whole.

```text
expected: (some? cut)
actual: (not (some? nil))
expected: (= 6000 (:seon.render.data/total cut))
actual: (not (= 6000 nil))
```

The same shape appears in `dir-of-a-large-namespace-shows-members-and-how-to-continue`
(`:881`, 2 failures / 2 errors), where the namespace listing is expected to end
in a requeryable cut and does not.

This is the recurring class AGENTS.md §2.4 names: the profile's
`:seon.render.profile/max-string-length` is the declared bound, and a bound that
silently stops firing reports nothing. Root cause not diagnosed here; the
owners to read are `seon.render.value/window` and `value-node*`
(`src/seon/render/value.clj`) against `seon.print`'s emitter and
`seon.render/agent-render-profile`. Whether the profile reaching evaluation
carries the string bound at all is the first question — an absent bound and a
bound that no longer applies look identical from the shown text.
