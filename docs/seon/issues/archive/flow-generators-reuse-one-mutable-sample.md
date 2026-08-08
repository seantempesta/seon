---
type: issue
status: resolved
severity: friction
tags: [issue, flow, contracts, testing]
---

# Generate fresh Flow contract values

## Problem

Flow's opaque predicate generators returned one delayed mutable object
forever. They satisfied the predicate once but could not explore lifecycle,
freshness, or state-dependent failures, so generative contract checks were
green for the wrong reason.

## Evidence

`src/seon/flow.clj:56-66` created one executor, atom, `FutureTask`, proc
launcher, graph, and channel in a delayed map. Lines 68-79 defined every
generator as `gen/fmap` over `(gen/return nil)`, returning that same object on
every generation. A direct probe generated two channel values and
`identical?` returned true.

`test/seon/public_contract_test.clj:83-93` checked only that one generated
value passed its predicate. Its freshness regression covered store connections
and file locks, not the Flow resources. In the same boundary,
`src/seon/flow.clj:94-107` gave `var-process` an input contract of `:any` and
then immediately required `var?` at runtime.

## Resolution

Commit `ac831976b`. The `generator-values` `defonce` delay is deleted; each of
the six generators now CONSTRUCTS its sample per generation. None of those
constructors starts a thread, binds a port, or runs a graph: an unsubmitted
executor has no worker, an uncalled `FutureTask` never runs, and
`create-flow` builds without starting.

`var-process` declares `:seon.flow/step-var` (new `seon.flow/step-var?` core
predicate plus `step-var-generator`, declared in
`resources/seon/schemas/seon.flow.edn`) in both arities instead of `:any`,
so the schema now names the refusal the function already made.

The class-killing regression is
`seon.public-contract-test/lifecycle-generators-make-a-fresh-sample-each-generation`:
every lifecycle generator must hand out a distinct sample, and executors and
sockets are released after the check. Live falsifier, 2026-08-07:

```clojure
:flow-atoms-distinct true
:flow-chans-distinct true
:flow-graphs-distinct true
```

and `var-process` still refuses a non-Var step at construction.

The remaining shared samples — the SCI ctx and the render.web server/mult —
belong to
[[opaque-contract-generators-share-live-process-objects]], which stays open.
