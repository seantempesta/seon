---
title: Message native result and database value cut
type: research
status: complete
tags: [research, prd, database, agent, cljs]
---

# Message native result and database value cut

## Decision

Keep `seon.agent.message/message!` as the one message writer and replace its
old database envelope and old coordinate acquisition in place. Do not add a
remote message namespace, compatibility result, second delivery channel, or
versioned API.

`message!` retains one namespaced request map and the useful message-domain
success data: the committed `:seon.agent.message/id` and derived
`:seon.agent.message/hops`. Delete the redundant
`:seon.agent.message/ok? true`; the presence of the committed identity already
means success. Every failure is a direct map with `:seon.error/message`.

The allocator remains an internal transaction mechanism. Its success is the
native transaction report enriched with `:seon.db.id/ids`; `message!` projects
that report into the concise message result. Its direct error passes through
unchanged. The raw report is not an agent-facing message result, and no
`:seon.db/ok?` or nested `:seon.db/error` is reconstructed around it.

Each send acquires one immutable database value, resolves sender and recipients
and derives hop depth at that value, then gives that same value to
`seon.db.id/allocate!`. Human-message plan rows remain in the same transaction
as the message. A successful result means the message and any plan rows
committed; it does not claim the receiving child already opened or renewed a
run. Wake remains the committed-datom interest owned by `seon.agent.loop`.

## Dependency ledger

| Dependency or owner | Selected source | Constraint used |
|---|---|---|
| Seon checkout | `7301ec5b06fd53645bc9c6af8f13adda0a460c80` plus the shared working tree | The async `seon.db` facade and native allocator are built; message acquisition still expects the removed coordinate/envelope contract. |
| Maintained Datahike | `reference-code/datahike` at `a464cd887458d2572414a6ea951c477b0981fdae` | Lookup refs resolve at the transaction's intermediate database value; ref-many map values become independent datoms; transaction failure is atomic. |
| Public database facade | `src/seon/db.cljs:440-749` | `db/db`, `pull-many`, `query`, and `execute-many` are async and accept ordinary `:seon.db/db` values; transaction failures are direct errors. |
| Native allocator | `src/seon/db/id.cljc:1323-1373,1435-1465` | `allocate!` accepts `:seon.db/db`, returns an enriched native report, retries only exact generated-id conflicts, and returns direct errors otherwise. |
| Message entity owner | `src/seon/agent/message.cljs` | Owns the one write entry point, entity schemas, origin/hop policy, agent-facing wrappers, and pure wake predicates. |
| Message acquisition helper | `src/seon/agent/message/internal.cljs` | Current sender/recipient/hop acquisition already intends one database value but still transports it as `::db/coordinate` and wraps results. |
| Wake consumer | `src/seon/agent/loop.cljs:624-708,830-855` | A committed `:seon.agent.message/to` datom selects candidates; the handler reads the transaction's exact `:db-after` and applies recipient, sender, origin, and hop rules. |
| Intended model | `docs/seon/architecture/agent-runtime.md:318-341,672-681` and `docs/seon/architecture/data-model.md:562-578` | Messages are committed database facts; human intake atomically creates one plan row per addressed agent; wake is selective and database-reactive. |

Datahike's lookup-ref behavior is directly proven by
`reference-code/datahike/test/datahike/test/lookup_refs_test.cljc:28-91`:
ref values accept lookup refs, lookup refs resolve against intermediate facts
earlier in the same transaction, and an unresolved entity-position lookup ref
throws. Lines 93-123 prove cardinality-many refs accept one or many lookup refs.
The transaction reducer in
`reference-code/datahike/src/datahike/db/transaction.cljc:644-700` expands a
many-valued map attribute into ordinary add operations. This is exactly the
existing message-row plus plan-row transaction: the message identity is added,
then each plan row can refer to it by lookup ref in that same atomic commit.

No writer-side message operation is needed. Datahike and the existing
allocator already supply identity collision handling, lookup-ref resolution,
cardinality-many storage, all-or-nothing transaction behavior, receipts, and
the native report.

## Retained public surface

### `message!`

Retain the one map request and its existing namespaced keys:

```clojure
(message!
 {:seon.agent.message/content content
  :seon.agent.message/from sender-ref       ; optional in agent scope
  :seon.agent.message/to recipient-or-refs  ; optional, defaults to user
  :seon.agent.message/origin :core          ; optional, core callers only
  :seon.db/db database})                    ; optional exact value
```

