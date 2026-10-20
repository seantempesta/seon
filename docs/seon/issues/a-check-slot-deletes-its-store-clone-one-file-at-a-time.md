---
type: issue
status: open
severity: friction
tags: [issue, operator, check, storage]
---

# A check slot deletes its store clone one file at a time

## Problem

`seon.operator/candidate-tests!` deletes its slot root (`fs/delete-tree disposable`) in its `finally`, after
the tests ran and before the check answers, while it holds the slot lock; `swept-check-roots!` and the slot's
own pre-clean delete the same way. A live-shape check's slot holds a clone of default's store, so the delete
is one unlink per konserve file: proportional to the store's file count, not to anything the check did.

Measured by lane check-clone, 2026-10-20 (load average 12-15): `rm -rf` of three 214,002-file clones of
`data/store` took about 270 s, about 90 s per clone. Since `seon.operator/cloned!` made the clone one
clonefile(2) call (3.4-10.5 s for the same store), the delete is the larger per-file cost of a live-shape
check. It cannot fail the check (it runs under no bound), but it delays the answer and holds a slot.

## To decide

The work proportional to the file count is the unlinking, which no directory-level syscall avoids on APFS.
Options: the store's file count (its collection is behind: 79,580 objects after the 2026-10-19 03:00
collection, 214,002 files now; `storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing.md`), or
moving the slot's store aside atomically and releasing the slot before the delete finishes.

## Proof

A row in the operator test: a check's answer and slot release do not wait on deleting a store clone of N
files.
