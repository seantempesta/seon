---
type: orchestrator
status: active
tags: [orchestrator, prd, database, flow, agent]
---

# Runtime reliability refactor — working context

## Current state

The CLJS pod works end to end, but its lifecycle boundaries are blurred. A warm
new-agent request currently enters the cluster boot path, which rebuilds the
core program graph twice, reconciles broad seed state, replays code,
reinstruments functions, and revisits global services. Live measurements put
that avoidable cluster work at roughly eight seconds per mint. This chunk first
models every state transition and transaction, then removes overlap using small
data-processing functions and exact Datahike transactions.

## How to run it

```bash
bin/seon status pod
bin/seon restart pod
bin/test-cljs
curl -fsS http://127.0.0.1:7890/agents >/dev/null
```

Use the default pod for live proof. Leave the ACME pod alone unless the owner
explicitly places it in scope. For a real browser drive, use the
`browser-automation` skill; verify long-lived gzip SSE feeds server-side because
the browser bridge does not proxy them reliably.

## Load-bearing findings

- `start-agent!` currently mixes cluster boot/resume with individual agent
  minting; `/agents/new` pays the cluster-wide cost.
- The core program snapshot builders currently cost about 3.7 seconds and are
  run once for indexing and again for ghost pruning.
- Function and test indexing reread a source file for individual vars instead
  of grouping work by file.
- Datahike transactions with empty application `tx-data` are not free when
  transaction metadata is present: `txInstant` and metadata still advance the
  transaction log and wake listeners.
- `seon.state/reconcile!` reasserts the complete desired set, removes absent
  entities, but does not generally retract attributes omitted from desired
  entities. Config healing and ghost pruning compensate for those gaps.
- Transaction provenance is per datom through its transaction id. Do not add an
  entity owner/kind attribute. Security and authorization are out of scope.
- The provenance redesign starts from the minimum useful fact: a transaction
  references the actor that asserted its datoms. Add more only for a concrete,
  otherwise-unanswerable query.

## Settled — do not re-litigate

- This is a reliability and simplification refactor, not a security system.
- Authentication and authorization are deferred.
- Provenance belongs on transaction entities, not projected domain attributes.
- State and status are derived from attributes and links; do not persist labels
  that can be queried.
- No entity kinds and no generic entity ownership mechanism.
- No second implementation, `v2` namespace, or compatibility path. Fix the
  existing mechanism and delete its compensating paths after live proof.
- `datahike.api/with` may be a test oracle; production diffing is explicit,
  efficient Clojure data processing.
- Tests assert structural behavior, not context wording.
- Commit each proven phase before continuing.

## Open design questions

- Which stable actors are genuinely required beyond root, boot, config, and
  agent entities?
- Can agent entities be direct `:seon.tx/actor` refs while system actors use
  `:seon.actor/id`, or should every actor share one identity attribute?
- Which current transaction-context attributes are facts that enable real
  queries, and which are duplicated classifications that should be removed?
- For mixed-origin entities, which exact attributes may each reconciliation
  process retract?
- Can boot/config candidate datoms be found cheaply from current indexes, or is
  a narrowly constrained history query needed only for deleted identities?
- Which seed layers Datahike permits in one atomic transaction after schema
  installation?

## Ordered next steps

1. Complete the transaction and lifecycle inventory with measured costs.
2. Write the proposed actor/transaction schemas and prove the required queries
   manually against Datahike values and history.
3. Ratify the minimum provenance attributes; reject every unproven field.
4. Separate cluster boot, agent mint, agent resume, hot reload, and eval paths.
5. Build one deterministic, file-grouped core program snapshot.
6. Replace partial reconciliation with exact scoped transaction compilation and
   skip empty transactions.
7. Fold stale core/config removal into reconciliation; delete ghost pruning and
   config healing.
8. Scope replay, instrumentation, listeners, servers, and timers to the correct
   lifecycle.
9. Run restart, agentic workflow, browser, SSE, CPU, and RSS acceptance drives.

## Entry points

- [[roadmap]] — current gap, phases, and graduation criteria.
- [[provenance-and-lifecycle-design]] — minimal data model and transition
  inventory under review.
- [[docs/seon/architecture/agent-runtime]] — ideal agent runtime.
- [[docs/seon/architecture/data-model]] — ideal entity and transaction model.
- `src/seon/client.cljs` — current boot/index/replay orchestration.
- `src/seon/state.cljs` — current reconciliation implementation.
- `src/seon/db.cljs` and `src/seon/db/internal.cljs` — database boundary and
  transaction metadata.
- `reference-code/datahike/src/datahike/db/transaction.cljc` — transaction,
  metadata, upsert, retract, and component semantics.
