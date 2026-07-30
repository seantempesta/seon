---
type: issue
status: resolved
severity: friction
tags: [issue, runtime, operator, database]
---

# Canonicalize the process-root store holder key

## Problem

`seon.cluster/root-store-holder` keys the process-local shared store by the
bootstrap `:seon.boot/store-dir` string, while `seon.cluster.store/open-store!`
canonicalizes the physical directory before deriving the Datahike
configuration and flock. Two spellings of one directory can therefore create
two holder entries in one JVM. The second entry attempts another physical open
and the flock correctly refuses it.

This does not weaken the fence or permit two writers, but an otherwise valid
same-root cluster add fails because the process-local reuse owner and the
physical-store owner disagree about identity.

## Evidence

The ruling-27 operator drive started pid 11074 with the default relative
bootstrap root `data/clusters`, then submitted an equivalent absolute root
while adding `default`. The existing root store stood and served `op-a`, but
the add failed above its new prepl with:

```text
the store at
/Users/sean/src/seon/tmp/operator-reconcile-20260729/data/clusters/store
is held by a live process
```

The partial `default` remained registered, appeared as `reserved` in the new
operator status, and stopped cleanly. Preserving the live JVM's established
relative bootstrap spelling made the next add succeed, proving that only the
holder key differed.

Source boundary:

- `src/seon/cluster.clj:228-261` uses raw `store-dir` as the
  `root-store-holder` key.
- `src/seon/cluster/store.clj:121-146` canonicalizes `store-dir` for both the
  lock file and Datahike configuration.

## Owner

`seon.cluster` process-root store acquisition. This source was protected
during the operator reconciliation lane and was not edited.

## Acceptance

- One pure canonical store-directory derivation supplies both
  `root-store-holder` lookup and `seon.cluster.store/open-store!`.
- Relative, `./`-prefixed, and absolute spellings of one physical directory
  reuse the exact held store and increment one holder count.
- Two genuinely different directories remain separate holders.
- A focused lifecycle test adds and stops two clusters whose bootstrap
  requests use equivalent path spellings, proving one store open, one flock,
  and complete release after the last stop.

## Resolution

Resolved in the current-source operational repair. `seon.cluster` now derives
one canonical path before both holder lookup and physical store open. The
regression acquires one store through relative and absolute spellings, proves
object identity and reference counting, then reopens the physical store after
the final release to prove that the flock was released.

Live proof used the formerly failing shape: the operator held the relative
bootstrap root while the edit hook refreshed through its absolute repository
root. The hook advanced `current-src` without a second store open.
