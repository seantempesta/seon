---
type: research
status: complete
tags: [research, database, flow, pod, architecture]
---

# Dead-leader process-subtree containment — 2026-07-15

## Decision

Do not make `seon.dev.process/stop!` signal a recorded numeric process group
after its recorded leader identity is dead. That refusal is the correct
fail-closed behavior: the same positive PGID may denote either remaining old
members or a later unrelated group once the old group lifetime has ended.

Replace the throwaway detached launcher with one persistent containment owner
outside the pod execution group and one minimal anchor that remains the
execution-group leader while the pod and every admitted execution child run in
that group. The operator records the containment owner's PID and start instant;
the owner's atomic launch descriptor also records the generation token and the
anchor's PID, start instant, and PGID. The pod is a child workload, not the
recorded group leader.

The anchor is the only code that signals the execution group. The containment
owner begins drain when the pod exits or receives a generation-matched command
on its private Unix socket, then tells the retained anchor over its private
pipe. The anchor ignores its own `SIGTERM`, calls `killpg()` from inside the
still-pinned group, waits the bounded grace, announces final escalation to the
owner, and calls one final `killpg(SIGKILL)` on its own group. That syscall
necessarily begins while the anchor is a live member; PGID reuse is impossible
at the signaling instant. The outside owner waits for and reaps its direct
anchor, validates the announced transition plus exit status, atomically
publishes a terminal drained result, and exits. The operator waits for that
result and the exact containment owner's exit before clearing the record or
admitting replacement readiness. It never probes or signals the PGID.

If the containment owner or anchor disappears without the terminal result,
the state is `containment-uncertain`: retain the complete descriptor, expose
degraded status, and refuse replacement. Do not turn an unproved absence into
readiness. A later Linux hard backend may satisfy the same descriptor and
terminal-result contract with one delegated cgroup v2 subtree; it does not
create a second lifecycle state machine.

This is the smallest mechanism that closes the current pod-death case without
claiming that a PID scan, `kill -0`, Java descendant snapshot, launchd label,
or naked PGID is an owned handle.

## Scope and shortest falsifier

This audit owns the process-containment prerequisite between the integrated
retained-branch SIGINT work and the future disposable eval child. It read the
target runtime architecture, the database lifecycle roadmap, the parent
capability and process-death audits, current operator source and tests, selected
dependency source, and OS interfaces. It changed no application or test source
and touched no default or ACME process.

The current failure is exact:

- `script/seon/dev/detach.py` calls Python `Popen(...,
  start_new_session=True)`, prints the workload PID, and exits. The workload is
  therefore both the recorded process and process-group leader.
- `spawn-detached!` persists that PID, its Java `ProcessHandle` start instant,
  and the equal PGID. No retained process object or kernel containment object
  survives in the operator.
- `stop!` drains a live leader's group. When the exact leader is dead but
  `kill(-pgid, 0)` still succeeds, it throws “Refusing to signal a process
  group whose recorded leader is dead.” The record remains and `ensure!`
  cannot start a replacement.

A project-local fixture used the real `detach.py` with a group-leading Bash
workload and one `sleep` child. Before failure, leader `26456` and child
`26457` both had PGID `26456`. After `SIGKILL` to the leader, the child was
alive under PPID 1 with the same PGID, and `/bin/kill -0 -- -26456` succeeded.
The fixture then killed its known disposable group and removed its files.

A second project-local fixture demonstrated the necessary anchor law. A
session leader `27090` remained alive while workload `27091` died; descendant
`27092` remained in PGID `27090`. Sending the final group `SIGKILL` while
anchor `27090` was still alive removed the descendant. This is executable
evidence for the ordering, not a production implementation.

## Dependency ledger

