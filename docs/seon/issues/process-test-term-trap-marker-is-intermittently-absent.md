---
type: issue
status: resolved
severity: friction
tags: [issue, process, test, flake]
---

# The well-behaved-tree process test intermittently found no TERM-trap marker

## Cause (verified 2026-09-24, lane fix-nil-process-agent)

The fixture launched the tree with babashka's default `:out`/`:err` pipes. When the
JDK sees its child (the tree's root) exit, the process reaper drains those pipes and
closes their file descriptors. See `ProcessImpl$ProcessPipeInputStream/processExited`
at `java/lang/ProcessImpl.java:589-596` in the JDK source that clojure-lsp caches.
The trapping shell was still waiting for its TERMed `sleep`. It then writes
"Terminated: 15" to its inherited stderr, receives SIGPIPE and dies before its
`trap` runs.

Evidence, in a `git archive` snapshot using `clojure -M:test`, with processes the
probe itself started:

| variant (owner = `terminate-subprocess!`) | marker written |
|---|---|
| owner, default pipes | 0/10, 1/10, 1/20 |
| owner, `:err :discard :out :discard` | 10/10 |
| owner, stderr redirected to a file | 10/10 (file held `"Terminated: 15\n"`) |
| a plain shell sending TERM in the same forward order | always |

The root can also exit by itself once its direct child dies. So no signal order in
the owner guarantees that a descendant's handler still has the pipe. A trial that
signalled the root last still failed 1 of 8 runs.

## Fix

The test fixture `start-tree` now sends the tree's output to `/dev/null`. The test
was 15/15 green afterwards; before, it failed in about 1 of 3 runs. The owner is
unchanged.

## Still open (production, not this test)

Any caller whose child's output is JDK pipes, such as `seon.shell.jvm` and
`run-process!`, has the same property. After the root exits, a descendant that
writes to stdout or stderr gets SIGPIPE. Its TERM handler may therefore not
complete, and the output it would have written is lost. Changing that means
changing the owner's termination semantics, or keeping the pipes open (the
descendants inherit the pipes while the JDK owns only the root). That decision
needs an owner ruling. It is not a test concern.
