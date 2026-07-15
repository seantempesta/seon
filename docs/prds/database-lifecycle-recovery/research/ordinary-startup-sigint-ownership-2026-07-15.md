---
type: research
status: complete
tags: [research, database, flow, pod]
---

# Ordinary startup SIGINT ownership

## Exit measure

An OS SIGINT at the watcher, writer, or pod start/readiness boundary exits the
Babashka owner with code 130 only after every process newly started by that
invocation is absent. A converged process from an earlier invocation remains
alive. Signal arrival cannot cross detached spawn plus managed-record
publication and leave an unowned child.

## Dependency ledger

| Dependency or mechanism | Selected source | Relevant behavior |
|---|---|---|
| Babashka | `v1.12.212` | Runs `seon.dev.cli`; JVM shutdown hooks run for SIGINT, but its process future executor is already terminated during hook execution. |
| babashka.process | `v0.6.25`, `16a84e0af0da51b8c84e289970f6b7cc35b35d18` | `reference-code/babashka-process/src/babashka/process.cljc:433-445` installs per-process shutdown hooks; `process/sh` uses executor-backed stream handling and is not a shutdown-hook signal primitive. |
| JDK runtime | Babashka host JVM | `Runtime.addShutdownHook`, one monitor, and synchronous `ProcessBuilder` provide the catchable-signal ownership boundary without a second supervisor. |
| Detached group launcher | `script/seon/dev/detach.py` | `start_new_session=True` makes the recorded leader the process-group id; `seon.dev.process/stop!` already owns exact group TERM/KILL and PID-start identity fencing. |
| Ordinary supervisor | `script/seon/dev/process.clj`, `script/seon/dev/cli.clj` | `specs`, `start-order`, `ensure!`, `wait-ready!`, and `stop!` remain the one process graph and inverse. |
| Prior idiom | `script/seon/dev/changed_test.clj:382-458` | Demonstrates bounded descendant cleanup and a JVM shutdown hook, but owns one attached `Process`, not Seon's retained detached process graph. |

## Smallest executable probes

The first standalone probe launched Babashka with SIGINT restored to its default
disposition, registered a JVM hook, and slept. Sending real OS SIGINT returned
exit 130 and appended `hook` after `ready <pid>`, proving this runtime executes
the hook.

The first implementation then deliberately held the real `spawn-detached!`
phase, sent SIGINT after ownership admission but before publication, and let the
main thread complete the spawn. That probe correctly falsified the draft: the
hook retained the process record and reported that `babashka.process/sh` could
not submit work to its terminated future executor. This was the shortest proof
that using the ordinary shell helper from shutdown was unsafe.

The final mechanism uses a monitor-protected finite state:

- shutdown atomically closes new-spawn admission and snapshots the ordered ids;
- a spawn admitted first retains the monitor through detached launch and exact
  managed-record publication, so the hook waits for a drainable identity;
- an invocation that observes a converged spec never adds its id; and
- reverse-order cleanup calls the existing `stop!`, with only its group-signal
  subprocess implemented directly through synchronous JDK `ProcessBuilder`.

No durable registry, alternate runner, signal daemon, or branch-specific
process path was added. The atom and monitor are invocation-local coordination;
the existing validated process records remain the recovery authority.

## Behavioral evidence

The focused real-signal fixture uses the real `spawn-detached!`, process records,
process groups, and production readiness predicates. Its bounded Python
children only supply the external behaviors those predicates observe:
flavor-owned Shadow completion lines and digest, writer Unix socket plus port
file, and pod HTTP readiness plus port file.

| Injected cut | Result before owner exit |
|---|---|
| ownership admitted, real spawn publication deliberately delayed | spawned PID absent; watcher record absent; owner exit 130 |
| watcher record published, watcher not ready | watcher group/record absent; writer and pod never published |
| writer record published, writer not ready | writer then watcher groups/records absent; pod never published |
| pod record published, pod not ready | pod, writer, then watcher groups/records absent |
| pod not ready with a converged writer | invocation-owned pod and watcher absent; the exact pre-existing writer remains alive and recorded |

The explicit focused branch/CLI/process selector passes 31 tests containing 153
assertions with zero failures or errors. The final process-only edit-hook gate
passes 21 tests/106 assertions at
`tmp/test-changed/changed-operator-1784106925892-6e71d17d-bc88-47b5-92db-cd1f945e856e.log`.

## Remaining boundary

This closes ordinary startup signal ownership and supplies the process inverse
used by the retained branch lifecycle. It does not expose branch CLI syntax,
status, or MCP discovery, and it does not claim the live default-plus-ACME
checkpoint. Those remain the next ordered lifecycle slice.
