---
type: issue
status: open
severity: blocker
tags: [issue, render, context, testing]
---

# Transcript decline entries drop the message content, and the fixture hides it

## Problem

`message-form` (`src/seon/render/transcript.clj:312-330`) dispatches on
`(::reason entry)` BEFORE `(::from entry)`, and that branch emits only
`to`, `about`, and `reason`:

```clojure
(::reason entry)
(list 'my.message/decline to about (::reason entry))
```

`:seon.cluster.message/content` is a REQUIRED attribute
(`resources/seon/schema/message.edn`). For a decline message it is
dropped entirely, with no elision marker, at `:full` detail under no
budget pressure at all. Falsified with a 100 000-token budget: the full
rendering of a decline message contains neither the content nor any
marker.

The same `cond` ordering means a message carrying a reason but no
`from` — one that arrived from outside the population — renders as
`(my.message/decline …)` as though this agent had sent it, which
contradicts `message-direction`'s own third branch.

The regression cannot see either defect. `transcript_test.clj` sets
`:seon.cluster.message/content` and `:my.message/reason` to the SAME
string, and the generator does the same
(`(assoc :my.message/reason content)`), so `reason ≡ content` in every
generated and seeded case and the assertion
`(str/includes? ai "(my.message/decline …)")` passes over the drop.

## Acceptance

Every message branch of `message-form` carries the content, or states
its omission as a marker in the output. The `from`/`reason` dispatch is
ordered so direction is decided by `from` and only the FORM is decided
by `reason`. The fixture and the generator make `reason` and `content`
distinct strings, so the class is visible; one regression asserts a
decline entry's rendered output contains both, and one asserts a
reason-bearing message with no `from` is not attributed to this agent.

## Evidence

`docs/prds/sci-execution-runtime/research/context-wave-audit-2026-07-31.md`
