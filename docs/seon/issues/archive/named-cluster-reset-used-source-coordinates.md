---
type: issue
status: resolved
severity: blocker
tags: [issue, operator, database]
---

# Bind named-cluster reset to the selected target

## Problem

The first implementation of arbitrary `cluster reset <name>` selected the
named launch descriptor but continued to use the top-level source
configuration for its database deletion. A reset request for `agentload0726`
therefore deleted the default database.

## Evidence

During the 2026-07-26 named-cluster proof, the first
`bin/seon cluster reset agentload0726` invocation stopped the requested named
processes but removed `data/clusters/default/db`. The default database's blob
files survived, but the database itself was not recoverable from its lazy PSS
nodes or a user-data snapshot. The default cluster was rebuilt fresh by the
normal reset/apply path. This was destructive user-data loss and is recorded
here explicitly.

The cause was mixed use of the source `configuration` and selected
`target-configuration` inside `reset-cluster!`. The corrected implementation
now derives the database path, applied manifest, package skeleton, process
targets, and selected release from the target configuration and checks exact
target/source equality before deletion.

## Owner

`seon.dev.cli/reset-cluster!` owns the destructive boundary.
`seon.dev.cluster/request` owns named target selection.

## Acceptance

- `cluster reset <name>` rejects any unresolved or mixed target coordinate
  before deleting anything.
- A named reset stops and deletes only that target's processes, database,
  applied manifest, and generated package skeleton.
- Default watcher/writer generations and the default database survive a named
  reset unchanged.

## Resolution

Resolved by `037e285e2` (`make named cluster reset coordinate-safe`).
The corrected live proof held the default watcher PID `66172` and writer PID
`88277` unchanged while removing the named database and preserving the default
database. The final post-load falsifier repeated the check: default watcher
generation `77448069-93b3-4491-b563-9aab360d93d6` / owner PID `14119` and
writer generation `a4f62e62-cce4-409d-bf04-6bf94da1ebc5` / owner PID `14973`
were unchanged; the named host/pod/web-render records and database were absent,
while the default database remained present and ready.
