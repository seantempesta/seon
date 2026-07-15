---
type: research
status: completed
tags: [research, database, flow]
---

# Database lifecycle source audit — 2026-07-14

## Decision

The next implementation must strengthen the existing writer, registry,
protocol, and replica in place. The maintained Datahike fork already contains
the required same-store branch primitives and the historical-secondary-index
correction. Seon does not yet carry their lineage through its registry,
protocol, feed, replica, runtime admission, or operator lifecycle. The active
`fork-database!` path is still a physical Konserve copy with a new database
identity and must be deleted when native branches become usable.

The first implementation boundary is not restore. It is one canonical resolved
coordinate plus one prevalidated schema/program publication transition. Until
every request, response, replay page, feed event, registry entry, and replica
attachment can distinguish `{database-id, branch, commit-id, t}`, enabling
same-store branches would let two lineages reuse the same numeric `t` while the
current protocol treats them as one stream.

## Dependency ledger

| Dependency or mechanism | Selected identity | Grounded source | Finding |
|---|---|---|---|
| Datahike | `org.replikativ/datahike` git SHA `6f90b339768b1a02066dce3b6fcc93a200758fcc` in both `:writer` and the `:cljs` override; the nominal CLJS Maven `0.8.1681` is overridden | `reference-code/datahike` is exactly the selected SHA; especially `src/datahike/versioning.cljc`, `connector.cljc`, `writing.cljc`, and `test/datahike/test/versioning_test.clj` | `branch!`, `delete-branch!`, `commit-as-db`, `branch-as-db`, and guarded `force-branch!` are maintained primitives. `branch!` now branches secondary indexes from the selected stored root, not an unrelated live head. `force-branch!` explicitly requires exclusive writer access despite its expected-head guard. |
| Konserve | `org.replikativ/konserve` git SHA `df6818d43ea3363a808cd051c0d68917f1b987a9` in both maintained dependency graphs; fork describes itself as upstream `0.9.356` plus the legacy header reader | `reference-code/konserve` is exactly the selected SHA; especially `src/konserve/protocols.cljc`, `store.cljc`, `filestore.clj`, and `compliance_test.cljc` | Per-key `update` is atomic and optional multi-key operations are all-or-nothing, but Datahike's guarded branch promotion still is not a CAS spanning an independent writer operation. Lifecycle quiescence is therefore required, not optional defensive ceremony. |
| Kabel / distributed transport | `org.replikativ/kabel` `0.3.100` appears only in Datahike's `:test` alias with `konserve-sync` `0.1.35`; it is not in Seon's writer or CLJS runtime basis | Maintained Datahike SHA contains `reference-code/datahike/src-kabel/datahike/kabel/{connector,writer,tx_broadcast}.cljc` | Kabel has a remote-writer plus synchronized local-store design, but it is not Seon's active transport and must not be smuggled into this local lifecycle chunk. Remote replication remains a separate PRD. The source is useful as evidence that a client must not expose a head before every reachable node is present and that writer shutdown owns pending operations/subscription cleanup. |
| Active Seon transport | `com.cognitect/transit-clj` `1.0.333` and `transit-cljs` `0.8.280` over the repository UDS implementation | `src/seon/db/transport/uds.clj`, `src/seon/db/transport/uds.cljs`, and their tests | UDS is a data-only request/reply plus publication transport. It currently routes by logical database name and does not carry a resolved lineage coordinate. Transport stays semantic-free; the protocol map must gain the coordinate. |
| Malli | `metosin/malli` Maven `0.20.0`; exact release commit `4c054bd7d042e70d60b83b9f07fb765bc103037f` | The working `reference-code/malli` checkout is newer (`80138076960e7820523b4cb932c5b5d1936d4e7f`). Exact `0.20.0` source was verified from the fetched tag. `registry.cljc` is identical for the relevant registry operations; `core.cljc` differs later, so line numbers from working HEAD are not proof of the selected artifact. | Release `0.20.0` supplies immutable `fast-registry`, composed registries, the mutable default-registry pointer, explicit-registry schema compilation, and `validate`/`explain`. Seon's build-then-activate design matches those primitives. Durable docs must cite the tag for exact behavior until `reference-code/malli` is aligned to the selected release. |
| Seon schema projection | `seon.schema` | `src/seon/schema.cljc`; boot and eval callers in `src/seon/client.cljs` and `src/seon/eval.cljs` | `build-projection` is pure and complete, and `activate-projection!` is one process-local swap. Eval commits canonical declaration facts before activation. A post-commit instrumentation failure only records a core error; it does not close admission or reconstruct the committed projection. |
| Seon database writer and registry | `seon.db.writer`, `seon.db.backend`, `seon.db.registry` | `src/seon/db/{writer,backend,registry}.clj` and focused JVM tests | One serialized writer, deterministic database id, durable receipts, explicit registry routing, and bounded replay exist. Registry identity is still logical database name to one connection; branch and commit are absent. |
| Seon protocol and replica | `seon.db.protocol`, `seon.db.replica` | `src/seon/db/protocol.cljc`, `src/seon/db/replica.cljs`, UDS tests, replay tests, and replica tests | The replica owns one attachment generation, buffers live events during bounded replay, rejects malformed pages, and reconnects from its watermark. Its internal attachment identity includes database id and branch but not commit id; protocol events/pages contain only database name and numeric basis values. |

