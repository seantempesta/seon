---
type: research
status: completed
tags: [research, database, flow, pod]
---

# Quiesced restart, restore, and undo audit — 2026-07-14

## Decision

Clean restart and restore must be two transitions of the existing external
supervisor, not new runtime modes. The pod owns agent-work admission and the
turn boundary. The JVM writer owns request admission and accepted-write drain.
The Babashka operator owns process order, the lifecycle lock, and restore intent
that must survive replacement of the selected database branch.

A planned restart closes every current open run at its next turn boundary with
one honest `:quiesced` run close, returns the final complete database coordinate,
then drains the pod's remote writer and the JVM Datahike writer. A process exit
before that proof is complete is unexpected; cold boot must continue to invoke
the existing one-transaction crash recovery. There is no clean-restart flag that
can suppress recovery.

Restore and undo use maintained Datahike branches and guarded
`force-branch!`. The normal writer and pod must be gone before the main head is
forced. A short-lived admin invocation of the existing writer artifact performs
the exclusive storage operation from the confirmed external intent, then exits.
Undo is the identical restore transition with the retained undo branch as its
target. The physical-copy `fork-database!` path is deleted when native branch
attachment lands; it is not retained as a fallback.

## Scope and dependency ledger

This audit is read-only with respect to production source. It defines the
implementation boundary for roadmap Slices 5–7 after native branch-qualified
registry and attachment work. ACME verification is downstream and out of scope.

| Dependency or mechanism | Selected identity | Source read | Constraint |
|---|---|---|---|
| Datahike | `org.replikativ/datahike` SHA `6f90b339768b1a02066dce3b6fcc93a200758fcc` | `reference-code/datahike/src/datahike/{connector,writer,versioning}.cljc` | Connection release closes admission and awaits every accepted primary/secondary write. `branch!`, `delete-branch!`, `commit-as-db`, `branch-as-db`, and guarded `force-branch!` are the native lifecycle primitives. `force-branch!` still requires exclusive write access. |
| Konserve | `org.replikativ/konserve` SHA `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve/src/konserve/{protocols,core}.cljc` | A single-key update is atomic and optional multi-key calls are all-or-nothing. There is no CAS spanning a separate Datahike writer operation, so expected-head checking cannot replace quiescence. |
| Seon operator | `seon.dev.cli`, `seon.dev.process`, `seon.dev.state` | `script/seon/dev/*.clj`, `bin/seon` | One lifecycle lock and exact process identities exist. Current stop is signal-based only. Atomic EDN replacement exists, but durable intent also needs file and parent-directory synchronization. |
| Agent lifecycle | `seon.agent.loop`, `seon.agent.run`, `seon.agent.turn` | `src/seon/agent/{loop,run,turn}.cljs` | The loop re-reads database state only between complete turns. Turn open, turn close, and owned run close already have exact transaction owners and CAS fences. |
| Unexpected recovery | `seon.runtime.recovery`, `seon.client/start-runtime!` | `src/seon/runtime/recovery.cljs`, `src/seon/client.cljs`, `test/seon/runtime/recovery_test.cljs` | Cold boot currently repairs all orphaned ownership before hosts resume. The repair changes only pointers, runs, turns, and one recovery anchor; it does not create a message or eval. |
| Wire lifecycle | `seon.db.protocol`, `seon.db.writer`, `seon.db.transport.uds`, `seon.db.replica` | `src/seon/db/{protocol,writer,replica}.clj*`, `src/seon/db/transport/uds.clj` | The typed protocol has no maintenance, native branch, or restore operation. The UDS server closes sockets but does not join handler threads. The pod remote writer already rejects new work and awaits admitted RPCs. |
| Canonical coordinate | `seon.db.coordinate` | current database-lifecycle roadmap and implemented request/feed/replica paths | Every boundary proof below returns `{database-id, branch, commit-id, t}`. A numeric `t` alone is never a drain or restore fence. |

## Observed current behavior

### `restart` is not a semantic drain

`seon.dev.cli/restart!` holds the existing `:stack` file lock, calls
`stop-development!`, then reconciles (`script/seon/dev/cli.clj:160-169`).
`stop-development!` only calls `process/stop!` in reverse process order
(`:19-22`). `process/drain-group!` sends `SIGTERM`, waits only 2.5 seconds,
sends `SIGKILL`, then waits five seconds (`script/seon/dev/process.clj:456-472`).
It cannot state whether an accepted turn or database request committed.

