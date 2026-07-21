---
title: Exact frozen transaction recovery
type: research
status: complete
tags: [research, prd, database]
---

# Exact frozen transaction recovery

## Decision

Strengthen the one existing `seon.db/transact!` submission. One invocation
freezes one protocol transaction request and owns it until the authority
returns a terminal result. On an ambiguous timeout or disconnect, that same
invocation reopens the existing database session and redelivers the same
immutable request:

- the same `:seon.db.protocol/request-id`;
- the same `:seon.db/db` and optional `:seon.db/expected-db`;
- the same once-encoded transaction data;
- the same transaction metadata; and
- the same generated candidates.

Do not regenerate any of those values during transport recovery. In
particular, `seon.db.id` remains the generated-identity conflict owner, not a
delivery retry owner. It regenerates candidates only after the existing exact
generated-candidate conflict.

Keep the current owners:

- `seon.db/submit-transaction!` freezes the request and resolves its result;
- `seon.db.transport.uds` owns the Bun socket, framing, physical pending
  requests, deadlines, cancellation, and backpressure;
- `seon.db.writer` owns active request execution and the sole durable Datahike
  receipt; and
- Datahike's existing writer owns serialized application and durable commit.

No retry queue, outbox, transaction registry, second receipt, public request
id, caller idempotency key, or versioned function is needed.

The one protocol clarification uses an existing name. A duplicate request id
that is still active returns the existing request-conflict error with
`:seon.db.protocol/running? true`. A committed receipt with the same request id
and a different transaction remains the current terminal request conflict and
does not carry `running?`. The frozen invocation may wait and redeliver after
the former; it must return the latter.

## Shortest falsifier

The current session does not retain enough information for exact recovery.

`seon.db.transport.uds/request-map!` encodes a frame and stores only
`resolve`, `reject`, and a deadline in `!pending`. `!output` retains the frame
only until Bun reports its bytes accepted. After full acceptance, neither
collection contains the message or transaction bytes. At a deadline,
`expire-deadlines!` replaces the callbacks with only `timed-out?`, rejects the
caller, sends cancellation, and later discards the response. At session close,
`terminate!` clears both collections.

Therefore this trace loses the only recoverable request:

1. `submit-transaction!` builds a request.
2. Bun accepts its complete frame.
3. The authority commits, or may still be committing.
4. The response is lost or the caller deadline fires.
5. `submit-transaction!` returns an error, releasing its lexical request.

The durable receipt may prove the commit, but no remaining client owner can
ask it with the same request. The focused UDS deadline test currently proves
this behavior: it retains only request identity and capacity, discards the late
response, and then permits reuse of the id.

## Dependency ledger

| Dependency or owner | Selected source | Constraint used |
|---|---|---|
| Seon checkout | `7f732e2e1b25e72374b3eafeb153df1006663f79` plus unrelated shared work | This report owns no source changes and does not freeze other lanes. |
| Required message seam | [[message-idempotent-delivery-seam-2026-07-16]] | Composite message transitions depend on exact transaction recovery, not domain deduplication. |
| CLJS database facade | `src/seon/db.cljs:224-245,278-381,397-402,522-551` | The request is currently frozen once, but only until the first transport result; session opening already coalesces concurrent opens for one selection. |
| Generated identity owner | `src/seon/db/id.cljc:1331-1373` | Candidates and transaction data are computed before `db/transact!`; only an exact generated-candidate conflict may rebuild them. |
| Bun UDS owner | `src/seon/db/transport/uds.cljs:291-318,400-505` | Pending correlation survives a timeout, but the message, callbacks, and accepted frame do not; close clears all physical state. |
| UDS proof | `test/seon/db/transport_uds_test.cljs:500-586` | Current tests prove late-response discard, id reuse only after physical retirement, and the 16-request physical bound. |
| Database protocol | `src/seon/db/protocol.cljc:630-638,684-693,752-758,853-863,1208-1223,1380-1390` | One immutable request already contains every receipt input; `running?`, `canceled?`, and `recovered?` already exist. |
| JVM request owner | `src/seon/db/writer.clj:1141-1230,1243-1300,2505-2540,3501-3550,3564-3651` | Receipt recovery precedes another transaction; connection close cancels and drains physical work; a duplicate active id is currently indistinguishable from a durable hash conflict. |
| Executor cancellation | `src/seon/db/executor.clj:340-414,656-684` | Queued work can be removed; running work is marked canceled but is not interrupted, so its database effect may still win. |
| Receipt proof | `test/seon/db/request_receipt_test.clj:75-146,410-429` | Exact repeated and concurrent delivery commits once; changed data under one id is rejected. |
| Maintained Datahike | `reference-code/datahike` at `a464cd887458d2572414a6ea951c477b0981fdae` | `LocalWriter` serializes operations per connection; `transact!` returns physical completion and publishes listeners only after its writer result. |
| Bun | `reference-code/bun` at `be77b652884b16a103cfaa4af3c1102f72f2dcd3` | JavaScript socket `write` reports accepted bytes; with ordinary writes Bun does not retain the unwritten suffix for the caller. |

