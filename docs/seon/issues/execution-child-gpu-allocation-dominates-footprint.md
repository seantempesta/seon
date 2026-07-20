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

## Bisect result (2026-07-20, live default cluster + controlled loads)

Full phase bisect in
`docs/prds/source-cleanup/research/child-footprint-bisect-2026-07-20.md`.
The dev-artifact hypothesis is FALSIFIED: a release `:simple` single
bundle (7.5 MB, built with the existing release machinery) loads to a
HIGHER footprint (104.6 MB) than the 933-file dev artifact (89.1 MB), and
node is no better (114–118 MB). The measured phases: bun floor 5.9 →
artifact load 89 → child ready (db session + admission projection) 180 →
first rendered prompt 214 → first eval 221 MB; a heavy eval burst then
permanently inflates the child to 416 MB (JSC heap capacity and mimalloc
dirty pages are never returned — footprint is peak-shaped, not
live-shaped). `MIMALLOC_OS_TAG=240` confirmed the honest relabel;
`BUN_JSC_forceRAMSize=64Mi` cut load footprint ~15%. No genuinely-simple
fix exists: the levers are the child's full seon.* require closure
(~85–100 MB), leaner child admission projection (~91 MB), and a GC/heap
cap for burst retention — each a bounded PRD unit, sized in the research
doc.

## Why it matters

Hundreds-of-agents scaling: measured per-child cost is ~180 MB
idle-ready / ~220 MB after one prompt+eval, degrading toward ~400 MB
with heavy turns — N=100 projects to 18–22 GB steady (worst ~40 GB), so
the 2–4 GB goal needs the closure/admission/heap-cap units, not
packaging. The open notes execution-children-retain-hundreds-of-megabytes
and eval-process-isolation-memory-containment are explained by the
peak-shaped retention finding: live JS heap returns to ~124 MB after GC
while JSC capacity and mimalloc dirty pages stay at the high-water mark.

## Acceptance

A child's physical footprint measured ≤50 MB with the GPU mapping absent,
or a grounded explanation of why the mapping is unavoidable, recorded
with the vmmap evidence.
