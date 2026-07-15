---
type: research
status: completed
tags: [research, database, flow, pod]
---

# Restore cold-composition reconciliation — 2026-07-15

## Result

The Slice-5 primitives are now real, but they do not yet compose into one cold
restore transition. `my.blob/materialize-retained!` proves the exact retained
database coordinate and blob set, `seon.db.restore/record!` owns the one
completion transaction, and `seon.runtime.admission/prepare-committed!` plus
`admit-prepared!` can keep executable admission closed across that transaction.
The missing owner is the existing `seon.client/start-runtime!` cold entry and
its operator-supplied immutable startup input—not another restore runtime.

No production seam is safe to add in isolation yet. The current cold entry
performs autonomous schema/boot/config/recovery writes before it begins program
publication, and then admits inside the same call before an external caller can
record completion. Its non-autonomous path avoids those early writes but still
admits and publishes a running web runtime. Adding a callback, a second boot
function, or an ambient restore file read would hide rather than settle the
cross-process contract.

Three predecessor blockers remain explicit:

- selected Datahike cannot yet force a file-backed Proximum secondary to the
  destination branch with equal Merkle roots, so the closed writer-admin result
  correctly rejects the transition;
- `seon.db.restore/record!` cannot return the original completion coordinate
  after a later main transaction because Seon's database protocol has no
  transaction-to-containing-commit resolver; and
- no closed, digest-bound startup operation carries the immutable intent,
  writer-admin result, and retained-blob result through the existing process
  specification into `start-runtime!`.

This report therefore leaves source unchanged and gives the implementation-
ready call graph and failure order below.

## Dependency ledger

| Dependency or owner | Selected identity | Exact grounding | Constraint |
|---|---|---|---|
| Datahike | `org.replikativ/datahike` `417649383c65e13f15ea41d394fb1ed742477965` | selected commit of `reference-code/datahike/src/datahike/versioning.cljc`; current checkout is test-only descendant `eb3e2239b650635977fdc8e73e7c657b23bf3383` | `branch-history` walks immutable stored database values and exposes each containing commit id and `max-tx`; `force-branch!` still has the tracked Proximum destination-branch defect. |
| Konserve | `org.replikativ/konserve` `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve/src/konserve/{core,filestore,protocols}.clj*` | Intent/result and blob publication need file plus parent-directory durability; a process result or log line is not storage convergence. |
| Malli | `metosin/malli` `0.20.0` | `deps.edn`, `reference-code/malli`; Seon `seon.schema` registry | Every cross-process input/result remains a closed registered value. A function callback or loose map is not a serializable restore contract. |
| ClojureScript | `org.clojure/clojurescript` `1.12.145` | `deps.edn`, `src/seon/client.cljs`, `src/seon/runtime/admission.cljs` | The cold owner is one `^:async` ordered transition. Promise completion must remain inside it; process-local wrappers and compiler state never cross restart. |
| Launch/process owner | current `seon.launch`, `seon.dev.process`, and `seon.db.replica` | `src/seon/launch.cljc`, `script/seon/dev/process.clj`, `src/seon/db/replica.cljs` | The pod currently receives one validated `SEON_LAUNCH_DESCRIPTOR`; readiness is HTTP 2xx from `/_seon/ready`, which is exactly `admission/available?`. Restore input must strengthen this owner rather than add unrelated environment selectors. |
| Cold runtime owner | current `seon.client/start-runtime!` | `src/seon/client.cljs:825-872,2464-2640,3080-3134` | Autonomous open installs provenance/runtime schema, boot/config, crash recovery, root/initial agents, replay, immediate admission, hosts, surfaces, and ticker in that order. Restore must factor this same entry and change only data-selected steps/order. |
| Admission owner | commits `ace68173` and current source | `src/seon/runtime/admission.cljs:224-343`, `test/seon/runtime/admission_test.cljs` | Preparation activates and retains one verified fingerprint under `:publishing`; only the exact returned generation can admit. No new status is needed. |
| Blob owner | commit `31bb8973` | `src/my/blob.cljs:405-597`, `test/my/blob_test.cljs` | Materialization consumes an already-resolved exact target database plus frozen source/destination views and digest. It performs no database write and is not operator-callable by itself. |
| Completion owner | commit `86d75924` | `src/seon/db/restore.cljs`, `test/seon/db/restore_test.cljs` | `record!` is root/boot-provenance-neutral, whole-head fenced, identity-idempotent, and admission-neutral. All completion schemas must already be installed. |
| Writer-admin owner | commit `75a5fe12` | `src/seon/db/restore_admin.clj`, `src/seon/db/{registry,server,writer}.clj`, writer-admin tests | Only `applied` or `already-applied` with exact forced coordinate, target, roster, and released connection may feed cold reconstruction. Unknown/rejected results cannot be upgraded by observation. |

