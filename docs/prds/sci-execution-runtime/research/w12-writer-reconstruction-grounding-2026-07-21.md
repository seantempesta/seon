---
type: research
status: active
tags: [research, architecture, database]
---

# W1.2 live writer reconstruction — sol source-grounding report (2026-07-21 night)

Read-only sol research lane with live-probe access (probes were cancelled by
the environment; read-only fallback evidence used instead). Orchestrator-
accepted 2026-07-21 night: the W1.2a/W1.5/W1.2b sequencing and the operator
clean-or-force!/ensure! seam SUPERSEDE the "reuse W0.4 replace-member!"
shorthand in the boot-contract design. The three critical open questions gate
the W1.2a spec.

# W1.2 — live writer reconstruction source-grounding report

Read-only audit; no files, processes, configuration, or database facts were changed.

## Executive finding

W1.2 is an operator-owned process-replacement unit, but the accepted shorthand “reuse W0.4 `replace-member!`” is materially imprecise:

- `replace-member!` is private, has signature `[writer]`, and only lazily fills an empty SCI-host socket-pool slot. It neither selects a failed member nor replaces the JVM writer process. `src/seon/host/context.clj:434-440`
- Whole-writer replacement can reuse the operator’s existing `clean-or-force!` → `ensure!` lifecycle and readiness machinery. `script/seon/dev/process.clj:1816-1844,2094-2122`
- The current pod quiesce endpoint is too broad for writer-only reconstruction: it drains agents, stops the SCI host, detaches projection, and closes the pod database session; there is no matching resume-from-quiesced operation. `src/seon/client.cljs:2600-2641,2650-2716`
- Current live `config apply` is also not yet a suitable handoff. The operator resolves an envelope, but sends only the manifest path; the pod rereads Aero and recomputes operational defaults using different hardware observations. `script/seon/dev/cli.clj:325-358`; `src/seon/web/serve.cljs:497-520`; `src/seon/config.cljs:658-665,876-881`
- W1.2 can land before W1.5 only with a narrow proof over currently enforced keys—heap and selected processors. Full “all boot-critical changes take effect” completion requires W1.5.

## Dependency ledger

| Mechanism | Version / owner | What W1.2 uses |
|---|---|---|
| Operator entry and lock | `bin/seon:1-7`; `script/seon/dev/cli.clj:367-380` | Serialize resolve, diff, stop, launch, proof, apply |
| Portable config resolver | Aero 1.1.6 in `bb.edn:1-5`; `src/seon/config/resolve.cljc:126-166,862-924` | Exact boot-critical key set, effective values, dispositions |
| Launch descriptor/envelope | `src/seon/launch.cljc:46-56,132-144` | Typed operator→pod envelope |
| Writer process specification | `script/seon/dev/process.clj:550-574` | New heap, envelope file, socket and database argv |
| Operator process supervisor | `script/seon/dev/process.clj:1433-1484,1816-1844,2094-2122` | Stop exact generation, launch replacement, await readiness |
| Writer readiness | `script/seon/dev/process.clj:648-656,782-794` | Require both REPL port file and connectable request socket |
| Writer envelope consumer | `src/seon/db/server.clj:382-430` | Validate envelope and pass selected processors into writer boot |
| Writer lifecycle | `src/seon/db/writer.clj:4182-4263,4265-4307` | Construct executors/transport; orderly close and database release |
| Pod database session | `src/seon/db.cljs:495-592,641-662` | Clear dead session and reconnect on demand |
| Pod listener recovery | `src/seon/db.cljs:533-564` | Re-register interests and emit current-value resynchronization |
| Pod transaction retry | `src/seon/db.cljs:815-892` | Retry ambiguous writes with the same request ID |
| Durable transaction receipts | Datahike local fork selected at `deps.edn:23-26`; `src/seon/db/writer.clj:1287-1454` | Recover a commit after acknowledgement loss or writer replacement |
| Committed-report feed | `src/seon/db/writer.clj:2304-2315,2737-2802` | Understand which feed state is process-local and must be rebuilt |
| SCI host writer pool | SCI local fork at `deps.edn:58-61`; `src/seon/host/context.clj:189-232` | Surviving host process reconnects socket members after writer return |
| SCI host process/session | `src/seon/host.clj:192-269`; `src/seon/host/session.clj:261-280` | Determine effect of writer death on live eval sessions |

