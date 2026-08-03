---
type: issue
status: open
severity: blocker
tags: [issue, architecture, cluster, flow, runtime]
---

# Give each cluster durable work-launcher ownership

## Problem

Every cluster boot installs a new process-global work launcher. Installation
atomically replaces the prior launcher and then stops it, including
`shutdownNow` on its owned task executor. All later `submit!!` calls resolve
only the replacement. Starting cluster B can therefore interrupt cluster A's
active submissions and silently apply B's queue/concurrency configuration to
every cluster in the JVM.

This contradicts `cluster/start!`'s claim that co-hosted clusters share only
the root store and root executors.

## Evidence

- `src/seon/cluster.clj:1368-1371` calls `install-work-launcher!` during every
  cluster boot.
- `src/seon/flow.clj:465-480` stores exactly one launcher and stops the previous
  value after every install.
- `src/seon/flow.clj:455-463` stops the graph and invokes `shutdownNow` on the
  launcher's task executor.
- `src/seon/flow.clj:492-514` routes every submission through that one current
  launcher.
- `src/seon/cluster.clj:1629-1630` stops the launcher only when the last cluster
  stops; lifecycle already treats it as process-global while boot treats its
  configuration as cluster-local.

An attempted two-cluster live falsifier reached the current in-flight schema
lane's unresolved `:seon.schema.admission/source` boundary before the sibling
could become ready. The source-level replacement and interruption sequence is
unconditional and does not depend on that unrelated boot failure.

## Owner

`seon.flow` work-launcher custody and `seon.cluster` lifecycle wiring.

## Acceptance

Starting or stopping cluster B cannot replace, stop, reconfigure, or interrupt
cluster A's accepted work. The owning configuration and lifecycle are explicit:
either one launcher per cluster, addressed from that cluster's graph, or one
genuinely process-root launcher whose single process-wide configuration is not
reinstalled by cluster boot. A concurrent two-cluster proof holds work in A,
starts and exercises B, and observes A complete exactly once.
