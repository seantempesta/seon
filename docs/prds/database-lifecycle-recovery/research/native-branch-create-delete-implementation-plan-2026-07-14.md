---
type: research
status: completed
tags: [research, database, flow]
---

# Native branch create/delete implementation plan — 2026-07-14

## Decision

The next database-lifecycle implementation unit can land the JVM-native
create, release, and delete operations now. The hard prerequisites are present
at current HEAD: complete coordinates, branch-qualified registry attachments,
roster-checked non-main opens, serialized source writes, awaited connection
release, joined request draining, and fail-closed runtime publication admission.
The unit does not need to wait for a forensic pod or restore promotion.

The unit must first correct one newly observed prerequisite in the existing
connection initializer. A non-main open currently calls the same initializer
as main. With `SEON_EMBED` enabled, that initializer calls `install!`, which
always transacts the embedding attribute schema, and may run backfill. The
branch can therefore advance before the create response publishes its exact
fork point. A native branch open must validate inherited protocol schema and
secondary resources and install its process-local listener without writing.
This is tracked in
[[../../../seon/issues/native-branch-open-runs-writing-main-initializer]].

Create supports only a complete coordinate whose `t` is the maximum
transaction in its containing commit. Datahike branches a commit UUID, not an
arbitrary cut within that commit. Returning typed `cut-not-branchable` data is
the honest behavior; rounding to the containing commit is forbidden.

## Reconciliation against current HEAD

This plan was reconciled at `04db16de`, including publication admission commits
`8f5936ae` and `dd494cd6`.

Already implemented:

- `seon.db.coordinate` carries one closed
  `{database-id, branch, commit-id, t}` point and stable attachment.
- `seon.db.registry` stores the attachment, enforces logical-route/attachment
  bijection and physical backend agreement, opens non-main branches only from
  Datahike's durable roster, and derives the current head from `d/db`.
- ensure requests accept an explicit attachment; writer transactions, replay,
  events, and replica progress are branch-qualified.
- `transact-once!` holds the connection monitor around expected-head checking,
  transformation, transaction, and receipt recovery.
- `release-database!` awaits maintained Datahike release, retains failed
  identity, and refuses to turn an unproved release into success.
- the UDS server closes request admission, preserves admitted responses, and
  joins accept/connection workers before writer release.
- `seon.runtime.admission` now reconstructs and verifies the committed program
  generation; agent, eval, web-command, schedule, wake, run-loop, and ticker
  work fail closed. Live restart/failure-injection evidence remains a separate
  graduation item, not a blocker to this JVM-local branch unit.
- `my.blob` has a validated overlay/read-base storage view, but no branch launch
  descriptor supplies it yet.

Still absent:

- typed create/release/delete branch protocol operations and writer handlers;
- registry-owned native creation/deletion transitions;
- a read-only non-writing branch initializer;
- crash-idempotent adoption of an exact branch created before registry
  publication;
- deletion of Seon's physical-copy `fork-database!` path; and
- operator/process topology for a second non-autonomous pod.

The older native-branch audit's claims that registry entries omit attachments,
release failures are hidden, and request workers are not joined are stale. Its
coordinate, roster, exact-cut, and non-autonomous-runtime constraints remain
valid.

## Dependency ledger

