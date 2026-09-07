---
type: issue
status: open
severity: cleanup
tags: [issue, render, agent, namespace, class/p2]
---

# The unowned-namespace oversight line still inverts assignment

## Problem

`seon.problems/unowned-namespaces` (`src/seon/problems.clj:279-291`) derives
"source-bearing program namespaces with no assigned agent" by the absence of
a `:seon.cluster.agent/namespace` edge pointing at the namespace:

```clojure
(not [_ :seon.cluster.agent/namespace ?namespace])
```

Since 2026-09-07 that edge no longer answers the question it is being asked.
Assignment is not stewardship (PRD §3, "Namespace is not identity"):
`:seon.cluster.agent/namespace` is the namespace an agent works in by
default, is no longer unique, and several agents may share one; the fact
that says who faults, complaints, and feature requests route to is
`:seon.ns/steward` on the namespace itself, read by
`seon.cluster.agent/steward-of` (`src/seon/cluster/agent.clj:334-348`).

So the oversight line now reports "owned" for a namespace that merely has
someone working in it, and reports "unowned" for a namespace that has a
steward but nobody assigned. It is the recurring failure class in a small
way: a check whose subject moved, still answering confidently.

## Expected shape

The clause becomes `(not [?namespace :seon.ns/steward _])`, the derivation
and the printed line rename to stewardship vocabulary
(`seon.problems unowned-namespace namespace=…` →
`unstewarded-namespace`), and
`seon.cluster.agent-namespace-test/source-bearing-namespaces-without-owners-derive-one-problem-line`
moves with it — its fixture currently declares assignment only and would
have to declare the steward.

## Why it is filed and not fixed

Found by the namespace-steward lane, whose owned paths did not include
`src/seon/problems.clj` or its printed report. The change is mechanical but
it renames an operator-visible line, so it belongs to one commit that owns
both the derivation and the vocabulary.

## Acceptance criteria

- The derivation reads `:seon.ns/steward`, never an inverted assignment.
- One regression asserts a namespace with an assigned-but-not-stewarding
  agent still reports, and a stewarded namespace does not.
