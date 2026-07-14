---
type: issue
status: open
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
