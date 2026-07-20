---
type: issue
status: resolved
tags: [issue, cljs, flow, health]
severity: friction
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

## Resolution

`bin/test-writer` now launches its JVM as an explicitly owned child. TERM, INT,
and HUP forward to that child, wait up to ten seconds for normal shutdown,
force it only if that grace expires, reap it, and return the signal-derived
shell status. Normal completion preserves the JVM's original exit status.

The focused operator regression replaces only `bb` and `clojure` through PATH,
runs the real `bin/test-writer`, observes its owned child, terminates the shell,
and proves the child is absent before the shell returns exit 143. The complete
`seon.dev.cli-test` namespace passes 39 tests and 104 assertions.
