---
type: issue
status: closed
severity: blocker
tags: [issue, agent, database]
---

# message! silently defaulted a mis-shaped recipient to the user

Originally filed as "message! can resolve a recipient at a stale basis to
a dangling entity". The stale-basis premise was falsified; the file name
is kept so existing references resolve.

## Original evidence (2026-07-20 night, live default cluster)

Messages `p7413gax9q6j` (02:40Z) and `uiga4705cvb6` (02:54Z), both
intended for agent `real-hats-wave`, committed with
`:seon.agent.message/to [{:db/id 3558}]` while the live agent entity was
7559. The wake path saw no live recipient and the agent never ran —
a silent no-op blocking battery L1. The working hypothesis was a stale
id→eid resolution binding a retracted, name-recycled prior agent.

## Root-cause findings (2026-07-21, live default cluster)

The stale-basis hypothesis is false on three decisive facts:

1. History query `[3558 :seon.agent/id ?v ?tx ?added]` returns EMPTY —
   eid 3558 never carried `:seon.agent/id` at any basis. It has been the
   one user entity (`:seon.user/id "user"`, asserted tx 536870915, never
   retracted) its whole life. Pulling both failing messages shows
   `to = [{:db/id 3558, :seon.user/id "user"}]` — the DEFAULT user
   recipient, not a dangling agent.
2. `"real-hats-wave"` has exactly ONE `:seon.agent/id` assertion in all
   history: eid 7559, tx 536871607. No recycled name ever existed.
3. `message!` transacts recipients as verbatim identity lookup refs
   (`[[:seon.agent/id id]]` in `:seon.db/tx-data`); the sole writer
   resolves them at commit basis. There is no client-side id→eid cache
   anywhere in `src/seon/agent/message.cljs` /
   `src/seon/agent/message/internal.cljs` — acquisition
   (`internal/acquire-send-data`) resolves participants at one acquired
   database value only to validate them and compute hops, and those eids
   never enter the transaction.

The one mechanism that produces exactly the observed rows: `message!`'s
request map was OPEN and `normalize-recipients` defaults a nil/absent
`:seon.agent.message/to` to the user. A mis-keyed request (e.g. a bare
`:to`, a typo'd key) therefore validated cleanly, dropped the intended
recipient, and silently rerouted the message to the user. Every other
wrong shape was probed live and errors loudly (bare id string → protocol
error; unknown agent id → "Message sender or recipient does not resolve
to a user or agent.").

## Fix (2026-07-21, src/seon/agent/message.cljs)

- `::message-request` is now `{:closed true}` — an unknown key is a loud
  instrumented invalid-input error, never a silent default.
- New `::participant-ref` schema (positive eid or identity lookup-ref
  tuple) for `from`/`to` — a bare string (a Datahike string TEMPID, never
  a valid participant) is refused at the boundary instead of surfacing a
  raw writer protocol error.
- Recipient resolution mechanism unchanged and documented: verbatim
  lookup refs in the transaction, resolved by the writer at commit basis,
  so a retract-and-remint cycle delivers to the CURRENT entity and a
  genuinely dangling id fails as an error value.

## Acceptance evidence

- Live (default cluster, 2026-07-21 02:59Z): `message!` from root to
  `[:seon.agent/id "real-hats-wave"]` committed message `x4odxxprl5s0`
  with `to = [{:db/id 7559}]` (the live entity); the agent WOKE — run
  opened, three turns completed, and it replied "done 42" (messages
  `onke1lqp2puv`, `qifokquqmxiu`) — battery L1 behavior restored.
- Regression tests in `test/seon/agent/message_test.cljs`:
  `message-request-refuses-mis-keyed-and-mis-shaped-recipients` and
  `recipient-reaches-the-writer-as-a-verbatim-lookup-ref` (the
  retract/remint guard: the acquisition-basis eid never enters the
  transaction; the stored refs are the verbatim lookup refs the writer
  resolves at commit).
- `bin/test-cljs --test=seon.agent.message-test`: 15 tests,
  78 assertions, 0 failures, 0 errors.