Adding optional `:seon.db/db` does not create another arity. Omission acquires
the process's cached current database value exactly once. An explicit value is
needed by callers such as post-close outcome routing that already hold the
native transaction report's `:db-after`.

The retained success domain is:

```clojure
{:seon.agent.message/id message-id
 :seon.agent.message/hops hops}
```

Failure remains a direct `{:seon.error/message ...}` value, optionally with
`:seon.error/kind` and `:seon.error/data`. Delete
`:seon.agent.message/ok?`; it is only a second success discriminator over a map
that already has a required committed identity.

### Thin wrappers and reads

Keep `user [content]` and `agent [to-id content]` with their positional Malli
arguments. Both return `message!`'s result unchanged. Move self-recipient
refusal into `message!`; `agent` must not remain a second validator.

Keep `waking-inbound?` and `hop-live?` as the one public pure wake rules. Keep
`recent` and `recent-all`: they are different bounded reads and are already
ordinary-database-value-shaped. Their direct read-error convention is the
correct convention for the write acquisition too.

Keep `seon.agent/message!` as a public re-export only if the application face
still requires it. It is not another implementation. Callers that need
instrumented validation should call the colocated
`seon.agent.message/message!`; do not add a second wrapper body.

## One immutable database value

### Normalize and validate before I/O

Purely normalize `:seon.agent.message/to` once:

- absent means `[user-ref]`;
- one lookup ref or eid becomes a one-element vector;
- a vector of refs remains a vector; and
- duplicate recipients are removed before reads, storage, and plan allocation.

Reject blank content, absent sender scope, and an empty recipient vector as
direct user-input errors before acquiring a database value. This prevents a
durable message addressed to nobody.

### Acquire sender, recipients, and hop history

Resolve `database` from the optional request key or one `(await (db/db))`.
Pass it into one private acquisition function beside the normalized sender and
recipients. That function performs:

1. one `execute-many` containing an ordered `pull-many` of the distinct sender
   and recipient refs plus the latest human-message barrier query; and
2. only for a non-human sender with agent peers, the dependent maximum-hop
   query against the same `:seon.db/db` value.

Every execute-many member must contain `:seon.db/db database`. The dependent
query must contain the same key and value. Do not return a coordinate: return
ordinary acquired data beside the exact `:seon.db/db` value already supplied.

Ordered `pull-many` preserves a `nil` at an unresolved input. Refuse an
unresolved sender or any unresolved recipient before allocation, with the
missing refs in `:seon.error/data`. This is required even for numeric eids:
the message contract says every sender and recipient is a real user or agent,
not merely a value accepted by a ref slot.

After resolution, refuse any recipient whose `:db/id` equals the sender's
`:db/id`. This enforces the namespace's existing “no self-to-self messages”
claim at the single write boundary. The current `agent`-wrapper-only check is
not sufficient because direct `message!` calls bypass it.

### Derive origin and hops

Derive ordinary origin from the resolved sender:

- sender has `:seon.user/id` -> `:human`;
- sender has `:seon.agent/id` -> `:agent`; and
- an explicit `:core` remains the one substrate override.

Do not accept explicit `:human` or `:agent` overrides. They duplicate a fact
already implied by the sender and allow an agent-origin message to mint human
intake work. The current run outcome caller's explicit `:agent` is redundant
and should be deleted; canvas recovery retains explicit `:core`.

Human sends have hops `0`. An agent send has one plus the maximum prior inbound
hop from the addressed peers after the latest human-message barrier. A fan-out
message retains one conservative shared hop value because the stored message
has one `:seon.agent.message/hops` attribute; changing that would require one
message per recipient or another stored model. The ordinary `message/agent`
path addresses one peer.

Two concurrent sends derived from the same database value may legitimately
carry the same hop depth: they are siblings, not a sequential bounce. A later
send observes the advanced database value and increments. Therefore the write
does not use `:seon.db/expected-db` and does not introduce a pair counter or
lock entity. Unrelated commits and independent sends remain parallel.

### Allocate and commit once

Pass the same `database` into `db.id/allocate!` as `:seon.db/db`. Let the
allocator acquire its generator policies at that value; do not duplicate its
policy reader in message code.

The pure transaction builder creates:

- one fully formed message row;
- for `:human` origin only, one `my.plan` row per distinct agent recipient;
  and