Pinned supporting dependencies include Clojure 1.12.0, the local Datahike fork, Konserve SHA `b5c99bc…`, and Proximum SHA `9846d3e…`. `deps.edn:6-7,20-42`

## 1. W0.4 pool

### Location and actual lifecycle API

The pool is not pod-side. It lives inside the separate JVM SCI host:

- `seon.host/start!` constructs one `context/writer-session`. `src/seon/host.clj:192-202`
- The resulting writer is shared through the host map and every agent host session. `src/seon/host.clj:235-244`; `src/seon/host/session.clj:261-280`
- Pool defaults currently derive size as `max(1, availableProcessors - 1)`. `src/seon/host/context.clj:189-199`

Actual member functions:

| Function | Signature | Meaning |
|---|---|---|
| `open-member!` | `[writer]` | Connect UDS, acquire database/head, return a member. `src/seon/host/context.clj:319-364` |
| `finish-opening!` | `[writer result]` | Finish an outstanding lazy open. `src/seon/host/context.clj:366-384` |
| `acquire-member!` | `[writer wait-timeout-ms]` | Lease available member, open below capacity, or return bounded error. `src/seon/host/context.clj:386-432` |
| `release-member!` | `[writer member]` | Return a still-current member to availability. `src/seon/host/context.clj:277-293` |
| `evict-member!` | `[writer member]` | Remove and close one exact member. `src/seon/host/context.clj:295-311` |
| `replace-member!` | `[writer]`, private | Acquire/open one member and immediately release it. `src/seon/host/context.clj:434-440` |
| `close-session!` | `[writer]`, public | Close all members and the pool executor. `src/seon/host/context.clj:442-457` |

`invoke-member!` performs the actual eviction on timeout or failure. `replace-member!` runs afterward only to populate the empty slot. `src/seon/host/context.clj:463-481,565-603`

Tests prove that a deadlined member is closed, receives a new member ID, and later calls succeed. `test/seon/host_pool_writer_test.clj:122-163`

### What W0.4 provides

- One in-flight call per leased member and concurrency across members. `src/seon/host/context.clj:208-214,403-412`
- Eviction of failed/deadlined channels. `src/seon/host/context.clj:463-481`
- One bounded retry on a newly acquired member. `src/seon/host/context.clj:565-608`
- Same-request-ID recovery for ambiguous writes. `src/seon/host/context.clj:522-563`
- Lazy reconnection to the same socket path after a replacement writer appears. `src/seon/host/context.clj:319-364,386-440`

### What W1.2 additionally needs

W0.4 provides none of the following:

- process termination or launch;
- heap or argv changes;
- new envelope publication;
- operator generation ownership;
- writer readiness;
- pod admission coordination;
- proof that the newly reachable socket belongs to the intended envelope generation.

Those mechanisms belong to the operator process lifecycle, not `replace-member!`. The accurate design statement is:

> W1.2 uses operator-supervised writer replacement; W0.4 lets a surviving SCI host discard dead socket members and converge on the replacement afterward.

## 2. Writer process lifecycle today

### Launch and supervision

`bin/seon` enters Babashka `seon.dev.cli`. `bin/seon:1-7`; `script/seon/dev/cli.clj:1121-1145`

The operator derives:

- writer process directory;
- request UDS path;
- pod HTTP port file;
- writer REPL port file.

`script/seon/dev/config.clj:470-498`

The writer spec contains G1GC, resolved `-Xmx`, the writer jar, file database path, request socket, envelope path, and dynamic REPL-port publication. `script/seon/dev/process.clj:550-574`

`process/ensure!` compares the complete process specification, starts an absent generation, and waits for readiness. It refuses to overwrite an existing managed process without prior stop evidence. `script/seon/dev/process.clj:2062-2077,2094-2122`

`clean-or-force!` stops selected processes in dependency-safe order and classifies their terminal evidence. `script/seon/dev/process.clj:1789-1844`

### Dynamic files

Default locations are:

- request socket: `tmp/seon-cluster-default-req.sock`;
- writer REPL port: `tmp/seon-writer-repl-port-<cluster>`;
- process record: under the configured operator process directory.

`script/seon/dev/config.clj:474-498`

The writer publishes the actual loopback io-prepl port and removes the file during ordered stop. `src/seon/db/server.clj:361-380,432-445`

Readiness requires both the port file and a connectable request socket. `script/seon/dev/process.clj:648-656`

### Pod behavior when the writer disappears

The UDS close callback removes the live session, cached database values, database name, and capabilities, but retains the selection and listener-handler registry. `src/seon/db.cljs:495-514`

For ordinary reads, the next demand makes one reopen attempt and returns an error value if it fails. There is no background read reconnect loop or read-side backoff. `src/seon/db.cljs:641-662`

A successful reconnect:

1. negotiates capabilities;
2. re-ensures and reacquires the database;
3. re-registers every retained listener;
4. sends each handler a resynchronization event carrying the current database value.

`src/seon/db.cljs:521-573`

Transactions are stronger: they retain the immutable request and same request ID, reconnect, and retry recoverable/ambiguous delivery with exponential delay from 1 ms to 250 ms. `src/seon/db.cljs:815-872`

### Retained versus lost

Retained:

- File-backed committed database facts. `script/seon/dev/process.clj:558-561`
- Transaction receipt metadata—request ID and request hash—stored in the committed transaction. `src/seon/db/writer.clj:1429-1445`
- Same-request-ID recovery of committed transaction reports after restart. `src/seon/db/writer.clj:1289-1385,1467-1490`
- Pod-side listener definitions and stable database selection. `src/seon/db.cljs:504-514`
- SCI contexts and pod→SCI-host sessions if only the writer process is replaced, because they reside in another JVM. `src/seon/host.clj:192-269`

Lost and reconstructed:

- Writer registry connections, active requests, query jobs, interests, deadline executor, dispatcher, and transport server; all are created during `writer/start!`. `src/seon/db/writer.clj:4182-4248`
- Writer-side committed-report sources and interest indexes. `src/seon/db/writer.clj:2304-2315,2451-2487`
- Writer transport connections; closing them cancels owned work, removes interests, and releases acquisitions. `src/seon/db/writer.clj:4008-4073`
- io-prepl session state. MCP transparently reopens only its default session when the port changes; named sessions explicitly report loss. `script/seon/dev/mcp.clj:818-836`

The feed is reconstructed at current truth, not replayed transaction-by-transaction: pod reconnection re-registers listeners and synthesizes resynchronization from the newly acquired database value. `src/seon/db.cljs:533-564`

**NOT GROUNDED:** a blanket promise that every uncommitted mutation fails before shutdown. Graceful connection close cancels and awaits owned work, but an abrupt containment escalation can leave commit-versus-ack timing ambiguous. The grounded recovery contract is that retrying the same request ID against the replacement writer discovers an already committed receipt. `src/seon/db/writer.clj:1373-1454,1467-1490,4008-4073`

**NOT GROUNDED:** a current “replica session” object. The live pod owns one multiplexed transport session and caches ordinary immutable database values; it does not maintain the former local replica mechanism. `src/seon/db.cljs:1-6,179-181`

## 3. Current `config apply` path

### Trace

1. `bin/seon` invokes `seon.dev.cli`. `bin/seon:1-7`
2. CLI dispatches `"config"` to `config!`. `script/seon/dev/cli.clj:1128-1145`
3. `config!` calls `select-config` before acquiring the stack lock. `script/seon/dev/cli.clj:367-379`
4. `select-manifest` reads Aero, observes hardware, computes a generation and complete envelope, and atomically overwrites `resolved-manifest.edn` and `launch-envelope.edn`. `script/seon/dev/config.clj:147-205`
5. Under the lock, `apply-live-config!` verifies a ready pod and POSTs only `{:seon.config/path path}`. `script/seon/dev/cli.clj:325-358`
6. The web boundary rereads that path through Aero inside the pod. `src/seon/web/serve.cljs:497-520`; `src/seon/config.cljs:704-735`
7. `seon.client/apply-config!` resolves the singleton and reconciles routes, skills, and singleton through one `seon.runtime.state/reconcile!`. `src/seon/client.cljs:1938-1984`

