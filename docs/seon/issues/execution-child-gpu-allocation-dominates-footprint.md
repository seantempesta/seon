---
type: issue
status: open
severity: friction
tags: [issue, agent, architecture]
---

# Execution-child GPU allocation dominates its memory footprint

## Problem

A headless execution child's physical footprint is ~178 MB, of which
IOAccelerator (GPU driver) regions are 374 MB resident / 163 MB dirty —
~90% of the real cost. The actual JS workload is ~15-20 MB (JIT 8 MB,
Gigacage 2 MB, malloc ~2 MB). RSS (~400 MB) additionally double-counts
the shared read-only Bun binary (~96 MB resident, shared once across all
bun processes).

## Evidence (2026-07-20, live default cluster)

`vmmap -summary` on execution child pid 97520: Physical footprint 177.8M;
IOAccelerator 516.4M virtual / 374.4M resident / 163.2M dirty; JS JIT
8288K resident; JS VM Gigacage 2144K; MALLOC_* ~1.5M; TOTAL dirty 177.8M.
The pod (96960) shows footprint 294.9M against RSS 942M — same
RSS-overstatement pattern.

## Investigation (plan work, not yet a fix)

Find what triggers the IOAccelerator mapping at child startup: suspects
are a graphics-adjacent API touched during bootstrap (canvas/WebGPU/
CoreGraphics via a transitive dependency) or a vendored-Bun default that
can be disabled headless. Probe: bisect child startup with vmmap after
each phase; try JSC/Bun flags; compare a bare `bun -e ""` footprint.

## Why it matters

Hundreds-of-agents scaling: at ~20-40 MB private per child (GPU
allocation avoided), 100 children ≈ 2-4 GB — the goal is reachable on one
host. The open notes execution-children-retain-hundreds-of-megabytes and
eval-process-isolation-memory-containment should be re-read against this
finding: the retained hundreds of MB are mostly not JS-heap retention.

## Acceptance

A child's physical footprint measured ≤50 MB with the GPU mapping absent,
or a grounded explanation of why the mapping is unavoidable, recorded
with the vmmap evidence.
