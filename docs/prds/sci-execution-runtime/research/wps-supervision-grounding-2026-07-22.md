---
type: research
status: active
tags: [research, architecture]
---

# WP-S supervision grounding — sol read-only pass (2026-07-22)

Orchestrator-accepted. Unit cut: WP-S1a (source-checkout sci-host as a
managed child: watcher→writer→host→pod order, raw-UDS readiness, socket
ownership safety) → WP-S2 (lazy crash recovery — the respawn actor shape is
already ruled by owner decision 6: client-driven demand, operator-owned
ensure) → WP-S3 (q24 generation-aware socket sweep) → WP-S4 (destructive
closure: q18 OOME, the pending W3 fence/authored live proofs, kill/restart
zero-fact-loss). WP-S1b (packaged host artifact) waits on the W9 pushed sci
coordinate.

# WP-S source-grounding report

## Executive decision

WP-S can safely begin with an eagerly reconciled, recorded sci-host process under `bin/seon`, ordered `writer → sci host → pod`, using a configured UDS path and a direct socket-connect readiness probe.

Two contracts are not grounded enough for a complete WP-S implementation:

1. **NOT GROUNDED — sci-host respawn actor.** The pod lazily reconnects an agent session after host death, but nothing relaunches the JVM process. The accepted “client-driven lazy respawn” ruling is fully designed only for future package hosts.
2. **NOT GROUNDED — packaged sci-host artifact.** Source checkout can run `clojure -M:writer:host -m seon.host ...`; the release manifest contains no sci-host jar/member/digest.

These should be explicit WP-S boundaries, not assumptions hidden in an implementation spec.

## Dependency ledger

| Dependency/mechanism | Selected identity | Grounded source and existing use |
|---|---|---|
| Clojure / Malli | Clojure 1.12.0, Malli 0.20.0 | [deps.edn:6](/Users/sean/src/seon/deps.edn:6) |
| sci | `:local/root reference-code/sci`, checkout `8fac6e88f32d53a5fd82ebe80640881e317b84fd` | Alias composition is documented at [deps.edn:53](/Users/sean/src/seon/deps.edn:53); current SHA correction is recorded at [program-synthesis-2026-07-21.md:934](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:934) |
| Writer dependency basis | `:writer` alias; maintained Datahike checkout currently `c1c4c29382257317cd34e160df11985cb384f8a6` | Writer dependency basis at [deps.edn:17](/Users/sean/src/seon/deps.edn:17); host composes onto it at [deps.edn:53](/Users/sean/src/seon/deps.edn:53) |
| Operator process graph | `seon.dev.process` | Spec schema at [process.clj:51](/Users/sean/src/seon/script/seon/dev/process.clj:51), writer template at [process.clj:550](/Users/sean/src/seon/script/seon/dev/process.clj:550), reconciliation at [cli.clj:113](/Users/sean/src/seon/script/seon/dev/cli.clj:113) |
| Detached containment | `detach.py` generation-bound owner/anchor/workload | Launch handoff at [process.clj:957](/Users/sean/src/seon/script/seon/dev/process.clj:957); owner publication at [detach.py:300](/Users/sean/src/seon/script/seon/dev/detach.py:300) |
| UDS transport | `seon.db.transport.uds` | Raw connect at [uds.cljc:295](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:295); EOF-safe frame read at [uds.cljc:271](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:271) |
| JVM sci host | `seon.host` | Start contract at [host.clj:199](/Users/sean/src/seon/src/seon/host.clj:199); manual `-main` at [host.clj:301](/Users/sean/src/seon/src/seon/host.clj:301) |
| Pod host client | `seon.execution.host` | Host-session connection at [host.cljs:561](/Users/sean/src/seon/src/seon/execution/host.cljs:561); lazy session re-ensure at [host.cljs:668](/Users/sean/src/seon/src/seon/execution/host.cljs:668) |
| Restart reconstruction proof | Writer test | Fresh host replays a pre-restart definition at [host_registry_writer_test.clj:626](/Users/sean/src/seon/test/seon/host_registry_writer_test.clj:626) |

## 1. Current supervisor mechanism

The managed process graph is presently closed over watcher, writer, and pod:

- IDs are declared at [process.clj:26](/Users/sean/src/seon/script/seon/dev/process.clj:26).
- `all-process-ids` contains only those three at [process.clj:30](/Users/sean/src/seon/script/seon/dev/process.clj:30).
- Target ownership combinations are hard-coded at [process.clj:199](/Users/sean/src/seon/script/seon/dev/process.clj:199).
- CLI log selection is also hard-coded at [cli.clj:643](/Users/sean/src/seon/script/seon/dev/cli.clj:643).

A managed process specification must supply:

- qualified process ID;
- argv vector;
- managed environment;
- dependency IDs;
- optional external dependencies;
- readiness strategy;
- readiness timeout;
- shutdown grace;
- artifact digest;
- optional process-specific artifact and port fields.

The exact schema is [process.clj:51](/Users/sean/src/seon/script/seon/dev/process.clj:51).

The writer is the correct template:

```clojure
{:seon.dev.process/id writer-id
 :seon.dev.process/argv [...]
 :seon.dev.process/environment environment
 :seon.dev.process/dependencies []
 :seon.dev.process/readiness :seon.dev.process.readiness/writer
 :seon.dev.process/ready-timeout-ms 180000
 :seon.dev.process/shutdown-grace-ms 30000
 :seon.dev.process/artifact-digest writer-digest}
```

See [process.clj:550](/Users/sean/src/seon/script/seon/dev/process.clj:550).

Generic machinery then provides:

- topological startup from declared dependencies: [process.clj:585](/Users/sean/src/seon/script/seon/dev/process.clj:585);
- exact argv/environment/artifact convergence: [process.clj:1105](/Users/sean/src/seon/script/seon/dev/process.clj:1105);
- per-lifetime log creation and retention: [process.clj:305](/Users/sean/src/seon/script/seon/dev/process.clj:305);
- generation-bound owner/anchor/workload records: [process.clj:1055](/Users/sean/src/seon/script/seon/dev/process.clj:1055);
- readiness polling and early failure detection: [process.clj:815](/Users/sean/src/seon/script/seon/dev/process.clj:815);
- exact-generation drain and terminal evidence: [process.clj:1407](/Users/sean/src/seon/script/seon/dev/process.clj:1407);
- startup unwind in reverse acquisition order: [process.clj:2175](/Users/sean/src/seon/script/seon/dev/process.clj:2175).

### What a sci-host kind must add

At minimum:

- `host-id` in all process-ID enums and CLI selectors.
- Configured host UDS path.
- Host argv/classpath and artifact identity.
- Dependencies `[writer-id]`.
- A host-specific readiness keyword and UDS-connect probe.
- Host socket in `readiness-paths`, so only the exact stopped generation’s artifact is removed.
- An unmanaged-listener probe before stale-path removal.
- Stop classification for a requested host drain.
- Tests for graph order, startup unwind, status, logs, drift replacement, and `down`.

The current explicit stop order is hard-coded as pod, writer, watcher at [process.clj:1835](/Users/sean/src/seon/script/seon/dev/process.clj:1835); host must be inserted there rather than relying on map order.

## 2. Current sci-host launch and readiness

`seon.host/-main` accepts one EDN argv containing:

```clojure
{:seon.host/socket-path ...
 :seon.host.context/writer-socket-path ...
 :seon.host.context/database-name ...}
```

The documented source launch is:

```text
clojure -M:writer:host -m seon.host '<edn>'
```

See [host.clj:301](/Users/sean/src/seon/src/seon/host.clj:301).

`start!`:

1. Creates the writer-session pool.
2. Acquires the committed projection.
3. Builds the shared sci base and registry.
4. Applies graduation and instrumentation.
5. Deletes the configured host socket path.
6. Binds the server socket.
7. Starts the acceptor.

See [host.clj:199](/Users/sean/src/seon/src/seon/host.clj:199) and [host.clj:245](/Users/sean/src/seon/src/seon/host.clj:245).

Only after `start!` returns does `-main` print:

```text
HOST READY <socket> base-loaded=... base-failed=...
```

at [host.clj:311](/Users/sean/src/seon/src/seon/host.clj:311).

### Recommended readiness evidence

Use the configured UDS path plus a **raw connect-and-close probe**:

- Binding completes before `start!` returns: [host.clj:283](/Users/sean/src/seon/src/seon/host.clj:283).
- `uds/connect!` directly proves the listener accepts connections: [uds.cljc:295](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:295).
- Closing without a startup frame is safe: `read-frame` returns `nil` on EOF, and the host simply skips startup admission: [uds.cljc:271](/Users/sean/src/seon/src/seon/db/transport/uds.cljc:271), [host.clj:126](/Users/sean/src/seon/src/seon/host.clj:126).

A ready file is unnecessary. A dynamic socket-path file is also unnecessary if the operator selects one stable per-cluster path. A dynamic file would create another mutable coordinate and stale-file failure mode.

The READY log line is useful diagnostic evidence, but architecture explicitly rejects log lines as readiness truth in favor of direct process/socket checks: [agent-runtime.md:572](/Users/sean/src/seon/docs/seon/architecture/agent-runtime.md:572).

### Existing unsafe edge

