---
type: research
status: completed
tags: [research, database, schema, flow]
---

# Remaining lifecycle gap reconciliation — 2026-07-14

This report is a commit-pinned pre-implementation decision at `bf8cf3b5`.
The selected admission slice subsequently landed in `dd494cd6` and
`8f5936ae`; its historical probes and dependency reasoning remain evidence,
while the PRD roadmap records the current state and next native-branch slice.

## Decision

At `bf8cf3b519912f56f0b1e1a7bedb5d2644bb5e53`, the next implementation
boundary is post-commit program publication admission, not native branch
create/delete. The branch-qualified registry prerequisite is present, but a
normal accepted declaration can still leave durable program facts at
generation N while autonomous work continues against process-local generation
N-1 or partially changed Malli wrappers. That is already a correctness failure
on the only main branch; it does not require native branches to reproduce.

The wrapper-arity blocker identified by the earlier audit is resolved. Exact
accessor-mutating multi-arity contracts are rejected before mutation, and a
pure variadic implementation admits a stricter schema only when every schema
arity is callable. Full committed reconstruction is therefore now a safe
repair primitive. The existing open issue is
[[../../../seon/issues/post-commit-program-publication-leaves-admission-open]].

Native branch operations depend on the same admission owner twice: branch
creation must quiesce writes before its final source-head check, and a forensic
branch pod needs an explicit non-autonomous admission mode before it can be
opened safely. Adding typed branch operations first is technically possible at
the JVM protocol layer, but it would create a destructive lifecycle surface
that cannot yet be used end to end without ad hoc stopping rules. Datahike's
maintained primitives are already selected and tested; delaying their Seon
wrappers loses no storage capability.

## Dependency ledger

| Dependency or mechanism | Selected identity | Source read for this decision | Constraint |
|---|---|---|---|
| Datahike | `org.replikativ/datahike` git SHA `6f90b339768b1a02066dce3b6fcc93a200758fcc` in `:writer` and the `:cljs` override | `reference-code/datahike/src/datahike/versioning.cljc:123-373`, `connector.cljc:268-390`, and `db/transaction.cljc:822-841,1125-1157`; the checkout is exactly the selected SHA | `branch!` and `delete-branch!` are ready, but deletion requires released branch connections and roster membership is authoritative. Transaction metadata is flushed against `db-before`, which is why receipt schema must exist at genesis. None of these primitives repairs a process-local program publication split. |
| Konserve | `org.replikativ/konserve` git SHA `df6818d43ea3363a808cd051c0d68917f1b987a9` in both runtime graphs | `reference-code/konserve/src/konserve/protocols.cljc:4-36`; the checkout is exactly the selected SHA | One-key update is atomic; multi-key atomicity is optional. Datahike writes immutable roots before the mutable branch head. Seon must quiesce the sole writer for branch promotion rather than invent a cross-operation CAS. Konserve is not involved in Malli publication. |
| Kabel reference transport | `org.replikativ/kabel` `0.3.100` and `org.replikativ/konserve-sync` `0.1.35`, only in Datahike's `:test` alias | `reference-code/datahike/src-kabel/datahike/kabel/connector.cljc:199-240` and `writer.cljc:70-158`; `reference-code/datahike/deps.edn:78-105` | The connector withholds the head until the full synchronization handshake and the writer owns pending work plus subscription cleanup. These are lifecycle laws, not authorization to add Kabel to Seon's runtime. |
| Active Seon transport | Transit CLJ `1.0.333` and CLJS `0.8.280` over the existing UDS protocol | `src/seon/db/protocol.cljc`, `writer.clj`, `transport/uds.clj`, `transport/uds.cljs`, and their focused tests | Protocol v2 already carries complete coordinates and explicit attachments. Branch operations later extend this one protocol; there is no admin socket or Kabel path. |
| Malli | selected Maven `metosin/malli` `0.20.0`, release commit `4c054bd7d042e70d60b83b9f07fb765bc103037f` | The current `reference-code/malli` checkout is newer at `80138076960e7820523b4cb932c5b5d1936d4e7f`. The relevant registry/instrumentation forms were checked in `registry.cljc:17-65` and `instrument.cljs:29-130`; exact release identity is the local `0.20.0` tag. | The default registry is a mutable library atom and its setter can throw. Function instrumentation mutates live vars one symbol/accessor at a time and supplies no rollback transaction. Admission must hide mutation and verify the complete committed target before reopening. Per-generation correctness uses the explicit immutable candidate registry, not Malli's convenience default. |
| ClojureScript | `1.12.145` | `reference-code/clojurescript` runtime function conventions plus `src/seon/instrument.cljc:418-883` | Live fixed accessors, variadic accessors, and schema arity rows now have one source-grounded compatibility check before Malli mutation. |
| Canonical program projection | current `seon.schema`, `seon.instrument`, `seon.client`, and `seon.eval` | `src/seon/schema.cljc:91-361`, `instrument.cljc:526-883`, `client.cljs:830-985,2300-2420`, and `eval.cljs:4219-4435,4816-5095` | `build-projection` is pure and complete. `activate-projection!` still performs three visible mutations, and eval still commits the declaration tee before wrapper/projection publication. Boot already reconstructs forms and contracts from one database value, but the query helpers and transition are private to `seon.client`. |
| Canonical database protocol | current `seon.db.coordinate` plus protocol version `2` | `src/seon/db/coordinate.cljc`, `protocol.cljc`, `registry.clj`, `writer.clj`, and `replica.cljs` | Complete `{database-id, branch, commit-id, t}` points and stable `{database-id, branch}` attachments are implemented. The registry stores one branch-qualified attachment per logical route and derives the current coordinate from `d/db`. |