## Maintained dependency findings

### Datahike already owns native lineage

At the selected SHA, `datahike.versioning/branch!` accepts either an existing
branch keyword or a commit UUID, rejects a missing commit and a disabled commit
graph before mutation, and constructs secondary-index roots from the same
selected stored database (`versioning.cljc:153-197`). `delete-branch!` refuses
`:db` and refuses an active connection to the selected store and branch
(`:199-227`). `commit-as-db` and `branch-as-db` load immutable roots directly
from the same Konserve store (`:337-373`).

`force-branch!` has the correct sharp boundary: it accepts an optional
`:expected-current-commit`, writes immutable nodes before the branch head,
rechecks the expected head inside the final per-key update, and reads the new
head back (`:229-335`). Its own source says that the caller must hold exclusive
write access because Konserve provides no CAS across an independent writer.
That makes quiescence a product invariant.

This materially updates the 2026-07-12 time-travel audit: the secondary-index
branch correction, connection-release waiting, expected-head guard, and head
read-back are now present in the selected Datahike SHA. They remain dependency
regression gates, not missing upstream implementation.

### Physical `fork-database` is a different operation

`seon.db.registry/fork-database!` calls `datahike.api/fork-database`, assigns a
new deterministic database id from the target logical name, verifies a copied
head plus full history scan, and retries one torn copy. This is useful evidence
for the old operator but it is not the architecture's writable branch:

- the target is a new physical Konserve store;
- source writes continue during the copy;
- the new database has a distinct database identity;
- release/destruction deletes a whole database, not one branch; and
- blob copying and branch-local overlay semantics are outside this mechanism.

Delete this owner and its operator callers in the same slice that makes native
same-store branch creation, attachment, and release usable. Do not retain it as
a compatibility fork.

### Kabel is reference evidence, not the selected local mechanism

Datahike's optional Kabel connector waits for the full Konserve synchronization
handshake before exposing the received branch head. Its writer waits until the
local synchronized database reaches the transaction's expected `max-tx`, owns
pending waiters, and cleans up its subscription on shutdown. Those are useful
replication laws. Adding Kabel to Seon's active aliases would create a second
transport and remote-replication policy; this PRD should instead preserve UDS
and make its semantic messages lineage-complete.

### Malli supports candidate-first publication

Malli `0.20.0`'s `fast-registry` is an immutable map projection,
`composite-registry` establishes explicit precedence, and
`set-default-registry!` changes one process-global pointer. `m/schema` accepts
an explicit registry, and validators/explainers compile from that schema.
Therefore Seon can and should build and validate the complete candidate before
any durable mutation, then publish the exact candidate after commit. No second
mutable registry or incremental guess is required.

The local `reference-code/malli` checkout is newer than selected Maven source.
This audit used the exact `0.20.0` tag for claims above. Aligning the checkout
is documentation/source hygiene, not a reason to change the runtime dependency
inside this PRD.

## Current implemented mechanisms

### Durable writer path

- `seon.db.backend` supplies one history-enabled Datahike configuration with a
  stable UUID and hardened file path.
- `seon.db.registry/ensure-database!` serializes first open, runs one fixed
  initializer before publishing an entry, and releases a failed connection.
