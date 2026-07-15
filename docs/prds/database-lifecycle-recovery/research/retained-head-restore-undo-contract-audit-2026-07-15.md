---
type: research
status: completed
tags: [research, database, flow]
---

# Retained-head restore and undo contract audit — 2026-07-15

## Result

Restore and undo are one transition over exact retained branch heads. Datahike
already supplies the required primary semantics: a native branch created from a
commit retains that commit id and `t`; guarded force creates a new main commit
at the selected `t` whose sole parent is the selected commit. Undo therefore
does not reverse datoms. It selects the prior completion's retained undo head,
first retains the current main as a new redo head, and runs the same transition.

The current primitives correctly encode exact `H`, `T`, derived `U`/`P`, forced
`F`, completion `C`, immutable intent, blob proof, root/boot completion
provenance, and fact-derived retry. Current restore composition and the
Proximum/Datahike secondary-index repair remain hard predecessors of destructive
proof.

Commit `6351790a` closes the pure retained-head selection gap in the existing
`seon.dev.restore/derive-intent` boundary. Ordinary restore retains its exact
non-main descriptor contract. Undo additionally requires one frozen main/head/
roster/completion observation and an id-or-retained-branch selector. The
function resolves exactly one completion, derives `T2` only from its database
id, undo branch, source commit, and source `t`, proves that head still exists,
and derives `U2` from the actual latest observed main. It rejects arbitrary
valid branches, crossed databases or lineage, duplicate claims, a previously
consumed completion, advanced heads, stale rosters, and pre-existing reserved
branches before producing the unchanged immutable intent shape. No effect path
or public restore command was added.

Focused Babashka proof passes 10 tests/72 assertions. The adjacent retained
writer-admin consumer passes 9 tests/53 assertions, proving that restore and
completion-bound undo still feed the same intent/admin/command mechanism. This
pure proof does not authorize destructive use: the selected Proximum/Datahike
force-secondary dependency cutover and integrated restore proof remain hard
predecessors.

## Dependency ledger

| Dependency or mechanism | Selected identity | Exact source grounding | Constraint |
|---|---|---|---|
| Datahike | `org.replikativ/datahike` `417649383c65e13f15ea41d394fb1ed742477965` | `deps.edn`; exact selected source in `reference-code/datahike/src/datahike/versioning.cljc` at `branch!`, `force-branch!`, `commit-as-db`, and `branch-as-db` | `branch!` copies the selected stored database under a new branch key without changing commit id or `t`. `force-branch!` requires exclusive access, checks the expected destination head twice, writes immutable values before the mutable head, creates a new commit with resolved parents, and verifies read-back. |
| Konserve | `org.replikativ/konserve` `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve/src/konserve/core.cljc` at `multi-assoc`; `protocols.cljc` | Ordered leaves-before-root publication is crash-safe, but no operation joins external intent, blobs, secondary storage, and a Datahike head atomically. Durable intent plus derived retry remains necessary. |
| Proximum | released `0.1.25`, source tag `5f7142d532aa173071f5651af91414b983d7320f` | `deps.edn`; `reference-code/proximum/src/proximum/{versioning,writing,vectors}.clj` | Released branch creation rejects an existing destination and branch-derived mmap identity is not a safe force replacement. The guarded existing-destination primitive and Datahike integration must land before main force can graduate with secondary roots. |
| Exact Seon coordinates | current `seon.db.coordinate` and writer registry | `src/seon/db/registry.clj:694-830`, `:952-1087` | A source is branchable only when its requested `t` equals the containing commit head. Registry create/adopt and restore admin fence complete coordinates, roster, primary data, secondary roots, and release. |
| Immutable restore intent | current `seon.dev.restore` | `src/seon/dev/restore.clj`; `test/seon/dev/restore_test.clj`; commit `6351790a` | Intent freezes exact main and target descriptors, expected complete roster, new reserved branches, artifact/protocol/generations, blob-set digest, and preserve-only overlay policy. Undo selection compiles one exact prior completion observation into that same shape; it stores no phase or copied completion proof. |
| Blob materialization | current `my.blob` internal restore boundary | `src/my/blob.cljs`; [[restore-blob-and-cold-reconstruction-contract-2026-07-15]] | Reachable hashes come from exact `T`, target lookup is overlay-first, and verified bytes become directory-durable in the append-only main archive before force. |
| Completion and admission | current `seon.db.restore` plus split admission | `src/seon/db/restore.cljc`; `src/seon/runtime/admission.cljs`; [[restore-completion-transaction-coordinate-2026-07-15]] | Completion is one root/boot-provenanced transaction before exact-generation admission. Equal retry resolves its original transaction coordinate without a write. |