`host/start!` unconditionally deletes the requested socket path before binding at [host.clj:248](/Users/sean/src/seon/src/seon/host.clj:248). If a foreign live listener owns that path, unlinking it makes the listener unreachable without stopping it.

WP-S must perform the same “accepting without a managed record” refusal used by writer/pod ownership checks, and then strengthen `seon.host` so it does not blindly unlink a live foreign socket.

## 3. Client adoption and lazy respawn

The agent-tier coordinate is registered as:

```clojure
:seon.execution.host/eval-socket-path
```

at [host.cljs:35](/Users/sean/src/seon/src/seon/execution/host.cljs:35).

At invocation time, the pod queries it from the invocation’s pinned database value and routes eval/authored calls to the host when present: [host.cljs:810](/Users/sean/src/seon/src/seon/execution/host.cljs:810), [host.cljs:861](/Users/sean/src/seon/src/seon/execution/host.cljs:861).

**NOT GROUNDED — production writer of the coordinate.** No checked-in production call site transacts this attribute. Existing CLJS tests inject the lookup seam, and the historical real-turn drive manually transacted the schema/fact, as recorded at [roadmap.md:369](/Users/sean/src/seon/docs/prds/sci-execution-runtime/roadmap.md:369).

When a host connection dies, the pod:

- settles the active invocation as a retired-child error;
- removes the session entry;
- leaves queued/future work able to re-enter `ensure-entry!`.

See [host.cljs:260](/Users/sean/src/seon/src/seon/execution/host.cljs:260). The next invocation opens a fresh session at the same socket path: [host.cljs:668](/Users/sean/src/seon/src/seon/execution/host.cljs:668).

That is **lazy session reconnection**, not JVM process respawn.

### Reconciliation with owner decision 6

The accepted ruling is “client-driven lazy respawn; `bin/seon` reaps recorded children”: [program-synthesis-2026-07-21.md:103](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:103).

For package hosts, the actor is assigned: the sci host spawns/respawns them, while their pid/socket/generation are recorded for operator status and reap: [w6-package-host-design-2026-07-21.md:320](/Users/sean/src/seon/docs/prds/sci-execution-runtime/research/w6-package-host-design-2026-07-21.md:320).

For the sci host itself:

- `bin/seon` is a one-shot reconciler, not a resident babysitter.
- The pod can reconnect sessions but cannot currently launch `seon.host`.
- The containment owner exits after its workload exits; it does not respawn it.

Therefore:

- An eager `bin/seon up` start is compatible with decision 6 for initial ownership and explicit reconciliation.
- Crash recovery without operator intervention is **not** implemented or assigned.

**Recommended ruling:** the pod is the sci host’s client, but any lazy launch should call an operator-owned “ensure sci host” entrypoint that reuses `seon.dev.process` locking, containment, records, readiness, and reaping. Do not recreate detached-process ownership inside CLJS. This seam does not exist today and needs a deliberate WP-S2 design.

## 4. Dependency and restart order

The hard dependency is:

```text
writer → sci host
```

Host startup immediately needs the writer socket and committed projection; failure aborts startup: [host.clj:204](/Users/sean/src/seon/src/seon/host.clj:204).

Recommended complete order:

```text
watcher → writer → sci host → pod
pod → sci host → writer → watcher   ; stop
```

Host-before-pod prevents resumed agents from racing host availability during pod boot. The exact host/pod ordering is **NOT GROUNDED** in an accepted roadmap passage; this is an inference from the boot behavior.

### What survives host restart

A host restart loses:

- active UDS sessions;
- sci context objects;
- retained live values and handles;
- active invocation threads and pools.

A new host starts with an empty context atom: [host.clj:228](/Users/sean/src/seon/src/seon/host.clj:228).

On the next agent startup frame, it:

1. forks the shared base;
2. reads the agent’s durable corpus definitions;
3. replays them;
4. reinstalls wrappers;
5. reapplies instrumentation;
6. publishes the context in the new process;
7. sends READY.

See [host.clj:66](/Users/sean/src/seon/src/seon/host.clj:66) and [context.clj:1405](/Users/sean/src/seon/src/seon/host/context.clj:1405).

The restart regression proves a pre-restart definition evaluates after fresh `host/start!`: [host_registry_writer_test.clj:626](/Users/sean/src/seon/test/seon/host_registry_writer_test.clj:626).

The interrupted form is not replayed. The durable agent is its corpus, plan, transcript, and memory; the context is a disposable cache: [design.md:38](/Users/sean/src/seon/docs/prds/sci-execution-runtime/design.md:38).

## 5. q24 containment socket sweep

Containment control sockets are created in the shared containment socket directory at [process.clj:964](/Users/sean/src/seon/script/seon/dev/process.clj:964). Normal owner cleanup unlinks them in `finally`: [detach.py:383](/Users/sean/src/seon/script/seon/dev/detach.py:383). SIGKILL of the owner bypasses that cleanup.

