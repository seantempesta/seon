---
type: issue
status: fixed (lane check-clone; live clone verified at the commit, default runs it after its next restart)
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

## Fix (lane check-clone, 2026-10-20)

The producer was the clone mechanism, not the bound: `cp -c -R` clones file by file from user space (open,
stat, clonefileat, attribute and xattr copy per file), and every created inode also feeds fseventsd, Spotlight
and the backup agent, so its cost is a dozen syscalls times the file count and is paced by whatever else loads
the host. `seon.operator/cloned!` now clones the directory in one clonefile(2) call (python3's ctypes, as
`bin/lane` already uses python3); both `live-store!` and `seeded-store!` use it. Measured on this host, same
load (load average 12-15, a backup upload and Spotlight running):

| store | `cp -c -R` | one clonefile(2) |
|---|---|---|
| 2,000 files | 0.97-1.20 s | 0.022-0.030 s |
| default's live store, 214,002 files | 198.3 s (sys 60.9 s) | 10.5 s, 8.3 s (a copy of it); 3.4 s through `cloned!` from bb, process starts included, load average 67 |

End to end: `check --live-shape --test seon.operator-test/a-check-clones-a-store-in-one-call-whatever-its-file-count`
run by this commit's operator on default's live store (214,002 files, live JVM writing) seeded in 3,192 ms
(pointer copy plus clone), booted ready in 20,285 ms and passed 5 of 5 assertions; total 58.3 s.

A concurrent writer into the source directory is not blocked during the directory clone (1,039 writes,
worst 15.6 ms, during an 8.3 s clone), and the clone is no snapshot, the same as `cp`'s: the pointer copy
taken first (`pointer-copy-form`) is what makes it consistent.

Why 18.8 s in the morning and 62.7 s at 07:00: the file count did not change between them; the host load did.
Template seeds of the same 26,308-file template took 2.2-2.4 s from 06:22 to 06:37 and 3.1-7.5 s from 06:41
to 07:01, as a backup upload (`bztransmit`, started about 06:56) and parallel builds ran; `cp -c -R`'s
per-file syscalls scale with that contention. Why the store has 214,002 files at all: its last collection
completed 2026-10-19 03:00 with 79,580 objects after it; none has run since
(`storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing.md` is the collection's open issue).

Remaining per-file cost: the check deletes its slot (`fs/delete-tree`, after the tests, unbounded) one file
at a time; deleting one 214k-file clone took about 90 s under the same load. It cannot fail the check, but
it holds the slot: `a-check-slot-deletes-its-store-clone-one-file-at-a-time.md`.
