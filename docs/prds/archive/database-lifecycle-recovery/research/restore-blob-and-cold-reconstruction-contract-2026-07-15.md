---
type: research
status: completed
tags: [research, database, flow, pod]
---

# Restore blob and cold-reconstruction contract — 2026-07-15

## Result

Restore Slices 4 and 5 strengthen two existing owners; they do not introduce a
blob copier or a restore replay path.

Slice 4 derives one canonical retained-hash set from the exact target database
value, verifies every member through `my.blob`'s selected overlay-to-base view,
and materializes verified content into the main writable archive before guarded
force. The set is database data, not a directory listing. An overlay orphan
with no `:my.blob/hash` projection is never copied.

Slice 5 fresh-connects the forced main branch with admission already closed,
runs the ordinary unexpected-ownership repair and one committed-program
publication, records the architecture-defined restore completion fact, and
only then opens executable admission. It retains the existing `:publishing`
state across program verification and completion; it adds no restore phase.

Two current source gaps block those contracts:

- `my.blob/publish!` fsyncs the temporary file but does not fsync the shard
  directory after atomic rename, so materialization is not yet a crash-durable
  prerequisite for force; and
- `admission/publish-committed!` admits immediately after wrapper verification,
  so it has no in-place seam for the required completion transaction.

No production source or pod was changed for this audit.

## Dependency ledger

| Dependency or owner | Selected identity | Exact grounding | Constraint |
|---|---|---|---|
| Datahike | `org.replikativ/datahike` `417649383c65e13f15ea41d394fb1ed742477965` | exact commit versions of `reference-code/datahike/src/datahike/{api,versioning}.cljc`; `src/seon/db.cljs` | A retained commit/branch yields an immutable db value. Datalog is pure over that value. `force-branch!` requires external exclusivity, checks the expected main commit before and during its final head update, publishes nodes before the mutable head, and verifies head read-back. |
| Konserve | `org.replikativ/konserve` `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve/src/konserve/filestore.clj` at the selected commit | Durable publication uses file synchronization, atomic move, and directory synchronization. Konserve cannot atomically join Datahike and Seon's separate blob archive. |
| Malli | `metosin/malli` `0.20.0` | `deps.edn`; exact tag commit `4c054bd7d042e70d60b83b9f07fb765bc103037f` in `reference-code/malli` | Restore request, completion, blob-view, and publication values remain closed registered schemas. The active projection fingerprint is process-local admission data, not a durable program identity. |
| Blob archive | current repository source | `src/my/blob.cljs`, `src/my/blob/schema.cljc`, `src/seon/launch.cljc`, `test/my/blob_test.cljs` | One process-local view has one writable directory plus ordered read-only bases. Reads stop at the first existing pathname and verify SHA-256. Writes use the one `publish!` operation. |
| Canonical database API | current repository source | `src/seon/db.cljs` | `db/at-coordinate` resolves one complete retained value. `db/installed-schema` gates a query that names an optional lazily installed attribute. All reachability reads use `seon.db`, never `datahike.api` directly. |
| Program publication | current repository source | `src/seon/runtime/admission.cljs`, `src/seon/schema.cljc`, `src/seon/instrument.cljc` | One committed projection is built from database schema/function facts, reconciled against live wrappers, activated, and admitted. Restore must extend this transition rather than reconstruct a second registry. |
| Cold runtime | current repository source | `src/seon/client.cljs`, `src/seon/runtime/recovery.cljs`, `src/seon/db/replica.cljs` | Existing cold start attaches, seeds autonomous inputs, repairs interrupted ownership, reconstructs program state, resumes agents, and starts surfaces. Restore must select frozen inputs and change order without adding another boot entry. |
| Slice 2 intent | current shared-tree implementation pending integration | `script/seon/dev/restore.clj`; contract in `post-clean-restart-restore-undo-multi-form-card-2026-07-15.md` | Supplies `:seon.dev.restore/intent-id`, operation/database name, exact source/target and derived undo/prepared-target coordinates, artifact flavor, consumer generations, one blob storage view, reachable-hash digest, and plan digest. The blob-view and completion-identity gaps below must settle before Slice 4 consumes it. |
| Slice 3 admin result | not implemented | same card and `crash-replacement-restore-undo-proof-handoff-2026-07-15.md` | Supplies the guarded-force result `F`, selected target ancestry, and exclusive no-listener release/read-back evidence. Reconstruction consumes that closed result; it does not infer success from process exit. |