| Dependency or mechanism | Selected identity | Source read | Constraint for this unit |
|---|---|---|---|
| Datahike | `org.replikativ/datahike` SHA `6f90b339768b1a02066dce3b6fcc93a200758fcc` in root `:writer` and `:cljs` override | `reference-code/datahike/src/datahike/versioning.cljc:123-370` | `branch!` accepts a branch keyword or retained commit UUID, branches enabled secondary roots from the selected stored record, writes the target head then roster, and rejects disabled/missing commit history. `delete-branch!` protects `:db`, requires roster membership, and rejects active target connections. `branch-as-db`/`commit-as-db` are the read-back owners. |
| Datahike release | same SHA | `reference-code/datahike/src/datahike/connector.cljc:438-510` | Final release closes write admission, awaits accepted primary work, closes secondary resources, then releases the store. Failure is a real failure, not a best-effort hint. |
| Datahike Proximum adapter | same Datahike SHA; `org.replikativ/proximum` `0.1.25` | `reference-code/datahike/src-secondary/datahike/index/secondary/proximum.clj:188-230,338-355` | Branching loads the selected stored Proximum commit, uses Proximum's native branch, synchronizes it, and returns a branch-qualified key map. A declared-but-unrestored index must fail, never rebuild silently. |
| Datahike Stratum adapter | same Datahike SHA; optional source on writer path | `reference-code/datahike/src-secondary/datahike/index/secondary/stratum.clj:1094-1103` | Branching forks the selected dataset commit and returns a target-branch key map. Verification must cover every enabled secondary, not assume Proximum is the only possible one. |
| Konserve | `org.replikativ/konserve` SHA `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve/src/konserve/protocols.cljc:4-36` and maintained Datahike versioning source | Per-key update is atomic; no cross-operation CAS exists. Seon delegates branch keys to Datahike and uses its JVM/connection locks rather than writing Konserve directly. |
| Seon writer/registry | current HEAD | `src/seon/db/{registry,writer}.clj` and focused JVM tests | Registry lock owns route/attachment lifecycle. The source connection monitor is the same exclusion used by transactions. Current initializer is writing and must be split by open intent before exact branch publication. |
| Seon protocol/transport | protocol version `2`, Transit CLJ `1.0.333` | `src/seon/db/protocol.cljc`, `transport/uds.clj`, `writer.clj` | Extend the one discriminated protocol and existing UDS handler. Lifecycle operations do not alter transaction receipt hashing, so protocol receipt version remains `2`. |
| Runtime admission | commits `8f5936ae`, `dd494cd6` | `src/seon/runtime/admission.cljs` plus agent/web callers | The fail-closed owner exists. Full planned quiescence is still required for restore/promotion, but same-writer branch creation can fence source transactions on their existing connection monitor. |

The `reference-code/datahike` and `reference-code/konserve` checkouts exactly
match their selected SHAs.

## Executable probe

A disposable in-memory writer-classpath probe created a branch from the live
head commit UUID and read it back:

```clojure
{:branch-return [#{:db} #{:probe/native :db}],
 :branches #{:probe/native :db},
 :head-cid #uuid "6a57037a-92a8-52d2-9c62-87c4cd2376f3",
 :head-t 536870913,
 :branch-cid #uuid "6a57037a-92a8-52d2-9c62-87c4cd2376f3",
 :branch-t 536870913,
 :same-datoms? true}
```

After `delete-branch!`, the roster was `#{:db}` while `branch-as-db` still
loaded the stale target head. This independently confirms that Seon's roster
check, not branch-head key existence, is the authoritative open gate. The
probe used a unique memory store and did not touch either live cluster.

The return from `branch!` is Konserve update data, not Seon's response shape.
The implementation must derive success from read-back.

## Exact implementation slice

### 1. Make branch open observational

Strengthen the existing initializer rather than adding another registry:

- change the initializer input from positional opaque values to one namespaced
  request carrying connection, logical route, attachment, and open intent;
- retain the one listener installation and protocol-schema validation;
- for main boot, retain optional embedding installation/backfill;
- for non-main open, require inherited protocol schema and, when the Proximum
  declaration exists, require its restored live instance; do not transact
  schema, backfill, config, or any other facts; and
- prove the coordinate before and after initialization is identical.

This is not a second initialization mechanism. It is one initializer whose
behavior is derived from the attachment and whose branch path is validation
only.

### 2. Add registry-native lifecycle functions

Add `create-branch!` and `delete-branch!` beside `ensure-database!` and
`release-database!`. Keep one `!registry` and one entry shape.

Create request data:

```clojure
{::registry/source-database-name :default
 ::registry/target-database-name :default-forensic-r
 ::registry/source-coordinate <complete point T>
 ::registry/expected-source-head <complete point H>
 ::registry/target-branch :seon.branch/r
 ::registry/initialize-connection! <writer initializer>}
```

Ordered create transition:

1. Under `locking !registry`, resolve the source entry and reject target route,
   target attachment, backend/path, and branch-name conflicts.
2. Require the source coordinate and expected head to share the source entry's
   exact attachment. Reject `:db` as a target and partial/malformed points
   before dependency calls.
3. Under `locking source-connection` (nested inside the registry lifecycle
   lock), derive current head and compare it to `expected-source-head`. This is
   the same monitor held by `transact-once!`, so an accepted source transaction
   finishes first and a later source transaction cannot enter until branch
   read-back completes.
4. Load `source-coordinate.commit-id` with `d/commit-as-db`. Require the loaded
   database identity/branch/commit to match the request and require
   `source-coordinate.t == loaded.max-tx`. Otherwise return missing-history,
   attachment-mismatch, or `cut-not-branchable`.
