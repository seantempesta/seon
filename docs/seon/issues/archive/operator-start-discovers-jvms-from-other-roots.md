---
type: issue
status: resolved
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
[[cluster-stop-release-failure-becomes-unaddressable]] at a different
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

## Resolution

Resolved by `26a5ef07f`. The operator now collects the advertisement files,
live JVM registries, Datahike connection IDs, and `(pid, start-instant)`
process identities before calling one pure `derive-cluster-truth` function.
Every command reads that value. The effectful collector uses an advertisement
only to reach a candidate JVM; the registered instance's canonical bootstrap
root establishes ownership, so process CWD is never mistaken for an operator
root.

Reconciliation is convergent and issues no writes when the sources agree. It
deletes malformed, misnamed, and dead-process advertisements; restores a
missing advertisement from a live registered instance; and then re-derives
until no repair remains. `stop` can therefore address a registered instance
through any reachable sibling prepl even when the target advertisement was
missing. A process-local reservation marker is also releasable through the
same generated stop form.

Ruling 26 landed at the same choke point:

- `start`, `open`, `logs`, and `config apply` default to `default`;
- `status` reconciles and reports every cluster in the current operator root;
- `stop` and `down` with no name act only for exactly one candidate and
  otherwise refuse with the complete sorted list;
- a bogus name lists the clusters that do exist;
- an explicitly named foreign-root cluster reports that root; and
- an unknown command prints the maintained usage.

### Proof

The test-forward pure matrix exercised shared-JVM destructive ambiguity, stale
advertisement drift, registered-plus-open but unadvertised state, and a
foreign-root JVM: 1 test, 8 assertions, green. The maintained focused gate
remained green: `bin/test seon.dev.fresh-operator-test`, 6 tests, 29
assertions.

The live drive used only
`tmp/operator-reconcile-20260729`, clusters `op-a`, `op-b`, and `default`,
and isolated JVM pid 11074:

- with `op-a` and `op-b` alive in pid 11074, bare `stop` refused with
  `["op-a" "op-b"]` and both remained alive;
- a dead-pid `stale-probe/prepl.edn` was removed by the next `status`;
- after `op-b/prepl.edn` was hidden, the next `stop op-b` restored it from the
  live registry and stopped `op-b` through prepl;
- explicit `start default` named the foreign root
  `/Users/sean/src/seon`, while bare `start` created the isolated root's own
  `default` in pid 11074;
- a start failure that left `default` registered without an open branch
  appeared as `reserved` in status and `stop default` removed it through
  prepl; and
- with one candidate left, bare `down` stopped `op-a`, then the empty isolated
  JVM exited.

After cleanup, project-root status still reported the owner's untouched
`default` at pid 8515, prepl 56088, and web port 7994.

### Additional finding

Operator ownership and bootstrap path spelling are different facts. A live JVM
may have been launched from one CWD while every registered cluster points at
another operator root, so CWD cannot establish ownership. Conversely, after
ownership is verified, changing an existing JVM's bootstrap root from relative
`data/clusters` to the equivalent absolute path exposes a protected
`seon.cluster` seam: `root-store-holder` keys by the uncanonicalized
`store-dir`, attempts a second open of the same physical store, and the flock
correctly refuses it. This operator preserves the live JVM's existing
bootstrap spelling rather than editing the protected runtime owner. A future
runtime change should canonicalize that holder key at
`src/seon/cluster.clj:235-261`; the protected seam is tracked in
[[../root-store-holder-does-not-canonicalize-store-dir]].