The working `reference-code/datahike` checkout is ahead of the selected
dependency. Claims above were checked with `git show` at the exact selected
SHA, not inferred from its working tree.

## Slice 4 — retained target blob contract

### Retention roots

The authoritative input is one exact immutable target database value resolved
from Slice 2's complete `T = {database-id, branch, commit-id, t}`. `T` must be
the retained branch head accepted by the restore contract; Slice 4 neither
rounds an interior cut nor chooses another commit.

If `:my.blob/hash` is not installed on that value, the retained set is empty.
Otherwise derive it with the equivalent of:

```clojure
[:find [?hash ...]
 :where
 [?blob :my.blob/hash ?hash]]
```

Validate every result as the existing lowercase 64-hex `:my.blob/hash` shape,
deduplicate, and sort lexicographically into one vector `B(T)`. The projection
entity is itself the durable retention root. Do not inspect incoming ref attrs:
agent-authored schemas can retain hashes as values, and the append-only policy
already promises every projected blob in the selected database. Do not list
files: an archive pathname without a target projection is an unreachable orphan.

### Canonical set digest

The digest is SHA-256 over the UTF-8 bytes of the canonical EDN encoding
`(pr-str B(T))`. Sorting and vector encoding make the same set byte-identical
across retry. Every member hash independently commits its content bytes, so the
set digest commits both membership and order-independent identity without
rehashing concatenated blob bodies.

Slice 2 now owns this as `:seon.dev.restore/reachable-hash-digest`. Slice 4
re-derives `B(T)` from the retained value and requires its digest to equal that
frozen value before any materialization. It returns the same digest and count
as evidence; it does not write a second manifest or a database roster. The
current Slice 2 schema validates only the 64-hex shape, so Slice 4 tests must
pin this canonical derivation rather than accept an arbitrary caller digest.

### Slice 2 blob-view dependency

The current draft intent carries one `:seon.dev.restore/blob-storage-view`.
One view cannot unambiguously express both required roles:

- the exact target source view must search the target branch overlay before
  its inherited bases so a corrupt overlay cannot be hidden by a valid main
  copy; and
- the destination is the ordinary main writable archive, which is not the
  target overlay.

The draft test value instead places main in `:my.blob/writable-dir` and a branch
directory in `:my.blob/read-only-dirs`, causing the existing resolver to search
main first. Before Slice 4 implementation, Slice 2 must settle either two exact
validated views or the exact target source view plus a separately derived main
writable directory/ordinary launch descriptor. Slice 4 must consume that
settled schema; it must not reinterpret one vector order differently from
`my.blob/get`.

### Verification and materialization

For each hash in `B(T)`, in canonical order:

1. Validate the Slice 2 source storage view with the existing
   `:my.blob/storage-view` schema.
2. Resolve the hash through the existing `my.blob` overlay-first search. The
   first existing pathname is authoritative: hash mismatch is corruption and
   must not fall through to a lower base.
3. If no source exists or digest verification fails, return a closed failure
   naming the hash, actual digest when available, and searched directories.
   Materialization may have copied prior immutable members, but force remains
   forbidden.
4. Resolve the main archive destination from Slice 2's selected ordinary launch
   descriptor. Never derive it from the target branch's writable overlay or an
   ambient `SEON_CLUSTER_DIR`.
5. If the destination exists, verify it through the same digest path. A valid
   destination is converged. A corrupt destination may be replaced only from
   the already verified source content; it must never be accepted by presence.
6. Publish missing/repaired content through the same `my.blob` publication
   helper: unique same-shard temporary file, complete write, file fsync, atomic
   rename, then shard-directory fsync. Read and hash the final destination
   before reporting that member materialized.

The current archive stores and hashes UTF-8 strings. Slice 4 preserves that
implemented contract; generalized binary byte storage is a separate blob design
and must not be smuggled into restore.

The operation is idempotent. A crash before force can leave only verified
content-addressed files or reclaimable temporary files. Retry re-derives the
same target set, verifies already published destinations, and copies only
missing members. It never transacts `:my.blob/*`: those projections already
come from `T` and arrive on main through Datahike force.

### Slice 4 result

The eventual internal result needs closed success and failure variants with:

- the exact target coordinate consumed from Slice 2;
- the canonical set digest and count;
- verified, newly materialized, and repaired counts;
- on failure, the exact hash, searched source paths, destination path, expected
  digest, optional actual digest, and operation; and
- confirmation that every final destination was read back and verified.

Names and nesting belong to the implementation owner after Slice 2 settles its
shared intent schema. Do not publish an agent-facing copier or expose filesystem
paths in ordinary toolkit responses.

