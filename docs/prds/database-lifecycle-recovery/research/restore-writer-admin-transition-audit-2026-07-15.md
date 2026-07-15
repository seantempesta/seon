---
type: research
status: completed
tags: [research, database, flow]
---

# Restore writer-admin transition audit — 2026-07-15

## Result

Restore Slice 3 should be one finite, no-listener invocation of the existing
writer artifact. It consumes the immutable Slice-2 intent, opens one
invocation-local observational Datahike connection through the existing backend
and registry owners, proves the exact main head, prepared-target head, and
durable branch roster, calls guarded `force-branch!`, proves the forced value by
read-back, releases the connection, and returns one closed result. It never
starts the UDS request server, publisher, REPL, transaction listener, embedding
initializer, or a second registry.

The transition can be idempotent without storing a mutable phase. If main is
still the intent's expected head, apply the force once. If main already has the
exact target parent, target `t`, primary value, secondary-index proof, and
expected roster, return `already-applied` without forcing again. Any other head
is divergence. A process exit or missing result is never success; a later
invocation re-derives one of those three storage states.

This audit does not define Slice-2 intent field names. During the audit, the
shared tree gained an uncommitted `seon.dev.restore` owner with version/id,
operation, main and target coordinates, derived undo/prepared coordinates,
artifact flavor, consumer generations, blob view, and digests. Those names are
recorded below as provisional until their owning lane commits. Backend locator,
exact roster, complete artifact/protocol binding, and result transport remain
absent and must be settled by Slice 2. Slice 3 references that one portable
schema rather than copying or weakening it.

No production source or pod was changed for this audit.

## Dependency ledger

| Dependency or owner | Selected identity | Exact source grounding | Constraint |
|---|---|---|---|
| Datahike | `org.replikativ/datahike` `417649383c65e13f15ea41d394fb1ed742477965` | selected commit via `git show`: `src/datahike/versioning.cljc:123-375`, `connector.cljc:422-510`, `writer.cljc:1-73`; `test/datahike/test/versioning_test.cljc:104-156` | `force-branch!` requires caller-exclusive store access, accepts only `:sync?` and `:expected-current-commit`, checks the expected commit before work and again inside the final head update, writes roster before head, creates a new commit, verifies only its commit id on read-back, and returns `nil`. `branches`, `branch-as-db`, and `parent-commit-ids` provide the surrounding proof. Release drains accepted writer and secondary/store work. |
| Konserve | `org.replikativ/konserve` `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve/src/konserve/protocols.cljc:4-36`, `core.cljc:278-375,434-505,771-784` | Per-key update is atomic and optional multi-key writes may be all-or-nothing. There is no CAS spanning roster and branch-head operations or an independent writer. Expected-head checking never substitutes for external writer absence. Seon must not mutate Konserve keys itself. |
| Malli | `metosin/malli` `0.20.0`, tag commit `4c054bd7d042e70d60b83b9f07fb765bc103037f` | `reference-code/malli/src/malli/core.cljc:1046-1085,2635-2675`; `src/seon/schema.cljc` | Closed maps plus one tagged union can validate the immutable intent reference and every terminal result before effects or publication. A rejected variant is data, not an exception crossing the operator boundary. |
| Canonical point | current `seon.db.coordinate` | `src/seon/db/coordinate.cljc` | Every fence is the closed `{database-id, branch, commit-id, t}` point. `t` must equal the retained branch head; an interior `as-of` cut is not branchable. |
| Backend and registry | current `seon.db.backend`, `seon.db.registry` | `src/seon/db/backend.clj:85-157`; `registry.clj:223-501,514-903` | Backend owns physical config. Registry already owns attachment/head/roster checks and Datahike lifecycle error classification, but every current open runs an initializer and publishes a route. Admin needs an invocation-local observational open in this owner, not another registry atom. |
| Writer and server | protocol version `2`, current source | `src/seon/db/protocol.cljc`, `writer.clj:306-323,758-1040,1092-1130`, `server.clj:1-330` | `writer/start!` always starts publisher and request server; connection initialization installs a transaction listener and main open may initialize embeddings. `server/-main` only distinguishes `--preflight` from ordinary daemon start. No restore admin entry or closed result exists. |
| Slice-2 intent and operator | predecessor contract, not yet landed | [[post-clean-restart-restore-undo-multi-form-card-2026-07-15]] and current Slice-2 lane | Intent durability and public orchestration remain external owners. Slice 3 consumes their exact validated value and runs only after generation-bound writer absence is proved. It does not infer exclusivity from a matching head. |