- `seon.db.writer` is the only application writer. It checks the expected head,
  adds durable request receipts, recovers a committed lost reply, and rejects
  request-id reuse with a different logical transaction hash.
- Writer replay reconstructs bounded transaction pages from Datahike history
  under one fixed `through-t` and includes add/retract datoms plus transaction
  metadata.
- UDS publisher queues are bounded. The CLJS replica opens publication first,
  replays the gap while buffering live frames, drains the buffer without an
  async gap, and then goes live.

### Read-only historical values

`seon.db/as-of`, `history`, `since`, and `basis-t` already wrap maintained
Datahike values. A read-only default-cluster REPL probe on 2026-07-14 observed
current basis `536870929` and an `as-of` value at `536870928`; no writer or
transaction was opened by the read. Numeric historical reads are implemented,
but they are not yet canonical lineage bookmarks.

### Program/schema reconstruction

`seon.schema/build-projection` compiles a complete immutable registry,
dependency indexes, function contracts, and render catalog. Boot code derives
canonical program/schema populations and eval builds a candidate before its
tee transaction. `activate-projection!` swaps the exact projection after an
accepted transaction. Existing tests cover converged core deltas, declaration
resume, registry relinking, crash repair facts, receipts, replay, and replica
overlap.

## Falsifiable current failures

### The live coordinate is split and incomplete

A read-only JVM REPL probe of the live default writer returned:

```clojure
{:database-name :default
 :basis-t 536870929
 :branch :db
 :commit-id #uuid "6a56adb5-1026-5ca0-8a2a-d04ead7d4a74"
 :branches #{:db}}

```

The matching live CLJS `seon.db.replica/status` returned a database id and
branch plus last-applied basis `536870929`, but no commit id. This is not a
display omission: `seon.db.protocol/transaction-event-map`, transaction
responses, and replay responses carry database name and bare basis values;
`seon.db.replica/connection-coordinate` carries database id and branch, while
`progress-coordinate` adds only basis-t.

Falsification: create two native branches whose next commit has the same
numeric `t`; current frames and replay cursors cannot state which commit or
branch they mean. Acceptance requires every resolved point to carry all four
coordinate fields and every stable attachment to carry database id plus branch.

### A committed declaration can fail publication without closing admission

In `seon.eval/eval-batch!`, the declaration transaction commits before
`instrument-projection-delta!`. If instrumentation returns `::ok? false`, the
code records a core error but does not activate the candidate, stop admission,
or reconstruct from committed facts. Subsequent work can therefore run with
database program facts from generation N and the old process projection from
generation N-1.

Falsification: inject an instrumentation-publication failure after an accepted
schema/function tee, then issue another ordinary eval. Current code has no
admission fence requiring that second eval to fail or wait. Acceptance is one
bounded core fault, closed admission, exact reconstruction from committed
facts, and either a verified reopen or process/readiness failure.

### Protocol receipt schema bypasses the canonical candidate

`seon.db.protocol` registers Malli forms for receipt attributes but also owns a
hand-written `receipt-schema` vector of native Datahike declarations.
`seon.db.writer/seed-receipt-schema!` compares and transacts that vector before
the composed database initializer. This is a second native-schema installation
path outside the canonical Malli-to-Datahike candidate.

Falsification: change the Malli receipt declaration while leaving the raw
vector unchanged. Boot can validate one shape and install or accept the other.
Acceptance is one candidate-derived native schema compatibility check and one
commit boundary, with receipts included in the canonical schema population.
This finding is tracked in
[[../../../seon/issues/database-receipt-schema-bypasses-candidate]].

### Native branch lifecycle is not wired

The selected Datahike source supports same-store branches, commit roots, guarded
promotion, and branch deletion. `seon.db.registry` does not call them. Its entry
shape contains connection, backend, and optional path, keyed only by logical
database name. The protocol has no branch operations or full coordinate, and
the pod exposes no non-autonomous forensic attachment mode.

Falsification: request a writable same-store branch at the live commit. There
is no canonical Seon request capable of representing, attaching, or later
deleting it. Acceptance is branch-qualified registry bijection, typed protocol
operations, non-autonomous branch runtime, and deletion through
`datahike.api/delete-branch!` only after all branch handles drain.

