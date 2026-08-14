---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, schema, wave/live-drive-context]
---

# Keep generated opening read evidence valid through context capture

## Problem

Drive 1's generated opening failed on its first `(help)` form before any
context capture or provider attempt. The task never reached DeepSeek, so the
drive could not measure context fidelity, deltas, rebirth, or plan behavior.

## Evidence

In preserved root `tmp/drive-1-root`, cluster `default`, run
`bootstrap:drive-one-agent` opened at `2026-08-14T05:39:41Z` and closed four
seconds later. Its only form and receipt were:

```clojure
; A new run just opened. Why am I awake — do I have messages?
(help)
```

```text
seon.test.accretion/non-generatable-advisory violated its contract (invalid-input): invalid type
```

The run carried the terminal error:

```text
The EDN-backed attribute :seon.db/read-request has an invalid logical value.
```

At observed basis `t=536871061`, the run had one form, one failed receipt,
zero `:seon.context.capture/run` facts, and zero model attempts. The requested
objective message was present and correct; execution failed before the model
could see it.

## Owner

`seon.db` read-evidence construction and the generated-opening terminal
transaction that stores `:seon.cluster.eval/read-evidence` components.

## Acceptance

A fresh generated opening executes `(help)`, commits logically valid replay
evidence, records a context capture, and reaches one bounded provider attempt.
A regression round-trips every `:seon.db/read-request` arm through the same EDN
attribute codec used by the terminal receipt.