The selected writer classpath contains the maintained Datahike and Konserve
SHAs and Malli `0.20.0`; it contains no Kabel runtime dependency. ACME inherits
the same root aliases and must continue to do so.

## Reconciled transition matrix

| Required transition | Landed at current HEAD | Remaining gap |
|---|---|---|
| Fresh boot and explicit config | Sole JVM writer, typed pod replica, config reconciliation, deterministic identity, complete projection building | Destructive fresh-boot proof belongs at graduation, after admission and later lifecycle slices. |
| Config-free existing reopen | Database program facts are read and namespaces replayed from one frozen database value | Reopen has no reusable admission state. A failed publication after readiness does not make readiness false or force committed reconstruction. |
| Receipt schema genesis/reopen | The five receipt declarations are derived from Malli, installed through Datahike `:initial-tx`, and validated before publishing an existing connection | Complete. No raw receipt schema or same-transaction tx-meta workaround remains. |
| Complete coordinates | Transactions, receipts, replay, replica progress, turns, errors, autocomplete, frozen web, and config cache carry complete points | Complete for current consumers. Later branch lifecycle requests must reuse these exact shapes. |
| Whole-head stale-write rejection | Transaction requests carry expected complete coordinates and reject cross-branch or stale heads | Complete. |
| Failed program publication | Candidate build occurs before commit and exact live/schema arity admission occurs before wrapper mutation | No owner closes admission, reconstructs the accepted generation, verifies wrappers/projection, or makes readiness fail. This is the next slice. |
| Writer drain | UDS stops admission under its lifecycle lock, joins admitted handlers, and retains failed Datahike release identity | Complete JVM prerequisite. Agent-turn quiescence and operator composition remain later. |
| Branch-qualified registry attach | Explicit attachments, route/attachment bijection, physical identity agreement, roster check, and actual-connection validation are implemented | Typed native create/release/delete operations, branch launch inputs, branch-local replica/feed proof, and forensic mode are absent. Physical `fork-database!` remains. |
| Branch-local blobs | One injected writable overlay plus ordered read bases, atomic publication, and digest verification are implemented | Launch descriptors, release, promotion materialization, and retention remain tied to native branch lifecycle. |
| Clean restart versus unexpected crash | Existing recovery transaction and writer drain pieces exist independently | One runtime admission/quiescence transition, turn acknowledgement, supervisor coordination, and destructive evidence are absent. |
| Restore and undo | Maintained Datahike guarded force and historical roots exist | Intent, quiescence, promotion, stale-handle reconnect, completion facts, blob materialization, and undo are absent. |
| Ordered multi-form process failure | Per-form durable recording exists | No real kill-after-N proof yet establishes that N+1 is neither run nor fabricated. |

