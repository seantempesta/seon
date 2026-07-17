---
title: Message idempotent delivery seam
type: research
status: complete
tags: [research, prd, database, agent]
---

# Message idempotent delivery seam

## Decision

Keep `seon.agent.message/message!` as the one public message write function and
keep its current request and result unchanged. Do not add a message idempotency
key, delivery function, queue, receipt entity, sent flag, dedupe registry, or
versioned API.

The retained public result remains:

```clojure
{:seon.agent.message/id message-id
 :seon.agent.message/hops hops}

```

A failure remains a direct map with `:seon.error/message`. The public request
continues to accept content, optional sender, normalized recipient refs,
optional `:core` origin, and optional `:seon.db/db`. It does not accept a
caller-selected message id or database request id.

Retry safety comes from putting each domain transition and its message in one
existing Datahike transaction:

- `lifecycle/complete` writes the run result, optionally creates its result
  message, closes the run, and retracts the agent's run ref in one transaction;
  and
- `agent/delegate!` creates the child and its initial task message in one
  identity-allocation transaction, then hosts the already-committed child.

The message namespace remains the owner of recipient normalization, sender and
origin validation, hop derivation, the fully formed message row, human plan
rows, and the concise result projection. Factor its existing construction into
private pure transaction-data functions and reuse those functions inside the
two composite transactions. That is one writer owner with internal
composition, not a second message API.

The existing database transaction receipt handles an ambiguous acknowledgement
only when the exact frozen transaction request is redelivered with the same
`:seon.db.protocol/request-id`. A later invocation normally has a different
request id, database value, timestamp, generated candidates, and transaction
hash. The receipt therefore does not, and cannot, make arbitrary later
`message!`, `complete`, or `delegate!` calls equivalent.

Completion already has the stable identity it needs: the current run and its
run-pointer fence. Delegation has no stable operation identity beyond the child
it creates. A wholly independent `delegate!` invocation with no known child id
is a new delegation; the same purpose and content may intentionally create two
children. Do not guess that it is a retry.

## Dependency ledger

| Dependency or owner | Selected source | Constraint used |
|---|---|---|
| Seon checkout | `001ae2246e7330ed4f861dc0f531bb350a3330a4` plus dirty shared message work | The native message cut is present in the working tree; this report does not edit or freeze that owned source. |
| Existing message cut | [[message-native-result-database-value-cut-2026-07-16]] | Keep one database value, direct errors, native allocation, one message writer, and the `{id, hops}` domain result. |
| Lifecycle cut | [[agent-lifecycle-native-result-database-value-cut-2026-07-16]] | Completion retry was explicitly unsettled pending a stable message/receipt design. This report replaces its sequential result-send-close recommendation with one transaction. |
| Message owner | `src/seon/agent/message.cljs` and `src/seon/agent/message/internal.cljs` | Message rows, sender/recipient validation, hop derivation, human plan rows, and wake predicates already have one owner. |
| Completion caller | `src/seon/agent/lifecycle.cljs:123-198` | Current code writes result, calls `message!`, and closes the run separately, exposing committed partial states and duplicate retry. |
| Delegation caller | `src/seon/agent.cljs:790-852` | Current code births/hosts a child and then sends its task, returning a partial child id when the send fails. |
| Generated identity allocator | `src/seon/db/id.cljc:1136-1171,1323-1373,1435-1465` | It builds one transaction from all requested candidates, retries only exact generated-identity conflicts, and returns generated ids from the committed report. |
| Public database facade | `src/seon/db.cljs:56-76,522-589` | `transact!` currently creates a random request id unless an internal request key is present; its closed public request does not expose that key. |
| Durable transaction receipt | `src/seon/db/protocol.cljc:1208-1223,1369-1412` and `src/seon/db/writer.clj:1063-1278` | Receipt identity is request id plus a hash of transaction data, database value, expected database value, metadata, version, and generated candidates. |
| Receipt proof | `test/seon/db/request_receipt_test.clj:75-146,401-432` | Identical redelivery recovers one commit; concurrent identical requests serialize to one commit; reuse with different data is a request conflict. |
| Maintained Datahike | `reference-code/datahike` at `a464cd887458d2572414a6ea951c477b0981fdae` | Lookup refs resolve against intermediate transaction facts; CAS aborts the whole transaction on mismatch; identity attributes upsert rather than enforce insert-only message intent. |
| Runtime wake owner | `src/seon/agent/loop.cljs` and the committed database interest | A message transaction records delivery intent. Wake and child hosting remain derived from committed facts, not synchronous message acknowledgement. |

## What the existing receipt guarantees

The writer stamps the transaction entity with:

- `:seon.db.protocol/request-id`;
- `:seon.db.protocol/request-hash`; and
- `:seon.db.protocol/version`.

Before a transaction is prepared, `recover-current` queries that request id.
When found, it compares the stored hash with
`protocol/logical-transaction-hash`. An equal hash reconstructs the native
report and generated entity ids from history without another commit. A
different hash returns the existing request-conflict error. The writer performs
the same recovery after a transaction Future fails because the commit may have
won before acknowledgement.

The hash includes all of the following:

```clojure
{:seon.db.protocol/version             current-version
 :seon.db.protocol/transaction-data    transaction-data
 :seon.db/db                           database
 :seon.db/expected-db                  expected-database
 :seon.db.protocol/transaction-meta    transaction-meta
 :seon.db.protocol/generated-candidates candidates}

```

This is deliberately stricter than “same business intention.” The receipt says
whether one exact database request committed; it does not compare message
content or infer a domain operation.

`db.id/allocate!` currently regenerates candidates only after an exact
generated-identity conflict. It must not regenerate after ambiguous transport
delivery: that would create a second logical transaction. The database facade
and transport must retain and redeliver the exact frozen request id and bytes
until the receipt resolves it or a terminal non-ambiguous error occurs. This
strengthens the existing `transact!` path; it is not a message retry loop.

The current Bun UDS timeout retains the pending request id until a late response
or session close, but it rejects the caller Promise and sends cancellation. It
does not give a later invocation the frozen request. Therefore an ambiguous
timeout may mean “committed but caller did not observe the report.” The atomic
domain transitions below make the database outcome safe even in that case.
The transport/facade proof must still show exact-request recovery so callers
observe the recovered report whenever the session can reconnect.

## Why a public message id or request id is not the answer

### A new invocation cannot reproduce the transaction receipt

`message!` currently creates `at` from the clock, derives hops from an acquired
database value, and allocates a random generated identity. A later invocation
also acquires a later database value. Reusing only the request id would produce
a different transaction hash and correctly fail as request-id reuse with
different data.

Exposing `:seon.db/request-id` on `message!` would therefore offer a false
promise. The transport owner, not the agent, must retain the exact frozen
transaction request during ambiguous delivery.

### Reusing a run or agent string as the message id is invalid

Run, agent, and message identity attributes all participate in the generated-id
policy. `seon.db.id/assert-generated-identity-report!` rejects one generated
value occurring under more than one managed identity attribute. A run id cannot
also be a message id, and a child id cannot also be its task message id.

Even without that guard, `:seon.agent.message/id` is a Datahike
`:db.unique/identity` attribute. Identity values drive upsert. Reusing a message
id with different content, time, hops, or recipients would update one entity;
it would not provide insert-only collision semantics. A preflight pull followed
by an upsert is also racy.

Do not change the message identity to `:db.unique/value` merely to implement
retry. The generated allocator deliberately operates on identity attributes,
same-transaction plan rows refer to the message by lookup ref, and the domain
already has stronger transition identities for completion and delegation.

### Content is not identity

Two equal messages may be intentional. Purpose plus task text may intentionally
spawn two experimental children. Hashing content, sender, and recipients would
silently coalesce real work and still need collision handling. No content hash
or “same message recently” query belongs in the write path.

## Completion: one run transition and one transaction

Acquire one database value containing the current run, agent and recipient,
latest test-run facts, and whether this agent already messaged that recipient
since the run began. Derive refusal and message omission purely from that
value.

If a result message is needed, call `db.id/allocate!` once for its ordinary
message id. Its pure transaction builder returns this ordered transaction data:

1. the existing run-pointer CAS asserting the agent still owns this run;
2. the optional run result and result-ref facts;
3. one fully formed message row from the message owner's private pure builder;
4. the run close row with reason `:completed`; and
5. retraction of `:seon.agent/run`.

If the result is blank or the agent already messaged the recipient this run,
submit the same run transition through `db/transact!` without allocating a
message id.

Completion also depends on absence predicates: no disqualifying latest test
result and no earlier answer message. Use `:seon.db/expected-db database` in
addition to the targeted run-pointer CAS. If any transaction lands between
acquisition and commit, reacquire and rebuild. This rare whole-value retry is
preferable to sending a second answer because a concurrent message was not
visible in the original database value. The targeted CAS still names the
actual run authority and prevents a superseded run from closing a newer run.

The transaction has only two durable outcomes:

- none of result, message, or close commits; or
- result, optional message, close, and pointer retraction all commit together.

If acknowledgement is lost after commit, the agent is already idle and the one
message is already durable. Pod restart observes the closed run and cannot
repeat the transition. There is no partial result that requires a delivery
queue and no open completed run that encourages a second `complete` send.

