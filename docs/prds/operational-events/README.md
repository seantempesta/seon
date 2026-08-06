---
type: prd
status: active
tags: [prd, runtime, observability, operator]
---

# Operational events: significant facts, noHistory gauges, referenced logs

## Status and refresh basis

Active, but implementation is queued. The operator and maintenance dependencies
have landed; this folder now waits for the current cleanup wave's green bare
gate, the error-model W1 contract it sits beside, and the messaging redesign
before any event-to-agent attention path is implemented. The landed exclusive
sweep and operations/maintenance slices are dependencies, not remaining waits.

For this 2026-08-06 refresh I read the repository `AGENTS.md`, this folder,
`src/seon/operator.clj`, and the sealed
[operations and maintenance specification](../sci-execution-runtime/plan/operations-and-maintenance-spec-2026-08-05.md)
end to end. I also checked the current error, Flow, wake, message, schedule,
schema, gauge, logging, operator-test, and Flow-test owners, plus the ruled
2026-08-05/06 batches and current working edge.

## Decision retained

ONE operational-observation model has three honestly different destinations:

1. **Significant events become durable facts.** Lifecycle transitions,
   refusals, degradations, backstop firings, and recovery actions that
   forensics, recovery, or an owner query may need are facts. The event carries
   a namespaced subject marker and evidence attributes; it does not carry a
   `:type`, `:kind`, severity, acknowledgement, or read marker.
2. **Last-observation gauges are `:db/noHistory` attributes.** Values worth
   showing but not retaining as a time series live on the entity they describe.
   The current value remains queryable while superseded values disappear from
   history.
3. **Text logs remain the complete process stream and link significant facts.**
   Foreign output and boot-before-database output remain text only. The exact
   coupling between a committed fact and its referenced log line is an open
   owner decision below; this README no longer claims an emitter that has not
   landed.

High-frequency observation such as streamed progress and render churn stays on
channels and derived surfaces under the transport law. This PRD adds no fourth
destination.

## Current re-grounding

Line references in the first column identify the pre-refresh README that this
revision replaces.

| Prior claim | Current evidence | Verdict |
| --- | --- | --- |
| The archived operator-integration PRD was the live predecessor and operator dependency (`README.md:11-12,51`). | The public operations owner is now `src/seon/operator.clj:56-167,176-233,375-421,446-521,744-791`; the operations specification is sealed at `docs/prds/sci-execution-runtime/plan/operations-and-maintenance-spec-2026-08-05.md:13-15`; the working edge records the landed wave at `docs/prds/sci-execution-runtime/plan/unsettled.md:66-74`. | Stale. The archived PRD is historical only; current operator Vars and the sealed specification are the dependency. |
| `:db/noHistory` still needed dependency verification before use, and history should return nothing (`README.md:48,93-94`). | Malli properties map to Datahike at `src/seon/schema/datahike.clj:232-244`; shipped gauges are declared at `resources/seon/schemas/seon.ai.model.edn:18-23`; their transaction data is built at `src/seon/ai.clj:877-901`; `test/seon/ai_test.clj:270-314` proves the current value remains visible while the superseded value is absent. | Stale. The bridge, first gauges, and regression have landed. New gauges reuse this owner and prove the same actual semantics. |
| Events should copy a resolvable `:seon.event/process` string onto each event (`README.md:57-60`). | Current provenance is a transaction ref (`resources/seon/schemas/seon.db.edn:111`), and current writers attach it as `:seon.db/process` transaction metadata, for example `src/seon/cluster.clj:1217-1221,1277-1281`. | Stale. Event provenance is derived from transaction metadata; the event does not duplicate process provenance as a domain attribute. |
| Events should reuse one fault-committer pattern by adding an event arm beside it (`README.md:46,77-78`). | `seon.error/commit-tx` is pure transaction data (`src/seon/error.clj:733-859`); escaped core faults alone enter the dedicated `:io` proc (`src/seon/flow.clj:741-814`), and the current fault graph has exactly that proc (`src/seon/flow.clj:840-845`). | Stale. A channel cannot be the only copy of a durable event. The open choice is owner-transaction composition versus a synchronous standalone event transaction, not another committer proc. |
| One emitter already had a coherent fact-and-log write boundary (`README.md:29-36,64-73`). | The detached operator redirects stdout/stderr to `seon.log` (`script/seon/fresh_operator.clj:55-68`); `seon.operator/rotate-logs!` only bounds that file (`src/seon/operator.clj:375-410`); first-party lifecycle output still calls Timbre directly (`src/seon/cluster.clj:549-579,1427-1434`); error log text is merely a derived value (`src/seon/error.clj:584-653`). | Unbuilt. There is no current shared log emitter and no atomic database/file boundary. |
| A durable event fact could be treated as a wake or notification implicitly (`README.md:89-100` relied on an event feed without naming the wake boundary). | Current wake routing is datom-selective, and message delivery wakes through `:seon.cluster.message/to` (`src/seon/cluster/message.clj:11-16`; `src/seon/cluster/wake.clj:163-229`). The 2026-08-06 ruling requires explicit addressing and deletes inferred replies (`docs/prds/sci-execution-runtime/plan/README.md:790-817`). | Clarified. An event fact alone wakes no agent. Any attention message is a separate, explicit addressed value under the redesigned messaging contract. |

