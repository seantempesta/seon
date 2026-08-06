---
type: research
status: active
tags: [prd, architecture, agent]
---

# PRD dependency refresh — 2026-08-06

## Scope and reading

This refresh retains all three conditional PRDs and replaces their inherited
dependency edges with current source and queue truth. The refresh read in full:

- root `AGENTS.md`;
- every file in [background-work](docs/prds/background-work/README.md),
  [operational-events](docs/prds/operational-events/README.md), and
  [in-server-tests](docs/prds/in-server-tests/README.md);
- the complete “Rulings 2026-08-06” block and all three “Rulings 2026-08-05”
  batches in the
  [active plan](docs/prds/sci-execution-runtime/plan/README.md); and
- the top working-edge block in
  [unsettled](docs/prds/sci-execution-runtime/plan/unsettled.md).

The operational pass also read the sealed
[operations and maintenance specification](docs/prds/sci-execution-runtime/plan/operations-and-maintenance-spec-2026-08-05.md)
in full. Each README's source claims were then checked against the named
current owners rather than inherited plan prose.

## Start order

The ranking is by the earliest point at which a coherent implementation unit
can begin, not by the amount of code already landed.

1. **Operational events.** The operator and exclusive maintenance request
   substrate are landed. The first event unit can begin after error-model W1
   settles the shared error boundary and the bare gate is green; alert-driven
   turns additionally wait for the messaging wave. The refreshed
   [operational-events README](docs/prds/operational-events/README.md) now
   depends on `src/seon/operator.clj` and the sealed operations specification,
   not archived operator-integration plans.
2. **Background work.** Effects, receipt settlement, blob storage, wake
   derivation, and rendering are already landed. The remaining lifecycle
   conversion waits for the messaging wave plus the owner rulings on wait
   targets and `result/<eid>` identity, then a coherent green bare gate. See
   the refreshed
   [background-work README](docs/prds/background-work/README.md).
3. **In-server tests.** The runner and indexed test-call facts are reusable,
   but dispatch, reload, process-unclean derivation, and live-cluster selection
   remain unbuilt. This unit starts after the active deletion sweep and a
   quiet-tree green bare gate; its root-agent proof additionally waits for the
   messaging wave. See the refreshed
   [in-server-tests README](docs/prds/in-server-tests/README.md).

## Owner questions — operational events

These decisions unblock the nearest retained folder.

1. **Where is an operational-event fact committed?**
   - **A — in the owning transition transaction (recommended):** the event
     producer returns pure transaction data and the operator, maintenance, or
     fault transition commits it atomically. This preserves one transition
     fact boundary but requires each owner to compose the event data.
   - **B — synchronously after the operation:** one shared emitter can be
     called after each operation settles. This centralizes emission but permits
     the operation and event fact to diverge across a crash.
2. **What stable identity appears in the paired text log?**
   - **A — a declared event id (recommended):** it remains queryable across
     later database values, but every producer must derive it honestly.
   - **B — the event entity id from `db-after`:** it visually resembles
     `result/<eid>` and stores no second id, but it is branch/basis-specific and
     does not make the event a result handle.
3. **How tightly is the fact coupled to the text log?**
   - **A — commit, then append from the transaction report (recommended):**
     the database stays authoritative and a crash can omit only the courtesy
     line.
   - **B — synchronously append after commit and fail the operation on append
     failure:** missing lines are loud, but the committed fact cannot be rolled
     back and logging failure becomes operation failure.
4. **Which significant events open an agent turn?**
   - **A — none by default (recommended):** events remain queryable rendered
     facts, avoiding surprise model work and notification state.
   - **B — selected schemas declare an explicit recipient and commit one
     addressed value:** attention is immediate, at the cost of a message/run
     policy separate from event durability. Under the August 6 redesign this
     must be a one-value send; no event may imply a reply or bare wait.

## Owner questions — background work

1. **How does a background receipt participate in target-aware wait?**
   - **A — wait on the durable effect ref (recommended):** send and background
     work share one lifecycle primitive, but the wait-target schema widens.
   - **B — return a distinct wait-target value:** validation stays local to
     background work, but one wrapper concept is added.
   - **C — retain the existing background-await disposition:** source churn is
     smallest, but parallel lifecycle syntax survives the one-wait redesign.
2. **What handle does a completed background effect render and accept?**
   - **A — `result/<effect-receipt-eid>` (recommended):** agents learn one
     syntax and no identity is allocated, but polling must accept the resolved
     ordinary value.
   - **B — keep the effect lookup ref distinct:** the data models remain
     explicit, but agents learn two result-reference forms.
   - **C — temporarily render both aliases:** discovery is easiest, but a
     transitional dual surface contradicts the one-mechanism direction.

The August 6 messaging ruling leaves one-value effect settlement intact, but
invalidates the README's old note-only wait assumption and makes the handle
identity decision explicit.

## Owner questions — in-server tests

1. **How is process-unclean test execution declared?**
   - **A — derive it from process-boundary leaf facts (recommended):** indexed
     call reachability automatically classifies new callers, at the cost of a
     small new leaf contract.
   - **B — declare it on every affected test row:** the query is direct, but
     each test author must repeat a classification that the graph could derive.
2. **What reload contract precedes an in-server run?**
   - **A — reload the selected namespaces' ordered downstream closure
     (recommended):** current edits are guaranteed visible, with a measured
     reload cost centralized in the runner.
   - **B — trust hot-reloaded Vars and refuse source/load disagreement:** side
     effects are minimized, but staleness becomes a caller-visible refusal.
3. **Which live cluster may `bin/test` choose automatically?**
   - **A — explicit `--live-cluster NAME`, otherwise exactly one answering
     cluster (recommended):** selection is deterministic, with one extra flag
     in multi-cluster development.
   - **B — use only an answering `default` cluster:** dispatch is simpler, but
     other deliberately selected development clusters cannot serve the path.
4. **Does an agent-run report create separate test-result facts?**
   - **A — keep it in the terminal eval receipt (recommended):**
     `result/<eid>` addresses one durable report and recorded gate history
     remains opt-in.
   - **B — also transact test-run and test-result facts:** historical queries
     become direct, but two durable representations need identity and
     retention rules.

The August 6 messaging redesign removes the old direct root-eval assumption:
root calls ordinary program-graph work, or sends one request value to a
namespace owner and waits on that send. Completion is exactly one report value;
the terminal eval receipt supplies the later `result/<eid>` handle, and no
reply is inferred.

## Closed documentation issue

The last residual in
[Give executable PRD briefs truthful lifecycle status](docs/seon/issues/archive/active-prd-briefs-present-superseded-designs-as-current.md)
is closed by the operational dependency refresh. No retained README now
presents archived operator integration, deleted execution paths, or
superseded messaging assumptions as its current dependency edge.
