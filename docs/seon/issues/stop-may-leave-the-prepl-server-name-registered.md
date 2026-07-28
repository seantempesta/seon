---
type: issue
status: open
tags: [issue, boot]
severity: friction
---

# stop! may leave the prepl server name registered

## Evidence

Flagged by a Gemini review during the failover unit (2026-07-27
night), NOT yet verified: `seon.cluster/stop!` closes the prepl
`ServerSocket` directly instead of calling
`clojure.core.server/stop-server`, which would leave the server NAME
registered in clojure.core.server's registry — a same-name
`start-server` in the SAME JVM would then fail. The cross-process
restart case is proven live (the crash drill); the same-JVM
stop-then-start-same-name case is the open question.

## Acceptance

- A REPL probe: start! → stop! → start! with the same cluster name in
  one JVM succeeds, or the defect is confirmed and stop! uses
  stop-server (the registered name is the resource; the socket is its
  consequence).
- A regression in the boot suite covering same-JVM same-name restart.
