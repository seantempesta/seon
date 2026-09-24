---
type: issue
status: open
severity: friction
tags: [issue, process, shell, termination]
---

# A subprocess descendant dies of SIGPIPE once the root exits

When the JDK's direct child exits, the process reaper drains and closes that
child's stdout and stderr pipes. The source is
`ProcessImpl$ProcessPipeInputStream/processExited`, at
`java/lang/ProcessImpl.java:589-596` in the clojure-lsp JDK source cache.
Descendants inherit those same pipe fds. A descendant that writes after the root
has exited therefore gets SIGPIPE: its TERM handler does not finish, and whatever
it would have written is lost. This affects `seon.shell.jvm` and
`seon.cluster.process/run-process!`, which both terminate through
`terminate-subprocess!`.

Evidence and the test-side fix are in
`docs/seon/issues/process-test-term-trap-marker-is-intermittently-absent.md`.
Signalling the root last reduced the failures (7 of 8 clean) but did not end them,
because the root can also exit on its own once its direct child dies. Other options,
not yet designed: give descendants a pipe the JVM keeps open until the owner's
termination finishes, or declare that shutdown output from descendants is not
captured. This needs an owner ruling.
