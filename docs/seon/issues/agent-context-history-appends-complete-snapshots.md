---
type: issue
status: open
severity: blocker
tags: [issue, render, agent, wave/render-context-cache]
---

# Replace retained context snapshots instead of appending them

## Problem

A changed agent-context walk appends a second complete snapshot after the
retained snapshot. Superseded message content therefore survives beside the
current trigger, unchanged toolkit material is duplicated, and the model can
follow the older objective.

## Evidence

The exact paid Drive 1 Attempt 4 capture
`6c5dad44-e55a-4184-b4bb-0cf07a6b8764-context-536871046` contains 40
blank-line-separated entries. Entries 0–18 are a 34,033-character opening
snapshot. Entries 19–35 repeat its cluster, config, instruction, toolkit,
agent, `seon.bootstrap`, and `seon.db` entries in another 33,285-character
snapshot. The paid-run tail is only 1,583 characters.

The old message occurs first at entry 18 and again at entry 38, verbatim:

```text
From outside this cluster to drive-one-agent-attempt-4: Define a durable contracted function named largest that returns the row with the greatest :example/amount, or {} for empty input. Call it once, query its stored :seon.fn/spec, then complete with a short reply naming what you built and its contract.
```

The current message occurs once, between those copies, at entry 37:

```text
From outside this cluster to drive-one-agent-attempt-4: Author and follow one my.plan for this task. Every authored item must use the NEW :my.plan.item/about shape: a plain vector mixing quoted qualified function symbols and namespaced keywords, targeting the actual functions and schema attributes you will use. Define a durable contracted function sum-of-squares in your namespace with a complete Malli contract, define a discoverable clojure.test usage test, run it through seon.test/run, complete every plan item, and close with my.run/complete reporting the exact test result. Do not edit repository files.
```

DeepSeek then began its reply with:

```clojure
; Looking at this task, I need to:
; 1. Define a durable contracted function named `largest` that returns the row with the greatest `:example/amount`, or `{}` for empty input
```

The assembly mechanism preserves this shape. `src/seon/render/web.clj:1050-1061`
defines prior observations as seen only by the pair of call ID and basis
transaction. `context-pass` derives a refreshed complete history and passes it
to `append-history` at lines 1093–1104. `src/seon/render/walk.clj:819-868`
assigns each generic history entry an observed basis, falling back to the
current database basis. A new-basis observation of the same logical call is
therefore not a replacement; it is appended behind the old bytes.

## Owner

`seon.render.web/context-pass`, `append-history`, and the retained AI-entry
identity shared with `seon.render.walk/history`.

## Acceptance

- A second run whose trigger supersedes an opening task contains the current
  task once and the superseded task zero times.
- Unchanged cluster, instruction, and toolkit entries occur once across the
  complete paid prompt.
- A changed observation replaces its prior logical entry while genuinely new
  transcript entries retain causal order.
- A live paid-context capture proves the invariant from durable bytes.
