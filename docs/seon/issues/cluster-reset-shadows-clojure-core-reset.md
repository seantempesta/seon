---
type: issue
status: open
severity: friction
tags: [issue, naming, operator]
---

# `seon.cluster/reset!` shadows `clojure.core/reset!`

## Problem

The cluster-priming work added `seon.cluster/reset!`, which shadows
`clojure.core/reset!` inside `seon.cluster`. Reloading the namespace prints:

```text
WARNING: reset! already refers to: #'clojure.core/reset! in namespace:
seon.cluster, being replaced by: #'seon.cluster/reset!
```

Two costs. Inside `seon.cluster` every `reset!` on an atom must now be
qualified or it silently means the cluster operation — a live hazard in a
namespace that holds several `defonce` atoms (`running-instances`,
`root-store-holder`). And for readers, `(reset! x)` no longer means what it
means everywhere else in Clojure.

Observed 2026-07-29 while hot-reloading the namespace on the owner's live
cluster.

## Owner

`src/seon/cluster.clj` and the operator verb that calls it.

## Acceptance

The destructive cluster operation has a name that does not shadow
`clojure.core`, the warning is gone, and the operator verb's surface is
unchanged (`bin/seon reset [CLUSTER]` may keep its user-facing spelling — this
is about the Clojure name, not the CLI word). Prefer a name grounded in what
it does to the cluster's world rather than a generic verb.
