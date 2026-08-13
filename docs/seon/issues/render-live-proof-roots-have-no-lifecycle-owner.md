---
type: issue
status: open
severity: friction
tags: [issue, operator, render, database, class/n4, wave/directory-claims]
---

# Render live-proof roots have no declared lifecycle owner

## Problem

Manually created isolated operator roots used for live render proofs have no
durable ephemeral owner or reap disposition. `bin/seon down` stops their JVMs
but nothing releases the directory.

## Evidence

`tmp/render-live-proof` survives at 92,373,420 allocated KiB (88.09 GiB), born
2026-08-03 06:39 and last modified 07:27. The store is 92,368,756 KiB. File
mtimes prove about 87.8 GiB was written by the complete source publication from
06:40 through 06:44, before the live render evidence at 07:23-07:29. The root
contains no declaration that connects it to the experiment that may reap it.

## Owner

The isolated operator-root lifecycle and the live-proof harness that launches
it.

## Acceptance

- The launching experiment commits an exact directory claim before creation
  and declares whether the root is evidence or reap-on-exit.
- Exit runs supervised `bin/seon down`, awaits every exact child, then releases
  and deletes only its own root when so declared.
- `bin/seon status` shows owner, liveness, reap disposition, and measured bytes
  for the root.
