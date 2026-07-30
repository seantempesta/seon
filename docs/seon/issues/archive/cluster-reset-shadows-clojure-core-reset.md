---
type: issue
status: resolved
severity: friction
tags: [issue, naming, operator]
---

# `seon.cluster/reset!` shadowed `clojure.core/reset!`

## Problem

The cluster-priming work added `seon.cluster/reset!`, which shadowed
`clojure.core/reset!` inside `seon.cluster`. Reloading the namespace warned
that the core Var was being replaced. Every atom reset in the cluster owner
then needed qualification, and `(reset! x)` no longer meant what it means in
ordinary Clojure.

## Evidence

The warning was observed while hot-reloading the namespace on 2026-07-29.
History identifies `41b9ba6a9` (`Prime and reset cluster program graphs`) as
the introduction of the Clojure operation. Its implementation stops one
cluster, destroys its branch, and forks a replacement from the published
source commit.

## Owner

`src/seon/cluster.clj` and the generated fresh-operator form that invokes the
operation.

## Acceptance

- The destructive operation has a grounded name that does not shadow
  `clojure.core`.
- No compatibility alias preserves the ambiguous name.
- `bin/seon init NAME --force` retains its public command semantics.
- Namespace reload emits no `reset!` shadow warning.
- The destructive branch replacement regression passes.

## Resolution

Resolved by `cb151d220` (`Name destructive cluster operation refork`).

The operation is now `seon.cluster/refork!`; the generated operator form and
the recurring branch replacement test use that name. The namespace no longer
excludes core `reset!`, and no compatibility alias exists.

A clean JVM reload reported:

```clojure
{:refork true :cluster-reset true :core-reset true}
```

Here `:cluster-reset` resolves only because ordinary referred
`clojure.core/reset!` is present; it is identical to the core Var and no
shadow warning was emitted. `bin/test seon.cluster.boot-test` passed 25 tests
and 115 assertions. The combined cluster/operator run exercised 36 tests and
173 assertions; its only failure was an honest current-source digest mismatch
caused by concurrent source edits, while the refork and generated operator
paths completed.