5. If the target is absent from the roster, call `d/branch!` from that commit.
   If it is already present, adopt it only when read-back has exactly the
   requested target attachment, commit id, and t. A different head is
   `branch-exists`, never overwritten.
6. Read through `d/branch-as-db`; verify target coordinate, complete primary
   datom equality at the fork point, and presence/shape of every selected
   secondary key map.
7. Open the target through the existing `open-entry!` with the observational
   branch initializer. Require the connected head to remain the exact fork
   point and all declared secondary instances to be live.
8. Publish the target registry entry only after every proof passes. Return the
   target logical route, attachment, exact coordinate, backend/path, and
   `created?` versus recovered/adopted status.

If branch creation succeeds but open/validation fails, release any target
connection, then delete the unpublished branch only after release is proved.
If cleanup cannot be proved, return the exact branch attachment/head and a
cleanup-required failure; never hide it or publish a registry route.

The target branch name is the durable idempotency key. A process death after
Datahike creates the head but before registry publication is recovered by step
5 only when the read-back exactly matches the original request.

Release request data requires target logical route, expected attachment, and
expected target head. Under the registry lock, it validates both before
calling the existing `release-database!`. An absent route returns released
false; a retained release error remains visible.

Delete request data requires a live source route, target logical route, target
attachment, and expected target head. It is retryable after a successful
release: when the route is absent, inspect the target through the source
connection and continue only if roster membership and exact head match.
Release a present target first; stop on unproved release. Then call
`d/delete-branch!` through the source connection, verify roster absence, and
return the unchanged source coordinate plus released/deleted facts. Never
delete a database or blob directory.

### 3. Extend the one typed protocol and writer interpreter

Add three discriminators to `seon.db.protocol`:

- `create-branch-operation`;
- `release-database-operation`; and
- `delete-branch-operation`.

Give each a closed request/response schema and pure constructor. Expand the
closed error enum for duplicate route/attachment, attachment mismatch, stale
source/target head, missing commit, disabled history, unbranchable cut,
branch exists/missing, protected main branch, active connection, initializer
failure, release failure, and cleanup-required. Map Datahike's `:type` and
registry failures deliberately; do not collapse lifecycle errors into generic
database text.

Add cases to the existing `writer/handle-request`. These operations remain
supervisor-only by having no wrapper in agent-facing `seon.db` or the `my.*`
toolkit. They use the same UDS server; no admin socket, REPL expression, or
second envelope is added.

Protocol receipt version remains `2`: lifecycle requests do not create or
hash durable transaction receipts.

### 4. Delete the superseded physical fork in the same unit

After native create/release/delete passes through UDS, remove from Seon:

- `registry/fork-database!` and `fork-verify!`;
- its `at`, fork-name, forked, basis-t, request, and response schemas;
- the physical-copy registry test; and
- stale source comments claiming `cluster fork/destroy` still call the writer
  REPL.

Do not delete `bin/seon-server-call` in this unit: current archive/readback
tools still call it. Its retirement requires typed replacements for those
remaining consumers. Do not retain physical fork as a fallback.

## Lock and ownership order

Use one order everywhere in this unit:

```text
registry lifecycle lock -> source connection monitor -> Datahike operation
```

Ordinary source transactions take only the connection monitor. Ensure,
release, create, and delete take the registry lifecycle lock; create/delete
then take the source monitor. No code takes the source monitor and later tries
to enter registry lifecycle. This prevents route publication races while
letting the existing transaction monitor be the exact source-head fence.

This is sufficient for branch creation inside the sole JVM writer. It is not
the exclusive-store proof required by `force-branch!`; restore/promotion still
requires full pod/JVM quiescence and fresh reopen.

## Failure matrix

| Failure boundary | Required result | Durable mutation allowed |
|---|---|---|
| source route absent | typed not-found | none |
| source point/expected head belongs to another attachment | attachment mismatch | none |
| accepted source write wins before lifecycle monitor | stale source head with current complete point | only that real source write |
| requested commit missing or commit graph disabled | missing-history / unsupported-history | none |
| requested `t` is below containing commit maximum | cut-not-branchable | none |
| target branch is `:db` or malformed | protected/invalid branch | none |
| target route or attachment belongs to another entry | duplicate route/attachment | none |
| roster target exists at a different point | branch-exists with actual target head | none |
| roster target exists at the exact requested point after interruption | adopt, validate, and publish once | no new branch mutation |
| secondary branch preflight fails inside Datahike | dependency failure | dependency-owned immutable orphans only; no Seon entry |
| branch head lands, target open/restore fails, cleanup release+delete succeeds | initializer failure with cleaned facts | target head/roster created then removed |
| cleanup release or delete cannot be proved | cleanup-required with exact attachment/head | unadvertised target may remain; no route publication |
| branch initializer writes or changes head | invariant failure; release and cleanup | no published route |
| release expected head is stale | stale target head | none |
| Datahike release fails | release failure; retain exact entry/error | none after failure |
| delete retried after route already released | validate branch through source, then delete | roster removal only |
| raw target connection remains active | active-connection | none |
| delete removes roster but stale branch key remains | success; later Seon ensure rejects roster absence | roster removal only |
| delete requested for `:db` | protected main branch | none |
| source changes during target delete | target deletion may proceed only after exact target proof; return current source head | target roster removal only; source facts unchanged |