### Boot-critical detection

The exact grounded boot-critical vocabulary is `config.resolve/operational-keys`, covering heap, selected processors, executor families, frame/transport caps, and codec settings. `src/seon/config/resolve.cljc:126-161`

The resolver also exposes `enforced-keys`, currently only:

- `:seon.config.database.writer/jvm-heap-mb`;
- `:seon.config.database.executor/selected-processors`.

`src/seon/config/resolve.cljc:163-166`

Therefore detection should be an equality diff over `operational-keys`, excluding generation and hardware metadata. `envelope-divergences` already demonstrates the correct per-key comparison shape. `src/seon/config/resolve.cljc:1072-1083`

### Current gaps in the apply seam

1. **Resolution occurs before the lock.** The accepted sequence says resolve/diff under the lock, but current `config!` constructs and publishes the new envelope first. `script/seon/dev/cli.clj:373-379`

2. **The old envelope file is overwritten in place before any diff.** The running writer loaded the prior value at boot, but `select-manifest` rewrites the same path. `script/seon/dev/config.clj:177-185`; `src/seon/db/server.clj:388-405`

3. **The operator’s resolved value is discarded at the HTTP boundary.** Only the path is sent; the pod rereads Aero. `script/seon/dev/cli.clj:339-347`; `src/seon/web/serve.cljs:508-520`

4. **Operator and pod hardware observations differ.** The operator observes real FD soft limit, while the pod hard-codes `1024`. `script/seon/dev/config.clj:132-145`; `src/seon/config.cljs:658-665`

   With no explicit connection cap, the resolver formula can therefore produce different defaults. `src/seon/config/resolve.cljc:878-881`

5. **Live apply does not run launch equality proof.** `prove-launch-configuration!` is called during runtime startup after configuration acquisition, not by `apply-config!`. `src/seon/client.cljs:2125-2138,2249-2252`

6. **The config endpoint itself is admission-gated.** Closing ordinary admission before the final POST would also reject the current config route. `src/seon/web/router.cljs:273-284`

7. **The existing operator quiesce endpoint is not a narrow pause.** It stops the SCI host, detaches runtime projection, and closes the pod’s database session. `src/seon/client.cljs:2600-2641`

8. **There is no matching runtime resume operation.** The lifecycle exposes start, quiesce, and stop, but no resume-from-quiesced path. `src/seon/client.cljs:2396-2412,2650-2716,2718-2765`

### Honest triggering seam

- **Operator-side orchestration:** required. Only the operator owns immutable JVM argv, containment generations, stop evidence, relaunch, and readiness. `script/seon/dev/process.clj:550-574,1433-1484,2094-2122`
- **Pod-side participation:** required for a narrow pause/drain and post-launch session/listener/equality proof. The pod cannot replace the JVM process itself.
- **Writer-side:** consumer and ordered shutdown participant only. It validates the envelope and stops its own runtime; it should not supervise its successor. `src/seon/db/server.clj:382-430,432-445`

## 4. W1.1 launch envelope

The envelope schema is a closed map containing:

- generation;
- hardware observations;
- per-key dispositions;
- every `operational-keys` value.

`src/seon/launch.cljc:46-56`

The operator writes it, embeds it in the launch descriptor, and passes its file path to the writer. `script/seon/dev/config.clj:177-200`; `script/seon/dev/process.clj:550-567`

The writer reads and validates it, then currently consumes only selected processors directly. `src/seon/db/server.clj:388-407`

Heap is consumed earlier by the operator when constructing `-Xmx`. `script/seon/dev/config.clj:48-55`; `script/seon/dev/process.clj:554-557`

The writer passes selected processors into `executor/capacity`. `src/seon/db/writer.clj:4182-4201`

It does not yet pass connection or other transport caps into `uds/start-request-server!`. `src/seon/db/writer.clj:4239-4245`

Frame size remains a private protocol-derived constant on both peers. `src/seon/db/transport/uds.cljc:166,221-256`; `src/seon/db/transport/uds.cljs:19`

Thus current dispositions are accurate:

- enforced: heap, selected processors;
- carried: connection cap, frame bytes, executor-family values, codec workers/queue, and other UDS caps.

