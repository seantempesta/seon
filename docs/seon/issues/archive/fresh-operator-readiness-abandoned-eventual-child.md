---
type: issue
status: resolved
severity: blocker
tags: [issue, operator, runtime]
---

# Fresh operator readiness abandoned an eventual child

## Problem

Fresh cluster startup waited only for a readiness socket. If the detached JVM
exited or crossed the 30-second backstop first, the operator learned neither
event and returned without owning cleanup. The corresponding child-JVM test
had the same defect: it timed out in `ServerSocket.accept`, killed only its
wrapper process, and could leave the eventual JVM alive.

The initialization test helper also waited 30 seconds for the Babashka wrapper
and killed only that wrapper even though its Clojure indexing subprocess still
had an observable completion event.

## Evidence

`fresh-process-loads-schema-before-every-operator-instrumentation` failed with
`SocketTimeoutException: Accept timed out`, then printed
`seon scratch added — instrumented 1 vars` after the failure. Readiness had
arrived late and the test had already abandoned its child.

After readiness was raced against process exit, the next run immediately
reported the actual child result instead of timing out:

```text
The anchor exited before readiness.
No `current-src` branch is published; run `bin/seon init` first.

```

That exposed an obsolete regression setup: fresh runtime startup now correctly
requires a published `current-src` branch.

## Owner

`script/seon/fresh_operator.clj` owns the detached JVM from returned PID through
readiness or failure. A readiness observer must race the socket event against
`ProcessHandle.onExit`; its clock is only a loud last-resort backstop. Every
failed start owns descendants-first process-tree termination and waits for the
real exit completions.

## Acceptance

- Readiness and launched-JVM exit are competing observable events.
- A launch that fails, exits, or exceeds the backstop leaves no child or
  descendant process alive.
- The child-JVM instrumentation regression initializes its isolated
  `current-src`, reports child output on failure, and cleans its process tree.
- Initialization test commands observe process completion and never abandon a
  Clojure subprocess behind a killed wrapper.
- The focused instrumentation and fresh-operator tests pass without orphan
  Seon JVMs.

## Resolution

Resolved by `f50024892` (`Make operator readiness own child lifecycle`).

Production startup retains the detached PID, races the readiness socket against
`ProcessHandle.onExit`, and invokes one descendants-first cleanup owner on every
failure. Graceful termination and forced escalation both wait on the process
handles' completion futures; the five-second clock is a loud termination
backstop rather than the source of lifecycle truth.

The test harness now observes command completion through `ProcessHandle.onExit`,
captures output concurrently, and tears down the whole process tree if its
last-resort backstop fires. Its anchor startup uses the same readiness-versus-
exit shape and initializes the isolated source branch before runtime boot.

The direct process-tree regression passed and `bin/seon status` reported one
intended JVM with `orphan seon JVMs: none`. The integrated focused run exercised
19 tests and 88 assertions through the real anchor/add path; the path completed
and printed `seon scratch added — instrumented 1 vars`. Its only remaining
source-publication failure was an honest concurrent-edit digest refusal while
other lanes were changing `src/`, not a readiness or lifecycle failure.