## Slice 5 — restore-aware cold reconstruction

### Inputs consumed, not re-derived

| Input | Owning predecessor | Slice 5 use |
|---|---|---|
| `:seon.dev.restore/intent-id`, `source-coordinate`, and `target-coordinate` | Slice 2 intent | Identify the operation and exact pre-restore `H` / selected `T`; reject a different pending operation. The identity mismatch below must settle before completion lookup. |
| Frozen core/config overlay selection and digests | Slice 2 contract, absent from its current v1 draft | Apply only explicitly selected candidates through the existing canonical reconciliation owner. If v1 intentionally supports preserve-only, record that constraint and reject overlays. Never read current source/config as an implicit restore input. |
| `artifact-flavor`, `consumer-generations`, and `plan-digest` | Slice 2 intent | Reject a runtime/process set that cannot interpret the confirmed transition. Protocol/artifact details committed by `plan-digest` must remain available as validated plan inputs, not be guessed from the digest alone. |
| Settled target source view and main destination view | Slice 2 intent/ordinary launch descriptor | Claim the exact main blob view and re-prove Slice 4. The current single `blob-storage-view` is insufficient until its role/order is settled. |
| `undo-coordinate` and `prepared-target-coordinate` | Slice 2 intent plus branch preparation | Populate the architecture-defined completion fact after exact read-back. |
| Exact forced-main `F` and target-parent/read-back proof | Slice 3 admin result | Require the fresh writer/main attachment to equal the guarded result and descend from selected `T`. Never infer force success from exit code or current-head resemblance. |
| `B(T)` digest and successful main-archive verification | Slice 4 result checked against Slice 2 | Prevent completion and admission when any restored projection lacks verified content. |

Slice 2's current fields are named above; its blob-view, overlay, and identity
relationships remain unsettled. Slice 3's result schema does not exist yet.
Slice 5 must require the final closed values from those owners and must not
accept a loose map or create a second interpretation.

### One ordered cold transition

The exact transition is:

1. The pod claims its immutable launch descriptor and main blob view before
   database, blob, or runtime effects. Admission remains its existing
   `:starting`/closed value.
2. Fresh-connect the ordinary writer and main replica. No prior connection,
   db value, feed cursor, listener, wrapper, compiler cache, or runtime host
   crosses force.
3. Read and validate the pending Slice 2 intent and Slice 3 result. Require the
   attachment, database id, branch `:db`, forced commit/t, target ancestry,
   artifact identity, and current writer/replica coordinate to agree.
4. Re-derive `B(T)` from the retained target and its digest, then verify every
   member in the main archive. This is verification of Slice 4's prerequisite,
   not a second materialization pass after force.
5. Apply only frozen selected overlays through the existing state/core/config
   candidate and reconciliation mechanisms. With no selected overlay, emit no
   ambient boot/config transaction. Any committed overlay advances main from
   `F`; retain its digest as Slice 2 evidence.
6. Run the existing `seon.runtime.recovery/recover!` once under root/boot
   provenance. It closes only real interrupted ownership and fabricates no eval
   or work result. An empty repair emits no transaction.
7. Ensure the self-host compiler state, load safe declarations from the restored
   program graph, build the one complete committed schema/function projection,
   reconcile every owned wrapper, activate that projection, and verify its
   fingerprint while admission remains the existing `:publishing` state.
8. Build and transact exactly one architecture-defined
   `:seon.db.restore/*` completion entity through `seon.db` with root/boot
   provenance. The transaction is identity-idempotent for the restore id and
   advances the main coordinate from `F` or the last selected overlay/recovery
   commit to completion coordinate `C`.
9. Re-read the completion entity, transaction provenance, current main
   coordinate `C`, verified projection fingerprint, and main blob set. Only
   then complete the same publication transition to `:available` for that
   exact retained fingerprint.
10. Rebuild replica/feed listeners and read surfaces from `C`, resume eligible
    hosts, and rearm provider/schedule/ticker autonomy last. Readiness must bind
    the admitted generation and writer/replica coordinate; it cannot be a log
    line or successful HTTP socket alone.

If a failure occurs before step 8, the external intent and forced main remain,
no completion fact exists, and admission stays closed. If completion commits
but the process dies before step 9, retry observes the completion fact, proves
the same inputs/current ancestry, reconstructs runtime-only state, and never
repeats force, overlays, recovery, or completion mutation merely from a phase
flag.

### Admission owner change