The completion result remains `:idle` or a direct error. The allocated message
id does not need to enter the public completion result; it is queryable from
the committed transaction when needed.

## Delegation: birth and initial task in one transaction

The current `delegate!` has a partial state by construction: `start!` commits
and hosts a child before the task message transaction. Replace that composition
inside the existing `delegate!`; do not add another delegate function.

Acquire the caller, parent-depth policy, home/context facts, sender facts, and
hop barrier at one database value. The child is new, so its first parent-to-child
message has no earlier child-to-parent hop and uses the first-contact hop value.
Call `db.id/allocate!` once with allocations for:

- the child agent id;
- the initial task message id; and
- any other identities already required by ordinary child birth.

The one pure transaction builder emits the existing child/home/context rows and
the fully formed task message row. The message recipient is
`[:seon.agent/id child-id]`; Datahike resolves that lookup ref against the child
identity asserted earlier in the same transaction. This behavior is proven in
`reference-code/datahike/test/datahike/test/lookup_refs_test.cljc:53-56`.

Only after the native allocation report commits does `delegate!` host the
child. If hosting fails or the process dies, the child and task remain ordinary
database facts. The runtime's committed-feed reconciliation must host the
eligible child and process the addressed message. There is no second task send.

This removes the current “child exists but message failed” result shape. The
retained public success is still `{:seon.agent/id child-id}`. A database failure
commits neither child nor task. A hosting failure returns a direct process error
carrying the committed child id so the supervisor can reconcile it; it does not
roll back the transaction.

### Limit of a later independent `delegate!` call

A repeated `delegate!` call with no known child id is semantically a request for
another child. The database cannot distinguish an intentional second
experiment from a retry by comparing purpose and task text. The transaction
receipt cannot help after the exact request id and bytes are gone.

Therefore the system must not automatically repeat a terminally ambiguous
`delegate!` invocation from scratch. It first resolves the original frozen
transaction request through the receipt. If the entire caller process and its
request are lost, recovery inspects the committed child/task facts; it does not
submit the same user intention again.

If a future external caller requires replay across loss of all process state,
the smallest honest extension is an optional existing child
`:seon.agent/id` on the same `delegate!` request, followed by exact convergence
of that child and its birth-transaction task. That is a tradeoff, not required
for this cut: callers cannot invent current generated agent ids outside
`db.id/allocate!`, and adding a general operation id would create the extra
identity this design is avoiding.

## Message owner composition

Keep one public `message!`. Internally, separate the already-existing work into
ordinary pure inputs and transaction data:

- normalize recipients;
- acquire and validate sender/recipients/hop facts at one database value;
- derive origin and hops;
- construct one fully formed message row and any human plan rows; and
- project the committed allocation report to `{message-id, hops}`.

Standalone `message!` invokes those parts and commits once exactly as it does
after the native-result cut. Completion and delegation call the same private
pure row constructor from their composite allocation builder. They do not call
public `message!`, because doing so would necessarily create a second
transaction. They also do not construct message maps independently in
`lifecycle.cljs` or `agent.cljs`.

The private constructor is implementation reuse inside the existing namespace,
not a public “prepare message” API. It accepts fully resolved ordinary data and
has no database, Promise, connection, socket, or request-id behavior.

## Exact paths to delete

### Completion partial workflow

Delete from `src/seon/agent/lifecycle.cljs:176-196`:

- the standalone result-row `db/transact!`;
- the separate `msg/message!` call;
- the separate `run/close-run!` call for `:completed`; and
- all three result branches that attempt to interpret partial success.

Replace them with one completion acquisition and one composite transaction.
Keep the test gate, answered-this-run derivation, run result attributes,
message owner, and run close transaction-data owner.

### Delegation partial workflow

Delete from `src/seon/agent.cljs:790-852`:

- `delegate!`'s `start!` followed by `msg/agent` composition;
- the “spawned but task message failed” logging/result branch; and
- the teaching that callers retry a task after a partially successful birth.

Retain `start!` for intentionally creating an idle child. `delegate!` is the
one atomic birth-with-task operation, not an alias for two public calls.

### Proposed duplicate mechanisms to reject

Do not add or retain:

- optional public message request ids or caller-selected message ids;
- a completion-message id attribute on runs;
- a delivered, sent, handled, pending, retry, or dedupe fact;
- a delivery queue, outbox, background resend loop, or listener retry path;
- content hashing as message identity;
- a second transaction-receipt table or completed-request map;
- a versioned `message!`, `complete`, or `delegate!`; or
- direct message-row construction in lifecycle and agent namespaces.

## Collision and retry semantics