The protected `reference-code/datahike` checkout was at test-only
`eb3e2239b650635977fdc8e73e7c657b23bf3383` during this audit. Dependency
claims above were checked against the selected `417649...` source and the
`:writer` dependency basis rather than treating that checkout head as a new pin.

## Executable falsifier

A disposable `:memory` Datahike database on the selected `:writer` basis ran
two guarded transitions. It created retained `old` and `redo` branches, forced
main to `old`, then forced main back to `redo`.

Observed coordinates:

- old source and retained old branch both had commit
  `6a579d8e-66d6-5686-bff5-b1ffffad758e`, `t=536870913`;
- current source and retained redo branch both had commit
  `6a579d8e-77ef-5523-9daf-c977c7591cbb`, `t=536870914`;
- first force created main commit
  `6a579d8e-bf81-56d1-a2c4-3a191ae5cbe5`, `t=536870913`, with sole parent the
  old source commit; and
- second force created main commit
  `6a579d8e-3102-5088-8371-42e077a9282a`, `t=536870914`, with sole parent the
  retained redo commit.

The roster remained `#{:db :probe/old :probe/redo}`. This proves the coordinate
and inverse model. It does not close the known file-backed Proximum secondary
falsifier; destructive restore remains forbidden until that separate proof is
green.

## Exact coordinate model

For one restore operation `R`:

- `H = {D, :db, h, th}` is the current main head frozen at confirmation;
- `T = {D, bt, t, tt}` is an exact retained non-main branch head;
- `U = {D, bu(R), h, th}` is the undo alias retained from `H`;
- `P = {D, bp(R), t, tt}` is the prepared target alias retained from `T`;
- `F = {D, :db, f, tt}` is the new main commit created by force, with
  `parents(F) = #{t}`; and
- `C = {D, :db, c, tc}` is the completion transaction's containing coordinate,
  after any frozen reconstruction writes and before admission.

`D` never changes. `U` and `P` keep their selected commit ids and transaction
cuts; they are not new transaction commits. `F` is never represented by changing
only `T.branch`: it has a distinct commit id and main attachment. `F.t` may
repeat `T.t`, which is why transaction-coordinate resolution must skip force
metadata commits when locating a later completion transaction.

For undo operation `R2`, let the current live head at confirmation be
`H2 = {D, :db, h2, th2}`. `H2` is the actual current head, not the older
completion coordinate `C`; ordinary work after restore must be retained. Let
the selected target be the prior restore's exact undo head
`T2 = {D, bu(R), h, th}`. The same plan creates:

- `U2 = {D, bu(R2), h2, th2}`, the redo point;
- `P2 = {D, bp(R2), h, th}`, the prepared prior value;
- `F2 = {D, :db, f2, th}` with `parents(F2) = #{h}`; and
- `C2`, a distinct root/boot completion transaction before admission.

No `undo` protocol or reverse-datom compiler is needed.

## Exact retained-target selector

The restore planner accepts an explicitly selected retained branch at its live
exact head. The undo planner is stricter and derives its target from durable
completion data:

1. Select one completed restore id, or select an undo branch and resolve the
   unique completion fact that recorded it.