### Planned restart quiescence and restore/undo are absent

The supervisor stops processes in order, and `seon.runtime.recovery/recover!`
has a one-transaction rule for interrupted runs. There is no admission gate
that drains accepted turns before a planned restart, no durable external
restore intent, no native undo branch, and no guarded main-head promotion flow.

Falsification: begin a turn, request planned restart, and observe whether the
turn reaches a committed boundary before shutdown. Current operator lifecycle
does not promise that outcome. For restore, kill after each intended boundary;
there is no intent from which a new supervisor can derive the next safe action.

## Transition matrix

| Transition | Implemented evidence | Exact gap | Graduation proof |
|---|---|---|---|
| Fresh boot | Operator selects config only for a fresh database; registry creates/connects once; writer initializer installs receipts and application schema/program; root/child boot tests exist | Receipt schema is a pre-initializer side path; no one complete candidate proves native schema plus program/config before post-genesis writes | Destructive default reset: one genesis/candidate sequence, valid root and initial child, one complete coordinate, no duplicate schema authority |
| Explicit converged config | Provenance-scoped reconciler and tests show equal desired state emits no delta | Candidate/native schema/program/config admission is not one fail-closed transition | Apply identical config twice; second application writes no datoms and leaves the same commit coordinate |
| Populated config-free reopen | CLI omits manifest after the database exists; program graph and agent namespaces reconstruct from facts | Need an end-to-end proof that no current source/config fallback participates and failed publication closes admission | Stop, remove operation-scoped config, reopen, compare canonical facts and projection fingerprint, prove zero config writes |
| Failed schema/program publication | Candidate build precedes commit; failed tee can fall back without accepting declaration facts | Post-commit instrumentation failure records an error but leaves admission open with old projection | Deterministically fail publication after commit; next work is rejected; cold reconstruction activates the committed generation or readiness fails |
| Clean planned restart | Supervisor has process ordering and writer release; pod reconstructs process-local state | No turn-boundary admission/drain protocol | Start bounded work, request restart, observe final committed turn/run coordinate before process stop, then reconstruct without crash marks |
| Unexpected interrupted-run recovery | `seon.runtime.recovery/recover!` and tests CAS-fence open runs/turns and record one recovery anchor transaction | Must prove supervisor invokes it only for unexpected exits and before autonomous hosts restart | Kill during an open turn; one recovery tx marks interruption/crash, no fabricated eval, agent derives idle, later restart is idempotent |
| Canonical coordinate | One closed coordinate now crosses writer responses, receipts, feeds, replay, replica progress, write fences, and exact historical reads | Registry/turn/error/cache boundaries remain partially or t-only keyed | Two branches with colliding t remain distinguishable in reads, feeds, caches, bookmarks, errors, and turn capture |
| Read-only as-of | `seon.db/at-coordinate` asynchronously loads the named containing commit, validates its attachment and cut, and returns an immutable `as-of` view or structured error data | Turn/error/debug/autocomplete/web consumers still call numeric `as-of`; no non-autonomous forensic runtime | A complete retained coordinate reproduces exact state; partial/wrong/missing selectors return data; reads create no transaction or runtime host |
| Writable branch | Maintained Datahike `branch!`, `branch-as-db`, `delete-branch!` and secondary-index behavior exist | Seon uses physical `fork-database`; attachments, feeds, registry, membership, blobs, and operator are not branch-qualified | Branch at historical commit; primary and every secondary query match; source and branch accept isolated writes; branch release cannot delete source |
| Restore and undo | Maintained guarded `force-branch!` and commit roots exist | No quiescence, external intent, undo branch, attachment rebuild, completion fact, or crash-resume derivation | Kill after every restore boundary; restart derives next action, preserves undo head, rebuilds from promoted facts, then undo follows the same restore path |
| Branch-local blobs | Core blob facts are content addressed | No read-only source base plus branch-local writable overlay or restore materialization rule | Branch reads source blobs, writes only its overlay, release deletes only overlay, restore verifies every referenced blob before admission |
| Stale writer/cursor rejection | A full expected coordinate now fences writes; replay pages validate a frozen commit and complete cursor chain | Restore/reset admission and downstream turn/error/cache identity remain incomplete | Frames/requests from an old commit ancestry fail as typed stale-lineage data; reconnect after restore resets rather than numerically replays |
| Ordered multi-form process failure | Parser/eval records real forms sequentially and durable eval facts exist | Process death boundary lacks a destructive acceptance case proving no later result is fabricated | Kill after form N commits in a multi-form batch; exactly forms through N have results, later forms have none, reconstruction replays declarations only |

