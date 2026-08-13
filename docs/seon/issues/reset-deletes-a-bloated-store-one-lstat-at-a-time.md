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
  attribute syscalls (measured before/after in the note).
- The symlink sentinel regression still passes (safety unchanged).
- A deletion running longer than the declared backstop emits progress
  naming the directory and counts.
