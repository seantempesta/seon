---
type: issue
status: open
severity: friction
tags: [issue, agent, architecture]
---

# Execution-child native heap mislabeled as GPU dominates footprint

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

## Baseline probe (2026-07-20, decisive)

Bare vendored Bun (`bun -e "setInterval(()=>{},1e4)"`): physical
footprint **5.9 MB**, IOAccelerator dirty ~2.7 MB. With 1e6 live
objects: 7.0 MB. The runtime is NOT the cost; the execution child's
163 MB IOAccelerator dirty is created by Seon's child startup — a ~30x
inflation with a proven ~6 MB floor. The bisect below now has a clean
baseline on both ends.

## Root identification (2026-07-20, from vendored Bun source)

The "IOAccelerator" label is a mislabel: Bun's mimalloc tags every OS
allocation `VM_MAKE_TAG(os_tag)` with default `os_tag` 100
(`reference-code/bun/vendor/mimalloc/src/options.c:143`,
`src/prim/unix/prim.c:369-373`), and macOS VM tag 100 is
`VM_MEMORY_IOACCELERATOR` — vmmap renders mimalloc arenas (128 MB
chunks + 512 MB reserved tail, observed exactly) as GPU memory. The
child's real cost is ~160 MB of ordinary native heap.

## Investigation (plan work, not yet a fix)

Prime suspect: the child loads the DEV artifact — 933 files / 46 MB of
`.shadow-cljs/builds/execution/dev/out/cljs-runtime` — with shadow's
module map, retained source strings, and self-host analysis state
resident. Candidate simple fix: a compact release-style child artifact
(single bundle, no dev module graph, trimmed bootstrap cache). Bisect:
vmmap after artifact load vs compiler init vs database session; compare a
release-build child. Optionally set `mi_option_os_tag` to an app tag so
future profiles label the heap honestly.

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