## Exact owners and ordered implementation slices

### Slice 1 — canonical coordinate and protocol lineage

- Add one `seon.db.coordinate` `.cljc` owner for the schema and pure
  projections. Use `database-id`, not the older `store-id` vocabulary.
- Project commit id through maintained `datahike.api/commit-id`. The commit
  pins an immutable containing database value and `t` selects a cut within it;
  never graph-search for a commit whose maximum transaction equals `t`.
- Replace database-name plus bare basis fields in transaction responses, replay
  pages, and events with logical routing data beside the canonical coordinate.
- Change replica attachment/progress, own-write correlations, replay validation,
  turn/error capture, and cache/bookmark keys together. A non-ancestor head is
  a reset, never a numeric replay.

Tests first: pure schema/selector tests, writer protocol validation, same-t
cross-branch collision, malformed partial coordinate, and replica stale-lineage
rejection. Live proof: JVM and CLJS report the exact same default head.

Implementation probing refined the coordinate law after this audit. Raw
Datahike commits may contain multiple temporal cuts, and concurrent raw writes
can share a commit root. Immediate transaction before/after coordinates must
therefore use the committed `db-after` as one container with distinct `t`
values. Recovery uses one frozen current commit after proving the receipt and
transaction datoms. Replay freezes one `through` commit, reloads that commit by
UUID on later pages, and expresses the cursor, events, and watermark as cuts
inside it. Parent traversal is valid only to prove that an initial cursor
commit is an ancestor of that frozen container, never to invent identity from
`t`. Datahike `as-of` remains a read filter and does not supply another commit
identity.

The first downstream dependency is now implemented. On CLJS,
`datahike.api/commit-as-db` is asynchronous because it reads Konserve; the
public `seon.db/at-coordinate` contract is therefore explicitly `^:async`.
It accepts only the current connection's complete database/branch attachment,
loads the requested commit directly, reprojects the requested coordinate with
`seon.db.coordinate/at`, and only then creates the temporal `as-of` wrapper.
Focused proof covers a t inside a later containing commit, wrong branch,
missing commit, partial coordinate, and out-of-range t (2 tests/11 assertions).
This resolver precedes removal of the t-only turn/error/cache fields; no
synchronous compatibility selector was introduced.

### Slice 2 — one schema/program candidate and fail-closed publication

- Fold protocol receipt declarations into the canonical Malli-derived native
  schema candidate; delete `receipt-schema` and `seed-receipt-schema!`.
- Validate native signatures, complete Malli references, program contracts,
  config lookup refs, and first facts before post-genesis mutation.
- Add one admission state derived from publication/readiness, not a duplicate
  lifecycle registry. A post-commit publication failure records one fault and
  stops work until exact committed reconstruction succeeds.
- Make config-free reopen consume only committed facts. Source/config overlays
  remain explicit operation inputs.

Tests first: incompatible native signature, unresolved reference, broken first
fact, publication failure after commit, converged no-write, and cold
reconstruction of that exact accepted generation. Live proof: destructive
fresh boot followed by config-free reopen with identical projection fingerprint.

### Slice 3 — branch-qualified registry and attachment

- Extend the existing `seon.db.registry` entry to one logical name plus stable
  `{database-id, branch}` attachment and current resolved head.
- Enforce a bijection between logical name and database/branch attachment.
- Route every request, feed, replay, runtime advertisement, and agent lookup
  through that attachment. Same agent ids on two branches are not ambiguous
  when the cluster/attachment is explicit.
- Define a non-autonomous forensic pod mode that installs reads and UI only;
  no ticker, wake trigger, host, schedule, provider synchronization, or external
  effect worker starts implicitly.

Tests first: duplicate attachment rejection, source/branch root routing,
cross-branch frame rejection, and config-free historical attach. Live proof:
simultaneous main and forensic branch views return distinct full coordinates.

