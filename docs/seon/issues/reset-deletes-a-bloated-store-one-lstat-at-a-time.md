---
type: issue
status: open
severity: friction
tags: [issue, operator, performance, class/p2]
---

# Reset deletes a bloated store one lstat at a time

## Problem

`bin/seon reset --force` on a 69 GB store ran `seon.fs/delete-recursively!`
for 21+ minutes and was still walking when interrupted: the symlink-safe
walk does a per-file `Files/isSymbolicLink` lstat, single-threaded, so
deletion is O(files) at syscall pace. On a bloated store that turns the
recover-from-anything operator verb into an hour-long silent wedge — a
direct violation of the ten-second-start law's spirit and a development
velocity tax exactly when the operator is most needed. The operation also
emits no progress, so it is indistinguishable from a hang (the
events-with-loud-backstops law: long work must publish progress or a
bounded heartbeat).

## Evidence

2026-08-13: jstack of the reset JVM (pid 59728, 21 minutes elapsed, main
RUNNABLE) showed `seon.fs$delete_recursively_BANG_$walk_BANG_` in
`Files.isSymbolicLink` → `UnixNativeDispatcher.lstat0` (`src/seon/fs.clj:71`).
A manual `rm -rf` of the same tree completed in seconds.

2026-08-13, commit `bdceb7915`: the focused synthetic measurement created
100,000 empty files in one directory, then compared the old two-probe walker
and the new one-attribute-read walker in the same JVM. Deletion took 5,755 ms
before and 5,508 ms after, a roughly 4% improvement. That is not the hoped-for
IO-bound transformation: the extra per-entry lstat was not the dominant cost
for this synthetic tree. The original 21-minute crawl likely paid primarily
for its enormous flat store directory and total file count. The proven gains
here are narrower and still real: one filesystem attribute syscall is removed
per entry, and `delete-recursively!` now exposes rate-bounded progress carrying
the current directory, elapsed time, and file/directory/symlink counts.

Focused proof: `bin/test seon.fs-test` passed 2 tests / 11 assertions. The
existing external symlink sentinel survived, intermediate symlink traversal
was still refused, and a deletion exceeding a 1 ms test backstop published
bounded progress with directory and counts.

## Owner

`seon.fs/delete-recursively!` (`src/seon/fs.clj`). The symlink-safety law
stands (never follow links; refuse escapes) — the fix is mechanical:
directory-stream attributes already carry the symlink bit
(`Files/newDirectoryStream` / `DirectoryStream` with
`LinkOption/NOFOLLOW_LINKS` attribute reads avoid the second lstat), and
the walk can delete per-directory batches; large deletions should emit
periodic progress through the operator's event surface.

## Acceptance

- Deleting a synthetic 100k-file tree is bounded by IO, not per-file
  attribute syscalls (measured before/after in the note). **Not demonstrated:**
  the measured improvement was only about 4%.
- The symlink sentinel regression still passes (safety unchanged). **Proven in
  `bdceb7915`.**
- A deletion running longer than the declared backstop emits progress naming
  the directory and counts. **Proven at the `seon.fs` callback seam in
  `bdceb7915`; operator caller wiring is deferred until the overlapping
  `seon.operator` census work lands.**

## Remaining question

Measure the new walker against a representative bloated store shape, including
the original scale and flat-directory fanout, after the operator caller wires
its declared event-silence backstop and output event surface into the new
callback. That evidence decides whether one attribute read per entry is enough
or whether deletion needs a different batching or native-filesystem mechanism.