## What is frozen

The recovery unit is the ordinary immutable protocol request map built by
`protocol/transaction-request`. `internal/encode-edn-slot-values` must run once
before that map enters recovery. The map already includes the database value,
expected database value, transaction metadata, and generated candidates used
by `logical-transaction-hash`.

“Same transaction bytes” means the encoded values in
`:seon.db.protocol/transaction-data` are unchanged. It does not require a Bun
`Uint8Array` to escape the UDS owner. UDS may Transit-encode the same immutable
map again after reconnect. The durable authority compares the logical request
hash, not incidental socket-frame identity. Keeping a native frame in the
facade would duplicate memory, couple `seon.db` to Bun, and provide no stronger
receipt guarantee.

The current invocation already has the necessary map at
`src/seon/db.cljs:526-537`. The missing behavior is to keep that lexical value
through ambiguous delivery instead of returning the first transport error.

## One recovery path

Implement the behavior privately under the current `submit-transaction!`; do
not add another public transaction function.

1. Acquire the database value and build the protocol request exactly once.
2. Capture the current session selection beside that immutable request.
3. Submit through the current UDS session.
4. If a terminal protocol result arrives, return it through the current native
   report or direct-error projection.
5. If the session closes after delivery may have begun, call the existing
   `open-session!` with the captured selection. Concurrent recovering
   transactions naturally share its existing `opening` promise.
6. Redeliver the same request after the session is acquired.
7. If the authority says the id is still active, identified by
   `request-conflict` plus existing `running? true`, wait briefly and redeliver
   the same request. Do not inspect error prose.
8. If the durable receipt matches, return its existing recovered report. If no
   receipt exists because queued work was canceled, the same request commits
   normally. If a durable receipt has a different hash, return the current
   terminal request conflict.

The invocation, not a shared registry, is the retry state. Multiple recovering
invocations share only the already-existing session opening and authority
admission. The UDS physical pending table remains the sole in-process
correlation and capacity owner.

### Deadline behavior

A write deadline cannot truthfully mean “the transaction did not happen.” For
transaction requests only, expiry should start the existing cancellation but
retain the original Promise callbacks until physical completion or session
close. Reads keep their current detach-and-reject deadline behavior.

This small UDS distinction prevents the facade from polling a timed-out local
pending id. It also preserves the late authoritative response:

- queued cancellation returns a failure marked with the existing
  `:seon.db.protocol/canceled? true`; `submit-transaction!` redelivers the
  frozen request;
- running cancellation cannot revoke Datahike work; `finish-transaction!`
  checks the receipt after the canceled executor outcome and returns recovered
  success if the commit won; and
- session close rejects the retained callback, after which the facade reopens
  and redelivers.

Marking a physically canceled transaction failure with existing `canceled?`
is preferable to treating every database error as retryable. It makes the
recovery predicate data, preserves terminal validation and Datahike errors,
and introduces no second error taxonomy.

An outer process supervisor remains the answer to work that never physically
completes. Closing the shared session merely to manufacture a transaction
timeout would also tear down unrelated reads and interests and is not the
transaction owner's decision.

## Exact failure and cancellation behavior

| Observation | Authority state | Required result |
|---|---|---|
| UDS rejects before accepting a frame | No delivery is possible | The same invocation may immediately submit the frozen request again on the live session. |
| Deadline cancels queued executor work | No transaction ran and no receipt exists | Return `canceled? true` to the invocation; redeliver the same request. |
| Deadline marks running executor work canceled | Datahike may commit despite cancellation | Let physical completion reach `finish-transaction!`; its receipt check returns recovered success if commit won, otherwise redeliver after the explicit canceled result. |
| Socket closes with an accepted request | Commit is unknown | The writer silences the dead callback, cancels, and drains the old connection; reopen and redeliver the frozen request. |
| Redelivery overlaps the old active request | The original has not physically retired | Return request conflict with `running? true`; wait and redeliver. |
| Receipt exists with matching hash | The exact transaction committed | Return the reconstructed report with existing `recovered? true`; never commit or notify listeners again. |
| Receipt exists with a different hash | The id was reused incorrectly | Return terminal request conflict without `running?`; never retry. |
| Expected database or generated candidate is stale before any commit | Transaction did not commit | Return the existing terminal conflict. `db.id` alone may rebuild after its exact candidate conflict. |
| Datahike returns an ordinary validation or storage failure and no receipt exists | Commit failed | Return the ordinary direct error; do not classify all database errors as ambiguous. |

Datahike's maintained `LocalWriter` applies operations in connection order and
may batch their durable commit. Once `d/transact!` has been dispatched,
transport cancellation is not rollback. The writer receipt in the transaction
metadata is therefore the only correct commit oracle.