The PRD roadmap's current-state prose is accurate at this HEAD; no roadmap edit
is required by this reconciliation.

## Live read-only probes

The default pod and writer were probed through the repository MCP server on
2026-07-14 without writing facts.

CLJS reported:

```clojure
{:head
 {:seon.db.coordinate/database-id
  #uuid "54b5b7e7-51fb-3220-b079-81a81914d86f"
  :seon.db.coordinate/branch :db
  :seon.db.coordinate/commit-id
  #uuid "6a56f643-11e1-5da6-a7f2-f3efda99b901"
  :seon.db.coordinate/t 536870980}
 :projection-fingerprint 863918296
 :projection-schema-count 1551
 :projection-function-count 806
 :database-schema-count 1551
 :database-function-contract-count 806
 :runtime-admission-ns? false}

```

The matching writer reported protocol version 2, one `:default` route attached
to the same database and `:db` coordinate, and branch roster `#{:db}`. This
proves the current healthy state is converged, coordinates agree across CLJ and
CLJS, and no admission owner exists. Count equality is not a publication
atomicity guarantee: the current source still commits before wrapper mutation
and leaves later work open on failure.

## Next implementation slice

Implement one fail-closed committed-program publication transition. This is one
semantic unit; splitting the atom, coordinator, entry gates, and readiness into
separate releases would expose a state that claims protection while an ungated
work entry can still run.

### Owners and changes

1. **One visible Seon schema state** — `src/seon/schema.cljc`.
   Replace the independent declaration collector and active-projection atoms
   with one state atom whose candidate forms and active immutable projection
   change through one Seon-visible swap. Install one stable Malli registry
   facade over that state. Keep `relink-registry!` only for initial load or a
   Malli bundle stomp; normal generation publication must not call the
   throwable default-registry setter.

2. **One runtime admission owner** — add
   `src/seon/runtime/admission.cljs`, with the closest source authority updated
   if its durable ownership table changes. Its process-local state is only
   `:starting`, `:publishing`, `:available`, or `:unavailable`, plus the active
   projection fingerprint and an optional reason. It is disposable projection
   state, not a second database lifecycle registry. There is no force-open
   function.

3. **One database-derived projection builder** — move or expose the pure
   `schema-forms-in-db` and `function-contracts-in-db` transformations now
   private to `src/seon/client.cljs` at the schema projection boundary. The
   coordinator dereferences `db/*conn*` once, builds the complete committed
   projection from that value, prepares every wrapper, reconciles the union of
   old and committed symbols behind closed admission, verifies coverage, and
   publishes that exact projection.

4. **One post-commit owner** — `src/seon/eval.cljs` and
   `src/seon/instrument.cljc`. Replace direct delta-instrument plus
   `activate-projection!` with the coordinator. A failed delta, a thrown Malli
   mutation after an arbitrary prefix, or projection publication failure all
   enter the same repair: record one bounded core fault, reconstruct once from
   a newly frozen database value, and reopen only after exact verification.
   If repair fails, remain unavailable. Do not transact rollback facts.

5. **Gate autonomous work at admission, not low-level database access** —
   `src/seon/eval.cljs`, `src/seon/agent/loop.cljs`,
   `src/seon/agent/message.cljs`, `src/seon/agent/schedule.cljs`,
   `src/seon/agent.cljs`, `src/seon/agent/runtime.cljs`, and
   `src/seon/web/reactive/call.cljs`. Check before a batch and before every next
   eval entry, before another LLM turn, run open/renew, message persistence,
   schedule fire, agent birth/resume, delayed drive, and direct function
   invocation. Stop/complete/terminate and internal fault recording remain
   usable so the process can drain and diagnose.

