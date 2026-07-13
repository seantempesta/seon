---
type: reference
status: draft
tags: [reference, agent, database]
---

# Third-party deployment boundary

This is not current operator guidance. The default cluster has completed the
database-server naming and database-layout cutover, while the ACME downstream
wrapper is intentionally pinned to its previous state until the default system
passes cold-start and live-agent proof. Do not copy ACME commands from history.

## Settled target

A deployed cluster has two long-running processes:

- the JVM `seon.db.server`, the sole Datahike writer and selected heavy-data
  worker; and
- the Node ClojureScript pod, which runs agents, context derivation, and the web
  UI while reading its local immutable replica.

Writes cross the typed database protocol. The startup manifest is desired-state
input that boot reconciles into database facts. Downstream product code remains
outside Seon and may join the pod build through `SEON_EXTRA_SRC` and
`SEON_EXTRA_PRELOAD`.

The standalone database-server artifact is built from the same dependency basis
as the source launcher:

```bash
clojure -T:build writer-uber
# target/seon-database-server-standalone.jar
```

That successful build proves the server artifact only. A supported third-party
distribution also needs a packaged CLJS pod, manifest and asset lookup rules,
database/socket paths, readiness checks, and an upgrade contract. Those pieces
must be proven together before this page becomes an operator runbook.

## Cutover acceptance

After the default cluster is stable:

- update `bin/acme` to delegate to the current `bin/seon` process names and
  database directory;
- rebuild the ACME pod from current source;
- cold-start both ACME processes and prove readiness ordering;
- create an agent, write and read a database fact, render a canvas, and exercise
  a button and form through the normal reactive path;
- prove the default cluster's PIDs, sockets, database, and logs were untouched;
- then replace this draft with the exact, observed packaging and startup
  commands.

Until that proof exists, use [[../process-management]] for the in-checkout
development system and [[../components/acme-harness]] only as a deferred-state
record.
