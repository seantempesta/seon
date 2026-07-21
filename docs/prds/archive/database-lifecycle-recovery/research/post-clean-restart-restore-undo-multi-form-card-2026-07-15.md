---
type: research
status: completed
tags: [research, database, flow, pod]
---

# Post-clean-restart restore, undo, and multi-form card — 2026-07-15

## Result

Restore and undo are not ready to implement ahead of the clean-or-force restart
coordinator. Existing native branch, closed coordinate, replica, blob-view,
runtime-admission, and process-containment work supplies their prerequisites,
but Seon still lacks the durable restore transition that joins them.

The first restore implementation must accept only a retained branch at a
branchable commit-head coordinate. A Datahike `as-of` value selects an interior
temporal cut for reads; it is not a concrete branch store that can be passed to
`force-branch!`. An interior-cut restore therefore fails explicitly as
`cut-not-branchable` until a separately specified materialization mechanism
exists. It must never round the requested cut to a containing commit.

The ordered multi-form failure proof in this unit is narrower than the durable
per-form execution protocol owned by the later agentic-refinement unit. This
unit proves that, for a real three-form turn, a committed first form remains
real, a second unresolved form interrupted by process death is recovered as
interrupted ownership, and the third form is absent. It must not fabricate an
eval, result, or replay. Durable positioned running receipts and child-process
containment remain the later unit's contract.

No production source or live pod was changed while preparing this card.

## Reconciled current boundary

### Already built

- `seon.db.coordinate` carries one closed database, branch, commit, and `t`
  value through writer, feed, replica, receipt, turn, and historical-read paths.
- The maintained Datahike fork exposes same-store branch creation/deletion,
  awaited connection release, commit/branch root reads, and guarded,
  read-back-verified `force-branch!`.
- The one writer registry and typed protocol create, adopt, release, and delete
  native branches. The physical-copy branch path is gone.
- A branch-qualified non-autonomous pod consumes an immutable launch descriptor,
  exact replica attachment, source writer, and overlay-plus-bases blob view.
- Runtime admission can close to `:quiescing`; the pod exposes a loopback-only
  drain action; the writer returns exact stop/release evidence in-process; and
  containment retains generation-bound process evidence.
- Cold boot reconstructs the committed program and repairs orphaned run/turn
  ownership without replaying arbitrary eval effects.

### Not built

- The operator does not yet perform one clean-or-force coordinated stop with a
  consumed, generation-bound writer terminal result. That is the immediate
  predecessor and remains the earliest unsettled contract.
- There is no immutable, fsync-durable restore intent or completion record.
- There is no restore protocol operation, exclusive admin-writer invocation,
  source-to-main blob materializer, or Seon caller of `force-branch!`.
- Startup does not reconstruct from a pending restore intent before reopening
  admission. Ordinary autonomous boot still reconciles current core/config.
- `bin/seon` exposes neither restore nor undo.
- There is no database completion fact binding intent, pre-restore undo branch,
  selected target, reconstructed coordinate, and program generation.
- General durable multi-form position remains open in
  `docs/seon/issues/multi-form-eval-order-is-not-durable.md`; the current eval
  path records an eval after the form settles.

## Dependency ledger