6. **Join boot, hot reload, and readiness** — `src/seon/client.cljs`, the
   owning web readiness route in `src/seon/web/serve.cljs`, and
   `script/seon/dev/process.clj`. Cold reconstruction begins at `:starting` and
   opens only after full wrapper/projection verification. Shadow reload uses
   the same coordinator and must not rehost or rearm the ticker on failure.
   The operator probes an exact application-readiness route and accepts only
   `:available`; delete the stale log-substring readiness requirement.

### Focused tests

- `test/seon/schema_test.cljs`: candidate collection and active projection are
  one visible generation; normal publication does not call Malli's default
  setter; failed publication exposes neither mixed state.
- `test/seon/instrument_delta_test.cljs`: retain exact fixed/pure-variadic
  precondition regressions and add injected second-wrapper failure plus full
  union repair and coverage verification.
- `test/seon/eval/record_eval_tee_test.cljs`: accepted declaration facts
  followed by deterministic publication failure produce one real eval, no
  fabricated later entries, one owning fault, and either repaired committed
  projection or typed unavailable state.
- New focused admission tests beside the owner: idempotent close, one fault per
  publication occurrence, exact fingerprint on reopen, and refusal without
  recursive recording.
- Existing boundary suites with closed-state cases:
  `test/seon/agent_loop_test.cljs`, `test/seon/agent/message_test.cljs`,
  `test/seon/agent/ticker_test.cljs`, agent birth/runtime tests, and
  `test/seon/web/reactive/call_test.cljs`.
- Client/hot-reload and operator tests: cold failure never becomes ready;
  late failure makes status not ready; config-free restart reconstructs the
  committed fingerprint; successful hot reload rearms work only after reopen.

### Live acceptance evidence

1. Start from a healthy default cluster and record the complete writer/pod
   coordinate and projection fingerprint.
2. Through a test-only injection at the real publication boundary, accept a
   declaration transaction and throw after the second Malli replacement.
3. Query the committed `:seon.schema` and `:seon.fn` facts. Confirm exactly one
   core fault for that occurrence, admission closed during repair, and no next
   eval, turn, message, schedule, direct call, or agent birth admitted.
4. If immediate reconstruction succeeds, prove wrapper coverage and projection
   fingerprint match the facts from one frozen database value before admission
   returns available. If it fails, prove the readiness route and
   `bin/seon status` remain unavailable while MCP diagnosis still works.
5. Restart without config. Prove the same committed generation reconstructs,
   readiness becomes available, and no config or compensation transaction was
   written.

## Deletion and non-duplication constraints

- Delete per-publication `mr/set-default-registry!`; retain only the one load
  relink integration call.
- Delete direct post-commit `instrument-projection-delta!` plus
  `activate-projection!` ownership from eval; it becomes an implementation
  choice inside the one coordinator.
- Do not add another program registry, replay engine, admission registry,
  schema manifest, error census, retry counter, or rollback transaction.
- Do not gate `seon.db` reads/writes globally. Reconstruction, fault capture,
  stop controls, and MCP diagnosis must work while autonomous admission is
  closed.
- Do not add native branch operations, forensic mode, or delete
  `fork-database!` in this slice. Those form the next coherent branch cutover
  after admission is proven.

## Follow-on order

After this slice passes focused and live config-free recovery proof:

1. extend the same admission owner with explicit quiescing/non-autonomous
   modes required by operator and forensic branch processes;
2. add typed native create, release, and delete operations to the existing
   protocol/writer/registry, then delete physical `fork-database!` and its tests
   in the same cutover;
3. supply branch launch descriptors to replica/feed/blob views and prove a
   simultaneous read-only forensic pod;
4. compose clean restart versus unexpected crash with agent-turn drain;
5. implement guarded restore/undo plus blob promotion materialization; and
6. finish with destructive ordered multi-form process-failure proof.
