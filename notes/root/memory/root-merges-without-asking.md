---
name: root-merges-without-asking
description: "As root, merge verified branches into main without asking the owner; merging is root's job"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 21e07b71-4cb1-4e53-a41e-5be4db8f99e1
  modified: 2026-10-23T05:40:16.925Z
---

When a branch is verified (green verdicts, review findings fixed, pins checked, bin/check health OK), merge it into main and run bin/update. Don't ask the owner first, and don't end a turn with "merge X?".

**Why:** the owner, 2026-10-23: "Don't ask me for permission to merge if you're acting as root. that's your job." Asking adds a round trip on the critical path of every integration.

**How to apply:** owner questions are for rulings (a new mechanism, a format, priorities), not for routine merges, pushes to public forks ([[push-forks-freely]]), or cleanup of verified garbage ([[kill-only-verified-pids]]). Sessions self-review before reporting ready (AGENTS §3), so a merge is verdicts plus pins plus health.