The pod has handlers for unhandled rejection and uncaught exception, but no
`SIGTERM`/`SIGINT` lifecycle handler (`src/seon/client.cljs:2469-2482`). On the
next cold boot, `start-runtime!` unconditionally calls
`seon.runtime.recovery/recover!` before rebuilding hosts
(`src/seon/client.cljs:2278-2320`). A normal operator restart during an open run
therefore looks exactly like an unexpected crash today.

### The honest turn boundary already exists

`seon.agent.turn/open-turn!` commits a `:running` turn before it invokes the
body and leads with the current-run CAS when a run is present
(`src/seon/agent/turn.cljs:275-339`). `close-turn!` writes `:done` only after the
whole body returns, or best-effort `:error` when it throws (`:341-387`).

`seon.agent.loop/run-loop!` freezes one database value, derives one event, and
on `:turn-ok` performs the beat and awaits the complete `turn/run-turn!` before
recurring (`src/seon/agent/loop.cljs:218-322`). That recurrence is the clean
quiescence boundary. Stopping inside `turn/run-turn!` would split an accepted
turn and would contradict the current batch contract.

`seon.agent.run/close-run!` already owns an atomic current-pointer CAS, run
close, and pointer retraction (`src/seon/agent/run.cljs:344-362,450-493`). Its
closed-reason schema has no planned-restart value (`:70-78`). Reusing
`:completed`, `:waited`, `:superseded`, or `:crashed` would make false task or
failure history. Add `:quiesced` and exclude it from outcome notifications.

Wake listeners and the one ticker have explicit inverses
(`src/seon/agent/loop.cljs:621-667,684-741`), but there is no process-wide
work-admission state. Wake work is Promise-based and not held in one joinable
registry. The durable run/turn facts and their committed coordinates, not a new
Promise inventory, must prove drain.

### The writer has the lower drain but Seon hides failures

The pod remote writer has the right local contract: it atomically closes
admission and awaits every admitted RPC (`src/seon/db/replica.cljs:432-459`),
with focused proof in `test/seon/db/replica_test.cljs:283-326`.

On the JVM, maintained Datahike release closes the transaction queue, joins the
processing loop, commit loop, and accepted out-of-band operations, then closes
secondary writers and the store
(`reference-code/datahike/src/datahike/writer.cljc:40-73` and
`connector.cljc:438-510`). That is the storage drain authority.

Two Seon boundaries currently weaken it:

- the UDS request server creates one thread per connection and
  `close-request-server!` closes sockets without joining those threads
  (`src/seon/db/transport/uds.clj:138-211`); and
- `seon.db.registry/release-database!` catches and discards any Datahike release
  failure, then removes the registry entry anyway
  (`src/seon/db/registry.clj:203-217`).

The latter is a hard restore blocker: exclusive access has not been proved when
release fails. Release must return a typed failure, retain enough registry
identity to diagnose/retry, and prevent promotion. Writer shutdown must surface
that failure instead of returning `::stopped? true` unconditionally
(`src/seon/db/writer.clj:971-981`).

### Native promotion exists; Seon lifecycle wiring does not

The selected Datahike `branch!` creates a same-store branch from a branch or
commit and reconstructs secondary indexes from that same selected root
(`reference-code/datahike/src/datahike/versioning.cljc:153-197`).
`delete-branch!` refuses `:db` and any branch with an active connection
(`:199-227`).

`force-branch!` validates an expected current commit before work, writes
immutable nodes first, rechecks inside the final branch-head update, and reads
the new head back (`:229-335`). Its source explicitly says the caller must hold
exclusive write access because the expected-head check cannot fence an
independent Konserve operation (`:237-242,306-312`). Konserve's atomic
single-key update contract supports that conclusion
(`reference-code/konserve/src/konserve/protocols.cljc:4-36` and
`core.cljc:278-317`).

The current protocol's closed operation set is only ping, ensure, transact,
replay, and KNN (`src/seon/db/protocol.cljc:20-53`), interpreted through one
writer switch (`src/seon/db/writer.clj:883-932`). The registry is keyed only by
logical database name (`src/seon/db/registry.clj:129-160`) and still implements
forensics by physical `datahike.api/fork-database` copy with a new database
identity (`:273-362`). These are the exact in-place cutover owners.

