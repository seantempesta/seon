---
type: issue
status: open
severity: blocker
tags: [issue, runtime, boot]
---

# Long-lived JVMs permanently lose soft-referenced dynamic classes

Found live 2026-08-03 by the in-server test research
([in-server-tests-2026-08-03.md](../../prds/sci-execution-runtime/research/in-server-tests-2026-08-03.md)):
running a test in the live `default` cluster failed with
`NoClassDefFoundError: datahike/writer$fn__56649$fn__56664`, and a
reflective read of `DynamicClassLoader/classCache` (43,721 entries) showed
both the outer and inner class ABSENT. Clojure's dynamic classloader holds
dynamically defined classes by SOFT reference; under heap pressure the
collector evicts rarely-executed ones, and because their bytecode exists
only in that cache (never on disk), eviction is PERMANENT — the next call
of a rarely-used code path throws `NoClassDefFoundError` in an otherwise
healthy JVM.

The earlier malli.generator/test.check staleness in the same JVM was
attributed at the time to the class-cache refresh mutating files under the
running process. That attribution is now UNCERTAIN: soft-reference
eviction explains the same symptoms without any cache mutation, and the
datahike.writer case occurred with no refresh involved. Both mechanisms
are real hazards; the incident evidence does not distinguish them.

Mitigation is ALREADY IN FLIGHT and this issue strengthens its rationale:
the load-time lane's content-addressed dependency-closure class cache puts
vendored-dependency classfiles ON DISK, so an evicted class is reloadable
instead of gone. Residual exposure after that lands: first-party
namespaces (source-loaded by design for hot reload) and any dynamically
`eval`-defined classes. Acceptance for closing this issue:

- reproduce eviction deliberately (bounded heap + soft-ref pressure) and
  show a cached-dependency class RELOADS after eviction instead of
  throwing;
- decide the first-party posture (accept-and-document with the recovery
  being `require :reload` of the affected namespace, or extend the disk
  cache) with the owner;
- the in-server test runner's first falsifier covers the recovery path
  (`require :reload` restoring an evicted namespace) in an expendable
  cluster, never first against the live default.

Owner: the load-time lane's cache work plus the in-server-tests
implementing lane.
