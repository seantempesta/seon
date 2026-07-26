---
type: issue
status: open
severity: friction
tags: [issue, database, flow, research]
---

# Calibrate cross-database writer scaling on shared file persistence

## Problem

Four independent Datahike databases in one writer process do progress in
parallel, but the shared file backend does not approach the research estimate
of four times one-database throughput. The estimate cannot currently serve as
a system gate without separating executor parallelism from persistence
device contention.

## Evidence

U3's 2026-07-23 file-backed Probe A beat workload measured one database at
13.52, 79.55, and 162.08 committed tx/s with 1, 16, and 64 callers. Probe C
measured four databases at 26.49, 123.57, and 251.30 aggregate committed tx/s
with the same per-database caller counts: 1.96x, 1.55x, and 1.55x rather than
approximately 4x.

All runs used one writer process, separate database names and store
directories, zero executor rejections, and production-sized mutation
admission. Raw evidence is in
`tmp/orchestrator/u3-probe-a-beat-{1,16,64}.log` and
`tmp/orchestrator/u3-probe-c-4x{1,16,64}.log`.

The maintained Datahike writer owns one processing thread and one commit
thread per database (`reference-code/datahike/src/datahike/writer.cljc`).
A bounded source search found no process-global Datahike or Konserve commit
lock. The observed ratio decreases as file load rises, which is consistent
with shared persistence-device contention but does not by itself prove that
cause.

## Owner

The writer throughput research and storage deployment boundary own the
calibration. `seon.db.executor` already admits and runs ordinary mutations
within and across databases concurrently; do not add a second mutation lane
or weaken file durability to manufacture the expected ratio.

## Acceptance

- Repeat Probe C with persistence-device utilization evidence and compare it
  with one database at the same per-database offered concurrency.
- Run the same workload with isolated physical persistence devices or a
  controlled non-file backend to distinguish executor scheduling from shared
  file contention.
- Replace the approximate 4x system threshold with measured hardware
  envelopes, or document a deployment topology that actually satisfies it.
- Preserve zero cross-database executor rejections and demonstrate concurrent
  running mutations for distinct databases.