## Dependency ledger

| Dependency | Current owner and ruling | Boundary for this PRD |
| --- | --- | --- |
| Operator lifecycle and maintenance results | `src/seon/operator.clj:56-167,176-233,375-421,446-521,744-791`; `src/seon/schedule.clj:232-281,382-440,469-520`; sealed operations specification `:13-69` | Start, stop, publish, cleanup, refork, census, reaping, collection, log rotation, and scheduled settlements emit through the event contract without growing verb-local schemas or logging policy. This dependency is landed. |
| Error facts and core-fault flow | `resources/seon/schemas/seon.error.edn:37-73`; `src/seon/error.clj:733-859`; `src/seon/flow.clj:741-845`; error-model W1 in the active queue | Events sit beside errors and reuse pure transaction-data composition, open schemas, named render producers, and transaction provenance. Events do not become a second error class or reuse `:seon.error/kind`. Exact adjacency waits for W1. |
| Datahike schema projection and gauges | `src/seon/schema/datahike.clj:232-244`; `resources/seon/schemas/seon.ai.model.edn:18-23`; `src/seon/ai.clj:877-901`; `test/seon/ai_test.clj:270-314` | New gauges use `:seon.db/no-history? true`; their regression proves current-value visibility and disappearance of superseded values. No new gauge mechanism is needed. |
| Messaging and wakes | 2026-08-06 rulings at `docs/prds/sci-execution-runtime/plan/README.md:778-817`; current pre-redesign seams at `src/seon/cluster/message.clj:135-182,306-423`; wake owner at `src/seon/cluster/wake.clj:163-229` | Event commits do not wake. Any event attention path waits for the messaging wave and uses explicit addressing, one admitted value, and no inferred reply. |
| Result handles | 2026-08-06 ruling at `docs/prds/sci-execution-runtime/plan/README.md:778-789`; evaluation receipt reader at `src/seon/eval/drive.clj:175-231` | `result/<eid>` is an evaluation-result handle, not automatically an event identity. It can occur as ordinary content in a one-value message, but event/log reference identity waits for the owner ruling below. |
| Log file ownership | `script/seon/fresh_operator.clj:55-68,126-128`; `src/seon/operator.clj:375-410`; current first-party calls at `src/seon/cluster.clj:549-579,1427-1434` | The operator owns file location and retention. This PRD must consolidate first-party significant-event logging without claiming atomicity across Datahike and the file. |
| Render discovery | Render producers declared on current error shapes at `resources/seon/schemas/seon.error.edn:37-72`; event schema family is absent from `resources/seon/schemas/` | Significant event shapes declare named `:seon.render/ai` and `:seon.render/html` producers. The generic value floor is fallback only. |
| Active queue | `docs/prds/sci-execution-runtime/plan/unsettled.md:19-41,56-61` | Begin implementation only after the cleanup wave reaches a green bare gate, error-model W1 settles the adjacent fact contract, and the messaging wave lands for any alert path. The operations/maintenance and exclusive-sweep slices are already landed and are not blockers. |