2. Read one frozen main database value and the completion entity. Derive the
   expected target coordinate from the completion's `database-id`,
   `undo-branch`, `from-commit-id`, and `from-t`.
3. Ask the writer for the current complete main head, full branch roster, and
   exact retained undo head. Require the retained head to equal that derived
   coordinate; absence or advancement is divergence.
4. Derive a non-autonomous target launch descriptor through the existing branch
   descriptor owner. It must bind the same writer artifact, physical store,
   exact attachment/head, branch overlay, and main archive base.
5. Freeze a new operation id, actual `H2`, exact `T2`, full post-preparation
   roster, process generations, reachable-blob digest, and preserve-only overlay
   selection into the ordinary immutable intent.

The operation keyword is an audit/CLI distinction; storage semantics remain
identical. It is not authority to substitute an arbitrary descriptor for `T2`.

## State transitions and retry

| Durable facts | Meaning | Only safe next effect |
|---|---|---|
| No intent | No confirmed operation | Resolve/plan only. |
| Intent; main=`H`; `U` absent | Confirmed, undo/redo point not retained | Create or exactly adopt `U` from `H`; read back exact coordinate and secondary roots. |
| Main=`H`; exact `U`; `P` absent | Target preparation incomplete | Create or exactly adopt `P` from `T`; read back exact coordinate and secondary roots. |
| Main=`H`; exact `U/P` | Prepared | Reprove target, blobs, complete roster, and consumer drain; then invoke admin once. |
| Main=`H`; any preflight/release uncertainty | Not exclusive | Retain intent and branches; do not invoke force. |
| Main exact desired `F`; no completion | Force committed, reconstruction incomplete | Fresh attach with admission closed; reprove admin/blob evidence, reconstruct, and record completion. Never force again. |
| Completion exists; intent remains | Database transition durable | Resolve original completion coordinate, reconstruct disposable runtime state, prove readiness/admission, then durably remove intent. |
| Completion exists; intent absent | Complete | Ordinary config-free boot. |
| Any reserved head differs, main is neither `H` nor exact desired `F`, or completion payload differs | Divergence | Keep maintenance and intent; diagnose without guessing. |

Retry derives from these immutable facts. It never stores phase, retry count,
or “undo available.” The retained branch and completion attributes are the
availability evidence.

## Provenance

- Existing restored datoms retain their original transaction ids and original
  `:seon.db/user`, `:seon.db/process`, and `:db/txInstant` facts. Restore does
  not rewrite them.
- Native branch aliasing and the forced root commit are storage-history
  operations, not ordinary domain transactions. The forced commit's exact id,
  selected parent, writer artifact, admin result, and external intent provide
  lifecycle evidence; no fabricated transaction entity is added for force.
- The completion entity is transacted under the root database user and
  `:seon.db.process/boot`. Its transaction supplies the completion time and
  provenance. The entity records `H`, `T`, `F`, `U`, and `P`, not copied
  created-at/by fields.
- Undo writes a second completion fact under the same provenance rules. Its
  `from-*` attributes record `H2`; `to-*` record the prior retained `U`; its
  `undo-branch` records the new redo point `U2`.
- Human confirmation is external operator authority bound to the canonical
  intent bytes and plan digest. Public CLI work must settle that confirmation
  envelope before exposure; it must not add provenance projections to domain
  entities or infer confirmation from an operation keyword.

## Branch and blob retention

Reserved `U`/`P` branches are immutable by contract. Datahike does not prevent a
caller from opening and writing them, so every retry reads their exact heads and
fails closed on movement. No autonomous pod, ticker, wake trigger, or scheduler
may attach to a reserved branch.

The append-only main blob archive is deliberately outside branch-root force.
Before each restore or undo, derive `B(T)` from the exact retained database,
verify target overlay before inherited bases, and materialize every member into
the main archive. Old archive members remain valid immutable content and make
the prior `U` reconstructable, but presence alone is never proof: every selected
hash is read and verified.