## Clean planned restart state machine

The state is derived from one process-local pod admission value plus canonical
database facts. Do not store a restart entity, phase row, acknowledgement flag,
or list of pending Promises.

| State | Owner and evidence | Allowed transition |
|---|---|---|
| `:open` | Pod runtime admission accepts agent work; ticker/wake/HTTP are armed | The operator's loopback-only lifecycle request atomically moves admission to `:quiescing`. |
| `:quiescing` | No new run, turn, schedule, or agent-visible write may enter. Existing accepted turns continue. | Uninstall ticker and all wake listeners first; close new agent/work HTTP acceptance; each loop observes quiescence only after its current `turn/run-turn!` returns. |
| `:turns-drained` | Query shows no `:running` turn owned by a nonterminated current run. Each current open run has been closed through `run/close-run!` as `:quiesced`; its pointer is absent. The response carries the final full coordinate and affected run/turn ids. | Stop feeds/listeners/web acceptance, then call the existing pod remote-writer shutdown. |
| `:pod-writes-drained` | Remote-writer shutdown resolved, proving every pod-admitted RPC committed or failed. The final local replica head equals the writer response coordinate. | Return the structured quiesce result and let the operator terminate the now-idle pod process. |
| `:writer-drained` | JVM request admission is closed, every accepted handler has returned, every registered Datahike connection release succeeded, and the writer process exited. | Restart processes for ordinary restart, or enter exclusive native lifecycle administration for restore. |
| `:reopened` | Boot reconstruction succeeds, no orphaned run pointer exists, writer and replica report one equal head, and autonomous admission is rearmed last. | Normal operation. |

The loop change belongs in `next-event`/`run-loop!`, not in the middle of eval.
At the recurrence boundary, `:quiesce` closes the still-owned run as
`:quiesced`. Paused open runs have no active turn and can close immediately.
A CAS loss means another owner already settled the run; the drain query, not a
local callback, decides completion.

The one process-local admission value should extend `seon.client/!state` or the
single fail-closed runtime-admission owner introduced by the schema publication
slice. Every autonomous entry point consults that one value. The existing ticker
and per-agent trigger atoms remain resource handles, not competing admission
authorities.

The operator should request quiescence through one private loopback route on the
existing pod web server. Do not add another daemon or socket. The route returns
only after the database-derived drain condition and remote-writer drain hold.
`SIGTERM` calls the same pod function as a best effort, but an absent/failed
reply never counts as clean proof. The current 2.5-second signal budget remains
the crash fallback, not the planned-drain deadline.

On successful restart, `start-runtime!` may still call `recover!`; it simply
finds no open ownership and writes nothing. This is stronger than persisting a
"clean" bit and skipping recovery. If quiescence times out or the pod dies, the
operator records no clean result and cold boot performs the existing unexpected
repair.

## Unexpected exit state machine and no-fabrication law

The existing recovery transaction remains the sole unexpected-exit owner:

1. Freeze current open-run/turn targets before any host is installed.
2. CAS-assert each current pointer, retract it, close an open run `:crashed`,
   mark only a still-`:running` turn `:interrupted`, and add one
   `:unexpected-exit` anchor in one transaction.
3. Rebuild declarations and hosts from canonical facts only; do not open a
   replacement run.

`test/seon/runtime/recovery_test.cljs:98-238` already proves one root/boot
transaction, terminated-agent preservation, derived idle state, no message
creation, and second-pass idempotence. Extend it to count eval rows as well.

The no-fabrication invariant is:

> An eval result exists only if that form actually returned and its eval
> transaction committed. Restart reconstructs declarations; it never reruns
> scratch/effectful source or invents a result for an attempted or unattempted
> form.

`eval-batch!` processes forms sequentially and awaits each per-form record. Its
single run fence is intentionally at batch admission, not between forms. A
planned restart therefore drains the whole accepted turn/batch. An unexpected
kill may leave results through form N and no result for N+1; turn recovery marks
the bracket interrupted but does not amend those eval facts. A lifecycle or
restore completion fact is operational history, never an eval result.

## Restore and undo state machine

### Immutable external intent