| Dependency or owner | Selected identity | Exact source grounding | Constraint used by this card |
|---|---|---|---|
| Datahike | `org.replikativ/datahike` `417649383c65e13f15ea41d394fb1ed742477965` | `reference-code/datahike/src/datahike/{api,connector,writer,versioning}.cljc` | `force-branch!` requires exclusive access and a concrete branch database value, checks the expected head, forces the branch root, and verifies read-back. `as-of` is a read wrapper, not a branch store. |
| Konserve | `org.replikativ/konserve` `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve/src/konserve/{core,protocols}.cljc` | Atomic key updates do not make an operator intent durable across a separate process/storage transition. The intent file needs file and parent-directory synchronization. |
| Writer lifecycle | `seon.db.server`, `seon.db.writer`, `seon.db.registry`, `seon.db.protocol` | `src/seon/db/{server,writer,registry,protocol}.clj*` | Restore strengthens the one writer artifact and registry. It does not add a second service, registry, or transport-specific meaning. |
| Operator lifecycle | `seon.dev.cli`, `seon.dev.process`, `seon.dev.state` | `script/seon/dev/{cli,process,state}.clj` | The one cluster lock and exact managed generations own stop, admin invocation, replacement, and retry. Atomic rename alone is not crash-durable intent publication. |
| Reconstruction | `seon.client`, `seon.runtime.admission`, `seon.runtime.recovery` | `src/seon/{client,runtime/admission,runtime/recovery}.cljs` | Recovery must run after force and before normal work, close prior interrupted ownership honestly, and publish completion before admission opens. |
| Blob archive | `my.blob` and descriptor-selected storage view | `src/my/blob.cljs`, `src/seon/launch.cljc` | Reachable target hashes are derived from the selected database. Missing verified bytes are copied from ordered bases to the main archive before the database head is forced. |
| Eval/turn ownership | `seon.eval`, `seon.eval.internal`, `seon.agent.turn`, `seon.agent.run` | `src/seon/{eval,eval/internal}.cljs`, `src/seon/agent/{turn,run}.cljs` | Unit 1 observes actual commit/absence and existing crash repair. It does not claim the later positioned receipt protocol is complete. |

## Shortest falsifiers

### Interior `as-of` is not branchable

A disposable in-memory Datahike probe created a database, transacted more than
one temporal cut, selected an earlier `as-of` value, and attempted to use that
value as the target value for `force-branch!`. The wrapper still reported its
origin containing commit/max transaction, but it had no concrete store for the
branch mutation; the call failed while resolving store capabilities. This
falsifies the tempting design that equates exact historical read resolution
with a valid restore source.

Required regression: select a coordinate whose `t` is below its containing
commit head and assert a structured `cut-not-branchable` result with no main
head, branch roster, blob, or lifecycle-record change.

### Stop proof cannot be inferred from absence

Kill or withhold the writer's terminal application result after the generation
is proven absent. The coordinator must classify the transition as forced
recovery, not clean. If subtree containment itself is uncertain, it must retain
the generation and block replacement and restore.

### Blob reachability cannot follow only new writes

Put a referenced hash only in a target branch base, leave it absent from the
main writable archive, then attempt restore. Promotion must either verify and
materialize those exact bytes before force or fail without moving the head.

### No fabricated suffix after process failure

Run three forms where form zero commits a unique fact, form one returns a never-
settling promise, and form two would transact another unique fact. Terminate the
pod after form one starts, then recover. Form zero's fact remains; form two's
fact and eval are absent; the interrupted turn/run are repaired; no synthetic
result or replay appears for either unfinished form.

## Restore contract

### Immutable intent

Under the existing cluster lifecycle lock, derive and durably publish one closed
intent before stopping consumers. It contains:

- a stable intent id and operation (`restore` or `undo`);
- target main attachment and expected current complete coordinate;
- selected retained branch attachment and exact branch-head coordinate;
- a newly retained undo-branch identity and its expected creation coordinate;
- the selected launch/artifact flavor and every consumer pod generation;
- the target blob view and derived reachable-hash digest; and
- phase plus exact inverse evidence, derived from immutable facts rather than
  a parallel mutable state machine.

Publishing the intent requires temporary-file write, file fsync, atomic rename,
and parent-directory fsync. A crash at every publication cut must yield either
the old complete intent or the new complete intent.

### Preparation while reads are available

Before exclusive writer shutdown:

1. Resolve the retained target branch and prove its current coordinate exactly
   matches the intent and is a branch head.
2. Create or exactly adopt the undo branch at the current main head.
3. Query the selected target database for reachable `:my.blob/hash` values.
4. Verify overlay-to-base bytes by digest and materialize missing bytes into the
   main archive using the existing durable blob publication operation.
5. Re-read target, undo, and main coordinates. Any drift invalidates the intent
   before force.

### Exclusive transition

All pods that consume the selected source writer, including sibling branch
pods, must stop cleanly before the writer becomes exclusive. A forced pod stop
is acceptable only as crash recovery before a newly planned restore; it is not
promotion proof. Uncertain containment blocks the transition.