## Focused test order

### JVM dependency/registry tests

1. Branch initializer preserves the exact coordinate with embeddings disabled
   and with a declared live Proximum index; an injected writing initializer is
   detected and never published.
2. Create at current head and at an older exact commit produces exact initial
   primary datoms and complete target coordinates.
3. A coordinate inside, but below the maximum of, a containing commit returns
   `cut-not-branchable`.
4. Source write held inside `transact-once!` proves create waits on the same
   monitor, then rejects the stale expected head; retry with the new head
   succeeds.
5. Duplicate route, duplicate attachment, missing commit, commit-graph opt-out,
   existing different branch, and main-branch target mutate nothing.
6. Exact pre-existing roster/head is adopted once; a different head is never
   adopted.
7. Inject target open, secondary restore, release, and delete failures at each
   boundary and assert registry/roster truth matches the failure matrix.
8. Release refuses stale attachment/head and retains release failure identity.
9. Delete works both from a live target route and after an interrupted prior
   release; active raw target connection and `:db` deletion refuse.
10. Source facts, source complete head, and source secondary query remain
    unchanged after target deletion.

Use `seon.db.registry-test`, a focused branch-lifecycle namespace if the
registry test becomes too broad, and the maintained Datahike versioning tests.

### Protocol/writer tests

1. Transit round-trips every lifecycle request/response and error exactly.
2. Invalid partial points and unknown fields fail protocol validation.
3. UDS create opens two routes over one database UUID, then isolated writes
   produce different branch-qualified coordinates and events.
4. Replay/search resolve the target route and never cross-deliver source
   frames.
5. UDS release/delete returns structured failures and cannot report success
   after unproved Datahike release.
6. The complete writer gate remains green after physical fork deletion.

Run focused `bin/test-writer` namespaces, then the complete writer checkpoint.
No CLJS production change is required for this JVM-only slice; retain the
existing replica branch-routing gate as a regression check.

## Live acceptance proof for this slice

Do not claim forensic-runtime graduation yet. The live proof for this unit is
writer-local and uses a disposable named test database through the typed UDS
protocol:

1. Start one writer, capture main point `H`, primary query rows, and enabled
   secondary query results.
2. Create target branch `B` at exact `H`; observe same database UUID and
   commit/t, different branch, unchanged source head, and no initialization
   transaction on `B`.
3. Send one typed transaction to each route and observe isolated facts,
   branch-qualified transaction events, and independent replay cursors.
4. Release `B`, delete it through the source route, and verify roster absence,
   target reopen refusal, stale raw head-key presence, and unchanged source
   facts/head/secondary results.
5. Restart the writer config-free and prove main reopens at its exact final
   coordinate with no target route or branch roster entry.

The later operator/forensic slice must additionally prove a second
non-autonomous pod, distinct ports/process coordinates, branch blob overlay,
gzip feeds, browser reads, and target-pod stop before release/delete.

## Program sequence after this unit

1. **Now:** observational branch initialization plus typed JVM native
   create/release/delete; remove physical fork.
2. **Then:** explicit branch launch descriptor, non-autonomous/read-only pod
   mode, branch-local blob view injection, and multi-pod operator ownership.
3. **Then:** clean agent-turn quiescence and the operator's one ordered
   clean-or-force stop path.
4. **Then:** immutable restore intent, no-listener admin invocation, guarded
   `force-branch!` promotion, fresh attachment reconstruction, and undo through
   the same restore path.
5. **Finally:** destructive default transition matrix and ACME coordination.

Parallel work may continue on non-autonomous pod design and operator proof
fixtures while this JVM unit is implemented, but production operator commands
must wait for typed lifecycle operations, and restore implementation must wait
for clean quiescence.
