---
type: issue
status: resolved
severity: blocker
tags: [issue, testing, tooling, deletion]
---

# Make every fresh recursive delete refuse symlink traversal

## Problem

Fresh production and test code has reintroduced the exact unsafe cleanup
shape that deleted 55 tracked paths on 2026-07-29: reverse a `file-seq` and
delete every returned entry. `file-seq` follows directory symlinks. A link
inside any of these roots can therefore make cleanup delete files outside the
root before it removes the link itself.

This is a direct regression against the repository's standing recursive-
deletion law and against the shared no-follow implementation and sentinel
regression that resolved
[[archive/a-test-fixture-deleted-tracked-files-through-symlinks]].

## Evidence

- `src/seon/cluster/store.clj:257-280` calls
  `delete-store-directory!` before creating or repairing a store; its
  implementation is `reverse (file-seq root)` followed by `.delete`.
- `src/seon/cluster/export.clj:93-97` duplicates the same implementation. It
  is called for retransact scratch data (`:129-171`), a failed clone target
  (`:174-203`), and a failed export temp tree (`:291-302`).
- `test/seon/cluster/export_test.clj:45-49`,
  `test/seon/cluster/store_test.clj:34-38`, and
  `test/seon/config_application_test.clj:140-142` each implement another
  following recursive delete instead of using `seon.test-support`.
  `test/seon/cluster/armed_test.clj:45-51,165-169,253-262,307-313` repeats
  the raw walk at four live cluster boundaries.
- `test/seon/test_support.clj:87-135` already owns a lexical-root-bounded,
  `NOFOLLOW_LINKS` cleanup and the required external-sentinel regression.
  The unsafe copies were added or survived after that owner landed.
- The resolved incident note records the observed blast radius: the same
  pattern followed scratch-tree symlinks and deleted all maintained `src/`
  plus thirteen vendored working trees. This is therefore a reproduced data-
  loss class, not a hypothetical hardening preference.

## Owner

The filesystem deletion boundary. Production store/export cleanup must share
one no-follow implementation with an explicit owned root; test cleanup must
use `seon.test-support/delete-recursively!` rather than local copies.

## Acceptance

- No production or test recursive delete uses `file-seq`, follows links, or
  derives its authority from the process working directory.
- Every delete walks with `NOFOLLOW_LINKS`, treats symlinks as leaves, and
  refuses an entry outside the exact caller-owned root before deleting it.
- Store creation/repair and export cleanup each have a regression that plants
  a symlink to an external sentinel and proves the sentinel survives.
- The existing shared test-support sentinel remains green, and a repository-
  wide search finds no new private recursive-delete implementation.

Resolved by `a4f739333`: `seon.fs` is the one no-follow deletion owner
(refuses escape from its own root, deletes link entries without
traversal); store/export and every unsafe test walker converted with
local copies deleted; sentinel regressions recur at the owner. Safety
gate 26/87/0.