The `reference-code/datahike` working tree currently points at later test-only
commit `eb3e2239b650635977fdc8e73e7c657b23bf3383`; all Datahike claims above were
read from the selected `417649...` commit rather than that moving checkout.

## Current gaps and one-mechanism cut

`seon.db.protocol` already owns closed native create, release, and delete data,
but there is no force/admin data shape. `seon.db.writer/handle-request` is a UDS
interpreter, not the right way to smuggle a restore through a live listener.
`seon.db.server/-main` always calls `writer/start!`, which starts the publisher
before ensuring main and then starts the UDS request server. The optional REPL
is a third listener. None may exist during the exclusive root move.

`seon.db.registry/ensure-database!` cannot be reused unchanged. It publishes a
process-global route and calls `writer/initialize-connection!`; that function
installs a transaction listener and, for main, runs the database initializer.
The smallest strengthening is an invocation-local registry operation that:

- derives the maintained config through `seon.db.backend`;
- refuses database creation and checks the requested physical identity;
- opens one Datahike connection without publishing `!registry`;
- performs protocol-schema and declared-secondary observational validation
  without installing listeners or running a writing initializer;
- owns `try`/`finally` release and includes release proof in its result; and
- returns only plain closed data to the server admin entry.

This is still the one registry and writer artifact. The connection map is a
function-local resource, not a second route registry or daemon.

## Slice-2 contract boundary

Slice 3 may depend on meanings now, but must wait for Slice 2 to settle their
exact keys and schemas.

| Meaning required by admin | Status | Slice-3 use |
|---|---|---|
| Intent id, operation, schema version, canonical digest | **Provisional Slice-2 fields exist** as `intent-id`, `operation`, `intent-version`, and `plan-digest` | Consume them after the owner commits; echo identity in every result and refuse an unvalidated or differently digested intent. Do not invent parallel ids. |
| Backend kind, durable database path, logical main route, physical database id | **Still missing from provisional Slice 2**, except `database-name` and the id inside coordinates | Settle whether exact backend/path are frozen in intent or derived from a digest-bound launch descriptor. Never accept ambient defaults or an arbitrary raw Konserve config. |
| Exact expected main head `H` | **Provisional field exists** as `pre-restore-main-coordinate` | Full pre-force attachment/head fence; pass only `H.commit-id` to Datahike's narrower guard. |
| Exact selected target `T` and prepared target head `P` | **Provisional fields exist** as `selected-target-coordinate` and derived `prepared-target-coordinate` | Admin forces only from the exact prepared branch head. `P.t` must be its containing commit head and its commit must equal the selected target commit intended for ancestry. |
| Reserved undo branch/head | **Provisional fields exist** as `undo-branch` and derived `undo-coordinate` | Require exact roster membership and head before force; admin does not create or repair it. |
| Exact expected durable roster after preparation | **Missing Slice-2 field to settle** | Require set equality, not mere membership, immediately before and after force. This detects an unrecorded branch transition while the intent was frozen. |
| Blob/config/program/confirmation fields | **Provisional Slice 2 has** blob view, reachable-hash digest, and plan digest; other confirmation semantics remain its owner | Their digest is part of intent identity, but admin does not interpret or apply them. |
| Artifact/protocol compatibility identity | **Artifact flavor exists; writer digest and protocol identity remain missing** | Refuse an intent not meant for the running writer artifact/schema. |
| Result destination and atomic publication contract | **Wait for Slice 2/operator** | Server returns one closed value through the selected bounded channel. Stdout, a second socket, and log parsing must not become semantic transports. |
| Generation-bound writer/pod absence evidence | Existing coordinator prerequisite, not an intent field by default | Operator proves this before launch. Admin cannot derive external process absence from storage and must never claim that it did. |