After all preflights and exact human confirmation, write one canonical EDN
intent under the selected cluster directory, outside `db/` and blob roots, for
example `data/clusters/<cluster>/lifecycle/restore-<R>.edn`. The existing
`seon.dev.state/write-edn!` is the right owner for atomic replacement
(`script/seon/dev/state.clj:25-39`), but it must fsync the written file and its
parent directory. Intent deletion must also fsync the directory. A cluster reset
must refuse while confirmed intent exists unless the operator explicitly
cancels or recovers it.

The intent contains immutable values, not a cursor or mutable phase:

- transition id `R`, logical database name, stable database id, and backend
  location;
- exact source-main coordinate `H` and selected target coordinate `T`;
- reserved `:seon.restore.undo/<R>` and `:seon.restore.target/<R>` names;
- frozen requested overlay set plus canonical desired maps/digests;
- required blob hashes and the planned base/overlay materialization;
- confirming user/process identity and confirmation digest; and
- selected artifact/protocol version needed to interpret the operation.

Do not store `phase`, `status`, `step`, `current`, or retry count. Current main
head, branch roster/heads, target ancestry, completion-fact presence, and intent
presence derive the state.

### Derived transition

| Durable observation | Meaning | Only safe action |
|---|---|---|
| No intent | No confirmed restore | Plan only; no lifecycle mutation. |
| Intent; main=`H`; neither reserved branch | Confirmed, unprepared | Quiesce or prove the cluster is already fully down; create and verify undo from exact `H`. |
| Main=`H`; undo exists; target absent | Preparation interrupted | Create target from exact `T`; verify primary root and every enabled secondary root. |
| Main=`H`; undo and target exist | Prepared | Recheck database id, complete `H`, complete `T`, schema/program compatibility, ancestry, frozen overlays, and every required blob. Then stop/release all main handles. |
| Main=`H`; release or preflight fails | Not exclusive/safe | Keep maintenance and intent; do not call `force-branch!`. Retry or explicitly cancel while main remains `H`. |
| Main descends through reserved target; no completion fact `R` | Head moved; reconstruction incomplete | Reopen fresh, verify forced head and blobs, rebuild target projections, apply only frozen requested overlays, then write the completion fact. Never repeat force from `H`. |
| Completion fact `R`; intent remains | Database transition complete; runtime reopen pending | Reconstruct feeds, replica, program/schema, routes, and hosts; prove readiness; rearm autonomy last; then remove intent durably. |
| Completion fact `R`; no intent | Complete | Ordinary boot/recovery. |
| Main matches neither `H` nor target ancestry | Diverged | Keep maintenance and intent; require operator diagnosis. Never guess. |

Preparation uses native `branch!` from the exact source/target commit, with
read-back of branch, commit, `t`, primary root, and all enabled secondary roots.
Missing blobs fail before promotion. Main-pod and writer quiescence then follow
the clean drain through `:writer-drained`.

Promotion runs as a short-lived `seon.db.server` admin mode from the same writer
artifact and dependency basis. It calls the native lifecycle functions added to
the existing `seon.db.registry` owner, opens only the reserved target branch,
rechecks stored main=`H`, calls `force-branch!` with
`:expected-current-commit`, reads the new main head back, releases successfully,
and exits. The ordinary request server is never started in this mode. This is
not a second database authority: the operator orders the transition, the
registry owns attachment/resource behavior, and maintained Datahike owns
branch mutation.

After promotion, the ordinary writer starts with a fresh main connection. No
old connection, listener, feed watermark, replica, or database value crosses
the root move. The pod reconstructs canonical schema/program data from the
promoted database, applies only overlays frozen in the intent, writes the one
`:seon.db.restore/*` completion entity defined in architecture, then rebuilds
the runtime. Admission opens only after writer/replica coordinates agree and
live proof passes.

Undo invokes this same state machine with the retained undo coordinate as `T`.
It first reserves a new undo branch from current main, which is the redo point.
There is no reverse-datom compiler, rollback mutation, or special undo protocol.

## In-place cutover and deletion map

1. Extend the one runtime admission state and `seon.agent.loop` boundary; add
   `:quiesced` to the existing run schema and tests. Do not create a quiescence
   entity or parallel loop.
2. Add the private lifecycle action to the existing web route/server and teach
   `seon.dev.cli` to use it under the current `:stack` lock. `restart`, `down`,
   rebuild reconciliation, reset, restore, and undo all call one ordered stop
   function with an explicit clean-or-force policy.