Do not auto-delete `U`, `P`, or a prior selected retained branch at completion.
`U` is the inverse and `P` is forensic evidence for the transition. Explicit
release later must stop any branch pod, release exact routes/connections, delete
with the exact current-head fence, and prove roster absence. Completion facts
and their transaction provenance remain. Garbage collection must treat every
roster branch as a retention root and is a later policy boundary.

## Crash cuts and acceptance tests

Focused proof must include both restore and undo for every row:

| Cut or falsifier | Required observation |
|---|---|
| Intent publication tears | Either prior complete intent or new complete intent; no branch/main effect from partial bytes. |
| Crash before/after `U` or `P` creation | Retry adopts only the exact coordinate; a differently moved reserved head is divergence. |
| Target is an interior `as-of` cut | Closed `cut-not-branchable`; no branch, blob, process, or main mutation. |
| Target/undo branch absent or advanced | Closed retained-head mismatch; never substitute its containing commit or latest head. |
| Missing/corrupt target or main blob | Force is never invoked; verified prefix publication is harmless and retryable. |
| Consumer or writer absence uncertain | Admin does not start; intent remains. |
| Crash before force head flip | Main stays `H`; immutable orphans are harmless; retry may force after complete reproof. |
| Response loss after force | Storage proves exact desired `F`; retry returns already-applied with zero second force calls. |
| Crash after `F`, before completion | Fresh reconstruction records the one completion; no old handle/cursor/cache crosses force. |
| Crash after completion, before admission/intent deletion | Equal completion resolves original coordinate with zero write; runtime rebuilds and admits exact prepared generation. |
| Ordinary writes after first completion, before undo | New `U2` equals actual latest `H2`, proving redo retains those writes rather than only old `C`. |
| Undo selector names unrelated branch | Rejected before intent publication even when the branch is a valid exact retained head. |
| Undo succeeds | Original facts/program/blob projections return; new redo `U2` remains; two distinct completion facts have correct root/boot provenance. |
| Config-free reopen after each direction | Writer/replica/completion coordinates agree; feeds, browser, and agent admission reconstruct without ambient overlays. |
| File-backed Proximum secondary fixture | Target, forced main, undo, and redo have equal primary EAVT, declared secondary identifiers/roots, KNN results, and reopen/write isolation. |

The live sequence uses an isolated named cluster first. It writes distinct
database facts and blob projections on both sides, restores, performs ordinary
post-restore work, then undoes. It proves `H/T/U/P/F/C` followed by
`H2/T2/U2/P2/F2/C2`, restarts config-free in both directions, and cleans only
proof-owned branch pods and explicitly released prepared branches. Default proof
waits for a source freeze and the full database-lifecycle graduation gate.

## Dependency order

1. Finish the guarded Proximum existing-destination replacement and consume it
   through Datahike's one `force-branch!`; retain the real file-backed root/KNN
   proof.
2. Finish current restore cold composition: validate startup identity plus
   exact admin/blob evidence, attach fresh with writes closed, preserve-only
   reconstruct, record completion, admit the exact generation, prove readiness,
   and durably remove intent.
3. The pure retained-head planner for ordinary restore is complete; compose its
   exact branch-status observations into the operator only after the selected
   dependency cutover admits destructive proof.
4. The pure undo selector is complete at `6351790a` as a stricter producer of
   the same intent. The later operator must query its frozen completion/head
   observation through the existing writer boundary and may not fork the state
   machine or writer operation.
5. Run focused crash cuts, isolated destructive restore/undo, then the
   coordinated default source-frozen REPL/datom/MCP/browser/config-free restart
   checkpoint.

The first restore composition is the dependency spine. The undo selector can be
implemented only after the completion payload and retained target descriptor
consumed by that spine are stable; its test/proof design is otherwise
source-disjoint and ready now.