`src/seon/config/resolve.cljc:163-166,1057-1070`

One additional resolver issue: `resolve-operational-values` always takes selected processors from observed hardware and does not consult a manifest override. `src/seon/config/resolve.cljc:865-870`  
Therefore “config apply changes selected processors” is currently **NOT GROUNDED** even though the key is present and enforced at writer boot.

## 5. Live probes

### Requested MCP probes

Attempted pod query:

```clojure
(select-keys
  (seon.db/decode-edn-values
    (seon.db/entity [:seon.config/id seon.config/cluster-config-id]))
  seon.config.resolve/operational-keys)
```

Result:

```text
user cancelled MCP tool call
```

Attempted writer query:

```clojure
{:runtime/max-memory-bytes
 (.maxMemory (Runtime/getRuntime))
 :runtime/available-processors
 (.availableProcessors (Runtime/getRuntime))}
```

Result:

```text
user cancelled MCP tool call
```

The attempts performed no writes.

### Why complete equality could not be observed

During the audit the cluster became degraded after an unrelated Shadow build failure; the pod recorded the fault and exited. `logs/operator/pod/8efdcef6-9924-4214-b36e-74f1c070bfdc.log:20-21`

Repository sandboxing also refused a direct loopback io-prepl fallback with `java.net.SocketException: Operation not permitted`. No restart was performed, per the read-only constraint.

Therefore:

- exact committed config singleton values: **NOT GROUNDED live**;
- `Runtime.maxMemory()`: **NOT GROUNDED live**;
- `Runtime.availableProcessors()`: **NOT GROUNDED live**.

### Read-only fallback evidence

The last operator-published envelope records:

```edn
{:seon.config.database.writer/jvm-heap-mb 4096
 :seon.config.database.executor/selected-processors 18
 :seon.config.database.transport/maximum-connections 272
 :seon.launch.envelope/hardware-observations
 {:seon.hardware/cores 18
  :seon.hardware/system-memory-bytes 137438953472
  :seon.hardware/fd-soft-limit 10240}}
```

`tmp/seon-operator/launch-envelope.edn:1`

The retained process record shows the running workload was launched with `-Xmx4096m` and that exact envelope path. `tmp/seon-operator/processes/writer.edn:1`

The writer log shows it booted, published the dynamic REPL port and request socket, logged heap/processors as enforced, and reached ready. `logs/operator/writer/08783ec3-4504-4510-8081-9b6d864bcb87.log:2-6`

This proves argv/envelope agreement for `4096 MiB` and the writer’s declared disposition, but it does not substitute for the requested `Runtime` calls or a database-fact query.

A further proof nuance: `Runtime.availableProcessors()` is a hardware observation, not proof that the writer uses the configured processor count. Actual enforcement is the explicit envelope value passed to `executor/capacity`. `src/seon/db/server.clj:398-405`; `src/seon/db/writer.clj:4197-4201`

## 6. Risks and sequencing

### Open SCI eval sessions

The pod talks to the SCI host over its own UDS stream. `src/seon/execution/host.cljs:562-639`

The SCI host independently owns the writer pool. `src/seon/host.clj:192-244`

Therefore writer-only replacement does not inherently destroy SCI contexts or pod→host sessions. However, every host session shares the same writer pool. `src/seon/host/session.clj:261-280`

If replacement begins during an active DB call:

- the leased member fails or deadlines and is evicted; `src/seon/host/context.clj:463-481`
- a read receives only the bounded replacement attempt and can return `:connection-failed` before the new writer is ready; `src/seon/host/context.clj:565-608`
- a write can recover only while its overall recovery deadline remains; `src/seon/host/context.clj:522-563`
- there is no transparent replay of the entire interrupted SCI form.

The existing full pod quiesce does safely drain durable agent work and then stop the SCI host before closing the database session. `src/seon/client.cljs:2512-2575,2600-2633`  
But because that has no resume path, W1.2 needs either a narrower replacement pause or an explicitly accepted pod reconstruction as well.

### W1.5 ordering

Recommended boundary:

