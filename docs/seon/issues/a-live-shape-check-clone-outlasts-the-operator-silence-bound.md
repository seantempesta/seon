---
type: issue
status: open
severity: blocking
tags: [issue, operator, check, storage]
---

# A live-shape check's store clone outlasts the operator silence bound

## Problem

`bin/seon check --live-shape` (every `bin/lane commit --restart-batch` and `--at-candidate`) clones default's
live store in `seon.operator`'s `live-store!` with `cp -c -R data/store <slot>/data/store`, bounded by
`operator-silence-backstop-ms` (`:seon.config.operator/event-silence-backstop-ms`, shipped 30000). The
clone of the live store takes longer than that, so the check fails before any candidate boots and the
restart batch cannot commit.

Measured by lane konserve-bump, 2026-10-20: `data/store` is 1.3 GB in 214,002 files;
`/usr/bin/time -p cp -c -R data/store <scratch>` took real 62.72 s (sys 50.59 s), disk 93% full.
Three of four `bin/lane commit konserve-bump --restart-batch ... reference-code/konserve` attempts ended
`Command did not exit within its bound.` with `:seon.operator/argv ["cp" "-c" "-R" ".../data/store"
".../data/check/slot-0/data/store"]`, `:timeout-ms 30000` (diagnostics
`data/clusters/default/logs/operator-check-20261020T070150.065.edn`, `...T070256.004.edn`,
`...T070544.826.edn`); the first attempt's clone finished inside the bound.

## Root cause to decide

The clone is bounded by a silence backstop for an operation whose cost is proportional to the store's file
count (an APFS clone is per-file metadata work: ~0.3 ms per file here). Either the clone gets its own dial
sized from that proportionality, or the store's file count is the defect (214k konserve files for 1.3 GB:
see the retention/GC issues), or the clone stops copying file by file (one clonefile(2) of the directory).

## Proof

A red table row in the cost estimate test: the live-shape seed of a store of N files completes within its
estimate, or `bin/seon check --live-shape` on default's store reaches the candidate boot.
