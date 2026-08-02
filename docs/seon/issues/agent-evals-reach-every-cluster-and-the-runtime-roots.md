---
type: issue
status: open
severity: blocker
tags: [issue, sci, runtime, database]
---

# Stop installing private vars so agents cannot reach other clusters or the runtime roots

## Problem

An agent evaluating through the door reaches every cluster in the JVM
and the process's mutable runtime roots. Probed live on cluster
`default`, 2026-08-02, via `seon.sci.eval/evaluate` against the live
cluster ctx:

```clojure
@@#'seon.cluster/running-instances
```

returns the full instance map — every cluster's live
`datahike/Connection`, the store `FileLockImpl`, the flow graph, the
agent routing atom, the prepl `ServerSocket`, and each cluster's own
SCI ctx env atom. From there an agent can write to another cluster's
branch, release the flock, or mutate routing state.

THE CAUSE IS OUR INSTALL STEP, NOT SCI. SCI is deny-by-default and that
part works: in the same context `future`, `pmap`, and
`Thread/currentThread` are all unresolvable ("Unable to resolve
symbol"), because `build-base-ctx` installs only the namespaces it
names and `:classes` carries only `Throwable`/`Error`
(`src/seon/sci/eval.clj:156-228`). Agents cannot spawn threads at all
today.

The reach exists because `acquire!` installs the program graph
INCLUDING PRIVATE VARS. Observed in the live ctx env:
`#'seon.cluster.store/held-flocks`, `#'seon.cluster.store/release-store!`,
`#'seon.cluster.store/panic-on-core-error?`,
`#'seon.cluster.store/jdk-integers->long`, `#'seon.cluster.run/refuse!`
— all `defn-`/`^:private` — alongside the public functions.
`#'seon.cluster/running-instances` is a `defonce ^:private`
(`src/seon/cluster.clj:188`) reachable the same way.

Ruling #20 makes every function in the cluster's PROGRAM GRAPH
callable. Whether private implementation vars are part of that graph is
the argument this fix turns on, and it must be made explicitly rather
than assumed: excluding them restricts no documented agent capability,
while installing them hands out the process's mutable roots.

## Acceptance

- The install seam stops publishing private vars into the cluster ctx
  (or an equivalent computed rule — never a hand list of names), with
  the ruling-#20 argument recorded in the plan.
- Verified before landing: no bootstrap or agent-facing path depends on
  reaching a private var.
- Process-global roots (`running-instances`, the root store holder,
  held flocks, root executors) are unreachable from an agent
  evaluation, proven by the probe above returning a resolution error.
- An agent in cluster A cannot obtain cluster B's connection by any
  route: var reach, an installed function's return value, or a captured
  object's methods.
- The deny-by-default properties that already hold become PINNED
  regressions rather than incidental: no thread creation, no host class
  beyond the installed set, no arbitrary interop.
- Regressions live in the database/SCI stability suite (in-memory
  connections, no disk growth).

Related: [[seon-db-is-not-the-one-database-namespace]] (ruling #41's
custody model), and ruling #30's persistence gate, which governs what
an agent may COMMIT and is a different control point from reachability.