3. Strengthen `seon.db.transport.uds` with request admission plus in-flight
   handler joining. A maintenance close returns typed rejection to new work;
   it does not merely tear down sockets.
4. Make `seon.db.registry/release-database!`, `seon.db.writer/stop!`, and
   `seon.db.server/stop!` surface release/drain failure. Never remove an entry or
   report stopped when exclusive release is unproved.
5. Add native branch plan/create/inspect/release operations to the closed
   `seon.db.protocol` and the existing `writer/handle-request` interpreter.
   They remain operator-only capability. Branch-qualified registry work must
   land first.
6. Add the no-listener admin invocation to `seon.db.server` and native guarded
   promotion to `seon.db.registry`. The Babashka operator never opens Konserve
   or reimplements Datahike.
7. Add restore-intent read/write/delete to `seon.dev.state`, including fsync and
   strict schema/version validation. The intent is the only external durable
   lifecycle input.
8. Delete `seon.db.registry/fork-database!`, physical-copy verification/retry,
   copied-database operator paths, and whole-database branch destruction in the
   same slice that native branch attachment graduates.
9. Reset every coordinate-bearing feed/replica/cache after promotion. Numeric
   replay across unrelated ancestry is forbidden.

## Failure matrix

| Failure boundary | Durable truth | Required restart behavior |
|---|---|---|
| Before pod admission closes | Ordinary open runtime | No lifecycle claim; retry from plan. |
| While an accepted turn runs | Open run/turn and only actually committed evals | Planned drain waits. Timeout/KILL becomes unexpected recovery; never write `:quiesced`. |
| After all run closes, before pod RPC drain | `:quiesced` run facts exist; accepted writes may remain | Keep admission closed, finish remote-writer drain, or crash-recover any genuinely open ownership. |
| After pod drain, before JVM drain | Pod cannot issue new work; JVM may have accepted requests | Close request admission, join handlers, await Datahike release. |
| Datahike release fails | Exclusive access unproved | Surface failure, preserve intent/maintenance, do not promote. |
| After undo branch, before target branch | Main=`H`, undo retained | Derive preparation state and finish target creation. |
| After target branch, before force | Main=`H`, both reserved branches verified | Revalidate all frozen inputs; force only after full drain. |
| Expected head changes before/during force | Main is not proven `H`; immutable orphan nodes may exist | Typed stale-plan failure, keep maintenance, never retry with a guessed head. |
| Process dies during force | Main branch head is old or new; prewritten immutable nodes may be orphaned | Read stored main and derive old/prepared versus promoted state. Never infer from process exit. |
| After force, before writer reconnect | Main descends from target; intent remains | Fresh-connect main, verify read-back, continue reconstruction. |
| Missing blob or projection/overlay failure before force | Main=`H` | Abort promotion and preserve intent for correction/cancel. |
| Missing blob or reconstruction failure after force | Promoted main, no completion fact | Keep admission closed; correct from frozen input or restore the retained undo through the same transition. |
| After completion fact, before runtime readiness | Restore is durable; runtime unavailable | Retry reconstruction only. Do not repeat force or overlays already evidenced by the fact/digest. |
| After readiness, before intent deletion | Completion fact and intent both exist | Re-prove readiness, then delete intent durably. |
| After intent deletion | Completion fact only | Ordinary operation. |
| Any unrecognized head/branch/fact combination | Divergence | Fail closed and require diagnosis. |

## Focused tests

### Pod and agent lifecycle

- Pure event tests: `:quiescing` wins only at a loop recurrence boundary;
  paused runs close immediately; superseded ownership is not reclosed.
- Run tests: `:quiesced` close is one CAS-fenced transaction, retracts only the
  owned pointer, emits no parent/root outcome message, and is idempotent under a
  competing close.
- Turn tests: a quiesce request during the body cannot mark the turn done early;
  the complete body result and close coordinate precede run quiescence.
- Admission tests: after the gate changes, message wakes, schedules, direct
  agent start, and new turns return typed maintenance values while already
  admitted turns finish.
- Restart tests: clean drain produces no recovery anchor; TERM/KILL before the
  quiesce response produces exactly one unexpected anchor and no clean claim.

### Writer and storage lifecycle

- UDS close rejects new requests, waits for already entered handlers, and does
  not return while a handler is blocked.