| Situation | Required result |
|---|---|
| Generated message or child id collides before commit | `db.id/allocate!` retries only the exact conflicting candidate and rebuilds the whole uncommitted transaction. |
| Same frozen transaction request is delivered twice | The writer receipt returns one committed report; listeners observe one commit. |
| Same request id is reused with different bytes or database value | Direct request-conflict error; never reinterpret it as a retry. |
| Completion database changes before commit | Expected-db fails; reacquire, re-run refusal/answered checks, and rebuild. |
| Completion run is superseded | Run-pointer CAS fails; no result, message, close, or pointer retract commits. |
| Completion acknowledgement is lost after commit | Closed run plus committed result/message are authoritative; do not send again. |
| Delegation database transaction fails | Neither child nor task exists. |
| Delegation commits but hosting fails | Child and task remain durable; runtime reconciliation hosts it. Do not resend. |
| Whole delegate response is lost with frozen request retained | Redeliver the exact request and recover its receipt. |
| Whole delegate response and frozen request are both lost | Inspect committed child/task facts; do not infer sameness from purpose/content and do not auto-submit another delegation. |
| Two intentional equal standalone messages | Both commit with distinct generated message ids. |

## Proof plan

### Writer and receipt proof

1. Send the exact same generated child-plus-message transaction concurrently
   with one request id. Assert one transaction, one child, one message, one
   listener event, and one recovered response.
2. Lose the first response after commit, reconnect, and redeliver the exact
   frozen request. Assert the generated child/message ids and transaction id
   are reconstructed from the receipt.
3. Reuse that request id with a changed database value, timestamp, recipient,
   content, or candidate. Assert request-conflict and no second commit.
4. Force a generated message-id collision. Assert the allocator changes only
   the exact candidate before any commit and the final transaction remains one
   child plus one message.

### Completion proof

5. Induce failure at every transaction-data item. Assert zero result, message,
   close, and pointer-retract facts commit.
6. Commit completion and drop the reply. Assert exactly one result message,
   one closed run, no live run pointer, and derived idle state after pod
   restart.
7. Commit an ordinary answer message after completion acquisition but before
   commit. Assert expected-db rejects the stale completion; reacquisition sees
   the answer and closes without a second message.
8. Supersede the run after acquisition. Assert the run-pointer CAS aborts the
   entire completion transaction and leaves the newer run untouched.
9. Complete with blank result or an earlier answer this run. Assert the result
   facts and close commit together without allocating a message.

### Delegation and runtime proof

10. Commit one delegation. Assert child identity, parent/home/context refs, and
    initial task message share one transaction and no second transaction is
    emitted by `delegate!`.
11. Deliver the committed event before the child process is ready. Assert
    committed-feed reconciliation hosts the child and consumes the one task;
    no resend is required.
12. Fail process hosting after commit, then restart the supervisor. Assert the
    same child is hosted and the same task wakes it once.
13. Fail the database transaction. Assert neither child nor task is queryable.
14. Call `delegate!` twice intentionally with equal purpose/content. Assert two
    distinct children and two tasks; content is not deduplicated.

### Source and result proof

15. Production scan finds one public `message!`, one `complete`, one
    `delegate!`, and zero queue/outbox/dedupe/sent/pending message mechanisms.
16. `message!` success remains exactly message id plus hops; completion remains
    `:idle`; delegation remains the child-id map; failures are direct errors.
17. `lifecycle.cljs` has no standalone result transaction, public message call,
    or completed run-close sequence. `delegate!` has no `start!` then
    `msg/agent` sequence.

## Ordered implementation boundary

1. Finish and freeze the current native message result/database-value cut.
2. Factor the message owner's pure row/plan construction without changing the
   public request or result.
3. Strengthen the existing transaction submission path so ambiguous transport
   recovery redelivers the exact frozen request id and bytes; do not regenerate
   allocation candidates.
4. Replace completion's three mutations with its one fenced composite
   transaction and port its focused retry/race tests.
5. Replace delegation's birth-then-message workflow with one allocation
   transaction, then prove committed-feed hosting when the event precedes
   process readiness.
6. Delete partial-success branches and obsolete tests, run focused CLJS and
   writer receipt gates, then certify both flows in the live default cluster.

## Graduation gate

This seam graduates only when completion has no observable partial
result/message/close state; delegation has no observable child-without-task
state; exact frozen database requests recover one writer receipt; generated
collisions retry before commit; a lost acknowledgement cannot cause a second
message; runtime restart consumes the one durable delegated task; equal
intentional standalone messages remain distinct; and the source contains one
unversioned message owner with no queue, registry, compatibility path, or
stored delivery status.