The sweep belongs in `seon.dev.process`, at the lifecycle/reconciliation boundary—not in `seon.host` and not in each process kind.

Safe rule:

- Run under the operator lifecycle lock before reconciliation/spawn.
- Preserve every socket referenced by a live, generation-matching managed record.
- Preserve any unrecorded socket that still accepts a control connection; report it as foreign/uncertain.
- Delete only an old socket that is unreferenced, fails a direct connection, and is outside the active launch race window.
- Never infer orphanhood from filename or mere record absence.

The launch race matters: the containment owner binds/listens before the managed record is published at [detach.py:313](/Users/sean/src/seon/script/seon/dev/detach.py:313). A directory-wide sweep without locking/age protection could unlink another operator’s just-created live control socket.

Host eval-socket cleanup is a separate readiness-artifact concern and belongs in `readiness-paths`.

## 6. Ranked risks and cheapest falsifiers

1. **Critical — no sci-host respawn actor.**  
   Falsifier: `bin/seon up`, kill the host, issue a host-tier invocation, and observe whether a new JVM PID appears without another operator command. Current source predicts no.

2. **Critical — no packaged host artifact identity.**  
   Release members include writer, pod, and execution artifacts but no sci host: [release.clj:51](/Users/sean/src/seon/script/seon/dev/release.clj:51).  
   Falsifier: derive a packaged host spec from the current release manifest. There is no host path/digest to select.

3. **High — live host socket can be unlinked.**  
   Falsifier: bind a foreign listener, call the current `host/start!` on the same path, and verify the foreign listener becomes unreachable while still alive.

4. **High — agent tier coordinate has no production publisher.**  
   Falsifier: create an ordinary agent through current production APIs and query for `:seon.execution.host/eval-socket-path`; no checked-in path is expected to add it.

5. **High — q24 sweep can race another launch.**  
   Falsifier: pause a containment owner after bind but before record publication while another operator runs the sweep; the live socket must survive.

6. **High — OOME bypasses in-process cleanup.**  
   q18 correctly requires a contained subprocess; never run the proof inside the writer test JVM. Queue ownership is recorded at [program-synthesis-2026-07-21.md:401](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:401).

7. **Medium — cold recovery latency.**  
   Prior drills measured host cold start at 8.2–11.5 seconds and context reconstruction after readiness separately: [roadmap.md:267](/Users/sean/src/seon/docs/prds/sci-execution-runtime/roadmap.md:267). This is close to the pod client’s current 10-second ready timeout at [host.cljs:31](/Users/sean/src/seon/src/seon/execution/host.cljs:31).

## Recommended implementation cut

### WP-S1a — source-checkout sci-host ownership

Add the host to the static managed graph:

- stable per-cluster socket config;
- source argv;
- writer dependency;
- raw UDS readiness;
- status/log/down/restart integration;
- safe socket ownership checks;
- `watcher → writer → host → pod` and reverse stop proof.

Gate: `bin/seon up`, status shows host ready, one host-tier invocation succeeds, `bin/seon down` leaves no process or eval socket.

### WP-S1b — packaged host artifact

Choose and publish a real host artifact/member/digest, including exact sci dependency identity. Do not claim packaged WP-S support until this exists.

This may depend on W9’s pushed sci coordinate; the present `:local/root` is explicitly non-publishable at [deps.edn:53](/Users/sean/src/seon/deps.edn:53).

### WP-S2 — lazy crash recovery

First settle the missing actor. Recommended shape: pod-triggered, operator-owned `ensure host`, reusing the existing process mechanism.

Gate:

- kill host mid-invocation;
- interrupted call records an honest retired-process error;
- next demand launches a new recorded generation without manual intervention;
- session reconnects;
- pre-kill corpus definition evaluates;
- failed form/effects are not replayed.

### WP-S3 — artifact hygiene

Add q24’s generation-aware containment control-socket sweep plus host eval-socket cleanup.

Gate: stale sockets removed; live, foreign, and record-publication-race sockets preserved.

### WP-S4 — destructive/live closure

Run only after WP-S2:

- process-contained OOME recovery for q18;
- pending W3c1 fence live proof;
- pending W3d1 authored-invocation live proof;
- host kill/restart with zero fact loss.

Those W3 live proofs are explicitly pending supervision at [program-synthesis-2026-07-21.md:1042](/Users/sean/src/seon/docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:1042).

The cheapest safe implementation boundary is **WP-S1a**. WP-S2 should stop until the sci-host respawn actor is explicitly accepted; WP-S1b should stop until the host artifact identity is selected.