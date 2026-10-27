---
name: verify-gitlink-pin-bumps
description: A submodule pin bump is a gitlink commit; verify with git ls-tree before reporting it
metadata:
  node_type: memory
  type: feedback
  originSessionId: 753730a3-f6a1-43a4-aa10-cbdcd3a6d09b
  modified: 2026-10-23T03:59:29.553Z
---

A dependency pin (reference-code/<lib>, Chez, Jolt) changes only when the commit records the gitlink. A worktree's checked-out submodule can be at the new SHA while HEAD still pins the old one.

**Why:** jolt/pure-tests' Malli checkout was 0762fb6b, but its commit pinned 5a830e14. A fresh checkout of main ran the old Malli until root committed the bump (ea8f7c44, 2026-10-23).

**How to apply:** before reporting a pin bump, or a branch that depends on one, run `git ls-tree HEAD reference-code/<lib>` and compare it with the submodule's `git rev-parse HEAD`. Related: [[kill-only-verified-pids]].