- Registry release propagates Datahike writer, secondary-index, and store-close
  failures and does not claim exclusive success.
- Writer/server stop aggregates typed release failures and readiness remains
  down.
- Native branch tests cover exact historical source, every enabled secondary
  root, missing commit, commit-graph-disabled source, active-connection delete,
  and source survival after branch release.
- Admin promotion tests cover correct expected head, stale head before and
  during force, forced-head read-back mismatch, and guaranteed connection
  release without starting request/publish/REPL listeners.

### Intent, restore, and undo

- Intent schema rejects partial coordinates, mutable phase/status keys,
  unversioned content, overlay digest mismatch, database-id mismatch, and branch
  names not derived from `R`.
- Atomic-write tests kill before rename, after rename, and before/after directory
  fsync; boot sees either the prior complete intent or the new complete intent,
  never partial EDN.
- Table-drive every durable observation in the restore state machine and every
  row of the failure matrix. Re-entering a state performs only missing
  idempotent work.
- Promotion with no overlays never reads current core/config input. Requested
  overlays use only frozen desired maps and record only actually committed
  digests.
- Undo calls the same transition function and creates a new redo branch.

### Ordered multi-form no-fabrication proof

Use the existing runtime and test runners, not a new eval driver. Submit a real
multi-form turn whose form N returns and commits, while form N+1 returns a
controllable unresolved Promise. Observe the eval row and complete coordinate
for N through the writer, then `SIGKILL` the pod before N+1 settles. On restart:

- eval rows/results are exactly the committed prefix through N;
- N+1 and later have no result row;
- the open turn is `:interrupted`, the run is `:crashed`, and one recovery
  anchor exists;
- no message/eval is synthesized by recovery;
- committed declaration facts reconstruct, while scratch effects and the
  unresolved form are not replayed; and
- an immediate second restart adds no recovery or eval fact.

The test must tolerate that N+1 began execution; the claim is about committed
results, not unknowable external rollback. An external effect without its own
idempotency contract is never described as undone.

## Destructive default-cluster graduation proof

Run only after focused JVM/CLJS/operator gates pass and after coordinating the
default cluster reset.

1. Reset and boot default. Record equal writer/replica full coordinate and
   counts of recovery anchors, messages, evals, open runs, and running turns.
2. Start a bounded turn that waits briefly before returning. Invoke
   `bin/seon restart` during it. Prove restart waits; the real eval and turn close
   commit; the run closes `:quiesced`; no unexpected anchor appears; writer and
   replica reopen at one equal descendant coordinate; a later message opens a
   fresh run.
3. Repeat with an unresolved form and kill the pod. Prove the unexpected path
   and ordered-prefix invariants above through CLJ/CLJS REPL queries.
4. Create a native target branch from a retained coordinate, write divergent
   facts/program declarations and a branch-local blob, and prove simultaneous
   source/branch isolation before restore.
5. Confirm restore with no overlays. At each named durable boundary, terminate
   the operator/admin process once, rerun the same command, and prove the state
   machine derives the next action without moving main twice.
6. After promotion, prove main's full coordinate/ancestry, target facts,
   canonical program projection, required blob bytes, config-free restart,
   full-paint feed reset, and absence of old-lineage replay.
7. Restore the retained undo branch through the identical command. Prove the
   new redo branch, original facts/program/blob, one new completion fact, and a
   clean config-free restart.
8. Release only explicitly selected debug/target branches after all readers
   drain. Prove main database and source blobs remain readable.

Required evidence is the operator's structured lifecycle log, exact intent
bytes/digests, branch roster and parent heads, completion facts with transaction
provenance, writer and replica coordinates, run/turn/eval datoms, and live web
feed/browser read-back. A passing process exit or test count alone is not
graduation evidence.

## Implementation blockers and ordering

This work must not start by adding restore commands. It depends on:

1. branch-qualified registry/attachment identity and native branch create,
   attach, inspect, and release;
2. one fail-closed runtime admission state and complete coordinate propagation;
3. branch-local blob base/overlay and promotion materialization rules; and
4. surfaced writer/request/Datahike drain failures.

With those prerequisites, clean quiescence can land independently and provide
the shared destructive crash harness. Restore/undo then reuses that drain and
native branch work. Ordered multi-form crash proof is the final runtime
acceptance slice, not another execution mechanism.
