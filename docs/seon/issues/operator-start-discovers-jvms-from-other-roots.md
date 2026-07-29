---
type: issue
status: open
severity: blocker
tags: [issue, operator, tooling]
---

# `bin/seon start` adds clusters to JVMs belonging to other operator roots

## Problem

Isolation by operator root is the documented pattern for lane work (a lane
boots its own root so its cluster cannot collide with anyone else's). That
isolation leaks at the discovery seam: `bin/seon start <name>` from the
project root discovers a live JVM belonging to a DIFFERENT root and tries to
add the cluster there, where the branch is already open. The cluster cannot
start, and the error surfaces from deep inside the store rather than naming
the real problem (a foreign JVM was selected).

Observed 2026-07-29 evening while restoring the owner's `default` cluster:

```text
$ bin/seon start default
✗ The cluster rejected the prepl operation.
  ... "branch :cluster-default already has a connection in this process"
  :seon.cluster.store/dir
    "/Users/sean/src/seon/tmp/skills-verification-operator-root/data/clusters/store"
```

The selected JVM (pid 3473) was a verification lane's, rooted at
`tmp/skills-verification-operator-root`. The project-root operator should
never have considered it.

## Second defect in the same tangle: a partial start is unaddressable

The failed start left a `default` entry in that JVM's process-local
`running-instances` while writing no advertisement. `start` then refused
("the cluster already has an instance in this process") and `stop` refused
("the cluster has no advertisement") — the cluster was addressable by neither
verb. Recovery required reaching into the JVM's registry over its prepl and
`dissoc`-ing the entry by hand.

This is the same class as the resolved
[[archive/cluster-stop-release-failure-becomes-unaddressable]] at a different
seam: a half-built instance must stay addressable by the operator that built
it.

## Owner

`script/seon/fresh_operator.clj` — JVM discovery and the start/stop
addressability contract.

## Acceptance

- Cluster/JVM discovery is scoped to the operator's own root; a JVM from
  another root is never selected, and if one is somehow named the refusal
  says so plainly.
- A start that fails after reserving leaves the cluster stoppable: `stop`
  cleans a reserved-but-unadvertised entry instead of refusing.
- Neither recovery needs a hand-written prepl form.

## Related

Ruling 26 (unambiguous is automatic, ambiguous fails loudly) — this is the
same principle at the discovery boundary: choosing a foreign JVM silently is
exactly the "silently pick" failure the ruling forbids.
