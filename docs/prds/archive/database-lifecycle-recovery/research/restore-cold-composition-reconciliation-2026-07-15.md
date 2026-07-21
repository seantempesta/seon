---
type: research
status: completed
tags: [research, database, flow, pod]
---

# Restore cold-composition reconciliation — 2026-07-15

## Result

The Slice-5 primitives now compose into one cold restore transition in the
existing `seon.client/start-runtime!` owner. `my.blob/materialize-retained!`
proves the exact retained database coordinate and blob set,
`seon.db.restore/record!` owns the one completion transaction, and
`seon.runtime.admission/prepare-committed!` plus `admit-prepared!` keep
executable admission closed across that transaction. The caller consumes the
operator-supplied immutable startup value; there is no second restore runtime.

The current cold entry still performs autonomous schema/boot/config/recovery
writes before it begins program publication, and then admits inside the same
call before an external caller can record completion. Its non-autonomous path
avoids those early writes but still admits and publishes a running web runtime.
Adding a callback, a second boot function, or an ambient restore file read
would hide rather than settle the cross-process contract.

Two of the three original predecessor contracts are now complete:

- `b2461d64` supplies the canonical writer-backed transaction-coordinate
  resolver, including later-head completion retry; and
- `c2b4013d` carries one closed digest-bound startup value through the existing
  launch descriptor and exact process publication.

One destructive predecessor remains explicit:

- selected Datahike cannot yet force a file-backed Proximum secondary to the
  destination branch with equal Merkle roots, so the closed writer-admin result
  correctly rejects the transition.

The client composition and the separate fresh-schema correction are now
implemented in place. Focused source proof covers ordering, mismatch,
completion failure and retry, missing restored schema, replay failure, and
ordinary-start parity. The destructive named-cluster proof remains gated by
the Proximum dependency repair; no destructive default or ACME transition was
attempted.

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
| Completion owner | commit `86d75924` | `src/seon/db/restore.cljc`, `test/seon/db/restore_test.cljs` | `record!` is root/boot-provenance-neutral, whole-head fenced, identity-idempotent, and admission-neutral. All completion schemas must already be installed. |
| Writer-admin owner | commit `75a5fe12` | `src/seon/db/restore_admin.clj`, `src/seon/db/{registry,server,writer}.clj`, writer-admin tests | Only `applied` or `already-applied` with exact forced coordinate, target, roster, and released connection may feed cold reconstruction. Unknown/rejected results cannot be upgraded by observation. |

## Executable probes

The repository MCP watcher was live but the default build advertised zero pod
runtimes, so a live CLJS probe correctly failed instead of selecting ACME. No
default restart was triggered while shared source lanes were editing build
inputs.

After the startup evidence landed, `bin/seon status` observed the default
cluster as degraded: watcher alive but not ready, writer alive, and pod drained
and not ready. This is the expected safe posture for source planning; no live
runtime claim or destructive restore proof is inferred from it.

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

## Inert startup value implemented

The launch descriptor now accepts one optional, closed
`:seon.launch/restore-startup` value composed solely from three authoritative
portable values: the frozen intent identity/digests and consumer-generation
map, one exact successful no-listener writer-admin result, and one exact blob
materialization success. Cross-owner validation proves intent id, plan digest,
reachable-hash digest, selected target coordinate, and the required frozen
`:seon.dev.process/pod` member agree. The descriptor's actual database
coordinate must equal the admin result's forced-main coordinate.

No expected-coordinate or expected-generation duplicate is stored. The future
cold caller derives the required pod generation from the frozen intent member,
and compares its actual fresh attachment with the admin result. No path,
callback, status, ambient reread, or boot behavior was added. Ordinary
descriptors remain the same EDN bytes when the optional value is absent.

`seon.dev.process/specs` already publishes the complete launch descriptor as
`SEON_LAUNCH_DESCRIPTOR` with `pr-str`; its retained branch-publication test
round-trips that exact value. Therefore the optional restore value crosses the
existing managed-process publication boundary without a second environment
variable or process implementation change. Focused proof is:

- `seon.launch-test`: seven tests/47 assertions, including closed-schema,
  EDN round-trip, six independent relational mutations, exact forced-main and
  consumer-generation binding, and unchanged ordinary descriptor bytes;
- `my.blob-test`: 21 tests/148 assertions after moving the materialization
  success contract to its portable schema owner;
- `seon.db.restore-admin-test`: nine tests/53 assertions after moving its
  result contracts to the portable schema owner; and
- `seon.dev.restore-test`: nine tests/57 assertions, including exact immutable
  startup-identity projection.

