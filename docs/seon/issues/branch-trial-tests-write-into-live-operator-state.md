---
type: issue
status: open
tags: [issue, database, testing]
severity: friction
---

# Branch trial tests write into the live operator state directory

## Problem

A branch-lifecycle trial (the `branch-sigint-reuse` harness, run
2026-07-20) left `tmp/seon-operator/branches/trial.edn` — a retained
branch intent whose writer socket names a dead
`tmp/br-adf9b3df-…/writer.sock` and whose lifecycle path does not match
the CLI's `<cluster>-<name>.edn` convention (`branch status trial`
answers "No retained branch intent exists" while `bin/seon status`
fails with "The retained branch intent names another source owner").
The stale intent made `bin/seon status` unusable for the live default
cluster until the file was deleted by hand. The same trial also left
`detach.py` owner processes and dozens of `tmp/br-*` directories.

## Expected owner

The branch trial harness (`tmp/branch-sigint-reuse-*` scripts /
`seon.dev.branch` tests) must run against its own operator process
directory, never `tmp/seon-operator`, mirroring how `bin/acme` isolates
its process dir; and retained intents it creates must be reaped by the
trial's own teardown.

## Acceptance

- Branch lifecycle trials write intents/containment only under a
  trial-private process directory.
- `bin/seon status` on the live default cluster is unaffected by any
  trial run, including an interrupted one.
- An intent file whose name does not match the lifecycle-path
  convention is surfaced by `status` as reapable drift, not a hard
  failure with no repair command.