### Slice 4 — native writable branch and physical-fork deletion

- Add the minimal branch operations under the existing `seon.db` JVM boundary
  and delegate storage behavior to maintained Datahike.
- Plan from a frozen source coordinate, close new admission, drain accepted
  writes, recheck the head, create the branch, and verify primary plus every
  enabled secondary index before publishing its attachment.
- Release all branch readers/writers before `delete-branch!`.
- Delete registry physical-fork code, its operator path, copy verification,
  whole-database branch destruction, and any blob-directory clone in the same
  slice.

Tests first: selected historical secondary parity, missing commit,
commit-graph-disabled source, active-connection delete rejection, and source
survival after branch release. Live proof: isolated write divergence and clean
source read-back.

### Slice 5 — clean quiescence and unexpected crash distinction

- Put the admission/drain transition in the one supervisor lifecycle, with
  turn-boundary acknowledgement as database facts/coordinates.
- Planned restart drains without crash marks. Unexpected restart invokes the
  existing recovery transaction before autonomous host reconstruction.
- Ensure writer release waits for pending work and every feed/listener is
  detached before branch or process teardown.

Tests first: restart during accepted work, TERM/KILL during a turn, committed
eval preserved, uncommitted result absent, and recovery idempotence. Live proof:
process kill plus datom/query read-back.

### Slice 6 — restore, undo, and branch-local blobs

- Persist one confirmed external lifecycle intent outside the branch being
  replaced. It records immutable inputs, not a procedural cursor.
- Quiesce, retain old main as an undo branch, verify the target and required
  blobs, guarded-force main, release stale handles, reconnect, reconstruct from
  committed facts, apply only explicit frozen overlays, and record the one
  completed-restore fact before admission.
- Undo is an ordinary restore to the retained undo coordinate.
- Add source-base plus branch-local blob overlay semantics and delete only the
  overlay on branch release.

Tests first: stale planned head, every crash boundary, missing blob, overlay
preserve/replace combinations, config-free post-restore reopen, and undo.
Live proof: destructive default-cluster restore and undo with full coordinate,
facts, program projection, and blob read-back.

### Slice 7 — ordered multi-form crash proof

- Drive a multi-form batch through the real runtime and kill the pod after a
  selected committed form.
- Reconstruct only declaration state from canonical facts; never rerun scratch
  forms or fabricate results for forms after the process boundary.
- Assert every persisted result corresponds to a real committed eval and the
  resumed namespace has exactly the declarations committed before death.

This slice can share the crash harness from Slice 5 but must remain a runtime
acceptance proof, not another eval runner.

## Required focused and live gates

- JVM: backend, registry routing, receipt recovery, replay, generated-id
  transaction, writer integration, transport UDS, branch/version, restore, and
  crash-boundary tests through `bin/test-writer`.
- CLJS: protocol envelopes, replica replay/live overlap, time travel,
  schema-divergence, boot candidate, eval tee/publication, resume, runtime
  recovery, turn/error coordinate, and multi-form interruption through
  `bin/test-cljs`.
- Operator: fresh reset, config apply/reopen, planned restart drain, unexpected
  kill, native branch create/release, restore, undo, and stale plan rejection.
- Live default only during implementation: exact CLJ/CLJS coordinate agreement,
  datom proof of recovery and restore completion, browser/read-only forensic
  branch proof, gzip feed lineage, and config-free restart. ACME coordination is
  downstream of default proof and outside this research lane.

## Uncertainties that require executable probes

- Whether the maintained Datahike branch roster plus commit graph needs an
  additional ancestry helper for unique `{branch,t}` resolution or whether the
  existing stored commit walk is sufficient at Seon's retention scale.
- Which secondary indexes will be enabled when branch work lands. Every enabled
  implementation needs historical-root parity; Proximum alone is not a
  sufficient generic claim.
- The exact external durable location and atomic write format for restore
  intent. It must survive replacement of the selected branch without becoming
  a second database history.
- Blob overlay layout and garbage-collection policy belong to the blob lifecycle
  design but block restore graduation.
- The current `reference-code/malli` working tree is not pinned to Maven
  `0.20.0`. The release tag is now locally available for exact reads, but the
  checkout policy should be aligned before later agents cite working-tree line
  numbers as selected-runtime evidence.