The fresh-schema ordering defect found by the broader CLJS gate is now fixed at
the canonical owner. `seon.db.restore/completion-attrs` owns the complete
identity-plus-thirteen-attribute closure, and
`seon.client/agent-bootstrap-attrs` includes that collection before
`install-runtime-schema!` publishes generator policies. A fresh isolated
database proves native schema transaction precedes the compact policy
transaction. The focused `seon.db.restore-test` gate passes 7 tests/37
assertions with zero failures, errors, or compile warnings at
`tmp/test-cljs-20260715-110003-54902.log`. Preserve-only restore still validates
that this schema already exists after force; it never installs current-source
schema as an implicit overlay.

## In-place client composition implemented

`seon.client/start-runtime!` now consumes the optional startup value before its
first await or database write. Restore requires a fresh autonomous process and
the exact frozen pod generation. It attaches main with `prepare-writes? false`,
then proves the writer/replica head equals both the launch coordinate and the
admin result's forced coordinate and proves the complete completion schema was
already present in the restored database. A mismatch fails before recovery,
replay, completion, hosts, web, provider synchronization, branding, or ticker
installation.

Preserve-only reconstruction skips ordinary boot seed, ambient config, root
creation, and initial-agent creation. It runs the existing crash-recovery and
program-replay owners under their existing root/boot boundaries, prepares the
committed projection, records and reads back the exact completion, and admits
only the unchanged preparation. Hosts, web, provider synchronization,
branding, and the ticker remain after admission. Ordinary cold startup still
uses its existing boot/config/publication composition when the optional value
is absent.

Replay fault persistence is an intentional bounded exception to the phrase
"no restore overlay writes." Existing replay with `record-failures? true` may
record operational fault evidence under the existing provenance boundary while
admission remains closed. That evidence is not a core, config, provider, or
schema overlay. Any nonzero replay failure blocks preparation, completion, and
admission; it cannot turn a damaged reconstruction into a ready process.

The implementation adds no `restore-runtime!`, callback, process mode,
admission status, registry, replay path, or ambient environment reread. The
destructive integrated proof remains unchanged: after the Proximum force repair
lands, an isolated named cluster must prove force/blob agreement, completion
before first readiness, crash/retry without a second force or completion fact,
and config-free restart at the same facts and original completion coordinate.

Focused proof passes `seon.client-runtime-test` at 29 tests/195 assertions,
including crash after committed completion and idempotent completion reuse
before retry admission, with zero compile warnings at
`tmp/test-cljs-20260715-112026-67588.log`. Adjacent owner proof passes
`seon.db.restore-test` at 7/37, `seon.runtime.admission-test` at 14/85, and
`seon.launch-test` at 7/47, all with zero failures, errors, or compile warnings.

### Pure selection and evidence projection

Add `seon.db.restore` as the completion owner used by `seon.client`. At the top
of `start-runtime-impl!`, validate the already parsed
`replica/process-launch-descriptor` through `launch/validate-descriptor` and
bind its optional `::launch/restore-startup` once. Before the first await:

- reject restore startup on an already attached process; retry must be a fresh
  process with no inherited connection, feed, compiler value, host, or web
  owner;
- require the ordinary autonomous main capability; a retained non-autonomous
  branch cannot consume a main restore transition;
- compare `SEON_PROCESS_GENERATION` byte-for-byte with the UUID string stored at
  `[:seon.dev.restore/startup-identity
  :seon.dev.restore/consumer-generations :seon.dev.process/pod]`; absence or
  difference is a core startup error before database effects; and
- retain the exact startup value in the async lexical scope. Never reread an
  intent/result path or process environment after this selection.

One private pure projection in `seon.db.restore` should construct the existing
closed `::completion` value from authoritative fields, rather than assembling
the same thirteen keys in `seon.client`. Its request consumes the logical
database name plus the validated startup value. The projection is exact:

| Completion value | Authoritative source |
|---|---|
| `id` | startup identity `intent-id` |
| `db-name` | launch database name, converted to the architecture keyword |
| `database-id` | admin `forced-main-coordinate` |
| `from-*` | admin `pre-restore-main-coordinate` |
| `to-*` | admin `selected-target-coordinate` |
| `forced-commit-id` | admin `forced-main-coordinate` |
| `undo-branch` | admin `undo-coordinate` branch |
| `target-branch` | admin `prepared-target-coordinate` branch |
| optional overlay digests | absent for the v1 preserve-only transition |

The helper validates the finished `::completion`. It never derives a commit,
target, branch, digest, or identity from current database state.

### Fresh attachment and write-closed validation

Call the existing `open-database-connection!` with
`::prepare-writes? false` whenever restore startup is present. Its ping,
writer-open, Datahike connect, and feed attachment remain the one attachment
mechanism; provenance/schema installation is skipped. After binding
`db/*conn*`, freeze one database value and require all of the following before
recovery or any other transaction:

- its complete head equals the launch descriptor coordinate and the admin
  `forced-main-coordinate`, including database id, `:db`, commit id, and `t`;
- the admin result remains the closed released `applied` or `already-applied`
  success already required by `::launch/restore-startup`;
