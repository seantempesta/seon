---
name: kill-only-verified-pids
description: "Before killing any process, check its full command path is my own checkout; pgrep -f patterns match other worktrees' processes"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 21e07b71-4cb1-4e53-a41e-5be4db8f99e1
  modified: 2026-10-23T05:08:57.857Z
---

Never kill a PID found by a pattern (pgrep -f, grep over ps) without first reading its full command line and confirming the path is my own checkout (e.g. next-shrink2), not another worktree (next-land9, next-renders...). Test scratch dirs share names across worktrees (source-test-nextgen-*), so a pattern on the test's name matches every worktree's run.

**Why:** 2026-10-23 I killed root's land9 nextgen test root and an unrelated 31-hour-old holder by matching `source-test-nextgen`; AGENTS §8 says kill only PIDs you started.

**How to apply:** select by my checkout's path, but `./seon` runs from target/ with a relative command line, so match the process's cwd (`lsof -a -p PID -d cwd`), not its command; show it, then kill by PID or `-PGID`. Verification is the rule, not authorship: an orphan or leftover whose path and cwd I've checked (a dead session's test, a lock helper in an old scratchpad, an outdated relay) is mine to kill without asking. The owner (2026-10-23): "why not kill the leftover processes? why wouldn't you". A harness bound (perl alarm) that ends `bin/check` does not end the roots it started: end their process group too.