| Dependency or mechanism | Selected identity | Exact source and consequence |
|---|---|---|
| Seon operator | `script/seon/dev/process.clj`, `state.clj`, and `detach.py` last changed by `c95f8e0397000fc2d34fcfaa4d23b8febc44a6fa` | `process-status` proves only the recorded PID/start pair. `group-alive?` and `group-command` act on the numeric PGID. `stop!` correctly refuses the dead-leader/live-group ambiguity. `with-startup-ownership` already supplies acquisition/inverse serialization and must remain the one transition owner. |
| Babashka | `1.12.212`; `babashka.process` `0.6.25` at `16a84e0af0da51b8c84e289970f6b7cc35b35d18` | `reference-code/babashka-process/src/babashka/process.cljc` builds a Java process and implements `destroy-tree` by taking a Java `descendants()` snapshot. It cannot recover reparented descendants after the parent dies. Shutdown cleanup also cannot depend on its future executor, as the ordinary-SIGINT proof already established. |
| OpenJDK | Homebrew OpenJDK `26.0.1`; exact installed source archive SHA-256 `df4a348c5523cfbc81aae596003a055a454e6c421730c7ca17783d229e628866` | Exact selected source is `/opt/homebrew/Cellar/openjdk/26.0.1/libexec/openjdk.jdk/Contents/Home/lib/src.zip`, `java.base/java/lang/ProcessHandle.java`, `ProcessHandleImpl.java`, and `ProcessImpl.java`. No matching JDK mirror exists under `reference-code/`. `children()` and `descendants()` are explicitly snapshots. Native `ProcessHandleImpl.destroy0(pid,startTime,...)` checks one process start time, but `ProcessImpl` still documents an unavoidable check-to-kill PID race. ProcessHandle remains diagnostic/wait evidence; the operator addresses the owner through its generation-bound control socket and never reconstructs signaling authority from a PID. No Java process-group handle exists. |
| Python | Homebrew CPython `3.14.6`; selected `subprocess.py` SHA-256 `6628ffdd65c093a6c08cae01ffe82877d3ced515aac9e7be0cff16512c30a7d9` | Exact installed source is `/opt/homebrew/Cellar/python@3.14/3.14.6/Frameworks/Python.framework/Versions/3.14/lib/python3.14/subprocess.py`; no matching CPython mirror exists under `reference-code/`. `start_new_session` reaches the POSIX spawn/fork path and `Popen.wait()` is the direct-child reap. The current helper discards that retained object by exiting. Strengthen this existing helper boundary rather than add a parallel launcher. |
| macOS process API | macOS `26.5.2`, build `25F84`; local `kill(2)`, `setsid(2)`, `setpgid(2)`, and `launchd.plist(5)` manuals; `/bin/kill` SHA-256 `78ebcba3034b8e84d2ef02f47689cd9d38aad8832bf1d1f517c0d7e57bd1c6c8` | `setsid()` makes the caller the sole session and group leader. Negative `kill()` targets every current group member. These APIs expose only numeric PGIDs. `libproc` can list PGID members, but that remains a snapshot and is not signaling authority. |
| POSIX.1-2024 | [Definitions, process group lifetime](https://pubs.opengroup.org/onlinepubs/9799919799/basedefs/V1_chap03.html) and [process ID reuse](https://pubs.opengroup.org/onlinepubs/9799919799/basedefs/V1_chap04.html) | A process group lives until its last member leaves, and its ID cannot be reused during that lifetime. Therefore a known-live exact anchor pins the numerical PGID. Once the anchor and all old members leave, reuse is legal and a delayed signal is unsafe. |
| Linux eventual target | [Linux credentials and process groups](https://man7.org/linux/man-pages/man7/credentials.7.html) and [kernel cgroup v2](https://www.kernel.org/doc/html/latest/admin-guide/cgroup-v2.html) | Linux process groups have the same numeric limitation. A delegated cgroup v2 directory provides the stronger backend: `cgroup.kill` kills its subtree and `cgroup.events` reports recursive `populated 0`. Availability and delegation must be admitted explicitly; lack of cgroup authority cannot fall back to dead-PGID signaling. |
| Unit 6 execution child | [[parent-capability-child-lifecycle-contract-2026-07-15]] and [[process-death-containment-audit-2026-07-15]] | Eval children are non-detached, have no `child_process` capability, and are subordinate to the pod. The operator containment descriptor must graduate before eval cutover. The child retains its direct Node handle for ordinary stop; the external owner closes the parent-death row. |

## Why the tempting alternatives fail

### Signal the dead leader's PGID

POSIX makes the PGID unique only during the old group lifetime. A successful
`kill(-pgid, 0)` after the recorded leader dies cannot distinguish an old
survivor from a later group that reused the number. A probe followed by a
signal adds a check-to-act race. Removing the existing refusal could kill an
unrelated process group.

### Scan `ProcessHandle.descendants()` or `ps`

Java says both child and descendant streams are snapshots and that a dead
process typically has no children. The observed child was already reparented
to PID 1. PGID scans find current membership but do not bind it to the recorded
generation, and they do not make a later signal atomic with identity.

### Retain only the workload `Process` object

A retained `Process` is useful within the creating JVM, but the Babashka
operator intentionally exits while managed processes persist. It also
identifies only the workload. After that leader dies it cannot address
reparented group members, and the next operator invocation cannot reconstruct
the object.

### Use pipe EOF, parent-death signal, or cooperative Node cancellation

Pipe EOF is valuable as a drain trigger but synchronous or native code need
not observe it. Linux `PR_SET_PDEATHSIG` is not available on macOS and targets
one immediate child, not an arbitrary subtree. Abort signals and Node
`ChildProcess.killed` prove only that a request was sent. They do not replace
the external hard boundary.

### Delegate current macOS proof to launchd

`launchd.plist(5)` says that, unless `AbandonProcessGroup` is true, launchd
kills remaining processes with the job's PGID when the job dies. A benign
fixture supported that statement: direct `SIGKILL` of a unique temporary job
leader removed its ordinary child in about 30 ms.

The hard fixture falsified it as the complete Seon inverse. `launchctl bootout`
returned success in 4 ms while both a TERM-ignoring leader and child remained
alive. The service later disappeared while its TERM-ignoring child was still
alive after 6.391 seconds. Direct `launchctl kill SIGKILL` removed the job
leader but left its TERM-ignoring same-PGID child alive after 6.430 seconds;
setting `ExitTimeOut` to one second still left that child alive after 12.867
seconds. `launchctl print` output is explicitly not an API. A launchd label is
therefore neither a bounded drained acknowledgement nor adequate hard proof
for the required TERM-refusal arm.

### Require cgroup v2 for macOS development

Linux cgroup v2 is an excellent future hard backend, but macOS has no native
cgroup v2 and Docker Desktop would move the pod behind a VM boundary that
cannot currently consume the host writer's Unix-domain sockets unchanged.
Making Docker or a new TCP transport a unit-1 prerequisite would expand this
slice into a deployment refactor. The portable anchored supervisor settles the
operator contract first; Linux may later implement the same descriptor and
terminal result with a delegated cgroup.

## Preferred containment protocol

### Published descriptor

Extend the existing managed process record rather than create a registry. Its
process identity names the persistent containment owner. Add one closed nested
descriptor with at least:

```clojure
{:seon.dev.process.containment/generation #uuid "..."
 :seon.dev.process.containment/owner-pid 41201
 :seon.dev.process.containment/owner-start-instant "..."
 :seon.dev.process.containment/anchor-pid 41202
 :seon.dev.process.containment/anchor-start-instant "..."
 :seon.dev.process.containment/process-group 41202
 :seon.dev.process.containment/workload-pid 41203
 :seon.dev.process.containment/workload-start-instant "..."
 :seon.dev.process.containment/control-socket "..."
 :seon.dev.process.containment/result-path "..."}
```

The exact schema and key ownership belong beside `process-record-schema`.
Generation and identities are one atomically published value. A partial or
semantically inconsistent descriptor fails spawn before readiness.

### Launch and readiness

1. `with-startup-ownership` closes acquisition admission or waits for complete
   descriptor publication exactly as it does today.
2. The persistent owner starts outside the execution group and retains the
   anchor as its direct child.
3. The anchor creates the new session/group, remains its leader, starts the
   workload in that group, and sends a bounded handshake to the owner. The
   workload and its allowed children never call `setsid()` or `setpgid()`.
4. The owner publishes the complete descriptor; only then may `spawn-detached!`
   publish the managed record.
5. Existing watcher/writer/pod readiness remains the application probe, but
   `ready?` additionally requires a live exact containment owner, live exact
   anchor, and a nonterminal matching generation. A workload endpoint alone
   can never make a replacement ready.

### One drain

1. Pod exit, operator stop, startup unwind, branch close, or owner control EOF
   closes new child admission.
2. The operator opens the descriptor's private Unix socket and sends the exact
   generation plus drain request. A mismatched response, missing socket, or
   timeout is uncertain. PID/start remains diagnostic identity, not signaling
   authority; the operator never calls `/bin/kill` on the execution PGID.
3. The owner tells the retained anchor to close workload admission. The anchor
   ignores its own TERM handler and calls `killpg(SIGTERM)` on its current
   group while it is necessarily a live member.
4. After the bounded grace, the anchor sends one escalation frame to its
   outside owner and calls `killpg(SIGKILL)` on its own current group. The
   anchor dies in that call. There is no group probe or later group signal.
5. The owner waits for and reaps its direct anchor, validates the escalation
   frame and signal exit, records workload exit plus
   the drained terminal result atomically, closes pipes, and exits.
6. `stop!` waits for the matching terminal result and exact owner exit, clears
   state/readiness, and returns. A missing/mismatched result retains the record
   as uncertain and fails the transition.

The terminal result is process evidence, not database truth and not a second
supervisor registry. Recovery still derives interrupted run/turn/eval facts
from the database only after process absence is proven.

## Cross-platform constraints

| Constraint | macOS development | Eventual Linux |
|---|---|---|
| Owner control | Generation-matched private Unix socket; PID/start is diagnostic only | Same protocol; a Linux backend may additionally retain pidfds internally |
| Execution group | POSIX session/process group pinned by a live anchor | Same portable mechanism |
| Hard subtree backend | Anchored group is the required first backend | Prefer a delegated cgroup v2 generation when available; `cgroup.kill` plus recursive `populated 0` may replace the group backend behind the same descriptor/result |
| Reaping | Owner waits its direct anchor; launchd reaps processes reparented after group kill | Owner waits anchor; a Linux implementation may set subreaper or rely on cgroup emptiness, but neither changes durable recovery semantics |
| Missing platform capability | Fail startup with a typed unsupported-containment result | Fail startup if neither the reviewed group backend nor an admitted delegated cgroup is available; never fall back to dead-PGID signaling |

Windows is outside the stated target. The schemas should nevertheless name the
backend explicitly so a later Windows Job Object implementation can satisfy
the same contract rather than pretending POSIX groups exist.

## Ordered implementation and test slice

1. **Freeze the descriptor.** Add the closed containment generation/owner/
   anchor/workload/result shape to the existing process record and status
   projection. Reject old incomplete live records as degraded; do not silently
   reinterpret the workload PID as a containment owner.
2. **Strengthen `detach.py` in place.** Make it the persistent owner and add
   its anchor mode. Use bounded framed startup/result handshakes, direct child
   handles, no shell command reconstruction, and one exact log. Do not add a
   parallel launcher or daemon.
3. **Replace group control.** `stop!` requests drain from the exact owner and
   consumes its matching terminal result. Delete `group-alive?` as lifecycle
   authority. Retain group operations only inside the owner while its anchor
   handle is live.
4. **Integrate every existing inverse.** Ordinary down/restart, startup SIGINT,
   retained branch close/unwind, readiness failure, and crash replacement all
   use the same drain. Preserve converged-process non-ownership.
5. **Prove the real OS matrix in isolated fixtures.** Run unique project-local
   process directories and ports only. Include a synchronously busy/TERM-
   ignoring execution child before claiming the unit-6 prerequisite.
6. **Run one source-frozen default checkpoint.** After focused operator proof,
   coordinate the root-owned default restart/crash/readiness gate. ACME remains
   untouched until the default cluster passes and its owner permits the same
   artifact digest to be exercised.

## Real failure matrix

| Cut | Required process evidence | Required operator result | Forbidden result |
|---|---|---|---|
| Before owner spawn | No descriptor or process | Start fails/interrupts cleanly | Partial record |
| Owner alive, before anchor handshake | Exact owner is drained and absent | No readiness | Workload spawn outside descriptor |
| Anchor alive, before workload handshake | Exact owner and anchor absent | No readiness | Reused stale result |
| Workload ready | Matching owner, anchor, workload, generation, artifact, and endpoint | Ready | Endpoint-only readiness |
| Ordinary stop | Generation-bound owner command, TERM, bounded grace, final group KILL, anchor reaped, matching terminal result | State/readiness cleared | Return after signal request only |
| Pod exits with ordinary child active | Anchor remains, owner drains group, terminal result precedes replacement | Recovery then replacement ready | Reparented child under PID 1 |
| Pod exits with TERM-ignoring child active | Final group KILL occurs while anchor identity is live; anchor and child absent | Recovery then replacement ready | Polling dead PGID or launchd label as proof |
| Operator SIGINT during publication/readiness | Existing monitor reverses exact owner generation | Exit 130 after inverse | Unowned owner/anchor/workload |
| Retained branch SIGINT | Pod containment result precedes branch release/delete | Existing exact branch inverse continues | Destructive request under uncertain drain |
| Converged process reused | No acquisition and no signal | Existing generation remains ready | Another invocation claims it |
| Containment owner disappears without result | Descriptor retained; no PGID signal | Degraded `containment-uncertain` | Replacement readiness |
| Anchor disappears before owned final signal | Descriptor retained; no later PGID signal | Degraded `containment-uncertain` | Signal to a possibly reused group |
| Terminal result write fails | Owner/descriptor evidence retained | Stop fails nonzero and retry stays fenced | Cleared record or successful close |
| Result from another generation | Ignored and retained for diagnosis | Current transition fails closed | Stale result authorizes readiness |
| PID and PGID numbers are later reused by fixture innocents | Innocents remain alive | Stale record is degraded/clearable only after owned terminal proof | Signal to reused identities |

## Exact acceptance evidence

- A focused real-process test kills the workload leader while an attached child
  remains. The recorded containment owner and anchor remain exact, the owner
  drains the group, and both old workload identities are absent before a new
  readiness transition begins.
- A TERM-ignoring child forces the final KILL arm. The retained log records the
  matching generation, grace expiry, the anchor's self-issued group signal,
  anchor reap, and terminal result. No operator PGID probe/signal occurs.
- Killing or corrupting the containment owner and anchor separately never
  signals an innocent group and never admits replacement. Status names
  `containment-uncertain` and retains every exact identity.
- A fixture deliberately reuses stale numeric PID/PGID-shaped values with an
  innocent process. `stop!`, `ensure!`, branch close, and startup unwind do not
  signal it.
- Pre-spawn, descriptor-publication, workload-readiness, terminal-result, and
  operator-SIGINT cuts leave no unowned fixture process and preserve a
  converged generation.
- Retained native branch cleanup consumes the same terminal result before any
  release/delete request. Injected incomplete drain sends no destructive
  writer request.
- The focused process/branch/CLI gate passes, then a coordinated default
  source-frozen checkpoint proves: ready pod, child admitted, pod workload
  killed, old subtree absent, recovery transaction exactly once, replacement
  ready, normal CLJS eval and database write/read successful.
- Only after that checkpoint may the unit-6 child experiment claim its parent-
  death prerequisite. The final runtime-reliability graduation still requires
  the later default browser/restart/reset/performance proof and coordinated
  simultaneous ACME evidence.

## Issue and implementation owner

The open root-cause note is
[[dead-process-group-leader-blocks-safe-subtree-drain]]. The implementation
owner is the one `seon.dev.process` transition plus the existing `detach.py`
boundary, with branch/CLI tests as consumers. The future eval child consumes
the proven contract; it does not implement another host supervisor.