## Executable probes

The repository MCP watcher was live but the default build advertised zero pod
runtimes, so a live CLJS probe correctly failed instead of selecting ACME. No
default restart was triggered while shared source lanes were editing build
inputs.

A disposable memory database against the exact `:writer` basis directly
probed the missing resolver primitive:

```clojure
(mapv (fn [database]
        {:max-tx (:max-tx database)
         :cid (datahike.versioning/commit-id database)})
      (<?? S (datahike.versioning/branch-history connection)))
```

After genesis plus three transactions it returned, newest first:

```clojure
[{:max-tx 536870915 :cid #uuid "6a579a22-66fe-5fc3-b04f-1ef8939a33ae"}
 {:max-tx 536870914 :cid #uuid "6a579a22-1b08-56de-b080-81c9e92c0346"}
 {:max-tx 536870913 :cid #uuid "6a579a21-67b9-558e-84eb-881af40c8d8f"}
 {:max-tx 536870912 :cid #uuid "6a579a21-09e1-5df1-a3a1-e5e12da74974"}]
```

This proves the maintained source can support a writer-backed resolver without
storing a shadow completion commit id. The Seon operation must still define
the exact containing-commit rule for batched commits and DAG parents, validate
the selected branch/database, and return one canonical coordinate through the
existing protocol. A bare `t` search in pod code is not sufficient.

## Current call graph and shortest falsifier

The present ordinary cold path is:

1. `seon.client/-main` validates the launch descriptor, claims its blob view,
   and calls `start-runtime!`.
2. `open-database-connection!` opens the writer route and local replica. With
   autonomous capability it immediately installs provenance and runtime schema
   before the caller can validate a restore result.
3. `start-runtime-impl!` runs `boot-seed!`, ambient manifest reconciliation,
   crash recovery, root/initial-agent creation, and compiler bootstrap.
4. It calls `admission/begin-publication!`, replays the program graph, then
   calls `admission/publish-committed!`, which prepares and admits immediately.
5. Only after admission opens does it resume hosts, start web surfaces, sync
   provider/brand state, and install the ticker.
6. `seon.dev.process/wait-ready!` accepts the pod only when
   `/_seon/ready` returns 2xx; that handler returns 2xx exactly when admission
   is `:available`.

The shortest falsifier is therefore an injected completion failure after
program verification. Calling current `start-runtime!` cannot place that
failure before `:available`: the only retained call is
`admission/publish-committed!`. Calling `prepare-committed!` separately from an
external process cannot help because all earlier cold writes and replay remain
private inside `start-runtime-impl!`. The existing admission unit test proves
the local seam; it does not prove cold composition.

## One ordered closed transition

The implementation must preserve the existing process graph and replace the
single cold sequence with data-selected composition:

1. **Operator prerequisite, before force.** Under the existing cluster lock,
   validate the immutable intent and invoke `my.blob/materialize-retained!`
   against the exact retained target pod/database and its frozen target/main
   views. Persist or otherwise consume one closed result bound by target
   coordinate and reachable-set digest. Do not use MCP eval or a directory
   scan as production transport.
2. **Exclusive root move.** Stop every consumer, verify the exact writer
   artifact digest, run the no-listener admin invocation, and accept only a
   valid released `applied`/`already-applied` result. The Proximum root defect
   must be fixed first.
3. **Ordinary writer replacement.** Start the normal writer artifact and prove
   its main coordinate equals the admin result's forced coordinate. No admin
   connection or cursor crosses this boundary.
