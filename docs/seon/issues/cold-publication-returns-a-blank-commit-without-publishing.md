---
type: issue
status: open
severity: friction
tags: [issue, operator, publication, boot]
---

# Cold publication reports a blank commit without publishing a branch

## Problem

On 2026-09-09, `bin/seon --root tmp/context-blocks-root init` exited zero
after `analysis started: 283 source inputs`, printing `●  commit  digest `.
Starting the owned scratch cluster then refused: no `current-src` branch
was published. MCP reported the failed boot's REPL advertisement as stale.

## Evidence

The isolated loop gate publishes and boots its real test base successfully
from the same selected source. A zero exit and blank source identity are
therefore not a successful publication verdict. The CLI's `init!` reads
`:seon.source/commit-id` without first requiring a published result.
The underlying cold-analysis failure has not yet been attributed.

## Owner

`script/seon/fresh_operator.clj` initialization result boundary and
`src/seon/cluster.clj` source publication. No default lifecycle was changed.

## Acceptance

A failed cold publication reports its complete refusal and a nonzero exit;
a successful publication reports an existing branch and commit. The scratch
cluster boots from that commit, and MCP reports its actual readiness.
