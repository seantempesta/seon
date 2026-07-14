---
type: issue
status: resolved
severity: friction
tags: [issue, component, flow]
---

# Cluster reset reused stale artifacts instead of rebuilding source

## Problem

`bin/seon cluster reset default` and `bin/acme cluster reset acme` promised a
fresh, ready cluster but read the previous development artifact manifest after
deleting the database. Source or dependency changes made since that manifest
could therefore run behind a freshly reset database while status still
reported the old manifest identity.

This violates the public operator contract: reset is the destructive form of a
complete reconciliation, not a database-only shortcut around the canonical
build.

## Evidence

`seon.dev.cli/reset-cluster!` previously called `artifact/read-manifest`,
stopped only the pod and writer, deleted the selected database, and reconstructed
process specs directly from that retained manifest. It never called the one
`artifact/build!` path owned by `reconcile-development!`.

The defect was observed while preparing the requested dual-cluster reset: both
commands wiped and reseeded their databases, but retained their preceding
artifact manifests and watcher builds.

## Owner

The one development reconciliation path in `script/seon/dev/cli.clj` and its
operator regression suite in `test/seon/dev/cli_test.clj`.

## Acceptance

- Reset holds the stack lock across stop, wipe, build, and readiness.
- Reset deletes only the selected current-layout database.
- Reset delegates to the same build/reconciliation mechanism as `up` and
  cannot read or start from a previous manifest.
- Focused and complete operator suites pass.
- Fresh public resets rebuild and return both default and ACME ready, followed
  by live HTTP, SSE, MCP CLJ/CLJS, and database-readback proof.

## Resolution

Resolved by `d3da5083`. The operator regression proves reset never reads the
previous manifest and delegates to the canonical build/reconciliation owner;
the later complete operator checkpoint passed 100 tests and 592 assertions.
Before ACME ownership transferred to the parallel agent, public
`bin/seon cluster reset default` and `bin/acme cluster reset acme` each rebuilt
writer, client, bootstrap, and CSS, then returned watcher, writer, and pod
ready. Both roots and data shells returned 200, and cluster-qualified MCP CLJ
and CLJS evaluations returned 42 while a bare `root` was rejected as
ambiguous.