- each plan row's `:my.plan/message` lookup ref to the message identity.

All rows stay in one allocation transaction. A missing ref, generated-id
failure, schema failure, or write failure commits none of them. On allocator
success, select the message id from `:seon.db.id/ids` and return it with the
already-derived hops. On allocator direct error, return that value unchanged.

## Recipient and wake semantics

`message!` stores delivery intent; `seon.agent.loop` owns wake execution. The
division remains:

1. Datahike commits one `:seon.agent.message/to` datom per distinct recipient.
2. The authority's existing selective interest delivers committed matching
   datoms and the transaction's exact `:db-after`.
3. The receiving child confirms `to` contains itself, `from` is not itself,
   origin is not `:core`, and the hop count is below the cap.
4. An idle agent opens a run, a running agent renews its run, and paused or
   terminated agents remain unchanged.
5. A hop-exhausted message remains durable and visible as a dead letter but
   does not wake.

The message transaction must not synchronously open or renew a run. That would
couple the sender to recipient process availability, duplicate the wake owner,
and turn fan-out into a multi-recipient lifecycle transaction. Likewise,
`message!` must not wait for the asynchronous wake handler before returning.
Its result means “stored with this id and hop depth,” not “recipient ran.”

The `:core` origin remains durable but quiet. Canvas recovery can leave an
explanation without restarting an idle agent. Human origin atomically creates
the existing address plan rows; agent and core origins do not.

## Exact obsolete and duplicate inventory

### Old database result and coordinate shapes

`src/seon/agent/message/internal.cljs` has the obsolete envelope constructor at
lines 86-90, expects `::db/coordinate` at line 109, returns
`:seon.db/ok?` maps at lines 123-125 and 144-146, and passes the old coordinate
to the dependent query at line 129.

`src/seon/agent/message.cljs` unwraps that envelope at lines 341-342 and
branches on the allocator's removed `:seon.db/ok?` at lines 415-419. Replace
both with direct-error classification. There is no `db/*conn*` in these two
production files today; do not introduce one while replacing the old
coordinate.

`test/seon/agent/message_test.cljs:14-29,77-115` fabricates both a coordinate
and a database value, and asserts the coordinate is threaded. Lines 220-261
fabricate allocator `:seon.db/ok?` results and only assert the absence of
`:seon.db/conn`. Replace those fixtures with one database value and enriched
native reports/direct errors.

### Duplicate validation and result conventions

- `message.cljs:464-471` checks self-send only in `agent`; move the rule into
  `message!` and make the wrapper unconditional.
- `message.cljs:229-234,347-350` accepts explicit human/agent origin even
  though resolved sender identity already determines it. Restrict the optional
  override to `:core`.
- `message.cljs:241-247` and `415-418` create a message-specific success
  boolean. Keep id and hops; delete the boolean.
- `message.internal/failure` duplicates the direct read-error constructor in
  the parent namespace only to create the old database envelope. Move the
  effectful send acquisition into `message.cljs` as a private function or
  return direct errors from it; keep `.internal` for its pure helpers.

`recent` and `recent-all` are not duplicate writers. `user` and `agent` are
useful agent-facing conveniences, not alternate storage paths, once they only
delegate.

### Direct callers that must change in the same cut

- `src/seon/agent/run.cljs:531-551` treats message errors as nested database
  errors. It should pass the close report's `:db-after`, test direct error, and
  log that value.
- `src/seon/agent/lifecycle.cljs:184-192` mixes native transaction and message
  success checks. It should classify each direct error independently.
- `src/my/plan/internal.cljs:1297-1310` checks
  `:seon.agent.message/ok?`; success is the message id.
- `src/seon/web/serve.cljs:1402-1430` checks the domain boolean but
  destructures failure from `:seon.db/error`, even though `message!` already
  returns direct errors. This can currently log a nil reason. It should branch
  on `:seon.error/message` and read id/hops directly on success.
- `src/seon/web/serve.cljs:1236-1239` ignores the send result and begins
  polling. A direct send error must end the request instead of timing out while
  waiting for a run that cannot open.
- `src/seon/render/sci.cljs:580-588` intentionally sends a best-effort core
  message. It should log a direct error but must not add a wake or retry path.

The public `seon.agent/message!` var is an alias, not a second write
implementation. Do not add behavior there to ease caller migration.

## Tests to retain, port, and delete

### Retain as pure CLJS proof