4. **One immutable pod startup input.** Extend the existing launch/process data
   owner with one optional closed restore startup value referencing the already
   validated intent, admin result, and blob result. Its bytes/digests must be
   part of the managed process identity. Do not add three ambient environment
   variables or read an unvalidated `restore.edn` merely because it exists.
5. **Fresh attach with writes still closed.** The existing `start-runtime!`
   entry claims the ordinary main launch/blob descriptor, connects with
   `prepare-writes? false`, and validates current writer/replica coordinate,
   intent id/plan digest, forced target parent, artifact/protocol identity, and
   retained blob proof before any database write, recovery, replay, host, web,
   provider, or ticker effect.
6. **Preserve-only restoration.** Intent v1 selects preserve for core/config,
   so this transition must not call ambient `boot-seed!`, config apply,
   provider sync, or brand sync before completion. It verifies required
   installed schema from restored facts; an older target missing completion
   schema fails explicitly rather than installing current-source declarations
   as an unrecorded overlay.
7. **Ordinary repair and program reconstruction.** Run the existing
   `seon.runtime.recovery/recover!` once under root/boot provenance, ensure the
   compiler state, replay the stored program graph, begin the one publication,
   then call `admission/prepare-committed!`. Admission remains `:publishing`.
8. **Completion inside the same async owner.** Build the exact
   `seon.db.restore/completion` from the validated intent and admin result and
   call `record!` under root/boot provenance. Require its exact read-back and
   completion coordinate. On failure, leave admission closed and publish no
   ready process result.
9. **Exact admission.** Call `admission/admit-prepared!` with the unchanged
   preparation returned in step 7. A changed or lost generation fails closed.
   Only then start/resume hosts, web surfaces, provider/schedule/ticker
   autonomy, and allow `/_seon/ready` to return 2xx.
10. **External cleanup.** After readiness proves the admitted generation plus
    writer/replica completion coordinate, the operator removes the immutable
    intent through its durable state owner. Intent deletion and public CLI
    remain outside `seon.client`; no runtime status/phase fact is added.

Ordinary startup supplies no restore startup value and composes the same
functions with its existing boot/config policy. The refactor must not create a
`restore-runtime!`, another admission cell, a completion callback, or a second
replay path.

## Crash and retry cuts

| Cut | Required retry observation |
|---|---|
| Blob materialization partial, before force | Same target/digest re-verifies valid files and repairs only missing/corrupt destinations; main is unchanged. |
| Admin result absent after possible force | Rerun the no-listener admin and derive old/exact desired/divergent storage state; never infer success from process exit. |
| Forced result proved, writer or pod start fails | Intent and admin/blob results remain; replacement fresh-connects again with admission closed. |
| Recovery or program preparation fails | No completion; admission stays closed; retry reconstructs from committed facts. |
| Completion transaction rejects | No admission; retry uses the same immutable inputs and whole-head fence. |
| Completion commits, pod dies before admission | Transaction-coordinate resolver returns the original completion coordinate even if a later head exists; retry does not force, overlay, or transact completion again. |
| Admission succeeds, intent deletion is lost | Readiness plus exact completion proves convergence; cleanup deletes the same intent without another runtime transition. |

## Blockers and acceptance boundary

- [[../../seon/issues/restore-writer-admin-transition-is-unimplemented]] owns
  the Datahike Proximum destination-secondary defect and exact admin success.
- [[../../seon/issues/restore-completion-cannot-precede-admission]] owns cold
  composition plus the transaction-to-containing-commit resolver.
- The next implementation should first land the canonical writer-backed
  resolver, because it closes crash-after-completion retry without changing the
  completion schema. Then settle the one closed startup input in the launch/
  process owner before editing `seon.client`.

The integrated exit is not a focused green test. It is an isolated named-
cluster destructive proof that force and blob evidence agree, completion
commits before the first `:available`, a crash at that boundary creates no
second force or completion fact, and config-free restart returns the same
database facts and exact original completion coordinate. Only after that proof
may the operator delete the intent and expose restore/undo publicly.