## Messaging redesign effects

The 2026-08-06 redesign changes this folder in four precise ways:

- An event fact is not a message and does not wake an agent. Attention, if
  selected, is one explicitly addressed `my.message/send` value; no recipient
  or reply is inferred from an event's subject.
- The sent payload is exactly one admitted value. A producer may send an event
  reference or a vector containing prose plus data, but this PRD adds no
  variadic or text-only alert API.
- `my.run/wait` takes the return of a send and belongs to an agent awaiting
  another agent. Lifecycle code and the event emitter never manufacture a
  wait, and a bare wait is refused.
- `result/<eid>` names an evaluation receipt result and is an ordinary value
  when sent or completed. It does not settle whether operational events use a
  declared identity or their Datahike entity id in log references.

## Implementation order after rulings

1. Settle the four owner questions below and record the chosen fact/log/wake
   contract.
2. Declare the event attributes and renderable entity shapes, then implement a
   pure transaction-data constructor or the ruled synchronous transaction
   boundary. Provenance stays in transaction metadata.
3. Convert operator lifecycle and maintenance settlements at their owning
   transaction boundaries. Do not add verb-local emitters.
4. Add only genuinely new gauges through the landed no-history path and reuse
   its regression shape.
5. Add the event AI/HTML producers and debug-surface query.
6. Consolidate first-party significant-event log output under the ruled
   post-commit path; retain raw text for foreign and boot-before-database
   output.

## Falsifiers

- A cluster start/stop/refork round-trip yields queryable event facts and the
  operator log links each fact using the ruled identity.
- Two gauge updates leave the current value visible through both the current
  database value and history, while the superseded value is absent.
- Event provenance is queryable through transaction metadata; no copied
  `:seon.event/process` attribute exists.
- A crash at every fact/log boundary preserves the ruled authority direction
  and never reports a log-only event as committed.
- A significant event alone opens no run. If explicit attention is selected,
  exactly one addressed one-value message wakes the selected agent and no
  inferred reply is produced.
- The event feed renders on the debug namespace page and in agent context
  through declared producers; no significant event falls to the generic value
  floor.
- Store growth from one day of normal operation is measured before the slice
  graduates.

## What not to build

- no log levels as config-gated fact admission;
- no event type, kind, or severity enum;
- no acknowledgement, read marker, notification queue, or stored render;
- no copied process provenance on event entities;
- no second gauge, message, wake, recursive-delete, or operator path;
- no retention machinery before measured event volume requires a separate
  owner decision; and
- no `result/<eid>` alias for event identity unless the owner explicitly rules
  that result handles generalize beyond evaluation receipts.

## Open design questions (2026-08-06)

1. **Where does the durable event commit happen?**
   **A — compose pure event transaction data into each owning transition
   (recommended):** lifecycle state and its event commit atomically, at the
   cost of touching each owner; external actions still commit immediately
   after their terminal result. **B — use one synchronous standalone event
   transaction after every source operation:** one commit surface is simpler
   for producers, but even database-backed transitions can be torn from their
   event by a crash.
2. **What identity does a referenced event log line carry?**
   **A — a declared `:seon.event/id` derived from the source transition
   (recommended):** stable and queryable across later database values, but each
   producer must supply an honest derivation. **B — the event entity id from
   `db-after`:** aligns visually with `result/<eid>` and stores no extra
   identity, but is branch/basis-specific and does not make events result
   handles.
3. **How is the database fact coupled to the text log?**
   **A — commit first, then derive/append the line from the transaction report
   (recommended):** the fact remains authoritative and a crash may omit only
   the courtesy line. **B — synchronously append after commit and fail the
   operation if append fails:** makes missing lines loud, but cannot roll back
   the already-committed fact and turns logging failure into operation failure.
4. **Which significant events open an agent turn?**
   **A — none by default; events are queried and rendered (recommended):** zero
   surprise model work and no notification state. **B — selected event schemas
   declare an explicit recipient and commit one addressed value:** immediate
   attention, at the cost of an additional message/run policy that must remain
   separate from event durability.
