---
type: landing
status: committed, unadopted (root adopts P0 and P2 together)
created: 2026-09-25
scope: lost-error seams P1 probe (read-only) and cut P2 (delivery-evidence preparation), design lost-error-seams-design-2026-09-25.md §0.1, §C.2, §E.2
---
# Landing: seams P1 probe and cut P2

## Broken or unproved

- **Nothing was run.** The lane ran no tests, loaded no namespace, and ran no scratch probes beyond those below.
- **Adoption order.** The schema must be installed before the code. The new `commit-call` queries
  `:seon.error/notified`, and `delivered-to` pulls it. The new attributes are `:seon.error/notified`,
  `:seon.error.occurrence/effect` and `:seon.error.occurrence/related`. On a store without them, that query can
  refuse, which turns every fault recording into a panic. Adopt the schema resources and consumers together, and
  prove the addition on a branch of the live store first.
- **`escalate-to "root"` is not shipped.** The value lives in `config/default.edn`, which is outside this grant.
  Until it ships, `delivered-to` is empty for any unattributed fault in a cluster that has not configured root.
  That is honest evidence; R4 turns it into a panic.
- **P1 finding: saturated control deadlocks graph stop.** This is new, and is recorded in design §0.1. A proc that
  exits on its own stop request never reads its control tap again. After about 20 further graph commands, every
  Flow `send-command` blocks, including `flow/stop`, which holds Flow's lock. It reproduced: 10 command threads
  were blocked plus the stopping thread. It was recovered by interrupting only the probe's own 11 threads, found by
  their stack frames in `tmp.lost_error_seams$eval12493164`. **One scratch graph's `:q` proc may remain parked** in
  default, with small memory; it goes away at the next restart. P1 production must fix this ordering first.
- **Routing is explicitly incomplete until R4.** The receipt now reports delivery, but policy does not yet require
  it, and the `:your-run`/`interrupted?` routing is unchanged.

## P1 probe (read-only)

`tmp/impl-seams-p1/probe.clj`, run in scratch namespace `tmp.impl-seams-p1.probe` with a stub recorder, no
database writes and bounded waits.

The fault port is a `WritePort` that commits its `Handler` under that handler's lock. The `ProcLauncher` wrapper
substitutes it for `::flow/error` in the `:outs` it forwards.

| Case | Result |
|---|---|
| `send-outputs` failure (`{:out 5}`) | disposed once: `{:pid :p :cid :in :count 1}`; the graph error channel is empty |
| transform throw | the same |
| more than 100 reports | 150/150 disposed; the graph error channel is empty |
| report racing `flow/stop` (the stop transition throws) | disposed, `{:pid :p :count 1}` |
| stalled recorder, `:record` | `:p` gives no answer, `:q` is `:running` |
| stalled recorder, `:panic` | log is `[:stop-request :record]` |
| failing recorder, `:panic` | `[:stop-request :record :emergency]`, with the emergency evidence captured |
| failing recorder, `:record` | `[:record :stop-request :emergency]` |
| failing emergency printer | `[:stop-request :record :emergency :emergency-failed]`; nothing escapes |
| saturated control and contended stop | **deadlock** (above) |

## P2: what changed

| Path | Change |
|---|---|
| `src/seon/error.clj` | `notification-id` is extracted from `message-tx`, one derivation that keeps the first-opening special case. `commit-call` records its selection: on a new opening it retracts the error entity's previous `:seon.error/notified` refs and asserts the new messages' tempids; a recurrence keeps the set. A request naming `:seon.error/obligation` whose occurrence exists returns only the process row, so no recount and no message. `recording` builds the obligation occurrence identity without the turn or process. Declared references are stored as refs: `:seon.error.occurrence/effect` and `:seon.error.occurrence/related` |
| `src/seon/fault.clj` | New `delivered-to` reads this transaction's own `:tx-data` plus `:db-before`, never the batch `:db-after`, as the selection. `record!` returns `[fact outcome occurrence delivered-to]` on every path. `fault!` reads the dial **before** recording and calls the owner's `:seon.fault/stop-request!` first under `:panic`, or after an uncommitted `:record`. A throw from it is kept as suppressed, never preventing the recording. `fault!` forwards only the declared `:seon.error/references` members. The receipt gains `:seon.fault/delivered-to`. F08: the last resort no longer prints `(str fault)` |
| `resources/seon/schemas/seon.fault.edn` | the 4-tuple recording; `:delivered-to`; `:stop-request!`; context members |
| `resources/seon/schemas/seon.error.edn` | `:notified` (on the error entity), `:obligation`, `:related-occurrence`, `:references`; observation and commit request members |
| `resources/seon/schemas/seon.error.occurrence.edn` | `:effect`, `:related` |
| `test/seon/cluster/fault_message_test.clj` | `the-receipt-names-the-writers-selected-recipients-and-references-persist`; `panic-requests-the-owners-stop-before-it-records` |

**Design corrections found while building.**
- The selection lives on the **error entity**, not the occurrence. A recurrence in a new process is a new
  occurrence of the open signature.
- The submission id is **not** stored, because its type has no single storable form; the effect ref is the durable
  reference.

Line counts: source −33 / +107; schema −5 / +37; tests +96. This is over the ~100-line guide for added source. The
additions are the delivery evidence, reference forwarding and obligation identity the reviewed design names. The
matching deletions are R1's and R4's, which are sequenced after this cut.

## REPL evidence

These runs used scratch copies of the new `notification-id`, `message-tx`, `commit-call` and `delivered-to`, in
`tmp.impl-seams-p2.scratch`, on `datahike.api/with` values of default's database with the new attributes
installed speculatively. `seon.error/recording` produced each request, and its `:db.fn/call` was pointed at the
scratch `commit-call`. Eight transactions took 80 ms in total.

- First fault: `delivered-to #{"root"}`, 1 new message, `notified ["4c8ecd14e622"]`.
- Recurrence, new process: `#{"root"}`, 0 new messages, the same notified set.
- Resolved, then recurring with no escalation owner: `#{}`, and notified is cleared. The old message is not
  counted.
- Resolved, then recurring with root: `#{"root"}`, 1 new message with a new id, `"d610e4231528"`.
- Obligation `"wake-1"` twice: count 1 after both, and the second transaction has 2 datoms (process row only).

Armed-contract validation used the edited schema files compiled over the live projection registry: 109 forms
compile. `:seon.fault/recorded` with `delivered-to`, `:seon.fault/context` with the new members, both the 4-tuple
and the failure `:seon.fault/recording`, the observation with references, and an empty `:delivered-to` all
validate.

## Deleted

- `message-tx`'s inline id derivation (1 line), replaced by the one `notification-id`.
- `fault!`'s post-write dial read, moved (4 lines).
- `record!`'s `(str fault)` last resort.

The committer, callback and `:your-run` deletions are R1 and R4.

## Root runs (after adopting schema and code together)

- `bin/test-check default --ns seon.cluster.fault-message-test`
- `bin/test-check default --ns seon.fault-test`
- `bin/test-check default --ns seon.cluster.fault-storage-test`
- `bin/test-check default --ns seon.error-test`
- `bin/test-check default --ns seon.flow-test`, because the committer still calls `record!` and destructures the
  tuple
- `bin/test-check default --ns seon.cluster-test`
- P0's list: `seon.instrument-test`, `seon.background-test`, `seon.ai-test`, `seon.sci.eval-test`