`admission/publish-committed!` currently performs projection reconstruction,
wrapper reconciliation, activation, and immediate `:available` publication.
Slice 5 must factor that one transition so the verified generation can remain
owned under the existing `:publishing` state while the completion transaction
commits. Ordinary boot and hot reload continue composing the two halves without
a completion callback; restore inserts the completion transaction between them.

This is not a second publication path. One owner still prepares, reconciles,
activates, verifies, and admits one projection. There is no new `:restoring`
status, force-open function, parallel registry, or restore-only instrumentation
pass. A failed completion leaves the already verified disposable projection
hidden behind closed admission and reconstructs it again on process retry.

### Completion fact boundary

The architecture table in `data-model.md` is the current schema authority. It
records the restore id/routing identity, exact pre-restore and selected-target
coordinates, forced commit, retained undo/prepared-target branches, and only
overlay digests actually committed. Its transaction supplies provenance/time;
the transaction's resulting coordinate is `C`.

Do not persist the admission projection fingerprint as a program generation:
`seon.runtime.admission` explicitly defines it as disposable process-local
state, not durable identity. The retained hash-set digest is a frozen intent
and verified transition precondition. Because `B(C)` is derivable from the
completion database value and every member hash commits its bytes, it is not a
second blob-manifest fact unless the architecture owner explicitly adds a
measured durable query requiring it.

There is one predecessor identity mismatch to settle. Slice 2 currently defines
`:seon.dev.restore/intent-id` as a UUID and derives
`:seon.dev.restore/completed-intent-ids` as a set of those UUIDs. Architecture
defines `:seon.db.restore/id` as a generated compact string identity. Slice 5
must not invent a UUID-to-compact conversion. Slice 2 and the architecture
completion owner must align one shared identity shape or add one explicit
architecture-owned intent-id attribute before retry can derive completion
presence safely.

This corrects the implementation card's earlier shorthand that the completion
entity itself binds a reconstructed generation and verified blob digest. The
closed transition proves both before admission; it does not guess new attrs.

## Smallest falsifiers

### Blob preparation

- Target schema lacks `:my.blob/hash`: canonical set and digest are the empty
  vector's digest; no raw schema scan throws.
- Target contains two projections plus one overlay orphan: only the two
  projected hashes are verified/materialized.
- First existing overlay path is corrupt while a lower base is valid: fail on
  corruption; do not hide it through fallback.
- Main destination exists with wrong bytes: never count presence as success;
  repair only from independently verified source content and verify read-back.
- Kill after temporary-file fsync, after rename, and after directory fsync:
  main force remains blocked until retry verifies every final destination.
- Repeated preparation against the same `T`: same set digest, zero new copies,
  identical verified result.

### Cold reconstruction

- Slice 3 result names another forced commit or target parent: no overlay,
  recovery, completion, admission, host, or ticker effect.
- Current source/config differs from restored facts with no frozen overlay:
  restored facts remain unchanged.
- Blob verification fails after force: admission remains closed and no
  completion fact is written.
- Program/schema reconstruction fails: one bounded core fault, no completion,
  and no work boundary opens.
- Completion transaction fails after wrapper verification: projection remains
  hidden under closed publication; retry rebuilds from database truth.
- Crash after completion before admission: retry observes the one completion,
  performs runtime-only reconstruction, and neither forces nor creates a second
  completion fact.
- Successful restore: completion transaction precedes the first
  `:available` state and every host/ticker/provider effect.

## Implementation ownership and order

1. Slice 2 settles and exports the one closed intent/request/result schema,
   including canonical target/main blob-view inputs and the retained-set digest.
2. Slice 3 settles and exports the guarded no-listener result containing exact
   `F` and target ancestry/read-back evidence.
3. `my.blob` adds internal retained-set derivation, verification, and
   destination materialization by reusing its existing resolver/hash/publisher;
   it also closes directory durability. No operator code reads blob bytes.
4. `seon.runtime.admission` factors its one publication transition without a new
   status. `seon.client` composes restore-aware order inside the existing cold
   entry and `seon.runtime.recovery` remains the sole ownership-repair owner.
5. Completion schemas live in their architecture-named namespace and transact
   through `seon.db`; operator retry derives convergence from intent, main
   ancestry, completion presence, and readiness.

Slices 4 and 5 can be implemented by separate source owners after Slice 2's
shared schema lands. The integrated destructive gate remains ordered: blob
preflight/materialization before force, Slice 3 force/read-back, then fresh
closed-admission reconstruction and completion.
