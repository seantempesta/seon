---
type: issue
status: active
tags: [issue, cljs, flow, health]
---

# Interrupted writer test can outlive its runner

## Evidence

On 2026-07-19, a `bin/test-writer` shell had been alive for more than one day
with a JVM child still consuming about 478 MiB RSS. Terminating the shell left
the JVM reparented to PID 1; it required its own normal TERM before exiting.
At the same time, a deliberately retained immutable release cluster consumed
about 3.8 GiB RSS across watcher, writer, and pod. The next default
`bin/seon restart` reached Tailwind and its Bun process was externally killed
with exit 137. Shutting the release through its own `bin/seon down` and
terminating the orphaned test JVM made the identical CSS build finish in 71 ms
and the default cluster reach ready.

## Owner and acceptance

`bin/test-writer` owns its JVM child for every normal exit, TERM, and bounded
timeout. It must forward termination, await child absence, and leave no JVM
that can consume resources or report against later source. A focused operator
test must interrupt the real shell/JVM shape and prove both processes exit.
Uncatchable SIGKILL remains an external limitation, repaired by explicit stale
process discovery rather than a second test runner.