1. **W1.2a — reconstruction lifecycle now**
   - diff full `operational-keys`;
   - reject or decline reconstruction for changed keys still marked `:carried`;
   - prove heap/process reconstruction using currently enforced keys;
   - add narrow pod pause/drain and post-launch resume/equality proof;
   - use operator `clean-or-force!` and `ensure!`.

2. **W1.5 — enforcement surfaces**
   - pass connection and existing UDS options;
   - inject frame ceiling on both peers;
   - inject executor-family capacity;
   - inject codec worker/queue settings;
   - flip dispositions to `:enforced`.

3. **W1.2b graduation**
   - allow every boot-critical key to trigger replacement;
   - live-drive connection-cap and frame/executor changes;
   - prove listener resynchronization and zero agent-loop crashes.

Reason: W1.2 does not need W1.5 to establish whole-process replacement, but it cannot honestly satisfy the accepted “connection cap/frame/executor changes take effect” contract until those constructor surfaces exist. `src/seon/config/resolve.cljc:163-166`; `src/seon/db/writer.clj:4239-4245`

## Recommended implementation seam

The work order should specify this boundary:

1. Acquire the existing stack lock before resolving or publishing the candidate envelope. `script/seon/dev/cli.clj:375-379`
2. Preserve the current launched envelope independently of the mutable candidate file—prefer immutable generation-named envelope files or equivalent generation-bound process evidence.
3. Resolve once in the operator and carry the resolved manifest, singleton/envelope, and generation into the pod; do not send only a path for the pod to resolve again.
4. Ask the pod to enter a new narrow writer-replacement phase:
   - close executable admission;
   - drain already-admitted agent work;
   - retain enough runtime ownership to resume without a pod restart.
5. Stop only `writer-id` through `clean-or-force!`. `script/seon/dev/process.clj:1816-1844`
6. Start the new writer through `ensure!` using the new spec and wait for existing readiness. `script/seon/dev/process.clj:2094-2122`
7. Have the pod reopen its database session, restore listeners, and receive resynchronization. `src/seon/db.cljs:495-592`
8. Prove new envelope equals committed facts and only claimed-`:enforced` values.
9. Reopen admission.
10. Publish the applied manifest only after the complete replacement/apply proof succeeds.

Do not expose or call private `replace-member!`; it remains a post-relaunch socket-pool convergence helper.

## Ranked open questions

1. **Critical — narrow pause/resume contract.** Existing quiesce destroys more runtime ownership than W1.2 can currently restore without restarting the pod. `src/seon/client.cljs:2600-2641,2650-2716`

2. **Critical — single-resolution handoff.** Current apply sends a path and lets the pod recompute with a different FD observation. The work order must define the exact typed resolved-value payload. `script/seon/dev/cli.clj:339-347`; `src/seon/config.cljs:658-665,876-881`

3. **Critical — immutable previous envelope.** `select-manifest` overwrites the current envelope before the apply lock and before diff. `script/seon/dev/config.clj:177-185`; `script/seon/dev/cli.clj:373-379`

4. **High — admitted config endpoint.** The current config route is closed by the same admission gate reconstruction intends to close. `src/seon/web/router.cljs:273-284`

5. **High — selected-processors override.** The resolver ignores a manifest value for this nominally configurable enforced key. `src/seon/config/resolve.cljc:865-890`

6. **High — in-flight mutation termination evidence.** Same-request recovery is grounded; “every uncommitted mutation failed cleanly before old process exit” is not. `src/seon/db/writer.clj:1373-1454,1467-1490`

7. **Medium — SCI-host reconnect window.** A general connection failure gets one immediate retry; recovery polling backoff applies only to ambiguous write conflicts. `src/seon/host/context.clj:510-608`

8. **Medium — listener restore failure policy.** Failure to restore one listener aborts the newly opened pod session; readiness must include successful interest restoration, not merely a connectable socket. `src/seon/db.cljs:533-592`

9. **Medium — generation fact chain.** The envelope and containment each have generations, but a durable database fact chain tying “committed config generation” to “running writer generation” is **NOT GROUNDED** in current source.

The data-oriented/Datahike grounding materially shaped the boundary above: durable committed facts and request receipts are treated separately from process-local connections, interests, pools, and eval sessions; W1.2 should reconstruct the latter from the former rather than persist another lifecycle registry.