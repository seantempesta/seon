---
type: issue
status: open
severity: friction
tags: [issue, runtime, render, context, wave/why-awake]
---

# Root's turns on a fresh dev cluster hit the context-acquisition backstop

## Problem

On the freshly reset `juniper-context` cluster, each of root's three turns
after the fixture messages arrived ended with
`:seon.await/backstop-fired`: the render context channel "never arrived for
:seon.render/context-acquisition within the declared
:seon.config.eval/time-limit-ms bound of 30000 ms". Each backstop was
followed by an unclassified core fault `Don't know how to create ISeq from:
java.lang.Long` whose stored data carries no first-party frames. The bound
did its job and the turn settled, but three 30-second waits and an
unattributed fault on every fresh cluster is a defect, not weather.

## Evidence

2026-09-07 06:39:45Z, 06:40:15Z, 06:40:46Z (`:seon.error/agent` root, runs
`e218a4a0…`, `3a5aae58…`, `3ce078ce…`), each paired with the ISeq fault
about 11 seconds later. Juniper's own runs in the same window settled as
`:seon.ai/no-credential` refusals without a backstop. Query:

```clojure
(seon.db/q '[:find [(pull ?e [:seon.error/at :seon.error/kind
                              :seon.error/message]) ...]
             :where [?e :seon.error/kind :seon.await/backstop-fired]] db)
```

## Owner

`src/seon/cluster/prompt.clj` context acquisition for root, and whichever
render path throws on a bare entity id during that acquisition; the ISeq
fault must carry its frames (the `:seon.error/data-edn` had none to read),
which is a diagnostic-honesty defect of its own.

## Acceptance

A fresh cluster's root turns settle without a backstop; a core fault always
records the first-party frames that threw it; one regression per class.