## Session and interest interaction

The current close callback clears the process session, including interest
handlers. Exact transaction recovery may reuse the current selection and the
existing coalesced open, but it must not create a second reconnect owner.

The broader session owner must eventually re-establish committed interests
after reconnect. That is an existing session-recovery gap, not a reason to put
listener restoration inside transaction recovery. This cut proves transaction
outcome only. It must not add a transaction-specific listener registry or
silently claim that a reopened socket retained old physical interests.

## One owner and deletion inventory

Retain and strengthen:

- `seon.db/submit-transaction!` as the one request-freeze and recovery owner;
- `seon.db.transport.uds/request!` as the one physical submission owner;
- `seon.db.writer` active requests as the one running-work owner;
- the transaction entity receipt as the one durable result owner; and
- `seon.db.id/allocate!` as the one generated-candidate conflict owner.

Replace or delete:

- the current `submit-transaction!` behavior that returns the first transport
  timeout/close as a terminal transaction error;
- the transaction-specific part of UDS timeout handling that drops callbacks
  and discards the late response;
- the undifferentiated active-duplicate response that uses the same shape as a
  durable request hash conflict; and
- any caller retry that reruns `message!`, `complete`, `delegate!`, an
  allocation builder, a timestamp, or a database acquisition after ambiguous
  delivery.

Do not create `transact-v2!`, `retry-transact!`, a remote transaction facade,
a compatibility namespace, or parallel timeout semantics in another
transport.

## Proof plan

### CLJS facade and generated identities

- Freeze one request, force a close after full frame acceptance, reconnect,
  and assert both deliveries contain equal request id, database value,
  transaction data, metadata, expected database, and generated candidates.
- Count calls to the transaction builder and candidate generator: transport
  recovery calls each once; an exact generated-candidate conflict may call
  them again under the existing allocator policy.
- Recover a committed request and assert the ordinary native report carries
  `:seon.db.id/recovered-commit? true` with the original generated entity ids.
- Return a durable changed-hash request conflict and assert there is no retry.
- Run several simultaneous disconnect recoveries and prove they share one
  `open-session!` operation rather than creating per-transaction sockets.

### UDS

- Preserve the current read deadline test unchanged.
- Add a transaction deadline test proving cancellation is written while the
  original callbacks and capacity remain physically owned.
- Deliver a late transaction response and prove it settles the original
  Promise instead of being discarded.
- Close after full frame acceptance and prove the Promise rejects so its
  facade invocation, not UDS, owns reconnect.
- Keep the existing short-write, drain, maximum-pending, protocol-error, and
  close proofs; recovery must not weaken their bounds.

### JVM writer and Datahike

- Assert an active exact duplicate returns request conflict with `running?
  true`; a committed changed-hash receipt returns request conflict without it.
- Cancel before executor start, redeliver exactly, and prove one commit and one
  listener event.
- Cancel after Datahike starts but before response delivery, redeliver exactly,
  and prove recovered success, one commit, and one listener event.
- Disconnect during a running transaction, reconnect before the old connection
  finishes draining, observe `running? true`, then recover one commit.
- Retain the current concurrent exact-delivery and changed-data receipt tests as
  the durable graduation gate.

## Latency and memory implications

The successful path adds no network hop, database lookup beyond the receipt
check already performed by the writer, timer, or copy. It retains the request
map for the same Promise lifetime that already awaits the response.

Ambiguous delivery adds only necessary work:

- an in-session cancellation and physical completion when a deadline fires;
- one existing session negotiation/acquisition after disconnect; and
- one receipt-resolution round trip, with short waits only while the original
  request is still active.

The frozen ordinary map is retained once per unresolved transaction. UDS still
owns at most its current bounded physical pending requests and transient
encoded output. Do not retain a second `Uint8Array` beside the map after Bun
accepts it. This avoids transaction-size-proportional duplicate native memory
while allowing UDS to encode again only on the rare reconnect path.

The design improves tail correctness without changing normal latency. It also
removes expensive domain-level retries that would reacquire a database value,
recompute a transaction, regenerate identities, and potentially commit
duplicate work.

## Ordered implementation boundary

Implement and prove this seam before relying on atomic completion or delegation
transactions for ambiguous delivery:

1. distinguish active request ownership with existing `running?`;
2. preserve transaction callbacks through deadline cancellation and mark a
   physically canceled transaction with existing `canceled?`;
3. make the current private `submit-transaction!` retain and redeliver its one
   frozen request through the existing session open; and
4. prove queued cancel, running cancel, accepted-frame disconnect, overlapping
   reconnect, recovered generated ids, one commit, and one listener event.

Graduation is one unversioned `transact!` path whose normal result is unchanged
and whose ambiguous delivery resolves through the existing durable receipt.
