---
type: issue
status: resolved
severity: blocker
tags: [issue, bootstrap, eval, testing]
---

# Preserve every live bootstrap drive root and raw report

## Problem

Concurrent bootstrap experiments share `tmp/bootstrap-drives/` as the parent
of their isolated database roots and raw EDN reports. During a live matrix the
parent contents disappeared, invalidating completed evidence and faulting the
writers of still-running roots. The owner later identified the remover as the
orchestrator's emergency disk sweep; that sweep is halted and `tmp/` deletion
is frozen. The incident remains the evidence for an explicit directory-claim
owner rather than an unidentified-remover investigation.

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
- The owner attributed the deletion to the orchestrator's emergency disk
  sweep and froze further `tmp/` deletion on 2026-08-04.
- A replacement 100-attempt experiment completed with every raw report and
  live database root isolated below
  `tmp/bootstrap-drives-rerun-20260805T000202Z/`; this avoids collision but
  does not supply the durable ownership claim required below.

## Owner

Strengthen the shared bootstrap-drive scratch/report lifecycle so one drive,
test, or cleanup owner cannot remove another live drive's root or completed
raw report.

The strengthened owner is a declared directory claim in a stable parent
database, recorded before creating the drive root. It connects the canonical
path to the drive/experiment fact and exact process identity. Liveness is
derived from that identity; a path prefix, mtime, or `ps` text match is never a
claim. The claim authority must not live only inside the directory it protects.

## Acceptance

A recurring proof runs multiple bootstrap drives concurrently, settles raw
reports in each, and tears them down independently. Every sibling database
root remains writable until its own drive stops, every completed raw report
survives, and the proof records the remover if any declared cleanup attempts
to cross its owned root.

Every drive root is queryable by canonical path for its owner, exact process
identity/liveness, evidence/reap disposition, and latest measured bytes.
Deletion refuses while a live claim exists or ownership is absent/ambiguous.
Teardown awaits every claimed child before releasing the claim or removing
records and files.

## Resolution

Resolved by the external operator existence authority. The installation's one
control root now holds atomic, one-record-per-identity root and process claims
outside every managed `data/clusters` tree. `seon.operator/claim-root!` publishes
canonical path, creator, disposition, store, and cluster intent before creation;
`seon.operator/existence` derives exact process liveness from those records
without opening Datahike. The record therefore survives both process death and
explicit managed-tree cleanup. Fixture launchers also await exact children
before deleting their isolated roots.

Recurring regressions prove a claim remains queryable while its target is
absent, cleanup does not follow a symlink, and fixture roots do not survive
their owning test.
