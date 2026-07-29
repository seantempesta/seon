---
type: issue
status: open
tags: [issue, boot]
severity: friction
---

# stop! may leave the prepl server name registered

## Evidence

Flagged by a Gemini review during the failover unit (2026-07-27 night), then
source-verified 2026-07-29: `seon.cluster/start!` registers the named PREPL
through `clojure.core.server/start-server` (`src/seon/cluster.clj:969-974`) and
its failed-start cleanup calls `clojure.core.server/stop-server` (line 997),
but ordinary `seon.cluster/stop!` closes the raw `ServerSocket` directly (line
1170). The same-JVM stop-then-start-same-name behavioral reproduction remains
the batch's acceptance proof.

## Acceptance

- A REPL probe: start! → stop! → start! with the same cluster name in
  one JVM succeeds, or the defect is confirmed and stop! uses
  stop-server (the registered name is the resource; the socket is its
  consequence).
- A regression in the boot suite covering same-JVM same-name restart.
