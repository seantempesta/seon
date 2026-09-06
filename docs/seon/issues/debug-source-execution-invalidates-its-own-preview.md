---
type: issue
status: open
severity: blocker
tags: [issue, render, database, wave/live-drive-render]
---

# Preserve a preview execution when its run changes the inspected graph

## Evidence — 2026-09-06

A fresh isolated cluster at `tmp/render-source-live-2026-09-06`, built with
`5083a373e`, showed at least 49 closed runs containing the same identity source
between 21:18:04 and 21:18:23 UTC. Root personally opened the AI debug page
for Juniper entity 32288 on port 7722. Its graph grew from 11 reference
assertions to 44; the rendered result remained `nil`, and found-value
acquisition eventually reported result weight 4006 exceeding 4000.

Root independently queried the stored run identities through MCP. The proof
agent compared their exact source bytes and stopped the isolated cluster.
`bin/seon --root tmp/render-source-live-2026-09-06 status` confirms no live
clusters, no orphan JVM, and a retained 0.27 GiB root. The shared user cluster
on port 7773 was not changed. Preserve the stopped root until the regression
and repeated-render proof capture the needed evidence.

The existing render invocation evidence includes its acquired argument.
Submitting a source run adds a reverse run ref to that agent argument. Source
production can therefore become stale even when the produced source and its
evaluated database reads still describe the same result. Discarding the stored
execution identity together with producer-input evidence allows another run.
The exact invalidating evidence must be verified by the correction; do not
infer it solely from the number of runs.

## Second live falsifier and correction

The next isolated build included `bffaa3779`, preserving the prior call's run
identity across changes to its acquired argument. It still created six source
runs/evaluations during the bounded eight-second feed probe and was stopped.
Inspection identified another invalidation owner: each ordinary evaluation
announces `:seon.render.web/runtime-eval`, whose handler cleared all retained
calls and invocation entries before the following settlement wake.

`ab558c858` retains source execution references through that event while
discarding output, producer-input reuse evidence, and invocation caches. The
next pass must regenerate the producer's source and validate its stored run's
program, starting namespace, agent, and evaluation read evidence. Retaining a
complete reusable output entry here would instead mask live code changes.
This correction still needs a successful fresh live proof; its focused test
is not closure of this issue.

The third fresh proof still produced seven runs/evaluations and was stopped.
Its stored evaluation has 15 read-evidence entries. Three have dependency plan
`:all`: the history-view construction and the current/historical maximum
transaction queries in `seon.call-preparation/newest-row-transaction`. Their
attribute input is exactly `:seon.call-preparation/key`, `/schema`, and
`/supplier`; they maintain call-preparation cache coherence. The evaluated
identity pull itself has precise attribute dependencies. A result-settlement
transaction therefore invalidates the broad machinery observations even when
the identity data is unchanged.

The next correction belongs at that existing metadata observation boundary.
It must preserve real supplier/declaration dependencies while preventing
internal cache-coherence reads from claiming that an identity result depends
on every database fact. Ignoring false read validity or returning a permanent
stale-result refusal would not fix the requested behavior. Do not classify this
as another presentation-key mismatch.

## Owner and acceptance

Correct the existing retained call/invocation cache in `seon.render.web` and
`seon.render`. Preserve pending and terminal execution identity across producer
refreshes when source, program, agent namespace, and execution read evidence
remain valid. Use direct cache lookups; do not scan every invocation or add a
second cache. Missing identity or dependency evidence cannot mean valid.

Prove a real debug feed's initial render, pending transaction wakes, terminal
wake, repeated refresh, and second presentation reuse one stored execution.
Then change an actual source dependency and verify the existing invalidation
mechanism produces the appropriate new result. A mocked execution count with
an immutable acquired argument did not cover this failure.