The clean-or-force coordinator then stops pod consumers, writer, and watcher in
the already specified order and consumes the exact generation-bound results.
A short-lived admin mode of the existing writer artifact opens no UDS listener,
accepts one validated immutable intent, reconnects the intended attachments,
rechecks every expected coordinate and roster fact, invokes guarded Datahike
`force-branch!`, verifies the new main head, and exits with a closed result.

### Fresh reconstruction and completion

The replacement writer and pod start from the forced main branch. While runtime
admission remains closed, startup consumes the pending intent as a one-shot
reconstruction input, runs existing unexpected-ownership recovery, rebuilds the
committed program from the restored facts, and applies only the config/core
policy explicitly frozen by that restore contract. It must not silently overlay
whatever source/config happens to be current on disk.

Before admission opens, transact the architecture-defined completion fact that
binds the intent id, pre-restore coordinate, retained undo branch, target
coordinate, forced commit, and only committed overlay digests. Its transaction
coordinate is the actual restored completion point. The same closed transition
proves the process-local reconstructed generation and the intent's reachable-
blob digest before opening; it does not persist either as an invented durable
identity. The operator then durably records completion. Retry derives
convergence from those facts and the current head; it never repeats force on an
assumed phase. Exact grounding is in
[[research/restore-blob-and-cold-reconstruction-contract-2026-07-15]].

### Undo

Undo is the same operation with the prior retained undo branch selected as the
target. It creates a fresh retained branch for the state being left, so repeated
undo/redo-style transitions remain explicit restore operations rather than an
in-place mutable history pointer.

## Implementation slices and path ownership

The slices are ordered. Only source-disjoint proof work may run beside the
critical path.

1. **Clean-or-force predecessor:** finish generation-bound pod/writer terminal
   evidence and the one coordinator. Restore work does not bypass this gate.
2. **Intent and command derivation:** add the one durable restore record owner,
   fsync publication, closed CLI request/response, and retry derivation under
   the existing cluster lock. Keep semantic storage behavior out of CLI code.
3. **Writer admin transition:** add one typed restore request/value and a
   no-listener admin entry through existing protocol/writer/registry owners.
   It performs exact preflight, force, and read-back; no new daemon exists.
4. **Blob preparation:** derive target reachability and materialize verified
   bytes through `my.blob`'s existing storage-view semantics before force.
5. **Restore-aware cold reconstruction:** consume the pending intent while
   admission is closed, run existing recovery and program reconstruction, then
   transact the completion fact before opening work.
6. **Undo and crash matrix:** reuse the same transition against the retained
   undo branch and test every durable-intent, stop, force, completion, and
   operator-publication cut.
7. **Ordered multi-form failure proof:** add the three-form destructive fixture
   against the real pod/process boundary. Record only current eval/recovery
   evidence; hand general durable per-form position to agentic refinement.

Shared owners such as `seon.db.protocol`, `seon.client`, `my.blob`, and operator
lifecycle files each have one implementation lane at a time. Architecture and
this roadmap remain top-level integration owners.

## Evidence and graduation for this boundary

Focused proof must cover:

- intent durability and idempotent retry across every file-publication cut;
- rejection of partial coordinates, interior cuts, stale main/target heads,
  missing roster branches, corrupt/missing blobs, and uncertain containment;
- exact pod-consumer drain and writer terminal-result consumption;
- admin mode with no listener and guarded force read-back;
- crash before force, after force, before database completion, and before
  operator completion, each converging without a second force or hidden replay;
- config-free restored startup and admission remaining closed until program,
  recovery, blob, and completion evidence agree;
- undo using the same transition and retaining the state it leaves; and
- the real three-form process-failure observations defined above.

The live destructive checkpoint starts only after the clean-or-force restart
matrix is green. It uses an isolated named cluster first, then the coordinated
default checkpoint: create target and undo branches, write distinct database
and blob facts, restore, restart config-free, verify CLJ/CLJS complete
coordinates and datoms, undo, and repeat the same reads. The final program gate
remains the complete database-lifecycle transition matrix in the roadmap.