- blob success names the admin selected-target coordinate and the startup
  reachable-set digest; and
- every `seon.db.restore/completion-attrs` member is installed in the restored
  database. Preserve-only restore never installs missing current-source schema
  after force.

`db/assert-preconditions!` is read-only and follows this exact evidence check.
Any mismatch throws one core `ex-info`; the existing `start-runtime!` owner
changes its phase to `cleanup-required`, and `-main` exits without reporting
readiness. No mismatch is coerced into an ordinary non-restore boot.

### Reconstruction, completion, and exact admission

Select the remainder of the current cold sequence with data, retaining the
ordinary branch unchanged:

1. Restore omits `boot-seed!`, ambient manifest/config reconciliation,
   `agent/create!`, `agent/ensure-initial-agent!`, `ai/sync!`, and
   `web.brand/sync!` until their explicit places below. The forced database is
   the complete preserve-only source.
2. Run the existing `recovery/recover!` once under root/boot provenance. A
   false `:seon.db/ok?` envelope is fatal and leaves admission closed; a
   converged recovery writes nothing.
3. Resolve the existing root/primary/resumable agent projections from the
   restored database, await `repl/ensure-bootstrap!`, acquire the one
   `admission/begin-publication!`, and call the existing
   `replay-program-graph!` with failure recording enabled.
4. Replace the restore branch's call to `publish-committed!` with
   `prepare-committed!`. Require `::admission/prepared? true` and retain that
   exact returned map; its fingerprint is disposable process state, not a
   restore fact.
5. Under root/boot transaction provenance, call `seon.db.restore/record!` with
   the pure completion projection. Require `::restore/ok? true`, exact
   completion read-back, and its complete original completion coordinate.
   Equal retry may call the settled writer resolver and performs no write.
6. Pass the unchanged preparation map to `admission/admit-prepared!`. Require
   `::admission/published? true`; a lost or changed generation remains closed.
7. Only after exact admission resume eligible agent hosts, start the web
   surface, then run provider and brand synchronization and install the ticker.
   The ordinary no-restore branch continues using
   `admission/publish-committed!` and its existing boot/config policy.

The existing failure values remain the contract: recovery and completion use
their closed `ok?` envelopes; preparation and admission use their closed
`prepared?`/`published?` maps with `:seon/error`. `start-runtime!` converts any
false result into the one existing core startup exception and
`cleanup-required` phase. No callback, restore status, or second error shape is
introduced.

### Readiness and durable intent deletion

`/_seon/ready` remains derived from `admission/available?`, so it cannot return
2xx before the exact completion read-back and admission above. HTTP 2xx alone
is not permission to delete the intent. After normal pod readiness, the
external operator must re-read the same completion id and prove the ordinary
writer/main coordinate is at or descends from the returned completion
coordinate. Only `seon.dev.restore-state`, under the retained cluster lock,
deletes the fsync-published intent. `seon.client` never owns that filesystem
inverse.

### Crash cuts and focused proof

| Cut | Required observation on retry |
|---|---|
| Generation or startup/head mismatch | No database transaction, recovery, replay, host, web, provider, brand, ticker, completion, or admission effect. |
| Recovery failure | Admission remains closed; no completion fact exists. |
| Replay or preparation failure | One existing bounded core-fault path; no completion or executable boundary. |
| Completion rejects after preparation | Prepared projection stays hidden under `:publishing`; no admission, host, web, or autonomy. |
| Completion commits and process dies before admission | Fresh retry reconstructs disposable compiler/projection state, `record!` returns the original coordinate without a write, and the exact new preparation alone admits. |
| Admission succeeds and intent deletion is lost | Operator re-proves readiness plus completion ancestry and deletes the same intent; no force, overlay, recovery repair, or second completion is inferred from a phase file. |

Extend `test/seon/client_runtime_test.cljs` at its existing async-safe stub owner
rather than creating another harness. The focused cases are ordinary-effect
parity, restore generation/head mismatch before writes, exact
attach/recovery/replay/prepare/completion/admit/host/web/autonomy order,
completion failure remaining closed, and equal-completion crash retry. Retain
the current focused owners in `test/seon/launch_test.cljs`,
`test/seon/db/restore_test.cljs`, and
`test/seon/runtime/admission_test.cljs`. Run each namespace through the one
`bin/test-cljs --test=<namespace>` selector while iterating, then one combined
source-frozen checkpoint before the destructive named-cluster proof.

The production ownership for the later implementation is limited to
`src/seon/client.cljs` and, for the pure projection only,
`src/seon/db/restore.cljc`; focused proof belongs to
`test/seon/client_runtime_test.cljs` and `test/seon/db/restore_test.cljs`.
`seon.launch`, process publication, blob materialization, writer admin,
admission, replica transport, web readiness, and operator intent deletion are
consumers with settled owners, not new edit surfaces for this unit.
