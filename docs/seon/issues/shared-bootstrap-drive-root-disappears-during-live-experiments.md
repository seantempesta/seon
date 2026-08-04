---
type: issue
status: open
severity: blocker
tags: [issue, bootstrap, eval, testing]
---

# Preserve every live bootstrap drive root and raw report

## Problem

Concurrent bootstrap experiments share `tmp/bootstrap-drives/` as the parent
of their isolated database roots and raw EDN reports. During a live matrix the
parent contents disappeared, invalidating completed evidence and faulting the
writers of still-running roots. The deleting process or owner is not yet
identified.

## Evidence

- On 2026-08-04 the Arm A matrix had produced O1/O2/O3/O4/O5 report counts of
  9/10/4/10/2 under `tmp/bootstrap-drives/`.
- A subsequent read found only `o2-10-a900e98a.edn` and
  `o3-5-b2c8be70.edn`; completed O4's ten reports and the other reports were
  absent.
- The live roots `tmp/bootstrap-drives/9295c147/clusters/store` and
  `tmp/bootstrap-drives/c07548a4/clusters/store` were absent while their JVMs
  were still writing.
- Both writers then reported `java.nio.file.NoSuchFileException` for their own
  Konserve `.new` paths and emitted `:datahike/writer-shutdown` core faults.
- No HTTP 402 or renderer invocation failure preceded the disappearance.

## Owner

First identify the operation that removed `tmp/bootstrap-drives/`. Then
strengthen the shared bootstrap-drive scratch/report lifecycle so one drive,
test, or cleanup owner cannot remove another live drive's root or completed
raw report.

## Acceptance

A recurring proof runs multiple bootstrap drives concurrently, settles raw
reports in each, and tears them down independently. Every sibling database
root remains writable until its own drive stops, every completed raw report
survives, and the proof records the remover if any declared cleanup attempts
to cross its owned root.