The exact roster is a real predecessor requirement. The immutable intent can
freeze the roster expected after adding its predetermined undo and prepared
target branches. If any unrelated branch appears before drain, the transition
becomes stale and must be reconfirmed rather than silently widening the intent.

## No-listener invocation

The server selects admin mode before calling `writer/start!`. Exact CLI flags
and result-path fields wait for Slice 2, but the semantic call is one map:

```clojure
(writer/run-restore-admin!
  {:seon.db.restore/intent validated-slice-2-intent})
```

The portable intent reference and result union belong beside the existing
coordinate/lifecycle data in `seon.db.protocol`; the semantic operation is not
added to the UDS `::request` dispatch because no live request server may accept
it. `seon.db.server` parses bounded input, validates it, calls the writer once,
publishes the returned closed value through Slice 2's selected result channel,
and exits nonzero for every rejected or unpublished result.

The invocation starts none of:

- `uds/start-publisher!` or `uds/start-request-server!`;
- `core-server/start-server`;
- `d/listen` transaction publication;
- embedding initialization, transaction transforms, or KNN dependencies; or
- registry route publication.

## Exact transition

Let `H={D,:db,h,th}` be expected main, `T={D,bt,t,tt}` the selected target,
and `P={D,bp,t,tt}` its prepared branch. Let `R` be the exact expected roster,
including `:db`, `bp`, the reserved undo branch, and every previously retained
branch.

1. Validate the complete Slice-2 intent and artifact compatibility before I/O.
2. Derive the main Datahike config from its existing backend owner. Refuse a
   missing database; never create it in admin mode.
3. Connect once to main in a fresh process and prove the actual database id and
   branch. Do not publish the connection or initialize runtime behavior.
4. Read `d/branches` and require exact equality with `R`. Load `:db` and `bp`
   via `d/branch-as-db`; resolve complete coordinates and require exact `H` and
   `P`. Require `P.commit-id=T.commit-id`, `P.t=T.t`, and head-level `t`.
5. Verify target primary EAVT and every declared secondary index before force.
   A missing declared secondary is failure, not permission to rebuild during
   the exclusive operation.
6. If main is exact `H`, call
   `(d/force-branch! target-db :db #{T.commit-id}
      {:expected-current-commit H.commit-id})` once.
7. If main is not `H`, do not call force. Continue only when it already
   satisfies the desired-state predicate below; otherwise return divergence.
8. Read `:db` and the roster again. Resolve `F` and require:
   `F.database-id=D`, `F.branch=:db`, `F.t=T.t`, `F.commit-id` present and not
   `h`, exact parents `#{T.commit-id}`, exact primary equality with `P`, every
   declared secondary operational/equivalent, and roster still exactly `R`.
9. Release the connection and await Datahike's complete writer, secondary, and
   Konserve release. Only a proved release permits an applied/already-applied
   success result.
10. Publish the closed result and exit. Ordinary writer startup always opens a
    fresh connection; no db value or cursor crosses the transition.

The Seon wrapper must perform steps 8–9 even though maintained Datahike already
checks the newly computed commit id. Datahike deliberately cannot validate
Seon's complete coordinate, target ancestry contract, roster, projection
parity, or external result publication.

## Closed result and idempotent retry

Register one closed tagged union in the portable protocol-data owner. Exact
field names that reference Slice-2 intent wait for that contract, but the
variants and evidence are fixed:

- `applied`: intent identity, `H`, `T`, `P`, actual `F`, exact roster,
  `released? true`, and outcome `applied`;
- `already-applied`: the same evidence, with outcome `already-applied` and no
  force call in that invocation; and