- `waking-inbound?` and `hop-live?` classification.
- `clip-title`, user-entity classification, and `outbound-hops` defaults.
- Every accepted public recipient input shape normalizes to one distinct
  vector.
- Blank content, missing scope, empty recipients, missing refs, and self-send
  are direct errors and allocate nothing.
- Origin is derived from the resolved sender; only explicit `:core` overrides
  it.

### Port as async owner proof

- Omitted database calls `db/db` exactly once; explicit database calls it zero
  times.
- Every execute-many member, dependent query, and allocator request contains
  the identical acquired `:seon.db/db` value.
- Ordered pull results validate every sender and recipient before allocation.
- User sends skip the hop query and allocate one plan id per distinct agent
  recipient; agent sends perform the bounded hop query and allocate no plan
  ids.
- Native allocator report becomes exactly `{message-id, hops}`; allocator
  direct error is unchanged.
- Closed runtime admission refuses before database acquisition or allocation.
- `user` and `agent` return the one writer's result without their own success
  or error convention.
- `recent` and `recent-all` retain their bounded, one-database-value reads.

### Port as real writer/interest proof

- The committed message row is fully formed and cardinality-many recipients
  produce one effective datom per distinct recipient.
- A human fan-out atomically commits the message and exactly one plan row per
  agent recipient, with each plan row resolving the same-transaction message
  lookup ref.
- An induced transaction failure leaves neither message nor plan rows.
- A committed human or agent message reaches only its addressed interest; core,
  self, unrelated-recipient, and hop-exhausted messages do not open a run.
- Message commit can succeed while a recipient is paused, terminated, or
  process-disconnected; the durable message remains queryable without another
  delivery queue. Re-drive after a later process start belongs to the runtime
  wake/recovery proof, not this writer result.
- Concurrent independent sends commit without a whole-database stale-value
  retry.

### Delete rather than port

Delete assertions and fixtures for:

- `::db/coordinate` or simultaneous coordinate-plus-database representations;
- `:seon.db/ok?`, nested `:seon.db/error`, and `:seon.db/conn`;
- a second self-send guard in `agent`;
- explicit `:human` or `:agent` origin override; and
- pod-local Datahike connections used only to seed render/chat message tests.

Render, chat, admission, lifecycle, planner, and run tests should seed messages
through the authority-backed `message!` or transact purpose-built fixture data
through the public facade. Do not retain an embedded database system just to
avoid awaiting the result.

## Shortest falsifiers

1. Source scan: `message.cljs`, `message/internal.cljs`, and focused tests have
   zero `::db/coordinate`, `:seon.db/ok?`, nested `:seon.db/error`,
   `:seon.db/conn`, or `db/*conn*` references.
2. Result shape: success is exactly message id plus hops; every failure has a
   direct `:seon.error/message`; no third result convention exists.
3. One database value: an instrumented test proves at most one `db/db` call and
   `identical?` values in all read members, the dependent query, and allocation.
4. Real refs only: one unresolved sender or recipient returns a direct error and
   calls neither allocator nor writer.
5. Single self-send rule: direct `message!`, `message/agent`, numeric eids, and
   lookup refs all reject sender equals recipient at the one boundary.
6. Origin integrity: user derives human, agent derives agent, core override is
   quiet, and an explicit human/agent override fails schema or validation.
7. Atomic human intake: one real commit produces the message plus one plan row
   per unique agent recipient; an induced failure produces none.
8. Wake separation: message success returns after commit without waiting for a
   run, while the exact committed interest wakes only addressed eligible
   recipients.
9. Caller reachability: run, lifecycle, planner, web, and canvas recovery have
   zero message-specific boolean or nested database-error checks.
10. Focused message CLJS tests and focused writer/interest integration tests
    pass before broader lifecycle or browser gates.

## Ordered implementation boundary

The coherent source-freeze cut is:

1. replace `message!` and its private acquisition with one database value and
   direct errors;
2. delete the redundant success boolean, wrapper-only self check, and
   human/agent origin override;
3. port `message_test.cljs` to native allocation results and add real
   writer/interest atomicity proof;
4. update run, lifecycle, planner, web, and canvas-recovery callers in the same
   result cut; and
5. then implement the already-settled run cut against this direct message
   result.

If a caller is not ready, keep the source checkpoint unbuilt until that caller
is migrated. Do not introduce a result adapter or preserve
`:seon.agent.message/ok?` temporarily. Git is the compatibility archive.
