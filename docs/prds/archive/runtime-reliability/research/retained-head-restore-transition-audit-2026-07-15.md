---
type: research
status: complete
tags: [research, database, flow, orchestrator]
---

# Retained-head restore transition audit

## Question

What is the smallest public, crash-convergent operator transition that turns
the existing immutable restore intent and runtime/database kernels into a real
retained-head restore without creating a second lifecycle mechanism?

## Conclusion

The restore kernel is substantially implemented, but the public operator
transition is not. `seon.dev.restore` already derives and validates immutable
intents, selects the next command from durable observations, derives the
writer-admin invocation, and closes startup identity. The writer already owns
guarded primary and secondary force, the pod already owns retained-blob
materialization, and cold startup already keeps admission closed until an
exact completion exists.

The smallest remaining implementation is one effect coordinator in
`seon.dev.restore-state`, exposed through `seon.dev.cli`. Before that
coordinator can be complete, it needs two production observations that do not
currently cross the operator boundary:

- one typed writer lifecycle read returning the exact main coordinate, the
  complete native branch-to-coordinate map, and the full native branch roster;
- one loopback-only pod door that observes and materializes the retained blob
  set against one frozen database value.

Do not infer either observation from lifecycle files, process records, MCP
eval, or an interior `as-of` cut. Do not persist a phase, retry count, or
"force completed" flag. Retry derives from the immutable intent plus current
storage and completion facts.

## Dependency ledger

- Datahike `9ada755087228e10cfb179fa5779ce227a6ed220`:
  `datahike.versioning/force-branch!` fences source and destination heads,
  forces primary plus declared secondary indexes, and reads the final head
  back; `datahike.connector` reopens the current durable branch state after a
  released connection.
- Konserve `b5c99bc02a7175652a610324215288b78551801f`:
  the selected Node filestore treats absent deletion as converged and fsyncs
  the parent after a successful deletion. The exact selected source was read
  with `git show` because the reference checkout is historical.
- Proximum `9846d3e79e1aee48474bc876d3d563d7137209c6`:
  its guarded force checks source generation and destination state before
  publishing a generation-addressed mmap snapshot. The exact selected source
  was read with `git show` because the reference checkout is historical.
- babashka.process `0.6.25`, source
  `16a84e0af0da51b8c84e289970f6b7cc35b35d18`: process values support bounded
  dereference, but their descendant shutdown hook is not Seon's containment
  authority.

## Existing mechanisms to compose

- `seon.dev.restore/derive-intent`, `validate-intent`, `next-command`,
  `derive-admin-invocation`, and `startup-identity`;
- `seon.dev.restore-state/publish-intent!` plus the atomic, parent-durable
  `seon.dev.state/write-edn!` and `delete-edn!` operations;
- typed branch create/adopt, ensure, release, and delete through the writer;
- `seon.dev.process/clean-or-force!` in pod, writer, watcher inverse order;
- the existing writer artifact's `--restore-admin-intent` and
  `--restore-admin-result` mode;
- `seon.db.server/run-restore-admin!` and `seon.db.writer/admin-restore!`;
- `my.blob/materialize-retained!`;
- `seon.launch/with-restore-startup`;
- `seon.dev.process/specs`, `start-order`, and `ensure!`;
- the restore-aware cold path in `seon.client/start-runtime!`; and
- completion publication and retry resolution in `seon.db.restore`.

## In-place ownership

### Writer lifecycle observation

Strengthen `seon.db.protocol`, `seon.db.writer`, and `seon.db.registry` with one
typed, read-only lifecycle observation. It returns the exact main coordinate,
all native branch coordinates, and the complete native roster. Completion
branches and externally created branches may have no open branch-pod record,
so operator files cannot be the roster authority.

### Retained blob door

Strengthen the existing `my.blob` and `seon.web`/router boundary with a
loopback-only operator door. Against one locally constructed frozen database
value it must:

- observe the exact retained coordinate and reachable-hash digest; and
- materialize from a validated frozen intent, returning the existing closed
  materialization result.

A database value must not cross HTTP, and MCP eval must not become production
transport.

### Operator coordinator

Keep durable effect composition in `seon.dev.restore-state`. Add one public
map-in/map-out transition that reads or publishes the immutable intent,
obtains fresh observations, calls `next-command`, performs exactly that
effect, and rereads durable facts. Blob and admin results belong at
deterministic intent-specific paths and are deleted only after graduation.

`seon.dev.branch` should expose only the narrow exact-head and retained-pod
stop operations the coordinator needs. Stopping a retained target must
preserve its branch and lifecycle record; the existing destructive `close!`
operation is not suitable.

`seon.dev.cli` owns parsing, confirmation, lock acquisition, and presentation
for `cluster restore <retained-branch>`. Extract the existing process-graph
reconciliation loop so ordinary startup and restore startup both consume
`process/specs`, `start-order`, and `ensure!`.

## Derived transition

1. With no intent, obtain fresh `H`, selected retained `T`, roster, target blob
   digest, artifact digest, and consumer generations. Reject an interior cut,
   derive the intent, and durably publish it before mutation.
2. Create or exactly adopt undo branch `U` from frozen `H`.
3. Create or exactly adopt prepared branch `P` from frozen `T`.
4. Reprove `U`, `P`, `H`, and the retained blob set; stop every consuming pod;
   require exact writer absence and the frozen writer artifact digest; then
   invoke the no-listener admin artifact.
5. If the admin result is missing or uncertain, invoke it again. Storage
   observation distinguishes `applied`, `already-applied`, and divergence;
   process exit status is not success evidence.
6. When main equals exact forced `F` and completion is absent, start the writer
   and one fresh pod with `with-restore-startup`. Preserve-only reconstruction
   records completion before opening admission.
7. When completion exists, resolve its original transaction coordinate, prove
   exact readiness, and durably remove intent-specific state.
8. Reconcile once through the ordinary descriptor after removing restore
   startup evidence. Otherwise normal status retains a restore-only process
   identity mismatch.

## Invariants and focused proof

- `T.t` equals the retained commit head; an interior cut is never rounded.
- `H`, `T`, `U`, `P`, `F`, and completion share one database id.
- Reserved heads never advance; movement is divergence.
- Every writer consumer is absent before admin launch, and uncertain
  containment blocks force.
- The writer jar digest equals the frozen manifest/intent claim immediately
  before launch.
- Admin accepts only released `applied` or `already-applied` evidence.
- Blob result agrees exactly on target coordinate and reachable-set digest.
- No connection, cursor, database value, compiler state, or cache crosses
  force.
- Completion precedes admission.
- Retry derives from intent, storage, and completion rather than mutable phase
  state.
- Undo selects the recorded completion's retained undo branch and freezes the
  actual latest main as the new redo point.

Focused tests must cover exact/complete lifecycle observation, loopback and
coordinate fencing on the blob door, pre-intent rejection, atomic intent
publication, exact adoption of `U` and `P`, blob/containment/artifact failures
before admin launch, every admin result boundary, crash cuts around force and
completion, ordinary-descriptor convergence after cleanup, and undo with an
intervening ordinary write.

## Live falsifier

Use an isolated named cluster. Create a retained target with database and blob
facts distinct from main, record exact `H` and `T`, and restore `T`. The new
main commit must have `T.commit-id` as its sole parent; EAVT, every declared
secondary root, KNN results, and selected blobs must equal the target; one
root/boot completion must exist; readiness must have remained closed until
that completion.

Then terminate the restore pod after completion but before intent cleanup and
repeat the public command. Admin must report `already-applied` without another
force, the completion coordinate must remain unchanged, and no second
completion transaction may exist. Reopen config-free and compare writer,
replica, and feed coordinates. Finally perform completion-derived undo after
an intervening main write and prove the new redo branch retains that write
while the original facts return.