- `rejected`: intent identity, a bounded typed error kind/message, every safely
  observed main/target/roster value, whether force was invoked, and release
  evidence when a connection was opened.

Do not add `success?`, `clean?`, or `phase` facts that duplicate derivable
variant/outcome data. A missing result is outside this union and remains
unknown delivery.

The desired-state retry predicate is deliberately strict:

- main has physical id `D`, branch `:db`, target `t`, and a commit distinct
  from `h`;
- its exact parent set is `#{T.commit-id}`;
- its complete primary EAVT value equals prepared target `P`;
- every declared secondary is present and proves the same selected value; and
- the durable roster equals `R`.

If all hold, repeating force would merely create another root commit and is
forbidden; return `already-applied`. If main is `H`, applying once is safe. Any
other state is rejected. This converges after response loss, process death
after head movement, and release-result loss without storing a mutable admin
phase or guessing from process exit.

## Failure matrix

| Failure or observed cut | Required result/action | Root rule |
|---|---|---|
| Intent invalid, partial, stale-version, or digest mismatch | Reject before connect. | No storage read or write. |
| Database absent or physical id/path mismatch | Reject; admin never creates. | Main unchanged. |
| External writer absence is unproved | Operator must not launch admin. | A matching head cannot substitute for exclusivity. |
| Roster differs before force | Reject with expected/actual roster. | No force. |
| Main differs from `H` and desired-state predicate is false | Divergence. | Never adopt a newly observed head into the intent. |
| Target/prepared/undo branch missing or head differs | Reject with exact observed coordinates. | No force or branch repair. |
| Target is an interior cut or retained commit is missing | Typed cut/missing failure. | Never round to containing head. |
| Declared secondary missing or target parity fails | Reject. | No rebuilding during admin force. |
| Datahike stale-head check fails before final update | Reject stale main; immutable orphan nodes may remain. | Main must remain unclaimed. |
| Datahike stale-head check fails inside final update | Reject stale main and preserve diagnostics. | Never retry against the observed replacement head. |
| Process dies before head update | Later invocation observes exact `H` and may apply. | Exit alone proves nothing. |
| Process dies after head update or result publication is lost | Later invocation proves desired state and returns `already-applied`. | Never force twice. |
| Forced commit-id read-back mismatches | Reject dependency read-back failure. | Preserve intent and maintenance. |
| Seon full read-back/parent/value/roster proof fails | Divergence even if Datahike returned. | No success result. |
| Datahike release fails or is unproved | Rejected result with observed head and release error; containment must prove admin subtree absent. | Do not start ordinary writer from a claimed clean handoff. |
| Closed result cannot be atomically published | Delivery unknown. | Retry derives from storage; log text is not result. |
| Retry sees exact desired state | Validate, release, return `already-applied`. | Zero force calls. |
| Retry sees completion fact for the intent | Slice-2 state classifier skips admin and continues reconstruction/readiness. | Admin is not a completion owner. |

## Focused proof

- Server admin selection proves zero publisher, request-server, REPL,
  transaction-listener, embedding-initializer, and route-publication calls.
- Table-driven registry tests cover every preflight fence and inject stale
  head both before and inside Datahike's final update.
- A real file-backed Datahike fixture covers exact target force, parent set,
  new main coordinate, primary facts, declared secondary availability, roster
  equality, and awaited release.
- Response-loss and process-cut fixtures rerun against `H`, desired `F`, and a
  divergent head, asserting force call counts `[1,0,0]` respectively.
- Result-schema tests reject unknown keys, partial coordinate/roster evidence,
  success without proved release, and a copied Slice-2 intent approximation.
- The integrated operator test launches only after the clean-or-force
  coordinator proves ordinary writer absence and starts the ordinary writer
  only after the admin process and connection release are proved complete.

The live destructive restore/undo gate remains later. Slice 3 graduates when
these focused writer tests pass and the no-listener process result is consumed
by Slice 2 without widening or translating the immutable intent.
